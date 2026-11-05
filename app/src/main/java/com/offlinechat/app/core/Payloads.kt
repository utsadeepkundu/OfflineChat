package com.offlinechat.app.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class PayloadWriter {
    private val bos = ByteArrayOutputStream(64)
    private val out = DataOutputStream(bos)

    fun byte(v: Int) = apply { out.writeByte(v) }
    fun bool(v: Boolean) = apply { out.writeBoolean(v) }
    fun int(v: Int) = apply { out.writeInt(v) }
    fun long(v: Long) = apply { out.writeLong(v) }
    fun string(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }
    fun bytes(b: ByteArray) = apply {
        out.writeInt(b.size)
        out.write(b)
    }
    fun toByteArray(): ByteArray = bos.toByteArray()
}

class PayloadReader(data: ByteArray) {
    private val buf = ByteBuffer.wrap(data)

    fun byte(): Int = buf.get().toInt() and 0xFF
    fun bool(): Boolean = buf.get().toInt() != 0
    fun int(): Int = buf.getInt()
    fun long(): Long = buf.getLong()
    fun string(): String = String(bytes(), Charsets.UTF_8)
    fun bytes(): ByteArray {
        val n = int()
        require(n in 0..buf.remaining()) { "Bad length $n" }
        return ByteArray(n).also { buf.get(it) }
    }
}

object HelloKind {
    const val LINK = 0
    const val PROBE = 1
    const val REPLY = 2
}

object RejectReason {
    const val FULL = 1
    const val DECLINED = 2
    const val NOT_HOST = 3
    const val UNKNOWN_GROUP = 4
}

object AckCode {
    const val DELIVERED = 0
    const val FILE_ACCEPT = 1
    const val FILE_COMPLETE = 2
    const val FILE_REJECT = 3
    const val CANCEL = 4
}

/** A group a device is currently hosting, advertised in HELLO replies. */
data class GroupAd(val groupId: Long, val name: String, val color: Int, val members: Int, val maxMembers: Int) {
    internal fun writeTo(w: PayloadWriter) {
        w.long(groupId).string(name).int(color).byte(members).byte(maxMembers)
    }

    companion object {
        internal fun read(r: PayloadReader) = GroupAd(r.long(), r.string(), r.int(), r.byte(), r.byte())
    }
}

data class Hello(
    val peerId: Long,
    val name: String,
    val kind: Int,
    val groups: List<GroupAd> = emptyList(),
    val protocol: Int = Protocol.VERSION,
) {
    fun encode(): ByteArray {
        val w = PayloadWriter().long(peerId).string(name).byte(kind).byte(protocol).int(groups.size)
        groups.forEach { it.writeTo(w) }
        return w.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): Hello {
            val r = PayloadReader(b)
            val id = r.long()
            val name = r.string()
            val kind = r.byte()
            val protocol = r.byte()
            val count = r.int()
            require(count in 0..32)
            return Hello(id, name, kind, List(count) { GroupAd.read(r) }, protocol)
        }
    }
}

data class MemberInfo(val peerId: Long, val name: String, val address: String, val online: Boolean) {
    internal fun writeTo(w: PayloadWriter) {
        w.long(peerId).string(name).string(address).bool(online)
    }

    fun encode(): ByteArray = PayloadWriter().also { writeTo(it) }.toByteArray()

    companion object {
        internal fun read(r: PayloadReader) = MemberInfo(r.long(), r.string(), r.string(), r.bool())
        fun decode(b: ByteArray) = read(PayloadReader(b))
    }
}

data class MemberList(val members: List<MemberInfo>) {
    fun encode(): ByteArray {
        val w = PayloadWriter().int(members.size)
        members.forEach { it.writeTo(w) }
        return w.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): MemberList {
            val r = PayloadReader(b)
            val n = r.int()
            require(n in 0..64)
            return MemberList(List(n) { MemberInfo.read(r) })
        }
    }
}

/** Group metadata. Sent in JOIN_ACCEPT and as GROUP_CREATE whenever the host changes it. */
data class GroupMeta(val groupId: Long, val name: String, val color: Int, val maxMembers: Int, val hostId: Long) {
    fun encode(): ByteArray = PayloadWriter().long(groupId).string(name).int(color).byte(maxMembers).long(hostId).toByteArray()

    companion object {
        fun decode(b: ByteArray): GroupMeta {
            val r = PayloadReader(b)
            return GroupMeta(r.long(), r.string(), r.int(), r.byte(), r.long())
        }
    }
}

data class JoinRequest(val name: String, val rejoin: Boolean) {
    fun encode(): ByteArray = PayloadWriter().string(name).bool(rejoin).toByteArray()

    companion object {
        fun decode(b: ByteArray): JoinRequest {
            val r = PayloadReader(b)
            return JoinRequest(r.string(), r.bool())
        }
    }
}

