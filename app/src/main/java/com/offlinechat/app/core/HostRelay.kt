package com.offlinechat.app.core

class MemberState(
    val peerId: Long,
    var name: String,
    var address: String,
    var online: Boolean,
    val joinOrder: Int,
)

sealed interface Admission {
    data class Accepted(val rejoin: Boolean) : Admission
    object Full : Admission
}

data class Sequenced(val seq: Long, val duplicate: Boolean)

/**
 * Pure state machine for the host of a group (star topology). It owns membership, capacity,
 * the monotonically increasing sequence number and de-duplication. It performs no I/O, which
 * keeps the relay rules unit-testable. All methods are synchronized because every member link
 * is served by its own reader coroutine.
 */
class HostRelay(
    val groupId: Long,
    val hostId: Long,
    hostName: String,
    var name: String,
    var color: Int,
    val maxMembers: Int,
    startSeq: Long = 0L,
    private val dedupeCapacity: Int = 4096,
) {
    private var seq = startSeq
    private var nextJoinOrder = 1

    private val seen = object : LinkedHashMap<Long, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > dedupeCapacity
    }
    private val members = LinkedHashMap<Long, MemberState>()

    init {
        members[hostId] = MemberState(hostId, hostName, "", true, 0)
    }

    @get:Synchronized
    val memberCount: Int get() = members.size

    @get:Synchronized
    val isFull: Boolean get() = members.size >= maxMembers

    @Synchronized
    fun isMember(peerId: Long): Boolean = members.containsKey(peerId)

    @Synchronized
    fun isOnline(peerId: Long): Boolean = members[peerId]?.online == true

    @Synchronized
    fun currentSeq(): Long = seq

    /** A known member reconnecting is always admitted, even if the group is otherwise full. */
    @Synchronized
    fun admit(peerId: Long, name: String, address: String): Admission {
        val existing = members[peerId]
        if (existing != null) {
            existing.name = name
            existing.address = address
            existing.online = true
            return Admission.Accepted(rejoin = true)
        }
        if (members.size >= maxMembers) return Admission.Full
        members[peerId] = MemberState(peerId, name, address, true, nextJoinOrder++)
        return Admission.Accepted(rejoin = false)
    }

    /** Re-creates a persisted member as offline (used when the host process restarts or after migration). */
    @Synchronized
    fun restore(peerId: Long, name: String, address: String, joinOrder: Int) {
        if (peerId == hostId) return
        members[peerId] = MemberState(peerId, name, address, false, joinOrder)
        if (joinOrder >= nextJoinOrder) nextJoinOrder = joinOrder + 1
    }

    @Synchronized
    fun setOnline(peerId: Long, online: Boolean) {
        members[peerId]?.online = online
    }

    @Synchronized
    fun remove(peerId: Long): Boolean = peerId != hostId && members.remove(peerId) != null

    /** Assigns the next sequence number, or returns the existing one if this message was already seen. */
    @Synchronized
    fun sequence(messageId: Long): Sequenced {
        val existing = seen[messageId]
        if (existing != null) return Sequenced(existing, duplicate = true)
        seq += 1
        seen[messageId] = seq
        return Sequenced(seq, duplicate = false)
    }

    @Synchronized
    fun recipients(except: Long? = null): List<Long> =
        members.values.filter { it.peerId != hostId && it.online && it.peerId != except }.map { it.peerId }

    @Synchronized
    fun onlineIds(): Set<Long> = members.values.filter { it.online }.map { it.peerId }.toSet()

    @Synchronized
    fun memberInfos(): List<MemberInfo> = members.values.map { MemberInfo(it.peerId, it.name, it.address, it.online) }

    @Synchronized
    fun memberName(peerId: Long): String? = members[peerId]?.name

    @Synchronized
    fun ad(): GroupAd = GroupAd(groupId, name, color, members.size, maxMembers)

    @Synchronized
    fun meta(): GroupMeta = GroupMeta(groupId, name, color, maxMembers, hostId)
}

/** Deterministic successor rule used when the host disappears: lowest peer id wins. */
object HostElection {
    fun candidates(members: List<MemberInfo>, oldHostId: Long): List<MemberInfo> =
        members.filter { it.peerId != oldHostId }.sortedBy { it.peerId }

    /** Successor chosen by a host that leaves voluntarily: the lowest-id member that is online right now. */
    fun successor(members: List<MemberInfo>, leavingHostId: Long): MemberInfo? =
        candidates(members.filter { it.online }, leavingHostId).firstOrNull()
}
