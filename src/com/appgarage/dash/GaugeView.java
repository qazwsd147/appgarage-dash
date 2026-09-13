package com.appgarage.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vehicle gauge dashboard for the 800x480 InTouch head-unit screen. Reads RAW
 * VS_ID_* sensor values (set via setValue(type, raw)) and renders calibrated gauges.
 *
 * Calibration (locked from on-car data + the DrivingPerformance reference app):
 *   RPM(13) = rpm, direct.        COOLANT(14) / OILTEMP(15) = degC, direct.
 *   TPMS(36-39) = psi, direct.    TORQUE(12) ~ Nm.
 *   POWER(32) = rpm*Nm  -> kW  = raw * 1.047e-4.
 *   OILPRESS(16) = MPa  -> psi = raw * 145  (confirmed ~22 psi warm idle).
 *   SPEED(17) = km/h    -> mph = raw * 0.621.
 *   GEAR(22) enum: P/R/N/D = 1/2/3/4, manual M1..M7 = 16..22 (confirmed on-car).
 *   G lat(20)/long(21): raw, ~1g full-scale assumed (not yet calibrated).
 */
public class GaugeView extends View {

    // sensor types
    private static final int TORQUE=12, RPM=13, COOLANT=14, OILT=15, OILP=16, SPEED=17,
            GLAT=20, GLONG=21, GEAR=22, THROTTLE=23, POWER=32,
            TP_FR=36, TP_FL=37, TP_RR=38, TP_RL=39;

    // scaling constants (calibrated on-car). Oil-pressure raw is MPa: x145 -> psi, or x10 -> bar.
    public static float OILP_RAW_TO_PSI = 145.0377f;   // MPa -> psi (~22 psi warm idle)
    public static float POWER_RAW_TO_KW = 0.0001047f;  // raw = rpm*Nm -> kW
    public static float SPEED_RAW_TO_MPH = 0.621371f;  // km/h -> mph
    private static final float PSI_TO_KPA = 6.894757f;
    private static final float KW_TO_PS = 1.3596216f;
    private static final float NM_TO_KGM = 0.10197162f;
    private static final int SETTINGS_ROW_TOP = 108;
    private static final int SETTINGS_ROW_STEP = 46;

    // Leave room for unknown/custom sensor types discovered on non-VR30 InTouch firmware.
    private static final int N = 256;
    private final float[] v = new float[N];
    private final boolean[] have = new boolean[N];
    private final float[][] sensorValues = new float[N][8];
    private final int[] sensorValueCount = new int[N];
    private final float[] sensorMin = new float[N];
    private final float[] sensorMax = new float[N];
    private final String[] sensorNames = new String[N];
    private final String[] sensorVendors = new String[N];
    private final long[] sensorEventCount = new long[N];
    private final long[] sensorFirstUpdate = new long[N];
    private final long[] sensorLastUpdate = new long[N];
    private String status = "";
    private int liveSignalCount = -1;
    private boolean demoMode;
    private boolean settingsOpen;
    private boolean diagnosticsOpen;
    private boolean systemDiagnostics;
    private int diagnosticsPage;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF settingsButton = new RectF();
    private final RectF diagnosticsButton = new RectF();
    private final RectF settingsPanel = new RectF();
    private final RectF diagnosticsPanel = new RectF();
    private final RectF diagnosticsCloseButton = new RectF();
    private final RectF diagnosticsPrevButton = new RectF();
    private final RectF diagnosticsNextButton = new RectF();
    private final RectF diagnosticsModeButton = new RectF();
    private final DashboardSettings settings;
    private final DashboardStrings strings;
    private final SystemProbe systemProbe;

    // colors
    private static final int BG=0xFF0B0E12, PANEL=0xFF151B22, LABEL=0xFF7FB0FF,
            VAL=0xFFFFFFFF, DIM=0xFF6A7684, OK=0xFF37E07A, WARN=0xFFFFB020, DANGER=0xFFFF4040,
            ARC_BG=0xFF243040, ARC_FG=0xFF39C0FF;

    public GaugeView(Context c) {
        super(c);
        settings = new DashboardSettings(c);
        strings = new DashboardStrings(c);
        systemProbe = new SystemProbe(c);
        setBackgroundColor(BG);
        p.setTypeface(Typeface.MONOSPACE);
    }

