// Tests du module de cryptographie de la page web (web/crypto.js), chargé tel quel
// dans Node 22 : mêmes vecteurs que relay/crypto.js et l'app Android, plus des
// allers-retours croisés avec relay/crypto.js.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as web from "../../web/crypto.js";
import * as relais from "../crypto.js";
import { MOTS as MOTS_WEB } from "../../web/bip39-english.js";
import { MOTS as MOTS_RELAIS } from "../bip39-english.js";

const V = JSON.parse(readFileSync(new URL("../../docs/VECTEURS.json", import.meta.url), "utf8"));
const S = web.hexVersOctets(V.S);

test("web : la liste BIP39 est identique à celle du relais", () => {
  assert.deepEqual([...MOTS_WEB], [...MOTS_RELAIS]);
});

test("web : vecteurs P et id", async () => {
  assert.equal(web.octetsVersHex(await web.derivePreuve(S)), V.P);
  assert.equal(await web.deriveId(S), V.id);
  assert.equal(await web.deriveId(V.S), V.id);
});

test("web : vecteurs K_sig et K_enc", async () => {
  const { sig, enc } = await web.deriveKeys(S);
  assert.equal(web.octetsVersHex(sig), V.K_sig);
  assert.equal(web.octetsVersHex(enc), V.K_enc);
});

test("web : vecteur commande signée", async () => {
  const m = V.commande.message;
  assert.deepEqual(await web.signCmd(S, m.cmd, m.ts, m.nonce), m);
  const cles = await web.preparer(S);
  assert.equal(cles.id, V.id);
  assert.deepEqual(await web.signerAvec(cles, m.cmd, m.ts, web.hexVersOctets(m.nonce)), m);
});

test("web : vecteur état chiffré, octet pour octet", async () => {
  const m = V.etat.message;
  const obj = JSON.parse(V.etat.plaintext);
  assert.deepEqual(await web.decryptState(S, V.id, m), obj);
  const cles = await web.preparer(S);
  assert.deepEqual(await web.dechiffrerAvec(cles, m), obj);
  assert.deepEqual(await web.encryptState(S, V.id, obj, m.nonce), { nonce: m.nonce, enc: m.enc });
});

test("web : vecteurs 12 mots et lien", async () => {
  assert.deepEqual(await web.toWords(S), V.mots);
  assert.deepEqual(await web.fromWords(V.mots), S);
  assert.deepEqual(await web.fromWords(V.mots.join("  ").toUpperCase()), S);
  assert.equal(web.toLink(S), V.lien);
  assert.deepEqual(web.fromLink(V.lien), S);
  assert.deepEqual(web.secretDuFragment(`#s=${V.S_base64url}`), S);
  assert.deepEqual(web.base64urlVersOctets(V.S_base64url), S);
  assert.equal(web.octetsVersBase64url(S), V.S_base64url);
});

test("web : lecture d'une saisie (lien ou mots)", async () => {
  const parLien = await web.lireSaisie(`  ${V.lien}\n`);
  assert.deepEqual(parLien.S, S);
  assert.equal(parLien.origine, "https://sonne.sidali.dev");
  const parMots = await web.lireSaisie(V.mots.join(" "));
  assert.deepEqual(parMots.S, S);
  assert.equal(parMots.origine, null);
  await assert.rejects(web.lireSaisie(""), /12 words/);
  await assert.rejects(web.lireSaisie("bonjour"), /12 words/);
});

test("web : allers-retours croisés avec relay/crypto.js", async () => {
  for (let i = 0; i < 50; i++) {
    const s = web.aleatoire(16);
    const id = await web.deriveId(s);
    assert.equal(id, relais.deriveId(Buffer.from(s)));
    assert.equal(web.octetsVersHex(await web.derivePreuve(s)), relais.derivePreuve(Buffer.from(s)).toString("hex"));
    assert.equal(relais.idDepuisPreuve(await web.derivePreuve(s)), id);
    // Signée par la page, vérifiée par le relais (comme le ferait le téléphone).
    const cmd = await web.signCmd(s, ["ring", "stop", "ping"][i % 3], Date.now());
    assert.equal(relais.verifyCmd(Buffer.from(s), cmd), true);
    // Chiffré par le relais (comme le téléphone), déchiffré par la page.
    const etat = { ts: Date.now(), ringing: i % 2 === 0, battery: i, charging: false, app: "1.0.0", ack: cmd.nonce, model: "Test é" };
    const chiffre = relais.encryptState(Buffer.from(s), id, etat);
    assert.deepEqual(await web.decryptState(s, id, chiffre), etat);
    // Et l'inverse.
    const chiffreWeb = await web.encryptState(s, id, etat);
    assert.deepEqual(relais.decryptState(Buffer.from(s), id, chiffreWeb), etat);
    // Mots et liens.
    assert.deepEqual(await web.toWords(s), relais.toWords(Buffer.from(s)));
    assert.equal(web.toLink(s, "http://localhost:7700"), relais.toLink(Buffer.from(s), "http://localhost:7700"));
  }
});

