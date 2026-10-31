// Tests du relais (node --test). Chaque test démarre son propre relais sur un port libre
// et simule un téléphone et des contrôleurs avec la bibliothèque ws.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import WebSocket from "ws";
import { demarrer, cleIp, commandeValide, ERREUR_ANCIEN_FORMAT, FILE_V3 } from "../server.js";
import {
  deriveId,
  derivePreuve,
  signCmd,
  encryptState,
  decryptState,
  genererSecret,
  chiffrerCommande,
  texteCommande,
  TAILLE_MESSAGE_COMMANDE,
} from "../crypto.js";

const V = JSON.parse(readFileSync(new URL("../../docs/VECTEURS.json", import.meta.url), "utf8"));

// ---------------------------------------------------------------------------
// Outils
// ---------------------------------------------------------------------------

async function relais(t, options = {}) {
  const r = await demarrer({ port: 0, hote: "127.0.0.1", silencieux: true, ...options });
  t.after(() => r.arreter());
  return { ...r, http: `http://127.0.0.1:${r.port}`, ws: `ws://127.0.0.1:${r.port}` };
}

/**
 * Ouvre un WebSocket et range chaque message JSON reçu dans une file.
 * prochain(type) attend le prochain message de ce type (ou de n'importe quel type).
 */
function client(url, protocoles = [], options = {}) {
  const ws = new WebSocket(url, protocoles, options);
  const recus = [];
  const bruts = []; // textes exacts reçus, dans l'ordre
  const attentes = [];
  ws.on("message", (d) => {
    bruts.push(d.toString());
    const msg = JSON.parse(d.toString());
    const i = attentes.findIndex((a) => !a.type || a.type === msg.type);
    if (i >= 0) attentes.splice(i, 1)[0].resolve(msg);
    else recus.push(msg);
  });
  const ouvert = new Promise((resolve, reject) => {
    ws.once("open", resolve);
    ws.once("error", reject);
    ws.once("unexpected-response", (_req, res) => reject(Object.assign(new Error("refusé"), { statut: res.statusCode })));
  });
  const ferme = new Promise((resolve) => ws.once("close", (code, raison) => resolve({ code, raison: raison.toString() })));
  return {
    ws,
    ouvert,
    ferme,
    recus,
    bruts,
    envoyer: (obj) => ws.send(typeof obj === "string" ? obj : JSON.stringify(obj)),
    prochain(type, delai = 2000) {
      const i = recus.findIndex((m) => !type || m.type === type);
      if (i >= 0) return Promise.resolve(recus.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        const a = { type, resolve };
        attentes.push(a);
        setTimeout(() => {
          const j = attentes.indexOf(a);
          if (j >= 0) {
            attentes.splice(j, 1);
            reject(new Error(`rien reçu de type ${type ?? "quelconque"} en ${delai} ms`));
          }
        }, delai);
      });
    },
    fermer() {
      ws.close();
      return ferme;
    },
  };
}

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** Téléphone du protocole v2 : il prouve qu'il détient S en présentant P (jamais d'id dans l'URL). */
const ouvrirTelephone = (r, S = V.S, options = {}) =>
  client(`${r.ws}/ws/phone`, ["sonne.v2", `sonne.p.${derivePreuve(S).toString("hex")}`], options);

/** Contrôleur du protocole v2 : l'id passe dans Sec-WebSocket-Protocol. */
const ouvrirControleur = (r, id = V.id) => client(`${r.ws}/ws/ctrl`, ["sonne.v2", `sonne.id.${id}`]);

async function postCmd(base, id, corps) {
  const res = await fetch(`${base}/api/cmd`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Sonne-Id": id },
    body: typeof corps === "string" ? corps : JSON.stringify(corps),
  });
  return { statut: res.status, corps: await res.json() };
}

