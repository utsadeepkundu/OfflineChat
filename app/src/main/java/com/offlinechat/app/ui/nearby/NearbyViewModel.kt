package com.offlinechat.app.ui.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.app.bluetooth.BluetoothController
import com.offlinechat.app.bluetooth.DiscoveryManager
import com.offlinechat.app.bluetooth.NearbyDevice
import com.offlinechat.app.core.GroupAd
import com.offlinechat.app.core.RejectReason
import com.offlinechat.app.domain.JoinOutcome
import com.offlinechat.app.engine.ChatEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NearbyEvent {
    data class OpenChat(val conversationId: Long) : NearbyEvent
    data class Message(val text: String) : NearbyEvent
}

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val discovery: DiscoveryManager,
    private val engine: ChatEngine,
    val controller: BluetoothController,
) : ViewModel() {

    val devices: StateFlow<List<NearbyDevice>> = discovery.devices
    val scanning: StateFlow<Boolean> = discovery.scanning
    val error: StateFlow<String?> = discovery.error
    val visible: StateFlow<Boolean> = controller.visible

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Addresses (or "address/groupId" for a join) with an action in flight. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    fun scan() = discovery.startScan()
    fun stop() = discovery.stopScan()
    fun clearError() = discovery.clearError()
    fun recheck(address: String) = discovery.probe(address)

    fun chat(device: NearbyDevice) = guarded(device.address) {
        try {
            val conversation = engine.connectDirect(device.address)
            _events.emit(NearbyEvent.OpenChat(conversation.id))
        } catch (e: java.io.IOException) {
            _events.emit(NearbyEvent.Message("Could not reach ${device.chatName ?: device.name.ifBlank { "that phone" }}. Make sure OfflineChat is open there and it is close by."))
        }
    }

    fun join(device: NearbyDevice, group: GroupAd) = guarded("${device.address}/${group.groupId}") {
        when (val outcome = engine.joinGroup(device.address, group.groupId)) {
            is JoinOutcome.Joined -> _events.emit(NearbyEvent.OpenChat(outcome.groupId))
            is JoinOutcome.Rejected -> _events.emit(NearbyEvent.Message(rejectText(outcome.reason, group.name)))
            is JoinOutcome.Failed -> _events.emit(NearbyEvent.Message(outcome.message))
        }
    }

    private fun rejectText(reason: Int, name: String) = when (reason) {
        RejectReason.FULL -> "\"$name\" is full."
        RejectReason.DECLINED -> "The host declined your request to join \"$name\"."
        RejectReason.NOT_HOST -> "That phone is no longer hosting \"$name\"."
        else -> "\"$name\" is no longer available."
    }

    private fun guarded(key: String, block: suspend () -> Unit) {
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.update { it - key }
            }
        }
    }

    override fun onCleared() {
        discovery.stopScan()
    }
}
