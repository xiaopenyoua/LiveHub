import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub_ui/livehub_ui.dart';

import 'indexed_controller.dart';

class IndexedPage extends GetView<IndexedController> {
  const IndexedPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    return OrientationBuilder(
      builder: (context, orientation) {
        return Scaffold(
          body: Row(
            children: [
              Visibility(
                visible: orientation == Orientation.landscape,
                child: LiveHubGlass(
                  level: 0.062,
                  radius: BorderRadius.zero,
                  child: Obx(
                    () => NavigationRail(
                      selectedIndex: controller.index.value,
                      onDestinationSelected: controller.setIndex,
                      labelType: NavigationRailLabelType.none,
                      backgroundColor: Colors.transparent,
                      indicatorColor: tokens.glassTint,
                      selectedIconTheme:
                          IconThemeData(color: tokens.text, size: 24),
                      unselectedIconTheme:
                          IconThemeData(color: tokens.palette.faint, size: 24),
                      destinations: controller.items
                          .map(
                            (item) => NavigationRailDestination(
                              icon: Icon(item.iconData),
                              label: Text(item.title),
                              padding: AppStyle.edgeInsetsV8,
                            ),
                          )
                          .toList(),
                    ),
                  ),
                ),
              ),
              Expanded(
                child: Obx(
                  () => IndexedStack(
                    index: controller.index.value,
                    children: controller.pages,
                  ),
                ),
              ),
            ],
          ),
          bottomNavigationBar: Visibility(
            visible: orientation == Orientation.portrait,
            child: LiveHubGlass(
              level: 0.10,
              radius: BorderRadius.zero,
              child: Obx(
                () => NavigationBar(
                  selectedIndex: controller.index.value,
                  onDestinationSelected: controller.setIndex,
                  height: 56,
                  backgroundColor: Colors.transparent,
                  labelBehavior: NavigationDestinationLabelBehavior.alwaysHide,
                  destinations: controller.items
                      .map(
                        (item) => NavigationDestination(
                          icon: Icon(item.iconData),
                          label: item.title,
                        ),
                      )
                      .toList(),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