const presenceApi = (base, id) => fetch(`${base}/api/presence`, { headers: { "X-Sonne-Id": id } });

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test("livraison d'une commande et accusé d'état chiffré", async (t) => {
  const r = await relais(t);
  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  assert.deepEqual(await telephone.prochain("welcome"), { type: "welcome", queued: [] });

  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  const p = await ctrl.prochain("presence");
  assert.equal(p.online, true);
  assert.equal(typeof p.lastSeen, "number");

  // Le contrôleur envoie une ring signée : le téléphone la reçoit telle quelle.
  const cmd = signCmd(V.S, "ring", Date.now());
  ctrl.envoyer(cmd);
  assert.deepEqual(await telephone.prochain("cmd"), cmd);
  assert.deepEqual(await ctrl.prochain("relayed"), { type: "relayed", nonce: cmd.nonce, delivered: true });

  // Le téléphone répond par un état chiffré qui accuse réception.
  const etat = { ts: Date.now(), ringing: true, battery: 54, charging: false, app: "1.0.0", ack: cmd.nonce, model: "Test" };
  telephone.envoyer({ type: "state", ...encryptState(V.S, V.id, etat) });
  const recu = await ctrl.prochain("state");
  assert.deepEqual(Object.keys(recu).sort(), ["enc", "nonce", "type"]);
  assert.deepEqual(decryptState(V.S, V.id, recu), etat);

  // Un second contrôleur reçoit aussi les états.
  const ctrl2 = ouvrirControleur(r);
  await ctrl2.ouvert;
  await ctrl2.prochain("presence");
  telephone.envoyer({ type: "state", ...encryptState(V.S, V.id, { ...etat, ringing: false }) });
  assert.equal(decryptState(V.S, V.id, await ctrl.prochain("state")).ringing, false);
  assert.equal(decryptState(V.S, V.id, await ctrl2.prochain("state")).ringing, false);

  // Les messages mal formés du téléphone ne sont pas relayés.
  telephone.envoyer({ type: "state", nonce: "zz", enc: "x" });
  telephone.envoyer("pas du json");
  await assert.rejects(ctrl.prochain("state", 200));

  // /health compte les connexions.
  const sante = await (await fetch(`${r.http}/health`)).json();
  assert.equal(sante.ok, true);
  assert.equal(sante.phones, 1);
  assert.equal(sante.ctrls, 2);
  assert.equal(typeof sante.uptime, "number");
  assert.equal(sante.version, "1.2.0");

  await Promise.all([telephone.fermer(), ctrl.fermer(), ctrl2.fermer()]);
});

test("POST /api/cmd livre au téléphone connecté", async (t) => {
  const r = await relais(t);
  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  await telephone.prochain("welcome");
  const cmd = signCmd(V.S, "ping", Date.now());
  const rep = await postCmd(r.http, V.id, cmd);
  assert.equal(rep.statut, 200);
  assert.deepEqual(rep.corps, { delivered: true });
  assert.deepEqual(await telephone.prochain("cmd"), cmd);
  // Seuls les champs du protocole passent : un champ en trop est retiré.
  await postCmd(r.http, V.id, { ...signCmd(V.S, "stop", Date.now()), extra: "x".repeat(10) });
  assert.equal("extra" in (await telephone.prochain("cmd")), false);
  await telephone.fermer();
});

test("présence : hors ligne, en ligne, puis hors ligne avec lastSeen", async (t) => {
  const r = await relais(t);
  let pres = await (await presenceApi(r.http, V.id)).json();
  assert.deepEqual(pres, { online: false, lastSeen: null });

  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  assert.deepEqual(await ctrl.prochain("presence"), { type: "presence", online: false, lastSeen: null });

  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  assert.equal((await ctrl.prochain("presence")).online, true);
  pres = await (await presenceApi(r.http, V.id)).json();
  assert.equal(pres.online, true);

  const avant = Date.now();
  await telephone.fermer();
  const horsLigne = await ctrl.prochain("presence");
  assert.equal(horsLigne.online, false);
  assert.ok(horsLigne.lastSeen >= avant - 5 && horsLigne.lastSeen <= Date.now());
  pres = await (await presenceApi(r.http, V.id)).json();
  assert.deepEqual(pres, { online: false, lastSeen: horsLigne.lastSeen });
  await ctrl.fermer();
});

test("file d'attente : ring et stop livrées à la reconnexion, dans l'ordre de réception", async (t) => {
  const r = await relais(t);
  const ring1 = signCmd(V.S, "ring", Date.now());
  const ring2 = signCmd(V.S, "ring", Date.now() + 1);
  const stop = signCmd(V.S, "stop", Date.now() + 2);
  const ping = signCmd(V.S, "ping", Date.now() + 3);

  assert.deepEqual((await postCmd(r.http, V.id, ring1)).corps, { delivered: false, queued: true });
  assert.deepEqual((await postCmd(r.http, V.id, ring2)).corps, { delivered: false, queued: true });
  // ping n'est jamais mise en file.
  assert.deepEqual((await postCmd(r.http, V.id, ping)).corps, { delivered: false, queued: false });

  // Par WebSocket aussi, avec le retour « relayed ».
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  ctrl.envoyer(stop);
  assert.deepEqual(await ctrl.prochain("relayed"), { type: "relayed", nonce: stop.nonce, delivered: false, queued: true });

  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  const accueil = await telephone.prochain("welcome");
  assert.deepEqual(accueil.queued, [ring1, ring2, stop]);
  assert.equal((await ctrl.prochain("presence")).online, true);
  // Le téléphone répond par un état : les commandes livrées sont considérées comme reçues.
  telephone.envoyer({ type: "state", ...encryptState(V.S, V.id, { ts: Date.now(), ringing: false }) });
  await ctrl.prochain("state");
  await telephone.fermer();

  // La file a été vidée par la livraison.
  const encore = ouvrirTelephone(r);
  await encore.ouvert;
  assert.deepEqual((await encore.prochain("welcome")).queued, []);
  await Promise.all([encore.fermer(), ctrl.fermer()]);
});

