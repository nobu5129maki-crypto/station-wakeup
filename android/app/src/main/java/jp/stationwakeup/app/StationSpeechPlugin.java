package jp.stationwakeup.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
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
     * SpeechRecognizer は開始・終了ごとにシステム音（ピッ）を鳴らす。
     * 連続認識では数秒おきに鳴ってうるさいため、その音が出る音量経路を黙らせる。
     *  - 他アプリが音楽を再生していなければ、監視中ずっとミュート（確実）
     *  - 音楽再生中なら、開始/終了の前後だけ短くミュート（音楽をなるべく止めない）
     */
    private AudioManager audioManager;
    private boolean sessionMuted = false;
    private boolean windowMuted = false;
    private boolean useSessionMute = false;
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
        useSessionMute = !isMusicPlayingElsewhere();
        if (useSessionMute && !sessionMuted) {
            adjustStreams(AudioManager.ADJUST_MUTE);
            sessionMuted = true;
        }
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

        wantListening = true;
        mainHandler.post(() -> {
            ensureRecognizer();
            beginSessionMuteIfNeeded();
            startListeningInternal();
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("beepMuted", sessionMuted ? "session" : "window");
            call.resolve(ret);
        });
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
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
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
            // 無音判定を長めにして再開（＝開始/終了音）の回数を減らす。判定は途中結果で行う
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000);
            intent.putExtra("android.speech.extra.DICTATION_MODE", true);

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
        notifyListeners("listeningState", ret);
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
