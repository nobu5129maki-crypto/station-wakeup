const CACHE_NAME = 'station-wakeup-v21';
const ASSETS = [
  './',
  './index.html',
  './download.html',
  'https://cdn.tailwindcss.com'
];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  // APK/ZIP はキャッシュせず常にネットから
  if (url.pathname.endsWith('.apk') || url.pathname.endsWith('.zip') || url.pathname.includes('/downloads/')) {
    event.respondWith(fetch(event.request));
    return;
  }
  // HTML はネットワーク優先（古い案内ページが残り続けないようにする）
  const isHtmlNav =
    event.request.mode === 'navigate' ||
    url.pathname.endsWith('.html') ||
    url.pathname === '/' ||
    url.pathname.endsWith('/');
  if (isHtmlNav) {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy)).catch(() => {});
          return response;
        })
        .catch(() => caches.match(event.request).then((cached) => cached || caches.match('./index.html')))
    );
    return;
  }
  event.respondWith(
    caches.match(event.request).then((response) => response || fetch(event.request))
  );
});

/**
 * Android は「同じ tag の更新」だと振動しないことが多い。
 * 少しずらして 2 通続けて出すと、フォアグラウンドでもバイブが付きやすい。
 */
self.addEventListener('message', (event) => {
  const data = event.data;
  if (!data || data.type !== 'alarm-haptic') return;
  const title = data.title || '駅に到着します';
  const o = data.options;
  if (!o || typeof o !== 'object') return;
  event.waitUntil(
    (async () => {
      try {
        await self.registration.showNotification(title, o);
        const tag2 = `${o.tag || 'alarm'}-f`;
        const o2 = Object.assign({}, o, { tag: tag2, renotify: false });
        await new Promise((r) => setTimeout(r, 520));
        await self.registration.showNotification(title, o2);
      } catch (e) { /* ignore */ }
    })()
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      if (clientList.length) {
        const c = clientList[0];
        if ('focus' in c) return c.focus();
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow('./index.html');
      }
    })
  );
});
