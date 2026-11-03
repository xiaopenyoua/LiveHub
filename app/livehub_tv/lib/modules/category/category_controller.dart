import 'package:flutter/widgets.dart';
import 'package:get/get.dart';
import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/models/live_room_item_ext.dart';

/// 分类页：左栏选平台，右栏选分类并直接看直播间。
///
/// 右栏按页加载：选中平台/分类时走对应的分页接口，滚动接近底部继续取下一页，
/// 直到接口返回 hasMore=false。
class CategoryController extends GetxController {
  /// 分类页默认平台，也是左栏的第一项。
  static const String kDefaultPlatform = "douyu";

  /// 当前选中的平台。
  final platformId = kDefaultPlatform.obs;

  /// 左栏平台顺序：斗鱼排第一，其余保持全局顺序。
  List<Site> get platforms {
    final first = Sites.allSites[kDefaultPlatform];
    if (first == null) return Sites.supportSites;
    return [
      first,
      ...Sites.supportSites.where((e) => e.id != kDefaultPlatform),
    ];
  }

  /// 0 = 全部，1..n 对应 [categories] 的某一项。
  final selectedCategoryIndex = 0.obs;

  /// 当前平台的分类。接口真正能查房间的是二级分类，
  /// 直接把它当 chips，才能一层到达直播间。
  final categories = <LiveSubCategoryExt>[].obs;

  /// 右栏网格数据。
  final rooms = <LiveRoomItemExt>[].obs;

  /// 首次加载 / 换平台 / 换分类时的整页加载。
  final loadingRooms = false.obs;

  /// 滚动到底部追加下一页时的加载。
  final loadingMore = false.obs;

  /// 接口是否还有下一页。
  final hasMore = true.obs;

  final errorMsg = "".obs;

  final ScrollController scrollController = ScrollController();
  final AppFocusNode retryFocusNode = AppFocusNode();

  final List<AppFocusNode> _categoryNodes = [];
  final Set<String> _seen = {};

  int _categoryRequest = 0;
  int _requestId = 0;
  int _nextPage = 1;

  Site? get site => Sites.allSites[platformId.value];

  LiveSubCategoryExt? get selectedCategory {
    final index = selectedCategoryIndex.value;
    if (index <= 0 || index > categories.length) return null;
    return categories[index - 1];
  }

  @override
  void onInit() {
    super.onInit();
    scrollController.addListener(_onScroll);
    // 默认平台开局就要有 chips：分类树和第一页直播并行取。
    _reload();
    _loadCategories();
  }

  @override
  void onClose() {
    scrollController.removeListener(_onScroll);
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
    // 分类树和第一页直播并行取，别让旧平台的列表在等待分类时空着。
    final categoriesFuture = _loadCategories();
    // 先启动整页加载（同步段会置 loadingRooms），再回顶：否则 jumpTo 触发的
    // _onScroll 会带着旧的 _nextPage 多发一页请求。
    final reloadFuture = _reload();
    _resetScroll();
    await reloadFuture;
    await categoriesFuture;
  }

  Future<void> selectCategory(int index) async {
    if (selectedCategoryIndex.value == index) return;
    selectedCategoryIndex.value = index;
    final reloadFuture = _reload();
    _resetScroll();
    await reloadFuture;
  }

  Future<void> retry() async {
    if (rooms.isNotEmpty) {
      await loadMore();
      return;
    }
    // 分类树也可能在冷启动时失败过；重试时一并补取，否则 chips 永远只剩“全部”。
    final reloadFuture = _reload();
    if (categories.isEmpty) {
      await _loadCategories();
    }
    await reloadFuture;
  }

  /// 重新从第一页开始加载（换平台 / 换分类 / 首次进入）。
  Future<void> _reload() async {
    final request = ++_requestId;
    rooms.clear();
    _seen.clear();
    _nextPage = 1;
    hasMore.value = true;
    loadingMore.value = false;
    errorMsg.value = "";
    loadingRooms.value = true;

    try {
      final page = await _fetchPage(1);
      if (request != _requestId) return;
      _appendPage(page.items);
      hasMore.value = page.hasMore;
      _nextPage = 2;
      _scheduleFillCheck();
    } catch (e) {
      if (request != _requestId) return;
      errorMsg.value = _errorMessage(e);
    } finally {
      if (request == _requestId) {
        loadingRooms.value = false;
      }
    }
  }

  /// 滚动到底部时追加下一页。
  Future<void> loadMore() async {
    if (loadingRooms.value || loadingMore.value || !hasMore.value) return;
    final request = _requestId;
    loadingMore.value = true;
    try {
      final page = await _fetchPage(_nextPage);
      if (request != _requestId) return;
      final before = rooms.length;
      _appendPage(page.items);
      final added = rooms.length > before;
      // 追加不到新条目（接口重复返回同一页）时停止，避免无限重试。
      hasMore.value = page.hasMore && added;
      if (added) _nextPage++;
      _scheduleFillCheck();
    } catch (_) {
      // 追加失败保留已有列表；下次滚动继续重试。
    } finally {
      if (request == _requestId) {
        loadingMore.value = false;
      }
    }
  }

  void _onScroll() {
    if (!scrollController.hasClients) return;
    final position = scrollController.position;
    if (position.pixels >= position.maxScrollExtent - 240) {
      loadMore();
    }
  }

  /// 某一页太少、根本没有产生滚动条时，滚动监听永远不会触发。
  /// 布局完成后检查一次：列表没占满且还有下一页，就主动追加。
  void _scheduleFillCheck() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!scrollController.hasClients) return;
      if (scrollController.position.maxScrollExtent <= 0 &&
          hasMore.value &&
          !loadingRooms.value &&
          !loadingMore.value) {
        loadMore();
      }
    });
  }

  void _appendPage(List<LiveRoomItemExt> items) {
    for (final item in items) {
      if (_seen.add("${item.site.id}:${item.roomId}")) {
        rooms.add(item);
      }
    }
  }

  Future<_PageResult> _fetchPage(int page) async {
    final current = site;
    if (current == null) {
      return const _PageResult(<LiveRoomItemExt>[], false);
    }

    final category = selectedCategory;
    if (category != null) {
      final result =
          await current.liveSite.getCategoryRooms(category, page: page);
      return _PageResult(_mapItems(result.items, current), result.hasMore);
    }

    final result = await current.liveSite.getRecommendRooms(page: page);
    return _PageResult(_mapItems(result.items, current), result.hasMore);
  }

  List<LiveRoomItemExt> _mapItems(List<LiveRoomItem> items, Site site) {
    return items
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

  String _errorMessage(Object e) {
    final message = e.toString().replaceAll("Exception:", "").trim();
    return message.isEmpty ? "分类内容加载失败" : message;
  }

  /// 换平台或换分类后从网格顶部开始，别停在上一组的滚动位置。
  void _resetScroll() {
    if (scrollController.hasClients) {
      scrollController.jumpTo(0);
    }
  }
}

class _PageResult {
  const _PageResult(this.items, this.hasMore);

  final List<LiveRoomItemExt> items;
  final bool hasMore;
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
