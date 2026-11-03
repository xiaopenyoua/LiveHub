import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/models/live_room_item_ext.dart';
import 'package:livehub_tv/services/local_storage_service.dart';

/// 搜索结果里的主播条目。
///
/// 和 [LiveRoomItemExt] 一样，每条自带所属平台：四个平台的搜索结果混在
/// 同一个网格里，点击时必须知道该用哪个 site 打开直播间。
class TvAnchorItem {
  TvAnchorItem({
    required this.roomId,
    required this.avatar,
    required this.userName,
    required this.liveStatus,
    required this.site,
  });

  final String roomId;
  final String avatar;
  final String userName;
  final bool liveStatus;
  final Site site;

  String get siteName => site.shortName;

  AppFocusNode focusNode = AppFocusNode();
}

/// 搜索页控制器：直播间 / 主播两种结果类型，四平台并发搜索。
class TvSearchController extends GetxController {
  /// 搜索历史的持久化 key。
  static const String kHistoryKey = "SearchHistory";

  static const int _historyLimit = 8;
  static const int _roomLimit = 12;
  static const int _queryLimit = 32;

  /// 0 = 直播间，1 = 主播。
  final mode = 0.obs;

  /// 是否有一次搜索正在进行。
  final searching = false.obs;

  /// 是否已经提交过搜索（没提交过时右栏显示搜索历史）。
  final hasSearched = false.obs;

  /// 最近一次实际使用的查询串（已裁剪到 32 个字符）。
  final queryText = "".obs;

  final rooms = <LiveRoomItemExt>[].obs;
  final anchors = <TvAnchorItem>[].obs;
  final history = <String>[].obs;

  /// 四个平台全部失败（断网 / 被限制）时置位。
  /// 用来区分“合法地搜不到”和“根本搜不了”，后者要给重试入口。
  final allFailed = false.obs;
  final errorText = "".obs;

  int _requestId = 0;

  @override
  void onInit() {
    super.onInit();
    _loadHistory();
  }

  int get resultCount => mode.value == 0 ? rooms.length : anchors.length;

  /// 排第一的结果是否与查询串完全相等。
  /// 用于结果区那句“按「搜索」直达”——和搜索键共用同一条结果。
  bool get firstIsExact {
    final query = queryText.value;
    if (query.isEmpty) return false;
    if (mode.value == 0) {
      if (rooms.isEmpty) return false;
      final first = rooms.first;
      return _matchRank(query, [first.roomId, first.userName, first.title]) == 0;
    }
    if (anchors.isEmpty) return false;
    final first = anchors.first;
    return _matchRank(query, [first.roomId, first.userName]) == 0;
  }

  /// 切换结果类型。已经搜过一次时，立刻按 [query]（输入框当前文本）重查，
  /// 用户不用再按一次搜索。不传 [query] 时退回上次提交的查询串。
  void setMode(int value, {String? query}) {
    if (value != 0 && value != 1) return;
    if (mode.value == value) return;
    mode.value = value;
    if (!hasSearched.value) return;
    final text = (query ?? queryText.value).trim();
    search(text, recordHistory: false);
  }

  /// 提交搜索。空白查询回到“搜索历史”状态。
  Future<void> search(String raw, {bool recordHistory = true}) async {
    final query = raw.trim();
    final requestId = ++_requestId;

    if (query.isEmpty) {
      hasSearched.value = false;
      searching.value = false;
      allFailed.value = false;
      errorText.value = "";
      rooms.clear();
      anchors.clear();
      return;
    }

    final limited =
        query.length > _queryLimit ? query.substring(0, _queryLimit) : query;
    queryText.value = limited;
    hasSearched.value = true;
    searching.value = true;
    allFailed.value = false;
    errorText.value = "";
    // 先清掉当前模式旧结果：请求失败时不能把上一次的结果留在屏上。
    if (mode.value == 0) {
      rooms.clear();
    } else {
      anchors.clear();
    }

    try {
      if (recordHistory) {
        await _recordHistory(limited);
      }
      if (mode.value == 0) {
        final (result, failures) = await _searchRooms(limited);
        if (requestId != _requestId) return;
        rooms.assignAll(result);
        _applyOutcome(failures);
      } else {
        final (result, failures) = await _searchAnchors(limited);
        if (requestId != _requestId) return;
        anchors.assignAll(result);
        _applyOutcome(failures);
      }
    } finally {
      if (requestId == _requestId) {
        searching.value = false;
      }
    }
  }