    public void setSensorInfo(int t, String name, String vendor) {
        if (t>=0 && t<N) {
            sensorNames[t] = name == null ? "" : name;
            sensorVendors[t] = vendor == null ? "" : vendor;
        }
    }
    public void setValue(int t, float val) {
        setValues(t, new float[]{val});
    }
    public void setValues(int t, float[] values) {
        if (t>=0 && t<N) {
            float val=(values != null && values.length > 0) ? values[0] : 0f;
            long now=SystemClock.elapsedRealtime();
            v[t]=val;
            int count=values == null ? 0 : Math.min(values.length, sensorValues[t].length);
            sensorValueCount[t]=count;
            for (int i=0;i<count;i++) sensorValues[t][i]=values[i];
            have[t]=true;
            if (sensorEventCount[t] == 0) {
                sensorFirstUpdate[t]=now;
                sensorMin[t]=val;
                sensorMax[t]=val;
            } else {
                sensorMin[t]=Math.min(sensorMin[t],val);
                sensorMax[t]=Math.max(sensorMax[t],val);
            }
            sensorLastUpdate[t]=now;
            sensorEventCount[t]++;
        }
    }
    public void setStatus(String s) { status = s; liveSignalCount = -1; demoMode = false; }
    public void setLiveSignalCount(int count) { liveSignalCount = count; demoMode = false; status = ""; }
    private float g(int t) { return have[t] ? v[t] : 0f; }
    private boolean h(int t) { return have[t]; }

    /** seed representative values so the layout is visible on an emulator (no vehicle bus). */
    public void seedDemo() {
        int[] t = {TORQUE,RPM,COOLANT,OILT,OILP,SPEED,GLAT,GLONG,GEAR,THROTTLE,POWER,TP_FR,TP_FL,TP_RR,TP_RL};
        float[] val = {180f,3120f,92f,105f,0.42f,68f,0.35f,-0.20f,3f,42f,3120f*180f,38.5f,38.5f,37f,36.8f};
        for (int i=0;i<t.length;i++) setValue(t[i], val[i]);
        demoMode = true;
        liveSignalCount = -1;
        status = "";
    }

    @Override
    protected void onDraw(Canvas cv) {
        int W=getWidth(), H=getHeight();
        p.setTypeface(settings.language == DashboardSettings.LANG_ZH_TW
                ? Typeface.DEFAULT : Typeface.MONOSPACE);
        p.setColor(BG); cv.drawRect(0,0,W,H,p);

        // Compact RPM at upper-left; primary driving values occupy the upper-right row.
        drawRpm(cv, 92, 88, 66);
        drawBigNum(cv, 210, 26, tr(DashboardStrings.SPEED), fmt0(speed()), speedUnit(), h(SPEED));
        drawBigNum(cv, 370, 26, tr(DashboardStrings.GEAR), gearStr(), "", h(GEAR));
        drawBigNum(cv, 510, 26, tr(DashboardStrings.THROTTLE), fmt0(g(THROTTLE)), "%", h(THROTTLE));

        // Compact vertical temperature/pressure bars leave room for the G display.
        int barStart=W/2-142; // 284 px group width: centered regardless of the display width
        drawVerticalBar(cv, barStart, 150, 54, 108, tr(DashboardStrings.OIL_TEMP),
                g(OILT), 40,150,120,140,"°C",hasDisplayValue(OILT));
        drawVerticalBar(cv, barStart+115, 150, 54, 108, tr(DashboardStrings.COOLANT),
                g(COOLANT),40,130,110,120,"°C",h(COOLANT));
        drawVerticalBar(cv, barStart+230, 150, 54, 108, tr(DashboardStrings.OIL_PRESSURE),
                oilPressure(),0,pressureFromPsi(100),pressureFromPsi(90),pressureFromPsi(100),
                pressureUnit(),hasDisplayValue(OILP));

        // power + torque
        p.setTextSize(14f);
        p.setColor(LABEL); cv.drawText(tr(DashboardStrings.POWER), W-250, 316, p);
        p.setColor(hasDisplayValue(POWER)?VAL:DIM); p.setTextSize(24f);
        cv.drawText(hasDisplayValue(POWER)?fmt0(power())+" "+powerUnit():"--", W-180, 318, p);
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText(tr(DashboardStrings.TORQUE), W-250, 346, p);
        p.setColor(hasDisplayValue(TORQUE)?VAL:DIM); p.setTextSize(24f);
        cv.drawText(hasDisplayValue(TORQUE)?torqueText()+" "+torqueUnit():"--", W-180, 348, p);

        drawTpms(cv, 16, 300, 280);
        drawGball(cv, W-92, 88, 66);

        // status footer
        p.setColor(DIM); p.setTextSize(11f);
        cv.drawText(statusText(), 16, H-8, p);
        drawFooterButtons(cv, W, H);
        if (settingsOpen) drawSettingsPanel(cv, W, H);
        if (diagnosticsOpen) drawDiagnosticsPanel(cv, W, H);
    }

