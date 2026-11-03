import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import 'tokens.dart';

/// 烟色玻璃：全项目唯一的材质公式。
///
/// [level] 即设计规范里的 `--a`（体亮度）。渐变停靠点用像素而非百分比，
/// 所以 56px 的胶囊与 300px 的弹层是同一种材质，只是薄厚不同。
class LiveHubGlass extends StatelessWidget {
  const LiveHubGlass({
    super.key,
    this.level = 0.062,
    this.radius,
    this.padding,
    this.blur = false,
    this.child,
  });

  final double level;
  final BorderRadius? radius;
  final EdgeInsetsGeometry? padding;

  /// TV 端保持 false（整屏模糊会掉帧），手机/桌面可用于少量浮层。
  final bool blur;

  final Widget? child;

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    final r = radius ?? tokens.listRadius;
    final content = CustomPaint(
      painter: _GlassPainter(
        level: level,
        radius: r,
        glow: tokens.glow,
        tint: tokens.glassTint,
        unit: tokens.unit,
      ),
      child: Padding(
        padding: padding ?? EdgeInsets.zero,
        child: child,
      ),
    );
    if (!blur) {
      return ClipRRect(borderRadius: r, child: content);
    }
    return ClipRRect(
      borderRadius: r,
      child: BackdropFilter(
        filter: ui.ImageFilter.blur(
          sigmaX: 18 * tokens.unit,
          sigmaY: 18 * tokens.unit,
        ),
        child: content,
      ),
    );
  }
}

class _GlassPainter extends CustomPainter {
  _GlassPainter({
    required this.level,
    required this.radius,
    required this.glow,
    required this.tint,
    required this.unit,
  });

  final double level;
  final BorderRadius radius;
  final Color glow;
  final Color tint;
  final double unit;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final rect = Offset.zero & size;
    final rrect = radius.toRRect(rect);
    canvas.drawRRect(rrect, Paint()..color = tint);

    final h = size.height;
    final warmStops = <double>[0, (14 * unit / h).clamp(0.0, 1.0), (34 * unit / h).clamp(0.0, 1.0)];
    if (h > 0 && warmStops[2] > 0) {
      final warm = ui.Gradient.linear(
        Offset(0, 0),
        Offset(0, h),
        [
          colorFrom(glow, level),
          colorFrom(glow, level * 0.30),
          colorFrom(glow, 0),
        ],
        _normalize(warmStops),
      );
      canvas.drawRRect(rrect, Paint()..shader = warm);
    }

    final whiteStops = _normalize(
      <double>[0, (16 * unit / h).clamp(0.0, 1.0), 1.0],
    );
    final white = ui.Gradient.linear(
      Offset(0, 0),
      Offset(0, h),
      [
        Colors.white.withValues(alpha: (level * 0.95).clamp(0.0, 1.0)),
        Colors.white.withValues(alpha: level.clamp(0.0, 1.0)),
        Colors.white.withValues(alpha: level.clamp(0.0, 1.0)),
      ],
      whiteStops,
    );
    canvas.drawRRect(rrect, Paint()..shader = white);

    final rim = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = unit
      ..color = Colors.white.withValues(
        alpha: (level * 0.7 + 0.045).clamp(0.0, 1.0),
      );
    canvas.drawRRect(rrect.deflate(unit / 2), rim);
  }

  List<double> _normalize(List<double> stops) {
    final result = <double>[];
    var last = 0.0;
    for (final s in stops) {
      final v = s.clamp(last, 1.0);
      result.add(v);
      last = v;
    }
    return result;
  }

  @override
  bool shouldRepaint(_GlassPainter old) =>
      old.level != level ||
      old.radius != radius ||
      old.glow != glow ||
      old.tint != tint ||
      old.unit != unit;
}

Color colorFrom(Color c, double alpha) => c.withValues(alpha: alpha.clamp(0.0, 1.0));

/// 焦点面：200ms 内把同一块玻璃推亮、放大、浮起来。
/// 用于 TV 端；手机端触屏不需要缩放焦点。
class LiveHubFocusSurface extends StatelessWidget {
  const LiveHubFocusSurface({
    super.key,
    required this.focused,
    required this.child,
    this.idleLevel = 0.062,
    this.focusLevel = 0.215,
    this.idleScale = 1,
    this.focusScale = 1.02,
    this.radius,
    this.padding,
  });

  final bool focused;
  final Widget child;
  final double idleLevel;
  final double focusLevel;
  final double idleScale;
  final double focusScale;
  final BorderRadius? radius;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: focused ? 1 : 0),
      duration: const Duration(milliseconds: 200),
      curve: const Cubic(0.32, 0.72, 0, 1),
      builder: (context, t, _) {
        return Transform.scale(
          scale: idleScale + (focusScale - idleScale) * t,
          child: LiveHubGlass(
            level: idleLevel + (focusLevel - idleLevel) * t,
            radius: radius,
            padding: padding,
            child: child,
          ),
        );
      },
    );
  }
}

/// 全屏背景：核部金光 + 主光椭圆 + 四角暗角。
class LiveHubBackdrop extends StatelessWidget {
  const LiveHubBackdrop({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Stack(
      fit: StackFit.expand,
      children: [
        ColoredBox(color: t.ground),
        IgnorePointer(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: RadialGradient(
                center: const Alignment(0, -0.18),
                radius: 0.55,
                colors: [
                  colorFrom(t.glow, 0.105),
                  Colors.transparent,
                ],
                stops: const [0, 0.68],
              ),
            ),
          ),
        ),
        IgnorePointer(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: RadialGradient(
                center: const Alignment(0, -0.10),
                radius: 1,
                colors: [
                  colorFrom(t.glow, 0.082),
                  colorFrom(t.glow, 0.030),
                  Colors.transparent,
                ],
                stops: const [0, 0.42, 0.70],
              ),
            ),
          ),
        ),
        IgnorePointer(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: RadialGradient(
                center: const Alignment(0, -0.08),
                radius: 1,
                colors: [
                  Colors.transparent,
                  colorFrom(t.groundDeep, 0.74),
                ],
                stops: const [0.40, 1.0],
              ),
            ),
          ),
        ),
        child,
      ],
    );
  }
}
