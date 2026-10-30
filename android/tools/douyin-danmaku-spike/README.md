# 抖音弹幕签名 spike（Milestone 1）

**这个目录不是产品代码，是一次性探针。** 它回答一个问题：抖音弹幕的签名能不能在我们的技术栈里
算出来、并且换到 WebSocket 的 `HTTP 101`。

之前的结论是「抖音弹幕做不了，因为签名要跑 `webmssdk.js` 里混淆过的 `frontierSign`，没有离线算法」。
那个结论**在原因上是错的**——签名是 `sign.js` 的 `get_sign(md5)`，能跑出来。
DTV Mobile 是 Kotlin Multiplatform 项目、抖音弹幕有完整实现，这个 spike 就是把它拆小、单独验证一遍。

**但这次验证推翻了这个模板的一半**：DTV 选的 JS 引擎（Rhino）**签出来的签名抖音不认**（HTTP 200），
换成真 V8 才拿到 101。所以 DTV 可照抄的是**协议**（参数集、protobuf 字段号、ack 顺序），
**不是引擎选择**——它那套在我们要用的这条路上跑不通，证据见下面「结论」。

## 跟 `danmaku-reconnect-test` 的关键区别

那边要拷 `app/src` 的 `core/{danmaku,net,model}` 源码副本再打补丁；
**这边一行生产源码都不读**。原因是 backend 同时在改 `core/net`、`core/site`、`core/model`，
拷源码会让这个 spike 被别人的重构卡住——而它要验的东西跟那三个目录无关。

依赖只有三样，全部来自 Gradle 缓存 + 一次下载：

| 依赖 | 来源 | 为什么 |
|---|---|---|
| kotlin-stdlib / okhttp / okio | `~/.gradle/caches/modules-2/files-2.1` | 与 app 同版本，避免「本地过、真机不过」 |
| `org.mozilla:rhino:1.7.14` | Maven Central，下载一次缓存到 `build/lib/` | **纯 JVM，无 native、无 ABI**。它在这里只承担一个角色：**对照组**——用同一份 sign.js 签出签名，证明「引擎不同 → 抖音认不认」这件事。**它不是生产引擎**（见下面「结论」） |

`run.sh` 会校验 Rhino jar 的 sha256。**换了 jar 就等于换了签名引擎**，必须重新验一遍。

## 怎么跑

```bash
cd android/tools/douyin-danmaku-spike
./run.sh                                       # 用下面记的那间房
./run.sh <web_rid> <room_id> [uid]             # 换房间（房间下播了就换）
./run.sh --scan <room_id,room_id,...> [web_rid] # 一次编译扫多间，只找「有活人说话」的那间
```

退出码 0 = 三步全过。输出是一份带断言的分步报告。

**最近一次全绿（2026-10-29 18:10 + 18:15，两次都 exit 0、0 条失败）**：

```
./run.sh 549491943275 7702027876891101995      # 小辣椒🌶️乡村大舞台（才艺直播，真人在台上说话）
→ ① Rhino 265ms/51ms 出 16 位；node 123ms 出 16 位
→ ② Rhino 6 种组合全 200；node 第一发 101（server=volc-dcdn upgrade=websocket）
→ ③ 48 帧 / 46 msg / gunzip 失败 0 / ack 46；16 种 method，WebcastChatMessage×3
      第 1 条：如初见～：家人们左上角点点关注加个粉丝灯牌[比心][比心][比心]

第二次（同一条命令、同一间房）：60 帧 / 57 msg / ack 57 / 16 种 method / Chat×3
      第 1 条：布丁：这么嗨的吗[捂脸]
      第 2 条：军歌嘹亮：[赞][赞][赞]
      第 3 条：一切随缘：[赞]
```

## 结论（2026-10-29 实测，三步全过）

```
❌ 200  Rhino 1.7.14（纯 JVM）        →  6M18xCpcliuf5lev
✅ 101  node / V8（WebView 替身）      →  fKATNAlzKRoSLCNg
```

**同一条 md5、同一份参数、同一台机器、同一个房间，只换 JS 引擎，200 和 101 就分开了。**
这是这个 spike 最值钱的一行，它否掉的是「照 DTV 用 Rhino 就能在纯 JVM 里签出可用 signature」这条路线：

- 三个 WS 域名 × 签名「编码/原样」= 6 种组合，Rhino 的签名**全是 200**（`server=volc-dcdn` / `Tengine`，body 空）。
- V8 的签名**第一发就 101**（`server=volc-dcdn upgrade=websocket`）。
- 排除法做过两次：给 node 补上与 Kotlin `JsEngine.bootstrap()` 完全一致的浏览器 shim（document/location/
  localStorage/screen/setTimeout/console），仍然 101 → **不是 shim 缺东西**；把 Rhino 的 signature 丢给
  **另一份独立实现**的握手探针，还是 200 → **不是参数、不是 URL 编码、不是我拼错**。

第二条排除法用的探针不该只活在 `/tmp` 里（证据会跟着临时目录一起消失），所以全文抄在这，
存成任意 `probe.js` 即可复跑：**入参就是别人算好的 signature**，它不签名、不读 sign.js，
只做「给定参数集 + 给定 signature → 握手看状态码」这一件事。

