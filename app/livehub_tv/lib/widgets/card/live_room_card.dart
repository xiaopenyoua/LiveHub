import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_tv/widgets/net_image.dart';
import 'package:livehub_ui/livehub_ui.dart';
import 'package:marquee/marquee.dart';

class LiveRoomCard extends StatelessWidget {
  final String cover;
  final String title;
  final String anchor;
  final String siteName;
  final String roomId;
  final int online;
  final bool autofocus;
  final AppFocusNode focusNode;
  final Function()? onTap;
  const LiveRoomCard({
    required this.cover,
    required this.title,
    required this.anchor,
    required this.siteName,
    required this.roomId,
    required this.focusNode,
    required this.online,
    this.autofocus = false,
    this.onTap,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    const overlayShadows = [
      Shadow(color: Color(0xE6000000), blurRadius: 1),
      Shadow(color: Color(0x99000000), blurRadius: 14),
    ];
    return HighlightWidget(
      focusNode: focusNode,
      onTap: onTap,
      borderRadius: t.cardRadius,
      focusScale: 1.08,
      child: Obx(
        () => ClipRRect(
          borderRadius: t.cardRadius,
          child: AspectRatio(
            aspectRatio: 16 / 9,
            child: Stack(
              fit: StackFit.expand,
              children: [
                // §8.4：封面亮度 0.95 → 焦点 1.04。玻璃体被不透明封面盖住，
                // 所以亮度必须落在封面本身。
                TweenAnimationBuilder<double>(
                  tween: Tween(
                    begin: 0.95,
                    end: focusNode.isFoucsed.value ? 1.04 : 0.95,
                  ),
                  duration: const Duration(milliseconds: 200),
                  curve: const Cubic(0.32, 0.72, 0, 1),
                  builder: (context, value, child) => ColorFiltered(
                    colorFilter: ColorFilter.matrix(<double>[
                      value, 0, 0, 0, 0,
                      0, value, 0, 0, 0,
                      0, 0, value, 0, 0,
                      0, 0, 0, 1, 0,
                    ]),
                    child: child,
                  ),
                  child: NetImage(
                    cover,
                    cacheWidth: 400,
                  ),
                ),
                LiveHubCardScrim(
                  child: Padding(
                    padding: EdgeInsets.fromLTRB(
                      20 * t.unit,
                      20 * t.unit,
                      20 * t.unit,
                      16 * t.unit,
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.end,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          height: 36 * t.unit,
                          child: focusNode.isFoucsed.value
                              ? Marquee(
                                  text: title,
                                  style: t.meta.copyWith(
                                    color: t.text,
                                    fontWeight: FontWeight.w500,
                                    shadows: overlayShadows,
                                  ),
                                  startAfter: const Duration(seconds: 1),
                                  velocity: 20,
                                  blankSpace: 200 * t.unit,
                                  scrollAxis: Axis.horizontal,
                                )
                              : Align(
                                  alignment: Alignment.centerLeft,
                                  child: Text(
                                    title,
                                    style: t.meta.copyWith(
                                      color: t.text,
                                      fontWeight: FontWeight.w500,
                                      shadows: overlayShadows,
                                    ),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                        ),
                        SizedBox(height: 2 * t.unit),
                        Text(
                          '$anchor · ${Utils.onlineToString(online)}',
                          style: t.cardMeta.copyWith(
                            color: Colors.white.withValues(alpha: 0.78),
                            shadows: overlayShadows,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),
                ),
                Positioned(
                  left: 12 * t.unit,
                  top: 12 * t.unit,
                  child: Text(
                    siteName,
                    style: t.cardMeta.copyWith(
                      color: t.text,
                      shadows: overlayShadows,
                    ),
                  ),
                ),
                Positioned(
                  right: 12 * t.unit,
                  top: 12 * t.unit,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 6 * t.unit,
                        height: 6 * t.unit,
                        decoration: BoxDecoration(
                          color: LiveHubPalette.dark.live,
                          shape: BoxShape.circle,
                        ),
                      ),
                      SizedBox(width: 4 * t.unit),
                      Text(
                        'LIVE',
                        style: t.cardMeta.copyWith(
                          color: t.text,
                          letterSpacing: 0.14 * t.ramp.cardMeta * t.unit,
                          fontWeight: FontWeight.w600,
                          shadows: overlayShadows,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