  /// 只有“所有平台都失败”才算错误；部分失败仍按可用结果展示。
  void _applyOutcome(int failures) {
    if (failures >= Sites.supportSites.length) {
      allFailed.value = true;
      errorText.value = "所有平台都连接失败，请检查网络后重试";
    }
  }

  Future<(List<LiveRoomItemExt>, int)> _searchRooms(String query) async {
    var failures = 0;
    final results = await Future.wait(
      Sites.supportSites.map((site) async {
        try {
          final result = await site.liveSite.searchRooms(query, page: 1);
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
        } catch (_) {
          // 单个平台失败不影响其他平台的结果。
          failures++;
          return <LiveRoomItemExt>[];
        }
      }),
    );

    final seen = <String>{};
    final merged = <LiveRoomItemExt>[];
    for (final item in results.expand((e) => e)) {
      if (seen.add("${item.site.id}:${item.roomId}")) {
        merged.add(item);
      }
    }
    // 先按匹配准确度（完全相等 > 前缀 > 包含）再按人气，避免高人气把
    // 完全命中的条目挤出前 12 条。
    merged.sort((a, b) {
      final rankA = _matchRank(query, [a.roomId, a.userName, a.title]);
      final rankB = _matchRank(query, [b.roomId, b.userName, b.title]);
      if (rankA != rankB) return rankA.compareTo(rankB);
      return b.online.compareTo(a.online);
    });
    return (merged.take(_roomLimit).toList(), failures);
  }

  Future<(List<TvAnchorItem>, int)> _searchAnchors(String query) async {
    var failures = 0;
    final results = await Future.wait(
      Sites.supportSites.map((site) async {
        try {
          final result = await site.liveSite.searchAnchors(query, page: 1);
          return result.items
              .map(
                (e) => TvAnchorItem(
                  roomId: e.roomId,
                  avatar: e.avatar,
                  userName: e.userName,
                  liveStatus: e.liveStatus,
                  site: site,
                ),
              )
              .toList();
        } catch (_) {
          failures++;
          return <TvAnchorItem>[];
        }
      }),
    );

    final seen = <String>{};
    final merged = <TvAnchorItem>[];
    for (final item in results.expand((e) => e)) {
      if (seen.add("${item.site.id}:${item.roomId}")) {
        merged.add(item);
      }
    }
    // 主播同样先按匹配准确度，再按“直播中优先”。
    merged.sort((a, b) {
      final rankA = _matchRank(query, [a.roomId, a.userName]);
      final rankB = _matchRank(query, [b.roomId, b.userName]);
      if (rankA != rankB) return rankA.compareTo(rankB);
      if (a.liveStatus != b.liveStatus) return a.liveStatus ? -1 : 1;
      return 0;
    });
    return (merged, failures);
  }

  /// 匹配准确度：0 完全相等 / 1 前缀 / 2 包含 / 3 不匹配。
  /// 查询串和字段一律转小写；房间号也当字符串比，不做数字转换。
  int _matchRank(String query, List<String> fields) {
    final q = query.toLowerCase();
    var best = 3;
    for (final field in fields) {
      final f = field.toLowerCase();
      if (f == q) return 0;
      if (f.startsWith(q)) {
        if (best > 1) best = 1;
      } else if (f.contains(q)) {
        if (best > 2) best = 2;
      }
    }
    return best;
  }

  void _loadHistory() {
    final stored =
        LocalStorageService.instance.getValue<List>(kHistoryKey, <String>[]);
    history.assignAll(
      stored
          .whereType<String>()
          .where((e) => e.trim().isNotEmpty)
          .take(_historyLimit),
    );
  }

  Future<void> _recordHistory(String query) async {
    history.removeWhere((e) => e == query);
    history.insert(0, query);
    if (history.length > _historyLimit) {
      history.removeRange(_historyLimit, history.length);
    }
    await LocalStorageService.instance
        .setValue<List<String>>(kHistoryKey, history.toList());
  }
}
