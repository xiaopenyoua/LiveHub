import 'package:flutter/material.dart';

import 'glass.dart';
import 'tokens.dart';

/// 主按钮：白玻璃胶囊，全屏最多一个。次按钮：半透明玻璃胶囊。
class LiveHubPillButton extends StatelessWidget {
  const LiveHubPillButton({
    super.key,
    required this.label,
    this.icon,
    this.primary = true,
    this.focused = false,
    this.onTap,
  });

  final String label;
  final IconData? icon;
  final bool primary;
  final bool focused;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final level = primary
        ? (focused ? 0.34 : 0.245)
        : (focused ? 0.30 : 0.10);
    return GestureDetector(
      onTap: onTap,
      child: AnimatedScale(
        scale: focused ? 1.06 : 1,
        duration: const Duration(milliseconds: 200),
        curve: const Cubic(0.32, 0.72, 0, 1),
        child: LiveHubGlass(
          level: level,
          radius: t.pillRadius,
          padding: EdgeInsets.symmetric(
            horizontal: t.px(LiveHubSpacing.xl),
            vertical: t.px(LiveHubSpacing.sm),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (icon != null) ...[
                Icon(icon, size: t.px(22), color: t.text),
                SizedBox(width: t.px(LiveHubSpacing.xs)),
              ],
              Text(
                label,
                style: t.body.copyWith(
                  fontWeight: primary ? FontWeight.w600 : FontWeight.w500,
                  color: t.text,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 标签 / 药丸。平台名与 LIVE 在卡片上不挂底（见设计规范 §8.4），
/// 这里的 Tag 只用于筛选栏、设置项等确实需要一枚可控的面的地方。
class LiveHubTag extends StatelessWidget {
  const LiveHubTag({
    super.key,
    required this.label,
    this.selected = false,
    this.focused = false,
    this.onTap,
  });

  final String label;
  final bool selected;
  final bool focused;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final level = focused
        ? (selected ? 0.36 : 0.34)
        : (selected ? 0.22 : 0.10);
    return GestureDetector(
      onTap: onTap,
      child: LiveHubFocusSurface(
        focused: focused,
        idleLevel: level,
        focusLevel: level,
        idleScale: 1.04,
        focusScale: 1.04,
        radius: t.pillRadius,
        padding: EdgeInsets.symmetric(
          horizontal: t.px(LiveHubSpacing.md),
          vertical: t.px(LiveHubSpacing.xs),
        ),
        child: Text(
          label,
          style: t.meta.copyWith(
            color: selected || focused ? t.text : t.muted,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
          ),
        ),
      ),
    );
  }
}

/// 在播状态：红点 + 文案。强调色只出现在这里、进度条与主按钮。
class LiveHubLiveDot extends StatelessWidget {
  const LiveHubLiveDot({super.key, this.label = '直播中', this.color});

  final String label;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final c = color ?? t.live;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: t.px(8),
          height: t.px(8),
          decoration: BoxDecoration(color: c, shape: BoxShape.circle),
        ),
        SizedBox(width: t.px(6)),
        Text(
          label,
          style: t.cardMeta.copyWith(
            color: c,
            letterSpacing: 0.14 * t.px(8),
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }
}

/// 内容行标题。首页/分类/关注共用同一档。
class LiveHubSectionHeader extends StatelessWidget {
  const LiveHubSectionHeader({super.key, required this.title, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Padding(
      padding: EdgeInsets.only(bottom: t.px(LiveHubSpacing.md)),
      child: Row(
        children: [
          Expanded(child: Text(title, style: t.h2)),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}

/// 卡片画面下沿的字幕底：50% 起笔、100% 收到 .72。
/// 字压在画面上时，这层暗是字幕底，不是装饰。
class LiveHubCardScrim extends StatelessWidget {
  const LiveHubCardScrim({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Colors.transparent, Color(0xB8000000)],
          stops: [0.50, 1.0],
        ),
      ),
      child: child,
    );
  }
}
