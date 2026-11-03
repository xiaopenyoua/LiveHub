import 'package:flutter/material.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:get/get.dart';
import 'package:livehub_ui/livehub_ui.dart';

class AppColors {
  /// 保留原成员名：旧代码通过它取 ColorScheme。
  /// 实际主题由 [AppStyle.lightTheme] 统一给出（LiveHub 深色品牌档）。
  static ColorScheme lightColorScheme = ColorScheme.fromSeed(
    seedColor: LiveHubPalette.dark.glow,
    brightness: Brightness.dark,
    surface: LiveHubPalette.dark.ground,
  );
}

/// TV 端样式入口。
///
/// 所有公开成员名保持不变，内部数值统一从 `livehub_ui` 的令牌换算。
/// TV 只用深色品牌档（设计规范只定义了深色），画布为 1920×1080，
/// 因此设计像素直接乘 `1.w`（即 `.w`）。
class AppStyle {
  static ThemeData lightTheme = liveHubTheme(
    brightness: Brightness.dark,
    ramp: LiveHubTextRamp.tv,
    unit: 1.w,
  ).copyWith(
    cardColor: LiveHubPalette.dark.raised,
    canvasColor: LiveHubPalette.dark.ground,
    scaffoldBackgroundColor: Colors.transparent,
  );

  static const LiveHubPalette _palette = LiveHubPalette.dark;

  static TextStyle _tv(
    double size, {
    FontWeight weight = FontWeight.w400,
    double height = 1.5,
    Color? color,
    double letterSpacing = 0,
  }) {
    return TextStyle(
      fontSize: size.w,
      fontWeight: weight,
      height: height,
      letterSpacing: letterSpacing,
      color: color ?? _palette.text,
      fontFamily: 'packages/livehub_ui/Inter',
      fontFamilyFallback: const [
        'PingFang SC',
        'Noto Sans CJK SC',
        'Microsoft YaHei',
      ],
      fontFeatures: const [FontFeature.tabularFigures()],
    );
  }

  static SizedBox get vGap4 => SizedBox(
        height: LiveHubSpacing.xxs.w,
      );
  static SizedBox get vGap8 => SizedBox(
        height: LiveHubSpacing.xs.w,
      );
  static SizedBox get vGap12 => SizedBox(
        height: LiveHubSpacing.sm.w,
      );
  static SizedBox get vGap16 => SizedBox(
        height: LiveHubSpacing.md.w,
      );
  static SizedBox get vGap24 => SizedBox(
        height: LiveHubSpacing.xl.w,
      );
  static SizedBox get vGap32 => SizedBox(
        height: LiveHubSpacing.xxl.w,
      );
  static SizedBox get vGap40 => SizedBox(
        height: 40.w,
      );
  static SizedBox get vGap48 => SizedBox(
        height: LiveHubSpacing.xxxl.w,
      );
  static SizedBox get hGap4 => SizedBox(
        width: LiveHubSpacing.xxs.w,
      );
  static SizedBox get hGap8 => SizedBox(
        width: LiveHubSpacing.xs.w,
      );
  static SizedBox get hGap12 => SizedBox(
        width: LiveHubSpacing.sm.w,
      );
  static SizedBox get hGap16 => SizedBox(
        width: LiveHubSpacing.md.w,
      );
  static SizedBox get hGap20 => SizedBox(
        width: LiveHubSpacing.lg.w,
      );
  static SizedBox get hGap24 => SizedBox(
        width: LiveHubSpacing.xl.w,
      );
  static SizedBox get hGap32 => SizedBox(
        width: LiveHubSpacing.xxl.w,
      );
  static SizedBox get hGap40 => SizedBox(
        width: 40.w,
      );
  static SizedBox get hGap48 => SizedBox(
        width: LiveHubSpacing.xxxl.w,
      );

