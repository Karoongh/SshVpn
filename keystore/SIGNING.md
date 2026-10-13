# Karoon Tunnel — APK signing key (debug)

This project uses a **fixed debug keystore** so every GitHub Actions build produces an APK with the **same signature**. After the first install of a build signed with this key, you can install newer APKs **without uninstalling**.

## Location

| Path | Purpose |
|------|---------|
| `keystore/karoon-debug.jks.base64` | Keystore file (base64). CI decodes it to `keystore/karoon-debug.jks` before build. |
| `keystore/SIGNING.md` | This file — credentials and fingerprints for agents. |

## Credentials (debug only — personal sideload)

```
storeFile   = keystore/karoon-debug.jks
storePassword = karoon_tunnel_debug
keyAlias      = karoon
keyPassword   = karoon_tunnel_debug
```

## Certificate fingerprints

```
SHA1:   04:F8:E1:3B:FC:12:52:2F:59:E5:B8:E4:E4:9F:B1:82:C7:D8:07:BD
SHA256: 06:F0:3D:3A:65:52:18:3C:F9:A9:FC:E2:76:C1:E9:49:0E:94:2C:67:FA:51:F8:85:A5:BA:EC:26:FD:BF:D1:B0
```

## Decode locally

```bash
base64 -d keystore/karoon-debug.jks.base64 > keystore/karoon-debug.jks
```

## First install after switching to this key

Uninstall the old app **once** (old builds used a random CI debug key). From then on, updates overwrite without uninstall.

## Security note

This is a **debug / personal** key committed for convenience. Do **not** use it as a Play Store release key. For a public production app, use a private key stored in GitHub Secrets.
