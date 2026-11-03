import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/sites.dart';
import 'package:livehub_ui/livehub_ui.dart';
import 'package:livehub/modules/search/search_controller.dart';
import 'package:livehub/modules/search/search_list_view.dart';

class SearchPage extends GetView<AppSearchController> {
  const SearchPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        title: TextField(
          controller: controller.searchController,
          autofocus: true,
          decoration: InputDecoration(
            hintText: "搜点什么吧",
            border: OutlineInputBorder(
              borderRadius: AppStyle.radius24,
            ),
            contentPadding: AppStyle.edgeInsetsH12,
            prefixIcon: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  onPressed: Get.back,
                  icon: const Icon(Icons.arrow_back),
                ),
                Obx(
                  () => DropdownButton<int>(
                    underline: const SizedBox(),
                    items: const [
                      DropdownMenuItem(
                        value: 0,
                        child: Text("房间"),
                      ),
                      DropdownMenuItem(
                        value: 1,
                        child: Text("主播"),
                      ),
                    ],
                    value: controller.searchMode.value,
                    onChanged: (e) {
                      controller.searchMode.value = e ?? 0;
                      controller.doSearch();
                    },
                  ),
                ),
                AppStyle.hGap8,
              ],
            ),
            suffixIcon: IconButton(
              onPressed: controller.doSearch,
              icon: const Icon(Icons.search),
            ),
          ),
          onSubmitted: (e) {
            controller.doSearch();
          },
        ),
        bottom: TabBar(
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
        physics: const NeverScrollableScrollPhysics(),
        controller: controller.tabController,
        children: Sites.supportSites
            .map((e) => SearchListView(
                      e.id,
                    )
                // (e) => e.id == Constant.kDouyin
                //     ? const DouyinSearchView()
                //     : SearchListView(
                //         e.id,
                //       ),
                )
            .toList(),
      ),
    );
  }
}
