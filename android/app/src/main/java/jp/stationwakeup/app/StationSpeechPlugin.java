package jp.stationwakeup.app;

import android.Manifest;
import android.content.Intent;
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
            startListeningInternal();
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        wantListening = false;
        mainHandler.removeCallbacks(restartRunnable);
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.cancel();
                }
            } catch (Exception ignored) {
                /* ignore */
            }
            isStarting = false;
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
                // 最終結果待ち。onResults / onError で再開する
            }

            @Override
            public void onError(int error) {
                isStarting = false;
                JSObject err = new JSObject();
                err.put("code", error);
                err.put("message", errorText(error));
                notifyListeners("error", err);

                // 権限不足以外は監視継続中なら再開
                if (wantListening && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    scheduleRestart(restartDelayMs);
                } else {
                    notifyListeningState("stopped");
                }
            }

            @Override
            public void onResults(Bundle results) {
                isStarting = false;
                emitMatches(results, true);
                if (wantListening) {
                    scheduleRestart(restartDelayMs);
                } else {
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
            // 車内アナウンス向けに無音判定を少し長めに
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200);
            intent.putExtra("android.speech.extra.DICTATION_MODE", true);

            isStarting = true;
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            isStarting = false;
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
        // バックグラウンドでは継続が難しい端末が多いが、監視中は再開予約を残す
        super.handleOnPause();
    }
}
