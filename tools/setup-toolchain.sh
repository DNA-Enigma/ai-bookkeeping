#!/usr/bin/env bash
# 安装 Android 构建工具链到 home 目录，不需要 sudo。
# 全部产物落在 $TOOLCHAIN_ROOT 下，删掉该目录即可完全卸载。
#
# 说明：Adoptium 官方地址会重定向到 GitHub，在这台机器上只有 ~50KB/s；
# 清华镜像实测可用，故 JDK 走镜像。
set -euo pipefail

TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$HOME/opt/android-toolchain}"
JDK_DIR="$TOOLCHAIN_ROOT/jdk"
SDK_DIR="$TOOLCHAIN_ROOT/sdk"
GRADLE_DIR="$TOOLCHAIN_ROOT/gradle-$GRADLE_VERSION"
DL_DIR="$TOOLCHAIN_ROOT/downloads"
CMDLINE_DIR="$TOOLCHAIN_ROOT/cmdline-tools"

GRADLE_VERSION="${GRADLE_VERSION:-9.8.0}"
JDK_TARBALL="OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz"
JDK_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/$JDK_TARBALL"
CMDLINE_ZIP="commandlinetools-linux-13114758_latest.zip"
CMDLINE_URL="https://dl.google.com/android/repository/$CMDLINE_ZIP"
# services.gradle.org 在这台机器上极慢，走华为云镜像
GRADLE_URL="https://mirrors.huaweicloud.com/gradle/gradle-${GRADLE_VERSION}-bin.zip"

# 传输超过 30 秒低于 10KB/s 就判定卡死，交给 --retry 重来
CURL=(curl -fL --retry 5 --retry-delay 3 --retry-all-errors
      --speed-limit 10240 --speed-time 30 --connect-timeout 20)

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

mkdir -p "$TOOLCHAIN_ROOT" "$DL_DIR" "$CMDLINE_DIR"

# ---- JDK 17 ---------------------------------------------------------------
if [ -x "$JDK_DIR/bin/javac" ]; then
  log "JDK 已存在，跳过"
else
  log "下载 JDK 17 (清华镜像)"
  "${CURL[@]}" -o "$DL_DIR/jdk17.tar.gz" "$JDK_URL"
  log "解压 JDK"
  rm -rf "$JDK_DIR"; mkdir -p "$JDK_DIR"
  tar -xzf "$DL_DIR/jdk17.tar.gz" -C "$JDK_DIR" --strip-components=1
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
log "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ---- Gradle ---------------------------------------------------------------
if [ -x "$GRADLE_DIR/bin/gradle" ]; then
  log "Gradle 已存在，跳过"
else
  log "下载 Gradle $GRADLE_VERSION"
  "${CURL[@]}" -o "$DL_DIR/gradle.zip" "$GRADLE_URL"
  log "解压 Gradle"
  rm -rf "$GRADLE_DIR"; mkdir -p "$GRADLE_DIR"
  unzip -q -o "$DL_DIR/gradle.zip" -d "$DL_DIR/gradle-extract"
  mv "$DL_DIR/gradle-extract/gradle-$GRADLE_VERSION/"* "$GRADLE_DIR/"
  rm -rf "$DL_DIR/gradle-extract"
fi
log "Gradle: $("$GRADLE_DIR/bin/gradle" --version 2>&1 | grep -i '^Gradle' || true)"

# ---- Android command-line tools -------------------------------------------
# 解压到独立空目录，再规整成 sdkmanager 要求的 <sdk>/cmdline-tools/latest 布局。
if [ -x "$CMDLINE_DIR/latest/bin/sdkmanager" ]; then
  log "cmdline-tools 已存在，跳过"
else
  log "下载 Android command-line tools"
  "${CURL[@]}" -o "$DL_DIR/$CMDLINE_ZIP" "$CMDLINE_URL"
  log "解压 cmdline-tools"
  rm -rf "$CMDLINE_DIR/latest" "$DL_DIR/cmdline-extract"
  mkdir -p "$DL_DIR/cmdline-extract"
  unzip -q -o "$DL_DIR/$CMDLINE_ZIP" -d "$DL_DIR/cmdline-extract"
  mkdir -p "$CMDLINE_DIR/latest"
  mv "$DL_DIR/cmdline-extract/cmdline-tools/"* "$CMDLINE_DIR/latest/"
  rmdir "$DL_DIR/cmdline-extract/cmdline-tools" "$DL_DIR/cmdline-extract"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
SDKMANAGER="$CMDLINE_DIR/latest/bin/sdkmanager"

log "接受 SDK 许可"
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true

# ---- SDK 组件 --------------------------------------------------------------
# Android 现在按次版本发布（37.0 / 37.1 / 37.2），platform 名要带次版本号。
# compileSdk 37 是依赖（Compose UI 1.12、core-ktx 1.19）的硬要求。
log "安装 SDK 组件（platform-tools / platform 37.2 / build-tools 37.0.0）"
"$SDKMANAGER" --sdk_root="$SDK_DIR" --install \
  "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"

log "已安装组件"
"$SDKMANAGER" --sdk_root="$SDK_DIR" --list_installed

cat <<EOF

工具链就绪。使用前导出：

  export JAVA_HOME="$JDK_DIR"
  export ANDROID_HOME="$SDK_DIR"
  export PATH="\$JAVA_HOME/bin:$GRADLE_DIR/bin:$CMDLINE_DIR/latest/bin:\$PATH"

EOF
