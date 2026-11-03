import 'package:flutter/material.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_tv/widgets/net_image.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/modules/category/category_controller.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_ui/livehub_ui.dart';

class CategoryPage extends GetView<CategoryController> {
  const CategoryPage({super.key});

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return AppScaffold(
      child: Column(
        children: [
          SizedBox(height: 32 * t.unit),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SizedBox(width: 48 * t.unit),
              HighlightButton(
                focusNode: AppFocusNode(),
                iconData: Icons.arrow_back,
                text: "返回",
                autofocus: true,
                onTap: () {
                  Get.back();
                },
              ),
              SizedBox(width: 32 * t.unit),
              Text(
                "直播类目",
                style: t.h2,
              ),
              SizedBox(width: 24 * t.unit),
              const Spacer(),
              Obx(
                () => Visibility(
                  visible: controller.loadding.value,
                  child: SizedBox(
                    width: 48 * t.unit,
                    height: 48 * t.unit,
                    child: CircularProgressIndicator(
                      color: t.text,
                      strokeWidth: 4 * t.unit,
                    ),
                  ),
                ),
              ),
              SizedBox(width: 48 * t.unit),
            ],
          ),
          SizedBox(height: 24 * t.unit),
          Wrap(
            alignment: WrapAlignment.center,
            spacing: 36 * t.unit,
            children: Sites.supportSites
                .map(
                  (e) => Obx(
                    () => HighlightButton(
                      text: e.shortName,
                      selected: controller.siteId.value == e.id,
                      focusNode: AppFocusNode(),
                      onTap: () {
                        controller.setSite(e.id);
                      },
                    ),
                  ),
                )
                .toList(),
          ),
          SizedBox(height: 24 * t.unit),
          Expanded(
            child: Obx(
              () => ListView.builder(
                padding: EdgeInsets.symmetric(horizontal: 48 * t.unit),
                itemCount: controller.list.length,
                controller: controller.scrollController,
                itemBuilder: (_, i) {
                  var item = controller.list[i];
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Padding(
                        padding: EdgeInsets.symmetric(vertical: 32 * t.unit),
                        child: Text(
                          item.name,
                          style: t.h2,
                        ),
                      ),
                      Obx(
                        () => GridView.count(
                          shrinkWrap: true,
                          padding: EdgeInsets.symmetric(vertical: 8 * t.unit),
                          physics: const NeverScrollableScrollPhysics(),
                          crossAxisCount: 8,
                          crossAxisSpacing: 36 * t.unit,
                          mainAxisSpacing: 36 * t.unit,
                          children: item.showAll.value
                              ? (item.childrenExt
                                  .map(
                                    (e) => buildSubCategory(e),
                                  )
                                  .toList())
                              : (item.take15
                                  .map(
                                    (e) => buildSubCategory(e),
                                  )
                                  .toList()
                                ..add(buildShowMore(item))),
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget buildSubCategory(LiveSubCategoryExt item) {
    final t = Get.context!.lh;
    return HighlightWidget(
      focusNode: item.focusNode,
      onTap: () {
        AppNavigator.toCategoryDetail(site: controller.site, category: item);
      },
      borderRadius: t.cardRadius,
      focusScale: 1.06,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          (item.pic != null && item.pic!.isNotEmpty)
              ? NetImage(
                  item.pic ?? "",
                  width: 64 * t.unit,
                  height: 64 * t.unit,
                  borderRadius: 16 * t.unit,
                  cacheWidth: 100,
                )
              : Icon(
                  Icons.category_outlined,
                  size: 64 * t.unit,
                  color: t.faint,
                ),
          SizedBox(height: 12 * t.unit),
          Text(
            item.name,
            maxLines: 1,
            textAlign: TextAlign.center,
            style: t.body.copyWith(color: t.text),
          ),
        ],
      ),
    );
  }

  Widget buildShowMore(AppLiveCategory item) {
    final t = Get.context!.lh;
    return HighlightWidget(
      focusNode: item.moreFocusNode,
      onTap: () {
        item.showAll.value = true;
      },
      borderRadius: t.cardRadius,
      focusScale: 1.06,
      child: Center(
        child: Text(
          "显示全部",
          maxLines: 1,
          textAlign: TextAlign.center,
          style: t.body.copyWith(color: t.muted),
        ),
      ),
    );
  }
}
