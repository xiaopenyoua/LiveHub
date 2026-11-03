import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'tokens.dart';

/// 构建三端共用的 LiveHub 主题。
///
/// - TV：`ramp: LiveHubTextRamp.tv, unit: 1.w`
/// - 手机/桌面：`ramp: LiveHubTextRamp.mobile, unit: 1`
ThemeData liveHubTheme({
  Brightness brightness = Brightness.dark,
  LiveHubTextRamp ramp = LiveHubTextRamp.mobile,
  double unit = 1,
}) {
  final palette =
      brightness == Brightness.dark ? LiveHubPalette.dark : LiveHubPalette.light;
  final tokens = LiveHubTokens(palette: palette, ramp: ramp, unit: unit);
  final scheme = ColorScheme(
    brightness: brightness,
    primary: palette.text,
    onPrimary: palette.ground,
    secondary: palette.glow,
    onSecondary: palette.ground,
    error: palette.live,
    onError: palette.onLive,
    surface: palette.ground,
    onSurface: palette.text,
    surfaceContainerHighest: palette.raised,
    onSurfaceVariant: palette.muted,
    outline: palette.faint,
  );

  final textTheme = TextTheme(
    displayLarge: tokens.hero,
    displayMedium: tokens.display,
    displaySmall: tokens.h1,
    headlineLarge: tokens.h1,
    headlineMedium: tokens.h2,
    headlineSmall: tokens.title,
    titleLarge: tokens.title,
    titleMedium: tokens.body.copyWith(fontWeight: FontWeight.w600),
    titleSmall: tokens.meta.copyWith(fontWeight: FontWeight.w600),
    bodyLarge: tokens.body,
    bodyMedium: tokens.meta,
    bodySmall: tokens.small,
    labelLarge: tokens.tab,
    labelMedium: tokens.meta,
    labelSmall: tokens.cardMeta,
  );

  final border = BorderSide(
    color: palette.text.withValues(alpha: 0.10),
    width: unit,
  );

  return ThemeData(
    brightness: brightness,
    useMaterial3: true,
    colorScheme: scheme,
    fontFamily: 'packages/livehub_ui/Inter',
    fontFamilyFallback: const [
      'PingFang SC',
      'Noto Sans CJK SC',
      'Microsoft YaHei',
    ],
    scaffoldBackgroundColor: Colors.transparent,
    canvasColor: palette.ground,
    dividerColor: Colors.transparent,
    dividerTheme: const DividerThemeData(
      color: Colors.transparent,
      thickness: 0,
      space: 0,
    ),
    textTheme: textTheme,
    primaryTextTheme: textTheme,
    iconTheme: IconThemeData(color: palette.text, size: 24 * unit),
    appBarTheme: AppBarTheme(
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: true,
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      foregroundColor: palette.text,
      titleTextStyle: tokens.title,
      systemOverlayStyle: brightness == Brightness.dark
          ? SystemUiOverlayStyle.light
          : SystemUiOverlayStyle.dark,
    ),
    cardTheme: CardThemeData(
      color: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: tokens.cardRadius),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: palette.raised,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: tokens.panelRadius),
      titleTextStyle: tokens.h2,
      contentTextStyle: tokens.body,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: palette.glassTint,
      hintStyle: tokens.body.copyWith(color: palette.faint),
      labelStyle: tokens.body.copyWith(color: palette.muted),
      contentPadding: EdgeInsets.symmetric(
        horizontal: LiveHubSpacing.md * unit,
        vertical: LiveHubSpacing.sm * unit,
      ),
      border: OutlineInputBorder(
        borderRadius: tokens.inputRadius,
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: tokens.inputRadius,
        borderSide: border,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: tokens.inputRadius,
        borderSide: BorderSide(
          color: palette.glow.withValues(alpha: 0.55),
          width: unit,
        ),
      ),
    ),
    switchTheme: SwitchThemeData(
      // 选中态：深色拇指压在白轨道上。若拇指也用 onLive，滑块会与轨道
      // 同色，开态看起来是一整块白（手机端曾为此局部打补丁）。
      thumbColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? palette.ground
            : palette.muted,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? palette.text
            : palette.raised2,
      ),
      trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
    ),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? palette.text
            : Colors.transparent,
      ),
      checkColor: WidgetStatePropertyAll(palette.ground),
      side: BorderSide(color: palette.faint, width: unit),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(4 * unit),
      ),
    ),
    radioTheme: RadioThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? palette.text
            : palette.faint,
      ),
    ),
    sliderTheme: SliderThemeData(
      activeTrackColor: palette.text,
      inactiveTrackColor: palette.raised2,
      thumbColor: palette.text,
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: palette.text,
      linearTrackColor: palette.raised2,
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: palette.raised,
      contentTextStyle: tokens.body,
      actionTextColor: palette.glow,
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: tokens.listRadius),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: palette.raised,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(LiveHubRadii.panel * unit),
        ),
      ),
    ),
    tabBarTheme: TabBarThemeData(
      labelColor: palette.text,
      unselectedLabelColor: palette.muted,
      indicatorColor: palette.text,
      dividerColor: Colors.transparent,
      labelStyle: tokens.tab,
      unselectedLabelStyle: tokens.tab,
      // 选中反馈只由胶囊指示器表达。默认的 Material ink 会在整个页签槽位
      // 上盖一块比胶囊更大的浅色方框，点按切换时非常抢眼。
      overlayColor: const WidgetStatePropertyAll(Colors.transparent),
      splashFactory: NoSplash.splashFactory,
    ),
    bottomNavigationBarTheme: BottomNavigationBarThemeData(
      backgroundColor: Colors.transparent,
      selectedItemColor: palette.text,
      unselectedItemColor: palette.faint,
      type: BottomNavigationBarType.fixed,
      elevation: 0,
      selectedLabelStyle: tokens.cardMeta,
      unselectedLabelStyle: tokens.cardMeta,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      // 与页签胶囊同一档：12% 文字色，而不是比背景更浅的 glassTint。
      indicatorColor: palette.text.withValues(alpha: 0.12),
      labelTextStyle: WidgetStatePropertyAll(tokens.cardMeta),
      iconTheme: WidgetStateProperty.resolveWith(
        (states) => IconThemeData(
          color: states.contains(WidgetState.selected)
              ? palette.text
              : palette.faint,
          size: 24 * unit,
        ),
      ),
    ),
    navigationRailTheme: NavigationRailThemeData(
      backgroundColor: Colors.transparent,
      indicatorColor: palette.text.withValues(alpha: 0.12),
      selectedIconTheme: IconThemeData(color: palette.text, size: 24 * unit),
      unselectedIconTheme: IconThemeData(color: palette.faint, size: 24 * unit),
      selectedLabelTextStyle: tokens.cardMeta.copyWith(color: palette.text),
      unselectedLabelTextStyle: tokens.cardMeta.copyWith(color: palette.faint),
    ),
    extensions: <ThemeExtension<dynamic>>[tokens],
  );
}
