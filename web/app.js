// Page de commande de Sonne (voir docs/PROTOCOL.md, section « Web page », protocole v2,
// commandes chiffrées v3). Sans framework. Les clés restent dans ce navigateur (IndexedDB, clés
// WebCrypto non exportables : le secret S lui-même n'est pas gardé) ; le relais ne reçoit que
// l'id et des messages chiffrés de taille fixe (commandes de 172 octets, états de 508).
//
// Transition (page 1.6) : une commande part chiffrée (v3) vers un téléphone qui a annoncé
// "cmdv":3 dans un état frais et authentifié, signée (v2) sinon, c'est-à-dire vers une app en
// 1.5 ou avant, ou un téléphone dont cette page n'a encore vu aucun état. Le drapeau est gardé
// pour toujours. Si le relais refuse la v2 (400, relais déjà coupé), la même intention repart en
// v3 avec un nouveau nonce ; jamais de retour à la v2 après un refus de la v3.
// Coupure (page 1.7) : COUPURE_V2 passe à true, tout part en v3.

import {
  preparer,
  completerCles,
  chiffrerAvec,
  texteCommande,
  accepteV3,
  formatCommande,
  signerAvec,
  dechiffrerAvec,
  lireSaisie,
  secretDuFragment,
  octetsVersBase64url,
  base64urlVersOctets,
  deriveId,
} from "./crypto.js";

// Note : history.replaceState retire le fragment de la barre d'adresse, mais la visite
// d'origine (avec #s=…) peut rester dans l'historique du navigateur et suivre sa
// synchronisation. D'où le conseil, dans l'app et le README, de préférer les 12 mots ou
// le lien collé dans le formulaire sur un navigateur synchronisé.

// ---------------------------------------------------------------------------
// 1. Le fragment d'abord : lu puis effacé avant toute autre chose.
// ---------------------------------------------------------------------------

