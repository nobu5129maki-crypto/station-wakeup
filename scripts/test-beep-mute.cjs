/**
 * 聞き取り中のシステム音（ピッ）対策の回帰チェック。
 * Run: node scripts/test-beep-mute.cjs
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const java = fs.readFileSync(
  path.join(root, 'android/app/src/main/java/jp/stationwakeup/app/StationSpeechPlugin.java'),
  'utf8'
);
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

let failed = 0;
function must(cond, msg) {
  if (cond) {
    console.log('OK', msg);
  } else {
    console.error('FAIL', msg);
    failed++;
  }
}

// --- ネイティブ側 ---
must(java.includes('AudioManager.ADJUST_MUTE'), 'ネイティブでシステム音の経路をミュートする');
must(java.includes('AudioManager.ADJUST_UNMUTE'), 'ネイティブでミュートを解除する');
must(java.includes('STREAM_MUSIC') && java.includes('STREAM_SYSTEM'), '音声認識の効果音が出るストリームを対象にしている');

// startListening の直前にミュート
const startIdx = java.indexOf('speechRecognizer.startListening(intent);');
const muteBefore = java.lastIndexOf('muteBeepWindow();', startIdx);
must(startIdx > 0 && muteBefore > 0 && startIdx - muteBefore < 200, '開始音の直前でミュートしている');

// 終了系コールバックでミュート
for (const cb of ['onEndOfSpeech', 'onError', 'onResults']) {
  const i = java.indexOf(`public void ${cb}(`);
  const body = java.slice(i, i + 400);
  must(i > 0 && body.includes('muteBeepWindow();'), `${cb} で終了音をミュートしている`);
}

// stop() では復帰（アラーム音が消えないように）
const stopIdx = java.indexOf('public void stop(PluginCall call)');
const stopBody = java.slice(stopIdx, stopIdx + 700);
must(stopBody.includes('restoreAudio();'), 'stop() で音量を必ず戻す');

// バックグラウンド・破棄時も復帰
must(/handleOnPause\(\)[\s\S]{0,200}restoreAudio\(\);/.test(java), 'onPause で音量を戻す');
must(/handleOnDestroy\(\)[\s\S]{0,300}restoreAudio\(\);/.test(java), 'onDestroy で音量を戻す');

// 音楽再生中は短時間ミュートに切り替える
must(java.includes('isMusicActive()'), '他アプリの音楽再生を判定している');
must(java.includes('BEEP_WINDOW_MS'), '音楽再生中は短時間だけミュートする');

// --- Web 側 ---
// アラーム時はネイティブ認識を止めて（＝ミュート解除して）から鳴らす
const trig = html.indexOf('function triggerAlarm(');
const trigBody = html.slice(trig, trig + 2500);
must(trigBody.includes('stopNativeSpeechSession()'), 'アラーム時にネイティブ認識を停止する');
// startVibration() は beginAlarmOutput の中だけで呼ばれ、beginAlarmOutput は停止完了後に呼ばれる
const startCalls = trigBody.match(/startVibration\(\);/g) || [];
const closureStart = trigBody.indexOf('const beginAlarmOutput = () => {');
const closureEnd = trigBody.indexOf('};', closureStart);
const startInClosure = trigBody.indexOf('startVibration();');
must(
  startCalls.length === 1 && closureStart > 0 && startInClosure > closureStart && startInClosure < closureEnd,
  'アラーム出力は beginAlarmOutput 内でのみ開始する'
);
must(
  trigBody.indexOf('stopNativeSpeechSession().then(beginAlarmOutput') > closureEnd,
  'ネイティブ停止（ミュート解除）の完了後に beginAlarmOutput を呼ぶ'
);
must(trigBody.includes('setTimeout(beginAlarmOutput'), '停止が遅れてもアラームは必ず鳴る');

// 監視開始時に公式アプリでは無音の音声出力を作らない／開いていれば止める（音楽再生中と誤判定させない）
must(/if \(!isCapacitorNative\(\)\) \{\s*primeAlarmAudioFromUserGesture\(\);/.test(html), '公式アプリでは開始時に音声出力を準備しない');
must(html.includes("alarmAudioCtx.suspend()"), '公式アプリでは開始時に開いている音声出力を止める');

// 開始音は onReadyForSpeech で鳴る → その直後にミュート継続と解除予約
const readyIdx = java.indexOf('public void onReadyForSpeech(');
const readyBody = java.slice(readyIdx, readyIdx + 400);
must(readyBody.includes('muteBeepWindow();') && readyBody.includes('scheduleBeepWindowEnd(BEEP_WINDOW_MS)'), 'onReadyForSpeech（開始音）でミュートし、鳴り終わってから戻す');
// 準備完了が来ない場合の保険（長すぎるミュートを防ぐ）
must(java.includes('scheduleBeepWindowEnd(BEEP_WINDOW_FALLBACK_MS)'), '準備完了が来なくても一定時間で音量を戻す');
// 再開回数を減らす（無音判定を長く）
must(/COMPLETE_SILENCE_LENGTH_MILLIS, (4000|5000|6000)\)/.test(java), '無音判定を長くして再開回数を減らす');

// 「待機」表示の点滅を抑える
must(html.includes('nativeIdleStatusTimer = setTimeout('), '待機表示は長引いた時だけ出す');

// --- 完全消音スイッチ（初期値 ON） ---
must(html.includes('id="fullMuteToggleBtn"') && html.includes('aria-checked="true"'), '完全消音スイッチが UI にある（初期 ON）');
must(/const FULL_MUTE_KEY = 'stationWakeUp_fullMute'/.test(html), '完全消音の設定を保存する');
must(/if \(raw == null\) return true;[\s\S]{0,120}FULL_MUTE|function isFullMuteEnabled\(\) \{[\s\S]{0,160}if \(raw == null\) return true;/.test(html), '未設定時は完全消音 ON');
must(html.includes('fullMute: isFullMuteEnabled()'), '監視開始時にネイティブへ完全消音の設定を渡す');
must(html.includes('P.setFullMute({ enabled: on })'), '監視中の切替も即時反映する');
must(java.includes('private boolean fullMute = true;'), 'ネイティブ側の初期値も ON');
must(java.includes('useSessionMute = fullMute || !isMusicPlayingElsewhere();'), 'ON のときは音楽再生中でも常時ミュート');
must(java.includes('public void setFullMute(PluginCall call)'), 'ネイティブに setFullMute がある');
must(java.includes('call.getBoolean("fullMute", true)'), 'start() で fullMute を受け取る');

if (failed) {
  console.error('FAILED', failed);
  process.exit(1);
}
console.log('All beep-mute checks passed');
