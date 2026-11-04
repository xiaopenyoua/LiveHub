# livehub_tv

LiveHub Android TV 客户端（Flutter，设计画布 1920×1080）。

## 启动流程

1. **系统开屏**：Android 12+ 的启动图标帧，底色 `#231C16`（取图标底，见
   `android/app/src/main/res/values/styles.xml` 与 `values/colors.xml`）。
2. **应用内开屏**：`lib/modules/splash/splash_overlay.dart`，按
   `design/v1.0.0/splash.html` 实现。
   - 字标 + 四行平台状态；行序固定 哔哩 / 斗鱼 / 虎牙 / 抖音，不按状态重排。
   - 四家并发探测 `getRecommendRooms(page: 1)`，单家 1500ms 超时，只用来给四行状态；
     开屏退场等的是**首页第一页有结论**（它盖住的正是首页的 loading）。
   - `T = max(960ms, 首页结论时刻 + 200ms)`，退场 200ms；首页第一页失败时不退出，
     整块换成「标题 + 说明 + 重试」，重试会同时重拉首页。
   - 首页异常迟迟不结论时，5s 兜底退场，交给首页自己的 loading / 错误态。
   - 开屏底 `#1A140F`；首页在背后加载，退场时直接露出。
3. **首页**（首次启动先到用户协议页）。

## 分类页

- 左栏平台顺序：斗鱼 / 哔哩哔哩 / 虎牙 / 抖音；斗鱼置顶，不再有「全部平台」。
- 右栏按页加载，滚动接近底部追加下一页；到底显示「已经到底了」。

## 目录

- `lib/modules/` 各页面控制器与视图
- `lib/widgets/` 共享控件（焦点面、卡片、设置行等）
- `lib/routes/` 路由与一级页签
- `android/` 平台资源与启动主题
