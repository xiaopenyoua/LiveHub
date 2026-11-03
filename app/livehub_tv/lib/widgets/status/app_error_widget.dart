import 'package:flutter/material.dart';
import 'package:lottie/lottie.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 错误页。有 [onRefresh] 时给一个可聚焦的重试按钮——
/// 电视上不能只靠文字提示，遥控器需要一个能按下去的出口。
class AppErrorWidget extends StatefulWidget {
  final Function()? onRefresh;
  final String errorMsg;
  const AppErrorWidget({this.errorMsg = "", this.onRefresh, Key? key})
      : super(key: key);

  @override
  State<AppErrorWidget> createState() => _AppErrorWidgetState();
}

class _AppErrorWidgetState extends State<AppErrorWidget> {
  final AppFocusNode _retryNode = AppFocusNode();

  @override
  void dispose() {
    _retryNode.dispose();
    super.dispose();
  }

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
              widget.errorMsg.isEmpty ? "出错了，请稍后重试" : widget.errorMsg,
              textAlign: TextAlign.center,
              style: t.body.copyWith(color: t.muted),
            ),
            if (widget.onRefresh != null) ...[
              SizedBox(height: 24 * t.unit),
              HighlightButton(
                text: "重试",
                iconData: Icons.refresh,
                focusNode: _retryNode,
                autofocus: true,
                onTap: widget.onRefresh,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