let fragmentInitial = "";
if (/(^#|&)s=/.test(location.hash)) {
  fragmentInitial = location.hash;
  history.replaceState(null, "", location.pathname + location.search);
}

// ---------------------------------------------------------------------------
// Stockage local (peut être indisponible : navigation privée, stockage bloqué)
// ---------------------------------------------------------------------------

const CLE_TELEPHONES = "sonne.telephones.v1";
const CLE_ACTIF = "sonne.actif";
const CLE_JOURNAL = "sonne.journal.";
const TAILLE_JOURNAL = 20;
const DELAI_ACCUSE_MS = 12_000;
/** Un état plus vieux que ça (horloge du téléphone) est ignoré : il peut avoir été rejoué par le relais. */
const AGE_MAX_ETAT_MS = 20 * 60_000;
/** Au-delà, la pastille propose de rajouter le téléphone (clé ou relais changés dans l'app). */
const SILENCE_SUSPECT_MS = 24 * 60 * 60_000;
/** Téléphones qui ont annoncé les commandes chiffrées : { id: true }, jamais remis à faux. */
const CLE_CMDV3 = "sonne.cmdv3";
/**
 * Coupure de l'ancien format (docs/PROTOCOL.md, « Transition, then cut-off ») : false pendant la
 * transition (page 1.6), true à l'étape 3 (page 1.7), où tout part en v3 sans regarder cmdv.
 */
const COUPURE_V2 = false;

function lire(cle, defaut) {
  try {
    const v = localStorage.getItem(cle);
    return v === null ? defaut : JSON.parse(v);
  } catch {
    return defaut;
  }
}

function ecrire(cle, valeur) {
  try {
    if (valeur === undefined) localStorage.removeItem(cle);
    else localStorage.setItem(cle, JSON.stringify(valeur));
  } catch {
    // Stockage indisponible : la page marche quand même, mais oubliera tout au rechargement.
  }
}

/**
 * [{ id, nom, nomAuto, prefixeNom, ajoute, s? }]
 * s (S en base64url) n'existe que si IndexedDB est indisponible, en secours.
 */
let telephones = lire(CLE_TELEPHONES, []).filter(
  (t) => t && typeof t.id === "string" && typeof t.nom === "string",
);
let actifId = lire(CLE_ACTIF, null);

const sauverTelephones = () => ecrire(CLE_TELEPHONES, telephones);

// Drapeau cmdv3 par téléphone. Clé à part de la liste des téléphones : une ancienne version de la
// page restée ouverte dans un autre onglet réécrit cette liste sans connaître le drapeau. Relu à
// chaque fois (autres onglets) ; une copie en mémoire sert quand le stockage est indisponible.
const cmdv3Memoire = new Set();

function drapeauxCmdV3() {
  const d = lire(CLE_CMDV3, {});
  return d && typeof d === "object" && !Array.isArray(d) ? d : {};
}

const aCmdV3 = (id) => cmdv3Memoire.has(id) || drapeauxCmdV3()[id] === true;

/** Retient qu'un téléphone accepte les commandes v3. Renvoie true la première fois. */
function retenirCmdV3(id) {
  const deja = aCmdV3(id);
  cmdv3Memoire.add(id);
  const d = drapeauxCmdV3();
  if (d[id] !== true) {
    d[id] = true;
    ecrire(CLE_CMDV3, d);
  }
  return !deja;
}

/** Oublie le drapeau d'un téléphone retiré de ce navigateur (rien ne doit rester de lui). */
function oublierCmdV3(id) {
  cmdv3Memoire.delete(id);
  const d = drapeauxCmdV3();
  if (id in d) {
    delete d[id];
    ecrire(CLE_CMDV3, Object.keys(d).length ? d : undefined);
  }
}

// ---------------------------------------------------------------------------
// Clés dans IndexedDB : { id, cleSig, cleEnc, cleCmd }, des CryptoKey non exportables.
// Un script qui lirait le stockage pourrait s'en servir ici, mais pas emporter S.
// Les entrées rangées avant la 1.6 n'ont pas cleCmd : elle est dérivée de cleSig au chargement
// (completerCles), puis rangée avec les autres.
// ---------------------------------------------------------------------------

const BASE = "sonne";
const MAGASIN = "cles";
let basePromise = null;

function ouvrirBase() {
  if (!basePromise) {
    basePromise = new Promise((resolve, reject) => {
      if (!globalThis.indexedDB) return reject(new Error("IndexedDB unavailable"));
      const req = indexedDB.open(BASE, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(MAGASIN, { keyPath: "id" });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error("IndexedDB blocked"));
    }).catch((e) => {
      basePromise = null;
      throw e;
    });
  }
  return basePromise;
}

async function operation(mode, action) {
  const base = await ouvrirBase();
  return new Promise((resolve, reject) => {
    const tx = base.transaction(MAGASIN, mode);
    const req = action(tx.objectStore(MAGASIN));
    tx.oncomplete = () => resolve(req.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error("Transaction aborted"));
  });
}

const rangerCles = (cles) =>
  operation("readwrite", (m) => m.put({ id: cles.id, cleSig: cles.cleSig, cleEnc: cles.cleEnc, cleCmd: cles.cleCmd }));
const lireCles = (id) => operation("readonly", (m) => m.get(id));
const oublierCles = (id) => operation("readwrite", (m) => m.delete(id));

/**
 * Migration des entrées qui gardaient S en clair : clés préparées rangées dans IndexedDB,
 * puis S effacé. L'id est recalculé au passage (le protocole v2 dérive l'id de la preuve P).
 */
async function migrerSecrets() {
  let change = false;
  for (const t of telephones) {
    if (typeof t.s !== "string") continue;
    let cles;
    try {
      cles = await preparer(base64urlVersOctets(t.s));
    } catch {
      continue;
    }
    if (cles.id !== t.id) {
      const journalAncien = lire(CLE_JOURNAL + t.id, null);
      if (journalAncien) ecrire(CLE_JOURNAL + cles.id, journalAncien);
      ecrire(CLE_JOURNAL + t.id, undefined);
      if (actifId === t.id) actifId = cles.id;
      t.id = cles.id;
      change = true;
    }
    try {
      await rangerCles(cles);
      delete t.s;
      change = true;
    } catch {
      // IndexedDB indisponible : S reste dans localStorage, comme avant.
    }
  }
  if (change) {
    const vus = new Set();
    telephones = telephones.filter((t) => !vus.has(t.id) && vus.add(t.id));
    sauverTelephones();
    ecrire(CLE_ACTIF, actifId ?? undefined);
  }
}

// ---------------------------------------------------------------------------
// Éléments
// ---------------------------------------------------------------------------

const $ = (id) => document.getElementById(id);
const el = {
  vide: $("vide"),
  tableau: $("tableau"),
  gestion: $("gestion"),
  blocChoix: $("bloc-choix"),
  choix: $("choix-telephone"),
  nom: $("nom-telephone"),
  pastille: $("pastille"),
  pastilleTexte: $("pastille-texte"),
  batterie: $("batterie"),
  batterieNiveau: $("batterie-niveau"),
  sonnerie: $("sonnerie"),
  dernierEtat: $("dernier-etat"),
  modele: $("modele"),
  indice: $("indice"),
  sonner: $("bouton-sonner"),
  arreter: $("bouton-arreter"),
  accuse: $("accuse"),
  journal: $("journal"),
  journalVide: $("journal-vide"),
  liste: $("liste-telephones"),
  form: $("form-ajout"),
  saisie: $("saisie"),
  nomSaisi: $("nom"),
  erreurAjout: $("erreur-ajout"),
  placeVide: $("place-ajout-vide"),
  placeGestion: $("place-ajout-gestion"),
};

// ---------------------------------------------------------------------------
// Temps
// ---------------------------------------------------------------------------

const heure = (ms) => new Date(ms).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });

