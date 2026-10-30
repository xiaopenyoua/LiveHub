// 弹幕「有限重连」夹具：本地 WS 服务端 + 仓库里真实的 Danmaku{Client,Huya,Douyu} 源码。
//
// 验三条承诺（每条都有专门的用例，删掉对应常量就会红）：
//   ① 重连过程不发任何事件，Connected 每次 collect 只见一次
//   ② 一次中断只提示一次 System，且「距上次连上不足 ANNOUNCE_AFTER_MS」时完全不提示
//   ③ 到 MAX_RETRY 才发 Failed 收尾，且上限分支只发 Failed，不先说「已断开」
//
// 用例命名：S1 抖动恢复 / S2 真断不复 / S3 从未连上 / S4 三秒阈值 / S5 关→开。
// S1~S4 都在同一个 collect 内部打转，S5 是「collect 被 cancel 之后重新 collect」——
// 那是产品承诺（关掉弹幕再打开）对应的唯一路径，代码分支与前者完全不同。
package harness

import com.livehub.tv.core.danmaku.DanmakuClient
import com.livehub.tv.core.danmaku.DanmakuEvent
import com.livehub.tv.core.danmaku.DouyuDanmaku
import com.livehub.tv.core.danmaku.HuyaDanmaku
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.system.exitProcess

// ------------------------------------------------------------------ 用例定义

class Case(
    val title: String,
    /** 覆盖点系统属性名：douyu.ws / huya.ws */
    val prop: String,
    val room: String,
    val client: DanmakuClient,
    val plans: List<Plan>,
    val durationMs: Long,
    /** 端口上没有人监听（从未连上） */
    val deadServer: Boolean = false,
    val expect: Expect,
    val extra: ((Result) -> Unit)? = null
)

data class Expect(
    val connected: Int,
    val systems: Int,
    val failed: Int,
    val chats: Int,
    val failedIsLast: Boolean = false,
    val chatIsLast: Boolean = false,
    /** System 允许出现的时间窗（毫秒） */
    val systemAt: LongRange? = null,
    /** System 与 Failed 至少隔多久（证明上限分支不是「先说已断开再说连接失败」） */
    val systemToFailedGap: Long? = null,
    val totalEvents: Int? = null
)

class Result(val title: String, val events: List<Pair<Long, String>>) {
    fun count(prefix: String) = events.count { it.second.startsWith(prefix) }
    fun first(prefix: String) = events.firstOrNull { it.second.startsWith(prefix) }
    fun last(prefix: String) = events.lastOrNull { it.second.startsWith(prefix) }
}

private fun describe(ev: DanmakuEvent): String = when (ev) {
    is DanmakuEvent.Connected -> "★ Connected"
    is DanmakuEvent.Chat -> "Chat(user=${ev.user}, text=${ev.text}, color=0x${ev.color.toString(16).uppercase()})"
    is DanmakuEvent.Gift -> "Gift(${ev.user} ${ev.giftName} x${ev.count})"
    is DanmakuEvent.System -> "◆ System(\"${ev.text}\")"
    is DanmakuEvent.Failed -> "✖ Failed(\"${ev.message}\")"
}

// ------------------------------------------------------------------ 执行一个用例

