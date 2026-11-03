import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:livehub_tv/app/controller/base_controller.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/models/live_room_item_ext.dart';
import 'package:livehub_tv/modules/category/category_controller.dart';

class CategoryDetailController extends BasePageController<LiveRoomItemExt> {
  final Site site;
  final LiveSubCategoryExt subCategory;
  CategoryDetailController({
    required this.site,
    required this.subCategory,
  });

  @override
  void onInit() {
    scrollController.addListener(scrollListener);
    refreshData();
    super.onInit();
  }

  void scrollListener() {
    if (scrollController.position.pixels >=
        (scrollController.position.maxScrollExtent - 100.w)) {
      loadData();
    }
  }

  @override
  Future<List<LiveRoomItemExt>> getData(int page, int pageSize) async {
    var result = await site.liveSite.getCategoryRooms(subCategory, page: page);
    return result.items
        .map(
          (e) => LiveRoomItemExt(
            roomId: e.roomId,
            title: e.title,
            cover: e.cover,
            userName: e.userName,
            online: e.online,
            site: site,
          ),
        )
        .toList();
  }

  @override
  void onClose() {
    scrollController.removeListener(scrollListener);
    super.onClose();
  }
}
