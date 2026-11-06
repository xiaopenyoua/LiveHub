import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/settings/settings_rows.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 测试里不走 AppStyle（它用 1.w，需要 ScreenUtil 初始化），
/// 直接用 liveHubTheme 给一份 unit=1 的等价主题。
final ThemeData _theme = liveHubTheme(
  brightness: Brightness.dark,
  ramp: LiveHubTextRamp.tv,
  unit: 1,
);

Widget wrap(Widget child) => MaterialApp(
      theme: _theme,
      home: Scaffold(body: Center(child: child)),
    );

void main() {
  testWidgets('枚举行的左右键真的会改值（设置面板点不动的回归用例）',
      (tester) async {
    final node = AppFocusNode();
    var value = 0;
    await tester.pumpWidget(
      StatefulBuilder(
        builder: (context, setState) => wrap(
          SettingsChoiceRow(
            focusNode: node,
            title: '清晰度',
            items: const {0: '超清', 1: '高清', 2: '标清'},
            value: value,
            autofocus: true,
            onChanged: (e) {
              setState(() => value = e as int);
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(value, 1, reason: '右键应当步进到下一档');

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await tester.pump();
    expect(value, 0, reason: '左键应当退回上一档');

    node.dispose();
  });

  testWidgets('开关行左键=关、右键=开', (tester) async {
    final node = AppFocusNode();
    var value = false;
    await tester.pumpWidget(
      StatefulBuilder(
        builder: (context, setState) => wrap(
          SettingsSwitchRow(
            focusNode: node,
            title: '弹幕开关',
            value: value,
            autofocus: true,
            onChanged: (e) => setState(() => value = e),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.pump();
    expect(value, isTrue);

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await tester.pump();
    expect(value, isFalse);

    node.dispose();
  });
}
