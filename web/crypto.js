// Cryptographie de Sonne côté navigateur (voir docs/PROTOCOLE.md).
// Module ES sans dépendance au DOM : il tourne dans le navigateur et dans Node 22
// (globalThis.crypto.subtle). Il est vérifié contre docs/VECTEURS.json par
// relay/test/web-crypto.test.js, comme relay/crypto.js et l'app Android.
//
// Les secrets et les clés sont des Uint8Array. Les fonctions qui utilisent
// WebCrypto sont asynchrones.

import { MOTS } from "./bip39-english.js";

/** Taille du secret S en octets (128 bits). */
export const TAILLE_SECRET = 16;

/** Commandes reconnues par le téléphone. */
export const COMMANDES = Object.freeze(["ring", "stop", "ping"]);

const RE_ID = /^[0-9a-f]{32}$/;
const RE_NONCE_ETAT = /^[0-9a-f]{24}$/;

const subtle = () => {
  const s = globalThis.crypto?.subtle;
  if (!s) throw new Error("WebCrypto unavailable: open the page over HTTPS or on localhost");
  return s;
};
const utf8 = (texte) => new TextEncoder().encode(texte);

// ---------------------------------------------------------------------------
// Conversions
// ---------------------------------------------------------------------------

export function octetsVersHex(octets) {
  return Array.from(new Uint8Array(octets), (o) => o.toString(16).padStart(2, "0")).join("");
}

