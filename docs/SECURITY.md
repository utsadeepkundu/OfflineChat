# Security and privacy notes

This document describes the current source implementation. OfflineChat is experimental and must not be treated as a secure or emergency-grade messenger.

## Network access

The manifest does not grant internet access. It uses manifest merge-removal rules for `INTERNET`, `ACCESS_NETWORK_STATE`, and Wi-Fi state/change permissions so a merged dependency manifest should not add them. The intended message transport is local Bluetooth Classic RFCOMM.

For a build you distribute, inspect the final APK's merged permissions rather than relying only on this source manifest. On a machine with Android Build Tools installed, use `apkanalyzer` or `aapt` to inspect the APK.

## Bluetooth connection modes

The **Quick connect** preference is enabled by default:

- **Quick connect enabled:** the app connects through an insecure RFCOMM service socket. Bluetooth pairing is not required for this connection mode, and the Bluetooth link is not encrypted by the secure RFCOMM pairing mechanism. Do not use it for sensitive messages.
- **Quick connect disabled:** the app uses the secure RFCOMM service socket, which relies on Android/Bluetooth pairing and link-level security. This is not end-to-end encryption implemented by OfflineChat, and the app does not provide an independent cryptographic identity-verification protocol.

Turning Quick connect off is a safer transport setting, but it does not make the app production-secure.

## Application-level protections not currently implemented

- No application-level end-to-end encryption for message content.
- No cryptographic public-key identity or QR-based identity verification.
- No application-level encryption of the Room database or received files.
- No robust account-based identity, device revocation, or key rotation system.
- No comprehensive abuse/spam reporting or rate-limiting policy.

The app uses random local peer IDs for protocol identity, but these IDs are not cryptographic credentials.

## Group privacy

Group members communicate through the group host. The host forwards group traffic and therefore is part of the trusted communication path. Auto-accepting group joins is optional and disabled by default; enabling it allows nearby compatible peers to join hosted groups without a manual approval prompt.

## Local data

Conversation and message records are stored in a local Room database. Received files are kept in app-specific storage. The manifest sets `allowBackup="false"`, but local data is not encrypted by an application-level encryption layer. Uninstalling the app normally removes app-private data.

## Recommendations for testers

1. Use test data, not confidential or personal messages.
2. Keep Quick connect disabled when testing the paired/secure RFCOMM path.
3. Do not enable auto-accept unless you are in a controlled test environment.
4. Install APKs only from a source you trust.
5. Do not use OfflineChat as the only communication channel for emergencies, disaster response, medical needs, or personal safety.

## Before a public release

A security review should define and implement a threat model, authenticated key exchange, end-to-end authenticated encryption, key verification and rotation, local data protection, robust replay/abuse controls, safe logging, and a privacy policy. These changes require protocol design and compatibility testing; they should not be improvised by adding encryption around individual UI calls.
