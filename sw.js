const CACHE_NAME = 'station-wakeup-v5';
const ASSETS = [
  './',
  './index.html',
  'https://cdn.tailwindcss.com'
];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
  event.respondWith(
    caches.match(event.request).then((response) => response || fetch(event.request))
  );
});

/** ページからの音声検知ではユーザ操作が無く、メインスレッドの showNotification だと振動が省略される端末があるため SW 側で表示する */
self.addEventListener('message', (event) => {
  const data = event.data;
  if (!data || data.type !== 'alarm-haptic') return;
  const title = data.title || '駅に到着します';
  const options = data.options;
  if (!options || typeof options !== 'object') return;
  event.waitUntil(
    self.registration.showNotification(title, options).catch(() => {})
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