```bash
node probe.js <room_id> <uid> <signature>      # 退出码 0 = 101
```

这段片段当天就用上面那两个签名验过（同一房间 `<room_id>=7702027876891101995`、同一 uid）：

```
$ node probe.js 7702027876891101995 7702031751300073003 6M18xCpcliuf5lev   # Rhino 的
=> HTTP 200 volc-dcdn        exit=1
$ node probe.js 7702027876891101995 7702031751300073003 fKATNAlzKRoSLCNg   # V8 的
=> UPGRADE 101               exit=0
```

顺带说明两件事：**signature 不是一次性的**（这两个签名是十分钟前算的，照样能用/照样被拒）；
**它也不是时间窗问题**——两个签名同时有效、同时发出，一个 200 一个 101，区别只在谁签的。

```js
const crypto = require('crypto'), https = require('https');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const [roomId, uid, sig] = process.argv.slice(2);
https.get('https://live.douyin.com/', { headers: { 'User-Agent': UA } }, (r) => {
  r.resume();
  const cookie = [...new Set((r.headers['set-cookie'] || []).map((c) => c.split(';')[0]))].join('; ');
  const enc = encodeURIComponent;
  const q = [['app_name','douyin_web'],['version_code','180800'],['webcast_sdk_version','1.3.0'],
    ['update_version_code','1.3.0'],['compress','gzip'],['cursor',`h-1_t-${Date.now()}_r-1_d-1_u-1`],
    ['host','https://live.douyin.com'],['aid','6383'],['live_id','1'],['did_rule','3'],['debug','false'],
    ['maxCacheMessageNumber','20'],['endpoint','live_pc'],['support_wrds','1'],['im_path','/webcast/im/fetch/'],
    ['user_unique_id',uid],['device_platform','web'],['identity','audience'],['room_id',roomId],['heartbeatDuration','0']]
    .map(([k, v]) => `${k}=${enc(v)}`).join('&');
  const req = https.request({ host: 'webcast5-ws-web-hl.douyin.com', method: 'GET',
    path: `/webcast/im/push/v2/?${q}&signature=${enc(sig)}`,
    headers: { 'User-Agent': UA, Cookie: cookie, Origin: 'https://live.douyin.com', Referer: 'https://live.douyin.com/',
      Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13',
      'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64') } });
  req.on('upgrade', (res) => { console.log('=> UPGRADE', res.statusCode); res.destroy(); process.exit(0); });
  req.on('response', (res) => { console.log('=> HTTP', res.statusCode, res.headers.server); res.resume(); process.exit(1); });
  req.on('error', (e) => { console.log('=> ERR', e.message); process.exit(2); });
  req.end();
});
```


所以生产端抖音弹幕只能跑在 **WebView（V8）** 里，`evaluateJavascript` 调 `get_sign`。
Rhino 那条「纯 JVM、APK 不涨」的路**日志齐了但结论是否**，别再照 DTV 的桌面路径规划。

## 房间上下文：两条路，日志里会写用了哪条

| 路 | 怎么拿 | 什么时候用 |
|---|---|---|
| 房间页 | GET `live.douyin.com/{web_rid}`，取 `user_unique_id` + `Set-Cookie` | 正常情况 |
| 首页 + 自造 uid | GET `live.douyin.com/`（取 `ttwid` 等 cookie）+ **随机 19 位 uid** | 房间页被拦时 |

**房间页会被限流**：同一 IP 短时间内多抓几次，返回的就不再是房间页，而是一个 **6KB 的「验证码中间页」**
（`<title>验证码中间页</title>`），既没有 uid 也没有 cookie。这次扫分类页时踩到过。
它不是代码坏了，重试也没用——换首页那条路即可。

**`user_unique_id` 不需要来自页面**：实测随机生成的 19 位数字照样 101。它只是拼进 md5 和 query，两边一致就行。
生产上就别为这一个字段去抓房间页（省掉一次会被限流的请求，也省掉一个会腐烂的正则）。


## 三步验什么

| 步 | 断言 | 为什么这条值钱 |
|---|---|---|
| ① 引擎求值 | `sign.js` 能算出非空 `signature`，且**复用作用域后**的耗时远低于首次（Rhino 265ms → 51ms；node 每进程 123ms，生产上是常驻 WebView 无此开销） | 首次要解析 485KB 混淆代码；如果每次签名都得重解析，重连一次卡 1~3 秒，等于不能用 |
| ② 握手 | 拿这个 signature 打真房间，**HTTP 101** | 这是决定性判据。之前 `signature=""` 和不带 signature 都回 `200`，说明抖音在握手阶段就拒了，签名是硬门槛 |
| ③ 解包 | 帧 → gunzip → protobuf → 至少 1 条 `WebcastChatMessage`，昵称/内容含中文且不乱码 | 「握手 101」只证明门开了；能解出一条真弹幕才证明整条链（解包、gzip、字段号）都对 |

## 房间下播了怎么办（这是最常见的运行失败）

