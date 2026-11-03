import 'dart:async';

import 'package:flutter/material.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/utils.dart';
import 'package:livehub/widgets/net_image.dart';
import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SuperChatCard extends StatefulWidget {
  final LiveSuperChatMessage message;
  final Function()? onExpire;
  final int? customCountdown;
  const SuperChatCard(
    this.message, {
    required this.onExpire,
    this.customCountdown,
    Key? key,
  }) : super(key: key);

  @override
  State<SuperChatCard> createState() => _SuperChatCardState();
}

class _SuperChatCardState extends State<SuperChatCard> {
  late Timer timer;

  int countdown = 0;

  @override
  void initState() {
    var currentTime = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    var endTime = widget.message.endTime.millisecondsSinceEpoch ~/ 1000;

    countdown = endTime - currentTime;

    timer = Timer.periodic(const Duration(seconds: 1), timerCallback);

    super.initState();
  }

  void timerCallback(e) {
    if (countdown <= 0) {
      widget.onExpire?.call();
      timer.cancel();
      return;
    }

    setState(() {
      countdown -= 1;
    });
  }

  @override
  Widget build(BuildContext context) {
    final displayCountdown = widget.customCountdown ?? countdown;
    return ClipRRect(
      borderRadius: BorderRadius.circular(LiveHubRadii.listItem),
      child: Container(
        decoration: BoxDecoration(
          color: Utils.convertHexColor(widget.message.backgroundColor),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: AppStyle.edgeInsetsA8,
              child: Row(
                children: [
                  NetImage(
                    widget.message.face,
                    width: 48,
                    height: 48,
                    borderRadius: 36,
                  ),
                  AppStyle.hGap12,
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(
                          widget.message.userName,
                          style: const TextStyle(
                            color: AppColors.black333,
                          ),
                        ),
                        Text(
                          "￥${widget.message.price}",
                          style: TextStyle(
                            fontSize: 12,
                            color: AppColors.black333.withValues(alpha: 0.7),
                          ),
                        ),
                      ],
                    ),
                  ),
                  Text(
                    "$displayCountdown",
                    style: TextStyle(
                      fontSize: 14,
                      color: AppColors.black333.withValues(alpha: 0.7),
                    ),
                  ),
                ],
              ),
            ),
            Container(
              decoration: BoxDecoration(
                color:
                    Utils.convertHexColor(widget.message.backgroundBottomColor),
              ),
              padding: AppStyle.edgeInsetsA8,
              child: Text(
                widget.message.message,
                style: const TextStyle(color: Colors.white),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    timer.cancel();
    super.dispose();
  }
}
