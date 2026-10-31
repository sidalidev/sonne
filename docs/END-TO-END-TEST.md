# End-to-end test on an emulator

> The first part describes the test of protocol v1 (October 18, 2026, before the review). The second,
> "After the review: protocol v2", covers the fixed points. Since v2, the relay address is
> set with `adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es relay http://10.0.2.2:7700`
> (the old `MainActivity` extra was removed).

Date: October 18, 2026. Result: **everything passes**, including on silent and in Do Not Disturb "Priority".
Only "Total silence" mode mutes the sound (an Android limit, see below).

## Equipment

- Emulator `Pixel_7_Pro_API_36` (Android 16, API 36, Google Play image, arm64), started without a window.
- Local relay: `PORT=7700 node relay/server.js`, reached from the emulator at `http://10.0.2.2:7700`.
- App: debug APK (`./gradlew assembleDebug`), installed with `adb install -r`.
- Controller: `relay/tools/controleur.js` (new). It opens `/ws/ctrl`, sends the signed command with `POST /api/cmd`, and decrypts the received statuses with `relay/crypto.js`.

## Preparation

```bash
PORT=7700 node relay/server.js > /tmp/sonne-relay.log 2>&1 &
$ANDROID_HOME/emulator/emulator -avd Pixel_7_Pro_API_36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device   # then wait for sys.boot_completed = 1

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.sidali.sonne android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_dnd dev.sidali.sonne          # Do Not Disturb access
adb shell appops set dev.sidali.sonne USE_FULL_SCREEN_INTENT allow
adb shell dumpsys deviceidle whitelist +dev.sidali.sonne        # exempt from battery optimization

# Debug build only: relay address set through adb, pairing link in the log.
adb shell am start -n dev.sidali.sonne/.MainActivity --es relay http://10.0.2.2:7700
adb logcat -d -s SONNE_PAIR       # gives http://10.0.2.2:7700/#s=…

# Phone on silent, alarm volume at minimum, Do Not Disturb on.
adb shell cmd audio set-ringer-mode SILENT
adb shell cmd media_session volume --stream 2 --set 0
adb shell cmd audio set-volume 4 1          # STREAM_ALARM: minimum = 1 on this emulator
adb shell cmd notification set_dnd priority # zen_mode = 1
```

No `CAMERA` permission to grant: the app does not declare it (the flashlight does not need it).
`settings put global zen_mode 1` does not stick on Android 16 (reset to 0 by the system): `cmd notification set_dnd` is required.

## Scenarios and results

Commands run from `relay/`: `node tools/controleur.js --lien "<lien>" ring|stop|ping`.

| # | Scenario | Expected | Observed |
|---|----------|---------|--------|
| 1 | `ring`, phone on silent, alarm at minimum, Do Not Disturb "Priority" | rings | `{"delivered":true}`; log `Command accepted: ring` then `Ringing started`; `dumpsys audio`: `MediaPlayer … state:started usage=USAGE_ALARM … mutedState:none`; alarm volume raised from 1 to 7; `RingActivity` in the foreground; decrypted status `ringing:true`, `ack` = nonce of the command, 300 ms after sending |
| 2 | `stop` | everything stops and is restored | `Ringing stopped`; no active player left; alarm volume back to 1; `RingActivity` closed; `sonne:sonnerie` lock released; status `ringing:false` with `ack` |
| 3 | `ring` in Do Not Disturb "Total silence" | known limit | the player starts but `mutedState:opPlayAudio`: Android mutes the sound. Since Android 15, the app cannot lift a Do Not Disturb set by the user |
| 4 | replayed `ping`, `ring` with a wrong signature, `ring` 16 min old | silently ignored | the relay delivers all four (`delivered:true`) but the phone accepts only the first `ping`: a single status with `ack`, a single `Command accepted` line |
| 5 | Airplane mode, `ring` sent, then airplane mode off | delivered on reconnection | relay: `{"delivered":false,"queued":true}`; when the network comes back, `Network available, reconnecting now`, command received in the `welcome` queue, ringing 1.3 s after airplane mode is turned off |
| 6 | Relay killed (SIGTERM), restarted 20 s later | reconnection in under 90 s | phone: `closed (1001)`, then attempts at 1, 3, 8, 17 and 37 s; online **17 s after the relay restart** (37 s of cut-off in total). Theoretical worst case with the capped delay: 75 s |
| 7 | Screen off (`KEYCODE_SLEEP`), then `ring` | the screen turns on with the ringing | `mWakefulness` goes from `Asleep` to `Awake`, `RingActivity` in the foreground (screenshot `captures/android-sonnerie-verrouille.png`) |
| 8 | Phone on and in use (home screen), then `ring` | alarm banner | like an Android alarm clock: banner at the top with a Stop button instead of full screen. The banner's Stop button stops everything, the `ringing:false` status is sent, the notification disappears |
| 9 | `ring` without touching anything | automatic stop after 3 min | `Ringing started` at 21:29:43.535, `Ringing stopped` at 21:32:43.537; `ringing:false` status received at 180.4 s; volume restored |
| 10 | `adb reboot` | standby restarts on its own | foreground service restarted by `BOOT_COMPLETED`, `isForeground=true`, log `Connecting to the relay` less than a minute after `adb reboot`; next `ping` acknowledged |

