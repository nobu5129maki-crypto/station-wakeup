package jp.stationwakeup.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;

/**
 * 駅アナウンス監視向けの連続音声認識。
 * Android WebView の Web Speech API は使えないため、SpeechRecognizer を直接使う。
 */
@CapacitorPlugin(
    name = "StationSpeech",
    permissions = {
        @Permission(
            alias = "microphone",
            strings = { Manifest.permission.RECORD_AUDIO }
        )
    }
)
public class StationSpeechPlugin extends Plugin {

    private SpeechRecognizer speechRecognizer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean wantListening = false;
    private boolean isStarting = false;
    private String language = "ja-JP";
    private int maxResults = 5;
    private int restartDelayMs = 450;
    private final Runnable restartRunnable = this::startListeningInternal;

    /**
     * 認識エンジン。
     *  - Android 13+ で端末内認識が使えれば「連続セッション（segmented）」を使う。
     *    セッションを再開しないので途中の効果音が鳴らない。
     *  - それ以外は従来のクラウド認識。無音判定を長くして再開（効果音）を減らす。
     */
    private static final String ENGINE_CONTINUOUS = "on-device-continuous";
    private static final String ENGINE_CLOUD = "cloud";
    private String engine = ENGINE_CLOUD;
    private boolean onDeviceUnavailable = false;
    /** クラウド認識の無音判定（長いほど再開＝効果音が減る） */
    private static final int CLOUD_SILENCE_MS = 30000;
    /** 連続セッションの区切り（結果を確定させる無音）。セッション自体は継続する */
    private static final int SEGMENT_SILENCE_MS = 5000;

