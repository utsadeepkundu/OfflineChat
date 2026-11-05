package com.offlinechat.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.offlinechat.app.core.GroupAd
import com.offlinechat.app.di.AppScope
import com.offlinechat.app.engine.ChatEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ProbeState { UNKNOWN, PROBING, CHAT, NO_CHAT }

data class NearbyDevice(
    val address: String,
    val name: String,
    val rssi: Int?,
    val paired: Boolean,
    val major: Int,
    val state: ProbeState = ProbeState.UNKNOWN,
    val chatName: String? = null,
    val groups: List<GroupAd> = emptyList(),
) {
    /** 1 (weak) to 4 (strong). */
    val signalBars: Int
        get() = when {
            rssi == null -> 0
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            else -> 1
        }
}

/**
 * Finds nearby phones. Classic discovery lists every visible Bluetooth device; each plausible phone is then
 * probed once (a tiny handshake, no pairing) to learn whether it runs OfflineChat and which groups it hosts.
 * Probing is sequential because Bluetooth connects are slow and fail when several run together.
 */
@Singleton
class DiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: BluetoothController,
    private val engine: ChatEngine,
    @AppScope private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val known = LinkedHashMap<String, NearbyDevice>()

    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var scanJob: Job? = null

    fun clearError() {
        _error.value = null
    }

    fun startScan() {
        if (_scanning.value) return
        _error.value = null
        when {
            !controller.isSupported -> _error.value = "This device has no Bluetooth."
            !controller.hasBluetoothPermissions() -> _error.value = "Bluetooth permissions are needed to look for nearby phones."
            !controller.enabled.value -> _error.value = "Turn Bluetooth on to look for nearby phones."
            else -> {
                _scanning.value = true
                scanJob = scope.launch {
                    try {
                        runScan()
                    } finally {
                        _scanning.value = false
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
        runCatching { if (controller.hasScanPermission()) controller.adapter?.cancelDiscovery() }
    }

    /** Re-checks a single device, for example a paired phone that did not show up in the scan. */
    fun probe(address: String) {
        scope.launch { probeOne(address) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runScan() {
        val adapter = controller.adapter ?: return
        synchronized(lock) { known.clear() }
        publish()
        seedPairedDevices()

        val finished = CompletableDeferred<Unit>()
        val found = LinkedHashSet<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = deviceExtra(intent) ?: return
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                            .takeIf { it != Short.MIN_VALUE.toInt() }
                        synchronized(found) { found += device.address }
                        onDeviceFound(device, rssi)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished.complete(Unit)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            if (!adapter.startDiscovery()) {
                _error.value = "Bluetooth could not start scanning. Try again in a moment."
                return
            }
            withTimeoutOrNull(DISCOVERY_MAX_MS) { finished.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { adapter.cancelDiscovery() }
        }

        val toProbe = synchronized(lock) { known.values.toList() }
            .filter { synchronized(found) { it.address in found } && worthProbing(it) }
            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            .take(MAX_PROBES)
        for (device in toProbe) probeOne(device.address)
    }

    @SuppressLint("MissingPermission")
    private fun seedPairedDevices() {
        if (!controller.hasConnectPermission()) return
        val bonded = runCatching { controller.adapter?.bondedDevices }.getOrNull().orEmpty()
        synchronized(lock) {
            for (d in bonded) {
                known.putIfAbsent(
                    d.address,
                    NearbyDevice(
                        address = d.address,
                        name = safeName(d),
                        rssi = null,
                        paired = true,
                        major = d.bluetoothClass?.majorDeviceClass ?: BluetoothClass.Device.Major.UNCATEGORIZED,
                    ),
                )
            }
        }
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceFound(device: BluetoothDevice, rssi: Int?) {
        synchronized(lock) {
            val old = known[device.address]
            known[device.address] = NearbyDevice(
                address = device.address,
                name = safeName(device).ifBlank { old?.name.orEmpty() },
                rssi = rssi ?: old?.rssi,
                paired = device.bondState == BluetoothDevice.BOND_BONDED,
                major = device.bluetoothClass?.majorDeviceClass ?: BluetoothClass.Device.Major.UNCATEGORIZED,
                state = old?.state ?: ProbeState.UNKNOWN,
                chatName = old?.chatName,
                groups = old?.groups.orEmpty(),
            )
        }
        publish()
    }

    private suspend fun probeOne(address: String) {
        update(address) { it.copy(state = ProbeState.PROBING) }
        val hello = withTimeoutOrNull(PROBE_TIMEOUT_MS) { engine.probe(address) }
        update(address) {
            if (hello == null) it.copy(state = ProbeState.NO_CHAT, chatName = null, groups = emptyList())
            else it.copy(state = ProbeState.CHAT, chatName = hello.name, groups = hello.groups)
        }
    }

    private fun update(address: String, change: (NearbyDevice) -> NearbyDevice) {
        synchronized(lock) { known[address]?.let { known[address] = change(it) } }
        publish()
    }

    private fun publish() {
        val list = synchronized(lock) { known.values.toList() }
        _devices.update {
            list.sortedWith(
                compareByDescending<NearbyDevice> { it.state == ProbeState.CHAT }
                    .thenByDescending { it.rssi != null }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
        }
    }

    /** Skips headphones, watches, keyboards and the like. Unknown device classes are still tried. */
    private fun worthProbing(d: NearbyDevice): Boolean = when (d.major) {
        BluetoothClass.Device.Major.AUDIO_VIDEO,
        BluetoothClass.Device.Major.PERIPHERAL,
        BluetoothClass.Device.Major.IMAGING,
        BluetoothClass.Device.Major.WEARABLE,
        BluetoothClass.Device.Major.TOY,
        BluetoothClass.Device.Major.HEALTH,
        BluetoothClass.Device.Major.NETWORKING,
        -> false
        else -> true
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        if (controller.hasConnectPermission()) runCatching { device.name }.getOrNull().orEmpty() else ""

    @Suppress("DEPRECATION")
    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private companion object {
        const val DISCOVERY_MAX_MS = 14_000L
        const val PROBE_TIMEOUT_MS = 9_000L
        const val MAX_PROBES = 8
    }
}
