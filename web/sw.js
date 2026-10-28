// Keeps the page working offline once it has been opened. Bump VERSION when files change.
const VERSION = 'sc-web-1';
const FILES = ['./', 'index.html', 'reader.js', 'manifest.webmanifest', 'apple-touch-icon.png', 'icon-192.png', 'icon-512.png',
  'fonts/BarlowCondensed-Bold.ttf', 'fonts/BarlowCondensed-SemiBold.ttf', 'fonts/BarlowSemiCondensed-Regular.ttf', 'fonts/BarlowSemiCondensed-Medium.ttf'];

self.addEventListener('install', e => e.waitUntil(caches.open(VERSION).then(c => c.addAll(FILES)).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim())
));
// Network first (so updates show up), the saved copy when offline.
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith(
    fetch(e.request).then(r => { const copy = r.clone(); caches.open(VERSION).then(c => c.put(e.request, copy)); return r; })
      .catch(() => caches.match(e.request, { ignoreSearch: true }).then(r => r || caches.match('index.html')))
  );
});
