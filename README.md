# AppGarage Dash

<img src="docs/icon.png" width="88" align="right" alt="AppGarage Dash icon" />

A **dongle-free vehicle gauge dashboard** that runs on the factory infotainment screen of the
**Infiniti InTouch** head unit in the **V37 Q50 / Q60** — reading the car's own CAN-bus signals
(RPM, oil temp, **oil pressure**, coolant, per-wheel TPMS, G-forces, gear, throttle, power) with
**no OBD dongle, no Bluetooth, no phone**.

![dashboard running on the head unit](docs/dashboard.jpg)

*(above: live idle data on the unit it was developed on — "42 CAN signals live")*

## ⚠️ Compatibility — read this first

**Target:** the **V37 Infiniti Q50 / Q60** whose InTouch head unit runs the **Android 2.3.x**
revision this was reverse-engineered from (a customized **Android 2.3.7, API 10, x86** unit).
Developed and verified on a **2018 Q60 Red Sport 400 AWD (V37, VR30DDTT)**; the bundled public OBU
cert and the sensor-type → unit calibration both come from that car's firmware.

**It is not guaranteed across every Q50/Q60.** Other model years, regions, and later InTouch
hardware/software ship different firmware (different OS/SoC, different CAN maps) — the app may not
load, or the sensor numbers/scalings may be off. If your unit isn't that **Android 2.3.x InTouch**,
treat this as **unverified**: only run it on a matching unit, on **a vehicle you own**, and
sanity-check the scalings against your own gauges.

## How it works

Under its Linux nav UI, this InTouch unit runs a customized **Android 2.3.7 (API 10, x86)** that
hosts the "App Garage." Two things make this app possible:

1. **It exposes the vehicle CAN bus as standard Android `Sensor`s** (`VS_ID_*`, vendor "Ygomi",
   sensor types 12–53). Any app reads them with plain `SensorManager.getDefaultSensor(type)` +
   `registerListener` → `onSensorChanged(e).values[0]`. Reading values needs
   `com.ygomi.permission.IVI_CAN_READ`, which is a *dangerous*-level permission → auto-granted to a
   normal self-signed app on Android 2.3.
2. **`ivi.isDistractive="false"`** in the manifest sets `runningRestriction=0`, so the app stays
   **usable while driving** (the platform defaults unmarked apps to restricted).

Pure-Java, ships no native code (runs on the x86 CPU), needs no Google services, works fully offline.

## Language and display units

Tap **SETTINGS** at the bottom of the dashboard to change the display without leaving the app.
Preferences are saved on the head unit and restored the next time the dashboard starts.

- Language: English / Traditional Chinese (繁體中文)
- Engine profile / RPM redline: VR30DDTT 6800 / VQ35HR 7000 / VQ37VHR 7500 rpm
- Speed: mph / km/h
- Pressure: psi / kPa (applies to oil pressure and TPMS)
- Power: kW / PS
- Torque: Nm / kgm

UI translations are maintained outside the Java source in `assets/i18n/en.json` and
`assets/i18n/zh-TW.json`. They are loaded once when the dashboard starts; a missing key falls back
to English and then to the key name. Add another JSON catalog and language option to extend i18n.

Tap **CAN** at the bottom of the dashboard to open the live diagnostics screen. It lists every
sensor reported by `SensorManager`, including unknown/custom types, with its type, name/vendor, raw
value vector (up to four fields), observed first-field range, average update rate, and current state
(`LIVE`, `STALE`, or `NO DATA`). Signals used by the
dashboard are marked `VR30 MAP`; everything else is marked `UNKNOWN`. Use PREV/NEXT to inspect
additional sensor pages. On a VQ35 Hybrid or any non-VR30 vehicle, treat the raw values as the truth
until each mapping and scaling has been verified on-car.

The diagnostics header also has a **SYSTEM** page. It performs a read-only inventory of Binder
services, installed packages, Android services, providers, receivers, and relevant permissions. No
unknown Binder call or CAN write is issued. Keyword matches are placed first so the results can be
photographed directly from a head unit that cannot export files.

The SYSTEM probe also reports whether Android exposes a Bluetooth adapter, paired devices, and an
`ACTION_SEND` handler for APK files. This confirms Bluetooth OPP feasibility before any export
feature is enabled.

It also lists visible network interfaces/IP addresses, external-storage and USB/SD mount points,
their available/total capacity, and each installed APK's byte size plus read/write accessibility. These checks stay read-only and
are intended to select a safe export path before copying any system package.

## Preview on Windows

An Android 2.3.3 (API 10) x86 AVD named `AppGarage_Dash_API10` can run the dashboard without a
vehicle. Double-click `preview.cmd` after building: it starts the emulator, waits for Android to
boot, installs `build/dash.apk`, and opens the dashboard in DEMO mode. The AVD is configured for the
head unit's 800x480 landscape display. Hardware acceleration must be enabled for usable performance.

## Signals & calibration

Raw sensor values are unlabeled floats; these were calibrated against the VR30DDTT they were read from:

### VQ35HR Hybrid field observations

