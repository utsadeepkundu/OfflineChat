package com.offlinechat.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.di.AppScope
import com.offlinechat.app.domain.EngineEvent
import com.offlinechat.app.engine.ChatEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object ChatServiceActions {
    const val ACTION_STOP = "com.offlinechat.app.STOP"

    fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, ChatService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * Foreground service: keeps Bluetooth links and the engine alive with the screen off and shows message notifications.
 * Its lifetime is the engine's lifetime.
 */
@AndroidEntryPoint
class ChatService : Service() {
    @Inject lateinit var engine: ChatEngine
    @Inject lateinit var notifications: NotificationHelper
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var launcher: EngineLauncher
    @Inject @field:AppScope lateinit var appScope: CoroutineScope

    private var watchers: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.ID_SERVICE,
                notifications.serviceNotification(0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Missing Bluetooth permission (Android 14+) or background start restriction.
            stopSelf()
            return
        }
        launcher.ensureStarted()
        watchers = appScope.launch {
            launch { engine.connectedPeers.collectLatest { notifications.updateService(it.size) } }
            launch {
                engine.events.collect { event ->
                    when (event) {
                        is EngineEvent.IncomingMessage -> {
                            // While the app is on screen the unread badges and the open chat already show the message.
                            if (!AppVisibility.inForeground) notifications.showMessage(event.conversation, event.message)
                        }
                        is EngineEvent.JoinRequested -> if (!AppVisibility.inForeground) notifications.showJoinRequest(event.request)
                        is EngineEvent.Notice -> Unit
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ChatServiceActions.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchers?.cancel()
        launcher.stop()
        super.onDestroy()
    }
}
