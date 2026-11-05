import 'package:get/get.dart';
import 'package:livehub/app/controller/base_controller.dart';
import 'package:livehub/app/sites.dart';

class SearchListController extends BasePageController {
  String keyword = "";

  /// 搜索模式，0=直播间，1=主播
  var searchMode = 0.obs;
  final Site site;
  SearchListController(
    this.site,
  );

  @override
  Future refreshData() async {
    if (keyword.isEmpty) {
      return;
    }
    return await super.refreshData();
  }

  @override
  Future<List> getData(int page, int pageSize) async {
    // 只有空白的关键词不往下发：斗鱼对空 kw 会回“【kw】kw不能为空”，
    // 其它平台也会白跑一次请求。
    final kw = keyword.trim();
    if (kw.isEmpty) {
      return [];
    }
    if (searchMode.value == 1) {
      // 搜索主播
      var result = await site.liveSite.searchAnchors(kw, page: page);
      return result.items;
    }
    var result = await site.liveSite.searchRooms(kw, page: page);

    return result.items;
  }

  void clear() {
    pageEmpty.value = false;
    list.clear();
  }
}