  static EdgeInsets get edgeInsetsH4 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.xxs.w);
  static EdgeInsets get edgeInsetsH8 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.xs.w);
  static EdgeInsets get edgeInsetsH12 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.sm.w);
  static EdgeInsets get edgeInsetsH16 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.md.w);
  static EdgeInsets get edgeInsetsH20 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.lg.w);
  static EdgeInsets get edgeInsetsH24 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.xl.w);
  static EdgeInsets get edgeInsetsH32 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.xxl.w);
  static EdgeInsets get edgeInsetsH40 =>
      EdgeInsets.symmetric(horizontal: 40.w);
  static EdgeInsets get edgeInsetsH48 =>
      EdgeInsets.symmetric(horizontal: LiveHubSpacing.xxxl.w);

  static EdgeInsets get edgeInsetsV4 =>
      EdgeInsets.symmetric(vertical: LiveHubSpacing.xxs.w);
  static EdgeInsets get edgeInsetsV8 =>
      EdgeInsets.symmetric(vertical: LiveHubSpacing.xs.w);
  static EdgeInsets get edgeInsetsV12 =>
      EdgeInsets.symmetric(vertical: LiveHubSpacing.sm.w);
  static EdgeInsets get edgeInsetsV24 =>
      EdgeInsets.symmetric(vertical: LiveHubSpacing.xl.w);
  static EdgeInsets get edgeInsetsV32 =>
      EdgeInsets.symmetric(vertical: LiveHubSpacing.xxl.w);
  static EdgeInsets get edgeInsetsV40 => EdgeInsets.symmetric(vertical: 40.w);
  static EdgeInsets get edgeInsetsV48 =>
      EdgeInsets.symmetric(vertical: LiveHubSpacing.xxxl.w);

  static EdgeInsets get edgeInsetsA4 => EdgeInsets.all(LiveHubSpacing.xxs.w);
  static EdgeInsets get edgeInsetsA8 => EdgeInsets.all(LiveHubSpacing.xs.w);
  static EdgeInsets get edgeInsetsA12 => EdgeInsets.all(LiveHubSpacing.sm.w);
  static EdgeInsets get edgeInsetsA16 => EdgeInsets.all(LiveHubSpacing.md.w);
  static EdgeInsets get edgeInsetsA20 => EdgeInsets.all(LiveHubSpacing.lg.w);
  static EdgeInsets get edgeInsetsA24 => EdgeInsets.all(LiveHubSpacing.xl.w);
  static EdgeInsets get edgeInsetsA32 => EdgeInsets.all(LiveHubSpacing.xxl.w);
  static EdgeInsets get edgeInsetsA40 => EdgeInsets.all(40.w);
  static EdgeInsets get edgeInsetsA48 => EdgeInsets.all(LiveHubSpacing.xxxl.w);

  static EdgeInsets get edgeInsetsR4 => EdgeInsets.only(right: 4.w);
  static EdgeInsets get edgeInsetsR8 => EdgeInsets.only(right: 8.w);
  static EdgeInsets get edgeInsetsR12 => EdgeInsets.only(right: 12.w);
  static EdgeInsets get edgeInsetsR16 => EdgeInsets.only(right: 16.w);
  static EdgeInsets get edgeInsetsR20 => EdgeInsets.only(right: 20.w);
  static EdgeInsets get edgeInsetsR24 => EdgeInsets.only(right: 24.w);

  static EdgeInsets get edgeInsetsL4 => EdgeInsets.only(left: 4.w);
  static EdgeInsets get edgeInsetsL8 => EdgeInsets.only(left: 8.w);
  static EdgeInsets get edgeInsetsL12 => EdgeInsets.only(left: 12.w);
  static EdgeInsets get edgeInsetsL16 => EdgeInsets.only(left: 16.w);
  static EdgeInsets get edgeInsetsL20 => EdgeInsets.only(left: 20.w);
  static EdgeInsets get edgeInsetsL24 => EdgeInsets.only(left: 24.w);

  static EdgeInsets get edgeInsetsT4 => EdgeInsets.only(top: 4.w);
  static EdgeInsets get edgeInsetsT8 => EdgeInsets.only(top: 8.w);
  static EdgeInsets get edgeInsetsT12 => EdgeInsets.only(top: 12.w);
  static EdgeInsets get edgeInsetsT24 => EdgeInsets.only(top: 24.w);

  static EdgeInsets get edgeInsetsB4 => EdgeInsets.only(bottom: 4.w);
  static EdgeInsets get edgeInsetsB8 => EdgeInsets.only(bottom: 8.w);
  static EdgeInsets get edgeInsetsB12 => EdgeInsets.only(bottom: 12.w);
  static EdgeInsets get edgeInsetsB24 => EdgeInsets.only(bottom: 24.w);

  static BorderRadius get radius4 => BorderRadius.circular(4.w);
  static BorderRadius get radius8 => BorderRadius.circular(8.w);
  static BorderRadius get radius12 =>
      BorderRadius.circular(LiveHubRadii.listItem.w);
  static BorderRadius get radius16 =>
      BorderRadius.circular(LiveHubRadii.card.w);
  static BorderRadius get radius24 => BorderRadius.circular(24.w);
  static BorderRadius get radius32 =>
      BorderRadius.circular(LiveHubRadii.pill.w);
  static BorderRadius get radius48 =>
      BorderRadius.circular(LiveHubRadii.pill.w);

  static const colorBlack33 = Color(0xff333333);

  /// 顶部状态栏的高度
  static double get statusBarHeight => MediaQuery.of(Get.context!).padding.top;

  /// 底部导航条的高度
  static double get bottomBarHeight =>
      MediaQuery.of(Get.context!).padding.bottom;

  /// 界面标题（§3 `--fs-h1`）
  static TextStyle get titleStyleWhite => _tv(
        LiveHubTextRamp.tv.h1,
        weight: FontWeight.w600,
        height: 1.15,
      );
  static TextStyle get titleStyleBlack => _tv(
        LiveHubTextRamp.tv.h1,
        weight: FontWeight.w600,
        height: 1.15,
      );

  /// 正文（§3 `--fs-body`）
  static TextStyle get textStyleWhite => _tv(LiveHubTextRamp.tv.body);
  static TextStyle get textStyleBlack => _tv(
        LiveHubTextRamp.tv.body,
        color: _palette.muted,
      );

  /// 元信息（§3 `--fs-meta`）
  static TextStyle get subTextStyleWhite => _tv(
        LiveHubTextRamp.tv.meta,
        height: 1.4,
        color: _palette.faint,
      );
  static TextStyle get subTextStyleBlack => _tv(
        LiveHubTextRamp.tv.meta,
        height: 1.4,
        color: _palette.muted,
      );

  /// 焦点柔落影：只给焦点态（§6.1）。
  static List<BoxShadow> get highlightShadow => [
        BoxShadow(
          color: Colors.black.withValues(alpha: 0.72),
          offset: Offset(0, 22.w),
          blurRadius: 48.w,
          spreadRadius: -22.w,
        )
      ];
}
