import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/modules/search/search_controller.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_tv/widgets/card/live_room_card.dart';
import 'package:livehub_tv/widgets/card/person_card.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';
import 'package:livehub_tv/widgets/tv_page_scaffold.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 搜索页：左栏是“直播间 / 主播”切换 + 输入框 + 搜索按钮，右栏是历史或结果。
///
/// 键盘方案被用户明确改成类型切换：不做屏上 QWERTY 键盘，输入交给系统输入法。
class SearchPage extends StatefulWidget {
  const SearchPage({super.key});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  late final TvSearchController _c = Get.find<TvSearchController>();

  final TextEditingController _textController = TextEditingController();
  final AppFocusNode _roomModeNode = AppFocusNode();
  final AppFocusNode _anchorModeNode = AppFocusNode();
  final AppFocusNode _inputNode = AppFocusNode();
  final AppFocusNode _searchButtonNode = AppFocusNode();
  final AppFocusNode _retryNode = AppFocusNode();
  final Map<String, AppFocusNode> _historyNodes = {};

  @override
  void initState() {
    super.initState();
    // 输入框聚焦后，方向键要能离开输入框：上下到模式切换/搜索按钮，
    // 右方向键只在光标已在末尾时才进入右栏，否则交给 TextField 移动光标。
    _inputNode.onKeyEvent = (node, event) {
      if (event is! KeyDownEvent) return KeyEventResult.ignored;
      if (event.logicalKey == LogicalKeyboardKey.arrowDown) {
        _searchButtonNode.requestFocus();
        return KeyEventResult.handled;
      }
      if (event.logicalKey == LogicalKeyboardKey.arrowUp) {
        (_c.mode.value == 0 ? _roomModeNode : _anchorModeNode).requestFocus();
        return KeyEventResult.handled;
      }
      if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
        final selection = _textController.selection;
        final atEnd = selection.isCollapsed &&
            selection.baseOffset == _textController.text.length;
        if (atEnd) {
          _inputNode.focusInDirection(TraversalDirection.right);
          return KeyEventResult.handled;
        }
        return KeyEventResult.ignored;
      }
      if (event.logicalKey == LogicalKeyboardKey.select ||
          event.logicalKey == LogicalKeyboardKey.enter) {
        // 部分盒子的 OK 键不会自动唤起输入法，这里显式补一次。
        SystemChannels.textInput.invokeMethod<void>('TextInput.show');
      }
      return KeyEventResult.ignored;
    };
  }

  @override
  void dispose() {
    _textController.dispose();
    _roomModeNode.dispose();
    _anchorModeNode.dispose();
    _inputNode.dispose();
    _searchButtonNode.dispose();
    _retryNode.dispose();
    for (final node in _historyNodes.values) {
      node.dispose();
    }
    super.dispose();
  }

  AppFocusNode _historyNode(String word) =>
      _historyNodes.putIfAbsent(word, () => AppFocusNode());

  /// 提交搜索。空白查询交给控制器回到“搜索历史”；有结果时按设计规范
  /// §8.3 打开第一条（“搜索键打开第一条”）。
  Future<void> _submit({String? raw, bool? openFirst}) async {
    final text = (raw ?? _textController.text).trim();
    await _c.search(text);
    if (!mounted || text.isEmpty) return;
    // 只有查询完全命中（房间号 / 精确主播名）才直接进直播间；
    // 普通关键字先把结果留在右栏，避免一按搜索就被播放器接走。
    final shouldOpen = openFirst ?? _c.firstIsExact;
    if (!shouldOpen) return;
    if (_c.mode.value == 0) {
      if (_c.rooms.isEmpty) return;
      final first = _c.rooms.first;
      AppNavigator.toLiveRoomDetail(site: first.site, roomId: first.roomId);
    } else {
      if (_c.anchors.isEmpty) return;
      final first = _c.anchors.first;
      AppNavigator.toLiveRoomDetail(site: first.site, roomId: first.roomId);
    }
  }

  /// 全平台失败后的重试：不自动打开第一条，让用户先看到结果。
  void _retry() {
    final text = _textController.text.trim();
    _c.search(
      text.isEmpty ? _c.queryText.value : text,
      recordHistory: false,
    );
  }

  void _useHistory(String word) {
    _textController.text = word;
    _textController.selection =
        TextSelection.collapsed(offset: _textController.text.length);
    _submit(raw: word, openFirst: false);
    _searchButtonNode.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return TvPageScaffold(
      tab: TvTab.search,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(
              80 * t.unit,
              32 * t.unit,
              80 * t.unit,
              0,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text("搜索", style: t.h1),
                SizedBox(height: 10 * t.unit),
                Text(
                  "输入直播间或主播关键字，按直播间 / 主播切换结果类型。",
                  style: t.body.copyWith(color: t.muted),
                ),
              ],
            ),
          ),
          SizedBox(height: 28 * t.unit),
          Expanded(
            child: Padding(
              padding: EdgeInsets.fromLTRB(
                80 * t.unit,
                0,
                80 * t.unit,
                0,
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  SizedBox(width: 700 * t.unit, child: _buildLeft(t)),
                  SizedBox(width: 44 * t.unit),
                  Expanded(child: _buildRight(t)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLeft(LiveHubTokens t) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Obx(
          () => Row(
            children: [
              HighlightButton(
                text: "直播间",
                iconData: Icons.live_tv,
                selected: _c.mode.value == 0,
                focusNode: _roomModeNode,
                autofocus: true,
                onTap: () {
                  _roomModeNode.requestFocus();
                  _c.setMode(0, query: _textController.text);
                },
              ),
              SizedBox(width: 20 * t.unit),
              HighlightButton(
                text: "主播",
                iconData: Icons.person,
                selected: _c.mode.value == 1,
                focusNode: _anchorModeNode,
                onTap: () {
                  _anchorModeNode.requestFocus();
                  _c.setMode(1, query: _textController.text);
                },
              ),
            ],
          ),
        ),
        SizedBox(height: 28 * t.unit),
        Obx(
          () => LiveHubGlass(
            level: _inputNode.isFoucsed.value ? 0.18 : 0.055,
            radius: t.cardRadius,
            child: SizedBox(
              width: 700 * t.unit,
              height: 96 * t.unit,
              child: Center(
                child: TextField(
                  controller: _textController,
                  focusNode: _inputNode,
                  style: t.body.copyWith(color: t.text),
                  cursorColor: t.text,
                  maxLines: 1,
                  textInputAction: TextInputAction.search,
                  inputFormatters: [
                    LengthLimitingTextInputFormatter(32),
                  ],
                  onSubmitted: (_) => _submit(),
                  decoration: InputDecoration(
                    filled: false,
                    border: InputBorder.none,
                    hintText: _c.mode.value == 0 ? "输入直播间关键字" : "输入主播关键字",
                    hintStyle: t.body.copyWith(color: t.faint),
                    contentPadding:
                        EdgeInsets.symmetric(horizontal: 32 * t.unit),
                  ),
                ),
              ),
            ),
          ),
        ),
        SizedBox(height: 24 * t.unit),
        HighlightButton(
          text: "搜索",
          iconData: Icons.search,
          focusNode: _searchButtonNode,
          onTap: () => _submit(),
        ),
      ],
    );
  }

  Widget _buildRight(LiveHubTokens t) {
    return Obx(() {
      if (_c.searching.value) {
        return Center(
          child: CircularProgressIndicator(color: t.text),
        );
      }
      if (_c.allFailed.value) {
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _c.errorText.value.isEmpty
                    ? "搜索失败，请检查网络后重试"
                    : _c.errorText.value,
                textAlign: TextAlign.center,
                style: t.body.copyWith(color: t.muted),
              ),
              SizedBox(height: 24 * t.unit),
              HighlightButton(
                text: "重试",
                iconData: Icons.refresh,
                focusNode: _retryNode,
                autofocus: true,
                onTap: _retry,
              ),
            ],
          ),
        );
      }
      if (!_c.hasSearched.value) {
        return _buildHistory(t);
      }
      final count = _c.resultCount;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            "找到 $count 个结果",
            style: t.meta.copyWith(color: t.faint),
          ),
          if (_c.firstIsExact) ...[
            SizedBox(height: 8 * t.unit),
            Text(
              "按「搜索」直达第一条结果",
              style: t.small.copyWith(color: t.faint),
            ),
          ],
          SizedBox(height: 20 * t.unit),
          Expanded(
            child: count == 0
                ? Align(
                    alignment: Alignment.topLeft,
                    child: Text(
                      _c.mode.value == 0
                          ? "没有找到相关直播间，换个关键字试试。"
                          : "没有找到相关主播，换个关键字试试。",
                      style: t.body.copyWith(color: t.muted),
                    ),
                  )
                : (_c.mode.value == 0
                    ? _buildRoomGrid(t)
                    : _buildAnchorGrid(t)),
          ),
        ],
      );
    });
  }

  Widget _buildHistory(LiveHubTokens t) {
    final words = _c.history;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text("搜索历史", style: t.meta.copyWith(color: t.faint)),
        SizedBox(height: 20 * t.unit),
        Expanded(
          child: words.isEmpty
              ? Align(
                  alignment: Alignment.topLeft,
                  child: Text(
                    "还没有搜索记录",
                    style: t.body.copyWith(color: t.muted),
                  ),
                )
              : GridView.builder(
                  // 左/上留出焦点放大后的外扩量，别让滚动容器把第一列/行切掉。
                  padding: EdgeInsets.fromLTRB(
                    16 * t.unit,
                    12 * t.unit,
                    24 * t.unit,
                    60 * t.unit,
                  ),
                  gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 2,
                    mainAxisExtent: 64 * t.unit,
                    crossAxisSpacing: 12 * t.unit,
                    mainAxisSpacing: 12 * t.unit,
                  ),
                  itemCount: words.length,
                  itemBuilder: (_, i) {
                    final word = words[i];
                    return HighlightWidget(
                      focusNode: _historyNode(word),
                      borderRadius: t.listRadius,
                      focusScale: 1.02,
                      onTap: () => _useHistory(word),
                      child: Padding(
                        padding: EdgeInsets.symmetric(
                          horizontal: 26 * t.unit,
                        ),
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            word,
                            style: t.body.copyWith(color: t.muted),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }

  Widget _buildRoomGrid(LiveHubTokens t) {
    final items = _c.rooms;
    return GridView.builder(
      // 设计稿的 .card-grid 下限是 400px：2 列时每张约 474px，
      // 3 列会掉到 317px，22px 的标题会被普遍截断。
      // 2 列时焦点放大 1.08 左右各外扩约 19px，左留白要大于它。
      padding: EdgeInsets.fromLTRB(
        24 * t.unit,
        12 * t.unit,
        24 * t.unit,
        60 * t.unit,
      ),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        crossAxisSpacing: 20 * t.unit,
        mainAxisSpacing: 24 * t.unit,
        childAspectRatio: 16 / 9,
      ),
      itemCount: items.length,
      itemBuilder: (_, i) {
        final item = items[i];
        return LiveRoomCard(
          cover: item.cover,
          title: item.title,
          anchor: item.userName,
          siteName: item.siteName,
          roomId: item.roomId,
          online: item.online,
          focusNode: item.focusNode,
          onTap: () => AppNavigator.toLiveRoomDetail(
            site: item.site,
            roomId: item.roomId,
          ),
        );
      },
    );
  }

  Widget _buildAnchorGrid(LiveHubTokens t) {
    final items = _c.anchors;
    return GridView.builder(
      padding: EdgeInsets.fromLTRB(
        16 * t.unit,
        12 * t.unit,
        24 * t.unit,
        60 * t.unit,
      ),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 4,
        crossAxisSpacing: 20 * t.unit,
        mainAxisSpacing: 20 * t.unit,
        mainAxisExtent: 236 * t.unit,
      ),
      itemCount: items.length,
      itemBuilder: (_, i) {
        final item = items[i];
        return PersonCard(
          avatar: item.avatar,
          name: item.userName,
          siteName: item.siteName,
          liveStatus: item.liveStatus,
          focusNode: item.focusNode,
          onTap: () => AppNavigator.toLiveRoomDetail(
            site: item.site,
            roomId: item.roomId,
          ),
        );
      },
    );
  }
}
