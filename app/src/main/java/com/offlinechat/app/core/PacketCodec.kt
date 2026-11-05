package com.offlinechat.app.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

class ProtocolException(message: String) : IOException(message)

/**
 * Binary framing: a fixed 38-byte big-endian header followed by the payload.
 *
 *  offset size field
 *  0      1    version
 *  1      1    type
 *  2      4    payload length
 *  6      8    message id
 *  14     8    sender id
 *  22     8    group id (0 = direct chat)
 *  30     8    sequence number (assigned by the group host, 0 otherwise)
 */
object PacketCodec {
    fun encode(p: Packet): ByteArray {
        val buf = ByteBuffer.allocate(Protocol.HEADER_SIZE + p.payload.size)
        buf.put(Protocol.VERSION.toByte())
        buf.put(p.type.id.toByte())
        buf.putInt(p.payload.size)
        buf.putLong(p.messageId)
        buf.putLong(p.senderId)
        buf.putLong(p.groupId)
        buf.putLong(p.seq)
        buf.put(p.payload)
        return buf.array()
    }

    /** Blocks until a full packet arrives. Unknown types are returned as [PacketType.UNKNOWN] so callers can skip them. */
    fun read(input: InputStream): Packet {
        val header = ByteArray(Protocol.HEADER_SIZE)
        readFully(input, header)
        val bb = ByteBuffer.wrap(header)
        val version = bb.get().toInt() and 0xFF
        if (version != Protocol.VERSION) throw ProtocolException("Unsupported protocol version $version")
        val type = PacketType.fromId(bb.get().toInt() and 0xFF)
        val length = bb.getInt()
        if (length < 0 || length > Protocol.MAX_PAYLOAD) throw ProtocolException("Bad payload length $length")
        val messageId = bb.getLong()
        val senderId = bb.getLong()
        val groupId = bb.getLong()
        val seq = bb.getLong()
        val payload = if (length == 0) Packet.EMPTY else ByteArray(length).also { readFully(input, it) }
        return Packet(type, messageId, senderId, groupId, seq, payload)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) throw EOFException("Stream closed")
            offset += n
        }
    }
}
