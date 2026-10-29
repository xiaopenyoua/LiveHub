# LiveHub

> 四个平台，一台电视。像换台一样换主播。

聚合哔哩哔哩、斗鱼、虎牙、抖音四家直播的 Android TV 客户端。当前阶段**只做界面设计，未写 Android 工程代码**——本仓库交付的是可交互的 1920×1080 界面原型、设计规范与市场结论。

## 为什么做这个

虎牙 TV 版 2026 年 8 月停服，斗鱼 TV 版已下架，B 站 TV 端以点播为主。四家平台都在往手机和网页收，**没有一个产品是为"沙发上的人 + 一个方向键"设计的四平台直播客户端**。详见 [`docs/01-市场调研.md`](docs/01-市场调研.md)。

产品的三根柱子：

1. **换台优先** —— 播放页 ↑↓ 直接换下一个直播间，不用退回列表。
2. **台号寻址** —— 每个直播间有一个永久三位台号，哔哩 100 段、斗鱼 200 段、虎牙 300 段、抖音 400 段。数字键三位直达。
3. **状态诚实** —— 未开播就说未开播，降级就说降到 720P，不装。

## 目录

```
design/
  index.html          六屏可交互原型（单文件，无构建）
  screenshots/        八张 1920×1080 定稿截图
  .selftest.mjs       遥控器键位链路自测（CDP 驱动真实浏览器）
  .shots.mjs          批量重截截图
  _canvas.html        inspect 闸门的入口（跳转到 ?canvas=1）
docs/
  01-市场调研.md      市场盘子、政策与牌照、竞品格局、痛点、产品定义
  02-设计规范.md      台号体系、色彩、字体、焦点、栅格、键位、信息架构、状态
assets/
  covers/             八张 1280×720 直播封面（AI 生成后统一处理）
  fonts/              Inter 可变字体（自托管）
```

## 看原型

直接用浏览器打开 `design/index.html`。

| 参数 | 作用 |
| --- | --- |
| 默认 | 带设计稿外壳，顶部可切六屏，底部显示键位提示 |
| `?canvas=1` | 剥掉外壳，纯净 1920×1080 TV 画布（出图用） |
| `?canvas=1&screen=player&osd=1&no=301` | 直接落到某屏 / 某状态 / 某台号 |

**键位**：↑↓←→ 移动焦点；OK 确认；返回上一级（最高级回首页，首页再按弹二次确认）；数字键三位直达台号；播放页 ↑↓ 换台、←→ 换平台。

## 六屏

| | |
| --- | --- |
| 首页 | `screenshots/01-home.png` |
| 分类 | `screenshots/02-browse.png` |
| 关注 | `screenshots/03-follow.png` |
| 搜索 | `screenshots/04-search.png` |
| 播放 | `screenshots/05-player.png` |
| 设置 | `screenshots/06-settings.png` |
| 换台条 | `screenshots/07-channel-osd.png` |
| 退出确认 | `screenshots/08-exit-dialog.png` |

## 焦点：不放边框

对齐 tvOS：**焦点不画环**，靠放大 1.08 + 封面提亮 0.86→1.00 + 标题转白 + 底色抬一档来表达。真黑底上投影看不见，所以"浮起"交给 scale，"选中"交给明度。加了白环会把界面切成一格一格的表格，也压过封面本身。

代价是网格必须留够放大的余量：卡片行间距 ≥ 36px，分组标题下沿距网格 ≥ 34px。

## 质检

```bash
# 静态规则（禁用模式、渐变、阴影、默认字体…）
node ~/.workbuddy/skills/design-taste-frontend/scripts/lint.mjs design/index.html
# → 0 FAIL

# 渲染后测量（对比度、裁切、密度、字号、色彩占比）
node ~/.workbuddy/skills/design-taste-frontend/scripts/inspect.mjs \
  design/_canvas.html --out .taste-skill/app/inspect --widths 1920 --allow edge-text
# → 0 FAIL

# 遥控器真实可操作性 + 焦点困住检测
node design/.selftest.mjs
# → 12 步链路全通，焦点原地不动 6/40（均为边界合理行为）

# 重新出图
node design/.shots.mjs
```

两个允许项及其理由：

- `edge-text` —— 首页推荐流是横向滚动行，最后一张卡被视口裁切是"还有更多"的提示，不是布局碰撞。
- `app-title-size`（WARN）—— 主图标题 76px 是按 3 米观看距离定的；桌面 app 的 18–32px 不适用于 10-foot UI。

## 尚未做

- Android 工程与播放器（ExoPlayer / IJK）、各平台取流、弹幕协议对接
- 账号扫码登录与关注/台号云同步
- 真实接口数据（当前是 24 条手写的演示数据）

## 参考

- 斗鱼直播流获取参考了 [@wbt5/real-url](https://github.com/wbt5/real-url)
- 抖音弹幕参考了 [@saermart/DouyinLiveWebFetcher](https://github.com/saermart/DouyinLiveWebFetcher)
- 虎牙参考了 https://github.com/liuchuancong/pure_live https://github.com/ihmily/DouyinLiveRecorder
- b站弹幕参考了 https://github.com/xfgryujk/blivedm
