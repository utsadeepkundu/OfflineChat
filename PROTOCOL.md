# OfflineChat wire protocol, version 1

Everything travels over Bluetooth Classic RFCOMM. There is no server and no internet. This file is the contract
between two phones; the reference implementation is `core/PacketCodec.kt`, `core/Payloads.kt` and `engine/ChatEngine.kt`.
All integers are big-endian. Strings are `int32 byteLength` followed by UTF-8 bytes. `bytes` fields use the same
length prefix. `bool` is one byte (0 or 1). `byte` is an unsigned 8-bit value.

## 1. Transport

| Item | Value |
|---|---|
| Service name | `OfflineChat` |
| Secure UUID (paired, encrypted) | `8f0e7c2a-5b1d-4c53-9a43-6d2f4b7e1a10` |
| Insecure UUID (quick connect, no pairing) | `8f0e7c2b-5b1d-4c53-9a43-6d2f4b7e1a10` |

Every phone keeps both server sockets open. A phone connects with the secure UUID when "Quick connect" is off and with the
insecure UUID when it is on. A phone with quick connect off rejects incoming insecure links (except the discovery probe).
There is **one link per pair of phones**. If two links are created at the same time, the one initiated by the phone with
the lower id is kept and the other is closed.

## 2. Packet framing

A packet is a fixed 38-byte header followed by `length` payload bytes.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | version | `1`. A different value closes the link. |
| 1 | 1 | type | See section 3. Unknown types are skipped, so new types can be added. |
| 2 | 4 | length | Payload size, `0..98304` (96 KiB). Larger values close the link. |
| 6 | 8 | messageId | Random id of the message or transfer this packet belongs to. |
| 14 | 8 | senderId | The sender's persistent random id (never 0). |
| 22 | 8 | groupId | `0` for direct chat, otherwise the group's id. |
| 30 | 8 | seq | Group sequence number, assigned by the host. `0` otherwise. |

## 3. Packet types

| Id | Name | Payload |
|---|---|---|
| 1 | HELLO | `long peerId, string name, byte kind, byte protocol, int groupCount, GroupAd[]` |
| 2 | TEXT | `string text, long timestamp, string senderName` |
| 3 | FILE_META | `string name, string mime, long size, bytes sha256, int chunkSize, int chunkCount, long timestamp, string senderName` |
| 4 | FILE_CHUNK | `int index, int crc32, raw data` (the rest of the payload) |
| 5 | ACK | `byte code, long value` |
| 6 | PING | empty |
| 7 | GROUP_CREATE | `GroupMeta`. Used by the host to push metadata changes (rename) to members. |
| 8 | JOIN_REQUEST | `string name, bool rejoin` |
| 9 | JOIN_ACCEPT | `GroupMeta` (the full member list follows in a separate MEMBER_LIST packet) |
| 10 | JOIN_REJECT | `byte reason` |
| 11 | MEMBER_LIST | `int count, MemberInfo[]` |
| 12 | MEMBER_JOINED | `MemberInfo` |
| 13 | MEMBER_LEFT | `long peerId, bool removed` |
| 14 | TYPING | `string name, bool typing` |
| 15 | HISTORY_SYNC | `int count, (long messageId, long senderId, string senderName, long seq, long timestamp, string text)[]` |
| 16 | HOST_MIGRATE | `long newHostId, string newHostName, string newHostAddress` |

Structures:

- `GroupAd` = `long groupId, string name, int argbColor, byte members, byte maxMembers`
- `GroupMeta` = `long groupId, string name, int argbColor, byte maxMembers, long hostId`
- `MemberInfo` = `long peerId, string name, string address, bool online`

Constants: `HELLO.kind` is 0 link, 1 probe, 2 reply. `ACK.code` is 0 delivered, 1 file accept, 2 file complete, 3 file reject,
4 cancel. `JOIN_REJECT.reason` is 1 full, 2 declined, 3 not host, 4 unknown group.

## 4. Handshake

1. The connecting phone sends HELLO (`kind` = link, or probe).
2. The listening phone answers with its own HELLO (`kind` = reply). It lists every group it currently hosts, so a probe
   tells the caller who is nearby and which groups can be joined without ever creating a chat.
3. A probe link is closed after the reply. A normal link becomes ready and starts exchanging packets.
4. The handshake must finish within 10 s. After that, a PING is sent every 8 s of silence and a link that is silent for
   25 s is closed.

## 5. Direct chat

- `TEXT` with `groupId = 0`. The receiver stores it and replies `ACK(code = delivered)` carrying the same `messageId`.
- Message ids make delivery idempotent: a repeated TEXT is acknowledged again but stored once.
- Text that has not been acknowledged (still sending, sent, or failed) is resent in order when the link comes back.
- Order on screen is the receive time. Both phones write messages in the order they arrive on the single link.

