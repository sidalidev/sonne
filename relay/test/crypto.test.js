// Tests de relay/crypto.js (node --test).
// Les vecteurs de docs/VECTEURS.json sont aussi recalculés avec WebCrypto,
// une implémentation indépendante, celle qu'utilisera la page web.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash, createHmac, hkdfSync, randomBytes } from "node:crypto";
import {
  deriveId,
  derivePreuve,
  idDepuisPreuve,
  deriveKeys,
  signCmd,
  verifyCmd,
  encryptState,
  decryptState,
  toWords,
  fromWords,
  toLink,
  fromLink,
  genererSecret,
  estIdValide,
  deriveCleBle,
  deriveBleId,
  fenetreBle,
  DUREE_FENETRE_BLE_MS,
  hkdfExpand,
  deriveCleCommande,
  cleCommandeDepuisSig,
  aadCommande,
  clairCommande,
  lireClairCommande,
  parametresSonnerie,
  chiffrerClairCommande,
  chiffrerCommande,
  chiffrerCommandeAvec,
  dechiffrerCommande,
  dechiffrerCommandeAvec,
  formeCommandeV3,
  texteCommande,
  creerFiltreCommandes,
  accepteV3,
  bourrerEtat,
  chiffrerEtatTexte,
  texteEtat,
  TAILLE_MESSAGE_COMMANDE,
  TAILLE_MESSAGE_ETAT,
  TAILLE_CLAIR_ETAT,
  LONGUEUR_ENC_COMMANDE,
  FENETRE_COMMANDE_MS,
  CODES_COMMANDE,
} from "../crypto.js";
import { MOTS } from "../bip39-english.js";

const V = JSON.parse(readFileSync(new URL("../../docs/VECTEURS.json", import.meta.url), "utf8"));
const subtle = globalThis.crypto.subtle;
const hex = (octets) => Buffer.from(octets).toString("hex");

// ---------------------------------------------------------------------------
// Vecteurs
// ---------------------------------------------------------------------------

test("vecteur : preuve P et id (protocole v2)", async () => {
  assert.equal(hex(derivePreuve(V.S)), V.P);
  // P recalculée avec WebCrypto : HKDF-SHA256(S, sel vide, "sonne-phone", 16 octets).
  const base = await subtle.importKey("raw", Buffer.from(V.S, "hex"), "HKDF", false, ["deriveBits"]);
  const bits = await subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: Buffer.from("sonne-phone") },
    base,
    128,
  );
  assert.equal(hex(bits), V.P);
  assert.equal(deriveId(V.S), V.id);
  assert.equal(idDepuisPreuve(Buffer.from(V.P, "hex")), V.id);
  // Recalcul direct depuis la définition : SHA-256("sonne-id" ‖ P), 32 premiers hex.
  const attendu = createHash("sha256")
    .update(Buffer.concat([Buffer.from("sonne-id"), Buffer.from(V.P, "hex")]))
    .digest("hex")
    .slice(0, 32);
  assert.equal(V.id, attendu);
  assert.ok(estIdValide(V.id));
  assert.throws(() => idDepuisPreuve(Buffer.alloc(15)), /Preuve invalide/);
});

test("vecteur : K_sig et K_enc (recalculés avec WebCrypto HKDF)", async () => {
  const { sig, enc } = deriveKeys(V.S);
  assert.equal(hex(sig), V.K_sig);
  assert.equal(hex(enc), V.K_enc);
  const base = await subtle.importKey("raw", Buffer.from(V.S, "hex"), "HKDF", false, ["deriveBits"]);
  for (const [info, attendu] of [["sonne-sig", V.K_sig], ["sonne-enc", V.K_enc]]) {
    const bits = await subtle.deriveBits(
      { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: new TextEncoder().encode(info) },
      base,
      256,
    );
    assert.equal(hex(bits), attendu);
  }
});

test("vecteur : 12 mots", () => {
  assert.deepEqual(toWords(V.S), V.mots);
  assert.equal(hex(fromWords(V.mots)), V.S);
});

