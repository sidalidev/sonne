# Sonne: protocol and architecture (v2, v3)

> **v2 (October 2026)**, changes from v1, following the security review:
> the id is derived from a phone proof P (the relay checks that it really is the phone connecting),
> the id never goes into a URL anymore, the queue keeps several commands per type, application heartbeat,
> less frequent ping for the phone, anti-replay based on nonce age and ordering of ring/stop, app in Direct Boot
> and `targetSdk 34`. v1 and v2 are not compatible: relay, page and app must be updated together.
>
> **App 1.1 (Bluetooth proximity)**: BLE beacon with a rotating identifier and commands signed over GATT, without Internet (section "Bluetooth proximity"), active only in **search mode**. Pure addition to protocol v2: relay and web page unchanged (the `pad` field of the status plaintext is ignored by the page).
>
> **App 1.2 (built-in update)**: the relay serves `GET /update/android.json` and `GET /download/<name>.apk`; the app checks, downloads, verifies the checksum and the signature, then installs with Android's confirmation (section "Update"). Protocol v2 unchanged.
>
> **v3 (app 1.6, relay 1.6): encrypted commands.** Commands are no longer signed in plaintext but encrypted (AES-256-GCM, key `K_cmd` derived from `K_sig`, AAD bound to the id) in a fixed-size message: the relay and a Bluetooth observer can no longer tell apart `ring`, `stop`, `ping` or the parameters. The phone announces the capability in its status (`cmdv`), senders only send v3 to phones that have announced it, then the old format is cut off in two steps (section "Protocol v3: encrypted commands"). Connections, statuses, beacon and update unchanged.

Sonne rings your Android phone from a web page, even on silent, without Google, without an account.
Three pieces: the Android app (`android/`), the relay (`relay/`, Node 22, a single dependency: `ws`) and the web command page (`web/`, served by the relay).
Reference hosting: `https://sonne.sidali.dev` (VPS, Traefik, Docker). Anyone can self-host the relay.

## Principle: a sync chain, no account

- On first launch, the phone draws a **16-byte secret S** (128 bits) with `SecureRandom`.
- **Phone proof** `P = HKDF-SHA256(S, salt = empty, info = "sonne-phone", 16 bytes)`. The phone presents it to the relay on every connection to prove that it holds S, without revealing S, `K_sig` or `K_enc`.
- **Public identifier** `id = hex(SHA-256("sonne-id" ‖ P))[0:32]`: this is all the controllers give the relay. The relay recomputes it from P when the phone connects. Knowing the id is therefore no longer enough to impersonate the phone.
- **Signing key** `K_sig = HKDF-SHA256(S, salt = empty, info = "sonne-sig", 32 bytes)`.
- **Encryption key** `K_enc = HKDF-SHA256(S, salt = empty, info = "sonne-enc", 32 bytes)`.
- **Command key** (v3, app 1.6) `K_cmd = HKDF-Expand(K_sig, info = "sonne-cmd-v3", 32 bytes)`, derived from `K_sig` so that browsers and "My other phones", which do not have S, can compute it (section "Protocol v3: encrypted commands").
- The phone shows S in three forms: a **QR code**, a **link** `https://sonne.sidali.dev/#s=<base64url(S)>` (the `#` fragment is never sent to the server) and **12 BIP39 words** (English list, 128 bits + 4 checksum bits, exactly like a Bitcoin wallet).
- The browser does not keep S: it derives the id and the keys `K_sig` and `K_enc` from it, imports them as **non-extractable** WebCrypto keys and stores those in IndexedDB (several phones possible, each with a locally chosen name). Only if IndexedDB is unavailable does S remain as a fallback in `localStorage`.
- Revoking a controller = regenerating S on the phone ("Change key").

## Messages

Everything travels as JSON text over WebSocket. Timestamps in Unix milliseconds.

### Command (web → relay → phone), signed (v2)

> v2 format, accepted until the cut-off described in "Protocol v3: encrypted commands". Since app 1.6, senders send the v3 encrypted command to any phone that announces `cmdv ≥ 3` in its status.

```json
{ "type": "cmd", "cmd": "ring" | "stop" | "ping", "ts": 1792300000000, "nonce": "16 bytes in hex", "sig": "hex(HMAC-SHA256(K_sig, cmd + '|' + ts + '|' + nonce))" }
```

The phone accepts if: `sig` valid, `|now - ts| ≤ 15 min`, `nonce` never seen. Otherwise it silently ignores it.
- **Age-based anti-replay**: a nonce is kept (with the `ts` of its command) as long as that `ts` is within the 15 min window, that is, as long as a replay would still be accepted. Beyond 2,000 valid nonces at once, new commands are refused rather than forgetting a nonce that could still be replayed. (v1 kept the last 200: beyond that, a nonce still within the window could be replayed.)
- **Ordering**: a `ring` or `stop` whose `ts` is smaller than that of the last accepted `ring` or `stop` is ignored (its nonce is still recorded). A relay therefore cannot make the phone ring again by delivering an old `ring` after a `stop`. `ping` is not affected.

`ring` triggers the ringing (see below); `stop` stops it; `ping` only requests a status.

### Status (phone → relay → web), encrypted

```json
{ "type": "state", "nonce": "hex 12 bytes", "enc": "base64(AES-256-GCM(K_enc, iv = nonce, aad = id, plaintext))" }
```

`plaintext` = `{ "ts", "ringing": bool, "battery": 0..100, "charging": bool, "app": "1.0.0", "ack": "<nonce of the processed command or null>", "model": "Pixel 10 Pro", "cmdv": 3, "pad": "   …" }`. `cmdv` (app 1.6 and later) announces v3 encrypted commands; `ack` is 32 hex for a v2 command, 24 for a v3 one.
Since app 1.1, `model` is truncated to 64 characters and the plaintext is **padded to 320 UTF-8 bytes** with the `pad` field (spaces): the length of the ciphertext reveals nothing about the model or the version, and the whole message is 508 bytes, under the limit of a GATT attribute. Readers ignore `pad`.
The phone sends a status on connection, after each command, when ringing starts or stops (since 1.6, except when it is the command it just acknowledged that starts or stops it: see "Response: a single status per command"), and every 15 min. The status that acknowledges a `ring` or a `stop` already carries the expected `ringing` (`true` or `false`): it is sent before the heavy work of ringing (preparing the sound, volume, Do Not Disturb, screen), which follows immediately.
The relay cannot read it. The web page checks the AAD (= id) and displays: online, battery, ringing or not, acknowledgment.
**Freshness**: the page keeps the largest `ts` received per phone and ignores any status whose `ts` is lower or equal, or older than 20 min (the relay could replay old encrypted statuses). An `ack` is only taken into account in a fresh status.

### Relay

The id **never goes into a URL**: the access logs of a proxy (Traefik, nginx, CDN) record the full URL, and the id is exactly what is needed to target a phone.

- `GET /`: the web command page (static, `web/`).
- `GET /ws/phone`: phone WebSocket. Header `Sec-WebSocket-Protocol: sonne.v2, sonne.p.<hex(P)>`. The relay computes `id` from P, rejects with 400 any connection without a valid P, then answers with the `sonne.v2` subprotocol. It only evicts the previous connection with the same id (code 4000, "most recent wins") and only delivers the queue to a client that presented P.
  On open: `{"type":"welcome","queued":[...pending commands]}`, then it relays.