data class JoinReject(val reason: Int) {
    fun encode(): ByteArray = PayloadWriter().byte(reason).toByteArray()

    companion object {
        fun decode(b: ByteArray) = JoinReject(PayloadReader(b).byte())
    }
}

data class MemberLeft(val peerId: Long, val removed: Boolean) {
    fun encode(): ByteArray = PayloadWriter().long(peerId).bool(removed).toByteArray()

    companion object {
        fun decode(b: ByteArray): MemberLeft {
            val r = PayloadReader(b)
            return MemberLeft(r.long(), r.bool())
        }
    }
}

data class TypingPayload(val name: String, val typing: Boolean) {
    fun encode(): ByteArray = PayloadWriter().string(name).bool(typing).toByteArray()

    companion object {
        fun decode(b: ByteArray): TypingPayload {
            val r = PayloadReader(b)
            return TypingPayload(r.string(), r.bool())
        }
    }
}

data class HistoryItem(
    val messageId: Long,
    val senderId: Long,
    val senderName: String,
    val seq: Long,
    val timestamp: Long,
    val text: String,
)

data class HistorySync(val items: List<HistoryItem>) {
    fun encode(): ByteArray {
        val w = PayloadWriter().int(items.size)
        items.forEach { w.long(it.messageId).long(it.senderId).string(it.senderName).long(it.seq).long(it.timestamp).string(it.text) }
        return w.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): HistorySync {
            val r = PayloadReader(b)
            val n = r.int()
            require(n in 0..500)
            return HistorySync(List(n) { HistoryItem(r.long(), r.long(), r.string(), r.long(), r.long(), r.string()) })
        }

        /** Rough encoded size, used to split large histories over several packets. */
        fun estimateSize(item: HistoryItem) = 48 + item.senderName.length * 3 + item.text.length * 3

        fun split(items: List<HistoryItem>, maxBytes: Int = 48 * 1024): List<HistorySync> {
            val out = ArrayList<HistorySync>()
            var current = ArrayList<HistoryItem>()
            var size = 0
            for (item in items) {
                val s = estimateSize(item)
                if (current.isNotEmpty() && size + s > maxBytes) {
                    out += HistorySync(current)
                    current = ArrayList()
                    size = 0
                }
                current += item
                size += s
            }
            if (current.isNotEmpty()) out += HistorySync(current)
            return out
        }
    }
}

data class HostMigrate(val newHostId: Long, val newHostName: String, val newHostAddress: String) {
    fun encode(): ByteArray = PayloadWriter().long(newHostId).string(newHostName).string(newHostAddress).toByteArray()

    companion object {
        fun decode(b: ByteArray): HostMigrate {
            val r = PayloadReader(b)
            return HostMigrate(r.long(), r.string(), r.string())
        }
    }
}

data class TextPayload(val text: String, val timestamp: Long, val senderName: String) {
    fun encode(): ByteArray = PayloadWriter().string(text).long(timestamp).string(senderName).toByteArray()

    companion object {
        fun decode(b: ByteArray): TextPayload {
            val r = PayloadReader(b)
            return TextPayload(r.string(), r.long(), r.string())
        }
    }
}

class FileMeta(
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: ByteArray,
    val chunkSize: Int,
    val chunkCount: Int,
    val timestamp: Long,
    val senderName: String,
) {
    fun encode(): ByteArray = PayloadWriter()
        .string(name).string(mime).long(size).bytes(sha256).int(chunkSize).int(chunkCount).long(timestamp).string(senderName)
        .toByteArray()

    companion object {
        fun decode(b: ByteArray): FileMeta {
            val r = PayloadReader(b)
            return FileMeta(r.string(), r.string(), r.long(), r.bytes(), r.int(), r.int(), r.long(), r.string())
        }

        fun chunkCountFor(size: Long, chunkSize: Int): Int = ((size + chunkSize - 1) / chunkSize).toInt()
    }
}

class FileChunk(val index: Int, val crc: Long, val data: ByteArray) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(8 + data.size)
        buf.putInt(index)
        buf.putInt(crc.toInt())
        buf.put(data)
        return buf.array()
    }

    companion object {
        fun decode(b: ByteArray): FileChunk {
            require(b.size >= 8)
            val bb = ByteBuffer.wrap(b)
            val index = bb.getInt()
            val crc = bb.getInt().toLong() and 0xFFFFFFFFL
            return FileChunk(index, crc, b.copyOfRange(8, b.size))
        }
    }
}

data class Ack(val code: Int, val value: Long = 0L) {
    fun encode(): ByteArray = PayloadWriter().byte(code).long(value).toByteArray()

    companion object {
        fun decode(b: ByteArray): Ack {
            val r = PayloadReader(b)
            return Ack(r.byte(), r.long())
        }
    }
}
