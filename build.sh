#!/usr/bin/env bash
# Builds the APK without Gradle. Used by flake.nix; also runs standalone given:
#   ANDROID_JAR    platforms/android-NN/android.jar
#   BUILD_TOOLS    build-tools/X.Y.Z directory (aapt2, d8, zipalign, apksigner)
#   KOTLIN_STDLIB  kotlin-stdlib.jar matching the kotlinc on PATH
#   KEYSTORE       signing keystore (default: debug.keystore, see genkey.sh); without it only
#                  the unsigned APK is produced, which is what the Nix build does
#   LINK_JAR       android.jar for resource linking (default: ANDROID_JAR)
# Output: $OUT/p3view-unsigned.apk, and $OUT/p3view.apk when the keystore exists (default OUT=build)
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_JAR:?}" "${BUILD_TOOLS:?}" "${KOTLIN_STDLIB:?}"
LINK_JAR=${LINK_JAR:-$ANDROID_JAR}
OUT=${OUT:-build}
MIN_SDK=36
TARGET_SDK=37
VERSION_CODE=1
VERSION_NAME=0.1.0

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/test"

echo "== protocol tests"
kotlinc -nowarn src/dev/p3view/Protocol.kt test/ProtocolTest.kt -d "$OUT/test"
java -cp "$OUT/test:$KOTLIN_STDLIB" dev.p3view.ProtocolTestKt

echo "== resources"
"$BUILD_TOOLS/aapt2" compile --dir res -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$OUT/unsigned.apk" -I "$LINK_JAR" \
  --manifest AndroidManifest.xml "$OUT/res.zip" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "== kotlin"
kotlinc -no-jdk -no-reflect -jvm-target 17 -classpath "$ANDROID_JAR" -d "$OUT/classes" src

echo "== dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" \
  @"$OUT/classes.txt" "$KOTLIN_STDLIB"

echo "== package"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes*.dex)
"$BUILD_TOOLS/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/p3view-unsigned.apk"
echo "built $OUT/p3view-unsigned.apk"
if [ -f "${KEYSTORE:-debug.keystore}" ]; then
  KEYSTORE=${KEYSTORE:-debug.keystore} ./sign.sh "$OUT/p3view-unsigned.apk" "$OUT/p3view.apk"
  echo "signed $OUT/p3view.apk"
else
  echo "no keystore, not signed (run ./genkey.sh)"
fi
