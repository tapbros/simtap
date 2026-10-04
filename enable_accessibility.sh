#!/usr/bin/env bash
# 사용법: ./enable_accessibility.sh <adb시리얼>
# 접근성 서비스를 shell 권한의 settings put 으로 켠다. 기존 값은 보존하고 이어 붙인다.
set -euo pipefail

SERIAL="${1:?usage: $0 <adb-serial>}"
SVC="com.tapbros.simtap/com.tapbros.simtap.SimTapService"
A=(adb -s "$SERIAL" shell)

CUR="$("${A[@]}" settings get secure enabled_accessibility_services | tr -d '\r')"
echo "기존 enabled_accessibility_services=$CUR"
echo "기존 accessibility_enabled=$("${A[@]}" settings get secure accessibility_enabled | tr -d '\r')"

case ":$CUR:" in
  *":$SVC:"*) echo "이미 등록됨: $SVC"; NEW="$CUR" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$SVC"; else NEW="$CUR:$SVC"; fi
    "${A[@]}" settings put secure enabled_accessibility_services "$NEW"
    ;;
esac
"${A[@]}" settings put secure accessibility_enabled 1
echo "현재 enabled_accessibility_services=$("${A[@]}" settings get secure enabled_accessibility_services | tr -d '\r')"
echo "현재 accessibility_enabled=$("${A[@]}" settings get secure accessibility_enabled | tr -d '\r')"