**先别加超时。** 空房间里等再久也只有心跳和 `RoomDataSync`，不是等得不够久。

```bash
# 1. 从分类页刮一批 room_id（下面那个锚点法），2. 一次编译扫一遍，3. 用赢的那间跑正式三步
./run.sh --scan 7702027876891101995,7702028086896151334,7702028086778366758
./run.sh 549491943275 7702027876891101995
```

找 room_id 的正确姿势（2026-10-29 实测，之前的写法已经失效）：

1. 分类页 `https://live.douyin.com/category/<id>` 仍是 SSR，房间对象完整，但
   **`"roomId":"…","web_rid":"…"` 在分类页里不相邻**——按相邻配对搜，永远是 0 命中。
2. 正确锚点是 `"user_count_str":"N"`：以它为位置，**向前 40KB 内取最后一个 `"id_str":"19位"`**（房间自己的 id），
   **向后 5KB 内取 `"nickname"`**（主播昵称）。
3. `web_rid` 不用再去房间页找：reflow 里的 **`data.room.owner.web_rid`** 就是它。
4. 确认在播：`https://webcast.amemv.com/webcast/room/reflow/info/?type_id=0&live_id=1&room_id=<19位>&app_id=1128`
   （**必须带 `Referer: https://live.douyin.com/`**），看 `data.room.status == 2`。

**`user_count` 不能当「有没有弹幕」的判据**：分类页里大量房间是门店/循环播放的无人值守直播，
几百「人看过」也可能一条弹幕都没有。按人数排序只能当候选顺序，最后还是要 `--scan` 实测。
这次能跑通的那间是「小辣椒乡村大舞台」这类**真人在台上说话**的房间。

## 两个 Rhino 坑（照 DTV 的做法处理，不是我们发明的）

1. **`let` 不支持**：`sign.js` 只有 wrapper 用到 `let`，整份替换成 `var`。
2. **块内函数声明不提升**：`sign.js` 里 `function w_0x42f5(){…}` 声明在块尾、却在块首被调用。
   浏览器走 Annex-B 兼容提升，Rhino 会 `ReferenceError`。做法是把这个函数整段挪到块首。

这两条都是**针对这一份 485KB 文件**的补丁。换了 JS 文件，`Js.prepare()` 里那条
「块内函数前移：**未生效**」的提示会打在流水里——看到它就别往下信签名结果。

> 补上之后 Rhino 确实能跑、能出 16 位 signature（首次 265ms，复用 51ms），
> **但抖音不认**（见上面「结论」）。保留这套补丁不是为了用 Rhino，是为了让
> 「200 和 101 的差异在引擎上」这个对照可复现——删了它，对照就没了。

## 它验不了什么

- **长时间稳定性**：spike 只连一次、收几条弹幕就退出。心跳 10s、ack 应答、断线重连
  （`core/danmaku` 那套 `attempt`/`backoff`）都不在这里验。
- **低端盒子上的耗时**：这里量的是桌面 JVM。真机上 Rhino 会慢一截，所以「首次求值耗时」
  只能当量级参考，不能当预算结论。
- **每一条消息类型的字段号**：只解了 `ChatMessage`。礼物/进场/点赞的字段号没核对过，
  照抄 DTV 的结构不代表那些也对。
- **弹幕去重**：第一次跑出的 3 条文本完全相同（同一个人刷同一句），第二次跑出的是 3 个不同用户
  的 3 条不同内容——所以**大概率是刷屏，不是重发**。但仍然没排除「ack 之后补发同一批」：
  两次样本都不够长。v2 上线前要拿一段更长的样本确认 `Response.messages` 里会不会出现已消费过的
  `ChatMessage`；如果会，就得按 `msgId` 去重，否则弹幕区会把一句话显示三遍。

## 出处与许可

- `src/spike/JsEngine.kt` 的 Rhino 补丁逻辑、`src/spike/Proto.kt` 的解码结构：
  **搬自 DTV Mobile**（`shared/src/androidMain/kotlin/dtv/mobile/platform/douyin/`），
  Copyright 2025 c.zeong，MIT License。
- `assets/sign.js`、`assets/webmssdk.js`：抖音自己的混淆产物，从 DTV 的资源目录取得，
  **不是 DTV 的代码**，未做任何改写（改了就换语义）。指纹：

  ```
  81fb78a5e8728af99ffb9d20625b01bcedc43a82b05e3bef79e2bc0d29ccff5d  sign.js      (485197B)
  acd5e9aec3a1c99b066df9767171a5c7b6534a545d8c80488f28297cbdf93d74  webmssdk.js  (625814B)
  ```
- `pure_live` 是 AGPL-3.0，**这个 spike 一行都没引用它**。它只用来理解协议行为
  （参数集、`/webcast/im/fetch/` 路径、ack 顺序），代码全部来自 MIT 的 DTV 或自己写。

> **进生产前必须做的事**：把上面「搬自 DTV」的那段版权声明原样带进 `app/src`，
> 并在仓库根的 `NOTICE` 里注明来源。这一条不做，「照搬 MIT 代码」就变成侵权。
