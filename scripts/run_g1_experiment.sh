#!/usr/bin/env bash
# G1 判定门一键实验（实施计划 P0-3）
# 前置：scripts/g1/reference.json 已由 gen_reference.py 生成
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_g1_experiment.sh [model.litertlm]
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.zhibox.memory"
TMP_DIR="/data/local/tmp/zhibox-g1"
REF="scripts/g1/reference.json"

cd "$(dirname "$0")/.."

[ -f "$REF" ] || { echo "缺少 $REF；先运行: .venv-g1/bin/python scripts/g1/gen_reference.py"; exit 1; }

MODEL="${1:-models/embeddinggemma-2-text-vision-440m.litertlm}"
[ -f "$MODEL" ] || { echo "缺少模型 $MODEL"; exit 1; }

echo "[1/5] 安装 APK"
"$ADB" install -r "$APK" | tail -1

echo "[2/5] 投放参考向量与模型"
"$ADB" shell "rm -rf $TMP_DIR" 2>/dev/null || true
"$ADB" shell "run-as $PKG rm -rf files/g1 files/g1_results" 2>/dev/null || true
"$ADB" shell "mkdir -p $TMP_DIR"
"$ADB" push "$REF" "$TMP_DIR/reference.json" | tail -1
"$ADB" push "$MODEL" "$TMP_DIR/model.litertlm" | tail -1
"$ADB" shell "run-as $PKG mkdir -p files/g1 files/models"
"$ADB" shell "run-as $PKG cp $TMP_DIR/reference.json files/g1/reference.json"
"$ADB" shell "run-as $PKG cp $TMP_DIR/model.litertlm files/models/$(basename "$MODEL")"
"$ADB" shell "rm -rf $TMP_DIR"

echo "[3/5] 触发 G1 对照（三模板 × 50 样本 × 双参考）"
"$ADB" logcat -c 2>/dev/null || true
"$ADB" shell "am force-stop $PKG"; sleep 1
"$ADB" shell am start -n "$PKG/.MainActivity" --ez autostart_g1 true
echo "  等待完成（约 2-4 分钟）..."
sleep 120

echo "[4/5] 拉取结果"
mkdir -p docs/phase0/g1-results
DEV_TAG=$("$ADB" get-serialno 2>/dev/null | tr -d '\r')
FILES=$("$ADB" shell "run-as $PKG ls files/g1_results" 2>/dev/null | tr -d '\r')
if [ -z "$FILES" ]; then
    echo "未发现结果；logcat 摘要:"
    "$ADB" logcat -d -s AndroidRuntime:E | tail -15
    exit 1
fi
for f in $FILES; do
    "$ADB" shell "run-as $PKG cat files/g1_results/$f" | tr -d '\r' > "docs/phase0/g1-results/${DEV_TAG}_$f"
    echo "  已拉取: ${DEV_TAG}_$f"
done

echo "[5/5] 判定摘要"
python3 - << 'EOF'
import json, glob
for p in sorted(glob.glob("docs/phase0/g1-results/*.json")):
    d = json.load(open(p))
    v = d.get("verdict", {})
    print(f"\n{p}")
    print(f"  model: {d.get('model_file')}  threshold: {d.get('threshold')}")
    for s in ("scheme_app_prefix", "scheme_runtime_default"):
        sv = v.get(s, {})
        print(f"  {s}: pass={sv.get('pass')}  doc_min={sv.get('doc_side_min')}  query_min={sv.get('query_side_min')}")
    print(f"  G1_PASS = {v.get('g1_pass')}")
EOF