private suspend fun runCase(c: Case): Result {
    Timeline.reset()
    val server = if (c.deadServer) {
        // 先绑定再立刻关闭：端口上没人监听，connect 直接 ECONNREFUSED
        FakeServer(emptyList()).also { it.shutdown() }
    } else {
        FakeServer(c.plans).also { it.start() }
    }
    System.setProperty(c.prop, "ws://127.0.0.1:${server.port}")
    println("\n>>> ${c.title}   (${c.prop}=ws://127.0.0.1:${server.port}${if (c.deadServer) "，未监听" else ""})")

    val events = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
    runBlocking {
        val flowDone = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            c.client.stream(c.room).collect { ev ->
                events.add((System.currentTimeMillis() - Timeline.t0) to describe(ev))
                Timeline.mark("client", describe(ev))
            }
            Timeline.mark("client", "流结束（collect 正常返回）")
            flowDone.complete(Unit)
        }
        // 流自己结束（Failed 收尾）就提前收工，不必等满 durationMs
        withTimeoutOrNull(c.durationMs) { flowDone.await() }
        job.cancelAndJoin()
    }
    server.shutdown()
    // cancel 会让 OkHttp 线程补一条 onFailure 日志；不歇一下，它会插进下面 dump 的中间，
    // 读流水的人会以为那条失败发生在别处。150ms 只影响日志落盘顺序，不影响任何时间戳。
    kotlinx.coroutines.delay(150)
    Timeline.dump("${c.title} —— 事件流水")
    return Result(c.title, events.toList())
}

// ------------------------------------------------------------------ 断言

private var failures = 0

private fun check(ok: Boolean, msg: String) {
    println((if (ok) "  PASS  " else "  FAIL  ") + msg)
    if (!ok) failures++
}

