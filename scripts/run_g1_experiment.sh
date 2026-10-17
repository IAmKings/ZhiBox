#!/usr/bin/env bash
# G1 判定门一键实验（实施计划 P0-3）
# 前置：scripts/g1/reference.json 已由 gen_reference.py 生成
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_g1_experiment.sh [model.litertlm]
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

REF="scripts/g1/reference.json"
MODEL="${1:-models/embeddinggemma-2-text-vision-440m.litertlm}"

[ -f "$REF" ] || die "缺少 $REF；先运行: .venv-g1/bin/python scripts/g1/gen_reference.py"
[ -f "$MODEL" ] || die "缺少模型 $MODEL"
require_adb
require_apk

echo "[1/5] 安装 APK"
install_apk

echo "[2/5] 投放参考向量与模型"
app_rm files/g1
app_rm files/g1_results
app_put "$REF" files/g1/reference.json
app_put "$MODEL" "files/models/$(basename "$MODEL")"

echo "[3/5] 触发 G1 对照（三模板 × 50 样本 × 双参考）"
logcat_clear
force_stop_app; sleep 1
start_autostart autostart_g1
echo "  轮询等待完成（每 10s，超时 10min）..."
g1_done() { [ -n "$(app_ls files/g1_results)" ]; }
poll_until "G1 结果" 600 10 g1_done || die "G1 实验超时（见 logcat）"

echo "[4/5] 拉取结果"
mkdir -p docs/phase0/g1-results
DEV_TAG=$(device_serial)
FILES=$(app_ls files/g1_results)
[ -n "$FILES" ] || die "未发现 G1 结果文件"
for f in $FILES; do
    pull_result files/g1_results "$f" "docs/phase0/g1-results/${DEV_TAG}_$f"
    echo "  已拉取: ${DEV_TAG}_$f"
done

echo "[5/5] 判定摘要"
python3 - << 'EOF'
import json, glob
for p in sorted(glob.glob("docs/phase0/g1-results/*.json")):
    d = json.load(open(p))
    v = d.get("verdict", {})
    r = d.get("retrieval_check") or {}
    print(f"\n{p}")
    print(f"  model: {d.get('model_file')}  threshold: {d.get('threshold')}")
    for s in ("scheme_app_prefix", "scheme_runtime_default"):
        sv = v.get(s, {})
        print(f"  {s}: pass={sv.get('pass')}  doc_min={sv.get('doc_side_min')}  query_min={sv.get('query_side_min')}")
    print(f"  template_gap_confirmed: {v.get('template_gap_confirmed')}")
    if r:
        print(f"  retrieval: R@1={r.get('recall_at_1')} R@3={r.get('recall_at_3')} R@5={r.get('recall_at_5')} MRR={r.get('mrr')} (n={r.get('n_pairs')})")
    print(f"  G1_PASS = {v.get('g1_pass')}")
EOF
