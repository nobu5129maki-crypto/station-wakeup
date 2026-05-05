const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const pub = path.join(root, 'public');
fs.mkdirSync(pub, { recursive: true });
for (const f of ['index.html', 'sw.js', 'manifest.json']) {
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
console.log('Copied static files to public/');
