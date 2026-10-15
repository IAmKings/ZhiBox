#!/usr/bin/env bash
# P0-2 一键基准脚本：安装 APK、推送模型、自动触发基准、拉取报告
#
# 模型投放方式（Android 16 scoped storage 经实测踩坑）：
#   直接 adb push 到 /sdcard/Android/data/<pkg>/ 会产生 shell 属主目录，
#   应用 uid 无权列出（表现为"未找到模型文件"）。
#   正确路径：push 到 /data/local/tmp → run-as 以应用 uid 拷入内部 filesDir/models。
#
# 用法: ./scripts/run_p02_benchmark.sh [model1.litertlm model2.litertlm ...]
#       不带参数则推送 models/ 目录下全部 .litertlm
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.zhibox.memory"
TMP_DIR="/data/local/tmp/zhibox-models"

cd "$(dirname "$0")/.."

if [ ! -f "$APK" ]; then
    echo "[1/6] 构建 APK..."
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    ./gradlew assembleDebug
fi

echo "[1/6] 安装 APK"
"$ADB" install -r "$APK" | tail -1

echo "[2/6] 清理设备旧模型与报告"
"$ADB" shell "rm -rf $TMP_DIR /sdcard/Android/data/$PKG/files/models" 2>/dev/null || true
"$ADB" shell "run-as $PKG rm -rf files/benchmark files/models" 2>/dev/null || true

MODELS=("$@")
if [ ${#MODELS[@]} -eq 0 ]; then
    mapfile -t MODELS < <(ls models/*.litertlm 2>/dev/null || true)
fi
if [ ${#MODELS[@]} -eq 0 ]; then echo "models/ 下无 .litertlm 文件"; exit 1; fi

echo "[3/6] 推送 ${#MODELS[@]} 个模型到 /data/local/tmp（中转）"
"$ADB" shell "mkdir -p $TMP_DIR"
for m in "${MODELS[@]}"; do
    echo "  -> $(basename "$m")"
    "$ADB" push "$m" "$TMP_DIR/" | tail -1
done

echo "[4/6] run-as 以应用身份拷入内部 filesDir/models"
"$ADB" shell "run-as $PKG mkdir -p files/models"
for m in "${MODELS[@]}"; do
    base=$(basename "$m")
    echo "  cp $base"
    "$ADB" shell "run-as $PKG cp $TMP_DIR/$base files/models/$base"
done
echo "  校验:"
"$ADB" shell "run-as $PKG ls -la files/models/"
"$ADB" shell "rm -rf $TMP_DIR"

echo "[5/6] 触发基准（force-stop 后冷启动，自动运行并导出 JSON）"
"$ADB" logcat -c 2>/dev/null || true
"$ADB" shell "am force-stop $PKG"
sleep 1
"$ADB" shell am start -n "$PKG/.MainActivity" --ez autostart_benchmark true
echo "  等待完成（约 1-3 分钟，视模型数量）..."
sleep 100

echo "[6/6] 拉取报告"
mkdir -p docs/phase0/bench-results
FILES=$("$ADB" shell "run-as $PKG ls files/benchmark" 2>/dev/null | tr -d '\r')
if [ -z "$FILES" ]; then
    echo "未发现报告文件；检查 logcat:"
    "$ADB" logcat -d -s AndroidRuntime:E System.err:W | tail -20
    exit 1
fi
for f in $FILES; do
    "$ADB" shell "run-as $PKG cat files/benchmark/$f" | tr -d '\r' > "docs/phase0/bench-results/$f"
    echo "  已拉取: $f"
done
echo "完成。结果位于 docs/phase0/bench-results/"
