import 'package:flutter/material.dart';
import 'package:lottie/lottie.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_ui/livehub_ui.dart';

class AppEmptyWidget extends StatelessWidget {
  final Function()? onRefresh;
  final String? text;
  const AppEmptyWidget({
    this.onRefresh,
    this.text,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Center(
      child: GestureDetector(
        onTap: () {
          onRefresh?.call();
        },
        child: Padding(
          padding: EdgeInsets.all(48 * t.unit),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 160 * t.unit,
                height: 160 * t.unit,
                child: Opacity(
                  opacity: 0.72,
                  child: LottieBuilder.asset(
                    'assets/lotties/empty.json',
                    repeat: false,
                  ),
                ),
              ),
              SizedBox(height: 24 * t.unit),
              Text(
                text ?? "这里什么都没有",
                textAlign: TextAlign.center,
                style: t.body.copyWith(color: t.muted),
              ),
              SizedBox(height: 24 * t.unit),
              if (onRefresh != null)
                HighlightButton(
                  text: "刷新",
                  iconData: Icons.refresh,
                  onTap: onRefresh,
                  focusNode: AppFocusNode(),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
