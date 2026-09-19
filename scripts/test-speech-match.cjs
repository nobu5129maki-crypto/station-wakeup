/**
 * Lightweight regression checks for speech matching helpers.
 * Run: node scripts/test-speech-match.cjs
 */
const fs = require('fs');
const path = require('path');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');

function extractFunction(name) {
  const re = new RegExp(`function ${name}\\([\\s\\S]*?\\n        \\}\\n`);
  const m = html.match(re);
  if (!m) throw new Error('missing function ' + name);
  return m[0];
}

// Evaluate normalizeForMatch in isolation
eval(extractFunction('normalizeForMatch'));
eval(extractFunction('escapeRegex'));

function checkMatchLike(target, text) {
  const normTarget = normalizeForMatch(target);
  const normText = normalizeForMatch(text);
  if (!normTarget) return false;
  const escaped = escapeRegex(normTarget);
  let pattern;
  if (normTarget.length <= 1) {
    pattern = new RegExp(`(?<![\\u3400-\\u9fff\\u3005新])${escaped}(?![\\u3400-\\u9fff\\u3005])`);
  } else {
    pattern = new RegExp(`(?<!新)${escaped}`);
  }
  return pattern.test(normText);
}

const cases = [
  ['品川', '次は品川です', true],
  ['品川', 'まもなく品川', true],
  ['お茶の水', '新お茶の水', false],
  ['新宿', '次は、新宿、新宿です', true],
  ['茅ヶ崎', 'まもなく茅が崎です', true],
  ['茅ヶ崎', '次は茅ヶ崎', true],
  ['橋', '日本橋', false],
  ['橋', '次は橋', true],
];

let failed = 0;
for (const [target, text, expect] of cases) {
  const got = checkMatchLike(target, text);
  if (got !== expect) {
    console.error('FAIL', { target, text, expect, got });
    failed++;
  } else {
    console.log('OK', target, '<=', text);
  }
}

const required = [
  'StationSpeech',
  'startNativeSpeechSession',
  'getNativeSpeechBackend',
  '聞き取り中（公式アプリ）',
];
for (const s of required) {
  if (!html.includes(s)) {
    console.error('MISSING in index.html:', s);
    failed++;
  }
}

if (!fs.existsSync(path.join(__dirname, '..', 'android/app/src/main/java/jp/stationwakeup/app/StationSpeechPlugin.java'))) {
  console.error('MISSING StationSpeechPlugin.java');
  failed++;
}

if (failed) {
  console.error('FAILED', failed);
  process.exit(1);
}
console.log('All speech checks passed');
