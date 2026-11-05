package com.offlinechat.app.testutil

import com.offlinechat.app.domain.RawConnection
import com.offlinechat.app.domain.Transport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope

/** A one-directional in-memory byte pipe with blocking reads and EOF on close. */
class BytePipe(private val bytesPerSecond: () -> Long = { 0L }) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    private var current: ByteArray? = null
    private var offset = 0

    val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var chunk = current
            if (chunk == null || offset >= chunk.size) {
                chunk = queue.take()
                if (chunk === EOF) {
                    queue.put(EOF)
                    return -1
                }
                current = chunk
                offset = 0
            }
            val n = minOf(len, chunk.size - offset)
            System.arraycopy(chunk, offset, b, off, n)
            offset += n
            return n
        }
    }

    val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("Pipe closed")
            if (len > 0) {
                val bps = bytesPerSecond()
                if (bps > 0) Thread.sleep(maxOf(0L, len * 1000L / bps))
                queue.put(b.copyOfRange(off, off + len))
            }
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) queue.put(EOF)
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}

private class PipeConnection(
    override val remoteAddress: String,
    override val secure: Boolean,
    private val rx: BytePipe,
    private val tx: BytePipe,
    private val onClose: () -> Unit,
) : RawConnection {
    override val input: InputStream get() = rx.input
    override val output: OutputStream get() = tx.output

    override fun close() {
        rx.close()
        tx.close()
        onClose()
    }
}

/** Simulates Bluetooth between engines in one JVM. Devices can be taken out of range. */
class PipeNetwork {
    private val nodes = ConcurrentHashMap<String, PipeTransport>()
    private val unreachable = ConcurrentHashMap.newKeySet<String>()

    /** Simulated link speed; 0 means unlimited. Real Bluetooth Classic RFCOMM moves roughly 100 to 200 KB/s. */
    @Volatile var bytesPerSecond: Long = 0L

    fun transport(address: String): PipeTransport = PipeTransport(address, this).also { nodes[address] = it }

    fun setReachable(address: String, reachable: Boolean) {
        if (reachable) unreachable.remove(address) else unreachable.add(address)
    }

    /** Cuts every live connection of [address], as if the device walked away or crashed. */
    fun kill(address: String) {
        setReachable(address, false)
        nodes[address]?.dropAll()
    }

    internal fun lookup(address: String): PipeTransport? = nodes[address]?.takeIf { address !in unreachable }
    internal fun isUp(address: String) = address !in unreachable
}

class PipeTransport(private val address: String, private val network: PipeNetwork) : Transport {
    @Volatile private var onIncoming: ((RawConnection) -> Unit)? = null
    private val live = ConcurrentHashMap.newKeySet<RawConnection>()

    override fun startListening(scope: CoroutineScope, onIncoming: (RawConnection) -> Unit) {
        this.onIncoming = onIncoming
    }

    override fun stopListening() {
        onIncoming = null
    }

    override suspend fun connect(address: String, secure: Boolean): RawConnection {
        val remote = network.lookup(address)
        val handler = remote?.onIncoming
        if (remote == null || handler == null || !network.isUp(this.address)) throw IOException("Device $address unreachable")
        val aToB = BytePipe { network.bytesPerSecond }
        val bToA = BytePipe { network.bytesPerSecond }
        lateinit var local: PipeConnection
        lateinit var far: PipeConnection
        local = PipeConnection(address, secure, rx = bToA, tx = aToB) { live.remove(local) }
        far = PipeConnection(this.address, secure, rx = aToB, tx = bToA) { remote.live.remove(far) }
        live.add(local)
        remote.live.add(far)
        handler(far)
        return local
    }

    fun dropAll() {
        live.toList().forEach { runCatching { it.close() } }
    }
}