The VQ35HR Hybrid firmware uses the same Android sensor type numbers but does not necessarily use the
same calibration as the VR30DDTT.  On the tested vehicle, types 15, 16, 12, and 32 reported negative
placeholder values (`-50`, `-0.098`, `-398`, and `-408462.5`) while stationary.  With the VQ35HR
profile selected, the dashboard therefore renders these values as `--`; the CAN diagnostics screen
continues to show the untouched raw values for calibration.

Type 13 is still named `VS_ID_ENGINE_RPM` by the vehicle and remains the RPM source.  A zero reading
can be valid while the hybrid system is READY but the combustion engine is stopped.  Type 43 is named
`VS_ID_DISTANCETOTALIZER` and must not be used as RPM without stronger capture evidence.

| Gauge | Sensor | Scaling | Notes |
|---|---|---|---|
| RPM | 13 | direct | warm idle ~650 (cold/warmup higher) |
| Coolant / Oil temp | 14 / 15 | direct °C | |
| **Oil pressure** | 16 | raw × 145 → **psi** | ~22 psi warm idle (raw is MPa) |
| Speed | 17 | raw × 0.621 → **mph** | raw is km/h |
| Torque | 12 | ≈ Nm | |
| Power | 32 | raw × 1.047e-4 → **kW** | raw = rpm × torque |
| TPMS ×4 | 36–39 | direct **psi** | |
| Throttle | 23 | % | |
| Gear | 22 | enum | P=1, R=2, N=3, D=4, M1–M7=16–22 (confirmed on-car) |
| G-force lat/long | 20 / 21 | raw | full-scale ≈ 1 g assumed |

These mappings are specific to the firmware above — treat them as a starting point on any other unit.
Constants live at the top of [`GaugeView.java`](src/com/appgarage/dash/GaugeView.java).

**Not available** (not on this unit's CAN): boost/MAP, AFR/lambda, knock, ignition timing — those
are ECU-tuning parameters only tools like EcuTek read. This is instrument-cluster-grade data.

## Build

Requirements: a JDK, the Android SDK command-line tools (`build-tools;34.0.0` + `platforms;android-34`),
and Python 3 with `cryptography` (`pip install cryptography`, used by the `.epk` step). Point the build
at your toolchain via the `JAVA_HOME` and `ANDROID_SDK` env vars (or edit the two lines atop `build.sh`).

```
git clone https://github.com/bugjosh/appgarage-dash && cd appgarage-dash
bash build.sh          # -> build/dash.apk  +  build/dash.epk  (loadable via App Garage)
```
The APK is `minSdkVersion 10`, pure-Dalvik (no `lib/`), self-signed with a persistent `keystore.ks`.

## Loading onto the head unit

The head unit's **App Garage** installs USB apps only as `.epk` packages. `build.sh` produces
`build/dash.epk` for you, using the **public** OBU certificate in [`keys/obu_cert.pem`](keys/README.md)
(only the public half is shipped — the private key and firmware are not).

**On a computer**
1. Format a USB stick as **FAT32**.
2. Copy **`build/dash.epk`** to the **root** of the stick (App Garage scans the stick for `.epk` files).

**In the car** (engine running, so the gauges show live data)

3. Insert the stick into the head unit's USB port. If it prompts *"USB music device detected… create or
   replace voice recognition data?"*, choose **No**.
4. Give the unit a moment after start-up — it can take **up to a minute** to finish loading, showing
   **"Loading all apps"** (or similar) along the bottom of the main screen. Wait until that clears.
5. From the main screen, press the **right arrow once** — the **App Garage** icon is there. Open it.
6. Choose **Install Apps via USB**. It lists the apps found on the stick — select **AppGarage Dash**
   (or **Install All Apps**), then confirm **Install this app?** → **Install**.
7. Wait for **Installation from USB complete** / **Apps installed** — don't pull the USB mid-install
   (*"Please do not remove the USB during installation"*).
8. Launch **AppGarage Dash** from App Garage (or its home-screen shortcut). The gauges go live.

**Updating:** App Garage hides an install candidate whose `versionCode` is ≤ the one already
installed, so each `build.sh` run stamps a higher `versionCode` (unix time). A new `dash.epk` will
then appear and install over the old app **provided it's signed with the same key** (`keystore.ks`).
If it doesn't show in the list, or you rebuilt with a different key — a fresh clone makes its own,
and the released `.epk` differs from a self-built one — **uninstall the existing "AppGarage Dash"
first**, then install.

> The exact menu wording above is taken from the App Garage firmware; your unit's labels should match
> or be close. The bundled cert works on units running the matching InTouch firmware; units with
> different firmware supply their own (see [`keys/README.md`](keys/README.md)). Only load software onto
> **a vehicle you own.**

## Credits

This wouldn't exist without the groundwork of others:

- **Firmware image:** the Q50/Q60 system image that made the platform legible was dumped and shared
  by **@tdpequinox** (on the **DCUFix** Discord) — thank you.
- **App Garage load format:** the on-device package format and how apps are signed/accepted was
  worked out from that firmware.

## Disclaimer

For use on **your own vehicle**, at your own risk. Don't let an on-screen display distract you from
driving. Not affiliated with Infiniti, Nissan, Ygomi, or Airbiquity; ships no proprietary keys,
firmware, or copyrighted assets.

## License

MIT — see [LICENSE](LICENSE).
