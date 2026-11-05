package com.offlinechat.app.net

import com.offlinechat.app.core.GroupAd
import com.offlinechat.app.core.Hello
import com.offlinechat.app.core.HelloKind
import com.offlinechat.app.core.Packet
import com.offlinechat.app.core.PacketType
import com.offlinechat.app.core.Timings
import com.offlinechat.app.domain.EngineSettings
import com.offlinechat.app.domain.IdentityProvider
import com.offlinechat.app.domain.RawConnection
import com.offlinechat.app.domain.Transport
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the set of live links: accepts incoming connections, performs the HELLO handshake,
 * guarantees a single link per peer, and hands everything else to the [Handler].
 */
class LinkManager(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val identity: IdentityProvider,
    private val settings: EngineSettings,
    private val timings: Timings,
    private val groupAds: () -> List<GroupAd>,
    private val handler: Handler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Handler {
        suspend fun onPacket(link: Link, packet: Packet)
        fun onLinkUp(link: Link)
        fun onLinkDown(link: Link)
    }

    private val links = ConcurrentHashMap<Long, Link>()
    private val connecting = HashMap<String, Deferred<Link>>()
    private val connectLock = Mutex()

    fun start() {
        transport.startListening(scope) { raw -> accept(raw) }
    }

    fun stop() {
        transport.stopListening()
        links.values.toList().forEach { it.close() }
    }

    fun linkFor(peerId: Long): Link? = links[peerId]?.takeIf { it.isAlive }

    fun linkForAddress(address: String): Link? = links.values.firstOrNull { it.isAlive && it.remoteAddress == address }

    fun livePeers(): Set<Long> = links.values.filter { it.isAlive }.map { it.peerId }.toSet()

    /** Returns the existing link to [address] or opens (and handshakes) a new one. */
    suspend fun connect(address: String): Link {
        linkForAddress(address)?.let { return it }
        val attempt = connectLock.withLock {
            connecting[address] ?: scope.async(io) { openLink(address) }.also { connecting[address] = it }
        }
        try {
            return attempt.await()
        } finally {
            connectLock.withLock { if (connecting[address] === attempt) connecting.remove(address) }
        }
    }

    /** Asks a device whether it runs OfflineChat and which groups it hosts, without opening a chat link. */
    suspend fun probe(address: String): Hello? {
        val raw = try {
            transport.connect(address, secure = false)
        } catch (e: IOException) {
            return null
        }
        val link = newLink(raw, outgoing = true)
        link.probeOnly = true
        link.start()
        link.send(helloPacket(HelloKind.PROBE))
        val reply = try {
            withTimeoutOrNull(timings.handshakeMs) { link.handshake.await() }
        } catch (e: Exception) {
            null
        }
        link.close()
        return reply
    }

    private suspend fun openLink(address: String): Link {
        val raw = transport.connect(address, secure = !settings.quickConnect)
        val link = newLink(raw, outgoing = true)
        link.start()
        link.send(helloPacket(HelloKind.LINK))
        val reply = try {
            withTimeout(timings.handshakeMs) { link.handshake.await() }
        } catch (e: Exception) {
            link.close()
            throw IOException("Handshake failed")
        }
        return links[reply.peerId]?.takeIf { it.isAlive } ?: throw IOException("Link lost during handshake")
    }

    private fun accept(raw: RawConnection) {
        val link = newLink(raw, outgoing = false)
        link.start()
        scope.launch {
            delay(timings.handshakeMs)
            if (!link.ready && !link.probeOnly && link.isAlive) link.close()
        }
    }

    private fun newLink(raw: RawConnection, outgoing: Boolean) = Link(
        raw = raw,
        outgoing = outgoing,
        scope = scope,
        io = io,
        timings = timings,
        clock = clock,
        dispatch = ::dispatch,
        onClosed = ::linkClosed,
    )

    private fun helloPacket(kind: Int, groups: List<GroupAd> = emptyList()) = Packet(
        PacketType.HELLO,
        senderId = identity.selfId,
        payload = Hello(identity.selfId, identity.selfName, kind, groups).encode(),
    )

    private suspend fun dispatch(link: Link, packet: Packet) {
        if (packet.type == PacketType.HELLO) {
            handleHello(link, packet)
            return
        }
        if (!link.ready) return
        // Direct-chat packets must come from the peer on the other end of this link.
        if (packet.groupId == 0L && packet.senderId != link.peerId) return
        handler.onPacket(link, packet)
    }

    private fun handleHello(link: Link, packet: Packet) {
        val hello = Hello.decode(packet.payload)
        if (hello.peerId == identity.selfId || hello.peerId == 0L) {
            link.close()
            return
        }
        if (link.outgoing) {
            if (hello.kind != HelloKind.REPLY) {
                link.close()
                return
            }
            link.hello = hello
            link.peerId = hello.peerId
            link.peerName = hello.name
            if (link.probeOnly) {
                link.handshake.complete(hello)
            } else {
                complete(link, hello)
            }
            return
        }
        link.hello = hello
        link.peerId = hello.peerId
        link.peerName = hello.name
        when (hello.kind) {
            HelloKind.PROBE -> {
                link.probeOnly = true
                link.send(helloPacket(HelloKind.REPLY, groupAds()))
                link.closeSoon()
            }
            HelloKind.LINK -> {
                if (!link.raw.secure && !settings.quickConnect) {
                    link.close()
                    return
                }
                link.send(helloPacket(HelloKind.REPLY, groupAds()))
                complete(link, hello)
            }
            else -> link.close()
        }
    }

    private fun complete(link: Link, hello: Hello) {
        val survivor = register(link)
        if (survivor === link) {
            link.ready = true
            link.handshake.complete(hello)
            handler.onLinkUp(link)
        } else {
            link.handshake.complete(hello)
        }
    }

    /**
     * One link per peer. If two links race (both sides connected at once) both devices keep the one
     * opened by the peer with the lower id, so they converge on the same socket.
     */
    @Synchronized
    private fun register(link: Link): Link {
        val existing = links[link.peerId]
        if (existing == null || !existing.isAlive) {
            links[link.peerId] = link
            return link
        }
        val keepNew = initiator(link) < initiator(existing)
        return if (keepNew) {
            links[link.peerId] = link
            existing.superseded()
            link
        } else {
            link.superseded()
            existing
        }
    }

    private fun initiator(link: Link): Long = if (link.outgoing) identity.selfId else link.peerId

    private fun Link.superseded() {
        probeOnly = true // suppresses link-down handling for the loser
        closeSoon()
    }

    private fun linkClosed(link: Link) {
        if (link.peerId == 0L || link.probeOnly) return
        if (links.remove(link.peerId, link)) handler.onLinkDown(link)
    }
}
