// 抖音弹幕 spike 入口：三步走，每步都打证据，不「编译通过就算过」。
//
//   ① 至少一种 JS 引擎能把 sign.js 跑出非空 signature（含首次 / 复用耗时）
//   ② 拿这个 signature 打真房间的 WS，**看到 HTTP 101**（我们之前一直卡在 200）
//   ③ 收到帧 → gunzip → protobuf → 至少 1 条中文昵称 + 中文内容的聊天弹幕
//
// 引擎是可插拔的，因为这次 spike 的**主要结论就在引擎上**：
// Rhino 能签、但抖音不认；真 V8（WebView）签的才认。所以两个都跑，结果并排打出来。
//
// 用法：./run.sh [web_rid] [room_id] [user_unique_id]
package spike

import java.io.File
import kotlin.system.exitProcess

private var failures = 0

private fun check(ok: Boolean, msg: String) {
    println((if (ok) "  PASS  " else "  FAIL  ") + msg)
    if (!ok) failures++
}

private fun hr(title: String) {
    println()
    println("=".repeat(88))
    println(title)
    println("=".repeat(88))
}

private const val DEFAULT_WEB_RID = "769867011849"
private const val DEFAULT_ROOM_ID = "7701999831820339968"

/**
 * 扫描模式：一次编译，挨个房间连一遍，只回答「哪间房有活人说话」。
 *
 * 为什么需要它：`user_count` 不能当判据——分类页里大量房间是门店/循环播放的
 * 无人值守直播，几百「人看过」也未必有一条弹幕。而每次普通模式跑一遍要重新编译，
 * 一间一间试太慢。这里复用同一个签名作用域 + 同一份 cookie，逐间接 15 秒。
 *
 * 用法：`./run.sh --scan <room_id,room_id,...> [web_rid] [uid]`
 */
private fun scan(args: Array<String>): Int {
    val rooms = args.getOrNull(1).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (rooms.isEmpty()) {
        println("用法：./run.sh --scan <room_id,room_id,...> [web_rid] [uid]")
        return 2
    }
    val here = File(System.getProperty("spike.dir") ?: ".").absoluteFile
    val signJs = File(here, "assets/sign.js")
    val nodeScript = File(here, "assets/sign_node.js")
    val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val node = NodeSigner(nodeScript, signJs)

    // uid/cookie 只取一次，所有房间共用：房间页被限流也没关系（uid 可以随机，见 Douyin.resolveCtx）
    val base = Douyin.resolveCtx(http, args.getOrNull(2) ?: DEFAULT_WEB_RID, rooms.first(), args.getOrNull(3))
    println("扫描 ${rooms.size} 间房，每间 15s，只找「有活人说话」的那间")
    println()
    var best: Pair<String, Douyin.Probe>? = null
    for (room in rooms) {
        val ctx = base.copy(roomId = room)
        val md5 = Js.md5Hex(Douyin.signStub(ctx))
        val sig = runCatching { node.sign(md5) }.getOrNull()
        if (sig.isNullOrBlank()) {
            println("  $room  签不出来，跳过")
            continue
        }
        var hit: Douyin.Probe? = null
        for (b in Douyin.WS_BASES) {
            val p = Douyin.probe(Douyin.buildWsUrl(b, ctx, sig, true), ctx, wantChats = 3, timeoutMs = 15_000)
            if (p.handshakeCode == 101) { hit = p; break }
        }
        if (hit == null) {
            println("  $room  握手没到 101")
            continue
        }
        val first = hit.chats.firstOrNull()
        println(
            "  $room  101 · ${hit.frames} 帧 / ${hit.msgFrames} msg / **${hit.chats.size} 条弹幕**" +
                (first?.let { "   第一条：${it.nick}：${it.content}" } ?: "")
        )
        if (hit.chats.isNotEmpty() && best == null) best = room to hit
    }
    println()
    val b = best
    if (b == null) {
        println("这几间房都没有人说话。换一批 room_id 重扫——**不要再加大超时**，")
        println("空房间等再久也只有心跳和 RoomDataSync，不是超时不够。")
        return 1
    }
    println("有活人说话：${b.first}（${b.second.chats.size} 条）")
    println("下一步：./run.sh <web_rid> ${b.first}")
    return 0
}