    private void drawRpm(Canvas cv, int cx, int cy, int r) {
        float val=g(RPM), red=redline(), max=red+700, start=135, sweep=270;
        oval.set(cx-r, cy-r, cx+r, cy+r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(7f,r*0.13f));
        p.setColor(ARC_BG); cv.drawArc(oval, start, sweep, false, p);
        // redline zone
        p.setColor(0x66FF4040); cv.drawArc(oval, start+sweep*(red/max), sweep*(1-red/max), false, p);
        // value arc
        float frac=Math.max(0,Math.min(1,val/max));
        p.setColor(val>=red?DANGER:ARC_FG); cv.drawArc(oval, start, sweep*frac, false, p);
        p.setStyle(Paint.Style.FILL);
        drawRpmTicks(cv,cx,cy,r,max,start,sweep);
        // digital center
        p.setColor(LABEL); p.setTextSize(11f); center(cv,tr(DashboardStrings.RPM),cx,cy-20);
        p.setColor(val>=red?DANGER:VAL); p.setTextSize(32f); center(cv, h(RPM)?fmt0(val):"--", cx, cy+10);
        p.setColor(DIM); p.setTextSize(10f);
        center(cv,tr(DashboardStrings.REDLINE)+" "+fmt0(red),cx,cy+r-4);
    }

    /**
     * A received value is not necessarily a usable measurement.  The VQ35HR Hybrid
     * firmware observed in-car reports negative placeholder values for several
     * fields that use the VR30 mapping.  Keep those raw values in CAN diagnostics,
     * but do not present them as real oil, power, or torque readings.
     */
    private boolean hasDisplayValue(int type) {
        if (!h(type)) return false;
        if (settings.engineProfile != DashboardSettings.ENGINE_VQ35HR) return true;
        switch (type) {
            case OILT:
                return g(type) > -45f && g(type) < 250f;
            case OILP:
                return g(type) >= 0f && g(type) < 2f;
            case POWER:
            case TORQUE:
                return g(type) >= 0f;
            default:
                return true;
        }
    }

    private void drawRpmTicks(Canvas cv,int cx,int cy,int r,float max,float start,float sweep) {
        int last=(int)(max/1000f);
        p.setStrokeWidth(1.5f);
        p.setTextSize(9f);
        for (int n=1;n<=last;n++) {
            float angle=start+sweep*(n*1000f/max);
            double radians=Math.toRadians(angle);
            float cos=(float)Math.cos(radians), sin=(float)Math.sin(radians);
            float inner=r-8, outer=r+5, labelRadius=r+14;
            p.setColor(DIM);
            cv.drawLine(cx+cos*inner,cy+sin*inner,cx+cos*outer,cy+sin*outer,p);
            String label=String.valueOf(n);
            float tx=cx+cos*labelRadius-p.measureText(label)/2f;
            float ty=cy+sin*labelRadius+3f;
            cv.drawText(label,tx,ty,p);
        }
    }

    private void drawVerticalBar(Canvas cv,int x,int y,int w,int h,String lab,float val,float min,float max,
                                 float warn,float red,String unit,boolean has){
        p.setColor(LABEL); p.setTextSize(12f); center(cv,lab,x+w/2,y-10);
        p.setColor(PANEL); cv.drawRect(x,y,x+w,y+h,p);
        float frac=Math.max(0,Math.min(1,(val-min)/(max-min)));
        int c = val>=red?DANGER : val>=warn?WARN : OK;
        if(has){ p.setColor(c); cv.drawRect(x,y+h*(1-frac),x+w,y+h,p); }
        p.setColor(has?VAL:DIM); p.setTextSize(14f);
        String s = has ? (fmt1(val)+" "+unit) : "--";
        center(cv,s,x+w/2,y+h+20);
    }

    private void drawBigNum(Canvas cv,int x,int y,String lab,String val,String unit,boolean has){
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText(lab, x, y-4, p);
        p.setColor(has?VAL:DIM); p.setTextSize(40f); cv.drawText(has?val:"--", x, y+34, p);
        if(has){ p.setColor(DIM); p.setTextSize(14f); cv.drawText(unit, x+2, y+50, p); }
    }

