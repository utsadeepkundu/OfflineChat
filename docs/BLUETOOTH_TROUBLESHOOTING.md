# Bluetooth setup and troubleshooting

OfflineChat currently uses Bluetooth Classic discovery and RFCOMM sockets. It does not currently use BLE advertising/scanning.

## Before testing

- Bluetooth must be enabled on both phones.
- Install the same OfflineChat build on both phones.
- Open OfflineChat on both phones.
- Grant the Nearby devices/Bluetooth permissions requested by Android.
- Keep the phones near each other during initial setup.

On Android 12 and newer, the app requests Nearby devices permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE`). On Android 11 and older, Bluetooth scanning requires location permission under the platform's legacy permission model. Notification permission is requested separately on Android 13 and newer.

## First connection between two phones

1. On Phone A, open **Nearby**.
2. Tap **Make me visible** and accept Android's temporary discoverability prompt.
3. On Phone B, open **Nearby** and tap **Search**.
4. Wait for the scan to finish and for the probe to identify OfflineChat peers.
5. Tap **Chat** next to the intended peer.

Android limits how long a phone remains discoverable. If Phone A is no longer visible, tap **Make me visible** again. Previously paired devices may be found without repeating the discoverability step.

## If the other phone does not appear

1. Confirm both phones have Bluetooth enabled.
2. Keep both apps open and retry. The first discovery/connection can take several seconds.
3. On the receiving phone, tap **Make me visible** again and accept the system prompt.
4. On the searching phone, stop and restart **Search**.
5. Confirm the Nearby devices permission is granted in Android Settings → Apps → OfflineChat → Permissions.
6. Move the phones closer and test without walls or large metal objects between them.
7. Turn off Bluetooth and turn it back on, then retry.
8. Check whether the phone's manufacturer battery restrictions are preventing OfflineChat from running.

## Quick connect setting

- **On (default):** tries the unpaired/insecure RFCOMM path. This avoids a pairing step but does not encrypt the Bluetooth link.
- **Off:** uses the secure paired RFCOMM path. Android may ask users to pair/confirm the peer. Use this mode for safer link-level testing.

The discovery probe itself is designed to be lightweight and may use the unpaired path even when Quick connect is disabled; normal app links are still subject to the selected connection mode.

## If connection fails after discovery

- Ensure the other phone still has OfflineChat open and its Bluetooth is on.
- Stop any active scan before trying again; Android Bluetooth discovery can interfere with connection setup.
- Retry once. Some devices may fail the first RFCOMM attempt immediately after discovery.
- On both phones, check that permissions have not been revoked.
- For a clean test, forget old Bluetooth pairings only if you understand that this removes the OS-level trusted pairing; then repeat the paired flow deliberately.

## If messages do not arrive

- Check whether the peer shows as connected/reconnecting.
- Keep both phones in range and OfflineChat active.
- Check whether the sender's message remains pending or failed.
- Retry after the link reconnects.
- Capture Android Studio Logcat output for both devices, including the time of the failure, before reporting a bug.

## If background delivery stops

Android and phone manufacturers can restrict background execution. Check Nearby devices permissions, notification permissions, battery optimization settings, and whether the foreground notification is visible. Force-stopping the app or revoking its Bluetooth permissions will stop normal background operation.

## Still stuck?

When reporting an issue, include device models, Android versions, Quick connect setting, whether the devices were paired, approximate distance, and the relevant Logcat error. Do not include private messages or personal data in logs.
