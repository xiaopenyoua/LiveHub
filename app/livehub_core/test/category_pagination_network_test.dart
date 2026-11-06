import 'dart:io';

import 'package:livehub_core/livehub_core.dart';
import 'package:test/test.dart';

/// 分类页分页联网实测（默认跳过）。
/// LIVEHUB_NETWORK_TEST=1 ~/flutter-3.38/bin/dart test test/category_pagination_network_test.dart
void main() {
  final enabled = Platform.environment['LIVEHUB_NETWORK_TEST'] == '1';

  Future<void> expectPaged(String name, LiveSite site) async {
    final cats = await site.getCategores();
    expect(cats, isNotEmpty, reason: name + ' 分类不能为空');
    final cat = cats.first;
    final sub = cat.children.length > 1 ? cat.children[1] : cat.children.first;
    final seen = <String>{};
    for (var page = 1; page <= 3; page++) {
      final r = await site.getCategoryRooms(sub, page: page);
      expect(r.items, isNotEmpty, reason: name + ' 第 ' + page.toString() + ' 页不应为空');
      seen.addAll(r.items.map((e) => e.roomId));
    }
    expect(seen.length, greaterThan(30), reason: name + ' 三页至少 30 个不同房间');
    print(name + ' ' + cat.name + '/' + sub.name + ' 三页去重后 ' + seen.length.toString() + ' 个房间');
  }

  test('B 站分类分页没被风控挡死（-352 回归）', () async {
    await expectPaged('bilibili', BiliBiliSite());
  }, skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑');

  test('抖音分类退化成「推荐」且能翻页', () async {
    await expectPaged('douyin', DouyinSite());
  }, skip: enabled ? false : '设置 LIVEHUB_NETWORK_TEST=1 才跑');
}
