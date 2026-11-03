import 'package:flutter/material.dart';

/// LiveHub 设计令牌：炭灰底 + 中央暖金光晕 + 烟色玻璃。
///
/// 数值来自 `design/system/设计规范.md`。TV 端以 1920×1080 为设计画布，
/// 使用时把 `LiveHubTokens.unit` 设为 `1.w`；手机/桌面端 `${LiveHubTextRamp.mobile}`
/// 已经是一次换算后的逻辑像素，`unit` 保持 1。
@immutable
class LiveHubPalette {
  const LiveHubPalette({
    required this.ground,
    required this.groundDeep,
    required this.panel,
    required this.raised,
    required this.raised2,
    required this.glassTint,
    required this.wash,
    required this.text,
    required this.muted,
    required this.faint,
    required this.live,
    required this.glow,
    required this.onLive,
  });

  /// 全局底色：低饱和深炭灰。
  final Color ground;

  /// 暗角，把四角压深托住中央的光。
  final Color groundDeep;

  /// 封面未加载时的底。
  final Color panel;

  final Color raised;
  final Color raised2;

  /// 烟色玻璃底：让面比环境暗。
  final Color glassTint;

  /// 静态说明块的薄雾。
  final Color wash;

  final Color text;
  final Color muted;
  final Color faint;

  /// 唯一强调色：在播、进度、主按钮。
  final Color live;
  final Color onLive;

  /// 暖金光晕，写成实色供透明度组合。
  final Color glow;

  static const dark = LiveHubPalette(
    ground: Color(0xFF15161A),
    groundDeep: Color(0xFF0C0D10),
    panel: Color(0xFF1B1C20),
    raised: Color(0xFF232428),
    raised2: Color(0xFF2C2D32),
    glassTint: Color(0x380B0C0F),
    wash: Color(0x0EFFFFFF),
    text: Color(0xFFFFFDFA),
    muted: Color(0xFFC3C4C9),
    faint: Color(0xFF9C9DA4),
    live: Color(0xFFFF453A),
    onLive: Color(0xFFFFFDFA),
    glow: Color(0xFFD8A65C),
  );

  /// 浅色派生档。设计规范只定义了深色；浅色沿用同一套暖金语气，
  /// 供「跟随系统 / 浅色」设置使用，不另立一套品牌语言。
  static const light = LiveHubPalette(
    ground: Color(0xFFF4EFE6),
    groundDeep: Color(0xFFE4DACB),
    panel: Color(0xFFEDE6DA),
    raised: Color(0xFFE7DED0),
    raised2: Color(0xFFDED3C2),
    // 烟色玻璃底：比环境暗，浅色下才分得出「面」与「底」（规范 §5.1）。
    glassTint: Color(0x241A150F),
    wash: Color(0x66FFFFFF),
    text: Color(0xFF1A1B1F),
    muted: Color(0xFF3F4047),
    // 小字对 ground 6.3:1、对玻璃面 4.7:1，均高于正文 4.5:1。
    faint: Color(0xFF56575E),
    // 「直播中」小字对 ground 6.4:1、对玻璃面 4.8:1；红点/进度沿用同一档。
    live: Color(0xFFA81F18),
    onLive: Color(0xFFFFFFFF),
    glow: Color(0xFFC08A3E),
  );
}

/// 字阶。TV 档为设计画布像素；手机档按同一层级比例换算到逻辑像素。
@immutable
class LiveHubTextRamp {
  const LiveHubTextRamp({
    required this.hero,
    required this.display,
    required this.h1,
    required this.h2,
    required this.title,
    required this.body,
    required this.meta,
    required this.tab,
    required this.cardMeta,
    required this.small,
  });

  final double hero;
  final double display;
  final double h1;
  final double h2;
  final double title;
  final double body;
  final double meta;
  final double tab;
  final double cardMeta;
  final double small;

  /// 1920×1080 设计画布。使用时乘以 `1.w`。
  static const tv = LiveHubTextRamp(
    hero: 76,
    display: 120,
    h1: 44,
    h2: 34,
    title: 26,
    body: 24,
    meta: 22,
    tab: 30,
    cardMeta: 18,
    small: 20,
  );

  /// 手机/桌面逻辑像素。层级与 TV 一致，绝对值为可读性换算值。
  static const mobile = LiveHubTextRamp(
    hero: 40,
    display: 56,
    h1: 28,
    h2: 22,
    title: 18,
    body: 16,
    meta: 14,
    tab: 15,
    cardMeta: 12,
    small: 13,
  );
}

class LiveHubSpacing {
  const LiveHubSpacing._();

  static const double xxs = 4;
  static const double xs = 8;
  static const double sm = 12;
  static const double row = 14;
  static const double md = 16;
  static const double lg = 20;
  static const double xl = 24;
  static const double xxl = 32;
  static const double xxxl = 48;
  static const double rowGap = 56;
  static const double pageInset = 60;
  static const double contentGutter = 80;
}

