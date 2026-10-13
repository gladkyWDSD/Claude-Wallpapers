#!/usr/bin/env bash
# Builds ClawdClock.apk with the raw SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

TOOLCHAIN="${TOOLCHAIN:-$HOME/.local/opt/neural-tycoon-toolchain}"
SDK="${ANDROID_HOME:-$TOOLCHAIN/android-sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
export PATH="$TOOLCHAIN/jdk-17/bin:$PATH"

rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 36 -o build/unsigned.apk build/res.zip
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

KS="${KEYSTORE:-$HOME/.android/clawd-debug.keystore}"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias clawd \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Clawd Clock" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out ClawdClock.apk build/aligned.apk
echo "built $(pwd)/ClawdClock.apk"
