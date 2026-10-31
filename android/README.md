# Sonne for Android

The Android app of Sonne: it keeps a connection to the relay and rings the phone on request, even on silent. Since 1.1, it also broadcasts a Bluetooth beacon and accepts commands over Bluetooth (Nearby), and acts as a detector to find another Sonne phone.
The full protocol is in `../docs/PROTOCOL.md`.

## Requirements

- JDK 21 (for example `/opt/homebrew/opt/openjdk@21` on macOS with Homebrew)
- Android SDK with platform 36 and build-tools 36: `$ANDROID_HOME` (`compileSdk 36`, `targetSdk 34`, see below)
- No need for Android Studio: the Gradle project is written by hand, the wrapper (Gradle 8.14.3) is included.

`local.properties` (ignored by git) only contains:

```
sdk.dir=/path/to/Android/sdk
```

## Build

From `android/`:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew testDebugUnitTest   # vectors from docs/VECTEURS.json (P, id, keys, 12 words, signature, status, BLE beacon, K_cmd, v3 commands, status with cmdv, 19 accept or refuse cases), command filter, proximity
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (signed)
./gradlew lintDebug           # static analysis
```

Install on a device or an emulator: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The debug build allows `ws://` and `http://` (local relay, for example `http://10.0.2.2:7700` from the emulator).
In debug only, the relay address can be set through adb, once the app has been launched at least once:

```bash
adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es relay http://10.0.2.2:7700
# Add another phone to "My other phones" (link read from its SONNE_PAIR log):
adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es pair "'http://10.0.2.2:7700/#s=…'" --es nom B
# Send a command to one of them through the relay, like the Nearby screen (v2 or v3, resent as v3 on a 400;
# --ez auto true: automatic check, preceded by presence):
adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es envoyer ring --es vers 84c083
```

A cut-off build (v2 refused, 172-byte GATT writes, v3 always sent), to test step 4 of the transition: `./gradlew assembleDebug -PsonneCoupureV2=true`. The default since 1.6 is the transition (`ACCEPTER_V2` true in `app/build.gradle.kts`); it becomes false for app 1.8.0 (1.7.0 only changed the look).

This receiver (`src/debug/`) is protected by the `DUMP` permission, which the adb shell holds and other apps do not: no installed app can redirect the phone to its own relay (whose page would receive the key at pairing). The pairing link is written to the log (`adb logcat -d -s SONNE_PAIR`). See `docs/END-TO-END-TEST.md`.
The release build only accepts `https://` / `wss://`, in the Settings screen as in the code (`Reglages.relaisValide`).

## Release signing

The keystore never lives in the repository:

- `~/.sonne/release.jks` (PKCS12, alias `sonne`, RSA 4096, 10000 days, `CN=Sonne`)
- `~/.sonne/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`

If they do not exist, create them like this (the password is drawn at random):

```bash
mkdir -p ~/.sonne && chmod 700 ~/.sonne
MDP=$(openssl rand -hex 24)
keytool -genkeypair -keystore ~/.sonne/release.jks -storetype PKCS12 -alias sonne \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Sonne" \
  -storepass "$MDP" -keypass "$MDP" -noprompt
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=sonne\nkeyPassword=%s\n' \
  "$HOME/.sonne/release.jks" "$MDP" "$MDP" > ~/.sonne/keystore.properties
chmod 600 ~/.sonne/release.jks ~/.sonne/keystore.properties
```

Without this file, `assembleRelease` produces an unsigned APK. **Back up these two files**: without them, no update can be installed over the app (Obtainium will refuse the new signature).

## Code layout (`app/src/main/java/dev/sidali/sonne/`)

