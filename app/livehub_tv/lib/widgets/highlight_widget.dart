import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_ui/livehub_ui.dart';

typedef FocusOnKeyDownCallback = KeyEventResult Function();

/// 可聚焦面。
///
/// 构造参数与按键回调 API 与上游一致；只把"反白 + 粉色投影"的焦点语言
/// 换成 LiveHub 的同一套玻璃：提亮、放大、浮起，不描边、不反色。
class HighlightWidget extends StatelessWidget {
  final AppFocusNode focusNode;
  final Widget child;
  final FocusOnKeyDownCallback? onUpKey;
  final FocusOnKeyDownCallback? onDownKey;
  final FocusOnKeyDownCallback? onLeftKey;
  final FocusOnKeyDownCallback? onRightKey;
  final Function(bool)? onFocusChange;
  final Function()? onTap;
  final Color foucsedColor;
  final Color color;
  final bool autofocus;
  final BorderRadius? borderRadius;
  final double order;
  final bool selected;

  /// 焦点放大比例：封面卡 1.08，按钮/按键 1.06，列表项 1.02（§6.1）。
  final double focusScale;

  /// 静置体亮度（`--a`）。为空时按 [selected] 取 .062 / .22。
  final double? idleLevel;

  /// 焦点体亮度（`--a`）。为空时按 [selected] 取 .215 / .34。
  final double? focusLevel;

  const HighlightWidget({
    required this.focusNode,
    required this.child,
    this.onUpKey,
    this.onDownKey,
    this.onLeftKey,
    this.onRightKey,
    this.onFocusChange,
    this.onTap,
    this.autofocus = false,
    this.selected = false,
    this.borderRadius,
    this.order = 0.0,
    this.color = Colors.transparent,
    this.foucsedColor = Colors.white,
    this.focusScale = 1.06,
    this.idleLevel,
    this.focusLevel,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final r = borderRadius ?? t.listRadius;
    final idle = idleLevel ?? (selected ? 0.22 : 0.062);
    final focus = focusLevel ?? (selected ? 0.34 : 0.215);
    return FocusTraversalOrder(
      order: NumericFocusOrder(order),
      child: Focus(
        focusNode: focusNode,
        autofocus: autofocus,
        onFocusChange: onFocusChange,
        onKeyEvent: (node, e) {
          if (e is KeyDownEvent) {
            if (e.logicalKey == LogicalKeyboardKey.arrowRight) {
              return onRightKey?.call() ?? KeyEventResult.ignored;
            }
            if (e.logicalKey == LogicalKeyboardKey.arrowLeft) {
              return onLeftKey?.call() ?? KeyEventResult.ignored;
            }
            if (e.logicalKey == LogicalKeyboardKey.arrowUp) {
              return onUpKey?.call() ?? KeyEventResult.ignored;
            }
            if (e.logicalKey == LogicalKeyboardKey.arrowDown) {
              return onDownKey?.call() ?? KeyEventResult.ignored;
            }
            if (e.logicalKey == LogicalKeyboardKey.enter ||
                e.logicalKey == LogicalKeyboardKey.select ||
                e.logicalKey == LogicalKeyboardKey.space) {
              return onTap?.call() ?? KeyEventResult.ignored;
            }
          }

          return KeyEventResult.ignored;
        },
        child: GestureDetector(
          onTap: onTap,
          child: Obx(
            () => TweenAnimationBuilder<double>(
              tween: Tween(
                begin: 0,
                end: focusNode.isFoucsed.value ? 1 : 0,
              ),
              duration: const Duration(milliseconds: 200),
              curve: const Cubic(0.32, 0.72, 0, 1),
              builder: (context, value, _) {
                final level = idle + (focus - idle) * value;
                final scale = 1 + (focusScale - 1) * value;
                return Transform.scale(
                  scale: scale,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      borderRadius: r,
                      boxShadow: value <= 0.001
                          ? null
                          : [
                              BoxShadow(
                                color: Colors.black.withValues(
                                  alpha: 0.72 * value,
                                ),
                                offset: Offset(0, 22 * t.unit),
                                blurRadius: 48 * t.unit,
                                spreadRadius: -22 * t.unit,
                              ),
                            ],
                    ),
                    child: LiveHubGlass(
                      level: level,
                      radius: r,
                      child: child,
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      ),
    );
  }
}
