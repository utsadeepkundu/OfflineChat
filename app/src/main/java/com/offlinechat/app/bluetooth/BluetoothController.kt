package com.offlinechat.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bluetooth adapter state, permissions and discoverability in one place. */
@Singleton
class BluetoothController @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

    val adapter: BluetoothAdapter? get() = manager?.adapter

    val isSupported: Boolean get() = adapter != null

    private val _enabled = MutableStateFlow(adapter?.isEnabled == true)

    /** True while Bluetooth is switched on. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _visible = MutableStateFlow(false)

    /** True while this phone can be found by others (classic discoverable mode). */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _enabled.value = state == BluetoothAdapter.STATE_ON
                    if (state != BluetoothAdapter.STATE_ON) _visible.value = false
                }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    val mode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR)
                    _visible.value = mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                }
            }
        }
    }

    /** Starts listening to adapter broadcasts. Safe to call more than once. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun register() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        _enabled.value = adapter?.isEnabled == true
        if (hasScanPermission()) {
            _visible.value = runCatching { adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }.getOrDefault(false)
        }
    }

    /** Permissions the app asks for in one go. */
    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN) else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasAdvertisePermission(): Boolean = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_ADVERTISE)

    /** Everything needed to chat. Notifications are optional and not part of this check. */
    fun hasBluetoothPermissions(): Boolean = hasConnectPermission() && hasScanPermission() && hasAdvertisePermission()

    fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    @SuppressLint("MissingPermission")
    fun bluetoothName(): String? = if (hasConnectPermission()) runCatching { adapter?.name }.getOrNull() else null

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
