# Karoon Tunnel

**Android SSH VPN client · Kotlin · Android VpnService · JNI/NDK · GitHub Actions**

Karoon Tunnel is an Android networking project that routes supported TCP traffic through an SSH server. It is also a portfolio project demonstrating Android service lifecycle management, native integration, SSH port forwarding, CI builds, and iterative debugging with AI-assisted development tools.

> **Project status:** Active development / portfolio project. It is not yet presented as production-ready or Google Play approved. Review the security limitations and release checklist before using it with sensitive traffic.

## Architecture

```text
Android apps
    │
    ▼
Android VpnService (TUN)
    │
    ▼
hev-socks5-tunnel (native tun2socks)
    │
    ▼
Local SOCKS5 listener (127.0.0.1)
    │
    ▼
SSH direct-tcpip forwarding (JSch)
    │
    ▼
Remote SSH server → destination
```

- Kotlin Android app with a native C networking component built using the Android NDK.
- SSH transport and TCP forwarding use [mwiede/jsch](https://github.com/mwiede/jsch).
- Native TUN-to-SOCKS forwarding uses [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).
- GitHub Actions builds and verifies a debug APK and runs Android lint.
- Import supports several connection-profile formats; see below.

## Current capabilities and limitations

| Area | Current state |
| --- | --- |
| SSH transport | SSH password/private-key authentication and TCP forwarding |
| VPN routing | IPv4 default route through Android `VpnService` |
| DNS | Configurable DNS path; transparent DNS uses the native tunnel's DNS mapping |
| UDP | General UDP traffic is not fully tunneled; do not assume QUIC or real-time UDP apps will work |
| IPv6 | IPv6 traffic is not currently routed through the tunnel |
| Auto-reconnect | Not implemented |
| SSH server identity | Host-key verification must be hardened before production use |
| Credential storage | Profile/credential storage needs a security review before production use |
| Google Play | Not submitted or approved; policy, privacy, security, and device testing remain required |

The app requires an SSH server that permits TCP forwarding (for example, `AllowTcpForwarding yes`). The actual behavior depends on the server configuration and Android device/network.

## Build locally

### Requirements

- Android Studio with JDK 17
- Android SDK Platform 36 and Build Tools 36.0.0
- Android NDK `26.1.10909125`
- CMake `3.22.1`
- Gradle wrapper (Gradle 8.11.1)

The native `hev-socks5-tunnel` source is fetched by the CI workflow with its submodules. For local builds, ensure the source tree and required submodules exist under `app/src/main/jni/hev-socks5-tunnel/`.

### Commands

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

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The release variant is **unsigned unless release-signing environment variables are supplied**. Do not publish an unsigned APK. See [Build and signing](docs/BUILD_AND_SIGNING.md).

## Importing a connection profile

Supported formats include:

- SSH URI: `ssh://user:pass@host:port#profile-name`
- Simple: `host:port@user:pass`
- Four-part: `host:port:user:pass`
- JSON: `{"host":"example.com","port":22,"username":"user","password":"replace-me"}`
- Key/value lines: `host=`, `port=`, `user=`, `pass=`
- Base64-encoded versions of supported formats

Private-key authentication is supported. **Never commit a real profile, password, private key, or exported configuration.** Treat profile links and diagnostic logs as potentially sensitive.

## Security and Google Play readiness

Before any public production release:

1. Replace the previously exposed signing material and configure a dedicated release/upload key outside version control. Deleting a key file does not remove it from Git history.
2. Implement and test strict SSH host-key verification or a safe first-use/pinning flow.
3. Review credential storage and encrypt secrets at rest where appropriate.
4. Publish a public privacy policy and complete the Play Console Data safety form accurately.
5. Provide prominent disclosure and obtain consent where required for VPN-related data handling. Explain the VPN purpose in the Play listing and ensure traffic is encrypted to the tunnel endpoint.
6. Test on real devices and networks, including VPN permission denial/revocation, network changes, DNS behavior, disconnect/reconnect, and IPv4/IPv6 behavior.
7. Review all Play policies, native-library licenses, app signing, release bundle requirements, and current target API requirements.
8. Run release checks, inspect the merged manifest, and test the signed artifact before submission.

Current target SDK is API 36, aligned with the Google Play target API requirement for new apps and updates as of August 31, 2026. Meeting the target API requirement alone does **not** establish Play policy compliance.

## CI and artifacts

Open [GitHub Actions](https://github.com/Karoongh/SshVpn/actions). Successful workflow runs build the debug APK, verify its signature, and retain the artifact for a limited period. Use only artifacts from a successful run and compare the signing certificate before attempting to update an existing installation.

## Project documentation

- [Build, install, and signing guide](docs/BUILD_AND_SIGNING.md)
- [Agent/developer workflow and safety rules](agents.md)
- [hev-socks5-tunnel upstream project](https://github.com/heiher/hev-socks5-tunnel)
- [JSch upstream project](https://github.com/mwiede/jsch)

## Portfolio notes

This repository demonstrates the implementation and iterative improvement of an Android networking application, native-library integration, Git-based change history, CI automation, and AI-assisted debugging. The project status and known limitations are documented explicitly rather than claiming unverified production readiness.