    private void drawTpms(Canvas cv,int x,int y,int w){
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText(tr(DashboardStrings.TPMS)+" ("+pressureUnit()+")", x, y-4, p);
        String[] lab={"FL","FR","RL","RR"}; int[] typ={TP_FL,TP_FR,TP_RL,TP_RR};
        int cw=w/2, ch=44;
        for(int i=0;i<4;i++){
            int cx=x+(i%2)*cw, cy=y+(i/2)*ch;
            p.setColor(DIM); p.setTextSize(13f); cv.drawText(lab[i], cx, cy+18, p);
            float rawPsi=g(typ[i]);
            float val=settings.pressureUnit == DashboardSettings.PRESSURE_KPA ? rawPsi*PSI_TO_KPA : rawPsi;
            int c = (have[typ[i]] && (rawPsi<30||rawPsi>42))?WARN:VAL;
            p.setColor(have[typ[i]]?c:DIM); p.setTextSize(24f);
            cv.drawText(have[typ[i]]?fmt1(val):"--", cx+34, cy+20, p);
        }
    }

    private void drawGball(Canvas cv,int cx,int cy,int r){
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(ARC_BG);
        cv.drawCircle(cx,cy,r,p); cv.drawCircle(cx,cy,r/2,p);
        cv.drawLine(cx-r,cy,cx+r,cy,p); cv.drawLine(cx,cy-r,cx,cy+r,p);
        p.setStyle(Paint.Style.FILL); p.setColor(LABEL); p.setTextSize(12f); center(cv,"G",cx,cy-r-4);
        // long accel = vertical (fwd up), lat = horizontal; assume ~1g full-scale
        float gx=Math.max(-1,Math.min(1,g(GLAT))), gy=Math.max(-1,Math.min(1,g(GLONG)));
        p.setColor(OK); cv.drawCircle(cx+gx*r, cy-gy*r, 6f, p);
    }

    private void center(Canvas cv,String s,int cx,int y){ cv.drawText(s, cx-p.measureText(s)/2, y, p); }
    private String fmt0(float f){ return String.valueOf(Math.round(f)); }
    private String fmt1(float f){ return String.valueOf(Math.round(f*10f)/10f); }

    private String tr(String key) { return strings.get(settings.language, key); }
    private float speed() {
        return settings.speedUnit == DashboardSettings.SPEED_KMH ? g(SPEED) : g(SPEED)*SPEED_RAW_TO_MPH;
    }
    private String speedUnit() {
        return settings.speedUnit == DashboardSettings.SPEED_KMH ? "km/h" : "mph";
    }
    private float oilPressure() {
        return settings.pressureUnit == DashboardSettings.PRESSURE_KPA
                ? g(OILP)*1000f : g(OILP)*OILP_RAW_TO_PSI;
    }
    private float pressureFromPsi(float psi) {
        return settings.pressureUnit == DashboardSettings.PRESSURE_KPA ? psi*PSI_TO_KPA : psi;
    }
    private String pressureUnit() {
        return settings.pressureUnit == DashboardSettings.PRESSURE_KPA ? "kPa" : "psi";
    }
    private float power() {
        float kw = g(POWER)*POWER_RAW_TO_KW;
        return settings.powerUnit == DashboardSettings.POWER_PS ? kw*KW_TO_PS : kw;
    }
    private String powerUnit() {
        return settings.powerUnit == DashboardSettings.POWER_PS ? "PS" : "kW";
    }
    private float torque() {
        return settings.torqueUnit == DashboardSettings.TORQUE_KGM ? g(TORQUE)*NM_TO_KGM : g(TORQUE);
    }
    private String torqueText() {
        return settings.torqueUnit == DashboardSettings.TORQUE_KGM ? fmt1(torque()) : fmt0(torque());
    }
    private String torqueUnit() {
        return settings.torqueUnit == DashboardSettings.TORQUE_KGM ? "kgm" : "Nm";
    }
    private float redline() {
        if (settings.engineProfile == DashboardSettings.ENGINE_VQ37VHR) return 7500f;
        if (settings.engineProfile == DashboardSettings.ENGINE_VQ35HR) return 7000f;
        return 6800f;
    }
    private String engineProfileName() {
        if (settings.engineProfile == DashboardSettings.ENGINE_VQ37VHR) return "VQ37VHR";
        if (settings.engineProfile == DashboardSettings.ENGINE_VQ35HR) return "VQ35HR";
        return "VR30DDTT";
    }
    private String statusText() {
        if (demoMode) return tr(DashboardStrings.DEMO_STATUS);
        if (liveSignalCount >= 0) {
            return settings.language == DashboardSettings.LANG_ZH_TW
                    ? liveSignalCount+" 個 CAN 訊號即時連線"
                    : liveSignalCount+" CAN signals live";
        }
        return status;
    }

