// Génère docs/VECTEURS.json : vecteurs de test partagés entre le relais,
// la page web et l'app Android. Usage : npm run vecteurs (depuis relay/).

import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  deriveId,
  derivePreuve,
  deriveKeys,
  signCmd,
  encryptState,
  toWords,
  toLink,
  deriveCleBle,
  deriveBleId,
  fenetreBle,
  deriveCleCommande,
  aadCommande,
  clairCommande,
  chiffrerClairCommande,
  chiffrerCommandeAvec,
  texteCommande,
  creerFiltreCommandes,
  bourrerEtat,
  chiffrerEtatTexte,
  texteEtat,
  TAILLE_MESSAGE_COMMANDE,
  TAILLE_MESSAGE_ETAT,
  FENETRE_COMMANDE_MS,
} from "../crypto.js";

const S = "000102030405060708090a0b0c0d0e0f";
const TS = 1792300000000;
const NONCE_CMD = "00112233445566778899aabbccddeeff";
const NONCE_ETAT = "0a0b0c0d0e0f101112131415";
// Fenêtre de la balise Bluetooth qui contient TS : floor(1792300000000 / 900000).
const W = fenetreBle(TS);
// Octet de version des données de service de la balise (PROTOCOL.md, Bluetooth proximity).
const VERSION_BALISE = "01";
// L'ordre des champs compte : c'est l'ordre de sérialisation attendu partout.
const ETAT = { ts: TS, ringing: false, battery: 77, charging: false, app: "1.0.0", ack: null, model: "Test" };

const id = deriveId(S);
const { sig, enc } = deriveKeys(S);
const commande = signCmd(S, "ring", TS, NONCE_CMD);
const etat = encryptState(S, id, ETAT, NONCE_ETAT);

const vecteurs = {
  description:
    "Vecteurs de test Sonne v2 et v3 (voir docs/PROTOCOL.md). Toutes les valeurs binaires sont en hex minuscule sauf mention contraire. P = HKDF-SHA256(S, sel vide, sonne-phone, 16) et id = hex(SHA-256(sonne-id || P))[0:32]. Les commandes chiffrées (protocole v3) sont dans le bloc commandes_v3, en dernier.",
  S,
  S_base64url: Buffer.from(S, "hex").toString("base64url"),
  P: derivePreuve(S).toString("hex"),
  id,
  K_sig: sig.toString("hex"),
  K_enc: enc.toString("hex"),
  mots: toWords(S),
  lien: toLink(S),
  commande: {
    texte_signe: `${commande.cmd}|${commande.ts}|${commande.nonce}`,
    message: commande,
  },
  etat: {
    plaintext: JSON.stringify(ETAT),
    aad: id,
    note: "AES-256-GCM, iv = nonce (12 octets), aad = id en UTF-8 (32 caractères ASCII), enc = base64 standard de (chiffré suivi de l'étiquette de 16 octets).",
    message: { type: "state", ...etat },
  },
  // Placé en dernier : le test JVM lit le JSON par expressions régulières, en prenant
  // la première occurrence d'une clé. Aucun nom de clé ci-dessous ne doit en masquer un plus haut.
  ble: {
    note: "Proximité Bluetooth (v1.1). B = HKDF-SHA256(S, sel vide, sonne-ble, 32 octets). w = floor(now_ms / 900000). id_ble = HMAC-SHA256(B, w en 8 octets big-endian)[0:8]. Données de service = id_ble suivi de l'octet de version 01.",
    B: deriveCleBle(S).toString("hex"),
    w: W,
    w_octets: Buffer.from(W.toString(16).padStart(16, "0"), "hex").toString("hex"),
    id_ble: deriveBleId(S, W).toString("hex"),
    donnees_service: deriveBleId(S, W).toString("hex") + VERSION_BALISE,
    id_ble_precedent: deriveBleId(S, W - 1).toString("hex"),
    id_ble_suivant: deriveBleId(S, W + 1).toString("hex"),
  },
  // Toujours en dernier, pour la même raison : noms de clés propres au bloc, lus par le test JVM
  // avec champ(nom, apres = "<nom du cas>").
  commandes_v3: blocV3(),
};

// ---------------------------------------------------------------------------
// Protocole v3 : commandes chiffrées (PROTOCOL.md, « Protocol v3: encrypted commands »)
// ---------------------------------------------------------------------------

