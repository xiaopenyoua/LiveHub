import 'package:flutter/material.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 筛选标签：一枚可控的玻璃药丸。
///
/// 选中 = 同一条灰阶上厚一档（§5.1），不翻白、不加描边。
class FilterButton extends StatelessWidget {
  final bool selected;
  final String text;
  final Function()? onTap;
  const FilterButton({
    this.selected = false,
    required this.text,
    this.onTap,
    Key? key,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    return GestureDetector(
      onTap: onTap,
      child: LiveHubGlass(
        level: selected ? 0.22 : 0.10,
        radius: tokens.pillRadius,
        padding: EdgeInsets.symmetric(
          horizontal: tokens.px(LiveHubSpacing.md),
          vertical: tokens.px(LiveHubSpacing.xs),
        ),
        child: Text(
          text,
          style: tokens.meta.copyWith(
            color: selected ? tokens.text : tokens.muted,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
          ),
        ),
      ),
    );
  }
}
