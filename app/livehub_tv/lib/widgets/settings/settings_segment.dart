import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 分段控件的一个选项。
class SettingsOption<T> {
  const SettingsOption(this.value, this.label);

  final T value;
  final String label;
}

/// 行内分段控件：轨道 .06，选中项由一枚滑动的玻璃药丸表示（.34，260ms）。
///
/// 只用于选项较少（2–5 个）的行；更多选项由所在行用“当前值 + 左右箭头”呈现。
///
/// **宽度必须收在父级约束里。** 旧实现是「按最长标签算一个单元格宽度，再乘选项数」：
/// 4 条线路时最长标签是 `线路4·HLS·fMP4`，单格 ≈220u × 4 + 间距 ⇒ 900u+，
/// 而播放页面板只有 800u（减掉行内 padding 只剩 ≈700u），所以那一行必然
/// RenderFlex 溢出 —— 屏幕上的表现就是黄黑警示条 + `RIGHT OVERFLOWED BY N PIXELS`。
class SettingsSegment<T> extends StatelessWidget {
  const SettingsSegment({
    super.key,
    required this.options,
    required this.value,
  });

  final List<SettingsOption<T>> options;
  final T value;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final measureStyle = t.meta.copyWith(fontWeight: FontWeight.w600);
    var maxTextWidth = 0.0;
    for (final option in options) {
      final painter = TextPainter(
        text: TextSpan(text: option.label, style: measureStyle),
        textDirection: TextDirection.ltr,
        maxLines: 1,
      )..layout();
      if (painter.width > maxTextWidth) {
        maxTextWidth = painter.width;
      }
      painter.dispose();
    }

    final itemHeight =
        (measureStyle.fontSize ?? 22 * t.unit) * (measureStyle.height ?? 1.4) +
            20 * t.unit;
    final gap = 6 * t.unit;
    final pad = 5 * t.unit;
    final selectedIndex = options.indexWhere((o) => o.value == value);

    return LayoutBuilder(
      builder: (context, constraints) {
        var itemWidth = maxTextWidth + 24 * t.unit;
        final maxWidth = constraints.maxWidth;
        if (maxWidth.isFinite) {
          final natural =
              options.length * itemWidth + (options.length - 1) * gap + pad * 2;
          if (natural > maxWidth) {
            final room = maxWidth - pad * 2 - (options.length - 1) * gap;
            itemWidth = (room / options.length).clamp(40 * t.unit, itemWidth);
          }
        }
        final totalWidth =
            (options.length * itemWidth + (options.length - 1) * gap + pad * 2)
                .clamp(0.0, maxWidth.isFinite ? maxWidth : double.infinity);

        return LiveHubGlass(
          level: 0.06,
          radius: t.pillRadius,
          child: SizedBox(
            width: totalWidth,
            height: itemHeight + pad * 2,
            child: Stack(
              children: [
                if (selectedIndex >= 0)
                  AnimatedPositioned(
                    left: pad + selectedIndex * (itemWidth + gap),
                    top: pad,
                    width: itemWidth,
                    height: itemHeight,
                    duration: const Duration(milliseconds: 260),
                    curve: const Cubic(0.32, 0.72, 0, 1),
                    child: LiveHubGlass(
                      level: 0.34,
                      radius: t.pillRadius,
                      child: const SizedBox.expand(),
                    ),
                  ),
                // Stack 默认 topStart；药丸用 top/left = pad 定位，Row 也必须
                // 缩进同样的 pad，否则文字会相对药丸上移、左移各 pad。
                Padding(
                  padding: EdgeInsets.all(pad),
                  child: Row(
                    children: [
                      for (var i = 0; i < options.length; i++)
                        Padding(
                          padding: EdgeInsets.only(left: i == 0 ? 0 : gap),
                          child: SizedBox(
                            width: itemWidth,
                            height: itemHeight,
                            child: Center(
                              child: Text(
                                options[i].label,
                                maxLines: 1,
                                softWrap: false,
                                overflow: TextOverflow.ellipsis,
                                textAlign: TextAlign.center,
                                style: t.meta.copyWith(
                                  color: i == selectedIndex ? t.text : t.muted,
                                  fontWeight: i == selectedIndex
                                      ? FontWeight.w600
                                      : FontWeight.w500,
                                ),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
