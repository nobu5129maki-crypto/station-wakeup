package jp.stationwakeup.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // カスタムプラグインは super.onCreate より前に登録する（Capacitor 要件）
        registerPlugin(AlarmVibratorPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
