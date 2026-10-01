#!/usr/bin/env bash
# 建置 AppGarage Dash：Java -> dex -> 已簽署 APK（minSdk 10、純 Java、不含原生程式庫），
# 再使用 keys/ 中的公開 OBU 憑證封裝成 App Garage 載入器可讀取的 .epk。
set -euo pipefail
cd "$(dirname "$0")"

# 使用者請在此指定自己的工具鏈：設定 JAVA_HOME／ANDROID_SDK 環境變數，或直接編輯下列設定。
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot}"
SDK="${ANDROID_SDK:-/c/Users/raid2/scoop/apps/android-clt/current}"
BT="$SDK/build-tools/34.0.0"
ANDJAR="$SDK/platforms/android-34/android.jar"

# 工具副檔名：Windows／Git Bash 使用 .exe／.bat；Linux／macOS 不使用副檔名。
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) X=.exe; B=.bat;; *) X=""; B="";; esac
AAPT="$BT/aapt$X"; ZIPALIGN="$BT/zipalign$X"; APKSIGNER="$BT/apksigner$B"; D8="$BT/d8$B"
JAVAC="$JAVA_HOME/bin/javac$X"; KEYTOOL="$JAVA_HOME/bin/keytool$X"; JAR="$JAVA_HOME/bin/jar$X"

# 使用單調遞增的 versionCode（Unix 時間），確保重新建置的版本永遠大於已安裝版本，
# 讓 App Garage 將它顯示為更新，而不是因 versionCode 小於或等於已安裝版本而隱藏。
# versionName（「1.0」）仍保留為 AndroidManifest.xml 中供使用者辨識的版本號。
VC=$(date +%s)

rm -rf build && mkdir -p build/classes build/dex
echo "== [1/6] javac (release 8) =="
"$JAVAC" --release 8 -encoding UTF-8 -g -d build/classes -classpath "$ANDJAR" src/com/appgarage/dash/*.java
echo "  compiled: $(find build/classes -name '*.class' | wc -l) classes"

echo "== [2/6] d8 -> classes.dex (min-api 10) =="
"$D8" --min-api 10 --lib "$ANDJAR" --output build/dex $(find build/classes -name '*.class')
ls -l build/dex/classes.dex

echo "== [3/6] package APK (versionCode=$VC) =="
# 將 versionCode 寫入暫存 Manifest（aapt 會採用 Manifest 的值；--version-code 在此不起作用）。
sed "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" AndroidManifest.xml > build/AndroidManifest.xml
"$AAPT" package -f -M build/AndroidManifest.xml -S res -A assets -I "$ANDJAR" -F build/dash.unsigned.apk
( cd build/dex && "$AAPT" add ../dash.unsigned.apk classes.dex >/dev/null )

echo "== [4/6] zipalign =="
"$ZIPALIGN" -f -p 4 build/dash.unsigned.apk build/dash.aligned.apk

echo "== [5/6] sign (v1 for API 10) =="
# keystore 位於 build/ 之外，因此執行 `rm -rf build` 後仍會保留；穩定簽章可讓更新覆蓋舊版。
if [ ! -f keystore.ks ]; then
  "$KEYTOOL" -genkeypair -keystore keystore.ks -alias dash -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=AppGarageDash" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks keystore.ks --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled true \
  --out build/dash.apk build/dash.aligned.apk
"$APKSIGNER" verify --min-sdk-version 10 build/dash.apk >/dev/null 2>&1 && echo "  signature OK"

echo "== [6/6] wrap into .epk (uses the public OBU cert in keys/obu_cert.pem) =="
if [ -f tools/epk_tool.py ] && [ -f keys/obu_cert.pem ]; then
  if ! python tools/epk_tool.py build build/dash.apk build/dash.epk --cert keys/obu_cert.pem; then
    echo "  .epk wrap failed — need Python 3 + 'pip install cryptography'. The APK is still ready."
  fi
else
  echo "  skipped: keys/obu_cert.pem missing — APK is ready, but no .epk was produced."
fi

echo ""
echo "== VERIFY =="
"$AAPT" dump badging build/dash.apk 2>/dev/null | grep -iE "package:|sdkVersion|native-code|launchable" || true
"$JAR" -tf build/dash.apk | grep -viE "META-INF/" | head
echo "DONE -> build/dash.apk"; ls -l build/dash.apk build/dash.epk 2>/dev/null
