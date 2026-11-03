import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// TV 顶栏：品牌 + 六个一级页签 + 网络/时间。
///
/// 焦点玻璃只有一枚，跟着焦点滑（设计规范 §6.1.1）；不给每个页签各画一块底。
class TvTopNav extends StatefulWidget {
  const TvTopNav({super.key, required this.current});

  final TvTab current;

  @override
  State<TvTopNav> createState() => _TvTopNavState();
}

class _TvTopNavState extends State<TvTopNav> {
  late final List<FocusNode> _nodes = List.generate(
    TvTab.values.length,
    (i) => FocusNode(debugLabel: "tv-tab-${TvTab.values[i].name}"),
  );
  int _focused = -1;
  Timer? _clock;
  DateTime _now = DateTime.now();

  @override
  void initState() {
    super.initState();
    _clock = Timer.periodic(const Duration(seconds: 20), (_) {
      if (mounted) setState(() => _now = DateTime.now());
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    for (final node in _nodes) {
      node.dispose();
    }
    super.dispose();
  }

  int get _pillIndex =>
      _focused >= 0 ? _focused : widget.current.index;

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    final h = 116 * t.unit;
    final tabWidth = 136 * t.unit;

    return SizedBox(
      height: h,
      child: Padding(
        padding: EdgeInsets.symmetric(horizontal: 80 * t.unit),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Text.rich(
              TextSpan(
                children: [
                  TextSpan(
                    text: "Live",
                    style: t.tab.copyWith(
                      fontSize: 30 * t.unit,
                      fontWeight: FontWeight.w600,
                      color: t.text,
                    ),
                  ),
                  TextSpan(
                    text: "Hub",
                    style: t.tab.copyWith(
                      fontSize: 30 * t.unit,
                      fontWeight: FontWeight.w600,
                      color: t.live,
                    ),
                  ),
                ],
              ),
            ),
            SizedBox(width: 44 * t.unit),
            SizedBox(
              width: tabWidth * TvTab.values.length,
              height: h,
              child: Stack(
                children: [
                  AnimatedPositioned(
                    duration: const Duration(milliseconds: 260),
                    curve: const Cubic(0.32, 0.72, 0, 1),
                    left: _pillIndex * tabWidth + 6 * t.unit,
                    top: (h - 56 * t.unit) / 2,
                    width: tabWidth - 12 * t.unit,
                    height: 56 * t.unit,
                    child: LiveHubGlass(
                      level: 0.26,
                      radius: t.pillRadius,
                      child: const SizedBox.expand(),
                    ),
                  ),
                  Row(
                    children: [
                      for (var i = 0; i < TvTab.values.length; i++)
                        SizedBox(
                          width: tabWidth,
                          height: h,
                          child: _buildTab(context, i),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const Spacer(),
            Container(
              width: 8 * t.unit,
              height: 8 * t.unit,
              decoration: const BoxDecoration(
                color: Color(0xFF30D158),
                shape: BoxShape.circle,
              ),
            ),
            SizedBox(width: 10 * t.unit),
            Text("在线", style: t.meta.copyWith(color: t.muted)),
            SizedBox(width: 24 * t.unit),
            Text(
              "${_now.hour.toString().padLeft(2, '0')}:${_now.minute.toString().padLeft(2, '0')}",
              style: t.meta.copyWith(color: t.faint),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTab(BuildContext context, int index) {
    final t = context.lh;
    final tab = TvTab.values[index];
    final selected = widget.current.index == index;
    final focused = _focused == index;

    return Focus(
      focusNode: _nodes[index],
      onFocusChange: (hasFocus) {
        setState(() {
          if (hasFocus) {
            _focused = index;
          } else if (_focused == index) {
            _focused = -1;
          }
        });
      },
      onKeyEvent: (node, event) {
        if (event is! KeyDownEvent) return KeyEventResult.ignored;
        if (event.logicalKey == LogicalKeyboardKey.arrowLeft) {
          if (index > 0) _nodes[index - 1].requestFocus();
          return KeyEventResult.handled;
        }
        if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
          if (index < _nodes.length - 1) _nodes[index + 1].requestFocus();
          return KeyEventResult.handled;
        }
        if (event.logicalKey == LogicalKeyboardKey.enter ||
            event.logicalKey == LogicalKeyboardKey.select ||
            event.logicalKey == LogicalKeyboardKey.space) {
          TvTabNav.go(tab);
          return KeyEventResult.handled;
        }
        return KeyEventResult.ignored;
      },
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () {
          _nodes[index].requestFocus();
          TvTabNav.go(tab);
        },
        child: Center(
          child: AnimatedScale(
            scale: focused ? 1.06 : 1,
            duration: const Duration(milliseconds: 200),
            curve: const Cubic(0.32, 0.72, 0, 1),
            child: Text(
              tab.label,
              style: t.tab.copyWith(
                color: selected || focused ? t.text : t.muted,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