class LiveHubRadii {
  const LiveHubRadii._();

  static const double card = 20;
  static const double input = 20;
  static const double listItem = 14;
  static const double panel = 28;
  static const double pill = 999;
}

/// 通过 `Theme.of(context).extension<LiveHubTokens>()` 或 `context.lh` 取用。
@immutable
class LiveHubTokens extends ThemeExtension<LiveHubTokens> {
  const LiveHubTokens({
    required this.palette,
    required this.ramp,
    this.unit = 1,
    // 字体声明在 livehub_ui 的 pubspec 里，Flutter 会把包内字体注册成
    // `packages/livehub_ui/<family>`；不带前缀会静默回退系统字体。
    this.fontFamily = 'packages/livehub_ui/Inter',
    this.fontFamilyFallback = const [
      'PingFang SC',
      'Noto Sans CJK SC',
      'Microsoft YaHei',
    ],
  });

  final LiveHubPalette palette;
  final LiveHubTextRamp ramp;

  /// 设计画布像素 → 当前设备逻辑像素的换算系数。TV 传 `1.w`，其余传 1。
  final double unit;

  final String fontFamily;
  final List<String> fontFamilyFallback;

  double px(double v) => v * unit;

  Color get ground => palette.ground;
  Color get groundDeep => palette.groundDeep;
  Color get panel => palette.panel;
  Color get raised => palette.raised;
  Color get raised2 => palette.raised2;
  Color get glassTint => palette.glassTint;
  Color get wash => palette.wash;
  Color get text => palette.text;
  Color get muted => palette.muted;
  Color get faint => palette.faint;
  Color get live => palette.live;
  Color get onLive => palette.onLive;
  Color get glow => palette.glow;

  TextStyle _style(
    double size, {
    FontWeight weight = FontWeight.w400,
    double height = 1.5,
    double? letterSpacing,
    Color? color,
  }) {
    return TextStyle(
      fontSize: size * unit,
      fontWeight: weight,
      height: height,
      letterSpacing: letterSpacing,
      color: color ?? text,
      fontFamily: fontFamily,
      fontFamilyFallback: fontFamilyFallback,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
  }

  TextStyle get hero =>
      _style(ramp.hero, weight: FontWeight.w600, height: 1.06, letterSpacing: -0.02);
  TextStyle get display =>
      _style(ramp.display, weight: FontWeight.w600, height: 1, letterSpacing: -0.02);
  TextStyle get h1 => _style(ramp.h1, weight: FontWeight.w600, height: 1.15);
  TextStyle get h2 => _style(ramp.h2, weight: FontWeight.w600, height: 1.2);
  TextStyle get title => _style(ramp.title, weight: FontWeight.w600, height: 1.3);
  TextStyle get body => _style(ramp.body, height: 1.5);
  TextStyle get meta => _style(ramp.meta, height: 1.4, color: faint);
  TextStyle get tab => _style(ramp.tab, weight: FontWeight.w500, height: 1);
  TextStyle get cardMeta => _style(ramp.cardMeta, height: 1.4);
  TextStyle get small => _style(ramp.small, height: 1.5, color: muted);

  BorderRadius get cardRadius => BorderRadius.circular(LiveHubRadii.card * unit);
  BorderRadius get listRadius =>
      BorderRadius.circular(LiveHubRadii.listItem * unit);
  BorderRadius get inputRadius =>
      BorderRadius.circular(LiveHubRadii.input * unit);
  BorderRadius get panelRadius =>
      BorderRadius.circular(LiveHubRadii.panel * unit);
  BorderRadius get pillRadius => BorderRadius.circular(999 * unit);

  @override
  LiveHubTokens copyWith({
    LiveHubPalette? palette,
    LiveHubTextRamp? ramp,
    double? unit,
    String? fontFamily,
    List<String>? fontFamilyFallback,
  }) {
    return LiveHubTokens(
      palette: palette ?? this.palette,
      ramp: ramp ?? this.ramp,
      unit: unit ?? this.unit,
      fontFamily: fontFamily ?? this.fontFamily,
      fontFamilyFallback: fontFamilyFallback ?? this.fontFamilyFallback,
    );
  }

  @override
  LiveHubTokens lerp(ThemeExtension<LiveHubTokens>? other, double t) {
    if (other is! LiveHubTokens) return this;
    return LiveHubTokens(
      palette: t < 0.5 ? palette : other.palette,
      ramp: t < 0.5 ? ramp : other.ramp,
      unit: other.unit,
      fontFamily: other.fontFamily,
      fontFamilyFallback: other.fontFamilyFallback,
    );
  }
}

extension LiveHubContext on BuildContext {
  LiveHubTokens get lh =>
      Theme.of(this).extension<LiveHubTokens>() ??
      const LiveHubTokens(
        palette: LiveHubPalette.dark,
        ramp: LiveHubTextRamp.mobile,
      );
}
