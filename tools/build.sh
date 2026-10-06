#!/usr/bin/env bash
# 在本地工具链下跑 Gradle。用法：tools/build.sh :app:assembleDebug
#
# AGP 9.x 要求 Gradle 9.x（8.14 会报 NoClassDefFoundError: ProjectTypeBinding），
# 所以这里固定 Gradle 9.8.0。项目里若有 gradlew 则优先用它。
set -euo pipefail

TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$HOME/opt/android-toolchain}"
GRADLE_VERSION="${GRADLE_VERSION:-9.8.0}"

export JAVA_HOME="$TOOLCHAIN_ROOT/jdk"
export ANDROID_HOME="$TOOLCHAIN_ROOT/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")/.."

if [ -x ./gradlew ]; then
  exec ./gradlew "$@"
fi

exec "$TOOLCHAIN_ROOT/gradle-$GRADLE_VERSION/bin/gradle" "$@"