test("file d'attente : 5 commandes au plus par type, sans doublon de nonce", async (t) => {
  const r = await relais(t);
  const rings = [];
  for (let i = 0; i < 7; i++) rings.push(signCmd(V.S, "ring", Date.now() + i));
  for (const c of rings) await postCmd(r.http, V.id, c);
  await postCmd(r.http, V.id, rings[6]); // doublon
  const stop = signCmd(V.S, "stop", Date.now() + 10);
  await postCmd(r.http, V.id, stop);
  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  assert.deepEqual((await telephone.prochain("welcome")).queued, [...rings.slice(2), stop]);
  await telephone.fermer();
});

test("commandes en vol : relivrées si la connexion meurt avant un état du téléphone", async (t) => {
  const r = await relais(t);
  const ancien = ouvrirTelephone(r);
  await ancien.ouvert;
  await ancien.prochain("welcome");
  const ring = signCmd(V.S, "ring", Date.now());
  assert.deepEqual((await postCmd(r.http, V.id, ring)).corps, { delivered: true });
  await ancien.prochain("cmd");
  // Connexion à moitié morte : le téléphone se reconnecte, le relais remplace l'ancienne
  // connexion et relivre la ring (le téléphone ignorera le nonce s'il l'avait déjà).
  const nouveau = ouvrirTelephone(r);
  await nouveau.ouvert;
  assert.deepEqual((await nouveau.prochain("welcome")).queued, [ring]);
  // Après un état, plus rien n'est relivré.
  nouveau.envoyer({ type: "state", ...encryptState(V.S, V.id, { ts: Date.now() }) });
  await pause(50);
  await nouveau.fermer();
  const encore = ouvrirTelephone(r);
  await encore.ouvert;
  assert.deepEqual((await encore.prochain("welcome")).queued, []);
  await encore.fermer();
});

test("battement applicatif : le relais répond hb au téléphone", async (t) => {
  const r = await relais(t);
  const tel = ouvrirTelephone(r);
  await tel.ouvert;
  await tel.prochain("welcome");
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  tel.envoyer({ type: "hb" });
  assert.deepEqual(await tel.prochain("hb"), { type: "hb" });
  // hb n'est ni relayé aux contrôleurs ni compté comme message mal formé.
  await assert.rejects(ctrl.prochain("state", 150));
  assert.equal(r.stats.refusForme, 0);
  await Promise.all([tel.fermer(), ctrl.fermer()]);
});

test("limites de connexions ouvertes : par IP, au total et contrôleurs par id", async (t) => {
  const r = await relais(t, { maxConnexionsParIp: 3, maxCtrlsParId: 2, limiteIp: 1000 });
  const a = ouvrirControleur(r);
  const b = ouvrirControleur(r);
  await Promise.all([a.ouvert, b.ouvert]);
  // Troisième contrôleur du même id : refusé.
  await assert.rejects(ouvrirControleur(r).ouvert, (e) => e.statut === 429);
  // Troisième connexion de l'IP (autre id) : acceptée, la quatrième non.
  const c = ouvrirControleur(r, deriveId(genererSecret()));
  await c.ouvert;
  await assert.rejects(ouvrirControleur(r, deriveId(genererSecret())).ouvert, (e) => e.statut === 429);
  // Une fermeture libère une place.
  await a.fermer();
  await pause(50);
  const d = ouvrirControleur(r, deriveId(genererSecret()));
  await d.ouvert;
  await Promise.all([b.fermer(), c.fermer(), d.fermer()]);

  const r2 = await relais(t, { maxConnexions: 1, limiteIp: 1000 });
  const seul = ouvrirTelephone(r2);
  await seul.ouvert;
  await assert.rejects(ouvrirControleur(r2).ouvert, (e) => e.statut === 503);
  await seul.fermer();
});

test("file d'attente : une commande trop ancienne expire", async (t) => {
  const r = await relais(t, { fileMs: 100 });
  await postCmd(r.http, V.id, signCmd(V.S, "ring", Date.now()));
  await pause(150);
  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  assert.deepEqual((await telephone.prochain("welcome")).queued, []);
  await telephone.fermer();
});

test("un nouveau téléphone pour le même id remplace l'ancien", async (t) => {
  const r = await relais(t);
  const ancien = ouvrirTelephone(r);
  await ancien.ouvert;
  await ancien.prochain("welcome");
  const nouveau = ouvrirTelephone(r);
  await nouveau.ouvert;
  await nouveau.prochain("welcome");
  assert.equal((await ancien.ferme).code, 4000);
  const cmd = signCmd(V.S, "ring", Date.now());
  assert.deepEqual((await postCmd(r.http, V.id, cmd)).corps, { delivered: true });
  assert.deepEqual(await nouveau.prochain("cmd"), cmd);
  await nouveau.fermer();
});