    private void drawFooterButtons(Canvas cv, int w, int h) {
        diagnosticsButton.set(w/2f-120, h-34, w/2f-8, h-7);
        settingsButton.set(w/2f+8, h-34, w/2f+120, h-7);
        p.setColor(PANEL); cv.drawRoundRect(diagnosticsButton, 5, 5, p);
        p.setColor(PANEL); cv.drawRoundRect(settingsButton, 5, 5, p);
        p.setColor(LABEL); p.setTextSize(13f);
        center(cv, "CAN", w/2-64, h-15);
        center(cv, tr(DashboardStrings.SETTINGS), w/2+64, h-15);
    }

    private void drawSettingsPanel(Canvas cv, int w, int h) {
        p.setColor(0xCC000000); cv.drawRect(0, 0, w, h, p);
        settingsPanel.set(90, 38, w-90, h-38);
        p.setColor(PANEL); cv.drawRoundRect(settingsPanel, 10, 10, p);

        p.setColor(VAL); p.setTextSize(28f);
        cv.drawText(tr(DashboardStrings.SETTINGS), 118, 78, p);
        p.setColor(DIM); p.setTextSize(13f);
        cv.drawText(tr(DashboardStrings.TAP_TO_CHANGE), 118, 101, p);

        int top = SETTINGS_ROW_TOP;
        drawSettingRow(cv, top, tr(DashboardStrings.ENGINE_PROFILE), engineProfileName());
        drawSettingRow(cv, top+SETTINGS_ROW_STEP, tr(DashboardStrings.LANGUAGE),
                settings.language == DashboardSettings.LANG_ZH_TW
                        ? tr(DashboardStrings.TRADITIONAL_CHINESE) : tr(DashboardStrings.ENGLISH));
        drawSettingRow(cv, top+SETTINGS_ROW_STEP*2, tr(DashboardStrings.SPEED_UNIT), speedUnit());
        drawSettingRow(cv, top+SETTINGS_ROW_STEP*3, tr(DashboardStrings.PRESSURE_UNIT), pressureUnit());
        drawSettingRow(cv, top+SETTINGS_ROW_STEP*4, tr(DashboardStrings.POWER_UNIT), powerUnit());
        drawSettingRow(cv, top+SETTINGS_ROW_STEP*5, tr(DashboardStrings.TORQUE_UNIT), torqueUnit());

        // Keep DONE in the header. Android 2.3's system/status bars reduce the Activity height,
        // so a bottom-anchored button can overlap the fifth settings row on an 800x480 display.
        settingsButton.set(w-250, 53, w-112, 91);
        p.setColor(ARC_FG); cv.drawRoundRect(settingsButton, 6, 6, p);
        p.setColor(BG); p.setTextSize(16f);
        center(cv, tr(DashboardStrings.DONE), w-181, 78);
    }

    private void drawSettingRow(Canvas cv, int y, String label, String value) {
        p.setColor(0xFF1D2732); cv.drawRect(112, y, getWidth()-112, y+38, p);
        p.setColor(LABEL); p.setTextSize(15f); cv.drawText(label, 130, y+25, p);
        p.setColor(VAL); p.setTextSize(18f);
        cv.drawText(value, getWidth()-130-p.measureText(value), y+25, p);
    }

