package com.livehub.tv.core.model

/**
 * 取流层/网络层的失败分类。**全项目唯一一份**。
 *
 * UI 文案、重试按钮、诊断页统计都从这个枚举查；不许在别处再定义一套 kind，
 * 也不许用消息字符串反推（`SiteError.valueOf(msg)` / `msg.contains("超时")` 一律算没做）——
 * 那样改一个中文字就会让文案映射静默退化，而测试看不出来，因为改的人以为自己在改文案。
 *
 * 零 android 依赖，将来 tvOS 直接复用本文件，所以这里**没有中文文案**：
 * 文案属于平台资源，在 `ui/strings` 里是一张「一个 [SiteError] 一行」的映射表。
 *
 * [retryable] 不是「技术上能不能重发」，是「用户点一下重试有没有意义」：它决定播放页
 * 给「重试」还是给「换线路 / 回首页」。这一列写错，UI 就会在一个永远不会好的房间上
 * 给用户一个按了没用的按钮。[report] 只决定是否计入诊断页的「每平台错误码 Top3」，
 * 与用户看到什么无关。
 *
 * 加码的门槛有两条，**缺一不可**：
 * 1. **用户该做的动作不同**。TLS 握手失败因此不单列——用户可做的动作与超时完全一样，
 *    靠 `detail="TLS"` 在诊断页区分就够了。
 * 2. **`retryable` 或 `report` 必须取不同值**，也就是「责任归属不同」。这条是最初漏掉、
 *    后来被 [ID_UNRESOLVED] 逼出来的一条：它和 [PARSE_BROKEN] 的文案可以一样，但一个
 *    不该叫醒我们（用户粘错了链接）、一个必须（我们的解析器坏了）。**一个枚举常量没法同时
 *    代表「不是 bug」和「是 bug」**，所以必须拆码。
 *
 * 反过来说：**文案不同不足以加码**。文案是 UI 的事，一个 kind 配一句就够；只有「动作」
 * 或「归责」不同才值得动这个枚举。
 */
enum class SiteError(val retryable: Boolean, val report: Boolean) {

    /** 系统报告无网络。判在请求发出之前，省掉一次 8s 连接超时，用户立刻知道是网的问题 */
    NET_OFFLINE(retryable = true, report = false),

    /**
     * 超时 / DNS 失败 / 连接被重置 / TLS 握手失败，全部归这里。
     * `UnknownHostException` 也归这里而不是 `NET_OFFLINE`：对用户的可做动作相同，
     * 报「网络未连接」会让他去查路由器。
     */
    NET_TIMEOUT(retryable = true, report = true),

    /**
     * 4xx。**不含下播房间的恒定 404**——那归 [STREAM_OFFLINE]：同一个地址重发一万次
     * 还是 404，给「重试」按钮是骗人。
     *
     * `retryable = false`：4xx 是「参数/路径/权限错」，重发一次拿回来的必然是同一个 4xx，
     * 只是让用户多等一次往返。这也让 [HTTP_4XX] 与 [HTTP_5XX] 的 `retryable` 取到不同值——
     * 按本文件头顶「加码门槛」第 2 条，两个码该不该合并只看这一条：**必须分开，不许合并**。
     */
    HTTP_4XX(retryable = false, report = true),

    /** 5xx：平台自身故障，等一会儿确实可能好转 */
    HTTP_5XX(retryable = true, report = true),

    /** 429 或返回体命中风控词。退避有效、原地立刻重试无效——这两者动作相反，所以和 [HTTP_4XX] 分开 */
    RATE_LIMITED(retryable = true, report = true),

    /**
     * 401 / 403、防盗链或签名校验失败。上报优先级最高：
     * 它是「平台改版」最早、最干净的信号，比用户投诉早好几天。
     */
    SIGN_INVALID(retryable = true, report = true),

    /**
     * JSON 结构不符预期（必填字段缺失/为空）、`playUrls` 解析不出任何地址。
     * 上报优先级最高。**`playUrls` 拿不到地址必须抛它，不许返回空列表**——
     * 空列表在 UI 上和「平台没播」长得一样，等于静默失败。
     */
    PARSE_BROKEN(retryable = false, report = true),