test("limite de débit : 20 commandes par minute et par id", async (t) => {
  const r = await relais(t);
  for (let i = 0; i < 20; i++) {
    const rep = await postCmd(r.http, V.id, signCmd(V.S, "ping", Date.now()));
    assert.equal(rep.statut, 200, `commande ${i + 1}`);
  }
  const refus = await postCmd(r.http, V.id, signCmd(V.S, "ping", Date.now()));
  assert.equal(refus.statut, 429);

  // Par WebSocket : message d'erreur au contrôleur, rien ne passe.
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  const cmd = signCmd(V.S, "ring", Date.now());
  ctrl.envoyer(cmd);
  const err = await ctrl.prochain("error");
  assert.equal(err.status, 429);
  assert.equal(err.nonce, cmd.nonce);
  await ctrl.fermer();

  // Un autre id n'est pas touché.
  const S2 = genererSecret();
  assert.equal((await postCmd(r.http, deriveId(S2), signCmd(S2, "ping", Date.now()))).statut, 200);
});

test("limite de débit : 60 requêtes par minute et par IP", async (t) => {
  const r = await relais(t);
  // 3 ids x 20 commandes = 60, toutes acceptées ; la 61e est refusée même sur un id neuf.
  for (let k = 0; k < 3; k++) {
    const S = genererSecret();
    const id = deriveId(S);
    for (let i = 0; i < 20; i++) {
      assert.equal((await postCmd(r.http, id, signCmd(S, "ping", Date.now()))).statut, 200);
    }
  }
  const S = genererSecret();
  assert.equal((await postCmd(r.http, deriveId(S), signCmd(S, "ping", Date.now()))).statut, 429);
  // Les connexions WebSocket de la même IP sont aussi refusées.
  const ctrl = ouvrirControleur(r);
  await assert.rejects(ctrl.ouvert, (e) => e.statut === 429);
});

test("derrière un proxy de confiance, la limite par IP suit X-Forwarded-For", async (t) => {
  const r = await relais(t, { faireConfianceAuProxy: true, limiteIp: 2 });
  const presence = (ip) =>
    fetch(`${r.http}/api/presence`, { headers: { "X-Sonne-Id": V.id, "X-Forwarded-For": `10.0.0.9, ${ip}` } }).then(
      (x) => x.status,
    );
  assert.equal(await presence("1.1.1.1"), 200);
  assert.equal(await presence("1.1.1.1"), 200);
  assert.equal(await presence("1.1.1.1"), 429);
  assert.equal(await presence("2.2.2.2"), 200);
  // IPv6 : tout le /64 compte pour une seule adresse.
  assert.equal(await presence("2001:db8:1:2::1"), 200);
  assert.equal(await presence("2001:db8:1:2:ffff::9"), 200);
  assert.equal(await presence("2001:db8:1:2:abcd:1:2:3"), 429);
  assert.equal(await presence("2001:db8:1:3::1"), 200);
  // IPv4 mappée : la même que l'IPv4.
  assert.equal(await presence("::ffff:2.2.2.2"), 200);
  assert.equal(await presence("2.2.2.2"), 429);
});

test("normalisation des adresses pour les limites", () => {
  assert.equal(cleIp("2001:db8:abcd:12:1:2:3:4"), "2001:db8:abcd:12::/64");
  assert.equal(cleIp("2001:DB8:ABCD:0012::9"), "2001:db8:abcd:12::/64");
  assert.equal(cleIp("2001:db8::1"), "2001:db8:0:0::/64");
  assert.equal(cleIp("::ffff:1.2.3.4"), "1.2.3.4");
  assert.equal(cleIp("1.2.3.4"), "1.2.3.4");
  assert.equal(cleIp("fe80::1%en0"), "fe80:0:0:0::/64");
  assert.equal(cleIp("[2001:db8:1:2::5]"), "2001:db8:1:2::/64");
  assert.equal(cleIp(undefined), "?");
});

