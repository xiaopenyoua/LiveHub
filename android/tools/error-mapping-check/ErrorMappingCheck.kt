@file:JvmName("ErrorMappingCheck")

package check

import com.livehub.tv.core.model.SiteError
import com.livehub.tv.core.model.SiteException
import com.livehub.tv.core.net.Net
import com.livehub.tv.core.site.DouyinSite
import com.livehub.tv.core.site.HuyaSite
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 取流层错误码的离线验收（`docs/04` §3.3.1 第五节第 2 条 + 硬规矩 6）。
 *
 * 为什么要有这个文件：`Net` 的 13 行映射和「虎牙重试一次」这两件事，
 * **编译器和 review 都看不出退化**——
 * - 映射写错一行：编译通过，用户看到错误文案，我们要等投诉；
 * - `catch` 落空（异常类型换了）：编译通过、无日志，重试悄悄变成一次就失败；
 * - `catch (Exception)` 接住一切：看起来「处理了」，实际把 kind 降级成兜底桶。
 * 所以这里用**真 HTTP 服务器 + 数请求次数**，不 mock、不反射。
 *
 * 跑法（无 Gradle、无 Android 运行时，需要 JDK 17）：
 * ```
 * ./android/tools/error-mapping-check/run.sh
 * ```
 * 它自己起 127.0.0.1 上的临时端口，不访问公网。
 */
private var pass = 0
private var fail = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  ok    $name")
    } else {
        fail++
        println("  FAIL  $name  $detail")
    }
}

private fun kindOf(block: () -> Unit): Pair<SiteError?, String> {
    return try {
        block()
        null to "没抛异常"
    } catch (e: SiteException) {
        e.kind to (e.detail ?: "")
    }
}

