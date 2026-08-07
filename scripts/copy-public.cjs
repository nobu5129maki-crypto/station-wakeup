const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const pub = path.join(root, 'public');
fs.mkdirSync(pub, { recursive: true });
for (const f of ['index.html', 'sw.js', 'manifest.json', 'download.html']) {
  const src = path.join(root, f);
  if (!fs.existsSync(src)) {
    console.error('Missing:', f);
    process.exit(1);
  }
  fs.copyFileSync(src, path.join(pub, f));
}
const icon = path.join(root, 'icon.svg');
if (fs.existsSync(icon)) {
  fs.copyFileSync(icon, path.join(pub, 'icon.svg'));
}

// 注意: downloads/*.apk は public にコピーしない。
// Capacitor が public を APK 内に同梱するため、配布用 APK をアプリに再梱包しない。
// Web（Vercel / GitHub Pages）はリポジトリ直下の downloads/ を配信する。

console.log('Copied static files to public/');