test("vecteur : lien", () => {
  assert.equal(toLink(V.S), V.lien);
  assert.equal(hex(fromLink(V.lien)), V.S);
});

test("vecteur : commande ring signée (recalculée avec WebCrypto HMAC)", async () => {
  const m = V.commande.message;
  assert.deepEqual(signCmd(V.S, m.cmd, m.ts, m.nonce), m);
  assert.equal(V.commande.texte_signe, `${m.cmd}|${m.ts}|${m.nonce}`);
  assert.equal(verifyCmd(V.S, m), true);
  const cle = await subtle.importKey("raw", Buffer.from(V.K_sig, "hex"), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const sig = await subtle.sign("HMAC", cle, new TextEncoder().encode(V.commande.texte_signe));
  assert.equal(hex(sig), m.sig);
});

test("vecteur : état chiffré (octet pour octet, et déchiffré avec WebCrypto)", async () => {
  const m = V.etat.message;
  const obj = JSON.parse(V.etat.plaintext);
  assert.equal(JSON.stringify(obj), V.etat.plaintext, "la sérialisation doit être stable");
  const produit = encryptState(V.S, V.id, obj, m.nonce);
  assert.equal(produit.nonce, m.nonce);
  assert.equal(produit.enc, m.enc);
  assert.deepEqual(decryptState(V.S, V.id, m), obj);

  const cle = await subtle.importKey("raw", Buffer.from(V.K_enc, "hex"), "AES-GCM", false, ["decrypt"]);
  const clair = await subtle.decrypt(
    { name: "AES-GCM", iv: Buffer.from(m.nonce, "hex"), additionalData: new TextEncoder().encode(V.etat.aad) },
    cle,
    Buffer.from(m.enc, "base64"),
  );
  assert.equal(new TextDecoder().decode(clair), V.etat.plaintext);
});

test("vecteur : balise Bluetooth, id_ble tournant (recalculé avec WebCrypto)", async () => {
  const B = V.ble;
  // La fenêtre du vecteur est celle de l'horodatage de la commande.
  assert.equal(B.w, 1991444);
  assert.equal(fenetreBle(V.commande.message.ts), B.w);
  assert.equal(fenetreBle(B.w * DUREE_FENETRE_BLE_MS), B.w);
  assert.equal(fenetreBle(B.w * DUREE_FENETRE_BLE_MS - 1), B.w - 1);

  assert.equal(hex(deriveCleBle(V.S)), B.B);
  assert.equal(hex(deriveBleId(V.S, B.w)), B.id_ble);
  assert.equal(hex(deriveBleId(V.S, B.w - 1)), B.id_ble_precedent);
  assert.equal(hex(deriveBleId(V.S, B.w + 1)), B.id_ble_suivant);
  assert.equal(B.donnees_service, B.id_ble + "01");

  // Recalcul indépendant : HKDF puis HMAC avec WebCrypto, w écrit à la main en big-endian.
  const base = await subtle.importKey("raw", Buffer.from(V.S, "hex"), "HKDF", false, ["deriveBits"]);
  const cle = await subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: new TextEncoder().encode("sonne-ble") },
    base,
    256,
  );
  assert.equal(hex(cle), B.B);
  const fenetre = new Uint8Array(8);
  let reste = B.w;
  for (let i = 7; i >= 0; i--) {
    fenetre[i] = reste % 256;
    reste = Math.floor(reste / 256);
  }
  assert.equal(hex(fenetre), B.w_octets);
  const cleHmac = await subtle.importKey("raw", cle, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await subtle.sign("HMAC", cleHmac, fenetre);
  assert.equal(hex(mac).slice(0, 16), B.id_ble);
});

