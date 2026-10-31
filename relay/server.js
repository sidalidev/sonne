// Relais de Sonne (voir docs/PROTOCOLE.md, sections « Relais » et « Protocole v3 : commandes chiffrées »).
//
// Rôle : faire passer des messages opaques entre un téléphone et ses contrôleurs.
// Le relais ne connaît que l'id et, pour le téléphone, la preuve P dont l'id est
// dérivé : il ne peut ni lire ni vérifier les commandes, ni lire les états chiffrés.
// Il ne persiste rien.
//
// Commandes v3 (chiffrées, 172 octets) : le relais n'en voit que la forme et le nonce,
// jamais le type ; toutes sont traitées de la même façon (file, en vol, réponse).
// Commandes v2 (signées, en clair) : acceptées seulement pendant la transition
// (SONNE_CMD_V2=1), refusées en 400 après la coupure. Le journal ne compte que le format.
//
// L'id ne passe jamais dans l'URL (les journaux d'accès des proxys l'écriraient) :
//   WebSocket : en-tête Sec-WebSocket-Protocol « sonne.v2, sonne.p.<P> » (téléphone)
//               ou « sonne.v2, sonne.id.<id> » (contrôleur) ;
//   HTTP /api : en-tête X-Sonne-Id.
//
// Routes :
//   GET  /              page web de commande (fichiers statiques de ../web)
//   GET  /ws/phone      WebSocket du téléphone (authentifié par P)
//   GET  /ws/ctrl       WebSocket des contrôleurs (plusieurs possibles)
//   POST /api/cmd       commande en corps JSON (v3 chiffrée, ou v2 signée pendant la transition)
//   GET  /api/presence  { online, lastSeen }
//   GET  /health        { ok, phones, ctrls, uptime, version }
//   GET  /update/android.json   annonce de la dernière version de l'app (mise à jour intégrée)
//   GET  /download/<nom>.apk    APK publiés (dossier SONNE_RELEASES_DIR, lecture seule)
//
// Variables : PORT (7700), SONNE_QUEUE_MIN (15, 15 au plus), SONNE_TRUST_PROXY (0),
// SONNE_WEB_DIR (../web), SONNE_PHONE_PING_S (240), SONNE_PHONE_DEAD_S (600),
// SONNE_RELEASES_DIR (aucun : /update et /download répondent 404),
// SONNE_CMD_V2 (1 : commandes v2 encore acceptées, transition de la 1.6 ; 0 après la coupure).

import http from "node:http";
import { isIP } from "node:net";
import { readFileSync, createReadStream } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer, WebSocket } from "ws";
import { estIdValide, idDepuisPreuve, formeCommandeV3, COMMANDES, VERSION_COMMANDE } from "./crypto.js";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const VERSION = JSON.parse(readFileSync(path.join(ICI, "package.json"), "utf8")).version;

/** Taille maximale d'un message, WebSocket ou corps HTTP. */
const TAILLE_MAX = 4096;
const MINUTE = 60_000;

const RE_NONCE_CMD = /^[0-9a-f]{32}$/;
/** Nonce d'une commande, v2 (32 hex) ou v3 (24 hex), repris dans « relayed » et « error ». */
const RE_NONCE_RETOUR = /^(?:[0-9a-f]{24}|[0-9a-f]{32})$/;
const RE_SIG = /^[0-9a-f]{64}$/;
const RE_NONCE_ETAT = /^[0-9a-f]{24}$/;
const RE_BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;
const RE_PREUVE = /^[0-9a-f]{32}$/;

/** Nom d'un APK servi par /download : pas de « / », pas de « .. », pas d'encodage. */
export const RE_NOM_APK = /^[A-Za-z0-9][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)*\.apk$/;
const LONGUEUR_MAX_NOM_APK = 100;
/** Annonce de mise à jour : 64 Ko au plus, gardée 5 min dans les caches. */
const TAILLE_MAX_ANNONCE = 64 * 1024;
const CACHE_ANNONCE = "public, max-age=300";

/** Vrai si le nom peut être servi par /download (validation stricte, voir RE_NOM_APK). */
export function nomApkValide(nom) {
  return typeof nom === "string" && nom.length <= LONGUEUR_MAX_NOM_APK && RE_NOM_APK.test(nom) && !nom.includes("..");
}

/** Sous-protocole WebSocket obligatoire, renvoyé par le relais à l'ouverture. */
export const SOUS_PROTOCOLE = "sonne.v2";
const PREFIXE_PREUVE = "sonne.p.";
const PREFIXE_ID = "sonne.id.";

/** Nombre de commandes v2 gardées en file par type (ring, stop) quand le téléphone est hors ligne. */
const FILE_PAR_TYPE = 5;
/** Nombre de commandes v3 gardées en file (types invisibles : toutes ensemble). */
export const FILE_V3 = 10;

/** Réponse à une commande v2 quand le relais n'accepte plus que la v3 (après la coupure). */
export const ERREUR_ANCIEN_FORMAT = "old command format: reload the page or update the app";

const TYPES_MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
  ".ttf": "font/ttf",
};

