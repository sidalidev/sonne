# Sonne : protocole et architecture (v2, v3)

> **v2 (octobre 2026)**, changements par rapport à v1, à la suite de la revue de sécurité :
> l'id est dérivé d'une preuve de téléphone P (le relais vérifie que c'est bien le téléphone qui se connecte),
> l'id ne passe plus jamais dans une URL, la file garde plusieurs commandes par type, battement applicatif,
> ping espacé pour le téléphone, anti rejeu par l'âge des nonces et ordre des ring/stop, app en Direct Boot
> et `targetSdk 34`. v1 et v2 ne sont pas compatibles : relais, page et app doivent être à jour ensemble.
>
> **App 1.1 (Proximité Bluetooth)** : balise BLE à identifiant tournant et commandes signées par GATT, sans Internet (section « Proximité Bluetooth »), actives seulement en **mode recherche**. Ajout pur au protocole v2 : relais et page web inchangés (le champ `pad` du clair de l'état est ignoré par la page).
>
> **App 1.2 (mise à jour intégrée)** : le relais sert `GET /update/android.json` et `GET /download/<nom>.apk` ; l'app vérifie, télécharge, contrôle la somme et la signature, puis installe avec la confirmation d'Android (section « Mise à jour »). Protocole v2 inchangé.
>
> **v3 (app 1.6, relais 1.6) : commandes chiffrées.** Les commandes ne sont plus signées en clair mais chiffrées (AES-256-GCM, clé `K_cmd` dérivée de `K_sig`, AAD liée à l'id) dans un message de taille fixe : le relais et un observateur Bluetooth ne distinguent plus `ring`, `stop`, `ping` ni les paramètres. Le téléphone annonce la capacité dans son état (`cmdv`), les émetteurs n'envoient v3 qu'aux téléphones qui l'ont annoncée, puis l'ancien format est coupé en deux étapes (section « Protocole v3 : commandes chiffrées »). Connexions, états, balise et mise à jour inchangés.

Sonne fait sonner ton téléphone Android depuis une page web, même en silencieux, sans Google, sans compte.
Trois morceaux : l'app Android (`android/`), le relais (`relay/`, Node 22, une seule dépendance : `ws`) et la page web de commande (`web/`, servie par le relais).
Hébergement de référence : `https://sonne.sidali.dev` (VPS, Traefik, Docker). Le relais est auto-hébergeable par n'importe qui.

## Principe : une chaîne de sync, pas de compte

- Au premier lancement, le téléphone tire un **secret S de 16 octets** (128 bits) avec `SecureRandom`.
- **Preuve de téléphone** `P = HKDF-SHA256(S, salt = vide, info = "sonne-phone", 16 octets)`. Le téléphone la présente au relais à chaque connexion pour prouver qu'il détient S, sans révéler S, `K_sig` ni `K_enc`.
- **Identifiant public** `id = hex(SHA-256("sonne-id" ‖ P))[0:32]` : c'est tout ce que les contrôleurs donnent au relais. Le relais le recalcule à partir de P quand le téléphone se connecte. Connaître l'id ne suffit donc plus pour se faire passer pour le téléphone.
- **Clé de signature** `K_sig = HKDF-SHA256(S, salt = vide, info = "sonne-sig", 32 octets)`.
- **Clé de chiffrement** `K_enc = HKDF-SHA256(S, salt = vide, info = "sonne-enc", 32 octets)`.
- **Clé de commande** (v3, app 1.6) `K_cmd = HKDF-Expand(K_sig, info = "sonne-cmd-v3", 32 octets)`, dérivée de `K_sig` pour que les navigateurs et « Mes autres téléphones », qui n'ont pas S, la calculent (section « Protocole v3 : commandes chiffrées »).
- Le téléphone montre S sous trois formes : un **QR code**, un **lien** `https://sonne.sidali.dev/#s=<base64url(S)>` (le fragment `#` n'est jamais envoyé au serveur) et **12 mots BIP39** (liste anglaise, 128 bits + 4 bits de somme de contrôle, exactement comme un portefeuille Bitcoin).
- Le navigateur ne garde pas S : il en dérive l'id et les clés `K_sig` et `K_enc`, les importe en clés WebCrypto **non exportables** et range celles-ci dans IndexedDB (plusieurs téléphones possibles, chacun avec un nom choisi localement). Seulement si IndexedDB est indisponible, S reste en secours dans `localStorage`.
- Révoquer un contrôleur = régénérer S sur le téléphone (« Changer la clé »).

## Messages

Tout transite en JSON texte sur WebSocket. Horodatages en millisecondes Unix.

### Commande (web → relais → téléphone), signée (v2)

> Format v2, accepté jusqu'à la coupure décrite dans « Protocole v3 : commandes chiffrées ». Depuis l'app 1.6, les émetteurs envoient la commande chiffrée v3 à tout téléphone qui annonce `cmdv ≥ 3` dans son état.

```json
{ "type": "cmd", "cmd": "ring" | "stop" | "ping", "ts": 1792300000000, "nonce": "16 octets en hex", "sig": "hex(HMAC-SHA256(K_sig, cmd + '|' + ts + '|' + nonce))" }
```

Le téléphone accepte si : `sig` valide, `|now - ts| ≤ 15 min`, `nonce` jamais vu. Sinon il ignore en silence.
- **Anti rejeu par l'âge** : un nonce est gardé (avec le `ts` de sa commande) tant que ce `ts` est dans la fenêtre de 15 min, c'est-à-dire tant qu'un rejeu serait encore accepté. Au-delà de 2 000 nonces valables à la fois, les nouvelles commandes sont refusées plutôt que d'oublier un nonce encore rejouable. (v1 gardait les 200 derniers : au-delà, un nonce encore dans la fenêtre pouvait être rejoué.)
- **Ordre** : une `ring` ou une `stop` dont `ts` est plus petit que celui de la dernière `ring` ou `stop` acceptée est ignorée (son nonce est quand même retenu). Un relais ne peut donc pas faire resonner le téléphone en livrant une vieille `ring` après une `stop`. Le `ping` n'est pas concerné.

`ring` déclenche la sonnerie (voir plus bas) ; `stop` l'arrête ; `ping` demande seulement un état.

### État (téléphone → relais → web), chiffré

```json
{ "type": "state", "nonce": "hex 12 octets", "enc": "base64(AES-256-GCM(K_enc, iv = nonce, aad = id, plaintext))" }
```

`plaintext` = `{ "ts", "ringing": bool, "battery": 0..100, "charging": bool, "app": "1.0.0", "ack": "<nonce de la commande traitée ou null>", "model": "Pixel 10 Pro", "cmdv": 3, "pad": "   …" }`. `cmdv` (app 1.6 et plus) annonce les commandes chiffrées v3 ; `ack` fait 32 hex pour une commande v2, 24 pour une v3.
Depuis l'app 1.1, `model` est tronqué à 64 caractères et le clair est **bourré à 320 octets UTF-8** par le champ `pad` (des espaces) : la longueur du chiffré ne dit rien du modèle ni de la version, et le message entier fait 508 octets, sous la limite d'un attribut GATT. Les lecteurs ignorent `pad`.
Le téléphone envoie un état à la connexion, après chaque commande, quand la sonnerie démarre ou s'arrête (depuis la 1.6, sauf quand c'est la commande qu'il vient d'accuser qui la démarre ou l'arrête : voir « Réponse : un seul état par commande »), et toutes les 15 min. L'état qui accuse une `ring` ou une `stop` porte déjà `ringing` attendu (`true` ou `false`) : il part avant le travail lourd de la sonnerie (préparation du son, volume, Ne pas déranger, écran), qui suit aussitôt.
Le relais ne peut pas le lire. La page web vérifie l'AAD (= id) et affiche : en ligne, batterie, sonne ou non, accusé de réception.
**Fraîcheur** : la page garde le plus grand `ts` reçu par téléphone et ignore tout état dont `ts` est inférieur ou égal, ou plus vieux que 20 min (le relais pourrait rejouer d'anciens états chiffrés). Un `ack` n'est pris en compte que dans un état frais.

### Relais

L'id ne passe **jamais dans une URL** : les journaux d'accès d'un proxy (Traefik, nginx, CDN) écrivent l'URL complète, et l'id est exactement ce qu'il faut pour viser un téléphone.

- `GET /` : la page web de commande (statique, `web/`).
- `GET /ws/phone` : WebSocket du téléphone. En-tête `Sec-WebSocket-Protocol: sonne.v2, sonne.p.<hex(P)>`. Le relais calcule `id` depuis P, refuse en 400 toute connexion sans P valide, puis répond avec le sous-protocole `sonne.v2`. Il n'éjecte l'ancienne connexion du même id (code 4000, « le plus récent gagne ») et ne livre la file qu'à un client qui a présenté P.
  À l'ouverture : `{"type":"welcome","queued":[...commandes en attente]}`, puis il relaie.
- `GET /ws/ctrl` : WebSocket des contrôleurs (16 au plus par id). En-tête `Sec-WebSocket-Protocol: sonne.v2, sonne.id.<id>` (le navigateur le pose avec `new WebSocket(url, ["sonne.v2", "sonne.id." + id])`). Ils envoient des `cmd`, reçoivent les `state` et `{"type":"presence","online":bool,"lastSeen":ms}` à la connexion et à chaque changement.
  Le relais répond au seul contrôleur qui a envoyé la commande (jamais au téléphone) : `{"type":"relayed","nonce","delivered":bool,"queued"?:bool}`, ou `{"type":"error","nonce","status","error"}` en cas de refus (400 commande mal formée, 429 débit).
- `POST /api/cmd` avec l'en-tête `X-Sonne-Id: <id>` et une `cmd` en corps : pour les scripts et les raccourcis. Répond `{"delivered":true}` si le téléphone est connecté ; sinon `{"delivered":false,"queued":true}` pour une `ring` ou une `stop`, et `{"delivered":false,"queued":false}` pour un `ping` (jamais mis en file).
- `GET /api/presence` avec l'en-tête `X-Sonne-Id: <id>` : `{ "online": bool, "lastSeen": ms }`.
- **Ping par rôle** : contrôleurs (navigateurs au premier plan) pingués toutes les 25 s, coupés après 60 s sans pong ni message ; téléphone pingué toutes les 4 min (`SONNE_PHONE_PING_S`, 240), coupé après 10 min (`SONNE_PHONE_DEAD_S`, 600). Chaque ping réveille le processeur et la radio du téléphone : 25 s en faisait le premier poste de consommation de l'app.
- **Battement applicatif** : le téléphone envoie `{"type":"hb"}`, le relais répond `{"type":"hb"}` (ni relayé, ni compté comme erreur). OkHttp ne montre pas les pongs à l'app : sans réponse au battement en 20 s, le téléphone tient la connexion pour morte (NAT expiré, relais tué sans FIN) et se reconnecte.
- **File d'attente** : si le téléphone est hors ligne, le relais garde les **5 dernières `ring`** et les 5 dernières `stop` (sans doublon de nonce) pendant 15 min au plus, livrées à la reconnexion dans l'ordre de réception. Plusieurs par type : quelqu'un qui connaît l'id ne peut plus remplacer la bonne `ring` par une `ring` mal signée ; le téléphone trie. Commandes v3 (types invisibles) : les 10 dernières, toutes mises en file et suivies en vol, voir « Ce qui change pour le relais ».
- **Commandes en vol** : une `ring` ou une `stop` livrée au téléphone reste notée jusqu'au prochain `state` du téléphone. Si la connexion meurt avant (ou est remplacée par une nouvelle connexion du même téléphone), elle repart en file et sera relivrée ; le téléphone ignore un nonce déjà vu.
- **Limites** : 20 commandes par minute et par `id` ; 60 requêtes par minute et par IP (commandes, ouvertures de WebSocket, `/api/presence`) ; 20 connexions WebSocket ouvertes en même temps par IP et 10 000 au total (429 ou 503 au-delà) ; 4 Ko par message ; `id` = 32 hex obligatoires. Les limites par IP ramènent une IPv6 à son préfixe /64 (un client dispose d'un /64 entier) et une IPv4 mappée `::ffff:a.b.c.d` à l'IPv4. Derrière un proxy de confiance (`SONNE_TRUST_PROXY=1`), l'adresse est la dernière entrée de `X-Forwarded-For`.
- Rien n'est écrit sur disque. Journal : seulement des compteurs et des erreurs, jamais d'`id` complet (6 premiers caractères).
- `GET /health` : `{ ok, phones, ctrls, uptime, version }`.
- Variables : `PORT` (7700), `SONNE_QUEUE_MIN` (15, plafonné à 15 : au-delà le téléphone refuserait la commande, hors de sa fenêtre de fraîcheur), `SONNE_TRUST_PROXY` (0), `SONNE_WEB_DIR` (../web), `SONNE_PHONE_PING_S` (240), `SONNE_PHONE_DEAD_S` (600), `SONNE_CMD_V2` (1 en relais 1.6, 0 à partir de la coupure : voir « Protocole v3 »).
- Proxy : le ping du téléphone suppose qu'une connexion WebSocket inactive 4 min n'est pas coupée en route. Traefik v2 n'a pas de délai de lecture par défaut ; Traefik v3 a `respondingTimeouts.readTimeout` à 60 s : le vérifier sur l'entrypoint, et mesurer l'expiration NAT réelle (opérateur mobile, box) avant de rallonger. Au besoin, baisser `SONNE_PHONE_PING_S`.

## Protocole v3 : commandes chiffrées (app 1.6, relais 1.6)

### Pourquoi

En v2, une commande est **signée mais en clair** : le relais lit `cmd` (`ring`, `stop` ou `ping`) et `ts` (l'horloge de l'émetteur), et se comporte différemment selon le type (file d'attente pour `ring` et `stop` seulement, réponse `queued:false` pour un `ping`). Un observateur Bluetooth à portée lit la même chose dans l'écriture GATT. En v3, le relais et l'observateur ne voient plus qu'un **message chiffré de taille fixe** (172 octets) : ni le type (`ring`, `stop`, `ping`, la vérification d'état ou « check »), ni l'horodatage, ni les paramètres de la sonnerie (durée, lampe). L'authentification (AEAD liée à l'id), l'anti rejeu (nonce, fenêtre de 15 min, ordre des `ring` et `stop`) et l'accusé (`ack` = nonce) sont gardés. La signature HMAC de v2 n'existe plus en v3 : l'étiquette GCM la remplace.

### Clé de commande

```
K_cmd = HKDF-Expand(PRK = K_sig, info = "sonne-cmd-v3", L = 32)
      = HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01)
```

- Dérivée de `K_sig` et non de S : les navigateurs et « Mes autres téléphones » n'ont pas S, seulement les clés dérivées. La page la calcule avec sa clé HMAC non exportable (`crypto.subtle.sign("HMAC", cleSig, "sonne-cmd-v3" ‖ 0x01)`), l'importe aussitôt en clé AES-GCM **non exportable** (`["encrypt"]`), met les octets à zéro et range la `CryptoKey` dans IndexedDB avec `cleSig` et `cleEnc` (ou la recalcule à chaque chargement). Le chercheur Android la calcule depuis `kSig`. Aucun nouvel appairage n'est nécessaire.
- HKDF-Expand seul (RFC 5869, section 2.3), sans Extract : `K_sig` est déjà une clé uniforme de 32 octets (sortie de HKDF), cas prévu par la RFC 5869, section 3.3. `relay/crypto.js` (`hkdfExpand`) est vérifié sur le cas de test 1 de la RFC.
- Séparation des domaines : `K_sig` ne signe jamais que des textes `cmd|ts|nonce` avec `cmd` parmi `ring`, `stop`, `ping`, qui ne peuvent pas valoir `"sonne-cmd-v3" ‖ 0x01` : aucune signature v2 publiée ne révèle `K_cmd`. `K_cmd` est distincte de `K_enc` (états) et de B (balise).
- « Changer la clé » change S, donc `K_sig` et `K_cmd`.

### Clair : 64 octets

| Octets | Champ | Valeur |
|---|---|---|
| 0 | version | `0x03` |
| 1 | commande | `0x01` ring, `0x02` stop, `0x03` ping (vérification d'état) |
| 2 à 9 | ts | millisecondes Unix, entier **non signé** sur 8 octets, big-endian |
| 10 et 11 | durée | secondes, big-endian. `0` = réglage du téléphone, sinon 60 à 600 |
| 12 | lampe | `0` = réglage du téléphone, `1` = allumée, `2` = éteinte |
| 13 à 63 | réservé | zéros à l'envoi, **ignorés** à la réception (place pour des paramètres facultatifs qu'un téléphone plus ancien ignorera) |

- **Bourrage** : le clair fait toujours 64 octets, quel que soit le type et les paramètres. Le message fait donc toujours 172 octets.
- **Paramètres** : seule une `ring` en porte. L'émetteur met `0` et `0` tant que l'utilisateur n'a rien choisi (pas d'interface en 1.6 : toujours `0`). Pour `stop` et `ping`, l'émetteur met `0` et le téléphone les ignore.
- **Application par le téléphone** (`parametresSonnerie` dans `relay/crypto.js`) : une durée de 60 à 600 s remplace, pour cette sonnerie seulement, l'arrêt automatique réglé ; toute autre valeur (0, ou hors bornes) laisse le réglage. Lampe `1` allume la torche pour cette sonnerie même si le réglage est éteint, `2` la laisse éteinte, toute autre valeur laisse le réglage. Une valeur inattendue ne refuse jamais la commande : un émetteur maladroit ne doit pas empêcher la sonnerie. Une `ring` reçue pendant une sonnerie relance l'arrêt automatique, comme aujourd'hui, avec sa propre durée (ou le réglage) ; elle ne change pas la lampe de la sonnerie en cours.

### Chiffrement

```
nonce = 12 octets aléatoires (SecureRandom, crypto.getRandomValues, randomBytes)
aad   = UTF-8("sonne-cmd-v3|" + id)          45 octets ASCII, id du téléphone visé
enc   = base64( AES-256-GCM(K_cmd, iv = nonce, aad, clair 64 octets) )   chiffré ‖ étiquette de 16 octets
```

- `enc` est en **base64 standard** (alphabet `+` et `/`, remplissage `=`) : 80 octets, donc **108 caractères** dont un seul `=` final.
- Le nonce sert à la fois d'IV GCM, d'identifiant d'anti rejeu et de valeur de l'`ack` (24 caractères hex minuscules). Il est authentifié (un nonce changé fait échouer l'étiquette). Beaucoup d'émetteurs partagent `K_cmd` : avec des IV aléatoires de 96 bits, la probabilité d'en réutiliser un est d'environ n²/2^97 pour n commandes (2^-49 pour 16 millions), loin sous la limite de 2^32 messages par clé du NIST SP 800-38D.
- L'AAD lie la commande au téléphone visé : une commande chiffrée pour une autre chaîne, ou avec `K_enc`, échoue.

### Message

Toujours ces **172 octets**, écrits à la main, sans espace, dans cet ordre :

```json
{"type":"cmd","v":3,"nonce":"<24 hex minuscules>","enc":"<108 caractères base64>"}
```

Pas de sérialiseur JSON qui échappe `/` en `\/` (c'est le cas de `JSONObject` sous Android) : la taille varierait avec le contenu du base64. Le même message passe par le WebSocket des contrôleurs, `POST /api/cmd`, la file `welcome` du relais et l'écriture GATT. Le relais le recopie tel quel (copie propre `{type, v, nonce, enc}`, sérialisée sans échappement par Node : toujours 172 octets).

### Acceptation par le téléphone

Dans cet ordre (raison entre guillemets, celle des cas de `docs/VECTEURS.json`) ; tout refus est silencieux, comme en v2 :

1. **forme** : objet JSON, `type` = `"cmd"`, `v` = le nombre `3`, `nonce` = 24 hex **minuscules**, `enc` = chaîne base64 standard. Sinon « forme ». Un message sans `v` est une commande v2 (règles v2, ou « ancien-format » après la coupure) ; toute autre valeur de `v` est « forme ».
2. **taille** : `enc` fait 108 caractères et se décode en exactement 80 octets. Sinon « taille ». Un clair de 63 ou 65 octets correctement chiffré est donc refusé.
3. **authentification** : AES-256-GCM avec `K_cmd`, iv = nonce, AAD = `"sonne-cmd-v3|"` + l'id **du téléphone**. Échec : « authentification » (autre chaîne, mauvaise clé, `K_enc` au lieu de `K_cmd`, octet ou nonce altéré).
4. **version** : octet 0 = `0x03`, sinon « version ».
5. **commande** : octet 1 parmi `0x01`, `0x02`, `0x03`, sinon « commande ».
6. **fraîcheur** : ts lu comme entier non signé de 64 bits. Au-delà de 2^53 - 1 (en particulier bit de poids fort à 1, **négatif dans un `Long` Kotlin** : à tester avant tout calcul, `abs(maintenant - ts)` déborderait), « fraicheur ». Puis `|maintenant - ts| > 15 min` : « fraicheur ».
7. **rejeu** : les nonces dont le ts est hors de la fenêtre sont oubliés, puis un nonce déjà vu est « rejeu ».
8. **capacité** : 2 000 nonces valables à la fois au plus, sinon « capacite ».
9. Le nonce est retenu (avec le ts du clair) ; une `ring` ou une `stop` dont le ts est plus petit que celui de la dernière `ring` ou `stop` acceptée est « ordre » (nonce gardé).
10. Exécution, puis accusé.

- **Une seule mémoire** de nonces pour v2 et v3, pour le relais et le Bluetooth (les nonces v2 font 32 hex, ceux de v3 24 : jamais de collision), et un seul `dernierTsOrdre`.
- **Écriture GATT** : les étapes 1 à 7 sont faites sans rien modifier (`recevable` dans `relay/crypto.js`), avant la limite de débit, comme en v2 : un message capté sur les ondes et rejoué compte comme une écriture invalide.
- Référence exécutable : `creerFiltreCommandes` dans `relay/crypto.js` (`accepter`, `recevable`). Les 19 cas du bloc `commandes_v3.cas` de `docs/VECTEURS.json` (acceptées, ancien format avant et après la coupure, mauvais id, mauvaise clé, `K_enc`, octet altéré, rejeu, trop vieux, futur, ordre, tailles, version, commande inconnue, ts au bit de poids fort, nonce en majuscules) doivent donner la même réponse partout.

### Réponse : un seul état par commande

L'accusé reste l'état chiffré habituel, avec `ack` = nonce de la commande (24 hex en v3) et `ringing` déjà à la valeur attendue. Nouveau en 1.6, pour toutes les commandes (v2 et v3) : **une commande acceptée produit exactement un état**, l'accusé. Le changement de sonnerie que cette commande provoque (démarrage après une `ring`, arrêt après une `stop`) n'envoie pas de second état, ni au relais ni en notification GATT : l'accusé porte déjà la bonne valeur. Sans cela, le relais compterait deux états après une `ring` qui fait sonner ou une `stop` qui arrête, un seul après un `ping`. Les changements qui ne viennent pas d'une commande (arrêt automatique en fin de durée, bouton Stop du téléphone, test local) envoient toujours leur état.

### Annonce de la capacité

Le clair de l'état gagne le champ `"cmdv":3`, après `model` et avant `pad` : « ce téléphone accepte les commandes v3 ». Absent (app 1.5 et avant) : v2 seulement. Le clair reste bourré à 320 octets (message de 508 octets). Vecteur : `commandes_v3.etat_v3`.

Les émetteurs (page, chercheur Android) lisent `cmdv` dans tout état **frais et authentifié** (par le relais ou en notification GATT) et retiennent, par téléphone, `cmdv ≥ 3` **pour toujours** (stockage local de la page, liste chiffrée de « Mes autres téléphones ») : on ne repasse jamais en v2 vers ce téléphone, même si un état plus tard n'a pas le champ. Le relais ne peut pas rejouer un vieil état pour faire redescendre (règle de fraîcheur), et la mémoire tient après un rechargement de la page.

### Ce que voit le relais, avant et après

| | v2 (app 1.5 et avant) | v3 (app 1.6) |
|---|---|---|
| Type de commande | en clair (`cmd`) | non |
| Durée, lampe | n'existent pas | chiffrées |
| Horloge de l'émetteur | en clair (`ts`) | chiffrée |
| Signature | HMAC visible (sans valeur pour lui) | aucune, étiquette GCM dans `enc` |
| Nonce | 16 octets aléatoires | 12 octets aléatoires |
| Taille | 165 octets au plus (ne dépend pas du type) | 172 octets, toujours |
| Mise en file, téléphone hors ligne | `ring` et `stop` (5 par type), jamais `ping` | toutes, 10 au plus |
| Réponse à l'émetteur | `queued:false` pour un `ping` | identique pour toutes |
| Commandes en vol | `ring` et `stop` | toutes |
| États qui suivent | deux après une `ring` qui fait sonner ou une `stop` qui arrête, un sinon | un, toujours |

Toujours visible : l'id, les tailles, les instants, les adresses IP, la présence du téléphone. **Reste devinable par le trafic** : une sonnerie qui s'arrête seule envoie un état à la fin de sa durée (3 min par défaut, ou la durée demandée par la commande, que le relais peut donc mesurer), comme quand quelqu'un appuie sur Stop sur le téléphone : un relais qui observe les instants peut supposer qu'une `ring` a précédé. La vérification automatique de la page quand le téléphone passe en ligne, et celle que l'écran À proximité répète toutes les 8 min, se reconnaissent à leur moment. Le mode recherche Bluetooth s'ouvre après toute commande acceptée, quel que soit son type (inchangé).

**Relais actif : oracle de l'ordre.** Un relais qui retient une commande A et livre d'abord une commande B plus récente apprend, selon qu'un état suit A ou non, si A et B étaient toutes deux des `ring` ou des `stop` : le téléphone refuse une `ring` ou une `stop` plus ancienne que la dernière acceptée (« ordre »), jamais un `ping`. A est perdue (pas d'accusé, l'émetteur le voit). Accepté : soumettre aussi le `ping` à l'ordre ferait refuser une `ring` après le `ping` d'un appareil dont l'horloge avance.

### Ce qui change pour le relais

Aujourd'hui (v2), le relais lit `cmd` pour : la validation de forme (liste `COMMANDES`), la file d'attente (`ring` et `stop` seulement, 5 par type), les commandes en vol (`ring` et `stop` seulement) et la réponse (`queued:false` pour un `ping`). Il ne route rien selon le type ; ses limites de débit (par id, par IP, connexions) ne dépendent pas du type ; son journal n'a que des compteurs, jamais le type ni la commande.

En v3 (relais 1.6) :

- **Forme** : une commande v3 est validée par `formeCommandeV3` de `relay/crypto.js` (champs, nonce de 24 hex minuscules, `enc` de 108 caractères qui se décode en 80 octets) et recopiée propre `{type, v, nonce, enc}`. Une commande v2 n'est acceptée que si `SONNE_CMD_V2` vaut `1` (voir Transition) ; sinon 400 `{"error":"old command format: reload the page or update the app"}`.
- **File** : une liste à part pour v3, les **10 dernières** (`FILE_V3`), sans doublon de nonce, 15 min au plus, livrées avec celles de v2 dans l'ordre de réception. Comme en v2, quelqu'un qui connaît l'id peut pousser 10 messages faux et faire sortir une bonne `ring` de la file ; le relais ne peut pas mieux faire sans `K_cmd`, le téléphone trie.
- **En vol** : toutes les commandes v3 livrées restent notées jusqu'au prochain état du téléphone, et repartent en file si la connexion meurt avant.
- **Réponse** : `{"delivered":true}`, ou `{"delivered":false,"queued":true}` pour **toute** commande v3 quand le téléphone est hors ligne. L'émetteur sait ce qu'il a envoyé. Les émetteurs n'envoient plus de vérification automatique quand la présence dit le téléphone hors ligne (elle serait mise en file).
- **Retour au contrôleur** : `relayed` et `error` reprennent le `nonce` de la commande, 24 ou 32 hex.
- **Limites** et taille maximale (4 Ko) : inchangées.
- **Journal** : deux compteurs de plus, `cmdV2` et `cmdV3` (le format, visible de toute façon, jamais le type) : c'est ce qui dit quand on peut couper l'ancien format.
- **Variable** `SONNE_CMD_V2` : `1` par défaut pendant la transition (relais 1.6), `0` par défaut après la coupure (relais 1.7).

### Bluetooth (GATT)

- La caractéristique Commande reçoit le même message de 172 octets. Il tient dans un attribut (512 octets) ; la négociation du MTU (517 demandé, 515 au moins) ne change pas.
- Transition : écritures jusqu'à 512 octets, comme aujourd'hui (v2 : 165 au plus, v3 : 172). Après la coupure : **exactement 172 octets**, sinon `GATT_INVALID_ATTRIBUTE_LENGTH` (`0x0d`).
- L'état notifié reste de 508 octets, un seul par commande. Un observateur à portée voit une écriture de 172 octets puis une notification de 508, quelle que soit la commande.

### Transition, puis coupure de l'ancien format

Les numéros sont ceux des versions de Sonne (`CHANGELOG.md`) : « relais et page 1.6 » désigne le relais et la page du dépôt publiés avec Sonne 1.6.0. Le numéro propre du relais (`relay/package.json`, renvoyé par `/health`, 1.1.0 avant la 1.6) monte aussi : 1.2.0 pour l'étape 1, 1.3.0 pour l'étape 3.

La page déployée sur sonne.sidali.dev et l'app installée ne sont pas mises à jour en même temps. La sonnerie ne doit jamais casser : aucun émetteur n'envoie v3 à un téléphone qui ne l'a pas annoncé, et aucun téléphone ne refuse v2 tant qu'un émetteur peut encore en envoyer.

**Étape 1 : relais et page 1.6** (un seul déploiement, le relais sert la page). Le relais accepte v2 et v3 (`SONNE_CMD_V2=1`). La page envoie v3 à un téléphone dont elle a vu `cmdv ≥ 3`, v2 sinon. Si le relais répond 400 à une commande v2 (relais déjà coupé), elle renvoie la même intention en v3, avec un nouveau nonce (montée de version, jamais l'inverse ; v2 refusée par le relais n'a pas été livrée).

**Étape 2 : app 1.6.0**, publiée seulement après l'étape 1 (son chercheur enverrait du v3 à un relais qui le refuse ; un relais auto-hébergé se met à jour avant ses téléphones). Le téléphone accepte v2 et v3, annonce `cmdv:3`, envoie un seul état par commande. Le chercheur envoie v3 aux chaînes qui ont annoncé `cmdv ≥ 3` (vu dans un état reçu par GATT, ou par le relais s'il l'écoute), v2 sinon, avec la même règle du 400.

Pendant la transition : un téléphone en 1.5 continue de recevoir du v2 et sonne comme avant ; un téléphone en 1.6 reçoit du v3 dès que l'émetteur a vu un de ses états (la toute première commande d'un nouveau navigateur peut encore partir en v2). Un relais malveillant qui cache les états d'un téléphone garde un nouveau navigateur en v2, c'est-à-dire dans la situation d'aujourd'hui ; une fois la capacité vue, plus de retour en arrière. Le chercheur Android n'écoute pas les états du relais (il envoie par `POST /api/cmd`) : vers un téléphone qu'il n'a jamais atteint par Bluetooth, toutes ses commandes partent en v2 jusqu'à la coupure, réveil automatique compris.

**Coupure**, quand ces trois conditions sont réunies :

- tous les téléphones qu'on veut pouvoir faire sonner sont en 1.6.0 ou plus (la page affiche la version sous chaque téléphone ; le chercheur la voit après un échange Bluetooth) ;
- le compteur `cmdV2` du journal du relais est resté à 0 pendant 14 jours ;
- 30 jours au moins après la publication de l'app 1.6.0 (mise à jour intégrée, vérifiée chaque jour).

Une fois la coupure faite, un téléphone resté en 1.5 ne peut plus sonner depuis la page : c'est le prix de la coupure, d'où la première condition.

**Étape 3 : relais et page 1.7.** La page envoie v3 à tous les téléphones, sans regarder `cmdv`. Le relais refuse v2 (`SONNE_CMD_V2=0` par défaut). En cas d'oubli, remettre `1` laisse de nouveau passer le v2 des chercheurs encore en 1.6, par exemple vers un téléphone resté en 1.5 ; la page 1.7, elle, n'envoie plus que du v3.

**Étape 4 : app 1.8.0** (la 1.7.0 n'a changé que l'apparence), jamais avant l'étape 3 (une page encore en transition pourrait envoyer du v2 à un téléphone dont elle n'a pas vu d'état). Le téléphone refuse v2 en silence par le relais et comme écriture invalide par GATT, n'accepte en GATT que des écritures de 172 octets ; le chercheur n'envoie que du v3 ; le code v2 (signature des commandes, `sig`, `verifyCmd`) est retiré des trois implémentations. `K_sig` ne sert plus qu'à dériver `K_cmd`.

### Constantes

| Nom | Valeur |
|---|---|
| libellé HKDF de `K_cmd` | `sonne-cmd-v3` |
| préfixe d'AAD | `sonne-cmd-v3\|` |
| `v` du message, octet 0 du clair | 3 |
| codes de commande | 1 ring, 2 stop, 3 ping |
| clair | 64 octets |
| `enc` décodé | 80 octets (108 caractères base64) |
| nonce | 12 octets (24 hex minuscules) |
| message | 172 octets |
| fenêtre | 15 min (900 000 ms), bornes comprises |
| mémoire des nonces | 2 000 |
| durée | 0 ou 60 à 600 s |
| lampe | 0 réglage, 1 allumée, 2 éteinte |
| champ de capacité de l'état | `"cmdv":3` |
| file v3 du relais | 10 commandes, 15 min |

Vecteurs : bloc `commandes_v3` de `docs/VECTEURS.json` (`K_cmd`, `aad_v3`, `v3_ring`, `v3_stop`, `v3_ping`, `v3_ring_parametres` avec leur clair, message et texte exact, `etat_v3`, et les cas), produits par `relay/scripts/genere-vecteurs.js` à partir de `relay/crypto.js`. Le bloc est en dernier et ses noms de clés sont propres : le test JVM le lit avec `champ(nom, apres = "<nom du cas>")`.

## Sonnerie : « même en silencieux »

1. **Canal alarme** : `MediaPlayer` avec `AudioAttributes` `USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION`, en boucle, sur la sonnerie d'alarme par défaut (`RingtoneManager.TYPE_ALARM`), avec repli sur un son embarqué (généré, pas de fichier sous licence douteuse).
2. **Volume** : mémoriser le volume de `STREAM_ALARM`, le mettre au maximum, le restaurer à l'arrêt.
3. **Ne pas déranger** : demander l'accès `ACCESS_NOTIFICATION_POLICY` à l'installation (écran de permissions). Si le DND est actif au moment de sonner, passer `INTERRUPTION_FILTER_ALL` et restaurer après (seulement si le filtre courant est encore celui que Sonne a posé).
   Depuis Android 15, une app qui vise `targetSdk 35` ou plus **ne peut plus modifier le mode global** (compat change `MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES`) : l'appel ne pilote qu'une règle propre à l'app, la sonnerie resterait muette en « Aucune interruption », et la restauration allumerait un mode inconnu de l'utilisateur. D'où `targetSdk 34` (voir App Android). Si ce choix change, le code n'appelle plus `setInterruptionFilter`, masque la ligne Ne pas déranger des autorisations et avertit sur l'accueil quand le mode actif coupe les alarmes.
4. **Vibration** continue (`VibratorManager`, usage alarme) et **lampe torche** qui clignote (`CameraManager.setTorchMode`), jusqu'à l'arrêt.
5. **Écran** : notification de catégorie `CATEGORY_ALARM` avec `fullScreenIntent` (`USE_FULL_SCREEN_INTENT`), activité au-dessus du verrouillage (`setShowWhenLocked`, `setTurnScreenOn`) avec un seul gros bouton **Stop**. `WakeLock` pendant la sonnerie.
6. **Arrêt automatique** après 3 min (réglable 1 à 10). Le bouton Stop et la commande `stop` arrêtent tout et restaurent volume et DND.
7. **Test local** : un bouton « Tester la sonnerie » dans l'app, qui passe exactement par le même chemin.

## Veille : rester joignable sans Google

- Service de premier plan (`foregroundServiceType="specialUse"`, permission `FOREGROUND_SERVICE_SPECIAL_USE`, sous-type décrit par `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, notification discrète « Sonne veille ») qui maintient un WebSocket OkHttp vers `wss://sonne.sidali.dev/ws/phone` (preuve P dans `Sec-WebSocket-Protocol`).
  Pas `dataSync` (v1 du protocole) : sur Android 15 et plus, un service `dataSync` est limité à 6 h par 24 h (puis `onTimeout`, et plantage s'il n'est pas arrêté) et ne peut pas être lancé depuis `BOOT_COMPLETED`. `specialUse` n'a ni l'un ni l'autre et ne demande aucune revue hors Play. `connectedDevice` ne conviendrait pas (il exige une permission Bluetooth, NFC, USB ou réseau d'appareil connecté).
- Reconnexion avec délai croissant (1 s → 60 s max, plus un peu d'aléa), relance immédiate sur changement de réseau et au retour du réseau après une perte totale (`ConnectivityManager.NetworkCallback`), au démarrage (`LOCKED_BOOT_COMPLETED` puis `BOOT_COMPLETED`) et après mise à jour de l'app.
- **Direct Boot** : le service, les récepteurs et l'écran de sonnerie sont `directBootAware`, les préférences et le secret vivent dans le stockage chiffré par appareil (migration unique depuis l'ancien stockage au premier lancement déverrouillé), la clé du Keystore n'a pas `setUnlockedDeviceRequired`. Après un redémarrage automatique (GrapheneOS au bout de 18 h verrouillé, Android au bout de 72 h), Sonne est joignable avant que l'utilisateur ait saisi son code.
- **Minuteurs** : un `Handler` s'arrête quand le processeur dort (un service de premier plan ne l'empêche pas). Un chien de garde `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` (sans permission) passe toutes les 5 min environ sous un WakeLock de 30 s au plus, relâché dès la réponse attendue (réponse au battement, accueil du relais, échec de connexion) ou tout de suite s'il n'y a rien à attendre : battement applicatif, reconnexion si besoin, état périodique de 15 min, fin du mode recherche. Les reconnexions de plus de 5 s passent aussi par cette alarme ; les plus courtes par un `Handler` sous un WakeLock. Pas de `pingInterval` OkHttp.
- **Secret** : un S présent mais illisible n'est jamais remplacé en silence (ce serait révoquer tous les navigateurs sans prévenir). Erreur passagère : on réessaie. Échecs définitifs répétés (étiquette GCM refusée, clé du Keystore invalidée ou absente) : l'ancienne boîte est gardée, l'accueil prévient, et une nouvelle clé n'est créée qu'avec l'accord de l'utilisateur.
- Demander l'exclusion de l'optimisation de batterie (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) avec explication ; vérifier et rappeler dans l'écran d'état si refusée.
- `WakeLock` partiel seulement pendant le traitement d'une commande.
- Pas de UnifiedPush en v1 (noté comme évolution).

## Proximité Bluetooth (v1.1)

Scénario : « il n'est pas loin, je m'en rapproche, et je le fais sonner ». Le téléphone protégé émet une balise Bluetooth Low Energy (BLE) que seuls les appareils qui connaissent S reconnaissent, et accepte les commandes signées directement par Bluetooth, sans Internet. Le chercheur est l'app Sonne installée sur un autre téléphone. Rien ne change pour le relais ni pour la page web : c'est un second chemin pour les mêmes messages.

### Mode recherche

Balise et serveur GATT ne tournent **qu'en mode recherche**. Hors de ce mode, le téléphone n'émet rien et personne ne peut s'y connecter (une balise connectable en permanence laisserait n'importe qui à portée lire son nom Bluetooth, voir Limites assumées).

- **Ouverture** : pour 10 min après chaque commande acceptée (`ring`, `stop` ou `ping`, par le relais ou par Bluetooth ; chaque commande prolonge le mode), tant que le téléphone sonne, et quand le relais est injoignable depuis **une heure** (téléphone hors ligne, peut-être perdu hors de toute couverture). Le relais rejoint, ce dernier cas se referme.
- **Demande par le chercheur** : l'écran À proximité envoie par le relais un `ping` (silencieux ; chiffré en v3 depuis l'app 1.6 pour un téléphone qui l'a annoncé) à l'ouverture, puis toutes les 8 min tant qu'il reste ouvert, seulement si `GET /api/presence` dit le téléphone en ligne (depuis la 1.6 : une v3 serait sinon mise en file). La page web envoie déjà un `ping` silencieux quand le téléphone passe en ligne : l'ouvrir ouvre aussi le mode recherche.
- **Fermeture** : à l'échéance, par un `Handler` (processeur éveillé) et par le chien de garde (processeur endormi), le premier qui passe.
- La notification de veille affiche « Recherche Bluetooth active » pendant le mode : le propriétaire voit quand la balise est ouverte. Le réglage « Balise Bluetooth » désactive tout.

### Identifiant tournant

- **Clé de balise** `B = HKDF-SHA256(S, salt = vide, info = "sonne-ble", 32 octets)`.
- **Fenêtre** `w = floor(now_ms / 900000)` (15 min, horloge murale Unix en millisecondes).
- **Identifiant** `id_ble = HMAC-SHA256(B, w en 8 octets big-endian)[0:8]`.
- Il change toutes les 15 min, aux frontières de fenêtre (heure pile, et 15, 30, 45). Sans S, on ne peut ni le prévoir ni relier deux fenêtres entre elles **par id_ble**. D'autres signaux peuvent le faire pendant le mode recherche (nom Bluetooth lisible après connexion, adresse résolvable par les appareils appairés) : voir Limites assumées. « Changer la clé » change B : les anciens chercheurs ne reconnaissent plus la balise.
- Vecteurs : bloc `ble` de `docs/VECTEURS.json` (S = `000102…0f`, w = 1991444, avec les fenêtres voisines), produits par `relay/crypto.js` (`deriveCleBle`, `deriveBleId`, `fenetreBle`).

### Balise (téléphone protégé)

- **UUID de service Sonne** (128 bits, fixe) : `2af8c8b3-3a13-489b-a3a3-ac220005799b`.
- **Données de service** : 9 octets, `id_ble` (8 octets) puis l'octet de **version** `0x01`. Une version inconnue est ignorée par le chercheur.
- **Paquet** : publicité classique (legacy, `ADV_IND`, connectable, 31 octets au plus), une seule structure AD « Service Data, UUID 128 bits » (type `0x21`) : 1 octet de longueur, 1 de type, 16 d'UUID (petit boutiste sur les ondes, comme toujours en BLE), 9 de données = 27 octets, plus les 3 octets de drapeaux ajoutés par Android pour une publicité connectable = 30 octets. Donc **ni nom d'appareil, ni puissance d'émission, ni liste d'UUID de service** (le paquet dépasserait 31 octets). Réponse au scan vide.
- **Émission** : `BluetoothLeAdvertiser.startAdvertisingSet`, `setLegacyMode(true)`, `setConnectable(true)`, `setScannable(true)` (obligatoire pour une publicité legacy connectable), intervalle `INTERVAL_MEDIUM` (400 × 0,625 ms = **250 ms**, l'équivalent de `ADVERTISE_MODE_BALANCED`), puissance `TX_POWER_LOW` (**-15 dBm** environ). Adresse : celle que choisit la pile, une **adresse privée résolvable** (RPA, dérivée de l'IRK du téléphone). Une adresse non résolvable demanderait `AdvertisingSetParameters.Builder.setOwnAddressType`, réservé aux apps système (`BLUETOOTH_PRIVILEGED`, absent du SDK public).
- **Rotation** : à chaque changement de w, le téléphone **arrête l'ensemble et en démarre un nouveau** (pas un simple `setAdvertisingData`) : la pile tire alors une nouvelle adresse, qui change donc avec `id_ble` à la frontière. L'adresse peut **aussi** changer seule en cours de fenêtre (minuterie de la RPA, 7 à 15 min selon la pile ; sur un contrôleur sans publicité étendue, une publicité legacy prend l'adresse commune de l'hôte, qui tourne sur sa propre minuterie) : cela ne relie rien, `id_ble` ne change pas en même temps. Le changement est planifié par `AlarmManager.setAndAllowWhileIdle` à la frontière (verrou relâché dès la rotation faite), et revérifié à chaque passage du chien de garde (5 min environ). Un retard de quelques minutes en veille profonde est sans gravité : le chercheur accepte la fenêtre précédente.
- **Quand** : depuis le service de veille, **en mode recherche**, tant que le réglage « Balise Bluetooth » est actif (par défaut), que le Bluetooth est allumé, que `BLUETOOTH_ADVERTISE` et `BLUETOOTH_CONNECT` sont accordées, que S est lisible, et **une fois le service GATT publié** (sinon un chercheur verrait la balise sans trouver le service). Reprise sur `BluetoothAdapter.ACTION_STATE_CHANGED` (passage à `STATE_ON`). Fonctionne en Direct Boot (S est dans le stockage chiffré par appareil). L'état affiché (« émet ») suit les rappels de la pile : démarrage réussi ou refusé, arrêt, désactivation. Échec (`ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`, erreur interne) : nouvel essai à 1, 2, 5, 10 puis 30 s.
- **Après une connexion** : une publicité legacy connectable est arrêtée par le contrôleur à la première connexion, et toutes les piles ne la relancent pas. 500 ms après la déconnexion d'un client GATT non appairé, le téléphone réactive l'ensemble existant (`enableAdvertising(true)`, même adresse, sans effet s'il émet encore) ; si la pile signale l'ensemble désactivé ou refuse la réactivation (`onAdvertisingEnabled`), il en démarre un neuf.

### Serveur GATT (téléphone protégé)

Ouvert par le service de veille en mode recherche, avant la balise (`BluetoothManager.openGattServer`), avec un **service primaire** de même UUID `2af8c8b3-3a13-489b-a3a3-ac220005799b` et deux caractéristiques :

| Caractéristique | UUID | Propriétés | Contenu |
|---|---|---|---|
| Commande | `2af8c8b4-3a13-489b-a3a3-ac220005799b` | `WRITE` (écriture **avec réponse**), permission `WRITE` | une commande, JSON UTF-8, exactement le message du relais : `{"type":"cmd","cmd","ts","nonce","sig"}` en v2, `{"type":"cmd","v":3,"nonce","enc"}` (172 octets) en v3 |
| État | `2af8c8b5-3a13-489b-a3a3-ac220005799b` | `NOTIFY`, descripteur CCCD `0x2902` (lecture et écriture) | l'état chiffré habituel : `{"type":"state","nonce","enc"}` |

- **Publication** : `addService` est asynchrone. Le serveur n'est prêt (et la balise ne démarre) qu'au rappel `onServiceAdded` avec `GATT_SUCCESS`. Échec, ou `openGattServer` qui renvoie `null` juste après le retour du Bluetooth : fermeture puis nouvel essai à 1, 2, 5, 10 puis 30 s, sous un WakeLock court.
- **Pas d'appairage Bluetooth** (ni bonding ni chiffrement du lien) : la sécurité est celle des messages, comme sur le relais. Aucune fenêtre système ne s'ouvre.
- **Taille et MTU** : un message fait **512 octets au plus** (taille maximale d'un attribut). Une commande en fait 165 au plus en v2, toujours 172 en v3 ; un état **toujours 508** (clair bourré à 320 octets, message `{"type":"state","nonce","enc"}` écrit sans échappement : un sérialiseur JSON qui échappe « / » en « \/ » dans le base64 le ferait dépasser 512). Pas de fragmentation applicative : le chercheur **doit négocier un MTU** (`requestMtu(517)`) avant tout échange, et abandonne le chemin Bluetooth si le MTU obtenu est inférieur à 515 (512 octets + 3 d'en-tête ATT). Le téléphone n'accepte qu'une écriture simple à l'offset 0 : écriture préparée (« long write ») refusée par `GATT_REQUEST_NOT_SUPPORTED` (`0x06`), valeur vide ou de plus de 512 octets par `GATT_INVALID_ATTRIBUTE_LENGTH` (`0x0d`). Il n'envoie une notification que si elle tient dans le MTU de cette connexion (MTU - 3 octets).
- **Traitement** : le téléphone répond `GATT_SUCCESS` à toute écriture bien formée **avant** de la traiter (la réponse ne dit rien de la validité), puis, sous un WakeLock court pris dès la réception, vérifie, sans rien modifier, la forme JSON, la signature HMAC (v2) ou l'étiquette GCM (v3), la fraîcheur (15 min) et que le nonce n'a jamais été vu, et seulement alors la limite de débit (une écriture qui échoue compte comme invalide : ni des octets au hasard ni une commande captée et rejouée ne peuvent épuiser la limite). Le message passe ensuite par **exactement le même chemin** qu'une commande reçue du relais : `verifyCmd` (v2) ou déchiffrement (v3), `|now - ts| ≤ 15 min`, ordre des ring/stop, et **la même mémoire de nonces** (une commande reçue par Bluetooth puis par le relais, ou l'inverse, n'est exécutée qu'une fois ; une écriture captée sur les ondes ne peut être rejouée par aucun des deux chemins). La mémoire des nonces est écrite sur disque de façon synchrone **avant** d'exécuter la commande, et seulement si elle a changé. Commande refusée : silence, comme sur le relais.
- **Réponse** : après une commande acceptée, notification de l'état (`ack` = nonce de la commande) au client qui l'a envoyée, s'il est abonné, **avant** le travail lourd de la sonnerie. Les clients abonnés **qui ont écrit une commande valide pendant la connexion en cours** reçoivent aussi l'état quand la sonnerie démarre ou s'arrête (depuis la 1.6, pas pour le changement que provoque la commande accusée elle-même) ; un inconnu abonné ne reçoit rien. Les notifications partent une à une (la suivante à `onNotificationSent`), codes de retour journalisés. L'état part en plus vers le relais comme d'habitude (les navigateurs le voient). Mêmes règles de fraîcheur côté chercheur que la page web (`ts` croissant, 20 min au plus).
- **Comptage des connexions** : un `BluetoothGattServer` voit tous les liens LE du téléphone (montre, écouteurs, liens des autres apps). Une connexion n'est comptée qu'à son **premier accès au service Sonne** (abonnement à l'État ou écriture d'une Commande). Quand Sonne coupe une connexion (`cancelConnection`), sa place est libérée tout de suite, sans attendre un `STATE_DISCONNECTED` qui peut ne jamais venir (lien tenu par une autre app), et l'adresse n'est plus admise avant sa déconnexion.
- **Limites** : 4 connexions Sonne au plus ; à saturation, la **plus ancienne sans commande valide est évincée** (si toutes en ont envoyé une, le nouveau venu est refusé) ; une connexion vit **5 s sans commande valide**, 10 s après ; **10 requêtes ATT** adressées à Sonne par connexion, **2 écritures invalides** (mal formées ou mal signées), puis coupure ; 20 commandes **valides** par minute au total sur le chemin Bluetooth (les suivantes sont ignorées).

### Chercheur (autre téléphone avec l'app Sonne)

- **Mes autres téléphones** : liste de chaînes connues (nom local, relais, et seulement les clés dérivées id, `K_sig`, `K_enc` et B : pas S, donc ni P ni lien d'appairage reconstituables), chiffrée par une clé du Keystore `setUnlockedDeviceRequired` (inutilisable téléphone verrouillé) et rangée en **stockage par identifiant** (illisible avant le premier déverrouillage) : le chercheur ne tourne qu'écran déverrouillé. La liste est oubliée de la mémoire quand on quitte l'écran. Ajout par QR (caméra), lien collé ou 12 mots.
- **Réveil** : à l'ouverture de l'écran À proximité, puis toutes les 8 min, `ping` par le relais pour ouvrir le mode recherche du téléphone cherché (voir Mode recherche), précédé depuis la 1.6 de `GET /api/presence` : rien n'est envoyé si le téléphone est hors ligne. L'écran dit si le réveil est livré, si le téléphone est hors ligne (sa balise s'ouvrira seule après une heure sans relais) ou si le relais est injoignable.
- **Scan** seulement tant que l'écran « Nearby » est au premier plan : `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, délai de rapport 0. Filtre matériel `ScanFilter.setServiceData(UUID Sonne, donnees, masque)` avec `donnees` = 8 octets à `0x00` puis `0x01` et `masque` = 8 octets à `0x00` puis `0xFF` : seules les balises Sonne de version 1 remontent (le filtre sur les données de service est nécessaire, la balise ne publie pas de liste d'UUID). Android bascule en scan opportuniste un scan qui dure plus de 30 min et refuse en silence plus de 5 démarrages en 30 s : redémarrer le scan toutes les 25 min, **jamais plus de 4 démarrages sur 30 s glissantes** (historique commun au processus, sans écart fixe). Le scan suit l'état du Bluetooth en direct (`ACTION_STATE_CHANGED`, relance au retour), est relancé s'il ne remonte plus rien depuis 60 s alors qu'une balise était vue, et est mis en pause pendant une connexion GATT (le scan à 100 % et l'ouverture de connexion se partagent la radio).
- **Reconnaissance** : pour chaque chaîne connue, le chercheur calcule `id_ble` pour **w - 1, w et w + 1** (w calculée avec sa propre horloge) et les recalcule à chaque changement de w. Une balise est reconnue si ses 8 premiers octets de données de service égalent l'une de ces valeurs (horloges décalées jusqu'à 15 min, rotation en retard). Une balise inconnue est ignorée sans trace. `id_ble` est public et peut être recopié : le chercheur suit le signal **adresse par adresse** (jamais de fusion des mesures), garde le `BluetoothDevice` du `ScanResult` de chaque adresse (il porte le type d'adresse aléatoire ; `getRemoteDevice(String)` créerait une adresse publique depuis Android 13), et signale quand deux adresses émettent le même `id_ble` en même temps (vues dans les 2 s) pendant plus de 3 s. Une rotation ne déclenche rien : l'ancienne adresse se tait.
- **Lissage du signal** (par adresse ; la jauge suit l'adresse au signal lissé le plus fort) : moyenne exponentielle pondérée par le temps, `r = r + α (mesure - r)` avec `α = 1 - exp(-Δt / τ)`, `τ = 2 s`, Δt depuis la mesure précédente. La première mesure initialise r ; r est remis à zéro si la balise n'a pas été vue depuis 10 s. Une mesure de 127 (RSSI indisponible) est ignorée.
- **Niveaux** (r en dBm, pour une balise à -15 dBm ; seuils à recaler sur le terrain, ce sont les constantes de l'app) :

| Niveau | Libellé (en / fr) | Condition | Bip toutes les |
|---|---|---|---|
| 0 | Lost / Perdu | jamais vu, ou vu il y a plus de 10 s | silence |
| 1 | Cold / Froid | r < -88 | 2 000 ms |
| 2 | Cool / Frais | -88 ≤ r < -80 | 1 300 ms |
| 3 | Warm / Tiède | -80 ≤ r < -72 | 800 ms |
| 4 | Hot / Chaud | -72 ≤ r < -64 | 450 ms |
| 5 | Very hot / Très chaud | r ≥ -64 | 200 ms |

  **Hystérésis** : on monte d'un niveau dès que r atteint le seuil, on ne redescend que quand r passe 3 dB sous le seuil du niveau courant (pas de clignotement à la limite). L'écran affiche aussi « vu il y a x s » et la dernière mesure brute.
- **Faire sonner** : si la balise a été vue il y a moins de 10 s, chemin Bluetooth, sur les adresses récentes de la balise (3 au plus, la plus forte d'abord), scan en pause : connexion GATT (`autoConnect = false`, 5 s au plus ; un statut 133, 62 ou 8 reçu avant 3 s vaut un second essai après 400 ms, dans ce budget), `requestMtu(517)`, découverte des services, abonnement à l'État (CCCD `0x0001`), écriture de la commande, attente d'une notification d'état frais dont `ack` = nonce, **5 s** au plus. Échec à n'importe quelle étape : adresse suivante, puis **le même message** (même nonce, v3 ou v2 selon la capacité connue) par le relais : le téléphone ne l'exécutera jamais deux fois. Depuis l'app 1.6, tout état frais reçu par GATT qui annonce `cmdv ≥ 3` est retenu pour la chaîne (liste chiffrée) ; un 400 du relais à une v2 fait renvoyer la même intention en v3 avec un nouveau nonce. Si une écriture a été acceptée par Bluetooth mais qu'aucun accusé n'est revenu et que le relais échoue, le résultat affiché est « écrite par Bluetooth, sans accusé » (la commande a peut-être été exécutée), pas un échec. Le chercheur se déconnecte dès la réponse reçue. `stop` et `ping` suivent le même chemin.

### Permissions (Android 12 et plus)

- Téléphone protégé : `BLUETOOTH_ADVERTISE` et `BLUETOOTH_CONNECT` (serveur GATT).
- Chercheur : `BLUETOOTH_SCAN` avec `android:usesPermissionFlags="neverForLocation"` (pas de localisation) et `BLUETOOTH_CONNECT` ; `CAMERA` seulement pour scanner un QR, demandée au moment du scan.
- L'écran d'état rappelle quand le Bluetooth est éteint alors que la balise est activée.
- **GrapheneOS** : la coupure automatique du Bluetooth (« Turn off Bluetooth automatically », réglage `Settings.Global` `bluetooth_off_timeout`, 0 = désactivé) éteint le Bluetooth quand aucun appareil n'est connecté ; ni la balise ni le scan ne comptent comme connexion. Quand le réglage est lisible et actif, l'accueil, l'écran Autorisations et À proximité le signalent avec un lien vers les réglages Bluetooth. Android 12 et plus peuvent refuser à une app la lecture d'un réglage caché : sans signal, la limite reste (voir Limites assumées).

### Limites assumées

Les six premières ne valent que **pendant le mode recherche** : hors de ce mode, le téléphone n'émet rien.

- **Nom Bluetooth** : la balise est connectable. N'importe qui à portée peut s'y connecter sans appairage et lire le service GAP standard d'Android : le nom Bluetooth du téléphone (souvent « Pixel de Prénom ») et l'empreinte de sa base GATT (Database Hash), des valeurs qui ne changent pas avec `id_ble` et relient les fenêtres entre elles. Sonne ne peut pas les masquer : les Réglages conseillent un nom Bluetooth neutre.
- **Appareils appairés** : l'adresse de la balise est une adresse privée résolvable. Tout appareil déjà appairé avec ce téléphone (voiture, voiture de location qui garde les clés, montre, ordinateur) peut reconnaître le téléphone derrière chaque publicité, quel que soit `id_ble`.
- **Détenteurs de S** : B est dérivée de S. Quiconque a les 12 mots, le QR ou le lien (ancien partenaire, navigateur synchronisé) peut reconnaître la balise avec un simple scanner, et peut **ouvrir** le mode recherche en envoyant une commande (un `ping` est silencieux). L'écran Appairage le dit ; la notification de veille montre quand le mode est ouvert. « Changer la clé » coupe ce pouvoir.
- L'UUID Sonne est visible : un observateur voit qu'**un** téléphone Sonne est à portée, pas lequel. Tant que les utilisateurs sont peu nombreux dans un lieu, c'est presque un indice d'identité. Dans une même fenêtre de 15 min, `id_ble` est fixe : suivi possible pendant 15 min au plus par lui seul.
- **Copie d'`id_ble`** : `id_ble` est public. N'importe qui peut le recopier et le réémettre plus fort. Le chercheur suit chaque adresse à part, essaie plusieurs adresses et signale la copie, mais un clone reçoit la commande écrite sur lui (chiffrée depuis la 1.6 : il ne peut pas la lire) et peut la garder (rejouable 15 min, une seule exécution au total grâce aux nonces). Sans Internet, la commande peut ne jamais arriver au vrai téléphone.
- **Déni de service local** : un inconnu à portée peut occuper les places du serveur GATT. Il doit tourner plus vite que l'échange légitime (5 s de vie sans commande valide, éviction de la plus ancienne connexion sans commande), mais il peut gêner le chemin Bluetooth ; le relais reste le chemin de secours.
- **Mode recherche hors ligne** : si le téléphone protégé n'a pas Internet, le chercheur ne peut pas ouvrir son mode recherche ; il s'ouvre seul après une heure sans relais.
- **GrapheneOS** : la coupure automatique du Bluetooth éteint la balise ; à désactiver sur le téléphone protégé (et sur le chercheur, pendant la recherche).
- La force du signal dépend du corps, des murs et des modèles de téléphone : la jauge guide l'approche, elle ne mesure pas une distance.

## App Android

- Kotlin, Jetpack Compose + Material 3, `minSdk 31`, `targetSdk 34`, `compileSdk 36`. `targetSdk 34` est voulu (distribution hors Play, par Obtainium) : c'est la condition pour pouvoir lever Ne pas déranger sur Android 15 et plus (voir Sonnerie, point 3). Gradle 8.14.3, AGP 8.13.0, Kotlin 2.x, JDK 21 (`/opt/homebrew/opt/openjdk@21`).
- Dépendances : OkHttp (WebSocket, `POST /api/cmd` du chercheur), ZXing core (QR : dessin et lecture, la caméra passe par Camera2 sans autre dépendance), AndroidX Compose/Material3/Activity, pas de Google Play Services, pas de Firebase, aucune lib d'analytics.
- Écrans : **Autorisations** au premier lancement (une phrase sur ce qu'est Sonne, puis les réglages), qui mène directement à **Appairage** ; **Accueil** (état : connecté / dernière connexion, batterie, bouton « Tester la sonnerie », rappel des permissions manquantes, « Étape suivante : relie un navigateur » tant que l'appairage n'a pas été ouvert, alerte si la clé est perdue ou si Ne pas déranger coupe les alarmes), **Appairage** (QR, lien à copier, 12 mots présentés comme le moyen de faire sonner depuis n'importe quel navigateur, hôte du lien en clair et avertissement si le relais n'est pas celui par défaut, début de l'id, « Changer la clé » avec confirmation, `FLAG_SECURE` : ni capture ni miniature), **Réglages** (adresse du relais, `https://` seulement en release, durée d'arrêt automatique, lampe torche oui/non, balise Bluetooth oui/non avec le conseil d'un nom Bluetooth neutre, bips de proximité oui/non), **Sonnerie** (plein écran, Stop), **À proximité** (depuis l'accueil, « Trouver un téléphone à proximité » : jauge, bips, Faire sonner) et **Mes autres téléphones** (chaînes connues, ajout par QR, lien ou 12 mots). L'accueil montre l'état de la balise (en veille, mode recherche, émet) et rappelle quand le Bluetooth est éteint ou se coupe tout seul (GrapheneOS) ; l'écran Autorisations a une ligne « Appareils à proximité ».
- Anglais uniquement (depuis la 1.4.0). Aucun tiret cadratin ni demi-cadratin dans les textes.
- `applicationId = dev.sidali.sonne`, version `1.6.0` (code 9). Signature de release : keystore `~/.sonne/release.jks` + `~/.sonne/keystore.properties` (créés par `keytool` s'ils n'existent pas ; jamais dans le dépôt).
- Tests unitaires : vecteurs de `docs/VECTEURS.json` (P, id, K_sig, K_enc, signature d'une commande, déchiffrement d'un état, B et `id_ble` de la balise Bluetooth, et depuis la 1.6 `K_cmd`, commandes chiffrées v3, état avec `cmdv` et cas d'acceptation ou de refus) partagés avec `relay/crypto.js` et la page web.

## Mise à jour (app 1.2)

Mise à jour intégrée, sans magasin, comme les versions hors Play de Signal ou Telegram. Le dépôt GitHub étant privé, c'est le relais qui sert les fichiers (Obtainium reste possible quand le dépôt sera public).

- **Relais** : `GET /update/android.json` renvoie l'annonce, un objet JSON publié tel quel depuis le dossier `SONNE_RELEASES_DIR` (monté en lecture seule sur `/releases`, dossier hôte `SONNE_RELEASES_HOST_DIR` dans `deploy/docker-compose.yml`), avec `Cache-Control: public, max-age=300` ; `GET /download/<nom>.apk` sert un APK de ce dossier. Le nom est validé strictement (`^[A-Za-z0-9][A-Za-z0-9_-]*(\.[A-Za-z0-9_-]+)*\.apk$`, 100 caractères au plus, ni `/`, ni `..`, ni encodage) ; tout le reste répond 404. Sans dossier, les deux routes répondent 404.
- **Annonce** :

  ```json
  { "version": "1.2.0", "versionCode": 4, "url": "https://sonne.sidali.dev/download/sonne-1.2.0.apk",
    "sha256": "<SHA-256 de l'APK, hex>", "certSha256": "<SHA-256 du certificat de signature (DER), hex>", "notes": "…" }
  ```

  Écrite par `tools/release-android.sh` (sommes calculées par `shasum` et `apksigner verify --print-certs`). L'APK est copié avant l'annonce, posée par un renommage : elle ne pointe jamais vers un fichier absent. Un APK publié ne change jamais sous le même nom.
- **Vérification** : `GET <relais réglé>/update/android.json` une fois par jour, depuis le chien de garde de la veille (pas de nouveau service ; nouvel essai une heure après un échec), et à l'ouverture de l'app (au plus une fois par minute). Réglage « Rechercher les mises à jour », actif par défaut. Rien d'autre n'est envoyé. Le `versionCode` fait foi : plus grand que celui installé, l'app affiche un bandeau sur l'accueil et une notification « Mise à jour disponible » (canal dédié, importance normale, une seule par version). `url` doit être en `https://` (`http://` accepté en debug seulement).
- **Téléchargement et contrôles** (au tap) : OkHttp dans le cache de l'app, 150 Mo au plus. Puis, dans cet ordre, sinon refus clair et suppression du fichier : SHA-256 égal à `sha256` ; paquet `dev.sidali.sonne` et `versionCode` égal à celui annoncé (`getPackageArchiveInfo`) ; certificats de signature (`GET_SIGNING_CERTIFICATES`, `apkContentsSigners`) **identiques à ceux de l'app installée** et contenant `certSha256`. Une annonce compromise (relais piraté) ne suffit donc pas : il faudrait aussi la clé de signature, gardée hors du dépôt et du serveur.
- **Installation** : session `PackageInstaller` (`MODE_FULL_INSTALL`, `setRequireUserAction(USER_ACTION_REQUIRED)`), permission `REQUEST_INSTALL_PACKAGES`. L'écran système de confirmation est toujours montré ; l'activité le lance dès qu'elle est au premier plan (sinon une notification invite à terminer). Si l'autorisation « Installer des applis inconnues » manque, le bandeau l'explique et ouvre le réglage système (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`). Annulation : retour au bandeau. Après l'installation, `MY_PACKAGE_REPLACED` relance la veille ; clé et réglages sont gardés.
- **Limites** : un relais auto-hébergé sans `SONNE_RELEASES_DIR` ne propose aucune mise à jour (404 tenu pour « à jour »). Sur un Android avec les services Google, Play Protect peut afficher en plus son propre écran d'analyse (vu sur l'émulateur avec Play Store). Le relais voit l'adresse IP qui interroge l'annonce, comme pour le WebSocket.

## Page web

- Page statique sans framework (`index.html`, `app.js`, `crypto.js`, `style.css`, `bip39-english.js`, plus `manifest.webmanifest` et `sw.js` pour l'installer comme PWA), aucun script en ligne pour respecter la CSP (`script-src 'self'`). WebCrypto (HKDF, HMAC, AES-GCM). Mobile d’abord. Anglais uniquement.
- Gros bouton **Faire sonner**, bouton **Arrêter**, pastille d'état (en ligne / vu il y a…), batterie, journal des accusés de réception, gestion de plusieurs téléphones (nom local, ajout par lien ou par 12 mots, suppression).
- Le fragment `#s=…` est lu puis effacé de la barre d'adresse (`history.replaceState`). **Limite** : la visite d'origine, avec le fragment, reste dans l'historique du navigateur et part avec sa synchronisation (Chrome Sync, Firefox Sync). Sur un navigateur synchronisé, mieux vaut coller le lien ou taper les 12 mots dans le formulaire de la page (rien dans l'historique). L'app et le README le disent.
- Un téléphone ajouté par un lien ouvert directement demande confirmation, avec le début de l'id (affiché aussi dans l'app) : n'importe qui peut fabriquer un tel lien. Son nom automatique est préfixé par « Nouveau : ».
- Le navigateur demande un stockage persistant (`navigator.storage.persist()`). Safari peut effacer les données d'un site après 7 jours sans visite : la page conseille de l'ajouter à l'écran d'accueil ou de garder les 12 mots.
- Commandes (page 1.6) : chiffrées v3 avec `cleCmd`, clé AES-GCM non exportable (`["encrypt"]`) dérivée de `cleSig` et rangée dans IndexedDB avec `cleSig` et `cleEnc` ; une entrée rangée avant la 1.6 est complétée à l'ouverture du téléphone (`completerCles`), sans nouvel appairage. Le drapeau de capacité est gardé par téléphone dans `localStorage` (`sonne.cmdv3`, clé à part de la liste des téléphones, effacé quand on retire le téléphone) ; sans lui, la commande part en v2. Un 400 du relais à une v2 fait repartir la même intention en v3 (nouveau nonce) et toutes les commandes suivantes de la vue. La constante `COUPURE_V2` de `web/app.js` passe à `true` à l'étape 3. Pas de vérification automatique (ping) quand la présence dit le téléphone hors ligne.
- La page qui manipule S est servie par le relais : un relais malveillant pourrait servir un script qui lit le fragment. N'utiliser qu'un relais de confiance ; l'app affiche l'hôte du lien d'appairage et avertit quand le relais n'est pas celui par défaut.

## Dépôt

- GitHub `sidalidev/sonne` (privé d'abord, public plus tard), licence Apache-2.0, `README.md` (pitch, installation par Obtainium, premiers pas, auto-hébergement du relais en Docker, limites), `CHANGELOG.md`.
- `relay/Dockerfile` + `deploy/docker-compose.yml` (`name: sonne`, labels Traefik pour `sonne.sidali.dev`, restart unless-stopped), utilisateur non root, système de fichiers en lecture seule, healthcheck.
- CI GitHub Actions : tests du relais, build de l'APK debug.