test("balise Bluetooth : propriétés de l'id_ble", () => {
  const S = genererSecret();
  const w = fenetreBle();
  const id = deriveBleId(S, w);
  assert.equal(id.length, 8);
  // Déterministe pour un même S et une même fenêtre.
  assert.deepEqual(deriveBleId(S, w), id);
  // Change à chaque fenêtre et d'un secret à l'autre.
  assert.notDeepEqual(deriveBleId(S, w + 1), id);
  assert.notDeepEqual(deriveBleId(genererSecret(), w), id);
  // B est distincte des autres clés dérivées de S.
  const { sig, enc } = deriveKeys(S);
  assert.notDeepEqual(deriveCleBle(S), sig);
  assert.notDeepEqual(deriveCleBle(S), enc);
  // Entrées invalides.
  assert.throws(() => deriveBleId(S, -1), /Fenêtre invalide/);
  assert.throws(() => deriveBleId(S, 1.5), /Fenêtre invalide/);
  assert.throws(() => deriveBleId(S, "1991444"), /Fenêtre invalide/);
  assert.throws(() => deriveBleId("0011", w), /Secret invalide/);
  assert.throws(() => fenetreBle(-1), /Horodatage invalide/);
});

// ---------------------------------------------------------------------------
// BIP39 : vecteurs officiels (Trezor, entropie 128 bits)
// ---------------------------------------------------------------------------

test("liste BIP39 : 2048 mots uniques, triés", () => {
  assert.equal(MOTS.length, 2048);
  assert.equal(new Set(MOTS).size, 2048);
  assert.deepEqual([...MOTS].sort(), [...MOTS]);
  assert.equal(MOTS[0], "abandon");
  assert.equal(MOTS[2047], "zoo");
});

test("BIP39 : vecteurs officiels", () => {
  const officiels = [
    ["00000000000000000000000000000000", "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"],
    ["7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", "legal winner thank year wave sausage worth useful legal winner thank yellow"],
    ["80808080808080808080808080808080", "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"],
    ["ffffffffffffffffffffffffffffffff", "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"],
    ["9e885d952ad362caeb4efe34a8e91bd2", "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"],
  ];
  for (const [entropie, phrase] of officiels) {
    assert.equal(toWords(entropie).join(" "), phrase);
    assert.equal(hex(fromWords(phrase)), entropie);
  }
});

// ---------------------------------------------------------------------------
// Allers-retours
// ---------------------------------------------------------------------------

test("aller-retour mots sur 200 secrets aléatoires", () => {
  for (let i = 0; i < 200; i++) {
    const S = genererSecret();
    const mots = toWords(S);
    assert.equal(mots.length, 12);
    assert.deepEqual(fromWords(mots), S);
    // Tolérance : majuscules et espaces superflus.
    assert.deepEqual(fromWords(`  ${mots.join("   ").toUpperCase()} `), S);
  }
});

test("aller-retour lien sur 200 secrets aléatoires", () => {
  for (let i = 0; i < 200; i++) {
    const S = genererSecret();
    const lien = toLink(S);
    assert.ok(lien.startsWith("https://sonne.sidali.dev/#s="));
    assert.ok(!lien.split("#s=")[1].includes("="), "pas de remplissage base64");
    assert.deepEqual(fromLink(lien), S);
  }
  // Relais auto-hébergé, avec ou sans barre finale.
  const S = genererSecret();
  assert.equal(toLink(S, "https://exemple.org"), toLink(S, "https://exemple.org/"));
  assert.deepEqual(fromLink(toLink(S, "http://localhost:7700")), S);
});

test("aller-retour état chiffré avec nonce aléatoire", () => {
  const S = genererSecret();
  const id = deriveId(S);
  const etat = { ts: Date.now(), ringing: true, battery: 12, charging: true, app: "1.0.0", ack: "ab".repeat(16), model: "Pixel 10 Pro" };
  const m = encryptState(S, id, etat);
  assert.match(m.nonce, /^[0-9a-f]{24}$/);
  assert.deepEqual(decryptState(S, id, m), etat);
});

// ---------------------------------------------------------------------------
// Rejets
// ---------------------------------------------------------------------------