fun main(args: Array<String>): Unit = run {
    if (args.firstOrNull() == "--scan") exitProcess(scan(args))

    val webRid = args.getOrNull(0) ?: DEFAULT_WEB_RID
    val roomIdArg = args.getOrNull(1) ?: DEFAULT_ROOM_ID
    val uidArg = args.getOrNull(2)

    println("抖音弹幕 spike —— 目标房间 web_rid=$webRid room_id=$roomIdArg")
    println("JDK=${System.getProperty("java.version")}")
    println("UA=${Douyin.UA}")

    val here = File(System.getProperty("spike.dir") ?: ".").absoluteFile
    val signJs = File(here, "assets/sign.js")
    val nodeScript = File(here, "assets/sign_node.js")
    check(signJs.exists(), "assets/sign.js 存在（${signJs.length()}B）")
    check(nodeScript.exists(), "assets/sign_node.js 存在")
    if (failures > 0) exitProcess(1)

    // ---------------------------------------------------------------- 房间上下文
    hr("0. 房间上下文（页面内嵌 JSON，和 backend 的 detail() 同一个取法）")
    val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val ctx = Douyin.resolveCtx(http, webRid, roomIdArg, uidArg)
    val stub = Douyin.signStub(ctx)
    val md5 = Js.md5Hex(stub)
    println("  签名原文：$stub")
    println("  原文 md5：$md5")
    check(md5.length == 32, "md5 是 32 位十六进制（${md5.length}）")

    // ---------------------------------------------------------------- ① 各引擎签名
    hr("① 同一条 md5，交给不同 JS 引擎签名")

    val rhino = RhinoSigner(signJs)
    val node = NodeSigner(nodeScript, signJs)
    // 顺序有讲究：先跑 Rhino（结论是「不行」），再跑 node（结论是「行」），
    // 两份结果并排，读的人一眼看出差异不在参数、不在 md5，而在引擎。
    val engines = listOf(rhino, node)

    data class Sig(val engine: SigSource, val value: String?, val error: String?)

    val sigs = engines.map { e ->
        val r = runCatching { e.sign(md5) }
        val v = r.getOrNull()?.trim()
        println()
        println("  ── ${e.label}")
        e.notes.forEach { println("     · $it") }
        r.exceptionOrNull()?.let { println("     ✗ ${it::class.simpleName}: ${it.message}") }
        if (v != null) {
            println("     signature = $v  (长度 ${v.length})")
            println("     首次 ${e.initMs}ms，本次 ${e.lastCallMs}ms")
        }
        check(!v.isNullOrBlank(), "${e.label} 算出非空 signature")
        Sig(e, v, r.exceptionOrNull()?.message)
    }

    // ---------------------------------------------------------------- ② 握手
    hr("② 用各自的 signature 打真 WS，看谁拿到 101")

    var printedUrl = false

    fun attempt(engine: SigSource, sig: String): Douyin.Probe? {
        for (encode in listOf(true, false)) {
            for (base in Douyin.WS_BASES) {
                val url = Douyin.buildWsUrl(base, ctx, sig, encode)
                if (!printedUrl) {
                    println("  完整 URL（第一条）：")
                    println("    $url")
                    printedUrl = true
                }
                val host = base.substringAfter("//").substringBefore("/")
                print("  [${engine.label}] $host 签名${if (encode) "编码" else "原样"} → ")
                val p = Douyin.probe(url, ctx, wantChats = 3, timeoutMs = 25_000)
                if (p.handshakeCode == 101) {
                    println("✓ 101")
                    return p
                }
            }
        }
        return null
    }

    var winner: Pair<SigSource, Douyin.Probe>? = null
    for (s in sigs) {
        val v = s.value ?: continue
        val p = attempt(s.engine, v)
        if (p != null) {
            winner = s.engine to p
            break
        }
    }

    check(winner != null, "至少一种引擎的 signature 能换到 HTTP 101")

    // 结论表：把「谁行谁不行」并排写出来，这是本次 spike 最值钱的一行
    println()
    println("  ── 引擎对照 ──────────────────────────────────────────────")
    for (s in sigs) {
        val ok = s.value != null && (winner?.first === s.engine)
        println("     ${if (ok) "✅ 101" else "❌ 200"}  ${s.engine.label}  →  ${s.value ?: "（没签出东西）"}")
    }
    println("  ─────────────────────────────────────────────────────────")

    val w = winner
    if (w == null) {
        hr("结论：没有 101，spike 到不了第 ③ 步")
        println("  改了 $failures 条断言失败")
        exitProcess(1)
    }

    // ---------------------------------------------------------------- ③ 解包
    hr("③ 解包：PushFrame → gunzip → Response → WebcastChatMessage  （用 ${w.first.label} 的连接）")

    val p = w.second
    println("  帧统计：总 ${p.frames}，其中 msg ${p.msgFrames}，gunzip 失败 ${p.gzipFailures}，发出 ack ${p.acks}")
    println(
        "  方法分布：" + p.methods.entries.sortedByDescending { it.value }.take(8)
            .joinToString("  ") { "${it.key}×${it.value}" }
    )
    check(p.msgFrames >= 1, "至少收到 1 个 payloadType=msg 的帧（${p.msgFrames}）")
    check(p.gzipFailures == 0, "gunzip 没有失败（${p.gzipFailures} 次）")
    check(p.methods.isNotEmpty(), "Response 里解出了至少一种消息（${p.methods.size} 种）")
    check(p.chats.isNotEmpty(), "解出至少 1 条聊天弹幕（${p.chats.size} 条）")

    val cjk = Regex("[\\u4e00-\\u9fa5]")
    p.chats.take(5).forEachIndexed { i, c -> println("  ── 第 ${i + 1} 条：${c.nick}：${c.content}") }
    p.chats.firstOrNull()?.let { first ->
        check(first.nick.isNotBlank(), "昵称非空（\"${first.nick}\"）")
        check(first.content.isNotBlank(), "内容非空（\"${first.content}\"）")
        check(
            cjk.containsMatchIn(first.content) || cjk.containsMatchIn(first.nick),
            "昵称或内容含中文且没乱码"
        )
    }
    println()
    println("  直播流耗时 ${p.elapsedMs}ms（握手到收到第 ${p.chats.size} 条弹幕）")

    hr(if (failures == 0) "全部断言通过（$failures 条失败）" else "有 $failures 条断言失败")
    exitProcess(if (failures == 0) 0 else 1)
}
