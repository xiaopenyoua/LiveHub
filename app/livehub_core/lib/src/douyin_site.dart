import 'dart:convert';
import 'dart:math';

import 'package:livehub_core/livehub_core.dart';
import 'package:livehub_core/src/common/convert_helper.dart';
import 'package:livehub_core/src/common/http_client.dart';
import 'package:livehub_core/src/scripts/douyin_sign.dart';

class DouyinSite implements LiveSite {
  @override
  String id = "douyin";

  @override
  String name = "抖音直播";

  @override
  LiveDanmaku getDanmaku() => DouyinDanmaku();

  /// 使用 QQBrowser User-Agent（参考 DouyinLiveRecorder）
  static const String kDefaultUserAgent =
      "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400";

  static const String kDefaultReferer = "https://live.douyin.com";

  static const String kDefaultAuthority = "live.douyin.com";

  /// 默认 Cookie - 只需要 ttwid 字段即可获取所有画质（包括蓝光）
  /// 经过测试验证，LOGIN_STATUS=1 等其他字段都是可选的
  static const String kDefaultCookie =
      "ttwid=1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511";

  /// 用户设置的 cookie
  String cookie = "";

  void _logDebug(String msg) {
    // 同时使用 print 和 CoreLog 确保日志输出
    print("[Douyin] $msg");
    CoreLog.d("[Douyin] $msg");
  }

  Map<String, dynamic> headers = {
    "Authority": kDefaultAuthority,
    "Referer": kDefaultReferer,
    "User-Agent": kDefaultUserAgent,
  };

  Future<Map<String, dynamic>> getRequestHeaders() async {
    try {
      // 如果用户已设置 cookie，直接使用用户的 cookie
      if (cookie.isNotEmpty) {
        headers["cookie"] = cookie;
        return headers;
      }

      // 使用默认的 ttwid cookie（只需要 ttwid 即可获取所有画质）
      headers["cookie"] = kDefaultCookie;
      return headers;
    } catch (e) {
      CoreLog.error(e);
      if (!(headers["cookie"]?.toString().isNotEmpty ?? false)) {
        headers["cookie"] = kDefaultCookie;
      }
      return headers;
    }
  }