test("rejet d'une signature fausse", () => {
  const m = V.commande.message;
  // Un seul caractère de la signature modifié.
  const dernier = m.sig.at(-1) === "0" ? "1" : "0";
  assert.equal(verifyCmd(V.S, { ...m, sig: m.sig.slice(0, -1) + dernier }), false);
  // Champs signés modifiés.
  assert.equal(verifyCmd(V.S, { ...m, cmd: "stop" }), false);
  assert.equal(verifyCmd(V.S, { ...m, ts: m.ts + 1 }), false);
  assert.equal(verifyCmd(V.S, { ...m, nonce: "ff" + m.nonce.slice(2) }), false);
  // Mauvais secret.
  assert.equal(verifyCmd(genererSecret(), m), false);
  // Messages mal formés : jamais d'exception.
  assert.equal(verifyCmd(V.S, null), false);
  assert.equal(verifyCmd(V.S, { ...m, sig: "zz" }), false);
  assert.equal(verifyCmd(V.S, { ...m, cmd: "explose" }), false);
  assert.equal(verifyCmd(V.S, { ...m, ts: "1792300000000" }), false);
  assert.equal(verifyCmd(V.S, { ...m, type: "state" }), false);
});

test("rejet d'une somme de contrôle BIP39 fausse", () => {
  // Dernier mot décalé d'un rang : l'entropie reste la même, seuls les 4 bits
  // de contrôle (les 4 bits de poids faible du dernier mot) changent.
  const faux = [...V.mots.slice(0, 11), MOTS[MOTS.indexOf(V.mots[11]) ^ 1]];
  assert.throws(() => fromWords(faux), /Somme de contrôle/);
  // Deux mots échangés.
  const echange = [V.mots[1], V.mots[0], ...V.mots.slice(2)];
  assert.throws(() => fromWords(echange), /Somme de contrôle/);
  // Officiel modifié : "abandon x12" n'est pas valide (le bon dernier mot est "about").
  assert.throws(() => fromWords(Array(12).fill("abandon")), /Somme de contrôle/);
  // Mot inconnu, mauvais nombre de mots.
  assert.throws(() => fromWords([...V.mots.slice(0, 11), "sonne"]), /Mot inconnu/);
  assert.throws(() => fromWords(V.mots.slice(0, 11)), /12 mots/);
});

test("rejet d'un état altéré ou d'un mauvais id", () => {
  const m = V.etat.message;
  const octets = Buffer.from(m.enc, "base64");
  octets[0] ^= 1;
  assert.throws(() => decryptState(V.S, V.id, { ...m, enc: octets.toString("base64") }), /authentification/);
  assert.throws(() => decryptState(V.S, "f".repeat(32), m), /authentification/);
  assert.throws(() => decryptState(genererSecret(), V.id, m), /authentification/);
});

test("rejet de liens et secrets mal formés", () => {
  assert.throws(() => fromLink("https://sonne.sidali.dev/"), /fragment/);
  assert.throws(() => fromLink("https://sonne.sidali.dev/#x=1"), /paramètre s/);
  assert.throws(() => fromLink("https://sonne.sidali.dev/#s=AAEC"), /taille/);
  assert.throws(() => deriveId("0011"), /Secret invalide/);
  assert.throws(() => deriveId(Buffer.alloc(15)), /Secret invalide/);
});

// ---------------------------------------------------------------------------
// Protocole v3 : commandes chiffrées
// ---------------------------------------------------------------------------

const V3 = V.commandes_v3;
const NOMS_V3 = ["v3_ring", "v3_stop", "v3_ping", "v3_ring_parametres"];

