import 'package:flutter_test/flutter_test.dart';
import 'package:livehub_tv/routes/tv_tab.dart';

void main() {
  test('TV 一级页签的顺序、标签与路由固定', () {
    expect(
      TvTab.values.map((e) => e.label).toList(),
      ['首页', '关注', '分类', '搜索', '记录', '设置'],
    );
    expect(
      TvTab.values.map((e) => e.route).toList(),
      ['/home', '/follow', '/category', '/search', '/history', '/settings'],
    );
  });
}
