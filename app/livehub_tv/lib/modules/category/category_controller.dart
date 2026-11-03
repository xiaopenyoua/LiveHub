import 'package:flutter/widgets.dart';
import 'package:get/get.dart';
import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/models/live_room_item_ext.dart';

/// 分类页：左栏选平台，右栏选分类并直接看直播间。
///
/// 设计稿 browse.png 是“平台 + 分类 → 直播间卡片”的单层结构，
/// 所以这里不再走“子分类磁贴 → 分类详情页”的第二次下钻。
class CategoryController extends GetxController {
  /// 左栏“全部平台”的哨兵值。
  static const String kAllPlatforms = "all";

  /// 当前选中的平台；[kAllPlatforms] 表示全部平台。
  final platformId = kAllPlatforms.obs;

  /// 0 = 全部，1..n 对应 [categories] 的某一项。
  final selectedCategoryIndex = 0.obs;

  /// 当前平台的分类。接口真正能查房间的是二级分类，
  /// 直接把它当 chips，才能一层到达直播间。
  final categories = <LiveSubCategoryExt>[].obs;

  /// 右栏网格数据。
  final rooms = <LiveRoomItemExt>[].obs;

  final loadingRooms = false.obs;
  final errorMsg = "".obs;

  final ScrollController scrollController = ScrollController();
  final AppFocusNode allPlatformFocusNode = AppFocusNode();
  final AppFocusNode retryFocusNode = AppFocusNode();

  final List<AppFocusNode> _categoryNodes = [];
  final Map<String, List<LiveRoomItemExt>> _platformRooms = {};
  final List<LiveRoomItemExt> _allRooms = [];

  int _categoryRequest = 0;
  int _roomRequest = 0;

  bool get isAllPlatforms => platformId.value == kAllPlatforms;

  Site? get site =>
      isAllPlatforms ? null : Sites.allSites[platformId.value];

  LiveSubCategoryExt? get selectedCategory {
    final index = selectedCategoryIndex.value;
    if (index <= 0 || index > categories.length) return null;
    return categories[index - 1];
  }

  @override
  void onInit() {
    super.onInit();
    _loadPlatformRooms();
  }

  @override
  void onClose() {
    allPlatformFocusNode.dispose();
    retryFocusNode.dispose();
    for (final node in _categoryNodes) {
      node.dispose();
    }
    scrollController.dispose();
    super.onClose();
  }

  /// 分类 chips 的焦点节点按位置复用，不在 build 里新建。
  AppFocusNode categoryFocusNode(int index) {
    while (_categoryNodes.length <= index) {
      _categoryNodes.add(AppFocusNode());
    }
    return _categoryNodes[index];
  }

  Future<void> selectPlatform(String id) async {
    if (platformId.value == id) return;
    platformId.value = id;
    selectedCategoryIndex.value = 0;
    _resetScroll();
    await _loadCategories();
    await _applySelection();
  }

  Future<void> selectCategory(int index) async {
    if (selectedCategoryIndex.value == index) return;
    selectedCategoryIndex.value = index;
    _resetScroll();
    await _applySelection();
  }

  Future<void> retry() => _applySelection();

  /// 四个平台的推荐列表一次取回：既当“全部平台 / 全部”的内容，
  /// 也当左栏每个平台的计数来源。
  Future<void> _loadPlatformRooms() async {
    loadingRooms.value = true;
    final entries = await Future.wait(
      Sites.supportSites.map((site) async {
        try {
          final result = await site.liveSite.getRecommendRooms(page: 1);
          final items = result.items
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
          return MapEntry<String, List<LiveRoomItemExt>>(site.id, items);
        } catch (_) {
          return MapEntry<String, List<LiveRoomItemExt>>(
            site.id,
            <LiveRoomItemExt>[],
          );
        }
      }),
    );

    _platformRooms.clear();
    _allRooms.clear();
    final perSite = <List<LiveRoomItemExt>>[];
    for (final entry in entries) {
      final rooms = List.of(entry.value)
        ..sort((a, b) => b.online.compareTo(a.online));
      _platformRooms[entry.key] = rooms;
      perSite.add(rooms);
    }
    // “全部平台”同样按平台轮流取一条，否则斗鱼的人气会把首屏占满。
    final longest = perSite.fold<int>(
      0,
      (max, rooms) => rooms.length > max ? rooms.length : max,
    );
    for (var i = 0; i < longest; i++) {
      for (final rooms in perSite) {
        if (i < rooms.length) _allRooms.add(rooms[i]);
      }
    }
    loadingRooms.value = false;

    await _applySelection();
  }

  /// 每个一级分类的 children 摊平成 chips；换平台时重新取。
  Future<void> _loadCategories() async {
    final request = ++_categoryRequest;
    categories.clear();
    selectedCategoryIndex.value = 0;
    final current = site;
    if (current == null) return;
    try {
      final result = await current.liveSite.getCategores();
      if (request != _categoryRequest) return;
      final subs = <LiveSubCategoryExt>[];
      for (final category in result) {
        for (final child in category.children) {
          subs.add(
            LiveSubCategoryExt(
              id: child.id,
              name: child.name,
              parentId: child.parentId,
              pic: child.pic,
            ),
          );
        }
      }
      categories.assignAll(subs);
    } catch (_) {
      // 分类列表失败不影响“全部”推荐内容的展示。
    }
  }

  /// 按当前平台 + 分类刷新右栏网格。
  Future<void> _applySelection() async {
    final request = ++_roomRequest;
    errorMsg.value = "";
    final current = site;

    if (current == null) {
      loadingRooms.value = false;
      rooms.assignAll(_allRooms);
      return;
    }

    final category = selectedCategory;
    if (category == null) {
      loadingRooms.value = false;
      rooms.assignAll(_platformRooms[current.id] ?? const []);
      return;
    }

    loadingRooms.value = true;
    try {
      final result = await current.liveSite.getCategoryRooms(category, page: 1);
      if (request != _roomRequest) return;
      rooms.assignAll(
        result.items
            .map(
              (e) => LiveRoomItemExt(
                roomId: e.roomId,
                title: e.title,
                cover: e.cover,
                userName: e.userName,
                online: e.online,
                site: current,
              ),
            )
            .toList(),
      );
    } catch (_) {
      if (request != _roomRequest) return;
      rooms.clear();
      errorMsg.value = "分类内容加载失败";
    } finally {
      if (request == _roomRequest) {
        loadingRooms.value = false;
      }
    }
  }

  /// 换平台或换分类后从网格顶部开始，别停在上一组的滚动位置。
  void _resetScroll() {
    if (scrollController.hasClients) {
      scrollController.jumpTo(0);
    }
  }
}

class LiveSubCategoryExt extends LiveSubCategory {
  LiveSubCategoryExt({
    required super.id,
    required super.name,
    required super.parentId,
    super.pic,
  });

  AppFocusNode focusNode = AppFocusNode();
}
