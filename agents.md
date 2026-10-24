# Agent Instructions — Karoon Tunnel

## Project purpose
Karoon Tunnel is an Android SSH-based VPN client. Intended traffic path:
Android apps → Android `VpnService` TUN → `hev-socks5-tunnel` → local SOCKS5 → SSH `direct-tcpip` forwarding → remote network.

This is also a public portfolio repository. Keep its history, documentation, build reproducibility, licensing, security posture, and claims suitable for technical review.

## Mandatory workflow
1. Read this file and `README.md` first. Read `docs/BUILD_AND_SIGNING.md` before changing build, package, or signing configuration.
2. Inspect current source and recent Git history before editing. Treat source code and verified CI results as authoritative; do not assume prior summaries describe the latest state.
3. Make focused changes. Avoid broad refactors and unrelated changes unless required for security or compatibility.
4. Never commit signing keystores, private keys, passwords, tokens, real VPN credentials, or exported user profiles. Never print secrets in logs.
5. A signing keystore was previously committed publicly as Base64. It has been removed from the current branch but remains in Git history; treat it as compromised. Do not restore or reuse it. If Google Play distribution is involved, determine the key's role and follow the supported upload-key reset procedure where applicable.
6. Do not change `applicationId` to bypass an update/signature issue. This creates a separate app identity and can strand app data. Explain trade-offs and obtain explicit approval before changing it.
7. Do not advise uninstalling the installed app without explaining possible loss of local data and checking whether a same-certificate update is possible.
8. Preserve native dependencies and their license notices. Do not edit vendored third-party code unless the defect is demonstrably there.
9. Validate with the Gradle wrapper and report exact commands/results. Never claim a build, test, security audit, or Play approval without evidence.
10. Keep README and docs aligned with the actual source, configuration, limitations, and current CI workflow.

## Build baseline
- Android Gradle Plugin: 8.9.1
- Gradle wrapper: 8.11.1
- Kotlin Gradle Plugin: 1.9.24
- Java/Kotlin target: 17
- compileSdk / targetSdk: 36
- minSdk: 26
- NDK: 26.1.10909125
- CMake: 3.22.1
- Application ID: `com.example.sshvpn`
- Native build: `app/src/main/jni/Android.mk`
- Release signing is configured only through local environment variables; never hardcode signing credentials.

## Required validation
When the environment permits, run:
- `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`)
- `./gradlew :app:assembleRelease`
- `./gradlew :app:lintDebug`
- `apksigner verify --verbose --print-certs <apk>`

Also review:
- CI result and artifact for the exact commit.
- Android merged manifest and foreground-service declarations.
- Real-device VPN connection, DNS, routing, permission denial/revocation, network changes, and disconnect behavior.
- Release signer certificate, privacy policy, Data safety declaration, VPN disclosure/consent, and Google Play policies.

## Known constraints and security requirements
- Do not disable SSH host-key verification in production. Implement verified host-key pinning or a safe first-use trust flow.
- Review protection and encryption of stored profiles and credentials.
- Current routing is IPv4-focused; IPv6 and general UDP are not fully supported. Do not claim full-device coverage for unsupported traffic.
- The UI may expose protocol choices that are not all implemented; document actual behavior and avoid presenting placeholders as working features.
- Google Play target API compliance alone does not establish policy compliance. VPN apps must meet the applicable VPN, privacy, data safety, disclosure, consent, and encryption requirements.
- Keep dependencies and native code licenses discoverable.
- Distinguish source-level changes, CI build success, real-device validation, security review, and Play approval as separate statuses.
