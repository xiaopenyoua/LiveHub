# livehub_console

基于 `livehub_core` 的命令行工具，用于直接探测直播间信息、播放直链与弹幕。

## 用法

```bash
cd app/livehub_console
dart pub get

# 获取直播间信息及播放直链
dart run bin/livehub_console.dart -i "[直播间地址]"

# 持续输出直播间弹幕
dart run bin/livehub_console.dart -d "[直播间地址]"
```

支持哔哩哔哩、斗鱼、虎牙、抖音四家直播间的地址。

包名与产品名均为 `livehub_console` / LiveHub。

## 测试

```bash
dart test
```
