package com.offlinechat.app.net

import com.offlinechat.app.core.Hello
import com.offlinechat.app.core.Packet
import com.offlinechat.app.core.PacketCodec
import com.offlinechat.app.core.PacketType
import com.offlinechat.app.core.Timings
import com.offlinechat.app.domain.RawConnection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * One live connection to one peer. A peer is reachable over exactly one Link, which carries both
 * direct chats and every group the two devices share.
 *
 * Writes go through two queues drained by a single writer coroutine, so packets from concurrent
 * senders can never interleave on the socket. The control queue (text, acks, membership) always wins
 * over the bounded bulk queue (file chunks), which keeps messages snappy during a transfer and
 * gives file senders natural backpressure.
 */
class Link(
    val raw: RawConnection,
    val outgoing: Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val timings: Timings,
    private val clock: () -> Long,
    private val dispatch: suspend (Link, Packet) -> Unit,
    private val onClosed: (Link) -> Unit,
) {
    @Volatile var peerId: Long = 0L
    @Volatile var peerName: String = ""
    @Volatile var ready: Boolean = false
    @Volatile var probeOnly: Boolean = false
    @Volatile var hello: Hello? = null

    /** Completed with the peer's HELLO; fails if the link dies first. */
    val handshake = CompletableDeferred<Hello>()

    val remoteAddress: String get() = raw.remoteAddress

    private val control = Channel<Packet>(Channel.UNLIMITED)
    private val bulk = Channel<Packet>(BULK_CAPACITY)
    private val closedFlag = AtomicBoolean(false)
    private val jobs = ArrayList<Job>(3)
    @Volatile private var lastRx = clock()

    val isAlive: Boolean get() = !closedFlag.get()

    fun start() {
        jobs += scope.launch(io) { readLoop() }
        jobs += scope.launch(io) { writeLoop() }
        jobs += scope.launch { keepAlive() }
    }

    /** Queues a packet on the priority lane. Never blocks. Returns false if the link is gone. */
    fun send(packet: Packet): Boolean = isAlive && control.trySend(packet).isSuccess

    /** Queues a file chunk; suspends while the bulk lane is full (backpressure). */
    suspend fun sendBulk(packet: Packet) {
        if (!isAlive) throw IOException("Link closed")
        try {
            bulk.send(packet)
        } catch (e: ClosedSendChannelException) {
            throw IOException("Link closed")
        }
    }

    /** Lets already-queued packets drain before closing. */
    fun closeSoon() {
        scope.launch {
            delay(timings.gracefulCloseMs)
            close()
        }
    }

    fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        control.close()
        bulk.close()
        runCatching { raw.close() }
        handshake.completeExceptionally(IOException("Link closed"))
        jobs.forEach { it.cancel() }
        onClosed(this)
    }

    private suspend fun readLoop() {
        val input = BufferedInputStream(raw.input, 16 * 1024)
        try {
            while (isAlive) {
                val packet = PacketCodec.read(input)
                lastRx = clock()
                if (packet.type == PacketType.PING || packet.type == PacketType.UNKNOWN) continue
                try {
                    dispatch(this, packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A malformed or unexpected packet must never take the connection down.
                }
            }
        } catch (e: Exception) {
            // Socket closed or protocol violation: fall through to close().
        } finally {
            close()
        }
    }

    private suspend fun writeLoop() {
        val out = BufferedOutputStream(raw.output, 16 * 1024)
        try {
            while (true) {
                val packet = control.tryReceive().getOrNull()
                    ?: select<Packet> {
                        control.onReceive { it }
                        bulk.onReceive { it }
                    }
                out.write(PacketCodec.encode(packet))
                out.flush()
            }
        } catch (e: Exception) {
            // Closed channel or broken socket.
        } finally {
            close()
        }
    }

    private suspend fun keepAlive() {
        while (isAlive) {
            delay(timings.pingMs)
            if (clock() - lastRx > timings.linkTimeoutMs) {
                close()
                return
            }
            send(Packet(PacketType.PING))
        }
    }

    companion object {
        const val BULK_CAPACITY = 8
    }
}