test("HKDF-Expand : RFC 5869, cas de test 1, et accord avec hkdfSync de Node", () => {
  const ikm = Buffer.alloc(22, 0x0b);
  const sel = Buffer.from("000102030405060708090a0b0c", "hex");
  const info = Buffer.from("f0f1f2f3f4f5f6f7f8f9", "hex");
  const prk = createHmac("sha256", sel).update(ikm).digest();
  assert.equal(prk.toString("hex"), "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
  const okm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
  assert.equal(hkdfExpand(prk, info, 42).toString("hex"), okm);
  assert.equal(Buffer.from(hkdfSync("sha256", ikm, sel, info, 42)).toString("hex"), okm);
  // 32 octets : un seul bloc, HMAC(PRK, info ‖ 0x01).
  assert.deepEqual(hkdfExpand(prk, info, 32), createHmac("sha256", prk).update(info).update(Buffer.from([1])).digest());
});

test("vecteur v3 : K_cmd depuis S et depuis K_sig (recalculée avec WebCrypto HMAC)", async () => {
  assert.equal(hex(deriveCleCommande(V.S)), V3.K_cmd);
  assert.equal(hex(cleCommandeDepuisSig(Buffer.from(V.K_sig, "hex"))), V3.K_cmd);
  assert.equal(hex(cleCommandeDepuisSig(V.K_sig)), V3.K_cmd);
  // Ce que fait la page avec sa clé HMAC non exportable : HMAC(K_sig, "sonne-cmd-v3" ‖ 0x01).
  const cleSig = await subtle.importKey("raw", Buffer.from(V.K_sig, "hex"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const info = new Uint8Array([...new TextEncoder().encode("sonne-cmd-v3"), 1]);
  assert.equal(hex(await subtle.sign("HMAC", cleSig, info)), V3.K_cmd);
  // Clé à part : ni K_sig, ni K_enc, ni B.
  for (const autre of [V.K_sig, V.K_enc, V.ble.B]) assert.notEqual(V3.K_cmd, autre);
  assert.equal(aadCommande(V.id).toString("utf8"), V3.aad_v3);
  assert.equal(V3.aad_v3, `sonne-cmd-v3|${V.id}`);
});

test("vecteurs v3 : ring, stop, ping et ring avec paramètres, octet pour octet", () => {
  const kCmd = Buffer.from(V3.K_cmd, "hex");
  for (const nom of NOMS_V3) {
    const e = V3[nom];
    // Clair : version, code, ts, durée, lampe, puis 51 octets à zéro.
    const clair = clairCommande(e.cmd, e.ts, { duree: e.duree, lampe: e.lampe });
    assert.equal(hex(clair), e.clair, nom);
    assert.equal(clair.length, 64);
    assert.equal(clair[0], 3);
    assert.equal(clair[1], CODES_COMMANDE[e.cmd]);
    assert.equal(clair.readBigUInt64BE(2), BigInt(e.ts));
    assert.equal(clair.readUInt16BE(10), e.duree);
    assert.equal(clair[12], e.lampe);
    assert.ok(clair.subarray(13).every((o) => o === 0));
    // Chiffrement déterministe avec le nonce du vecteur.
    const m = chiffrerCommandeAvec(kCmd, V.id, e.cmd, e.ts, { nonce: e.message.nonce, duree: e.duree, lampe: e.lampe });
    assert.deepEqual(m, e.message, nom);
    assert.deepEqual(chiffrerCommande(V.S, e.cmd, e.ts, { nonce: e.message.nonce, duree: e.duree, lampe: e.lampe }), e.message);
    // Message exact, 172 octets, et relu par JSON.parse.
    assert.equal(texteCommande(m), e.texte);
    assert.equal(Buffer.byteLength(e.texte), TAILLE_MESSAGE_COMMANDE);
    assert.equal(e.octets, TAILLE_MESSAGE_COMMANDE);
    assert.deepEqual(JSON.parse(e.texte), e.message);
    assert.equal(e.message.enc.length, LONGUEUR_ENC_COMMANDE);
    // Déchiffrement.
    assert.deepEqual(dechiffrerCommande(V.S, e.message), { cmd: e.cmd, ts: e.ts, duree: e.duree, lampe: e.lampe, nonce: e.message.nonce });
    assert.deepEqual(dechiffrerCommandeAvec(kCmd, V.id, JSON.parse(e.texte)).cmd, e.cmd);
  }
  assert.deepEqual(parametresSonnerie(V3.v3_ring_parametres), { dureeS: 300, lampe: false });
  assert.deepEqual(parametresSonnerie(V3.v3_ring), { dureeS: null, lampe: null });
});

test("vecteurs v3 : déchiffrés par une implémentation indépendante (WebCrypto AES-GCM)", async () => {
  const cle = await subtle.importKey("raw", Buffer.from(V3.K_cmd, "hex"), "AES-GCM", false, ["encrypt", "decrypt"]);
  for (const nom of NOMS_V3) {
    const e = V3[nom];
    const clair = await subtle.decrypt(
      { name: "AES-GCM", iv: Buffer.from(e.message.nonce, "hex"), additionalData: new TextEncoder().encode(V3.aad_v3), tagLength: 128 },
      cle,
      Buffer.from(e.message.enc, "base64"),
    );
    assert.equal(hex(clair), e.clair, nom);
    const rechiffre = await subtle.encrypt(
      { name: "AES-GCM", iv: Buffer.from(e.message.nonce, "hex"), additionalData: new TextEncoder().encode(V3.aad_v3), tagLength: 128 },
      cle,
      Buffer.from(e.clair, "hex"),
    );
    assert.equal(Buffer.from(rechiffre).toString("base64"), e.message.enc);
  }
});

test("v3 : rien ne distingue ring, stop, ping ni les paramètres dans le message", () => {
  const S = genererSecret();
  const tailles = new Set();
  const formes = new Set();
  const cas = [
    ["ring", {}],
    ["stop", {}],
    ["ping", {}],
    ["ring", { duree: 60 }],
    ["ring", { duree: 600, lampe: 1 }],
    ["ring", { lampe: 2 }],
  ];
  for (let i = 0; i < 300; i++) {
    const [cmd, p] = cas[i % cas.length];
    const m = chiffrerCommande(S, cmd, Date.now() + i, p);
    const t = texteCommande(m);
    tailles.add(Buffer.byteLength(t));
    formes.add(Object.keys(JSON.parse(t)).join(","));
    assert.match(m.nonce, /^[0-9a-f]{24}$/);
    assert.equal(m.enc.length, LONGUEUR_ENC_COMMANDE);
    assert.equal(dechiffrerCommande(S, m).cmd, cmd);
  }
  assert.deepEqual([...tailles], [TAILLE_MESSAGE_COMMANDE]);
  assert.deepEqual([...formes], ["type,v,nonce,enc"]);
});

test("v3 : chaque cas du vecteur donne l'attendu avec le filtre de référence", () => {
  const messages = Object.fromEntries(NOMS_V3.map((n) => [n, JSON.parse(V3[n].texte)]));
  assert.ok(Object.keys(V3.cas).length >= 15);
  for (const [nom, c] of Object.entries(V3.cas)) {
    const filtre = creerFiltreCommandes({ accepterV2: c.phase === "transition" });
    if (c.avant) assert.equal(filtre.accepter(V.S, messages[c.avant], c.maintenant).accepte, true, `${nom} : ${c.avant}`);
    const msg = JSON.parse(c.texte);
    // Écriture GATT : recevable ne modifie rien et donne la même raison jusqu'au rejeu compris.
    const avantNonces = filtre.nonces();
    const rec = filtre.recevable(V.S, msg, c.maintenant);
    assert.deepEqual(filtre.nonces(), avantNonces, `${nom} : recevable ne modifie rien`);
    const r = filtre.accepter(V.S, msg, c.maintenant);
    assert.equal(r.accepte ? "acceptee" : r.raison, c.attendu, nom);
    if (c.attendu === "acceptee" || c.attendu === "ordre") assert.equal(rec.recevable, true, nom);
    else assert.equal(rec.raison, c.attendu, nom);
  }
  // Une commande acceptée rend ses paramètres.
  const r = creerFiltreCommandes().accepter(V.S, messages.v3_ring_parametres, V3.cas.accepte_parametres.maintenant);
  assert.equal(r.accepte, true);
  assert.equal(r.format, 3);
  assert.equal(r.duree, 300);
  assert.equal(r.lampe, 2);
});

test("v3 : une seule mémoire de nonces pour v2 et v3, ordre commun des ring et stop", () => {
  const filtre = creerFiltreCommandes({ accepterV2: true });
  const t = V.commande.message.ts;
  // v2 ring (ts) puis v3 stop (ts + 1000) : acceptées ; la v3 ring (ts) arrive après : ordre.
  assert.equal(filtre.accepter(V.S, V.commande.message, t + 5000).accepte, true);
  assert.equal(filtre.accepter(V.S, V3.v3_stop.message, t + 5000).accepte, true);
  assert.deepEqual(filtre.accepter(V.S, V3.v3_ring.message, t + 5000), { accepte: false, raison: "ordre" });
  // Le ping n'est pas concerné par l'ordre, et son nonce est retenu.
  assert.equal(filtre.accepter(V.S, V3.v3_ping.message, t + 5000).accepte, true);
  assert.deepEqual(filtre.accepter(V.S, V3.v3_ping.message, t + 5000), { accepte: false, raison: "rejeu" });
  assert.equal(filtre.dernierTsOrdre, t + 1000);
  assert.deepEqual(Object.keys(filtre.nonces()).sort(), [V.commande.message.nonce, V3.v3_ping.message.nonce, V3.v3_ring.message.nonce, V3.v3_stop.message.nonce].sort());
  // Les nonces trop vieux sont oubliés : la mémoire ne grossit pas sans fin.
  const plusTard = t + 2000 + FENETRE_COMMANDE_MS + 1;
  const neuve = chiffrerCommande(V.S, "ping", plusTard);
  assert.equal(filtre.accepter(V.S, neuve, plusTard).accepte, true);
  assert.deepEqual(Object.keys(filtre.nonces()), [neuve.nonce]);
});

test("v3 : capacité de la mémoire des nonces", () => {
  const filtre = creerFiltreCommandes({ capacite: 3 });
  const t = Date.now();
  for (let i = 0; i < 3; i++) assert.equal(filtre.accepter(V.S, chiffrerCommande(V.S, "ping", t), t).accepte, true);
  assert.deepEqual(filtre.accepter(V.S, chiffrerCommande(V.S, "ring", t), t), { accepte: false, raison: "capacite" });
});

test("v3 : refus de messages altérés, mal formés ou d'une autre chaîne", () => {
  const m = V3.v3_ring.message;
  const kCmd = Buffer.from(V3.K_cmd, "hex");
  const raison = (f) => {
    try {
      f();
    } catch (e) {
      return e.raison;
    }
    return "aucune";
  };
  // Nonce changé : l'IV fait partie de l'authentification.
  assert.equal(raison(() => dechiffrerCommande(V.S, { ...m, nonce: "ff" + m.nonce.slice(2) })), "authentification");
  // Chaque octet de enc compte (clair et étiquette).
  for (const i of [0, 40, 63, 64, 79]) {
    const o = Buffer.from(m.enc, "base64");
    o[i] ^= 0x80;
    assert.equal(raison(() => dechiffrerCommande(V.S, { ...m, enc: o.toString("base64") })), "authentification", `octet ${i}`);
  }
  // Mauvais id en AAD, mauvaise clé.
  assert.equal(raison(() => dechiffrerCommandeAvec(kCmd, "f".repeat(32), m)), "authentification");
  assert.equal(raison(() => dechiffrerCommande(genererSecret(), m)), "authentification");
  // Formes refusées (aussi par le relais).
  const formes = [
    null,
    [],
    { ...m, type: "state" },
    { ...m, v: "3" },
    { ...m, v: 4 },
    { ...m, nonce: m.nonce.toUpperCase() },
    { ...m, nonce: m.nonce + "00" },
    { ...m, enc: m.enc.replace(/\+/g, "-").replace(/\//g, "_") },
    { type: "cmd", v: 3, nonce: m.nonce },
  ];
  for (const f of formes) {
    assert.equal(formeCommandeV3(f), null, JSON.stringify(f));
    assert.equal(raison(() => dechiffrerCommande(V.S, f)), "forme", JSON.stringify(f));
  }
  // Taille : enc qui ne fait pas 80 octets.
  for (const n of [0, 16, 79, 81, 96]) {
    const enc = randomBytes(n).toString("base64");
    assert.equal(formeCommandeV3({ ...m, enc }), null);
    assert.equal(raison(() => dechiffrerCommande(V.S, { ...m, enc })), "taille", `${n} octets`);
  }
  // Copie propre : les champs en trop sont retirés.
  assert.deepEqual(formeCommandeV3({ ...m, cmd: "ring", extra: 1 }), m);
  assert.throws(() => texteCommande({ ...m, nonce: "00" }), /mal formée/);
  // Version et code de commande lus après authentification.
  assert.equal(raison(() => lireClairCommande(Buffer.alloc(63))), "taille");
  assert.equal(raison(() => lireClairCommande(clairCommande("ring", 1, { version: 2 }))), "version");
  assert.equal(raison(() => lireClairCommande(clairCommande("ring", 1, { code: 0 }))), "commande");
  const tsMax = clairCommande("ring", 1);
  tsMax.writeBigUInt64BE(0xffffffffffffffffn, 2);
  assert.equal(raison(() => lireClairCommande(tsMax)), "fraicheur");
  // Un clair chiffré correctement mais de mauvaise taille est refusé avant le déchiffrement.
  const long = chiffrerClairCommande(kCmd, V.id, Buffer.alloc(65), randomBytes(12));
  assert.equal(raison(() => dechiffrerCommande(V.S, long)), "taille");
});

test("v3 : règles d'envoi des paramètres", () => {
  const S = genererSecret();
  assert.throws(() => chiffrerCommande(S, "check", 1), /Commande inconnue/);
  assert.throws(() => chiffrerCommande(S, "ring", 1, { duree: 59 }), /Durée invalide/);
  assert.throws(() => chiffrerCommande(S, "ring", 1, { duree: 601 }), /Durée invalide/);
  assert.throws(() => chiffrerCommande(S, "ring", 1, { lampe: 3 }), /Lampe invalide/);
  assert.throws(() => chiffrerCommande(S, "stop", 1, { duree: 120 }), /que pour ring/);
  assert.throws(() => chiffrerCommande(S, "ping", 1, { lampe: 1 }), /que pour ring/);
  assert.throws(() => chiffrerCommande(S, "ring", -1), /Horodatage/);
  assert.throws(() => chiffrerCommande(S, "ring", 1, { nonce: "00".repeat(16) }), /12 octets/);
  // Côté téléphone, une valeur inattendue retombe sur le réglage au lieu d'empêcher la sonnerie.
  assert.deepEqual(parametresSonnerie({ duree: 30, lampe: 9 }), { dureeS: null, lampe: null });
  assert.deepEqual(parametresSonnerie({ duree: 60, lampe: 1 }), { dureeS: 60, lampe: true });
});

test("vecteur v3 : état d'une app 1.6 (cmdv = 3, clair bourré, 508 octets)", async () => {
  const e = V3.etat_v3;
  assert.equal(Buffer.byteLength(e.clair), TAILLE_CLAIR_ETAT);
  assert.equal(e.octets_clair, TAILLE_CLAIR_ETAT);
  const sansPad = { ...JSON.parse(e.clair) };
  delete sansPad.pad;
  assert.equal(bourrerEtat(JSON.stringify(sansPad)), e.clair);
  assert.equal(chiffrerEtatTexte(V.S, V.id, e.clair, e.message.nonce).enc, e.message.enc);
  assert.equal(texteEtat(e.message), e.texte);
  assert.equal(Buffer.byteLength(e.texte), TAILLE_MESSAGE_ETAT);
  const etat = decryptState(V.S, V.id, e.message);
  assert.equal(etat.cmdv, 3);
  assert.equal(etat.ack, V3.v3_ring.message.nonce);
  assert.equal(accepteV3(etat), true);
  assert.equal(accepteV3(JSON.parse(V.etat.plaintext)), false, "un état sans cmdv (app 1.5 et avant)");
  assert.equal(accepteV3({ cmdv: "3" }), false);
  assert.equal(accepteV3(null), false);
  // Même résultat avec WebCrypto.
  const cle = await subtle.importKey("raw", Buffer.from(V.K_enc, "hex"), "AES-GCM", false, ["decrypt"]);
  const clair = await subtle.decrypt(
    { name: "AES-GCM", iv: Buffer.from(e.message.nonce, "hex"), additionalData: new TextEncoder().encode(V.id) },
    cle,
    Buffer.from(e.message.enc, "base64"),
  );
  assert.equal(new TextDecoder().decode(clair), e.clair);
});
