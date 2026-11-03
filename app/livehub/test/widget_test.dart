import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:livehub_ui/livehub_ui.dart';

void main() {
  test('共享主题注册了 LiveHub 设计令牌', () {
    final theme = liveHubTheme();

    final tokens = theme.extension<LiveHubTokens>();
    expect(tokens, isNotNull);
    expect(tokens!.palette.ground, LiveHubPalette.dark.ground);
    expect(tokens.palette.glow, LiveHubPalette.dark.glow);
    expect(theme.scaffoldBackgroundColor, Colors.transparent);
    expect(theme.textTheme.bodyLarge?.fontFamily, 'packages/livehub_ui/Inter');
  });

  test('TV 档与手机档字阶各自成立', () {
    const tv = LiveHubTokens(
      palette: LiveHubPalette.dark,
      ramp: LiveHubTextRamp.tv,
    );
    const mobile = LiveHubTokens(
      palette: LiveHubPalette.dark,
      ramp: LiveHubTextRamp.mobile,
    );

    expect(tv.hero.fontSize, 76);
    expect(mobile.hero.fontSize, 40);
    expect(tv.hero.fontSize, greaterThan(tv.h1.fontSize!));
    expect(mobile.hero.fontSize, greaterThan(mobile.h1.fontSize!));
  });
}
