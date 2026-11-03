import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:livehub_tv/app/app_focus_node.dart';
import 'package:livehub_tv/modules/account/bilibili/qr_login_controller.dart';
import 'package:livehub_tv/widgets/app_scaffold.dart';
import 'package:livehub_tv/widgets/button/highlight_button.dart';
import 'package:livehub_ui/livehub_ui.dart';

class BiliBiliQRLoginPage extends GetView<BiliBiliQRLoginController> {
  const BiliBiliQRLoginPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final t = context.lh;
    return AppScaffold(
      child: Column(
        children: [
          SizedBox(height: 32 * t.unit),
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
                "登录哔哩哔哩",
                style: t.h2,
              ),
              SizedBox(width: 24 * t.unit),
              const Spacer(),
            ],
          ),
          SizedBox(height: 48 * t.unit),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Center(
                  child: Obx(
                    () {
                      if (controller.qrStatus.value == QRStatus.loading) {
                        return SizedBox(
                          width: 64 * t.unit,
                          height: 64 * t.unit,
                          child: CircularProgressIndicator(
                            strokeWidth: 8 * t.unit,
                            color: t.text,
                          ),
                        );
                      }
                      if (controller.qrStatus.value == QRStatus.failed) {
                        return Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              "二维码加载失败",
                              style: t.body.copyWith(color: t.muted),
                            ),
                            HighlightButton(
                              focusNode: AppFocusNode(),
                              iconData: Icons.refresh,
                              text: "重试",
                              onTap: controller.loadQRCode,
                            ),
                          ],
                        );
                      }
                      if (controller.qrStatus.value == QRStatus.failed) {
                        return Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              "二维码已失效",
                              style: t.body.copyWith(color: t.muted),
                            ),
                            HighlightButton(
                              focusNode: AppFocusNode(),
                              iconData: Icons.refresh,
                              text: "刷新",
                              onTap: controller.loadQRCode,
                            ),
                          ],
                        );
                      }
                      return Column(
                        children: [
                          ClipRRect(
                            borderRadius: t.listRadius,
                            child: QrImageView(
                              data: controller.qrcodeUrl.value,
                              version: QrVersions.auto,
                              backgroundColor: Colors.white,
                              size: 360 * t.unit,
                              padding: EdgeInsets.all(12 * t.unit),
                            ),
                          ),
                          SizedBox(height: 8 * t.unit),
                          Visibility(
                            visible:
                                controller.qrStatus.value == QRStatus.scanned,
                            child: Text(
                              "已扫描，请在手机上确认登录",
                              style: t.body.copyWith(color: t.muted),
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                ),
                Padding(
                  padding: EdgeInsets.all(24 * t.unit),
                  child: Text(
                    "请使用哔哩哔哩手机客户端扫描二维码登录\n必须登录后才能观看哔哩哔哩直播",
                    textAlign: TextAlign.center,
                    style: t.small,
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