test("web : rejets", async () => {
  const m = V.etat.message;
  // Mauvais id en AAD, état altéré, mauvais secret.
  await assert.rejects(web.decryptState(S, "f".repeat(32), m), /authentication/);
  const octets = web.base64VersOctets(m.enc);
  octets[3] ^= 1;
  await assert.rejects(web.decryptState(S, V.id, { ...m, enc: web.octetsVersBase64(octets) }), /authentication/);
  await assert.rejects(web.decryptState(web.aleatoire(16), V.id, m), /authentication/);
  await assert.rejects(web.decryptState(S, V.id, { nonce: "00", enc: m.enc }), /nonce/);
  // Somme de contrôle BIP39, mot inconnu.
  const faux = [...V.mots.slice(0, 11), MOTS_WEB[MOTS_WEB.indexOf(V.mots[11]) ^ 1]];
  await assert.rejects(web.fromWords(faux), /checksum/);
  await assert.rejects(web.fromWords([...V.mots.slice(0, 11), "sonne"]), /Unknown word/);
  // Liens mal formés, secrets de mauvaise taille, commande inconnue.
  assert.throws(() => web.fromLink("https://sonne.sidali.dev/"), /fragment/);
  assert.throws(() => web.fromLink("https://sonne.sidali.dev/#s=AAEC"), /wrong size/);
  await assert.rejects(web.deriveId(new Uint8Array(15)), /Invalid key/);
  await assert.rejects(web.signCmd(S, "explose", 1), /Unknown command/);
});

// ---------------------------------------------------------------------------
// Protocole v3 : commandes chiffrées, construites par la page
// ---------------------------------------------------------------------------

const V3 = V.commandes_v3;
const NOMS_V3 = ["v3_ring", "v3_stop", "v3_ping", "v3_ring_parametres"];
const subtle = globalThis.crypto.subtle;

/** Clé HMAC non exportable de K_sig, comme celles que la page range dans IndexedDB. */
const cleSigVecteur = () =>
  subtle.importKey("raw", web.hexVersOctets(V.K_sig), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);

test("web v3 : K_cmd depuis la clé HMAC de K_sig", async () => {
  const octets = await web.octetsCleCommande(await cleSigVecteur());
  assert.equal(web.octetsVersHex(octets), V3.K_cmd);
  // Celle de preparer() : AES-GCM 256, non exportable, chiffrement seulement.
  const { cleCmd } = await web.preparer(S);
  assert.equal(cleCmd.type, "secret");
  assert.equal(cleCmd.extractable, false);
  assert.deepEqual(cleCmd.usages, ["encrypt"]);
  assert.equal(cleCmd.algorithm.name, "AES-GCM");
  assert.equal(cleCmd.algorithm.length, 256);
  await assert.rejects(subtle.exportKey("raw", cleCmd));
});

