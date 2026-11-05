package com.offlinechat.app.engine

import com.offlinechat.app.core.Ack
import com.offlinechat.app.core.AckCode
import com.offlinechat.app.core.ChunkReassembler
import com.offlinechat.app.core.FileChunk
import com.offlinechat.app.core.FileMeta
import com.offlinechat.app.core.Hashing
import com.offlinechat.app.core.HostRelay
import com.offlinechat.app.core.Packet
import com.offlinechat.app.core.PacketType
import com.offlinechat.app.core.Protocol
import com.offlinechat.app.core.Timings
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.ChatStore
import com.offlinechat.app.domain.Conversation
import com.offlinechat.app.domain.ConversationType
import com.offlinechat.app.domain.ImportedFile
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus
import com.offlinechat.app.domain.TransferState
import com.offlinechat.app.net.Link
import com.offlinechat.app.net.LinkManager
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal interface TransferHost {
    val selfId: Long
    val selfName: String
    fun relay(groupId: Long): HostRelay?
    suspend fun conversation(id: Long): Conversation?
    suspend fun ensureLink(conv: Conversation): Link?
    suspend fun ensureDirectConversation(link: Link): Conversation
    suspend fun localSortKey(conv: Conversation): Long
    suspend fun onFileReceived(conv: Conversation, msg: ChatMessage)
    fun notice(text: String)
}

/**
 * Chunked file transfer with integrity checks, progress reporting, cancel and (for direct chats)
 * resume. In groups the host receives from the sender and relays chunk by chunk to every other member.
 */
