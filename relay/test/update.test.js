// Tests de la mise à jour intégrée : /update/android.json et /download/<nom>.apk.

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import http from "node:http";
import { demarrer, nomApkValide } from "../server.js";

function dossierTemporaire(t) {
  const d = mkdtempSync(path.join(tmpdir(), "sonne-releases-"));
  t.after(() => rmSync(d, { recursive: true, force: true }));
  return d;
}

async function relais(t, options = {}) {
  const r = await demarrer({ port: 0, hote: "127.0.0.1", silencieux: true, ...options });
  t.after(() => r.arreter());
  return { ...r, http: `http://127.0.0.1:${r.port}` };
}

/** Requête brute : le chemin part tel quel, sans normalisation par fetch (../, %2e). */
function brute(port, chemin, methode = "GET") {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: "127.0.0.1", port, path: chemin, method: methode }, (res) => {
      const morceaux = [];
      res.on("data", (m) => morceaux.push(m));
      res.on("end", () => resolve({ statut: res.statusCode, entetes: res.headers, corps: Buffer.concat(morceaux) }));
    });
    req.on("error", reject);
    req.end();
  });
}

const ANNONCE = {
  version: "1.2.0",
  versionCode: 4,
  url: "https://sonne.sidali.dev/download/sonne-1.2.0.apk",
  sha256: "a".repeat(64),
  certSha256: "b".repeat(64),
  notes: "Mise à jour intégrée",
};

test("noms d'APK : validation stricte", () => {
  for (const ok of ["sonne-1.2.0.apk", "sonne.apk", "Sonne_1.apk", "a-b.c-d.apk"]) assert.equal(nomApkValide(ok), true, ok);
  for (const ko of [
    "",
    ".apk",
    "..apk",
    "../sonne.apk",
    "a/b.apk",
    "a\\b.apk",
    "sonne..apk",
    "sonne-1..2.apk",
    ".cache.apk",
    "sonne.apk.json",
    "android.json",
    "sonne%2e.apk",
    "sonne .apk",
    "sonne\0.apk",
    "x".repeat(97) + ".apk",
    undefined,
    42,
  ]) {
    assert.equal(nomApkValide(ko), false, String(ko));
  }
});

test("annonce et téléchargement servis depuis le dossier des versions", async (t) => {
  const d = dossierTemporaire(t);
  writeFileSync(path.join(d, "android.json"), JSON.stringify(ANNONCE));
  const apk = Buffer.from("PK\u0003\u0004 faux apk pour le test");
  writeFileSync(path.join(d, "sonne-1.2.0.apk"), apk);
  const r = await relais(t, { dossierReleases: d });

  const res = await fetch(`${r.http}/update/android.json`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type"), /^application\/json/);
  assert.equal(res.headers.get("cache-control"), "public, max-age=300");
  assert.equal(res.headers.get("x-content-type-options"), "nosniff");
  assert.deepEqual(await res.json(), ANNONCE);

  const tete = await fetch(`${r.http}/update/android.json`, { method: "HEAD" });
  assert.equal(tete.status, 200);

  const dl = await fetch(`${r.http}/download/sonne-1.2.0.apk`);
  assert.equal(dl.status, 200);
  assert.equal(dl.headers.get("content-type"), "application/vnd.android.package-archive");
  assert.equal(Number(dl.headers.get("content-length")), apk.length);
  assert.match(dl.headers.get("content-disposition"), /filename="sonne-1\.2\.0\.apk"/);
  assert.deepEqual(Buffer.from(await dl.arrayBuffer()), apk);

  const dlTete = await fetch(`${r.http}/download/sonne-1.2.0.apk`, { method: "HEAD" });
  assert.equal(dlTete.status, 200);
  assert.equal(Number(dlTete.headers.get("content-length")), apk.length);
  assert.equal((await dlTete.arrayBuffer()).byteLength, 0);

  // Autres méthodes refusées.
  assert.equal((await fetch(`${r.http}/update/android.json`, { method: "POST" })).status, 405);
  assert.equal((await fetch(`${r.http}/download/sonne-1.2.0.apk`, { method: "DELETE" })).status, 405);
});

test("téléchargement : chemins hors du dossier et fichiers absents refusés", async (t) => {
  const racine = dossierTemporaire(t);
  const d = path.join(racine, "releases");
  mkdirSync(d);
  mkdirSync(path.join(d, "dossier.apk"));
  writeFileSync(path.join(racine, "secret.apk"), "secret");
  writeFileSync(path.join(d, "android.json"), JSON.stringify(ANNONCE));
  writeFileSync(path.join(d, "notes.txt"), "pas un apk");
  const r = await relais(t, { dossierReleases: d });

  for (const chemin of [
    "/download/../secret.apk",
    "/download/..%2fsecret.apk",
    "/download/%2e%2e/secret.apk",
    "/download/%2e%2e%2fsecret.apk",
    "/download/..%5csecret.apk",
    "/download/android.json",
    "/download/notes.txt",
    "/download/absent.apk",
    "/download/dossier.apk",
    "/download/",
    "/download/sub/x.apk",
  ]) {
    const { statut, corps } = await brute(r.port, chemin);
    assert.notEqual(statut, 200, chemin);
    assert.ok(!corps.toString().includes("secret"), chemin);
  }
});

test("sans dossier de versions, ou annonce invalide : 404", async (t) => {
  const sans = await relais(t);
  assert.equal((await fetch(`${sans.http}/update/android.json`)).status, 404);
  assert.equal((await fetch(`${sans.http}/download/sonne-1.2.0.apk`)).status, 404);

  const d = dossierTemporaire(t);
  const r = await relais(t, { dossierReleases: d });
  assert.equal((await fetch(`${r.http}/update/android.json`)).status, 404);
  writeFileSync(path.join(d, "android.json"), "{ pas du json");
  assert.equal((await fetch(`${r.http}/update/android.json`)).status, 404);
  writeFileSync(path.join(d, "android.json"), "[1, 2]");
  assert.equal((await fetch(`${r.http}/update/android.json`)).status, 404);
  writeFileSync(path.join(d, "android.json"), JSON.stringify({ ...ANNONCE, versionCode: 5 }));
  assert.equal((await (await fetch(`${r.http}/update/android.json`)).json()).versionCode, 5);
});
