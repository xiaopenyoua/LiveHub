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

/// 搜索页：左栏输入框 + 搜索按钮，右栏是历史或结果。
///
/// 一次搜索同时返回「正在直播」与「主播」两类结果；提交搜索只刷新结果，
/// 不自动进入任何直播间。键盘输入交给系统输入法，不做屏上 QWERTY。
class SearchPage extends StatefulWidget {
  const SearchPage({super.key});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  late final TvSearchController _c = Get.find<TvSearchController>();

  final TextEditingController _textController = TextEditingController();
  final AppFocusNode _inputNode = AppFocusNode();
  final AppFocusNode _searchButtonNode = AppFocusNode();
  final AppFocusNode _retryNode = AppFocusNode();
  final Map<String, AppFocusNode> _historyNodes = {};

  @override
  void initState() {
    super.initState();
    // 输入框聚焦后，方向键要能离开输入框：下到搜索按钮，右方向键只在
    // 光标已在末尾时才进入结果区，否则交给 TextField 移动光标。
    _inputNode.onKeyEvent = (node, event) {
      if (event is! KeyDownEvent) return KeyEventResult.ignored;
      if (event.logicalKey == LogicalKeyboardKey.arrowDown) {
        _searchButtonNode.requestFocus();
        return KeyEventResult.handled;
      }
      if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
        final selection = _textController.selection;
        final atEnd = selection.isCollapsed &&
            selection.baseOffset == _textController.text.length;
        if (atEnd) {
          final first = _firstResultNode;
          if (first != null) {
            first.requestFocus();
            return KeyEventResult.handled;
          }
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

  AppFocusNode? get _firstResultNode {
    if (_c.rooms.isNotEmpty) return _c.rooms.first.focusNode;
    if (_c.anchors.isNotEmpty) return _c.anchors.first.focusNode;
    return null;
  }

  /// 提交搜索：只刷新结果区，进入哪个直播间由用户按 OK 选择。
  Future<void> _submit({String? raw}) async {
    final text = (raw ?? _textController.text).trim();
    await _c.search(text);
    if (!mounted || text.isEmpty) return;
    _focusFirstResult();
  }

  void _focusFirstResult() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _firstResultNode?.requestFocus();
    });
  }

  /// 全平台失败后的重试：同样只刷新结果，不自动打开。
  void _retry() {
    final text = _textController.text.trim();
    _c
        .search(
      text.isEmpty ? _c.queryText.value : text,
      recordHistory: false,
    )
        .then((_) {
      if (mounted) _focusFirstResult();
    });
  }

  void _useHistory(String word) {
    _textController.text = word;
    _textController.selection =
        TextSelection.collapsed(offset: _textController.text.length);
    _submit(raw: word);
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
                  "输入主播或直播间关键字，一次搜索四个平台。",
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
    // 输入法弹出时窗口高度会被 adjustResize 压掉一大截，这里的固定高度内容
    // （96u 输入框 + 24u 间距 + 搜索按钮）一旦超过剩余高度就会竖向溢出，
    // 表现是紧贴键盘上方那条黄黑警示条。改成可滚动即不会再溢出。
    return SingleChildScrollView(
      child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
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
                    hintText: "输入主播名、房间号或关键字",
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
      ),
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
      if (_c.resultCount == 0) {
        return _buildEmpty(t);
      }
      return _buildResults(t);
    });
  }

  Widget _buildEmpty(LiveHubTokens t) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          "没有找到相关直播间或主播，换个关键字试试。",
          style: t.body.copyWith(color: t.muted),
        ),
        if (_c.platformNotices.isNotEmpty) ...[
          SizedBox(height: 16 * t.unit),
          Text(
            _c.platformNotices.join(" · "),
            style: t.small.copyWith(color: t.faint),
          ),
        ],
      ],
    );
  }

  Widget _buildResults(LiveHubTokens t) {
    final rooms = _c.rooms;
    final anchors = _c.anchors;
    return CustomScrollView(
      slivers: [
        if (_c.platformNotices.isNotEmpty)
          SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.only(bottom: 16 * t.unit),
              child: Text(
                _c.platformNotices.join(" · "),
                style: t.small.copyWith(color: t.faint),
              ),
            ),
          ),
        if (rooms.isNotEmpty) ...[
          SliverToBoxAdapter(
            child: _sectionHeader(t, "正在直播", rooms.length),
          ),
          SliverPadding(
            padding: EdgeInsets.fromLTRB(
              24 * t.unit,
              12 * t.unit,
              24 * t.unit,
              24 * t.unit,
            ),
            sliver: SliverGrid(
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                crossAxisSpacing: 20 * t.unit,
                mainAxisSpacing: 24 * t.unit,
                childAspectRatio: 16 / 9,
              ),
              delegate: SliverChildBuilderDelegate(
                (_, i) {
                  final item = rooms[i];
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
                childCount: rooms.length,
              ),
            ),
          ),
        ],
        if (anchors.isNotEmpty) ...[
          SliverToBoxAdapter(
            child: _sectionHeader(t, "主播", anchors.length),
          ),
          SliverPadding(
            padding: EdgeInsets.fromLTRB(
              16 * t.unit,
              12 * t.unit,
              24 * t.unit,
              60 * t.unit,
            ),
            sliver: SliverGrid(
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 4,
                crossAxisSpacing: 20 * t.unit,
                mainAxisSpacing: 20 * t.unit,
                mainAxisExtent: 236 * t.unit,
              ),
              delegate: SliverChildBuilderDelegate(
                (_, i) {
                  final item = anchors[i];
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
                childCount: anchors.length,
              ),
            ),
          ),
        ],
        const SliverToBoxAdapter(child: SizedBox(height: 60)),
      ],
    );
  }

  Widget _sectionHeader(LiveHubTokens t, String title, int count) {
    return Padding(
      padding: EdgeInsets.only(left: 24 * t.unit),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          Text(title, style: t.title),
          SizedBox(width: 12 * t.unit),
          Text(
            "$count 个",
            style: t.meta.copyWith(color: t.faint),
          ),
        ],
      ),
    );
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
}
