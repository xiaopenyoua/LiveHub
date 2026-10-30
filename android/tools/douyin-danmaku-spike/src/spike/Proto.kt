// 手写 protobuf-lite 解码：只解抖音弹幕这条链上用得着的四个消息。
//
// 出处：结构**搬自 DTV Mobile** shared/src/androidMain/kotlin/dtv/mobile/platform/douyin/
//   DouyinProtoLite.kt，Copyright 2025 c.zeong，MIT License。
// 只做三件事：varint / length-delimited / 跳字段。不引 protobuf runtime（省一个依赖）。
//
// 字段号依据（PushFrame / Response / Message / ChatMessage / User），越界一律 skip 不报错：
//   PushFrame  2=logId  7=payloadType  8=payload(gzip)
//   Response   1=message[]  5=internalExt  9=needAck
//   Message    1=method     2=payload
//   ChatMessage 2=user      3=content
//   User       3=nickName
package spike

import java.io.ByteArrayOutputStream

object Proto {
    private const val WIRE_VARINT = 0
    private const val WIRE_LEN = 2

    data class PushFrame(val logId: Long, val payloadType: String, val payload: ByteArray)
    data class Response(val needAck: Boolean, val internalExt: String, val messages: List<Message>)
    data class Message(val method: String, val payload: ByteArray)
    data class Chat(val nick: String, val content: String)

    class Reader(private val data: ByteArray) {
        private var pos = 0
        fun isEof() = pos >= data.size
        private fun byte(): Int {
            if (pos >= data.size) error("protobuf eof")
            return data[pos++].toInt() and 0xFF
        }
        fun varint(): Long {
            var shift = 0
            var out = 0L
            while (shift < 64) {
                val b = byte()
                out = out or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return out
                shift += 7
            }
            error("bad varint")
        }
        fun tag(): Pair<Int, Int>? {
            if (isEof()) return null
            val key = varint().toInt()
            return (key ushr 3) to (key and 0x07)
        }
        fun bytes(): ByteArray {
            val len = varint().toInt()
            if (len < 0 || pos + len > data.size) error("bad len=$len")
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
        fun string(): String = bytes().toString(Charsets.UTF_8)
        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_LEN -> {
                    val len = varint().toInt()
                    if (len < 0 || pos + len > data.size) error("bad skip len=$len")
                    pos += len
                }
                else -> error("unsupported wire=$wire")
            }
        }
    }

    // ---------------------------------------------------------------- 编码（心跳 / ack）

    private fun writeVarint(out: ByteArrayOutputStream, v0: Long) {
        var v = v0
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.write(b); return }
            out.write(b or 0x80)
        }
    }

    private fun key(out: ByteArrayOutputStream, field: Int, wire: Int) = writeVarint(out, ((field shl 3) or wire).toLong())

    /** 心跳/ack 都只是「同一个 PushFrame 换 payloadType」，没必要区分两套编码器。 */
    fun encodePushFrame(payloadType: String, logId: Long, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(64 + payload.size)
        key(out, 2, WIRE_VARINT); writeVarint(out, logId)
        key(out, 7, WIRE_LEN); writeVarint(out, payloadType.toByteArray().size.toLong()); out.write(payloadType.toByteArray())
        if (payload.isNotEmpty()) {
            key(out, 8, WIRE_LEN); writeVarint(out, payload.size.toLong()); out.write(payload)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 解码

    fun decodePushFrame(data: ByteArray): PushFrame? {
        val r = Reader(data)
        var logId = 0L
        var type = ""
        var payload = ByteArray(0)
        while (!r.isEof()) {
            val (f, w) = r.tag() ?: break
            when (f) {
                2 -> if (w == WIRE_VARINT) logId = r.varint() else r.skip(w)
                7 -> if (w == WIRE_LEN) type = r.string() else r.skip(w)
                8 -> if (w == WIRE_LEN) payload = r.bytes() else r.skip(w)
                else -> r.skip(w)
            }
        }
        if (type.isBlank()) return null
        return PushFrame(logId, type, payload)
    }

    fun decodeResponse(data: ByteArray): Response {
        val r = Reader(data)
        val messages = ArrayList<Message>(8)
        var needAck = false
        var ext = ""
        while (!r.isEof()) {
            val (f, w) = r.tag() ?: break
            when (f) {
                1 -> if (w == WIRE_LEN) decodeMessage(r.bytes())?.let(messages::add) else r.skip(w)
                5 -> if (w == WIRE_LEN) ext = r.string() else r.skip(w)
                9 -> if (w == WIRE_VARINT) needAck = r.varint() != 0L else r.skip(w)
                else -> r.skip(w)
            }
        }
        return Response(needAck, ext, messages)
    }

    private fun decodeMessage(data: ByteArray): Message? {
        val r = Reader(data)
        var method = ""
        var payload = ByteArray(0)
        while (!r.isEof()) {
            val (f, w) = r.tag() ?: break
            when (f) {
                1 -> if (w == WIRE_LEN) method = r.string() else r.skip(w)
                2 -> if (w == WIRE_LEN) payload = r.bytes() else r.skip(w)
                else -> r.skip(w)
            }
        }
        if (method.isBlank() || payload.isEmpty()) return null
        return Message(method, payload)
    }

    fun decodeChat(data: ByteArray): Chat? {
        val r = Reader(data)
        var content = ""
        var nick = ""
        while (!r.isEof()) {
            val (f, w) = r.tag() ?: break
            when (f) {
                2 -> if (w == WIRE_LEN) nick = decodeNick(r.bytes()) else r.skip(w)
                3 -> if (w == WIRE_LEN) content = r.string() else r.skip(w)
                else -> r.skip(w)
            }
        }
        if (content.isBlank()) return null
        return Chat(nick.ifBlank { "匿名" }, content)
    }

    private fun decodeNick(userPayload: ByteArray): String {
        val r = Reader(userPayload)
        var nick = ""
        while (!r.isEof()) {
            val (f, w) = r.tag() ?: break
            when (f) {
                3 -> if (w == WIRE_LEN) nick = r.string() else r.skip(w)
                else -> r.skip(w)
            }
        }
        return nick
    }
}
