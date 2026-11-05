# OfflineChat

Chat with people nearby over Bluetooth. No internet, no mobile network, no Wi-Fi, no account, no server. One-to-one chats,
groups of up to 7 people who write at the same time, and small file sharing (photos, PDFs, documents) inside the chat.

Kotlin, Jetpack Compose, Material 3, MVVM, Coroutines and Flow, Hilt, Room, Navigation Compose. minSdk 26, targetSdk 35.
The app declares **no INTERNET permission** and the manifest actively removes it if a library tries to add one.

> **Read this first.** The protocol, chat engine, host relay, file transfer and failover logic (`core/`, `net/`, `engine/`,
> `domain/`) are pure Kotlin and are covered by 59 JVM unit and scenario tests that run two to seven simulated phones inside one
> process. The Android layers (Room, Bluetooth, service, Compose UI) were written without an Android SDK available, so they have
> **never been compiled or run on a device**. Expect a handful of small compile errors on the first build (an import, a renamed
> API) and fix them in Android Studio. Real Bluetooth timing also differs from the simulation, so test on two real phones before
> relying on it. Section 6 lists what to check.

## 1. Build and run

1. Install Android Studio (Ladybug or newer) with the Android 15 (API 35) SDK. JDK 17 is bundled.
2. Open the `OfflineChat` folder. Android Studio downloads Gradle 8.10.2 and syncs.
   The Gradle wrapper (`gradlew`, `gradlew.bat`, wrapper jar) is included, so `./gradlew` works too.
3. Plug in a phone (Bluetooth does not work in the emulator) and press Run.
4. Release build: set up your own keystore in `app/build.gradle.kts`. It currently signs with the debug key so that
   `./gradlew assembleRelease` works out of the box. R8 minification and resource shrinking are on.

Run the logic tests without Android: `./gradlew :app:testDebugUnitTest`.

## 2. Using the app

- **Two phones, one chat.** Open OfflineChat on both. On phone A tap *Nearby*, then *Make me visible* (needed once if the phones
  were never paired). On phone B tap *Nearby*, then *Search*. Phone A appears with an OfflineChat badge. Tap *Chat*.
- **Group.** *Chats*, then the group icon. Choose a name, colour and maximum size. Others find you under *Nearby* (you appear
  with your group and a *Join* button). You get an Accept or Decline dialog for each person unless you turned on
  auto-accept. Your phone is the host: keep it on and near the others.
- **Files.** Paperclip in the chat. Up to 10 MB in a one-to-one chat, 5 MB in a group. Big photos are shrunk first.
- **Quick connect** (Settings, on by default): connect without a pairing prompt. Links are not encrypted over the air. Turn it
  off to require pairing, which encrypts the link.

## 3. Verify it really has no network access

```
./gradlew assembleDebug
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```
The list must not contain `android.permission.INTERNET`, `ACCESS_NETWORK_STATE` or any Wi-Fi permission. You can also turn
on airplane mode, then Bluetooth only, and chat.

## 4. Architecture

```
app/src/main/java/com/offlinechat/app/
  core/       Pure Kotlin. Packet codec, payloads, chunk reassembly (CRC32 + SHA-256), host relay and election rules.
  domain/     Models and the ports the engine needs: Transport, ChatStore, IdentityProvider, EngineSettings.
  net/        Link (one connection: priority write queues, keep-alive) and LinkManager (handshake, one link per peer).
  engine/     ChatEngine (direct and group logic, failover) and FileTransfers (streaming send, receive, resume, relay).
  data/       Room database, RoomChatStore, preferences, FileImporter.
  bluetooth/  RFCOMM transport, discovery plus probing, adapter and permission state.
  service/    Foreground service, notifications.
  ui/         Compose screens: chat, chatlist, nearby, groups, files, settings, onboarding, navigation, theme.
  di/         Hilt module.
```
The engine never touches Android. It talks to a `Transport` and a `ChatStore`. In production those are Bluetooth and Room, in
tests they are in-memory pipes and a map. That is why the hard parts (ordering, deduplication, relay, failover) are tested
without hardware. Wire format: see **PROTOCOL.md**.

### Why it is fast
- Binary protocol with a 38-byte header, no JSON, no reflection, no serialisation library.
- One writer per link with a priority lane: text and acks always jump ahead of file chunks (a message typed during a transfer
  waits for at most one 8 KB chunk). The file lane is bounded, so memory stays flat.