test("web v3 : ring, stop, ping et ring avec paramètres, octet pour octet", async () => {
  const cles = await web.preparer(S);
  assert.equal(cles.id, V.id);
  for (const nom of NOMS_V3) {
    const e = V3[nom];
    assert.equal(web.octetsVersHex(web.clairCommande(e.cmd, e.ts, { duree: e.duree, lampe: e.lampe })), e.clair, nom);
    const options = { nonce: e.message.nonce, duree: e.duree, lampe: e.lampe };
    const m = await web.chiffrerAvec(cles, e.cmd, e.ts, options);
    assert.deepEqual(m, e.message, nom);
    // Le nonce peut aussi être donné en octets.
    assert.deepEqual(await web.chiffrerAvec(cles, e.cmd, e.ts, { ...options, nonce: web.hexVersOctets(e.message.nonce) }), e.message);
    assert.deepEqual(await web.chiffrerCommande(S, e.cmd, e.ts, options), e.message);
    // Message exact : 172 octets, le même que relay/crypto.js.
    const texte = web.texteCommande(m);
    assert.equal(texte, e.texte, nom);
    assert.equal(new TextEncoder().encode(texte).length, web.TAILLE_MESSAGE_COMMANDE);
    assert.equal(texte, relais.texteCommande(e.message));
  }
});

test("web v3 : clés rangées avant la 1.6 (sans cleCmd), complétées sans nouvel appairage", async () => {
  const { id, cleSig, cleEnc } = await web.preparer(S);
  const anciennes = { id, cleSig, cleEnc };
  await assert.rejects(web.chiffrerAvec(anciennes, "ring", 1), /Command key missing/);
  const completes = await web.completerCles(anciennes);
  assert.equal(completes.cleSig, cleSig);
  assert.equal(completes.cleEnc, cleEnc);
  assert.equal("cleCmd" in anciennes, false, "l'objet d'origine n'est pas modifié");
  assert.equal(await web.completerCles(completes), completes);
  const e = V3.v3_ring;
  assert.deepEqual(await web.chiffrerAvec(completes, e.cmd, e.ts, { nonce: e.message.nonce }), e.message);
  // Même chose depuis une clé HMAC importée à part.
  const cleCmd = await web.cleCommandeDepuisSig(await cleSigVecteur());
  assert.deepEqual(await web.chiffrerAvec({ id: V.id, cleCmd }, e.cmd, e.ts, { nonce: e.message.nonce }), e.message);
});

test("web v3 : état d'une app 1.6, capacité cmdv", async () => {
  const cles = await web.preparer(S);
  const etat = await web.dechiffrerAvec(cles, V3.etat_v3.message);
  assert.deepEqual(etat, JSON.parse(V3.etat_v3.clair));
  assert.equal(etat.ack, V3.v3_ring.message.nonce);
  assert.equal(web.accepteV3(etat), true);
  assert.equal(web.accepteV3(await web.dechiffrerAvec(cles, V.etat.message)), false, "état d'une app 1.5");
  for (const faux of [null, {}, { cmdv: "3" }, { cmdv: 2 }, { cmdv: 3.5 }]) assert.equal(web.accepteV3(faux), false);
  assert.equal(web.accepteV3({ cmdv: 4 }), true);
  for (const e of [etat, null, { cmdv: "3" }]) assert.equal(web.accepteV3(e), relais.accepteV3(e));
});

test("web v3 : choix du format, jamais de retour à la v2", () => {
  assert.equal(web.formatCommande(), 2);
  assert.equal(web.formatCommande({ cmdv3: true }), 3);
  assert.equal(web.formatCommande({ relaisRefuseV2: true }), 3);
  assert.equal(web.formatCommande({ coupure: true }), 3);
  assert.equal(web.formatCommande({ cmdv3: true, coupure: false, relaisRefuseV2: false }), 3);
});

