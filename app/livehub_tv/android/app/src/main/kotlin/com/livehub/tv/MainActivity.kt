package com.livehub.tv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * 电视端在线更新的原生安装通道
 *
 * Dart 侧见 lib/services/update_service.dart（通道名 com.livehub.tv/update）：
 * - canInstallPackages：Android 8.0+ 是否允许安装未知来源应用
 * - openInstallSettings：跳转「安装未知应用」授权页
 * - installApk：把缓存目录里的 APK 通过 FileProvider 转成 content:// URI，交给系统安装器
 */
class MainActivity : FlutterActivity() {

    private val channelName = "com.livehub.tv/update"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "canInstallPackages" -> result.success(canInstallPackages())
                    "openInstallSettings" -> {
                        try {
                            openInstallSettings()
                            result.success(null)
                        } catch (e: Exception) {
                            result.error("SETTINGS_FAILED", e.message ?: "无法打开系统设置", null)
                        }
                    }
                    "installApk" -> {
                        val path = call.argument<String>("path")
                        if (path.isNullOrBlank()) {
                            result.error("INVALID_PATH", "安装包路径为空", null)
                        } else {
                            try {
                                installApk(path)
                                result.success(null)
                            } catch (e: Exception) {
                                result.error(
                                    "INSTALL_FAILED",
                                    e.message ?: "无法调起系统安装程序",
                                    null
                                )
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /** Android 8.0 起安装未知来源应用需要用户单独授权 */
    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** 跳转本应用的「安装未知应用」授权页 */
    private fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + packageName)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    /** FileProvider authority 与 AndroidManifest.xml 中的 android:authorities 保持一致 */
    private fun installApk(path: String) {
        val file = File(path)
        require(file.exists() && file.length() > 0) { "安装包不存在或为空" }

        val uri = FileProvider.getUriForFile(
            this,
            packageName + ".fileProvider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }
}