private fun assertCase(r: Result, e: Expect) {
    check(r.count("★") == e.connected, "Connected ${r.count("★")} 次（期望 ${e.connected}）")
    check(r.count("◆") == e.systems, "System ${r.count("◆")} 次（期望 ${e.systems}）")
    check(r.count("✖") == e.failed, "Failed ${r.count("✖")} 次（期望 ${e.failed}）")
    check(r.count("Chat(") == e.chats, "Chat ${r.count("Chat(")} 条（期望 ${e.chats}）")

    if (e.failedIsLast) {
        check(r.events.lastOrNull()?.second?.startsWith("✖") == true, "最后一个事件是 Failed（流已结束）")
    }
    if (e.chatIsLast) {
        check(r.events.lastOrNull()?.second?.startsWith("Chat(") == true, "最后一个事件是弹幕（连接还活着）")
    }
    e.totalEvents?.let { check(r.events.size == it, "整个序列 ${r.events.size} 个事件（期望 $it）") }

    e.systemAt?.let { win ->
        val at = r.first("◆")?.first ?: -1
        check(at in win, "System 出现在 t=${at}ms（期望落在 ${win.first}..${win.last}ms）")
    }
    e.systemToFailedGap?.let { need ->
        val sysAt = r.first("◆")?.first ?: -1
        val failAt = r.last("✖")?.first ?: -1
        check(
            failAt - sysAt > need,
            "Failed(t=${failAt}ms) 与 System(t=${sysAt}ms) 相隔 ${failAt - sysAt}ms（要求 > $need），" +
                "说明上限分支没有紧贴一条「已断开」"
        )
    }
}

// ------------------------------------------------------------------ 用例表

// 三段共用的「稳定后」「抖动前」话术，只是为了让流水好读

private fun douyuCases(): List<Case> {
    val s1 = Case(
        title = "S1 抖动恢复（斗鱼）：连上后 200~700ms 掐断 4 次，每次 1s 后重连成功",
        prop = "douyu.ws", room = "9999", client = DouyuDanmaku(),
        plans = listOf(
            Plan(
                outs = listOf(
                    Out(300, douyuChatPush("老观众", "第一条真实弹幕"), "chatmsg「第一条真实弹幕」"),
                    Out(450, douyuChatPush("红字君", "这条应该是红字", col = 16711680), "chatmsg「这条应该是红字」"),
                    Out(550, douyuChatPush("机器人", "if=0 这条不该出现", iflag = 0), "chatmsg「if=0 这条不该出现」")
                ),
                cutAt = 700, style = "graceful"
            ),
            Plan(listOf(Out(120, douyuChatPush("弹幕二", "重连后的第二条"), "chatmsg")), cutAt = 250, style = "graceful"),
            Plan(listOf(Out(120, douyuChatPush("弹幕三", "重连后的第三条"), "chatmsg")), cutAt = 250, style = "abrupt"),
            Plan(listOf(Out(120, douyuChatPush("弹幕四", "重连后的第四条"), "chatmsg")), cutAt = 250, style = "graceful"),
            Plan(listOf(Out(120, douyuChatPush("弹幕五", "稳定后最后一条"), "chatmsg")))
        ),
        durationMs = 9_000,
        // 5 个连接共发 7 条，其中 if@=0 那条必须被过滤 → 6 条
        expect = Expect(connected = 1, systems = 0, failed = 0, chats = 6),
        extra = { r ->
            check(r.events.none { it.second.contains("机器人") }, "if@=0 的消息没有进弹幕")
            check(r.events.any { it.second.contains("color=0xFFFF0000") }, "col=16711680 解成 0xFFFF0000")
        }
    )

    val s4 = Case(
        title = "S4 三秒阈值（斗鱼）：连上→300ms 被掐→下次重连被拒(距上次成功 1.3s)→再下次成功",
        prop = "douyu.ws", room = "9999", client = DouyuDanmaku(),
        plans = listOf(
            Plan(listOf(Out(100, douyuChatPush("抖一下", "抖动前还有弹幕"), "chatmsg")), cutAt = 300, style = "graceful"),
            Plan(style = "refuse"),
            Plan(listOf(Out(100, douyuChatPush("恢复了", "自动恢复后的弹幕"), "chatmsg")))
        ),
        durationMs = 7_000,
        // 距上次成功 1.3s < ANNOUNCE_AFTER_MS(3s) → 一个字都不该出现；删掉这个常量此组必红
        expect = Expect(connected = 1, systems = 0, failed = 0, chats = 2, chatIsLast = true)
    )

    val s2 = Case(
        title = "S2 真断不复（斗鱼）：连上 5s 后 RST 并关闭监听，之后永远连不上",
        prop = "douyu.ws", room = "9999", client = DouyuDanmaku(),
        plans = listOf(
            Plan(
                listOf(
                    Out(200, douyuChatPush("甲", "断线前的弹幕一"), "chatmsg"),
                    Out(400, douyuChatPush("乙", "断线前的弹幕二"), "chatmsg")
                ),
                cutAt = 5_000, style = "abrupt", stopAccept = true
            )
        ),
        durationMs = 50_000,
        expect = Expect(
            connected = 1, systems = 1, failed = 1, chats = 2,
            failedIsLast = true,
            systemAt = 3_000L..12_000L,
            systemToFailedGap = 10_000
        )
    )

    val s3 = Case(
        title = "S3 从未连上（斗鱼）：端口没人监听",
        prop = "douyu.ws", room = "9999", client = DouyuDanmaku(),
        plans = emptyList(), durationMs = 45_000, deadServer = true,
        // everUp=false：一句「已断开」都不该出现（用户根本没连上过）
        expect = Expect(connected = 0, systems = 0, failed = 1, chats = 0, failedIsLast = true, totalEvents = 1)
    )

    return listOf(s1, s4, s2, s3)
}

private fun huyaCases(): List<Case> {
    val s1 = Case(
        title = "S1 抖动恢复（虎牙）：连上后 200~700ms 掐断 4 次，每次 1s 后重连成功",
        prop = "huya.ws", room = "1234", client = HuyaDanmaku(),
        plans = listOf(
            Plan(
                outs = listOf(
                    Out(300, huyaChatPush("老观众", "第一条真实弹幕"), "HYPushMessage uri=1400「第一条真实弹幕」"),
                    Out(450, huyaChatPush("红字君", "这条应该是红字", color = 16711680), "HYPushMessage uri=1400「这条应该是红字」"),
                    Out(550, huyaChatPush("默认色", "没有 fontColor 字段的白字"), "HYPushMessage uri=1400「默认色」")
                ),
                cutAt = 700, style = "graceful"
            ),
            Plan(listOf(Out(120, huyaChatPush("弹幕二", "重连后的第二条"), "uri=1400")), cutAt = 250, style = "graceful"),
            Plan(listOf(Out(120, huyaChatPush("弹幕三", "重连后的第三条"), "uri=1400")), cutAt = 250, style = "abrupt"),
            Plan(listOf(Out(120, huyaChatPush("弹幕四", "重连后的第四条"), "uri=1400")), cutAt = 250, style = "graceful"),
            Plan(listOf(Out(120, huyaChatPush("弹幕五", "稳定后最后一条"), "uri=1400")))
        ),
        durationMs = 9_000,
        expect = Expect(connected = 1, systems = 0, failed = 0, chats = 7),
        extra = { r ->
            check(r.events.any { it.second.contains("color=0xFFFF0000") }, "fontColor=16711680 解成 0xFFFF0000")
            check(r.events.any { it.second.contains("color=0xFFFFFFFF") }, "缺 fontColor 字段的那条按白字处理")
            check(r.events.any { it.second.contains("user=老观众") }, "HYSender.nick 解出来了（中文昵称）")
        }
    )

    val s4 = Case(
        title = "S4 三秒阈值（虎牙）：连上→300ms 被掐→下次重连被拒(距上次成功 1.3s)→再下次成功",
        prop = "huya.ws", room = "1234", client = HuyaDanmaku(),
        plans = listOf(
            Plan(listOf(Out(100, huyaChatPush("抖一下", "抖动前还有弹幕"), "uri=1400")), cutAt = 300, style = "graceful"),
            Plan(style = "refuse"),
            Plan(listOf(Out(100, huyaChatPush("恢复了", "自动恢复后的弹幕"), "uri=1400")))
        ),
        durationMs = 7_000,
        expect = Expect(connected = 1, systems = 0, failed = 0, chats = 2, chatIsLast = true)
    )

    val s3 = Case(
        title = "S3 从未连上（虎牙）：端口没人监听",
        prop = "huya.ws", room = "1234", client = HuyaDanmaku(),
        plans = emptyList(), durationMs = 45_000, deadServer = true,
        expect = Expect(connected = 0, systems = 0, failed = 1, chats = 0, failedIsLast = true, totalEvents = 1)
    )

    return listOf(s1, s4, s3)
}

// ------------------------------------------------------------------ S5 关→开

/**
 * S5 验的是**产品承诺**，不是内部约定：电视上唯一可靠的自助恢复手段就是「关掉弹幕再打开」。
 *
 * S1/S3/S4 全部在**同一个 collect 内部**打转（断线 → 重连），走的是 `while (isActive)`
 * 里的分支；S5 走的是完全另一条路——collect 被 cancel、流被丢弃、`stream()` 被重新调用，
 * 也就是新 channelFlow、新 WebSocket、新计数。前者绿不代表后者绿。
 *
 * [cancelAtMs]      第一次 collect 里「用户关弹幕」的时刻（相对本组用例开始）
 * [secondFirstChat] 第二次 collect 从开始到收到第一条弹幕的上限；
 *                   对「脏计数」那组它是个判别阈值，见下面 s5Cases 的注释
 */
private data class S5(
    val title: String,
    val prop: String,
    val room: String,
    val client: DanmakuClient,
    val plans: List<Plan>,
    val cancelAtMs: Long,
    val secondFirstChat: Long,
    val dirtyCounter: Boolean,
    /** cancel 之后隔多久再 collect —— S5 基础组故意放到「服务端把旧连接关掉」之后 */
    val delayAfterCancel: Long = 200
)

private class S5Result(
    val title: String,
    val first: List<Pair<Long, String>>,
    val second: List<Pair<Long, String>>,
    val cancelAt: Long,
    val secondStartAt: Long,
    /** 第一次 collect 以「被取消」结束，而不是流自己 Failed 收尾后正常返回 */
    val firstEndedByCancel: Boolean,
    /** 第二次 collect 里第一条弹幕相对它开始的延迟 —— 脏计数会让它变长 */
    val secondFirstChatDelay: Long
) {
    fun firstCount(p: String) = first.count { it.second.startsWith(p) }
    fun secondCount(p: String) = second.count { it.second.startsWith(p) }
}

private suspend fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond()) return true
        kotlinx.coroutines.delay(10)
    }
    return cond()
}