Screenshots: `captures/android-sonnerie.png` (scenario 1), `captures/android-sonnerie-verrouille.png` (scenario 7).
The emulator is in English: the interface is therefore shown in English (`values-en` fallback), as expected.

## Build checks

- `./gradlew testDebugUnitTest`: `tests="11" skipped="0" failures="0" errors="0"`.
- `./gradlew lintDebug`: no errors.
- `cd relay && npm test`: `# tests 40 / # pass 40 / # fail 0`.
- `./gradlew assembleRelease`: the `SONNE_PAIR` and `SONNE_DEBUG` strings are absent from the release dex (removed by R8, `BuildConfig.DEBUG` is false), present in the debug one.

## What was added for this test

- `MainActivity` (debug only): `relay` intent extra that sets the relay address and forces standby to reconnect.
- `VeilleService` (debug only): the pairing link is written to the log under the `SONNE_PAIR` tag.
- `relay/tools/controleur.js`: command-line controller.

No blocking bug found in the app or in the relay.

## Limits and untested points

- **Total silence**: the sound is muted by Android (scenario 3). "Priority" mode lets alarms through by default, so the ringing gets through.
- **60 s window on the relay side**: if the phone disappears without closing its connection (abrupt cut-off), the relay believes it is online for up to 60 s without a pong. A command sent during that time is marked `delivered:true` but lost, instead of being queued. When the phone reconnects, its new connection replaces the old one immediately.
- **Network change**: standby restarts the connection on every new network, even if the old one still worked (seen in scenario 5: one extra reconnection 3 s after the first). Harmless.
- **PIN lock** not tested: the emulator has no PIN (security settings left unchanged). Full screen over the lock screen is therefore checked with the screen off, without a PIN.
- **Flashlight and vibration**: called without errors, but the emulator has no real flash or vibrator. To be checked on a real phone.
- **Deep sleep (Doze)** not tested over time: the app was on the allowlist.

## After the review: protocol v2

Date: October 18, 2026, same emulator (`Pixel_7_Pro_API_36`, Android 16), local relay on port 7700, debug APK v2 (`targetSdk 34`) installed **over** v1 with its data.