    private void drawDiagnosticsPanel(Canvas cv, int w, int h) {
        long now=SystemClock.elapsedRealtime();
        p.setColor(0xDD000000); cv.drawRect(0, 0, w, h, p);
        diagnosticsPanel.set(34, 18, w-34, h-18);
        p.setColor(PANEL); cv.drawRoundRect(diagnosticsPanel, 10, 10, p);

        p.setColor(VAL); p.setTextSize(24f);
        cv.drawText(systemDiagnostics ? "SYSTEM PROBE" : tr(DashboardStrings.CAN_DIAGNOSTICS), 54, 51, p);

        diagnosticsModeButton.set(w-286, 28, w-174, 62);
        p.setColor(ARC_BG); cv.drawRoundRect(diagnosticsModeButton, 6, 6, p);
        p.setColor(VAL); p.setTextSize(12f);
        center(cv, systemDiagnostics ? "SENSORS" : "SYSTEM", w-230, 50);

        diagnosticsCloseButton.set(w-166, 28, w-54, 62);
        p.setColor(ARC_FG); cv.drawRoundRect(diagnosticsCloseButton, 6, 6, p);
        p.setColor(BG); p.setTextSize(14f);
        center(cv, tr(DashboardStrings.DONE), w-110, 50);

        if (systemDiagnostics) {
            drawSystemDiagnostics(cv,w,h);
            return;
        }

        p.setColor(DIM); p.setTextSize(11f);
        cv.drawText(tr(DashboardStrings.FOUND)+": "+knownSensorCount()
                +"   "+tr(DashboardStrings.UNKNOWN)+": "+unknownSensorCount()
                +"   "+tr(DashboardStrings.LIVE)+": "+liveSensorCount(now), 54, 70, p);
        p.setColor(WARN); p.setTextSize(10f);
        cv.drawText(settings.engineProfile == DashboardSettings.ENGINE_VR30DDTT
                ? "VR30DDTT" : tr(DashboardStrings.NON_VR30_RAW_HINT), 54, 88, p);

        int[] col={54,100,390,500,590};
        p.setColor(LABEL); p.setTextSize(11f);
        cv.drawText("TYPE", col[0], 108, p);
        cv.drawText(tr(DashboardStrings.SENSOR), col[1], 108, p);
        cv.drawText(tr(DashboardStrings.RAW_VALUE), col[2], 108, p);
        cv.drawText(tr(DashboardStrings.RATE)+" Hz", col[3], 108, p);
        cv.drawText(tr(DashboardStrings.STATE), col[4], 108, p);

        int total=knownSensorCount();
        int pageCount=Math.max(1, (total+6)/7);
        if (diagnosticsPage>=pageCount) diagnosticsPage=pageCount-1;
        int skip=diagnosticsPage*7, shown=0, seen=0;
        for (int t=0;t<N && shown<7;t++) {
            if (!isKnownSensor(t)) continue;
            if (seen++<skip) continue;
            int y=116+shown*38;
            p.setColor((shown&1)==0 ? 0xFF1D2732 : 0xFF19222C);
            cv.drawRect(48, y, w-48, y+34, p);

            p.setColor(VAL); p.setTextSize(13f); cv.drawText(String.valueOf(t), col[0], y+21, p);
            String name=sensorDisplayName(t);
            p.setColor(VAL); p.setTextSize(12f); cv.drawText(ellipsize(name, 270), col[1], y+14, p);
            String vendor=sensorVendors[t] == null ? "" : sensorVendors[t];
            String mapState=isMappedSensor(t)
                    ? tr(DashboardStrings.VR30_MAP) : tr(DashboardStrings.UNKNOWN);
            if (vendor.length()>0) vendor=vendor+" / "+mapState;
            else vendor=mapState;
            p.setColor(DIM); p.setTextSize(9f); cv.drawText(ellipsize(vendor, 270), col[1], y+28, p);

            p.setColor(have[t] ? VAL : DIM); p.setTextSize(9f);
            cv.drawText(have[t] ? ellipsize(sensorVector(t),100) : "--", col[2], y+14, p);
            p.setColor(DIM); p.setTextSize(8f);
            cv.drawText(have[t] ? fmtRaw(sensorMin[t])+".."+fmtRaw(sensorMax[t]) : "", col[2], y+28, p);
            p.setColor(have[t] ? VAL : DIM); p.setTextSize(12f);
            cv.drawText(sensorRate(t), col[3], y+21, p);

            String state;
            int stateColor;
            if (demoMode && have[t]) {
                state=tr(DashboardStrings.DEMO); stateColor=LABEL;
            } else if (!have[t]) {
                state=tr(DashboardStrings.NO_DATA); stateColor=DIM;
            } else if (now-sensorLastUpdate[t]<=2500) {
                state=tr(DashboardStrings.LIVE); stateColor=OK;
            } else {
                state=tr(DashboardStrings.STALE); stateColor=WARN;
            }
            p.setColor(stateColor); p.setTextSize(12f); cv.drawText(state, col[4], y+21, p);
            shown++;
        }

        diagnosticsPrevButton.set(54, h-54, 154, h-25);
        diagnosticsNextButton.set(w-154, h-54, w-54, h-25);
        p.setColor(diagnosticsPage>0 ? ARC_BG : PANEL); cv.drawRoundRect(diagnosticsPrevButton, 5, 5, p);
        p.setColor(diagnosticsPage<pageCount-1 ? ARC_BG : PANEL); cv.drawRoundRect(diagnosticsNextButton, 5, 5, p);
        p.setColor(VAL); p.setTextSize(12f);
        center(cv, tr(DashboardStrings.PREVIOUS), 104, h-35);
        center(cv, tr(DashboardStrings.NEXT), w-104, h-35);
        p.setColor(DIM);
        center(cv, tr(DashboardStrings.PAGE)+" "+(diagnosticsPage+1)+" / "+pageCount, w/2, h-35);
    }

