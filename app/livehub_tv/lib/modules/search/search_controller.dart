import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/models/live_room_item_ext.dart';
import 'package:livehub_tv/services/local_storage_service.dart';

/// 搜索结果里的主播条目。
///
/// 和 [LiveRoomItemExt] 一样，每条自带所属平台：四个平台的搜索结果混在
/// 同一个列表里，点击时必须知道该用哪个 site 打开直播间。
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

/// 搜索页控制器：一次查询同时搜「正在直播」与「主播」两类结果。
///
/// 不再提供「直播间 / 主播」模式切换：用户输入的是主播名时，未开播的主播
/// 只在主播索引里，先让用户猜对模式等于把可搜到的结果藏起来。
class TvSearchController extends GetxController {
  /// 搜索历史的持久化 key。
  static const String kHistoryKey = "SearchHistory";

  static const int _historyLimit = 8;
  static const int _roomLimit = 12;
  static const int _anchorLimit = 20;
  static const int _queryLimit = 32;

  /// 是否有一次搜索正在进行。
  final searching = false.obs;

  /// 是否已经提交过搜索（没提交过时右栏显示搜索历史）。
  final hasSearched = false.obs;

  /// 最近一次实际使用的查询串（已裁剪到 32 个字符）。
  final queryText = "".obs;

  final rooms = <LiveRoomItemExt>[].obs;
  final anchors = <TvAnchorItem>[].obs;
  final history = <String>[].obs;

  /// 单个平台搜索失败 / 不支持时的说明（如“抖音：请先登录”）。
  final platformNotices = <String>[].obs;

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

  int get resultCount => rooms.length + anchors.length;

  /// 提交搜索。空白查询回到“搜索历史”状态。
  Future<void> search(String raw, {bool recordHistory = true}) async {
    final query = raw.trim();
    final requestId = ++_requestId;

    if (query.isEmpty) {
      hasSearched.value = false;
      searching.value = false;
      allFailed.value = false;
      errorText.value = "";
      platformNotices.clear();
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
    platformNotices.clear();
    // 先清掉旧结果：请求失败时不能把上一次的结果留在屏上。
    rooms.clear();
    anchors.clear();

    try {
      if (recordHistory) {
        await _recordHistory(limited);
      }
      final outcomes = await Future.wait([
        _searchRooms(limited),
        _searchAnchors(limited),
      ]);
      if (requestId != _requestId) return;

      final roomOutcome = outcomes[0] as _RoomOutcome;
      final anchorOutcome = outcomes[1] as _AnchorOutcome;

      rooms.assignAll(roomOutcome.items);

      // 直播间已经占了一格的，主播区不再重复展示。
      final roomKeys = rooms.map((e) => "${e.site.id}:${e.roomId}").toSet();
      anchors.assignAll(
        anchorOutcome.items
            .where((e) => !roomKeys.contains("${e.site.id}:${e.roomId}"))
            .take(_anchorLimit),
      );

      // 只展示“正在直播”这一侧的失败：主播侧抖音本来就不支持，
      // 重复提示只会让用户以为搜了两次。
      platformNotices.assignAll(roomOutcome.notices);
      _applyOutcome(
        roomOutcome.failureCount,
        anchorOutcome.failureCount,
      );
    } finally {
      if (requestId == _requestId) {
        searching.value = false;
      }
    }
  }

  /// 只有“两类结果的所有平台都失败”才算错误；
  /// 部分失败仍按可用结果展示，并在 [platformNotices] 里说明原因。
  void _applyOutcome(int roomFailures, int anchorFailures) {
    if (roomFailures >= Sites.supportSites.length &&
        anchorFailures >= Sites.supportSites.length) {
      allFailed.value = true;
      errorText.value = "所有平台都连接失败，请检查网络后重试";
    }
  }

  Future<_RoomOutcome> _searchRooms(String query) async {
    final failures = <String>[];
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
        } catch (e) {
          // 单个平台失败不影响其他平台的结果。
          failures.add("${site.shortName}：${_message(e)}");
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
    return _RoomOutcome(merged.take(_roomLimit).toList(), failures);
  }

  Future<_AnchorOutcome> _searchAnchors(String query) async {
    final failures = <String>[];
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
        } catch (e) {
          failures.add("${site.shortName}：${_message(e)}");
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
    // 主播按名字找：平台返回的模糊结果里常混入与查询毫无关系的账号
    // （例如搜“我是阿南”返回“湘南阿杰”），这里只保留本地可解释的匹配。
    merged.removeWhere(
      (e) => _matchRank(query, [e.roomId, e.userName]) >= 3,
    );
    // 主播同样先按匹配准确度，再按“直播中优先”。
    merged.sort((a, b) {
      final rankA = _matchRank(query, [a.roomId, a.userName]);
      final rankB = _matchRank(query, [b.roomId, b.userName]);
      if (rankA != rankB) return rankA.compareTo(rankB);
      if (a.liveStatus != b.liveStatus) return a.liveStatus ? -1 : 1;
      return 0;
    });
    return _AnchorOutcome(merged, failures);
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

  String _message(Object error) {
    const prefix = "Exception: ";
    final text = error.toString();
    return text.startsWith(prefix) ? text.substring(prefix.length) : text;
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

class _RoomOutcome {
  const _RoomOutcome(this.items, this.failures);

  final List<LiveRoomItemExt> items;
  final List<String> failures;

  int get failureCount => failures.length;
  List<String> get notices => failures;
}

class _AnchorOutcome {
  const _AnchorOutcome(this.items, this.failures);

  final List<TvAnchorItem> items;
  final List<String> failures;

  int get failureCount => failures.length;
}
