# Build, install, and signing notes

## Current project baseline
The Android module is `:app`. Build configuration observed on the default branch:
- Android Gradle Plugin 8.5.2
- Kotlin Gradle Plugin 1.9.24
- Gradle 8.7 wrapper
- JDK target 17
- compileSdk 34
- NDK 26.1.10909125
- Native build file: `app/src/main/jni/Android.mk`
- Application ID: `com.example.sshvpn`

Install the matching NDK and CMake tools in Android Studio's SDK Manager. Keep the native dependency directories described in README present.

## Build APKs

From the repository root:

Windows PowerShell / CMD:
```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

macOS / Linux:
```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Expected outputs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

The release build is currently configured to use the debug signing key. This is not a production signing setup and can yield different signing identities on different computers, because each computer may have a different debug keystore.

## Why the phone reports “Package signature does not match the installed app”

Android allows an installed app to be updated only when the application ID matches and the new APK is signed by the same certificate (or a valid signing-key rotation lineage exists). The screenshot supplied on 2026-10-12 reports a certificate mismatch for the app labelled “Karoon Tunnel”.

The supplied SHA-256 `bea0bece75da7f8fa0d0846b01b644c8a4f7fd2026a1668d2270c7692887e575` is described by the user as the software hash. Unless it was generated from the APK signing certificate, it is a hash of the APK file and cannot be compared directly with the certificate fingerprint.

### Diagnose before removing the installed app
1. Locate the exact APK being installed and confirm its file hash:
   `certutil -hashfile app-debug.apk SHA256` (Windows)
   or `sha256sum app-debug.apk` (macOS/Linux).
2. Print the APK signer certificate:
   `apksigner verify --verbose --print-certs app-debug.apk`
3. Obtain the signer certificate fingerprint for the installed app. If Android SDK tools and USB debugging are available, use `adb shell pm path com.example.sshvpn` to locate its installed APK, pull it with `adb pull <device-path> installed.apk`, then run `apksigner verify --print-certs installed.apk`. Some devices/Android versions may restrict pulling the package; if so, use a trusted APK/signing inspection tool or the original APK that was installed.
4. Compare the **signer certificate SHA-256**, not the APK file hash. If they differ, an in-place update will be rejected.

### Choose the appropriate remedy
- **Preferred, preserve app data:** build/sign the new APK with the same private signing key as the installed app. The private key must be available from the original build environment or backup. Do not upload or commit it.
- **If the old signing key is unavailable:** Android cannot accept this APK as an in-place update under the same application ID. Back up/export any important configuration first. Only after confirming the user accepts loss of local app data, uninstall the existing app for all Android users/PrivateSpace profiles and install the new APK.
- **Separate installation:** changing `applicationId` can permit installation as a separate app, but does not update the old app or transfer its private data. Do not make this change just to hide the signature problem without explicit approval.

## Stable release signing (not configured yet)
For a release that must update consistently across devices/build machines, use a dedicated, backed-up keystore and keep it outside version control. Configure the release signing config to read the keystore path, alias, and passwords from local environment variables or an ignored local properties file. Never commit the keystore or credentials. Record the public certificate SHA-256 fingerprint in secure project/release documentation; the fingerprint is not a private key.

Do not generate a new signing key and assume it will match an already-installed APK. A newly generated key only establishes a new signing identity.

## Validation checklist
- [ ] Gradle sync succeeds.
- [ ] Debug build succeeds.
- [ ] Release build succeeds.
- [ ] APK signature verification succeeds.
- [ ] Record APK file SHA-256 and signer certificate SHA-256 separately.
- [ ] Compare signer certificate with the installed build before trying an update.
- [ ] Install/update tested on a real device.
- [ ] Do not report success for any unchecked item.
