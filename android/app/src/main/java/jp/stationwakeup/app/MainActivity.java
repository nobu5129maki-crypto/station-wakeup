package jp.stationwakeup.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.getcapacitor.BridgeActivity;

/**
 * 公式アプリ本体。
 * インストール直後にホーム画面へアイコンが出ない端末向けに、
 * 初回起動でピン留め（ホーム追加）を案内する。
 */
public class MainActivity extends BridgeActivity {
    private static final String PREFS = "station_wakeup_prefs";
    private static final String KEY_ASKED_PIN = "asked_home_pin_v1";
    private static final String SHORTCUT_ID = "station_wakeup_home";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // カスタムプラグインは super.onCreate より前に登録する（Capacitor 要件）
        registerPlugin(AlarmVibratorPlugin.class);
        registerPlugin(StationSpeechPlugin.class);
        registerPlugin(HomeScreenPlugin.class);
        super.onCreate(savedInstanceState);

        // 初回のみ：ランチャーが対応していればホーム画面への追加を促す
        getWindow().getDecorView().post(this::maybeRequestHomePin);
    }

    private void maybeRequestHomePin() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (prefs.getBoolean(KEY_ASKED_PIN, false)) {
                return;
            }
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                return;
            }
            boolean requested = requestHomeScreenPin(false);
            if (requested) {
                prefs.edit().putBoolean(KEY_ASKED_PIN, true).apply();
            }
        } catch (Exception ignored) {
            // ランチャー差で失敗してもアプリ本体は使える
        }
    }

    /** @return true if a pin request was submitted to the launcher */
    boolean requestHomeScreenPin(boolean force) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            return false;
        }
        Intent launch = new Intent(this, MainActivity.class);
        launch.setAction(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(this, SHORTCUT_ID)
                .setShortLabel(getString(R.string.app_name))
                .setLongLabel(getString(R.string.app_name))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(launch)
                .build();

        Intent callback = new Intent(this, MainActivity.class);
        callback.setAction("jp.stationwakeup.app.PIN_SHORTCUT_RESULT");
        PendingIntent success = PendingIntent.getActivity(
                this,
                1001,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        boolean ok = ShortcutManagerCompat.requestPinShortcut(this, shortcut, success.getIntentSender());
        if (ok && force) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ASKED_PIN, true)
                    .apply();
        }
        return ok;
    }
}
