#!/usr/bin/env bash
# 打包 → 生成版本清单 → 通过 HTTP 提供，供手机下载与在线更新。
#
#   tools/serve-apk.sh [基础地址] [端口]
#
# 基础地址是**设备**能访问到的地址，会写进 version.json 里的 APK 直链：
#   模拟器：  tools/serve-apk.sh http://10.0.2.2:8080
#   真机：    tools/serve-apk.sh http://192.168.1.23:8080
# 不给则自动取本机第一个局域网地址。
set -euo pipefail

TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$HOME/opt/android-toolchain}"
SDK="$TOOLCHAIN_ROOT/sdk"
SHARE_DIR="${SHARE_DIR:-/tmp/apk-share}"
PORT="${2:-8080}"

export JAVA_HOME="$TOOLCHAIN_ROOT/jdk"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

cd "$(dirname "$0")/.."

LAN_IP="$(ip -4 addr show 2>/dev/null | grep -oP 'inet \K[0-9.]+' \
  | grep -vE '^127\.|^172\.1[78]\.' | head -1)"
BASE_URL="${1:-http://${LAN_IP:-127.0.0.1}:$PORT}"

APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> 构建"
bash tools/build.sh :app:assembleDebug --no-build-cache >/dev/null

echo "==> 读取版本信息"
BADGING="$("$SDK/build-tools/37.0.0/aapt2" dump badging "$APK")"
VERSION_CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)"
VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
[ -n "$VERSION_CODE" ] || { echo "读不到 versionCode" >&2; exit 1; }

mkdir -p "$SHARE_DIR"
cp "$APK" "$SHARE_DIR/ai-bookkeeping.apk"

SHA256="$(sha256sum "$SHARE_DIR/ai-bookkeeping.apk" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$SHARE_DIR/ai-bookkeeping.apk")"

cat > "$SHARE_DIR/version.json" <<EOF
{
  "version_code": $VERSION_CODE,
  "version_name": "$VERSION_NAME",
  "url": "$BASE_URL/ai-bookkeeping.apk",
  "sha256": "$SHA256",
  "sizeBytes": $SIZE,
  "releaseNotes": "由 tools/serve-apk.sh 自动生成",
  "mandatory": false
}
EOF

echo "==> 清单"
cat "$SHARE_DIR/version.json"
echo
echo "==> APK   $BASE_URL/ai-bookkeeping.apk"
echo "==> 清单  $BASE_URL/version.json"
echo

if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  echo "端口 $PORT 已被占用，不重复启动服务（清单已更新，直接可用）"
  exit 0
fi

echo "==> 在 $PORT 提供服务（Ctrl-C 停止）"
cd "$SHARE_DIR"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
