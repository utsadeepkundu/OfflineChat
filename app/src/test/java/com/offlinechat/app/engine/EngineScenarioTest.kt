package com.offlinechat.app.engine

import com.offlinechat.app.core.RejectReason
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.domain.JoinOutcome
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus
import com.offlinechat.app.testutil.PipeNetwork
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineScenarioTest {
    private val net = PipeNetwork()
    private val devices = mutableListOf<Device>()

    private fun device(address: String, id: Long, name: String, settings: TestSettings = TestSettings()): Device =
        Device(net, address, id, name, settings).start().also { devices += it }

    @After
    fun tearDown() = devices.forEach { it.stop() }

    // ------------------------------------------------------------------ direct chat

    @Test
    fun directChatDeliversBothWaysAndMarksDelivered() = runBlocking {
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val conv = a.engine.connectDirect("BB")
        assertEquals(20L, conv.id)
        assertEquals("Bipin", conv.title)

        a.engine.sendText(conv.id, "hello bipin")
        eventually(message = "B receives") { b.store.texts(10) == listOf("hello bipin") }
        eventually(message = "A sees delivered ack") {
            runBlocking { a.store.all(20).single { it.kind == MessageKind.TEXT }.status == MessageStatus.DELIVERED }
        }

        b.engine.sendText(10, "hi asha")
        eventually(message = "A receives") { a.store.texts(20).contains("hi asha") }
        assertEquals(listOf("hello bipin", "hi asha"), a.store.texts(20))
        assertEquals(1, b.store.conversations().first { it.id == 10L }.unread)
    }

    @Test
    fun probeListsHostedGroupsWithoutCreatingAChat() = runBlocking {
        val host = device("HH", 50, "Host")
        val seeker = device("SS", 60, "Seeker")
        val group = host.engine.createGroup("Trek", 0xFF0000)
        val hello = seeker.engine.probe("HH")
        assertNotNull(hello)
        assertEquals("Host", hello!!.name)
        assertEquals(listOf(group.id), hello.groups.map { it.groupId })
        assertEquals(1, hello.groups[0].members)
        assertTrue(seeker.store.conversations().isEmpty())
    }

    @Test
    fun unreachableDeviceFailsFastWithAnError() = runBlocking {
        val a = device("AA", 10, "Asha")
        try {
            a.engine.connectDirect("ZZ")
            throw AssertionError("expected failure")
        } catch (e: java.io.IOException) {
            // expected
        }
    }

    @Test
    fun messageSentWhileOfflineFailsThenCanBeRetried() = runBlocking {
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val conv = a.engine.connectDirect("BB")
        a.engine.sendText(conv.id, "first")
        eventually { b.store.texts(10) == listOf("first") }

        net.kill("BB")
        eventually(message = "link down") { 20L !in a.engine.connectedPeers.value }
        a.engine.sendText(conv.id, "while away")
        eventually(message = "marked failed") {
            runBlocking { a.store.all(20).last { it.kind == MessageKind.TEXT }.status == MessageStatus.FAILED }
        }

        net.setReachable("BB", true)
        val failed = a.store.all(20).last { it.kind == MessageKind.TEXT }
        a.engine.retryMessage(failed.id)
        eventually(message = "retry delivered") { b.store.texts(10).contains("while away") }
        eventually { runBlocking { a.store.getMessage(failed.id)!!.status == MessageStatus.DELIVERED } }
        assertEquals(1, b.store.texts(10).count { it == "while away" })
    }

    // ------------------------------------------------------------------- groups

    private suspend fun joinAll(host: Device, groupId: Long, vararg members: Device) {
        for (m in members) {
            val outcome = m.engine.joinGroup(host.address, groupId)
            assertEquals(JoinOutcome.Joined(groupId), outcome)
        }
    }

    @Test
    fun everyoneSeesTheSameOrderWhenAllSendAtOnce() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Trek", 1, 5)
        joinAll(host, group.id, a, b)

        val jobs = listOf(host, a, b).flatMap { d -> (1..15).map { i -> d to "${d.name}-$i" } }
        jobs.map { (d, t) -> async { d.engine.sendText(group.id, t) } }.awaitAll()

        eventually(timeoutMs = 10_000, message = "all 45 messages everywhere") {
            listOf(host, a, b).all { it.store.texts(group.id).size == 45 }
        }
        val hostOrder = host.store.texts(group.id)
        assertEquals(hostOrder, a.store.texts(group.id))
        assertEquals(hostOrder, b.store.texts(group.id))
        // Each sender's own messages stay in the order it sent them.
        for (d in listOf(host, a, b)) {
            val mine = hostOrder.filter { it.startsWith(d.name) }
            assertEquals(mine.sortedBy { it.substringAfter('-').toInt() }, mine)
        }
        eventually(message = "member messages acked") {
            runBlocking { a.store.all(group.id).filter { it.outgoing && it.kind == MessageKind.TEXT }.all { it.status == MessageStatus.DELIVERED } }
        }
    }

    @Test
    fun lateJoinerReceivesRecentHistory() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val late = device("LL", 30, "Late")
        val group = host.engine.createGroup("Trek", 1)
        joinAll(host, group.id, a)
        for (i in 1..5) host.engine.sendText(group.id, "old-$i")
        eventually { a.store.texts(group.id).size == 5 }

        late.engine.joinGroup("HH", group.id)
        eventually(message = "history arrived") { late.store.texts(group.id) == (1..5).map { "old-$it" } }
        eventually(message = "members synced") { late.store.memberList(group.id).size == 3 }
    }

    @Test
    fun historyIsCappedToTheConfiguredCount() = runBlocking {
        val host = device("HH", 50, "Host", TestSettings(historyCount = 3))
        val late = device("LL", 30, "Late")
        val group = host.engine.createGroup("Trek", 1)
        for (i in 1..10) host.engine.sendText(group.id, "m$i")
        late.engine.joinGroup("HH", group.id)
        eventually { late.store.texts(group.id) == listOf("m8", "m9", "m10") }
    }

    @Test
    fun fullGroupRejectsNewcomersButLetsMembersRejoin() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Duo", 1, maxMembers = 2)
        joinAll(host, group.id, a)

        assertEquals(JoinOutcome.Rejected(RejectReason.FULL), b.engine.joinGroup("HH", group.id))
        assertNull(b.store.getConversation(group.id))
        assertEquals(JoinOutcome.Joined(group.id), a.engine.joinGroup("HH", group.id))
    }

    @Test
    fun hostCanDeclineAndAcceptJoinRequests() = runBlocking {
        val host = device("HH", 50, "Host", TestSettings(autoAcceptJoins = false))
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Private", 1)

        val first = async(Dispatchers.Default) { a.engine.joinGroup("HH", group.id) }
        eventually(message = "request visible") { host.engine.pendingJoins.value.size == 1 }
        host.engine.respondJoin(host.engine.pendingJoins.value[0].requestId, accept = false)
        assertEquals(JoinOutcome.Rejected(RejectReason.DECLINED), first.await())

        val second = async(Dispatchers.Default) { b.engine.joinGroup("HH", group.id) }
        eventually(message = "second request visible") { host.engine.pendingJoins.value.size == 1 }
        assertEquals("Bipin", host.engine.pendingJoins.value[0].name)
        host.engine.respondJoin(host.engine.pendingJoins.value[0].requestId, accept = true)
        assertEquals(JoinOutcome.Joined(group.id), second.await())
        assertTrue(host.engine.pendingJoins.value.isEmpty())
    }

    @Test
    fun unansweredJoinRequestExpiresAsDeclined() = runBlocking {
        val host = device("HH", 50, "Host", TestSettings(autoAcceptJoins = false))
        val a = device("AA", 10, "Asha")
        val group = host.engine.createGroup("Slow", 1)
        val outcome = a.engine.joinGroup("HH", group.id)
        assertEquals(JoinOutcome.Rejected(RejectReason.DECLINED), outcome)
        assertTrue(host.engine.pendingJoins.value.isEmpty())
    }

    @Test
    fun joiningADeviceThatIsNotHostingIsRefused() = runBlocking {
        val notHost = device("HH", 50, "Plain")
        val a = device("AA", 10, "Asha")
        assertEquals(JoinOutcome.Rejected(RejectReason.NOT_HOST), a.engine.joinGroup("HH", 12345L))
    }

    @Test
    fun memberLeavingNotifiesEveryoneAndFreesTheSeat() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Trek", 1, 3)
        joinAll(host, group.id, a, b)
        eventually { b.store.memberList(group.id).size == 3 }

        a.engine.leaveConversation(group.id)
        assertNull(a.store.getConversation(group.id))
        eventually(message = "host removed her") { host.store.memberList(group.id).none { it.peerId == 10L } }
        eventually(message = "other member told") { b.store.memberList(group.id).none { it.peerId == 10L } }
        eventually(message = "leave notice") { b.store.all(group.id).any { it.kind == MessageKind.SYSTEM && it.text == "Asha left" } }

        val c = device("CC", 30, "Chandra")
        assertEquals(JoinOutcome.Joined(group.id), c.engine.joinGroup("HH", group.id))
    }

    @Test
    fun typingIndicatorsReachEveryMemberAndExpire() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Trek", 1)
        joinAll(host, group.id, a, b)

        a.engine.setTyping(group.id, true)
        eventually(message = "host sees typing") { host.engine.typing.value[group.id] == listOf("Asha") }
        eventually(message = "other member sees typing") { b.engine.typing.value[group.id] == listOf("Asha") }
        eventually(message = "typing expires") { b.engine.typing.value[group.id] == null }
    }

    // -------------------------------------------------------------------- files

    @Test
    fun directFileArrivesIntactWithProgress() = runBlocking {
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val conv = a.engine.connectDirect("BB")
        val file = a.makeFile("photo.bin", 700_000)

        a.engine.sendFile(conv.id, file)
        eventually(timeoutMs = 20_000, message = "file delivered") {
            runBlocking { b.store.all(10).any { it.kind == MessageKind.FILE && it.status == MessageStatus.DELIVERED && it.filePath != null } }
        }
        val received = b.store.all(10).single { it.kind == MessageKind.FILE }
        assertArrayEquals(file.file.readBytes(), File(received.filePath!!).readBytes())
        eventually(message = "sender marked delivered") {
            runBlocking { a.store.all(20).single { it.kind == MessageKind.FILE }.status == MessageStatus.DELIVERED }
        }
        assertTrue(a.engine.transfers.value.isEmpty())
        assertTrue(b.engine.transfers.value.isEmpty())
    }

    @Test
    fun oversizedFilesAreRefusedBeforeSending() = runBlocking {
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val conv = a.engine.connectDirect("BB")
        val tooBig = a.makeFile("big.bin", 10 * 1024 * 1024 + 1)
        a.engine.sendFile(conv.id, tooBig)
        Thread.sleep(300)
        assertTrue(b.store.all(10).none { it.kind == MessageKind.FILE })
        assertTrue(a.store.all(20).none { it.kind == MessageKind.FILE })
    }

    @Test
    fun groupFileFromMemberIsRelayedByTheHostToEveryoneElse() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Trek", 1)
        joinAll(host, group.id, a, b)

        val file = a.makeFile("notes.pdf", 300_000, seed = 7)
        a.engine.sendFile(group.id, file)

        for (receiver in listOf(host, b)) {
            eventually(timeoutMs = 20_000, message = "${receiver.name} got file") {
                runBlocking { receiver.store.all(group.id).any { it.kind == MessageKind.FILE && it.status == MessageStatus.DELIVERED && it.filePath != null } }
            }
            val msg = receiver.store.all(group.id).single { it.kind == MessageKind.FILE }
            assertArrayEquals(file.file.readBytes(), File(msg.filePath!!).readBytes())
            assertEquals("Asha", msg.senderName)
        }
        eventually(message = "sender delivered") {
            runBlocking { a.store.all(group.id).single { it.kind == MessageKind.FILE }.status == MessageStatus.DELIVERED }
        }
    }

    @Test
    fun groupFileFromHostReachesAllMembers() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Trek", 1)
        joinAll(host, group.id, a, b)

        val file = host.makeFile("map.bin", 120_000, seed = 3)
        host.engine.sendFile(group.id, file)
        for (m in listOf(a, b)) {
            eventually(timeoutMs = 20_000, message = "${m.name} got file") {
                runBlocking { m.store.all(group.id).any { it.kind == MessageKind.FILE && it.filePath != null } }
            }
            assertArrayEquals(file.file.readBytes(), File(m.store.all(group.id).single { it.kind == MessageKind.FILE }.filePath!!).readBytes())
        }
    }

    @Test
    fun textStaysResponsiveWhileAFileIsInFlight() = runBlocking {
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        net.bytesPerSecond = 400_000 // roughly Bluetooth speed, so a 2 MB file takes about 5 s
        val conv = a.engine.connectDirect("BB")
        a.engine.sendFile(conv.id, a.makeFile("video.bin", 2_000_000))
        eventually(message = "transfer started") { a.engine.transfers.value.isNotEmpty() }
        val sentAt = System.currentTimeMillis()
        a.engine.sendText(conv.id, "ping during transfer")
        eventually(timeoutMs = 2_000, message = "text arrives while the file is still in flight") { b.store.texts(10).contains("ping during transfer") }
        val latency = System.currentTimeMillis() - sentAt
        assertTrue("text took ${latency}ms behind a file", latency < 1_500)
        assertTrue("file should still be transferring", b.engine.transfers.value.isNotEmpty())
    }

    // ---------------------------------------------------------------- failover

    @Test
    fun hostLeavingHandsTheGroupToTheLowestIdMember() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val group = host.engine.createGroup("Trek", 1, 4)
        joinAll(host, group.id, a, b)
        host.engine.sendText(group.id, "before")
        eventually { a.store.texts(group.id) == listOf("before") && b.store.texts(group.id) == listOf("before") }
        eventually { a.store.memberList(group.id).size == 3 && b.store.memberList(group.id).size == 3 }

        host.engine.leaveConversation(group.id)

        eventually(message = "Asha became host") { runBlocking { a.store.getConversation(group.id)?.isHost == true } }
        eventually(message = "Bipin follows Asha") {
            runBlocking { b.store.getConversation(group.id)?.hostId == 10L } && 10L in (b.engine.onlineMembers.value[group.id] ?: emptySet())
        }
        b.engine.sendText(group.id, "after migration")
        a.engine.sendText(group.id, "hello from new host")
        eventually(message = "messages flow in the new group") {
            a.store.texts(group.id).containsAll(listOf("before", "after migration")) &&
                b.store.texts(group.id).containsAll(listOf("before", "hello from new host"))
        }
        eventually(message = "member's message confirmed by the new host") {
            b.store.all(group.id).filter { it.outgoing && it.kind == MessageKind.TEXT }.all { it.status == MessageStatus.DELIVERED }
        }
        eventually(message = "same order on both devices") { a.store.texts(group.id) == b.store.texts(group.id) }
        assertFalse(a.store.getConversation(group.id)!!.ended)
        assertTrue(a.store.memberList(group.id).none { it.peerId == 50L })
    }

    @Test
    fun hostCrashTriggersAutomaticElection() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val b = device("BB", 20, "Bipin")
        val c = device("CC", 30, "Chandra")
        val group = host.engine.createGroup("Trek", 1, 5)
        joinAll(host, group.id, a, b, c)
        host.engine.sendText(group.id, "alive")
        eventually { listOf(a, b, c).all { it.store.texts(group.id) == listOf("alive") && it.store.memberList(group.id).size == 4 } }

        net.kill("HH")

        eventually(timeoutMs = 15_000, message = "Asha is the new host") { runBlocking { a.store.getConversation(group.id)?.isHost == true } }
        fun dump() = listOf(a, b, c).joinToString(" | ") { d ->
            runBlocking { val v = d.store.getConversation(group.id); "${d.name}: host=${v?.hostId} isHost=${v?.isHost} ended=${v?.ended} reconnecting=${group.id in d.engine.reconnecting.value}" }
        }
        eventually(timeoutMs = 15_000, message = "B and C follow", diagnostics = ::dump) {
            runBlocking { listOf(b, c).all { it.store.getConversation(group.id)?.hostId == 10L && !it.store.getConversation(group.id)!!.ended } }
        }
        c.engine.sendText(group.id, "still here")
        eventually(timeoutMs = 10_000, message = "post-crash message everywhere") {
            listOf(a, b, c).all { it.store.texts(group.id).contains("still here") }
        }
        // Sequence numbers continue where the old host stopped.
        val seqs = a.store.all(group.id).filter { it.kind == MessageKind.TEXT }.map { it.seq }
        assertEquals(listOf(1L, 2L), seqs)
    }

    @Test
    fun groupEndsCleanlyWhenTheLastHostLeavesAlone() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val group = host.engine.createGroup("Trek", 1)
        joinAll(host, group.id, a)
        eventually { a.store.memberList(group.id).size == 2 }
        // Asha goes offline first, so the host has nobody to hand over to.
        net.kill("AA")
        eventually { 10L !in host.engine.connectedPeers.value }
        host.engine.leaveConversation(group.id)
        assertNull(host.store.getConversation(group.id))
        assertEquals(ConversationType.GROUP, a.store.getConversation(group.id)?.type)
    }

    @Test
    fun memberReconnectsToTheSameHostAfterATemporaryDrop() = runBlocking {
        val host = device("HH", 50, "Host")
        val a = device("AA", 10, "Asha")
        val group = host.engine.createGroup("Trek", 1)
        joinAll(host, group.id, a)
        eventually { 50L in a.engine.connectedPeers.value }

        net.kill("HH")
        a.engine.sendText(group.id, "queued while host is away")
        net.setReachable("HH", true)

        eventually(timeoutMs = 10_000, message = "message delivered after reconnect") {
            host.store.texts(group.id).contains("queued while host is away")
        }
        eventually { runBlocking { a.store.all(group.id).first { it.text == "queued while host is away" }.status == MessageStatus.DELIVERED } }
        assertFalse(a.store.getConversation(group.id)!!.isHost)
        assertEquals(50L, a.store.getConversation(group.id)!!.hostId)
    }
}
