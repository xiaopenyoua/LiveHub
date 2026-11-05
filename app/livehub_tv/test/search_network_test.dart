import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:livehub_tv/app/sites.dart';

/// 电视端搜索链路的联网实测（走 TV 端自己的 Sites 装配 + livehub_core）。
/// 默认跳过，手动跑：
/// LIVEHUB_NETWORK_TEST=1 ~/flutter-3.38/bin/flutter test test/search_network_test.dart
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // flutter_test 默认把所有 HTTP 请求挡成 400，联网用例必须放开
  HttpOverrides.global = null;
  final enabled = Platform.environment['LIVEHUB_NETWORK_TEST'] == '1';

  Future<List<String>> searchAll(String keyword) async {
    final out = <String>[];
    for (final site in Sites.supportSites) {
      try {
        final r = await site.liveSite.searchRooms(keyword);
        for (final item in r.items.take(2)) {
          out.add(site.shortName + ':' + item.userName + '/' + item.roomId);
        }
      } catch (e) {
        out.add(site.shortName + ':ERR ' + e.toString());
      }
    }
    return out;
  }

  test(
    '电视端搜索「yjjimpaopao」命中抖音直播间',
    () async {
      final hits = await searchAll('yjjimpaopao');
      // ignore: avoid_print
      print('yjjimpaopao -> ' + hits.toString());
      expect(hits.any((e) => e.startsWith('抖音:') && !e.contains('ERR')), isTrue);
    },
    skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑',
  );

  test(
    '电视端搜索「女流66」命中抖音主播',
    () async {
      final hits = await searchAll('女流66');
      // ignore: avoid_print
      print('女流66 -> ' + hits.toString());
      expect(hits.any((e) => e.startsWith('抖音:') && !e.contains('ERR')), isTrue);
    },
    skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑',
  );
}
