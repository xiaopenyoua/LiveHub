import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 设计稿开关：88×50 轨道、42 圆滑块。
///
/// 位移 180ms `cubic-bezier(.4,0,.2,1)`；滑块变色 120ms、延后 55ms
/// （设计规范 §7.2）。轨道是玻璃，开态转白。
class SettingsSwitch extends StatefulWidget {
  const SettingsSwitch({super.key, required this.value});

  final bool value;

  @override
  State<SettingsSwitch> createState() => _SettingsSwitchState();
}

class _SettingsSwitchState extends State<SettingsSwitch>
    with SingleTickerProviderStateMixin {
  static const _shiftCurve = Cubic(0.4, 0, 0.2, 1);
  static const _colorCurve = Cubic(0.32, 0.72, 0, 1);
  static const _durationMs = 180.0;
  static const _colorDelayMs = 55.0;
  static const _colorDurationMs = 120.0;

  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 180),
    value: widget.value ? 1 : 0,
  );

  @override
  void didUpdateWidget(SettingsSwitch oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.value != widget.value) {
      _controller.animateTo(widget.value ? 1 : 0);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final width = 88 * t.unit;
    final height = 50 * t.unit;
    final thumb = 42 * t.unit;
    final pad = (height - thumb) / 2;
    final travel = width - thumb - pad * 2;

    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        final shift = _shiftCurve.transform(_controller.value);
        final rawColor = ((_controller.value * _durationMs - _colorDelayMs) /
                _colorDurationMs)
            .clamp(0.0, 1.0);
        final colorT = _colorCurve.transform(rawColor);
        final trackColor = Color.lerp(
          t.text.withValues(alpha: 0.13),
          t.text,
          shift,
        )!;
        final thumbColor = Color.lerp(t.text, t.ground, colorT)!;

        return SizedBox(
          width: width,
          height: height,
          child: Stack(
            children: [
              Positioned.fill(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: trackColor,
                    borderRadius: t.pillRadius,
                  ),
                ),
              ),
              Positioned(
                left: pad + travel * shift,
                top: pad,
                width: thumb,
                height: thumb,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: thumbColor,
                    shape: BoxShape.circle,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
