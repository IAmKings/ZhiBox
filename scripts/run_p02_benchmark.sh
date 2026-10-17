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
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

build_apk_if_missing
require_adb

echo "[1/6] 安装 APK"
install_apk

echo "[2/6] 清理设备旧模型与报告"
adb shell "rm -rf /data/local/tmp/zhibox-models /sdcard/Android/data/$PKG/files/models" 2>/dev/null || true
app_rm files/benchmark
app_rm files/models

MODELS=("$@")
if [ ${#MODELS[@]} -eq 0 ]; then
    mapfile -t MODELS < <(ls models/*.litertlm 2>/dev/null || true)
fi
if [ ${#MODELS[@]} -eq 0 ]; then die "models/ 下无 .litertlm 文件"; fi

echo "[3/6] 推送 ${#MODELS[@]} 个模型（/data/local/tmp 中转 + run-as cp）"
app_mkdir files/models
for m in "${MODELS[@]}"; do
    echo "  -> $(basename "$m")"
    app_put "$m" "files/models/$(basename "$m")"
done
echo "  校验:"
adb shell "run-as $PKG ls -la files/models/"

echo "[4/6] 触发基准（force-stop 后冷启动，自动运行并导出 JSON）"
logcat_clear
force_stop_app; sleep 1
start_autostart autostart_benchmark
echo "  轮询等待完成（每 10s，超时 5min，视模型数量）..."
p02_done() { [ -n "$(app_ls files/benchmark)" ]; }
poll_until "benchmark 报告" 300 10 p02_done || die "基准报告超时（见 logcat）"

echo "[5/6] 拉取报告"
mkdir -p docs/phase0/bench-results
DEV_TAG=$(device_serial)
FILES=$(app_ls files/benchmark)
[ -n "$FILES" ] || die "未发现报告文件（见 logcat）"
for f in $FILES; do
    pull_result files/benchmark "$f" "docs/phase0/bench-results/${DEV_TAG}_$f"
    echo "  已拉取: ${DEV_TAG}_$f"
done
echo "[6/6] 完成。设备 $DEV_TAG 结果位于 docs/phase0/bench-results/"
