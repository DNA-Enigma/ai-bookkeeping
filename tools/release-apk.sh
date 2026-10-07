#!/usr/bin/env bash
# 打包 → 生成版本清单 → 发布到 GitHub Releases，供 App 在线更新。
#
#   tools/release-apk.sh [tag] [--notes "更新说明"]
#
# 为什么走 Releases 而不是 serve-apk.sh 的局域网 HTTP（2026-10-07 用户选定）：
#   * serve-apk.sh 默认 `http://10.0.2.2:8080` 是**模拟器专用别名**，真机/外网都不通；
#   * release 构建的 network_security_config 是 `cleartextTrafficPermitted="false"`，
#     **明文 HTTP 会被系统直接拦**，所以更新源必须是 https；
#   * GitHub Releases 天然是 https、域名永久不变，`releases/latest/download/<资产名>`
#     这条 URL **永远指向最新一版**，客户端只需在出厂时写死一次。
#
# 客户端侧对应改动：`UpdateChecker.DEFAULT_MANIFEST_URL` =
#   https://github.com/DNA-Enigma/ai-bookkeeping/releases/latest/download/version.json
#
# 资产名是**固定的**（ai-bookkeeping.apk / version.json），于是：
#   - 清单 URL 永远稳定（latest/download/version.json）
#   - 清单里的 url 指向**它自己那一版**的 APK（releases/download/<tag>/...），
#     而不是 latest——否则"清单是新版、APK 还是旧版"这种错配会发生。
#
# 用完即走，不常驻服务：不像 cloudflared 需要进程活着，隧道断了更新就断。
set -euo pipefail

TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$HOME/opt/android-toolchain}"
export JAVA_HOME="$TOOLCHAIN_ROOT/jdk"
export ANDROID_HOME="$TOOLCHAIN_ROOT/sdk"
export ANDROID_SDK_ROOT="$TOOLCHAIN_ROOT/sdk"
APK="$TOOLCHAIN_ROOT/sdk"   # 仅为下面 aapt2 路径可读

REPO="DNA-Enigma/ai-bookkeeping"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
NOTES=""

# --- 参数 ---
TAG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --notes) NOTES="$2"; shift 2 ;;
    -*)      echo "未知参数: $1" >&2; exit 2 ;;
    *)       TAG="$1"; shift ;;
  esac
done

command -v gh >/dev/null || { echo "需要 gh CLI" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh 未登录：先跑 gh auth login" >&2; exit 1; }

echo "==> 构建"
bash tools/build.sh :app:assembleDebug --no-build-cache >/dev/null
[[ -f "$APK_PATH" ]] || { echo "APK 不存在: $APK_PATH" >&2; exit 1; }

echo "==> 读取版本信息"
BADGING="$("$TOOLCHAIN_ROOT/sdk/build-tools/37.0.0/aapt2" dump badging "$APK_PATH")"
VERSION_CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)"
VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
[[ -n "$VERSION_CODE" ]] || { echo "读不到 versionCode" >&2; exit 1; }

TAG="${TAG:-v$VERSION_NAME}"

SHA256="$(sha256sum "$APK_PATH" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$APK_PATH")"
APK_URL="https://github.com/$REPO/releases/download/$TAG/ai-bookkeeping.apk"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$APK_PATH" "$WORK/ai-bookkeeping.apk"

cat > "$WORK/version.json" <<EOF
{
  "version_code": $VERSION_CODE,
  "version_name": "$VERSION_NAME",
  "url": "$APK_URL",
  "sha256": "$SHA256",
  "sizeBytes": $SIZE,
  "releaseNotes": "${NOTES:-由 tools/release-apk.sh 发布}",
  "mandatory": false
}
EOF

echo "==> 清单"
cat "$WORK/version.json"
echo

echo "==> 校验 URL 可达性（发布前先确认仓库是 public，否则清单拉不到）"
gh repo view "$REPO" --json visibility --jq '.visibility' | sed 's/^/    仓库可见性: /'

# tag 已存在就先删（同一版本号重复发版是常态：改个 bug 重发）
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "==> tag $TAG 已存在，删除后重建（同版本号重发）"
  gh release delete "$TAG" --yes --cleanup-tag
fi

echo "==> 发布 $TAG"
gh release create "$TAG" \
  "$WORK/ai-bookkeeping.apk" \
  "$WORK/version.json" \
  --title "v$VERSION_NAME" \
  --notes "${NOTES:-v$VERSION_NAME（versionCode $VERSION_CODE）}"

echo
echo "==> 完成。客户端下次「检查更新」会读："
echo "    https://github.com/$REPO/releases/latest/download/version.json"
echo "    APK 直链: $APK_URL"
echo "    sha256:   $SHA256"