| # | Scenario | Expected | Observed |
|---|----------|---------|--------|
| 11 | Update from v1 to v2 without uninstalling | secret and settings kept, switch to device-protected storage | the encrypted box `s` from `/data/user/0/…/sonne-coffre.xml` ends up identical in `/data/user_de/0/…/sonne-coffre.xml`, the old folder is empty, the relay address is kept; reconnection in v2 right away (`MY_PACKAGE_REPLACED`) |
| 12 | v2 connection (proof P in `Sec-WebSocket-Protocol`, nothing in the URL) | relay accepts, commands and statuses go through | `/health`: `phones: 1`; `controleur.js ping`: `delivered:true` then decrypted status with `ack` in 3 ms |
| 13 | `ring` in Do Not Disturb "No interruptions", alarm at minimum | **rings** (this was the limit of scenario 3) | `mZenMode` goes from `ZEN_MODE_NO_INTERRUPTIONS` to `ZEN_MODE_OFF`; player `usage=USAGE_ALARM state:started mutedState:none` (instead of `opPlayAudio` in v1) |
| 14 | `stop` | everything is restored | `mZenMode` returns to `ZEN_MODE_NO_INTERRUPTIONS`, alarm volume back to 1, no player, no ringing notification |
| 15 | Watchdog | `ELAPSED_WAKEUP` alarm about every 5 min | `dumpsys alarm`: `*walarm*:dev.sidali.sonne/.ChienDeGarde`, inexact window of 3 min 45; fired, rescheduled at +5 min, heartbeat answered, no needless reconnection |
| 16 | Half-dead connection (TCP proxy that swallows everything without closing, like an expired NAT) | detected by the heartbeat | `No answer to the heartbeat: reconnecting` 20 s after the heartbeat; once the proxy is restored, online (`Reachable`), `ping` acknowledged |
| 17 | Debug receiver | adb yes, other app no | `am broadcast` from the shell: relay set and reconnection; the same broadcast sent with the app's uid (without `DUMP`) is refused with `SecurityException` |
| 18 | `adb reboot` | standby restarts on its own | service restarted and connected 11 s after `sys.boot_completed`, relay `phones: 1` |

Not tested: Direct Boot with a real lock PIN (the emulator has none, and its security settings are left unchanged), deep Doze over time (the emulator does not really suspend its processor, a `Handler` always advances there), real flashlight and vibration.

Method note: `kill -STOP` on the relay does not freeze the process in this sandbox (it stays in state `S`); hence the "black hole" proxy of scenario 16.

## Nearby (app 1.1.0): two emulators linked over Bluetooth

Date: October 19, 2026. Two instances of `Pixel_7_Pro_API_36` (Android 16) started with `-read-only` on ports 5554 (protected phone) and 5556 (searcher). The emulator's virtual Bluetooth (netsim) links the two: one's beacon is seen by the other, with a fake RSSI of +20 dBm (gauge always "Very hot"). Local relay on `http://10.0.2.2:7700`.

Pitfall: both instances start from the same image, hence the same secret. `adb -s emulator-5556 shell pm clear dev.sidali.sonne` on the searcher draws a new one.

| # | Scenario | Observed |
|---|----------|--------|
| 1 | Protected phone startup | `GATT server opened`, `Beacon on air (window 1991513, -15 dBm)`, `ROTATION` alarm set at 00:30:01 (`dumpsys alarm`) |
| 2 | Searcher: added by pasted link (debug) then by 12 words (release) | confirmation with identifier `9087f9`, identical to the protected phone's; beacon recognized (filter on service data, `neverForLocation`): "Very hot, seen 0 s ago" |
| 3 | Ring, relay running | over GATT: `Command accepted: ring` on the protected phone, "Received over Bluetooth: ringing, battery 100%" on the searcher |
| 4 | Relay stopped, Stop | over GATT, without a relay: `Command accepted: stop`, "not ringing" status received |
| 5 | Searcher's Bluetooth off, Ring | "Bluetooth is off" on the screen, command sent through the relay: "Delivered through the relay", the protected phone rings |
| 6 | Protected phone's Bluetooth turned off then on again | `Bluetooth on: beacon restarted`, GATT server reopened |
| 7 | Window boundary (00:30) | inexact alarm served at 00:32:44: `Beacon on air (window 1991514)`; the searcher recognizes the beacon without interruption (windows w - 1, w, w + 1) |
| 8 | Signed release APK on the searcher | added by 12 words, Ring and Stop over GATT: OK (R8 breaks nothing) |
| 9 | "Change key" on the protected phone | beacon restarted right away with the new key; the old searcher shows "Lost" |

