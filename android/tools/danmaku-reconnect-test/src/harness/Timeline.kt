// 客户端事件与服务端动作共用一条时间线——输出就是一份能按时间读的流水。
package harness

import java.util.Collections

object Timeline {
    @Volatile
    var t0: Long = System.currentTimeMillis()
    private val items = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())

    fun mark(who: String, what: String) {
        items.add((System.currentTimeMillis() - t0) to "$who $what")
    }

    fun reset() {
        items.clear()
        t0 = System.currentTimeMillis()
    }

    fun dump(title: String) {
        println()
        println("=".repeat(86))
        println(title)
        println("-".repeat(86))
        synchronized(items) { items.toList() }.sortedBy { it.first }
            .forEach { (t, s) -> println(String.format("t=%7dms  %s", t, s)) }
        println("=".repeat(86))
    }
}
