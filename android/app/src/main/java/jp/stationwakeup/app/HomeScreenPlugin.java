package jp.stationwakeup.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import androidx.core.content.pm.ShortcutManagerCompat;

@CapacitorPlugin(name = "HomeScreen")
public class HomeScreenPlugin extends Plugin {

    @PluginMethod
    public void canPin(PluginCall call) {
        JSObject ret = new JSObject();
        boolean supported = false;
        try {
            supported = ShortcutManagerCompat.isRequestPinShortcutSupported(getContext());
        } catch (Exception ignored) {
            supported = false;
        }
        ret.put("supported", supported);
        call.resolve(ret);
    }

    @PluginMethod
    public void pinToHome(PluginCall call) {
        try {
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null) {
                call.reject("Activity unavailable");
                return;
            }
            boolean requested = activity.requestHomeScreenPin(true);
            JSObject ret = new JSObject();
            ret.put("requested", requested);
            if (!requested) {
                ret.put("message", "この端末のホーム画面では自動追加に対応していません。アプリ一覧から Station WakeUp を長押ししてホームに追加してください。");
            } else {
                ret.put("message", "ホーム画面への追加確認が出たら「追加」をタップしてください。");
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to request home pin: " + e.getMessage(), e);
        }
    }
}
