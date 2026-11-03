import 'package:flutter/material.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/sites.dart';
import 'package:livehub/app/utils.dart';
import 'package:livehub/routes/app_navigation.dart';
import 'package:livehub/widgets/net_image.dart';
import 'package:livehub/widgets/shadow_card.dart';
import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 卡片是画面，不是信息块（设计规范 §8.4）。
///
/// 整张卡只有封面；平台名在左上角，标题与「主播 · 人数」压在画面下沿，
/// 靠字幕底站住。平台名不挂底、不用品牌色；在播状态用一枚红点。
class LiveRoomCard extends StatelessWidget {
  final Site site;
  final LiveRoomItem item;
  const LiveRoomCard(this.site, this.item, {Key? key}) : super(key: key);

  static const _textShadows = [
    Shadow(blurRadius: 1, color: Color(0xCC000000)),
    Shadow(blurRadius: 14, color: Color(0x66000000)),
  ];

  /// 压在封面上的字永远是暖白：字幕底固定深色，与浅色主题无关。
  static const _onCoverText = Color(0xFFFFFDFA);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    // 卡片内是专用紧凑字阶：手机两列时卡片只有约 170dp 宽，
    // 直接套页面字阶会让两行标题顶到平台名/LIVE 标签上。
    final cardTitle = tokens.title.copyWith(
      fontSize: tokens.ramp.title * 0.84,
      height: 1.2,
    );
    final cardMeta = tokens.cardMeta.copyWith(
      fontSize: tokens.ramp.cardMeta * 0.92,
      height: 1.35,
    );
    final radius = BorderRadius.circular(LiveHubRadii.card);
    final online = Utils.onlineToString(item.online);
    final meta = item.userName.isEmpty ? online : '${item.userName} · $online';
    return ShadowCard(
      radius: LiveHubRadii.card,
      onTap: () {
        AppNavigator.toLiveRoomDetail(site: site, roomId: item.roomId);
      },
      child: ClipRRect(
        borderRadius: radius,
        child: AspectRatio(
          aspectRatio: 16 / 9,
          child: Stack(
            fit: StackFit.expand,
            children: [
              NetImage(
                item.cover,
                fit: BoxFit.cover,
              ),
              Positioned.fill(
                child: LiveHubCardScrim(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.end,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: cardTitle.copyWith(
                            color: _onCoverText,
                            shadows: _textShadows,
                          ),
                        ),
                        AppStyle.vGap4,
                        Text(
                          meta,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: cardMeta.copyWith(
                            color: _onCoverText.withValues(alpha: 0.78),
                            shadows: _textShadows,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              Positioned(
                left: 12,
                top: 12,
                child: Text(
                  site.shortName,
                  style: cardMeta.copyWith(
                    color: _onCoverText,
                    shadows: _textShadows,
                  ),
                ),
              ),
              Positioned(
                right: 12,
                top: 12,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 6,
                      height: 6,
                      decoration: BoxDecoration(
                        color: LiveHubPalette.dark.live,
                        shape: BoxShape.circle,
                      ),
                    ),
                    AppStyle.hGap4,
                    Text(
                      'LIVE',
                      style: cardMeta.copyWith(
                        color: _onCoverText,
                        letterSpacing: 0.14 * tokens.ramp.cardMeta * tokens.unit,
                        fontWeight: FontWeight.w600,
                        shadows: _textShadows,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
