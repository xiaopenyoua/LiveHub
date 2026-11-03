import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SettingsItemWidget extends StatelessWidget {
  final AppFocusNode foucsNode;
  final Map<dynamic, String> items;
  final dynamic value;
  final String title;
  final bool autofocus;
  final Function(dynamic) onChanged;
  const SettingsItemWidget({
    required this.foucsNode,
    required this.items,
    required this.value,
    required this.title,
    required this.onChanged,
    this.autofocus = false,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return HighlightWidget(
      focusNode: foucsNode,
      autofocus: autofocus,
      borderRadius: t.listRadius,
      focusScale: 1.02,
      onLeftKey: () {
        if (items.isEmpty) return KeyEventResult.handled;
        if (items.keys.first == value) {
          onChanged(items.keys.last);
        } else {
          onChanged(
              items.keys.elementAt(items.keys.toList().indexOf(value) - 1));
        }
        return KeyEventResult.handled;
      },
      onRightKey: () {
        if (items.isEmpty) return KeyEventResult.handled;
        if (items.keys.last == value) {
          onChanged(items.keys.first);
        } else {
          onChanged(
              items.keys.elementAt(items.keys.toList().indexOf(value) + 1));
        }
        return KeyEventResult.handled;
      },
      onTap: () {
        showSettingsDialog();
      },
      child: Padding(
        padding: EdgeInsets.all(24 * t.unit),
        child: Row(
          children: [
            Text(
              title,
              style: t.body.copyWith(fontWeight: FontWeight.w500),
            ),
            const Spacer(),
            if (foucsNode.isFoucsed.value && items.isNotEmpty)
              Icon(
                Icons.chevron_left,
                size: 40 * t.unit,
                color: t.muted,
              ),
            SizedBox(width: 12 * t.unit),
            ConstrainedBox(
              constraints: BoxConstraints(minWidth: 120 * t.unit),
              child: Text(
                items[value] ?? '',
                style: t.body.copyWith(color: t.muted),
                textAlign:
                    foucsNode.isFoucsed.value ? TextAlign.center : TextAlign.right,
              ),
            ),
            SizedBox(width: 12 * t.unit),
            if (foucsNode.isFoucsed.value && items.isNotEmpty)
              Icon(
                Icons.chevron_right,
                size: 40 * t.unit,
                color: t.muted,
              ),
          ],
        ),
      ),
    );
  }

  void showSettingsDialog() {
    final t = Get.context!.lh;
    Get.dialog(
      AlertDialog(
        backgroundColor: LiveHubPalette.dark.raised,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: t.panelRadius),
        title: Text(title, style: t.h2),
        scrollable: true,
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: items.keys.map((e) {
            return ListTile(
              title: Text(items[e] ?? '', style: t.body),
              contentPadding: EdgeInsets.symmetric(
                horizontal: 20 * t.unit,
              ),
              autofocus: e == value,
              shape: RoundedRectangleBorder(
                borderRadius: t.listRadius,
              ),
              selected: e == value,
              selectedTileColor: Colors.white.withValues(alpha: 0.10),
              focusColor: Colors.white.withValues(alpha: 0.16),
              onTap: () {
                onChanged(e);
                Get.back();
              },
            );
          }).toList(),
        ),
      ),
    );
  }
}
