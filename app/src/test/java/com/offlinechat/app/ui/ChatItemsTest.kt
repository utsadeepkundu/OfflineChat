package com.offlinechat.app.ui

import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus
import com.offlinechat.app.ui.chat.ChatItem
import com.offlinechat.app.ui.chat.ChatItems
import com.offlinechat.app.ui.util.formatSize
import com.offlinechat.app.ui.util.initials
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatItemsTest {
    private val zone = ZoneId.systemDefault()
    private val day1 = LocalDate.of(2026, 3, 10).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun msg(id: Long, sender: Long, at: Long, outgoing: Boolean = false, kind: MessageKind = MessageKind.TEXT) = ChatMessage(
        id = id, conversationId = 1, senderId = sender, senderName = "S$sender", kind = kind, text = "t$id",
        status = MessageStatus.DELIVERED, outgoing = outgoing, timestamp = at, sortKey = at,
    )

    private fun messages(items: List<ChatItem>) = items.filterIsInstance<ChatItem.Message>()

    @Test
    fun resultIsNewestFirst() {
        val items = ChatItems.build(listOf(msg(1, 7, day1), msg(2, 7, day1 + minute)), isGroup = false, today = LocalDate.of(2026, 3, 10))
        assertEquals(listOf(2L, 1L), messages(items).map { it.message.id })
        assertTrue(items.last() is ChatItem.DayHeader)
    }

    @Test
    fun consecutiveMessagesFromOnePersonFormARunWithOneTail() {
        val items = messages(ChatItems.build(listOf(msg(1, 7, day1), msg(2, 7, day1 + minute), msg(3, 7, day1 + 2 * minute)), true))
        val byId = items.associateBy { it.message.id }
        assertTrue(byId[1]!!.topGap && !byId[1]!!.tail && byId[1]!!.showSender)
        assertTrue(!byId[2]!!.topGap && !byId[2]!!.tail && !byId[2]!!.showSender)
        assertTrue(!byId[3]!!.topGap && byId[3]!!.tail && !byId[3]!!.showSender)
    }

    @Test
    fun speakerChangeAndLongPausesStartNewRuns() {
        val items = messages(ChatItems.build(listOf(msg(1, 7, day1), msg(2, 8, day1 + minute), msg(3, 8, day1 + 20 * minute)), true))
        val byId = items.associateBy { it.message.id }
        assertTrue(byId[1]!!.tail)
        assertTrue(byId[2]!!.topGap && byId[2]!!.tail)
        assertTrue(byId[3]!!.topGap && byId[3]!!.tail)
    }

    @Test
    fun ownMessagesNeverShowASenderName() {
        val items = messages(ChatItems.build(listOf(msg(1, 7, day1, outgoing = true)), true))
        assertFalse(items.single().showSender)
        val direct = messages(ChatItems.build(listOf(msg(2, 8, day1)), isGroup = false))
        assertFalse(direct.single().showSender)
    }

    @Test
    fun dayHeadersAppearOncePerDayAndSayTodayOrYesterday() {
        val today = LocalDate.of(2026, 3, 10)
        val yesterday = today.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val items = ChatItems.build(listOf(msg(1, 7, yesterday), msg(2, 7, day1), msg(3, 7, day1 + minute)), false, today)
        val headers = items.filterIsInstance<ChatItem.DayHeader>().map { it.label }
        assertEquals(listOf("Today", "Yesterday"), headers)
    }

    @Test
    fun systemMessagesBreakRunsAndBecomeNotices() {
        val items = ChatItems.build(
            listOf(msg(1, 7, day1), msg(2, 0, day1 + minute, kind = MessageKind.SYSTEM), msg(3, 7, day1 + 2 * minute)), true,
        )
        assertEquals(1, items.count { it is ChatItem.Notice })
        val byId = messages(items).associateBy { it.message.id }
        assertTrue(byId[1]!!.tail && byId[3]!!.topGap)
    }

    @Test
    fun itemKeysAreUnique() {
        val list = (1L..50L).map { msg(it, if (it % 3 == 0L) 8 else 7, day1 + it * minute) }
        val keys = ChatItems.build(list, true).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun formattingHelpers() {
        assertEquals("AR", initials("asha rao"))
        assertEquals("B", initials("bipin"))
        assertEquals("?", initials("  "))
        assertEquals("512 B", formatSize(512))
        assertEquals("2 KB", formatSize(2048))
        assertEquals("1.5 MB", formatSize(1_572_864))
    }
}