## 6. Group chat (host relay, star topology)

One member is the **host**. Every other member keeps one link to the host and no links to each other. Limits: default 6
members, hard cap 7 (a Bluetooth piconet allows 7 active slaves).

**Joining.** The joiner sends JOIN_REQUEST. The host asks its user (or auto-accepts if the setting is on, 90 s timeout means
decline). On accept it replies JOIN_ACCEPT, then MEMBER_LIST, then HISTORY_SYNC with the last N sequenced texts (default 50, split into packets
of at most 48 KiB), and broadcasts MEMBER_JOINED to everyone else. A request from someone who is already a member is accepted without a prompt (this is how members reconnect).

**Sending.** A member sends TEXT with `groupId` set and `seq = 0`. The host:

1. drops the packet if the sender is not a member;
2. looks the `messageId` up in a deduplication table (last 4096 ids). A duplicate gets the original `seq` back and is not
   relayed again;
3. otherwise assigns the next `seq` (starting at 1, strictly increasing);
4. replies `ACK(code = delivered, value = seq)` to the sender and forwards the TEXT, now carrying `seq`, to every other
   online member.

Everyone sorts the group by `seq`, so all phones show the same order. A member's own message that has no `seq` yet sits at
the bottom, marked as sending, until the ack arrives. Unacknowledged messages are resent after a reconnect; the host's
deduplication makes that safe.

**Members.** MEMBER_LIST sends the full list, MEMBER_JOINED and MEMBER_LEFT keep it current. `MEMBER_LEFT.removed = true`
means the person left for good, `false` means they only went out of range.

**Typing.** TYPING is relayed by the host and expires after 4.5 s without a refresh.

**Metadata.** The host sends GROUP_CREATE (a `GroupMeta`) to all members when the group is renamed.

## 7. Host handover

*Graceful.* A host that leaves picks the online member with the lowest id as successor and sends HOST_MIGRATE to everyone.
Members connect to the successor's address and rejoin. The successor restores the member list and the sequence counter from
its local database and continues numbering from the highest `seq` it has seen. If nobody else is in the group, the group ends.

*Crash or out of range.* A member that loses the host retries the same address 4 times, 4 s apart. It then walks the
deterministic candidate list: all known members except the old host, lowest id first. For each candidate:

- unreachable: abandoned after the same retries;
- reachable but answers NOT_HOST: given a grace window (link timeout + retries x delay) so the candidate has time to notice
  and promote itself;
- the candidate that is the member itself: it becomes host.

Because every member walks the same list in the same order, they converge on the same successor. No election messages are
needed. Known limitation: if the group splits so that members cannot all reach the same candidate, two hosts can result.

## 8. Files

Limits: 10 MB in direct chat, 5 MB in groups. Chunks are 8 KiB (a receiver accepts up to 32 KiB). Each chunk carries a CRC-32;
the whole file carries a SHA-256 in FILE_META.

1. Sender: FILE_META (`messageId` identifies the transfer).
2. Receiver: `ACK(file accept, value = firstChunkIndexWanted)`. A direct-chat receiver that holds a `.part` file from an earlier
   attempt answers with the chunk it needs next, which resumes the transfer; otherwise `0`.
   Or `ACK(file reject)` (too large, no space, unknown sender).
3. Sender: FILE_CHUNK packets in index order. Chunks use the low-priority lane, so TEXT, ACK and PING are written first.
4. Receiver: verifies every CRC, appends to disk, and at the end checks the SHA-256. It replies `ACK(file complete)`, or
   `ACK(file reject)` when the hash is wrong.
5. Either side can send `ACK(cancel)` at any time.

In a group the sender's FILE_META and chunks go to the host, which forwards them to the other members as they arrive (it
does not store the whole file first). Each recipient answers the host. A recipient that stalls for 5 s is dropped from that
transfer so it cannot block everyone else.

## 9. Write queues

Each link has two queues drained by one writer. The control queue (TEXT, ACK, PING, membership, typing) is unbounded and is
always emptied first. The bulk queue (FILE_CHUNK) holds 8 packets; a sender that fills it waits, which is the backpressure.
Result: a chat message typed during a file transfer waits behind at most one chunk (8 KiB, about 50 ms of Bluetooth time).

## 10. Compatibility rules

- Never change the meaning of an existing field. Add new packet types or append fields at the end of a payload.
- Unknown packet types are skipped. A different `version` byte closes the link, so bump it only for incompatible changes.
- Ids are random 64-bit values, so two phones will practically never collide.
