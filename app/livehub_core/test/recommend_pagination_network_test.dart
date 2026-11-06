import 'dart:io';

import 'package:livehub_core/livehub_core.dart';
import 'package:test/test.dart';

/// 「全部」分类的深翻页联网实测（默认跳过）：
/// LIVEHUB_NETWORK_TEST=1 ~/flutter-3.38/bin/dart test test/recommend_pagination_network_test.dart
void main() {
  final enabled = Platform.environment['LIVEHUB_NETWORK_TEST'] == '1';

  Future<void> expectDeep(String name, LiveSite site) async {
    const pages = 30;
    final seen = <String>{};
    var emptyPages = 0;
    for (var page = 1; page <= pages; page++) {
      final r = await site.getRecommendRooms(page: page);
      if (r.items.isEmpty) {
        emptyPages++;
      }
      seen.addAll(r.items.map((e) => e.roomId));
    }
    print(name + ' 前 ' + pages.toString() + ' 页去重后 ' + seen.length.toString() + ' 个房间');
    // 虎牙对连续请求会偶发 503（已重试 3 次），允许极少数页失败；
    // 关键是「有内容」和「总量足够」。
    expect(emptyPages, lessThanOrEqualTo(1), reason: name + ' 前 30 页几乎不应出现空页');
    expect(seen.length, greaterThan(300), reason: name + ' 前 30 页应累计 300+ 个不重复房间');
  }

  test('斗鱼「全部」30 页', () async {
    await expectDeep('douyu', DouyuSite());
  }, skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑');

  test('虎牙「全部」30 页', () async {
    await expectDeep('huya', HuyaSite());
  }, skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑');

  test('B 站「全部」30 页', () async {
    await expectDeep('bilibili', BiliBiliSite());
  }, skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑');

  test('抖音「全部」30 页', () async {
    await expectDeep('douyin', DouyinSite());
  }, skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑');
}