Not covered on the emulator: scanning a real QR code (the Camera2 preview shows and the analysis runs without errors, but the virtual scene shows no QR in the default framing), gauge thresholds (fake RSSI), beeps (muted emulator), a command received both over Bluetooth and through the relay.

### Second run: evidence from the log, protected phone offline

Date: October 19, 2026, 00:39 to 00:47. Emulator 35.6.11: virtual Bluetooth goes through `netsimd`, started on its own with the first instance (no `-feature` option needed; `avdmanager` is missing from the SDK, hence two `-read-only` instances of `Pixel_7_Pro_API_36` rather than a second AVD). A = searcher (`emulator-5554`), B = protected phone (`emulator-5556`). Debug APK installed with `adb install -r -g` after uninstalling on both (so each draws its own secret). **No relay started**, and B cut off from any network (`svc wifi disable`, `svc data disable`: `Active default network: none`, `ping 10.0.2.2`: `Network is unreachable`).

Pairing B on A without a screen, through the debug receiver (new `pair` extra, same path as the pasted link):

```bash
adb -s emulator-5556 logcat -d -s SONNE_PAIR            # http://10.0.2.2:7700/#s=…
adb -s emulator-5554 shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver \
    --es pair "'http://10.0.2.2:7700/#s=…'" --es nom B   # "Other phone added by adb: AJOUTEE (id cd0074)"
```

Nearby screen opened on A with `uiautomator dump` then `input tap` ("Find a phone nearby", "Ring", "Stop ringing"). In debug, the searcher logs (removed from the release by R8, checked in the dex) the first recognition of each `id_ble` and, every 2 s, the raw signal, the smoothed signal and the level.

| # | Scenario | Observed log |
|---|----------|----------------|
| 10 | B's beacon | `SonneBalise: Beacon on air (window 1991514, id_ble 06b2a43cd320c7fc, -15 dBm)` |
| 11 | Recognition on A | `SonneChercheur: Beacon recognized: chain cd0074, id_ble 06b2a43cd320c7fc, window w+0 (1991514), address 40:3C:12:6D:1E:0A, 20 dBm` (same `id_ble` as the one emitted by B) |
| 12 | Signal strength | `Signal cd0074: raw 20 dBm, smoothed 20.0 dBm, level 5, seen 16 ms ago`, every 2 s; screen "Very hot, Seen 0 s ago" |
| 13 | Ring, B offline | A: `Sending ring over Bluetooth (GATT)`, `Command ring written over GATT`, `Acknowledged over Bluetooth: ring, ringing true, battery 100%` (1.3 s in total); B: `Command accepted: ring (Bluetooth)`, `Ringing started`, player `USAGE_ALARM state:started`; A's screen "Received over Bluetooth: ringing, battery 100%" |
| 14 | Stop, B offline | A: `Acknowledged over Bluetooth: stop, ringing false` (0.5 s); B: `Command accepted: stop (Bluetooth)`, `Ringing stopped`, no active player left |
| 15 | B's Bluetooth off, Ring | the beacon takes about 16 s to disappear (slow shutdown of the emulator's stack), then "Lost"; Ring: `Bluetooth given up (timeout): going through the relay` at 5 s, `Relay unreachable: ConnectException`, screen "Could not reach the phone, neither over Bluetooth nor through the relay." No crash on B (same process) |
| 16 | B's Bluetooth back on | `Bluetooth on: beacon restarted`, `GATT server opened`, `Beacon on air`; A sees B again in under a second |
| 17 | A's Bluetooth off during the scan | "Bluetooth is off" card with "Turn on Bluetooth", gauge "Lost", no crash (same process); back on: the scan restarts on its own, "Very hot" in under 15 s |
| 18 | Window boundary (00:45) | inexact alarm served at 00:46:53: `Beacon on air (window 1991515, id_ble cc5f73b81a898b2a)`; on A, in the same second: `Beacon recognized: … id_ble cc5f73b81a898b2a, window w+0 (1991515), address 64:5E:57:22:BB:BF` (new address); between 00:45 and 00:46:53, the old `id_ble` is still recognized as w - 1 |
| 19 | Ring then Stop after the rotation | over GATT, same logs as in points 13 and 14, B still offline |

