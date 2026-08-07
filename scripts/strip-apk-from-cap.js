const fs = require('fs');
const path = require('path');

/** Capacitor 同梱から配布用 APK を除外（アプリ肥大化防止） */
function stripHostedApkFromCapacitorAssets(platformPublicDir) {
  const dir = path.join(platformPublicDir, 'downloads');
  if (!fs.existsSync(dir)) return;
  for (const name of fs.readdirSync(dir)) {
    if (name.endsWith('.apk')) {
      fs.unlinkSync(path.join(dir, name));
      console.log('Removed hosted APK from Capacitor assets:', path.join(dir, name));
    }
  }
  try {
    if (fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
  } catch (e) { /* ignore */ }
}

const root = path.join(__dirname, '..');
stripHostedApkFromCapacitorAssets(path.join(root, 'android/app/src/main/assets/public'));
stripHostedApkFromCapacitorAssets(path.join(root, 'ios/App/App/public'));
