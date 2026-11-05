import 'dart:io';

import 'package:livehub_core/livehub_core.dart';
import 'package:test/test.dart';

String fmt(LiveRoomItem e) {
  return e.userName +
      '/' +
      e.roomId +
      '/' +
      e.title +
      '/online=' +
      e.online.toString();
}

/// 抖音搜索联网实测（需要真实网络，默认跳过）。
/// 本地手动跑：LIVEHUB_NETWORK_TEST=1 ~/flutter-3.38/bin/dart test test/douyin_search_network_test.dart
void main() {
  final enabled = Platform.environment['LIVEHUB_NETWORK_TEST'] == '1';
  test(
    '抖音搜索：正在直播的主播昵称（yjjimpaopao）',
    () async {
      final site = DouyinSite();
      final result = await site.searchRooms('yjjimpaopao');
      expect(result.items, isNotEmpty);
      expect(result.items.first.roomId, isNotEmpty);
      print('yjjimpaopao -> ' + fmt(result.items.first));
    },
    skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑',
    timeout: const Timeout(Duration(minutes: 3)),
  );

  test(
    '抖音搜索：未开播的主播昵称（女流66）走网页索引兜底',
    () async {
      final site = DouyinSite();
      final result = await site.searchRooms('女流66');
      expect(result.items, isNotEmpty);
      print('女流66 -> ' + fmt(result.items.first));
    },
    skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑',
    timeout: const Timeout(Duration(minutes: 3)),
  );

  test(
    '抖音搜索：抖音号直连（nvliu66）',
    () async {
      final site = DouyinSite();
      final result = await site.searchRooms('nvliu66');
      expect(result.items, isNotEmpty);
      expect(result.items.first.roomId, 'nvliu66');
      print('nvliu66 -> ' + fmt(result.items.first));
    },
    skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑',
    timeout: const Timeout(Duration(minutes: 2)),
  );
}