JVM tests added (`ProximiteTest`): recognition at the window boundary and with clocks offset by 14 min 59 s in both directions, rejection of a rotation two windows late or of an unknown version byte; step-by-step smoothing over a series of measurements (steady rise, α = 0 at zero Δt, isolated spike damped to 12 %, reset after 10 s). 29 JVM tests pass (17 CryptoTest, 12 ProximiteTest).

No blocking bug found. Still not tested: real QR code, gauge thresholds and beeps (fake RSSI of +20 dBm, muted emulator), a command received both over Bluetooth and through the relay.

### After the 1.1 review: search mode

Date: October 19, 2026, 01:19 to 01:34. Same two `-read-only` instances of `Pixel_7_Pro_API_36` (A = searcher `emulator-5554`, B = protected phone `emulator-5556`), debug APK installed with `adb install -r -g` after uninstalling, local relay `PORT=7700 node relay/server.js` set through the debug receiver on both.

| # | Scenario | Observed log |
|---|----------|----------------|
| 20 | B started, relay reached, Bluetooth on | neither `Search mode` nor `Beacon on air`: outside search mode, nothing is emitted |
| 21 | A opens Nearby | A: `Sending ping through the relay` (01:21:50.861); B: `Command accepted: ping (relay)`, `Search mode opened`, `GATT server ready` and only then `Beacon on air` (01:21:50.926); A: `Beacon recognized` 83 ms after the ping |
| 22 | Ring (first try) | **bug found**: `Status too long for the connection MTU (518 > 514)` on B, acknowledgement lost, A falls back to the relay after 5 s. Cause: `JSONObject` escapes the "/" of the base64. Fixed: status message written by hand, always 508 bytes (JVM test added) |
| 23 | Ring, Stop, Ring, Stop over GATT | acknowledgements received 421, 295, 349 and 427 ms after the tap; B: `Command accepted: … (Bluetooth)` then `Ringing started` or `stopped` 11 to 17 ms after acceptance (acknowledgement before the heavy work); a single address, the beacon stays visible after each connection (advertising set re-enabled, same address) |
| 24 | B's standby notification during the mode | `Reachable · Bluetooth search on` |
| 25 | A leaves Nearby, waiting | B: `Search mode closed` at 01:34:09.665, 10 min after the last command (01:24:09.061); notification back to `Reachable`; `GATT Advertiser Map` empty |
| 26 | Watchdog | `dumpsys power`: `ACQ sonne:garde` 01:32:22.393, `REL sonne:garde` 01:32:22.426 (33 ms, instead of 30 s); beacon rotation served by the same alarm (`Beacon on air (window 1991518)`), lock released right away |

A first try had also shown that fully restarting the beacon after a connection changed its address: the following stop first tried the dead address (5 s lost). Fixed twice: re-enabling the existing advertising set, and only addresses heard in the last 2 s are tried.

Not tested here: opening search mode after an hour offline (JVM tests only), saturation of the GATT server by a stranger, Bluetooth name read by a third party (nRF Connect on a real phone), reading `bluetooth_off_timeout` on GrapheneOS, release APK in a real setting (the local relay is on `http://`, refused in release).

## Built-in update (app 1.2.0), 2026-10-20

Emulator `Sonne_Update_API_36` (system image of `Pixel_7_Pro_API_36`, Android 16 with Play Store), started with `-port 5640 -no-window`. Local relay on a free port with `SONNE_RELEASES_DIR` pointing to a test folder; the debug app set to `http://10.0.2.2:<port>` through `DebugRelaisReceiver`.

```bash
./gradlew assembleDebug                                                  # 1.2.0 (4), installed
./gradlew assembleDebug -PsonneVersionCode=5 -PsonneVersionName=1.2.1    # 1.2.1 (5), served by the relay
apksigner verify --print-certs sonne-1.2.1.apk                          # certSha256 (debug key)
```

