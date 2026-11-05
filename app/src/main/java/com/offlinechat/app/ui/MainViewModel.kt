package com.offlinechat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.data.UserSettings
import com.offlinechat.app.domain.EngineEvent
import com.offlinechat.app.domain.JoinRequestItem
import com.offlinechat.app.engine.ChatEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val engine: ChatEngine,
    private val preferences: AppPreferences,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = preferences.settings
    val pendingJoins: StateFlow<List<JoinRequestItem>> = engine.pendingJoins

    /** Short engine notices ("Asha left the group") shown as snackbars. */
    val notices: Flow<String> = engine.events.filterIsInstance<EngineEvent.Notice>().map { it.text }

    fun respond(request: JoinRequestItem, accept: Boolean) {
        viewModelScope.launch { engine.respondJoin(request.requestId, accept) }
    }
}
