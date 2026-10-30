// 夹具桩：桌面 JVM 上没有真正的 android.util.Log，把日志按原格式转发到 stdout。
// 只为让真实的 core/danmaku 源码能在 JVM 上编译运行，不参与任何断言。
// 注意 package 就是 android.util，编译时它顶替 android.jar 里的同名类。
package android.util

object Log {
    @JvmStatic fun v(tag: String, msg: String): Int { println("  [V/$tag] $msg"); return 0 }
    @JvmStatic fun d(tag: String, msg: String): Int { println("  [D/$tag] $msg"); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int { println("  [I/$tag] $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { println("  [W/$tag] $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int {
        println("  [W/$tag] $msg ${tr.message}"); return 0
    }
    @JvmStatic fun e(tag: String, msg: String): Int { println("  [E/$tag] $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int {
        println("  [E/$tag] $msg ${tr.message}"); return 0
    }
}
