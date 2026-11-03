import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_tv/widgets/net_image.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 主播卡。
///
/// 默认是紧凑头像格（搜索页主播结果）；[cover] 为 true 时是 16:9 封面卡
/// （关注 / 记录页），用头像当封面。
class PersonCard extends StatelessWidget {
  const PersonCard({
    super.key,
    required this.avatar,
    required this.name,
    required this.siteName,
    required this.focusNode,
    this.liveStatus = false,
    this.autofocus = false,
    this.onTap,
    this.cover = false,
    this.status,
    this.dimmed = false,
  });

  final String avatar;
  final String name;
  final String siteName;
  final bool liveStatus;
  final AppFocusNode focusNode;
  final bool autofocus;
  final Function()? onTap;

  /// true：16:9 封面卡；false：紧凑头像格。
  final bool cover;

  /// 封面卡副行状态；为空时按 [liveStatus] 取“直播中 / 未开播”。
  final String? status;

  /// 是否压暗封面（未开播）。
  final bool dimmed;

  @override
  Widget build(BuildContext context) {
    return cover ? _buildCover(context) : _buildTile(context);
  }

  Widget _buildTile(BuildContext context) {
    final t = context.lh;
    return HighlightWidget(
      focusNode: focusNode,
      autofocus: autofocus,
      onTap: onTap,
      borderRadius: t.cardRadius,
      focusScale: 1.08,
      child: Padding(
        padding: EdgeInsets.all(20 * t.unit),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            NetImage(
              avatar,
              width: 96 * t.unit,
              height: 96 * t.unit,
              borderRadius: 96 * t.unit,
              cacheWidth: 160,
            ),
            SizedBox(height: 12 * t.unit),
            Text(
              name,
              style: t.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
            ),
            SizedBox(height: 6 * t.unit),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  siteName,
                  style: t.cardMeta.copyWith(color: t.faint),
                ),
                SizedBox(width: 10 * t.unit),
                if (liveStatus)
                  const LiveHubLiveDot(label: "直播中")
                else
                  Text(
                    "未开播",
                    style: t.cardMeta.copyWith(color: t.faint),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCover(BuildContext context) {
    final t = context.lh;
    const overlayShadows = [
      Shadow(color: Color(0xE6000000), blurRadius: 1),
      Shadow(color: Color(0x99000000), blurRadius: 14),
    ];
    final state = status ?? (liveStatus ? "直播中" : "未开播");
    return HighlightWidget(
      focusNode: focusNode,
      autofocus: autofocus,
      onTap: onTap,
      borderRadius: t.cardRadius,
      focusScale: 1.08,
      child: Obx(() {
        final focused = focusNode.isFoucsed.value;
        final brightness =
            dimmed ? (focused ? 0.75 : 0.5) : (focused ? 1.04 : 0.95);
        return ClipRRect(
          borderRadius: t.cardRadius,
          child: AspectRatio(
            aspectRatio: 16 / 9,
            child: Stack(
              fit: StackFit.expand,
              children: [
                ColorFiltered(
                  colorFilter: ColorFilter.matrix(
                    _brightnessSaturation(brightness, dimmed ? 0.5 : 1.0),
                  ),
                  child: avatar.isEmpty
                      ? ColoredBox(
                          color: t.panel,
                          child: Icon(
                            Icons.person_outline,
                            size: 64 * t.unit,
                            color: t.faint,
                          ),
                        )
                      : NetImage(avatar, cacheWidth: 400),
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
                        Text(
                          name,
                          style: t.meta.copyWith(
                            color: t.text,
                            fontWeight: FontWeight.w600,
                            shadows: overlayShadows,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        SizedBox(height: 4 * t.unit),
                        Row(
                          children: [
                            if (liveStatus) ...[
                              Container(
                                width: 8 * t.unit,
                                height: 8 * t.unit,
                                decoration: BoxDecoration(
                                  color: t.live,
                                  shape: BoxShape.circle,
                                ),
                              ),
                              SizedBox(width: 6 * t.unit),
                            ],
                            Flexible(
                              child: Text(
                                '$siteName · $state',
                                style: t.cardMeta.copyWith(
                                  color: Colors.white.withValues(alpha: 0.78),
                                  shadows: overlayShadows,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      }),
    );
  }

  /// 亮度 + 饱和度矩阵：未开播时封面去色压暗，焦点落在封面本身（§8.4）。
  List<double> _brightnessSaturation(double brightness, double saturation) {
    final inv = 1 - saturation;
    final r = 0.2126 * inv;
    final g = 0.7152 * inv;
    final b = 0.0722 * inv;
    return [
      (r + saturation) * brightness,
      g * brightness,
      b * brightness,
      0,
      0,
      r * brightness,
      (g + saturation) * brightness,
      b * brightness,
      0,
      0,
      r * brightness,
      g * brightness,
      (b + saturation) * brightness,
      0,
      0,
      0,
      0,
      0,
      1,
      0,
    ];
  }
}
