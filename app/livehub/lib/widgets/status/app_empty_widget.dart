import 'package:flutter/material.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub_ui/livehub_ui.dart';
import 'package:lottie/lottie.dart';

class AppEmptyWidget extends StatelessWidget {
  final Function()? onRefresh;
  const AppEmptyWidget({this.onRefresh, Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    return Center(
      child: GestureDetector(
        onTap: () {
          onRefresh?.call();
        },
        child: Padding(
          padding: AppStyle.edgeInsetsA12,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              LottieBuilder.asset(
                'assets/lotties/empty.json',
                width: 200,
                height: 200,
                repeat: false,
              ),
              Text(
                "这里什么都没有",
                textAlign: TextAlign.center,
                style: tokens.small.copyWith(color: tokens.muted),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