private suspend fun runS5(s: S5): S5Result {
    Timeline.reset()
    val server = FakeServer(s.plans).also { it.start() }
    System.setProperty(s.prop, "ws://127.0.0.1:${server.port}")
    println("\n>>> ${s.title}   (${s.prop}=ws://127.0.0.1:${server.port})")

    val first = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
    val second = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
    var cancelAt = -1L
    var secondStartAt = -1L
    var endedByCancel = false

    runBlocking {
        // ---- collect #1：用户开着弹幕
        val job1 = launch(Dispatchers.Default) {
            try {
                s.client.stream(s.room).collect { ev ->
                    first.add((System.currentTimeMillis() - Timeline.t0) to describe(ev))
                    Timeline.mark("client#1", describe(ev))
                }
                Timeline.mark("client#1", "流自己结束（正常返回）")
            } catch (e: CancellationException) {
                endedByCancel = true
                Timeline.mark("client#1", "collect 被取消（CancellationException）")
                throw e
            }
        }
        // 等到真的收到弹幕：说明注册组/心跳都发了，第一条连接确实在工作
        awaitUntil(8_000) { first.any { it.second.startsWith("Chat(") } }
        val waitToCancel = s.cancelAtMs - (System.currentTimeMillis() - Timeline.t0)
        if (waitToCancel > 0) kotlinx.coroutines.delay(waitToCancel)
        cancelAt = System.currentTimeMillis() - Timeline.t0
        Timeline.mark("harness", "→ 用户关弹幕：cancel() 掉 collect #1")
        job1.cancelAndJoin()

        // ---- collect #2：同一实例、同一房间，重新 stream()
        kotlinx.coroutines.delay(s.delayAfterCancel)
        secondStartAt = System.currentTimeMillis() - Timeline.t0
        Timeline.mark("harness", "→ 用户重新开弹幕：再 collect 一次同一实例的 stream()")
        val job2 = launch(Dispatchers.Default) {
            try {
                s.client.stream(s.room).collect { ev ->
                    second.add((System.currentTimeMillis() - Timeline.t0) to describe(ev))
                    Timeline.mark("client#2", describe(ev))
                }
                Timeline.mark("client#2", "流自己结束（正常返回）")
            } catch (e: CancellationException) {
                Timeline.mark("client#2", "collect 被取消（用例收尾）")
                throw e
            }
        }
        awaitUntil(20_000) { second.any { it.second.startsWith("Chat(") } }
        job2.cancelAndJoin()
    }
    server.shutdown()
    kotlinx.coroutines.delay(150)
    Timeline.dump("${s.title} —— 事件流水（client#1 = 关之前，client#2 = 开之后）")

    val firstChatAt = second.firstOrNull { it.second.startsWith("Chat(") }?.first ?: -1
    return S5Result(
        title = s.title,
        first = first.toList(),
        second = second.toList(),
        cancelAt = cancelAt,
        secondStartAt = secondStartAt,
        firstEndedByCancel = endedByCancel,
        secondFirstChatDelay = if (firstChatAt < 0) -1 else firstChatAt - secondStartAt
    )
}