    private boolean canUseOnDevice() {
        if (onDeviceUnavailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        try {
            return SpeechRecognizer.isOnDeviceRecognitionAvailable(getContext());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 端末内認識が言語未対応などで使えないと分かったら、クラウド認識に切り替える */
    private void fallbackToCloud() {
        onDeviceUnavailable = true;
        engine = ENGINE_CLOUD;
        try {
            if (speechRecognizer != null) {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            }
        } catch (Exception ignored) {
            /* ignore */
        }
        speechRecognizer = null;
        isStarting = false;
    }

    /**
     * SpeechRecognizer は開始・終了ごとにシステム音（ピッ）を鳴らす。
     * 連続認識では数秒おきに鳴ってうるさいため、その音が出る音量経路を黙らせる。
     *  - 他アプリが音楽を再生していなければ、監視中ずっとミュート（確実）
     *  - 音楽再生中なら、開始/終了の前後だけ短くミュート（音楽をなるべく止めない）
     */
    private AudioManager audioManager;
    private boolean sessionMuted = false;
    private boolean windowMuted = false;
    private boolean useSessionMute = false;
    /** 完全消音: 音楽再生中でも監視中はずっとミュートする（初期値 ON） */
    private boolean fullMute = true;
    /** 開始音は onReadyForSpeech の時点で鳴る。準備完了後この時間は黙らせる */
    private static final long BEEP_WINDOW_MS = 800L;
    /** onReadyForSpeech が来ない場合の保険（ここまで待って戻す） */
    private static final long BEEP_WINDOW_FALLBACK_MS = 5000L;
    private static final int[] BEEP_STREAMS = {
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM
    };
    private final Runnable unmuteWindowRunnable = this::unmuteBeepWindow;

    private AudioManager audio() {
        if (audioManager == null) {
            Context ctx = getContext();
            if (ctx != null) {
                audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            }
        }
        return audioManager;
    }

    private void adjustStreams(int direction) {
        AudioManager am = audio();
        if (am == null) {
            return;
        }
        for (int stream : BEEP_STREAMS) {
            try {
                am.adjustStreamVolume(stream, direction, 0);
            } catch (Exception ignored) {
                // マナーモード中の一部ストリームは調整不可
            }
        }
    }

    private boolean isMusicPlayingElsewhere() {
        AudioManager am = audio();
        try {
            return am != null && am.isMusicActive();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void beginSessionMuteIfNeeded() {
        useSessionMute = fullMute || !isMusicPlayingElsewhere();
        if (useSessionMute && !sessionMuted) {
            mainHandler.removeCallbacks(unmuteWindowRunnable);
            adjustStreams(AudioManager.ADJUST_MUTE);
            sessionMuted = true;
            windowMuted = false;
        }
    }

    /** 監視中に完全消音の ON/OFF が変わったとき、即座にモードを切り替える */
    private void applyFullMuteChange() {
        if (!wantListening) {
            return;
        }
        if (fullMute) {
            beginSessionMuteIfNeeded();
        } else if (sessionMuted && isMusicPlayingElsewhere()) {
            // 音楽を聴きたい人向け: 常時ミュートをやめて短時間モードへ
            adjustStreams(AudioManager.ADJUST_UNMUTE);
            sessionMuted = false;
            useSessionMute = false;
        }
    }

    @PluginMethod
    public void setFullMute(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", true);
        fullMute = enabled == null || enabled;
        mainHandler.post(() -> {
            applyFullMuteChange();
            JSObject ret = new JSObject();
            ret.put("fullMute", fullMute);
            ret.put("beepMuted", sessionMuted ? "session" : "window");
            call.resolve(ret);
        });
    }

    private void muteBeepWindow() {
        if (sessionMuted) {
            return;
        }
        mainHandler.removeCallbacks(unmuteWindowRunnable);
        if (!windowMuted) {
            adjustStreams(AudioManager.ADJUST_MUTE);
            windowMuted = true;
        }
    }

    private void scheduleBeepWindowEnd(long delayMs) {
        if (sessionMuted) {
            return;
        }
        mainHandler.removeCallbacks(unmuteWindowRunnable);
        mainHandler.postDelayed(unmuteWindowRunnable, delayMs);
    }

    private void unmuteBeepWindow() {
        if (windowMuted) {
            adjustStreams(AudioManager.ADJUST_UNMUTE);
            windowMuted = false;
        }
    }

    /** 監視終了・アラーム・バックグラウンド時は必ず音を戻す */
    private void restoreAudio() {
        mainHandler.removeCallbacks(unmuteWindowRunnable);
        if (sessionMuted || windowMuted) {
            adjustStreams(AudioManager.ADJUST_UNMUTE);
        }
        sessionMuted = false;
        windowMuted = false;
    }

    private boolean isAvailable() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(getContext());
        } catch (Exception e) {
            return false;
        }
    }

    @PluginMethod
    public void available(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("available", isAvailable());
        call.resolve(ret);
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        JSObject ret = new JSObject();
        PermissionState state = getPermissionState("microphone");
        ret.put("microphone", state != null ? state.toString() : "prompt");
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            JSObject ret = new JSObject();
            ret.put("microphone", "granted");
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("microphone", call, "microphonePermsCallback");
    }

    @PermissionCallback
    private void microphonePermsCallback(PluginCall call) {
        JSObject ret = new JSObject();
        PermissionState state = getPermissionState("microphone");
        ret.put("microphone", state != null ? state.toString() : "denied");
        if (state == PermissionState.GRANTED) {
            call.resolve(ret);
        } else {
            call.reject("マイク権限が拒否されました");
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!isAvailable()) {
            call.reject("この端末では音声認識を利用できません。Google 音声サービス等を有効にしてください。");
            return;
        }
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("マイク権限がありません。requestPermissions を先に呼んでください。");
            return;
        }

        language = call.getString("language", "ja-JP");
        Integer mr = call.getInt("maxResults", 5);
        maxResults = mr != null ? Math.max(1, Math.min(5, mr)) : 5;
        Integer delay = call.getInt("restartDelayMs", 450);
        restartDelayMs = delay != null ? Math.max(200, Math.min(3000, delay)) : 450;
        Boolean fm = call.getBoolean("fullMute", true);
        fullMute = fm == null || fm;

        wantListening = true;
        mainHandler.post(() -> {
            ensureRecognizer();
            beginSessionMuteIfNeeded();
            startListeningInternal();
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("beepMuted", sessionMuted ? "session" : "window");
            ret.put("engine", engine);
            call.resolve(ret);
        });
    }

    /** アプリのバージョン表示用 */
    @PluginMethod
    public void getAppInfo(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            Context ctx = getContext();
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            ret.put("versionName", info.versionName);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
            ret.put("versionCode", code);
        } catch (Exception e) {
            ret.put("versionName", "unknown");
            ret.put("versionCode", 0);
        }
        ret.put("androidSdk", Build.VERSION.SDK_INT);
        ret.put("onDeviceAvailable", canUseOnDevice());
        ret.put("engine", engine);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        wantListening = false;
        mainHandler.removeCallbacks(restartRunnable);
        // アラーム音が消えないよう、先に音量を戻す
        restoreAudio();
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.cancel();
                }
            } catch (Exception ignored) {
                /* ignore */
            }
            isStarting = false;
            restoreAudio();
            notifyListeningState("stopped");
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void isListening(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("listening", wantListening);
        call.resolve(ret);
    }

    private void ensureRecognizer() {
        if (speechRecognizer != null) {
            return;
        }
        if (canUseOnDevice()) {
            try {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(getContext());
                engine = ENGINE_CONTINUOUS;
            } catch (Exception ignored) {
                speechRecognizer = null;
                onDeviceUnavailable = true;
            }
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            engine = ENGINE_CLOUD;
        }
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isStarting = false;
                // ここで開始音が鳴る。鳴り終わるまで黙らせてから戻す
                muteBeepWindow();
                scheduleBeepWindowEnd(BEEP_WINDOW_MS);
                notifyListeningState("started");
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                // 終了音が鳴るタイミング。再開後の開始音まで黙らせる
                muteBeepWindow();
            }

