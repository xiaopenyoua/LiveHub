import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/modules/sync/sync_controller.dart';
import 'package:livehub_tv/services/signalr_service.dart';
import 'package:livehub_tv/services/sync_service.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_ui/livehub_ui.dart';

class SyncPage extends GetView<SyncController> {
  const SyncPage({super.key});

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return AppScaffold(
      child: Column(
        children: [
          SizedBox(height: 24 * t.unit),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SizedBox(width: 48 * t.unit),
              HighlightButton(
                focusNode: AppFocusNode(),
                iconData: Icons.arrow_back,
                text: "返回",
                autofocus: true,
                onTap: () {
                  Get.back();
                },
              ),
              SizedBox(width: 32 * t.unit),
              Text(
                "数据同步",
                style: t.h2,
              ),
              const Spacer(),
            ],
          ),
          SizedBox(height: 24 * t.unit),
          Expanded(
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        "远程同步",
                        style: t.title,
                      ),
                      SizedBox(height: 16 * t.unit),
                      Obx(
                        () => Visibility(
                          visible: SyncService.instance.httpRunning.value,
                          child: GestureDetector(
                            onTap: () {
                              Get.back();
                            },
                            child: QrImageView(
                              data: controller.currentRoomId.value,
                              version: QrVersions.auto,
                              backgroundColor: Colors.white,
                              padding: EdgeInsets.all(24 * t.unit),
                              size: 420.0 * t.unit,
                            ),
                          ),
                        ),
                      ),
                      SizedBox(height: 24 * t.unit),
                      Obx(
                        () => Visibility(
                          visible: controller.state.value ==
                              SignalRConnectionState.connected,
                          child: Text.rich(
                            TextSpan(
                              text: '房间号：',
                              children: [
                                TextSpan(
                                  text: controller.currentRoomId.value,
                                  style: t.body.copyWith(
                                    fontWeight: FontWeight.bold,
                                  ),
                                )
                              ],
                            ),
                            style: t.body,
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                      Obx(
                        () => Visibility(
                          visible: controller.state.value ==
                              SignalRConnectionState.disconnected,
                          child: Text(
                            '连接已断开，请尝试重进此页面',
                            style: t.body.copyWith(color: t.muted),
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                      Obx(
                        () => Visibility(
                          visible: controller.state.value ==
                              SignalRConnectionState.connecting,
                          child: Text(
                            '正在创建房间...',
                            style: t.body.copyWith(color: t.muted),
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                      SizedBox(height: 12 * t.unit),
                      Obx(
                        () => Visibility(
                          visible: controller.state.value ==
                              SignalRConnectionState.connected,
                          child: Text(
                            "${controller.countDown}秒后将自动关闭服务\n请扫描二维码或输入房间号进行连接",
                            style: t.small,
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                VerticalDivider(
                  color: Colors.white.withValues(alpha: 0.10),
                  thickness: 2 * t.unit,
                  endIndent: 120 * t.unit,
                  indent: 120 * t.unit,
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        "局域网同步",
                        style: t.title,
                      ),
                      SizedBox(height: 16 * t.unit),
                      Obx(
                        () => Visibility(
                          visible: SyncService.instance.httpRunning.value,
                          child: GestureDetector(
                            onTap: () {
                              Get.back();
                            },
                            child: QrImageView(
                              data: SyncService.instance.ipAddress.value,
                              version: QrVersions.auto,
                              backgroundColor: Colors.white,
                              padding: EdgeInsets.all(24 * t.unit),
                              size: 420.0 * t.unit,
                            ),
                          ),
                        ),
                      ),
                      SizedBox(height: 24 * t.unit),
                      Obx(
                        () => Visibility(
                          visible: SyncService.instance.httpRunning.value,
                          child: Text(
                            '服务已启动：${SyncService.instance.ipAddress.value.split(';').map((e) => '$e:${SyncService.httpPort}').join('；')}',
                            style: t.small,
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                      Obx(
                        () => Visibility(
                          visible: !SyncService.instance.httpRunning.value,
                          child: Text(
                            'HTTP服务未启动：${SyncService.instance.httpErrorMsg}，请尝试重启应用',
                            style: t.small,
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                      SizedBox(height: 12 * t.unit),
                      Obx(
                        () => Visibility(
                          visible: SyncService.instance.httpRunning.value,
                          child: Text(
                            "请扫描二维码或输入IP地址进行连接\n建立连接后可在APP端选择需要同步至TV端的数据",
                            style: t.small,
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