- Files are streamed from and to disk in 8 KB chunks. They are never loaded into memory.
- Photos over 600 KB are shrunk to 1600 px JPEG before sending: a 5 MB photo becomes about 300 KB, roughly 20 times less airtime.
- Compose list uses a reversed `LazyColumn` with stable keys and content types, a paged Room query (120 at a time), list
  building on `Dispatchers.Default`, thumbnails decoded off the main thread with an LRU cache.
- Progress updates are throttled to every 80 ms.
- R8 and resource shrinking, plus a baseline profile (`app/src/main/baseline-prof.txt`).

## 5. Known limitations (read before promising anything to users)

- **Range** is about 10 to 30 m in the open, much less through walls and bodies.
- **At most 7 phones in one group** and throughput is shared. Bluetooth Classic gives roughly 100 to 200 KB/s per link in the
  best case, so a 5 MB group file takes a while and slows everyone.
- **The host is a single point of failure.** If the host leaves normally, another member takes over automatically. If the host
  crashes, members elect the same successor from a fixed list. If the group is split so that members cannot all reach the same
  candidate, two hosts can appear. Messages sent while no host is reachable are kept and delivered when one is found.
- **Late joiners get recent text only** (default 50 messages), not old files.
- **Discoverability**: Android only lets an app make a phone visible for a limited time and only after the user taps OK (the
  *Make me visible* button). Phones that were paired once can find each other without it. BLE advertising, which would remove
  this step, was deliberately left out to keep one reliable transport; it is the first thing to add.
- **Quick connect is not encrypted.** Anyone within range running the app can connect (and a host still approves group joins).
  Turn quick connect off to use encrypted, paired links.
- No end-to-end encryption, no message editing or deletion on other phones, no read receipts (ticks mean delivered to the
  other phone, or to the host in a group).
- Android 14 and newer require the foreground service to run while Bluetooth permission is granted. If the user revokes it,
  the service stops.
- UI strings are hard-coded in English. Move them to `strings.xml` for translation.
- `fallbackToDestructiveMigration()` is on in `AppDatabase` for development: schema changes erase chat history. Write real
  Room migrations before shipping.
- Bluetooth behaviour varies by manufacturer. Some phones need a second attempt on the first connection (the transport already
  retries once).

## 6. First-run checklist on real phones

1. Build compiles. Fix any import or API-name errors (Material 3 and Compose versions are pinned in `gradle/libs.versions.toml`).
2. Grant the Nearby devices permission, switch Bluetooth on, finish onboarding. A "OfflineChat is active" notification appears.
3. Chat both ways between two phones, then lock one screen and send again (notification should arrive).
4. Send a 2 MB photo and a PDF both ways. Type a message while a file is moving. It should appear immediately.
5. Walk out of range and back. The banner shows *Reconnecting* and the chat resumes by itself.
6. Create a group with three phones. All three write at once and see the same order.
7. Close the app on the host phone (swipe away and stop the notification). Members should move to a new host within about 30 s.

## 7. Tests

`app/src/test/...` (JVM only, no device):
- `core/ProtocolTest`: packet and payload round trips, split streams, truncated or corrupted data, wrong version, oversized length, unknown types, history splitting.
- `core/ChunkReassemblerTest`: in-order assembly, duplicate, out-of-order and bad-CRC chunks, final SHA-256 check, resume after an interruption.
- `core/HostRelayTest`: sequence numbers, deduplication, capacity, recipients, member removal, restore after migration, election order.
- `net/LinkTest`: text overtakes queued file chunks, bounded bulk lane applies backpressure, concurrent senders never interleave bytes.
- `engine/EngineScenarioTest`: 22 end-to-end scenarios on simulated Bluetooth (ordering, groups, join approval, files with
  resume, text during a throttled file transfer, graceful host hand-over, host crash election, reconnect).
- `ui/ChatItemsTest`: day headers, sender grouping, bubble tails.

## 8. Five good next improvements

1. **BLE advertising and scanning** so nearby OfflineChat phones appear without the *Make me visible* step.
2. **End-to-end encryption** (X25519 key exchange on first contact, ChaCha20-Poly1305 per packet) so quick connect is private too.
3. **Multi-hop mesh relay**: let members forward for each other so a group can stretch beyond one phone's range and past 7 people.
4. **Voice notes and image previews** sent as small Opus and thumbnail packets, with a hold-to-record button.
5. **Message search, replies, reactions and group admin roles** (several hosts, remove member, pinned messages), plus a real Room
   migration setup and translated strings.
