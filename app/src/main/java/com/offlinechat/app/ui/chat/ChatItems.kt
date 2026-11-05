package com.offlinechat.app.ui.chat

import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.ui.util.dayLabel
import com.offlinechat.app.ui.util.localDate
import java.time.LocalDate

/** What the chat list actually shows: messages decorated with their place in a run, plus day headers and notices. */
sealed interface ChatItem {
    val key: String
    val contentType: Int

    data class DayHeader(val label: String, val dayKey: Long) : ChatItem {
        override val key get() = "day-$dayKey"
        override val contentType get() = TYPE_DAY
    }

    data class Notice(val id: Long, val text: String) : ChatItem {
        override val key get() = "notice-$id"
        override val contentType get() = TYPE_NOTICE
    }

    /**
     * [showSender]: draw the sender's coloured name (group chats, first bubble of a run).
     * [tail]: this is the last bubble of a run, so it gets the tail (and the avatar in groups).
     * [topGap]: this is the first bubble of a run, so it gets extra space above.
     */
    data class Message(
        val message: ChatMessage,
        val showSender: Boolean,
        val tail: Boolean,
        val topGap: Boolean,
    ) : ChatItem {
        override val key get() = "msg-${message.id}"
        override val contentType get() = if (message.kind == MessageKind.FILE) TYPE_FILE else TYPE_TEXT
    }

    companion object {
        const val TYPE_DAY = 0
        const val TYPE_NOTICE = 1
        const val TYPE_TEXT = 2
        const val TYPE_FILE = 3
    }
}

object ChatItems {
    private const val RUN_GAP_MS = 5 * 60 * 1000L

    /**
     * [messages] must be oldest first. The result is newest first, ready for a `reverseLayout` LazyColumn,
     * so the list opens at the latest message and stays pinned to it.
     */
    fun build(messages: List<ChatMessage>, isGroup: Boolean, today: LocalDate = LocalDate.now()): List<ChatItem> {
        val out = ArrayList<ChatItem>(messages.size + 8)
        var previousDay: LocalDate? = null
        for (i in messages.indices) {
            val m = messages[i]
            val day = localDate(m.timestamp)
            if (day != previousDay) {
                out += ChatItem.DayHeader(dayLabel(day, today), day.toEpochDay())
                previousDay = day
            }
            if (m.kind == MessageKind.SYSTEM) {
                out += ChatItem.Notice(m.id, m.text)
                continue
            }
            val prev = messages.getOrNull(i - 1)
            val next = messages.getOrNull(i + 1)
            val first = !continuesRun(prev, m)
            val last = !continuesRun(m, next)
            out += ChatItem.Message(
                message = m,
                showSender = isGroup && !m.outgoing && first,
                tail = last,
                topGap = first,
            )
        }
        out.reverse()
        return out
    }

    /** True when [b] directly continues the run that [a] belongs to. */
    private fun continuesRun(a: ChatMessage?, b: ChatMessage?): Boolean {
        if (a == null || b == null) return false
        if (a.kind == MessageKind.SYSTEM || b.kind == MessageKind.SYSTEM) return false
        if (a.senderId != b.senderId || a.outgoing != b.outgoing) return false
        if (localDate(a.timestamp) != localDate(b.timestamp)) return false
        return kotlin.math.abs(b.timestamp - a.timestamp) <= RUN_GAP_MS
    }
}
