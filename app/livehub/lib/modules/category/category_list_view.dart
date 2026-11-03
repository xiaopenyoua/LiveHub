import 'package:flutter/material.dart';
import 'package:flutter_easyrefresh/easy_refresh.dart';

import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/modules/category/category_list_controller.dart';
import 'package:livehub/routes/app_navigation.dart';
import 'package:livehub/widgets/keep_alive_wrapper.dart';
import 'package:livehub/widgets/net_image.dart';
import 'package:livehub/widgets/shadow_card.dart';
import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_ui/livehub_ui.dart';
import 'package:sticky_headers/sticky_headers.dart';

class CategoryListView extends StatelessWidget {
  final String tag;
  const CategoryListView(this.tag, {Key? key}) : super(key: key);
  CategoryListController get controller =>
      Get.find<CategoryListController>(tag: tag);
  @override
  Widget build(BuildContext context) {
    return KeepAliveWrapper(
      child: Obx(
        () => EasyRefresh(
          firstRefresh: true,
          controller: controller.easyRefreshController,
          onRefresh: controller.refreshData,
          header: MaterialHeader(
            completeDuration: const Duration(milliseconds: 400),
          ),
          child: ListView.builder(
            padding: AppStyle.edgeInsetsA12,
            itemCount: controller.list.length,
            controller: controller.scrollController,
            itemBuilder: (_, i) {
              var item = controller.list[i];
              return Column(
                children: [
                  StickyHeader(
                    header: Container(
                      padding: AppStyle.edgeInsetsV8.copyWith(left: 4),
                      color: context.lh.ground,
                      alignment: Alignment.centerLeft,
                      child: Text(
                        item.name,
                        style: context.lh.h2,
                      ),
                    ),
                    content: Obx(
                      () => GridView.count(
                        shrinkWrap: true,
                        padding: AppStyle.edgeInsetsV8,
                        physics: const NeverScrollableScrollPhysics(),
                        crossAxisCount: MediaQuery.of(context).size.width ~/ 80,
                        crossAxisSpacing: 8,
                        mainAxisSpacing: 8,
                        children: item.showAll.value
                            ? (item.children
                                .map(
                                  (e) => buildSubCategory(context, e),
                                )
                                .toList())
                            : (item.take15
                                .map(
                                  (e) => buildSubCategory(context, e),
                                )
                                .toList()
                              ..add(buildShowMore(context, item))),
                      ),
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  Widget buildSubCategory(
      BuildContext context, LiveSubCategory item) {
    return ShadowCard(
      onTap: () {
        AppNavigator.toCategoryDetail(site: controller.site, category: item);
      },
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          NetImage(
            item.pic ?? "",
            width: 40,
            height: 40,
            borderRadius: 8,
          ),
          AppStyle.vGap4,
          Text(
            item.name,
            maxLines: 1,
            textAlign: TextAlign.center,
            style: context.lh.cardMeta,
          ),
        ],
      ),
    );
  }

  Widget buildShowMore(
      BuildContext context, AppLiveCategory item) {
    return ShadowCard(
      onTap: () {
        item.showAll.value = true;
      },
      child: Center(
        child: Text(
          "显示全部",
          maxLines: 1,
          textAlign: TextAlign.center,
          style: context.lh.cardMeta.copyWith(color: context.lh.muted),
        ),
      ),
    );
  }
}
