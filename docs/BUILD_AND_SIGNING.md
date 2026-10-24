# Build, install, and signing

## Current build baseline

- Android Gradle Plugin: 8.9.1
- Gradle wrapper: 8.11.1
- Kotlin Gradle Plugin: 1.9.24
- JDK target: 17
- compileSdk / targetSdk: 36
- minSdk: 26
- NDK: 26.1.10909125
- CMake: 3.22.1
- Application ID: `com.example.sshvpn`

Install Android SDK Platform 36, Build Tools 36.0.0, the NDK version above, and CMake through Android Studio's SDK Manager.

## Build

From the repository root:

Windows:
```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
gradlew.bat :app:lintDebug
```

macOS/Linux:
```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lintDebug
```

Expected APK paths:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk` when no release signing variables are provided (the exact filename can vary by AGP task output).

The CI workflow fetches the upstream `hev-socks5-tunnel` source with submodules before compiling native code. A local build must have that source tree available under `app/src/main/jni/hev-socks5-tunnel/`.

## Signing configuration

Debug builds use the machine-local Android debug key. Do not commit a debug keystore or rely on matching debug certificates between computers.

Release signing is enabled only when all of these environment variables are set:

- `ANDROID_RELEASE_STORE_FILE`: path to the local release keystore
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Keep the keystore and passwords outside Git. Configure them in a trusted local environment or protected CI secrets. Do not print secrets in build logs. The repository intentionally does not contain release signing credentials.

Example (PowerShell; replace values locally and do not paste real secrets into source files):
```powershell
$env:ANDROID_RELEASE_STORE_FILE = "C:\secure\karoon-upload-key.jks"
$env:ANDROID_RELEASE_STORE_PASSWORD = "<set-locally>"
$env:ANDROID_RELEASE_KEY_ALIAS = "<set-locally>"
$env:ANDROID_RELEASE_KEY_PASSWORD = "<set-locally>"
.\gradlew.bat :app:assembleRelease
```

## Important signing-key exposure

A signing keystore was previously committed to this public repository as Base64 data and has now been removed from the current branch. **Removing a file from the latest commit does not remove it from Git history or revoke the key.** Treat that signing key as compromised.

- If the app has not been published and this key was only used for local/debug builds, generate a new private key locally and keep it out of version control.
- If the app is registered in Google Play, determine whether the exposed key is the upload key or the app-signing key. Follow Play Console's supported upload-key reset process where applicable. Do not casually replace an app-signing key: it can affect update continuity.
- For any existing sideloaded installation, compare the signer certificate before updating. A different certificate will not update the installed package in place unless Android's supported signing lineage applies.
- Never send the keystore or its passwords in chat, issue trackers, or public CI logs.

## Verify the APK signature

Use Android SDK Build Tools:

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
```

To compare two APKs, compare the **signer certificate SHA-256 fingerprint** from `apksigner`, not the SHA-256 of the APK file itself. The APK file hash identifies the file; the signer fingerprint identifies the signing certificate.

## Installation error: package signature mismatch

Android normally permits an in-place update only when the package/application ID matches and the new APK has the same signing identity (or a valid supported signing-key rotation lineage).

1. Confirm which APK is being installed and calculate its file hash:
   - Windows: `certutil -hashfile app-debug.apk SHA256`
   - macOS/Linux: `sha256sum app-debug.apk`
2. Run `apksigner verify --verbose --print-certs app-debug.apk`.
3. Obtain the signer fingerprint of the installed package using a trusted method or the original installed APK.
4. Compare the certificate fingerprints. Do not compare an APK file hash to a signer fingerprint.
5. Prefer rebuilding with the original trusted key if it is still safe and available. If the key has been exposed publicly, assess the compromise first.
6. Uninstalling can erase app-local data. Export/back up important profiles before uninstalling and do not use uninstall as the default fix.

## Release validation checklist

- [ ] Gradle sync succeeds.
- [ ] Debug build succeeds.
- [ ] Release build succeeds.
- [ ] Android lint completes and findings are reviewed.
- [ ] APK/AAB signature is verified.
- [ ] Release is signed with a protected, intended key.
- [ ] Signing fingerprint and artifact hash are recorded separately.
- [ ] App is installed and tested on real devices.
- [ ] VPN permission denial/revocation, network transitions, DNS, IPv4/IPv6, and disconnect behavior are tested.
- [ ] Privacy policy, Play Console Data safety, VPN disclosure/consent, store listing, and current policy requirements are reviewed.

Do not mark an unchecked item as passed. A successful CI build proves buildability only; it does not prove VPN correctness, security, or Google Play approval.