| Scenario | Result |
| --- | --- |
| App opened, 1.2.1 announced | log `Announced 1.2.1 (5), installed 1.2.0 (4)`, "Update available" notification on the `mises_a_jour` channel (importance 3), banner on the home screen |
| Tap on the notification without the "Install unknown apps" permission | explanation banner, the button opens Sonne's system setting; permission granted, back: "Download and install" button |
| Announcement with the wrong `sha256` | `Update refused (SHA256)`, banner "Update refused… checksum", `cache/maj` empty |
| APK re-signed with another key, announcement consistent with it (checksum and `certSha256` of the other key) | `Update refused (CERTIFICAT)`, banner "not signed with the same key", file deleted |
| Correct announcement | `APK 1.2.1 verified (SHA-256 and certificate)`, system screen "Do you want to update this app?", accepted with `adb shell input tap` |
| Play Protect (image with Play Store) | on the first try, "App scan recommended" screen; once closed, the installation is cancelled and the banner comes back; on the second try, installed directly |
| After installation | `dumpsys package`: `versionCode=5`, `versionName=1.2.1`; `MY_PACKAGE_REPLACED` restarts standby, same chain id, log `Update installed` |

Limit: the test goes through the debug build (local relay on `http://`, refused in release). The 1.2.0 release was checked online (announcement and download), not installed over itself.

## Encrypted commands: page and relay 1.6 (relay 1.2.0), 2026-10-25

Everything local, nothing deployed. Relay `node relay/server.js` on 127.0.0.1 (ports 7751 to 7753), fake phone `relay/tools/faux-telephone.js` (`--ancien` for app 1.5: old format only, no `cmdv`; without option for app 1.6: reference filter `creerFiltreCommandes`, `"cmdv":3`, status padded to 508 bytes, a single status per command), page served by the relay and driven in headless Chromium (Playwright already present on the machine, fresh contexts, WebSocket frames captured).

| # | Scenario | Observed |
|---|----------|--------|
| 1 | Relay in transition (`SONNE_CMD_V2=1`), phone 1.5, page opened from the link | fragment cleared, "Online", "app 1.5.0"; automatic check sent in v2 |
| 2 | Ring then Stop to app 1.5 | v2 frames (`cmd`, `sig`); acknowledgements "Phone is ringing", "Ringing stopped"; no `sonne.cmdv3` flag |
| 3 | The phone moves to 1.6 (same key) | a single check when it comes back online, still in v2; the status announces `cmdv` 3: flag set, "Commands to this phone are now encrypted" in the activity |
| 4 | Ring then Stop | 172-byte frames, keys `type,v,nonce,enc`; accepted in v3 by the phone; `relayed` and `ack` carry the 24-hex nonce |
| 5 | Page reload | the automatic check already goes out in v3 (persistent flag) |
| 6 | Phone offline | no automatic check; Ring v3: "Phone offline: the command waits on the relay"; delivered by `welcome` on reconnection and accepted |
| 7 | Keys stored before 1.6 (`{id, cleSig, cleEnc}` without `cleCmd`, flag set) | `cleCmd` derived on load and stored (secret, non-exportable, `["encrypt"]`); Ring v3 accepted |
| 8 | Remove the phone | its flag disappears with it |
| 9 | Relay cut off (`SONNE_CMD_V2=0`), fresh browser, phone 1.6 | v2 check refused (400 `old command format…`), sent again in v3 right away, silently; flag set |
| 10 | Ring then Stop on this relay | v3 directly |
| 11 | Relay cut off, fresh browser, phone offline, Ring | v2 refused, same intent sent again in v3 (a single "Ring: command sent" line), queued, accepted on reconnection |
| 12 | Texts and cache | footer and About up to date; service worker cache `sonne-v6` |

Relay log: no mention of `ring`, `stop` or `ping` (only startup and counters). `cd relay && npm test`: 79 tests, 0 failures (including the v3 vectors replayed by `web/crypto.js` and the new relay tests: v3 relayed in 172 bytes, v3 ping queued, in-flight v3 redelivered, at most 10 queued, 400 on a v2 after the cut-off, `SONNE_CMD_V2` read at startup).

