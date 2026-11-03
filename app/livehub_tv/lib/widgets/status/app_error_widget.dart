import 'package:flutter/material.dart';
import 'package:lottie/lottie.dart';
import 'package:livehub_ui/livehub_ui.dart';

class AppErrorWidget extends StatelessWidget {
  final Function()? onRefresh;
  final String errorMsg;
  const AppErrorWidget({this.errorMsg = "", this.onRefresh, Key? key})
      : super(key: key);

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Center(
      child: Padding(
        padding: EdgeInsets.all(12 * t.unit),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(
              width: 200 * t.unit,
              child: Opacity(
                opacity: 0.72,
                child: LottieBuilder.asset(
                  'assets/lotties/error.json',
                  repeat: false,
                ),
              ),
            ),
            SizedBox(height: 24 * t.unit),
            Text(
              errorMsg.isEmpty ? "出错了，请稍后重试" : errorMsg,
              textAlign: TextAlign.center,
              style: t.body.copyWith(color: t.muted),
            ),
          ],
        ),
      ),
    );
  }
}
