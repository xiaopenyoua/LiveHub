# livehub_core

项目核心库，实现获取各个网站的信息及弹幕。

## 抖音直播搜索（`DouyinSite.searchRooms` / `searchAnchors`）

抖音官方的关键词搜索接口 `https://www.douyin.com/aweme/v1/web/live/search/`
是**登录墙**：匿名请求固定返回
`{"status_code":2483,"status_msg":"请先登录，再继续搜索吧"}`。
参考实现 [junlive](https://github.com/xinyuzjj/junlive) 因此在
`src-tauri/src/platforms/douyin.rs` 里把抖音搜索改成「只按直播间号」；
[DTV](https://github.com/chen-zeong/DTV) 的 `searchPlatform` 对 douyin 直接 `return null`。

本库不放弃关键词搜索，按四层兜底（任何一层命中就返回）：

| 顺序 | 手段 | 能覆盖什么 |
| --- | --- | --- |
| 1 | 用户自填抖音 Cookie 时走官方 `/aweme/v1/web/live/search/` | 完整关键词搜索 |
| 2 | `webcast/room/web/enter` 的 `web_rid` 直连：数字直播间号**或字母数字抖音号** | 已知抖音号/房间号，未开播也能拿到主播 |
| 3 | 公开网页索引兜底：`site:live.douyin.com <关键词>`（Bing / DuckDuckGo）取候选房间号，再用抖音接口校验昵称 | 未开播的主播昵称 |
| 4 | 实时直播索引：`webcast/web/partition/detail/room/v2` + `webcast/feed` 抓「正在直播」的房间，按昵称/标题包含匹配 | 正在直播的主播昵称 |

几个容易踩的坑（都已按实测处理）：

- `webcast/room/web/enter` 的 `web_rid` **只吃数字房间号或纯 ASCII 抖音号**；
  中文昵称直接返回 `status_code=10011`，不能把「女流66」剥成「66」当房间号。
- 分区/推荐流接口里的 `room.status` 是 reflow 对象的状态位（在播时为 `0`，不是 enter 接口的 `2`），
  只有 `4` 才表示已结束；按 `status == 2` 判断会把所有在播房间判成未开播。
- 未开播时 enter 接口仍会返回 `data.user`（昵称/头像），所以「未开播」也能正常展示与进房。
- 命中过的「关键词 → 直播间」在本次进程内缓存（`_learnedHits`），再次搜索未开播的主播也能命中。

实测（网络用例：`LIVEHUB_NETWORK_TEST=1 ~/flutter-3.38/bin/dart test test/douyin_search_network_test.dart`）：

| 关键词 | 结果 | 来源 |
| --- | --- | --- |
| `yjjimpaopao` | `359765653648`，在播 | 第 4 层（实时直播索引） |
| `女流66` | `531854687617`，未开播 | 第 3 层（公开网页索引兜底） |
| `nvliu66` | 抖音号直连，未开播也能解析出主播 | 第 2 层 |
