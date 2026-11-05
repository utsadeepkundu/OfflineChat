package com.offlinechat.app.engine

import com.offlinechat.app.core.Ack
import com.offlinechat.app.core.AckCode
import com.offlinechat.app.core.Admission
import com.offlinechat.app.core.GroupAd
import com.offlinechat.app.core.GroupMeta
import com.offlinechat.app.core.Hello
import com.offlinechat.app.core.HelloKind
import com.offlinechat.app.core.HistoryItem
import com.offlinechat.app.core.HistorySync
import com.offlinechat.app.core.HostElection
import com.offlinechat.app.core.HostMigrate
import com.offlinechat.app.core.HostRelay
import com.offlinechat.app.core.JoinReject
import com.offlinechat.app.core.JoinRequest
import com.offlinechat.app.core.MemberInfo
import com.offlinechat.app.core.MemberLeft
import com.offlinechat.app.core.MemberList
import com.offlinechat.app.core.Packet
import com.offlinechat.app.core.PacketType
import com.offlinechat.app.core.Protocol
import com.offlinechat.app.core.RejectReason
import com.offlinechat.app.core.TextPayload
import com.offlinechat.app.core.Timings
import com.offlinechat.app.core.TypingPayload
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.ChatStore
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.domain.EngineEvent
import com.offlinechat.app.domain.EngineSettings
import com.offlinechat.app.domain.IdentityProvider
import com.offlinechat.app.domain.ImportedFile
import com.offlinechat.app.domain.JoinOutcome
import com.offlinechat.app.domain.JoinRequestItem
import com.offlinechat.app.domain.Member
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus
import com.offlinechat.app.domain.Transport
import com.offlinechat.app.net.Link
import com.offlinechat.app.net.LinkManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The brain of the app. It is pure Kotlin (no Android types), so the whole chat, group and file
 * logic is unit-testable on the JVM with an in-memory [Transport] and [ChatStore].
 *
 * Topology: every pair of devices shares at most one [Link]. A group is a star: its host keeps a link
 * to every member, sequences and relays each message, and answers late joiners with recent history.
 */
