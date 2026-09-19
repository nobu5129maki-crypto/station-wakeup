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

import java.util.List;

/**
 * 公式アプリ本体。
 * インストール直後にホーム画面へアイコンが出ない端末向けに、
 * 前面に出たタイミングでホーム画面への追加を促し、実際に置かれたかも確認する。
 */
public class MainActivity extends BridgeActivity {
    static final String PREFS = "station_wakeup_prefs";
    static final String KEY_ASKED_PIN = "asked_home_pin_v2";
    static final String KEY_PINNED = "home_pinned_v1";
    static final String SHORTCUT_ID = "station_wakeup_home";
    private static final String ACTION_PIN_RESULT = "jp.stationwakeup.app.PIN_SHORTCUT_RESULT";
    private static final String LEGACY_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT";
    private static final long FIRST_PROMPT_DELAY_MS = 1600L;

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

    boolean hasAskedHomePin() {
        return prefs().getBoolean(KEY_ASKED_PIN, false);
    }

    void markHomePinned() {
        prefs().edit().putBoolean(KEY_PINNED, true).putBoolean(KEY_ASKED_PIN, true).apply();
    }

    /**
     * ランチャーに実際にピン留めされているかを確認する。
     * 確認できない環境では、追加成功コールバックの記録を使う。
     */
    boolean isHomePinned() {
        try {
            List<ShortcutInfoCompat> pinned =
                    ShortcutManagerCompat.getShortcuts(this, ShortcutManagerCompat.FLAG_MATCH_PINNED);
            for (ShortcutInfoCompat s : pinned) {
                if (SHORTCUT_ID.equals(s.getId())) {
                    prefs().edit().putBoolean(KEY_PINNED, true).apply();
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 一部ランチャーでは取得できない
        }
        return prefs().getBoolean(KEY_PINNED, false);
    }

    boolean isPinSupported() {
        try {
            return ShortcutManagerCompat.isRequestPinShortcutSupported(this);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void maybeRequestHomePin() {
        try {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (hasAskedHomePin() || isHomePinned()) {
                return;
            }
            if (!isPinSupported()) {
                return;
            }
            if (requestHomeScreenPin()) {
                prefs().edit().putBoolean(KEY_ASKED_PIN, true).apply();
            }
        } catch (Exception ignored) {
            // ランチャー差で失敗してもアプリ本体は使える
        }
    }

    private Intent buildLaunchIntent() {
        ComponentName component = new ComponentName(this, MainActivity.class);
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setComponent(component);
        launch.setPackage(getPackageName());
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return launch;
    }

    /** @return true if a pin request was submitted to the launcher */
    boolean requestHomeScreenPin() {
        Intent launch = buildLaunchIntent();

        if (!isPinSupported()) {
            return sendLegacyInstallShortcut(launch);
        }

        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(this, SHORTCUT_ID)
                .setShortLabel(getString(R.string.app_name))
                .setLongLabel(getString(R.string.app_name))
                .setActivity(new ComponentName(this, MainActivity.class))
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

        boolean ok = ShortcutManagerCompat.requestPinShortcut(this, shortcut, success.getIntentSender());
        if (!ok) {
            ok = sendLegacyInstallShortcut(launch);
        }
        return ok;
    }

    /** ピン留め非対応のランチャー向け（古い OEM ランチャーが今も受け付ける） */
    private boolean sendLegacyInstallShortcut(Intent launch) {
        try {
            Intent add = new Intent(LEGACY_INSTALL_SHORTCUT);
            add.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
            add.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.app_name));
            add.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher));
            add.putExtra("duplicate", false);
            sendBroadcast(add);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
