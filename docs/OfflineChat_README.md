# OfflineChat

**Offline, nearby messaging for Android over Bluetooth Classic.**

OfflineChat is an experimental Android messaging app for exchanging messages and small files when an internet connection, mobile data, and a Wi-Fi router are unavailable. Phones communicate directly over Bluetooth; there is no account system, cloud backend, or messaging server.

> **Project status:** The app has been built and launched on a physical Samsung Android phone. Multi-device behaviour still needs systematic testing on multiple phone models. Treat this project as experimental software, not as an emergency communications service or a production-secure messenger.

## What it currently does

- **Direct chats:** one-to-one text messaging between nearby OfflineChat phones.
- **Group chats:** groups use a host-centred star topology. Each member connects to the host, and the host relays group messages. The default group capacity is 6 devices; the hard limit is 7 devices, including the host.
- **Nearby discovery:** uses Android Bluetooth Classic discovery, then probes discovered devices to identify OfflineChat peers and groups.
- **File sharing:** sends photos, PDFs, and other files. The maximum is 10 MB in direct chats and 5 MB in group chats.
- **Transfer handling:** file data is chunked, each chunk is checked with CRC-32, and the complete file is checked with SHA-256. Direct transfers can resume from a partial file.
- **Local history:** conversations and messages are stored locally with Room/SQLite.
- **Background operation:** a connected-device foreground service keeps the chat engine active while Android allows it and displays notifications.
- **Settings:** display name, quick connect, group join approval, history size for new members, theme, notifications, haptics, and image shrinking.
- **No internet dependency:** the app manifest removes the `INTERNET` permission and also removes network-state and Wi-Fi permissions.

## Important: transport and range

The current implementation uses **Bluetooth Classic RFCOMM**, not Bluetooth Low Energy (BLE). It does not currently implement BLE advertising/scanning, Wi-Fi Direct, LoRa, or a general-purpose multi-hop mesh network.

The app's group relay is a **single-host star topology**: group members send to the host, and the host forwards group messages. This is not the same as arbitrary phone-to-phone mesh routing across multiple hops.

Real-world range depends on the phones, antenna design, environment, interference, and obstacles. The approximate 10–30 metre figure shown in the app is only a rough expectation, not a guaranteed operating range. Measure range and delivery reliability on the devices you intend to support.

## Screens and features

The app includes onboarding, a chat list, nearby device discovery, one-to-one chat, group creation and group details, file transfer/history, and settings. UI strings are currently English-only.

## Technology stack

| Area | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM, repository/port-style boundaries |
| Async work | Kotlin Coroutines + Flow |
| Dependency injection | Hilt |
| Local database | Room over SQLite |
| Nearby transport | Android Bluetooth Classic RFCOMM sockets |
| Build | Gradle wrapper + Android Gradle Plugin |
| Tests | JUnit 4 and Kotlin Coroutines Test |

**Android compatibility:** `minSdk 26` (Android 8.0), `compileSdk 35`, `targetSdk 35`. Java source/bytecode compatibility is configured for Java 17, but the app source code is Kotlin. The project is not a Java rewrite.

## Requirements

- Android Studio (a current stable release)
- JDK 17 or a compatible newer JDK selected in Android Studio's Gradle settings
- Android SDK Platform 35 and the corresponding build tools
- A physical Android phone with Bluetooth for hardware testing
- A second Android phone for end-to-end Bluetooth testing

An emulator can be useful for some UI work, but it is not a substitute for real Bluetooth testing.

## Build and run

1. Clone the repository or download the source ZIP.
2. Open the repository's `OfflineChat` root folder in Android Studio (the folder containing `settings.gradle.kts`).
3. Allow Gradle sync to finish and install any requested Android SDK components.
4. Connect a physical Android phone with USB debugging enabled, select the `app` run configuration, and press **Run**.

### Build from the command line

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

macOS/Linux:

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) for a more detailed setup and installation guide.

## First two-phone test