const ENTETES_SECURITE = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "X-Frame-Options": "DENY",
  "Content-Security-Policy":
    "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; " +
    "manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
};

/** Vrai pour une commande v3 (champ v présent), quelle que soit sa forme. */
const estV3 = (msg) => msg.v !== undefined;

/**
 * Vérifie la forme d'une commande. Renvoie une copie propre (seuls les champs du protocole) ou null.
 * v3 : { type, v, nonce, enc } (formeCommandeV3 : nonce de 24 hex, enc de 80 octets) ; le relais
 * n'a pas K_cmd et ne peut rien vérifier de plus. v2 : { type, cmd, ts, nonce, sig }, seulement
 * si accepterV2 (transition) ; sinon null.
 */
export function commandeValide(msg, { accepterV2 = true } = {}) {
  if (!msg || typeof msg !== "object" || Array.isArray(msg)) return null;
  if (estV3(msg)) return formeCommandeV3(msg);
  if (!accepterV2) return null;
  const { type, cmd, ts, nonce, sig } = msg;
  if (type !== "cmd") return null;
  if (!COMMANDES.includes(cmd)) return null;
  if (!Number.isSafeInteger(ts) || ts < 0) return null;
  if (typeof nonce !== "string" || !RE_NONCE_CMD.test(nonce)) return null;
  if (typeof sig !== "string" || !RE_SIG.test(sig)) return null;
  return { type: "cmd", cmd, ts, nonce, sig };
}

/** Vérifie la forme d'un état chiffré. Renvoie une copie propre ou null. */
export function etatValide(msg) {
  if (!msg || typeof msg !== "object" || Array.isArray(msg)) return null;
  const { type, nonce, enc } = msg;
  if (type !== "state") return null;
  if (typeof nonce !== "string" || !RE_NONCE_ETAT.test(nonce)) return null;
  if (typeof enc !== "string" || enc.length < 24 || !RE_BASE64.test(enc)) return null;
  return { type: "state", nonce, enc };
}

/**
 * Clé utilisée pour les limites par adresse. Une IPv4 mappée (::ffff:a.b.c.d) redevient
 * une IPv4 ; une IPv6 est ramenée à son préfixe /64, puisqu'un client dispose en général
 * d'un /64 entier et pourrait sinon changer d'adresse à chaque requête.
 */
