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
// アイコンは PNG を配信（Android / iPhone / 通知で共通に使える）
for (const f of ['icon.png', 'icon.svg']) {
  const src = path.join(root, f);
  if (fs.existsSync(src)) fs.copyFileSync(src, path.join(pub, f));
}
const iconsSrc = path.join(root, 'icons');
const iconsDest = path.join(pub, 'icons');
if (fs.existsSync(iconsSrc)) {
  fs.mkdirSync(iconsDest, { recursive: true });
  for (const name of fs.readdirSync(iconsSrc)) {
    if (name.endsWith('.png')) {
      fs.copyFileSync(path.join(iconsSrc, name), path.join(iconsDest, name));
    }
  }
}

// Web 配信用 APK（Vercel は public/ を配信）
const downloadsSrc = path.join(root, 'downloads');
const downloadsDest = path.join(pub, 'downloads');
if (fs.existsSync(downloadsSrc)) {
  fs.mkdirSync(downloadsDest, { recursive: true });
  for (const name of fs.readdirSync(downloadsSrc)) {
    if (name.startsWith('.')) continue;
    fs.copyFileSync(path.join(downloadsSrc, name), path.join(downloadsDest, name));
  }
}

console.log('Copied static files to public/');
