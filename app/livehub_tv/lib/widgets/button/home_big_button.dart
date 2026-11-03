import 'package:flutter/material.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

class HomeBigButton extends StatelessWidget {
  final String text;
  final IconData iconData;
  final AppFocusNode focusNode;
  final Function()? onTap;
  final bool autofocus;
  const HomeBigButton({
    required this.iconData,
    required this.text,
    this.onTap,
    required this.focusNode,
    this.autofocus = false,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return HighlightWidget(
        onTap: onTap,
        autofocus: autofocus,
        focusNode: focusNode,
        borderRadius: t.cardRadius,
        focusScale: 1.06,
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            48 * t.unit,
            32 * t.unit,
            48 * t.unit,
            32 * t.unit,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                iconData,
                size: 64 * t.unit,
                color: t.text,
              ),
              SizedBox(height: 24 * t.unit),
              Text(
                text,
                style: t.h2,
              ),
            ],
          ),
        ),
    );
  }
}
