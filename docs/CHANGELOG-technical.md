# Technical notes, versions 1.0 to 1.2

Detailed notes from the first releases, kept as written at the time. The human changelog is `CHANGELOG.md`.

## 1.2.0 (2026-10-20): built-in update

Like the non-Play versions of Signal or Telegram: the app updates itself, from the relay (the GitHub repository is private). Protocol v2 unchanged (section "Update (app 1.2)").

### Added

- **Relay 1.1.0**: `GET /update/android.json` (announcement `{ version, versionCode, url, sha256, certSha256, notes }`, 5 min cache) and `GET /download/<nom>.apk`, served from `SONNE_RELEASES_DIR` (host folder mounted read-only in `deploy/docker-compose.yml`). Names strictly validated, 404 for everything else. Tests `relay/test/update.test.js` (directory traversal, encodings, missing files, invalid announcement).
- **App**: check once a day in the existing watchdog and when the app opens, can be turned off (Settings, "Check for updates", on by default, with "Check now"). "Update available" notification (new Updates channel, normal importance) and a banner on the home screen.
- On tap: OkHttp download into the cache, then checks of the announced SHA-256, the expected package and `versionCode`, and a signing certificate identical to that of the installed app and to `certSha256`. Otherwise an explained refusal and the file is deleted.
- Installation through `PackageInstaller` with `USER_ACTION_REQUIRED` (Android's confirmation screen is always shown), `REQUEST_INSTALL_PACKAGES` permission, an explanation and a button to the "Install unknown apps" setting when it is missing.
- `tools/release-android.sh`: signed release APK, checksums, `android.json`, copy to the VPS with scp, online check, GitHub release with the APK and its checksum. `--sans-publication` to only prepare.
- JVM tests `MiseAJourTest`: version comparison, announcement validation, SHA-256 of a file, refusal of a different certificate (fake bytes).
- `versionCode` and `versionName` can be overridden with `-PsonneVersionCode` and `-PsonneVersionName`, for the end-to-end test only.

## 1.1.0 (2026-10-19): Nearby

"It's not far, I'm getting closer, and I make it ring." Pure addition to protocol v2 (section "Bluetooth proximity (v1.1)"): relay and web page unchanged.

### Added

- **Bluetooth beacon** emitted by the standby service **in search mode only** (see below): connectable legacy BLE advertising, low power (`TX_POWER_LOW`), 250 ms, service data under the Sonne UUID `2af8c8b3-3a13-489b-a3a3-ac220005799b` = `id_ble` (8 bytes) then version `0x01`. `id_ble = HMAC-SHA256(B, w)[0:8]`, `B = HKDF(S, "sonne-ble", 32)`, `w = floor(now / 15 min)`: only a device that knows S recognizes it, and it changes every 15 min (the advertising set is restarted, the stack picks a new address). Restarted when Bluetooth comes back, rechecked by the watchdog, rotated by an alarm at the window boundary.
- **GATT server** on the same UUID: Command characteristic (write with response) that accepts exactly the signed commands from the relay, through the same path (`FiltreCommandes`, same nonce memory: a command received over Bluetooth and then through the relay is executed only once); Status characteristic (notification) with the usual encrypted status. At most 4 Sonne connections (eviction of the oldest one without a valid command), 5 s without a valid command, 10 s after, request budget per connection, 20 valid commands per minute.
- **My other phones**: list of known chains (local name, relay), added by scanning a QR code (camera, permission requested at scan time), by pasted link or by 12 words, with confirmation by the start of the identifier. Only the derived keys (id, `K_sig`, `K_enc`, B) are kept, not S, encrypted with an Android Keystore key usable only while the phone is unlocked, in credential-encrypted storage. This phone's own key is refused.
- **Nearby screen** from the home screen ("Find a phone nearby"): BLE scan filtered on the Sonne UUID in the foreground only, recognition for w - 1, w and w + 1, smoothed signal (exponential average, τ = 2 s), gauge from 0 to 5 (Lost to Very hot) with 3 dB hysteresis, "seen x s ago" and last measurement, generated beep that speeds up (notification stream, can be turned off), vibration, big Ring button over GATT if the phone was seen less than 10 s ago, otherwise (or on failure) the same signed message through the relay; Stop button.
- Settings: "Bluetooth beacon" (on by default) and "Proximity beeps". Permissions: "Nearby devices" row (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`). Home: beacon status and a reminder when Bluetooth is off.
- Permissions: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` with `neverForLocation` (no location), `CAMERA` (QR scan only). Bluetooth and camera hardware not required.
- Debug build, for tests on two emulators: adding another phone through adb (`pair` extra of `DebugRelaisReceiver`), log of beacon recognition and of the smoothed signal (`SonneChercheur`), absent from the release.
- QR code reader without any new dependency: Camera2 and ZXing core (already present).
- JVM tests: `ble` vector from `docs/VECTEURS.json` (B, `id_ble`, neighboring windows, service data), link to secret, and `ProximiteTest` (data and filter, recognition over three windows, smoothing, levels and hysteresis, rate limit, status freshness, GATT write statuses).
- `relay/crypto.js`: `deriveCleBle`, `deriveBleId`, `fenetreBle`, with their vector and an independent WebCrypto test.

### Changed (security and reliability review of 1.1)

#### Critical

- **Search mode**: the beacon and the GATT server no longer run 24 hours a day. They open for 10 min after each accepted command (`ring`, `stop` or `ping`, through the relay or over Bluetooth), while ringing, or when the relay has been unreachable for an hour. Before, the beacon was connectable at all times: anyone in range could connect to it and read the phone's Bluetooth name (Android's GAP service) and the fingerprint of its GATT database, fixed values that linked all the windows together. The Nearby screen requests search mode through the relay (signed, silent `ping`) when it opens and then every 8 min, and says whether the request went through. The standby notification shows "Bluetooth search on". Settings recommend a neutral Bluetooth name.

#### Major

- Tracking by holders of S: B is still derived from S (the 12 words and the link are enough to add a phone in Nearby), but the beacon now only emits in search mode. The Pairing screen warns: whoever has the 12 words can tell when the phone is nearby while the beacon emits, and can make it emit.
- GATT server: signature, freshness and unseen nonce checked before the limit of 20 commands per minute (a stranger who writes random bytes, or who replays a captured command, no longer blocks real commands); 2 invalid writes per connection, then disconnection; writes from a refused or cut-off connection ignored.
- GATT server: when full, eviction of the oldest connection without a valid command instead of refusing the newcomer; 5 s of life without a valid command (30 s before).
- GATT server: budget of 10 ATT requests per connection, then disconnection; the nonce memory is only rewritten if it changed.
- GATT server: a connection is only counted on its first access to the Sonne service. Watches, earbuds and other apps' links no longer take up a slot, and a slot is freed as soon as Sonne disconnects, without waiting for a disconnection that may never come.
- GATT server: `onServiceAdded` tracked; ready only on success, retried at 1, 2, 5, 10 then 30 s (also when `openGattServer` fails right after Bluetooth comes back); the beacon only emits once the service is published.
- Searcher: the device from the `ScanResult` is kept (random address type) instead of `getRemoteDevice(adresse)`, which targets a public address since Android 13.
- Searcher: second connection attempt on a status 133, 62 or 8 received before 3 s; scan paused during the GATT connection.
- Acknowledgement: the phone notifies the status (with `ringing` expected) before the heavy work of ringing; the searcher waits 5 s (3 s before) and, if the write was accepted but nothing comes back and the relay fails, shows "written over Bluetooth, no acknowledgement" instead of a failure.
- Battery: the watchdog's 30 s wake lock is released as soon as the heartbeat is answered (or right away if there is nothing to wait for); the rotation alarm's lock as soon as the rotation is done.
- GrapheneOS: automatic Bluetooth turn-off (setting `bluetooth_off_timeout`) is detected when it is readable and reported on the home screen, in Permissions and in Nearby.

#### Minor

- Statuses notified over Bluetooth only to connections that wrote a valid command; status plaintext padded to 320 bytes (`pad` field, model truncated to 64 characters): the ciphertext length no longer reveals anything about the model or the version. The status message is written without escaping (508 bytes, always): `JSONObject` escaped the "/" of base64 and could exceed the 512 bytes of a GATT attribute (found while testing on emulators).
- Searcher: signal tracked address by address (a clone that re-emits `id_ble` no longer hijacks the gauge or the send), up to 3 recent addresses tried for Ring, alert when two addresses emit the same `id_ble` for more than 3 s.
- My other phones: credential-encrypted storage (unreadable before the first unlock), Keystore key `setUnlockedDeviceRequired`, only the derived keys, list dropped from memory when leaving the screen. The old list is moved once.
- Backup: the `device_*` domains (device-encrypted storage) are excluded from cloud backup and device transfer.
- Nonces and ring/stop ordering written synchronously (`commit`) before executing the command.
- Beacon: restarted after a GATT client disconnects and when the stack disables it (`onAdvertisingEnabled`); `id_ble` logged in debug only.
- Home: the beacon status follows the stack's callbacks (emitting, refused, stopped), with a short retry on failure.
- GATT server: short lock between receiving a write and processing it; notifications queued, one at a time, return codes logged.
- Searcher: live tracking of the Bluetooth state (scan restarted when it comes back), restart of a scan that has been silent for 60 s; scan starts limited to 4 per sliding 30 s (instead of a fixed 10 s gap, which delayed the scan when returning to the screen).

#### Dismissed

- Non-resolvable address for the beacon: `setOwnAddressType` is reserved for system apps (`BLUETOOTH_PRIVILEGED`, absent from the public SDK). Documented limit.
- Beacon secret separate from S: it would require a second phone-to-phone pairing, and the 12 words would no longer be enough for Nearby. Search mode limits tracking to the moments when someone is searching.
- 1 s interval for the beacon: irrelevant as long as it only emits in search mode; 250 ms kept for gauge responsiveness.

## 1.0.1 (2026-10-18)
- Android app in English by default, French as a translation.

## 1.0.0 (2026-10-18)

### Added

- Repository foundation: Apache-2.0 license, `.gitignore`, README.
- `relay/crypto.js`: identifier, HKDF keys, signed commands (HMAC-SHA256), encrypted statuses (AES-256-GCM), 12 BIP39 words and pairing link.
- `docs/VECTEURS.json`: shared test vectors, generated by `npm run vecteurs`.
- `node --test` tests of the cryptography module, cross-checked with WebCrypto and the official BIP39 vectors.
- `relay/server.js`: HTTP and WebSocket relay (phone, controllers, `/api/cmd`, `/api/presence`, `/health`), 15 min queue, rate limits, graceful shutdown.
- `web/`: command page without a framework (WebCrypto), installable as a PWA, several phones, log.
- `relay/tools/faux-telephone.js`: fake phone to try the page without the app.
- `relay/tools/controleur.js`: command-line controller (sends `ring`, `stop` or `ping` and decrypts the received statuses).
- Android app, debug build: relay address settable through adb (`DebugRelaisReceiver` receiver protected by the `DUMP` permission) and pairing link in the log (tag `SONNE_PAIR`), for end-to-end tests.
- `docs/END-TO-END-TEST.md`: scenario and results of the test on an Android 16 emulator.
- `relay/Dockerfile` and `deploy/docker-compose.yml` (Traefik, `sonne.sidali.dev`).

### Changed (security and usability review, protocol v2)

v1 and v2 are not compatible: relay, page and app must be updated together. The page converts its saved phones on its own (new id, keys stored in IndexedDB); the app keeps its secret.

#### Critical

- App: `targetSdk 34` instead of 36. From 35 on, Android 15 and later prevent the app from lifting Do Not Disturb: the ringing stayed silent in "Total silence". Verified on Android 16: the mode is lifted while ringing and restored on stop. `Ringer.dndPilotable` keeps the code correct if this choice changes (no call, no dangerous restore, alert on the home screen).
- App: Direct Boot. Service, receivers and ringing screen `directBootAware`, start on `LOCKED_BOOT_COMPLETED`, preferences and secret in device-encrypted storage (one-time migration from the old storage), fallback sound in that storage's cache. Sonne is reachable after an automatic restart, before the code is entered.

#### Major

- Protocol v2: the phone proves it holds S by presenting `P = HKDF(S, "sonne-phone", 16)`; `id = SHA-256("sonne-id" ‖ P)[0:32]`, recomputed by the relay. Knowing the id no longer makes it possible to kick out the real phone or take its queue.
- Relay: the queue keeps the last 5 `ring` and `stop` (no duplicate nonce) instead of only the last one; a command delivered to a connection that dies before the next status is redelivered.
- Relay: limits on open connections (20 per IP, 10,000 in total, 16 browsers per phone).
- Relay: phone ping every 4 min (disconnection after 10 min) instead of 25 s, configurable with `SONNE_PHONE_PING_S` and `SONNE_PHONE_DEAD_S`; browsers keep 25 s and 60 s. Application-level heartbeat `hb`.
- App: `AlarmManager` watchdog (every 5 min, no permission) for the heartbeat, the periodic status and long reconnections; a half-dead connection is detected (no heartbeat reply within 20 s). No more OkHttp `pingInterval`.
- App: volume and Do Not Disturb are restored before going to the foreground, which no longer crashes the service if the system refuses it.
- App: an unreadable secret is no longer silently replaced. Transient error: retry. Established loss (repeated permanent failures): old box kept, alert on the home screen, new key only after confirmation.
- App: guided first launch. Tagline on the Permissions screen, then straight to Pairing; "Next step: link a browser" card on the home screen as long as pairing has not been opened. Do Not Disturb row hidden where it serves no purpose.
- App: the 12 words are presented as the way to ring the phone from any browser, to be written on paper or in a password manager.
- Page: request for persistent storage and Safari warning (7 days without a visit).
- README: installation (Obtainium, APK), first steps, limits.

#### Minor

- The id no longer goes in URLs: `Sec-WebSocket-Protocol` for WebSockets, `X-Sonne-Id` header for `/api`.
- Relay: per-IP limits on the /64 prefix in IPv6, IPv4-mapped addresses reduced to IPv4; `SONNE_QUEUE_MIN` capped at 15.
- App: replay protection by nonce age (kept while they are within the window, 2,000 at most, refused beyond that); a `ring` or `stop` older than the last accepted one is ignored.
- Page: replayed or too old statuses (more than 20 min) ignored; adding through a directly opened link requires confirmation with the start of the id, automatic name prefixed with "New: "; S is no longer kept, only non-exportable WebCrypto keys in IndexedDB; "No news from the phone" hint after a day of silence; no more hard-coded promised queue duration.
- Page: clearer empty-screen text (phone or computer), honest footer about the relay's role, AA contrast in light theme, 44 px touch targets.
- App: `FLAG_SECURE` on the Pairing screen; link host displayed and warning for a custom relay; start of the id displayed; `http://` refused in release (also in the setter); debug relay address set by a receiver protected by `DUMP` instead of a `MainActivity` extra.
- App: immediate reconnection when the network comes back after a total loss; orphaned ringing notification removed; flashlight tracked by `TorchCallback` (pause then resume); fallback to the backup sound if playback fails midway; ringing WakeLock rearmed on each `ring`.
- App: "Granted" green readable in dark theme, with a check mark; texts corrected ("Full screen over the lock screen", "over", "Keep them", "Change key" instead of "New chain").
- Protocol: `specialUse` instead of `dataSync` (with the reason), limits and `relayed` and `error` messages documented, actual list of the page's files, honest limit on browser history.
