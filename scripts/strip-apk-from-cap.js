const fs = require('fs');
const path = require('path');

/** Capacitor 同梱から配布用 downloads（APK/ZIP）を除外（アプリ肥大化・混乱防止） */
function stripHostedPackagesFromCapacitorAssets(platformPublicDir) {
  const dir = path.join(platformPublicDir, 'downloads');
  if (!fs.existsSync(dir)) return;
  fs.rmSync(dir, { recursive: true, force: true });
  console.log('Removed hosted downloads from Capacitor assets:', dir);
}

const root = path.join(__dirname, '..');
stripHostedPackagesFromCapacitorAssets(path.join(root, 'android/app/src/main/assets/public'));
stripHostedPackagesFromCapacitorAssets(path.join(root, 'ios/App/App/public'));