| File | Role |
| --- | --- |
| `Crypto.kt` | HKDF, HMAC, AES-GCM, proof P, BIP39, link (both ways), beacon key B and `id_ble`; v3 commands (`cleCommande`, 64-byte plaintext, encryption, exact 172-byte message, decryption with the refusal reason, `parametresSonnerie`) and v2 (`messageCommandeV2`); `ACCEPTER_V2` (transition, `false` at the cut-off); `FiltreCommandes` (v2 and v3 in a single memory: shape, signature or authentication, 15 min window, nonces kept while inside the window, capacity, order of ring and stop; `recevable` for GATT). Pure Kotlin (and org.json), tested on the JVM. |
| `Proximite.kt` | Constants of Bluetooth proximity (UUIDs, MTU, thresholds, beep rhythm, search mode, GATT server limits), service data, `modeRecherche`, `clairEtat` (written by hand, with `"cmdv":3`), `bourrerEtat`, `statutEcriture` (512 bytes at most during the transition, exactly 172 after the cut-off), `SuiviSignal` (exponential smoothing, levels with hysteresis), `SuiviBalises` (per address, copy detection), `Reconnaissance` (w - 1, w, w + 1), `LimiteDebit`, `HistoriqueDemarrages`, `aEvincer`, `FraicheurEtats`. Pure Kotlin, tested on the JVM. |
| `Balise.kt` | BLE beacon of the protected phone (`startAdvertisingSet`, legacy, connectable, `TX_POWER_LOW`), in search mode only, restarted at each window and after a connection; state confirmed by the stack's callbacks. |
| `ServeurGatt.kt` | GATT server: Command characteristic (write with response), Status (notification); ready on `onServiceAdded`; connections counted from the first Sonne access, 4 at most with eviction, 5 s without a valid command, request budget, signature checked before the limit of 20 commands per minute, queued notifications. |
| `Chaines.kt` | "My other phones": keys derived from known chains (not S, `K_cmd` computed from `K_sig`) and a `cmdv3` flag (never reset to false), encrypted by a Keystore key that requires the device to be unlocked, in credential-encrypted storage; parsing of a link or 12 words (`Saisie`). |
| `Chercheur.kt`, `EnvoiCommande.kt` | Filtered scan, recognition, tracking per address, gauge, beeps and vibration, tracking of the Bluetooth state; sending a command (v3 to chains that announced `cmdv ≥ 3`, v2 otherwise, v3 with a new nonce if the relay refuses v2 with 400) over GATT (devices from the `ScanResult`, second try on 133, several addresses) then, as a fallback, through the relay (`POST /api/cmd`, same message, same nonce); capability learned from any fresh GATT status; waking search mode with a `ping` to the relay, only if `/api/presence` says the phone is online. |
| `EcranNearby.kt`, `ScanQr.kt` | Nearby and My other phones screens; QR code reader (Camera2 and ZXing core, no new dependency). |
| `Reglages.kt` | `Stockage` (preferences in device-encrypted storage, Direct Boot, one-time migration), preferences, `Coffre` (S encrypted by an AES key of the Android Keystore, never silently replaced), shared `Etat`, BIP39 list (`res/raw`). |
| `VeilleService.kt` | Foreground service, OkHttp WebSocket authenticated by P, reconnection from 1 s to 60 s with jitter, `NetworkCallback`, `AlarmManager` watchdog (heartbeat, periodic status, long reconnections, end of search mode; lock released as soon as the answer comes), v2 and v3 commands from the relay and the GATT server through the same path (nonces written before running, acknowledgment before ringing, a single status per command, duration and torch of a v3 ring), encrypted and padded statuses with `"cmdv":3`, search mode, beacon and GATT server (tracking of the Bluetooth state, retries, rotation alarm); `ChienDeGarde`, `BootReceiver`, `StopReceiver`. |
| `Ringer.kt` | Ringing: alarm stream at maximum, Do Not Disturb, vibration, torch (tracked by `TorchCallback`), full screen notification, automatic stop (or duration requested by a v3 command), generated fallback sound (also on playback error). |
| `RingActivity.kt` | Ringing screen above the lock screen, Stop button. |
| `MainActivity.kt`, `Ecrans.kt` | Compose: Home, Pairing (ZXing QR, link, 12 words, Change key, `FLAG_SECURE`), Settings, Permissions, navigation to Nearby. |
| `Permissions.kt` | Permission state and the matching system screens. |
| `VerifMiseAJour.kt` | Built-in update, pure Kotlin tested on the JVM: validated `Annonce`, version comparison, SHA-256 of the file, signing certificates (APK = installed app, and contains `certSha256`). |
| `MiseAJour.kt` | Check (`GET <relay>/update/android.json`, once a day from the watchdog and when the app opens), notification (Updates channel), OkHttp download into the cache, checks, install through `PackageInstaller` (`USER_ACTION_REQUIRED`), `InstallationRecue`. |
| `src/debug/.../DebugRelaisReceiver.kt` | Debug only: relay address (`relay`) and adding another phone by its link (`pair`, `nom`) set through adb. |

