package jp.stationwakeup.app;

import android.app.Activity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "HomeScreen")
public class HomeScreenPlugin extends Plugin {

    private MainActivity mainActivity() {
        Activity a = getActivity();
        return a instanceof MainActivity ? (MainActivity) a : null;
    }

    private void fillStatus(JSObject ret, MainActivity activity) {
        ret.put("supported", activity != null && activity.isPinSupported());
        ret.put("pinned", activity != null && activity.isHomePinned());
        ret.put("asked", activity != null && activity.hasAskedHomePin());
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        MainActivity activity = mainActivity();
        JSObject ret = new JSObject();
        if (activity == null) {
            fillStatus(ret, null);
            call.resolve(ret);
            return;
        }
        activity.runOnUiThread(() -> {
            fillStatus(ret, activity);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void canPin(PluginCall call) {
        MainActivity activity = mainActivity();
        JSObject ret = new JSObject();
        ret.put("supported", activity != null && activity.isPinSupported());
        call.resolve(ret);
    }

    @PluginMethod
    public void pinToHome(PluginCall call) {
        MainActivity activity = mainActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        activity.runOnUiThread(() -> {
            JSObject ret = new JSObject();
            try {
                boolean supported = activity.isPinSupported();
                boolean requested = activity.requestHomeScreenPin();
                ret.put("requested", requested);
                ret.put("supported", supported);
                if (requested && supported) {
                    ret.put("message", "画面に「ホーム画面に追加」の確認が出ます。「追加」を押してください。");
                } else if (requested) {
                    ret.put("message", "ホーム画面に追加を依頼しました。出ない場合はアプリ一覧で Station WakeUp を長押し →「ホーム画面に追加」してください。");
                } else {
                    ret.put("message", "この端末では自動追加できません。アプリ一覧で Station WakeUp を長押し →「ホーム画面に追加」してください。");
                }
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Failed to request home pin: " + e.getMessage(), e);
            }
        });
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