  @override
  Future<List<LiveCategory>> getCategores() async {
    List<LiveCategory> categories = [];
    var result = await HttpClient.instance.getText(
      "https://live.douyin.com/",
      queryParameters: {},
      header: await getRequestHeaders(),
    );

    var renderData =
        RegExp(
          r'\{\\"pathname\\":\\"\/\\",\\"categoryData.*?\]\\n',
        ).firstMatch(result)?.group(0) ??
        "";
    var renderDataJson = json.decode(
      renderData
          .trim()
          .replaceAll('\\"', '"')
          .replaceAll(r"\\", r"\")
          .replaceAll(']\\n', ""),
    );

    for (var item in renderDataJson["categoryData"]) {
      List<LiveSubCategory> subs = [];
      var id = '${item["partition"]["id_str"]},${item["partition"]["type"]}';
      for (var subItem in item["sub_partition"]) {
        var subCategory = LiveSubCategory(
          id: '${subItem["partition"]["id_str"]},${subItem["partition"]["type"]}',
          name: asT<String?>(subItem["partition"]["title"]) ?? "",
          parentId: id,
          pic: "",
        );
        subs.add(subCategory);
      }

      var category = LiveCategory(
        children: subs,
        id: id,
        name: asT<String?>(item["partition"]["title"]) ?? "",
      );
      subs.insert(
        0,
        LiveSubCategory(
          id: category.id,
          name: category.name,
          parentId: category.id,
          pic: "",
        ),
      );
      categories.add(category);
    }
    return categories;
  }

  @override
  Future<LiveCategoryResult> getCategoryRooms(
    LiveSubCategory category, {
    int page = 1,
  }) async {
    var ids = category.id.split(',');
    var partitionId = ids[0];
    var partitionType = ids[1];

    String serverUrl =
        "https://live.douyin.com/webcast/web/partition/detail/room/v2/";
    var uri = Uri.parse(serverUrl).replace(
      scheme: "https",
      port: 443,
      queryParameters: {
        "aid": '6383',
        "app_name": "douyin_web",
        "live_id": '1',
        "device_platform": "web",
        "language": "zh-CN",
        "enter_from": "link_share",
        "cookie_enabled": "true",
        "screen_width": "1980",
        "screen_height": "1080",
        "browser_language": "zh-CN",
        "browser_platform": "Win32",
        "browser_name": "Edge",
        "browser_version": "125.0.0.0",
        "browser_online": "true",
        "count": '15',
        "offset": ((page - 1) * 15).toString(),
        "partition": partitionId,
        "partition_type": partitionType,
        "req_from": '2',
      },
    );
    var requestUrl = DouyinSign.getAbogusUrl(uri.toString(), kDefaultUserAgent);

    var result = await HttpClient.instance.getJson(
      requestUrl,
      header: await getRequestHeaders(),
    );

    var hasMore = (result["data"]["data"] as List).length >= 15;
    var items = <LiveRoomItem>[];
    for (var item in result["data"]["data"]) {
      var roomItem = LiveRoomItem(
        roomId: item["web_rid"],
        title: item["room"]["title"].toString(),
        cover: item["room"]["cover"]["url_list"][0].toString(),
        userName: item["room"]["owner"]["nickname"].toString(),
        online:
            int.tryParse(
              item["room"]["room_view_stats"]["display_value"].toString(),
            ) ??
            0,
      );
      items.add(roomItem);
    }
    return LiveCategoryResult(hasMore: hasMore, items: items);
  }

  @override
  Future<LiveCategoryResult> getRecommendRooms({int page = 1}) async {
    String serverUrl =
        "https://live.douyin.com/webcast/web/partition/detail/room/v2/";
    var uri = Uri.parse(serverUrl).replace(
      scheme: "https",
      port: 443,
      queryParameters: {
        "aid": '6383',
        "app_name": "douyin_web",
        "live_id": '1',
        "device_platform": "web",
        "language": "zh-CN",
        "enter_from": "link_share",
        "cookie_enabled": "true",
        "screen_width": "1980",
        "screen_height": "1080",
        "browser_language": "zh-CN",
        "browser_platform": "Win32",
        "browser_name": "Edge",
        "browser_version": "125.0.0.0",
        "browser_online": "true",
        "count": '15',
        "offset": ((page - 1) * 15).toString(),
        "partition": '720',
        "partition_type": '1',
        "req_from": '2',
      },
    );
    var requestUrl = DouyinSign.getAbogusUrl(uri.toString(), kDefaultUserAgent);

    var result = await HttpClient.instance.getJson(
      requestUrl,
      header: await getRequestHeaders(),
    );

    var hasMore = (result["data"]["data"] as List).length >= 15;
    var items = <LiveRoomItem>[];
    for (var item in result["data"]["data"]) {
      var roomItem = LiveRoomItem(
        roomId: item["web_rid"],
        title: item["room"]["title"].toString(),
        cover: item["room"]["cover"]["url_list"][0].toString(),
        userName: item["room"]["owner"]["nickname"].toString(),
        online:
            int.tryParse(
              item["room"]["room_view_stats"]["display_value"].toString(),
            ) ??
            0,
      );
      items.add(roomItem);
    }
    return LiveCategoryResult(hasMore: hasMore, items: items);
  }

  @override
  Future<LiveRoomDetail> getRoomDetail({required String roomId}) async {
    // 有两种roomId，一种是webRid，一种是roomId
    // roomId是一次性的，用户每次重新开播都会生成一个新的roomId
    // roomId一般长度为19位，例如：7376429659866598196
    // webRid是固定的，用户每次开播都是同一个webRid
    // webRid一般长度为11-12位，例如：416144012050
    // 这里简单进行判断，如果roomId长度小于15，则认为是webRid
    if (roomId.length <= 16) {
      var webRid = roomId;
      return await getRoomDetailByWebRid(webRid);
    }

    return await getRoomDetailByRoomId(roomId);
  }

  /// 通过roomId获取直播间信息
  /// - [roomId] 直播间ID
  /// - 返回直播间信息
  Future<LiveRoomDetail> getRoomDetailByRoomId(String roomId) async {
    // 读取房间信息
    var roomData = await _getRoomDataByRoomId(roomId);

    // 通过房间信息获取WebRid
    var webRid = roomData["data"]["room"]["owner"]["web_rid"].toString();

    // 读取用户唯一ID，用于弹幕连接
    // 似乎这个参数不是必须的，先随机生成一个
    //var userUniqueId = await _getUserUniqueId(webRid);
    var userUniqueId = generateRandomNumber(12).toString();

    var room = roomData["data"]["room"];
    var owner = room["owner"];

    var status = asT<int?>(room["status"]) ?? 0;

    // roomId是一次性的，用户每次重新开播都会生成一个新的roomId
    // 所以如果roomId对应的直播间状态不是直播中，就通过webRid获取直播间信息
    if (status == 4) {
      var result = await getRoomDetailByWebRid(webRid);
      return result;
    }

    var roomStatus = status == 2;
    // 主要是为了获取cookie,用于弹幕websocket连接
    var headers = await getRequestHeaders();

    return LiveRoomDetail(
      roomId: webRid,
      title: room["title"].toString(),
      cover: roomStatus ? room["cover"]["url_list"][0].toString() : "",
      userName: owner["nickname"].toString(),
      userAvatar: owner["avatar_thumb"]["url_list"][0].toString(),
      online: roomStatus
          ? asT<int?>(room["room_view_stats"]["display_value"]) ?? 0
          : 0,
      status: roomStatus,
      url: "https://live.douyin.com/$webRid",
      introduction: owner["signature"].toString(),
      notice: "",
      danmakuData: DouyinDanmakuArgs(
        webRid: webRid,
        roomId: roomId,
        userId: userUniqueId,
        cookie: headers["cookie"],
      ),
      data: room["stream_url"],
    );
  }

  /// 通过WebRid获取直播间信息
  /// - [webRid] 直播间RID
  /// - 返回直播间信息
  Future<LiveRoomDetail> getRoomDetailByWebRid(String webRid) async {
    try {
      var result = await _getRoomDetailByWebRidApi(webRid);
      return result;
    } catch (e) {
      CoreLog.error(e);
    }
    return await _getRoomDetailByWebRidHtml(webRid);
  }

  /// 通过WebRid访问直播间API，从API中获取直播间信息
  /// - [webRid] 直播间RID
  /// - 返回直播间信息
  Future<LiveRoomDetail> _getRoomDetailByWebRidApi(String webRid) async {
    // 读取房间信息
    var data = await _getRoomDataByApi(webRid);

    final roomList = data["data"];
    final userData = data["user"];
    if (roomList is! List || roomList.isEmpty) {
      // 未开播时 enter 接口不返回房间，只返回主播信息（抖音号也能这样解析）
      if (userData is Map) {
        final avatarList = userData["avatar_thumb"] is Map
            ? userData["avatar_thumb"]["url_list"]
            : null;
        final headers = await getRequestHeaders();
        return LiveRoomDetail(
          roomId: webRid,
          title: "未开播",
          cover: "",
          userName: userData["nickname"].toString(),
          userAvatar: avatarList is List && avatarList.isNotEmpty
              ? avatarList[0].toString()
              : "",
          online: 0,
          status: false,
          url: "https://live.douyin.com/$webRid",
          introduction: "",
          notice: "",
          danmakuData: DouyinDanmakuArgs(
            webRid: webRid,
            roomId: "",
            userId: generateRandomNumber(12).toString(),
            cookie: headers["cookie"],
          ),
          data: {},
        );
      }
      throw Exception("抖音直播间 $webRid 不存在");
    }

    var roomData = roomList[0];
    var roomId = roomData["id_str"].toString();

    // 读取用户唯一ID，用于弹幕连接
    // 似乎这个参数不是必须的，先随机生成一个
    //var userUniqueId = await _getUserUniqueId(webRid);
    var userUniqueId = generateRandomNumber(12).toString();

    var owner = roomData["owner"];

    var roomStatus = (asT<int?>(roomData["status"]) ?? 0) == 2;

    // 主要是为了获取cookie,用于弹幕websocket连接
    var headers = await getRequestHeaders();
    return LiveRoomDetail(
      roomId: webRid,
      title: roomData["title"].toString(),
      cover: roomStatus ? roomData["cover"]["url_list"][0].toString() : "",
      userName: roomStatus
          ? owner["nickname"].toString()
          : userData["nickname"].toString(),
      userAvatar: roomStatus
          ? owner["avatar_thumb"]["url_list"][0].toString()
          : userData["avatar_thumb"]["url_list"][0].toString(),
      online: roomStatus
          ? asT<int?>(roomData["room_view_stats"]["display_value"]) ?? 0
          : 0,
      status: roomStatus,
      url: "https://live.douyin.com/$webRid",
      introduction: owner?["signature"]?.toString() ?? "",
      notice: "",
      danmakuData: DouyinDanmakuArgs(
        webRid: webRid,
        roomId: roomId,
        userId: userUniqueId,
        cookie: headers["cookie"],
      ),
      data: roomStatus ? roomData["stream_url"] : {},
    );
  }

  /// 通过WebRid访问直播间网页，从网页HTML中获取直播间信息
  /// - [webRid] 直播间RID
  /// - 返回直播间信息
  Future<LiveRoomDetail> _getRoomDetailByWebRidHtml(String webRid) async {
    var roomData = await _getRoomDataByHtml(webRid);
    var roomId = roomData["roomStore"]["roomInfo"]["room"]["id_str"].toString();
    var userUniqueId = roomData["userStore"]["odin"]["user_unique_id"]
        .toString();

    var room = roomData["roomStore"]["roomInfo"]["room"];
    var owner = room["owner"];
    var anchor = roomData["roomStore"]["roomInfo"]["anchor"];
    var roomStatus = (asT<int?>(room["status"]) ?? 0) == 2;

    // 主要是为了获取cookie,用于弹幕websocket连接
    var headers = await getRequestHeaders();

    return LiveRoomDetail(
      roomId: webRid,
      title: room["title"].toString(),
      cover: roomStatus ? room["cover"]["url_list"][0].toString() : "",
      userName: roomStatus
          ? owner["nickname"].toString()
          : anchor["nickname"].toString(),
      userAvatar: roomStatus
          ? owner["avatar_thumb"]["url_list"][0].toString()
          : anchor["avatar_thumb"]["url_list"][0].toString(),
      online: roomStatus
          ? asT<int?>(room["room_view_stats"]["display_value"]) ?? 0
          : 0,
      status: roomStatus,
      url: "https://live.douyin.com/$webRid",
      introduction: owner?["signature"]?.toString() ?? "",
      notice: "",
      danmakuData: DouyinDanmakuArgs(
        webRid: webRid,
        roomId: roomId,
        userId: userUniqueId,
        cookie: headers["cookie"],
      ),
      data: roomStatus ? room["stream_url"] : {},
    );
  }

  /// 读取用户的唯一ID
  /// - [webRid] 直播间RID
  // ignore: unused_element
  Future<String> _getUserUniqueId(String webRid) async {
    try {
      var webInfo = await _getRoomDataByHtml(webRid);
      return webInfo["userStore"]["odin"]["user_unique_id"].toString();
    } catch (e) {
      return generateRandomNumber(12).toString();
    }
  }

  /// 进入直播间前需要先获取cookie
  /// - [webRid] 直播间RID
  Future<String> _getWebCookie(String webRid) async {
    var headResp = await HttpClient.instance.head(
      "https://live.douyin.com/$webRid",
      header: headers,
    );
    var dyCookie = "";
    headResp.headers["set-cookie"]?.forEach((element) {
      var cookie = element.split(";")[0];
      if (cookie.contains("ttwid")) {
        dyCookie += "$cookie;";
      }
      if (cookie.contains("__ac_nonce")) {
        dyCookie += "$cookie;";
      }
      if (cookie.contains("msToken")) {
        dyCookie += "$cookie;";
      }
    });
    return dyCookie;
  }

  /// 通过webRid获取直播间Web信息
  /// - [webRid] 直播间RID
  Future<Map> _getRoomDataByHtml(String webRid) async {
    var dyCookie = await _getWebCookie(webRid);
    var result = await HttpClient.instance.getText(
      "https://live.douyin.com/$webRid",
      queryParameters: {},
      header: {
        "Authority": kDefaultAuthority,
        "Referer": kDefaultReferer,
        "Cookie": dyCookie,
        "User-Agent": kDefaultUserAgent,
      },
    );

    var renderData =
        RegExp(
          r'\{\\"state\\":\{\\"appStore.*?\]\\n',
        ).firstMatch(result)?.group(0) ??
        "";
    var str = renderData
        .trim()
        .replaceAll('\\"', '"')
        .replaceAll(r"\\", r"\")
        .replaceAll(']\\n', "");
    var renderDataJson = json.decode(str);
    return renderDataJson["state"];
  }

  /// 通过webRid获取直播间Web信息
  /// - [webRid] 直播间RID
  Future<Map> _getRoomDataByApi(String webRid) async {
    String serverUrl = "https://live.douyin.com/webcast/room/web/enter/";

    // 提前获取 headers
    var requestHeader = await getRequestHeaders();

    // 使用动态 Referer（包含房间号，参考 DouyinLiveRecorder）
    requestHeader["Referer"] = "https://live.douyin.com/$webRid";

    var uri = Uri.parse(serverUrl).replace(
      scheme: "https",
      port: 443,
      queryParameters: {
        "aid": '6383',
        "app_name": "douyin_web",
        "live_id": '1',
        "device_platform": "web",
        "language": "zh-CN",
        "browser_language": "zh-CN",
        "browser_platform": "Win32",
        "browser_name": "Chrome",
        "browser_version": "125.0.0.0",
        "web_rid": webRid,
        "msToken": "",
      },
    );
    var requestUrl = DouyinSign.getAbogusUrl(uri.toString(), kDefaultUserAgent);

    var result = await HttpClient.instance.getJson(
      requestUrl,
      header: requestHeader,
    );

    if (result is! Map) {
      throw Exception("抖音接口返回格式异常");
    }

    // status_code 非 0 时 data 里只有 message，交给调用方走 HTML 兜底
    final statusCode = asT<int?>(result["status_code"]) ?? 0;
    if (statusCode != 0) {
      throw Exception("抖音接口返回异常：$statusCode");
    }

    return result["data"];
  }

  /// 通过roomId获取直播间信息
  /// - [roomId] 直播间ID
  Future<Map> _getRoomDataByRoomId(String roomId) async {
    var result = await HttpClient.instance.getJson(
      'https://webcast.amemv.com/webcast/room/reflow/info/',
      queryParameters: {
        "type_id": 0,
        "live_id": 1,
        "room_id": roomId,
        "sec_user_id": "",
        "version_code": "99.99.99",
        "app_id": 6383,
      },
      header: await getRequestHeaders(),
    );
    return result;
  }

  @override
  Future<List<LivePlayQuality>> getPlayQualites({
    required LiveRoomDetail detail,
  }) async {
    List<LivePlayQuality> qualities = [];

    try {
      var liveCoreData = detail.data["live_core_sdk_data"];

      if (liveCoreData == null) {
        return qualities;
      }

      var pullData = liveCoreData["pull_data"];

      if (pullData == null) {
        return qualities;
      }

      var options = pullData["options"];

      var qulityList = options?["qualities"];

      var streamData = pullData["stream_data"]?.toString() ?? "";

      if (!streamData.startsWith('{')) {
        var flvList = (detail.data["flv_pull_url"] as Map).values
            .cast<String>()
            .toList();
        var hlsList = (detail.data["hls_pull_url_map"] as Map).values
            .cast<String>()
            .toList();
        for (var quality in qulityList) {
          int level = quality["level"];
          List<String> urls = [];
          var flvIndex = flvList.length - level;
          if (flvIndex >= 0 && flvIndex < flvList.length) {
            urls.add(flvList[flvIndex]);
          }
          var hlsIndex = hlsList.length - level;
          if (hlsIndex >= 0 && hlsIndex < hlsList.length) {
            urls.add(hlsList[hlsIndex]);
          }
          var qualityItem = LivePlayQuality(
            quality: quality["name"],
            sort: level,
            data: urls,
          );
          if (urls.isNotEmpty) {
            qualities.add(qualityItem);
          }
        }
      } else {
        var qualityData = json.decode(streamData)["data"] as Map;

        for (var quality in qulityList) {
          List<String> urls = [];

          var flvUrl = qualityData[quality["sdk_key"]]?["main"]?["flv"]
              ?.toString();

          if (flvUrl != null && flvUrl.isNotEmpty) {
            urls.add(flvUrl);
          }
          var hlsUrl = qualityData[quality["sdk_key"]]?["main"]?["hls"]
              ?.toString();

          if (hlsUrl != null && hlsUrl.isNotEmpty) {
            urls.add(hlsUrl);
          }

          var qualityItem = LivePlayQuality(
            quality: quality["name"],
            sort: quality["level"],
            data: urls,
          );
          if (urls.isNotEmpty) {
            qualities.add(qualityItem);
          }
        }
      }
    } catch (e, stackTrace) {
      CoreLog.error(e);
      CoreLog.error(stackTrace);
    }
    // var qualityData = json.decode(
    //     detail.data["live_core_sdk_data"]["pull_data"]["stream_data"])["data"];

    qualities.sort((a, b) => b.sort.compareTo(a.sort));
    _logDebug("获取到的画质列表: ${qualities.map((q) => q.quality).toList()}");
    return qualities;
  }

  @override
  Future<LivePlayUrl> getPlayUrls({
    required LiveRoomDetail detail,
    required LivePlayQuality quality,
  }) async {
    // 返回列表的副本，防止外部 clear() 影响原始数据
    return LivePlayUrl(urls: List<String>.from(quality.data));
  }

  // ==================== 搜索 ====================
  //
  // 抖音的“关键词搜索”接口（/aweme/v1/web/live/search/）是登录墙：
  // 匿名请求固定返回 {"status_code":2483,"status_msg":"请先登录，再继续搜索吧"}，
  // 参考实现 junlive 因此把搜索改成“只按直播间号”，DTV 的 searchPlatform 对 douyin 直接返回 null。
  //
  // 这里不放弃，用四级兜底把能拿到的都拿到（任何一级命中就返回）：
  //   1. 官方搜索：用户自己在设置里填了抖音 Cookie（登录态）时，走真正的关键词搜索。
  //   2. 门牌号直连：webcast/room/web/enter 的 web_rid 既吃数字直播间号，
  //      也吃字母数字的抖音号（实测 nvliu66 这类账号可以解析出主播，未开播也能拿到主播信息）。
  //   3. 实时直播索引：webcast/web/partition/detail/room/v2 把“正在直播”的房间抓成索引
  //      （热门 + 各分类分页），按昵称/标题包含匹配，解决中文昵称搜不到的问题。
  //   4. 会话学习缓存：解析成功过的“昵称/抖音号 → 直播间”记下来，之后未开播也能命中。

  /// 一次搜索命中的条目，直播间结果与主播结果共用同一份数据
  static final Map<String, _DouyinSearchHit> _learnedHits =
      <String, _DouyinSearchHit>{};

  /// 实时直播索引缓存（正在直播的房间）
  List<_DouyinSearchHit> _indexCache = <_DouyinSearchHit>[];

  /// 索引缓存时间
  DateTime _indexCacheAt = DateTime.fromMillisecondsSinceEpoch(0);

  /// 索引缓存有效期
  static const Duration _indexTtl = Duration(minutes: 5);

  /// 官方关键词搜索接口（需要登录态 Cookie）
  static const String _officialSearchApi =
      "https://www.douyin.com/aweme/v1/web/live/search/";

  /// 分区房间接口（匿名可用）
  static const String _partitionRoomApi =
      "https://live.douyin.com/webcast/web/partition/detail/room/v2/";

  /// 推荐流接口（匿名可用）
  static const String _feedApi = "https://live.douyin.com/webcast/feed/";

  @override
  Future<LiveSearchRoomResult> searchRooms(
    String keyword, {
    int page = 1,
  }) async {
    final result = await _searchAll(keyword, page: page);
    final items = result.hits
        .map(
          (e) => LiveRoomItem(
            roomId: e.roomId,
            title: e.live ? e.title : "未开播",
            cover: e.cover,
            userName: e.userName,
            online: e.online,
            live: e.live,
          ),
        )
        .toList();
    return LiveSearchRoomResult(hasMore: result.hasMore, items: items);
  }

  @override
  Future<LiveSearchAnchorResult> searchAnchors(
    String keyword, {
    int page = 1,
  }) async {
    final result = await _searchAll(keyword, page: page);
    final items = result.hits
        .map(
          (e) => LiveAnchorItem(
            roomId: e.roomId,
            avatar: e.avatar.isNotEmpty ? e.avatar : e.cover,
            userName: e.userName,
            liveStatus: e.live,
          ),
        )
        .toList();
    return LiveSearchAnchorResult(hasMore: result.hasMore, items: items);
  }

  /// 搜索主流程：官方搜索 → 门牌号直连 → 学习缓存 → 实时直播索引
  Future<({List<_DouyinSearchHit> hits, bool hasMore})> _searchAll(
    String keyword, {
    int page = 1,
  }) async {
    final kw = keyword.trim();
    if (kw.isEmpty) {
      return (hits: <_DouyinSearchHit>[], hasMore: false);
    }

    // 翻页只对“索引结果”有意义：直连和缓存都只有一条
    if (page > 1) {
      final index = await _ensureLiveIndex(deep: true);
      return _pageSlice(_matchHits(index, kw), page);
    }

    // 1) 官方搜索（只有用户配置了登录态 Cookie 才可能成功）
    final official = await _searchByOfficialApi(kw);
    if (official.isNotEmpty) {
      for (final hit in official.take(3)) {
        _learn(hit.userName, hit);
      }
      return _pageSlice(official, 1);
    }

    // 2) 门牌号 / 抖音号直连
    final rid = _extractRoomId(kw);
    if (rid.isNotEmpty) {
      final direct = await _resolveByWebRid(rid);
      if (direct != null) {
        _learn(kw, direct);
        _learn(direct.userName, direct);
        return (hits: <_DouyinSearchHit>[direct], hasMore: false);
      }
    }

    // 3) 会话学习缓存
    final learned = _lookupLearned(kw);
    if (learned != null) {
      return (hits: <_DouyinSearchHit>[learned], hasMore: false);
    }

    // 4) 公开网页索引兜底：抖音的关键词搜索要登录，但搜索引擎收录了
    //    live.douyin.com/<直播间号> 这些页面，拿它们的房间号再用抖音接口校验一遍，
    //    未开播的主播也能定位到（索引里只有“正在直播”的房间，覆盖不到离线主播）。
    final webIndexHit = await _searchByWebIndex(kw);
    if (webIndexHit != null) {
      _learn(kw, webIndexHit);
      _learn(webIndexHit.userName, webIndexHit);
      return (hits: <_DouyinSearchHit>[webIndexHit], hasMore: false);
    }

    // 5) 实时直播索引（先浅扫热门，没命中再扩到各分类）
    var index = await _ensureLiveIndex();
    var matched = _matchHits(index, kw);
    if (matched.isEmpty) {
      index = await _ensureLiveIndex(deep: true);
      matched = _matchHits(index, kw);
    }
    if (matched.isNotEmpty) {
      for (final hit in matched.take(3)) {
        _learn(hit.userName, hit);
      }
      return _pageSlice(matched, 1);
    }

    throw Exception(
      "抖音未找到「$kw」的直播间。抖音的关键词搜索接口要求登录，未登录时只能：① 输入抖音号（如 nvliu66）或直播间号；② 匹配正在直播的主播昵称。可在设置里填入抖音 Cookie 后使用完整关键词搜索。",
    );
  }


  /// 用公开网页索引（Bing / DuckDuckGo 的 HTML 结果）找到 live.douyin.com/<房间号>，
  /// 再用抖音自己的接口校验房间号，避免把无关页面当结果。
  Future<_DouyinSearchHit?> _searchByWebIndex(String keyword) async {
    try {
      final kwQuery = "site:live.douyin.com $keyword";
      final q = Uri.encodeComponent(kwQuery);
      final urls = <String>[
        "https://cn.bing.com/search?q=$q",
        "https://www.bing.com/search?q=$q",
        "https://html.duckduckgo.com/html/?q=$q",
      ];
      final ids = <String>[];
      for (final url in urls) {
        final html = await _fetchTextQuietly(url);
        if (html.isEmpty) {
          continue;
        }
        final reg = RegExp(r"live\.douyin\.com(?:%2F|/)(\d{6,20})");
        for (final m in reg.allMatches(html)) {
          final id = m.group(1) ?? "";
          if (id.isNotEmpty && !ids.contains(id)) {
            ids.add(id);
          }
        }
        if (ids.length >= 6) {
          break;
        }
      }
      if (ids.isEmpty) {
        return null;
      }
      final candidates = ids.take(6).toList();
      _logDebug("网页索引候选房间号：$candidates");
      _DouyinSearchHit? best;
      var bestScore = 0;
      for (final id in ids.take(6)) {
        final hit = await _resolveByWebRid(id);
        if (hit == null) {
          continue;
        }
        final score = _matchScore(hit, keyword);
        if (score > bestScore) {
          best = hit;
          bestScore = score;
        }
      }
      return bestScore > 0 ? best : null;
    } catch (e) {
      CoreLog.error(e);
    }
    return null;
  }

  /// 命中与关键词的匹配分：同名 3、昵称包含 2、标题包含 1、不相关 0
  int _matchScore(_DouyinSearchHit hit, String keyword) {
    final kw = keyword.trim().toLowerCase();
    if (kw.isEmpty) {
      return 0;
    }
    final nick = hit.userName.trim().toLowerCase();
    final title = hit.title.trim().toLowerCase();
    if (nick == kw) {
      return 3;
    }
    if (nick.isNotEmpty && (nick.contains(kw) || kw.contains(nick))) {
      return 2;
    }
    if (title.isNotEmpty && title.contains(kw)) {
      return 1;
    }
    // 网页索引命中的房间，昵称和标题都不含关键词时仍可能是本人（如昵称带后缀），
    // 但宁可不返回，避免张冠李戴
    return 0;
  }

  /// 拉网页文本，失败返回空串（带超时，兜底链路不能被它拖住）
  Future<String> _fetchTextQuietly(String url) async {
    try {
      final text = await HttpClient.instance
          .getText(url, queryParameters: {}, header: {
        "User-Agent": kDefaultUserAgent,
        "accept": "text/html,application/xhtml+xml",
        "accept-language": "zh-CN,zh;q=0.9",
      }).timeout(const Duration(seconds: 6));
      return text;
    } catch (e) {
      CoreLog.error(e);
      return "";
    }
  }

  /// 从关键词里提取直播间号/抖音号：
  /// 支持纯数字、字母数字抖音号，以及 live.douyin.com/<rid>、抖音分享链接
  String _extractRoomId(String keyword) {
    var kw = keyword.trim();
    final liveMatch =
        RegExp(r"live\.douyin\.com/([A-Za-z0-9_.-]+)").firstMatch(kw);
    if (liveMatch != null) {
      kw = liveMatch.group(1) ?? "";
    } else if (kw.contains("/")) {
      final shareMatch =
          RegExp(r"douyin\.com/[A-Za-z0-9_/.-]*?([A-Za-z0-9_.-]+)$")
              .firstMatch(kw);
      kw = shareMatch?.group(1) ?? "";
    }
    // 只有“纯 ASCII 门牌号/抖音号”才当直连目标；
    // 中文昵称不能剥掉汉字只剩数字（否则「女流66」会被当成房间号 66）
    if (!RegExp(r"^[A-Za-z0-9_.-]+$").hasMatch(kw)) {
      return "";
    }
    return kw;
  }

  /// 通过 webRid（数字直播间号或字母数字抖音号）解析一个直播间/主播
  /// - 正在直播：返回带封面/标题/在线人数的房间
  /// - 未开播：返回只有主播昵称/头像的条目（标题标记为未开播）
  Future<_DouyinSearchHit?> _resolveByWebRid(String webRid) async {
    if (webRid.isEmpty) {
      return null;
    }
    try {
      final raw = await _enterRoomRaw(webRid);
      if (raw == null) {
        return null;
      }
      final statusCode = asT<int?>(raw["status_code"]) ?? -1;
      if (statusCode != 0) {
        return null;
      }
      final data = raw["data"];
      if (data is! Map) {
        return null;
      }
      final user = data["user"];
      final rooms = data["data"];
      if (rooms is List && rooms.isNotEmpty) {
        final room = rooms.first;
        if (room is Map) {
          final owner = room["owner"] is Map ? room["owner"] : user;
          final live = (asT<int?>(room["status"]) ?? 0) == 2;
          return _DouyinSearchHit(
            roomId: webRid,
            title: asT<String?>(room["title"]) ?? "",
            cover: live ? _firstUrl(room["cover"]) : _avatarOf(owner),
            avatar: _avatarOf(owner),
            userName: _nickOf(owner),
            live: live,
            online: live ? _parseOnline(room["room_view_stats"]) : 0,
          );
        }
      }
      if (user is Map) {
        return _DouyinSearchHit(
          roomId: webRid,
          title: "",
          cover: _avatarOf(user),
          avatar: _avatarOf(user),
          userName: _nickOf(user),
          live: false,
          online: 0,
        );
      }
    } catch (e) {
      CoreLog.error(e);
    }
    return null;
  }

  /// 调用 webcast/room/web/enter 原始接口，返回完整 JSON（不抛异常）
  Future<Map?> _enterRoomRaw(String webRid) async {
    try {
      final requestHeader = await getRequestHeaders();
      requestHeader["Referer"] = "https://live.douyin.com/$webRid";
      final uri = Uri.parse("https://live.douyin.com/webcast/room/web/enter/")
          .replace(
            scheme: "https",
            port: 443,
            queryParameters: {
              "aid": "6383",
              "app_name": "douyin_web",
              "live_id": "1",
              "device_platform": "web",
              "language": "zh-CN",
              "enter_from": "web_live",
              "cookie_enabled": "true",
              "screen_width": "1920",
              "screen_height": "1080",
              "browser_language": "zh-CN",
              "browser_platform": "Win32",
              "browser_name": "Chrome",
              "browser_version": "125.0.0.0",
              "web_rid": webRid,
              "msToken": "",
            },
          );
      final requestUrl =
          DouyinSign.getAbogusUrl(uri.toString(), kDefaultUserAgent);
      final result =
          await HttpClient.instance.getJson(requestUrl, header: requestHeader);
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
    } catch (e) {
      CoreLog.error(e);
    }
    return null;
  }

  /// 官方关键词搜索：抖音要求登录态，未登录固定 2483（请先登录）
  Future<List<_DouyinSearchHit>> _searchByOfficialApi(String keyword) async {
    if (cookie.isEmpty) {
      return <_DouyinSearchHit>[];
    }
    try {
      final uri = Uri.parse(_officialSearchApi).replace(
        scheme: "https",
        port: 443,
        queryParameters: {
          "device_platform": "webapp",
          "aid": "6383",
          "channel": "channel_pc_web",
          "search_channel": "aweme_live",
          "keyword": keyword,
          "search_source": "switch_tab",
          "query_correct_type": "1",
          "is_filter_search": "0",
          "from_group_id": "",
          "offset": "0",
          "count": "20",
          "pc_client_type": "1",
          "version_code": "170400",
          "version_name": "17.4.0",
          "cookie_enabled": "true",
          "screen_width": "1980",
          "screen_height": "1080",
          "browser_language": "zh-CN",
          "browser_platform": "Win32",
          "browser_name": "Edge",
          "browser_version": "125.0.0.0",
          "browser_online": "true",
          "engine_name": "Blink",
          "engine_version": "125.0.0.0",
          "os_name": "Windows",
          "os_version": "10",
          "cpu_core_num": "12",
          "device_memory": "8",
          "platform": "PC",
          "downlink": "10",
          "effective_type": "4g",
          "round_trip_time": "100",
          "webid": "7382872326016435738",
        },
      );
      final requestUrl =
          DouyinSign.getAbogusUrl(uri.toString(), kDefaultUserAgent);
      final encoded = Uri.encodeComponent(keyword);
      final result = await HttpClient.instance.getJson(
        requestUrl,
        header: {
          "Authority": "www.douyin.com",
          "accept": "application/json, text/plain, */*",
          "accept-language": "zh-CN,zh;q=0.9,en;q=0.8",
          "cookie": cookie,
          "referer": "https://www.douyin.com/search/$encoded?type=live",
          "user-agent": kDefaultUserAgent,
        },
      );
      if (result is! Map) {
        return <_DouyinSearchHit>[];
      }
      // 2483 = 请先登录，其它非 0 也当作不可用，交给下一级兜底
      if ((asT<int?>(result["status_code"]) ?? 0) != 0) {
        return <_DouyinSearchHit>[];
      }
      final list = result["data"];
      if (list is! List) {
        return <_DouyinSearchHit>[];
      }
      final hits = <_DouyinSearchHit>[];
      for (final item in list) {
        try {
          final itemData = json.decode(item["lives"]["rawdata"].toString());
          final owner = itemData["owner"];
          hits.add(
            _DouyinSearchHit(
              roomId: owner["web_rid"].toString(),
              title: itemData["title"].toString(),
              cover: _firstUrl(itemData["cover"]),
              avatar: _avatarOf(owner),
              userName: _nickOf(owner),
              live: true,
              online:
                  int.tryParse(itemData["stats"]["total_user"].toString()) ??
                      0,
            ),
          );
        } catch (_) {
          // 单条解析失败不影响其它结果
        }
      }
      return hits;
    } catch (e) {
      CoreLog.error(e);
      return <_DouyinSearchHit>[];
    }
  }

  /// 构建“正在直播”的房间索引
  /// - [deep] true 时额外扫各分类，覆盖面上限约 600 个房间
  Future<List<_DouyinSearchHit>> _ensureLiveIndex({bool deep = false}) async {
    final now = DateTime.now();
    if (!deep &&
        _indexCache.isNotEmpty &&
        now.difference(_indexCacheAt) < _indexTtl) {
      return _indexCache;
    }

    final hits = <String, _DouyinSearchHit>{};
    void put(_DouyinSearchHit? hit) {
      if (hit != null && hit.roomId.isNotEmpty) {
        hits.putIfAbsent(hit.roomId, () => hit);
      }
    }

    // 热门/推荐分区：按热度排序，前几页就能覆盖头部主播
    final hotPages = deep ? 6 : 3;
    for (var page = 1; page <= hotPages; page++) {
      final list = await _fetchPartitionRooms("720", "1", page);
      if (list.isEmpty) {
        break;
      }
      list.forEach(put);
    }
    // 推荐流补充
    (await _fetchFeedRooms()).forEach(put);

    // 深度扫描：各分类各取一页
    if (deep || hits.length < 40) {
      final categories = await _safeCategories();
      for (final category in categories) {
        final subs = category.children.take(3).toList();
        for (final sub in subs) {
          final ids = sub.id.split(",");
          if (ids.length < 2) {
            continue;
          }
          final list = await _fetchPartitionRooms(ids[0], ids[1], 1);
          list.forEach(put);
          if (hits.length >= 600) {
            break;
          }
        }
        if (hits.length >= 600) {
          break;
        }
      }
    }

    _indexCache = hits.values.toList();
    _indexCacheAt = now;
    final total = _indexCache.length;
    _logDebug("抖音直播索引：$total 个在播房间（deep=$deep）");
    return _indexCache;
  }

  /// 读取某个分区正在直播的房间
  Future<List<_DouyinSearchHit>> _fetchPartitionRooms(
    String partition,
    String partitionType,
    int page, {
    int count = 20,
  }) async {
    try {
      final uri = Uri.parse(_partitionRoomApi).replace(
        scheme: "https",
        port: 443,
        queryParameters: {
          "aid": "6383",
          "app_name": "douyin_web",
          "live_id": "1",
          "device_platform": "web",
          "language": "zh-CN",
          "enter_from": "link_share",
          "cookie_enabled": "true",
          "screen_width": "1920",
          "screen_height": "1080",
          "browser_language": "zh-CN",
          "browser_platform": "Win32",
          "browser_name": "Chrome",
          "browser_version": "131.0.0.0",
          "browser_online": "true",
          "count": count.toString(),
          "offset": ((page - 1) * count).toString(),
          "partition": partition,
          "partition_type": partitionType,
          "req_from": "2",
        },
      );
      final requestUrl =
          DouyinSign.getAbogusUrl(uri.toString(), kDefaultUserAgent);
      final result = await HttpClient.instance
          .getJson(requestUrl, header: await getRequestHeaders());
      if (result is! Map) {
        return <_DouyinSearchHit>[];
      }
      final data = result["data"];
      final list = (data is Map ? data["data"] : null);
      if (list is! List) {
        return <_DouyinSearchHit>[];
      }
      final hits = <_DouyinSearchHit>[];
      for (final item in list) {
        final hit = _hitFromRoomItem(item);
        if (hit != null) {
          hits.add(hit);
        }
      }
      return hits;
    } catch (e) {
      CoreLog.error(e);
      return <_DouyinSearchHit>[];
    }
  }

  /// 读取推荐流里的在播房间
  Future<List<_DouyinSearchHit>> _fetchFeedRooms() async {
    try {
      final uri = Uri.parse(_feedApi).replace(
        scheme: "https",
        port: 443,
        queryParameters: {
          "aid": "6383",
          "app_name": "douyin_web",
          "live_id": "1",
          "device_platform": "web",
          "language": "zh-CN",
          "enter_from": "link_share",
          "cookie_enabled": "true",
          "screen_width": "1920",
          "screen_height": "1080",
          "browser_language": "zh-CN",
          "browser_platform": "Win32",
          "browser_name": "Chrome",
          "browser_version": "131.0.0.0",
          "browser_online": "true",
          "count": "20",
          "offset": "0",
          "req_from": "2",
        },
      );
      final requestUrl =
          DouyinSign.getAbogusUrl(uri.toString(), kDefaultUserAgent);
      final result = await HttpClient.instance
          .getJson(requestUrl, header: await getRequestHeaders());
      if (result is! Map) {
        return <_DouyinSearchHit>[];
      }
      final list = result["data"];
      if (list is! List) {
        return <_DouyinSearchHit>[];
      }
      final hits = <_DouyinSearchHit>[];
      for (final item in list) {
        if (item is! Map) {
          continue;
        }
        final room = item["data"];
        if (room is! Map) {
          continue;
        }
        final owner = room["owner"];
        final rid =
            (item["web_rid"] ?? (owner is Map ? owner["web_rid"] : "") ?? "")
                .toString();
        if (rid.isEmpty) {
          continue;
        }
        hits.add(
          _DouyinSearchHit(
            roomId: rid,
            title: asT<String?>(room["title"]) ?? "",
            cover: _firstUrl(room["cover"]),
            avatar: _avatarOf(owner),
            userName: _nickOf(owner),
            live: (asT<int?>(room["status"]) ?? 0) != 4,
            online: asT<int?>(room["user_count"]) ?? 0,
          ),
        );
      }
      return hits;
    } catch (e) {
      CoreLog.error(e);
      return <_DouyinSearchHit>[];
    }
  }

  /// 把分区接口里的一条房间数据转成命中条目
  _DouyinSearchHit? _hitFromRoomItem(dynamic item) {
    if (item is! Map) {
      return null;
    }
    final room = item["room"];
    if (room is! Map) {
      return null;
    }
    final owner = room["owner"];
    final rid =
        (item["web_rid"] ?? (owner is Map ? owner["web_rid"] : "") ?? "")
            .toString();
    if (rid.isEmpty) {
      return null;
    }
    // 分区/推荐流接口只返回正在直播的房间；其 room.status 是 reflow 对象的状态位
    // （在播时是 0，不是 enter 接口的 2），只有 4 才表示已结束。
    final status = asT<int?>(room["status"]) ?? 0;
    return _DouyinSearchHit(
      roomId: rid,
      title: asT<String?>(room["title"]) ?? "",
      cover: _firstUrl(room["cover"]),
      avatar: _avatarOf(owner),
      userName: _nickOf(owner),
      live: status != 4,
      online: _parseOnline(room["room_view_stats"]),
    );
  }

  /// 昵称/标题包含匹配，双向包含以覆盖「女流66」←→「女流66nvliu66」这类情况
  List<_DouyinSearchHit> _matchHits(
    List<_DouyinSearchHit> index,
    String keyword,
  ) {
    final kw = keyword.trim().toLowerCase();
    if (kw.isEmpty) {
      return <_DouyinSearchHit>[];
    }
    final matched = <_DouyinSearchHit>[];
    for (final hit in index) {
      final nick = hit.userName.trim().toLowerCase();
      final title = hit.title.trim().toLowerCase();
      if (nick.contains(kw) ||
          title.contains(kw) ||
          (nick.length >= 2 && kw.contains(nick)) ||
          (title.length >= 4 && kw.contains(title))) {
        matched.add(hit);
      }
    }
    // 完全同名 > 昵称前缀 > 昵称包含 > 只有标题命中（标题命中容易被同名粉丝房干扰，排最后）
    matched.sort((a, b) {
      int rank(_DouyinSearchHit h) {
        final nick = h.userName.trim().toLowerCase();
        if (nick == kw) {
          return 0;
        }
        if (nick.startsWith(kw)) {
          return 1;
        }
        if (nick.contains(kw)) {
          return 2;
        }
        return 3;
      }

      final diff = rank(a).compareTo(rank(b));
      if (diff != 0) {
        return diff;
      }
      return b.online.compareTo(a.online);
    });
    return matched;
  }

  /// 记录「关键词 → 直播间」，供后续未开播时命中
  void _learn(String keyword, _DouyinSearchHit hit) {
    final key = keyword.trim().toLowerCase();
    if (key.isEmpty || hit.roomId.isEmpty) {
      return;
    }
    _learnedHits[key] = hit;
  }

  /// 查学习缓存
  _DouyinSearchHit? _lookupLearned(String keyword) {
    final key = keyword.trim().toLowerCase();
    if (key.isEmpty) {
      return null;
    }
    final direct = _learnedHits[key];
    if (direct != null) {
      return direct;
    }
    for (final entry in _learnedHits.entries) {
      if (entry.key.contains(key) || key.contains(entry.key)) {
        return entry.value;
      }
    }
    return null;
  }

  /// 索引结果分页（pageSize 与各端一致，默认 24）
  ({List<_DouyinSearchHit> hits, bool hasMore}) _pageSlice(
    List<_DouyinSearchHit> hits,
    int page, {
    int pageSize = 24,
  }) {
    if (hits.length <= pageSize) {
      return (hits: hits, hasMore: false);
    }
    final start = (page - 1) * pageSize;
    if (start >= hits.length) {
      return (hits: <_DouyinSearchHit>[], hasMore: false);
    }
    final end = min(start + pageSize, hits.length);
    return (hits: hits.sublist(start, end), hasMore: end < hits.length);
  }

  /// 读取分类，失败时返回空列表
  Future<List<LiveCategory>> _safeCategories() async {
    try {
      return await getCategores();
    } catch (e) {
      CoreLog.error(e);
      return <LiveCategory>[];
    }
  }

  /// 取图片列表第一个地址
  String _firstUrl(dynamic cover) {
    try {
      final list = cover["url_list"];
      if (list is List && list.isNotEmpty) {
        return list.first.toString();
      }
    } catch (_) {}
    return "";
  }

  /// 取主播头像
  String _avatarOf(dynamic owner) {
    if (owner is! Map) {
      return "";
    }
    final avatar = owner["avatar_thumb"];
    final url = _firstUrl(avatar);
    if (url.isNotEmpty) {
      return url;
    }
    return _firstUrl(owner["avatar_large"]);
  }

  /// 取主播昵称
  String _nickOf(dynamic owner) {
    if (owner is Map) {
      final nick = asT<String?>(owner["nickname"]);
      if (nick != null && nick.isNotEmpty) {
        return nick;
      }
    }
    return "";
  }

  /// 解析在线人数（分区接口给的是 "1w+" / "1234" 这类展示文本）
  int _parseOnline(dynamic stats) {
    if (stats is! Map) {
      return 0;
    }
    final display = stats["display_value"]?.toString() ?? "";
    final raw = stats["total_user"]?.toString() ?? "";
    final text = display.isNotEmpty ? display : raw;
    if (text.isEmpty) {
      return 0;
    }
    if (text.contains("万") || text.toLowerCase().contains("w")) {
      final value =
          double.tryParse(text.replaceAll(RegExp(r"[^0-9.]"), "")) ?? 0;
      return (value * 10000).round();
    }
    return int.tryParse(text.replaceAll(RegExp(r"[^0-9]"), "")) ?? 0;
  }
  @override
  Future<bool> getLiveStatus({required String roomId}) async {
    var result = await getRoomDetail(roomId: roomId);
    return result.status;
  }

  @override
  Future<List<LiveSuperChatMessage>> getSuperChatMessage({
    required String roomId,
  }) {
    return Future.value(<LiveSuperChatMessage>[]);
  }

  //生成指定长度的16进制随机字符串
  String generateRandomString(int length) {
    var random = Random.secure();
    var values = List<int>.generate(length, (i) => random.nextInt(16));
    StringBuffer stringBuffer = StringBuffer();
    for (var item in values) {
      stringBuffer.write(item.toRadixString(16));
    }
    return stringBuffer.toString();
  }

  // 生成随机的数字
  int generateRandomNumber(int length) {
    var random = Random.secure();
    var values = List<int>.generate(length, (i) => random.nextInt(10));
    StringBuffer stringBuffer = StringBuffer();
    for (var item in values) {
      stringBuffer.write(item);
    }
    return int.tryParse(stringBuffer.toString()) ??
        Random().nextInt(1000000000);
  }
}


/// 抖音搜索的一条命中：直播间结果与主播结果共用
class _DouyinSearchHit {
  _DouyinSearchHit({
    required this.roomId,
    required this.title,
    required this.cover,
    required this.avatar,
    required this.userName,
    required this.live,
    required this.online,
  });

  /// 直播间号（web_rid）；也用作打开直播间的 roomId
  final String roomId;

  /// 直播间标题（未开播时为空）
  final String title;

  /// 直播间封面
  final String cover;

  /// 主播头像
  final String avatar;

  /// 主播昵称
  final String userName;

  /// 是否正在直播
  final bool live;

  /// 在线人数
  final int online;

  @override
  String toString() {
    return 'DouyinSearchHit(roomId: $roomId, userName: $userName, live: $live)';
  }
}
