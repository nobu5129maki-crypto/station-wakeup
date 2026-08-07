package jp.stationwakeup.app;

import android.content.Context;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * アラーム用途の本体バイブ。Android Vibrator API を直接使い、
 * 可能なら USAGE_ALARM 属性で確実に振動させる。
 */
@CapacitorPlugin(name = "AlarmVibrator")
public class AlarmVibratorPlugin extends Plugin {

    private Vibrator vibrator;

    private Vibrator getVibrator() {
        if (vibrator != null) {
            return vibrator;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager =
                (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) {
                vibrator = manager.getDefaultVibrator();
            }
        } else {
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }
        return vibrator;
    }

    private void cancelInternal() {
        Vibrator v = getVibrator();
        if (v != null) {
            try {
                v.cancel();
            } catch (Exception ignored) {
                /* ignore */
            }
        }
    }

    private void vibrateWaveform(long[] timings, int[] amplitudes, boolean repeat) {
        Vibrator v = getVibrator();
        if (v == null || !v.hasVibrator()) {
            return;
        }
        int repeatIndex = repeat ? 0 : -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect;
            if (amplitudes != null && amplitudes.length == timings.length) {
                effect = VibrationEffect.createWaveform(timings, amplitudes, repeatIndex);
            } else {
                effect = VibrationEffect.createWaveform(timings, repeatIndex);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationAttributes attrs = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build();
                v.vibrate(effect, attrs);
            } else {
                v.vibrate(effect);
            }
        } else {
            v.vibrate(timings, repeatIndex);
        }
    }

    private void vibrateOneShot(long durationMs) {
        Vibrator v = getVibrator();
        if (v == null || !v.hasVibrator()) {
            return;
        }
        long ms = Math.max(1L, Math.min(durationMs, 5000L));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect =
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationAttributes attrs = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build();
                v.vibrate(effect, attrs);
            } else {
                v.vibrate(effect);
            }
        } else {
            v.vibrate(ms);
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        try {
            cancelInternal();
            // [待ち, 振動, 待ち, 振動, ...] を繰り返すアラームパターン
            long[] timings = new long[] { 0, 420, 110, 420, 110, 420, 180, 650, 280 };
            int[] amplitudes = new int[] { 0, 255, 0, 255, 0, 255, 0, 255, 0 };
            vibrateWaveform(timings, amplitudes, true);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("AlarmVibrator.start failed: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void pulse(PluginCall call) {
        try {
            Integer duration = call.getInt("duration", 500);
            cancelInternal();
            vibrateOneShot(duration != null ? duration.longValue() : 500L);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("AlarmVibrator.pulse failed: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            cancelInternal();
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("AlarmVibrator.stop failed: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        Vibrator v = getVibrator();
        boolean ok = v != null && v.hasVibrator();
        JSObject ret = new JSObject();
        ret.put("available", ok);
        call.resolve(ret);
    }

    @Override
    protected void handleOnDestroy() {
        cancelInternal();
        super.handleOnDestroy();
    }
}
