import 'package:flutter/material.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_tv/widgets/net_image.dart';
import 'package:livehub_ui/livehub_ui.dart';

class AnchorCard extends StatelessWidget {
  final String siteId;
  final String face;
  final String name;
  final String roomId;
  final int liveStatus;
  final bool autofocus;
  final Function()? onTap;
  final AppFocusNode? focusNode;
  const AnchorCard({
    required this.face,
    required this.siteId,
    required this.name,
    required this.liveStatus,
    required this.roomId,
    this.autofocus = false,
    this.focusNode,
    this.onTap,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    var site = Sites.allSites[siteId]!;
    var focusNode = this.focusNode ?? AppFocusNode();
    return HighlightWidget(
      onTap: onTap ??
          () {
            AppNavigator.toLiveRoomDetail(site: site, roomId: roomId);
          },
      focusNode: focusNode,
      autofocus: autofocus,
      borderRadius: t.cardRadius,
      focusScale: 1.08,
      child: Padding(
        padding: EdgeInsets.all(20 * t.unit),
        child: Row(
          mainAxisSize: MainAxisSize.max,
          children: [
            NetImage(
              face,
              width: 100 * t.unit,
              height: 100 * t.unit,
              borderRadius: 100 * t.unit,
              cacheWidth: 100,
            ),
            SizedBox(width: 16 * t.unit),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    name,
                    style: t.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  SizedBox(height: 4 * t.unit),
                  Row(
                    children: [
                      Text(
                        site.shortName,
                        style: t.cardMeta.copyWith(color: t.faint),
                      ),
                      if (liveStatus == 2) ...[
                        SizedBox(width: 12 * t.unit),
                        const LiveHubLiveDot(label: "直播中"),
                      ],
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