export function cleIp(adresse) {
  if (typeof adresse !== "string" || !adresse.trim()) return "?";
  let a = adresse.trim();
  if (a.startsWith("[") && a.endsWith("]")) a = a.slice(1, -1);
  const zone = a.indexOf("%");
  if (zone >= 0) a = a.slice(0, zone);
  const mappee = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/i.exec(a);
  if (mappee) return mappee[1];
  if (isIP(a) !== 6) return a;
  // IPv4 embarquée en fin d'adresse (64:ff9b::1.2.3.4) : remplacée par deux groupes hex.
  const v4 = /(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(a);
  if (v4) {
    const [x, y, z, w] = v4.slice(1).map(Number);
    a = a.slice(0, v4.index) + ((x << 8) | y).toString(16) + ":" + ((z << 8) | w).toString(16);
  }
  const [tete, queue] = a.split("::");
  const debut = tete ? tete.split(":") : [];
  const fin = queue === undefined ? null : queue ? queue.split(":") : [];
  const groupes = fin === null ? debut : [...debut, ...Array(8 - debut.length - fin.length).fill("0"), ...fin];
  return groupes.slice(0, 4).map((g) => Number.parseInt(g, 16).toString(16)).join(":") + "::/64";
}

/** Les jetons de l'en-tête Sec-WebSocket-Protocol, sans espaces. */
function sousProtocoles(req) {
  const brut = req.headers["sec-websocket-protocol"];
  if (typeof brut !== "string") return [];
  return brut.split(",").map((t) => t.trim()).filter(Boolean);
}

/**
 * Identifie un client WebSocket d'après ses sous-protocoles.
 * Téléphone : « sonne.p.<P en hex> », l'id est recalculé depuis P (preuve qu'il détient S).
 * Contrôleur : « sonne.id.<id> ».
 * Renvoie { id } ou { statut, erreur }.
 */
export function identifier(req, role) {
  const jetons = sousProtocoles(req);
  if (!jetons.includes(SOUS_PROTOCOLE)) {
    return { statut: 400, erreur: `sous-protocole ${SOUS_PROTOCOLE} attendu (Sec-WebSocket-Protocol)` };
  }
  if (role === "phone") {
    const jeton = jetons.find((t) => t.startsWith(PREFIXE_PREUVE));
    const hex = jeton?.slice(PREFIXE_PREUVE.length);
    if (!hex || !RE_PREUVE.test(hex)) return { statut: 400, erreur: "preuve invalide : sonne.p.<32 hex> attendu" };
    return { id: idDepuisPreuve(Buffer.from(hex, "hex")) };
  }
  const jeton = jetons.find((t) => t.startsWith(PREFIXE_ID));
  const id = jeton?.slice(PREFIXE_ID.length);
  if (!estIdValide(id)) return { statut: 400, erreur: "id invalide : sonne.id.<32 hex> attendu" };
  return { id };
}

/** Les 6 premiers caractères d'un id, seuls autorisés dans le journal. */
const court = (id) => (typeof id === "string" ? id.slice(0, 6) : "?");

/**
 * Compteur à fenêtre fixe d'une minute par clé.
 * autorise(cle) renvoie false quand la limite est atteinte.
 */
function limiteur(max, fenetre = MINUTE) {
  const compteurs = new Map();
  return {
    autorise(cle, maintenant = Date.now()) {
      let c = compteurs.get(cle);
      if (!c || maintenant - c.debut >= fenetre) {
        c = { debut: maintenant, n: 0 };
        compteurs.set(cle, c);
      }
      c.n += 1;
      return c.n <= max;
    },
    nettoyer(maintenant = Date.now()) {
      for (const [cle, c] of compteurs) if (maintenant - c.debut >= fenetre) compteurs.delete(cle);
    },
    get taille() {
      return compteurs.size;
    },
  };
}

/**
 * Démarre un relais. Renvoie une promesse de { serveur, port, arreter() }.
 * Options (toutes facultatives) :
 *   port, hote, dossierWeb, fileMs (durée de la file d'attente),
 *   pingMs et delaiMortMs (contrôleurs), pingTelephoneMs et delaiMortTelephoneMs (téléphone),
 *   limiteId, limiteIp, maxConnexionsParIp, maxConnexions, maxCtrlsParId,
 *   faireConfianceAuProxy, silencieux,
 *   dossierReleases (APK et android.json de la mise à jour intégrée ; absent = routes 404),
 *   accepterV2 (true pendant la transition de la 1.6 : commandes v2 encore relayées ; false après la coupure).
 */
export function demarrer(options = {}) {
  const o = {
    port: 7700,
    hote: undefined,
    dossierWeb: path.resolve(ICI, "../web"),
    fileMs: 15 * MINUTE,
    // Contrôleurs : des navigateurs au premier plan, on peut les sonder souvent.
    pingMs: 25_000,
    delaiMortMs: 60_000,
    // Téléphone : chaque ping réveille le processeur et la radio, on espace (batterie).
    pingTelephoneMs: 4 * MINUTE,
    delaiMortTelephoneMs: 10 * MINUTE,
    limiteId: 20,
    limiteIp: 60,
    maxConnexionsParIp: 20,
    maxConnexions: 10_000,
    maxCtrlsParId: 16,
    faireConfianceAuProxy: false,
    silencieux: false,
    dossierReleases: null,
    accepterV2: true,
    ...options,
  };
  const debut = Date.now();

  // -------------------------------------------------------------------------
  // Journal : compteurs et erreurs uniquement, jamais d'id complet ni de type de commande.
  // cmdV2 et cmdV3 comptent les commandes relayées par format (le format est visible de toute
  // façon) : cmdV2 resté à 0 pendant 14 jours est l'une des conditions de la coupure de la v2.
  // refusV2 : commandes v2 refusées après la coupure.
  // -------------------------------------------------------------------------
  const stats = {
    cmd: 0,
    cmdV2: 0,
    cmdV3: 0,
    livrees: 0,
    enFile: 0,
    etats: 0,
    refusDebit: 0,
    refusForme: 0,
    refusV2: 0,
    connexions: 0,
  };
  const journal = (...t) => {
    if (!o.silencieux) console.log(new Date().toISOString(), ...t);
  };
  const erreur = (...t) => {
    if (!o.silencieux) console.error(new Date().toISOString(), "erreur :", ...t);
  };

  // -------------------------------------------------------------------------
  // État en mémoire, par id.
  // -------------------------------------------------------------------------
  /**
   * id -> { phone, ctrls: Set, lastSeen, file: [{ msg, recue }], enVol: [{ msg, recue }] }
   * file : commandes en attente, livrées dans l'ordre de réception : v2 ring et stop (les
   * FILE_PAR_TYPE dernières de chaque type) et v3 (les FILE_V3 dernières, types invisibles).
   * enVol : commandes livrées au téléphone (toutes les v3, les v2 ring et stop) mais pas encore
   * suivies d'un état ; si la connexion meurt avant, elles repartent en file (le téléphone
   * ignore un nonce déjà vu).
   */
  const canaux = new Map();
  const parId = limiteur(o.limiteId);
  const parIp = limiteur(o.limiteIp);
  /** Connexions WebSocket ouvertes (ou en cours d'ouverture) par adresse, et au total. */
  const ouvertesParIp = new Map();
  let ouvertesTotal = 0;

  function canal(id) {
    let c = canaux.get(id);
    if (!c) {
      c = { phone: null, ctrls: new Set(), lastSeen: null, file: [], enVol: [] };
      canaux.set(id, c);
    }
    return c;
  }

  /**
   * Ajoute une commande à la file, sans doublon de nonce. v3 : les FILE_V3 dernières (le relais
   * ne voit pas leur type). v2 : FILE_PAR_TYPE au plus par type.
   */
  function mettreEnFile(c, msg, recue) {
    if (c.file.some((e) => e.msg.nonce === msg.nonce)) return;
    c.file.push({ msg, recue });
    const v3 = estV3(msg);
    const garde = v3 ? FILE_V3 : FILE_PAR_TYPE;
    const memeGroupe = c.file
      .filter((e) => (v3 ? estV3(e.msg) : !estV3(e.msg) && e.msg.cmd === msg.cmd))
      .sort((a, b) => a.recue - b.recue);
    for (const e of memeGroupe.slice(0, Math.max(0, memeGroupe.length - garde))) {
      c.file.splice(c.file.indexOf(e), 1);
    }
  }

  /** Remet en file les commandes en vol (connexion perdue avant un état du téléphone). */
  function reprendreEnVol(c) {
    for (const e of c.enVol) mettreEnFile(c, e.msg, e.recue);
    c.enVol = [];
  }

  /** Commandes en attente encore valables (entrées complètes), dans l'ordre de réception. */
  function fileValide(c, maintenant = Date.now()) {
    c.file = c.file.filter((e) => maintenant - e.recue <= o.fileMs).sort((a, b) => a.recue - b.recue);
    return c.file;
  }

  /** Oublie un canal vide (ni téléphone, ni contrôleur, ni file), sauf sa dernière présence récente. */
  function oublierSiVide(id, maintenant = Date.now()) {
    const c = canaux.get(id);
    if (!c || c.phone || c.ctrls.size) return;
    const fileVide = fileValide(c, maintenant).length === 0;
    const vuRecemment = c.lastSeen && maintenant - c.lastSeen < 24 * 60 * MINUTE;
    if (fileVide && !vuRecemment) canaux.delete(id);
  }

  const presence = (c) => ({ type: "presence", online: !!c.phone, lastSeen: c.lastSeen });

  function envoyer(ws, obj) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
  }

  function diffuserPresence(c) {
    const p = presence(c);
    for (const ws of c.ctrls) envoyer(ws, p);
  }

  /**
   * Adresse du client pour les limites par IP (normalisée par cleIp : /64 en IPv6).
   * Derrière Traefik, la dernière entrée de X-Forwarded-For.
   */
  function ipDe(req) {
    if (o.faireConfianceAuProxy) {
      const xff = req.headers["x-forwarded-for"];
      if (typeof xff === "string" && xff.trim()) {
        const liste = xff.split(",").map((s) => s.trim()).filter(Boolean);
        if (liste.length) return cleIp(liste[liste.length - 1]);
      }
    }
    return cleIp(req.socket.remoteAddress);
  }

  /**
   * Traite une commande venue d'un contrôleur (WebSocket ou HTTP).
   * Renvoie { statut, corps } : 200 { delivered, queued? }, 400, 429.
   */
  function traiterCommande(id, brut, ip) {
    const msg = commandeValide(brut, { accepterV2: o.accepterV2 });
    if (!msg) {
      // Après la coupure, une commande sans champ v est l'ancien format : l'émetteur (page ou
      // app 1.6) renvoie la même intention en v3 avec un nouveau nonce.
      if (!o.accepterV2 && brut && typeof brut === "object" && !Array.isArray(brut) && brut.type === "cmd" && !estV3(brut)) {
        stats.refusV2 += 1;
        return { statut: 400, corps: { error: ERREUR_ANCIEN_FORMAT } };
      }
      stats.refusForme += 1;
      return { statut: 400, corps: { error: "invalid command" } };
    }
    // Les deux compteurs avancent à chaque tentative.
    const okIp = parIp.autorise(ip);
    const okId = parId.autorise(id);
    if (!okIp || !okId) {
      stats.refusDebit += 1;
      return { statut: 429, corps: { error: "trop de commandes, réessaie dans une minute" } };
    }
    stats.cmd += 1;
    const v3 = msg.v === VERSION_COMMANDE;
    if (v3) stats.cmdV3 += 1;
    else stats.cmdV2 += 1;
    const c = canal(id);
    // v3 : type invisible, toutes gardées. v2 : ring et stop seulement, jamais ping.
    const garder = v3 || msg.cmd === "ring" || msg.cmd === "stop";
    if (c.phone && c.phone.readyState === WebSocket.OPEN) {
      envoyer(c.phone, msg);
      stats.livrees += 1;
      if (garder) {
        c.enVol.push({ msg, recue: Date.now() });
        c.enVol = c.enVol.slice(-(2 * FILE_PAR_TYPE + FILE_V3));
      }
      return { statut: 200, corps: { delivered: true } };
    }
    if (garder) {
      // Plusieurs commandes gardées : une commande fausse ne peut pas effacer la bonne.
      mettreEnFile(c, msg, Date.now());
      stats.enFile += 1;
      return { statut: 200, corps: { delivered: false, queued: true } };
    }
    return { statut: 200, corps: { delivered: false, queued: false } };
  }

  // -------------------------------------------------------------------------
  // HTTP
  // -------------------------------------------------------------------------
  function repondreJson(res, statut, obj, entetes = {}) {
    const corps = JSON.stringify(obj);
    res.writeHead(statut, {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Content-Length": Buffer.byteLength(corps),
      ...ENTETES_SECURITE,
      ...entetes,
    });
    res.end(corps);
  }

  async function servirStatique(req, res, chemin) {
    let relatif;
    try {
      relatif = decodeURIComponent(chemin);
    } catch {
      return repondreJson(res, 400, { error: "chemin invalide" });
    }
    if (relatif.endsWith("/")) relatif += "index.html";
    const racine = o.dossierWeb;
    const fichier = path.resolve(racine, "." + path.posix.normalize("/" + relatif));
    if (!fichier.startsWith(racine + path.sep) || relatif.includes("\0")) {
      return repondreJson(res, 404, { error: "introuvable" });
    }
    const ext = path.extname(fichier).toLowerCase();
    const type = TYPES_MIME[ext];
    try {
      const infos = await stat(fichier);
      if (!infos.isFile() || !type) throw new Error("pas un fichier servi");
      // Fichiers petits : le navigateur revalide à chaque fois (ETag), une mise à jour est vue tout de suite.
      const etag = `"${infos.size.toString(16)}-${Math.floor(infos.mtimeMs).toString(16)}"`;
      const entetes = { "Cache-Control": "no-cache", ETag: etag, ...ENTETES_SECURITE };
      if (req.headers["if-none-match"] === etag) {
        res.writeHead(304, entetes);
        return res.end();
      }
      const contenu = await readFile(fichier);
      res.writeHead(200, { "Content-Type": type, "Content-Length": contenu.length, ...entetes });
      res.end(req.method === "HEAD" ? undefined : contenu);
    } catch {
      repondreJson(res, 404, { error: "introuvable" });
    }
  }

  /** GET /update/android.json : l'annonce telle que publiée, si c'est un objet JSON. */
  async function servirAnnonce(req, res) {
    if (!o.dossierReleases) return repondreJson(res, 404, { error: "pas de mise à jour publiée" });
    let annonce;
    try {
      const fichier = path.join(o.dossierReleases, "android.json");
      const infos = await stat(fichier);
      if (!infos.isFile() || infos.size > TAILLE_MAX_ANNONCE) throw new Error("annonce absente ou trop grosse");
      annonce = JSON.parse(await readFile(fichier, "utf8"));
      if (!annonce || typeof annonce !== "object" || Array.isArray(annonce)) throw new Error("annonce invalide");
    } catch (e) {
      if (e?.code !== "ENOENT") erreur("annonce", e?.message);
      return repondreJson(res, 404, { error: "pas de mise à jour publiée" });
    }
    const corps = JSON.stringify(annonce);
    res.writeHead(200, {
      "Content-Type": "application/json; charset=utf-8",
      "Content-Length": Buffer.byteLength(corps),
      "Cache-Control": CACHE_ANNONCE,
      ...ENTETES_SECURITE,
    });
    res.end(req.method === "HEAD" ? undefined : corps);
  }

  /** GET /download/<nom>.apk : un APK du dossier des versions, nom validé strictement. */
  async function servirApk(req, res, nom) {
    if (!o.dossierReleases || !nomApkValide(nom)) return repondreJson(res, 404, { error: "introuvable" });
    const racine = path.resolve(o.dossierReleases);
    const fichier = path.join(racine, nom);
    if (path.dirname(fichier) !== racine) return repondreJson(res, 404, { error: "introuvable" });
    let infos;
    try {
      infos = await stat(fichier);
      if (!infos.isFile()) throw new Error("pas un fichier");
    } catch {
      return repondreJson(res, 404, { error: "introuvable" });
    }
    const entetes = {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": infos.size,
      "Content-Disposition": `attachment; filename="${nom}"`,
      // Un nom par version : le fichier ne change jamais.
      "Cache-Control": "public, max-age=86400",
      ...ENTETES_SECURITE,
    };
    if (req.method === "HEAD") {
      res.writeHead(200, entetes);
      return res.end();
    }
    await new Promise((resolve) => {
      const flux = createReadStream(fichier);
      flux.once("open", () => {
        res.writeHead(200, entetes);
        flux.pipe(res);
      });
      flux.once("error", (e) => {
        erreur("apk", e?.message);
        if (!res.headersSent) repondreJson(res, 404, { error: "introuvable" });
        else res.destroy();
        resolve();
      });
      res.once("close", () => {
        flux.destroy();
        resolve();
      });
    });
  }

  function lireCorps(req) {
    return new Promise((resolve, reject) => {
      const morceaux = [];
      let taille = 0;
      req.on("data", (m) => {
        taille += m.length;
        // Au-delà de 4 Ko on jette sans garder, pour pouvoir répondre 413 proprement ;
        // au-delà de 64 Ko on coupe net.
        if (taille > 16 * TAILLE_MAX) {
          req.destroy();
          reject(Object.assign(new Error("trop gros"), { statut: 413 }));
          return;
        }
        if (taille <= TAILLE_MAX) morceaux.push(m);
      });
      req.on("end", () => {
        if (taille > TAILLE_MAX) reject(Object.assign(new Error("trop gros"), { statut: 413 }));
        else resolve(Buffer.concat(morceaux).toString("utf8"));
      });
      req.on("error", reject);
    });
  }

  async function routeHttp(req, res) {
    const url = new URL(req.url, "http://relais.local");
    const chemin = url.pathname;

    if (chemin === "/health" && (req.method === "GET" || req.method === "HEAD")) {
      let phones = 0;
      let ctrls = 0;
      for (const c of canaux.values()) {
        if (c.phone) phones += 1;
        ctrls += c.ctrls.size;
      }
      return repondreJson(res, 200, {
        ok: true,
        phones,
        ctrls,
        uptime: Math.round((Date.now() - debut) / 1000),
        version: VERSION,
      });
    }

    if (chemin.startsWith("/api/")) {
      // L'id passe dans un en-tête, jamais dans l'URL (journaux d'accès des proxys).
      const id = req.headers["x-sonne-id"];
      if (!estIdValide(id)) {
        return repondreJson(res, 400, { error: "id invalide : en-tête X-Sonne-Id de 32 caractères hex attendu" });
      }
      const ip = ipDe(req);

      if (chemin === "/api/presence" && req.method === "GET") {
        if (!parIp.autorise(ip)) {
          stats.refusDebit += 1;
          return repondreJson(res, 429, { error: "trop de requêtes" });
        }
        const c = canaux.get(id);
        return repondreJson(res, 200, { online: !!c?.phone, lastSeen: c?.lastSeen ?? null });
      }

      if (chemin === "/api/cmd" && req.method === "POST") {
        let brut;
        try {
          brut = JSON.parse(await lireCorps(req));
        } catch (e) {
          if (e.statut === 413) return repondreJson(res, 413, { error: "message trop gros (4 Ko max)" });
          stats.refusForme += 1;
          return repondreJson(res, 400, { error: "JSON invalide" });
        }
        const { statut, corps } = traiterCommande(id, brut, ip);
        return repondreJson(res, statut, corps);
      }

      return repondreJson(res, 405, { error: "méthode ou route inconnue" });
    }

    if (chemin.startsWith("/ws/")) {
      return repondreJson(res, 426, { error: "WebSocket attendu" });
    }

    if (chemin === "/update/android.json" || chemin.startsWith("/download/")) {
      if (req.method !== "GET" && req.method !== "HEAD") return repondreJson(res, 405, { error: "méthode non autorisée" });
      if (chemin === "/update/android.json") return servirAnnonce(req, res);
      return servirApk(req, res, chemin.slice("/download/".length));
    }

    if (req.method === "GET" || req.method === "HEAD") return servirStatique(req, res, chemin);
    return repondreJson(res, 405, { error: "méthode non autorisée" });
  }

  const serveur = http.createServer((req, res) => {
    routeHttp(req, res).catch((e) => {
      erreur("http", e?.message);
      if (!res.headersSent) repondreJson(res, 500, { error: "erreur interne" });
      else res.destroy();
    });
  });
  serveur.headersTimeout = 15_000;
  serveur.requestTimeout = 30_000;

  // -------------------------------------------------------------------------
  // WebSocket
  // -------------------------------------------------------------------------
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: TAILLE_MAX,
    perMessageDeflate: false,
    // Le client a été vérifié avant l'ouverture : on lui renvoie le sous-protocole commun.
    handleProtocols: (protocoles) => (protocoles.has(SOUS_PROTOCOLE) ? SOUS_PROTOCOLE : false),
  });

  function refuserUpgrade(socket, statut, texte) {
    const corps = JSON.stringify({ error: texte });
    socket.end(
      `HTTP/1.1 ${statut} ${http.STATUS_CODES[statut]}\r\n` +
        "Content-Type: application/json; charset=utf-8\r\n" +
        `Content-Length: ${Buffer.byteLength(corps)}\r\n` +
        "Connection: close\r\n\r\n" +
        corps,
    );
  }

  serveur.on("upgrade", (req, socket, tete) => {
    socket.on("error", () => {});
    let url;
    try {
      url = new URL(req.url, "http://relais.local");
    } catch {
      return refuserUpgrade(socket, 400, "url invalide");
    }
    const role = url.pathname === "/ws/phone" ? "phone" : url.pathname === "/ws/ctrl" ? "ctrl" : null;
    if (!role) return refuserUpgrade(socket, 404, "route inconnue");
    // Identification avant tout : un téléphone doit prouver qu'il détient S (P), sinon
    // il ne remplace pas le vrai et ne reçoit pas sa file.
    const { id, statut, erreur: refus } = identifier(req, role);
    if (!id) {
      stats.refusForme += 1;
      return refuserUpgrade(socket, statut, refus);
    }
    const ip = ipDe(req);
    if (!parIp.autorise(ip)) {
      stats.refusDebit += 1;
      return refuserUpgrade(socket, 429, "trop de connexions");
    }
    // Connexions ouvertes en même temps : par adresse, au total, et contrôleurs par id.
    if (ouvertesTotal >= o.maxConnexions) {
      stats.refusDebit += 1;
      return refuserUpgrade(socket, 503, "relais saturé, réessaie plus tard");
    }
    if ((ouvertesParIp.get(ip) ?? 0) >= o.maxConnexionsParIp) {
      stats.refusDebit += 1;
      return refuserUpgrade(socket, 429, "trop de connexions ouvertes depuis cette adresse");
    }
    if (role === "ctrl" && (canaux.get(id)?.ctrls.size ?? 0) >= o.maxCtrlsParId) {
      stats.refusDebit += 1;
      return refuserUpgrade(socket, 429, "trop de contrôleurs pour ce téléphone");
    }
    // Compté dès maintenant, décompté à la fermeture du socket TCP (réussite ou échec).
    ouvertesTotal += 1;
    ouvertesParIp.set(ip, (ouvertesParIp.get(ip) ?? 0) + 1);
    socket.once("close", () => {
      ouvertesTotal -= 1;
      const n = (ouvertesParIp.get(ip) ?? 1) - 1;
      if (n > 0) ouvertesParIp.set(ip, n);
      else ouvertesParIp.delete(ip);
    });
    wss.handleUpgrade(req, socket, tete, (ws) => {
      stats.connexions += 1;
      ws.sonne = { role, id, ip, dernierSigne: Date.now(), dernierPing: Date.now() };
      if (role === "phone") accueillirTelephone(ws, id);
      else accueillirControleur(ws, id);
    });
  });

  function lireJson(donnees, binaire) {
    if (binaire) return null;
    try {
      return JSON.parse(donnees.toString("utf8"));
    } catch {
      return null;
    }
  }

  function accueillirTelephone(ws, id) {
    const c = canal(id);
    if (c.phone && c.phone !== ws) {
      // Un seul téléphone par id : le plus récent gagne. Il a prouvé qu'il détient S.
      const ancien = c.phone;
      ancien.sonne.remplace = true;
      ancien.close(4000, "remplacé par une nouvelle connexion");
    }
    c.phone = ws;
    c.lastSeen = Date.now();
    // Les commandes livrées à l'ancienne connexion sans état en retour sont relivrées.
    reprendreEnVol(c);
    const aLivrer = fileValide(c);
    c.file = [];
    c.enVol = aLivrer;
    envoyer(ws, { type: "welcome", queued: aLivrer.map((e) => e.msg) });
    diffuserPresence(c);

    ws.on("pong", () => {
      ws.sonne.dernierSigne = Date.now();
      if (c.phone === ws) c.lastSeen = ws.sonne.dernierSigne;
    });
    ws.on("message", (donnees, binaire) => {
      ws.sonne.dernierSigne = Date.now();
      if (c.phone === ws) c.lastSeen = ws.sonne.dernierSigne;
      const brut = lireJson(donnees, binaire);
      // Battement applicatif : le téléphone vérifie que la connexion vit encore.
      if (brut && brut.type === "hb") {
        envoyer(ws, { type: "hb" });
        return;
      }
      const etat = etatValide(brut);
      if (!etat) {
        stats.refusForme += 1;
        return;
      }
      stats.etats += 1;
      // Un état suit chaque commande traitée : celles en vol sont arrivées.
      if (c.phone === ws) c.enVol = [];
      for (const ctrl of c.ctrls) envoyer(ctrl, etat);
    });
    ws.on("close", () => {
      if (c.phone === ws) {
        c.phone = null;
        c.lastSeen = Date.now();
        reprendreEnVol(c);
        diffuserPresence(c);
      }
      oublierSiVide(id);
    });
    ws.on("error", (e) => erreur("ws téléphone", court(id), e?.message));
  }

  function accueillirControleur(ws, id) {
    const c = canal(id);
    if (c.ctrls.size >= o.maxCtrlsParId) {
      ws.close(1008, "trop de contrôleurs pour ce téléphone");
      return;
    }
    c.ctrls.add(ws);
    envoyer(ws, presence(c));

    ws.on("pong", () => {
      ws.sonne.dernierSigne = Date.now();
    });
    ws.on("message", (donnees, binaire) => {
      ws.sonne.dernierSigne = Date.now();
      const brut = lireJson(donnees, binaire);
      const { statut, corps } = traiterCommande(id, brut, ws.sonne.ip);
      const nonce = typeof brut?.nonce === "string" && RE_NONCE_RETOUR.test(brut.nonce) ? brut.nonce : null;
      // Retour au contrôleur seulement (le téléphone ne voit jamais ce message).
      if (statut === 200) envoyer(ws, { type: "relayed", nonce, ...corps });
      else envoyer(ws, { type: "error", nonce, status: statut, error: corps.error });
    });
    ws.on("close", () => {
      c.ctrls.delete(ws);
      oublierSiVide(id);
    });
    ws.on("error", (e) => erreur("ws contrôleur", court(id), e?.message));
  }

  // Ping par rôle : contrôleurs toutes les 25 s (coupés après 60 s de silence),
  // téléphone toutes les 4 min (coupé après 10 min) pour ménager sa batterie.
  const pas = Math.max(10, Math.min(o.pingMs, o.pingTelephoneMs, 5_000));
  const minuteurPing = setInterval(() => {
    const maintenant = Date.now();
    for (const ws of wss.clients) {
      const tel = ws.sonne.role === "phone";
      const mort = tel ? o.delaiMortTelephoneMs : o.delaiMortMs;
      const periode = tel ? o.pingTelephoneMs : o.pingMs;
      if (maintenant - ws.sonne.dernierSigne > mort) {
        ws.terminate();
        continue;
      }
      if (maintenant - ws.sonne.dernierPing < periode) continue;
      ws.sonne.dernierPing = maintenant;
      try {
        ws.ping();
      } catch {
        ws.terminate();
      }
    }
  }, pas);
  minuteurPing.unref();

  // Ménage et compteurs toutes les minutes.
  let derniersStats = "";
  let minutes = 0;
  const minuteurMenage = setInterval(() => {
    const maintenant = Date.now();
    parId.nettoyer(maintenant);
    parIp.nettoyer(maintenant);
    for (const id of [...canaux.keys()]) oublierSiVide(id, maintenant);
    minutes += 1;
    if (minutes % 15 === 0) {
      const t = JSON.stringify(stats);
      if (t !== derniersStats) {
        derniersStats = t;
        journal("compteurs", t, "canaux", canaux.size, "connexions ouvertes", wss.clients.size);
      }
    }
  }, MINUTE);
  minuteurMenage.unref();

  let arrete = null;
  function arreter() {
    if (arrete) return arrete;
    arrete = new Promise((resolve) => {
      clearInterval(minuteurPing);
      clearInterval(minuteurMenage);
      for (const ws of wss.clients) ws.close(1001, "relais arrêté");
      // Laisse une seconde aux fermetures propres, puis coupe ce qui reste.
      const dernierDelai = setTimeout(() => {
        for (const ws of wss.clients) ws.terminate();
        serveur.closeAllConnections?.();
      }, 1000);
      dernierDelai.unref();
      wss.close();
      serveur.close(() => {
        clearTimeout(dernierDelai);
        resolve();
      });
      serveur.closeIdleConnections?.();
    });
    return arrete;
  }

  return new Promise((resolve, reject) => {
    serveur.once("error", reject);
    serveur.listen(o.port, o.hote, () => {
      serveur.off("error", reject);
      serveur.on("error", (e) => erreur("serveur", e?.message));
      const port = serveur.address().port;
      journal(
        `Sonne relais ${VERSION} (protocole v2, commandes v3${o.accepterV2 ? " et v2" : " seulement"}) à l'écoute ` +
          `sur le port ${port}, file d'attente ${o.fileMs / MINUTE} min, ping téléphone ${o.pingTelephoneMs / 1000} s`,
      );
      resolve({ serveur, port, arreter, stats });
    });
  });
}

