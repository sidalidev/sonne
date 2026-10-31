# Privacy policy

Sonne has no account, no analytics, no ads, no crash reports and no Google services. Your key is created on your phone and stays there unless you choose to save it somewhere. This page lists everything that leaves your device, where it goes, and whether it is encrypted. The app shows the same list under Settings, About, What leaves your device.

## Phone status

- What: battery level, whether the phone is ringing, phone model and app version.
- To: your relay (sonne.sidali.dev unless you set another one in Settings).
- Encrypted: yes, end to end (AES-256-GCM with a key derived from your key). Only your paired browsers and phones can read it.

## Ring commands

- What: ring, stop and status checks, sent from your browsers and from your other phones, over the relay or over Bluetooth (Nearby).
- To: the relay of the phone you are ringing, or the phone itself when it is close by.
- Encrypted: yes, end to end (AES-256-GCM with a key derived from your key). Every command is a message of the same size: the relay cannot tell a ring from a stop or a status check. Nobody without your key can send or read one, and a phone ignores a replayed command.
- A phone that still runs Sonne 1.5 or older only understands the old format, signed (HMAC-SHA256) but not encrypted, where the relay can see which of the three commands it is. Your browsers and phones keep using it for that phone until it updates. A browser also uses it for its first command to a phone it has not heard from yet, and your other phones until they have reached that phone once over Bluetooth (Nearby). The old format will be switched off once phones have updated.

## What the relay sees

- A random chain id, message sizes (the same for every command, the same for every status), times, and your IP address while connected. It never sees the content.
- It writes nothing to disk. Waiting commands are dropped after 15 minutes, the last seen time of a phone after 24 hours, and everything is gone when the relay restarts.
- Its own log only has counters and errors, with at most the first 6 characters of a chain id.
- On sonne.sidali.dev, the web server in front of the relay logs each request (IP address, time, path, response size, never the chain id) for 7 days.

## Update check

- What: once a day and when the app opens, a request for the latest version number, and the download if you choose to install it. No identifier is sent.
- To: your relay.
- Encrypted: in transit (HTTPS).
- You can turn it off in Settings.

## Bluetooth beacon (Nearby)

- What: a short Bluetooth signal so your other devices can find the phone close by. Only for 10 minutes after a command, while ringing, or after an hour without the relay.
- To: devices within Bluetooth range.
- Encrypted: no, but its id changes every 15 minutes and only devices with your key recognize it.
- You can turn it off in Settings.

## Your key

- Only where you choose: your password manager, a rescue kit PDF you save, or the clipboard (marked sensitive), when you tap one of these buttons.
- It is never sent to the relay. The pairing link carries it after a `#`, a part that browsers never send to a server.

## The web page

- The page at sonne.sidali.dev keeps your phones in this browser only: non-exportable keys in IndexedDB, names and activity in local storage. No cookies, no analytics.
- It talks only to the relay that served it, with the same rules as above.

## Your data on the phone

- Your key is stored encrypted by a key in the Android Keystore. Backups and device-to-device transfer are turned off for it.
- Erase everything (Settings, About) deletes your key, your settings and your other phones, destroys the encryption keys, and closes the app. The relay has no erase command: once the app is gone, it forgets the phone within 24 hours.

## Contact

Sid, contact@sidali.dev. Questions, requests and security reports are welcome.
