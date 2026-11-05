package com.offlinechat.app.core

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {

    @Test
    fun packetRoundTripKeepsEveryHeaderField() {
        val p = Packet(PacketType.TEXT, messageId = -42L, senderId = 7L, groupId = 99L, seq = 123456789L, payload = "hello".toByteArray())
        val bytes = PacketCodec.encode(p)
        assertEquals(Protocol.HEADER_SIZE + 5, bytes.size)
        val back = PacketCodec.read(ByteArrayInputStream(bytes))
        assertEquals(PacketType.TEXT, back.type)
        assertEquals(-42L, back.messageId)
        assertEquals(7L, back.senderId)
        assertEquals(99L, back.groupId)
        assertEquals(123456789L, back.seq)
        assertArrayEquals("hello".toByteArray(), back.payload)
    }

    @Test
    fun emptyPayloadAndBackToBackPacketsDecode() {
        val a = PacketCodec.encode(Packet(PacketType.PING))
        val b = PacketCodec.encode(Packet(PacketType.TEXT, 1, 2, 0, 0, byteArrayOf(1, 2, 3)))
        val stream = ByteArrayInputStream(a + b)
        assertEquals(PacketType.PING, PacketCodec.read(stream).type)
        assertArrayEquals(byteArrayOf(1, 2, 3), PacketCodec.read(stream).payload)
    }

    @Test
    fun streamThatDeliversOneByteAtATimeStillDecodes() {
        val bytes = PacketCodec.encode(Packet(PacketType.ACK, 5, 6, 0, 0, byteArrayOf(9, 9, 9, 9)))
        val trickle = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= bytes.size) return -1
                b[off] = bytes[i++]
                return 1
            }
        }
        assertEquals(PacketType.ACK, PacketCodec.read(trickle).type)
    }

    @Test
    fun truncatedStreamThrowsEof() {
        val bytes = PacketCodec.encode(Packet(PacketType.TEXT, payload = ByteArray(50)))
        try {
            PacketCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 10)))
            fail("expected EOF")
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test
    fun wrongVersionAndOversizedLengthAreRejected() {
        val good = PacketCodec.encode(Packet(PacketType.TEXT))
        val wrongVersion = good.copyOf().also { it[0] = 9 }
        try { PacketCodec.read(ByteArrayInputStream(wrongVersion)); fail() } catch (e: ProtocolException) { }
        val huge = good.copyOf().also { it[2] = 0x7F }
        try { PacketCodec.read(ByteArrayInputStream(huge)); fail() } catch (e: ProtocolException) { }
    }

    @Test
    fun unknownTypeIsReturnedAsUnknownSoCallersCanSkipIt() {
        val bytes = PacketCodec.encode(Packet(PacketType.TEXT)).also { it[1] = 77 }
        assertEquals(PacketType.UNKNOWN, PacketCodec.read(ByteArrayInputStream(bytes)).type)
    }

    @Test
    fun helloAndGroupAdsRoundTrip() {
        val hello = Hello(11L, "Asha", HelloKind.REPLY, listOf(GroupAd(5L, "Trek", 0xFF00AA, 3, 6), GroupAd(6L, "Chai", 1, 6, 6)))
        val back = Hello.decode(hello.encode())
        assertEquals(hello, back)
    }

    @Test
    fun memberListHistoryAndTextRoundTripWithUnicode() {
        val members = MemberList(listOf(MemberInfo(1, "Ravi", "AA:BB", true), MemberInfo(2, "রিয়া", "CC:DD", false)))
        assertEquals(members, MemberList.decode(members.encode()))

        val history = HistorySync(listOf(HistoryItem(1, 2, "Ravi", 3, 4, "নমস্কার 👋"), HistoryItem(5, 6, "Ria", 7, 8, "ok")))
        assertEquals(history, HistorySync.decode(history.encode()))

        val text = TextPayload("héllo wörld ✓", 1234L, "Zoë")
        assertEquals(text, TextPayload.decode(text.encode()))
    }

    @Test
    fun fileMetaChunkAndAckRoundTrip() {
        val sha = ByteArray(32) { it.toByte() }
        val meta = FileMeta("a.pdf", "application/pdf", 20_000, sha, 8192, 3, 99L, "Ria")
        val back = FileMeta.decode(meta.encode())
        assertEquals("a.pdf", back.name)
        assertEquals(20_000L, back.size)
        assertArrayEquals(sha, back.sha256)
        assertEquals(3, back.chunkCount)

        val data = ByteArray(100) { (it * 3).toByte() }
        val chunk = FileChunk(7, Hashing.crc32(data), data)
        val chunkBack = FileChunk.decode(chunk.encode())
        assertEquals(7, chunkBack.index)
        assertEquals(chunk.crc, chunkBack.crc)
        assertArrayEquals(data, chunkBack.data)

        assertEquals(Ack(AckCode.FILE_ACCEPT, 12L), Ack.decode(Ack(AckCode.FILE_ACCEPT, 12L).encode()))
    }

    @Test
    fun chunkCountRoundsUp() {
        assertEquals(1, FileMeta.chunkCountFor(1, 8192))
        assertEquals(1, FileMeta.chunkCountFor(8192, 8192))
        assertEquals(2, FileMeta.chunkCountFor(8193, 8192))
    }

    @Test
    fun hugeHistoryIsSplitIntoPacketsThatFitTheLimit() {
        val items = (1..300).map { HistoryItem(it.toLong(), 1, "Name", it.toLong(), 0, "x".repeat(1500)) }
        val parts = HistorySync.split(items)
        assertTrue(parts.size > 1)
        assertEquals(300, parts.sumOf { it.items.size })
        parts.forEach { assertTrue(it.encode().size <= Protocol.MAX_PAYLOAD) }
    }

    @Test
    fun corruptedPayloadFailsInsteadOfAllocatingGarbage() {
        try {
            MemberList.decode(PayloadWriter().int(1_000_000).toByteArray())
            fail()
        } catch (e: IllegalArgumentException) { }
    }
}
