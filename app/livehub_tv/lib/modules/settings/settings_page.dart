import 'package:flutter/material.dart';
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/app/controller/app_settings_controller.dart';
import 'package:livehub_tv/app/utils.dart';
import 'package:livehub_tv/modules/settings/settings_controller.dart';
import 'package:livehub_tv/routes/app_navigation.dart';
import 'package:livehub_tv/routes/tv_tab.dart';
import 'package:livehub_tv/services/bilibili_account_service.dart';
import 'package:livehub_tv/services/follow_user_service.dart';
import 'package:livehub_tv/widgets/settings/settings_group_tabs.dart';
import 'package:livehub_tv/widgets/settings/settings_row.dart';
import 'package:livehub_tv/widgets/settings/settings_rows.dart';
import 'package:livehub_tv/widgets/settings/settings_status_card.dart';
import 'package:livehub_tv/widgets/tv_page_scaffold.dart';
import 'package:livehub_ui/livehub_ui.dart';

/// 设置页：左栏只读状态卡，右栏分组条 + 可滚动的设置条目。
///
/// 左栏一个可聚焦元素都没有；默认焦点落在第一条设置行上（设计规范 §8.1）。
class SettingsPage extends GetView<SettingsController> {
  const SettingsPage({super.key});

  static const _groupLabels = ["播放", "弹幕", "关注", "账号与同步", "关于"];

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return TvPageScaffold(
      tab: TvTab.settings,
      child: Obx(() {
        final group = controller.groupIndex.value;
        return Padding(
          padding: EdgeInsets.fromLTRB(
            80 * t.unit,
            36 * t.unit,
            80 * t.unit,
            36 * t.unit,
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 496 * t.unit,
                child: _buildStatusCard(group),
              ),
              SizedBox(width: 56 * t.unit),
              Expanded(child: _buildRightPane(context, group)),
            ],
          ),
        );
      }),
    );
  }

  Widget _buildStatusCard(int group) {
    final status = _statusFor(group);
    return SettingsStatusCard(
      kicker: status.kicker,
      title: status.title,
      subtitle: status.subtitle,
      items: status.items,
      note: status.note,
    );
  }

  Widget _buildRightPane(BuildContext context, int group) {
    final t = context.lh;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroupTabs(
          labels: _groupLabels,
          focusNodes: controller.groupTabFocusNodes,
          selectedIndex: group,
          onSelect: controller.selectGroup,
        ),
        SizedBox(height: 24 * t.unit),
        Expanded(child: _buildList(context, group)),
      ],
    );
  }

  Widget _buildList(BuildContext context, int group) {
    final t = context.lh;
    final rows = _rowsFor(context, group);
    // 设计稿 .spane 的负 margin + 等量 padding：把裁剪边界退到条目边缘外 14px，
    // 行内容仍与分组条左缘对齐，焦点行放大 1.02 的外扩量也不会被切掉（§8.2）。
    return LayoutBuilder(
      builder: (context, constraints) {
        final bleed = 14 * t.unit;
        return OverflowBox(
          minWidth: constraints.maxWidth + bleed * 2,
          maxWidth: constraints.maxWidth + bleed * 2,
          alignment: Alignment.center,
          child: ListView.separated(
            key: ValueKey(group),
            padding: EdgeInsets.fromLTRB(
              bleed,
              6 * t.unit,
              bleed,
              26 * t.unit,
            ),
            itemCount: rows.length,
            separatorBuilder: (_, __) => SizedBox(height: 8 * t.unit),
            itemBuilder: (_, index) => rows[index],
          ),
        );
      },
    );
  }

  List<Widget> _rowsFor(BuildContext context, int group) {
    switch (group) {
      case 1:
        return _danmakuRows(context);
      case 2:
        return _followRows(context);
      case 3:
        return _accountRows(context);
      case 4:
        return _aboutRows(context);
      default:
        return _playerRows(context);
    }
  }

  // ---------------------------------------------------------------- 播放

  List<Widget> _playerRows(BuildContext context) {
    final settings = AppSettingsController.instance;
    return [
      _switchRow(
        context,
        focusNode: controller.hardwareDecodeFocusNode,
        title: "硬件解码",
        desc: "硬件解码更省电，遇到花屏再切软件",
        value: settings.hardwareDecode.value,
        onChanged: settings.setHardwareDecode,
        autofocus: true,
      ),
      _choiceRow(
        context,
        focusNode: controller.defaultQualityFocusNode,
        title: "默认清晰度",
        desc: "网络不稳时自动降一档",
        items: const {
          0: "最低画质",
          1: "中等画质",
          2: "最高画质",
        },
        value: settings.qualityLevel.value,
        onChanged: (value) => settings.setQualityLevel(value as int),
      ),
      _choiceRow(
        context,
        focusNode: controller.scaleFoucsNode,
        title: "画面比例",
        desc: "适应 / 拉伸 / 铺满 / 16:9 / 4:3",
        items: const {
          0: "适应",
          1: "拉伸",
          2: "铺满",
          3: "16:9",
          4: "4:3",
        },
        value: settings.scaleMode.value,
        onChanged: (value) => settings.setScaleMode(value as int),
      ),
      _switchRow(
        context,
        focusNode: controller.compatibleModeFocusNode,
        title: "兼容模式",
        desc: "部分设备取流异常时打开",
        value: settings.playerCompatMode.value,
        onChanged: settings.setPlayerCompatMode,
      ),
    ];
  }

  // ---------------------------------------------------------------- 弹幕

  List<Widget> _danmakuRows(BuildContext context) {
    final settings = AppSettingsController.instance;
    return [
      _switchRow(
        context,
        focusNode: controller.danmakuFoucsNode,
        title: "弹幕开关",
        desc: "进入直播间自动显示弹幕",
        value: settings.danmuEnable.value,
        onChanged: settings.setDanmuEnable,
        autofocus: true,
      ),
      _choiceRow(
        context,
        focusNode: controller.danmakuSizeFoucsNode,
        title: "弹幕大小",
        desc: "大屏建议 40 以上",
        items: {
          24.0: "24",
          32.0: "32",
          40.0: "40",
          48.0: "48",
          56.0: "56",
          64.0: "64",
          72.0: "72",
        },
        value: settings.danmuSize.value,
        onChanged: (value) => settings.setDanmuSize(value as double),
      ),
      _choiceRow(
        context,
        focusNode: controller.danmakuSpeedFoucsNode,
        title: "弹幕速度",
        desc: "越大越慢",
        items: {
          18.0: "很慢",
          14.0: "较慢",
          12.0: "慢",
          10.0: "正常",
          8.0: "快",
          6.0: "较快",
          4.0: "很快",
        },
        value: settings.danmuSpeed.value,
        onChanged: (value) => settings.setDanmuSpeed(value as double),
      ),
      _choiceRow(
        context,
        focusNode: controller.danmakuAreaFoucsNode,
        title: "显示区域",
        desc: "只铺上半屏，字幕和比分不会被挡",
        items: {
          0.25: "1/4",
          0.5: "1/2",
          0.75: "3/4",
          1.0: "全屏",
        },
        value: settings.danmuArea.value,
        onChanged: (value) => settings.setDanmuArea(value as double),
      ),
      _choiceRow(
        context,
        focusNode: controller.danmakuOpacityFoucsNode,
        title: "不透明度",
        desc: "压低一点，别盖住画面细节",
        items: {
          0.1: "10%",
          0.2: "20%",
          0.3: "30%",
          0.4: "40%",
          0.5: "50%",
          0.6: "60%",
          0.7: "70%",
          0.8: "80%",
          0.9: "90%",
          1.0: "100%",
        },
        value: settings.danmuOpacity.value,
        onChanged: (value) => settings.setDanmuOpacity(value as double),
      ),
      _choiceRow(
        context,
        focusNode: controller.danmakuStorkeFoucsNode,
        title: "描边宽度",
        desc: "字压在亮画面上时保住可读性",
        items: {
          2.0: "2",
          4.0: "4",
          6.0: "6",
          8.0: "8",
          10.0: "10",
          12.0: "12",
          14.0: "14",
          16.0: "16",
        },
        value: settings.danmuStrokeWidth.value,
        onChanged: (value) => settings.setDanmuStrokeWidth(value as double),
      ),
      _choiceRow(
        context,
        focusNode: controller.danmakuRateFoucsNode,
        title: "弹幕密度",
        desc: "每秒最多上屏几条；超出的在排版前就丢掉，不掉帧",
        items: {
          8: "8",
          12: "12",
          20: "20",
          30: "30",
          45: "45",
          60: "60",
        },
        value: settings.danmuMaxRate.value,
        onChanged: (value) => settings.setDanmuMaxRate(value as int),
      ),
    ];
  }

  // ---------------------------------------------------------------- 关注

  List<Widget> _followRows(BuildContext context) {
    final settings = AppSettingsController.instance;
    return [
      _switchRow(
        context,
        focusNode: controller.autoUpdateFollowEnableFocusNode,
        title: "自动更新关注",
        desc: "关注的主播开播时自动刷新状态",
        value: settings.autoUpdateFollowEnable.value,
        onChanged: (value) {
          settings.setAutoUpdateFollowEnable(value);
          FollowUserService.instance.initTimer();
        },
        autofocus: true,
      ),
      _choiceRow(
        context,
        focusNode: controller.autoUpdateFollowDurationFocusNode,
        title: "自动更新间隔",
        desc: "越勤越准，流量也越多",
        items: const {
          5: "5分钟",
          10: "10分钟",
          15: "15分钟",
          20: "20分钟",
          25: "25分钟",
          30: "30分钟",
          60: "1小时",
        },
        value: settings.autoUpdateFollowDuration.value,
        onChanged: (value) {
          settings.setAutoUpdateFollowDuration(value as int);
          FollowUserService.instance.initTimer();
        },
      ),
      _choiceRow(
        context,
        focusNode: controller.updateFollowThreadFocusNode,
        title: "更新线程数",
        desc: "同时更新的主播线程数",
        items: const {
          1: "1",
          2: "2",
          3: "3",
          4: "4",
          6: "6",
          8: "8",
          10: "10",
          12: "12",
        },
        value: settings.updateFollowThreadCount.value,
        onChanged: (value) => settings.setUpdateFollowThreadCount(value as int),
      ),
    ];
  }

  // ---------------------------------------------------------------- 账号与同步

  List<Widget> _accountRows(BuildContext context) {
    final t = context.lh;
    final account = BiliBiliAccountService.instance;
    return [
      _actionRow(
        context,
        focusNode: controller.syncFocusNode,
        title: "数据同步",
        desc: "在手机与电视之间同步关注、记录与账号",
        leading: SizedBox(
          width: 96 * t.unit,
          child: Align(
            alignment: Alignment.centerLeft,
            child: Icon(Icons.devices, size: 40 * t.unit, color: t.muted),
          ),
        ),
        onTap: AppNavigator.toSync,
        autofocus: true,
      ),
      _actionRow(
        context,
        focusNode: controller.bilibiliFoucsNode,
        title: "哔哩哔哩账号",
        desc: account.logined.value ? "已登录：${account.name.value}" : "未登录，点击登录",
        leading: _platformLabel(context, "哔哩"),
        onTap: controller.bilibiliTap,
      ),
      _actionRow(
        context,
        focusNode: controller.douyuAccountFocusNode,
        title: "斗鱼账号",
        desc: "无需登录",
        leading: _platformLabel(context, "斗鱼"),
        onTap: () {
          SmartDialog.showToast("无需登录斗鱼，您可以直接观看直播");
        },
      ),
      _actionRow(
        context,
        focusNode: controller.huyaAccountFocusNode,
        title: "虎牙账号",
        desc: "无需登录",
        leading: _platformLabel(context, "虎牙"),
        onTap: () {
          SmartDialog.showToast("无需登录虎牙，您可以直接观看直播");
        },
      ),
      _actionRow(
        context,
        focusNode: controller.douyinAccountFocusNode,
        title: "抖音账号",
        desc: "无需登录",
        leading: _platformLabel(context, "抖音"),
        onTap: () {
          SmartDialog.showToast("无需登录抖音，您可以直接观看直播");
        },
      ),
    ];
  }

  // ---------------------------------------------------------------- 关于

  List<Widget> _aboutRows(BuildContext context) {
    return [
      SettingsRow(
        focusNode: controller.versionFocusNode,
        title: "版本",
        desc: "v${Utils.packageInfo.version}",
        autofocus: true,
      ),
    ];
  }

  // ---------------------------------------------------------------- 行控件

  Widget _switchRow(
    BuildContext context, {
    required AppFocusNode focusNode,
    required String title,
    required String desc,
    required bool value,
    required ValueChanged<bool> onChanged,
    bool autofocus = false,
  }) {
    return SettingsSwitchRow(
      focusNode: focusNode,
      title: title,
      desc: desc,
      value: value,
      onChanged: onChanged,
      autofocus: autofocus,
    );
  }

  /// 选项较少时用行内分段控件，选项较多时用“当前值 + 左右箭头”。
  /// 两条路径都只在行内完成，不打开模态弹层。
  Widget _choiceRow(
    BuildContext context, {
    required AppFocusNode focusNode,
    required String title,
    required String desc,
    required Map<dynamic, String> items,
    required dynamic value,
    required ValueChanged<dynamic> onChanged,
    bool autofocus = false,
  }) {
    return SettingsChoiceRow(
      focusNode: focusNode,
      title: title,
      desc: desc,
      items: items,
      value: value,
      onChanged: onChanged,
      autofocus: autofocus,
    );
  }

  Widget _actionRow(
    BuildContext context, {
    required AppFocusNode focusNode,
    required String title,
    required String desc,
    Widget? leading,
    required VoidCallback onTap,
    bool autofocus = false,
  }) {
    return SettingsRow(
      focusNode: focusNode,
      title: title,
      desc: desc,
      leading: leading,
      chevron: true,
      autofocus: autofocus,
      onTap: onTap,
    );
  }

  Widget _platformLabel(BuildContext context, String label) {
    final t = context.lh;
    return SizedBox(
      width: 96 * t.unit,
      child: Text(
        label,
        style: t.body.copyWith(
          color: t.muted,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }

  // ---------------------------------------------------------------- 左栏文案

  _SettingsStatus _statusFor(int group) {
    final settings = AppSettingsController.instance;
    switch (group) {
      case 1:
        return _danmakuStatus(settings);
      case 2:
        return _followStatus(settings);
      case 3:
        return _accountStatus();
      case 4:
        return _aboutStatus();
      default:
        return _playerStatus(settings);
    }
  }

  _SettingsStatus _playerStatus(AppSettingsController settings) {
    final hardware = settings.hardwareDecode.value;
    final quality = _qualityLabel(settings.qualityLevel.value);
    final compat = settings.playerCompatMode.value;
    return _SettingsStatus(
      kicker: "当前播放",
      title: "$quality · ${hardware ? "硬件解码" : "软件解码"}",
      subtitle:
          "兼容模式${compat ? "开" : "关"} · 缓冲 ${settings.playerBufferSize.value}",
      items: [
        SettingsStatusItem("默认清晰度", quality),
        SettingsStatusItem("硬件解码", hardware ? "开" : "关"),
        SettingsStatusItem("画面比例", _scaleLabel(settings.scaleMode.value)),
        SettingsStatusItem("兼容模式", compat ? "开" : "关"),
      ],
      note: "这些设置只影响本机播放，不改变账号与关注内容。",
    );
  }

  _SettingsStatus _danmakuStatus(AppSettingsController settings) {
    final enabled = settings.danmuEnable.value;
    return _SettingsStatus(
      kicker: "当前弹幕",
      title:
          "字号 ${settings.danmuSize.value.toInt()} · ${_areaLabel(settings.danmuArea.value)}",
      subtitle:
          "弹幕${enabled ? "开" : "关"} · 速度 ${_speedLabel(settings.danmuSpeed.value)}",
      items: [
        SettingsStatusItem("弹幕开关", enabled ? "开" : "关"),
        SettingsStatusItem("字号", settings.danmuSize.value.toInt().toString()),
        SettingsStatusItem("显示区域", _areaLabel(settings.danmuArea.value)),
        SettingsStatusItem(
          "不透明度",
          "${(settings.danmuOpacity.value * 100).round()}%",
        ),
      ],
      note: "字号与不透明度只影响本机渲染，不发送到直播间。",
    );
  }

  _SettingsStatus _followStatus(AppSettingsController settings) {
    final service = FollowUserService.instance;
    final enabled = settings.autoUpdateFollowEnable.value;
    return _SettingsStatus(
      kicker: "当前关注",
      title: "${service.list.length} 位关注主播",
      subtitle:
          "自动更新 ${enabled ? "开" : "关"} · 每 ${settings.autoUpdateFollowDuration.value} 分钟",
      items: [
        SettingsStatusItem("自动更新", enabled ? "开" : "关"),
        SettingsStatusItem(
          "间隔",
          "${settings.autoUpdateFollowDuration.value} 分钟",
        ),
        SettingsStatusItem(
          "线程数",
          settings.updateFollowThreadCount.value.toString(),
        ),
      ],
      note: "当前 ${service.livingList.length} 位正在直播。",
    );
  }

  _SettingsStatus _accountStatus() {
    final account = BiliBiliAccountService.instance;
    final logged = account.logined.value;
    final name = logged ? account.name.value : "未登录";
    return _SettingsStatus(
      kicker: "当前账号",
      title: "哔哩${logged ? "已登录" : "未登录"}",
      subtitle: "数据同步与账号管理",
      items: [
        SettingsStatusItem("哔哩账号", name),
        const SettingsStatusItem("数据同步", "扫码连接"),
        const SettingsStatusItem("平台账号", "4 个平台"),
      ],
      note: "换电视后扫码登录即可恢复关注与观看记录。",
    );
  }

  _SettingsStatus _aboutStatus() {
    final version = Utils.packageInfo.version;
    return _SettingsStatus(
      kicker: "关于",
      title: "LiveHub v$version",
      subtitle: "仅观看各平台公开直播内容",
      items: [
        SettingsStatusItem("版本", "v$version"),
        const SettingsStatusItem("开源许可", "GPL-3.0"),
      ],
      note: "仅观看各平台公开直播内容，版权归原作者。",
    );
  }

  String _qualityLabel(int value) {
    switch (value) {
      case 0:
        return "最低画质";
      case 2:
        return "最高画质";
      default:
        return "中等画质";
    }
  }

  String _scaleLabel(int value) {
    switch (value) {
      case 0:
        return "适应";
      case 1:
        return "拉伸";
      case 2:
        return "铺满";
      case 3:
        return "16:9";
      case 4:
        return "4:3";
      default:
        return "适应";
    }
  }

  String _areaLabel(double value) {
    if (value <= 0.25) return "1/4";
    if (value <= 0.5) return "1/2";
    if (value <= 0.75) return "3/4";
    return "全屏";
  }

  String _speedLabel(double value) {
    final labels = {
      18.0: "很慢",
      14.0: "较慢",
      12.0: "慢",
      10.0: "正常",
      8.0: "快",
      6.0: "较快",
      4.0: "很快",
    };
    return labels[value] ?? "正常";
  }
}

class _SettingsStatus {
  const _SettingsStatus({
    required this.kicker,
    required this.title,
    required this.subtitle,
    required this.items,
    required this.note,
  });

  final String kicker;
  final String title;
  final String subtitle;
  final List<SettingsStatusItem> items;
  final String note;
}
