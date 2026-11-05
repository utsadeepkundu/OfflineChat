# Testing guide

Testing has two parts: JVM tests for protocol/engine logic and physical-device tests for Android Bluetooth behaviour. Passing one does not imply the other passes.

## JVM unit tests

Run from the project root:

Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

macOS/Linux:

```bash
./gradlew :app:testDebugUnitTest
```

The source currently contains 59 `@Test` methods under `app/src/test`. The tests use in-memory stores and simulated links; they do not require Android hardware.

### Main test areas

| Test area | Coverage focus |
|---|---|
| `core/ProtocolTest` | Packet/payload encoding, round trips, malformed/truncated input, protocol version and size handling. |
| `core/ChunkReassemblerTest` | Chunk ordering, duplicate/out-of-order data, CRC validation, final digest validation, partial transfer behaviour. |
| `core/HostRelayTest` | Group admission, capacity, deduplication, sequence assignment, recipient selection, successor ordering. |
| `net/LinkTest` | Write queue priority, bulk backpressure, concurrent writes. |
| `engine/EngineScenarioTest` | Simulated direct/group flows, joins, file transfer, reconnect and host handover scenarios. |
| `ui/ChatItemsTest` | Chat list item formatting/grouping. |

## Build test

```powershell
.\gradlew.bat assembleDebug
```

A successful debug build confirms the Android source compiles and packages. It does not prove that Bluetooth discovery or connection works on every phone.

## Two-phone smoke test

Use two physical Android phones, ideally from different manufacturers or Android versions.

1. Install the same debug APK on both phones.
2. Turn Bluetooth on. For the first run, keep both apps open and nearby.
3. On Phone A, open **Nearby** and tap **Make me visible**; approve the Android prompt.
4. On Phone B, open **Nearby** and tap **Search**.
5. Confirm the OfflineChat peer is identified and open a direct chat.
6. Send text in both directions and verify acknowledgements.
7. Turn Wi-Fi and mobile data off on both phones; repeat the test with Bluetooth still enabled.
8. Repeat with Quick connect disabled to exercise the secure paired RFCOMM path.
9. Lock one screen and test whether notifications/background delivery behave as expected on both devices.
10. Move one phone out of range and back. Observe reconnect status and pending message delivery.

## Group test

Use three or more physical phones:

1. Create a group on Phone A.
2. Join from Phones B and C, approving join requests on the host.
3. Send messages from all members and verify consistent group order.
4. Send a small file and check that recipients receive it.
5. Have the host leave normally and observe host migration.
6. Separately simulate loss of host connectivity and observe election/reconnect behaviour.

Group membership limit is 7 phones including the host; the default configured capacity is 6.

## File test

- Send a small photo, a PDF, and a generic document in both directions.
- Verify that the received file opens and has the expected size/content.
- Send a text message while a file is transferring to check that control traffic stays responsive.
- Test a file at the configured direct/group limits (10 MB direct, 5 MB group) and one above the limit.
- Interrupt a direct transfer and test resume. Do not assume group transfers have the same resume behaviour.

## Offline and permission test

- Confirm messaging works with Wi-Fi disconnected and mobile data disabled.
- Confirm the app still works with no internet connection available.
- Test Bluetooth permission denial, permission revocation, Bluetooth disabled, and notification permission denial.
- Inspect the built APK's permissions to confirm no internet or Wi-Fi permission is present after manifest merging.

## Record results

For each physical test, record:

- Phone manufacturer/model and Android version
- Whether phones were paired before the test
- Quick connect setting
- Approximate distance and obstructions
- Discovery time and connection success/failure
- Message delivery time and any duplicates
- File size and transfer duration
- Whether the app was foregrounded, backgrounded, or screen-locked
- Battery and reconnect observations

Do not publish a range claim based on one successful test. Repeat measurements in open areas and indoors across several devices.
