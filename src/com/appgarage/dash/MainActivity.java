package com.appgarage.dash;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.WindowManager;

import java.util.List;

/**
 * AppGarage Dash 是供 Infiniti InTouch 車機使用的免轉接器車輛儀表板
 * （適用於搭載 Android 2.3.x 版 InTouch 的 V37 Q50／Q60）。車機會將車輛的 CAN 訊號
 * （轉速、機油溫度／壓力、冷卻液溫度、速度、G 值、檔位、油門、功率、胎壓等）
 * 以標準 Android Sensor 形式公開（VS_ID_*、廠商為「Ygomi」），類型編號為 12–53。
 * 本程式會為每個感測器註冊監聽器，並將即時值交給 GaugeView 繪製經校正的儀表。
 * 程式使用純 Java、不含原生程式庫，minSdk 為 10，可在 x86 API 10 車機上執行。
 */
public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sm;
    private GaugeView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GaugeView(this);
        setContentView(view);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    private void registerAll() {
        if (sm == null) { view.setStatus("SENSOR_SERVICE = null"); return; }
        int n = 0; boolean hasVehicleBus = false;
        try {
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            if (all != null) for (Sensor s : all) {
                view.setSensorInfo(s.getType(), s.getName(), s.getVendor());
                boolean vehicleSensor = isVehicleSensor(s);
                if (vehicleSensor) hasVehicleBus = true;
                try { if (sm.registerListener(this, s, 200000) && vehicleSensor) n++; } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { view.setStatus("getSensorList error: " + t); }
        if (!hasVehicleBus) view.seedDemo();                          // 模擬器／沒有 CAN 時顯示示範版面
        else view.setLiveSignalCount(n);
        view.invalidate();
    }

    private boolean isVehicleSensor(Sensor sensor) {
        String name = sensor.getName() == null ? "" : sensor.getName().toUpperCase();
        String vendor = sensor.getVendor() == null ? "" : sensor.getVendor().toUpperCase();
        int type = sensor.getType();
        return vendor.indexOf("YGOMI") >= 0 || name.indexOf("VS_ID") >= 0
                || (type >= 12 && type <= 53);
    }

    @Override protected void onResume() { super.onResume(); registerAll(); }
    @Override protected void onPause()  { super.onPause(); try { sm.unregisterListener(this); } catch (Throwable ignored) {} }

    @Override
    public void onSensorChanged(SensorEvent e) {
        try {
            view.setValues(e.sensor.getType(), e.values);
            view.invalidate();
        } catch (Throwable ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