test("id ou preuve invalides refusés partout", async (t) => {
  const r = await relais(t);
  for (const mauvais of ["abc", V.id.toUpperCase(), V.id + "0", "g".repeat(32), "..-..-etc"]) {
    const c = client(`${r.ws}/ws/ctrl`, ["sonne.v2", `sonne.id.${mauvais}`]);
    await assert.rejects(c.ouvert, (e) => e.statut === 400, `ctrl ${mauvais}`);
    const p = client(`${r.ws}/ws/phone`, ["sonne.v2", `sonne.p.${mauvais}`]);
    await assert.rejects(p.ouvert, (e) => e.statut === 400, `phone ${mauvais}`);
    assert.equal((await presenceApi(r.http, mauvais)).status, 400);
    assert.equal((await postCmd(r.http, mauvais, signCmd(V.S, "ring", Date.now()))).statut, 400);
  }
  // L'ancien format (id dans l'URL) n'est plus accepté : il fuirait dans les journaux d'accès.
  for (const role of ["phone", "ctrl"]) {
    const c = client(`${r.ws}/ws/${role}?id=${V.id}`);
    await assert.rejects(c.ouvert, (e) => e.statut === 400, `${role} ?id=`);
  }
  assert.equal((await fetch(`${r.http}/api/presence?id=${V.id}`)).status, 400);
  // Sans le sous-protocole sonne.v2 : refusé.
  const sansV2 = client(`${r.ws}/ws/ctrl`, [`sonne.id.${V.id}`]);
  await assert.rejects(sansV2.ouvert, (e) => e.statut === 400);
  // Un téléphone ne peut pas se présenter avec un id : il lui faut P.
  const telAvecId = client(`${r.ws}/ws/phone`, ["sonne.v2", `sonne.id.${V.id}`]);
  await assert.rejects(telAvecId.ouvert, (e) => e.statut === 400);
  const inconnu = client(`${r.ws}/ws/autre`, ["sonne.v2", `sonne.id.${V.id}`]);
  await assert.rejects(inconnu.ouvert, (e) => e.statut === 404);
  // Le relais renvoie le sous-protocole commun.
  const bon = ouvrirControleur(r);
  await bon.ouvert;
  assert.equal(bon.ws.protocol, "sonne.v2");
  await bon.fermer();
});

test("un faux téléphone qui ne connaît que l'id ne peut ni éjecter le vrai ni prendre sa file", async (t) => {
  const r = await relais(t);
  // Une ring légitime attend en file.
  const bonne = signCmd(V.S, "ring", Date.now());
  await postCmd(r.http, V.id, bonne);
  // L'attaquant connaît l'id mais pas S : la seule chose qu'il peut présenter est une preuve
  // quelconque, qui donne un autre id. Le canal de V.id n'est pas touché.
  const faux = client(`${r.ws}/ws/phone`, ["sonne.v2", `sonne.p.${"ab".repeat(16)}`]);
  await faux.ouvert;
  assert.deepEqual((await faux.prochain("welcome")).queued, []);
  // Il ne peut pas non plus remplacer la ring en file par une ring mal signée :
  // les deux sont gardées et le vrai téléphone choisira.
  const mauvaise = { ...signCmd(V.S, "ring", Date.now() + 1), sig: "f".repeat(64) };
  await postCmd(r.http, V.id, mauvaise);
  const vrai = ouvrirTelephone(r);
  await vrai.ouvert;
  assert.deepEqual((await vrai.prochain("welcome")).queued, [bonne, mauvaise]);
  // Et le vrai téléphone n'est pas éjecté par le faux.
  const faux2 = client(`${r.ws}/ws/phone`, ["sonne.v2", `sonne.p.${"cd".repeat(16)}`]);
  await faux2.ouvert;
  await faux2.prochain("welcome");
  await assert.rejects(Promise.race([vrai.ferme, pause(200).then(() => Promise.reject(new Error("ouvert")))]), /ouvert/);
  await Promise.all([faux.fermer(), faux2.fermer(), vrai.fermer()]);
});

test("commandes mal formées refusées", async (t) => {
  const r = await relais(t);
  const bonne = signCmd(V.S, "ring", Date.now());
  const mauvaises = [
    "pas du json",
    { ...bonne, type: "state" },
    { ...bonne, cmd: "explose" },
    { ...bonne, ts: "123" },
    { ...bonne, nonce: "00" },
    { ...bonne, nonce: bonne.nonce.toUpperCase() },
    { ...bonne, sig: "zz" },
    [bonne],
    null,
  ];
  for (const m of mauvaises) {
    const rep = await postCmd(r.http, V.id, typeof m === "string" ? m : JSON.stringify(m));
    assert.equal(rep.statut, 400, JSON.stringify(m));
  }
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  ctrl.envoyer({ ...bonne, sig: "00" });
  assert.equal((await ctrl.prochain("error")).status, 400);
  await ctrl.fermer();
});

test("4 Ko maximum par message", async (t) => {
  const r = await relais(t);
  const gros = { ...signCmd(V.S, "ring", Date.now()), bourrage: "x".repeat(5000) };
  assert.equal((await postCmd(r.http, V.id, gros)).statut, 413);
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  ctrl.envoyer(gros);
  assert.equal((await ctrl.ferme).code, 1009);
  // Le relais continue de servir.
  assert.equal((await fetch(`${r.http}/health`)).status, 200);
});

test("ping régulier et fermeture d'une connexion qui ne répond plus", async (t) => {
  const r = await relais(t, { pingTelephoneMs: 40, delaiMortTelephoneMs: 150 });
  // Un client qui répond aux pings reste ouvert.
  const vivant = ouvrirTelephone(r);
  let pings = 0;
  vivant.ws.on("ping", () => (pings += 1));
  await vivant.ouvert;
  // Un client muet (pas de pong automatique) est coupé.
  const S2 = genererSecret();
  const muet = ouvrirTelephone(r, S2, { autoPong: false });
  await muet.ouvert;
  const fin = await muet.ferme;
  assert.equal(fin.code, 1006, "coupure franche attendue");
  assert.ok(pings >= 2, `pings reçus : ${pings}`);
  assert.equal(vivant.ws.readyState, WebSocket.OPEN);
  await vivant.fermer();
});

