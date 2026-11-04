import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_screenutil/flutter_screenutil.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/sites.dart';
import 'package:livehub_tv/app/startup_signal.dart';
import 'package:livehub_tv/modules/home/home_controller.dart';
import 'package:livehub_tv/widgets/highlight_widget.dart';

/// 应用内开屏，按 `design/v1.0.0/splash.html` 实现的 TV 版。
///
/// 结构：字标 + 四行平台连接状态，同轴居中于 x=960。
/// 开屏的作用是盖住首页首屏加载直播间的 loading：等首页第一页有结论后再退场，
/// `T = max(960, 结论时刻 + 200)`。首页第一页四家全失败时不退出，整块换成
/// 「标题 + 说明 + 重试」。
/// 行序钉死 哔哩 / 斗鱼 / 虎牙 / 抖音，不按状态或响应速度排序。
class SplashOverlay extends StatefulWidget {
  const SplashOverlay({super.key, this.waitForHome = true});

  /// 首页在开屏背后时，以首页第一页的结论为准；
  /// 首次启动先到用户协议页时没有首页可等，退回按平台探测结论退场。
  final bool waitForHome;

  @override
  State<SplashOverlay> createState() => _SplashOverlayState();
}

enum _SiteState { connecting, ready, late }

// 设计稿里的五个颜色值，一个都不多（splash.html :root）。
const Color _ground = Color(0xFF1A140F);
const Color _white = Color(0xFFFFFFFF);
const Color _red = Color(0xFFFF453A);
const Color _dim = Color(0xFFB4B4B4);
const Color _dim2 = Color(0xFF8E8E93);

const String _latin = 'packages/livehub_ui/Inter';
const List<String> _cjkFallback = [
  'PingFang SC',
  'Noto Sans CJK SC',
  'Microsoft YaHei',
];

// 04 §3.1：入场、退场、既有 --ease 三条曲线。
const Cubic _enterCurve = Cubic(0.22, 1, 0.36, 1);
const Cubic _exitCurve = Cubic(0.4, 0, 1, 1);
const Cubic _easeCurve = Cubic(0.32, 0.72, 0, 1);

