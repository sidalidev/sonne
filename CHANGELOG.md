# Changelog

## 1.7.0 (2026-10-27)

- Sonne has its own color now: honey, the same in the icon, the app, the notifications and the web page. It no longer follows your wallpaper, so it looks the same on every phone, in light and dark.
- New icon: a rounded bell on flat honey. The themed icon (Android 13 and later) and the notification icon use the same drawing.
- Everything is flat and rounded: no gradients left, soft corners on cards, buttons and fields, round line ends on every drawing.
- New type: Bricolage Grotesque for titles, Figtree for text, both built into the app. Your system text size is still respected.
- The home screen shows the bell on a honey disc, swinging while the phone rings. When Sonne is off, the disc goes quiet.
- The ringing screen is flat honey with a large dark Stop button, easy to see across a room in any theme.
- Every color pair has been checked for contrast (WCAG AA), in light and dark.
- Web page: same colors, icon and type. Its offline cache is renewed.

## 1.6.2 (2026-10-26)

- Sonne is on / off: one switch at the top of the home screen turns everything off in a single tap, and back on just as easily. No confirmation, no settings to dig through.
- While off, this phone can't be rung or found: the background service, its watchdog alarms, the Bluetooth beacon and the connection to the relay all stop, and the permanent notification goes away.
- It stays off after a restart of the phone or of the app, until you turn it back on. Turning it on starts everything again as before, without asking for anything.
- TalkBack reads the switch, its state and what it means.

## 1.6.1 (2026-10-26)

- Permissions page, from Settings at any time (and from the home screen): every permission Sonne uses on one line, why it is needed and which feature depends on it, with its current state.
- Turning a permission off is as easy as turning it on: when it is on, a tap opens the exact Settings screen where you can turn it off.
- Sonne reads each permission again whenever you come back to it. A feature whose permission is off shows as paused, with one calm sentence on how to turn it back on. No repeated prompts.
- Camera and Install updates now appear there too, with Nearby devices covering both the beacon and the search.
- TalkBack reads each row's state and what a tap will do.

## 1.6.0 (2026-10-25)

- Commands are now encrypted end to end, not just signed. Ring, stop and status checks all travel as a message of the same size: the relay, and anyone within Bluetooth range during Nearby, can no longer tell them apart.
- One status per command: the phone answers each command exactly once, whatever it does.
- Your phone tells your browsers and other phones that it understands encrypted commands. They switch as soon as they hear from it. A phone still on Sonne 1.5 or older keeps receiving the old signed format until it updates.
- Nearby shows which version of Sonne the other phone runs, after a Bluetooth exchange.
- Nearby only wakes the other phone's beacon when the relay says that phone is online.
- What leaves your device: updated for encrypted commands.
- Web page: sends encrypted commands to phones that support them. Its offline cache is renewed.

## 1.5.0 (2026-10-24)

- New About screen, from Settings: our principles, the installed version with what's new, the date of the last security review, and the fingerprint of the certificate that signed your copy of Sonne, read from the phone itself so you can compare it with SECURITY.md.
- What leaves your device: a plain list of what Sonne sends, where to, and whether it is encrypted.
- Privacy policy readable inside the app.
- Erase everything: deletes your key, your settings and your other phones from this phone, destroys the encryption keys, and closes Sonne. The next launch starts fresh. Type ERASE to confirm.
- Report a security issue and Contact, by e-mail.
- Web page: a discreet About link with the same principles. Its offline cache is renewed.
- Repository: SECURITY.md, PRIVACY.md, a threat model, a dated internal security review, the list of expected permissions, and checks that run on every push (tests, lint, release build, permissions).

## 1.4.0 (2026-10-24)

- The app takes its colors from your phone (Material You, from your wallpaper) on Android 12 and later, in light and dark. No fixed blue left. Green for "connected" and switches, amber for warnings and red for stop stay recognizable, tuned to your palette.
- The ringing screen uses a strong gradient from the same palette, so it stays easy to spot across a room.
- Buttons react from the first frame with a small spring and a light tap of haptics. Switches tick as they flip.
- Predictive back: on Nearby and My other phones, the screen follows your back swipe and springs back if you change your mind.
- Themed icon: a dedicated monochrome layer, so the icon follows your theme on Android 13 and later.
- No flash at launch: the window starts in your palette's page color.
- English only. The French translation is gone, from the app and the web page.
- Web page: in English, same design. Its offline cache is renewed so installed pages pick up the change.

## 1.3.1 (2026-10-23)

- Removes two biometric permissions that a library added without being used.
- Release builds no longer embed build metadata (step towards reproducible builds).

## 1.3.0 (2026-10-23)

- New look, in line with the rest of the family: light and dark themes, large type, rounded cards, a bottom tab bar, a ringing screen with animated waves and a new icon.
- Your key: one screen to back it up. Save it to your password manager (Proton Pass, Bitwarden, any Credential Manager provider), download a rescue kit (PDF with the QR code and the 12 words), then check your backup by typing 3 of the words. A reminder stays on the home screen until it is done.
- Permissions grouped in a single checklist with clear explanations.
- Web page restyled to match the app.


## 1.2.0 (2026-10-20)

- Sonne updates itself, like the versions of Signal or Telegram outside the Play Store. The relay announces the new version; the app checks the checksum, the package and the signing certificate before Android asks you to confirm the install. Can be turned off in Settings.

## 1.1.0 (2026-10-19)

- Nearby: find a phone close by with a hot and cold gauge over Bluetooth, and ring it without Internet.
- My other phones: add your other phones by QR code, link or 12 words, and ring them from this one.
- The Bluetooth beacon only runs for a short while after a command, so the phone cannot be followed the rest of the time.

## 1.0.1 (2026-10-18)

- The app is in English by default.

## 1.0.0 (2026-10-18)

- First version: ring your Android phone from a web page, even in silent mode, with no Google services and no account. Commands are signed and status updates are encrypted end to end; the relay only passes them along.

Detailed technical notes for 1.0 to 1.2 (in French): `docs/CHANGELOG-technical.md`.
