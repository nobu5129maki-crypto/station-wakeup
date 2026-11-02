/**
 * Lightweight regression checks for speech matching helpers.
 * Run: node scripts/test-speech-match.cjs
 */
const fs = require('fs');
const path = require('path');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');

function extractFunction(name) {
  const re = new RegExp(`function ${name}\\([\\s\\S]*?\\n        \\}\\r?\\n`);
  const m = html.match(re);
  if (!m) throw new Error('missing function ' + name);
  return m[0];
}

eval(extractFunction('normalizeForMatch'));
eval(extractFunction('escapeRegex'));
eval(extractFunction('isKanjiChar'));
eval(extractFunction('isCuePhrase'));
eval(extractFunction('boundaryBefore'));
eval(extractFunction('boundaryAfter'));
eval(extractFunction('boundedIncludes'));
eval(extractFunction('textMatchesTarget'));

function checkMatchLike(target, text) {
  return textMatchesTarget(target, text);
}

const cases = [
  ['品川', '次は品川です', true],
  ['品川', 'まもなく品川', true],
  ['品川', '次は品川シーサイドです', false],
  ['品川', '品川シーサイド', false],
  ['品川', '品川駅', true],
  ['品川駅', '次は品川です', true],
  ['品川', '品川の次は高輪', true],
  ['お茶の水', '新お茶の水', false],
  ['お茶の水', '次はお茶の水です', true],
  ['新宿', '次は、新宿、新宿です', true],
  ['新宿', '次は東新宿', false],
  ['新宿', '新宿三丁目', false],
  ['新宿', '東新宿、新宿です', true],
  ['横浜', '新横浜', false],
  ['横浜', 'まもなく横浜です', true],
  ['船橋', '西船橋', false],
  ['中野', '中野坂上', false],
  ['東京', '東京駅', true],
  ['東京', '東京テレポート', false],
  ['茅ヶ崎', 'まもなく茅が崎です', true],
  ['茅ヶ崎', '次は茅ヶ崎', true],
  ['橋', '日本橋', false],
  ['橋', '次は橋', true],
  ['橋', '橋本', false],
  ['つつじヶ丘', 'つつじヶ丘', true],
  ['つつじ', 'つつじヶ丘', false],
  ['まもなく', 'まもなく品川', true],
  ['次は', '次は品川です', true],
  ['次', '次は品川', true],
  ['次', '次回の電車', false],
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

const javaPath = path.join(__dirname, '..', 'android/app/src/main/java/jp/stationwakeup/app/StationSpeechPlugin.java');
if (!fs.existsSync(javaPath)) {
  console.error('MISSING StationSpeechPlugin.java');
  failed++;
} else {
  const java = fs.readFileSync(javaPath, 'utf8');
  if (java.includes('EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE')) {
    console.error('FAIL: EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE is a boolean extra; a string value can drop transcripts');
    failed++;
  } else {
    console.log('OK android recognizer does not set ONLY_RETURN_LANGUAGE_PREFERENCE');
  }
  if (!java.includes('LANGUAGE_MODEL_FREE_FORM')) {
    console.error('FAIL: missing LANGUAGE_MODEL_FREE_FORM');
    failed++;
  }
}
if (!html.includes('function failMonitoring(') || !html.includes('keepStatusOnEnd')) {
  console.error('MISSING fatal monitoring stop');
  failed++;
}

if (failed) {
  console.error('FAILED', failed);
  process.exit(1);
}
console.log('All speech checks passed');
