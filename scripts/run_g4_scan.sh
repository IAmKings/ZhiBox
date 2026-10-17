#!/usr/bin/env bash
# G4 判定门一键基准（实施计划 P0-6）：三实现精确扫描 1k/5k/10k
# 前置：filesDir/models/ 已有 .litertlm（真实向量来源；无模型时用全随机向量，结果记 real=0）
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_g4_scan.sh
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

build_apk_if_missing
require_adb

echo "[1/4] 安装并清理旧结果"
install_apk
app_rm files/scan_bench

echo "[2/4] 触发扫描基准（10k 向量构建 + 3 实现 × 3 规模 × 33 轮）"
logcat_clear
wake_and_stayon
force_stop_app; sleep 1
start_autostart autostart_scan
echo "  轮询等待完成（每 10s，超时 8min）..."
g4_done() { app_ls files/scan_bench | grep -q 'scan_bench_v1.json'; }
poll_until "G4 结果" 480 10 g4_done || die "G4 扫描超时（见 logcat）"

echo "[3/4] 拉取结果"
mkdir -p docs/phase0/g4-results
DEV_TAG=$(device_serial)
pull_result files/scan_bench scan_bench_v1.json "docs/phase0/g4-results/${DEV_TAG}_scan_bench_v1.json"
ls -la docs/phase0/g4-results/

echo "[4/4] 摘要"
python3 - << 'EOF'
import json, glob
for p in sorted(glob.glob("docs/phase0/g4-results/*.json")):
    d = json.load(open(p))
    print(f"\n{p}")
    print(f"  vectors: real={d['real_vectors']} random={d['random_vectors']} dim={d['dim']}")
    bt = d["build_times"]
    print(f"  build(ms): sqlite={bt['sqlite_blob_ms']} flat={bt['mmap_flat_ms']} preload={bt['preloaded_ms']}")
    print(f"  {'impl':12} {'n':>6} {'cold':>8} {'p50':>8} {'p95':>8}")
    for r in d["results"]:
        print(f"  {r['impl']:12} {r['n']:>6} {r['cold_ms']:>8} {r['p50_ms']:>8} {r['p95_ms']:>8}")
    print(f"  top10 一致性: {d['consistency_top10']}")
    g = d["gates"]
    print(f"  gates: 1k p95={g['best_p95_1k']}ms(≤150:{g['p95_1k_le_150ms']}) 10k p95={g['best_p95_10k']}ms(≤800:{g['p95_10k_le_800ms']})")
    print(f"  G4_PASS = {d['g4_pass']}")
EOF
