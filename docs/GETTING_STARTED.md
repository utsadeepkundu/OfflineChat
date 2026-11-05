# Getting started

This guide explains how to open, build, install, and test OfflineChat from source.

## 1. Development requirements

- Android Studio (current stable release)
- JDK 17 or a compatible newer JDK selected for Gradle inside Android Studio
- Android SDK Platform 35
- Android SDK Build-Tools and Platform-Tools (ADB)
- A physical Android phone with Bluetooth
- A second phone for peer-to-peer tests

The app supports Android 8.0/API 26 and newer (`minSdk = 26`). The current build uses `compileSdk = 35` and `targetSdk = 35`.

## 2. Open the project

1. Extract the source archive, if using a ZIP.
2. Start Android Studio and select **Open**.
3. Select the project root: the folder containing `settings.gradle.kts`, `gradlew.bat`, and the `app` folder.
4. Wait for Gradle sync to complete.
5. If Android Studio asks for Android SDK Platform 35 or build tools, install them from **Tools → SDK Manager**.
6. Under **Settings → Build, Execution, Deployment → Build Tools → Gradle**, select Android Studio's bundled JDK or another JDK 17+ supported by the configured Android Gradle Plugin.

Do not open only the `app` subfolder. Open the root project.

## 3. Run on a phone from Android Studio

1. On the Android phone, enable Developer options by tapping **Build number** seven times in **Settings → About phone → Software information** (menu names vary by manufacturer).
2. In Developer options, enable **USB debugging**.
3. Connect the phone to the computer with a data-capable USB cable.
4. Accept the **Allow USB debugging** prompt on the phone.
5. In Android Studio, choose the `app` run configuration and select the connected phone in the device selector.
6. Press **Run**.
7. Grant the requested Nearby devices/Bluetooth permissions. Allow notifications if you want message notifications.

Bluetooth behaviour must be tested on physical phones. An emulator is not sufficient for this project.

## 4. Build a debug APK

### Windows PowerShell

From the project root:

```powershell
.\gradlew.bat assembleDebug
```

### macOS/Linux

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Copy this APK to the second phone (USB, Quick Share, or another file-transfer method), open it, and follow Android's installation prompt. Android may require permission to install apps from the file manager or browser used to open the APK.

You can also use Android Studio's **Build → Build Bundle(s) / APK(s) → Build APK(s)** menu.

## 5. Install with ADB

With USB debugging enabled and the phone authorized:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On macOS/Linux, replace the path separators with `/`.

## 6. Run unit tests

Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

macOS/Linux:

```bash
./gradlew :app:testDebugUnitTest
```

These are local JVM tests; they do not verify the Android Bluetooth stack or replace tests using physical phones.

## 7. Build output and common commands

| Goal | Windows PowerShell | macOS/Linux |
|---|---|---|
| Clean build outputs | `.\gradlew.bat clean` | `./gradlew clean` |
| Debug APK | `.\gradlew.bat assembleDebug` | `./gradlew assembleDebug` |
| JVM unit tests | `.\gradlew.bat :app:testDebugUnitTest` | `./gradlew :app:testDebugUnitTest` |
| Release APK | `.\gradlew.bat assembleRelease` | `./gradlew assembleRelease` |

**Release warning:** the current `release` build type is configured with the debug signing configuration. Do not distribute that release APK as a production release. Follow [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) and configure a private release keystore before publishing.