class _SplashOverlayState extends State<SplashOverlay>
    with TickerProviderStateMixin {
  /// 行序钉死（03 §5），不跟随 Sites.supportSites 的全局顺序。
  static const List<String> _siteIds = [
    "bilibili",
    "douyu",
    "huya",
    "douyin",
  ];

  /// site 层超时：到点必有结论（04 §3.3），只决定四行状态，不决定退场。
  static const Duration _probeTimeout = kSiteProbeTimeout;

  /// 一次状态翻转被看见的最短时长，也是 T 公式里的 200。
  static const int _resultFlipMs = 200;

  /// 入场结束时刻：品牌曝光的下限。
  static const int _entryMs = 960;

  /// 字标基线与状态行基线（03 §5）。
  static const double _wordmarkBaseline = 583;
  static const double _firstRowBaseline = 695;
  static const double _rowGap = 48;

  /// CSS `line-height:1` 下 Inter 的行盒顶到基线距离，见 splash.html :root 推导。
  static const double _baselineRatio = 0.8633;

  final Map<String, _SiteState> _states = {};
  final AppFocusNode _retryFocusNode = AppFocusNode();
  final FocusNode _blockerFocusNode = FocusNode(debugLabel: "splash-blocker");

  late final List<Site> _sites;
  late final AnimationController _enter;
  late final AnimationController _exit;

  bool _started = false;
  bool _reduced = false;
  bool _failed = false;
  bool _offline = false;
  bool _exiting = false;
  bool _done = false;

  int _runId = 0;
  int _completed = 0;
  int? _firstReadyMs;
  bool _exitScheduled = false;
  int _minExposureMs = _entryMs;

  final Stopwatch _clock = Stopwatch();
  Timer? _exitTimer;
  Timer? _failTimer;
  Timer? _homeCapTimer;
  Future<bool>? _offlineFuture;

  @override
  void initState() {
    super.initState();
    _sites = [
      for (final id in _siteIds)
        if (Sites.allSites[id] != null) Sites.allSites[id]!,
    ];
    for (final site in _sites) {
      _states[site.id] = _SiteState.connecting;
    }
    if (widget.waitForHome) {
      HomeFirstPage.state.addListener(_onHomeState);
    }
    _enter = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: _entryMs),
    );
    _exit = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: _resultFlipMs),
    );
    _exit.addStatusListener((status) {
      if (status == AnimationStatus.completed) {
        setState(() => _done = true);
        // 开屏期间焦点被拦在这里，首页自己的 autofocus 已经被跳过。
        // 退场后把焦点还给首页主图按钮；没有首页（协议页）时给第一个可聚焦元素。
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          if (widget.waitForHome && Get.isRegistered<HomeController>()) {
            final home = Get.find<HomeController>();
            if (home.heroItem != null) {
              home.heroFocusNode.requestFocus();
              return;
            }
          }
          final descendants = FocusManager
              .instance.rootScope.traversalDescendants
              .where((node) => node is! FocusScopeNode);
          if (descendants.isNotEmpty) descendants.first.requestFocus();
        });
      }
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    _reduced = MediaQuery.of(context).disableAnimations;
    if (_reduced) {
      _enter.duration = const Duration(milliseconds: 16);
      _exit.duration = const Duration(milliseconds: 16);
      // reduced-motion：首帧即终态，但整段仍保留 960ms 静止曝光（04 §5）。
      _enter.value = 1;
    } else {
      _enter.forward();
    }
    _startRun(minExposureMs: _entryMs);
    // 极端情况：首页在开屏第一帧之前就已有结论（热重载）。
    if (widget.waitForHome) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _onHomeState();
      });
    }
  }

  @override
  void dispose() {
    if (widget.waitForHome) {
      HomeFirstPage.state.removeListener(_onHomeState);
    }
    _exitTimer?.cancel();
    _failTimer?.cancel();
    _homeCapTimer?.cancel();
    _enter.dispose();
    _exit.dispose();
    _retryFocusNode.dispose();
    _blockerFocusNode.dispose();
    super.dispose();
  }

  void _startRun({required int minExposureMs}) {
    final run = ++_runId;
    _exitTimer?.cancel();
    _failTimer?.cancel();
    _homeCapTimer?.cancel();
    _clock
      ..reset()
      ..start();
    _completed = 0;
    _firstReadyMs = null;
    _exitScheduled = false;
    _minExposureMs = minExposureMs;
    // 只在真失败时才用得上；和四家探测并行跑，别让降级文案再多等一次 DNS。
    _offlineFuture = _looksOffline();

    if (_failed || _exiting) {
      setState(() {
        _failed = false;
        _offline = false;
        _exiting = false;
        for (final site in _sites) {
          _states[site.id] = _SiteState.connecting;
        }
      });
    }

    for (final site in _sites) {
      _probe(site, run);
    }

    if (widget.waitForHome) {
      // 首页第一页正常在 1–2s 内出结论；这里的 5s 只是兜底，
      // 不让异常状态无限拦路（到点仍露首页自己的 loading / 错误态）。
      _homeCapTimer = Timer(const Duration(seconds: 5), () {
        if (!mounted || run != _runId || _done || _exiting || _failed) return;
        _scheduleExit(run, _clock.elapsedMilliseconds);
      });
    }
  }

  Future<void> _probe(Site site, int run) async {
    var state = _SiteState.late;
    try {
      final result =
          await site.liveSite.getRecommendRooms(page: 1).timeout(_probeTimeout);
      if (result.items.isNotEmpty) state = _SiteState.ready;
    } catch (_) {
      state = _SiteState.late;
    }
    if (!mounted || run != _runId) return;

    final elapsed = _clock.elapsedMilliseconds;
    setState(() => _states[site.id] = state);

    if (state == _SiteState.ready && _firstReadyMs == null) {
      _firstReadyMs = elapsed;
      // 等首页时，探测只负责四行状态，不决定退场。
      if (!widget.waitForHome) _scheduleExit(run, elapsed);
    }
    _completed++;
    if (!widget.waitForHome &&
        _completed == _sites.length &&
        _firstReadyMs == null) {
      _scheduleFailure(run);
    }
  }

  /// 首页第一页有结论：ready 才退场；failed 换成降级态。
  void _onHomeState() {
    if (!mounted || _done || _exiting || _failed) return;
    switch (HomeFirstPage.state.value) {
      case HomeFirstPageState.loading:
        return;
      case HomeFirstPageState.ready:
        _homeCapTimer?.cancel();
        _scheduleExit(_runId, _clock.elapsedMilliseconds);
        return;
      case HomeFirstPageState.failed:
        _homeCapTimer?.cancel();
        _scheduleFailure(_runId, ignoreReady: true);
        return;
    }
  }

  void _scheduleExit(int run, int readyAtMs) {
    if (_exitScheduled) return;
    _exitScheduled = true;
    final target = math.max(_minExposureMs, readyAtMs + _resultFlipMs);
    final wait = math.max(0, target - _clock.elapsedMilliseconds);
    _exitTimer = Timer(Duration(milliseconds: wait), () {
      if (!mounted || run != _runId || _failed || _exiting) return;
      setState(() => _exiting = true);
      _exit.forward(from: 0);
    });
  }

  void _scheduleFailure(int run, {bool ignoreReady = false}) {
    final wait = math.max(0, 760 - _clock.elapsedMilliseconds);
    _failTimer = Timer(Duration(milliseconds: wait), () async {
      if (!mounted || run != _runId) return;
      final offline = await (_offlineFuture ?? _looksOffline());
      if (!mounted ||
          run != _runId ||
          (!ignoreReady && _firstReadyMs != null)) {
        return;
      }
      setState(() {
        _offline = offline;
        _failed = true;
      });
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _failed) _retryFocusNode.requestFocus();
      });
    });
  }

  /// 降级文案的判据是系统网络，不是我们猜的（03 §6）。
  /// 没有 connectivity 插件，用一次短 DNS 查询近似「电视有没有连上网」。
  Future<bool> _looksOffline() async {
    try {
      final result = await InternetAddress.lookup(
        "www.baidu.com",
      ).timeout(const Duration(milliseconds: 600));
      return result.isEmpty;
    } catch (_) {
      return true;
    }
  }

  void _retry() {
    if (_done || _exiting) return;
    _retryFocusNode.unfocus();
    _blockerFocusNode.requestFocus();
    _startRun(minExposureMs: _resultFlipMs);
    // 开屏等的是首页；只重探四家而不重拉首页，退场后仍是错误页。
    if (widget.waitForHome && Get.isRegistered<HomeController>()) {
      Get.find<HomeController>().refreshData();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_done) return const SizedBox.shrink();
    final unit = 1.w;

    // 开屏期间吞掉所有按键；首页在背后自动抢了焦点也拉回来。
    if (!_failed && !_exiting && !_blockerFocusNode.hasFocus) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || _failed || _exiting || _done) return;
        if (!_blockerFocusNode.hasFocus) _blockerFocusNode.requestFocus();
      });
    }

    return DefaultTextStyle(
      // 开屏挂在 MaterialApp.builder 上，位于 Material/Theme 之外；
      // 不显式关掉，文字会继承框架的红色/黄色「error」样式。
      style: const TextStyle(decoration: TextDecoration.none),
      child: Focus(
        focusNode: _blockerFocusNode,
        autofocus: true,
        onKeyEvent: (_, __) => KeyEventResult.handled,
        child: FadeTransition(
          opacity: Tween<double>(begin: 1, end: 0).animate(
            CurvedAnimation(parent: _exit, curve: _exitCurve),
          ),
          child: ColoredBox(
            color: _ground,
            child: Stack(
              fit: StackFit.expand,
              clipBehavior: Clip.none,
              children: [
                _buildWordmark(unit),
                _buildStates(unit),
                _buildFailure(unit),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ---------------------------------------------------------------- 字标

  Widget _buildWordmark(double unit) {
    final appear = CurvedAnimation(
      parent: _enter,
      curve: const Interval(0, 260 / _entryMs, curve: _enterCurve),
    );
    return Positioned(
      left: 0,
      right: 0,
      top: (_wordmarkBaseline - _baselineRatio * 120 + 1) * unit,
      child: AnimatedBuilder(
        animation: appear,
        builder: (context, child) => Opacity(
          opacity: appear.value.clamp(0.0, 1.0),
          child: Transform.translate(
            offset: Offset(0, (1 - appear.value) * 16 * unit),
            child: child,
          ),
        ),
        child: Center(
          child: Text.rich(
            TextSpan(
              children: [
                TextSpan(text: "Live", style: _wordmarkStyle(unit)),
                TextSpan(
                  text: "Hub",
                  style: _wordmarkStyle(unit, color: _red),
                ),
              ],
            ),
            maxLines: 1,
          ),
        ),
      ),
    );
  }

  TextStyle _wordmarkStyle(double unit, {Color color = _white}) {
    return TextStyle(
      fontFamily: _latin,
      fontFamilyFallback: _cjkFallback,
      // 关掉光学尺寸（= CSS font-optical-sizing:none）：InterVariable 的 opsz 轴
      // 在 120px 会把字标换成 Display 切法，排版宽会缩 3%。
      fontVariations: const [FontVariation('opsz', 14)],
      fontSize: 120 * unit,
      fontWeight: FontWeight.w600,
      height: 1,
      letterSpacing: -2.4 * unit,
      leadingDistribution: TextLeadingDistribution.even,
      color: color,
    );
  }

  // ---------------------------------------------------------------- 状态区

  Widget _buildStates(double unit) {
    return Positioned.fill(
      child: AnimatedOpacity(
        opacity: _failed ? 0 : 1,
        duration: Duration(milliseconds: _reduced ? 16 : _resultFlipMs),
        curve: _easeCurve,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            for (var i = 0; i < _sites.length; i++)
              _buildStatusRow(_sites[i], i, unit),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusRow(Site site, int index, double unit) {
    final state = _states[site.id] ?? _SiteState.connecting;
    final baseline = _firstRowBaseline + index * _rowGap;
    final appear = CurvedAnimation(
      parent: _enter,
      curve: Interval(
        (600 + index * 60) / _entryMs,
        (780 + index * 60) / _entryMs,
        curve: _enterCurve,
      ),
    );
    return Positioned(
      left: 862 * unit,
      top: (baseline - _baselineRatio * 22) * unit,
      width: 196 * unit,
      height: 22 * unit,
      child: AnimatedBuilder(
        animation: appear,
        builder: (context, child) => Opacity(
          opacity: appear.value.clamp(0.0, 1.0),
          child: Transform.translate(
            offset: Offset(0, (1 - appear.value) * 8 * unit),
            child: child,
          ),
        ),
        child: SizedBox(
          width: 196 * unit,
          height: 22 * unit,
          child: AnimatedSwitcher(
            duration: Duration(milliseconds: _reduced ? 16 : _resultFlipMs),
            switchInCurve: _easeCurve,
            switchOutCurve: _easeCurve,
            // 单行三态交叉淡变，左对齐；宽度不做动画（04 §3.2 P4）。
            layoutBuilder: (current, previous) => Stack(
              alignment: Alignment.topLeft,
              clipBehavior: Clip.none,
              children: [
                ...previous,
                if (current != null) current,
              ],
            ),
            child: _buildFace(site.shortName, state, unit),
          ),
        ),
      ),
    );
  }

  Widget _buildFace(String name, _SiteState state, double unit) {
    final color = switch (state) {
      _SiteState.connecting => _dim,
      _SiteState.ready => _white,
      _SiteState.late => _dim2,
    };
    final word = switch (state) {
      _SiteState.connecting => "连接中",
      _SiteState.ready => "就绪",
      _SiteState.late => "超时降级",
    };
    // 超时态只缩短墨迹、不缩槽位：槽固定 16，墨迹 8 左对齐（03 §5）。
    final barWidth = state == _SiteState.late ? 8.0 : 16.0;
    return SizedBox(
      key: ValueKey<_SiteState>(state),
      width: 196 * unit,
      height: 22 * unit,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned(
            left: 0,
            top: _baselineRatio * 22 * unit - 10 * unit,
            child: Container(
              width: barWidth * unit,
              height: 4 * unit,
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(2 * unit),
              ),
            ),
          ),
          Positioned(
            left: 32 * unit,
            top: 0,
            child: Text(name, maxLines: 1, style: _metaStyle(unit, color)),
          ),
          Positioned(
            left: 108 * unit,
            top: 0,
            child: Text(word, maxLines: 1, style: _metaStyle(unit, color)),
          ),
        ],
      ),
    );
  }

  TextStyle _metaStyle(double unit, Color color) {
    return TextStyle(
      fontFamily: _latin,
      fontFamilyFallback: _cjkFallback,
      fontSize: 22 * unit,
      fontWeight: FontWeight.w400,
      height: 1,
      leadingDistribution: TextLeadingDistribution.even,
      color: color,
    );
  }

  // ---------------------------------------------------------------- 降级态

  Widget _buildFailure(double unit) {
    return Positioned.fill(
      child: AnimatedOpacity(
        opacity: _failed ? 1 : 0,
        duration: Duration(milliseconds: _reduced ? 16 : _resultFlipMs),
        curve: _easeCurve,
        child: IgnorePointer(
          ignoring: !_failed,
          child: ExcludeFocus(
            excluding: !_failed,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned(
                  left: 0,
                  right: 0,
                  top: (704 - _baselineRatio * 34) * unit,
                  child: Center(
                    child: Text(
                      _failedTitle,
                      maxLines: 1,
                      style: _failureStyle(
                        unit,
                        size: 34,
                        weight: FontWeight.w600,
                        color: _white,
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  top: (746 - _baselineRatio * 24) * unit,
                  child: Center(
                    child: Text(
                      _failedDesc,
                      maxLines: 1,
                      style: _failureStyle(
                        unit,
                        size: 24,
                        weight: FontWeight.w400,
                        color: _dim,
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  top: 776 * unit,
                  child: Center(child: _buildRetry(unit)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String get _failedTitle => _offline ? "电视没有连上网" : "四家平台都没连上";

  String get _failedDesc =>
      _offline ? "检查路由器和电视的网络设置，再试一次。" : "网络在，但四家都没有响应。稍等片刻再试一次。";

  TextStyle _failureStyle(
    double unit, {
    required double size,
    required FontWeight weight,
    required Color color,
  }) {
    return TextStyle(
      fontFamily: _latin,
      fontFamilyFallback: _cjkFallback,
      fontSize: size * unit,
      fontWeight: weight,
      height: 1,
      leadingDistribution: TextLeadingDistribution.even,
      color: color,
    );
  }

  Widget _buildRetry(double unit) {
    return HighlightWidget(
      focusNode: _retryFocusNode,
      borderRadius: BorderRadius.circular(999 * unit),
      focusScale: 1.04,
      idleLevel: 0,
      focusLevel: 0,
      onTap: _retry,
      child: Container(
        width: 132 * unit,
        height: 72 * unit,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: _white,
          borderRadius: BorderRadius.circular(999 * unit),
        ),
        child: Text(
          "重试",
          maxLines: 1,
          style: _failureStyle(
            unit,
            size: 26,
            weight: FontWeight.w500,
            color: _ground,
          ),
        ),
      ),
    );
  }
}
