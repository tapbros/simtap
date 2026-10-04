#!/usr/bin/env bash
# 가짜 SIM 관리자(com.tapbros.fakesim)를 Gradle 없이 aapt2, javac, d8, zipalign, apksigner 로 만든다.
# 도구 탐색은 저장소 루트 build.sh 와 같다. 이 앱 전용 디버그 키로만 서명한다(저장소 비포함).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
BT_VER="${BT_VER:-35.0.0}"
PLAT="${PLAT:-android-34}"
BT="$SDK/build-tools/$BT_VER"
AJAR="$SDK/platforms/$PLAT/android.jar"
if [ -z "${JAVA_HOME:-}" ]; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

B="$ROOT/build"
KS_DIR="$ROOT/.keystore"
KS="$KS_DIR/debug.jks"
OUT="$B/fakesim-debug.apk"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJAR"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

# 이전 산출물 정리: 고정 디렉터리 내 파일만 삭제
mkdir -p "$B/compiled" "$B/gen" "$B/classes" "$B/dex"
find "$B" -type f -delete

if [ ! -f "$KS" ]; then
  mkdir -p "$KS_DIR"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=FakeSim Debug,O=Android,C=US" >/dev/null
fi

"$BT/aapt2" compile --dir "$ROOT/res" -o "$B/compiled"
"$BT/aapt2" link -I "$AJAR" --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 1.0 \
  --java "$B/gen" -o "$B/base.apk" "$B"/compiled/*.flat

javac --release 8 -Xlint:-options -cp "$AJAR" -d "$B/classes" \
  $(find "$ROOT/src" "$B/gen" -name '*.java')

"$BT/d8" --lib "$AJAR" --min-api 26 --output "$B/dex" \
  $(find "$B/classes" -name '*.class')

cp "$B/base.apk" "$B/unaligned.apk"
( cd "$B/dex" && zip -q -j "$B/unaligned.apk" classes.dex )
"$BT/zipalign" -f 4 "$B/unaligned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT"
echo "APK: $OUT"
