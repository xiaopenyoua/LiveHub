// Rhino 引擎：把抖音的混淆 JS 跑起来，暴露成一个「输入 md5 → 输出 signature」的函数。
//
// 出处：这段补丁逻辑（let→var、块内函数声明前移、关 JIT、ES5.1 语义）**搬自 DTV Mobile**
//   shared/src/androidMain/kotlin/dtv/mobile/platform/douyin/DouyinWebcastSignatureAndroid.kt
//   Copyright 2025 c.zeong，MIT License。
// 它踩过并留了注释的两个坑，我们原样保留：
//   ① Rhino 1.7.x 对 `let` 支持不稳，sign.js 只有 wrapper 用到，整份替换成 `var`；
//   ② Rhino 不实现浏览器 Annex-B 的「块内函数声明提升」，`w_0x42f5` 在声明点之前
//      被调用会 ReferenceError，必须把它挪到块首。
// 这两条都是**针对具体这份 sign.js** 的补丁，换了 JS 文件就要重新验证（README 写了怎么验）。
package spike

import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import java.io.File
import java.security.MessageDigest

object Js {

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 块内函数声明前移：把 `function X` 所在的那一段整体搬到包含块的 `{` 之后。 */
    fun moveBlockFunctionEarlier(src: String, functionName: String, blockMarker: String): String {
        val blockStart = src.indexOf(blockMarker)
        if (blockStart < 0) return src
        val declStart = src.indexOf("function $functionName", startIndex = blockStart)
        if (declStart < 0) return src
        val braceOpen = src.indexOf('{', startIndex = declStart)
        if (braceOpen < 0) return src

        var depth = 0
        var i = braceOpen
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val declEndExclusive = i + 1
                        val decl = src.substring(declStart, declEndExclusive).trimEnd()
                        val without = src.substring(0, declStart) + src.substring(declEndExclusive)
                        val insertPos = without.indexOf('{', startIndex = blockStart)
                        if (insertPos < 0) return src
                        val at = insertPos + 1
                        return without.substring(0, at) + "\n" + decl + "\n" + without.substring(at)
                    }
                }
            }
            i++
        }
        return src
    }

    /** sign.js 需要的两处预处理；任何一处没生效都要在流水里说出来，不许静默继续。 */
    fun prepare(src: String): Pair<String, List<String>> {
        val notes = mutableListOf<String>()
        val noLet = src.replace(Regex("\\blet\\b"), "var")
        notes += "let→var：替换 ${Regex("\\blet\\b").findAll(src).count()} 处"
        val hoisted = moveBlockFunctionEarlier(noLet, "w_0x42f5", "if (!window.byted_acrawler) {")
        if (hoisted == noLet) {
            notes += "块内函数前移：**未生效**（找不到 w_0x42f5 或块标记）——这份 JS 可能换版本了"
        } else {
            notes += "块内函数前移：w_0x42f5 已挪到 `if (!window.byted_acrawler)` 块首"
        }
        return hoisted to notes
    }
}

/**
 * 一个可复用的 Rhino 作用域。
 *
 * 为什么要缓存 [scope]：sign.js 是 485KB 混淆代码，求值一次要一两秒，而每次重连都要重新签名。
 * 所以照 DTV 的做法——**作用域建一次、签名调多次**，并用锁串行化（Scriptable 不是线程安全的）。
 */
class JsEngine(private val file: File, private val profile: Profile) {

    enum class Profile {
        /** sign.js：`get_sign(md5)` → X-Bogus */
        SIGN,
        /** webmssdk.js：`getMSSDKSignature(stub, ua)` → signature */
        WEBMSSDK,
    }

    private val lock = Any()
    private var scope: Scriptable? = null

    var initMs: Long = -1
        private set
    var lastCallMs: Long = -1
        private set
    var prepareNotes: List<String> = emptyList()
        private set

    private fun bootstrap(cx: RhinoContext, s: Scriptable) {
        // sign.js 自己会覆盖 window/document/navigator，这里只是保证它求值时这些东西存在。
        // webmssdk 需要更多浏览器 API，给足（但都是空壳，够它跑起来就行）。
        val js = """
            this.window = this;
            this.self = this;
            this.globalThis = this;
            this.document = { cookie: '', createElement: function(){ return {}; },
                             getElementsByTagName: function(){ return []; },
                             addEventListener: function(){}, documentElement: {} };
            this.navigator = { userAgent: '${Douyin.UA}', platform: 'Win32', languages: ['zh-CN'] };
            this.location = { href: 'https://live.douyin.com/', protocol: 'https:', host: 'live.douyin.com' };
            this.localStorage = { getItem: function(){ return null; }, setItem: function(){}, removeItem: function(){} };
            this.sessionStorage = { getItem: function(){ return null; }, setItem: function(){}, removeItem: function(){} };
            this.screen = { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040 };
            this.setTimeout = function(f){ return 0; };
            this.console = { log: function(){}, warn: function(){}, error: function(){} };
        """.trimIndent()
        cx.evaluateString(s, js, "[bootstrap]", 1, null)
    }

    private fun ensureScope(cx: RhinoContext): Scriptable {
        scope?.let { return it }
        val t0 = System.nanoTime()
        val s = cx.initStandardObjects()
        bootstrap(cx, s)
        val raw = file.readText(Charsets.UTF_8)
        val (src, notes) = Js.prepare(raw)
        prepareNotes = notes
        cx.evaluateString(s, src, file.name, 1, null)
        initMs = (System.nanoTime() - t0) / 1_000_000
        scope = s
        return s
    }

    /** 求值一条表达式，返回字符串结果。**复用的是同一个 scope**（第二次调用不重新解析 JS）。 */
    fun eval(expr: String): String = synchronized(lock) {
        val cx = RhinoContext.enter()
        try {
            // 关 JIT：混淆代码结构太怪，-1（解释执行）比默认优化级稳定，DTV 同款设置。
            // 用 runCatching 包住：Rhino 1.8.x 已经移除了优化编译器，给 -1 之外的级别会抛。
            runCatching { cx.optimizationLevel = -1 }
            // 用 ES5.1 语义，**不要**开 ES6：Annex-B 的函数提升行为在 ES6 下会变。
            runCatching { cx.languageVersion = RhinoContext.VERSION_1_8 }
            val s = ensureScope(cx)
            val t = System.nanoTime()
            val out = cx.evaluateString(s, expr, "[eval]", 1, null)
            lastCallMs = (System.nanoTime() - t) / 1_000_000
            RhinoContext.toString(out).trim()
        } finally {
            RhinoContext.exit()
        }
    }

    fun signature(md5: String): String = when (profile) {
        Profile.SIGN -> eval("get_sign('$md5')")
        Profile.WEBMSSDK -> eval("getMSSDKSignature(\"$md5\", \"${Douyin.UA}\")")
    }
}
