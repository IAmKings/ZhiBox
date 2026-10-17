#!/usr/bin/env bash
# 连通性矩阵探针：catalog EGemma2 模型 × 通道（UE/LM/LM-NPU）× 后端，每格 15s 超时
# B7 起 Path C（.task/tasks-text）已下线；模型仅 .litertlm
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_probe.sh
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

require_adb
require_apk

echo "[1/3] install + stage ALL models"
install_apk
app_mkdir files/models
for f in models/*.litertlm; do
  [ -f "$f" ] || continue
  app_put "$f" "files/models/$(basename "$f")"
done
app_ls files/models

echo "[2/3] trigger probe (all models x channel x backend, 15s timeout/cell)"
logcat_clear
force_stop_app; sleep 1
start_autostart autostart_probe

echo "[3/3] poll (每 30s，超时 15min)"
F=""
probe_done() {
  F=$(app_ls files/probe_results | { grep matrix || true; } | sort | tail -1)
  log "  poll: ${F:-none}"
  [ -n "$F" ]
}
poll_until "probe 矩阵结果" 900 30 probe_done || { logcat_errors; exit 1; }

mkdir -p docs/phase1/probe-results
DEV=$(device_model)
pull_result files/probe_results "$F" "docs/phase1/probe-results/${DEV}_$F"
python3 - "docs/phase1/probe-results/${DEV}_$F" << 'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"device: {d['device']['model']} ({d['device']['soc']})  models={d['n_models']} cells={d['n_cells']} ok={d['ok']} timeouts={d['timeouts']}")
print(f"{'model':<52}{'cell':<10}{'ok':<5}{'dims':<6}{'ms':<7}err")
for r in d['rows']:
    cell = f"{r['channel']}-{r['backend']}"
    print(f"{r['model'][:50]:<52}{cell:<10}{str(r.get('ok')):<5}{r.get('dims','-'):<6}{r.get('first_embed_ms','-'):<7}{str(r.get('error',''))[:70]}")
EOF
