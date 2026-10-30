package com.livehub.tv.core.danmaku.tars

/**
 * Tars（腾讯 JCE）最小编解码器，只为虎牙弹幕协议服务：读写 int / long / string /
 * bytes / list<string> / struct 够用。Map、Float、Double 虎牙不用，故意不实现，
 * 遇到就抛异常让调用方跳过这条消息，而不是猜。
 *
 * ## 字段头
 * 一个字节 `(tag shl 4) or type`；tag >= 15 时先写 `(15 shl 4) or type`，
 * 再补一个字节的 tag。type 取值见 [TarsType]。
 *
 * ## 两个踩过的坑（改动前务必读）
 *
 * **1. SIMPLE_LIST（byte 数组）的长度是「带类型的定长整数」，不是裸的 4 字节。**
 * 长度本身是一个字段：长度 48 写成 `0x00 0x30`（BYTE 头 + 值），长度 0 写成
 * ZERO_TAG 头（只有头、没有值）。若按教科书 JCE 写裸 4 字节长度，虎牙服务器会
 * 把这个数组读成**空数组**：命令 16 照样收到回执（cmd 17），但注册组是空的，
 * 于是一条弹幕都收不到，日志里毫无线索。这是拿真实服务器逐字节验证出来的。
 *
 * **2. 读必须严格按位置推进。**
 * 早先写过「向前扫描找 tag」的读取器，它会扫进兄弟 struct 里的同名 tag，之后
 * 全部错位。这里改成先给一个 struct 建 tag -> 字段 的索引，再按 tag 取，
 * 和 Tars 生成代码的做法一致。
 */
class TarsException(message: String) : Exception(message)

object TarsType {
    const val BYTE = 0
    const val SHORT = 1
    const val INT = 2
    const val LONG = 3
    const val FLOAT = 4
    const val DOUBLE = 5
    const val STRING1 = 6
    const val STRING4 = 7
    const val MAP = 8
    const val LIST = 9
    const val STRUCT_BEGIN = 10
    const val STRUCT_END = 11
    const val ZERO_TAG = 12
    const val SIMPLE_LIST = 13
}

/** 写。虎牙要的东西很少，直接照协议拼，不做通用序列化。 */
class TarsWriter(capacity: Int = 128) {

    private var buf = ByteArray(capacity)
    private var size = 0

    fun int(tag: Int, value: Long): TarsWriter {
        when {
            // 0 用 ZERO_TAG：只写头不写值，Tars 的默认压缩约定
            value == 0L -> head(TarsType.ZERO_TAG, tag)
            value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE -> {
                head(TarsType.BYTE, tag)
                put(value.toInt())
            }
            value >= Short.MIN_VALUE && value <= Short.MAX_VALUE -> {
                head(TarsType.SHORT, tag)
                put((value.toInt() shr 8))
                put(value.toInt())
            }
            value >= Int.MIN_VALUE && value <= Int.MAX_VALUE -> {
                head(TarsType.INT, tag)
                putInt(value.toInt())
            }
            else -> {
                head(TarsType.LONG, tag)
                putLong(value)
            }
        }
        return this
    }

    /**
     * 字符串固定用 STRING4（4 字节长度）。不用 STRING1：
     * 虎牙服务器的组名解析只认 4 字节长度那一版，发 STRING1 它会把组列表读成空。
     */
    fun string(tag: Int, value: String): TarsWriter {
        val raw = value.toByteArray(Charsets.UTF_8)
        head(TarsType.STRING4, tag)
        putInt(raw.size)
        putBytes(raw)
        return this
    }

    /** byte 数组。见类注释坑 1：长度要用 [int] 写成带类型的字段。 */
    fun bytes(tag: Int, value: ByteArray): TarsWriter {
        head(TarsType.SIMPLE_LIST, tag)
        head(TarsType.BYTE, 0)          // 元素类型头，固定 BYTE
        int(0, value.size.toLong())     // 长度本身也是个字段
        putBytes(value)
        return this
    }