Not covered here: the real Android 1.6 app against this page (Android batch), Safari and Firefox, and the move from an installed 1.5 page (cache `sonne-v5`) to 1.6.

## Android app 1.6.0: encrypted commands, 2026-10-25

Everything local, nothing deployed. Emulator `emulator-5554` (Android 16, Google image, `sdk_gphone64_arm64`) with the **1.5.0 debug app installed with its key**, updated to **1.6.0 debug over it** (`adb install -r`: same id `00a347`, reconnection right away). Local relay 1.2.0 (`node relay/server.js`, ports 7741 to 7743, `SONNE_CMD_V2=1` unless stated otherwise), reached from the emulator at `http://10.0.2.2:<port>` (debug receiver). Controller `relay/tools/controleur.js` (v3 by default, `--v2`), a small Node script for the refusals (same functions from `relay/crypto.js`), fake phone `relay/tools/faux-telephone.js`, and the local page in headless Chromium (Playwright). Global settings noted before (Do Not Disturb off, normal ringer, alarm volume 6, Bluetooth on) and found the same at the end.

| # | Scenario | Observed |
|---|----------|--------|
| 1 | App **1.5.0**, relay in transition: `ping` v3, then `ping` v2 | v3 relayed (172 bytes) and ignored; v2 acknowledged (32-hex `ack`), status without `cmdv` |
| 2 | 1.6.0: `ring` v3 | `delivered:true`; **a single status** in 20 s, 85 ms later: 508 bytes, `ack` = 24-hex nonce, `ringing:true`, `"cmdv":3`, `app` 1.6.0; log `Command accepted: ring, v3 (relay)`; player `USAGE_ALARM state:started`, alarm volume 6 then 7 |
| 3 | `stop` v3, then `ping` v3 | one status each (`ringing:false`, then `ack`); no player left, volume back to 6 |
| 4 | `ring` then `stop` in **v2** (transition) | accepted, 32-hex `ack`, one status each |
| 5 | The same v3 replayed; a 16-min-old v3; a v3 encrypted with another key | a single acknowledgement; debug log `Command refused: rejeu`, `fraicheur`, `authentification`; nothing else |
| 6 | `ring` v3 of **60 s, flashlight off** | acknowledgement `ringing:true`, then automatic stop at 60.09 s (setting: 3 min) with its own status (`ack` null) |
| 7 | Phone stopped (`am force-stop`), `ring` v3 | delivered to the dead connection, queued again when it closed (in flight), accepted on reconnection |
| 8 | Phone offline (presence `false`), `ping` v3 | `{"delivered":false,"queued":true}`; delivered by `welcome`, accepted |
| 9 | **Cut-off** build (`./gradlew assembleDebug -PsonneCoupureV2=true`): `ping` v2, then `ping` v3 | v2 silently refused (`ancien-format`, no status); v3 acknowledged |
| 10 | Searcher (same `EnvoiCommande` as Nearby, launched by the debug receiver, `--es envoyer ring --es vers 84c083`), **relay cut off** (`SONNE_CMD_V2=0`), fake phone 1.6 | `ring` in v2 refused (400) in 79 ms, same intent sent again in v3 with a new nonce, delivered; the fake phone accepts it in v3. Same for `stop` and `ping` |
| 11 | Searcher's automatic check (`--ez auto true`) | phone online: presence, then `ping` sent; offline: `Phone offline: automatic ping not sent`, nothing is sent |
| 12 | Searcher's `ring`, fake phone offline | v2, 400, v3: `queued:true` |
| 13 | **Local page** (headless Chromium, fresh browser) against the real app | link accepted (confirmation with `00a347`); automatic check in v2 (162 bytes), the app's status announces `cmdv` 3: "Commands to this phone are now encrypted"; Ring: 172-byte frame `type,v,nonce,enc`, "Phone is ringing (confirmed in 73 ms)", a single status; Stop: v3, "confirmed in 59 ms"; after reload, the check already goes out in v3; the page shows "app 1.6.0" |
| 14 | What leaves your device screen | Ring commands: "Encrypted: yes, end to end", text on the fixed size and Sonne 1.5; What the relay sees: identical sizes for all commands (`captures/android-ce-qui-sort-1.6.0.png`) |