1. Build the debug APK and install it on both Android phones.
2. Turn Bluetooth on and open OfflineChat on both phones.
3. On Phone A, open **Nearby** and tap **Make me visible**. Android will ask the user to allow temporary Bluetooth discoverability.
4. On Phone B, open **Nearby** and tap **Search**.
5. Wait for Phone A to appear with the OfflineChat indicator, then tap **Chat**.
6. Send a message in both directions.
7. Repeat with Wi-Fi and mobile data turned off on both phones, while leaving Bluetooth on.

For troubleshooting, see [docs/BLUETOOTH_TROUBLESHOOTING.md](docs/BLUETOOTH_TROUBLESHOOTING.md).

## Security and privacy at a glance

- The app does not request internet access; chat traffic is intended to remain on the local Bluetooth connection.
- **Quick connect is enabled by default.** It uses an insecure RFCOMM socket and does not encrypt the Bluetooth link. Anyone nearby who can connect may be able to interact with the app, subject to the app's protocol and group approval behaviour.
- Turning Quick connect off makes the app use a secure, paired Bluetooth RFCOMM connection. This relies on Bluetooth link-level security; it is **not end-to-end encryption implemented by OfflineChat**.
- Message content and received files are stored locally. The app does not currently provide application-level encryption for the local database/files.
- Do not use the current build for sensitive, confidential, safety-critical, or emergency communications.

Read [docs/SECURITY.md](docs/SECURITY.md) before testing with real users or sharing builds.

## Project structure

```text
OfflineChat/
├── app/src/main/java/com/offlinechat/app/
│   ├── bluetooth/   # Bluetooth adapter, Classic discovery, RFCOMM transport
│   ├── core/        # Wire protocol, packet codec, chunk reassembly, group relay rules
│   ├── data/        # Room database, preferences, file import
│   ├── di/          # Hilt dependency injection
│   ├── domain/      # Models and interfaces used by the engine
│   ├── engine/      # Direct/group chat and file-transfer logic
│   ├── net/         # Peer links, handshakes, queues and link management
│   ├── service/     # Foreground service and notifications
│   └── ui/          # Compose screens, view models, components and theme
├── app/src/test/    # JVM unit and simulated-network tests
├── docs/            # Project, setup, architecture, security and testing docs
├── PROTOCOL.md      # Version 1 Bluetooth wire protocol
├── CONTRIBUTING.md
└── CHANGELOG.md
```

## Documentation

- [Getting started and build instructions](docs/GETTING_STARTED.md)
- [Architecture and data flow](docs/ARCHITECTURE.md)
- [Bluetooth discovery and troubleshooting](docs/BLUETOOTH_TROUBLESHOOTING.md)
- [Wire protocol specification](PROTOCOL.md)
- [Security and privacy notes](docs/SECURITY.md)
- [Testing guide](docs/TESTING.md)
- [Roadmap and known limitations](docs/ROADMAP.md)
- [Release checklist](docs/RELEASE_CHECKLIST.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Current limitations

- Bluetooth Classic discovery depends on Android's user-controlled, time-limited discoverability mode for unpaired devices. Previously paired devices may be discoverable without repeating that step.
- Quick connect is not encrypted. Secure paired RFCOMM is link-level protection, not app-level end-to-end encryption.
- Group delivery depends on the host phone. Host migration is implemented, but network partitions can still produce inconsistent group state or more than one apparent host.
- A group supports at most 7 devices including its host; Bluetooth throughput is shared among participants.
- Direct file transfer supports resume from a partial file. Group file transfers are streamed through the host and do not use the same resume path.
- Room currently uses destructive migration during development. A schema change can erase stored chat history until proper migrations are added.
- Bluetooth/background behaviour varies by Android version and manufacturer power-management policy.
- BLE, Wi-Fi Direct, LoRa, arbitrary multi-hop mesh, end-to-end encryption, message editing/deletion, read receipts, and localization are not implemented.

See [docs/ROADMAP.md](docs/ROADMAP.md) for planned improvements.

## License

No license has been added to this repository yet. Before publishing the repository publicly or accepting contributions, choose and add a license that reflects how you want others to use the project. Until then, do not assume others have permission to reuse or redistribute the code.
