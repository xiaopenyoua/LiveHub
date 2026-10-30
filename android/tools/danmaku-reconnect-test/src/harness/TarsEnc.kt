// 手写一个最小 Tars/JCE 编码器，只为造虎牙的推送包。
//
// 为什么不复用生产代码里的 TarsWriter：那一个是「写请求」用的（只有 int/string/bytes/list，
// 没有 struct 的 begin/end）。更重要的一点——**编码器与解码器由两套独立实现**，
// 服务器端这样拼出来的字节如果被生产的 TarsReader 解对了，「我们自己写错、自己读对」
// 这种同源 bug 的概率就低得多。真实抓包验证过的字节序见 Tars.kt 顶部注释。
package harness

import java.io.ByteArrayOutputStream

class TarsEnc {
    private val out = ByteArrayOutputStream()

    private fun head(type: Int, tag: Int) {
        if (tag < 15) {
            out.write((tag shl 4) or type)
        } else {
            out.write((15 shl 4) or type)
            out.write(tag and 0xFF)
        }
    }

    /** 0 走 ZERO_TAG（只写头），其余按范围选 BYTE/SHORT/INT/LONG，与虎牙生成代码一致。 */
    fun int(tag: Int, v: Long): TarsEnc {
        when {
            v == 0L -> head(12, tag)
            v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE -> {
                head(0, tag); out.write(v.toInt() and 0xFF)
            }
            v >= Short.MIN_VALUE && v <= Short.MAX_VALUE -> {
                head(1, tag)
                out.write((v.toInt() shr 8) and 0xFF)
                out.write(v.toInt() and 0xFF)
            }
            v >= Int.MIN_VALUE && v <= Int.MAX_VALUE -> {
                head(2, tag); putInt(v.toInt())
            }
            else -> {
                head(3, tag)
                for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
            }
        }
        return this
    }

    /** 虎牙的服务端组名解析只认 STRING4，这里也统一用 STRING4。 */
    fun str(tag: Int, s: String): TarsEnc {
        val raw = s.toByteArray(Charsets.UTF_8)
        head(7, tag)
        putInt(raw.size)
        out.write(raw)
        return this
    }

    /** SIMPLE_LIST：元素类型头 + 「带类型的长度字段」+ 内容（见 Tars.kt 坑 1）。 */
    fun bytes(tag: Int, b: ByteArray): TarsEnc {
        head(13, tag)
        head(0, 0)
        int(0, b.size.toLong())
        out.write(b)
        return this
    }

    /** 开始一个 tag 为 [tag] 的 struct。 */
    fun begin(tag: Int): TarsEnc {
        head(10, tag)
        return this
    }

    /** 结束当前 struct。 */
    fun end(): TarsEnc {
        head(11, 0)
        return this
    }

    fun done(): ByteArray = out.toByteArray()

    private fun putInt(v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}

/**
 * 一条虎牙聊天推送，逐层对齐生产解码器：
 * 信封 `{0: 7, 1: HYPushMessage}` → `HYPushMessage{0: pushType, 1: uri=1400, 2: msg}`
 * → `HYMessage{0: HYSender{0: uid, 2: nick}, 3: content, 6: HYBulletFormat{0: fontColor}}`。
 *
 * [color] = 0 时不写字段 6，用来复现「fontColor 缺省」的那批消息（生产代码会当白字）。
 */
fun huyaChatPush(nick: String, text: String, color: Long = 0L): ByteArray {
    val msg = TarsEnc()
    msg.begin(0).int(0, 1200).str(2, nick).end()
    msg.str(3, text)
    if (color != 0L) msg.begin(6).int(0, color).end()

    val push = TarsEnc()
    push.int(0, 1400)   // pushType
    push.int(1, 1400)   // uri
    push.bytes(2, msg.done())

    val envelope = TarsEnc()
    envelope.int(0, 7)  // 7 = HYPushMessage
    envelope.bytes(1, push.done())
    return envelope.done()
}

/** 斗鱼 STT 包：`[4B 长度小端][4B 长度小端][4B 类型=689][正文][\0]`，与生产 pack() 同构。 */
fun douyuChatPush(user: String, text: String, col: Long = 0L, iflag: Int = 1): ByteArray {
    val body = "type@=chatmsg/rid@=9999/uid@=1001/nn@=$user/txt@=$text/level@=12/col@=$col/if@=$iflag/"
        .toByteArray(Charsets.UTF_8)
    val len = body.size + 9
    val buf = java.nio.ByteBuffer.allocate(12 + body.size + 1)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putInt(len).putInt(len).putInt(689)
        .put(body).put(0)
    return buf.array()
}
