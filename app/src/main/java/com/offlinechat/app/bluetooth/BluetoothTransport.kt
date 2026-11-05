package com.offlinechat.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.offlinechat.app.core.Protocol
import com.offlinechat.app.domain.RawConnection
import com.offlinechat.app.domain.Transport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bluetooth Classic (RFCOMM) byte pipes.
 *
 * Two server sockets are open at the same time:
 *  - a secure one, used when "Quick connect" is off (the phones must be paired, links are encrypted), and
 *  - an insecure one, used for quick connect and for the lightweight nearby probe (no pairing prompt).
 */
@Singleton
class BluetoothTransport @Inject constructor(private val controller: BluetoothController) : Transport {

    private var serverJob: Job? = null
    private val servers = ConcurrentHashMap.newKeySet<BluetoothServerSocket>()

    override fun startListening(scope: CoroutineScope, onIncoming: (RawConnection) -> Unit) {
        stopListening()
        serverJob = scope.launch(Dispatchers.IO) {
            launch { acceptLoop(secure = true, onIncoming = onIncoming) }
            launch { acceptLoop(secure = false, onIncoming = onIncoming) }
        }
    }

    override fun stopListening() {
        serverJob?.cancel()
        serverJob = null
        servers.toList().forEach { runCatching { it.close() } }
        servers.clear()
    }

    @SuppressLint("MissingPermission")
    private suspend fun CoroutineScope.acceptLoop(secure: Boolean, onIncoming: (RawConnection) -> Unit) {
        while (isActive) {
            controller.enabled.first { it }
            val adapter = controller.adapter
            if (adapter == null || !controller.hasConnectPermission()) {
                delay(RETRY_DELAY_MS)
                continue
            }
            val server = try {
                if (secure) {
                    adapter.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SECURE_UUID)
                } else {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.INSECURE_UUID)
                }
            } catch (e: IOException) {
                delay(RETRY_DELAY_MS)
                continue
            } catch (e: SecurityException) {
                delay(RETRY_DELAY_MS)
                continue
            }
            servers += server
            try {
                while (isActive) {
                    val socket = server.accept() // blocks until a device connects or the socket is closed
                    onIncoming(BluetoothRawConnection(socket, secure))
                }
            } catch (e: IOException) {
                // Closed by stopListening() or Bluetooth was switched off. The outer loop decides what happens next.
            } finally {
                servers -= server
                runCatching { server.close() }
            }
            delay(SHORT_DELAY_MS)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String, secure: Boolean): RawConnection = withContext(Dispatchers.IO) {
        if (!controller.hasConnectPermission()) throw IOException("Bluetooth permission is missing")
        val adapter = controller.adapter ?: throw IOException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid Bluetooth address")
        }
        var lastError: IOException? = null
        for (attempt in 1..2) {
            // A running discovery makes connecting slow and unreliable, so stop it first.
            try {
                adapter.cancelDiscovery()
            } catch (e: SecurityException) {
                throw IOException("Bluetooth permission is missing")
            }
            val socket: BluetoothSocket = try {
                if (secure) device.createRfcommSocketToServiceRecord(Protocol.SECURE_UUID)
                else device.createInsecureRfcommSocketToServiceRecord(Protocol.INSECURE_UUID)
            } catch (e: SecurityException) {
                throw IOException("Bluetooth permission is missing")
            }
            // Closing the socket is the only way to abort a blocking connect(), so tie it to cancellation.
            val job = coroutineContext[Job]
            val handle = job?.invokeOnCompletion(onCancelling = true) { cause ->
                if (cause != null) runCatching { socket.close() }
            }
            try {
                socket.connect()
                return@withContext BluetoothRawConnection(socket, secure)
            } catch (e: IOException) {
                runCatching { socket.close() }
                lastError = e
                // The first connection right after a scan often fails with "read failed"; a short pause fixes it.
                val transient = e.message?.contains("read failed", ignoreCase = true) == true ||
                    e.message?.contains("closed", ignoreCase = true) == true
                if (attempt == 2 || !transient) break
                delay(RETRY_PAUSE_MS)
            } catch (e: SecurityException) {
                runCatching { socket.close() }
                throw IOException("Bluetooth permission is missing")
            } finally {
                handle?.dispose()
            }
        }
        throw lastError ?: IOException("Could not connect")
    }

    private companion object {
        const val RETRY_DELAY_MS = 2_000L
        const val SHORT_DELAY_MS = 500L
        const val RETRY_PAUSE_MS = 400L
    }
}

class BluetoothRawConnection(private val socket: BluetoothSocket, override val secure: Boolean) : RawConnection {
    override val remoteAddress: String = socket.remoteDevice.address
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream

    override fun close() {
        runCatching { socket.close() }
    }
}
