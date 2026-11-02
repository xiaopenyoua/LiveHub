package com.livehub.tv

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.livehub.tv.core.store.Prefs
import com.livehub.tv.ui.AppRoot
import com.livehub.tv.ui.theme.LiveHubTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        // 调试入口（仅 debug 包）：`adb shell am start -n com.livehub.tv/.MainActivity
        //   --es debug_room bilibili:545068 --es debug_quality 250`
        // 用途是跨平台/跨房间/跨清晰度的测试矩阵——自绘键盘只有 0-9 A-Z，
        // 每进一个房间都靠遥控器敲一遍会让回归成本高到没人愿意跑。
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debugRoom = if (debuggable) intent?.getStringExtra("debug_room") else null
        val debugQuality = if (debuggable) intent?.getStringExtra("debug_quality") else null
        val debugScreen = if (debuggable) intent?.getStringExtra("debug_screen") else null
        if (!debugQuality.isNullOrBlank()) Prefs.preferredQuality = debugQuality
        Log.i("LiveHubDebug", "debuggable=$debuggable room=$debugRoom quality=$debugQuality screen=$debugScreen")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            LiveHubTheme {
                AppRoot(debugRoom = debugRoom, debugScreen = debugScreen)
            }
        }
    }
}
