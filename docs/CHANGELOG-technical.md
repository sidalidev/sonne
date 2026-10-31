# Technical notes, versions 1.0 to 1.2

Detailed notes from the first releases, kept as written at the time (in French). The human changelog is `CHANGELOG.md`.

## 1.2.0 (2026-10-20) : mise à jour intégrée

Comme les versions hors Play de Signal ou Telegram : l'app se met à jour elle-même, depuis le relais (le dépôt GitHub est privé). Protocole v2 inchangé (section « Mise à jour »).

### Ajouté

- **Relais 1.1.0** : `GET /update/android.json` (annonce `{ version, versionCode, url, sha256, certSha256, notes }`, cache 5 min) et `GET /download/<nom>.apk`, servis depuis `SONNE_RELEASES_DIR` (dossier hôte monté en lecture seule dans `deploy/docker-compose.yml`). Noms validés strictement, 404 pour tout le reste. Tests `relay/test/update.test.js` (traversée de dossier, encodages, fichiers absents, annonce invalide).
- **App** : vérification une fois par jour dans le chien de garde existant et à l'ouverture, désactivable (Réglages, « Rechercher les mises à jour », actif par défaut, avec « Vérifier maintenant »). Notification « Mise à jour disponible » (nouveau canal Mises à jour, importance normale) et bandeau sur l'accueil.
- Au tap : téléchargement OkHttp dans le cache, puis SHA-256 annoncé, paquet et `versionCode` attendus, et certificat de signature identique à celui de l'app installée et à `certSha256`. Sinon refus expliqué et fichier supprimé.
- Installation par `PackageInstaller` avec `USER_ACTION_REQUIRED` (l'écran de confirmation d'Android est toujours montré), permission `REQUEST_INSTALL_PACKAGES`, explication et bouton vers le réglage « Installer des applis inconnues » quand il manque.
- `tools/release-android.sh` : APK release signé, sommes, `android.json`, copie sur le VPS par scp, vérification en ligne, release GitHub avec l'APK et sa somme. `--sans-publication` pour préparer seulement.
- Tests JVM `MiseAJourTest` : comparaison de versions, validation de l'annonce, SHA-256 d'un fichier, refus d'un certificat différent (faux octets).
- `versionCode` et `versionName` surchargeables par `-PsonneVersionCode` et `-PsonneVersionName`, pour le test de bout en bout seulement.

## 1.1.0 (2026-10-19) : À proximité (Nearby)

« Il n'est pas loin, je m'en rapproche, et je le fais sonner. » Ajout pur au protocole v2 (section « Proximité Bluetooth ») : relais et page web inchangés.

### Ajouté

