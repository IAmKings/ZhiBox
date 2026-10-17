#!/usr/bin/env bash
# W4-6 功耗/温度长跑报告（§9.2）：负载序列（导入 50 篇 → 评测跑批 262 条）× 温度/电量采样。
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_thermal.sh [model]
# 前置: models/ 有评测语料与模型；脚本自含（清库→导入→检索→采样→报告）
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

MODEL="${1:-models/embeddinggemma-2-text-vision-440m.litertlm}"
[ -f "$MODEL" ] || die "缺少模型：$MODEL"
require_adb
require_apk

sample() {  # label temp deci-C battery% -- 采样属于负载曲线，保留固定采样节奏（非等待）
  local label="$1" out temp level
  out=$(adb shell dumpsys battery 2>/dev/null | tr -d '\r')
  temp=$(echo "$out" | grep -i temperature | awk '{print $2}' | head -1)
  level=$(echo "$out" | grep -i level | awk '{print $2}' | head -1)
  echo "$(date +%H:%M:%S) $label temp=$temp(0.1C) battery=$level%"
}

echo "[1/5] install + clean + stage"
reinstall_app
app_put "$MODEL" "files/models/$(basename "$MODEL")"
app_put_dir scripts/eval/corpus files/import-staging '*.txt'
N=$(app_ls files/import-staging | wc -l | tr -d ' ')
echo "staged $N docs"

REPORT="docs/phase1/thermal/thermal_$(date +%m%d_%H%M).txt"
mkdir -p "$(dirname "$REPORT")"
sample "baseline" > "$REPORT"

echo "[2/5] import load ($N docs)"
force_stop_app; sleep 1
start_autostart autostart_import
for i in $(seq 1 6); do
  sleep 30
  sample "import+$((i*30))s" >> "$REPORT"
done

echo "[3/5] search load (eval run 262 queries)"
app_put scripts/eval/evalset.json files/eval/evalset.json
app_rm files/eval_results
force_stop_app; sleep 1
start_autostart autostart_eval
# 采样 + 完成条件合并：每 30s 采样一次并检查 eval_results 是否出现（超时仅告警，保持原宽松语义）
search_sample() {
  sample "search_load" >> "$REPORT"
  local r; r=$(app_ls files/eval_results | grep -c evalrun || true)
  [ "$r" -ge 1 ]
}
poll_until "search load" 240 30 search_sample || warn "搜索跑批未在 240s 内完成（继续拉取已有结果）"
sample "post-load" >> "$REPORT"

echo "[4/5] cooldown sampling (3min)"
for i in $(seq 1 3); do
  sleep 60
  sample "cooldown+$((i*60))s" >> "$REPORT"
done

echo "[5/5] pull eval result + report"
F=$(latest_remote_file files/eval_results 'evalrun')
mkdir -p docs/phase1/eval-results
if [ -n "$F" ]; then
  pull_result files/eval_results "$F" "docs/phase1/eval-results/thermal_$F" 2>/dev/null || true
fi
sample "final" >> "$REPORT"
cat "$REPORT"
echo "report: $REPORT"
