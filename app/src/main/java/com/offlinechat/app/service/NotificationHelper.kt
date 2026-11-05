package com.offlinechat.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.offlinechat.app.MainActivity
import com.offlinechat.app.R
import com.offlinechat.app.data.AppPreferences
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.domain.JoinRequestItem
import com.offlinechat.app.domain.MessageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val history = ConcurrentHashMap<Long, ArrayDeque<NotificationCompat.MessagingStyle.Message>>()

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    fun serviceNotification(connected: Int): Notification {
        val text = when (connected) {
            0 -> "Ready. Waiting for nearby phones"
            1 -> "Connected to 1 phone"
            else -> "Connected to $connected phones"
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("OfflineChat is active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(null))
            .addAction(0, "Stop", ChatServiceActions.stopIntent(context))
            .build()
    }

    @SuppressLint("MissingPermission")
    fun updateService(connected: Int) {
        if (canPost()) manager.notify(ID_SERVICE, serviceNotification(connected))
    }

    @SuppressLint("MissingPermission")
    fun showMessage(conversation: Conversation, message: ChatMessage) {
        if (!preferences.settings.value.notifications || !canPost()) return
        val isGroup = conversation.type == ConversationType.GROUP
        val me = Person.Builder().setName("You").build()
        val sender = Person.Builder().setName(message.senderName.ifBlank { conversation.title }).setKey(message.senderId.toString()).build()
        val body = if (message.kind == MessageKind.FILE) "📎 ${message.fileName ?: "File"}" else message.text

        val lines = history.getOrPut(conversation.id) { ArrayDeque() }
        synchronized(lines) {
            lines.addLast(NotificationCompat.MessagingStyle.Message(body, message.timestamp, sender))
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(isGroup)
        if (isGroup) style.setConversationTitle(conversation.title)
        synchronized(lines) { lines.forEach { style.addMessage(it) } }

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setStyle(style)
            .setContentTitle(conversation.title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setGroup(GROUP_KEY)
            .setContentIntent(openApp(conversation.id))
            .build()
        manager.notify(notificationId(conversation.id), notification)

        val summary = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("OfflineChat")
            .setContentText("New messages")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(null))
            .build()
        manager.notify(ID_SUMMARY, summary)
    }

    @SuppressLint("MissingPermission")
    fun showJoinRequest(item: JoinRequestItem) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("${item.name} wants to join")
            .setContentText("Group: ${item.groupName}. Open OfflineChat to accept or decline.")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(null))
            .build()
        manager.notify(ID_JOIN_BASE + (item.requestId % 1000).toInt(), notification)
    }

    fun cancelConversation(conversationId: Long) {
        history.remove(conversationId)
        manager.cancel(notificationId(conversationId))
        if (history.isEmpty()) manager.cancel(ID_SUMMARY)
    }

    private fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun openApp(conversationId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        val requestCode = conversationId?.hashCode() ?: 0
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notificationId(conversationId: Long): Int = 1000 + (conversationId.hashCode() and 0x7FFFFF)

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        const val ID_SERVICE = 1
        private const val ID_SUMMARY = 2
        private const val ID_JOIN_BASE = 100
        private const val GROUP_KEY = "offlinechat_messages"
        private const val MAX_LINES = 6
    }
}