- **Balise Bluetooth** émise par le service de veille **en mode recherche seulement** (voir plus bas) : publicité BLE legacy connectable, faible puissance (`TX_POWER_LOW`), 250 ms, données de service sous l'UUID Sonne `2af8c8b3-3a13-489b-a3a3-ac220005799b` = `id_ble` (8 octets) puis la version `0x01`. `id_ble = HMAC-SHA256(B, w)[0:8]`, `B = HKDF(S, "sonne-ble", 32)`, `w = floor(now / 15 min)` : seul un appareil qui connaît S la reconnaît, et elle change toutes les 15 min (l'ensemble d'émission est relancé, la pile tire une nouvelle adresse). Relancée au retour du Bluetooth, revérifiée par le chien de garde, rotation par alarme à la frontière de fenêtre.
- **Serveur GATT** sur le même UUID : caractéristique Commande (écriture avec réponse) qui accepte exactement les commandes signées du relais, par le même chemin (`FiltreCommandes`, même mémoire de nonces : une commande reçue par Bluetooth puis par le relais n'est exécutée qu'une fois) ; caractéristique État (notification) avec l'état chiffré habituel. 4 connexions Sonne au plus (éviction de la plus ancienne sans commande valide), 5 s sans commande valide, 10 s après, budget de requêtes par connexion, 20 commandes valides par minute.
- **Mes autres téléphones** : liste de chaînes connues (nom local, relais), ajout par scan de QR code (caméra, permission demandée au moment du scan), par lien collé ou par 12 mots, avec confirmation par le début de l'identifiant. Seules les clés dérivées (id, `K_sig`, `K_enc`, B) sont gardées, pas S, chiffrées par une clé de l'Android Keystore utilisable seulement téléphone déverrouillé, en stockage par identifiant. La clé de ce téléphone est refusée.
- **Écran À proximité (Nearby)** depuis l'accueil (« Trouver un téléphone à proximité ») : scan BLE filtré sur l'UUID Sonne seulement au premier plan, reconnaissance pour w - 1, w et w + 1, signal lissé (moyenne exponentielle, τ = 2 s), jauge de 0 à 5 (Perdu à Très chaud) avec hystérésis de 3 dB, « vu il y a x s » et dernière mesure, bip généré qui accélère (flux des notifications, désactivable), vibration, gros bouton Faire sonner par GATT si le téléphone a été vu il y a moins de 10 s, sinon (ou en cas d'échec) le même message signé par le relais ; bouton Arrêter.
- Réglages : « Balise Bluetooth » (active par défaut) et « Bips de proximité ». Autorisations : ligne « Appareils à proximité » (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`). Accueil : état de la balise et rappel quand le Bluetooth est éteint.
- Permissions : `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` avec `neverForLocation` (pas de localisation), `CAMERA` (scan de QR seulement). Matériel Bluetooth et caméra non requis.
- Version debug, pour les tests sur deux émulateurs : ajout d'un autre téléphone par adb (extra `pair` de `DebugRelaisReceiver`), journal de la reconnaissance des balises et du signal lissé (`SonneChercheur`), absents de la release.
- Lecteur de QR code sans dépendance nouvelle : Camera2 et ZXing core (déjà présent).
- Tests JVM : vecteur `ble` de `docs/VECTEURS.json` (B, `id_ble`, fenêtres voisines, données de service), lien vers secret, et `ProximiteTest` (données et filtre, reconnaissance sur trois fenêtres, lissage, niveaux et hystérésis, limite de débit, fraîcheur des états, statuts d'écriture GATT).
- `relay/crypto.js` : `deriveCleBle`, `deriveBleId`, `fenetreBle`, avec leur vecteur et un test WebCrypto indépendant.

### Modifié (revue de sécurité et de fiabilité de la 1.1)

#### Critique

- **Mode recherche** : la balise et le serveur GATT ne tournent plus 24 h sur 24. Ils s'ouvrent pour 10 min après chaque commande acceptée (`ring`, `stop` ou `ping`, par le relais ou par Bluetooth), pendant la sonnerie, ou quand le relais est injoignable depuis une heure. Avant, la balise était connectable en permanence : n'importe qui à portée pouvait s'y connecter et lire le nom Bluetooth du téléphone (service GAP d'Android) et l'empreinte de sa base GATT, des valeurs fixes qui reliaient toutes les fenêtres entre elles. L'écran À proximité demande le mode recherche par le relais (`ping` signé, silencieux) à l'ouverture puis toutes les 8 min, et dit si la demande est passée. La notification de veille affiche « Recherche Bluetooth active ». Les Réglages conseillent un nom Bluetooth neutre.

#### Majeur

- Pistage par les détenteurs de S : B reste dérivée de S (les 12 mots et le lien suffisent à ajouter un téléphone dans Nearby), mais la balise n'émet plus qu'en mode recherche. L'écran Appairage prévient : qui a les 12 mots peut savoir quand le téléphone est à proximité pendant que la balise émet, et peut la faire émettre.
- Serveur GATT : signature, fraîcheur et nonce inédit vérifiés avant la limite de 20 commandes par minute (un inconnu qui écrit des octets au hasard, ou qui rejoue une commande captée, ne bloque plus les vraies commandes) ; 2 écritures invalides par connexion, puis coupure ; écritures d'une connexion refusée ou coupée ignorées.
- Serveur GATT : à saturation, éviction de la plus ancienne connexion sans commande valide au lieu de refuser le nouveau venu ; 5 s de vie sans commande valide (30 s avant).
- Serveur GATT : budget de 10 requêtes ATT par connexion, puis coupure ; la mémoire des nonces n'est réécrite que si elle a changé.
- Serveur GATT : une connexion n'est comptée qu'au premier accès au service Sonne. Montre, écouteurs et liens des autres apps ne prennent plus de place, et une place est libérée dès que Sonne coupe, sans attendre une déconnexion qui peut ne jamais venir.
- Serveur GATT : `onServiceAdded` suivi ; prêt seulement sur succès, nouvel essai à 1, 2, 5, 10 puis 30 s (aussi quand `openGattServer` échoue juste après le retour du Bluetooth) ; la balise n'émet qu'une fois le service publié.
- Chercheur : l'appareil du `ScanResult` est gardé (type d'adresse aléatoire) au lieu de `getRemoteDevice(adresse)`, qui vise une adresse publique depuis Android 13.
- Chercheur : second essai de connexion sur un statut 133, 62 ou 8 reçu avant 3 s ; scan en pause pendant la connexion GATT.
- Accusé : le téléphone notifie l'état (avec `ringing` attendu) avant le travail lourd de la sonnerie ; le chercheur attend 5 s (3 s avant) et, si l'écriture a été acceptée mais que rien ne revient et que le relais échoue, affiche « écrite par Bluetooth, sans accusé » au lieu d'un échec.
- Batterie : le verrou de 30 s du chien de garde est relâché dès la réponse au battement (ou tout de suite s'il n'y a rien à attendre) ; celui de l'alarme de rotation dès la rotation faite.
- GrapheneOS : la coupure automatique du Bluetooth (réglage `bluetooth_off_timeout`) est détectée quand elle est lisible et signalée sur l'accueil, dans les Autorisations et dans À proximité.

#### Mineur

- États notifiés par Bluetooth seulement aux connexions qui ont écrit une commande valide ; clair de l'état bourré à 320 octets (champ `pad`, modèle tronqué à 64 caractères) : la longueur du chiffré ne dit plus rien du modèle ni de la version. Le message d'état est écrit sans échappement (508 octets, toujours) : `JSONObject` échappait les « / » du base64 et pouvait dépasser les 512 octets d'un attribut GATT (trouvé au test sur émulateurs).
- Chercheur : signal suivi adresse par adresse (un clone qui réémet `id_ble` ne détourne plus la jauge ni l'envoi), jusqu'à 3 adresses récentes essayées pour Faire sonner, alerte quand deux adresses émettent le même `id_ble` plus de 3 s.
- Mes autres téléphones : stockage par identifiant (illisible avant le premier déverrouillage), clé du Keystore `setUnlockedDeviceRequired`, seulement les clés dérivées, liste oubliée de la mémoire en quittant l'écran. L'ancienne liste est déplacée une fois.
- Sauvegarde : les domaines `device_*` (stockage chiffré par appareil) sont exclus de la sauvegarde cloud et du transfert d'appareil.
- Nonces et ordre des ring/stop écrits de façon synchrone (`commit`) avant d'exécuter la commande.
- Balise : relancée après la déconnexion d'un client GATT et quand la pile la désactive (`onAdvertisingEnabled`) ; `id_ble` journalisé en debug seulement.
- Accueil : l'état de la balise suit les rappels de la pile (émise, refusée, arrêtée), avec un nouvel essai court en cas d'échec.
- Serveur GATT : verrou court entre la réception d'une écriture et son traitement ; notifications en file, une à la fois, codes de retour journalisés.
- Chercheur : suivi en direct de l'état du Bluetooth (relance du scan au retour), relance d'un scan muet depuis 60 s ; démarrages de scan limités à 4 par 30 s glissantes (au lieu d'un écart fixe de 10 s, qui retardait le scan au retour sur l'écran).

#### Écarté

- Adresse non résolvable pour la balise : `setOwnAddressType` est réservé aux apps système (`BLUETOOTH_PRIVILEGED`, absent du SDK public). Limite documentée.
- Secret de balise distinct de S : il faudrait un second appairage téléphone à téléphone, et les 12 mots ne suffiraient plus pour Nearby. Le mode recherche borne le pistage aux moments où l'on cherche.
- Intervalle de 1 s pour la balise : sans objet tant qu'elle n'émet qu'en mode recherche ; 250 ms gardé pour la réactivité de la jauge.

## 1.0.1 (2026-10-18)
- App Android en anglais par défaut, français en traduction.

## 1.0.0 (2026-10-18)

### Ajouté

- Socle du dépôt : licence Apache-2.0, `.gitignore`, README.
- `relay/crypto.js` : identifiant, clés HKDF, commandes signées (HMAC-SHA256), états chiffrés (AES-256-GCM), 12 mots BIP39 et lien d'appairage.
- `docs/VECTEURS.json` : vecteurs de test partagés, générés par `npm run vecteurs`.
- Tests `node --test` du module de cryptographie, recoupés avec WebCrypto et les vecteurs BIP39 officiels.
- `relay/server.js` : relais HTTP et WebSocket (téléphone, contrôleurs, `/api/cmd`, `/api/presence`, `/health`), file d'attente de 15 min, limites de débit, arrêt propre.
- `web/` : page de commande sans framework (WebCrypto), installable comme PWA, plusieurs téléphones, journal.
- `relay/tools/faux-telephone.js` : faux téléphone pour essayer la page sans l'app.
- `relay/tools/controleur.js` : contrôleur en ligne de commande (envoie `ring`, `stop` ou `ping` et déchiffre les états reçus).
- App Android, version debug : adresse du relais posable par adb (récepteur `DebugRelaisReceiver` protégé par la permission `DUMP`) et lien d'appairage dans le journal (étiquette `SONNE_PAIR`), pour les tests de bout en bout.
- `docs/TEST-BOUT-EN-BOUT.md` : scénario et résultats du test sur émulateur Android 16.
- `relay/Dockerfile` et `deploy/docker-compose.yml` (Traefik, `sonne.sidali.dev`).

### Modifié (revue de sécurité et d'usage, protocole v2)

v1 et v2 ne sont pas compatibles : relais, page et app doivent être mis à jour ensemble. La page convertit seule ses téléphones enregistrés (nouvel id, clés rangées dans IndexedDB) ; l'app garde son secret.

#### Critique

- App : `targetSdk 34` au lieu de 36. À partir de 35, Android 15 et plus empêchent l'app de lever Ne pas déranger : la sonnerie restait muette en « Aucune interruption ». Vérifié sur Android 16 : le mode est levé pendant la sonnerie et rétabli à l'arrêt. `Ringer.dndPilotable` garde le code correct si ce choix change (pas d'appel, pas de restauration dangereuse, alerte sur l'accueil).
- App : Direct Boot. Service, récepteurs et écran de sonnerie `directBootAware`, démarrage sur `LOCKED_BOOT_COMPLETED`, préférences et secret en stockage chiffré par appareil (migration unique depuis l'ancien stockage), son de secours dans le cache de ce stockage. Sonne est joignable après un redémarrage automatique, avant la saisie du code.

#### Majeur

- Protocole v2 : le téléphone prouve qu'il détient S en présentant `P = HKDF(S, "sonne-phone", 16)` ; `id = SHA-256("sonne-id" ‖ P)[0:32]`, recalculé par le relais. Connaître l'id ne permet plus d'éjecter le vrai téléphone ni de prendre sa file.
- Relais : la file garde les 5 dernières `ring` et `stop` (sans doublon de nonce) au lieu de la seule dernière ; une commande livrée à une connexion qui meurt avant l'état suivant est relivrée.
- Relais : limites de connexions ouvertes (20 par IP, 10 000 au total, 16 navigateurs par téléphone).
- Relais : ping du téléphone toutes les 4 min (coupure après 10 min) au lieu de 25 s, réglables par `SONNE_PHONE_PING_S` et `SONNE_PHONE_DEAD_S` ; les navigateurs gardent 25 s et 60 s. Battement applicatif `hb`.
- App : chien de garde `AlarmManager` (toutes les 5 min, sans permission) pour le battement, l'état périodique et les reconnexions longues ; une connexion à moitié morte est détectée (pas de réponse au battement en 20 s). Plus de `pingInterval` OkHttp.
- App : le volume et Ne pas déranger sont restaurés avant la mise au premier plan, qui ne fait plus planter le service si le système la refuse.
- App : un secret illisible n'est plus remplacé en silence. Erreur passagère : nouvel essai. Perte établie (échecs définitifs répétés) : ancienne boîte gardée, alerte sur l'accueil, nouvelle clé seulement après confirmation.
- App : premier lancement guidé. Phrase d'accroche sur l'écran Autorisations, puis passage direct à l'Appairage ; carte « Étape suivante : relie un navigateur » sur l'accueil tant que l'appairage n'a pas été ouvert. Ligne Ne pas déranger masquée là où elle ne sert à rien.
- App : les 12 mots sont présentés comme le moyen de faire sonner le téléphone depuis n'importe quel navigateur, à noter sur papier ou dans un gestionnaire de mots de passe.
- Page : demande de stockage persistant et avertissement Safari (7 jours sans visite).
- README : installation (Obtainium, APK), premiers pas, limites.

#### Mineur

- L'id ne passe plus dans les URL : `Sec-WebSocket-Protocol` pour les WebSocket, en-tête `X-Sonne-Id` pour `/api`.
- Relais : limites par IP sur le préfixe /64 en IPv6, IPv4 mappées ramenées à l'IPv4 ; `SONNE_QUEUE_MIN` plafonné à 15.
- App : anti rejeu par l'âge des nonces (gardés tant qu'ils sont dans la fenêtre, 2 000 au plus, refus au-delà) ; une `ring` ou une `stop` plus ancienne que la dernière acceptée est ignorée.
- Page : états rejoués ou trop vieux (plus de 20 min) ignorés ; ajout par lien ouvert directement soumis à confirmation avec le début de l'id, nom automatique préfixé par « Nouveau : » ; S n'est plus gardé, seulement des clés WebCrypto non exportables dans IndexedDB ; indice « Pas de nouvelles du téléphone » après un jour de silence ; plus de durée de file promise en dur.
- Page : texte de l'écran vide plus clair (téléphone ou ordinateur), pied de page honnête sur le rôle du relais, contrastes AA en thème clair, cibles tactiles de 44 px.
- App : `FLAG_SECURE` sur l'écran Appairage ; hôte du lien affiché et avertissement pour un relais personnalisé ; début de l'id affiché ; `http://` refusé en release (aussi dans le setter) ; adresse de relais de debug posée par un récepteur protégé par `DUMP` au lieu d'un extra de `MainActivity`.
- App : reconnexion immédiate au retour du réseau après une perte totale ; notification de sonnerie orpheline retirée ; torche suivie par `TorchCallback` (pause puis reprise) ; repli sur le son de secours si la lecture échoue en cours de route ; WakeLock de sonnerie réarmé à chaque `ring`.
- App : vert « Accordé » lisible en thème sombre, avec une coche ; textes corrigés (« Plein écran sur l'écran verrouillé », « par-dessus », « Garde-les », « Changer la clé » au lieu de « Nouvelle chaîne »).
- Protocole : `specialUse` au lieu de `dataSync` (avec la raison), limites et messages `relayed` et `error` documentés, liste réelle des fichiers de la page, limite honnête sur l'historique du navigateur.