function blocV3() {
  const kCmd = deriveCleCommande(S);
  // Une autre chaîne, pour le cas « mauvais id » et « mauvaise clé ».
  const S_AUTRE = "ffeeddccbbaa99887766554433221100";
  const idAutre = deriveId(S_AUTRE);

  const entree = (nom, cmd, ts, nonce, parametres = {}) => {
    const message = chiffrerCommandeAvec(kCmd, id, cmd, ts, { nonce, ...parametres });
    const texte = texteCommande(message);
    if (Buffer.byteLength(texte) !== TAILLE_MESSAGE_COMMANDE) throw new Error(`${nom} : taille ${texte.length}`);
    return {
      cmd,
      ts,
      duree: parametres.duree ?? 0,
      lampe: parametres.lampe ?? 0,
      clair: clairCommande(cmd, ts, parametres).toString("hex"),
      message,
      texte,
      octets: Buffer.byteLength(texte),
    };
  };
  const ring = entree("v3_ring", "ring", TS, "a0a1a2a3a4a5a6a7a8a9aaab");
  const stop = entree("v3_stop", "stop", TS + 1000, "b0b1b2b3b4b5b6b7b8b9babb");
  const ping = entree("v3_ping", "ping", TS + 2000, "c0c1c2c3c4c5c6c7c8c9cacb");
  const ringParametres = entree("v3_ring_parametres", "ring", TS + 3000, "d0d1d2d3d4d5d6d7d8d9dadb", { duree: 300, lampe: 2 });
  const messages = { v3_ring: ring, v3_stop: stop, v3_ping: ping, v3_ring_parametres: ringParametres };

  // État d'une app 1.6 : annonce cmdv = 3, accuse la ring v3, clair bourré à 320 octets.
  const ETAT_V3 = { ts: TS + 50, ringing: true, battery: 77, charging: false, app: "1.6.0", ack: ring.message.nonce, model: "Pixel 10 Pro", cmdv: 3 };
  const clairEtat = bourrerEtat(JSON.stringify(ETAT_V3));
  const chiffre = chiffrerEtatTexte(S, id, clairEtat, "e0e1e2e3e4e5e6e7e8e9eaeb");
  const etatV3 = {
    clair: clairEtat,
    octets_clair: Buffer.byteLength(clairEtat),
    message: { type: "state", ...chiffre },
    texte: texteEtat(chiffre),
    octets: Buffer.byteLength(texteEtat(chiffre)),
  };
  if (etatV3.octets !== TAILLE_MESSAGE_ETAT) throw new Error(`état v3 : ${etatV3.octets} octets`);

  // Messages fabriqués pour les refus : clair de mauvaise taille, version ou commande inconnues,
  // ts au bit de poids fort à 1, mauvais id, mauvaise clé, octet altéré, ancien format.
  const brut = (clair, nonce, cle = kCmd, idAad = id) => texteBrut(chiffrerClairCommande(cle, idAad, clair, nonce));
  const texteBrut = (m) => `{"type":"cmd","v":3,"nonce":"${m.nonce}","enc":"${m.enc}"}`;
  const clairRing = clairCommande("ring", TS);
  const tsHorsPlage = Buffer.from(clairRing);
  tsHorsPlage.writeBigUInt64BE(0x8000000000000000n, 2);
  const altere = Buffer.from(ring.message.enc, "base64");
  altere[0] ^= 0x01;
  const v2 = JSON.stringify(signCmd(S, "ring", TS, NONCE_CMD));
  const apres = TS + 5000;

  const cas = {
    accepte_ring: { texte: ring.texte, maintenant: apres, phase: "coupure", attendu: "acceptee", note: "ring, durée et lampe du téléphone" },
    accepte_limite: { texte: ring.texte, maintenant: TS + FENETRE_COMMANDE_MS, phase: "coupure", attendu: "acceptee", note: "15 min tout juste" },
    accepte_parametres: { texte: ringParametres.texte, maintenant: apres, phase: "coupure", attendu: "acceptee", note: "ring de 300 s, lampe éteinte" },
    accepte_ancien_format_transition: { texte: v2, maintenant: apres, phase: "transition", attendu: "acceptee", note: "commande v2 (bloc commande) pendant la transition" },
    refus_ancien_format: { texte: v2, maintenant: apres, phase: "coupure", attendu: "ancien-format", note: "la même commande v2 après la coupure" },
    refus_mauvais_id: { texte: brut(clairRing, "f0f1f2f3f4f5f6f7f8f9fafb", kCmd, idAutre), maintenant: apres, phase: "coupure", attendu: "authentification", note: `bonne clé, AAD avec l'id ${idAutre} d'une autre chaîne` },
    refus_mauvaise_cle: { texte: brut(clairRing, "f1f2f3f4f5f6f7f8f9fafbfc", deriveCleCommande(S_AUTRE)), maintenant: apres, phase: "coupure", attendu: "authentification", note: "K_cmd d'une autre chaîne, bon id" },
    refus_cle_etat: { texte: brut(clairRing, "f2f3f4f5f6f7f8f9fafbfcfd", enc), maintenant: apres, phase: "coupure", attendu: "authentification", note: "chiffrée avec K_enc au lieu de K_cmd" },
    refus_altere: { texte: texteBrut({ nonce: ring.message.nonce, enc: altere.toString("base64") }), maintenant: apres, phase: "coupure", attendu: "authentification", note: "premier octet de enc inversé" },
    refus_rejeu: { avant: "v3_ring", texte: ring.texte, maintenant: apres, phase: "coupure", attendu: "rejeu", note: "la même ring une seconde fois (par le relais puis par Bluetooth, ou l'inverse)" },
    refus_trop_vieux: { texte: ring.texte, maintenant: TS + FENETRE_COMMANDE_MS + 1, phase: "coupure", attendu: "fraicheur", note: "15 min et 1 ms après ts" },
    refus_futur: { texte: ring.texte, maintenant: TS - FENETRE_COMMANDE_MS - 1, phase: "coupure", attendu: "fraicheur", note: "15 min et 1 ms avant ts" },
    refus_ordre: { avant: "v3_stop", texte: ring.texte, maintenant: apres, phase: "coupure", attendu: "ordre", note: "ring plus ancienne que la stop déjà acceptée" },
    refus_taille_courte: { texte: brut(clairRing.subarray(0, 63), "a3a4a5a6a7a8a9aaabacadae"), maintenant: apres, phase: "coupure", attendu: "taille", note: "clair de 63 octets, chiffré correctement" },
    refus_taille_longue: { texte: brut(Buffer.concat([clairRing, Buffer.alloc(1)]), "a4a5a6a7a8a9aaabacadaeaf"), maintenant: apres, phase: "coupure", attendu: "taille", note: "clair de 65 octets, chiffré correctement" },
    refus_version: { texte: brut(clairCommande("ring", TS, { version: 4 }), "a5a6a7a8a9aaabacadaeafb0"), maintenant: apres, phase: "coupure", attendu: "version", note: "octet de version à 4" },
    refus_commande_inconnue: { texte: brut(clairCommande("ring", TS, { code: 4 }), "a6a7a8a9aaabacadaeafb0b1"), maintenant: apres, phase: "coupure", attendu: "commande", note: "code de commande 4" },
    refus_ts_hors_plage: { texte: brut(tsHorsPlage, "a7a8a9aaabacadaeafb0b1b2"), maintenant: apres, phase: "coupure", attendu: "fraicheur", note: "ts = 2^63 : négatif en Long signé, à refuser avant tout calcul" },
    refus_forme_nonce: { texte: ring.texte.replace(ring.message.nonce, ring.message.nonce.toUpperCase()), maintenant: apres, phase: "coupure", attendu: "forme", note: "nonce en majuscules" },
  };

  // Le filtre de référence doit donner exactement l'attendu de chaque cas.
  for (const [nom, c] of Object.entries(cas)) {
    const filtre = creerFiltreCommandes({ accepterV2: c.phase === "transition" });
    if (c.avant) {
      const r = filtre.accepter(S, JSON.parse(messages[c.avant].texte), c.maintenant);
      if (!r.accepte) throw new Error(`${nom} : ${c.avant} refusée (${r.raison})`);
    }
    const r = filtre.accepter(S, JSON.parse(c.texte), c.maintenant);
    const obtenu = r.accepte ? "acceptee" : r.raison;
    if (obtenu !== c.attendu) throw new Error(`${nom} : attendu ${c.attendu}, obtenu ${obtenu}`);
  }

  return {
    note:
      "Protocole v3, commandes chiffrées. K_cmd = HKDF-Expand(K_sig, sonne-cmd-v3, 32) = HMAC-SHA256(K_sig, sonne-cmd-v3 || 01). " +
      "enc = base64 standard de AES-256-GCM(K_cmd, iv = nonce de 12 octets, aad = sonne-cmd-v3| suivi de l'id, clair de 64 octets) : 80 octets, 108 caractères. " +
      "Clair : version 03, commande (01 ring, 02 stop, 03 ping), ts sur 8 octets big-endian, durée en secondes sur 2 octets big-endian (0 = réglage), lampe sur 1 octet (0 = réglage, 1 allumée, 2 éteinte), 51 octets réservés à zéro. " +
      "Message exact, toujours 172 octets : {\"type\":\"cmd\",\"v\":3,\"nonce\":\"…\",\"enc\":\"…\"}. " +
      "Cas : chaque texte est soumis à un filtre neuf du téléphone à l'instant maintenant, après le message avant s'il y en a un ; phase transition = v2 encore accepté, coupure = v2 refusé.",
    K_cmd: kCmd.toString("hex"),
    aad_v3: aadCommande(id).toString("utf8"),
    ...messages,
    etat_v3: etatV3,
    cas,
  };
}

const sortie = fileURLToPath(new URL("../../docs/VECTEURS.json", import.meta.url));
writeFileSync(sortie, JSON.stringify(vecteurs, null, 2) + "\n");
console.log(`Vecteurs écrits dans ${sortie}`);
