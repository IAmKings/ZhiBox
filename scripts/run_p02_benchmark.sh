#!/usr/bin/env bash
# P0-2 一键基准脚本：安装 APK、推送模型、自动触发基准、拉取报告
# 用法: ./scripts/run_p02_benchmark.sh [model1.litertlm model2.litertlm ...]
#       不带参数则推送 models/ 目录下全部 .litertlm
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.zhibox.memory"
DEV_DIR="/sdcard/Android/data/$PKG/files/models"

cd "$(dirname "$0")/.."

if [ ! -f "$APK" ]; then
    echo "[1/5] 构建 APK..."
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    ./gradlew assembleDebug
fi

echo "[1/5] 安装 APK"
"$ADB" install -r "$APK" | tail -1

echo "[2/5] 清理设备旧模型与报告"
"$ADB" shell "rm -rf $DEV_DIR" 2>/dev/null || true
"$ADB" shell "run-as $PKG rm -rf files/benchmark" 2>/dev/null || true
"$ADB" shell mkdir -p "$DEV_DIR"

MODELS=("$@")
if [ ${#MODELS[@]} -eq 0 ]; then
    mapfile -t MODELS < <(ls models/*.litertlm 2>/dev/null || true)
fi
if [ ${#MODELS[@]} -eq 0 ]; then echo "models/ 下无 .litertlm 文件"; exit 1; fi

echo "[3/5] 推送 ${#MODELS[@]} 个模型"
for m in "${MODELS[@]}"; do
    echo "  -> $(basename "$m")"
    "$ADB" push "$m" "$DEV_DIR/" | tail -1
done

echo "[4/5] 触发基准（自动运行并导出 JSON）"
"$ADB" logcat -c 2>/dev/null || true
"$ADB" shell am start -n "$PKG/.MainActivity" --ez autostart_benchmark true
echo "  等待完成（约 1-3 分钟，视模型数量）..."
sleep 90

echo "[5/5] 拉取报告"
mkdir -p docs/phase0/bench-results
"$ADB" shell "run-as $PKG ls files/benchmark" 2>/dev/null
"$ADB" shell "run-as $PKG cat files/benchmark" 2>/dev/null >/dev/null # noop 校验
for f in $("$ADB" shell "run-as $PKG ls files/benchmark" 2>/dev/null | tr -d '\r'); do
    "$ADB" shell "run-as $PKG cat files/benchmark/$f" | tr -d '\r' > "docs/phase0/bench-results/$f"
    echo "  已拉取: $f"
done
echo "完成。结果位于 docs/phase0/bench-results/"