class ChatEngine(
    transport: Transport,
    private val store: ChatStore,
    private val identity: IdentityProvider,
    private val settings: EngineSettings,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    partsDir: File,
    receivedDir: File,
    private val timings: Timings = Timings(),
    private val clock: () -> Long = System::currentTimeMillis,
) : LinkManager.Handler, TransferHost {

    private class PendingJoin(val link: Link, val relay: HostRelay, val name: String, val timeout: Job)

    override val selfId: Long get() = identity.selfId
    override val selfName: String get() = identity.selfName

    private val relays = ConcurrentHashMap<Long, HostRelay>()
    private val pending = ConcurrentHashMap<Long, PendingJoin>()
    private val joinWaiters = ConcurrentHashMap<Long, CompletableDeferred<JoinOutcome>>()
    private val groupJobs = ConcurrentHashMap<Long, Job>()
    private val directJobs = ConcurrentHashMap<Long, Job>()
    private val typingMap = ConcurrentHashMap<Long, ConcurrentHashMap<Long, Pair<String, Long>>>()
    private val sendLocks = ConcurrentHashMap<Long, Mutex>()
    private val requestSeq = AtomicLong(0)

    private val _connected = MutableStateFlow<Set<Long>>(emptySet())
    private val _online = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    private val _typing = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    private val _joins = MutableStateFlow<List<JoinRequestItem>>(emptyList())
    private val _reconnecting = MutableStateFlow<Set<Long>>(emptySet())
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Peer ids with a live link right now. */
    val connectedPeers: StateFlow<Set<Long>> = _connected.asStateFlow()

    /** For each group this device knows: which member ids are currently online. */
    val onlineMembers: StateFlow<Map<Long, Set<Long>>> = _online.asStateFlow()

    /** Conversation id to the names of people typing there. */
    val typing: StateFlow<Map<Long, List<String>>> = _typing.asStateFlow()

    /** Join requests waiting for this device (as host) to accept or reject. */
    val pendingJoins: StateFlow<List<JoinRequestItem>> = _joins.asStateFlow()

    /** Conversation ids currently trying to restore their connection. */
    val reconnecting: StateFlow<Set<Long>> = _reconnecting.asStateFlow()

    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    @Volatile var activeConversation: Long = 0L
        private set

    private val links: LinkManager = LinkManager(
        transport = transport,
        scope = scope,
        io = io,
        identity = identity,
        settings = settings,
        timings = timings,
        groupAds = { relays.values.map { it.ad() } },
        handler = this,
        clock = clock,
    )

    private val files = FileTransfers(this, store, links, partsDir, receivedDir, scope, io, timings, clock)
    val transfers get() = files.transfers

    // ---------------------------------------------------------------- lifecycle

    suspend fun start() {
        store.conversations().filter { it.type == ConversationType.GROUP && it.isHost && !it.ended }.forEach { buildRelay(it) }
        links.start()
    }

    fun stop() {
        links.stop()
        groupJobs.values.forEach { it.cancel() }
        directJobs.values.forEach { it.cancel() }
        pending.values.forEach { it.timeout.cancel() }
    }

    fun isHostingAny(): Boolean = relays.isNotEmpty()

    // ------------------------------------------------------------ public actions

    suspend fun probe(address: String): Hello? = links.probe(address)

    /** Opens (or reuses) a link to a nearby device and returns the direct chat with it. Throws IOException if unreachable. */
    suspend fun connectDirect(address: String): Conversation {
        val link = links.connect(address)
        return ensureDirectConversation(link)
    }

    fun openConversation(convId: Long) {
        activeConversation = convId
        scope.launch(io) {
            store.clearUnread(convId)
            val conv = store.getConversation(convId) ?: return@launch
            if (conv.ended || (conv.type == ConversationType.GROUP && conv.isHost)) return@launch
            val peer = if (conv.type == ConversationType.DIRECT) conv.id else conv.hostId
            if (links.linkFor(peer) != null) return@launch
            if (conv.type == ConversationType.DIRECT) startDirectReconnect(conv) else startHostRecovery(conv, delayFirst = false, allowMigration = false)
        }
    }

    fun closeConversation(convId: Long) {
        if (activeConversation == convId) activeConversation = 0L
    }

    /**
     * Sends are serialized per conversation (the mutex is fair), so quick consecutive messages from one
     * person always reach the other side, and get their sequence numbers, in the order they were sent.
     */
    suspend fun sendText(convId: Long, raw: String) {
        val text = raw.trim().take(Protocol.MAX_TEXT_CHARS)
        if (text.isEmpty()) return
        sendLocks.getOrPut(convId) { Mutex() }.withLock {
            val conv = store.getConversation(convId) ?: return
            if (conv.ended) {
                notice("This group has ended.")
                return
            }
            val now = clock()
            val id = newId()
            val base = ChatMessage(
                id = id,
                conversationId = convId,
                senderId = selfId,
                senderName = selfName,
                kind = MessageKind.TEXT,
                text = text,
                status = MessageStatus.SENDING,
                outgoing = true,
                sortKey = localSortKey(conv),
                timestamp = now,
            )
            if (conv.type == ConversationType.GROUP && conv.isHost) {
                val relay = relays[convId] ?: buildRelay(conv)
                val seq = relay.sequence(id).seq
                store.insertMessage(base.copy(seq = seq, sortKey = seq * 1000, status = MessageStatus.DELIVERED))
                store.recordActivity(convId, text, now, 0)
                broadcast(relay, Packet(PacketType.TEXT, id, selfId, convId, seq, TextPayload(text, now, selfName).encode()))
                return
            }
            store.insertMessage(base)
            store.recordActivity(convId, text, now, 0)
            deliverText(conv, base)
        }
    }

    suspend fun sendFile(convId: Long, file: ImportedFile) {
        val conv = store.getConversation(convId)
        if (conv == null || conv.ended) {
            file.file.delete()
            notice("This chat is not available.")
            return
        }
        files.sendFile(conv, file)
    }

    suspend fun retryMessage(messageId: Long) {
        val msg = store.getMessage(messageId) ?: return
        if (!msg.outgoing || msg.status == MessageStatus.DELIVERED) return
        val conv = store.getConversation(msg.conversationId) ?: return
        when (msg.kind) {
            MessageKind.FILE -> files.retry(msg, conv)
            MessageKind.TEXT -> sendLocks.getOrPut(conv.id) { Mutex() }.withLock {
                store.updateStatus(msg.id, MessageStatus.SENDING)
                deliverText(conv, msg)
            }
            MessageKind.SYSTEM -> Unit
        }
    }

    fun cancelTransfer(messageId: Long) = files.cancel(messageId)

    suspend fun setTyping(convId: Long, typing: Boolean) {
        val conv = store.getConversation(convId) ?: return
        val isGroup = conv.type == ConversationType.GROUP
        val packet = Packet(PacketType.TYPING, 0, selfId, if (isGroup) convId else 0L, 0, TypingPayload(selfName, typing).encode())
        when {
            !isGroup -> links.linkFor(convId)?.send(packet)
            conv.isHost -> relays[convId]?.let { broadcast(it, packet) }
            else -> links.linkFor(conv.hostId)?.send(packet)
        }
    }

    suspend fun createGroup(name: String, color: Int, maxMembers: Int = Protocol.DEFAULT_MAX_MEMBERS): Conversation {
        val max = maxMembers.coerceIn(2, Protocol.HARD_MAX_MEMBERS)
        val id = newId()
        val conv = Conversation(
            id = id,
            type = ConversationType.GROUP,
            title = name.trim().take(40).ifBlank { "Group" },
            hostId = selfId,
            color = color,
            isHost = true,
            maxMembers = max,
            lastMessageAt = clock(),
            lastPreview = "Group created",
        )
        store.upsertConversation(conv)
        store.upsertMember(Member(id, selfId, selfName, "", 0))
        buildRelay(conv)
        systemMessage(id, "You created the group")
        return conv
    }

    suspend fun renameGroup(convId: Long, name: String) {
        val relay = relays[convId] ?: return
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        relay.name = clean
        store.getConversation(convId)?.let { store.upsertConversation(it.copy(title = clean)) }
        broadcast(relay, Packet(PacketType.GROUP_CREATE, 0, selfId, convId, 0, relay.meta().encode()))
    }

    suspend fun joinGroup(address: String, groupId: Long): JoinOutcome {
        val existing = store.getConversation(groupId)
        return requestJoin(address, groupId, rejoin = existing != null, expectedPeer = null)
    }

    suspend fun respondJoin(requestId: Long, accept: Boolean) = resolveJoin(requestId, accept, fromTimeout = false)

    /** Leaves a group (as member), hands it over to a successor (as host) or just deletes a direct chat. */
    suspend fun leaveConversation(convId: Long) {
        val conv = store.getConversation(convId) ?: return
        groupJobs.remove(convId)?.cancel()
        directJobs.remove(convId)?.cancel()
        if (conv.type == ConversationType.GROUP) {
            val relay = relays.remove(convId)
            if (relay != null) {
                val successor = HostElection.successor(relay.memberInfos(), selfId)
                val packet = if (successor != null) {
                    Packet(PacketType.HOST_MIGRATE, 0, selfId, convId, 0, HostMigrate(successor.peerId, successor.name, successor.address).encode())
                } else {
                    Packet(PacketType.MEMBER_LEFT, 0, selfId, convId, 0, MemberLeft(selfId, true).encode())
                }
                relay.recipients().forEach { links.linkFor(it)?.send(packet) }
            } else if (!conv.ended) {
                links.linkFor(conv.hostId)?.send(Packet(PacketType.MEMBER_LEFT, 0, selfId, convId, 0, MemberLeft(selfId, true).encode()))
            }
            _online.update { it - convId }
        }
        _reconnecting.update { it - convId }
        store.deleteConversation(convId)
    }

    // --------------------------------------------------------------- transport

    override fun onLinkUp(link: Link) {
        _connected.update { it + link.peerId }
        scope.launch(io) {
            val conv = store.getConversation(link.peerId)
            if (conv != null && conv.type == ConversationType.DIRECT) {
                if (conv.title != link.peerName || conv.peerAddress != link.remoteAddress) {
                    store.upsertConversation(conv.copy(title = link.peerName, peerAddress = link.remoteAddress))
                }
                resendPending(conv.id, link)
            }
        }
    }

    override fun onLinkDown(link: Link) {
        val peer = link.peerId
        _connected.update { it - peer }
        files.onLinkDown(peer)
        typingMap.forEach { (convId, m) -> if (m.remove(peer) != null) publishTyping(convId) }
        pending.entries.filter { it.value.link === link }.forEach { (id, _) ->
            pending.remove(id)?.timeout?.cancel()
            _joins.update { list -> list.filterNot { it.requestId == id } }
        }
        scope.launch(io) {
            for (relay in relays.values) {
                if (relay.isMember(peer) && relay.isOnline(peer)) {
                    relay.setOnline(peer, false)
                    publishOnline(relay.groupId)
                    broadcast(relay, Packet(PacketType.MEMBER_LEFT, 0, selfId, relay.groupId, 0, MemberLeft(peer, false).encode()), except = peer)
                }
            }
            store.conversations()
                .filter { it.type == ConversationType.GROUP && !it.isHost && !it.ended && it.hostId == peer }
                .forEach { startHostRecovery(it, delayFirst = true, allowMigration = true) }
            val direct = store.getConversation(peer)
            if (direct != null && direct.type == ConversationType.DIRECT && activeConversation == direct.id) startDirectReconnect(direct)
        }
    }

    override suspend fun onPacket(link: Link, packet: Packet) {
        when (packet.type) {
            PacketType.TEXT -> onText(link, packet)
            PacketType.FILE_META -> files.onMeta(link, packet)
            PacketType.FILE_CHUNK -> files.onChunk(link, packet)
            PacketType.ACK -> onAck(link, packet)
            PacketType.JOIN_REQUEST -> onJoinRequest(link, packet)
            PacketType.JOIN_ACCEPT -> onJoinAccept(link, packet)
            PacketType.JOIN_REJECT -> onJoinReject(link, packet)
            PacketType.MEMBER_LIST -> onMemberList(link, packet)
            PacketType.MEMBER_JOINED -> onMemberJoined(link, packet)
            PacketType.MEMBER_LEFT -> onMemberLeft(link, packet)
            PacketType.TYPING -> onTyping(link, packet)
            PacketType.HISTORY_SYNC -> onHistory(link, packet)
            PacketType.GROUP_CREATE -> onGroupMeta(link, packet)
            PacketType.HOST_MIGRATE -> onHostMigrate(link, packet)
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- messages

    private suspend fun onText(link: Link, p: Packet) {
        val t = TextPayload.decode(p.payload)
        val now = clock()
        if (p.groupId == 0L) {
            val conv = ensureDirectConversation(link)
            val msg = ChatMessage(
                id = p.messageId,
                conversationId = conv.id,
                senderId = link.peerId,
                senderName = link.peerName,
                kind = MessageKind.TEXT,
                text = t.text.take(Protocol.MAX_TEXT_CHARS),
                status = MessageStatus.DELIVERED,
                outgoing = false,
                sortKey = now,
                timestamp = now,
            )
            val inserted = store.insertMessage(msg)
            link.send(ack(p.messageId, AckCode.DELIVERED))
            noteTyping(conv.id, link.peerId, link.peerName, false)
            if (inserted) incoming(conv, msg)
            return
        }

        val relay = relays[p.groupId]
        if (relay != null) {
            onGroupTextAtHost(link, relay, p, t, now)
            return
        }
        val conv = memberConv(link, p.groupId) ?: return
        if (p.seq <= 0L || p.senderId == selfId) return
        val name = t.senderName.ifBlank { store.members(conv.id).firstOrNull { it.peerId == p.senderId }?.name.orEmpty() }
        val msg = ChatMessage(
            id = p.messageId,
            conversationId = conv.id,
            senderId = p.senderId,
            senderName = name,
            kind = MessageKind.TEXT,
            text = t.text.take(Protocol.MAX_TEXT_CHARS),
            status = MessageStatus.DELIVERED,
            outgoing = false,
            seq = p.seq,
            sortKey = p.seq * 1000,
            timestamp = now,
        )
        noteTyping(conv.id, p.senderId, name, false)
        if (store.insertMessage(msg)) incoming(conv, msg)
    }

    private suspend fun onGroupTextAtHost(link: Link, relay: HostRelay, p: Packet, t: TextPayload, now: Long) {
        if (p.senderId != link.peerId || !relay.isMember(link.peerId)) return
        val known = store.getMessage(p.messageId)
        if (known != null) {
            link.send(groupAck(relay.groupId, p.messageId, known.seq))
            return
        }
        val sequenced = relay.sequence(p.messageId)
        if (sequenced.duplicate) {
            link.send(groupAck(relay.groupId, p.messageId, sequenced.seq))
            return
        }
        val name = relay.memberName(link.peerId) ?: link.peerName
        val msg = ChatMessage(
            id = p.messageId,
            conversationId = relay.groupId,
            senderId = link.peerId,
            senderName = name,
            kind = MessageKind.TEXT,
            text = t.text.take(Protocol.MAX_TEXT_CHARS),
            status = MessageStatus.DELIVERED,
            outgoing = false,
            seq = sequenced.seq,
            sortKey = sequenced.seq * 1000,
            timestamp = now,
        )
        store.insertMessage(msg)
        val relayed = TextPayload(msg.text, now, name).encode()
        broadcast(relay, Packet(PacketType.TEXT, p.messageId, link.peerId, relay.groupId, sequenced.seq, relayed), except = link.peerId)
        link.send(groupAck(relay.groupId, p.messageId, sequenced.seq))
        noteTyping(relay.groupId, link.peerId, name, false)
        store.getConversation(relay.groupId)?.let { incoming(it, msg) }
    }

    private suspend fun onAck(link: Link, p: Packet) {
        val a = Ack.decode(p.payload)
        if (a.code == AckCode.DELIVERED) {
            if (a.value > 0 && p.groupId != 0L) store.markSequenced(p.messageId, a.value, MessageStatus.DELIVERED)
            else store.updateStatus(p.messageId, MessageStatus.DELIVERED)
        } else {
            files.onAck(link, p, a)
        }
    }

    private suspend fun incoming(conv: Conversation, msg: ChatMessage) {
        val preview = when (msg.kind) {
            MessageKind.FILE -> "📎 ${msg.fileName ?: "File"}"
            else -> msg.text
        }
        val unread = if (activeConversation == conv.id) 0 else 1
        store.recordActivity(conv.id, preview, msg.timestamp, unread)
        _events.tryEmit(EngineEvent.IncomingMessage(conv, msg))
    }

    private suspend fun deliverText(conv: Conversation, msg: ChatMessage) {
        val link = ensureLink(conv)
        if (link == null) {
            // A group member that is already recovering keeps the message queued; it is resent after rejoining.
            if (conv.type == ConversationType.GROUP && groupJobs.containsKey(conv.id)) return
            store.updateStatus(msg.id, MessageStatus.FAILED)
            return
        }
        val ok = link.send(textPacket(conv, msg))
        store.updateStatus(msg.id, if (ok) MessageStatus.SENT else MessageStatus.FAILED)
    }

    private suspend fun resendPending(convId: Long, link: Link?) {
        link ?: return
        val conv = store.getConversation(convId) ?: return
        for (m in store.pendingOutgoing(convId)) {
            if (link.send(textPacket(conv, m))) store.updateStatus(m.id, MessageStatus.SENT)
        }
    }

    private fun textPacket(conv: Conversation, m: ChatMessage) = Packet(
        PacketType.TEXT, m.id, selfId, if (conv.type == ConversationType.GROUP) conv.id else 0L, 0,
        TextPayload(m.text, m.timestamp, selfName).encode(),
    )

    // ------------------------------------------------------------------ groups

    private suspend fun buildRelay(conv: Conversation): HostRelay {
        val relay = HostRelay(conv.id, selfId, selfName, conv.title, conv.color, conv.maxMembers, store.maxSeq(conv.id))
        store.members(conv.id).forEach { if (it.peerId != selfId) relay.restore(it.peerId, it.name, it.address, it.joinOrder) }
        relays[conv.id] = relay
        publishOnline(conv.id)
        return relay
    }

    private fun publishOnline(groupId: Long) {
        val relay = relays[groupId] ?: return
        _online.update { it + (groupId to relay.onlineIds()) }
    }

    private fun broadcast(relay: HostRelay, packet: Packet, except: Long? = null) {
        for (peer in relay.recipients(except)) links.linkFor(peer)?.send(packet)
    }

    private fun reject(link: Link, groupId: Long, reason: Int) {
        link.send(Packet(PacketType.JOIN_REJECT, 0, selfId, groupId, 0, JoinReject(reason).encode()))
    }

    private suspend fun onJoinRequest(link: Link, p: Packet) {
        val relay = relays[p.groupId]
        if (relay == null) {
            reject(link, p.groupId, RejectReason.NOT_HOST)
            return
        }
        val req = JoinRequest.decode(p.payload)
        val name = req.name.ifBlank { link.peerName }.take(40)
        when {
            relay.isMember(link.peerId) -> admit(link, relay, name)
            relay.isFull -> reject(link, relay.groupId, RejectReason.FULL)
            settings.autoAcceptJoins -> admit(link, relay, name)
            _joins.value.any { it.peerId == link.peerId && it.groupId == relay.groupId } -> Unit
            else -> {
                val id = requestSeq.incrementAndGet()
                val item = JoinRequestItem(id, relay.groupId, relay.name, link.peerId, name, link.remoteAddress)
                val timeout = scope.launch {
                    delay(timings.joinDecisionMs)
                    resolveJoin(id, false, fromTimeout = true)
                }
                pending[id] = PendingJoin(link, relay, name, timeout)
                _joins.update { it + item }
                _events.tryEmit(EngineEvent.JoinRequested(item))
            }
        }
    }

    private suspend fun resolveJoin(requestId: Long, accept: Boolean, fromTimeout: Boolean) {
        val pj = pending.remove(requestId) ?: return
        if (!fromTimeout) pj.timeout.cancel()
        _joins.update { list -> list.filterNot { it.requestId == requestId } }
        if (!pj.link.isAlive) return
        if (accept) admit(pj.link, pj.relay, pj.name) else reject(pj.link, pj.relay.groupId, RejectReason.DECLINED)
    }

    private suspend fun admit(link: Link, relay: HostRelay, name: String) {
        when (val result = relay.admit(link.peerId, name, link.remoteAddress)) {
            Admission.Full -> reject(link, relay.groupId, RejectReason.FULL)
            is Admission.Accepted -> {
                val gid = relay.groupId
                val known = store.members(gid)
                val order = known.firstOrNull { it.peerId == link.peerId }?.joinOrder ?: ((known.maxOfOrNull { it.joinOrder } ?: 0) + 1)
                store.upsertMember(Member(gid, link.peerId, name, link.remoteAddress, order))

                link.send(Packet(PacketType.JOIN_ACCEPT, 0, selfId, gid, 0, relay.meta().encode()))
                link.send(Packet(PacketType.MEMBER_LIST, 0, selfId, gid, 0, MemberList(relay.memberInfos()).encode()))
                val history = store.recentSequenced(gid, settings.historyCount)
                    .filter { it.kind == MessageKind.TEXT }
                    .map { HistoryItem(it.id, it.senderId, it.senderName, it.seq, it.timestamp, it.text) }
                HistorySync.split(history).forEach { link.send(Packet(PacketType.HISTORY_SYNC, 0, selfId, gid, 0, it.encode())) }

                val info = MemberInfo(link.peerId, name, link.remoteAddress, true)
                broadcast(relay, Packet(PacketType.MEMBER_JOINED, 0, selfId, gid, 0, info.encode()), except = link.peerId)
                publishOnline(gid)
                if (!result.rejoin) {
                    systemMessage(gid, "$name joined")
                    _events.tryEmit(EngineEvent.Notice("$name joined ${relay.name}"))
                }
            }
        }
    }

    private suspend fun onJoinAccept(link: Link, p: Packet) {
        val meta = GroupMeta.decode(p.payload)
        if (meta.hostId != link.peerId || meta.groupId != p.groupId) return
        val existing = store.getConversation(meta.groupId)
        store.upsertConversation(
            Conversation(
                id = meta.groupId,
                type = ConversationType.GROUP,
                title = meta.name,
                peerAddress = link.remoteAddress,
                hostId = meta.hostId,
                color = meta.color,
                isHost = false,
                maxMembers = meta.maxMembers,
                ended = false,
                lastMessageAt = existing?.lastMessageAt?.takeIf { it > 0 } ?: clock(),
                lastPreview = existing?.lastPreview?.ifBlank { null } ?: "You joined the group",
                unread = existing?.unread ?: 0,
            ),
        )
        joinWaiters.remove(meta.groupId)?.complete(JoinOutcome.Joined(meta.groupId))
    }

    private fun onJoinReject(link: Link, p: Packet) {
        val reason = JoinReject.decode(p.payload).reason
        joinWaiters.remove(p.groupId)?.complete(JoinOutcome.Rejected(reason))
    }

    private suspend fun requestJoin(address: String, groupId: Long, rejoin: Boolean, expectedPeer: Long?): JoinOutcome {
        try {
            val link = links.connect(address)
            if (expectedPeer != null && link.peerId != expectedPeer) return JoinOutcome.Failed("A different device answered")
            val waiter = CompletableDeferred<JoinOutcome>()
            joinWaiters[groupId] = waiter
            try {
                val request = Packet(PacketType.JOIN_REQUEST, 0, selfId, groupId, 0, JoinRequest(selfName, rejoin).encode())
                if (!link.send(request)) return JoinOutcome.Failed("Connection lost")
                val outcome = withTimeoutOrNull(timings.joinDecisionMs + timings.handshakeMs) { waiter.await() }
                if (outcome is JoinOutcome.Joined) resendPending(groupId, link)
                return outcome ?: JoinOutcome.Failed("The host did not answer")
            } finally {
                joinWaiters.remove(groupId, waiter)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return JoinOutcome.Failed(e.message ?: "Could not connect")
        }
    }

    private suspend fun onMemberList(link: Link, p: Packet) {
        val conv = memberConv(link, p.groupId) ?: return
        val list = MemberList.decode(p.payload).members
        store.replaceMembers(conv.id, list.mapIndexed { i, m -> Member(conv.id, m.peerId, m.name, m.address, i) })
        _online.update { it + (conv.id to list.filter { m -> m.online }.map { m -> m.peerId }.toSet()) }
    }

    private suspend fun onMemberJoined(link: Link, p: Packet) {
        val conv = memberConv(link, p.groupId) ?: return
        val info = MemberInfo.decode(p.payload)
        if (info.peerId == selfId) return
        val known = store.members(conv.id)
        val isNew = known.none { it.peerId == info.peerId }
        store.upsertMember(Member(conv.id, info.peerId, info.name, info.address, known.size))
        _online.update { it + (conv.id to ((it[conv.id] ?: emptySet()) + info.peerId)) }
        if (isNew) systemMessage(conv.id, "${info.name} joined")
    }

    private suspend fun onMemberLeft(link: Link, p: Packet) {
        val left = MemberLeft.decode(p.payload)
        val relay = relays[p.groupId]
        if (relay != null) {
            // A member announcing its own departure.
            if (p.senderId != link.peerId || left.peerId != link.peerId || !relay.isMember(link.peerId)) return
            val name = relay.memberName(link.peerId) ?: link.peerName
            relay.remove(link.peerId)
            store.removeMember(relay.groupId, link.peerId)
            publishOnline(relay.groupId)
            broadcast(relay, Packet(PacketType.MEMBER_LEFT, 0, selfId, relay.groupId, 0, MemberLeft(link.peerId, true).encode()))
            systemMessage(relay.groupId, "$name left")
            return
        }
        val conv = memberConv(link, p.groupId) ?: return
        if (left.peerId == conv.hostId) {
            if (left.removed) {
                groupJobs.remove(conv.id)?.cancel()
                markEnded(conv, "The host ended this group")
            }
            return
        }
        _online.update { it + (conv.id to ((it[conv.id] ?: emptySet()) - left.peerId)) }
        if (left.removed) {
            val name = store.members(conv.id).firstOrNull { it.peerId == left.peerId }?.name ?: "Someone"
            store.removeMember(conv.id, left.peerId)
            systemMessage(conv.id, "$name left")
        }
    }

    private suspend fun onTyping(link: Link, p: Packet) {
        val t = TypingPayload.decode(p.payload)
        if (p.groupId == 0L) {
            noteTyping(link.peerId, link.peerId, t.name.ifBlank { link.peerName }, t.typing)
            return
        }
        val relay = relays[p.groupId]
        if (relay != null) {
            if (p.senderId != link.peerId || !relay.isMember(link.peerId)) return
            noteTyping(relay.groupId, link.peerId, t.name, t.typing)
            broadcast(relay, p, except = link.peerId)
            return
        }
        val conv = memberConv(link, p.groupId) ?: return
        if (p.senderId != selfId) noteTyping(conv.id, p.senderId, t.name, t.typing)
    }

    private suspend fun onHistory(link: Link, p: Packet) {
        val conv = memberConv(link, p.groupId) ?: return
        val items = HistorySync.decode(p.payload).items
        var newest: HistoryItem? = null
        for (it in items) {
            val mine = it.senderId == selfId
            val inserted = store.insertMessage(
                ChatMessage(
                    id = it.messageId,
                    conversationId = conv.id,
                    senderId = it.senderId,
                    senderName = it.senderName,
                    kind = MessageKind.TEXT,
                    text = it.text,
                    status = MessageStatus.DELIVERED,
                    outgoing = mine,
                    seq = it.seq,
                    sortKey = it.seq * 1000,
                    timestamp = it.timestamp,
                ),
            )
            if (!inserted && mine) store.markSequenced(it.messageId, it.seq, MessageStatus.DELIVERED)
            if (inserted && (newest == null || it.seq > newest.seq)) newest = it
        }
        val latest = newest
        if (latest != null && latest.timestamp >= conv.lastMessageAt) {
            store.recordActivity(conv.id, latest.text, latest.timestamp, 0)
        }
    }

    private suspend fun onGroupMeta(link: Link, p: Packet) {
        val conv = memberConv(link, p.groupId) ?: return
        val meta = GroupMeta.decode(p.payload)
        store.upsertConversation(conv.copy(title = meta.name, color = meta.color, maxMembers = meta.maxMembers))
    }

    // ------------------------------------------------------------ host failover

    private suspend fun onHostMigrate(link: Link, p: Packet) {
        val conv = memberConv(link, p.groupId) ?: return
        val m = HostMigrate.decode(p.payload)
        if (m.newHostId == selfId) {
            launchGroupJob(conv.id, replace = true) { becomeHost(conv, link.peerId) }
        } else {
            launchGroupJob(conv.id, replace = true) {
                var joined = followSuccessor(conv, m.newHostAddress, m.newHostId)
                if (!joined) joined = electAndRejoin(conv)
                if (!joined) markEnded(conv, "The group ended because the host left")
            }
        }
    }

    private fun startHostRecovery(conv: Conversation, delayFirst: Boolean, allowMigration: Boolean) {
        launchGroupJob(conv.id, replace = false) {
            var restored = false
            repeat(timings.hostRetries) { attempt ->
                if (restored) return@repeat
                if (attempt > 0 || delayFirst) delay(timings.hostRetryDelayMs)
                val outcome = requestJoin(conv.peerAddress, conv.id, rejoin = true, expectedPeer = conv.hostId)
                when {
                    outcome is JoinOutcome.Joined -> restored = true
                    outcome is JoinOutcome.Rejected && outcome.reason != RejectReason.NOT_HOST -> {
                        markEnded(conv, "You can no longer join this group")
                        return@launchGroupJob
                    }
                }
            }
            if (restored || !allowMigration) return@launchGroupJob
            if (!electAndRejoin(conv)) markEnded(conv, "The host is gone and no one could take over")
        }
    }

    /**
     * Tries to join [hostId] at [address] as the new host. An unreachable device is given up on after a few
     * attempts. A device that answers "I am not hosting" is merely slower to notice the old host is gone, so it
     * gets a grace period of about one link timeout. That is what prevents two members from both promoting
     * themselves (split brain) when they notice the failure at different moments.
     */
    private suspend fun followSuccessor(conv: Conversation, address: String, hostId: Long): Boolean {
        val notHostDeadline = clock() + timings.linkTimeoutMs + timings.hostRetryDelayMs * timings.hostRetries
        var unreachable = 0
        var first = true
        while (true) {
            if (!first) delay(timings.hostRetryDelayMs)
            first = false
            when (val outcome = requestJoin(address, conv.id, rejoin = true, expectedPeer = hostId)) {
                is JoinOutcome.Joined -> return true
                is JoinOutcome.Rejected -> {
                    if (outcome.reason != RejectReason.NOT_HOST) return false
                    if (clock() > notHostDeadline) return false
                }
                is JoinOutcome.Failed -> if (++unreachable >= timings.hostRetries) return false
            }
        }
    }

    /**
     * Deterministic failover. Every member walks the same list (all members except the old host, lowest id
     * first) and joins the first candidate it can reach, or becomes host itself when its own turn comes.
     * Online hints are deliberately ignored: they can be stale or differ between devices, and disagreeing
     * about the candidate list is exactly what would cause two members to promote themselves.
     */
    private suspend fun electAndRejoin(conv: Conversation): Boolean {
        val fresh = store.getConversation(conv.id) ?: return false
        val members = store.members(conv.id).map { MemberInfo(it.peerId, it.name, it.address, true) }
        for (candidate in HostElection.candidates(members, fresh.hostId)) {
            if (candidate.peerId == selfId) {
                becomeHost(fresh, fresh.hostId)
                return true
            }
            if (followSuccessor(fresh, candidate.address, candidate.peerId)) return true
        }
        return false
    }

    private suspend fun becomeHost(conv: Conversation, oldHostId: Long) {
        store.removeMember(conv.id, oldHostId)
        val updated = conv.copy(hostId = selfId, isHost = true, peerAddress = "", ended = false)
        store.upsertConversation(updated)
        buildRelay(updated)
        systemMessage(conv.id, "The host left. You are now the host of this group")
        _events.tryEmit(EngineEvent.Notice("You are now the host of \"${conv.title}\""))
    }

    private suspend fun markEnded(conv: Conversation, text: String) {
        store.getConversation(conv.id)?.let { store.upsertConversation(it.copy(ended = true)) }
        systemMessage(conv.id, text)
        _online.update { it - conv.id }
        _events.tryEmit(EngineEvent.Notice(text))
    }

    private fun launchGroupJob(convId: Long, replace: Boolean, block: suspend () -> Unit) {
        val holder = arrayOfNulls<Job>(1)
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                holder[0]?.let { groupJobs.remove(convId, it) }
                if (!groupJobs.containsKey(convId)) _reconnecting.update { it - convId }
            }
        }
        holder[0] = job
        val previous = if (replace) groupJobs.put(convId, job) else groupJobs.putIfAbsent(convId, job)
        if (!replace && previous != null) {
            job.cancel()
            return
        }
        previous?.cancel()
        _reconnecting.update { it + convId }
        job.start()
    }

    // ------------------------------------------------------- direct reconnection

    private fun startDirectReconnect(conv: Conversation) {
        if (conv.peerAddress.isBlank()) return
        val holder = arrayOfNulls<Job>(1)
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            try {
                for (attempt in 0 until RECONNECT_ATTEMPTS) {
                    if (links.linkFor(conv.id) != null) return@launch
                    if (activeConversation != conv.id) return@launch
                    if (attempt > 0) delay(timings.hostRetryDelayMs * minOf(attempt, 4))
                    try {
                        links.connect(conv.peerAddress)
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Out of range or app closed on the other side: try again shortly.
                    }
                }
            } finally {
                holder[0]?.let { directJobs.remove(conv.id, it) }
                if (!directJobs.containsKey(conv.id)) _reconnecting.update { it - conv.id }
            }
        }
        holder[0] = job
        if (directJobs.putIfAbsent(conv.id, job) != null) {
            job.cancel()
            return
        }
        _reconnecting.update { it + conv.id }
        job.start()
    }

    // ----------------------------------------------------------- TransferHost

    override fun relay(groupId: Long): HostRelay? = relays[groupId]

    override suspend fun conversation(id: Long): Conversation? = store.getConversation(id)

    override suspend fun ensureLink(conv: Conversation): Link? {
        val peer = if (conv.type == ConversationType.DIRECT) conv.id else conv.hostId
        links.linkFor(peer)?.let { return it }
        if (conv.peerAddress.isBlank()) return null
        return try {
            links.connect(conv.peerAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun ensureDirectConversation(link: Link): Conversation {
        val existing = store.getConversation(link.peerId)
        if (existing != null) {
            if (existing.title != link.peerName || existing.peerAddress != link.remoteAddress) {
                val updated = existing.copy(title = link.peerName, peerAddress = link.remoteAddress)
                store.upsertConversation(updated)
                return updated
            }
            return existing
        }
        val conv = Conversation(
            id = link.peerId,
            type = ConversationType.DIRECT,
            title = link.peerName,
            peerAddress = link.remoteAddress,
            lastMessageAt = clock(),
            lastPreview = "",
        )
        store.upsertConversation(conv)
        return conv
    }

    /**
     * Sort key for a message this device sends. In a group it stays at the very bottom until the host confirms it
     * and assigns the real sequence number, so a pending message never jumps around above newer ones.
     */
    override suspend fun localSortKey(conv: Conversation): Long =
        if (conv.type == ConversationType.GROUP) PENDING_SORT_BASE + clock() else clock()

    private suspend fun systemSortKey(conv: Conversation): Long =
        if (conv.type == ConversationType.GROUP) store.maxSeq(conv.id) * 1000 + 1 else clock()

    override suspend fun onFileReceived(conv: Conversation, msg: ChatMessage) = incoming(conv, msg)

    override fun notice(text: String) {
        _events.tryEmit(EngineEvent.Notice(text))
    }

    // ----------------------------------------------------------------- helpers

    /** The group conversation if this device is a plain member of it and [link] is its host. */
    private suspend fun memberConv(link: Link, groupId: Long): Conversation? {
        val conv = store.getConversation(groupId) ?: return null
        if (conv.type != ConversationType.GROUP || conv.isHost || conv.hostId != link.peerId) return null
        return conv
    }

    private suspend fun systemMessage(convId: Long, text: String) {
        val conv = store.getConversation(convId)
        val now = clock()
        store.insertMessage(
            ChatMessage(
                id = newId(),
                conversationId = convId,
                senderId = 0L,
                senderName = "",
                kind = MessageKind.SYSTEM,
                text = text,
                status = MessageStatus.DELIVERED,
                sortKey = if (conv != null) systemSortKey(conv) else now,
                timestamp = now,
            ),
        )
    }

    private fun noteTyping(convId: Long, peerId: Long, name: String, typing: Boolean) {
        val m = typingMap.getOrPut(convId) { ConcurrentHashMap() }
        if (typing) {
            val expires = clock() + timings.typingTtlMs
            m[peerId] = name to expires
            scope.launch {
                delay(timings.typingTtlMs)
                if (m[peerId]?.second == expires) {
                    m.remove(peerId)
                    publishTyping(convId)
                }
            }
        } else {
            m.remove(peerId)
        }
        publishTyping(convId)
    }

    private fun publishTyping(convId: Long) {
        val names = typingMap[convId]?.values?.map { it.first }.orEmpty()
        _typing.update { if (names.isEmpty()) it - convId else it + (convId to names) }
    }

    private fun ack(id: Long, code: Int, value: Long = 0L) =
        Packet(PacketType.ACK, id, selfId, 0L, 0L, Ack(code, value).encode())

    private fun groupAck(groupId: Long, id: Long, seq: Long) =
        Packet(PacketType.ACK, id, selfId, groupId, 0L, Ack(AckCode.DELIVERED, seq).encode())

    private fun newId(): Long {
        var id: Long
        do {
            id = Random.nextLong()
        } while (id == 0L)
        return id
    }

    private companion object {
        const val RECONNECT_ATTEMPTS = 6
        const val PENDING_SORT_BASE = Long.MAX_VALUE / 4
    }
}
