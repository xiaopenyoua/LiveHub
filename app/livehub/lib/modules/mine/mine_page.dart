import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:remixicon/remixicon.dart';
import 'package:livehub/app/app_style.dart';
import 'package:livehub/app/log.dart';
import 'package:livehub/app/utils.dart';
import 'package:livehub/routes/route_path.dart';
import 'package:livehub/services/signalr_service.dart';
import 'package:livehub_ui/livehub_ui.dart';
import 'package:url_launcher/url_launcher_string.dart';

class MinePage extends StatelessWidget {
  const MinePage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final tokens = context.lh;
    final faint = tokens.palette.faint;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: Theme.of(context).brightness == Brightness.dark
          ? SystemUiOverlayStyle.light.copyWith(
              systemNavigationBarColor: Colors.transparent,
            )
          : SystemUiOverlayStyle.dark.copyWith(
              systemNavigationBarColor: Colors.transparent,
            ),
      child: SafeArea(
        child: ListView(
          padding: AppStyle.edgeInsetsA4,
          children: [
            AppStyle.vGap12,
            ListTile(
              leading: Image.asset(
                'assets/images/logo.png',
                width: 56,
                height: 56,
              ),
              title: Text(
                "LiveHub",
                style: tokens.title.copyWith(height: 1.0),
              ),
              subtitle: Text(
                "简简单单看直播",
                style: tokens.small.copyWith(color: faint),
              ),
              trailing: Icon(Icons.chevron_right, color: faint),
              onTap: () {
                Get.dialog(AboutDialog(
                  applicationIcon: Image.asset(
                    'assets/images/logo.png',
                    width: 48,
                    height: 48,
                  ),
                  applicationName: "LiveHub",
                  applicationVersion: "简简单单看直播",
                  applicationLegalese:
                      "Ver ${Utils.packageInfo.version}\n基于 dart_simple_live（GPL-3.0）二次开发",
                ));
              },
            ),
            AppStyle.divider,
            _buildCard(
              context,
              children: [
                ListTile(
                  leading: const Icon(Remix.history_line),
                  title: const Text("观看记录"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kHistory);
                  },
                ),
              ],
            ),
            AppStyle.divider,
            ListTile(
              leading: const Icon(Remix.account_circle_line),
              title: const Text("账号管理"),
              trailing: Icon(
                Icons.chevron_right,
                color: faint,
              ),
              onTap: () {
                Get.toNamed(RoutePath.kSettingsAccount);
              },
            ),
            AppStyle.divider,
            ListTile(
              leading: const Icon(Icons.devices),
              title: const Text("数据同步"),
              trailing: Icon(
                Icons.chevron_right,
                color: faint,
              ),
              onTap: () {
                Get.toNamed(RoutePath.kSync);
              },
            ),
            AppStyle.divider,
            ListTile(
              leading: const Icon(Remix.link),
              title: const Text("链接解析"),
              trailing: Icon(
                Icons.chevron_right,
                color: faint,
              ),
              onTap: () {
                Get.toNamed(RoutePath.kTools);
              },
            ),
            AppStyle.divider,
            _buildCard(
              context,
              children: [
                ListTile(
                  leading: const Icon(Remix.moon_line),
                  title: const Text("外观设置"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kAppstyleSetting);
                  },
                ),
                ListTile(
                  leading: const Icon(Remix.home_2_line),
                  title: const Text("主页设置"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kSettingsIndexed);
                  },
                ),
                ListTile(
                  leading: const Icon(Remix.play_circle_line),
                  title: const Text("直播设置"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kSettingsPlay);
                  },
                ),
                ListTile(
                  leading: const Icon(Remix.text),
                  title: const Text("弹幕设置"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kSettingsDanmu);
                  },
                ),
                ListTile(
                  leading: const Icon(Remix.heart_line),
                  title: const Text("关注设置"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kSettingsFollow);
                  },
                ),
                ListTile(
                  leading: const Icon(Remix.timer_2_line),
                  title: const Text("定时关闭"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kSettingsAutoExit);
                  },
                ),
                ListTile(
                  leading: const Icon(Remix.apps_line),
                  title: const Text("其他设置"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    Get.toNamed(RoutePath.kSettingsOther);
                  },
                ),
                if (kDebugMode)
                  ListTile(
                    leading: const Icon(Remix.apps_line),
                    title: const Text("测试"),
                    trailing: Icon(
                      Icons.chevron_right,
                      color: faint,
                    ),
                    onTap: () async {
                      SignalRService signalRService = SignalRService();
                      await signalRService.connect();
                      //Get.toNamed(RoutePath.kTest);
                      var room = await signalRService.createRoom();
                      Log.logPrint(room);
                    },
                  ),
              ],
            ),
            AppStyle.divider,
            _buildCard(
              context,
              children: [
                ListTile(
                  leading: const Icon(Remix.error_warning_line),
                  title: const Text("免责声明"),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: Utils.showStatement,
                ),
                ListTile(
                  leading: const Icon(Remix.github_line),
                  title: const Text("开源主页"),
                  subtitle: Text(
                    "基于 dart_simple_live（GPL-3.0）二次开发",
                    style: tokens.small.copyWith(color: faint),
                  ),
                  trailing: Icon(
                    Icons.chevron_right,
                    color: faint,
                  ),
                  onTap: () {
                    launchUrlString(
                      "https://github.com/xiaopenyoua/LiveHub",
                      mode: LaunchMode.externalApplication,
                    );
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCard(BuildContext context, {required List<Widget> children}) {
    final tokens = context.lh;
    return Theme(
      data: Theme.of(context).copyWith(
        listTileTheme: ListTileThemeData(
          shape: RoundedRectangleBorder(borderRadius: tokens.listRadius),
        ),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: children,
      ),
    );
  }
}