    private void drawSystemDiagnostics(Canvas cv,int w,int h) {
        systemProbe.collectIfNeeded();
        p.setColor(DIM); p.setTextSize(10f);
        cv.drawText("Read-only Binder, package and component inventory",54,76,p);
        int[] col={54,110,315};
        p.setColor(LABEL); p.setTextSize(11f);
        cv.drawText("KIND",col[0],108,p);
        cv.drawText("NAME",col[1],108,p);
        cv.drawText("DETAIL",col[2],108,p);
        int total=systemProbe.size(), perPage=7;
        int pageCount=Math.max(1,(total+perPage-1)/perPage);
        if (diagnosticsPage>=pageCount) diagnosticsPage=pageCount-1;
        int start=diagnosticsPage*perPage;
        for (int i=0;i<perPage && start+i<total;i++) {
            SystemProbe.Entry e=systemProbe.get(start+i);
            int y=116+i*38;
            p.setColor((i&1)==0 ? 0xFF1D2732 : 0xFF19222C); cv.drawRect(48,y,w-48,y+34,p);
            p.setColor(e.relevant?WARN:VAL); p.setTextSize(10f); cv.drawText(e.kind,col[0],y+20,p);
            p.setColor(e.relevant?WARN:VAL); p.setTextSize(10f); cv.drawText(ellipsize(e.name,195),col[1],y+14,p);
            p.setColor(DIM); p.setTextSize(8f); cv.drawText(e.relevant?"keyword match":"",col[1],y+28,p);
            p.setColor(VAL); p.setTextSize(9f); cv.drawText(ellipsize(e.detail,w-col[2]-55),col[2],y+20,p);
        }
        diagnosticsPrevButton.set(54,h-54,154,h-25);
        diagnosticsNextButton.set(w-154,h-54,w-54,h-25);
        p.setColor(diagnosticsPage>0?ARC_BG:PANEL); cv.drawRoundRect(diagnosticsPrevButton,5,5,p);
        p.setColor(diagnosticsPage<pageCount-1?ARC_BG:PANEL); cv.drawRoundRect(diagnosticsNextButton,5,5,p);
        p.setColor(VAL); p.setTextSize(12f);
        center(cv,tr(DashboardStrings.PREVIOUS),104,h-35);
        center(cv,tr(DashboardStrings.NEXT),w-104,h-35);
        p.setColor(DIM); center(cv,tr(DashboardStrings.PAGE)+" "+(diagnosticsPage+1)+" / "+pageCount,w/2,h-35);
    }