// ---------------------------------------------------------------------------
// Lancement direct : node server.js
// ---------------------------------------------------------------------------
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number.parseInt(process.env.PORT ?? "7700", 10);
  const fileMin = Number.parseFloat(process.env.SONNE_QUEUE_MIN ?? "15");
  const secondes = (nom, defaut) => {
    const v = Number.parseFloat(process.env[nom] ?? "");
    return (Number.isFinite(v) && v > 0 ? v : defaut) * 1000;
  };
  const relais = await demarrer({
    port: Number.isFinite(port) ? port : 7700,
    // 15 min au plus : au-delà, le téléphone refuse la commande (fenêtre de fraîcheur).
    fileMs: Math.min(15, Number.isFinite(fileMin) && fileMin > 0 ? fileMin : 15) * MINUTE,
    pingTelephoneMs: secondes("SONNE_PHONE_PING_S", 240),
    delaiMortTelephoneMs: secondes("SONNE_PHONE_DEAD_S", 600),
    faireConfianceAuProxy: ["1", "true", "oui"].includes(String(process.env.SONNE_TRUST_PROXY ?? "").toLowerCase()),
    ...(process.env.SONNE_WEB_DIR ? { dossierWeb: path.resolve(process.env.SONNE_WEB_DIR) } : {}),
    ...(process.env.SONNE_RELEASES_DIR ? { dossierReleases: path.resolve(process.env.SONNE_RELEASES_DIR) } : {}),
    // Transition de la 1.6 : v2 encore accepté par défaut. Relais 1.7 : défaut à "0".
    accepterV2: !["0", "false", "non", "no"].includes(String(process.env.SONNE_CMD_V2 ?? "1").trim().toLowerCase()),
  });
  const fin = async (signal) => {
    console.log(new Date().toISOString(), `${signal} reçu, arrêt propre`);
    await relais.arreter();
    process.exit(0);
  };
  process.once("SIGTERM", () => fin("SIGTERM"));
  process.once("SIGINT", () => fin("SIGINT"));
}
