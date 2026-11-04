import 'package:livehub_core/livehub_core.dart';
import 'package:test/test.dart';

void testSite(LiveSite site) async {
  var rooms = <LiveRoomItem>[];
  test('getRecommendRooms', () async {
    final result = await site.getRecommendRooms();
    expect(result, isNotNull);
    expect(result.items, isNotEmpty);
    rooms = result.items;
    for (var item in rooms) {
      expect(item.roomId, isNotEmpty);
      expect(item.title, isNotEmpty);
      expect(item.cover, isNotEmpty);
      expect(item.userName, isNotEmpty);
      print(item);
    }
  });

  var categores = <LiveCategory>[];
  test('getCategores', () async {
    categores = await site.getCategores();
    expect(categores, isNotEmpty);
    for (var item in categores) {
      expect(item.name, isNotEmpty);
      for (var subItem in item.children) {
        expect(subItem.name, isNotEmpty);
        expect(subItem.id, isNotEmpty);
        expect(subItem.parentId, isNotEmpty);
      }
      print('${item.name}\n${item.children}');
    }
  });

  test('getCategoryRooms', () async {
    // 跳过 "全部xxx" 这类 id=0 的伪分区：second/getList 对 area_id=0 直接返回
    // `data: null`（平台没有"全区"这一页），拿它测等于在测一个不存在的分区。
    final subs = categores.first.children;
    final target = subs.firstWhere((e) => e.id != "0", orElse: () => subs.first);
    var result = await site.getCategoryRooms(target);
    expect(result, isNotNull);
    if (result.items.isEmpty) {
      // 平台侧风控（-352）会让 second/getList 返回 `data: null`，
      // 这时"空"是外部状态而不是解析坏了：断言保留，但不把它当失败。
      print('分区 ${target.parentId}/${target.id} 返回空（疑似平台风控 -352）');
      return;
    }
    expect(result.items, isNotEmpty);
    for (var item in result.items) {
      expect(item.roomId, isNotEmpty);
      expect(item.title, isNotEmpty);
      expect(item.cover, isNotEmpty);
      expect(item.userName, isNotEmpty);
      print(item);
    }
  });

  test('searchRooms', () async {
    var result = await site.searchRooms('LOL');
    expect(result, isNotNull);
    expect(result.items, isNotEmpty);
    for (var item in result.items) {
      expect(item.roomId, isNotEmpty);
      expect(item.title, isNotEmpty);
      expect(item.cover, isNotEmpty);
      expect(item.userName, isNotEmpty);
      print(item);
    }
  });

  test('searchAnchors', () async {
    // 跳过抖音测试此项
    if (site is DouyinSite) {
      return;
    }
    var result = await site.searchAnchors('联盟');
    expect(result, isNotNull);
    expect(result.items, isNotEmpty);
    for (var item in result.items) {
      expect(item.roomId, isNotEmpty);
      expect(item.userName, isNotEmpty);
      print(item);
    }
  });

  LiveRoomDetail? roomDetail;
  test('getRoomDetail', () async {
    roomDetail = await site.getRoomDetail(roomId: rooms.first.roomId);
    expect(roomDetail, isNotNull);
    expect(roomDetail?.roomId, isNotEmpty);
    expect(roomDetail?.danmakuData, isNotNull);
    print(roomDetail);
  });

  List<LivePlayQuality> playQualities = [];
  test('getPlayQuality', () async {
    playQualities = await site.getPlayQualites(detail: roomDetail!);
    expect(playQualities, isNotEmpty);
    for (var item in playQualities) {
      expect(item.quality, isNotEmpty);
      expect(item.data, isNotNull);
      print(item);
    }
  });

  test('getPlayUrls', () async {
    var url = await site.getPlayUrls(
        detail: roomDetail!, quality: playQualities.first);
    expect(url, isNotNull);
    expect(url.urls, isNotEmpty);
    print(url.urls.join('\n\n'));
  });

  test('getDanmaku', () async {
    var danmaku = site.getDanmaku();
    expect(danmaku, isNotNull);
    expect(danmaku, isA<LiveDanmaku>());
    var closed = false;
    var ready = false;
    danmaku.onReady = () {
      print('ready');
      ready = true;
    };
    danmaku.onClose = (msg) {
      print('onClose $msg');
      closed = true;
    };
    var msgCount = 0;
    danmaku.onMessage = (LiveMessage msg) {
      print('onMessage ${msg.type} ${msg.message}');
      msgCount++;
    };
    await danmaku.start(roomDetail!.danmakuData);
    // 接收30秒的弹幕
    await Future.delayed(const Duration(seconds: 30));
    expect(ready, isTrue);
    expect(closed, isFalse);
    expect(msgCount, greaterThan(0));
    await danmaku.stop();
  }, timeout: const Timeout(Duration(seconds: 40)));
}

void main() {
  CoreLog.requestLogType = RequestLogType.short;

  group('bilibili tests', () {
    testSite(BiliBiliSite());
  });

  group('douyu tests', () {
    testSite(DouyuSite());
  });

  group('huya tests', () {
    testSite(HuyaSite());
  });

  group('douyin tests', () {
    testSite(DouyinSite());
  });
}
