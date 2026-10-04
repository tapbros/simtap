#!/usr/bin/env bash
# Gradle 없이 aapt2, javac, d8, zipalign, apksigner 로 APK 를 만든다.
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

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJAR"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

# 버전: VERSION_NAME 과 VERSION_CODE 를 둘 다 주면 그 값, 아니면 APK 에 들어가는 파일을 마지막으로 바꾼 커밋 제목의 vA.BB.CC.DD 라벨.
# 문서만 고친 커밋은 앱 버전을 올리지 않는다.
if [ -n "${VERSION_NAME:-}" ] && [ -n "${VERSION_CODE:-}" ]; then
  :
elif [ -n "${VERSION_NAME:-}" ] || [ -n "${VERSION_CODE:-}" ]; then
  echo "set both VERSION_NAME and VERSION_CODE, or neither" >&2; exit 1
else
  SUBJECT="$(git -C "$ROOT" log -1 --format=%s HEAD -- src res AndroidManifest.xml 2>/dev/null || true)"
  LABEL_RE='^v([0-9]+)\.([0-9]{2})\.([0-9]{2})\.([0-9]{2})(:| |$)'
  if [[ "$SUBJECT" =~ $LABEL_RE ]]; then
    VERSION_NAME="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}.${BASH_REMATCH[4]}"
    VERSION_CODE=$(( 10#${BASH_REMATCH[1]} * 1000000 + 10#${BASH_REMATCH[2]} * 10000 \
      + 10#${BASH_REMATCH[3]} * 100 + 10#${BASH_REMATCH[4]} ))
  else
    echo "HEAD commit subject has no vA.BB.CC.DD label: '${SUBJECT}'" >&2
    echo "set VERSION_NAME and VERSION_CODE explicitly (e.g. VERSION_NAME=0.01.00.00 VERSION_CODE=10000)" >&2
    exit 1
  fi
fi
# 시험 빌드: SIMTAP_TARGET_PKG 를 주면 telephonyui 대신 그 패키지(testbed/fakesim 등)의 SIM 관리자 화면을 본다.
# 앱 패키지명(com.tapbros.simtap)은 그대로라 덮어 설치해 시험하고 릴리스판으로 되돌린다. 비어 있으면 릴리스 빌드다.
TARGET_PKG="${SIMTAP_TARGET_PKG:-}"
SUFFIX=""
if [ -n "$TARGET_PKG" ]; then
  [[ "$TARGET_PKG" =~ ^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$ ]] \
    || { echo "SIMTAP_TARGET_PKG is not a package name: '$TARGET_PKG'" >&2; exit 1; }
  SUFFIX="-testbed"
fi
OUT="$B/simtap-v${VERSION_NAME}${SUFFIX}.apk"

# 이전 산출물 정리: 고정 디렉터리 내 파일만 삭제
mkdir -p "$B/compiled" "$B/gen" "$B/classes" "$B/dex"
find "$B" -type f -delete

# 서명: RELEASE_KS 가 있으면 릴리스 키, 없으면 디버그 키(저장소 비포함)
RELEASE_KS="${RELEASE_KS:-}"
if [ -n "$RELEASE_KS" ]; then
  [ -f "$RELEASE_KS" ] || { echo "RELEASE_KS not found: $RELEASE_KS" >&2; exit 1; }
  [ -n "${RELEASE_KS_ALIAS:-}" ] || { echo "RELEASE_KS_ALIAS is empty" >&2; exit 1; }
  [ -n "${RELEASE_KS_PASS:-}" ] || { echo "RELEASE_KS_PASS is empty" >&2; exit 1; }
  export RELEASE_KS_PASS
  SIGN_ARGS=(--ks "$RELEASE_KS" --ks-key-alias "$RELEASE_KS_ALIAS" \
    --ks-pass env:RELEASE_KS_PASS --key-pass env:RELEASE_KS_PASS)
else
  echo "warning: debug-signed (not for distribution); set RELEASE_KS to sign for release" >&2
  if [ ! -f "$KS" ]; then
    mkdir -p "$KS_DIR"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null
  fi
  SIGN_ARGS=(--ks "$KS" --ks-pass pass:android --key-pass pass:android)
fi

# 위젯 레이아웃에 RemoteViews 가 못 푸는 뷰가 있으면 기기에서 「위젯을 추가할 수 없습니다」가 뜬다. 빌드 전에 막는다.
python3 "$ROOT/tools/check_widget_layouts.py"

# 대상 앱 상수. 소스 트리 밖(build/gen-target)에 만들어 원본 파일을 바꾸지 않는다.
# 릴리스: SimCardMgrActivity 본체와 OPEN_SIMCARD_ACTIVITY 는 MODIFY_PHONE_STATE 를 요구해 일반 앱이 못 연다.
# 같은 화면을 가리키는 activity-alias NoPermissionSimCardMgrActivity 는 권한 속성이 없다(telephonyui 매니페스트).
# 시험: 대상 패키지의 .SimCardMgrActivity 를 action 없이 연다(SimTapService.SIM_MGR_CLASS_SUFFIX 와 맞는 이름).
GEN_TARGET="$B/gen-target/com/tapbros/simtap"
mkdir -p "$GEN_TARGET"
if [ -n "$TARGET_PKG" ]; then
  CFG_PKG="$TARGET_PKG"
  CFG_ACTION="null"
  CFG_CLASS="$TARGET_PKG.SimCardMgrActivity"
else
  CFG_PKG="com.samsung.android.app.telephonyui"
  CFG_ACTION='"android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"'
  CFG_CLASS="$CFG_PKG.netsettings.NoPermissionSimCardMgrActivity"
fi
cat > "$GEN_TARGET/TargetConfig.java" <<JAVA
package com.tapbros.simtap;

/** build.sh 가 만든다. 직접 고치지 않는다. */
final class TargetConfig {
    private TargetConfig() {}
    static final String PKG = "$CFG_PKG";
    static final String SIM_MANAGER_ACTION = $CFG_ACTION;
    static final String SIM_MANAGER_CLASS = "$CFG_CLASS";
}
JAVA

# 시험 빌드는 res 사본에서 접근성 설정의 packageNames 만 바꾼다. 릴리스는 원본 res 를 그대로 쓴다.
RES_DIR="$ROOT/res"
if [ -n "$TARGET_PKG" ]; then
  RES_DIR="$B/res-testbed"
  mkdir -p "$RES_DIR"
  cp -R "$ROOT/res/." "$RES_DIR/"
  A11Y=xml/accessibility_service_config.xml
  sed "s/android:packageNames=\"com\.samsung\.android\.app\.telephonyui\"/android:packageNames=\"$TARGET_PKG\"/" \
    "$ROOT/res/$A11Y" > "$RES_DIR/$A11Y"
  grep -q "android:packageNames=\"$TARGET_PKG\"" "$RES_DIR/$A11Y" \
    || { echo "packageNames substitution failed in $A11Y" >&2; exit 1; }
fi

"$BT/aapt2" compile --dir "$RES_DIR" -o "$B/compiled"
"$BT/aapt2" link -I "$AJAR" --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$B/gen" -o "$B/base.apk" "$B"/compiled/*.flat

javac --release 8 -Xlint:-options -cp "$AJAR" -d "$B/classes" \
  $(find "$ROOT/src" "$B/gen" "$B/gen-target" -name '*.java')

"$BT/d8" --lib "$AJAR" --min-api 26 --output "$B/dex" \
  $(find "$B/classes" -name '*.class')

cp "$B/base.apk" "$B/unaligned.apk"
( cd "$B/dex" && zip -q -j "$B/unaligned.apk" classes.dex )
"$BT/zipalign" -f 4 "$B/unaligned.apk" "$B/aligned.apk"
"$BT/apksigner" sign "${SIGN_ARGS[@]}" --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT"
echo "APK: $OUT ($VERSION_NAME, code $VERSION_CODE)"
"$BT/apksigner" verify --print-certs "$OUT" | grep -E 'certificate (DN|SHA-256 digest)'
echo "APK SHA-256: $(shasum -a 256 "$OUT" | cut -d' ' -f1)"
