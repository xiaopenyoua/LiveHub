import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/sites.dart';
import 'package:livehub_ui/livehub_ui.dart';
import 'package:livehub/modules/category/category_controller.dart';
import 'package:livehub/modules/category/category_list_view.dart';

class CategoryPage extends GetView<CategoryController> {
  const CategoryPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 8,
        title: TabBar(
          controller: controller.tabController,
          padding: EdgeInsets.zero,
          tabAlignment: TabAlignment.center,
          tabs: Sites.supportSites
              .map(
                (e) => Tab(
                  text: e.shortName,
                ),
              )
              .toList(),
          labelPadding: AppStyle.edgeInsetsH20,
          isScrollable: true,
          indicatorSize: TabBarIndicatorSize.tab,
          indicatorPadding:
              const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          indicator: BoxDecoration(
            color: context.lh.palette.text.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(999),
          ),
        ),
      ),
      body: TabBarView(
        controller: controller.tabController,
        children: Sites.supportSites
            .map(
              (e) => CategoryListView(
                e.id,
              ),
            )
            .toList(),
      ),
    );
  }
}
