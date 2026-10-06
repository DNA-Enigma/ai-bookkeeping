#!/usr/bin/env bash
# 无头启动安卓模拟器，装上 APK 并拉起来，最后截一张图。
#
#   tools/run-emulator.sh [apk路径]
#
# 依赖 tools/setup-toolchain.sh 装好的工具链，以及 emulator + 系统镜像：
#   sdkmanager --install "emulator" "system-images;android-36;google_apis;x86_64"
#
# 产物：/tmp/app-screen.png（截图）、/tmp/emulator.log（模拟器日志）
set -euo pipefail

TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$HOME/opt/android-toolchain}"
SDK="$TOOLCHAIN_ROOT/sdk"
AVD_NAME="${AVD_NAME:-bookkeeping}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-36;google_apis;x86_64}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
SCREENSHOT="${SCREENSHOT:-/tmp/app-screen.png}"
LAUNCH_COMPONENT="dev.dzsun.bookkeeping/.MainActivity"

export JAVA_HOME="$TOOLCHAIN_ROOT/jdk"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"

cd "$(dirname "$0")/.."

[ -f "$APK" ] || { echo "找不到 APK：$APK（先跑 tools/build.sh :app:assembleDebug）" >&2; exit 1; }

# ---- AVD -------------------------------------------------------------------
if avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
  echo "AVD $AVD_NAME 已存在"
else
  echo "创建 AVD $AVD_NAME"
  echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_6 --force
fi

# ---- 启动 ------------------------------------------------------------------
# -no-window: 无显示器环境；swiftshader_indirect: 无 GPU 时用软件渲染
if adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
  echo "模拟器已在运行"
else
  echo "启动模拟器（无头）"
  nohup emulator -avd "$AVD_NAME" \
    -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect \
    > /tmp/emulator.log 2>&1 &
  adb wait-for-device
  echo -n "等待系统启动完成"
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    echo -n "."; sleep 2
  done
  echo " 就绪"
fi

# ---- 安装并启动 ------------------------------------------------------------
echo "安装 $APK"
adb install -r "$APK"

echo "清空日志并启动 $LAUNCH_COMPONENT"
adb logcat -c
adb shell am start -n "$LAUNCH_COMPONENT"
sleep 6

# ---- 结果 ------------------------------------------------------------------
if adb logcat -d -s AndroidRuntime:E 2>/dev/null | grep -q "FATAL EXCEPTION"; then
  echo "!! 应用崩溃，崩溃栈如下：" >&2
  adb logcat -d -s AndroidRuntime:E | tail -40 >&2
  exit 1
fi

adb exec-out screencap -p > "$SCREENSHOT"
echo "未检测到崩溃；截图：$SCREENSHOT"
