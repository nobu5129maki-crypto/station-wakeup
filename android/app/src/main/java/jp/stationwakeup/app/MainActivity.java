package jp.stationwakeup.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.getcapacitor.BridgeActivity;

/**
 * 公式アプリ本体。
 * インストール直後にホーム画面へアイコンが出ない端末向けに、
 * 初回起動が前面に出たタイミングでホーム画面への追加を促す。
 */
public class MainActivity extends BridgeActivity {
    static final String PREFS = "station_wakeup_prefs";
    static final String KEY_ASKED_PIN = "asked_home_pin_v2";
    static final String KEY_PINNED = "home_pinned_v1";
    private static final String SHORTCUT_ID = "station_wakeup_home";
    private static final String ACTION_PIN_RESULT = "jp.stationwakeup.app.PIN_SHORTCUT_RESULT";
    private static final long FIRST_PROMPT_DELAY_MS = 1400L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean promptScheduled = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // カスタムプラグインは super.onCreate より前に登録する（Capacitor 要件）
        registerPlugin(AlarmVibratorPlugin.class);
        registerPlugin(StationSpeechPlugin.class);
        registerPlugin(HomeScreenPlugin.class);
        super.onCreate(savedInstanceState);
        handlePinResultIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handlePinResultIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        // requestPinShortcut は前面 Activity が必要なので、描画後に一度だけ促す
        if (!promptScheduled) {
            promptScheduled = true;
            mainHandler.postDelayed(this::maybeRequestHomePin, FIRST_PROMPT_DELAY_MS);
        }
    }

    private void handlePinResultIntent(Intent intent) {
        if (intent == null || !ACTION_PIN_RESULT.equals(intent.getAction())) {
            return;
        }
        prefs().edit().putBoolean(KEY_PINNED, true).apply();
    }

    SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    boolean isHomePinned() {
        return prefs().getBoolean(KEY_PINNED, false);
    }

    boolean hasAskedHomePin() {
        return prefs().getBoolean(KEY_ASKED_PIN, false);
    }

    void markHomePinned() {
        prefs().edit().putBoolean(KEY_PINNED, true).putBoolean(KEY_ASKED_PIN, true).apply();
    }

    private void maybeRequestHomePin() {
        try {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (isHomePinned() || hasAskedHomePin()) {
                return;
            }
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                return;
            }
            boolean requested = requestHomeScreenPin();
            if (requested) {
                prefs().edit().putBoolean(KEY_ASKED_PIN, true).apply();
            }
        } catch (Exception ignored) {
            // ランチャー差で失敗してもアプリ本体は使える
        }
    }

    /** @return true if a pin request was submitted to the launcher */
    boolean requestHomeScreenPin() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            return false;
        }
        ComponentName component = new ComponentName(this, MainActivity.class);
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setComponent(component);
        launch.setPackage(getPackageName());
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(this, SHORTCUT_ID)
                .setShortLabel(getString(R.string.app_name))
                .setLongLabel(getString(R.string.app_name))
                .setActivity(component)
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(launch)
                .build();

        Intent callback = new Intent(this, MainActivity.class);
        callback.setAction(ACTION_PIN_RESULT);
        PendingIntent success = PendingIntent.getActivity(
                this,
                1001,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return ShortcutManagerCompat.requestPinShortcut(this, shortcut, success.getIntentSender());
    }
}