fun main() = runBlocking {
    val hits = AtomicInteger(0)
    var nextStatus = 200
    var nextBody = "{}"

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        hits.incrementAndGet()
        val body = nextBody.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(nextStatus, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.start()
    val port = server.address.port
    val base = "http://127.0.0.1:$port"

    suspend fun resp(status: Int, body: String): SiteError? {
        nextStatus = status
        nextBody = body
        return try {
            Net.getJson("$base/x")
            null
        } catch (e: SiteException) {
            e.kind
        }
    }

    println("\n== docs/04 §3.3.1 第二节：13 行 HTTP→kind ==")

    // 1. 无网短路：探针说没网就不该发请求
    hits.set(0)
    Net.onConnectivityCheck { false }
    val offline = try {
        Net.get("$base/never")
        null
    } catch (e: SiteException) {
        e.kind
    }
    check("1  无网 → NET_OFFLINE", offline == SiteError.NET_OFFLINE, "实际 $offline")
    check("1b 无网时一次请求都没发", hits.get() == 0, "实际 ${hits.get()} 次")
    Net.onConnectivityCheck { true }

    check("5  401 → SIGN_INVALID", resp(401, "") == SiteError.SIGN_INVALID)
    check("6  403（无风控词）→ SIGN_INVALID", resp(403, "forbidden") == SiteError.SIGN_INVALID)
    check("7  403 + -352 → RATE_LIMITED", resp(403, """{"code":-352}""") == SiteError.RATE_LIMITED)
    check("8  429 → RATE_LIMITED", resp(429, "") == SiteError.RATE_LIMITED)
    check("9  404 → HTTP_4XX（不是房间下播）", resp(404, "") == SiteError.HTTP_4XX)
    check("10 418 → HTTP_4XX", resp(418, "") == SiteError.HTTP_4XX)
    check("11 503 → HTTP_5XX", resp(503, "") == SiteError.HTTP_5XX)
    check("12 200 但非 JSON → PARSE_BROKEN", resp(200, "<html>nope</html>") == SiteError.PARSE_BROKEN)
    check("13 204 空体 → PARSE_BROKEN（200 不等于成功）", resp(204, "") == SiteError.PARSE_BROKEN)

    // 2/3/4：连不上 / DNS 失败 / TLS 失败，全部归 NET_TIMEOUT（不单列码）
    val refused = kindOf { runBlocking { Net.get("http://127.0.0.1:1/x") } }.first
    check("2  连接被拒 → NET_TIMEOUT", refused == SiteError.NET_TIMEOUT, "实际 $refused")
    val dns = kindOf { runBlocking { Net.get("http://no-such-host.invalid/x") } }.first
    check("3  DNS 失败 → NET_TIMEOUT", dns == SiteError.NET_TIMEOUT, "实际 $dns")
    // 4：**这一条只验到「HTTPS 打到明文端口 → NET_TIMEOUT」**。真正的 SSLHandshakeException
    // 需要一个会讲 TLS 的服务器，离线造不出来（拿自签证书起 TLS 要 keytool + 平台相关，收益不值）。
    // 写明是为了不让人以为「TLS 测过了」——没测，只测了归类没漏到别的 kind。
    val tls = kindOf { runBlocking { Net.get("https://127.0.0.1:$port/x") } }.first
    check("4  HTTPS 打到明文端口 → NET_TIMEOUT（没漏到别的 kind）", tls == SiteError.NET_TIMEOUT, "实际 $tls")

    println("\n== 硬规矩 6：虎牙「非 2xx 重试一次」还活着吗（数 Net 调用次数）==")
    val huya = HuyaSite()

    // 「数 Net 调用次数」用 Net.requestCount，**不再用探针被问了几次**：探针结果现在是缓存的
    // （低端盒子上 ConnectivityManager 查询本身有开销），探针次数会少于请求次数。
    // 让探针返回 false，每个请求都抛**可重试**的 NET_OFFLINE，正好落在 `fetchProfile` 的 catch 位置。
    // 这一条同时验两件事：
    //   ① 可重试的失败真的会重发一次（2 次）；
    //   ② catch 没有因为异常类型换了而落空（落空就只会有 1 次，且异常直接冒出）。
    // 不 mock、不改生产代码——这恰恰是编译器和 review 都看不出来的那一类退化。
    val beforeHuya = Net.requestCount
    Net.onConnectivityCheck { false }
    val huyaRetry = try {
        huya.detail("123456")
        null
    } catch (e: SiteException) {
        e.kind
    }
    val huyaCalls = Net.requestCount - beforeHuya
    check("可重试失败重发一次：Net 调用次数 == 2", huyaCalls == 2L, "实际 $huyaCalls 次")
    // 期望值是 NET_OFFLINE（探针触发的那个），不是 NET_TIMEOUT——这里钉的是
    // 「fetchProfile 没有把 kind 降级成 PARSE_BROKEN」，不是某一条具体链路。
    check(
        "重试耗尽后 kind 原样透出（没被降级成 PARSE_BROKEN）",
        huyaRetry == SiteError.NET_OFFLINE, "实际 $huyaRetry"
    )

    println("\n== 步4 断言③：抖音位数不对「一个字节都不发」==")
    // 探针保持 false：这组断言全部离线完成，不会真的打 live.douyin.com——
    // 「有没有进到 Net」看 requestCount，「进到哪条路」看 SiteException.url。
    val douyin = DouyinSite()

    suspend fun douyinCall(id: String): Pair<SiteException?, Long> {
        val before = Net.requestCount
        var err: SiteException? = null
        try {
            douyin.detail(id)
        } catch (e: SiteException) {
            err = e
        }
        return err to (Net.requestCount - before)
    }

    val (e15, c15) = douyinCall("123456789012345")
    check("15 位纯数字 → ID_UNRESOLVED", e15?.kind == SiteError.ID_UNRESOLVED, "实际 ${e15?.kind}")
    check("15 位纯数字 → 0 次 Net 调用（不许打到 reflow）", c15 == 0L, "实际 $c15 次")

    val (eEmpty, cEmpty) = douyinCall("   ")
    check(
        "空入参 → ID_UNRESOLVED 且 0 次 Net 调用",
        eEmpty?.kind == SiteError.ID_UNRESOLVED && cEmpty == 0L, "实际 ${eEmpty?.kind} / $cEmpty 次"
    )

    val (e19, c19) = douyinCall("7026727478496348968")
    check(
        "19 位 room_id → 直连 reflow（不走页面配对）",
        c19 == 1L && e19?.url?.contains("reflow/info") == true,
        "实际 $c19 次 / url=${e19?.url}"
    )

    // 这一条是回归护栏：把守卫写成「不是 19 位就抛」会砍掉抖音**唯一的常规入口形态**。
    // 4 位 web_rid 必须去取页面（离线时表现为 NET_OFFLINE，url 落在直播间页上）。
    val (e4, c4) = douyinCall("8888")
    check(
        "4 位 web_rid → 走页面配对，不许在本地判死",
        c4 == 1L && e4?.url?.contains("live.douyin.com/8888") == true,
        "实际 ${e4?.kind} / $c4 次 / url=${e4?.url}"
    )

    val (eLink, cLink) = douyinCall("live.douyin.com/123456789012345")
    check(
        "链接末段 15 位 → ID_UNRESOLVED 且 0 次 Net 调用",
        eLink?.kind == SiteError.ID_UNRESOLVED && cLink == 0L, "实际 ${eLink?.kind} / $cLink 次"
    )

    val (eShort, cShort) = douyinCall("v.douyin.com/iJbXkLmN")
    check(
        "字母短码 → 交给落地页（不在本地判死）",
        cShort == 1L && eShort?.url?.contains("v.douyin.com") == true,
        "实际 ${eShort?.kind} / $cShort 次 / url=${eShort?.url}"
    )
    Net.onConnectivityCheck { true }

    // 不可重试的失败不许进重试器。这一条不联网：`fetchProfile` 的重试条件就是
    // `kind.retryable`，所以表对了逻辑就对，反过来表错了逻辑必错。
    check("ROOM_NOT_FOUND 不可重试", !SiteError.ROOM_NOT_FOUND.retryable)
    check("PARSE_BROKEN 不可重试", !SiteError.PARSE_BROKEN.retryable)
    check("STREAM_OFFLINE 不可重试", !SiteError.STREAM_OFFLINE.retryable)
    check("ID_UNRESOLVED 不可重试（动作是换输入，不是再试一次）", !SiteError.ID_UNRESOLVED.retryable)
    check("HTTP_4XX 可重试（下播房间的恒定 404 不归它）", SiteError.HTTP_4XX.retryable)

    // report 列 = 「责任归属」。这是 ID_UNRESOLVED 存在的唯一理由，所以必须钉住
    check("ID_UNRESOLVED 上报=false（怪用户，别叫醒我们）", !SiteError.ID_UNRESOLVED.report)
    check("PARSE_BROKEN 上报=true（怪我们，必须叫醒）", SiteError.PARSE_BROKEN.report)
    check("枚举恰好 13 个码", SiteError.entries.size == 13, "实际 ${SiteError.entries.size}")

    server.stop(0)

    println("\n通过 $pass   失败 $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