function ilYA(ms) {
  if (!ms) return "never";
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 10) return "just now";
  if (s < 60) return `${s} s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} days ago`;
}

const NOMS_CMD = { ring: "Ring", stop: "Stop", ping: "Status request" };

// ---------------------------------------------------------------------------
// Journal (20 derniers événements par téléphone)
// ---------------------------------------------------------------------------

let journal = [];

function chargerJournal(id) {
  journal = lire(CLE_JOURNAL + id, []).filter((e) => e && typeof e.texte === "string").slice(0, TAILLE_JOURNAL);
  afficherJournal();
}

function noter(texte, genre = "info") {
  if (!actifId) return;
  journal.unshift({ t: Date.now(), texte, genre });
  journal = journal.slice(0, TAILLE_JOURNAL);
  ecrire(CLE_JOURNAL + actifId, journal);
  afficherJournal();
}

function afficherJournal() {
  el.journal.replaceChildren(
    ...journal.map((e) => {
      const li = document.createElement("li");
      li.dataset.genre = e.genre;
      const t = document.createElement("time");
      t.dateTime = new Date(e.t).toISOString();
      t.textContent = heure(e.t);
      const s = document.createElement("span");
      s.textContent = e.texte;
      li.append(t, s);
      return li;
    }),
  );
  el.journalVide.hidden = journal.length > 0;
}

function accuse(texte, genre = "info") {
  el.accuse.textContent = texte;
  el.accuse.dataset.genre = genre;
}

// ---------------------------------------------------------------------------
// Connexion au relais pour le téléphone actif
// ---------------------------------------------------------------------------

/** État courant de l'écran, remis à zéro à chaque changement de téléphone. */
let vue = null;

function nouvelleVue(tel) {
  return {
    tel,
    cles: null,
    ws: null,
    relaisOuvert: false,
    enLigne: null,
    lastSeen: null,
    etat: null,
    recuA: null,
    attente: null, // { nonce, cmd, format, silencieux, envoyeA, minuteur }
    relaisRefuseV2: false, // le relais a répondu 400 à une v2 : tout part en v3 sur cette vue
    dernierTs: 0, // plus grand etat.ts reçu : les états rejoués ou dans le désordre sont ignorés
    illisibleNote: false,
    delai: 1000,
    minuteurReconnexion: null,
    ferme: false,
  };
}

