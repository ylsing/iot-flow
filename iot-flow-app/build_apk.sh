#!/usr/bin/env bash
# 手动打包 APK（不依赖 Gradle）：aapt2 -> javac -> d8 -> 打包 -> zipalign -> apksigner
set -euo pipefail

SDK="${ANDROID_SDK:-C:/Users/Administrator/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="E:/Program Files/Java/jdk-20"
JAVAC="/e/Program Files/Java/jdk-20/bin/javac"
KEYTOOL="/e/Program Files/Java/jdk-20/bin/keytool"

cd "$(dirname "$0")"
SRC=app/src/main
OUT=build
rm -rf "$OUT/obj" "$OUT/dex" "$OUT/gen" "$OUT/res.zip" "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/_dex"
mkdir -p "$OUT/obj" "$OUT/dex" "$OUT/gen"

echo "== 1/6 aapt2 compile"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest "$SRC/AndroidManifest.xml" \
  -R "$OUT/res.zip" -A "$SRC/assets" --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version 34 --version-code 1 --version-name 1.0.0

echo "== 3/6 javac"
find "$OUT/gen" "$SRC/java" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn -classpath "$PLATFORM" -d "$OUT/obj" @"$OUT/sources.txt"

echo "== 4/6 d8"
find "$OUT/obj" -name '*.class' > "$OUT/classes.txt"
"$BT/d8.bat" --lib "$PLATFORM" --min-api 21 --release --output "$OUT/dex" @"$OUT/classes.txt"

echo "== 5/6 打包 + 对齐"
python build_pack.py build

echo "== 6/6 签名"
if [ ! -f "$OUT/debug.keystore" ]; then
  "$KEYTOOL" -genkeypair -keystore "$OUT/debug.keystore" -storepass android -keypass android \
    -alias flow -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=IoT Flow, OU=dev, O=mywulian, L=Hefei, S=Anhui, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$OUT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias flow --min-sdk-version 21 --out "$OUT/iot-flow.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify --min-sdk-version 21 "$OUT/iot-flow.apk" && echo "签名校验 OK"

ls -lh "$OUT/iot-flow.apk"
"$BT/aapt2" dump badging "$OUT/iot-flow.apk" | head -2