- `GET /ws/ctrl`: controllers WebSocket (16 at most per id). Header `Sec-WebSocket-Protocol: sonne.v2, sonne.id.<id>` (the browser sets it with `new WebSocket(url, ["sonne.v2", "sonne.id." + id])`). They send `cmd` messages, receive `state` messages and `{"type":"presence","online":bool,"lastSeen":ms}` on connection and on each change.
  The relay replies only to the controller that sent the command (never to the phone): `{"type":"relayed","nonce","delivered":bool,"queued"?:bool}`, or `{"type":"error","nonce","status","error"}` when refused (400 malformed command, 429 rate).
- `POST /api/cmd` with the header `X-Sonne-Id: <id>` and a `cmd` as body: for scripts and shortcuts. Replies `{"delivered":true}` if the phone is connected; otherwise `{"delivered":false,"queued":true}` for a `ring` or a `stop`, and `{"delivered":false,"queued":false}` for a `ping` (never queued).
- `GET /api/presence` with the header `X-Sonne-Id: <id>`: `{ "online": bool, "lastSeen": ms }`.
- **Ping by role**: controllers (browsers in the foreground) pinged every 25 s, disconnected after 60 s without pong or message; phone pinged every 4 min (`SONNE_PHONE_PING_S`, 240), disconnected after 10 min (`SONNE_PHONE_DEAD_S`, 600). Each ping wakes the phone's processor and radio: 25 s made it the app's top source of power consumption.
- **Application heartbeat**: the phone sends `{"type":"hb"}`, the relay replies `{"type":"hb"}` (neither relayed nor counted as an error). OkHttp does not expose pongs to the app: without a reply to the heartbeat within 20 s, the phone considers the connection dead (expired NAT, relay killed without FIN) and reconnects.
- **Queue**: if the phone is offline, the relay keeps the **last 5 `ring`** and the last 5 `stop` (no duplicate nonce) for 15 min at most, delivered on reconnection in order of receipt. Several per type: someone who knows the id can no longer replace the good `ring` with a badly signed `ring`; the phone sorts them out. v3 commands (types invisible): the last 10, all queued and tracked in flight, see "What changes for the relay".
- **In-flight commands**: a `ring` or a `stop` delivered to the phone stays recorded until the phone's next `state`. If the connection dies before that (or is replaced by a new connection from the same phone), it goes back into the queue and will be redelivered; the phone ignores a nonce it has already seen.
- **Limits**: 20 commands per minute per `id`; 60 requests per minute per IP (commands, WebSocket opens, `/api/presence`); 20 simultaneously open WebSocket connections per IP and 10,000 in total (429 or 503 beyond that); 4 KB per message; `id` = 32 hex required. Per-IP limits reduce an IPv6 address to its /64 prefix (a client has an entire /64) and an IPv4-mapped `::ffff:a.b.c.d` to the IPv4 address. Behind a trusted proxy (`SONNE_TRUST_PROXY=1`), the address is the last entry of `X-Forwarded-For`.
- Nothing is written to disk. Log: only counters and errors, never a full `id` (first 6 characters).
- `GET /health`: `{ ok, phones, ctrls, uptime, version }`.
- Variables: `PORT` (7700), `SONNE_QUEUE_MIN` (15, capped at 15: beyond that the phone would refuse the command, outside its freshness window), `SONNE_TRUST_PROXY` (0), `SONNE_WEB_DIR` (../web), `SONNE_PHONE_PING_S` (240), `SONNE_PHONE_DEAD_S` (600), `SONNE_CMD_V2` (1 in relay 1.6, 0 from the cut-off: see "Protocol v3").
- Proxy: the phone ping assumes that a WebSocket connection idle for 4 min is not cut along the way. Traefik v2 has no read timeout by default; Traefik v3 has `respondingTimeouts.readTimeout` at 60 s: check it on the entrypoint, and measure the actual NAT expiry (mobile carrier, home router) before lengthening. If needed, lower `SONNE_PHONE_PING_S`.

## Protocol v3: encrypted commands (app 1.6, relay 1.6)

### Why