    fun stringList(tag: Int, values: List<String>): TarsWriter {
        head(TarsType.LIST, tag)
        if (values.isEmpty()) return this
        int(0, values.size.toLong())    // 元素个数也是字段
        values.forEach { string(0, it) }
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun head(type: Int, tag: Int) {
        if (tag < 15) {
            put((tag shl 4) or type)
        } else {
            put((15 shl 4) or type)
            put(tag and 0xFF)
        }
    }

    private fun ensure(n: Int) {
        if (size + n <= buf.size) return
        var cap = buf.size
        while (cap < size + n) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    private fun put(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    private fun putBytes(b: ByteArray) {
        ensure(b.size)
        System.arraycopy(b, 0, buf, size, b.size)
        size += b.size
    }

    private fun putInt(v: Int) {
        put((v ushr 24) and 0xFF)
        put((v ushr 16) and 0xFF)
        put((v ushr 8) and 0xFF)
        put(v and 0xFF)
    }

    private fun putLong(v: Long) {
        put((v ushr 56).toInt() and 0xFF)
        put((v ushr 48).toInt() and 0xFF)
        put((v ushr 40).toInt() and 0xFF)
        put((v ushr 32).toInt() and 0xFF)
        put((v ushr 24).toInt() and 0xFF)
        put((v ushr 16).toInt() and 0xFF)
        put((v ushr 8).toInt() and 0xFF)
        put(v.toInt() and 0xFF)
    }
}

/**
 * 读。构造时把 [start, end) 之间的字段建成索引（tag -> 类型 + 值区间），之后按
 * tag 取。字段缺失或类型不符返回默认值；只有数据本身崩坏才抛 [TarsException]，
 * 由调用方丢掉这一条。索引是必要成本：一条 1400 弹幕只有 3~20 个字段。
 */
class TarsReader private constructor(
    private val data: ByteArray,
    private val start: Int,
    private val end: Int
) {

    private val types = HashMap<Int, Int>(8)
    private val starts = HashMap<Int, Int>(8)
    private val ends = HashMap<Int, Int>(8)

    init {
        if (start < 0 || end > data.size || start > end) throw TarsException("bad range")
        index()
    }

    companion object {
        fun of(data: ByteArray): TarsReader = TarsReader(data, 0, data.size)
    }

    fun has(tag: Int): Boolean = types.containsKey(tag)

    fun typeOf(tag: Int): Int? = types[tag]

    fun long(tag: Int, def: Long = 0L): Long {
        val type = types[tag] ?: return def
        val p = starts.getValue(tag)
        return when (type) {
            TarsType.ZERO_TAG -> 0L
            TarsType.BYTE -> valueOfByte(p).toLong()
            TarsType.SHORT -> valueOfShort(p).toLong()
            TarsType.INT -> readInt(p).toLong()
            TarsType.LONG -> readLong(p)
            else -> def
        }
    }

    fun int(tag: Int, def: Int = 0): Int {
        val v = long(tag, def.toLong())
        return if (v > Int.MAX_VALUE || v < Int.MIN_VALUE) def else v.toInt()
    }

    fun string(tag: Int, def: String = ""): String {
        val type = types[tag] ?: return def
        val p = starts.getValue(tag)
        return when (type) {
            TarsType.ZERO_TAG -> ""
            TarsType.STRING1 -> {
                val n = data[p].toInt() and 0xFF
                if (p + 1 + n > end) def else String(data, p + 1, n, Charsets.UTF_8)
            }
            TarsType.STRING4 -> {
                val n = readInt(p)
                if (n < 0 || p + 4 + n > end) def else String(data, p + 4, n, Charsets.UTF_8)
            }
            else -> def
        }
    }

    /** 缺失返回 null，空数组返回 `ByteArray(0)` —— 两者必须能区分。 */
    fun bytes(tag: Int): ByteArray? {
        val type = types[tag] ?: return null
        if (type == TarsType.ZERO_TAG) return ByteArray(0)
        if (type != TarsType.SIMPLE_LIST) return null
        val elementsHead = headEnd(starts.getValue(tag))     // 元素类型头之后
        val len = lengthAt(elementsHead)
        if (len < 0) return null
        val from = lengthEnd(elementsHead)
        if (from + len > end) return null
        return data.copyOfRange(from, from + len)
    }

    /** 嵌套 struct（STRUCT_BEGIN 字段）。 */
    fun struct(tag: Int): TarsReader? {
        if (types[tag] != TarsType.STRUCT_BEGIN) return null
        val s = starts.getValue(tag)
        val e = ends.getValue(tag)
        return if (e > s) TarsReader(data, s, e) else null
    }

    /** LIST 里的 struct 元素（如 HYPushMessageV2.items）。 */
    fun structs(tag: Int): List<TarsReader> {
        if (types[tag] != TarsType.LIST) return emptyList()
        val countField = starts.getValue(tag)
        val count = lengthAt(countField)
        if (count <= 0) return emptyList()
        val limit = ends.getValue(tag)
        val out = ArrayList<TarsReader>(count.coerceAtMost(32))
        var q = lengthEnd(countField)
        var i = 0
        while (i < count && q < limit) {
            val type = typeAt(q)
            val bodyStart = headEnd(q)
            val bodyEnd = skipValue(type, bodyStart)
            if (type == TarsType.STRUCT_BEGIN && bodyEnd > bodyStart) {
                out.add(TarsReader(data, bodyStart, bodyEnd))
            }
            q = bodyEnd
            i++
        }
        return out
    }

    // ---- 内部

    private fun index() {
        var p = start
        while (p < end) {
            val type = typeAt(p)
            if (type == TarsType.STRUCT_END) return
            val tag = tagAt(p)
            val bodyStart = headEnd(p)
            val bodyEnd = skipValue(type, bodyStart)
            types[tag] = type
            starts[tag] = bodyStart
            ends[tag] = bodyEnd
            p = bodyEnd
        }
    }

    private fun typeAt(p: Int): Int {
        if (p >= data.size) throw TarsException("head past end")
        return data[p].toInt() and 0x0F
    }

    private fun tagAt(p: Int): Int {
        if (p >= data.size) throw TarsException("head past end")
        val high = (data[p].toInt() and 0xFF) ushr 4
        if (high != 15) return high
        if (p + 1 >= data.size) throw TarsException("tag past end")
        return data[p + 1].toInt() and 0xFF
    }

    /** 值区间起点 = 头之后；tag >= 15 的头占两字节。 */
    private fun headEnd(p: Int): Int {
        if (p >= data.size) throw TarsException("head past end")
        val high = (data[p].toInt() and 0xFF) ushr 4
        return if (high == 15) p + 2 else p + 1
    }

    /** 位置 p 是「带类型的长度字段」的头，返回长度；不支持的类型返回 -1。 */
    private fun lengthAt(p: Int): Int {
        val type = typeAt(p)
        val v = headEnd(p)
        return when (type) {
            TarsType.ZERO_TAG -> 0
            TarsType.BYTE -> valueOfByte(v).toInt()
            TarsType.SHORT -> valueOfShort(v).toInt()
            TarsType.INT -> readInt(v)
            else -> -1
        }
    }

    /** 位置 p 是「带类型的长度字段」的头，返回值之后的位置。 */
    private fun lengthEnd(p: Int): Int {
        val type = typeAt(p)
        val v = headEnd(p)
        return when (type) {
            TarsType.ZERO_TAG -> v
            TarsType.BYTE -> v + 1
            TarsType.SHORT -> v + 2
            TarsType.INT -> v + 4
            else -> v
        }
    }

    private fun skipValue(type: Int, p: Int): Int {
        val next = when (type) {
            TarsType.BYTE -> p + 1
            TarsType.SHORT -> p + 2
            TarsType.INT, TarsType.FLOAT -> p + 4
            TarsType.LONG, TarsType.DOUBLE -> p + 8
            TarsType.STRING1 -> p + 1 + (data[p].toInt() and 0xFF)
            TarsType.STRING4 -> p + 4 + readInt(p)
            TarsType.SIMPLE_LIST -> {
                val elementsHead = headEnd(p)      // 元素类型头
                lengthEnd(elementsHead) + lengthAt(elementsHead)
            }
            // LIST / MAP 靠「元素个数」而不是结束标记推进：Tars 不写结束标记，
            // 按 STRUCT_END 找会提前停在第一个 struct 元素里。
            TarsType.LIST -> skipElements(p, lengthAt(p))
            TarsType.MAP -> skipElements(p, lengthAt(p) * 2)
            TarsType.STRUCT_BEGIN -> skipUntilStructEnd(p)
            TarsType.STRUCT_END, TarsType.ZERO_TAG -> p
            else -> throw TarsException("unknown tars type $type")
        }
        if (next < p || next > data.size) throw TarsException("field overruns buffer")
        return next
    }

    /** 跳过 count 个 tag 0 的元素；p 是「个数」字段的头。 */
    private fun skipElements(p: Int, count: Int): Int {
        if (count <= 0) return lengthEnd(p)
        var q = lengthEnd(p)
        var i = 0
        while (i < count && q < data.size) {
            val type = typeAt(q)
            q = skipValue(type, headEnd(q))
            i++
        }
        return q
    }

    private fun skipUntilStructEnd(p: Int): Int {
        var q = p
        while (q < data.size) {
            val type = typeAt(q)
            val bodyStart = headEnd(q)
            if (type == TarsType.STRUCT_END) return bodyStart
            q = skipValue(type, bodyStart)
        }
        return q
    }

    private fun valueOfByte(p: Int): Byte = data[p]

    private fun valueOfShort(p: Int): Short =
        (((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)).toShort()

    private fun readInt(p: Int): Int =
        ((data[p].toInt() and 0xFF) shl 24) or
            ((data[p + 1].toInt() and 0xFF) shl 16) or
            ((data[p + 2].toInt() and 0xFF) shl 8) or
            (data[p + 3].toInt() and 0xFF)

    private fun readLong(p: Int): Long =
        (readInt(p).toLong() shl 32) or (readInt(p + 4).toLong() and 0xFFFFFFFFL)
}
