package com.offlinechat.app.core

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChunkReassemblerTest {
    private lateinit var dir: File
    private val chunkSize = 1000
    private val data = Random(1).nextBytes(3500)
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data)

    @Before fun setUp() { dir = Files.createTempDirectory("reasm").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun reassembler(name: String = "f.part") = ChunkReassembler(File(dir, name), data.size.toLong(), chunkSize, 4, sha)
    private fun chunk(i: Int) = data.copyOfRange(i * chunkSize, minOf(data.size, (i + 1) * chunkSize))

    @Test
    fun inOrderChunksAssembleAndVerify() {
        val r = reassembler()
        for (i in 0 until 4) {
            val c = chunk(i)
            assertEquals(ChunkReassembler.Result.ACCEPTED, r.accept(i, Hashing.crc32(c), c))
        }
        assertTrue(r.isComplete)
        assertEquals(data.size.toLong(), r.bytesReceived)
        assertTrue(r.verify())
        val target = File(dir, "out/final.bin")
        assertTrue(r.moveTo(target))
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun duplicatesOutOfOrderAndBadCrcAreRejected() {
        val r = reassembler()
        val c0 = chunk(0)
        assertEquals(ChunkReassembler.Result.ACCEPTED, r.accept(0, Hashing.crc32(c0), c0))
        assertEquals(ChunkReassembler.Result.DUPLICATE, r.accept(0, Hashing.crc32(c0), c0))
        val c2 = chunk(2)
        assertEquals(ChunkReassembler.Result.OUT_OF_ORDER, r.accept(2, Hashing.crc32(c2), c2))
        val c1 = chunk(1)
        assertEquals(ChunkReassembler.Result.BAD_CRC, r.accept(1, Hashing.crc32(c1) xor 1, c1))
        assertEquals(ChunkReassembler.Result.BAD_LENGTH, r.accept(1, Hashing.crc32(c1.copyOf(10)), c1.copyOf(10)))
        assertEquals(1, r.nextChunk)
        r.discard()
    }

    @Test
    fun tamperedContentFailsFinalSha() {
        val r = reassembler()
        for (i in 0 until 4) {
            val c = chunk(i).copyOf()
            if (i == 2) c[5] = (c[5] + 1).toByte() // corrupt, but with a CRC that matches the corrupted bytes
            r.accept(i, Hashing.crc32(c), c)
        }
        assertTrue(r.isComplete)
        assertFalse(r.verify())
    }

    @Test
    fun interruptedTransferResumesFromTheLastFullChunk() {
        val first = reassembler()
        for (i in 0 until 2) first.accept(i, Hashing.crc32(chunk(i)), chunk(i))
        first.close()

        val resumed = reassembler()
        assertEquals(2, resumed.nextChunk)
        for (i in resumed.nextChunk until 4) resumed.accept(i, Hashing.crc32(chunk(i)), chunk(i))
        assertTrue(resumed.verify())
    }

    @Test
    fun partialTrailingBytesAreTruncatedOnResume() {
        val part = File(dir, "f.part")
        part.writeBytes(data.copyOf(2400)) // 2 full chunks + 400 stray bytes
        val r = ChunkReassembler(part, data.size.toLong(), chunkSize, 4, sha)
        assertEquals(2, r.nextChunk)
        assertEquals(2000L, part.length())
        r.close()
    }
}
