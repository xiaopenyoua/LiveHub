import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

class AppScaffold extends StatelessWidget {
  final Widget child;
  const AppScaffold({required this.child, Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: LiveHubBackdrop(child: child),
    );
  }
}
