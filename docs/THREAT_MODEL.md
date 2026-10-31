# Threat model

**What is protected.** The key (128 bits, created on the phone) and everything derived from it: who can ring or stop the phone, and the content of its status (battery, ringing, model, app version).

**Against whom.**
- The relay operator, or anyone who takes over the relay: cannot ring the phone or read a command (since 1.6.0, commands are encrypted and authenticated with AES-256-GCM, bound to the chain id; before, they were signed with HMAC-SHA256), cannot read a status (AES-256-GCM, end to end), cannot replay an old command (nonce, 15 minute window, order of ring and stop). Since 1.6.0 it cannot tell a ring from a stop or a status check, nor read the ringing duration or flashlight choice: every command is an encrypted message of the same size (172 bytes), queued and answered the same way, and the phone sends exactly one status in reply to each.
- Someone who knows the chain id: can ask whether the phone is online and queue malformed commands, which the phone ignores. Cannot impersonate the phone (the relay checks a proof derived from the key).
- Someone within Bluetooth range: sees a beacon only in search mode, with an id that changes every 15 minutes and that only holders of the key recognize. During a Nearby exchange, sees a command of 172 bytes and a status of 508 bytes, both encrypted since 1.6.0, whatever the command.
- A malicious update: refused unless the checksum, package, version and signing certificate all match, and Android always asks the user to confirm.
- Other apps on the phone: the key is encrypted by a non-exportable Android Keystore key, and is excluded from backups and device transfer.

**What is not protected.**
- The relay sees a random chain id, message sizes (fixed for commands and statuses), times and the IP address of every connection. The web proxy on sonne.sidali.dev logs IP addresses, times and paths for 7 days.
- Timing can still hint at a ring: a ringing that stops on its own sends a status at the end of its duration (3 minutes by default, or the duration the command asked for, which the relay can therefore measure), as does pressing Stop on the phone, so a relay that watches times may guess that a ring came first. The page's automatic status check when the phone comes online, and the one Nearby repeats every 8 minutes, are recognizable by their timing.
- A relay that holds a command back and delivers a later one first learns, from whether a status follows, if both were a ring or a stop: the phone refuses a ring or a stop older than the last one it accepted, never a status check. The held command is lost, and the sender sees no confirmation.
- During the switch to encrypted commands, the old signed format is still used, and the relay can read the command type: for a phone that runs Sonne 1.5 or older, for the very first command from a browser that has not yet received a status from a 1.6 phone, and from your other phones until they have reached that phone once over Bluetooth (Nearby), since they never receive its statuses through the relay. A malicious relay that hides a phone's statuses can keep a new browser on the old format. The old format is switched off once every phone runs 1.6.0 or later (`docs/PROTOCOLE.md`, Protocol v3).
- The page served by the relay handles the key in the browser: a malicious relay could serve a page that steals it. Use a relay you trust, or host your own.
- The key on the phone is readable before the first unlock (Direct Boot, so a lost phone that rebooted still rings). Someone with full control of an unlocked or rooted phone can use it.
- Anyone who has the 12 words or the pairing link can ring the phone until the key is changed.
