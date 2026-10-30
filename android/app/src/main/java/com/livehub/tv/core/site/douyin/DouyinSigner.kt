package com.livehub.tv.core.site.douyin

/*
 * 抖音签名器 —— 骨架，只有注释，无实现（首版不启用）。
 *
 * 为什么这个文件现在是空的：
 *   抖音的列表 / 分区 / 搜索接口要求 a_bogus（新版）或 X-Bogus（老版）query 参数，
 *   签名算法在 webmssdk.js 里，未签名时服务端返回 2483「请先登录」或 444 Access Denied，
 *   HTTP 状态仍是 200 —— 只看状态码会被骗。首版 DouyinSite 的 recommend/categories/
 *   categoryRooms/search 因此显式返回空列表（抖音详情与取流走免签 reflow，不受影响）。
 *
 * 兜底落点（v1，见 android/docs/架构.md §4.2）：
 *   用系统 WebView 载入 webmssdk.js，注入点 get_sign(md5)：入参是「待签串的 MD5」，
 *   出参是 X-Bogus 字符串，直接作为 query 参数拼到请求上；需要签的三个接口是
 *   live.douyin.com/webcast/room/web/enter、aweme/v1/web/live/search、直播间 feed。
 *   WebView 方案零体积、零 so、不用分 ABI。
 *
 * 接口形状（等 core/site/Signer.kt 的契约落地后，取消注释并与之一一对应；
 * 现在不写具名类型，避免与正在设计的 Signer 契约重复声明而编译冲突）：
 *   sign(input: String): SignOut
 *   SignOut(query: Map<String, String>, headers: Map<String, String>)  // 只产出参数，不产出 URL
 *
 * 分层硬约束（验收口径写进 docs/04 §4.2）：
 *   1. Signer 契约是纯函数：不碰网络、不碰文件、不碰 Context。
 *   2. WebView 需要 Context 与主线程 —— 这个妥协只能关在 core/site 的 android 源集里，
 *      由内部 MainThreadExecutor 适配器消化，对外只暴露 sign(input)。
 *   3. core/model 与 Signer 契约绝不允许出现 Context；一旦 Context 顺着这层漏进
 *      core/model，说明妥协已失守，必须立刻改回进程内实现。
 *
 * v2 换 QuickJS 时只换内部引擎（引 C 库 / 编 so / ABI 分包 / JNI 沙箱），对外接口不动。
 */
