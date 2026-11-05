package com.offlinechat.app.core

import java.util.UUID

/** Wire-level constants. Everything here is documented in PROTOCOL.md. */
object Protocol {
    const val VERSION = 1
    const val HEADER_SIZE = 38
    const val MAX_PAYLOAD = 96 * 1024

    const val FILE_CHUNK_SIZE = 8 * 1024
    const val MAX_FILE_CHUNK_SIZE = 32 * 1024
    const val MAX_DIRECT_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_GROUP_FILE_BYTES = 5L * 1024 * 1024
    const val MAX_TEXT_CHARS = 2000

    const val DEFAULT_MAX_MEMBERS = 6
    const val HARD_MAX_MEMBERS = 7
    const val DEFAULT_HISTORY = 50

    const val SERVICE_NAME = "OfflineChat"
    val SECURE_UUID: UUID = UUID.fromString("8f0e7c2a-5b1d-4c53-9a43-6d2f4b7e1a10")
    val INSECURE_UUID: UUID = UUID.fromString("8f0e7c2b-5b1d-4c53-9a43-6d2f4b7e1a10")
}

/** Tunable timings. Tests shrink these so scenarios run in milliseconds. */
data class Timings(
    val pingMs: Long = 8_000,
    val linkTimeoutMs: Long = 25_000,
    val handshakeMs: Long = 10_000,
    val fileAcceptMs: Long = 20_000,
    val fileCompleteMs: Long = 30_000,
    val relayStallMs: Long = 5_000,
    val hostRetryDelayMs: Long = 4_000,
    val hostRetries: Int = 4,
    val joinDecisionMs: Long = 90_000,
    val typingTtlMs: Long = 4_500,
    val gracefulCloseMs: Long = 400,
)

enum class PacketType(val id: Int) {
    UNKNOWN(0),
    HELLO(1),
    TEXT(2),
    FILE_META(3),
    FILE_CHUNK(4),
    ACK(5),
    PING(6),
    GROUP_CREATE(7),
    JOIN_REQUEST(8),
    JOIN_ACCEPT(9),
    JOIN_REJECT(10),
    MEMBER_LIST(11),
    MEMBER_JOINED(12),
    MEMBER_LEFT(13),
    TYPING(14),
    HISTORY_SYNC(15),
    HOST_MIGRATE(16);

    companion object {
        private val byId = values().associateBy { it.id }
        fun fromId(id: Int): PacketType = byId[id] ?: UNKNOWN
    }
}

class Packet(
    val type: PacketType,
    val messageId: Long = 0L,
    val senderId: Long = 0L,
    val groupId: Long = 0L,
    val seq: Long = 0L,
    val payload: ByteArray = EMPTY,
) {
    fun withSeq(newSeq: Long) = Packet(type, messageId, senderId, groupId, newSeq, payload)

    override fun toString() = "Packet($type msg=$messageId from=$senderId group=$groupId seq=$seq len=${payload.size})"

    companion object {
        val EMPTY = ByteArray(0)
    }
}
