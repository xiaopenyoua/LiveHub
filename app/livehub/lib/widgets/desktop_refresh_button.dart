import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

class DesktopRefreshButton extends StatelessWidget {
  final bool refreshing;
  final Function()? onPressed;
  const DesktopRefreshButton(
      {required this.refreshing, this.onPressed, super.key});

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    return SizedBox(
      width: 40,
      height: 40,
      child: LiveHubGlass(
        level: 0.10,
        radius: tokens.pillRadius,
        child: Material(
          color: Colors.transparent,
          child: refreshing
              ? Center(
                  child: SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: tokens.text,
                    ),
                  ),
                )
              : IconButton(
                  onPressed: onPressed,
                  icon: const Icon(Icons.refresh),
                ),
        ),
      ),
    );
  }
}
