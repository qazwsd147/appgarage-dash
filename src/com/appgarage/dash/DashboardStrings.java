package com.appgarage.dash;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Loads the UI translations from assets/i18n, with English and key fallbacks. */
final class DashboardStrings {
    static final String RPM = "rpm";
    static final String REDLINE = "redline";
    static final String OIL_TEMP = "oil_temp";
    static final String COOLANT = "coolant";
    static final String OIL_PRESSURE = "oil_pressure";
    static final String SPEED = "speed";
    static final String GEAR = "gear";
    static final String THROTTLE = "throttle";
    static final String POWER = "power";
    static final String TORQUE = "torque";
    static final String TPMS = "tpms";
    static final String SETTINGS = "settings";
    static final String LANGUAGE = "language";
    static final String SPEED_UNIT = "speed_unit";
    static final String PRESSURE_UNIT = "pressure_unit";
    static final String POWER_UNIT = "power_unit";
    static final String TORQUE_UNIT = "torque_unit";
    static final String DONE = "done";
    static final String TAP_TO_CHANGE = "tap_to_change";
    static final String ENGLISH = "english";
    static final String TRADITIONAL_CHINESE = "traditional_chinese";
    static final String DEMO_STATUS = "demo_status";
    static final String CAN_DIAGNOSTICS = "can_diagnostics";
    static final String SENSOR = "sensor";
    static final String RAW_VALUE = "raw_value";
    static final String RATE = "rate";
    static final String STATE = "state";
    static final String LIVE = "live";
    static final String STALE = "stale";
    static final String NO_DATA = "no_data";
    static final String PREVIOUS = "previous";
    static final String NEXT = "next";
    static final String PAGE = "page";
    static final String FOUND = "found";
    static final String DEMO = "demo";
    static final String UNKNOWN = "unknown";
    static final String VR30_MAP = "vr30_map";
    static final String NON_VR30_RAW_HINT = "non_vr30_raw_hint";
    static final String ENGINE_PROFILE = "engine_profile";

    private final JSONObject english;
    private final JSONObject traditionalChinese;

    DashboardStrings(Context context) {
        english = load(context, "i18n/en.json");
        traditionalChinese = load(context, "i18n/zh-TW.json");
    }

    String get(int language, String key) {
        JSONObject selected = language == DashboardSettings.LANG_ZH_TW
                ? traditionalChinese : english;
        String value = selected.optString(key, "");
        if (value.length() == 0) value = english.optString(key, "");
        return value.length() == 0 ? key : value;
    }

    private static JSONObject load(Context context, String path) {
        InputStream input = null;
        try {
            input = context.getAssets().open(path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), "UTF-8"));
        } catch (Throwable ignored) {
            return new JSONObject();
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignored) {}
        }
    }
}