In v2, a command is **signed but in plaintext**: the relay reads `cmd` (`ring`, `stop` or `ping`) and `ts` (the sender's clock), and behaves differently depending on the type (queue for `ring` and `stop` only, `queued:false` response for a `ping`). A Bluetooth observer in range reads the same thing in the GATT write. In v3, the relay and the observer only see a **fixed-size encrypted message** (172 bytes): neither the type (`ring`, `stop`, `ping`, the status check or "check"), nor the timestamp, nor the ringing parameters (duration, flashlight). Authentication (AEAD bound to the id), anti-replay (nonce, 15 min window, ordering of `ring` and `stop`) and acknowledgment (`ack` = nonce) are kept. The v2 HMAC signature no longer exists in v3: the GCM tag replaces it.

### Command key

```
K_cmd = HKDF-Expand(PRK = K_sig, info = "sonne-cmd-v3", L = 32)
      = HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01)
```

- Derived from `K_sig` and not from S: browsers and "My other phones" do not have S, only the derived keys. The page computes it with its non-extractable HMAC key (`crypto.subtle.sign("HMAC", cleSig, "sonne-cmd-v3" ‖ 0x01)`), immediately imports it as a **non-extractable** AES-GCM key (`["encrypt"]`), zeroes the bytes and stores the `CryptoKey` in IndexedDB with `cleSig` and `cleEnc` (or recomputes it on each load). The Android searcher computes it from `kSig`. No new pairing is needed.
- HKDF-Expand alone (RFC 5869, section 2.3), without Extract: `K_sig` is already a uniform 32-byte key (HKDF output), a case provided for by RFC 5869, section 3.3. `relay/crypto.js` (`hkdfExpand`) is checked against test case 1 of the RFC.
- Domain separation: `K_sig` only ever signs `cmd|ts|nonce` texts with `cmd` among `ring`, `stop`, `ping`, which cannot equal `"sonne-cmd-v3" ‖ 0x01`: no published v2 signature reveals `K_cmd`. `K_cmd` is distinct from `K_enc` (statuses) and from B (beacon).
- "Change key" changes S, and therefore `K_sig` and `K_cmd`.

### Plaintext: 64 bytes

| Bytes | Field | Value |
|---|---|---|
| 0 | version | `0x03` |
| 1 | command | `0x01` ring, `0x02` stop, `0x03` ping (status check) |
| 2 to 9 | ts | Unix milliseconds, 8-byte **unsigned** integer, big-endian |
| 10 and 11 | duration | seconds, big-endian. `0` = phone setting, otherwise 60 to 600 |
| 12 | flashlight | `0` = phone setting, `1` = on, `2` = off |
| 13 to 63 | reserved | zeros when sending, **ignored** on receipt (room for optional parameters that an older phone will ignore) |

- **Padding**: the plaintext is always 64 bytes, whatever the type and parameters. The message is therefore always 172 bytes.
- **Parameters**: only a `ring` carries any. The sender puts `0` and `0` as long as the user has not chosen anything (no interface in 1.6: always `0`). For `stop` and `ping`, the sender puts `0` and the phone ignores them.
- **Application by the phone** (`parametresSonnerie` in `relay/crypto.js`): a duration of 60 to 600 s replaces, for this ringing only, the configured automatic stop; any other value (0, or out of bounds) keeps the setting. Flashlight `1` turns on the torch for this ringing even if the setting is off, `2` leaves it off, any other value keeps the setting. An unexpected value never causes the command to be refused: a clumsy sender must not prevent ringing. A `ring` received during ringing restarts the automatic stop, as today, with its own duration (or the setting); it does not change the flashlight of the current ringing.

### Encryption

```
nonce = 12 random bytes (SecureRandom, crypto.getRandomValues, randomBytes)
aad   = UTF-8("sonne-cmd-v3|" + id)          45 ASCII bytes, id of the target phone
enc   = base64( AES-256-GCM(K_cmd, iv = nonce, aad, 64-byte plaintext) )   ciphertext ‖ 16-byte tag
```

- `enc` is in **standard base64** (alphabet `+` and `/`, `=` padding): 80 bytes, so **108 characters** including a single trailing `=`.
- The nonce serves at once as the GCM IV, the anti-replay identifier and the `ack` value (24 lowercase hex characters). It is authenticated (a changed nonce makes the tag fail). Many senders share `K_cmd`: with random 96-bit IVs, the probability of reusing one is about n²/2^97 for n commands (2^-49 for 16 million), far below the limit of 2^32 messages per key of NIST SP 800-38D.
- The AAD binds the command to the target phone: a command encrypted for another chain, or with `K_enc`, fails.

### Message

Always these **172 bytes**, written by hand, without spaces, in this order:

```json
{"type":"cmd","v":3,"nonce":"<24 lowercase hex>","enc":"<108 base64 characters>"}
```

No JSON serializer that escapes `/` as `\/` (as `JSONObject` does on Android): the size would vary with the content of the base64. The same message goes through the controllers WebSocket, `POST /api/cmd`, the relay's `welcome` queue and the GATT write. The relay copies it as is (clean copy `{type, v, nonce, enc}`, serialized without escaping by Node: always 172 bytes).

### Acceptance by the phone

In this order (reason in quotes, the one used by the cases in `docs/VECTEURS.json`); every refusal is silent, as in v2:

1. **shape**: JSON object, `type` = `"cmd"`, `v` = the number `3`, `nonce` = 24 **lowercase** hex, `enc` = standard base64 string. Otherwise "forme". A message without `v` is a v2 command (v2 rules, or "ancien-format" after the cut-off); any other value of `v` is "forme".
2. **size**: `enc` is 108 characters and decodes to exactly 80 bytes. Otherwise "taille". A correctly encrypted 63- or 65-byte plaintext is therefore refused.
3. **authentication**: AES-256-GCM with `K_cmd`, iv = nonce, AAD = `"sonne-cmd-v3|"` + the id **of the phone**. Failure: "authentification" (other chain, wrong key, `K_enc` instead of `K_cmd`, altered byte or nonce).
4. **version**: byte 0 = `0x03`, otherwise "version".
5. **command**: byte 1 among `0x01`, `0x02`, `0x03`, otherwise "commande".
6. **freshness**: ts read as an unsigned 64-bit integer. Above 2^53 - 1 (in particular with the most significant bit set, **negative in a Kotlin `Long`**: test this before any computation, `abs(maintenant - ts)` would overflow), "fraicheur". Then `|maintenant - ts| > 15 min`: "fraicheur".
7. **replay**: nonces whose ts is outside the window are forgotten, then an already seen nonce is "rejeu".
8. **capacity**: 2,000 valid nonces at once at most, otherwise "capacite".
9. The nonce is recorded (with the ts from the plaintext); a `ring` or a `stop` whose ts is smaller than that of the last accepted `ring` or `stop` is "ordre" (nonce kept).
10. Execution, then acknowledgment.

- **A single memory** of nonces for v2 and v3, for the relay and Bluetooth (v2 nonces are 32 hex, v3 ones 24: never a collision), and a single `dernierTsOrdre`.
- **GATT write**: steps 1 to 7 are done without modifying anything (`recevable` in `relay/crypto.js`), before the rate limit, as in v2: a message captured over the air and replayed counts as an invalid write.
- Executable reference: `creerFiltreCommandes` in `relay/crypto.js` (`accepter`, `recevable`). The 19 cases of the `commandes_v3.cas` block of `docs/VECTEURS.json` (accepted, old format before and after the cut-off, wrong id, wrong key, `K_enc`, altered byte, replay, too old, future, ordering, sizes, version, unknown command, ts with the most significant bit set, uppercase nonce) must give the same answer everywhere.

### Response: a single status per command

The acknowledgment remains the usual encrypted status, with `ack` = nonce of the command (24 hex in v3) and `ringing` already at the expected value. New in 1.6, for all commands (v2 and v3): **an accepted command produces exactly one status**, the acknowledgment. The ringing change that this command causes (start after a `ring`, stop after a `stop`) does not send a second status, neither to the relay nor as a GATT notification: the acknowledgment already carries the right value. Without this, the relay would count two statuses after a `ring` that makes the phone ring or a `stop` that stops it, and only one after a `ping`. Changes that do not come from a command (automatic stop at the end of the duration, the phone's Stop button, local test) still send their status.

### Capability announcement

The status plaintext gains the `"cmdv":3` field, after `model` and before `pad`: "this phone accepts v3 commands". Absent (app 1.5 and earlier): v2 only. The plaintext remains padded to 320 bytes (508-byte message). Vector: `commandes_v3.etat_v3`.

Senders (page, Android searcher) read `cmdv` in any **fresh and authenticated** status (via the relay or as a GATT notification) and remember, per phone, `cmdv ≥ 3` **forever** (the page's local storage, encrypted list of "My other phones"): they never go back to v2 for that phone, even if a later status lacks the field. The relay cannot replay an old status to force a downgrade (freshness rule), and the memory persists after a page reload.

### What the relay sees, before and after

| | v2 (app 1.5 and earlier) | v3 (app 1.6) |
|---|---|---|
| Command type | in plaintext (`cmd`) | no |
| Duration, flashlight | do not exist | encrypted |
| Sender's clock | in plaintext (`ts`) | encrypted |
| Signature | visible HMAC (worthless to it) | none, GCM tag inside `enc` |
| Nonce | 16 random bytes | 12 random bytes |
| Size | 165 bytes at most (does not depend on the type) | 172 bytes, always |
| Queuing, phone offline | `ring` and `stop` (5 per type), never `ping` | all, 10 at most |
| Response to the sender | `queued:false` for a `ping` | identical for all |
| In-flight commands | `ring` and `stop` | all |
| Statuses that follow | two after a `ring` that makes the phone ring or a `stop` that stops it, one otherwise | one, always |

Still visible: the id, the sizes, the times, the IP addresses, the phone's presence. **Still guessable from traffic**: a ringing that stops on its own sends a status at the end of its duration (3 min by default, or the duration requested by the command, which the relay can therefore measure), as when someone presses Stop on the phone: a relay that watches the timing can assume that a `ring` came before. The page's automatic check when the phone comes online, and the one the Nearby screen repeats every 8 min, can be recognized by their timing. Bluetooth search mode opens after any accepted command, whatever its type (unchanged).

**Active relay: ordering oracle.** A relay that holds back a command A and first delivers a more recent command B learns, depending on whether a status follows A or not, whether A and B were both `ring` or `stop` commands: the phone refuses a `ring` or a `stop` older than the last accepted one ("ordre"), never a `ping`. A is lost (no acknowledgment, the sender sees it). Accepted: also subjecting `ping` to ordering would cause a `ring` to be refused after a `ping` from a device whose clock runs fast.

### What changes for the relay

Today (v2), the relay reads `cmd` for: shape validation (`COMMANDES` list), the queue (`ring` and `stop` only, 5 per type), in-flight commands (`ring` and `stop` only) and the response (`queued:false` for a `ping`). It routes nothing by type; its rate limits (per id, per IP, connections) do not depend on the type; its log has only counters, never the type or the command.

In v3 (relay 1.6):

- **Shape**: a v3 command is validated by `formeCommandeV3` in `relay/crypto.js` (fields, nonce of 24 lowercase hex, `enc` of 108 characters that decodes to 80 bytes) and copied clean as `{type, v, nonce, enc}`. A v2 command is only accepted if `SONNE_CMD_V2` is `1` (see Transition); otherwise 400 `{"error":"old command format: reload the page or update the app"}`.
- **Queue**: a separate list for v3, the **last 10** (`FILE_V3`), no duplicate nonce, 15 min at most, delivered along with the v2 ones in order of receipt. As in v2, someone who knows the id can push 10 fake messages and push a good `ring` out of the queue; the relay cannot do better without `K_cmd`, the phone sorts them out.
- **In flight**: all delivered v3 commands stay recorded until the phone's next status, and go back into the queue if the connection dies before that.
- **Response**: `{"delivered":true}`, or `{"delivered":false,"queued":true}` for **any** v3 command when the phone is offline. The sender knows what it sent. Senders no longer send an automatic check when presence says the phone is offline (it would be queued).
- **Feedback to the controller**: `relayed` and `error` echo the command's `nonce`, 24 or 32 hex.
- **Limits** and maximum size (4 KB): unchanged.
- **Log**: two more counters, `cmdV2` and `cmdV3` (the format, visible anyway, never the type): this is what tells when the old format can be cut off.
- **Variable** `SONNE_CMD_V2`: `1` by default during the transition (relay 1.6), `0` by default after the cut-off (relay 1.7).

### Bluetooth (GATT)

- The Command characteristic receives the same 172-byte message. It fits in one attribute (512 bytes); the MTU negotiation (517 requested, 515 at least) does not change.
- Transition: writes of up to 512 bytes, as today (v2: 165 at most, v3: 172). After the cut-off: **exactly 172 bytes**, otherwise `GATT_INVALID_ATTRIBUTE_LENGTH` (`0x0d`).
- The notified status remains 508 bytes, a single one per command. An observer in range sees a 172-byte write then a 508-byte notification, whatever the command.

### Transition, then cut-off of the old format

The numbers are those of Sonne versions (`CHANGELOG.md`): "relay and page 1.6" means the relay and page of the repository published with Sonne 1.6.0. The relay's own version number (`relay/package.json`, returned by `/health`, 1.1.0 before 1.6) also goes up: 1.2.0 for step 1, 1.3.0 for step 3.

The page deployed on sonne.sidali.dev and the installed app are not updated at the same time. Ringing must never break: no sender sends v3 to a phone that has not announced it, and no phone refuses v2 as long as a sender may still send it.

**Step 1: relay and page 1.6** (a single deployment, the relay serves the page). The relay accepts v2 and v3 (`SONNE_CMD_V2=1`). The page sends v3 to a phone for which it has seen `cmdv ≥ 3`, v2 otherwise. If the relay answers 400 to a v2 command (relay already cut off), it resends the same intent in v3, with a new nonce (version upgrade, never the reverse; a v2 command refused by the relay was not delivered).

**Step 2: app 1.6.0**, published only after step 1 (its searcher would send v3 to a relay that refuses it; a self-hosted relay is updated before its phones). The phone accepts v2 and v3, announces `cmdv:3`, sends a single status per command. The searcher sends v3 to chains that have announced `cmdv ≥ 3` (seen in a status received over GATT, or via the relay if it listens to it), v2 otherwise, with the same 400 rule.

During the transition: a phone on 1.5 keeps receiving v2 and rings as before; a phone on 1.6 receives v3 as soon as the sender has seen one of its statuses (the very first command from a new browser may still go out as v2). A malicious relay that hides a phone's statuses keeps a new browser on v2, that is, in today's situation; once the capability has been seen, there is no going back. The Android searcher does not listen to statuses from the relay (it sends via `POST /api/cmd`): to a phone it has never reached over Bluetooth, all its commands go out as v2 until the cut-off, automatic wake-up included.

**Cut-off**, when these three conditions are met:

- all the phones you want to be able to ring are on 1.6.0 or later (the page shows the version under each phone; the searcher sees it after a Bluetooth exchange);
- the `cmdV2` counter in the relay log has stayed at 0 for 14 days;
- at least 30 days after the release of app 1.6.0 (built-in update, checked every day).

Once the cut-off is done, a phone left on 1.5 can no longer ring from the page: that is the price of the cut-off, hence the first condition.

**Step 3: relay and page 1.7.** The page sends v3 to all phones, without looking at `cmdv`. The relay refuses v2 (`SONNE_CMD_V2=0` by default). If something was overlooked, setting it back to `1` lets v2 through again from searchers still on 1.6, for example to a phone left on 1.5; page 1.7, for its part, only sends v3.

**Step 4: app 1.8.0** (1.7.0 only changed the appearance), never before step 3 (a page still in transition could send v2 to a phone for which it has not seen a status). The phone silently refuses v2 via the relay and as an invalid write over GATT, only accepts 172-byte writes over GATT; the searcher only sends v3; the v2 code (command signing, `sig`, `verifyCmd`) is removed from all three implementations. `K_sig` then only serves to derive `K_cmd`.

### Constants

| Name | Value |
|---|---|
| HKDF label of `K_cmd` | `sonne-cmd-v3` |
| AAD prefix | `sonne-cmd-v3\|` |
| message `v`, plaintext byte 0 | 3 |
| command codes | 1 ring, 2 stop, 3 ping |
| plaintext | 64 bytes |
| decoded `enc` | 80 bytes (108 base64 characters) |
| nonce | 12 bytes (24 lowercase hex) |
| message | 172 bytes |
| window | 15 min (900,000 ms), bounds included |
| nonce memory | 2,000 |
| duration | 0 or 60 to 600 s |
| flashlight | 0 setting, 1 on, 2 off |
| status capability field | `"cmdv":3` |
| relay v3 queue | 10 commands, 15 min |

Vectors: `commandes_v3` block of `docs/VECTEURS.json` (`K_cmd`, `aad_v3`, `v3_ring`, `v3_stop`, `v3_ping`, `v3_ring_parametres` with their plaintext, message and exact text, `etat_v3`, and the cases), produced by `relay/scripts/genere-vecteurs.js` from `relay/crypto.js`. The block comes last and its key names are clean: the JVM test reads it with `champ(nom, apres = "<case name>")`.

## Ringing: "even on silent"

1. **Alarm channel**: `MediaPlayer` with `AudioAttributes` `USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION`, looping, on the default alarm sound (`RingtoneManager.TYPE_ALARM`), falling back to a bundled sound (generated, no file under a dubious license).
2. **Volume**: remember the `STREAM_ALARM` volume, set it to maximum, restore it on stop.
3. **Do Not Disturb**: request `ACCESS_NOTIFICATION_POLICY` access at setup (permissions screen). If DND is active when ringing starts, switch to `INTERRUPTION_FILTER_ALL` and restore afterwards (only if the current filter is still the one Sonne set).
   Since Android 15, an app that targets `targetSdk 35` or higher **can no longer change the global mode** (compat change `MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES`): the call only drives a rule owned by the app, the ringing would stay silent in "No interruptions", and the restore would turn on a mode unknown to the user. Hence `targetSdk 34` (see Android app). If this choice changes, the code no longer calls `setInterruptionFilter`, hides the Do Not Disturb line in the permissions, and warns on the home screen when the active mode silences alarms.
4. Continuous **vibration** (`VibratorManager`, alarm usage) and blinking **flashlight** (`CameraManager.setTorchMode`), until stopped.
5. **Screen**: notification of category `CATEGORY_ALARM` with `fullScreenIntent` (`USE_FULL_SCREEN_INTENT`), activity over the lock screen (`setShowWhenLocked`, `setTurnScreenOn`) with a single big **Stop** button. `WakeLock` while ringing.
6. **Automatic stop** after 3 min (adjustable from 1 to 10). The Stop button and the `stop` command stop everything and restore volume and DND.
7. **Local test**: a "Test the ringer" button in the app, which goes through exactly the same path.

## Standby: staying reachable without Google

- Foreground service (`foregroundServiceType="specialUse"`, permission `FOREGROUND_SERVICE_SPECIAL_USE`, subtype described by `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, discreet "Sonne standby" notification) that keeps an OkHttp WebSocket open to `wss://sonne.sidali.dev/ws/phone` (proof P in `Sec-WebSocket-Protocol`).
  Not `dataSync` (v1 of the protocol): on Android 15 and later, a `dataSync` service is limited to 6 h per 24 h (then `onTimeout`, and a crash if it is not stopped) and cannot be started from `BOOT_COMPLETED`. `specialUse` has neither restriction and requires no review outside Play. `connectedDevice` would not fit (it requires a Bluetooth, NFC, USB or connected-device network permission).
- Reconnection with increasing delay (1 s → 60 s max, plus some jitter), immediate retry on network change and when the network comes back after a total loss (`ConnectivityManager.NetworkCallback`), at boot (`LOCKED_BOOT_COMPLETED` then `BOOT_COMPLETED`) and after an app update.
- **Direct Boot**: the service, the receivers and the ringing screen are `directBootAware`, preferences and the secret live in device-encrypted storage (one-time migration from the old storage at the first unlocked launch), the Keystore key does not have `setUnlockedDeviceRequired`. After an automatic reboot (GrapheneOS after 18 h locked, Android after 72 h), Sonne is reachable before the user has entered their PIN.
- **Timers**: a `Handler` stops when the CPU sleeps (a foreground service does not prevent this). An `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` watchdog (no permission needed) runs about every 5 min under a WakeLock of 30 s at most, released as soon as the expected response arrives (heartbeat reply, relay welcome, connection failure) or immediately if there is nothing to wait for: application heartbeat, reconnection if needed, periodic 15 min status, end of search mode. Reconnections longer than 5 s also go through this alarm; shorter ones through a `Handler` under a WakeLock. No OkHttp `pingInterval`.
- **Secret**: an S that is present but unreadable is never silently replaced (that would revoke every browser without warning). Transient error: retry. Repeated permanent failures (GCM tag rejected, Keystore key invalidated or missing): the old box is kept, the home screen warns, and a new key is only created with the user's consent.
- Request exemption from battery optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) with an explanation; check and remind on the status screen if refused.
- Partial `WakeLock` only while a command is being processed.
- No UnifiedPush in v1 (noted as a future evolution).

## Bluetooth proximity (v1.1)

Scenario: "it's not far, I get closer, and I make it ring". The protected phone emits a Bluetooth Low Energy (BLE) beacon that only devices that know S recognize, and accepts signed commands directly over Bluetooth, without Internet. The searcher is the Sonne app installed on another phone. Nothing changes for the relay or the web page: it is a second path for the same messages.

### Search mode

The beacon and the GATT server run **only in search mode**. Outside this mode, the phone emits nothing and nobody can connect to it (a permanently connectable beacon would let anyone in range read its Bluetooth name, see Accepted limits).

- **Opening**: for 10 min after each accepted command (`ring`, `stop` or `ping`, through the relay or over Bluetooth; each command extends the mode), while the phone is ringing, and when the relay has been unreachable for **one hour** (phone offline, possibly lost out of any coverage). Once the relay is reached, this last case closes.
- **Request by the searcher**: the Nearby screen sends a `ping` through the relay (silent; encrypted in v3 since app 1.6 for a phone that announced it) when it opens, then every 8 min while it stays open, only if `GET /api/presence` reports the phone online (since 1.6: a v3 would otherwise be queued). The web page already sends a silent `ping` when the phone comes online: opening it also opens search mode.
- **Closing**: at expiry, by a `Handler` (CPU awake) and by the watchdog (CPU asleep), whichever comes first.
- The standby notification shows "Bluetooth search on" during the mode: the owner sees when the beacon is open. The "Bluetooth beacon" setting turns everything off.

### Rotating identifier

- **Beacon key** `B = HKDF-SHA256(S, salt = empty, info = "sonne-ble", 32 bytes)`.
- **Window** `w = floor(now_ms / 900000)` (15 min, Unix wall clock in milliseconds).
- **Identifier** `id_ble = HMAC-SHA256(B, w as 8 bytes big-endian)[0:8]`.
- It changes every 15 min, at window boundaries (on the hour, and at 15, 30, 45). Without S, one can neither predict it nor link two windows together **through id_ble**. Other signals can do so during search mode (Bluetooth name readable after connecting, address resolvable by paired devices): see Accepted limits. "Change key" changes B: old searchers no longer recognize the beacon.
- Vectors: `ble` block of `docs/VECTEURS.json` (S = `000102…0f`, w = 1991444, with the neighboring windows), produced by `relay/crypto.js` (`deriveCleBle`, `deriveBleId`, `fenetreBle`).

### Beacon (protected phone)

- **Sonne service UUID** (128 bits, fixed): `2af8c8b3-3a13-489b-a3a3-ac220005799b`.
- **Service data**: 9 bytes, `id_ble` (8 bytes) then the **version** byte `0x01`. An unknown version is ignored by the searcher.
- **Packet**: classic advertising (legacy, `ADV_IND`, connectable, 31 bytes at most), a single "Service Data, 128-bit UUID" AD structure (type `0x21`): 1 byte of length, 1 of type, 16 of UUID (little-endian on air, as always in BLE), 9 of data = 27 bytes, plus the 3 flag bytes added by Android for a connectable advertisement = 30 bytes. So **no device name, no TX power, no service UUID list** (the packet would exceed 31 bytes). Empty scan response.
- **Emission**: `BluetoothLeAdvertiser.startAdvertisingSet`, `setLegacyMode(true)`, `setConnectable(true)`, `setScannable(true)` (mandatory for a connectable legacy advertisement), interval `INTERVAL_MEDIUM` (400 × 0.625 ms = **250 ms**, the equivalent of `ADVERTISE_MODE_BALANCED`), power `TX_POWER_LOW` (about **-15 dBm**). Address: the one the stack picks, a **resolvable private address** (RPA, derived from the phone's IRK). A non-resolvable address would require `AdvertisingSetParameters.Builder.setOwnAddressType`, reserved for system apps (`BLUETOOTH_PRIVILEGED`, absent from the public SDK).
- **Rotation**: at each change of w, the phone **stops the set and starts a new one** (not a mere `setAdvertisingData`): the stack then draws a new address, which therefore changes together with `id_ble` at the boundary. The address can **also** change on its own mid-window (RPA timer, 7 to 15 min depending on the stack; on a controller without extended advertising, a legacy advertisement takes the host's common address, which rotates on its own timer): this links nothing, since `id_ble` does not change at the same time. The change is scheduled by `AlarmManager.setAndAllowWhileIdle` at the boundary (lock released as soon as the rotation is done), and rechecked at each watchdog pass (about 5 min). A delay of a few minutes in deep sleep is harmless: the searcher accepts the previous window.
- **When**: from the standby service, **in search mode**, as long as the "Bluetooth beacon" setting is on (the default), Bluetooth is on, `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` are granted, S is readable, and **once the GATT service is published** (otherwise a searcher would see the beacon without finding the service). Resumes on `BluetoothAdapter.ACTION_STATE_CHANGED` (transition to `STATE_ON`). Works in Direct Boot (S is in device-encrypted storage). The displayed state ("emitting") follows the stack callbacks: start succeeded or refused, stop, disable. Failure (`ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`, internal error): retry at 1, 2, 5, 10 then 30 s.
- **After a connection**: a connectable legacy advertisement is stopped by the controller at the first connection, and not every stack restarts it. 500 ms after an unpaired GATT client disconnects, the phone re-enables the existing set (`enableAdvertising(true)`, same address, no effect if it is still emitting); if the stack reports the set disabled or refuses to re-enable it (`onAdvertisingEnabled`), it starts a new one.

### GATT server (protected phone)

Opened by the standby service in search mode, before the beacon (`BluetoothManager.openGattServer`), with a **primary service** of the same UUID `2af8c8b3-3a13-489b-a3a3-ac220005799b` and two characteristics:

| Characteristic | UUID | Properties | Content |
|---|---|---|---|
| Command | `2af8c8b4-3a13-489b-a3a3-ac220005799b` | `WRITE` (write **with response**), permission `WRITE` | a command, UTF-8 JSON, exactly the relay message: `{"type":"cmd","cmd","ts","nonce","sig"}` in v2, `{"type":"cmd","v":3,"nonce","enc"}` (172 bytes) in v3 |
| Status | `2af8c8b5-3a13-489b-a3a3-ac220005799b` | `NOTIFY`, CCCD descriptor `0x2902` (read and write) | the usual encrypted status: `{"type":"state","nonce","enc"}` |

- **Publication**: `addService` is asynchronous. The server is only ready (and the beacon only starts) on the `onServiceAdded` callback with `GATT_SUCCESS`. On failure, or if `openGattServer` returns `null` right after Bluetooth comes back: close, then retry at 1, 2, 5, 10 then 30 s, under a short WakeLock.
- **No Bluetooth pairing** (neither bonding nor link encryption): security comes from the messages, as on the relay. No system dialog opens.
- **Size and MTU**: a message is **512 bytes at most** (maximum attribute size). A command is at most 165 bytes in v2, always 172 in v3; a status is **always 508** (plaintext padded to 320 bytes, message `{"type":"state","nonce","enc"}` written without escaping: a JSON serializer that escapes "/" as "\/" in the base64 would push it past 512). No application-level fragmentation: the searcher **must negotiate an MTU** (`requestMtu(517)`) before any exchange, and abandons the Bluetooth path if the obtained MTU is below 515 (512 bytes + 3 of ATT header). The phone only accepts a simple write at offset 0: a prepared write ("long write") is refused with `GATT_REQUEST_NOT_SUPPORTED` (`0x06`), an empty value or one over 512 bytes with `GATT_INVALID_ATTRIBUTE_LENGTH` (`0x0d`). It only sends a notification if it fits in that connection's MTU (MTU - 3 bytes).
- **Processing**: the phone replies `GATT_SUCCESS` to any well-formed write **before** processing it (the reply says nothing about validity), then, under a short WakeLock taken on reception, checks, without modifying anything, the JSON form, the HMAC signature (v2) or the GCM tag (v3), freshness (15 min) and that the nonce has never been seen, and only then the rate limit (a write that fails counts as invalid: neither random bytes nor a captured and replayed command can exhaust the limit). The message then goes through **exactly the same path** as a command received from the relay: `verifyCmd` (v2) or decryption (v3), `|now - ts| ≤ 15 min`, ordering of ring/stop, and **the same nonce memory** (a command received over Bluetooth then through the relay, or the reverse, is only executed once; a write captured over the air cannot be replayed through either path). The nonce memory is written to disk synchronously **before** executing the command, and only if it changed. Refused command: silence, as on the relay.
- **Response**: after an accepted command, notification of the status (`ack` = the command's nonce) to the client that sent it, if subscribed, **before** the heavy ringing work. Subscribed clients **that wrote a valid command during the current connection** also receive the status when ringing starts or stops (since 1.6, not for the change caused by the acknowledged command itself); a subscribed stranger receives nothing. Notifications go out one at a time (the next one on `onNotificationSent`), return codes logged. The status is also sent to the relay as usual (browsers see it). Same freshness rules on the searcher side as the web page (increasing `ts`, 20 min at most).
- **Connection counting**: a `BluetoothGattServer` sees all of the phone's LE links (watch, earbuds, other apps' links). A connection is only counted on its **first access to the Sonne service** (subscription to Status or write of a Command). When Sonne drops a connection (`cancelConnection`), its slot is freed immediately, without waiting for a `STATE_DISCONNECTED` that may never come (link held by another app), and the address is no longer admitted until it disconnects.
- **Limits**: 4 Sonne connections at most; when full, the **oldest one without a valid command is evicted** (if all have sent one, the newcomer is refused); a connection lives **5 s without a valid command**, 10 s after one; **10 ATT requests** addressed to Sonne per connection, **2 invalid writes** (malformed or badly signed), then cut-off; 20 **valid** commands per minute in total on the Bluetooth path (the following ones are ignored).

### Searcher (another phone with the Sonne app)

- **My other phones**: list of known chains (local name, relay, and only the derived keys id, `K_sig`, `K_enc` and B: not S, so neither P nor a pairing link can be reconstructed), encrypted with a Keystore key with `setUnlockedDeviceRequired` (unusable while the phone is locked) and kept in **credential-encrypted storage** (unreadable before the first unlock): the searcher only runs with the screen unlocked. The list is dropped from memory when leaving the screen. Added by QR (camera), pasted link or 12 words.
- **Wake-up**: when the Nearby screen opens, then every 8 min, a `ping` through the relay to open the search mode of the sought phone (see Search mode), preceded since 1.6 by `GET /api/presence`: nothing is sent if the phone is offline. The screen says whether the wake-up was delivered, whether the phone is offline (its beacon will open on its own after one hour without relay) or whether the relay is unreachable.
- **Scan** only while the "Nearby" screen is in the foreground: `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, report delay 0. Hardware filter `ScanFilter.setServiceData(Sonne UUID, donnees, masque)` with `donnees` = 8 bytes of `0x00` then `0x01` and `masque` = 8 bytes of `0x00` then `0xFF`: only version 1 Sonne beacons come through (the service data filter is required, since the beacon does not publish a UUID list). Android switches a scan lasting more than 30 min to opportunistic scanning and silently refuses more than 5 starts in 30 s: restart the scan every 25 min, **never more than 4 starts over a sliding 30 s** (history shared across the process, no fixed spacing). The scan follows the Bluetooth state live (`ACTION_STATE_CHANGED`, restart when it comes back), is restarted if nothing has come through for 60 s while a beacon was seen, and is paused during a GATT connection (100 % scanning and connection setup share the radio).
- **Recognition**: for each known chain, the searcher computes `id_ble` for **w - 1, w and w + 1** (w computed with its own clock) and recomputes them at each change of w. A beacon is recognized if the first 8 bytes of its service data equal one of these values (clocks off by up to 15 min, late rotation). An unknown beacon is ignored without a trace. `id_ble` is public and can be copied: the searcher tracks the signal **address by address** (measurements are never merged), keeps the `BluetoothDevice` from the `ScanResult` of each address (it carries the random address type; `getRemoteDevice(String)` would create a public address since Android 13), and reports when two addresses emit the same `id_ble` at the same time (seen within 2 s) for more than 3 s. A rotation triggers nothing: the old address goes quiet.
- **Signal smoothing** (per address; the gauge follows the address with the strongest smoothed signal): time-weighted exponential moving average, `r = r + α (mesure - r)` with `α = 1 - exp(-Δt / τ)`, `τ = 2 s`, Δt since the previous measurement. The first measurement initializes r; r is reset if the beacon has not been seen for 10 s. A measurement of 127 (RSSI unavailable) is ignored.
- **Levels** (r in dBm, for a beacon at -15 dBm; thresholds to be recalibrated in the field, these are the app's constants):

| Level | Label (en / fr) | Condition | Beep every |
|---|---|---|---|
| 0 | Lost / Perdu | never seen, or seen more than 10 s ago | silence |
| 1 | Cold / Froid | r < -88 | 2,000 ms |
| 2 | Cool / Frais | -88 ≤ r < -80 | 1,300 ms |
| 3 | Warm / Tiède | -80 ≤ r < -72 | 800 ms |
| 4 | Hot / Chaud | -72 ≤ r < -64 | 450 ms |
| 5 | Very hot / Très chaud | r ≥ -64 | 200 ms |

  **Hysteresis**: the level goes up as soon as r reaches the threshold, and only goes down when r falls 3 dB below the current level's threshold (no flickering at the boundary). The screen also shows "seen x s ago" and the last raw measurement.
- **Ring**: if the beacon was seen less than 10 s ago, Bluetooth path, on the beacon's recent addresses (3 at most, strongest first), scan paused: GATT connection (`autoConnect = false`, 5 s at most; a status 133, 62 or 8 received before 3 s triggers a second attempt after 400 ms, within that budget), `requestMtu(517)`, service discovery, subscription to Status (CCCD `0x0001`), write of the command, wait for a fresh status notification whose `ack` = nonce, **5 s** at most. Failure at any step: next address, then **the same message** (same nonce, v3 or v2 depending on the known capability) through the relay: the phone will never execute it twice. Since app 1.6, any fresh status received over GATT that announces `cmdv ≥ 3` is recorded for the chain (encrypted list); a 400 from the relay to a v2 makes the same intent be resent in v3 with a new nonce. If a write was accepted over Bluetooth but no acknowledgement came back and the relay fails, the displayed result is "written over Bluetooth, no acknowledgement" (the command may have been executed), not a failure. The searcher disconnects as soon as the response is received. `stop` and `ping` follow the same path.

### Permissions (Android 12 and later)

- Protected phone: `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` (GATT server).
- Searcher: `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` (no location) and `BLUETOOTH_CONNECT`; `CAMERA` only to scan a QR, requested at scan time.
- The status screen reminds the user when Bluetooth is off while the beacon is enabled.
- **GrapheneOS**: automatic Bluetooth turn-off ("Turn off Bluetooth automatically", `Settings.Global` setting `bluetooth_off_timeout`, 0 = disabled) turns Bluetooth off when no device is connected; neither the beacon nor the scan counts as a connection. When the setting is readable and active, the home screen, the Permissions screen and Nearby flag it with a link to the Bluetooth settings. Android 12 and later can deny an app the reading of a hidden setting: without a signal, the limit remains (see Accepted limits).

### Accepted limits

The first six only apply **during search mode**: outside this mode, the phone emits nothing.

- **Bluetooth name**: the beacon is connectable. Anyone in range can connect to it without pairing and read Android's standard GAP service: the phone's Bluetooth name (often "Firstname's Pixel") and the fingerprint of its GATT database (Database Hash), values that do not change with `id_ble` and link windows together. Sonne cannot hide them: Settings recommend a neutral Bluetooth name.
- **Paired devices**: the beacon's address is a resolvable private address. Any device already paired with this phone (car, rental car that keeps the keys, watch, computer) can recognize the phone behind each advertisement, whatever the `id_ble`.
- **Holders of S**: B is derived from S. Anyone who has the 12 words, the QR or the link (former partner, synced browser) can recognize the beacon with a simple scanner, and can **open** search mode by sending a command (a `ping` is silent). The Pairing screen says so; the standby notification shows when the mode is open. "Change key" removes this power.
- The Sonne UUID is visible: an observer sees that **a** Sonne phone is in range, not which one. As long as users are few in a given place, this is almost an identity clue. Within the same 15 min window, `id_ble` is fixed: tracking is possible for 15 min at most through it alone.
- **Copying `id_ble`**: `id_ble` is public. Anyone can copy it and re-emit it louder. The searcher tracks each address separately, tries several addresses and reports the copy, but a clone receives the command written to it (encrypted since 1.6: it cannot read it) and can keep it (replayable for 15 min, a single execution in total thanks to the nonces). Without Internet, the command may never reach the real phone.
- **Local denial of service**: a stranger in range can occupy the GATT server slots. They must cycle faster than the legitimate exchange (5 s of life without a valid command, eviction of the oldest connection without a command), but they can disrupt the Bluetooth path; the relay remains the fallback path.
- **Offline search mode**: if the protected phone has no Internet, the searcher cannot open its search mode; it opens on its own after one hour without relay.
- **GrapheneOS**: automatic Bluetooth turn-off shuts down the beacon; disable it on the protected phone (and on the searcher, during the search).
- Signal strength depends on the body, walls and phone models: the gauge guides the approach, it does not measure a distance.

## Android app

- Kotlin, Jetpack Compose + Material 3, `minSdk 31`, `targetSdk 34`, `compileSdk 36`. `targetSdk 34` is intentional (distribution outside Play, via Obtainium): it is the condition for being able to lift Do Not Disturb on Android 15 and later (see Ringing, item 3). Gradle 8.14.3, AGP 8.13.0, Kotlin 2.x, JDK 21 (`/opt/homebrew/opt/openjdk@21`).
- Dependencies: OkHttp (WebSocket, the searcher's `POST /api/cmd`), ZXing core (QR: drawing and reading, the camera goes through Camera2 with no other dependency), AndroidX Compose/Material3/Activity, no Google Play Services, no Firebase, no analytics library.
- Screens: **Permissions** at first launch (one sentence on what Sonne is, then the settings), which leads directly to **Pairing**; **Home** (status: connected / last connection, battery, "Test the ringer" button, reminder of missing permissions, "Next step: link a browser" as long as pairing has not been opened, alert if the key is lost or if Do Not Disturb silences alarms), **Pairing** (QR, link to copy, 12 words presented as the way to ring from any browser, link host shown in clear and a warning if the relay is not the default one, start of the id, "Change key" with confirmation, `FLAG_SECURE`: no screenshot or thumbnail), **Settings** (relay address, `https://` only in release, automatic stop duration, flashlight on/off, Bluetooth beacon on/off with the advice of a neutral Bluetooth name, proximity beeps on/off), **Ringing** (full screen, Stop), **Nearby** (from home, "Find a phone nearby": gauge, beeps, Ring) and **My other phones** (known chains, added by QR, link or 12 words). Home shows the beacon state (standby, search mode, emitting) and reminds the user when Bluetooth is off or turns itself off (GrapheneOS); the Permissions screen has a "Nearby devices" line.
- English only (since 1.4.0). No em dash or en dash in the texts.
- `applicationId = dev.sidali.sonne`, version `1.6.0` (code 9). Release signing: keystore `~/.sonne/release.jks` + `~/.sonne/keystore.properties` (created by `keytool` if they do not exist; never in the repository).
- Unit tests: vectors from `docs/VECTEURS.json` (P, id, K_sig, K_enc, signature of a command, decryption of a status, B and `id_ble` of the Bluetooth beacon, and since 1.6 `K_cmd`, v3 encrypted commands, status with `cmdv` and acceptance or rejection cases) shared with `relay/crypto.js` and the web page.

## Update (app 1.2)

Built-in update, without a store, like the non-Play versions of Signal or Telegram. Since the GitHub repository is private, the relay serves the files (Obtainium remains possible once the repository is public).

- **Relay**: `GET /update/android.json` returns the announcement, a JSON object published as is from the `SONNE_RELEASES_DIR` folder (mounted read-only on `/releases`, host folder `SONNE_RELEASES_HOST_DIR` in `deploy/docker-compose.yml`), with `Cache-Control: public, max-age=300`; `GET /download/<nom>.apk` serves an APK from this folder. The name is strictly validated (`^[A-Za-z0-9][A-Za-z0-9_-]*(\.[A-Za-z0-9_-]+)*\.apk$`, 100 characters at most, no `/`, no `..`, no encoding); everything else returns 404. Without the folder, both routes return 404.
- **Announcement**:

  ```json
  { "version": "1.2.0", "versionCode": 4, "url": "https://sonne.sidali.dev/download/sonne-1.2.0.apk",
    "sha256": "<SHA-256 of the APK, hex>", "certSha256": "<SHA-256 of the signing certificate (DER), hex>", "notes": "…" }
  ```

  Written by `tools/release-android.sh` (checksums computed by `shasum` and `apksigner verify --print-certs`). The APK is copied before the announcement, which is put in place by a rename: it never points to a missing file. A published APK never changes under the same name.
- **Check**: `GET <configured relay>/update/android.json` once a day, from the standby watchdog (no new service; retry one hour after a failure), and when the app opens (at most once per minute). "Check for updates" setting, on by default. Nothing else is sent. The `versionCode` is authoritative: if it is greater than the installed one, the app shows a banner on the home screen and an "Update available" notification (dedicated channel, normal importance, only one per version). `url` must be `https://` (`http://` accepted in debug only).
- **Download and checks** (on tap): OkHttp into the app cache, 150 MB at most. Then, in this order, otherwise a clear refusal and deletion of the file: SHA-256 equal to `sha256`; package `dev.sidali.sonne` and `versionCode` equal to the announced one (`getPackageArchiveInfo`); signing certificates (`GET_SIGNING_CERTIFICATES`, `apkContentsSigners`) **identical to those of the installed app** and containing `certSha256`. A compromised announcement (hacked relay) is therefore not enough: the signing key would also be needed, and it is kept outside the repository and the server.
- **Installation**: `PackageInstaller` session (`MODE_FULL_INSTALL`, `setRequireUserAction(USER_ACTION_REQUIRED)`), permission `REQUEST_INSTALL_PACKAGES`. The system confirmation screen is always shown; the activity launches it as soon as it is in the foreground (otherwise a notification invites the user to finish). If the "Install unknown apps" permission is missing, the banner explains it and opens the system setting (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`). Cancellation: back to the banner. After installation, `MY_PACKAGE_REPLACED` restarts standby; key and settings are kept.
- **Limits**: a self-hosted relay without `SONNE_RELEASES_DIR` offers no update (404 treated as "up to date"). On an Android with Google services, Play Protect may additionally show its own scan screen (seen on the emulator with Play Store). The relay sees the IP address that queries the announcement, as for the WebSocket.

## Web page

- Static page without a framework (`index.html`, `app.js`, `crypto.js`, `style.css`, `bip39-english.js`, plus `manifest.webmanifest` and `sw.js` to install it as a PWA), no inline script in order to respect the CSP (`script-src 'self'`). WebCrypto (HKDF, HMAC, AES-GCM). Mobile first. English only.
- Big **Ring** button, **Stop** button, status badge (online / seen … ago), battery, log of acknowledgements, management of several phones (local name, added by link or by 12 words, removal).
- The `#s=…` fragment is read and then cleared from the address bar (`history.replaceState`). **Limit**: the original visit, with the fragment, stays in the browser history and leaves with its sync (Chrome Sync, Firefox Sync). On a synced browser, it is better to paste the link or type the 12 words into the page's form (nothing in the history). The app and the README say so.
- A phone added through a directly opened link asks for confirmation, with the start of the id (also shown in the app): anyone can craft such a link. Its automatic name is prefixed with "New: ".
- The browser requests persistent storage (`navigator.storage.persist()`). Safari may erase a site's data after 7 days without a visit: the page advises adding it to the home screen or keeping the 12 words.
- Commands (page 1.6): encrypted v3 with `cleCmd`, a non-exportable AES-GCM key (`["encrypt"]`) derived from `cleSig` and stored in IndexedDB with `cleSig` and `cleEnc`; an entry stored before 1.6 is completed when the phone is opened (`completerCles`), without new pairing. The capability flag is kept per phone in `localStorage` (`sonne.cmdv3`, a key separate from the phone list, erased when the phone is removed); without it, the command goes out in v2. A 400 from the relay to a v2 makes the same intent go out again in v3 (new nonce), along with all following commands from the view. The `COUPURE_V2` constant in `web/app.js` switches to `true` at step 3. No automatic check (ping) when presence reports the phone offline.
- The page that handles S is served by the relay: a malicious relay could serve a script that reads the fragment. Only use a trusted relay; the app shows the host of the pairing link and warns when the relay is not the default one.

## Repository

- GitHub `sidalidev/sonne` (private first, public later), Apache-2.0 license, `README.md` (pitch, installation via Obtainium, first steps, self-hosting the relay with Docker, limits), `CHANGELOG.md`.
- `relay/Dockerfile` + `deploy/docker-compose.yml` (`name: sonne`, Traefik labels for `sonne.sidali.dev`, restart unless-stopped), non-root user, read-only file system, healthcheck.
- GitHub Actions CI: relay tests, debug APK build.
