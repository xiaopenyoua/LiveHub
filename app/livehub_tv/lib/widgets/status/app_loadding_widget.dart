import 'package:flutter/cupertino.dart';
import 'package:livehub_ui/livehub_ui.dart';

class AppLoaddingWidget extends StatelessWidget {
  const AppLoaddingWidget({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return Center(
      child: Container(
        padding: EdgeInsets.all(12 * t.unit),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: t.raised,
        ),
        child: CupertinoActivityIndicator(
          radius: 10 * t.unit,
          color: t.muted,
        ),
      ),
    );
  }
}
