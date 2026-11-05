# Roadmap and known limitations

This roadmap separates the current implementation from possible future development. Items below are not promises or claims that the features already exist.

## Current implementation

- Bluetooth Classic RFCOMM transport.
- Nearby Classic Bluetooth discovery and OfflineChat handshake probe.
- One-to-one text chat.
- Host-relayed group chat with a maximum of 7 phones including the host.
- Group history sync for recent text messages.
- File transfers with chunk checksums and final SHA-256 verification.
- Local Room history and a connected-device foreground service.
- JVM unit/scenario tests for protocol, link, relay, reassembly, and engine behaviour.

## Known limitations

- No BLE advertising or scanning implementation. BLE permissions in the manifest do not mean BLE is a working transport.
- No Wi-Fi Direct transport.
- No LoRa or other external radio hardware integration.
- No arbitrary multi-hop mesh routing. Group messages are relayed by one group host in a star topology.
- Quick connect defaults to an unencrypted Bluetooth RFCOMM socket.
- No application-level end-to-end encryption or cryptographic peer verification.
- No application-level encryption for local database/files.
- Group host is a critical dependency. Host migration is implemented, but a network partition can lead to split-brain group state.
- Bluetooth discovery and background behaviour vary across Android versions and manufacturers.
- Database schema migrations and user-facing localization need work before a stable public release.

## Suggested priorities

### P0 — Hardening before wider testing

- Test two-phone direct messaging across multiple Android versions and manufacturers.
- Test group membership, reconnect, host leave, and host loss with 3–7 physical phones.
- Verify the merged APK permission list and document observed battery/range behaviour.
- Replace destructive Room migration fallback with versioned migrations.
- Replace debug signing in the release build type with a private release keystore workflow.

### P1 — Security and reliability

- Threat model and implement authenticated key agreement and end-to-end authenticated encryption.
- Add cryptographic identity verification and device/key rotation/revocation.
- Protect local database and received file data where appropriate.
- Add abuse controls, safe connection approval, and structured diagnostics without logging private message content.

### P2 — Discovery and transfer improvements

- Evaluate BLE advertising/scanning as a discovery enhancement, while retaining a well-defined data transport.
- Improve reconnection and background behaviour with device-specific testing.
- Add message search, replies, reactions, and group administration.
- Add accessibility improvements and translated string resources.

### P3 — Optional transport experiments

- Evaluate Wi-Fi Direct for faster local data transfer where supported.
- Explore a separate external LoRa bridge (for example, an ESP32-based companion device) if long-range, low-bandwidth messaging is a project requirement.
- Measure hardware, regulatory, battery, throughput, and interoperability constraints before committing to any radio extension.

## Product boundary

OfflineChat is a nearby Bluetooth messenger, not a cellular replacement. A phone must be in Bluetooth range of the peer it connects to, or be connected as a member of a supported group through that group's host. Do not represent the current app as providing long-distance, region-wide, or emergency communications coverage.
