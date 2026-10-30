// 签名引擎抽象。spike 要回答的第一个问题不是「能不能签出东西」，而是
// **「用哪个 JS 引擎签出来的东西抖音认」**——所以引擎是可插拔的，结果逐条打出来。
package spike

import java.io.File
import java.util.concurrent.TimeUnit

interface SigSource {
    val label: String
    /** 这个引擎是不是「生产可用」（决定结论怎么写，不影响断言） */
    val productionViable: Boolean
    fun sign(md5: String): String
    /** 首次初始化耗时（含解析 JS）；不适用则为 -1 */
    val initMs: Long
    /** 最近一次签名耗时 */
    val lastCallMs: Long
    val notes: List<String>
}

/** Rhino：纯 JVM、零 native、APK 不涨——**但签出来的抖音不认**（见 README）。 */
class RhinoSigner(private val js: File) : SigSource {
    override val label = "Rhino 1.7.14（纯 JVM）"
    override val productionViable = false
    private val engine = JsEngine(js, JsEngine.Profile.SIGN)
    override fun sign(md5: String): String = engine.signature(md5)
    override val initMs: Long get() = engine.initMs
    override val lastCallMs: Long get() = engine.lastCallMs
    override val notes: List<String> get() = engine.prepareNotes
}

/**
 * 真 V8（node）：桌面上的 **WebView 替身**。
 * 生产端真正的引擎是 Android WebView（也是 V8），这里用 node 只是因为桌面 JVM 没有 WebView。
 * 每次签名起一个 node 进程（约几十毫秒），生产上 WebView 是常驻的，这次开销不存在。
 */
class NodeSigner(private val script: File, private val js: File) : SigSource {
    override val label = "node / V8（WebView 替身）"
    override val productionViable = true
    override var initMs: Long = -1
    override var lastCallMs: Long = -1
    override val notes = listOf(
        "每次签名起一个 node 进程；生产上用常驻 WebView，无此开销",
        "V8 在 sloppy mode 下按 Annex B 提升块内函数声明，不需要 Rhino 那条前移补丁",
    )

    override fun sign(md5: String): String {
        val t0 = System.nanoTime()
        val p = ProcessBuilder("node", script.absolutePath, md5, js.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(30, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (initMs < 0) initMs = ms
        lastCallMs = ms
        require(out.isNotBlank()) { "node 输出为空（stderr 见上）" }
        return out
    }
}