    /**
     * 明确的「房间不存在」。
     *
     * 判据必须来自平台给的确定字段，**不许用「解析失败」当前提**：虎牙是
     * **HTTP 200 + `status:422`**；斗鱼是**平台自己写在提示页里的那句话**——正文含
     * `BETARD_ABSENT_MARKERS`（「该房间目前没有开放」/「没有找到该房间」，
     * 见 `docs/06` §2.4）。判据落在 `DouyuSite.betardShape()` + `betardFailure()`，
     * **唯一一处**。
     *
     * 为什么不能只写「不是 JSON」：斗鱼改版、维护页、WAF 风控页、运营商劫持页**都不是
     * JSON**，只按形状判会把它们全说成「房间不存在」，而这个码 `report = false`
     * ——整站静默失效。
     *
     * **别用体积当判据，哪怕它看起来很划算。** 中间版本写过「非 JSON 且 < 4000 字节」，
     * 被实测推翻：体积从 238 到 127820 连续分布（238 / 1349 / 1354 / 5239 / 119584 / 127820），
     * 那条线实际竖在 1354 和 5239 之间——**一张 300 字节的 HTTP 200 风控页会落进这个码**
     * （`report = false`），正是要防的那件事。两个方向的反例都写成了单测
     * （`DouyuBetardShapeTest`）。
     *
     * **也不能只匹配 `<title>提示信息 -斗鱼</title>`**：那张模板被通用 404 页复用
     * （`betard/-1`，5239 字节），会把「路径写错了」说成「房间没了」。
     *
     * **抖音侧：能进 `reflow` 的 id 一定是 19 位纯数字**（由 `douyinIdShape()` 的三分类
     * 保证：只有 `ROOM_ID` 那档是 19 位；`WEB_RID` 是 4–12 位、走页面配对；`NEITHER`
     * 本地就抛 `ID_UNRESOLVED`，一个请求都不发），
     * 所以 `10011` 只剩「这个号不存在」一种解释，**裸映射，不加任何前缀条件**。
     * `10011` 同时也是「参数不合法」——那只发生在**页面**上，不是 `reflow`；把
     * 「id 是否来自页面配对」当成判据会漏掉用户收藏/最近观看那条最常走的路径，
     * 让这个码变成几乎不可达。`detail` 留原始 body，诊断页照常计数。
     */
    ROOM_NOT_FOUND(retryable = false, report = false),

    /**
     * 我们没能把**用户给的东西**变成房间标识：空入参、纯数字但位数不对、短链落地页里
     * 配不上唯一的一个 roomId。
     *
     * 和 [PARSE_BROKEN] 的分界线是**归责**，不是文案：
     * - 这里：用户粘错了 / 少打了几位 / 用的是失效短链 → `report = false`，别叫醒我们；
     * - [PARSE_BROKEN]：页面拿到了、但结构变了，配不出 roomId → `report = true`，必须叫醒。
     *
     * 用户动作也确实是另一件事：**换一种输入**（`live.douyin.com/房间号` 或 App 内分享链接），
     * 而 [PARSE_BROKEN] 是「稍后再试」。两条门槛都满足，所以它是第 13 个码而不是一个 detail。
     */
    ID_UNRESOLVED(retryable = false, report = false),

    /**
     * 业务字段说未开播/已下播。**HTTP 可能仍是 200**，响应体也可能满配播放地址
     * （抖音下播房间照样给 4 路 FLV + 4 路 HLS，只是全拉不动）。
     * 「否」是为了不让 UI 在一个已经结束的房间上给一个按了没用的按钮。
     */
    STREAM_OFFLINE(retryable = false, report = false),

    /** 地区限制。**目前四家都没有落点**：返回码没确认前一律归 [HTTP_4XX] + `detail`，不上报成新码 */
    GEO_BLOCKED(retryable = false, report = true),

    /** 所有线路的编码设备都不支持。设置里给了「强制 H.264」这条出路，所以不重试 */
    DECODE_UNSUPPORTED(retryable = false, report = true),

    /** 弹幕建连/解包失败。不影响画面：静默重连，只在彻底放弃时提示一次 */
    DANMAKU_PROTOCOL(retryable = true, report = true)
}

/**
 * 取流层/网络层唯一的异常类型（[com.livehub.tv.core.net.Net] 与四家 site 只抛它）。
 *
 * [Exception.message] 是给日志和诊断页看的，**不是给用户看的**：主文案由 UI 用 [kind]
 * 查表得到。这条是整套错误码的唯一意义——把「用户看到什么」和「我们怎么描述」解耦。
 *
 * **上面这句现在是要求，还不是现状。** 播放页已经改成查表（`ui/strings/SiteErrorText.kt`），
 * 但走 `Async.Failed(String)` 的那条链还在把 `message` 拼上屏：`ui/components/Bits.kt:77`
 * 是泄漏源，`HomeScreen.kt:70` / `BrowseScreen.kt:208` / `SearchScreen.kt:566` 三个点直接上屏。
 * 也就是说**今天**首页最坏会显示「首页推荐没有取到：TLS: SSLHandshakeException」。
 * 这四处在步 6 改完之后，这句才从"要求"变成"事实"——在那之前别引用它当保证。
 *
 * @param url 出错时的完整地址，诊断页要能直接复制去 curl 复现
 * @param detail 原始接口消息 / 状态码 / 解析失败在第几步，**只进诊断页**，不进主文案
 * @param cause 原始异常（OkHttp 的 IOException、org.json 的 JSONException 等）
 */
