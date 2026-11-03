import 'package:flutter/cupertino.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub_ui/livehub_ui.dart';

class AppLoaddingWidget extends StatelessWidget {
  const AppLoaddingWidget({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    return Center(
      child: LiveHubGlass(
        level: 0.10,
        radius: BorderRadius.circular(999),
        padding: AppStyle.edgeInsetsA12,
        child: CupertinoActivityIndicator(
          radius: 10,
          color: tokens.text,
        ),
      ),
    );
  }
}
