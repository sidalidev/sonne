// Service worker de Sonne : permet d'installer la page et de l'ouvrir hors ligne.
// Réseau d'abord (la page doit rester à jour), cache en secours.
// Le relais (/ws, /api, /health) n'est jamais mis en cache.

const CACHE = "sonne-v7";
const COQUILLE = ["./", "index.html", "about.html", "app.js", "crypto.js", "bip39-english.js", "style.css", "icon.svg", "icon-maskable.svg", "manifest.webmanifest", "fonts/Figtree.ttf", "fonts/BricolageGrotesque-Bold.ttf"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(COQUILLE)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((cles) => Promise.all(cles.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== self.location.origin) return;
  if (/^\/(ws|api|health)(\/|$)/.test(url.pathname)) return;
  e.respondWith(
    fetch(e.request)
      .then((rep) => {
        if (rep.ok) {
          const copie = rep.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copie));
        }
        return rep;
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match("index.html"))),
  );
});