private fun assertS5(r: S5Result, s: S5) {
    // 关之前：正常收到 Connected + 弹幕，且一次「已断开」都没弹
    check(r.firstCount("★") == 1, "collect#1 Connected ${r.firstCount("★")} 次（期望 1）")
    check(r.firstCount("Chat(") >= 1, "collect#1 收到弹幕 ${r.firstCount("Chat(")} 条（期望 ≥1）")
    check(r.firstCount("◆") == 0, "collect#1 System ${r.firstCount("◆")} 次（用户主动关弹幕，不该说「已断开」）")
    check(r.firstCount("✖") == 0, "collect#1 Failed ${r.firstCount("✖")} 次（取消不是失败）")
    check(r.firstEndedByCancel, "collect#1 以 CancellationException 结束（而不是流自己 Failed 收尾后正常返回）")

    // 开之后：新 collect 自己拿到一条 Connected，而且能收到新弹幕
    check(r.secondCount("★") == 1, "collect#2 Connected ${r.secondCount("★")} 次（期望 1，重新开弹幕该有一次新的）")
    check(r.secondCount("Chat(") >= 1, "collect#2 收到弹幕 ${r.secondCount("Chat(")} 条（期望 ≥1）")
    check(r.secondCount("◆") == 0, "collect#2 System ${r.secondCount("◆")} 次（重连过程不该说话）")
    check(r.secondCount("✖") == 0, "collect#2 Failed ${r.secondCount("✖")} 次（期望 0）")

    val firstStar = r.second.firstOrNull { it.second.startsWith("★") }?.first ?: -1
    val firstChat = r.second.firstOrNull { it.second.startsWith("Chat(") }?.first ?: -1
    check(firstStar in 0..firstChat, "collect#2 里 Connected(t=${firstStar}ms) 先于第一条弹幕(t=${firstChat}ms)")

    if (s.dirtyCounter) {
        // 判别阈值：干净计数 → 失败#1 退避 1s、失败#2 退避 2s，约 3.2s 后恢复；
        // 若 attempt/announced 被跨 collect 共享（例如从局部变量挪成字段），起点带着 1，
        // 就是 2s+4s，约 6.1s。4800ms 把两者分开。
        check(
            r.secondFirstChatDelay in 0..s.secondFirstChat,
            "collect#2 从开始到第一条弹幕 ${r.secondFirstChatDelay}ms" +
                "（上限 ${s.secondFirstChat}ms：退避序列必须是 1s,2s 而不是 2s,4s，说明计数从 0 起算）"
        )
    }
}

// ------------------------------------------------------------------ S5 用例表

private fun s5Cases(): List<S5> = listOf(
    S5(
        title = "S5 关→开（斗鱼）：collect#1 收到弹幕后 cancel，服务端随后关掉旧连接，再重新 collect",
        prop = "douyu.ws", room = "9999", client = DouyuDanmaku(),
        plans = listOf(
            // 旧连接：先给一条弹幕，900ms 后由服务端关掉；我们在 600ms 就 cancel 了，
            // 所以顺序是「用户关弹幕 → 服务端才把那条连接收掉 → 用户重新打开」，
            // 也就是要证明：一条被丢弃的连接晚一步被关掉，不能影响新 collect。
            Plan(
                listOf(Out(200, douyuChatPush("关之前", "关弹幕之前的弹幕"), "chatmsg")),
                cutAt = 900, style = "graceful"
            ),
            Plan(listOf(Out(150, douyuChatPush("开之后", "重新打开之后的弹幕"), "chatmsg")))
        ),
        cancelAtMs = 600, secondFirstChat = 3_000, dirtyCounter = false, delayAfterCancel = 1_200
    ),
    S5(
        title = "S5 关→开·脏计数（斗鱼）：在退避窗口里 cancel（此刻 attempt=1），重新 collect 必须从 0 起算",
        prop = "douyu.ws", room = "9999", client = DouyuDanmaku(),
        plans = listOf(
            // 先制造一次失败（300ms 处被掐），客户端进入 1s 退避；我们在 1100ms 取消，
            // 也就是「attempt 已经涨到 1、还没重新连上」的那一刻关掉弹幕。
            Plan(listOf(Out(200, douyuChatPush("断之前", "断线之前还有弹幕"), "chatmsg")),
                cutAt = 300, style = "abrupt"),
            // 重新开弹幕后：前两次重连被拒，第三次成功——这样退避序列会暴露计数起点
            Plan(style = "refuse"),
            Plan(style = "refuse"),
            Plan(listOf(Out(120, douyuChatPush("恢复了", "重新打开并重连后的弹幕"), "chatmsg")))
        ),
        cancelAtMs = 1_100, secondFirstChat = 4_800, dirtyCounter = true
    ),

    S5(
        title = "S5 关→开（虎牙）：collect#1 收到弹幕后 cancel，服务端随后关掉旧连接，再重新 collect",
        prop = "huya.ws", room = "1234", client = HuyaDanmaku(),
        plans = listOf(
            Plan(
                listOf(Out(200, huyaChatPush("关之前", "关弹幕之前的弹幕"), "uri=1400")),
                cutAt = 900, style = "graceful"
            ),
            Plan(listOf(Out(150, huyaChatPush("开之后", "重新打开之后的弹幕"), "uri=1400")))
        ),
        cancelAtMs = 600, secondFirstChat = 3_000, dirtyCounter = false, delayAfterCancel = 1_200
    ),
    S5(
        title = "S5 关→开·脏计数（虎牙）：在退避窗口里 cancel（此刻 attempt=1），重新 collect 必须从 0 起算",
        prop = "huya.ws", room = "1234", client = HuyaDanmaku(),
        plans = listOf(
            Plan(listOf(Out(200, huyaChatPush("断之前", "断线之前还有弹幕"), "uri=1400")),
                cutAt = 300, style = "abrupt"),
            Plan(style = "refuse"),
            Plan(style = "refuse"),
            Plan(listOf(Out(120, huyaChatPush("恢复了", "重新打开并重连后的弹幕"), "uri=1400")))
        ),
        cancelAtMs = 1_100, secondFirstChat = 4_800, dirtyCounter = true
    )
)

// ------------------------------------------------------------------ 入口

fun main(args: Array<String>): Unit = runBlocking {
    // 覆盖点：房间信息不走真实 HTTP（见 patches/endpoints.patch）。
    // 虎牙每次重连前都要调一次 mp.huya.com 换 topSid，不替换的话这组用例会变成网络测试。
    System.setProperty("huya.room", "2272316519")

    val raw = args.firstOrNull()
    // 用例标题里写的是中文平台名，这里给 douyu/huya 两个英文别名，省得敲中文
    val filter = when (raw?.lowercase()) {
        null -> null
        "douyu", "斗鱼" -> "斗鱼"
        "huya", "虎牙" -> "虎牙"
        else -> raw
    }
    val cases = (douyuCases() + huyaCases())
        .filter { filter == null || it.title.contains(filter, ignoreCase = true) }
    val s5s = s5Cases().filter { filter == null || it.title.contains(filter, ignoreCase = true) }
    println("共 ${cases.size + s5s.size} 组用例，预计 3 分钟（S2/S3 要等退避 1+2+4+8+16s 走完）")
    if (filter != null) println("（已按 \"$filter\" 过滤）")

    for (c in cases) {
        val r = runCase(c)
        println("\n--- 断言：${c.title}")
        assertCase(r, c.expect)
        c.extra?.invoke(r)
    }

    // S5：collect 被 cancel 之后再 collect，与上面几组走的是完全不同的分支
    for (s in s5s) {
        val r = runS5(s)
        println("\n--- 断言：${s.title}")
        assertS5(r, s)
    }

    println()
    println("#".repeat(86))
    println(if (failures == 0) "# 全部断言通过（$failures 条失败）" else "# 有 $failures 条断言失败")
    println("#".repeat(86))
    exitProcess(if (failures == 0) 0 else 1)
}