JVM: `./gradlew testDebugUnitTest`: 52 tests, 0 failures (26 CryptoTest including `K_cmd`, RFC 5869 A.1 for Expand, the 4 messages and `etat_v3` byte for byte, the 19 cases in transition and after the cut-off, the shapes; 20 ProximiteTest including the size of GATT writes in both phases and the plaintext of the hand-written status; 6 MiseAJourTest). `lintDebug`: no errors.

Conditions: very loaded Mac (photo upload, swap memory almost full). The emulator had several ANRs (including app **1.5.0**, before the update) and a `system_server` restart by its watchdog (ActivityManager blocked for 69 s): commands sometimes waited up to a minute for the main thread to free up. Nothing was lost or executed twice.

Not covered: the Bluetooth (GATT) path between two 1.6 phones (it needs two emulators; only the message size and the write rules are tested on the JVM), learning `cmdv` from a status received over GATT, a real phone, the release APK in a real setting (local relay on `http://`).

## Adversarial review of 1.6.0, 2026-10-25

Everything local, nothing deployed. Repository relay 1.2.0 on `127.0.0.1:7761` (transition, then `SONNE_CMD_V2=0`), serving the 1.6 page; 1.5 page (commit `dc3638b`) served by a small proxy that forwards `/ws` and `/api` to the same relay; 1.6.0 debug app on `emulator-5554`; headless Chromium (Playwright), WebSocket frames captured. Global emulator settings noted before and identical after.

| # | Scenario | Observed |
|---|----------|--------|
| 1 | Vectors recomputed without the repository code (Python, `cryptography`) | `K_cmd` (HMAC and HKDF-Expand), the 4 messages byte for byte, `etat_v3`, the 19 cases: identical. `genere-vecteurs.js` gives back exactly `docs/VECTEURS.json` |
| 2 | Release 1.5.0 (code 8), then the 1.6.0 test APK over it (`adb install -r`), emulator network cut off | installed; same uid, `firstInstallTime` unchanged, same id (`34d2f6`) before and after; certificate `3100c7dc…02e3` on both sides; the test APK is identical to `assembleRelease` of commit `bcfd0cb` |
| 3 | 1.6 page, fresh browser, relay in transition | automatic check in v2 (162 bytes), status with `cmdv` 3, flag set; Ring and Stop: 172 bytes, keys `type,v,nonce,enc`, a single 508-byte status each; after reload, the check goes out in v3 |
| 4 | The captured v3 ring, replayed by another controller | `relayed`, `delivered:true`, no status; app log `Command refused: rejeu` |
| 5 | 1.5 page during the transition | check, Ring and Stop in v2 accepted by app 1.6, ringing started then stopped; no flag |
| 6 | `controleur.js`: ping, ring, stop in v3, three rounds | one status each, 24-hex `ack` |
| 7 | Relay cut off, fresh 1.6 page, then 1.5 page | 1.6 page: v2 refused (400), same intent sent again in v3, flag set; 1.5 page: "Refused by the relay: old command format: reload the page or update the app" |
| 8 | Relay that swaps two commands (A held back, newer B delivered first) | A ring, B stop: A refused ("ordre"), a single status; A ring, B ping: two statuses. Active leak documented in `PROTOCOL.md` and `THREAT_MODEL.md` |
| 9 | Relay log | no command type |

Acknowledgement delay on the very loaded emulator (system ANR): ring 1.7 to 2.6 s, stop 4.8 to 9 s, ping 1 to 6 s. The gap probably comes from the main thread being busy during ringing; to be measured on the Pixel: if it is confirmed there, the acknowledgement delay is one more clue for the relay.

Not covered: the Bluetooth (GATT) path between two 1.6 phones, a real phone.
