# Agent Instructions — Karoon Tunnel

## Project purpose
Karoon Tunnel is an Android SSH-based VPN client. Its intended traffic path is:
Android apps → Android `VpnService` TUN interface → `hev-socks5-tunnel` → local SOCKS5 → SSH `direct-tcpip` forwarding → remote network.

## Mandatory workflow for every agent
1. Read this file and `README.md` first. Read `docs/BUILD_AND_SIGNING.md` before changing build, package, signing, or installation configuration.
2. Inspect the existing implementation and Git history relevant to the task before editing. Do not assume unrecorded project history.
3. Make the smallest change that solves the verified problem. Avoid duplicate implementations, unnecessary files, broad refactors, and unrelated UI changes.
4. Never overwrite, regenerate, or commit a signing keystore, private key, password, token, or real VPN credentials. Do not request secrets in chat. Use local environment variables or ignored local properties for signing.
5. Do not change `applicationId` merely to bypass a signing mismatch. That creates a separate app installation and can strand existing app data. Explain the migration trade-off and get explicit approval before changing it.
6. Do not advise uninstalling the installed app until the user understands that its local app data may be erased and a same-certificate update has been ruled out.
7. Preserve native source dependencies and their licensing notices. Avoid editing vendored third-party code unless the bug is demonstrably there.
8. Validate changes with the Gradle wrapper and report the exact commands, results, and any checks that could not be run. Never claim a successful build without actual build output.

## Build baseline
- Android Gradle Plugin: 8.5.2
- Kotlin Gradle Plugin: 1.9.24
- Gradle wrapper distribution: 8.7
- Java/Kotlin target: 17
- Compile SDK: 34
- NDK: 26.1.10909125
- Native build: `app/src/main/jni/Android.mk`
- Module: `:app`
- Current namespace/application ID: `com.example.sshvpn`
- Release build currently uses the Gradle debug signing configuration. This is suitable only for local testing, not a stable release identity across machines.

## Required validation
At minimum, when the environment permits:
- `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`)
- `./gradlew :app:assembleRelease` (Windows: `gradlew.bat :app:assembleRelease`)
- Verify APK signing with Android SDK `apksigner verify --verbose --print-certs <apk>`.
- Compare the new APK certificate SHA-256 with the installed app's signer before attempting an in-place update.
- Report native build, packaging, and signing failures separately.

## Known constraints and security concerns
- The reported installation error says the APK signer does not match the installed app. This is an Android package-signature/update constraint; by itself it does not prove a source-code compilation defect.
- README currently documents that host-key checking is disabled, stored connection details are not separately encrypted, IPv6 is not tunneled, and non-DNS UDP is not forwarded. Do not silently represent these as solved.
- Treat user-provided APK hashes as file hashes unless explicitly identified as certificate fingerprints. An APK SHA-256 is not the signing certificate SHA-256.
- Keep documentation concise and update it when verified architecture, commands, build settings, or limitations change.