internal class FileTransfers(
    private val host: TransferHost,
    private val store: ChatStore,
    private val links: LinkManager,
    private val partsDir: File,
    private val receivedDir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val timings: Timings,
    private val clock: () -> Long,
) {
    private class Download(
        val reassembler: ChunkReassembler,
        val convId: Long,
        val fromPeer: Long,
        val relayTargets: MutableList<Long>?,
        val replyComplete: Boolean,
        val size: Long,
    )

    private val uploads = ConcurrentHashMap<Long, Job>()
    private val uploadTargets = ConcurrentHashMap<Long, MutableList<Link>>()
    private val acceptWaiters = ConcurrentHashMap<Long, CompletableDeferred<Long>>()
    private val completeWaiters = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val downloads = ConcurrentHashMap<Long, Download>()
    private val lastReport = ConcurrentHashMap<Long, Long>()

    private val _transfers = MutableStateFlow<Map<Long, TransferState>>(emptyMap())
    val transfers: StateFlow<Map<Long, TransferState>> = _transfers.asStateFlow()

    // ---------------------------------------------------------------- sending

    suspend fun sendFile(conv: Conversation, file: ImportedFile) {
        val limit = if (conv.type == ConversationType.GROUP) Protocol.MAX_GROUP_FILE_BYTES else Protocol.MAX_DIRECT_FILE_BYTES
        if (file.size <= 0 || file.size > limit) {
            host.notice("Files must be between 1 byte and ${limit / (1024 * 1024)} MB in this chat.")
            file.file.delete()
            return
        }
        val now = clock()
        val msg = ChatMessage(
            id = newId(),
            conversationId = conv.id,
            senderId = host.selfId,
            senderName = host.selfName,
            kind = MessageKind.FILE,
            fileName = file.name,
            fileMime = file.mime,
            fileSize = file.size,
            filePath = file.file.absolutePath,
            status = MessageStatus.SENDING,
            outgoing = true,
            sortKey = host.localSortKey(conv),
            timestamp = now,
        )
        store.insertMessage(msg)
        store.recordActivity(conv.id, "📎 ${file.name}", now, 0)
        startUpload(conv, msg, file.sha256)
    }

    suspend fun retry(msg: ChatMessage, conv: Conversation) {
        if (!msg.outgoing || msg.kind != MessageKind.FILE || uploads.containsKey(msg.id)) return
        startUpload(conv, msg, null)
    }

    fun cancel(messageId: Long) {
        val upload = uploads[messageId]
        if (upload != null) {
            uploadTargets[messageId]?.forEach { it.send(ack(messageId, AckCode.CANCEL)) }
            upload.cancel()
            return
        }
        val d = downloads.remove(messageId) ?: return
        d.reassembler.discard()
        links.linkFor(d.fromPeer)?.send(ack(messageId, AckCode.CANCEL))
        scope.launch(io) { markFailed(messageId) }
    }

    private fun startUpload(conv: Conversation, msg: ChatMessage, knownSha: ByteArray?) {
        uploads[msg.id]?.cancel()
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            try {
                store.updateStatus(msg.id, MessageStatus.SENDING)
                upload(conv, msg, knownSha)
            } catch (e: TimeoutCancellationException) {
                withContext(NonCancellable) { markFailed(msg.id) }
                host.notice("The other device didn't respond, so the file wasn't sent.")
            } catch (e: CancellationException) {
                withContext(NonCancellable) { markFailed(msg.id) }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) { markFailed(msg.id) }
                host.notice("File transfer failed: ${e.message ?: "connection lost"}")
            } finally {
                uploads.remove(msg.id)
                uploadTargets.remove(msg.id)
            }
        }
        uploads[msg.id] = job
        job.start()
    }

    private suspend fun upload(conv: Conversation, msg: ChatMessage, knownSha: ByteArray?) {
        val file = File(msg.filePath ?: throw IOException("File missing"))
        if (!file.exists()) throw IOException("File is no longer available")
        val size = file.length()
        val sha = knownSha ?: Hashing.sha256(file)
        val groupId = if (conv.type == ConversationType.GROUP) conv.id else 0L
        val relay = if (conv.type == ConversationType.GROUP && conv.isHost) host.relay(conv.id) else null
        val chunkSize = Protocol.FILE_CHUNK_SIZE
        val count = FileMeta.chunkCountFor(size, chunkSize)
        val meta = FileMeta(msg.fileName ?: file.name, msg.fileMime ?: "application/octet-stream", size, sha, chunkSize, count, msg.timestamp, host.selfName)

        val targets: MutableList<Link>
        var resume = 0
        var completion: CompletableDeferred<Unit>? = null
        if (relay != null) {
            targets = relay.recipients().mapNotNull { links.linkFor(it) }.toMutableList()
            if (targets.isEmpty()) throw IOException("Nobody is online to receive this file")
            uploadTargets[msg.id] = targets
            val seq = relay.sequence(msg.id).seq
            store.markSequenced(msg.id, seq, MessageStatus.SENDING)
            val packet = Packet(PacketType.FILE_META, msg.id, host.selfId, groupId, seq, meta.encode())
            targets.forEach { it.send(packet) }
        } else {
            val link = host.ensureLink(conv) ?: throw IOException("Not connected")
            targets = mutableListOf(link)
            uploadTargets[msg.id] = targets
            val accept = CompletableDeferred<Long>().also { acceptWaiters[msg.id] = it }
            completion = CompletableDeferred<Unit>().also { completeWaiters[msg.id] = it }
            link.send(Packet(PacketType.FILE_META, msg.id, host.selfId, groupId, 0, meta.encode()))
            resume = try {
                withTimeout(timings.fileAcceptMs) { accept.await() }.toInt()
            } finally {
                acceptWaiters.remove(msg.id)
            }
        }

        report(msg.id, minOf(size, resume.toLong() * chunkSize), size, incoming = false, force = true)
        RandomAccessFile(file, "r").use { raf ->
            var index = resume.coerceIn(0, count)
            raf.seek(index.toLong() * chunkSize)
            val buffer = ByteArray(chunkSize)
            while (index < count) {
                val length = minOf(chunkSize.toLong(), size - index.toLong() * chunkSize).toInt()
                raf.readFully(buffer, 0, length)
                val data = buffer.copyOf(length)
                val packet = Packet(
                    PacketType.FILE_CHUNK, msg.id, host.selfId, groupId, 0,
                    FileChunk(index, Hashing.crc32(data), data).encode(),
                )
                if (relay != null) {
                    sendToTargets(msg.id, packet, targets)
                    if (targets.isEmpty()) throw IOException("Every member dropped out of the transfer")
                } else {
                    targets[0].sendBulk(packet)
                }
                index++
                report(msg.id, minOf(size, index.toLong() * chunkSize), size, incoming = false)
            }
        }
        report(msg.id, size, size, incoming = false, force = true)

        if (completion != null) {
            try {
                withTimeout(timings.fileCompleteMs) { completion.await() }
            } finally {
                completeWaiters.remove(msg.id)
            }
        }
        store.updateStatus(msg.id, MessageStatus.DELIVERED)
        clear(msg.id)
    }

    /** Sends to every target; one that cannot keep up for [Timings.relayStallMs] is dropped from the transfer. */
    private suspend fun sendToTargets(fileId: Long, packet: Packet, targets: MutableList<Link>) {
        for (target in targets.toList()) {
            val ok = try {
                withTimeoutOrNull(timings.relayStallMs) { target.sendBulk(packet) } != null
            } catch (e: IOException) {
                false
            }
            if (!ok) {
                targets.remove(target)
                target.send(ack(fileId, AckCode.CANCEL))
            }
        }
    }

    // -------------------------------------------------------------- receiving

    suspend fun onMeta(link: Link, p: Packet) {
        val meta = FileMeta.decode(p.payload)
        val isGroup = p.groupId != 0L
        val relay = if (isGroup) host.relay(p.groupId) else null
        val conv: Conversation
        if (!isGroup) {
            conv = host.ensureDirectConversation(link)
        } else if (relay != null) {
            if (p.senderId != link.peerId || !relay.isMember(link.peerId)) return
            conv = host.conversation(p.groupId) ?: return
        } else {
            conv = host.conversation(p.groupId) ?: return
            if (conv.hostId != link.peerId) return
        }
        val replies = !isGroup || relay != null

        val limit = if (isGroup) Protocol.MAX_GROUP_FILE_BYTES else Protocol.MAX_DIRECT_FILE_BYTES
        val valid = meta.size in 1..limit &&
            meta.chunkSize in 1..Protocol.MAX_FILE_CHUNK_SIZE &&
            meta.chunkCount == FileMeta.chunkCountFor(meta.size, meta.chunkSize) &&
            meta.sha256.size == 32
        if (!valid) {
            if (replies) link.send(ack(p.messageId, AckCode.FILE_REJECT))
            return
        }

        val existing = store.getMessage(p.messageId)
        if (existing != null && !existing.outgoing && existing.status == MessageStatus.DELIVERED && existing.filePath?.let { File(it).exists() } == true) {
            if (replies) {
                link.send(ack(p.messageId, AckCode.FILE_ACCEPT, meta.chunkCount.toLong()))
                link.send(ack(p.messageId, AckCode.FILE_COMPLETE))
            }
            return
        }

        val seq = relay?.sequence(p.messageId)?.seq ?: p.seq
        val part = File(partsDir, "${p.messageId}.part")
        if (isGroup) part.delete()
        val reassembler = ChunkReassembler(part, meta.size, meta.chunkSize, meta.chunkCount, meta.sha256)
        val authorId = if (isGroup) p.senderId else link.peerId
        val authorName = meta.senderName.ifBlank { link.peerName }
        val now = clock()
        val message = ChatMessage(
            id = p.messageId,
            conversationId = conv.id,
            senderId = authorId,
            senderName = authorName,
            kind = MessageKind.FILE,
            fileName = safeName(meta.name),
            fileMime = meta.mime,
            fileSize = meta.size,
            status = MessageStatus.SENDING,
            outgoing = false,
            seq = seq,
            sortKey = if (seq > 0) seq * 1000 else now,
            timestamp = now,
        )
        if (existing == null) store.insertMessage(message) else store.updateStatus(message.id, MessageStatus.SENDING)

        val targets = relay?.recipients(except = link.peerId)?.toMutableList()
        downloads.put(p.messageId, Download(reassembler, conv.id, link.peerId, targets, replies, meta.size))?.reassembler?.close()
        report(p.messageId, reassembler.bytesReceived, meta.size, incoming = true, force = true)

        if (!isGroup) {
            link.send(ack(p.messageId, AckCode.FILE_ACCEPT, reassembler.nextChunk.toLong()))
        } else if (relay != null) {
            link.send(ack(p.messageId, AckCode.FILE_ACCEPT, 0))
            val forwarded = Packet(PacketType.FILE_META, p.messageId, p.senderId, p.groupId, seq, p.payload)
            targets?.forEach { links.linkFor(it)?.send(forwarded) }
        }
        if (reassembler.isComplete) finish(p.messageId, link)
    }

    suspend fun onChunk(link: Link, p: Packet) {
        val d = downloads[p.messageId] ?: return
        if (d.fromPeer != link.peerId) return
        val chunk = FileChunk.decode(p.payload)
        when (d.reassembler.accept(chunk.index, chunk.crc, chunk.data)) {
            ChunkReassembler.Result.ACCEPTED -> Unit
            ChunkReassembler.Result.DUPLICATE -> return
            else -> {
                abort(p.messageId, notifySender = d.replyComplete, rejected = true)
                host.notice("A file arrived damaged and was discarded.")
                return
            }
        }
        d.relayTargets?.let { forward(p, it) }
        report(p.messageId, d.reassembler.bytesReceived, d.size, incoming = true)
        if (d.reassembler.isComplete) finish(p.messageId, link)
    }

    private suspend fun forward(p: Packet, targets: MutableList<Long>) {
        for (peerId in targets.toList()) {
            val link = links.linkFor(peerId)
            val ok = link != null && try {
                withTimeoutOrNull(timings.relayStallMs) { link.sendBulk(p) } != null
            } catch (e: IOException) {
                false
            }
            if (!ok) {
                targets.remove(peerId)
                link?.send(ack(p.messageId, AckCode.CANCEL))
            }
        }
    }

    private suspend fun finish(id: Long, link: Link) {
        val d = downloads.remove(id) ?: return
        val message = store.getMessage(id) ?: return
        if (!d.reassembler.verify()) {
            d.reassembler.discard()
            markFailed(id)
            if (d.replyComplete) link.send(ack(id, AckCode.FILE_REJECT))
            host.notice("A received file failed its integrity check and was discarded.")
            return
        }
        val target = uniqueFile(receivedDir, message.fileName ?: "file")
        if (!d.reassembler.moveTo(target)) {
            markFailed(id)
            if (d.replyComplete) link.send(ack(id, AckCode.FILE_REJECT))
            return
        }
        store.completeFile(id, target.absolutePath, MessageStatus.DELIVERED)
        clear(id)
        if (d.replyComplete) link.send(ack(id, AckCode.FILE_COMPLETE))
        host.conversation(d.convId)?.let { host.onFileReceived(it, message.copy(filePath = target.absolutePath, status = MessageStatus.DELIVERED)) }
    }

    // ------------------------------------------------------------------- acks

    fun onAck(link: Link, p: Packet, ack: Ack) {
        val id = p.messageId
        when (ack.code) {
            AckCode.FILE_ACCEPT -> acceptWaiters.remove(id)?.complete(ack.value)
            AckCode.FILE_COMPLETE -> completeWaiters.remove(id)?.complete(Unit)
            AckCode.FILE_REJECT, AckCode.CANCEL -> {
                val failure = IOException("The other device cancelled or rejected the file")
                acceptWaiters.remove(id)?.completeExceptionally(failure)
                completeWaiters.remove(id)?.completeExceptionally(failure)
                uploads[id]?.cancel()
                val d = downloads[id]
                if (d != null && d.fromPeer == link.peerId) {
                    scope.launch(io) { abort(id, notifySender = false, rejected = false) }
                }
            }
        }
    }

    fun onLinkDown(peerId: Long) {
        downloads.entries.filter { it.value.fromPeer == peerId }.forEach { (id, d) ->
            downloads.remove(id)
            d.reassembler.close() // keep the .part file so a direct transfer can resume
            scope.launch(io) { markFailed(id) }
        }
        uploadTargets.entries.filter { (_, t) -> t.size == 1 && t[0].peerId == peerId }.forEach { (id, _) ->
            uploads[id]?.cancel()
        }
    }

    private suspend fun abort(id: Long, notifySender: Boolean, rejected: Boolean) {
        val d = downloads.remove(id) ?: return
        d.reassembler.discard()
        if (notifySender) links.linkFor(d.fromPeer)?.send(ack(id, if (rejected) AckCode.FILE_REJECT else AckCode.CANCEL))
        d.relayTargets?.forEach { links.linkFor(it)?.send(ack(id, AckCode.CANCEL)) }
        markFailed(id)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun markFailed(id: Long) {
        store.updateStatus(id, MessageStatus.FAILED)
        clear(id)
    }

    private fun report(id: Long, done: Long, total: Long, incoming: Boolean, force: Boolean = false) {
        val now = clock()
        if (!force && done < total && now - (lastReport[id] ?: 0L) < 80) return
        lastReport[id] = now
        _transfers.update { it + (id to TransferState(id, done, total, incoming)) }
    }

    private fun clear(id: Long) {
        lastReport.remove(id)
        _transfers.update { it - id }
    }

    private fun ack(id: Long, code: Int, value: Long = 0L) =
        Packet(PacketType.ACK, id, host.selfId, 0, 0, Ack(code, value).encode())

    private fun newId(): Long {
        var id: Long
        do {
            id = Random.nextLong()
        } while (id == 0L)
        return id
    }

    private fun safeName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ ()\\-]"), "_")
            .trim().take(80).ifBlank { "file" }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }
}
