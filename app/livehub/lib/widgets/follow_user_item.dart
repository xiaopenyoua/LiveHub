import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:remixicon/remixicon.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/log.dart';
import 'package:livehub/app/sites.dart';
import 'package:livehub/models/db/follow_user.dart';
import 'package:livehub/widgets/net_image.dart';
import 'package:livehub_ui/livehub_ui.dart';
import 'dart:ui' as ui;

class FollowUserItem extends StatelessWidget {
  final FollowUser item;
  final Function()? onRemove;
  final Function()? onTap;
  final Function()? onLongPress;
  final bool playing;
  const FollowUserItem({
    required this.item,
    this.onRemove,
    this.onTap,
    this.onLongPress,
    this.playing = false,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    final faint = tokens.palette.faint;
    var site = Sites.allSites[item.siteId]!;
    return LiveHubGlass(
      level: playing ? 0.10 : 0.062,
      radius: tokens.listRadius,
      child: Material(
        color: Colors.transparent,
        child: ListTile(
          contentPadding: AppStyle.edgeInsetsL16.copyWith(right: 4),
          leading: NetImage(
            item.face,
            width: 48,
            height: 48,
            borderRadius: 24,
          ),
          title: Text.rich(
            TextSpan(
              text: item.userName,
              style: tokens.body.copyWith(
                color: tokens.text,
                fontWeight: FontWeight.w500,
              ),
              children: [
                WidgetSpan(
                  alignment: ui.PlaceholderAlignment.middle,
                  child: Obx(
                    () => Offstage(
                      offstage: item.liveStatus.value == 0,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          AppStyle.hGap12,
                          Container(
                            width: 8,
                            height: 8,
                            decoration: BoxDecoration(
                              color: item.liveStatus.value == 2
                                  ? tokens.live
                                  : faint,
                              shape: BoxShape.circle,
                            ),
                          ),
                          AppStyle.hGap4,
                          Text(
                            getStatus(item.liveStatus.value),
                            style: tokens.cardMeta.copyWith(
                              fontWeight: FontWeight.normal,
                              color: item.liveStatus.value == 2
                                  ? tokens.live
                                  : faint,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          subtitle: Wrap(
            runSpacing: 1.0,
            children: [
              Text(
                site.shortName,
                style: tokens.cardMeta.copyWith(color: faint),
                overflow: TextOverflow.ellipsis,
              ),
              if (playing)
                Padding(
                  padding: AppStyle.edgeInsetsL8,
                  child: Text(
                    "正在观看",
                    style: tokens.cardMeta.copyWith(
                      color: tokens.text,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                )
              else if (item.liveStatus.value == 2 && item.liveStartTime != null)
                Padding(
                  padding: AppStyle.edgeInsetsL8,
                  child: Text(
                    '开播了${formatLiveDuration(item.liveStartTime)}',
                    style: tokens.cardMeta.copyWith(color: faint),
                  ),
                ),
            ],
          ),
          trailing: playing
              ? SizedBox(
                  width: 64,
                  child: Center(
                    child: Icon(
                      Icons.play_arrow,
                      color: tokens.live,
                    ),
                  ),
                )
              : (onRemove == null
                  ? null
                  : IconButton(
                      onPressed: () {
                        onRemove?.call();
                      },
                      icon: Icon(Remix.dislike_line, color: faint),
                    )),
          onTap: onTap,
          onLongPress: onLongPress,
        ),
      ),
    );
  }

  String getStatus(int status) {
    if (status == 0) {
      return "读取中";
    } else if (status == 1) {
      return "未开播";
    } else {
      return "直播中";
    }
  }

  String formatLiveDuration(String? startTimeStampString) {
    if (startTimeStampString == null ||
        startTimeStampString.isEmpty ||
        startTimeStampString == "0") {
      return "";
    }
    try {
      int startTimeStamp = int.parse(startTimeStampString);
      int currentTimeStamp = DateTime.now().millisecondsSinceEpoch ~/ 1000;
      int durationInSeconds = currentTimeStamp - startTimeStamp;

      int hours = durationInSeconds ~/ 3600;
      int minutes = (durationInSeconds % 3600) ~/ 60;

      String hourText = hours > 0 ? '$hours小时' : '';
      String minuteText = minutes > 0 ? '$minutes分钟' : '';

      if (hours == 0 && minutes == 0) {
        return "不足1分钟";
      }

      return '$hourText$minuteText';
    } catch (e) {
      Log.logPrint('格式化开播时长出错: $e');
      return "--小时--分钟";
    }
  }
}