export function hexVersOctets(hex) {
  if (typeof hex !== "string" || hex.length % 2 !== 0 || !/^[0-9a-f]*$/i.test(hex)) {
    throw new Error("Invalid hexadecimal");
  }
  const octets = new Uint8Array(hex.length / 2);
  for (let i = 0; i < octets.length; i++) octets[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return octets;
}

export function octetsVersBase64(octets) {
  let binaire = "";
  for (const o of new Uint8Array(octets)) binaire += String.fromCharCode(o);
  return btoa(binaire);
}

export function base64VersOctets(texte) {
  const binaire = atob(texte);
  const octets = new Uint8Array(binaire.length);
  for (let i = 0; i < binaire.length; i++) octets[i] = binaire.charCodeAt(i);
  return octets;
}

export function octetsVersBase64url(octets) {
  return octetsVersBase64(octets).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64urlVersOctets(texte) {
  if (!/^[A-Za-z0-9_-]*={0,2}$/.test(texte)) throw new Error("Invalid base64url");
  const b64 = texte.replace(/=+$/, "").replace(/-/g, "+").replace(/_/g, "/");
  return base64VersOctets(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
}

/** Aléa cryptographique. */
export function aleatoire(taille) {
  return globalThis.crypto.getRandomValues(new Uint8Array(taille));
}

/** Normalise S : Uint8Array de 16 octets ou chaîne hex de 32 caractères. Renvoie une copie. */
function secret(S) {
  const octets = typeof S === "string" ? hexVersOctets(S) : S instanceof Uint8Array ? new Uint8Array(S) : null;
  if (!octets || octets.length !== TAILLE_SECRET) throw new Error("Invalid key: 16 bytes expected");
  return octets;
}

function nonceEnOctets(nonce, taille) {
  const octets = typeof nonce === "string" ? hexVersOctets(nonce) : nonce instanceof Uint8Array ? nonce : null;
  if (!octets || octets.length !== taille) throw new Error(`Invalid nonce: ${taille} bytes expected`);
  return octets;
}

// ---------------------------------------------------------------------------
// Identifiant et clés
// ---------------------------------------------------------------------------

/** Preuve de téléphone P = HKDF-SHA256(S, sel vide, "sonne-phone", 16 octets) (protocole v2). */
export async function derivePreuve(S) {
  return hkdf(S, "sonne-phone", 16);
}

/** id = hex(SHA-256("sonne-id" ‖ P))[0:32], avec P dérivée de S. */
export async function deriveId(S) {
  const p = await derivePreuve(S);
  const prefixe = utf8("sonne-id");
  const tout = new Uint8Array(prefixe.length + p.length);
  tout.set(prefixe, 0);
  tout.set(p, prefixe.length);
  return octetsVersHex(await subtle().digest("SHA-256", tout)).slice(0, 32);
}

export function estIdValide(id) {
  return typeof id === "string" && RE_ID.test(id);
}

/** HKDF-SHA256(S, sel vide, info), sortie de `longueur` octets (32 par défaut). */
async function hkdf(S, info, longueur = 32) {
  const base = await subtle().importKey("raw", secret(S), "HKDF", false, ["deriveBits"]);
  const bits = await subtle().deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: utf8(info) },
    base,
    longueur * 8,
  );
  return new Uint8Array(bits);
}

/** K_sig et K_enc en octets bruts : HKDF-SHA256(S, sel vide, info, 32 octets). */
export async function deriveKeys(S) {
  return { sig: await hkdf(S, "sonne-sig"), enc: await hkdf(S, "sonne-enc") };
}

/**
 * Prépare tout ce qu'il faut pour un téléphone : id et clés WebCrypto non exportables
 * (cleSig, cleEnc, et cleCmd pour les commandes chiffrées v3).
 * À appeler une fois par téléphone, puis passer le résultat à chiffrerAvec, signerAvec ou dechiffrerAvec.
 */
export async function preparer(S) {
  const s = secret(S);
  const { sig, enc } = await deriveKeys(s);
  const cleSig = await subtle().importKey("raw", sig, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const cleEnc = await subtle().importKey("raw", enc, "AES-GCM", false, ["encrypt", "decrypt"]);
  sig.fill(0);
  enc.fill(0);
  const cleCmd = await cleCommandeDepuisSig(cleSig);
  return { id: await deriveId(s), cleSig, cleEnc, cleCmd };
}

// ---------------------------------------------------------------------------
// Protocole v3 : commandes chiffrées (docs/PROTOCOLE.md, « Protocole v3 : commandes chiffrées »)
//
// Le relais ne voit plus qu'un message de taille fixe, toujours 172 octets :
//   {"type":"cmd","v":3,"nonce":"<12 octets en hex>","enc":"<base64 de 80 octets>"}
// enc = AES-256-GCM(K_cmd, iv = nonce, aad = "sonne-cmd-v3|" + id, clair de 64 octets).
// K_cmd = HKDF-Expand(K_sig, "sonne-cmd-v3", 32) = HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01) :
// la page la calcule avec sa clé HMAC non exportable, sans jamais avoir S.
// ---------------------------------------------------------------------------

/** Version du format de commande chiffrée : champ v du message et premier octet du clair. */
export const VERSION_COMMANDE = 3;
/** Taille fixe du clair d'une commande v3, en octets. */
export const TAILLE_CLAIR_COMMANDE = 64;
/** Taille du nonce d'une commande v3 (IV AES-GCM, identifiant d'anti rejeu et valeur de l'ack). */
export const TAILLE_NONCE_COMMANDE = 12;
/** Longueur de enc en base64 standard (80 octets : 107 caractères puis un « = »). */
export const LONGUEUR_ENC_COMMANDE = 108;
/** Taille exacte du message d'une commande v3, en octets. */
export const TAILLE_MESSAGE_COMMANDE = 172;
/** Code de chaque commande dans le clair v3 (octet 1). « ping » est la vérification d'état. */
export const CODES_COMMANDE = Object.freeze({ ring: 1, stop: 2, ping: 3 });
/** Lampe torche demandée (octet 12) : réglage du téléphone, allumée, éteinte. */
export const LAMPE = Object.freeze({ reglage: 0, allumee: 1, eteinte: 2 });
/** Durée de sonnerie demandée (octets 10 et 11) : 0 = réglage du téléphone, sinon 60 à 600 s. */
export const DUREE_MIN_S = 60;
export const DUREE_MAX_S = 600;
/** Champ de l'état par lequel le téléphone annonce les commandes v3. */
export const CHAMP_CAPACITE = "cmdv";

const INFO_CLE_COMMANDE = "sonne-cmd-v3";
const PREFIXE_AAD_COMMANDE = "sonne-cmd-v3|";
const RE_NONCE_V3 = /^[0-9a-f]{24}$/;
const RE_ENC_V3 = /^[A-Za-z0-9+/]{107}=$/;

/**
 * Octets bruts de K_cmd : HMAC-SHA256(K_sig, "sonne-cmd-v3" ‖ 0x01), c'est-à-dire HKDF-Expand
 * (RFC 5869) sur un seul bloc. cleSig est la clé HMAC (non exportable) de K_sig.
 * L'appelant doit effacer le résultat (fill(0)) dès qu'il n'en a plus besoin.
 */
export async function octetsCleCommande(cleSig) {
  const info = utf8(INFO_CLE_COMMANDE);
  const entree = new Uint8Array(info.length + 1);
  entree.set(info, 0);
  entree[info.length] = 0x01;
  return new Uint8Array(await subtle().sign("HMAC", cleSig, entree));
}

/**
 * K_cmd en clé AES-GCM non exportable, réservée au chiffrement, depuis la clé HMAC de K_sig.
 * Sert dans preparer() et pour les clés rangées avant la 1.6 (sans cleCmd) : aucun nouvel appairage.
 */
export async function cleCommandeDepuisSig(cleSig) {
  const octets = await octetsCleCommande(cleSig);
  try {
    return await subtle().importKey("raw", octets, "AES-GCM", false, ["encrypt"]);
  } finally {
    octets.fill(0);
  }
}

/** Complète des clés rangées avant la 1.6 ({ id, cleSig, cleEnc }) avec cleCmd. Renvoie un nouvel objet. */
export async function completerCles(cles) {
  if (!cles || cles.cleCmd) return cles;
  return { ...cles, cleCmd: await cleCommandeDepuisSig(cles.cleSig) };
}

/** Paramètres permis à l'envoi : 0 ou 60..600 s ; lampe 0, 1 ou 2 ; tous deux à 0 pour stop et ping. */
function verifierParametres(cmd, duree, lampe) {
  if (!Object.prototype.hasOwnProperty.call(CODES_COMMANDE, cmd)) throw new Error(`Unknown command: ${cmd}`);
  if (!Number.isInteger(duree) || (duree !== 0 && (duree < DUREE_MIN_S || duree > DUREE_MAX_S))) {
    throw new Error(`Invalid duration: 0 or ${DUREE_MIN_S} to ${DUREE_MAX_S} s`);
  }
  if (!Object.values(LAMPE).includes(lampe)) throw new Error("Invalid flashlight: 0, 1 or 2");
  if (cmd !== "ring" && (duree !== 0 || lampe !== 0)) throw new Error("Duration and flashlight only apply to ring");
}

/**
 * Clair d'une commande v3, 64 octets :
 *   0      version (3)
 *   1      commande (1 ring, 2 stop, 3 ping)
 *   2..9   ts, millisecondes Unix, entier non signé big-endian
 *   10..11 durée de sonnerie en secondes, big-endian (0 = réglage du téléphone)
 *   12     lampe (0 = réglage du téléphone, 1 = allumée, 2 = éteinte)
 *   13..63 réservé, à zéro
 */
export function clairCommande(cmd, ts, { duree = 0, lampe = 0 } = {}) {
  verifierParametres(cmd, duree, lampe);
  if (!Number.isSafeInteger(ts) || ts < 0) throw new Error("Invalid timestamp");
  const clair = new Uint8Array(TAILLE_CLAIR_COMMANDE);
  const vue = new DataView(clair.buffer);
  clair[0] = VERSION_COMMANDE;
  clair[1] = CODES_COMMANDE[cmd];
  vue.setBigUint64(2, BigInt(ts), false);
  vue.setUint16(10, duree, false);
  clair[12] = lampe;
  return clair;
}

/**
 * Commande v3 chiffrée avec des clés préparées (cles.id et cles.cleCmd) : { type, v, nonce, enc },
 * exactement ce que rend chiffrerCommandeAvec de relay/crypto.js.
 * options : nonce (12 octets, hex ou octets, aléatoire par défaut), duree (0 ou 60..600 s), lampe (0, 1, 2).
 */
export async function chiffrerAvec(cles, cmd, ts = Date.now(), { nonce, duree = 0, lampe = 0 } = {}) {
  if (!estIdValide(cles?.id)) throw new Error("Invalid identifier");
  if (!cles.cleCmd) throw new Error("Command key missing");
  const clair = clairCommande(cmd, ts, { duree, lampe });
  const iv = nonceEnOctets(nonce ?? aleatoire(TAILLE_NONCE_COMMANDE), TAILLE_NONCE_COMMANDE);
  try {
    const chiffre = await subtle().encrypt(
      { name: "AES-GCM", iv, additionalData: utf8(PREFIXE_AAD_COMMANDE + cles.id), tagLength: 128 },
      cles.cleCmd,
      clair,
    );
    return { type: "cmd", v: VERSION_COMMANDE, nonce: octetsVersHex(iv), enc: octetsVersBase64(chiffre) };
  } finally {
    clair.fill(0);
  }
}

/** Commande v3 chiffrée directement depuis S (tests et outils). */
export async function chiffrerCommande(S, cmd, ts = Date.now(), options = {}) {
  return chiffrerAvec(await preparer(S), cmd, ts, options);
}

/**
 * Message exact d'une commande v3, tel qu'il part vers le relais : 172 octets, écrit à la main,
 * sans espace, dans l'ordre du protocole. Lève une erreur si la commande est mal formée.
 */
export function texteCommande(msg) {
  const { nonce, enc } = msg ?? {};
  const bien =
    msg?.type === "cmd" &&
    msg.v === VERSION_COMMANDE &&
    typeof nonce === "string" &&
    RE_NONCE_V3.test(nonce) &&
    typeof enc === "string" &&
    RE_ENC_V3.test(enc) &&
    enc.length === LONGUEUR_ENC_COMMANDE;
  if (!bien) throw new Error("Malformed v3 command");
  const texte = `{"type":"cmd","v":${VERSION_COMMANDE},"nonce":"${nonce}","enc":"${enc}"}`;
  if (texte.length !== TAILLE_MESSAGE_COMMANDE) throw new Error("Malformed v3 command");
  return texte;
}

/** Vrai si un état déchiffré annonce les commandes v3 (champ cmdv ≥ 3). */
export function accepteV3(etat) {
  const v = etat?.[CHAMP_CAPACITE];
  return Number.isInteger(v) && v >= VERSION_COMMANDE;
}

/**
 * Format des commandes vers un téléphone (PROTOCOLE.md, « Transition, puis coupure ») :
 * 3 si le téléphone a annoncé cmdv ≥ 3 dans un état frais et authentifié (drapeau gardé pour
 * toujours), si le relais vient de refuser la v2 (400), ou après la coupure ; 2 sinon.
 * Jamais de retour à 2 une fois l'une de ces conditions vraie.
 */
export function formatCommande({ cmdv3 = false, relaisRefuseV2 = false, coupure = false } = {}) {
  return cmdv3 || relaisRefuseV2 || coupure ? VERSION_COMMANDE : 2;
}

// ---------------------------------------------------------------------------
// Commandes signées (v2, téléphones en 1.5 ou avant, jusqu'à la coupure) et états chiffrés
// ---------------------------------------------------------------------------

/** Commande signée avec des clés préparées. nonce : 16 octets (hex ou octets), aléatoire par défaut. */
export async function signerAvec(cles, cmd, ts = Date.now(), nonce = aleatoire(16)) {
  if (!COMMANDES.includes(cmd)) throw new Error(`Unknown command: ${cmd}`);
  if (!Number.isSafeInteger(ts) || ts < 0) throw new Error("Invalid timestamp");
  const nonceHex = octetsVersHex(nonceEnOctets(nonce, 16));
  const sig = await subtle().sign("HMAC", cles.cleSig, utf8(`${cmd}|${ts}|${nonceHex}`));
  return { type: "cmd", cmd, ts, nonce: nonceHex, sig: octetsVersHex(sig) };
}

/** Commande signée directement depuis S : { type: "cmd", cmd, ts, nonce, sig }. */
export async function signCmd(S, cmd, ts = Date.now(), nonce = aleatoire(16)) {
  return signerAvec(await preparer(S), cmd, ts, nonce);
}

/** Déchiffre un état avec des clés préparées. Lève une erreur si la clé ou l'id (AAD) ne correspondent pas. */
export async function dechiffrerAvec(cles, msg) {
  if (!msg || typeof msg.nonce !== "string" || !RE_NONCE_ETAT.test(msg.nonce)) {
    throw new Error("Invalid status: 12-byte nonce expected");
  }
  if (typeof msg.enc !== "string") throw new Error("Invalid status: enc field missing");
  let donnees;
  try {
    donnees = base64VersOctets(msg.enc);
  } catch {
    throw new Error("Invalid status: base64 expected");
  }
  if (donnees.length < 16) throw new Error("Invalid status: too short");
  let clair;
  try {
    clair = await subtle().decrypt(
      { name: "AES-GCM", iv: hexVersOctets(msg.nonce), additionalData: utf8(cles.id), tagLength: 128 },
      cles.cleEnc,
      donnees,
    );
  } catch {
    throw new Error("Unreadable status: authentication failed");
  }
  return JSON.parse(new TextDecoder().decode(clair));
}

/** Déchiffre un état { nonce, enc } depuis S. L'id sert d'AAD et doit être celui de S. */
export async function decryptState(S, id, msg) {
  const cles = await preparer(S);
  return dechiffrerAvec({ ...cles, id }, msg);
}

/** Chiffre un état (utile aux tests et aux outils) : renvoie { nonce, enc }. */
export async function encryptState(S, id, obj, nonce = aleatoire(12)) {
  if (!estIdValide(id)) throw new Error("Invalid identifier");
  const iv = nonceEnOctets(nonce, 12);
  const { cleEnc } = await preparer(S);
  const chiffre = await subtle().encrypt(
    { name: "AES-GCM", iv, additionalData: utf8(id), tagLength: 128 },
    cleEnc,
    utf8(JSON.stringify(obj)),
  );
  return { nonce: octetsVersHex(iv), enc: octetsVersBase64(chiffre) };
}

// ---------------------------------------------------------------------------
// 12 mots BIP39
// ---------------------------------------------------------------------------

async function controle4bits(s) {
  return new Uint8Array(await subtle().digest("SHA-256", s))[0] >> 4;
}

/** S vers 12 mots BIP39 anglais. */
export async function toWords(S) {
  const s = secret(S);
  let bits = 0n;
  for (const o of s) bits = (bits << 8n) | BigInt(o);
  bits = (bits << 4n) | BigInt(await controle4bits(s));
  const mots = [];
  for (let i = 11; i >= 0; i--) mots.push(MOTS[Number((bits >> BigInt(i * 11)) & 0x7ffn)]);
  return mots;
}

/** 12 mots (tableau ou texte) vers S. Insensible à la casse et aux espaces. */
export async function fromWords(words) {
  const liste = (Array.isArray(words) ? words.join(" ") : String(words ?? ""))
    .trim()
    .toLowerCase()
    .split(/[\s,]+/)
    .filter(Boolean);
  if (liste.length !== 12) throw new Error("Enter exactly 12 words");
  let bits = 0n;
  for (const mot of liste) {
    const index = MOTS.indexOf(mot);
    if (index < 0) throw new Error(`Unknown word: ${mot}`);
    bits = (bits << 11n) | BigInt(index);
  }
  const controle = Number(bits & 0xfn);
  let entropie = bits >> 4n;
  const s = new Uint8Array(TAILLE_SECRET);
  for (let i = TAILLE_SECRET - 1; i >= 0; i--) {
    s[i] = Number(entropie & 0xffn);
    entropie >>= 8n;
  }
  if (controle !== (await controle4bits(s))) throw new Error("These 12 words do not match (checksum). Check them for a typo");
  return s;
}

// ---------------------------------------------------------------------------
// Lien d'appairage : https://sonne.sidali.dev/#s=<base64url(S)>
// ---------------------------------------------------------------------------

export function toLink(S, base = "https://sonne.sidali.dev/") {
  const racine = base.endsWith("/") ? base : `${base}/`;
  return `${racine}#s=${octetsVersBase64url(secret(S))}`;
}

/** Lit S dans un fragment seul (« #s=… » ou « s=… »). */
export function secretDuFragment(fragment) {
  const params = new URLSearchParams(String(fragment ?? "").replace(/^#/, ""));
  const valeur = params.get("s");
  if (!valeur || !/^[A-Za-z0-9_-]+={0,2}$/.test(valeur)) throw new Error("Invalid link: the key is missing or malformed");
  const s = base64urlVersOctets(valeur);
  if (s.length !== TAILLE_SECRET) throw new Error("Invalid link: the key has the wrong size");
  return s;
}

/** Lien complet vers S. */
export function fromLink(url) {
  const texte = String(url ?? "");
  const diese = texte.indexOf("#");
  if (diese < 0) throw new Error("Invalid link: fragment missing");
  return secretDuFragment(texte.slice(diese));
}

/**
 * Lit S dans ce que l'utilisateur a collé : un lien d'appairage ou les 12 mots.
 * Renvoie { S, origine } où origine est l'adresse du relais du lien (ou null pour les mots).
 */
export async function lireSaisie(texte) {
  const t = String(texte ?? "").trim();
  if (!t) throw new Error("Paste a link or your 12 words");
  if (t.includes("#")) {
    const S = fromLink(t);
    let origine = null;
    try {
      origine = new URL(t).origin;
    } catch {
      origine = null;
    }
    return { S, origine };
  }
  return { S: await fromWords(t), origine: null };
}
