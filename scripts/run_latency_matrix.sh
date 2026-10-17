#!/usr/bin/env bash
# 长度 × delegate 延迟矩阵基准（CPU+GPU 两遍，4 档长度）
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_latency_matrix.sh
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

build_apk_if_missing
require_adb

echo "[1/4] 安装并确认模型在位"
install_apk
app_ls files/models | grep -q "440m" || die "缺少 440M 模型（先投放到 files/models/）"
app_rm files/lenbench

echo "[2/4] 触发（CPU→GPU 两遍串行）"
wake_and_stayon
force_stop_app; sleep 1
start_autostart autostart_lenbench

echo "[3/4] 轮询（每 10s，超时 6min）"
len_done() {
  local n
  n=$(app_ls files/lenbench | grep -c '_v1.json' || true)
  log "  lenbench=$n/2"
  [ "$n" = "2" ]
}
poll_until "长度矩阵结果" 360 10 len_done || die "超时（结果不足 2 个；见 logcat）"

echo "[4/4] 拉取与汇总"
mkdir -p docs/phase1/lenbench-results
DEV_TAG=$(device_serial)
for m in cpu gpu; do
    pull_result files/lenbench "${m}_v1.json" "docs/phase1/lenbench-results/${DEV_TAG}_${m}_v1.json"
done
python3 - << 'EOF'
import json, glob
for mode in ("cpu", "gpu"):
    files = sorted(glob.glob(f"docs/phase1/lenbench-results/*_{mode}_v1.json"))
    for p in files:
        d = json.load(open(p))
        head = f"{d['device_model']}/{d['soc']}/A{d['android']} {mode.upper()}"
        if d.get("error"):
            print(f"{head}: 失败 → {d['error'][:80]}")
            continue
        cells = "  ".join(f"{r['chars']}字={r['p50_ms']:.0f}/{r['p95_ms']:.0f}ms" for r in d["rows"])
        print(f"{head}: {cells}   (RSS Δ{(d['rss_after_kb']-d['rss_before_kb'])//1024}MB)")
EOF
release_stayon
