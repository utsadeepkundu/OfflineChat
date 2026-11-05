# Contributing

Thanks for considering a contribution to OfflineChat. The project is experimental; please discuss large changes or protocol changes before investing significant work.

## Development setup

1. Read [README.md](README.md) and [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md).
2. Open the project root in Android Studio and let Gradle sync.
3. Build with `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
4. Run JVM tests with `./gradlew :app:testDebugUnitTest` (Windows: `gradlew.bat :app:testDebugUnitTest`).
5. Use physical Android phones for Bluetooth-related changes.

## Contribution guidelines

- Keep changes focused and explain the problem they solve.
- Preserve the separation between platform-independent protocol/engine code and Android-specific UI/transport/storage code.
- Add or update unit tests for protocol, state-machine, ordering, retry, and file-transfer changes.
- Test Bluetooth changes on real phones and document phone models/Android versions.
- Do not log message bodies, file contents, credentials, or other private user data.
- Do not commit generated build outputs, APKs, AABs, local IDE settings, keystores, or secrets.
- Update `PROTOCOL.md` when changing wire-format behaviour.
- Update the relevant documentation when user-visible behaviour or limitations change.

## Protocol compatibility

The protocol is versioned. Do not change the meaning or byte layout of existing fields without an explicit compatibility plan. Prefer adding new packet types or append-only payload changes where compatible. If a change is incompatible, update the protocol version and document the migration/rollout plan in `PROTOCOL.md`.

## Pull requests

A useful pull request includes:

- A clear summary and motivation.
- A description of user-visible behaviour changes.
- Tests added/updated and the commands run.
- Physical-device test details for Bluetooth changes.
- Any protocol or database migration impact.
- Screenshots for significant UI changes, where appropriate.

There is no license file in the current repository snapshot. Add a project license before accepting external contributions under an explicit reuse policy.