            @Override
            public void onError(int error) {
                isStarting = false;
                muteBeepWindow();

                // 端末内認識が日本語未対応・未ダウンロード等なら、クラウド認識へ切り替えて続行
                if (ENGINE_CONTINUOUS.equals(engine) && isOnDeviceUnusableError(error)) {
                    fallbackToCloud();
                    if (wantListening) {
                        scheduleRestart(restartDelayMs);
                    }
                    return;
                }

                JSObject err = new JSObject();
                err.put("code", error);
                err.put("message", errorText(error));
                notifyListeners("error", err);

                // 権限不足以外は監視継続中なら再開
                if (wantListening && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    scheduleRestart(restartDelayMs);
                } else {
                    restoreAudio();
                    notifyListeningState("stopped");
                }
            }

            /** 連続セッション: 区切りごとの確定結果（セッションは続く。効果音は鳴らない） */
            @Override
            public void onSegmentResults(Bundle segmentResults) {
                emitMatches(segmentResults, true);
            }

            /** 連続セッションの終了（上限時間など）。監視中なら再開 */
            @Override
            public void onEndOfSegmentedSession() {
                isStarting = false;
                muteBeepWindow();
                if (wantListening) {
                    scheduleRestart(restartDelayMs);
                } else {
                    restoreAudio();
                    notifyListeningState("stopped");
                }
            }

            @Override
            public void onResults(Bundle results) {
                isStarting = false;
                muteBeepWindow();
                emitMatches(results, true);
                if (wantListening) {
                    scheduleRestart(restartDelayMs);
                } else {
                    restoreAudio();
                    notifyListeningState("stopped");
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                emitMatches(partialResults, false);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void emitMatches(Bundle bundle, boolean isFinal) {
        if (bundle == null) {
            return;
        }
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        JSObject ret = new JSObject();
        ret.put("matches", new JSArray(matches));
        ret.put("isFinal", isFinal);
        notifyListeners(isFinal ? "results" : "partialResults", ret);
    }

    private void startListeningInternal() {
        if (!wantListening || isStarting) {
            return;
        }
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            wantListening = false;
            JSObject err = new JSObject();
            err.put("code", SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            err.put("message", "マイク権限がありません");
            notifyListeners("error", err);
            notifyListeningState("stopped");
            return;
        }
        try {
            ensureRecognizer();
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
            intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
            if (ENGINE_CONTINUOUS.equals(engine) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 連続セッション: 無音で区切って結果を確定しつつ、セッション自体は続ける（再開しない＝効果音なし）
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SEGMENT_SILENCE_MS);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SEGMENT_SILENCE_MS);
            } else {
                // クラウド認識: 無音判定を大きく延ばして再開（＝開始/終了音）の回数を減らす。判定は途中結果で行う
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, CLOUD_SILENCE_MS);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, CLOUD_SILENCE_MS);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, CLOUD_SILENCE_MS);
                intent.putExtra("android.speech.extra.DICTATION_MODE", true);
            }

            isStarting = true;
            // 開始前からミュート。onReadyForSpeech（開始音）後に戻す。来なければ保険時間で戻す
            muteBeepWindow();
            speechRecognizer.startListening(intent);
            scheduleBeepWindowEnd(BEEP_WINDOW_FALLBACK_MS);
        } catch (Exception e) {
            isStarting = false;
            unmuteBeepWindow();
            JSObject err = new JSObject();
            err.put("code", -1);
            err.put("message", e.getMessage() != null ? e.getMessage() : "startListening failed");
            notifyListeners("error", err);
            if (wantListening) {
                scheduleRestart(Math.max(800, restartDelayMs));
            }
        }
    }

    private void scheduleRestart(int delayMs) {
        mainHandler.removeCallbacks(restartRunnable);
        if (!wantListening) {
            return;
        }
        mainHandler.postDelayed(restartRunnable, delayMs);
    }

    private void notifyListeningState(String status) {
        JSObject ret = new JSObject();
        ret.put("status", status);
        ret.put("engine", engine);
        notifyListeners("listeningState", ret);
    }

    /** 端末内認識が使えない系のエラー（言語未対応・未ダウンロード・サポート確認不可） */
    private static boolean isOnDeviceUnusableError(int code) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        return code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                || code == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
                || code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED;
    }

    private static String errorText(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "マイクの録音エラー";
            case SpeechRecognizer.ERROR_CLIENT:
                return "音声認識クライアントエラー";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "マイク権限が不足しています";
            case SpeechRecognizer.ERROR_NETWORK:
                return "ネットワークエラー";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "ネットワークタイムアウト";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "一致なし";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "音声認識サービスがビジーです";
            case SpeechRecognizer.ERROR_SERVER:
                return "音声認識サーバーエラー";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "音声入力がありません";
            default:
                return "音声認識エラー: " + code;
        }
    }

    @Override
    protected void handleOnDestroy() {
        wantListening = false;
        mainHandler.removeCallbacks(restartRunnable);
        restoreAudio();
        try {
            if (speechRecognizer != null) {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        } catch (Exception ignored) {
            /* ignore */
        }
        super.handleOnDestroy();
    }

    @Override
    protected void handleOnPause() {
        // 他アプリに切り替えたときに音が消えたままにならないよう戻す
        restoreAudio();
        super.handleOnPause();
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (wantListening) {
            beginSessionMuteIfNeeded();
        }
    }
}
