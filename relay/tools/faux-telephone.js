// Faux téléphone pour essayer le relais et la page web sans l'app Android.
//
// Usage (depuis relay/) :
//   node tools/faux-telephone.js [--relais http://localhost:7700] [--secret <32 hex>] [--ancien | --coupure]
//
// Par défaut il utilise le secret S de docs/VECTEURS.json et se comporte comme l'app 1.6 :
// il se connecte en /ws/phone en présentant sa preuve P, filtre chaque commande comme l'app
// (creerFiltreCommandes : v3 chiffrée, v2 signée pendant la transition, fraîcheur de 15 min,
// nonce jamais vu dans la fenêtre, ring et stop jamais plus anciennes que la dernière acceptée),
// affiche « SONNE » sur une ring et répond par exactement un état chiffré qui accuse réception.
// Ses états annoncent les commandes v3 ("cmdv":3) et sont bourrés à 320 octets (508 en tout).
//
//   --ancien   se comporte comme l'app 1.5 : v2 seulement, pas de cmdv dans l'état.
//   --coupure  app 1.8 : refuse la v2 (ancien format).

import { readFileSync } from "node:fs";
import WebSocket from "ws";
import {
  deriveId,
  derivePreuve,
  toLink,
  toWords,
  creerFiltreCommandes,
  parametresSonnerie,
  bourrerEtat,
  chiffrerEtatTexte,
  texteEtat,
} from "../crypto.js";

function option(nom, defaut) {
  const i = process.argv.indexOf(`--${nom}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : defaut;
}

const V = JSON.parse(readFileSync(new URL("../../docs/VECTEURS.json", import.meta.url), "utf8"));
const relais = option("relais", "http://localhost:7700").replace(/\/+$/, "");
const S = Buffer.from(option("secret", V.S), "hex");
const id = deriveId(S);
const ancien = process.argv.includes("--ancien");
const coupure = !ancien && process.argv.includes("--coupure");
const VERSION_APP = ancien ? "1.5.0" : coupure ? "1.8.0" : "1.6.0";
const QUINZE_MIN = 15 * 60_000;
const AUTO_STOP_MS = 3 * 60_000;

const P = derivePreuve(S).toString("hex");
// Une seule mémoire de nonces et un seul ordre des ring et stop, pour v2 et v3.
const filtre = creerFiltreCommandes({ accepterV2: !coupure });
let sonne = false;
let minuteurArret = null;
let batterie = 77;
let ws = null;
let delai = 1000;

const heure = () => new Date().toLocaleTimeString("fr-FR");
const log = (...t) => console.log(`[${heure()}]`, ...t);

function envoyerEtat(ack = null) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  const etat = { ts: Date.now(), ringing: sonne, battery: batterie, charging: false, app: VERSION_APP, ack, model: "Fake phone" };
  if (!ancien) etat.cmdv = 3;
  const texte = texteEtat(chiffrerEtatTexte(S, id, bourrerEtat(JSON.stringify(etat))));
  ws.send(texte);
}

function demarrerSonnerie(dureeS, lampe) {
  sonne = true;
  const lampeTexte = lampe === null ? "réglage" : lampe ? "allumée" : "éteinte";
  console.log(`\n  SONNE  SONNE  SONNE  (${dureeS ?? AUTO_STOP_MS / 1000} s, lampe ${lampeTexte})\n`);
  clearTimeout(minuteurArret);
  // Une ring pendant une sonnerie relance l'arrêt automatique avec sa propre durée.
  minuteurArret = setTimeout(
    () => {
      log("arrêt automatique");
      arreterSonnerie();
      envoyerEtat(); // ne vient pas d'une commande : état envoyé
    },
    dureeS ? dureeS * 1000 : AUTO_STOP_MS,
  );
}

function arreterSonnerie() {
  if (sonne) log("sonnerie arrêtée");
  sonne = false;
  clearTimeout(minuteurArret);
}

function traiter(cmd) {
  if (ancien && cmd?.v !== undefined) return log("commande ignorée : format v3 inconnu de l'app 1.5");
  const r = filtre.accepter(S, cmd, Date.now());
  if (!r.accepte) return log(`commande ignorée : ${r.raison}`);
  log(`commande ${r.cmd} acceptée (v${r.format})`);
  if (r.cmd === "ring") {
    const { dureeS, lampe } = parametresSonnerie(r);
    demarrerSonnerie(dureeS, lampe);
  } else if (r.cmd === "stop") arreterSonnerie();
  // Un seul état par commande : l'accusé porte déjà la sonnerie attendue.
  envoyerEtat(r.nonce);
}

function connecter() {
  const url = `${relais.replace(/^http/, "ws")}/ws/phone`;
  ws = new WebSocket(url, ["sonne.v2", `sonne.p.${P}`]);
  ws.on("open", () => {
    delai = 1000;
    log("connecté au relais");
    envoyerEtat();
  });
  ws.on("message", (donnees) => {
    let msg;
    try {
      msg = JSON.parse(donnees.toString());
    } catch {
      return;
    }
    if (msg.type === "welcome") {
      if (msg.queued?.length) log(`${msg.queued.length} commande(s) en attente`);
      for (const c of msg.queued ?? []) traiter(c);
    } else if (msg.type === "cmd") {
      traiter(msg);
    }
  });
  ws.on("close", (code) => {
    log(`déconnecté (${code}), nouvelle tentative dans ${Math.round(delai / 1000)} s`);
    setTimeout(connecter, delai);
    delai = Math.min(delai * 2, 60_000);
  });
  ws.on("error", (e) => log("erreur :", e.message));
}

console.log(`Faux téléphone Sonne ${VERSION_APP}, id ${id.slice(0, 6)}…`);
console.log(`Lien d'appairage : ${toLink(S, relais)}`);
console.log(`12 mots : ${toWords(S).join(" ")}`);
connecter();

// État toutes les 15 min, et la batterie baisse doucement.
setInterval(() => {
  batterie = Math.max(5, batterie - 1);
  envoyerEtat();
}, QUINZE_MIN);

process.on("SIGINT", () => process.exit(0));
process.on("SIGTERM", () => process.exit(0));
