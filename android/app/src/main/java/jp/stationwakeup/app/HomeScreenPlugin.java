package jp.stationwakeup.app;

import android.app.Activity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import androidx.core.content.pm.ShortcutManagerCompat;

@CapacitorPlugin(name = "HomeScreen")
public class HomeScreenPlugin extends Plugin {

    private MainActivity mainActivity() {
        Activity a = getActivity();
        return a instanceof MainActivity ? (MainActivity) a : null;
    }

    private boolean isSupported() {
        try {
            return ShortcutManagerCompat.isRequestPinShortcutSupported(getContext());
        } catch (Exception ignored) {
            return false;
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        MainActivity activity = mainActivity();
        JSObject ret = new JSObject();
        ret.put("supported", isSupported());
        ret.put("pinned", activity != null && activity.isHomePinned());
        ret.put("asked", activity != null && activity.hasAskedHomePin());
        call.resolve(ret);
    }

    @PluginMethod
    public void canPin(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("supported", isSupported());
        call.resolve(ret);
    }

    @PluginMethod
    public void pinToHome(PluginCall call) {
        try {
            MainActivity activity = mainActivity();
            if (activity == null) {
                call.reject("Activity unavailable");
                return;
            }
            final boolean[] requested = new boolean[1];
            activity.runOnUiThread(() -> {
                requested[0] = activity.requestHomeScreenPin();
                JSObject ret = new JSObject();
                ret.put("requested", requested[0]);
                if (!requested[0]) {
                    ret.put("message", "この端末では自動追加に対応していません。アプリ一覧で Station WakeUp を長押し →「ホーム画面に追加」を選んでください。");
                } else {
                    ret.put("message", "確認が出たら「追加」を押してください。ホーム画面に Station WakeUp が並びます。");
                }
                call.resolve(ret);
            });
        } catch (Exception e) {
            call.reject("Failed to request home pin: " + e.getMessage(), e);
        }
    }

    /** ユーザーが「もう追加した」と申告したときに保存する */
    @PluginMethod
    public void markPinned(PluginCall call) {
        MainActivity activity = mainActivity();
        if (activity != null) {
            activity.markHomePinned();
        }
        JSObject ret = new JSObject();
        ret.put("pinned", true);
        call.resolve(ret);
    }
}
