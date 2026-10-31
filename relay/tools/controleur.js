// Contrôleur en ligne de commande, pour tester un vrai téléphone (ou le faux) sans la page web.
//
// Usage (depuis relay/) :
//   node tools/controleur.js --lien "<lien d'appairage>" [--relais http://localhost:7700] [--attendre 8] [--v2] ring|stop|ping
//
// Il ouvre /ws/ctrl pour recevoir la présence et les états chiffrés (déchiffrés ici avec S),
// envoie la commande par POST /api/cmd, affiche la réponse du relais,
// puis écoute pendant --attendre secondes avant de quitter.
// Par défaut la commande est chiffrée (protocole v3, 172 octets) ; --v2 envoie l'ancien
// format signé (téléphone en 1.5 ou avant, relais encore en transition).
// Sans commande, il écoute seulement.

import WebSocket from "ws";
import { deriveId, fromLink, signCmd, chiffrerCommande, texteCommande, decryptState, COMMANDES } from "../crypto.js";

function option(nom, defaut) {
  const i = process.argv.indexOf(`--${nom}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : defaut;
}

const lien = option("lien", null);
if (!lien) {
  console.error("Il faut --lien \"<lien d'appairage>\"");
  process.exit(2);
}
const S = fromLink(lien);
const id = deriveId(S);
const relais = option("relais", "http://localhost:7700").replace(/\/+$/, "");
const attendre = Number(option("attendre", "8")) * 1000;
const v2 = process.argv.includes("--v2");
const cmd = process.argv.slice(2).find((a) => COMMANDES.includes(a));

const t0 = Date.now();
const log = (quoi, objet) => console.log(JSON.stringify({ t: Date.now() - t0, quoi, ...objet }));

const ws = new WebSocket(`${relais.replace(/^http/, "ws")}/ws/ctrl`, ["sonne.v2", `sonne.id.${id}`]);
ws.on("message", (donnees) => {
  let msg;
  try {
    msg = JSON.parse(donnees.toString());
  } catch {
    return;
  }
  if (msg.type === "presence") log("presence", { online: msg.online, lastSeen: msg.lastSeen });
  else if (msg.type === "state") {
    try {
      log("etat", { octets: donnees.length, etat: decryptState(S, id, msg) });
    } catch (e) {
      log("etat_illisible", { erreur: e.message });
    }
  }
});
ws.on("error", (e) => log("erreur", { erreur: e.message }));

ws.on("open", async () => {
  log("ctrl_connecte", { id: id.slice(0, 6) });
  if (cmd) {
    // Laisse passer la présence initiale avant d'envoyer.
    await new Promise((r) => setTimeout(r, 300));
    const commande = v2 ? signCmd(S, cmd, Date.now()) : chiffrerCommande(S, cmd, Date.now());
    const corps = v2 ? JSON.stringify(commande) : texteCommande(commande);
    const reponse = await fetch(`${relais}/api/cmd`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-sonne-id": id },
      body: corps,
    });
    log("relais", {
      cmd,
      format: v2 ? 2 : 3,
      octets: Buffer.byteLength(corps),
      nonce: commande.nonce,
      status: reponse.status,
      reponse: await reponse.json(),
    });
  }
  setTimeout(() => {
    ws.close();
    process.exit(0);
  }, attendre);
});
