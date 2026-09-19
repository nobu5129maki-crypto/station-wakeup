#!/usr/bin/env bash
# Verify official APK is launchable and has a visible home-screen icon.
set -euo pipefail
APK="${1:-public/downloads/StationWakeUp-Official.apk}"
BUILD_TOOLS="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/35.0.0"
AAPT="$BUILD_TOOLS/aapt"
test -f "$APK"
test -x "$AAPT"

echo "== badging =="
BADGING=$("$AAPT" dump badging "$APK")
printf '%s\n' "$BADGING" | grep -E 'package:|application:|launchable-activity:' || true

[[ "$BADGING" == *"launchable-activity: name='jp.stationwakeup.app.MainActivity'"* ]]
[[ "$BADGING" == *"application-label:'Station WakeUp'"* ]]
[[ "$BADGING" == *"versionCode='10'"* ]]

RESOURCES=$("$AAPT" dump resources "$APK")
[[ "$RESOURCES" == *'color/ic_launcher_background: t=0x1d d=0xff1e3a8a'* ]]

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" '*.png' -d "$TMP"

python3 - "$TMP" <<'PY'
from pathlib import Path
from PIL import Image
import sys
root = Path(sys.argv[1])
blueish = []
for p in root.rglob('*.png'):
    im = Image.open(p).convert('RGBA')
    w, h = im.size
    if w != h:
        continue
    if w < 48 or w > 512:
        continue
    px = list(im.getdata())
    opaque = [(r, g, b) for r, g, b, a in px if a > 200]
    if len(opaque) < (w * h) * 0.2:
        continue
    avg = tuple(sum(c[i] for c in opaque) // len(opaque) for i in range(3))
    if avg[0] > 220 and avg[1] > 220 and avg[2] > 220:
        continue
    if avg[2] > avg[0] + 20:
        blueish.append((w, avg, p))

if not blueish:
    print('ERROR: no visible brand launcher icons found in APK')
    sys.exit(1)
blueish.sort(reverse=True)
print(f'brand icons found: {len(blueish)} (largest {blueish[0][0]}px avg={blueish[0][1]})')
print('ICON_VISIBLE_OK')
PY

DEX_STRINGS=$(unzip -p "$APK" classes.dex | strings || true)
[[ "$DEX_STRINGS" == *'HomeScreenPlugin'* ]]
[[ "$DEX_STRINGS" == *'requestPinShortcut'* ]]
[[ "$DEX_STRINGS" == *'asked_home_pin_v2'* ]]
echo 'HOME_PIN_CODE_OK'
echo 'APK_LAUNCHER_OK'
