package com.appgarage.dash;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Persistent display preferences, kept deliberately API-10 compatible. */
final class DashboardSettings {
    static final int LANG_EN = 0;
    static final int LANG_ZH_TW = 1;

    static final int SPEED_MPH = 0;
    static final int SPEED_KMH = 1;

    static final int PRESSURE_PSI = 0;
    static final int PRESSURE_KPA = 1;

    static final int POWER_KW = 0;
    static final int POWER_PS = 1;

    static final int TORQUE_NM = 0;
    static final int TORQUE_KGM = 1;

    static final int ENGINE_VR30DDTT = 0;
    static final int ENGINE_VQ35HR = 1;
    static final int ENGINE_VQ37VHR = 2;

    private static final String PREFS = "dashboard_settings";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_SPEED_UNIT = "speed_unit";
    private static final String KEY_PRESSURE_UNIT = "pressure_unit";
    private static final String KEY_POWER_UNIT = "power_unit";
    private static final String KEY_TORQUE_UNIT = "torque_unit";
    private static final String KEY_ENGINE_PROFILE = "engine_profile";

    private final SharedPreferences prefs;

    int language;
    int speedUnit;
    int pressureUnit;
    int powerUnit;
    int torqueUnit;
    int engineProfile;

    DashboardSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int defaultLanguage = Locale.getDefault().getLanguage().startsWith("zh")
                ? LANG_ZH_TW : LANG_EN;
        language = prefs.getInt(KEY_LANGUAGE, defaultLanguage);
        speedUnit = prefs.getInt(KEY_SPEED_UNIT, SPEED_MPH);
        pressureUnit = prefs.getInt(KEY_PRESSURE_UNIT, PRESSURE_PSI);
        powerUnit = prefs.getInt(KEY_POWER_UNIT, POWER_KW);
        torqueUnit = prefs.getInt(KEY_TORQUE_UNIT, TORQUE_NM);
        engineProfile = prefs.getInt(KEY_ENGINE_PROFILE, ENGINE_VR30DDTT);
    }

    void toggleLanguage() {
        language = language == LANG_EN ? LANG_ZH_TW : LANG_EN;
        prefs.edit().putInt(KEY_LANGUAGE, language).apply();
    }

    void toggleSpeedUnit() {
        speedUnit = speedUnit == SPEED_MPH ? SPEED_KMH : SPEED_MPH;
        prefs.edit().putInt(KEY_SPEED_UNIT, speedUnit).apply();
    }

    void togglePressureUnit() {
        pressureUnit = pressureUnit == PRESSURE_PSI ? PRESSURE_KPA : PRESSURE_PSI;
        prefs.edit().putInt(KEY_PRESSURE_UNIT, pressureUnit).apply();
    }

    void togglePowerUnit() {
        powerUnit = powerUnit == POWER_KW ? POWER_PS : POWER_KW;
        prefs.edit().putInt(KEY_POWER_UNIT, powerUnit).apply();
    }

    void toggleTorqueUnit() {
        torqueUnit = torqueUnit == TORQUE_NM ? TORQUE_KGM : TORQUE_NM;
        prefs.edit().putInt(KEY_TORQUE_UNIT, torqueUnit).apply();
    }

    void toggleEngineProfile() {
        if (engineProfile == ENGINE_VR30DDTT) engineProfile = ENGINE_VQ35HR;
        else if (engineProfile == ENGINE_VQ35HR) engineProfile = ENGINE_VQ37VHR;
        else engineProfile = ENGINE_VR30DDTT;
        prefs.edit().putInt(KEY_ENGINE_PROFILE, engineProfile).apply();
    }
}