    private boolean isKnownSensor(int t) { return sensorNames[t]!=null || have[t]; }
    private int knownSensorCount() {
        int count=0;
        for (int t=0;t<N;t++) if (isKnownSensor(t)) count++;
        return count;
    }
    private int unknownSensorCount() {
        int count=0;
        for (int t=0;t<N;t++) if (isKnownSensor(t) && !isMappedSensor(t)) count++;
        return count;
    }
    private boolean isMappedSensor(int t) {
        switch(t) {
            case TORQUE: case RPM: case COOLANT: case OILT: case OILP: case SPEED:
            case GLAT: case GLONG: case GEAR: case THROTTLE: case POWER:
            case TP_FR: case TP_FL: case TP_RR: case TP_RL:
                return true;
            default:
                return false;
        }
    }
    private int liveSensorCount(long now) {
        int count=0;
        if (demoMode) return 0;
        for (int t=0;t<N;t++) {
            if (isKnownSensor(t) && have[t] && now-sensorLastUpdate[t]<=2500) count++;
        }
        return count;
    }
    private String sensorDisplayName(int t) {
        if (sensorNames[t]!=null && sensorNames[t].length()>0) return sensorNames[t];
        switch(t) {
            case TORQUE: return tr(DashboardStrings.TORQUE);
            case RPM: return tr(DashboardStrings.RPM);
            case COOLANT: return tr(DashboardStrings.COOLANT);
            case OILT: return tr(DashboardStrings.OIL_TEMP);
            case OILP: return tr(DashboardStrings.OIL_PRESSURE);
            case SPEED: return tr(DashboardStrings.SPEED);
            case GLAT: return "G LAT";
            case GLONG: return "G LONG";
            case GEAR: return tr(DashboardStrings.GEAR);
            case THROTTLE: return tr(DashboardStrings.THROTTLE);
            case POWER: return tr(DashboardStrings.POWER);
            case TP_FR: return "TPMS FR";
            case TP_FL: return "TPMS FL";
            case TP_RR: return "TPMS RR";
            case TP_RL: return "TPMS RL";
            default: return "Sensor "+t;
        }
    }
    private String sensorRate(int t) {
        if (sensorEventCount[t]<2 || sensorLastUpdate[t]<=sensorFirstUpdate[t]) return "--";
        float hz=(sensorEventCount[t]-1)*1000f/(sensorLastUpdate[t]-sensorFirstUpdate[t]);
        return fmt1(hz);
    }
    private String fmtRaw(float value) { return String.valueOf(Math.round(value*1000f)/1000f); }
    private String sensorVector(int t) {
        int count=Math.min(sensorValueCount[t],4);
        if (count<=0) return fmtRaw(v[t]);
        String s="";
        for (int i=0;i<count;i++) {
            if (i>0) s=s+"/";
            s=s+fmtRaw(sensorValues[t][i]);
        }
        return s;
    }
    private String ellipsize(String value, float maxWidth) {
        if (value==null) return "";
        if (p.measureText(value)<=maxWidth) return value;
        String suffix="...";
        int end=value.length();
        while (end>0 && p.measureText(value.substring(0,end)+suffix)>maxWidth) end--;
        return value.substring(0,end)+suffix;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        float x=event.getX(), y=event.getY();
        if (diagnosticsOpen) {
            int total=systemDiagnostics ? systemProbe.size() : knownSensorCount();
            int pageCount=Math.max(1, (total+6)/7);
            if (diagnosticsCloseButton.contains(x,y) || !diagnosticsPanel.contains(x,y)) {
                diagnosticsOpen=false;
            } else if (diagnosticsModeButton.contains(x,y)) {
                systemDiagnostics=!systemDiagnostics;
                diagnosticsPage=0;
            } else if (diagnosticsPrevButton.contains(x,y) && diagnosticsPage>0) {
                diagnosticsPage--;
            } else if (diagnosticsNextButton.contains(x,y) && diagnosticsPage<pageCount-1) {
                diagnosticsPage++;
            }
            invalidate();
            return true;
        }
        if (!settingsOpen) {
            if (diagnosticsButton.contains(x,y)) {
                diagnosticsOpen=true;
                diagnosticsPage=0;
                invalidate();
            } else if (settingsButton.contains(x, y)) {
                settingsOpen=true;
                invalidate();
            }
            return true;
        }

        if (settingsButton.contains(x, y) || !settingsPanel.contains(x, y)) {
            settingsOpen=false;
        } else if (y>=SETTINGS_ROW_TOP && y<SETTINGS_ROW_TOP+SETTINGS_ROW_STEP*6) {
            int row=(int)((y-SETTINGS_ROW_TOP)/SETTINGS_ROW_STEP);
            if (row==0) settings.toggleEngineProfile();
            else if (row==1) settings.toggleLanguage();
            else if (row==2) settings.toggleSpeedUnit();
            else if (row==3) settings.togglePressureUnit();
            else if (row==4) settings.togglePowerUnit();
            else settings.toggleTorqueUnit();
        }
        invalidate();
        return true;
    }
    // GEAR_POSITION enum (confirmed on-car): P/R/N/D = 1/2/3/4; manual mode M1..M7 = 16..22.
    private String gearStr(){
        if(!h(GEAR)) return "--";
        int gg=Math.round(g(GEAR));
        switch(gg){
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return (gg>=16 && gg<=22) ? "M"+(gg-15) : String.valueOf(gg);
        }
    }
}
