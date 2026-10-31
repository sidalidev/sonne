# Test de bout en bout sur émulateur

> La première partie décrit le test du protocole v1 (18 octobre 2026, avant la revue). La seconde,
> « Après la revue : protocole v2 », reprend les points corrigés. Depuis la v2, l'adresse du relais se
> pose par `adb shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver --es relay http://10.0.2.2:7700`
> (l'ancien extra de `MainActivity` a été retiré).

Date : 18 octobre 2026. Résultat : **tout passe**, y compris en silencieux et en Ne pas déranger « Priorité ».
Seul le mode « Silence total » coupe le son (limite d'Android, voir plus bas).

## Matériel

- Émulateur `Pixel_7_Pro_API_36` (Android 16, API 36, image Google Play, arm64), lancé sans fenêtre.
- Relais local : `PORT=7700 node relay/server.js`, joint depuis l'émulateur à `http://10.0.2.2:7700`.
- App : APK debug (`./gradlew assembleDebug`), installée par `adb install -r`.
- Contrôleur : `relay/tools/controleur.js` (nouveau). Il ouvre `/ws/ctrl`, envoie la commande signée par `POST /api/cmd`, et déchiffre les états reçus avec `relay/crypto.js`.

## Préparation

```bash
PORT=7700 node relay/server.js > /tmp/sonne-relay.log 2>&1 &
$ANDROID_HOME/emulator/emulator -avd Pixel_7_Pro_API_36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device   # puis attendre sys.boot_completed = 1

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.sidali.sonne android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_dnd dev.sidali.sonne          # accès Ne pas déranger
adb shell appops set dev.sidali.sonne USE_FULL_SCREEN_INTENT allow
adb shell dumpsys deviceidle whitelist +dev.sidali.sonne        # hors optimisation de batterie

# Version debug seulement : adresse du relais posée par adb, lien d'appairage dans le journal.
adb shell am start -n dev.sidali.sonne/.MainActivity --es relay http://10.0.2.2:7700
adb logcat -d -s SONNE_PAIR       # donne http://10.0.2.2:7700/#s=…

# Téléphone en silencieux, volume d'alarme au minimum, Ne pas déranger activé.
adb shell cmd audio set-ringer-mode SILENT
adb shell cmd media_session volume --stream 2 --set 0
adb shell cmd audio set-volume 4 1          # STREAM_ALARM : minimum = 1 sur cet émulateur
adb shell cmd notification set_dnd priority # zen_mode = 1
```

Pas de permission `CAMERA` à accorder : l'app ne la déclare pas (la lampe torche n'en a pas besoin).
`settings put global zen_mode 1` ne tient pas sur Android 16 (remis à 0 par le système) : il faut `cmd notification set_dnd`.

## Scénarios et résultats

Commandes lancées depuis `relay/` : `node tools/controleur.js --lien "<lien>" ring|stop|ping`.

| # | Scénario | Attendu | Obtenu |
|---|----------|---------|--------|
| 1 | `ring`, téléphone en silencieux, alarme au minimum, Ne pas déranger « Priorité » | sonne | `{"delivered":true}` ; journal `Commande acceptée : ring` puis `Sonnerie démarrée` ; `dumpsys audio` : `MediaPlayer … state:started usage=USAGE_ALARM … mutedState:none` ; volume d'alarme passé de 1 à 7 ; `RingActivity` au premier plan ; état déchiffré `ringing:true`, `ack` = nonce de la commande, 300 ms après l'envoi |
| 2 | `stop` | tout s'arrête et se restaure | `Sonnerie arrêtée` ; plus de lecteur actif ; volume d'alarme revenu à 1 ; `RingActivity` fermée ; verrou `sonne:sonnerie` relâché ; état `ringing:false` avec `ack` |
| 3 | `ring` en Ne pas déranger « Silence total » | limite connue | le lecteur démarre mais `mutedState:opPlayAudio` : Android coupe le son. L'app ne peut pas lever un Ne pas déranger posé par l'utilisateur depuis Android 15 |
| 4 | `ping` rejoué, `ring` à signature fausse, `ring` vieux de 16 min | ignorés en silence | le relais livre les quatre (`delivered:true`) mais le téléphone n'accepte que le premier `ping` : un seul état avec `ack`, une seule ligne `Commande acceptée` |
| 5 | Mode avion, `ring` envoyé, puis fin du mode avion | livré à la reconnexion | relais : `{"delivered":false,"queued":true}` ; au retour du réseau, `Réseau disponible, reconnexion immédiate`, commande reçue dans la file `welcome`, sonnerie 1,3 s après la fin du mode avion |
| 6 | Relais tué (SIGTERM), relancé 20 s plus tard | reconnexion en moins de 90 s | téléphone : `fermé (1001)`, puis essais à 1, 3, 8, 17 et 37 s ; en ligne **17 s après la relance du relais** (37 s de coupure en tout). Pire cas théorique avec le délai plafonné : 75 s |
| 7 | Écran éteint (`KEYCODE_SLEEP`), puis `ring` | l'écran s'allume sur la sonnerie | `mWakefulness` passe de `Asleep` à `Awake`, `RingActivity` au premier plan (capture `captures/android-sonnerie-verrouille.png`) |
| 8 | Téléphone allumé et en usage (écran d'accueil), puis `ring` | bandeau d'alarme | comme un réveil Android : bandeau en haut avec bouton Stop au lieu du plein écran. Le bouton Stop du bandeau arrête tout, l'état `ringing:false` part, la notification disparaît |
| 9 | `ring` sans rien toucher | arrêt automatique après 3 min | `Sonnerie démarrée` à 21:29:43.535, `Sonnerie arrêtée` à 21:32:43.537 ; état `ringing:false` reçu à 180,4 s ; volume restauré |
| 10 | `adb reboot` | la veille repart seule | service de premier plan relancé par `BOOT_COMPLETED`, `isForeground=true`, journal `Connexion au relais` moins d'une minute après `adb reboot` ; `ping` suivant accusé |

Captures : `captures/android-sonnerie.png` (scénario 1), `captures/android-sonnerie-verrouille.png` (scénario 7).
L'émulateur est en anglais : l'interface s'affiche donc en anglais (repli `values-en`), comme prévu.

## Vérifications de construction

- `./gradlew testDebugUnitTest` : `tests="11" skipped="0" failures="0" errors="0"`.
- `./gradlew lintDebug` : aucune erreur.
- `cd relay && npm test` : `# tests 40 / # pass 40 / # fail 0`.
- `./gradlew assembleRelease` : les chaînes `SONNE_PAIR` et `SONNE_DEBUG` sont absentes du dex de release (supprimées par R8, `BuildConfig.DEBUG` vaut false), présentes dans celui de debug.

## Ce qui a été ajouté pour ce test

- `MainActivity` (debug seulement) : extra d'intent `relay` qui pose l'adresse du relais et force la veille à se reconnecter.
- `VeilleService` (debug seulement) : le lien d'appairage est écrit dans le journal sous l'étiquette `SONNE_PAIR`.
- `relay/tools/controleur.js` : contrôleur en ligne de commande.

Aucun bug bloquant trouvé dans l'app ni dans le relais.

## Limites et points non testés

- **Silence total** : le son est coupé par Android (scénario 3). Le mode « Priorité » laisse passer les alarmes par défaut, donc la sonnerie passe.
- **Fenêtre de 60 s côté relais** : si le téléphone disparaît sans fermer sa connexion (coupure brutale), le relais le croit en ligne jusqu'à 60 s sans pong. Une commande envoyée dans ce laps est marquée `delivered:true` mais perdue, au lieu d'être mise en file. Quand le téléphone se reconnecte, sa nouvelle connexion remplace l'ancienne tout de suite.
- **Changement de réseau** : la veille relance la connexion à chaque nouveau réseau, même si l'ancienne marchait encore (vu au scénario 5 : une reconnexion de plus 3 s après la première). Sans gravité.
- **Verrouillage par code** non testé : l'émulateur n'a pas de code (pas de modification des réglages de sécurité). Le plein écran au-dessus du verrouillage est donc vérifié écran éteint, sans code.
- **Lampe torche et vibration** : appelées sans erreur, mais l'émulateur n'a ni flash ni vibreur réel. À vérifier sur un vrai téléphone.
- **Mise en veille profonde (Doze)** non testée sur la durée : l'app était dans la liste blanche.

## Après la revue : protocole v2

Date : 18 octobre 2026, même émulateur (`Pixel_7_Pro_API_36`, Android 16), relais local sur le port 7700, APK debug v2 (`targetSdk 34`) installé **par-dessus** la v1 avec ses données.

| # | Scénario | Attendu | Obtenu |
|---|----------|---------|--------|
| 11 | Mise à jour v1 vers v2 sans désinstaller | secret et réglages gardés, passage au stockage par appareil | la boîte chiffrée `s` de `/data/user/0/…/sonne-coffre.xml` se retrouve à l'identique dans `/data/user_de/0/…/sonne-coffre.xml`, l'ancien dossier est vide, l'adresse du relais est gardée ; reconnexion en v2 aussitôt (`MY_PACKAGE_REPLACED`) |
| 12 | Connexion v2 (preuve P dans `Sec-WebSocket-Protocol`, rien dans l'URL) | relais accepte, commandes et états passent | `/health` : `phones: 1` ; `controleur.js ping` : `delivered:true` puis état déchiffré avec `ack` en 3 ms |
| 13 | `ring` en Ne pas déranger « Aucune interruption », alarme au minimum | **sonne** (c'était la limite du scénario 3) | `mZenMode` passe de `ZEN_MODE_NO_INTERRUPTIONS` à `ZEN_MODE_OFF` ; lecteur `usage=USAGE_ALARM state:started mutedState:none` (au lieu de `opPlayAudio` en v1) |
| 14 | `stop` | tout est rétabli | `mZenMode` revient à `ZEN_MODE_NO_INTERRUPTIONS`, volume d'alarme revenu à 1, plus de lecteur, plus de notification de sonnerie |
| 15 | Chien de garde | alarme `ELAPSED_WAKEUP` toutes les 5 min environ | `dumpsys alarm` : `*walarm*:dev.sidali.sonne/.ChienDeGarde`, fenêtre inexacte de 3 min 45 ; déclenchée, reprogrammée à +5 min, battement répondu, aucune reconnexion inutile |
| 16 | Connexion à moitié morte (proxy TCP qui avale tout sans fermer, comme un NAT expiré) | détectée par le battement | `Pas de réponse au battement : reconnexion` 20 s après le battement ; une fois le proxy rétabli, en ligne (`Joignable`), `ping` accusé |
| 17 | Récepteur de debug | adb oui, autre app non | `am broadcast` depuis le shell : relais posé et reconnexion ; le même broadcast lancé avec l'uid de l'app (sans `DUMP`) est refusé par `SecurityException` |
| 18 | `adb reboot` | la veille repart seule | service relancé et connecté 11 s après `sys.boot_completed`, relais `phones: 1` |

Non testé : Direct Boot avec un vrai code de verrouillage (l'émulateur n'en a pas, et on ne modifie pas ses réglages de sécurité), Doze profond sur la durée (l'émulateur ne suspend pas vraiment son processeur, un `Handler` y avance toujours), torche et vibration réelles.

Note de méthode : `kill -STOP` sur le relais ne gèle pas le processus dans ce bac à sable (il reste à l'état `S`) ; d'où le proxy « trou noir » du scénario 16.

## À proximité (app 1.1.0) : deux émulateurs reliés par Bluetooth

Date : 19 octobre 2026. Deux instances de `Pixel_7_Pro_API_36` (Android 16) lancées avec `-read-only` sur les ports 5554 (téléphone protégé) et 5556 (chercheur). Le Bluetooth virtuel de l'émulateur (netsim) relie les deux : la balise de l'un est vue par l'autre, avec un RSSI fictif de +20 dBm (jauge toujours « Très chaud »). Relais local sur `http://10.0.2.2:7700`.

Piège : les deux instances partent de la même image, donc du même secret. `adb -s emulator-5556 shell pm clear dev.sidali.sonne` sur le chercheur en tire un autre.

| # | Scénario | Obtenu |
|---|----------|--------|
| 1 | Démarrage du protégé | `Serveur GATT ouvert`, `Balise émise (fenêtre 1991513, -15 dBm)`, alarme `ROTATION` posée à 00:30:01 (`dumpsys alarm`) |
| 2 | Chercheur : ajout par lien collé (debug) puis par 12 mots (release) | confirmation avec l'identifiant `9087f9`, identique à celui du protégé ; balise reconnue (filtre sur les données de service, `neverForLocation`) : « Very hot, seen 0 s ago » |
| 3 | Faire sonner, relais en marche | par GATT : `Commande acceptée : ring` sur le protégé, « Received over Bluetooth: ringing, battery 100% » sur le chercheur |
| 4 | Relais arrêté, Arrêter | par GATT, sans relais : `Commande acceptée : stop`, état « not ringing » reçu |
| 5 | Bluetooth du chercheur éteint, Faire sonner | « Bluetooth is off » sur l'écran, commande partie par le relais : « Delivered through the relay », le protégé sonne |
| 6 | Bluetooth du protégé éteint puis rallumé | `Bluetooth allumé : balise relancée`, serveur GATT rouvert |
| 7 | Frontière de fenêtre (00:30) | alarme inexacte servie à 00:32:44 : `Balise émise (fenêtre 1991514)` ; le chercheur reconnaît la balise sans interruption (fenêtres w - 1, w, w + 1) |
| 8 | APK release signé sur le chercheur | ajout par 12 mots, Faire sonner et Arrêter par GATT : OK (R8 ne casse rien) |
| 9 | « Changer la clé » sur le protégé | balise relancée aussitôt avec la nouvelle clé ; l'ancien chercheur affiche « Lost » |

Non couvert sur émulateur : lecture d'un vrai QR code (l'aperçu Camera2 s'affiche et l'analyse tourne sans erreur, mais la scène virtuelle ne montre pas de QR au cadrage par défaut), seuils de la jauge (RSSI fictif), bips (émulateur muet), commande reçue à la fois par Bluetooth et par le relais.

### Reprise : preuves par le journal, téléphone protégé hors ligne

Date : 19 octobre 2026, 00:39 à 00:47. Émulateur 35.6.11 : le Bluetooth virtuel passe par `netsimd`, lancé tout seul avec la première instance (aucune option `-feature` à donner ; `avdmanager` est absent du SDK, d'où deux instances `-read-only` de `Pixel_7_Pro_API_36` plutôt qu'une seconde AVD). A = chercheur (`emulator-5554`), B = téléphone protégé (`emulator-5556`). APK debug installé par `adb install -r -g` après désinstallation sur les deux (chacun tire donc son propre secret). **Aucun relais lancé**, et B coupé de tout réseau (`svc wifi disable`, `svc data disable` : `Active default network: none`, `ping 10.0.2.2` : `Network is unreachable`).

Appairage de B sur A sans écran, par le récepteur de debug (nouvel extra `pair`, même chemin que le lien collé) :

```bash
adb -s emulator-5556 logcat -d -s SONNE_PAIR            # http://10.0.2.2:7700/#s=…
adb -s emulator-5554 shell am broadcast -n dev.sidali.sonne/.DebugRelaisReceiver \
    --es pair "'http://10.0.2.2:7700/#s=…'" --es nom B   # « Autre téléphone ajouté par adb : AJOUTEE (id cd0074) »
```

Écran Nearby ouvert sur A par `uiautomator dump` puis `input tap` (« Find a phone nearby », « Ring », « Stop ringing »). Le chercheur journalise en debug (supprimé de la release par R8, vérifié dans le dex) la première reconnaissance de chaque `id_ble` et, toutes les 2 s, le signal brut, lissé et le niveau.

| # | Scénario | Journal obtenu |
|---|----------|----------------|
| 10 | Balise de B | `SonneBalise: Balise émise (fenêtre 1991514, id_ble 06b2a43cd320c7fc, -15 dBm)` |
| 11 | Reconnaissance sur A | `SonneChercheur: Balise reconnue : chaîne cd0074, id_ble 06b2a43cd320c7fc, fenêtre w+0 (1991514), adresse 40:3C:12:6D:1E:0A, 20 dBm` (même `id_ble` que celui émis par B) |
| 12 | Force du signal | `Signal cd0074 : brut 20 dBm, lissé 20.0 dBm, niveau 5, vu il y a 16 ms`, toutes les 2 s ; écran « Very hot, Seen 0 s ago » |
| 13 | Ring, B hors ligne | A : `Envoi de ring par Bluetooth (GATT)`, `Commande ring écrite par GATT`, `Accusé reçu par Bluetooth : ring, sonne true, batterie 100 %` (1,3 s en tout) ; B : `Commande acceptée : ring (Bluetooth)`, `Sonnerie démarrée`, lecteur `USAGE_ALARM state:started` ; écran de A « Received over Bluetooth: ringing, battery 100% » |
| 14 | Stop, B hors ligne | A : `Accusé reçu par Bluetooth : stop, sonne false` (0,5 s) ; B : `Commande acceptée : stop (Bluetooth)`, `Sonnerie arrêtée`, plus aucun lecteur actif |
| 15 | Bluetooth de B éteint, Ring | la balise met environ 16 s à disparaître (arrêt lent de la pile de l'émulateur), puis « Lost » ; Ring : `Bluetooth abandonné (délai dépassé) : passage par le relais` à 5 s, `Relais injoignable : ConnectException`, écran « Could not reach the phone, neither over Bluetooth nor through the relay. » Pas de plantage sur B (même processus) |
| 16 | Bluetooth de B rallumé | `Bluetooth allumé : balise relancée`, `Serveur GATT ouvert`, `Balise émise` ; A revoit B en moins d'une seconde |
| 17 | Bluetooth de A éteint pendant le scan | carte « Bluetooth is off » avec « Turn on Bluetooth », jauge « Lost », pas de plantage (même processus) ; rallumé : le scan repart seul, « Very hot » en moins de 15 s |
| 18 | Frontière de fenêtre (00:45) | alarme inexacte servie à 00:46:53 : `Balise émise (fenêtre 1991515, id_ble cc5f73b81a898b2a)` ; sur A, à la même seconde : `Balise reconnue : … id_ble cc5f73b81a898b2a, fenêtre w+0 (1991515), adresse 64:5E:57:22:BB:BF` (nouvelle adresse) ; entre 00:45 et 00:46:53, l'ancien `id_ble` reste reconnu comme w - 1 |
| 19 | Ring puis Stop après la rotation | par GATT, mêmes journaux qu'aux points 13 et 14, B toujours hors ligne |

Tests JVM ajoutés (`ProximiteTest`) : reconnaissance à la frontière de fenêtre et avec des horloges décalées de 14 min 59 s dans les deux sens, refus d'une rotation en retard de deux fenêtres ou d'un octet de version inconnu ; lissage pas à pas sur une série de mesures (montée régulière, α = 0 à Δt nul, pic isolé amorti à 12 %, remise à zéro après 10 s). 29 tests JVM passent (17 CryptoTest, 12 ProximiteTest).

Aucun bug bloquant trouvé. Toujours pas testé : vrai QR code, seuils de la jauge et bips (RSSI fictif de +20 dBm, émulateur muet), commande reçue à la fois par Bluetooth et par le relais.

### Après la revue de la 1.1 : mode recherche

Date : 19 octobre 2026, 01:19 à 01:34. Mêmes deux instances `-read-only` de `Pixel_7_Pro_API_36` (A = chercheur `emulator-5554`, B = téléphone protégé `emulator-5556`), APK debug installé par `adb install -r -g` après désinstallation, relais local `PORT=7700 node relay/server.js` posé par le récepteur de debug sur les deux.

| # | Scénario | Journal obtenu |
|---|----------|----------------|
| 20 | B démarré, relais joint, Bluetooth allumé | ni `Mode recherche`, ni `Balise émise` : hors mode recherche, rien n'est émis |
| 21 | A ouvre À proximité | A : `Envoi de ping par le relais` (01:21:50.861) ; B : `Commande acceptée : ping (relais)`, `Mode recherche ouvert`, `Serveur GATT prêt` puis seulement `Balise émise` (01:21:50.926) ; A : `Balise reconnue` 83 ms après le ping |
| 22 | Ring (premier essai) | **bogue trouvé** : `État trop long pour le MTU de la connexion (518 > 514)` sur B, accusé perdu, A repasse par le relais au bout de 5 s. Cause : `JSONObject` échappe les « / » du base64. Corrigé : message d'état écrit à la main, 508 octets toujours (test JVM ajouté) |
| 23 | Ring, Stop, Ring, Stop par GATT | accusés reçus 421, 295, 349 et 427 ms après l'appui ; B : `Commande acceptée : … (Bluetooth)` puis `Sonnerie démarrée` ou `arrêtée` 11 à 17 ms après l'acceptation (accusé avant le travail lourd) ; une seule adresse, la balise reste visible après chaque connexion (ensemble réactivé, même adresse) |
| 24 | Notification de veille de B pendant le mode | `Reachable · Bluetooth search on` |
| 25 | A quitte À proximité, attente | B : `Mode recherche fermé` à 01:34:09.665, 10 min après la dernière commande (01:24:09.061) ; notification revenue à `Reachable` ; `GATT Advertiser Map` vide |
| 26 | Chien de garde | `dumpsys power` : `ACQ sonne:garde` 01:32:22.393, `REL sonne:garde` 01:32:22.426 (33 ms, au lieu de 30 s) ; rotation de la balise servie à la même alarme (`Balise émise (fenêtre 1991518)`), verrou relâché aussitôt |

Un premier essai avait aussi montré que la relance complète de la balise après une connexion changeait son adresse : l'arrêt suivant avait d'abord essayé l'adresse éteinte (5 s perdues). Corrigé deux fois : réactivation de l'ensemble existant, et seules les adresses entendues dans les 2 s sont essayées.

Non testé ici : ouverture du mode recherche après une heure hors ligne (tests JVM seulement), saturation du serveur GATT par un inconnu, nom Bluetooth lu par un tiers (nRF Connect sur un vrai téléphone), lecture de `bluetooth_off_timeout` sur GrapheneOS, APK release en situation (le relais local est en `http://`, refusé en release).

## Mise à jour intégrée (app 1.2.0), 2026-10-20

Émulateur `Sonne_Update_API_36` (image système de `Pixel_7_Pro_API_36`, Android 16 avec Play Store), lancé avec `-port 5640 -no-window`. Relais local sur un port libre avec `SONNE_RELEASES_DIR` pointant vers un dossier de test ; l'app debug réglée sur `http://10.0.2.2:<port>` par `DebugRelaisReceiver`.

```bash
./gradlew assembleDebug                                                  # 1.2.0 (4), installée
./gradlew assembleDebug -PsonneVersionCode=5 -PsonneVersionName=1.2.1    # 1.2.1 (5), servie par le relais
apksigner verify --print-certs sonne-1.2.1.apk                          # certSha256 (clé de debug)
```

| Scénario | Résultat |
| --- | --- |
| Ouverture de l'app, annonce 1.2.1 | journal `Annonce 1.2.1 (5), installée 1.2.0 (4)`, notification « Update available » sur le canal `mises_a_jour` (importance 3), bandeau sur l'accueil |
| Tap sur la notification sans l'autorisation « Installer des applis inconnues » | bandeau d'explication, le bouton ouvre le réglage système de Sonne ; autorisation donnée, retour : bouton « Download and install » |
| Annonce au mauvais `sha256` | `Mise à jour refusée (SHA256)`, bandeau « Update refused… checksum », `cache/maj` vide |
| APK re-signé par une autre clé, annonce cohérente avec lui (somme et `certSha256` de l'autre clé) | `Mise à jour refusée (CERTIFICAT)`, bandeau « not signed with the same key », fichier supprimé |
| Bonne annonce | `APK 1.2.1 vérifié (SHA-256 et certificat)`, écran système « Do you want to update this app? », accepté par `adb shell input tap` |
| Play Protect (image avec Play Store) | au premier essai, écran « App scan recommended » ; fermé, l'installation est annulée et le bandeau revient ; au second essai, installée directement |
| Après installation | `dumpsys package` : `versionCode=5`, `versionName=1.2.1` ; `MY_PACKAGE_REPLACED` relance la veille, même id de chaîne, journal `Mise à jour installée` |

Limite : le test passe par la version debug (relais local en `http://`, refusé en release). La release 1.2.0 a été vérifiée en ligne (annonce et téléchargement), pas installée par-dessus elle-même.

## Commandes chiffrées : page et relais 1.6 (relais 1.2.0), 2026-10-25

Tout en local, rien de déployé. Relais `node relay/server.js` sur 127.0.0.1 (ports 7751 à 7753), faux téléphone `relay/tools/faux-telephone.js` (`--ancien` pour l'app 1.5 : ancien format seulement, pas de `cmdv` ; sans option pour l'app 1.6 : filtre de référence `creerFiltreCommandes`, `"cmdv":3`, état bourré à 508 octets, un seul état par commande), page servie par le relais et pilotée dans Chromium headless (Playwright déjà présent sur la machine, contextes neufs, trames WebSocket capturées).

| # | Scénario | Obtenu |
|---|----------|--------|
| 1 | Relais en transition (`SONNE_CMD_V2=1`), téléphone 1.5, page ouverte par le lien | fragment effacé, « Online », « app 1.5.0 » ; vérification automatique envoyée en v2 |
| 2 | Ring puis Stop vers l'app 1.5 | trames v2 (`cmd`, `sig`) ; accusés « Phone is ringing », « Ringing stopped » ; aucun drapeau `sonne.cmdv3` |
| 3 | Le téléphone passe en 1.6 (même clé) | une seule vérification au retour en ligne, encore en v2 ; l'état annonce `cmdv` 3 : drapeau posé, « Commands to this phone are now encrypted » dans l'activité |
| 4 | Ring puis Stop | trames de 172 octets, clés `type,v,nonce,enc` ; acceptées en v3 par le téléphone ; `relayed` et `ack` reprennent le nonce de 24 hex |
| 5 | Rechargement de la page | la vérification automatique part déjà en v3 (drapeau persistant) |
| 6 | Téléphone hors ligne | aucune vérification automatique ; Ring v3 : « Phone offline: the command waits on the relay » ; livrée par `welcome` à la reconnexion et acceptée |
| 7 | Clés rangées avant la 1.6 (`{id, cleSig, cleEnc}` sans `cleCmd`, drapeau posé) | `cleCmd` dérivée au chargement et rangée (secrète, non exportable, `["encrypt"]`) ; Ring v3 acceptée |
| 8 | Retirer le téléphone | son drapeau disparaît avec lui |
| 9 | Relais coupé (`SONNE_CMD_V2=0`), navigateur neuf, téléphone 1.6 | vérification v2 refusée (400 `old command format…`), renvoyée aussitôt en v3, en silence ; drapeau posé |
| 10 | Ring puis Stop sur ce relais | v3 directement |
| 11 | Relais coupé, navigateur neuf, téléphone hors ligne, Ring | v2 refusée, même intention renvoyée en v3 (une seule ligne « Ring: command sent »), mise en file, acceptée à la reconnexion |
| 12 | Textes et cache | pied de page et À propos à jour ; cache du service worker `sonne-v6` |

Journal du relais : aucune mention de `ring`, `stop` ni `ping` (seulement le démarrage et les compteurs). `cd relay && npm test` : 79 tests, 0 échec (dont les vecteurs v3 rejoués par `web/crypto.js` et les nouveaux tests du relais : v3 relayée en 172 octets, ping v3 mis en file, v3 en vol relivrées, 10 au plus en file, 400 sur une v2 après la coupure, `SONNE_CMD_V2` lu au lancement).

Non couvert ici : l'app Android 1.6 réelle face à cette page (lot Android), Safari et Firefox, et le passage d'une page 1.5 installée (cache `sonne-v5`) à la 1.6.

## App Android 1.6.0 : commandes chiffrées, 2026-10-25

Tout en local, rien de déployé. Émulateur `emulator-5554` (Android 16, image Google, `sdk_gphone64_arm64`) avec l'app **1.5.0 debug installée et sa clé**, mise à jour en **1.6.0 debug par-dessus** (`adb install -r` : même id `00a347`, reconnexion aussitôt). Relais 1.2.0 local (`node relay/server.js`, ports 7741 à 7743, `SONNE_CMD_V2=1` sauf mention), joint depuis l'émulateur à `http://10.0.2.2:<port>` (récepteur de debug). Contrôleur `relay/tools/controleur.js` (v3 par défaut, `--v2`), petit script Node pour les refus (mêmes fonctions de `relay/crypto.js`), faux téléphone `relay/tools/faux-telephone.js`, et la page locale dans Chromium headless (Playwright). Réglages globaux relevés avant (Ne pas déranger éteint, sonnerie normale, volume d'alarme 6, Bluetooth allumé) et retrouvés à la fin.

| # | Scénario | Obtenu |
|---|----------|--------|
| 1 | App **1.5.0**, relais en transition : `ping` v3, puis `ping` v2 | v3 relayée (172 octets) et ignorée ; v2 accusée (`ack` de 32 hex), état sans `cmdv` |
| 2 | 1.6.0 : `ring` v3 | `delivered:true` ; **un seul état** en 20 s, 85 ms après : 508 octets, `ack` = nonce de 24 hex, `ringing:true`, `"cmdv":3`, `app` 1.6.0 ; journal `Command accepted: ring, v3 (relay)` ; lecteur `USAGE_ALARM state:started`, volume d'alarme 6 puis 7 |
| 3 | `stop` v3, puis `ping` v3 | un état chacun (`ringing:false`, puis `ack`) ; plus de lecteur, volume revenu à 6 |
| 4 | `ring` puis `stop` en **v2** (transition) | acceptées, `ack` de 32 hex, un état chacune |
| 5 | La même v3 rejouée ; une v3 de 16 min ; une v3 chiffrée avec une autre clé | un seul accusé ; journal debug `Command refused: rejeu`, `fraicheur`, `authentification` ; rien d'autre |
| 6 | `ring` v3 de **60 s, lampe éteinte** | accusé `ringing:true`, puis arrêt automatique à 60,09 s (réglage : 3 min) avec son propre état (`ack` null) |
| 7 | Téléphone arrêté (`am force-stop`), `ring` v3 | livrée à la connexion morte, remise en file à sa fermeture (en vol), acceptée à la reconnexion |
| 8 | Téléphone hors ligne (présence `false`), `ping` v3 | `{"delivered":false,"queued":true}` ; livrée par `welcome`, acceptée |
| 9 | Version **coupée** (`./gradlew assembleDebug -PsonneCoupureV2=true`) : `ping` v2, puis `ping` v3 | v2 refusée en silence (`ancien-format`, aucun état) ; v3 accusée |
| 10 | Chercheur (même `EnvoiCommande` que Nearby, lancé par le récepteur de debug, `--es envoyer ring --es vers 84c083`), **relais coupé** (`SONNE_CMD_V2=0`), faux téléphone 1.6 | `ring` en v2 refusée (400) en 79 ms, même intention renvoyée en v3 avec un nouveau nonce, livrée ; le faux téléphone l'accepte en v3. Idem `stop` et `ping` |
| 11 | Vérification automatique du chercheur (`--ez auto true`) | téléphone en ligne : présence, puis `ping` envoyé ; hors ligne : `Phone offline: automatic ping not sent`, rien n'est envoyé |
| 12 | `ring` du chercheur, faux téléphone hors ligne | v2, 400, v3 : `queued:true` |
| 13 | **Page locale** (Chromium headless, navigateur neuf) face à l'app réelle | lien accepté (confirmation avec `00a347`) ; vérification automatique en v2 (162 octets), l'état de l'app annonce `cmdv` 3 : « Commands to this phone are now encrypted » ; Ring : trame de 172 octets `type,v,nonce,enc`, « Phone is ringing (confirmed in 73 ms) », un seul état ; Stop : v3, « confirmed in 59 ms » ; après rechargement, la vérification part déjà en v3 ; la page affiche « app 1.6.0 » |
| 14 | Écran What leaves your device | Ring commands : « Encrypted: yes, end to end », texte sur la taille fixe et Sonne 1.5 ; What the relay sees : tailles identiques pour toutes les commandes (`captures/android-ce-qui-sort-1.6.0.png`) |

JVM : `./gradlew testDebugUnitTest` : 52 tests, 0 échec (26 CryptoTest dont `K_cmd`, RFC 5869 A.1 pour Expand, les 4 messages et `etat_v3` octet pour octet, les 19 cas en transition et après la coupure, les formes ; 20 ProximiteTest dont la taille des écritures GATT dans les deux phases et le clair de l'état écrit à la main ; 6 MiseAJourTest). `lintDebug` : aucune erreur.

Conditions : Mac très chargé (envoi de photos, mémoire d'échange presque pleine). L'émulateur a eu plusieurs ANR (dont l'app **1.5.0**, avant la mise à jour) et un redémarrage de `system_server` par son chien de garde (ActivityManager bloqué 69 s) : des commandes ont parfois attendu jusqu'à une minute que le fil principal se libère. Rien n'a été perdu ni exécuté deux fois.

Non couvert : le chemin Bluetooth (GATT) entre deux téléphones 1.6 (il faut deux émulateurs ; seuls la taille des messages et les règles d'écriture sont testées sur la JVM), l'apprentissage de `cmdv` par un état reçu en GATT, un vrai téléphone, l'APK release en situation (relais local en `http://`).

## Revue adverse de la 1.6.0, 2026-10-25

Tout en local, rien de déployé. Relais 1.2.0 du dépôt sur `127.0.0.1:7761` (transition, puis `SONNE_CMD_V2=0`), qui sert la page 1.6 ; page 1.5 (commit `dc3638b`) servie par un petit proxy qui renvoie `/ws` et `/api` vers ce même relais ; app 1.6.0 debug sur `emulator-5554` ; Chromium headless (Playwright), trames WebSocket capturées. Réglages globaux de l'émulateur relevés avant et identiques après.

| # | Scénario | Obtenu |
|---|----------|--------|
| 1 | Vecteurs recalculés sans le code du dépôt (Python, `cryptography`) | `K_cmd` (HMAC et HKDF-Expand), les 4 messages octet pour octet, `etat_v3`, les 19 cas : identiques. `genere-vecteurs.js` redonne exactement `docs/VECTEURS.json` |
| 2 | Release 1.5.0 (code 8), puis l'APK de test 1.6.0 par-dessus (`adb install -r`), réseau de l'émulateur coupé | installée ; même uid, `firstInstallTime` inchangé, même id (`34d2f6`) avant et après ; certificat `3100c7dc…02e3` des deux côtés ; l'APK de test est identique à `assembleRelease` du commit `bcfd0cb` |
| 3 | Page 1.6, navigateur neuf, relais en transition | vérification automatique en v2 (162 octets), état avec `cmdv` 3, drapeau posé ; Ring et Stop : 172 octets, clés `type,v,nonce,enc`, un seul état de 508 octets chacun ; après rechargement, la vérification part en v3 |
| 4 | La ring v3 capturée, rejouée par un autre contrôleur | `relayed`, `delivered:true`, aucun état ; journal de l'app `Command refused: rejeu` |
| 5 | Page 1.5 pendant la transition | vérification, Ring et Stop en v2 acceptés par l'app 1.6, sonnerie démarrée puis arrêtée ; aucun drapeau |
| 6 | `controleur.js` : ping, ring, stop en v3, trois tours | un état chacun, `ack` de 24 hex |
| 7 | Relais coupé, page 1.6 neuve, puis page 1.5 | page 1.6 : v2 refusée (400), même intention renvoyée en v3, drapeau posé ; page 1.5 : « Refused by the relay: old command format: reload the page or update the app » |
| 8 | Relais qui inverse deux commandes (A retenue, B plus récente livrée d'abord) | A ring, B stop : A refusée (« ordre »), un seul état ; A ring, B ping : deux états. Fuite active documentée dans `PROTOCOLE.md` et `THREAT_MODEL.md` |
| 9 | Journal du relais | aucun type de commande |

Délai de l'accusé sur l'émulateur très chargé (ANR système) : ring 1,7 à 2,6 s, stop 4,8 à 9 s, ping 1 à 6 s. L'écart vient sans doute du fil principal occupé pendant la sonnerie ; à mesurer sur le Pixel : s'il s'y confirme, le délai de l'accusé est un indice de plus pour le relais.

Non couvert : le chemin Bluetooth (GATT) entre deux téléphones 1.6, un vrai téléphone.
