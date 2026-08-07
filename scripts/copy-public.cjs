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
