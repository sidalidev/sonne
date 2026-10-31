// Cryptographie de Sonne (voir docs/PROTOCOL.md).
// Fonctions pures, sans état, utilisables par le relais, les tests et les outils.
// Tout repose sur node:crypto, aucune dépendance externe.

import {
  createHash,
  createHmac,
  createCipheriv,
  createDecipheriv,
  hkdfSync,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";
import { MOTS } from "./bip39-english.js";

/** Taille du secret S en octets (128 bits). */
export const TAILLE_SECRET = 16;

/** Adresse du relais de référence, utilisée par défaut pour les liens. */
export const RELAIS_PAR_DEFAUT = "https://sonne.sidali.dev/";

/** Commandes reconnues par le téléphone. */
export const COMMANDES = Object.freeze(["ring", "stop", "ping"]);

const RE_HEX = /^[0-9a-f]*$/i;
const RE_NONCE_CMD = /^[0-9a-f]{32}$/i; // 16 octets en hex
const RE_SIG = /^[0-9a-f]{64}$/i; // HMAC-SHA256 en hex
const RE_NONCE_ETAT = /^[0-9a-f]{24}$/i; // 12 octets en hex
const RE_ID = /^[0-9a-f]{32}$/;

// ---------------------------------------------------------------------------
// Outils internes
// ---------------------------------------------------------------------------

/**
 * Normalise S : accepte un Buffer, un Uint8Array ou une chaîne hex de 32 caractères.
 * Renvoie toujours un Buffer neuf de 16 octets, ou lève une erreur.
 */
function secret(S) {
  let octets;
  if (typeof S === "string") {
    if (S.length !== TAILLE_SECRET * 2 || !RE_HEX.test(S)) {
      throw new Error("Secret invalide : 32 caractères hexadécimaux attendus");
    }
    octets = Buffer.from(S, "hex");
  } else if (S instanceof Uint8Array) {
    octets = Buffer.from(S);
  } else {
    throw new Error("Secret invalide : Buffer, Uint8Array ou chaîne hex attendu");
  }
  if (octets.length !== TAILLE_SECRET) {
    throw new Error(`Secret invalide : ${TAILLE_SECRET} octets attendus`);
  }
  return octets;
}

/** Normalise un nonce donné en hex ou en octets, de la taille attendue. */
function nonceEnOctets(nonce, taille) {
  const octets =
    typeof nonce === "string" && RE_HEX.test(nonce) && nonce.length % 2 === 0
      ? Buffer.from(nonce, "hex")
      : nonce instanceof Uint8Array
        ? Buffer.from(nonce)
        : null;
  if (!octets || octets.length !== taille) {
    throw new Error(`Nonce invalide : ${taille} octets attendus`);
  }
  return octets;
}

/** HKDF-SHA256 avec sel vide, sortie de `longueur` octets (32 par défaut). */
function hkdf(S, info, longueur = 32) {
  return Buffer.from(hkdfSync("sha256", S, Buffer.alloc(0), Buffer.from(info, "utf8"), longueur));
}

/** Texte exact signé pour une commande : cmd + "|" + ts + "|" + nonce. */
function texteASigner(cmd, ts, nonceHex) {
  return `${cmd}|${ts}|${nonceHex}`;
}

// ---------------------------------------------------------------------------
// Secret, identifiant et clés
// ---------------------------------------------------------------------------

/** Tire un nouveau secret S de 16 octets (aléa cryptographique). */
export function genererSecret() {
  return randomBytes(TAILLE_SECRET);
}

/** Taille de la preuve de téléphone P en octets. */
export const TAILLE_PREUVE = 16;

/**
 * Preuve de téléphone P = HKDF-SHA256(S, sel vide, "sonne-phone", 16 octets).
 * Le téléphone la présente au relais pour prouver qu'il détient S, sans révéler S
 * ni les clés K_sig et K_enc (protocole v2).
 */
export function derivePreuve(S) {
  return hkdf(secret(S), "sonne-phone", TAILLE_PREUVE);
}

/** id = hex(SHA-256("sonne-id" ‖ P))[0:32]. Le relais le recalcule à partir de P. */
export function idDepuisPreuve(P) {
  const p = P instanceof Uint8Array ? Buffer.from(P) : null;
  if (!p || p.length !== TAILLE_PREUVE) throw new Error(`Preuve invalide : ${TAILLE_PREUVE} octets attendus`);
  return createHash("sha256").update(Buffer.from("sonne-id", "utf8")).update(p).digest("hex").slice(0, 32);
}

/** id = hex(SHA-256("sonne-id" ‖ P))[0:32] avec P dérivée de S. Seule chose que les contrôleurs donnent au relais. */
export function deriveId(S) {
  return idDepuisPreuve(derivePreuve(S));
}

/** Vérifie qu'une chaîne a la forme d'un id (32 caractères hex minuscules). */
export function estIdValide(id) {
  return typeof id === "string" && RE_ID.test(id);
}

/** K_sig et K_enc, dérivées de S par HKDF-SHA256 (sel vide, 32 octets). */
export function deriveKeys(S) {
  const s = secret(S);
  return { sig: hkdf(s, "sonne-sig"), enc: hkdf(s, "sonne-enc") };
}

// ---------------------------------------------------------------------------
// Proximité Bluetooth (v1.1) : identifiant tournant de la balise
// ---------------------------------------------------------------------------

/** Durée d'une fenêtre de la balise : 15 min. L'identifiant change à chaque fenêtre. */
export const DUREE_FENETRE_BLE_MS = 900_000;

/** Taille de l'identifiant tournant id_ble en octets. */
export const TAILLE_ID_BLE = 8;

/** Fenêtre courante de la balise : w = floor(now_ms / 900000). */
export function fenetreBle(maintenantMs = Date.now()) {
  if (!Number.isSafeInteger(maintenantMs) || maintenantMs < 0) throw new Error("Horodatage invalide");
  return Math.floor(maintenantMs / DUREE_FENETRE_BLE_MS);
}

/** Clé de balise B = HKDF-SHA256(S, sel vide, "sonne-ble", 32 octets). */
export function deriveCleBle(S) {
  return hkdf(secret(S), "sonne-ble");
}

/**
 * Identifiant tournant de la balise pour la fenêtre w :
 * id_ble = HMAC-SHA256(B, w en 8 octets big-endian)[0:8].
 * Seul un appareil qui connaît S peut le recalculer ; il change toutes les 15 min.
 */
export function deriveBleId(S, w) {
  if (!Number.isSafeInteger(w) || w < 0) throw new Error("Fenêtre invalide : entier positif attendu");
  const fenetre = Buffer.alloc(8);
  fenetre.writeBigUInt64BE(BigInt(w));
  return createHmac("sha256", deriveCleBle(S)).update(fenetre).digest().subarray(0, TAILLE_ID_BLE);
}

// ---------------------------------------------------------------------------
// Commandes signées (web vers téléphone)
// ---------------------------------------------------------------------------

/**
 * Construit une commande signée.
 * nonce : 16 octets (hex ou octets). S'il est absent, il est tiré au hasard.
 * Renvoie { type: "cmd", cmd, ts, nonce, sig }.
 */
export function signCmd(S, cmd, ts, nonce = randomBytes(16)) {
  if (!COMMANDES.includes(cmd)) throw new Error(`Commande inconnue : ${cmd}`);
  if (!Number.isSafeInteger(ts) || ts < 0) throw new Error("Horodatage invalide");
  const nonceHex = nonceEnOctets(nonce, 16).toString("hex");
  const { sig: kSig } = deriveKeys(S);
  const sig = createHmac("sha256", kSig).update(texteASigner(cmd, ts, nonceHex), "utf8").digest("hex");
  return { type: "cmd", cmd, ts, nonce: nonceHex, sig };
}

/**
 * Vérifie la forme et la signature d'une commande. Renvoie true ou false, ne lève jamais.
 * Ne contrôle ni la fraîcheur de ts ni la réutilisation du nonce : c'est le rôle du téléphone.
 * Le nonce est signé tel qu'il est écrit dans le message (hex minuscule attendu).
 */
export function verifyCmd(S, msg) {
  try {
    if (!msg || typeof msg !== "object") return false;
    const { cmd, ts, nonce, sig } = msg;
    if (msg.type !== undefined && msg.type !== "cmd") return false;
    if (!COMMANDES.includes(cmd)) return false;
    if (!Number.isSafeInteger(ts) || ts < 0) return false;
    if (typeof nonce !== "string" || !RE_NONCE_CMD.test(nonce)) return false;
    if (typeof sig !== "string" || !RE_SIG.test(sig)) return false;
    const { sig: kSig } = deriveKeys(S);
    const attendue = createHmac("sha256", kSig).update(texteASigner(cmd, ts, nonce), "utf8").digest();
    return timingSafeEqual(attendue, Buffer.from(sig, "hex"));
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// États chiffrés (téléphone vers web)
// ---------------------------------------------------------------------------

/**
 * Chiffre un état : AES-256-GCM(K_enc, iv = nonce, aad = id en UTF-8, JSON.stringify(obj)).
 * enc = base64(chiffré ‖ étiquette de 16 octets), comme WebCrypto et javax.crypto.
 * nonce : 12 octets (hex ou octets). S'il est absent, il est tiré au hasard.
 * Renvoie { nonce, enc }.
 */
export function encryptState(S, id, obj, nonce = randomBytes(12)) {
  if (!estIdValide(id)) throw new Error("Identifiant invalide");
  const iv = nonceEnOctets(nonce, 12);
  const { enc: kEnc } = deriveKeys(S);
  const chiffreur = createCipheriv("aes-256-gcm", kEnc, iv);
  chiffreur.setAAD(Buffer.from(id, "utf8"));
  const corps = Buffer.concat([chiffreur.update(JSON.stringify(obj), "utf8"), chiffreur.final()]);
  const etiquette = chiffreur.getAuthTag();
  return { nonce: iv.toString("hex"), enc: Buffer.concat([corps, etiquette]).toString("base64") };
}

/**
 * Déchiffre un état { nonce, enc } et renvoie l'objet.
 * Lève une erreur si la clé, l'id (AAD) ou le contenu ne correspondent pas.
 */
export function decryptState(S, id, msg) {
  if (!estIdValide(id)) throw new Error("Identifiant invalide");
  if (!msg || typeof msg.nonce !== "string" || !RE_NONCE_ETAT.test(msg.nonce)) {
    throw new Error("État invalide : nonce de 12 octets attendu");
  }
  if (typeof msg.enc !== "string") throw new Error("État invalide : champ enc manquant");
  const donnees = Buffer.from(msg.enc, "base64");
  if (donnees.length < 16) throw new Error("État invalide : trop court");
  const corps = donnees.subarray(0, donnees.length - 16);
  const etiquette = donnees.subarray(donnees.length - 16);
  const { enc: kEnc } = deriveKeys(S);
  const dechiffreur = createDecipheriv("aes-256-gcm", kEnc, Buffer.from(msg.nonce, "hex"));
  dechiffreur.setAAD(Buffer.from(id, "utf8"));
  dechiffreur.setAuthTag(etiquette);
  let clair;
  try {
    clair = Buffer.concat([dechiffreur.update(corps), dechiffreur.final()]);
  } catch {
    throw new Error("État illisible : authentification refusée");
  }
  return JSON.parse(clair.toString("utf8"));
}

// ---------------------------------------------------------------------------
// 12 mots BIP39 (128 bits + 4 bits de somme de contrôle)
// ---------------------------------------------------------------------------

/** S vers 12 mots BIP39 anglais, exactement comme un portefeuille Bitcoin. */
export function toWords(S) {
  const s = secret(S);
  const controle = createHash("sha256").update(s).digest()[0] >> 4; // 4 premiers bits
  // 132 bits = 128 bits de S suivis des 4 bits de contrôle.
  let bits = 0n;
  for (const octet of s) bits = (bits << 8n) | BigInt(octet);
  bits = (bits << 4n) | BigInt(controle);
  const mots = [];
  for (let i = 11; i >= 0; i--) {
    mots.push(MOTS[Number((bits >> BigInt(i * 11)) & 0x7ffn)]);
  }
  return mots;
}

/**
 * 12 mots (tableau ou chaîne séparée par des espaces) vers S.
 * Insensible à la casse et aux espaces superflus. Lève une erreur si un mot est
 * inconnu, si le nombre est faux ou si la somme de contrôle ne correspond pas.
 */
export function fromWords(words) {
  const liste = (Array.isArray(words) ? words.join(" ") : String(words ?? ""))
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  if (liste.length !== 12) throw new Error("Il faut exactement 12 mots");
  let bits = 0n;
  for (const mot of liste) {
    const index = MOTS.indexOf(mot);
    if (index < 0) throw new Error(`Mot inconnu : ${mot}`);
    bits = (bits << 11n) | BigInt(index);
  }
  const controle = Number(bits & 0xfn);
  let entropie = bits >> 4n;
  const s = Buffer.alloc(TAILLE_SECRET);
  for (let i = TAILLE_SECRET - 1; i >= 0; i--) {
    s[i] = Number(entropie & 0xffn);
    entropie >>= 8n;
  }
  const attendu = createHash("sha256").update(s).digest()[0] >> 4;
  if (controle !== attendu) throw new Error("Somme de contrôle incorrecte : vérifie les mots");
  return s;
}

// ---------------------------------------------------------------------------
// Lien d'appairage : https://sonne.sidali.dev/#s=<base64url(S)>
// ---------------------------------------------------------------------------

/** S vers lien d'appairage. Le secret est dans le fragment, jamais envoyé au serveur. */
export function toLink(S, base = RELAIS_PAR_DEFAUT) {
  const racine = base.endsWith("/") ? base : `${base}/`;
  return `${racine}#s=${secret(S).toString("base64url")}`;
}

/** Lien d'appairage vers S. Lève une erreur si le fragment est absent ou mal formé. */
export function fromLink(url) {
  const texte = String(url ?? "");
  const diese = texte.indexOf("#");
  if (diese < 0) throw new Error("Lien invalide : fragment absent");
  const params = new URLSearchParams(texte.slice(diese + 1));
  const valeur = params.get("s");
  if (!valeur || !/^[A-Za-z0-9_-]+={0,2}$/.test(valeur)) {
    throw new Error("Lien invalide : paramètre s absent ou mal formé");
  }
  const s = Buffer.from(valeur.replace(/=+$/, ""), "base64url");
  if (s.length !== TAILLE_SECRET) throw new Error("Lien invalide : secret de mauvaise taille");
  return s;
}

// ---------------------------------------------------------------------------
// Protocole v3 : commandes chiffrées (PROTOCOL.md, « Protocol v3: encrypted commands »)
//
// Le relais et un observateur Bluetooth ne voient plus qu'un message de taille fixe :
//   {"type":"cmd","v":3,"nonce":"<12 octets en hex>","enc":"<base64 de 80 octets>"}  (172 octets)
// enc = AES-256-GCM(K_cmd, iv = nonce, aad = "sonne-cmd-v3|" + id, clair de 64 octets).
// K_cmd = HKDF-Expand(K_sig, "sonne-cmd-v3", 32) = HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01) :
// tout détenteur de K_sig (navigateurs, « Mes autres téléphones ») la calcule sans connaître S.
// ---------------------------------------------------------------------------

/** Version du format de commande chiffrée : champ v du message et premier octet du clair. */
export const VERSION_COMMANDE = 3;

/** Taille fixe du clair d'une commande v3, en octets. */
export const TAILLE_CLAIR_COMMANDE = 64;

/** Taille fixe de enc une fois décodé : clair suivi de l'étiquette GCM de 16 octets. */
export const TAILLE_ENC_COMMANDE = TAILLE_CLAIR_COMMANDE + 16;

/** Longueur de enc en base64 standard (80 octets : 107 caractères puis un « = »). */
export const LONGUEUR_ENC_COMMANDE = 108;

/** Taille exacte du message JSON d'une commande v3, en octets (écrit sans espace, dans l'ordre du protocole). */
export const TAILLE_MESSAGE_COMMANDE = 172;

/** Taille du nonce d'une commande v3 (IV AES-GCM, identifiant d'anti rejeu et valeur de l'ack). */
export const TAILLE_NONCE_COMMANDE = 12;

/** Écart maximal entre le ts d'une commande (v2 ou v3) et l'horloge du téléphone. */
export const FENETRE_COMMANDE_MS = 15 * 60_000;

/** Nonces valables retenus au plus par le téléphone ; au-delà, les nouvelles commandes sont refusées. */
export const CAPACITE_NONCES = 2000;

/** Code de chaque commande dans le clair v3 (octet 1). « ping » est la vérification d'état (check). */
export const CODES_COMMANDE = Object.freeze({ ring: 1, stop: 2, ping: 3 });

/** Lampe torche demandée (octet 12 du clair) : réglage du téléphone, allumée, éteinte. */
export const LAMPE = Object.freeze({ reglage: 0, allumee: 1, eteinte: 2 });

/** Durée de sonnerie demandée (octets 10 et 11) : 0 = réglage du téléphone, sinon 60 à 600 s. */
export const DUREE_MIN_S = 60;
export const DUREE_MAX_S = 600;

/** Libellé HKDF de K_cmd. */
export const INFO_CLE_COMMANDE = "sonne-cmd-v3";

/** Préfixe de l'AAD d'une commande v3, suivi de l'id. */
export const PREFIXE_AAD_COMMANDE = "sonne-cmd-v3|";

/** Champ de l'état (clair chiffré) par lequel le téléphone annonce les commandes v3. */
export const CHAMP_CAPACITE = "cmdv";

/** Taille fixe du clair d'un état, bourré par « pad » (app 1.1 et plus) ; le message fait alors 508 octets. */
export const TAILLE_CLAIR_ETAT = 320;
export const TAILLE_MESSAGE_ETAT = 508;

const RE_NONCE_V3 = /^[0-9a-f]{24}$/;
const RE_ENC_V3 = /^[A-Za-z0-9+/]{107}=$/;
const NOMS_COMMANDE = Object.freeze(Object.fromEntries(Object.entries(CODES_COMMANDE).map(([n, c]) => [c, n])));

/** Erreur de lecture d'une commande, avec sa raison (voir PROTOCOL.md, ordre des vérifications). */
function refus(raison, texte) {
  return Object.assign(new Error(texte ?? `Commande refusée : ${raison}`), { raison });
}

/** Normalise une clé de 32 octets (Buffer, Uint8Array ou hex). */
function cle32(cle, nom) {
  const octets =
    typeof cle === "string" && cle.length === 64 && RE_HEX.test(cle)
      ? Buffer.from(cle, "hex")
      : cle instanceof Uint8Array
        ? Buffer.from(cle)
        : null;
  if (!octets || octets.length !== 32) throw new Error(`${nom} invalide : 32 octets attendus`);
  return octets;
}

/**
 * HKDF-Expand (RFC 5869, section 2.3) avec SHA-256 : T(1) ‖ T(2) ‖ …, T(i) = HMAC(PRK, T(i-1) ‖ info ‖ i).
 * Sert à dériver K_cmd de K_sig, qui est déjà une clé pseudo-aléatoire de 32 octets (sortie de HKDF) :
 * l'étape Extract est inutile (RFC 5869, section 3.3).
 */
export function hkdfExpand(prk, info, longueur = 32) {
  const cle = Buffer.from(prk);
  const infoOctets = typeof info === "string" ? Buffer.from(info, "utf8") : Buffer.from(info);
  if (!Number.isInteger(longueur) || longueur < 1 || longueur > 255 * 32) throw new Error("Longueur HKDF invalide");
  const blocs = [];
  let precedent = Buffer.alloc(0);
  for (let i = 1; Buffer.concat(blocs).length < longueur; i++) {
    precedent = createHmac("sha256", cle).update(precedent).update(infoOctets).update(Buffer.from([i])).digest();
    blocs.push(precedent);
  }
  return Buffer.concat(blocs).subarray(0, longueur);
}

/** K_cmd depuis K_sig : HKDF-Expand(K_sig, "sonne-cmd-v3", 32) = HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01). */
export function cleCommandeDepuisSig(kSig) {
  return hkdfExpand(cle32(kSig, "K_sig"), INFO_CLE_COMMANDE, 32);
}

/** K_cmd depuis S (le téléphone protégé, ou un contrôleur qui a S). */
export function deriveCleCommande(S) {
  return cleCommandeDepuisSig(deriveKeys(S).sig);
}

/** AAD d'une commande v3 : "sonne-cmd-v3|" + id, en UTF-8 (45 octets ASCII). */
export function aadCommande(id) {
  if (!estIdValide(id)) throw new Error("Identifiant invalide");
  return Buffer.from(PREFIXE_AAD_COMMANDE + id, "utf8");
}

/** Paramètres permis à l'envoi : 0 ou 60..600 s ; lampe 0, 1 ou 2 ; tous deux à 0 pour stop et ping. */
function verifierParametres(cmd, duree, lampe) {
  if (!Object.hasOwn(CODES_COMMANDE, cmd)) throw new Error(`Commande inconnue : ${cmd}`);
  if (!Number.isInteger(duree) || (duree !== 0 && (duree < DUREE_MIN_S || duree > DUREE_MAX_S))) {
    throw new Error(`Durée invalide : 0 ou ${DUREE_MIN_S} à ${DUREE_MAX_S} s`);
  }
  if (!Object.values(LAMPE).includes(lampe)) throw new Error("Lampe invalide : 0, 1 ou 2");
  if (cmd !== "ring" && (duree !== 0 || lampe !== 0)) throw new Error("Durée et lampe ne valent que pour ring");
}

/**
 * Clair d'une commande v3, 64 octets :
 *   0      version (3)
 *   1      commande (1 ring, 2 stop, 3 ping)
 *   2..9   ts, millisecondes Unix, entier non signé big-endian
 *   10..11 durée de sonnerie en secondes, big-endian (0 = réglage du téléphone)
 *   12     lampe (0 = réglage du téléphone, 1 = allumée, 2 = éteinte)
 *   13..63 réservé : zéros à l'envoi, ignorés à la réception
 * Ne vérifie que les bornes des champs (pour fabriquer aussi des vecteurs de refus) ;
 * chiffrerCommandeAvec applique en plus les règles d'envoi.
 */
export function clairCommande(cmd, ts, { duree = 0, lampe = 0, version = VERSION_COMMANDE, code } = {}) {
  const c = code ?? CODES_COMMANDE[cmd];
  if (!Number.isInteger(c) || c < 0 || c > 255) throw new Error(`Commande inconnue : ${cmd}`);
  if (!Number.isSafeInteger(ts) || ts < 0) throw new Error("Horodatage invalide");
  if (!Number.isInteger(duree) || duree < 0 || duree > 0xffff) throw new Error("Durée invalide");
  if (!Number.isInteger(lampe) || lampe < 0 || lampe > 0xff) throw new Error("Lampe invalide");
  if (!Number.isInteger(version) || version < 0 || version > 255) throw new Error("Version invalide");
  const clair = Buffer.alloc(TAILLE_CLAIR_COMMANDE);
  clair[0] = version;
  clair[1] = c;
  clair.writeBigUInt64BE(BigInt(ts), 2);
  clair.writeUInt16BE(duree, 10);
  clair[12] = lampe;
  return clair;
}

/**
 * Lit un clair v3 déchiffré. Renvoie { cmd, ts, duree, lampe } ou lève une erreur dont `raison` vaut
 * "taille", "version", "commande" ou "fraicheur" (ts au-delà de 2^53 - 1, bit de poids fort compris).
 */
export function lireClairCommande(clair) {
  const c = Buffer.from(clair);
  if (c.length !== TAILLE_CLAIR_COMMANDE) throw refus("taille");
  if (c[0] !== VERSION_COMMANDE) throw refus("version");
  const cmd = NOMS_COMMANDE[c[1]];
  if (!cmd) throw refus("commande");
  const ts = c.readBigUInt64BE(2);
  if (ts > BigInt(Number.MAX_SAFE_INTEGER)) throw refus("fraicheur");
  return { cmd, ts: Number(ts), duree: c.readUInt16BE(10), lampe: c[12] };
}

/**
 * Ce que le téléphone applique pour une ring : { dureeS, lampe }, null voulant dire « son réglage ».
 * Une durée hors de 60..600 s ou une lampe inconnue retombent sur le réglage : un émetteur
 * maladroit ne doit jamais empêcher la sonnerie.
 */
export function parametresSonnerie({ duree, lampe }) {
  return {
    dureeS: duree >= DUREE_MIN_S && duree <= DUREE_MAX_S ? duree : null,
    lampe: lampe === LAMPE.allumee ? true : lampe === LAMPE.eteinte ? false : null,
  };
}

/** Chiffre un clair quelconque (64 octets attendus par le téléphone) en message v3 { type, v, nonce, enc }. */
export function chiffrerClairCommande(kCmd, id, clair, nonce = randomBytes(TAILLE_NONCE_COMMANDE)) {
  const aad = aadCommande(id);
  const iv = nonceEnOctets(nonce, TAILLE_NONCE_COMMANDE);
  const chiffreur = createCipheriv("aes-256-gcm", cle32(kCmd, "K_cmd"), iv);
  chiffreur.setAAD(aad);
  const enc = Buffer.concat([chiffreur.update(Buffer.from(clair)), chiffreur.final(), chiffreur.getAuthTag()]);
  return { type: "cmd", v: VERSION_COMMANDE, nonce: iv.toString("hex"), enc: enc.toString("base64") };
}

/**
 * Commande v3 chiffrée avec K_cmd, pour le téléphone d'identifiant id.
 * options : nonce (12 octets, hex ou octets, aléatoire par défaut), duree (0 ou 60..600 s), lampe (0, 1, 2).
 */
export function chiffrerCommandeAvec(kCmd, id, cmd, ts, { nonce, duree = 0, lampe = 0 } = {}) {
  verifierParametres(cmd, duree, lampe);
  return chiffrerClairCommande(kCmd, id, clairCommande(cmd, ts, { duree, lampe }), nonce ?? randomBytes(TAILLE_NONCE_COMMANDE));
}

/** Commande v3 chiffrée depuis S (id et K_cmd en sont dérivés). */
export function chiffrerCommande(S, cmd, ts, options = {}) {
  return chiffrerCommandeAvec(deriveCleCommande(S), deriveId(S), cmd, ts, options);
}

/**
 * Forme d'un message v3, sans clé : ce que vérifient le relais et le téléphone avant tout.
 * Renvoie { nonce, octets } ou lève une erreur de raison "forme" ou "taille".
 */
function analyserCommandeV3(msg) {
  if (!msg || typeof msg !== "object" || Array.isArray(msg)) throw refus("forme");
  if (msg.type !== "cmd" || msg.v !== VERSION_COMMANDE) throw refus("forme");
  const { nonce, enc } = msg;
  if (typeof nonce !== "string" || !RE_NONCE_V3.test(nonce)) throw refus("forme");
  if (typeof enc !== "string" || !/^[A-Za-z0-9+/]*={0,2}$/.test(enc)) throw refus("forme");
  const octets = Buffer.from(enc, "base64");
  if (!RE_ENC_V3.test(enc) || octets.length !== TAILLE_ENC_COMMANDE) throw refus("taille");
  return { nonce, octets };
}

/**
 * Copie propre d'une commande v3 bien formée ({ type, v, nonce, enc }, rien d'autre), ou null.
 * C'est le contrôle du relais : il ne peut rien vérifier de plus, il n'a pas K_cmd.
 */
export function formeCommandeV3(msg) {
  try {
    const { nonce } = analyserCommandeV3(msg);
    return { type: "cmd", v: VERSION_COMMANDE, nonce, enc: msg.enc };
  } catch {
    return null;
  }
}

/** Message exact d'une commande v3, tel qu'il part sur le WebSocket, en HTTP et par GATT (172 octets). */
export function texteCommande(msg) {
  const propre = formeCommandeV3(msg);
  if (!propre) throw new Error("Commande v3 mal formée");
  return `{"type":"cmd","v":${VERSION_COMMANDE},"nonce":"${propre.nonce}","enc":"${propre.enc}"}`;
}

/**
 * Déchiffre et lit une commande v3. Renvoie { cmd, ts, duree, lampe, nonce } ou lève une erreur dont
 * `raison` vaut, dans l'ordre des vérifications : "forme", "taille", "authentification", "version",
 * "commande", "fraicheur" (ts illisible). Ne contrôle ni la fenêtre de 15 min ni le rejeu.
 */
export function dechiffrerCommandeAvec(kCmd, id, msg) {
  const { nonce, octets } = analyserCommandeV3(msg);
  const dechiffreur = createDecipheriv("aes-256-gcm", cle32(kCmd, "K_cmd"), Buffer.from(nonce, "hex"));
  dechiffreur.setAAD(aadCommande(id));
  dechiffreur.setAuthTag(octets.subarray(TAILLE_CLAIR_COMMANDE));
  let clair;
  try {
    clair = Buffer.concat([dechiffreur.update(octets.subarray(0, TAILLE_CLAIR_COMMANDE)), dechiffreur.final()]);
  } catch {
    throw refus("authentification", "Commande illisible : authentification refusée");
  }
  return { ...lireClairCommande(clair), nonce };
}

/** Déchiffre une commande v3 depuis S (le téléphone protégé). */
export function dechiffrerCommande(S, msg) {
  return dechiffrerCommandeAvec(deriveCleCommande(S), deriveId(S), msg);
}

/** Vrai si un état déchiffré annonce les commandes v3 (champ cmdv ≥ 3). */
export function accepteV3(etat) {
  const v = etat?.[CHAMP_CAPACITE];
  return Number.isInteger(v) && v >= VERSION_COMMANDE;
}

/**
 * Filtre de référence du téléphone (PROTOCOL.md, « Acceptance by the phone ») : forme,
 * authentification, fraîcheur (15 min), anti rejeu par l'âge, capacité, ordre des ring et stop.
 * Une seule mémoire de nonces pour v2 et v3, pour le relais et le Bluetooth.
 *
 * options : accepterV2 (true pendant la transition, false après la coupure), capacite,
 * nonces ({ nonce: ts } déjà retenus), dernierTsOrdre.
 *
 * accepter(S, msg, maintenant) renvoie { accepte: true, format, cmd, ts, nonce, duree, lampe }
 * ou { accepte: false, raison } avec raison parmi : "forme", "ancien-format", "signature", "taille",
 * "authentification", "version", "commande", "fraicheur", "rejeu", "capacite", "ordre".
 * recevable(S, msg, maintenant) fait les contrôles jusqu'au rejeu compris, sans rien modifier
 * (écriture GATT, avant la limite de débit).
 */
export function creerFiltreCommandes({
  accepterV2 = true,
  capacite = CAPACITE_NONCES,
  nonces = {},
  dernierTsOrdre = 0,
} = {}) {
  const vus = new Map(Object.entries(nonces).map(([n, ts]) => [n.toLowerCase(), ts]));
  let dernierOrdre = dernierTsOrdre;

  /** Contrôles sans état : renvoie { format, cmd, ts, nonce, duree, lampe } ou { raison }. */
  function lire(S, msg, maintenant) {
    if (!msg || typeof msg !== "object" || Array.isArray(msg) || msg.type !== "cmd") return { raison: "forme" };
    let lu;
    if (msg.v === undefined) {
      if (!accepterV2) return { raison: "ancien-format" };
      if (!verifyCmd(S, msg)) return { raison: "signature" };
      lu = { format: 2, cmd: msg.cmd, ts: msg.ts, nonce: msg.nonce.toLowerCase(), duree: 0, lampe: 0 };
    } else if (msg.v === VERSION_COMMANDE) {
      try {
        lu = { format: 3, ...dechiffrerCommande(S, msg) };
      } catch (e) {
        return { raison: e.raison ?? "forme" };
      }
    } else {
      return { raison: "forme" };
    }
    if (Math.abs(maintenant - lu.ts) > FENETRE_COMMANDE_MS) return { raison: "fraicheur" };
    return lu;
  }

  /** Oublie les nonces dont la commande ne pourrait plus être acceptée de toute façon. */
  function purger(maintenant) {
    for (const [n, ts] of vus) if (Math.abs(maintenant - ts) > FENETRE_COMMANDE_MS) vus.delete(n);
  }

  return {
    recevable(S, msg, maintenant) {
      const lu = lire(S, msg, maintenant);
      if (lu.raison) return { recevable: false, raison: lu.raison };
      if (vus.has(lu.nonce)) return { recevable: false, raison: "rejeu" };
      return { recevable: true };
    },
    accepter(S, msg, maintenant) {
      const lu = lire(S, msg, maintenant);
      if (lu.raison) return { accepte: false, raison: lu.raison };
      purger(maintenant);
      if (vus.has(lu.nonce)) return { accepte: false, raison: "rejeu" };
      if (vus.size >= capacite) return { accepte: false, raison: "capacite" };
      vus.set(lu.nonce, lu.ts);
      if (lu.cmd === "ring" || lu.cmd === "stop") {
        // Nonce retenu même si l'ordre la refuse : elle ne pourra pas revenir plus tard.
        if (lu.ts < dernierOrdre) return { accepte: false, raison: "ordre" };
        dernierOrdre = lu.ts;
      }
      return { accepte: true, ...lu };
    },
    nonces: () => Object.fromEntries(vus),
    get dernierTsOrdre() {
      return dernierOrdre;
    },
  };
}

// ---------------------------------------------------------------------------
// États : clair bourré à taille fixe (app 1.1 et plus) et message exact
// ---------------------------------------------------------------------------

/**
 * Bourre un clair JSON (objet) avec un champ "pad" d'espaces pour qu'il fasse exactement
 * `taille` octets UTF-8 (comme Proximite.bourrerEtat de l'app). Rendu tel quel s'il est trop long.
 */
export function bourrerEtat(texte, taille = TAILLE_CLAIR_ETAT) {
  const t = String(texte).trimEnd();
  if (!t.endsWith("}")) throw new Error("Objet JSON attendu");
  const corps = t.slice(0, -1);
  const entete = corps.trimEnd().endsWith("{") ? '"pad":"' : ',"pad":"';
  const manque = taille - Buffer.byteLength(corps, "utf8") - entete.length - 2;
  if (manque < 0) return String(texte);
  return `${corps}${entete}${" ".repeat(manque)}"}`;
}

/** Chiffre un clair d'état déjà sérialisé (et bourré) : { nonce, enc }, comme encryptState. */
export function chiffrerEtatTexte(S, id, texte, nonce = randomBytes(12)) {
  if (!estIdValide(id)) throw new Error("Identifiant invalide");
  const iv = nonceEnOctets(nonce, 12);
  const chiffreur = createCipheriv("aes-256-gcm", deriveKeys(S).enc, iv);
  chiffreur.setAAD(Buffer.from(id, "utf8"));
  const enc = Buffer.concat([chiffreur.update(String(texte), "utf8"), chiffreur.final(), chiffreur.getAuthTag()]);
  return { nonce: iv.toString("hex"), enc: enc.toString("base64") };
}

/** Message exact d'un état, écrit sans échappement (508 octets avec un clair de 320). */
export function texteEtat({ nonce, enc }) {
  if (typeof nonce !== "string" || !RE_NONCE_ETAT.test(nonce)) throw new Error("Nonce d'état invalide");
  if (typeof enc !== "string" || !/^[A-Za-z0-9+/]+={0,2}$/.test(enc)) throw new Error("enc invalide");
  return `{"type":"state","nonce":"${nonce.toLowerCase()}","enc":"${enc}"}`;
}
