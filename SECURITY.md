# Security

## Report a security issue

Write to **contact@sidali.dev** with "Sonne security issue" in the subject. Please do not open a public issue. You will get a first answer within 72 hours. Once a fix is released, the report is credited in the changelog if you wish.

## Check your copy of the app

Every release of the Android app is signed with the same certificate. Its SHA-256 fingerprint is:

```
31:00:C7:DC:6A:BD:3E:50:7A:CB:53:DD:29:BF:9C:91:1F:72:21:D0:22:1A:F6:24:8C:E1:40:5B:C8:FD:02:E3
```

The same value without colons: `3100c7dc6abd3e507acb53dd29bf9c911f7221d0221af6248ce1405bc8fd02e3`.

The app shows the start and the end of it (`3100c7dc…02e3`) under Settings, About, Signing certificate. It reads it from the phone itself, not from a constant. To check a downloaded APK: `apksigner verify --print-certs sonne-X.Y.Z.apk`. The built-in updater refuses any APK signed with another certificate.

## In scope

- The Android app (`android/`): permissions, storage of the key, the Bluetooth beacon and GATT server, the built-in updater.
- The relay (`relay/`): anything that lets someone ring, stop or follow a phone without its key, read a status, or exhaust the relay.
- The web page (`web/`): key handling in the browser, content security policy.
- The protocol and its cryptography (`docs/PROTOCOL.md`, `docs/VECTEURS.json`).

## Out of scope

- What the relay can see by design (chain id, fixed message sizes, times, IP address, and the command type for phones that still run 1.5 or older): see `docs/THREAT_MODEL.md`.
- A phone that is already rooted or compromised, or someone who has your 12 words.
- Denial of service that needs more traffic than the documented rate limits allow.

## Reviews

Internal security reviews are dated in `docs/security/`. The latest date is shown in the app. There has been no external audit yet.