class SiteException(
    val kind: SiteError,
    val url: String? = null,
    val detail: String? = null,
    cause: Throwable? = null
) : Exception(detail ?: kind.name, cause)

/**
 * 日志专用：把归责码和细节拆成**两个独立字段**，保证谁也不吞谁。
 *
 * ## 为什么不能写 `"${e.message}"`
 *
 * [SiteException] 的构造器是 `Exception(detail ?: kind.name, cause)`，所以：
 *
 * - `detail` 非空 → `message` **就是** detail，**kind 在这一行日志里根本不存在**；
 * - `detail` 为空 → `message` 是 kind，但这时候本来也没有 detail 可丢。
 *
 * 也就是说 `Log.w(TAG, "失败: ${e.message}")` 的实际行为是「**有细节就丢归责码，
 * 没细节才有**」——而恰恰是出错信息最详细的那些 case，归责码丢得最干净。
 *
 * ## 这不是「某几处写漏了」，是结构性的
 *
 * [com.livehub.tv.core.net.Net] 的四个 [SiteException] 抛出点，`detail` 都**不可能为空**：
 *
 * | 抛出点 | detail 构造 |
 * |---|---|
 * | `httpError` | `"HTTP $code"` + 可能为空的 `" body=…"` → 至少前半句 |
 * | `ioError` | `"${simpleName}: ${message.orEmpty()}"` 或 `"TLS: …"` |
 * | `parseError` | `"返回体不是 JSON（200 不等于成功）: …"` |
 * | `NET_OFFLINE` | 写死 `"网络未连接"` |
 *
 * ⇒ **凡是 `Net` 抛出来的 [SiteException]，用 `message` 打日志就永远只能看见 detail。**
 * 没有例外，也不需要逐处去判断。
 *
 * ## 什么时候可以继续用 `message`
 *
 * 只有当那个 `e` **结构上不可能是 [SiteException]** 时——纯字节解析（`HuyaDanmaku.decode`
 * 的 Tars 解码）、`MediaCodecList` 探测、`requestFocus`。这条例外**必须写成
 * 「这个 e 不可能是 SiteException」**，不能写成「这一处不重要」：后者会随
 * [com.livehub.tv.core.net.Net] 改一次就变假，前者永真。
 *
 * 用法：`Log.w(TAG, "ws failure: ${t.logFields}")`
 */
val Throwable.logFields: String
    get() {
        val se = this as? SiteException
            ?: return "kind=- detail=${message ?: javaClass.simpleName}"
        return "kind=${se.kind} detail=${se.detail ?: "-"}"
    }

/**
 * 任意异常 → [SiteException]。给「反正不能把用户晾着」的那类兜底 `catch` 用。
 *
 * **名字不是 `toSiteError`**：那个名字留给 [com.livehub.tv.core.site.toSiteError]
 * （`Throwable → SiteError`，只取归责码）。两个都叫 `toSiteError` 时，
 * `e.toSiteError()` 会因为默认参数而在两个重载之间**歧义**——编译器报的是
 * 「overload resolution ambiguity」，而看懂它要五分钟。返回类型不同就该名字不同。
 *
 * 存在理由：UI/播放器层迟早要写 `catch (e: Throwable)`，而那种 catch 一旦写成
 * `{ emptyList() }`，**kind 就被扔在门口**——用户只看到一句笼统的「没有取到播放地址」，
 * 诊断页也算不到这笔账。有了这个扩展，兜底也能保住归责：
 * `catch (e: Throwable) { show(e.toSiteException()) }`。
 *
 * 三条纪律：
 * 1. **调用方必须先 rethrow `CancellationException`**，否则协程取消会被当成「平台改版」上报；
 * 2. 只把「意料之外」映到 `PARSE_BROKEN`（`report = true`，叫醒我们）；
 *    `IOException` 的细分（TLS 前缀等）**只在 [com.livehub.tv.core.net.Net] 里判一次**，
 *    这里不重复一份——两份映射就是两个真相；
 * 3. [SiteException] 原样返回，不包一层：诊断页要看到最初那个 url 和 detail。
 */
fun Throwable.toSiteException(url: String? = null): SiteException = when (this) {
    is SiteException -> this
    else -> SiteException(
        SiteError.PARSE_BROKEN, url,
        "未分类异常 ${javaClass.simpleName}: ${message.orEmpty()}".trim(), this
    )
}