test("web v3 : allers-retours croisés avec relay/crypto.js et le filtre du téléphone", async () => {
  const cas = [
    ["ring", {}],
    ["stop", {}],
    ["ping", {}],
    ["ring", { duree: 60 }],
    ["ring", { duree: 600, lampe: 1 }],
    ["ring", { lampe: 2 }],
  ];
  const tailles = new Set();
  for (let i = 0; i < 30; i++) {
    const s = web.aleatoire(16);
    const cles = await web.preparer(s);
    const [cmd, p] = cas[i % cas.length];
    const ts = Date.now() + i;
    // Chiffrée par la page, lue par relay/crypto.js (comme le ferait le téléphone).
    const m = await web.chiffrerAvec(cles, cmd, ts, p);
    const lu = relais.dechiffrerCommande(Buffer.from(s), m);
    assert.deepEqual(lu, { cmd, ts, duree: p.duree ?? 0, lampe: p.lampe ?? 0, nonce: m.nonce });
    const texte = web.texteCommande(m);
    tailles.add(Buffer.byteLength(texte));
    // Relayée telle quelle puis acceptée par un téléphone après la coupure (v3 seulement).
    const filtre = relais.creerFiltreCommandes({ accepterV2: false });
    const r = filtre.accepter(Buffer.from(s), JSON.parse(texte), ts);
    assert.equal(r.accepte, true);
    assert.equal(r.format, 3);
    assert.equal(r.cmd, cmd);
    assert.deepEqual(filtre.accepter(Buffer.from(s), JSON.parse(texte), ts), { accepte: false, raison: "rejeu" });
    // Même nonce, même résultat que la référence.
    const kCmd = relais.deriveCleCommande(Buffer.from(s));
    assert.deepEqual(relais.chiffrerCommandeAvec(kCmd, cles.id, cmd, ts, { ...p, nonce: m.nonce }), m);
    // Une autre chaîne ne peut pas la lire.
    assert.throws(() => relais.dechiffrerCommande(relais.genererSecret(), m), /authentification/);
  }
  assert.deepEqual([...tailles], [web.TAILLE_MESSAGE_COMMANDE]);
  // Et la v2 de la page reste acceptée pendant la transition par le même filtre.
  const s = web.aleatoire(16);
  const v2 = await web.signCmd(s, "ring", Date.now());
  assert.equal(relais.creerFiltreCommandes({ accepterV2: true }).accepter(Buffer.from(s), v2, Date.now()).accepte, true);
  assert.deepEqual(relais.creerFiltreCommandes({ accepterV2: false }).accepter(Buffer.from(s), v2, Date.now()), {
    accepte: false,
    raison: "ancien-format",
  });
});

test("web v3 : refus à l'envoi", async () => {
  const cles = await web.preparer(S);
  await assert.rejects(web.chiffrerAvec(cles, "check", 1), /Unknown command/);
  await assert.rejects(web.chiffrerAvec(cles, "ring", 1, { duree: 59 }), /Invalid duration/);
  await assert.rejects(web.chiffrerAvec(cles, "ring", 1, { duree: 601 }), /Invalid duration/);
  await assert.rejects(web.chiffrerAvec(cles, "ring", 1, { lampe: 3 }), /Invalid flashlight/);
  await assert.rejects(web.chiffrerAvec(cles, "stop", 1, { duree: 120 }), /only apply to ring/);
  await assert.rejects(web.chiffrerAvec(cles, "ping", 1, { lampe: 1 }), /only apply to ring/);
  await assert.rejects(web.chiffrerAvec(cles, "ring", -1), /Invalid timestamp/);
  await assert.rejects(web.chiffrerAvec(cles, "ring", 1, { nonce: "00".repeat(16) }), /12 bytes/);
  await assert.rejects(web.chiffrerAvec({ ...cles, id: "abc" }, "ring", 1), /Invalid identifier/);
  const m = V3.v3_ring.message;
  for (const f of [
    null,
    { ...m, v: "3" },
    { ...m, type: "state" },
    { ...m, nonce: m.nonce.toUpperCase() },
    { ...m, enc: m.enc.slice(4) },
    { ...m, enc: m.enc.replace(/\+/g, "-").replace(/\//g, "_") },
    { ...m, enc: `${m.enc.slice(0, -2)}==` },
  ]) {
    assert.throws(() => web.texteCommande(f), /Malformed/, JSON.stringify(f));
  }
});

test("web v3 : les cas du vecteur, côté émetteur (forme et taille)", () => {
  const cas = Object.entries(V3.cas);
  assert.equal(cas.length, 19);
  for (const [nom, c] of cas) {
    const msg = JSON.parse(c.texte);
    if (msg.v === undefined) {
      assert.match(nom, /ancien_format/, nom);
      continue;
    }
    if (c.attendu === "forme" || c.attendu === "taille") {
      // La page n'écrit jamais un message que le téléphone refuserait pour sa forme ou sa taille.
      assert.throws(() => web.texteCommande(msg), /Malformed/, nom);
    } else {
      // Les autres sont bien formés : la page les réécrit octet pour octet (172 octets).
      assert.equal(web.texteCommande(msg), c.texte, nom);
      assert.equal(Buffer.byteLength(c.texte), web.TAILLE_MESSAGE_COMMANDE, nom);
    }
  }
});
