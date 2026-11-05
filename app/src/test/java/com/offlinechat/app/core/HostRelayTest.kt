package com.offlinechat.app.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRelayTest {
    private fun relay(max: Int = 3) = HostRelay(groupId = 1, hostId = 100, hostName = "Host", name = "Trek", color = 0, maxMembers = max)

    @Test
    fun sequenceNumbersIncreaseWithoutGaps() {
        val r = relay()
        assertEquals(listOf(1L, 2L, 3L), listOf(10L, 11L, 12L).map { r.sequence(it).seq })
    }

    @Test
    fun duplicateMessageIdKeepsItsOriginalSequence() {
        val r = relay()
        assertEquals(Sequenced(1, false), r.sequence(500))
        r.sequence(501)
        assertEquals(Sequenced(1, true), r.sequence(500))
        assertEquals(3L, r.sequence(502).seq)
    }

    @Test
    fun concurrentSendersGetUniqueIncreasingSequences() {
        val r = relay()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        repeat(8) { t ->
            pool.execute {
                repeat(250) { i -> seen.add(r.sequence(t * 1000L + i).seq) }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(2000, seen.size)
        assertEquals(2000L, seen.max())
    }

    @Test
    fun capacityIncludesTheHostAndRejoinsAlwaysSucceed() {
        val r = relay(max = 3)
        assertEquals(Admission.Accepted(false), r.admit(1, "A", "a"))
        assertEquals(Admission.Accepted(false), r.admit(2, "B", "b"))
        assertTrue(r.isFull)
        assertEquals(Admission.Full, r.admit(3, "C", "c"))
        r.setOnline(1, false)
        assertEquals(Admission.Accepted(true), r.admit(1, "A", "a2"))
        assertTrue(r.isOnline(1))
    }

    @Test
    fun recipientsAreOnlineMembersExceptSenderAndHost() {
        val r = relay(max = 5)
        r.admit(1, "A", "a"); r.admit(2, "B", "b"); r.admit(3, "C", "c")
        r.setOnline(2, false)
        assertEquals(listOf(1L, 3L), r.recipients())
        assertEquals(listOf(3L), r.recipients(except = 1))
    }

    @Test
    fun removingAMemberFreesASeatButNeverRemovesTheHost() {
        val r = relay(max = 2)
        r.admit(1, "A", "a")
        assertTrue(r.isFull)
        assertTrue(r.remove(1))
        assertFalse(r.isFull)
        assertFalse(r.remove(100))
    }

    @Test
    fun restoredMembersStartOfflineAndKeepJoinOrder() {
        val r = relay(max = 5)
        r.restore(7, "G", "g", 3)
        assertFalse(r.isOnline(7))
        assertTrue(r.isMember(7))
        assertEquals(Admission.Accepted(false), r.admit(8, "H", "h"))
        assertEquals(listOf(100L, 7L, 8L), r.memberInfos().map { it.peerId })
    }

    @Test
    fun hostCanContinueSequenceAfterMigration() {
        val r = HostRelay(1, 5, "New", "Trek", 0, 4, startSeq = 41)
        assertEquals(42L, r.sequence(1).seq)
    }

    @Test
    fun electionPicksLowestOnlineIdAndSkipsTheOldHost() {
        val members = listOf(
            MemberInfo(100, "Host", "", true), MemberInfo(30, "C", "c", true),
            MemberInfo(10, "A", "a", false), MemberInfo(20, "B", "b", true),
        )
        assertEquals(20L, HostElection.successor(members, 100)?.peerId)
        assertEquals(listOf(10L, 20L, 30L), HostElection.candidates(members, 100).map { it.peerId })
        assertNull(HostElection.successor(listOf(MemberInfo(100, "Host", "", true)), 100))
    }
}
