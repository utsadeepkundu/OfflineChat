package com.offlinechat.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32

object Hashing {
    fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    fun crc32(data: ByteArray): Long = CRC32().also { it.update(data, 0, data.size) }.value

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Writes in-order file chunks straight to disk (never holds the file in memory) and can resume
 * from a partial file left behind by an interrupted transfer.
 */
class ChunkReassembler(
    private val partFile: File,
    val size: Long,
    val chunkSize: Int,
    val chunkCount: Int,
    private val expectedSha256: ByteArray,
) : Closeable {

    enum class Result { ACCEPTED, DUPLICATE, OUT_OF_ORDER, BAD_CRC, BAD_LENGTH }

    private var raf: RandomAccessFile? = null

    /** Index of the next chunk we expect; also the resume point reported to the sender. */
    var nextChunk: Int = 0
        private set

    val isComplete: Boolean get() = nextChunk >= chunkCount
    val bytesReceived: Long get() = minOf(size, nextChunk.toLong() * chunkSize)

    init {
        partFile.parentFile?.mkdirs()
        if (partFile.exists()) {
            val len = partFile.length()
            nextChunk = if (len >= size) chunkCount else (len / chunkSize).toInt().coerceAtMost(chunkCount)
            if (nextChunk < chunkCount) {
                RandomAccessFile(partFile, "rw").use { it.setLength(nextChunk.toLong() * chunkSize) }
            }
        }
    }

    fun accept(index: Int, crc: Long, data: ByteArray): Result {
        if (index < nextChunk) return Result.DUPLICATE
        if (index != nextChunk || index >= chunkCount) return Result.OUT_OF_ORDER
        val expectedLength = if (index == chunkCount - 1) (size - index.toLong() * chunkSize).toInt() else chunkSize
        if (data.size != expectedLength) return Result.BAD_LENGTH
        if (Hashing.crc32(data) != crc) return Result.BAD_CRC
        val file = raf ?: RandomAccessFile(partFile, "rw").also { raf = it }
        file.seek(index.toLong() * chunkSize)
        file.write(data)
        nextChunk++
        return Result.ACCEPTED
    }

    /** Whole-file integrity check against the SHA-256 announced in FILE_META. */
    fun verify(): Boolean {
        close()
        return partFile.length() == size && Hashing.sha256(partFile).contentEquals(expectedSha256)
    }

    fun moveTo(target: File): Boolean {
        close()
        target.parentFile?.mkdirs()
        if (partFile.renameTo(target)) return true
        return try {
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun discard() {
        close()
        partFile.delete()
    }

    override fun close() {
        runCatching { raf?.close() }
        raf = null
    }
}
