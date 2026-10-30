package com.livehub.tv.core.danmaku

import com.livehub.tv.core.model.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 抖音弹幕 —— **首版未接入，诚实失败**。
 *
 * ## 门槛在哪（2026-10-29 已实测到「一条真弹幕」，不再是推测）
 * 抖音弹幕真正的门槛不在 protobuf，而在**签名**：握手 URL 上的 `signature` 是
 * 13 个固定顺序的 key 拼成 `k=v,` → **md5 hex** → 把 md5 喂给 `sign.js`（485KB 混淆产物）里的
 * **`get_sign(md5)`** → 得到 16 位字符串。没有离线算法，只能跑那段 JS。
 * 完整的三步验证在 `android/tools/douyin-danmaku-spike/`（独立 spike，不读 `app/src`）：
 * ① 签出非空 signature ② 真房间换到 **HTTP 101** ③ gunzip + protobuf 解出真弹幕。
 *
 * **生产端引擎已定：必须 WebView（V8）。** 同一个 md5、同一份参数、同一台机器，只换 JS 引擎：
 * Rhino 1.7.14 签出来的 signature 打三个域名 × 编码/原样 = 6 种组合全是 `200`；
 * V8 签的**第一发就 101**（`server=volc-dcdn upgrade=websocket`）。给 node 补上和
 * `JsEngine.bootstrap()` 一模一样的浏览器 shim 仍然 101，把 Rhino 的签名丢给另一个独立探针
 * 也还是 200 —— 所以差异在引擎，不在参数、不在 URL 编码。
 * 「Rhino 纯 JVM、APK 不涨体积」这条路**日志齐了、结论是否**，别再按它排期。
 *
 * 也就没有「没签名先连着试试」这条退路。早期实测（`signature=""` 与**完全不带**
 * signature 参数）：服务端都返回 `HTTP/1.1 200 OK`——不是 101，握手直接失败，
 * 与房间信息能否取到无关。
 *
 * 关于房间详情接口（写在这里是为了防止后来人误读）：
 * `webcast/room/reflow/info/` 本身是**可用**的。我一开始拿到
 * `HTTP 200` + `{"data":{"message":"Request params error"}}` / `status_code 4001038`，
 * 一度以为接口挂了；实际原因是 `room_id` 必须传 **19 位**，而直播间地址里拿到的是
 * `web_rid`（实测 4–12 位都有），少了转换这一步（backend 侧实测 `web_rid=715855351148` →
 * `room_id=7701975308739611407` 之后 `status_code 0`，标题/主播/流地址全有）。
 * 所以那条 4001038 **不是接口不可用的证据，只是我自己参数错了**。
 * 唯一值得留下的教训是：**这个接口连出错也回 HTTP 200**，解析前必须判
 * `data.room` 存在，只看状态码会被骗。
 *
 * 这个 19 位 id 现在**不用自己想办法弄**：`data.room` 里自带
 * `id_str`（19 位字符串形态，与数字字段 `id` 同值），backend 已经把
 * `DouyinSite.detail()` 的 `Room.roomId` 归一成 `id_str`（和另外三家一样返回
 * 平台真实 id，哔哩短号也会被换成真实房号）。所以 [stream] 收到的 `roomId`
 * 就是 19 位，v2 直接拿去签名即可。
 * 万一将来要从分享文案（只有 `web_rid`）直接进弹幕，免签的转换路径是
 * `GET https://live.douyin.com/{web_rid}` 桌面 UA 抓页面内嵌 JSON 里的
 * `"roomId":"19位"` —— **不是** `webcast/room/web/enter`（那个要签名，
 * 硬上会撞 4001038）。
 *
 * 但**弹幕层不该自己抓这个页面**，这条已经和 backend 定死：转换是取流层
 * （`DouyinSite.detail()`）的归一化职责。理由是那页 879KB、含 78 个 19 位数字，
 * 其中 77 个不是 room_id——正则配错**不报错，只会静静连到别人的弹幕房**，
 * 比连不上难查一个数量级。所以 v2 接上后，[stream] 开头要留一道**不发任何请求**的守卫：
 * ```kotlin
 * // 房间号合法性只有一个真源：core/site 的 isDouyinRoomId。这里不写字面量 15 或 19。
 * if (!isDouyinRoomId(roomId)) {
 *     emit(DanmakuEvent.Failed("请从首页或搜索打开这个直播间"))
 *     return@flow
 * }
 * ```
 * 文案口径：**给下一步、不给内部原因**（不出现 web_rid / 未解析 / 解析失败 这类词）。
 * 阈值**不要在这里写**：`isDouyinRoomId` 是 `core/site/DouyinSite.kt` 末尾的 `internal` 顶层函数，
 * **全仓只此一份**；这里连它的判断条件都不抄——抄一句就又多一份会漂的描述。
 * 取流层「19 位直连 / 4–12 位当 web_rid / 13–18 位 `ID_UNRESOLVED`」那套三分类也走它。弹幕层只问一个问题——「这是不是 19 位真房号」。
 * 拿到 `detail()` 归一化后的 id 时它必然成立；不成立就是上游错了，一条守卫足够。
 * **这里写 `< 15` 或 `length == 19` 都会长出第二处真源**，而「两处真源」这次已经连出两次事故：
 * 一处被删掉、另一处还指着它，读的人却以为两边都在。所以那句「别只改一处」只剩提醒价值——
 * 它提醒的是**危险**，不是**解法**；解法是让它根本不再有第二处。
 * 这条 `Failed` 将来带 `kind` 时对应 `SiteError.ID_UNRESOLVED`（`retryable=false, report=false`：
 * 换输入才有用、怪输入不怪我们）。**别用 `PARSE_BROKEN`**——那个 `report=true`，
 * 会把「用户拿了个旧 id」送进最高优先告警。
 * 这两段现在只是注释、不进代码：v2 之前 `stream()` 无论如何都只发一条「暂不可用」，写成代码也是死分支。
 *
 * 所以这里选择不猜：不发假的 [DanmakuEvent.Connected]，不猜 protobuf 字段，
 * 不做重连循环（重连一万次也还是 200），直接给一条失败提示然后结束流。
 * 播放页把它渲染成一行系统提示，界面上不会出现「画面正常但弹幕区永远空白」
 * 这种让人反复去翻设置的状态。
 *
 * 另外 `LiveSite.capabilities.danmaku` 对抖音是 `false`，所以播放页现在**整个
 * 不渲染弹幕层**，这条 `Failed` 根本不会被消费——这是有意的：给用户一个点了
 * 没反应的弹幕开关，比没有这个开关更差。等签名器接通，把那个能力位翻成 `true`
 * 的同时删掉这段注释即可，`stream()` 换实现就行，上层不用改。
 *
 * ## 下一步（v2 路线，已按 spike 结果修正过一次）
 * 1. `WebView` 加载 `sign.js`（485KB，纯计算，不用打开页面、不用联网），
 *    `evaluateJavascript("get_sign('<md5>')")` 取回 16 位 signature。
 *    **不是** `webmssdk.js` 的 `frontierSign`/`X-Bogus`——那是 HTTP 接口那条线的说法，
 *    握手只用 `get_sign`。签名原文 = 13 个 key 固定顺序拼 `k=v,`（`live_id,aid,version_code,
 *    webcast_sdk_version,room_id,sub_room_id,sub_channel_id,did_rule,user_unique_id,
 *    device_platform,device_type,ac,identity`，缺的填空串**不许跳过**）；
 * 2. 拼 `wss://webcast{3,5}-ws-web-{hl,lq,lf}.douyin.com/webcast/im/push/v2/?…&signature=`
 *    （`room_id` 用入参，已是 19 位，见上面 `id_str` 那段）。`user_unique_id` **不必来自房间页**，
 *    随机 19 位照样 101（它只参与拼串，两边一致即可）；cookie 至少要带首页给的 `ttwid`；
 * 3. protobuf 手写 varint 即可（spike 里就是这么写的，没引 runtime）：
 *    `PushFrame{7=payloadType, 8=payload(gzip), 2=logId}` → gunzip →
 *    `Response{1=messages[], 5=internalExt, 9=needAck}` → `Message{1=method, 2=payload}` →
 *    `WebcastChatMessage{2=user, 3=content}`，`User{3=nickName}`。
 *    **`needAck` 必须回**：回 `PushFrame{payloadType="ack", logId=来帧的 logId, payload=internalExt}`，
 *    实测 25s 内 46 个 msg 帧全部 needAck=true，回了才持续推（心跳 10s 一次 `payloadType="hb"`）；
 * 4. 用签名版替换 [stream] 的函数体即可，类名/接口/上层调用都不用改；
 *    接通之后再让 backend 把 `LiveSite.capabilities.danmaku` 翻成 `true`
 *    （顺序不能反：翻早了用户只会看到一个点了立刻失败的开关），
 *    **并且同时删掉本段「诚实失败」的说明**——只翻能力位不改注释，
 *    下一个人会以为代码没更新。
 *
 * 还有一件 v2 必须验的事：spike 第一次收尾那 3 条弹幕文本完全相同（同一个人刷同一句），
 * 第二次是 3 个不同用户的 3 条不同内容——**大概率是刷屏，但两次样本都不够长，没排除**
 * 「ack 之后被补发了同一批 `ChatMessage`」。上线前要拿长样本确认，必要时按 `msgId` 去重，
 * 否则弹幕区会把一句话显示三遍。
 *
 * 同一次提交里还要删掉上面那段**守卫示例注释**（整段删，不是改成「已实现」）。
 * 留着它的典型结局：代码已经支持 web_rid 了，注释还在说「web_rid 要守卫」，
 * 下一个人照着注释把守卫加回来，把功能砍掉一半。**同一个事实只留一处描述。**
 *
 * ## v2 不要重复的一件事
 * 「这个房间在不在播」在本项目里有**三个来源**（`detail()` 的房间详情、弹幕层自己的通道、
 * 播放器只认的 `playUrls` 契约），它们**不是同一个真相**。表和三根规矩写在
 * `android/docs/架构.md` §3.2「三套『在播』判据不是同一个真相来源」，**这里不抄第二份**——
 * 文档会更新，注释里那份不会。
 *
 * 对弹幕层只有一条落地的约束：**不要因为 `playable == false` 就拒连弹幕**，也不要拿
 * `Room` / `roomId` 非空当「可以开弹幕」的判据。我们只关心「弹幕通道能不能连」；
 * 把「我们坏了」说成「未开播」，正是取流层刻意拆开这两件事要避免的。
 */
class DouyinDanmaku : DanmakuClient {

    override val platform = Platform.DOUYIN

    override fun stream(roomId: String): Flow<DanmakuEvent> = flow {
        // 只有一条：不建连、不重连、不假装连上。见类注释里的实测结论。
        emit(DanmakuEvent.Failed("抖音弹幕暂不可用"))
    }
}
