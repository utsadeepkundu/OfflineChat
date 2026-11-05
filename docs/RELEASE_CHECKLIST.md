# Release checklist

The current project is suitable for development builds and controlled testing. Complete this checklist before distributing a public or production release.

## Build and signing

- [ ] Confirm the intended `applicationId`, version name, and version code.
- [ ] Verify `minSdk`, `targetSdk`, and compile SDK requirements for the release date.
- [ ] Create a release keystore and store it outside the repository.
- [ ] Configure Gradle signing properties through environment variables or an untracked local properties file.
- [ ] Remove the current debug signing configuration from the `release` build type.
- [ ] Never commit keystore files, passwords, tokens, or signing credentials.
- [ ] Build and inspect a signed release artifact.
- [ ] Test the release build with minification and resource shrinking enabled.

## Functional quality

- [ ] Run `:app:testDebugUnitTest` and review the test report.
- [ ] Build `assembleDebug` and the configured release variant.
- [ ] Complete the two-phone direct chat test on supported devices.
- [ ] Complete the three-or-more-phone group test, including host migration.
- [ ] Test direct and group file transfer at and around their size limits.
- [ ] Test Bluetooth disabled, permission denied/revoked, and reconnect scenarios.
- [ ] Test foreground, background, screen-locked, and force-stop behaviour.
- [ ] Measure range, delivery reliability, and battery use on multiple devices.

## Security and privacy

- [ ] Complete a security threat model and independent review.
- [ ] Implement and test end-to-end encryption and peer identity verification before claiming private/secure messaging.
- [ ] Decide whether and how local database and received files should be encrypted.
- [ ] Inspect the merged APK permissions; verify no unintended internet/network permissions are present.
- [ ] Review logs, notifications, file sharing, and crash reports for sensitive content.
- [ ] Review default Quick connect and auto-accept settings.
- [ ] Prepare accurate privacy disclosures and a privacy policy if required by the distribution channel.

## Data and operations

- [ ] Replace `fallbackToDestructiveMigration()` with real Room migrations.
- [ ] Test upgrade from every previously released database version.
- [ ] Add localization and accessibility coverage as required.
- [ ] Document supported devices, known limitations, and troubleshooting steps.
- [ ] Add a license and decide the project's contribution policy.
- [ ] Provide a support/issue reporting channel.