test("ping : espacé pour le téléphone, fréquent pour les contrôleurs", async (t) => {
  const r = await relais(t, { pingMs: 30, delaiMortMs: 500, pingTelephoneMs: 60_000, delaiMortTelephoneMs: 120_000 });
  const tel = ouvrirTelephone(r);
  const ctrl = ouvrirControleur(r);
  let pingsTel = 0;
  let pingsCtrl = 0;
  tel.ws.on("ping", () => (pingsTel += 1));
  ctrl.ws.on("ping", () => (pingsCtrl += 1));
  await Promise.all([tel.ouvert, ctrl.ouvert]);
  await pause(200);
  assert.equal(pingsTel, 0);
  assert.ok(pingsCtrl >= 3, `pings contrôleur : ${pingsCtrl}`);
  await Promise.all([tel.fermer(), ctrl.fermer()]);
});

test("fichiers statiques et en-têtes", async (t) => {
  const r = await relais(t);
  const page = await fetch(`${r.http}/`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-type"), /text\/html/);
  assert.match(page.headers.get("content-security-policy"), /default-src 'self'/);
  assert.match(await page.text(), /<span>Ring<\/span>/);
  for (const [fichier, type] of [
    ["app.js", /javascript/],
    ["crypto.js", /javascript/],
    ["style.css", /css/],
    ["manifest.webmanifest", /manifest\+json/],
    ["sw.js", /javascript/],
    ["icon.svg", /svg/],
  ]) {
    const res = await fetch(`${r.http}/${fichier}`);
    assert.equal(res.status, 200, fichier);
    assert.match(res.headers.get("content-type"), type, fichier);
  }
  assert.equal((await fetch(`${r.http}/absent.js`)).status, 404);
  assert.equal((await fetch(`${r.http}/%2e%2e/relay/package.json`)).status, 404);
  assert.equal((await fetch(`${r.http}/..%2f..%2fetc%2fpasswd`)).status, 404);
  assert.equal((await fetch(`${r.http}/ws/phone`)).status, 426);
});

test("arrêt propre : les connexions sont fermées avec le code 1001", async (t) => {
  const r = await demarrer({ port: 0, hote: "127.0.0.1", silencieux: true });
  const telephone = ouvrirTelephone({ ws: `ws://127.0.0.1:${r.port}` });
  await telephone.ouvert;
  await r.arreter();
  assert.equal((await telephone.ferme).code, 1001);
});

test("fichiers statiques : revalidation par ETag", async (t) => {
  const r = await relais(t);
  const premier = await fetch(`${r.http}/app.js`);
  const etag = premier.headers.get("etag");
  assert.ok(etag);
  assert.equal(premier.headers.get("cache-control"), "no-cache");
  const second = await fetch(`${r.http}/app.js`, { headers: { "If-None-Match": etag } });
  assert.equal(second.status, 304);
});

// ---------------------------------------------------------------------------
// Protocole v3 : commandes chiffrées (le relais n'en voit ni le type ni le contenu)
// ---------------------------------------------------------------------------

const V3 = V.commandes_v3;
const v3 = (cmd, ts = Date.now(), S = V.S) => chiffrerCommande(S, cmd, ts);

test("v3 : relayée telle quelle en 172 octets, retour avec le nonce de 24 hex", async (t) => {
  const r = await relais(t);
  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  await telephone.prochain("welcome");
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");

  // Par le WebSocket des contrôleurs : le texte exact du vecteur arrive octet pour octet.
  ctrl.envoyer(V3.v3_ring.texte);
  assert.deepEqual(await telephone.prochain("cmd"), V3.v3_ring.message);
  assert.equal(telephone.bruts.at(-1), V3.v3_ring.texte);
  assert.equal(Buffer.byteLength(telephone.bruts.at(-1)), TAILLE_MESSAGE_COMMANDE);
  assert.deepEqual(await ctrl.prochain("relayed"), { type: "relayed", nonce: V3.v3_ring.message.nonce, delivered: true });

  // Par POST /api/cmd, avec des champs en trop (même un « cmd » en clair) : copie propre, 172 octets.
  const ping = v3("ping");
  const rep = await postCmd(r.http, V.id, { ...ping, cmd: "ping", ts: 1, extra: "x" });
  assert.deepEqual(rep, { statut: 200, corps: { delivered: true } });
  assert.deepEqual(await telephone.prochain("cmd"), ping);
  assert.equal(telephone.bruts.at(-1), texteCommande(ping));
  assert.equal(Buffer.byteLength(telephone.bruts.at(-1)), TAILLE_MESSAGE_COMMANDE);

  // Le relais ne fait aucune différence entre ring, stop, ping et une ring avec paramètres.
  for (const nom of ["v3_stop", "v3_ping", "v3_ring_parametres"]) {
    ctrl.envoyer(V3[nom].texte);
    assert.equal((await telephone.prochain("cmd")).nonce, V3[nom].message.nonce);
    assert.equal(telephone.bruts.at(-1), V3[nom].texte, nom);
    assert.deepEqual(await ctrl.prochain("relayed"), { type: "relayed", nonce: V3[nom].message.nonce, delivered: true });
  }

  // Une v2 passe encore pendant la transition ; le journal compte les formats, /health ne les montre pas.
  const v2 = signCmd(V.S, "ring", Date.now());
  ctrl.envoyer(v2);
  assert.deepEqual(await telephone.prochain("cmd"), v2);
  assert.equal(r.stats.cmdV3, 5);
  assert.equal(r.stats.cmdV2, 1);
  const sante = await (await fetch(`${r.http}/health`)).json();
  assert.deepEqual(Object.keys(sante).sort(), ["ctrls", "ok", "phones", "uptime", "version"]);
  await Promise.all([telephone.fermer(), ctrl.fermer()]);
});

test("v3 : toute commande est mise en file hors ligne, même un ping, dans l'ordre avec la v2", async (t) => {
  const r = await relais(t);
  const ring2 = signCmd(V.S, "ring", Date.now());
  const ping2 = signCmd(V.S, "ping", Date.now());
  const ping3 = v3("ping");
  const stop3 = v3("stop");
  assert.deepEqual((await postCmd(r.http, V.id, ring2)).corps, { delivered: false, queued: true });
  // Le ping v3 est indiscernable : mis en file et annoncé comme tel.
  assert.deepEqual((await postCmd(r.http, V.id, ping3)).corps, { delivered: false, queued: true });
  // Le ping v2, lui, ne l'est toujours pas.
  assert.deepEqual((await postCmd(r.http, V.id, ping2)).corps, { delivered: false, queued: false });
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  ctrl.envoyer(texteCommande(stop3));
  assert.deepEqual(await ctrl.prochain("relayed"), { type: "relayed", nonce: stop3.nonce, delivered: false, queued: true });

  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  assert.deepEqual((await telephone.prochain("welcome")).queued, [ring2, ping3, stop3]);
  // Chaque commande v3 de la file garde ses 172 octets dans le message welcome.
  assert.ok(telephone.bruts[0].includes(texteCommande(ping3)));
  assert.ok(telephone.bruts[0].includes(texteCommande(stop3)));
  await Promise.all([telephone.fermer(), ctrl.fermer()]);
});

test("v3 : 10 au plus en file, sans doublon de nonce, sans toucher aux v2", async (t) => {
  const r = await relais(t);
  const ring2 = signCmd(V.S, "ring", Date.now());
  await postCmd(r.http, V.id, ring2);
  const commandes = [];
  for (let i = 0; i < FILE_V3 + 2; i++) commandes.push(v3(["ring", "stop", "ping"][i % 3], Date.now() + i));
  for (const c of commandes) assert.deepEqual((await postCmd(r.http, V.id, c)).corps, { delivered: false, queued: true });
  await postCmd(r.http, V.id, commandes.at(-1)); // doublon
  const stop2 = signCmd(V.S, "stop", Date.now() + 100);
  await postCmd(r.http, V.id, stop2);
  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  // Les deux plus anciennes v3 sont sorties ; la ring v2 reçue avant elles est restée.
  assert.deepEqual((await telephone.prochain("welcome")).queued, [ring2, ...commandes.slice(2), stop2]);
  await telephone.fermer();
});

test("v3 : commandes en vol relivrées si la connexion meurt avant un état", async (t) => {
  const r = await relais(t);
  const ancien = ouvrirTelephone(r);
  await ancien.ouvert;
  await ancien.prochain("welcome");
  // Toutes les v3 sont suivies, ping compris : le relais ne sait pas lequel c'est.
  const ping = v3("ping");
  const ring = v3("ring", Date.now() + 1);
  assert.deepEqual((await postCmd(r.http, V.id, ping)).corps, { delivered: true });
  assert.deepEqual((await postCmd(r.http, V.id, ring)).corps, { delivered: true });
  await ancien.prochain("cmd");
  await ancien.prochain("cmd");
  const nouveau = ouvrirTelephone(r);
  await nouveau.ouvert;
  assert.deepEqual((await nouveau.prochain("welcome")).queued, [ping, ring]);
  // Un état (un seul par commande en 1.6) : plus rien n'est relivré.
  nouveau.envoyer({ type: "state", ...encryptState(V.S, V.id, { ts: Date.now() }) });
  await pause(50);
  await nouveau.fermer();
  const encore = ouvrirTelephone(r);
  await encore.ouvert;
  assert.deepEqual((await encore.prochain("welcome")).queued, []);
  await encore.fermer();
});

test("v3 : formes refusées en 400, comme une v2 mal formée", async (t) => {
  const r = await relais(t);
  const m = V3.v3_ring.message;
  const mauvaises = [
    { ...m, v: "3" },
    { ...m, v: 4 },
    { ...m, v: null },
    { ...m, nonce: m.nonce.toUpperCase() },
    { ...m, nonce: m.nonce + "00" },
    { ...m, enc: m.enc.slice(0, -4) },
    { ...m, enc: m.enc.replace(/\+/g, "-").replace(/\//g, "_") },
    { ...m, enc: Buffer.alloc(81).toString("base64") },
    { type: "cmd", v: 3, nonce: m.nonce },
    { ...m, type: "state" },
  ];
  for (const f of mauvaises) {
    const rep = await postCmd(r.http, V.id, f);
    assert.equal(rep.statut, 400, JSON.stringify(f));
    assert.equal(rep.corps.error, "invalid command");
    assert.equal(commandeValide(f), null);
  }
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  ctrl.envoyer({ ...m, enc: "AAAA" });
  assert.deepEqual(await ctrl.prochain("error"), { type: "error", nonce: m.nonce, status: 400, error: "invalid command" });
  assert.equal(r.stats.cmdV3, 0);
  await ctrl.fermer();
});

test("coupure : une v2 est refusée en 400, la v3 passe toujours", async (t) => {
  const r = await relais(t, { accepterV2: false });
  const telephone = ouvrirTelephone(r);
  await telephone.ouvert;
  await telephone.prochain("welcome");
  const v2 = signCmd(V.S, "ring", Date.now());
  assert.deepEqual(await postCmd(r.http, V.id, v2), { statut: 400, corps: { error: ERREUR_ANCIEN_FORMAT } });
  // Même une v2 incomplète : c'est l'ancien format, l'émetteur doit passer en v3.
  assert.deepEqual((await postCmd(r.http, V.id, { type: "cmd", cmd: "ping" })).corps, { error: ERREUR_ANCIEN_FORMAT });
  const ctrl = ouvrirControleur(r);
  await ctrl.ouvert;
  await ctrl.prochain("presence");
  ctrl.envoyer(v2);
  // Le nonce de 32 hex est repris pour que la page renvoie la même intention en v3.
  assert.deepEqual(await ctrl.prochain("error"), { type: "error", nonce: v2.nonce, status: 400, error: ERREUR_ANCIEN_FORMAT });
  // Rien n'a été livré ni mis en file.
  await assert.rejects(telephone.prochain("cmd", 150));
  const nouvelle = v3("ring");
  ctrl.envoyer(texteCommande(nouvelle));
  assert.deepEqual(await telephone.prochain("cmd"), nouvelle);
  assert.deepEqual(await ctrl.prochain("relayed"), { type: "relayed", nonce: nouvelle.nonce, delivered: true });
  assert.equal(r.stats.refusV2, 3);
  assert.equal(r.stats.cmdV2, 0);
  assert.equal(r.stats.cmdV3, 1);
  assert.equal(commandeValide(v2, { accepterV2: false }), null);
  assert.deepEqual(commandeValide(v2), v2);
  await Promise.all([telephone.fermer(), ctrl.fermer()]);
});

test("SONNE_CMD_V2=0 au lancement : le relais refuse la v2", async (t) => {
  const serveur = fileURLToPath(new URL("../server.js", import.meta.url));
  const lancer = (env) =>
    new Promise((resolve, reject) => {
      const p = spawn(process.execPath, [serveur], {
        // Une valeur undefined n'est pas transmise : sans SONNE_CMD_V2, le défaut s'applique.
        env: { ...process.env, PORT: "0", SONNE_CMD_V2: undefined, ...env },
        stdio: ["ignore", "pipe", "pipe"],
      });
      t.after(() => p.kill("SIGTERM"));
      let sortie = "";
      p.stdout.on("data", (d) => {
        sortie += d.toString();
        const port = /sur le port (\d+)/.exec(sortie)?.[1];
        if (port) resolve({ port, sortie });
      });
      p.once("exit", (code) => reject(new Error(`relais arrêté (${code}) : ${sortie}`)));
      setTimeout(() => reject(new Error(`relais muet : ${sortie}`)), 5000);
    });
  const coupe = await lancer({ SONNE_CMD_V2: "0" });
  assert.match(coupe.sortie, /commandes v3 seulement/);
  const v2 = signCmd(V.S, "stop", Date.now());
  assert.equal((await postCmd(`http://127.0.0.1:${coupe.port}`, V.id, v2)).statut, 400);
  assert.equal((await postCmd(`http://127.0.0.1:${coupe.port}`, V.id, v3("stop"))).statut, 200);
  // Sans la variable : transition, la v2 passe.
  const transition = await lancer({});
  assert.match(transition.sortie, /commandes v3 et v2/);
  assert.equal((await postCmd(`http://127.0.0.1:${transition.port}`, V.id, v2)).statut, 200);
});
