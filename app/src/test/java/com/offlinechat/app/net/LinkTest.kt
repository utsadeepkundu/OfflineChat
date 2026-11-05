package com.offlinechat.app.net

import com.offlinechat.app.core.Packet
import com.offlinechat.app.core.PacketCodec
import com.offlinechat.app.core.PacketType
import com.offlinechat.app.core.Timings
import com.offlinechat.app.domain.RawConnection
import com.offlinechat.app.testutil.BytePipe
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    /** A connection whose writes are slow and recorded, so queue ordering becomes observable. */
    private class SlowSink(private val delayMs: Long) : RawConnection {
        val written = Collections.synchronizedList(mutableListOf<Packet>())
        private val pending = java.io.ByteArrayOutputStream()
        override val remoteAddress = "XX"
        override val secure = true
        private val idle = BytePipe()
        override val input: InputStream get() = idle.input
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = pending.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) {
                pending.write(b, off, len)
            }

            override fun flush() {
                Thread.sleep(delayMs)
                val bytes = pending.toByteArray()
                pending.reset()
                val stream = ByteArrayInputStream(bytes)
                while (stream.available() > 0) written += PacketCodec.read(stream)
            }
        }

        override fun close() = idle.close()
    }

    private fun link(raw: RawConnection) = Link(
        raw = raw, outgoing = true, scope = scope, io = Dispatchers.IO,
        timings = Timings(pingMs = 60_000, linkTimeoutMs = 120_000),
        clock = System::currentTimeMillis, dispatch = { _, _ -> }, onClosed = {},
    )

    @Test
    fun textOvertakesQueuedFileChunks() = runBlocking {
        val sink = SlowSink(delayMs = 30)
        val link = link(sink).also { it.start() }
        for (i in 0 until 6) link.sendBulk(Packet(PacketType.FILE_CHUNK, messageId = i.toLong(), payload = ByteArray(1024)))
        link.send(Packet(PacketType.TEXT, messageId = 999, payload = byteArrayOf(1)))

        val deadline = System.currentTimeMillis() + 5_000
        while (sink.written.size < 7 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(7, sink.written.size)
        val textIndex = sink.written.indexOfFirst { it.type == PacketType.TEXT }
        assertTrue("text should jump the bulk queue, was at index $textIndex", textIndex <= 2)
        link.close()
    }

    @Test
    fun bulkSenderSuspendsWhenTheQueueIsFull() = runBlocking {
        val sink = SlowSink(delayMs = 200)
        val link = link(sink).also { it.start() }
        val start = System.currentTimeMillis()
        for (i in 0 until Link.BULK_CAPACITY + 4) link.sendBulk(Packet(PacketType.FILE_CHUNK, messageId = i.toLong(), payload = ByteArray(16)))
        assertTrue("backpressure should have slowed the producer", System.currentTimeMillis() - start >= 150)
        link.close()
    }

    @Test
    fun concurrentSendersNeverInterleavePacketBytes() = runBlocking {
        val net = com.offlinechat.app.testutil.PipeNetwork()
        val a = net.transport("A")
        val b = net.transport("B")
        val received = Collections.synchronizedList(mutableListOf<Packet>())
        b.startListening(scope) { raw ->
            Link(raw, false, scope, Dispatchers.IO, Timings(pingMs = 60_000, linkTimeoutMs = 120_000), System::currentTimeMillis, { _, p -> received += p }, {}).start()
        }
        val sender = link(a.connect("B", true)).also { it.start() }
        val threads = (0 until 8).map { t ->
            Thread { repeat(200) { i -> sender.send(Packet(PacketType.TEXT, messageId = t * 1000L + i, payload = ByteArray(300) { t.toByte() })) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val deadline = System.currentTimeMillis() + 5_000
        while (received.size < 1600 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1600, received.size)
        received.forEach { p -> assertTrue(p.payload.all { it == p.payload[0] }) }
        sender.close()
    }
}