## Notable choices (also in the protocol)

- **`targetSdk 34`, not 36.** From `targetSdk 35`, Android 15 and later forbid an app to change the global Do Not Disturb mode (`setInterruptionFilter` only drives a rule of the app itself, compat change `MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES`). The ring would stay silent in "Total silence", and restoring would turn on a mode unknown to the user. With 34, checked on the Android 16 emulator: `ZEN_MODE_NO_INTERRUPTIONS` goes to `ZEN_MODE_OFF` while ringing (player `mutedState:none`) and comes back on stop. The app is distributed outside Play (Obtainium), which imposes no minimum `targetSdk`. If this choice changes, `Ringer.dndPilotable` cuts every call and the home screen warns when the active mode blocks alarms.
- **Foreground service type: `specialUse`.** Android 15 and later limit a `dataSync` service to 6 h per 24 h and forbid starting it from `BOOT_COMPLETED`. `specialUse` has none of these limits (permission `FOREGROUND_SERVICE_SPECIAL_USE`, subtype described in the manifest). Outside the Play Store, nothing has to be declared.
- **Direct Boot**: service, receivers and ringing screen are `directBootAware`, preferences and secret in device-encrypted storage, start on `LOCKED_BOOT_COMPLETED`. Reachable after an automatic restart, before the code is entered.
- **`CAMERA` only to scan a QR code** (adding another phone), requested at scan time. The torch does not need it (`CameraManager.setTorchMode`). Camera and flash are declared as not required.
- **Bluetooth without location**: `BLUETOOTH_SCAN` carries `neverForLocation`. Android may then drop from the results some beacons it considers location beacons; Sonne's (service data under a 128-bit UUID) gets through, checked on the Android 16 emulator.
- **`ACCESS_NOTIFICATION_POLICY` declared**: without it, the app does not appear in the list of Do Not Disturb access.

## Built-in update

See `../docs/PROTOCOL.md`, section "Update (app 1.2)", and `../tools/release-android.sh` to publish.
Permission `REQUEST_INSTALL_PACKAGES`: the first time, the home screen explains the "Install unknown apps" permission and opens the system setting. Android's confirmation screen is always shown.
For an end-to-end test, a higher version is built without touching the file: `./gradlew assembleDebug -PsonneVersionCode=5 -PsonneVersionName=1.2.1` (see `../docs/END-TO-END-TEST.md`).

## Known limits

- Do Not Disturb: lifted and restored thanks to `targetSdk 34` (see above). If Do Not Disturb access is not granted and the active mode silences alarms, the home screen says so.
- The full screen above the lock screen depends on the "full screen notifications" permission (Android 14 and later). Without it, an alarm notification with the Stop button still shows.
- Without the battery optimization exemption, Android may cut the WebSocket during deep sleep, and refuse to restart the service from the background (the service then stops cleanly instead of crashing).
- The watchdog uses an inexact alarm (no permission): it may drift by a few minutes, more in Doze.
- Direct Boot not tested with a real lock code (the emulator has none).
- No UnifiedPush in v1.
- Nearby: gauge thresholds estimated for a beacon at -15 dBm, to be tuned in the field with real phones. Also to check on a device (nRF Connect, a Pixel on GrapheneOS and an entry-level phone on Android 12): the beacon reappears less than 1 s after a closed GATT connection, the address changes at rotation, the `bluetooth_off_timeout` key is readable on GrapheneOS, the Keystore key `setUnlockedDeviceRequired` works without a lock code, and the power draw (`dumpsys batterystats`). The QR reader assumes a phone held in portrait for the preview (in landscape, the image is rotated but reading works).