/** Adresse du WebSocket des contrôleurs. L'id ne va jamais dans l'URL (journaux d'accès des proxys). */
function urlRelais() {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${location.host}/ws/ctrl`;
}

function connecter(v) {
  if (v.ferme) return;
  let ws;
  try {
    // L'id passe dans l'en-tête Sec-WebSocket-Protocol.
    ws = new WebSocket(urlRelais(), ["sonne.v2", `sonne.id.${v.tel.id}`]);
  } catch {
    planifierReconnexion(v);
    return;
  }
  v.ws = ws;
  afficherPastille();

  ws.addEventListener("open", () => {
    if (v !== vue) return ws.close();
    v.relaisOuvert = true;
    v.delai = 1000;
    afficherBoutons();
  });

  ws.addEventListener("message", (e) => {
    if (v !== vue) return;
    let msg;
    try {
      msg = JSON.parse(e.data);
    } catch {
      return;
    }
    if (msg.type === "presence") return recevoirPresence(v, msg);
    if (msg.type === "state") return recevoirEtat(v, msg);
    if (msg.type === "relayed") return recevoirRelaye(v, msg);
    if (msg.type === "error") return recevoirErreur(v, msg);
  });

  ws.addEventListener("close", () => {
    if (v.ws === ws) {
      v.ws = null;
      v.relaisOuvert = false;
    }
    if (v !== vue || v.ferme) return;
    afficherPastille();
    afficherBoutons();
    planifierReconnexion(v);
  });
}

function planifierReconnexion(v) {
  if (v.ferme || v.minuteurReconnexion) return;
  const delai = v.delai + Math.random() * 500;
  v.delai = Math.min(v.delai * 2, 30_000);
  v.minuteurReconnexion = setTimeout(() => {
    v.minuteurReconnexion = null;
    connecter(v);
  }, delai);
}

function fermerVue(v) {
  if (!v) return;
  v.ferme = true;
  clearTimeout(v.minuteurReconnexion);
  if (v.attente) clearTimeout(v.attente.minuteur);
  try {
    v.ws?.close();
  } catch {
    // déjà fermé
  }
}

function recevoirPresence(v, msg) {
  const avant = v.enLigne;
  v.enLigne = !!msg.online;
  v.lastSeen = typeof msg.lastSeen === "number" ? msg.lastSeen : null;
  if (avant !== null && avant !== v.enLigne) {
    noter(v.enLigne ? "Phone online" : "Phone offline", v.enLigne ? "ok" : "info");
  }
  // Dès que le téléphone est joignable, on lui demande un état frais. Jamais quand il est hors
  // ligne : en v3 le relais mettrait cette vérification en file, comme une ring.
  if (v.enLigne && avant !== true) envoyer("ping", { silencieux: true });
  afficherPastille();
  afficherBoutons();
}

async function recevoirEtat(v, msg) {
  let etat;
  try {
    etat = await dechiffrerAvec(v.cles, msg);
  } catch {
    if (v === vue && !v.illisibleNote) {
      v.illisibleNote = true;
      noter("Unreadable status from the relay, ignored", "alerte");
    }
    return;
  }
  if (v !== vue) return;
  // Fraîcheur : le relais pourrait rejouer d'anciens états (« Sonne » alors que c'est fini).
  if (!Number.isFinite(etat?.ts) || etat.ts <= v.dernierTs || etat.ts < Date.now() - AGE_MAX_ETAT_MS) return;
  v.dernierTs = etat.ts;
  // Capacité : seulement dans un état frais et authentifié, retenue pour toujours.
  if (accepteV3(etat) && retenirCmdV3(v.tel.id)) noter("Commands to this phone are now encrypted", "ok");
  const avant = v.etat;
  v.etat = etat;
  v.recuA = Date.now();
  if (!v.enLigne) {
    v.enLigne = true;
    afficherPastille();
  }

  // Nom automatique : le modèle du téléphone, tant que l'utilisateur n'a rien choisi.
  // Ajouté par un lien : préfixé par « New: », un lien piégé ne peut pas se donner le nom du tien.
  const propose =
    typeof etat.model === "string" && etat.model.trim() ? `${v.tel.prefixeNom ?? ""}${etat.model.trim()}`.slice(0, 40) : "";
  if (v.tel.nomAuto && propose && v.tel.nom !== propose) {
    v.tel.nom = propose;
    sauverTelephones();
    afficherListe();
    el.nom.textContent = v.tel.nom;
  }

  // Accusé de réception de la commande en attente.
  if (v.attente && etat.ack === v.attente.nonce) {
    const { cmd, envoyeA } = v.attente;
    clearTimeout(v.attente.minuteur);
    v.attente = null;
    const ms = Date.now() - envoyeA;
    const duree = ms < 1000 ? `${ms} ms` : `${(ms / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 })} s`;
    if (cmd === "ring") {
      const texte = etat.ringing ? `Phone is ringing (confirmed in ${duree})` : `Command received in ${duree}`;
      accuse(texte, "ok");
      noter(etat.ringing ? "Confirmed: phone is ringing" : "Confirmed: command received", "ok");
    } else if (cmd === "stop") {
      accuse(`Ringing stopped (confirmed in ${duree})`, "ok");
      noter("Confirmed: ringing stopped", "ok");
    }
  } else if (avant && avant.ringing !== etat.ringing) {
    noter(etat.ringing ? "Phone is ringing" : "Ringing stopped", etat.ringing ? "ok" : "info");
  }
  afficherEtat();
}

function recevoirRelaye(v, msg) {
  // Nonce de 24 hex (v3) ou 32 (v2) : on compare au nonce envoyé.
  if (!v.attente || msg.nonce !== v.attente.nonce) return;
  const { cmd, silencieux } = v.attente;
  if (msg.delivered) {
    if (!silencieux) accuse("Delivered, waiting for the phone to confirm…");
    return;
  }
  clearTimeout(v.attente.minuteur);
  v.attente = null;
  // En v3, le relais met aussi en file une vérification : rien à dire pour celle de fond.
  if (msg.queued && !silencieux) {
    accuse("Phone offline: the command waits on the relay for a few minutes and is delivered when it reconnects", "alerte");
    noter(`${NOMS_CMD[cmd]}: queued on the relay`, "alerte");
  }
}

function recevoirErreur(v, msg) {
  if (v.attente && msg.nonce === v.attente.nonce) {
    const attente = v.attente;
    clearTimeout(attente.minuteur);
    v.attente = null;
    // Relais déjà coupé : la v2 refusée n'a pas été livrée. Même intention en v3, nouveau nonce.
    // Jamais l'inverse : une v3 refusée n'est pas renvoyée en v2.
    if (msg.status === 400 && attente.format === 2) {
      v.relaisRefuseV2 = true;
      envoyer(attente.cmd, { silencieux: attente.silencieux, renvoi: true });
      return;
    }
    if (attente.silencieux) return;
  }
  const texte = msg.status === 429 ? "Too many commands, try again in a minute" : `Refused by the relay: ${msg.error ?? "error"}`;
  accuse(texte, "alerte");
  noter(texte, "alerte");
}

/**
 * Chiffre (v3) ou signe (v2, transition) une commande et l'envoie au téléphone actif.
 * renvoi : la même intention, renvoyée en v3 après un refus de la v2 par le relais.
 */
async function envoyer(cmd, { silencieux = false, renvoi = false } = {}) {
  const v = vue;
  if (!v || !v.cles) return;
  if (!v.relaisOuvert || v.ws?.readyState !== WebSocket.OPEN) {
    if (!silencieux) accuse("Relay unreachable, trying again…", "alerte");
    return;
  }
  // Un ping de fond ne doit pas écraser une vraie commande en attente.
  if (silencieux && v.attente && !v.attente.silencieux) return;
  const format = formatCommande({ cmdv3: aCmdV3(v.tel.id), relaisRefuseV2: v.relaisRefuseV2, coupure: COUPURE_V2 });
  let msg;
  let texte;
  try {
    if (format === 3) {
      msg = await chiffrerAvec(v.cles, cmd, Date.now());
      texte = texteCommande(msg); // toujours 172 octets
    } else {
      msg = await signerAvec(v.cles, cmd, Date.now());
      texte = JSON.stringify(msg);
    }
  } catch (e) {
    if (v === vue && !silencieux) accuse(`Could not prepare the command: ${e.message}`, "alerte");
    return;
  }
  if (v !== vue) return;
  if (!v.ws || v.ws.readyState !== WebSocket.OPEN) {
    if (!silencieux) accuse("Relay unreachable, trying again…", "alerte");
    return;
  }
  if (v.attente) clearTimeout(v.attente.minuteur);
  v.attente = {
    nonce: msg.nonce,
    cmd,
    format,
    silencieux,
    envoyeA: Date.now(),
    minuteur: setTimeout(() => {
      if (v.attente?.nonce !== msg.nonce) return;
      v.attente = null;
      if (!silencieux) {
        accuse("No confirmation from the phone yet", "alerte");
        noter(`${NOMS_CMD[cmd]}: no confirmation`, "alerte");
      }
    }, DELAI_ACCUSE_MS),
  };
  v.ws.send(texte);
  if (!silencieux) {
    accuse("Sending…");
    if (!renvoi) noter(`${NOMS_CMD[cmd]}: command sent`);
  }
}

// ---------------------------------------------------------------------------
// Affichage
// ---------------------------------------------------------------------------

function afficherPastille() {
  const v = vue;
  if (!v) return;
  let etat;
  let texte;
  if (!v.relaisOuvert) {
    const enCours = v.ws && v.ws.readyState === WebSocket.CONNECTING;
    etat = enCours && v.delai <= 1000 ? "connexion" : "relais";
    texte = etat === "connexion" ? "Connecting…" : "Relay unreachable";
  } else if (v.enLigne) {
    etat = "en-ligne";
    texte = "Online";
  } else if (v.enLigne === false) {
    etat = "hors-ligne";
    texte = v.lastSeen ? `Offline, seen ${ilYA(v.lastSeen)}` : "Never seen by the relay";
  } else {
    etat = "connexion";
    texte = "Connecting…";
  }
  el.pastille.dataset.etat = etat;
  el.pastilleTexte.textContent = texte;
  // Jamais vu, ou silencieux depuis plus d'un jour : peut-être une nouvelle clé ou un autre relais.
  el.indice.hidden = !(
    v.relaisOuvert &&
    v.enLigne === false &&
    (!v.lastSeen || Date.now() - v.lastSeen > SILENCE_SUSPECT_MS)
  );
}

function afficherEtat() {
  const v = vue;
  const e = v?.etat;
  if (!e) {
    el.batterie.textContent = "?";
    el.batterieNiveau.style.width = "0";
    el.sonnerie.textContent = "?";
    el.dernierEtat.textContent = "none";
    el.modele.textContent = "";
    el.sonner.dataset.sonne = "non";
    return;
  }
  const niveau = Number.isFinite(e.battery) ? Math.max(0, Math.min(100, Math.round(e.battery))) : null;
  el.batterie.textContent = niveau === null ? "?" : `${niveau}%${e.charging ? ", charging" : ""}`;
  el.batterie.title = e.charging ? "Charging" : "";
  el.batterieNiveau.style.width = niveau === null ? "0" : `calc(${niveau}% - 2px)`;
  el.batterie.parentElement.classList.toggle("batterie-faible", niveau !== null && niveau <= 15 && !e.charging);
  el.sonnerie.textContent = e.ringing ? "Yes" : "No";
  el.sonner.dataset.sonne = e.ringing ? "oui" : "non";
  el.dernierEtat.textContent = ilYA(v.recuA);
  const details = [e.model, e.app ? `app ${e.app}` : null].filter((x) => typeof x === "string" && x);
  el.modele.textContent = details.join(" · ");
}

function afficherBoutons() {
  const pret = !!(vue && vue.cles && vue.relaisOuvert);
  el.sonner.disabled = !pret;
  el.arreter.disabled = !pret;
}

function afficherListe() {
  // Sélecteur du haut
  el.choix.replaceChildren(
    ...telephones.map((t) => {
      const o = document.createElement("option");
      o.value = t.id;
      o.textContent = t.nom;
      o.selected = t.id === actifId;
      return o;
    }),
  );
  el.blocChoix.hidden = telephones.length < 2;

  // Liste de gestion : renommer, supprimer
  el.liste.replaceChildren(
    ...telephones.map((t) => {
      const li = document.createElement("li");
      const champ = document.createElement("input");
      champ.type = "text";
      champ.value = t.nom;
      champ.maxLength = 40;
      champ.setAttribute("aria-label", `Name of ${t.nom}`);
      champ.addEventListener("change", () => {
        const nom = champ.value.trim();
        if (!nom) {
          champ.value = t.nom;
          return;
        }
        t.nom = nom;
        t.nomAuto = false;
        sauverTelephones();
        afficherListe();
        if (t.id === actifId) el.nom.textContent = nom;
      });
      const suppr = document.createElement("button");
      suppr.type = "button";
      suppr.className = "supprimer";
      suppr.textContent = "Remove";
      suppr.addEventListener("click", () => supprimer(t));
      const id = document.createElement("span");
      id.className = "id";
      id.textContent = `id ${t.id.slice(0, 6)}…`;
      li.append(champ, suppr, id);
      return li;
    }),
  );

  // Où placer le formulaire d'ajout
  const aucun = telephones.length === 0;
  el.vide.hidden = !aucun;
  el.tableau.hidden = aucun;
  el.gestion.hidden = aucun;
  (aucun ? el.placeVide : el.placeGestion).append(el.form);
}

async function activer(id) {
  const tel = telephones.find((t) => t.id === id) ?? telephones[0];
  fermerVue(vue);
  vue = null;
  actifId = tel?.id ?? null;
  ecrire(CLE_ACTIF, actifId ?? undefined);
  afficherListe();
  if (!tel) return;
  const v = nouvelleVue(tel);
  vue = v;
  el.nom.textContent = tel.nom;
  accuse("");
  chargerJournal(tel.id);
  afficherEtat();
  afficherBoutons();
  afficherPastille();
  let cles = null;
  try {
    cles = typeof tel.s === "string" ? await preparer(base64urlVersOctets(tel.s)) : await lireCles(tel.id);
    if (cles && !cles.cleCmd) {
      // Clés rangées avant la 1.6 : la clé de commande est dérivée de cleSig, puis rangée.
      cles = await completerCles(cles);
      rangerCles(cles).catch(() => {});
    }
  } catch (e) {
    if (v === vue) accuse(e.message, "alerte");
    return;
  }
  if (v !== vue) return;
  if (!cles) {
    accuse("Key not found in this browser: add this phone again with its link or 12 words", "alerte");
    return;
  }
  v.cles = cles;
  afficherBoutons();
  connecter(v);
}

// ---------------------------------------------------------------------------
// Ajout et suppression
// ---------------------------------------------------------------------------

/**
 * Ajoute un téléphone. Seules ses clés préparées (non exportables) sont rangées, pas S.
 * depuisLien : ajout par un lien ouvert directement (nom proposé préfixé par « New: »).
 */
async function ajouter(S, nom, origine, { depuisLien = false } = {}) {
  const cles = await preparer(S);
  const id = cles.id;
  const existant = telephones.find((t) => t.id === id);
  if (existant) {
    if (nom) {
      existant.nom = nom;
      existant.nomAuto = false;
    }
    if (typeof existant.s !== "string") await rangerCles(cles).catch(() => {});
    sauverTelephones();
    await activer(id);
    noter("This phone was already added");
    return;
  }
  const tel = {
    id,
    nom: nom || (telephones.length ? `Phone ${telephones.length + 1}` : "My phone"),
    nomAuto: !nom,
    prefixeNom: depuisLien ? "New: " : "",
    ajoute: Date.now(),
  };
  if (depuisLien && !nom) tel.nom = `New: ${tel.nom}`;
  try {
    await rangerCles(cles);
  } catch {
    // Pas d'IndexedDB (navigation privée de certains navigateurs) : S en secours dans localStorage.
    tel.s = octetsVersBase64url(S);
  }
  telephones.push(tel);
  sauverTelephones();
  await activer(id);
  noter("Phone added to this browser", "ok");
  if (origine && origine !== location.origin) {
    noter(`This link comes from ${origine}: the phone must use the same relay as this page`, "alerte");
  }
  // Demande au navigateur de ne pas effacer ce site de lui-même (Safari efface après 7 jours sans visite).
  let persistant = false;
  try {
    persistant = (await navigator.storage?.persist?.()) === true;
  } catch {
    persistant = false;
  }
  if (!persistant) {
    noter(
      "On iPhone and Mac, Safari may forget this phone after 7 days without a visit. Add the page to your Home Screen, or keep your 12 words.",
      "alerte",
    );
  }
}

function supprimer(tel) {
  if (!confirm(`Remove “${tel.nom}” from this browser? You will need its link or 12 words to add it again.`)) return;
  telephones = telephones.filter((t) => t.id !== tel.id);
  sauverTelephones();
  ecrire(CLE_JOURNAL + tel.id, undefined);
  oublierCmdV3(tel.id);
  oublierCles(tel.id).catch(() => {});
  activer(tel.id === actifId ? telephones[0]?.id : actifId);
}

el.form.addEventListener("submit", async (e) => {
  e.preventDefault();
  el.erreurAjout.textContent = "";
  try {
    const { S, origine } = await lireSaisie(el.saisie.value);
    await ajouter(S, el.nomSaisi.value.trim().slice(0, 40), origine);
    el.saisie.value = "";
    el.nomSaisi.value = "";
    el.gestion.open = false;
  } catch (err) {
    el.erreurAjout.textContent = err.message;
  }
});

el.choix.addEventListener("change", () => activer(el.choix.value));
el.sonner.addEventListener("click", () => envoyer("ring"));
el.arreter.addEventListener("click", () => envoyer("stop"));

// Lien collé dans la barre d'adresse alors que la page est déjà ouverte.
window.addEventListener("hashchange", () => {
  if (!/(^#|&)s=/.test(location.hash)) return;
  const fragment = location.hash;
  history.replaceState(null, "", location.pathname + location.search);
  ajouterDepuisFragment(fragment);
});

/**
 * Ajout par un lien ouvert directement. N'importe qui peut fabriquer un tel lien : on demande
 * confirmation avec le début de l'identifiant (affiché aussi dans l'app, écran Appairage)
 * avant d'ajouter un téléphone inconnu et d'en faire le téléphone affiché.
 */
async function ajouterDepuisFragment(fragment) {
  try {
    const S = secretDuFragment(fragment);
    const id = await deriveId(S);
    if (!telephones.some((t) => t.id === id)) {
      const ok = confirm(
        `Add the phone with identifier ${id.slice(0, 6)} to this browser?\n\n` +
          "Only accept if this link comes from the Sonne app on your own phone. Check that its Your key tab shows the same identifier.",
      );
      if (!ok) return;
    }
    await ajouter(S, "", null, { depuisLien: true });
  } catch (err) {
    if (telephones.length === 0) el.erreurAjout.textContent = err.message;
    else noter(err.message, "alerte");
  }
}

// Rafraîchit les « il y a… ».
setInterval(() => {
  afficherPastille();
  if (vue?.etat) el.dernierEtat.textContent = ilYA(vue.recuA);
}, 15_000);

// Au retour sur la page (mobile), on se reconnecte tout de suite si besoin.
document.addEventListener("visibilitychange", () => {
  const v = vue;
  if (document.visibilityState !== "visible" || !v || v.relaisOuvert) return;
  clearTimeout(v.minuteurReconnexion);
  v.minuteurReconnexion = null;
  v.delai = 1000;
  if (!v.ws) connecter(v);
});

// ---------------------------------------------------------------------------
// Démarrage
// ---------------------------------------------------------------------------

await migrerSecrets();
if (fragmentInitial) await ajouterDepuisFragment(fragmentInitial);
if (!vue) await activer(actifId);

if ("serviceWorker" in navigator && window.isSecureContext) {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
