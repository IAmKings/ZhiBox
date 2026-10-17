#!/usr/bin/env bash
# W3 评测集全流程（单模型一轮）：清库 → 推模型 → 导入 50 卡语料 → 当前 262 条查询跑批 → 拉取计分
# 评测输入主路径已是 files/search/queries.json；本脚本用兼容路径 files/eval/evalset.json。
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_eval.sh <model-file> <tag>
#   例: ANDROID_SERIAL=eipzae799dkfpffq ./scripts/run_eval.sh models/embeddinggemma-2-text-vision-440m.litertlm 440M
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

MODEL="${1:?usage: run_eval.sh <model-file> <tag>}"
TAG="${2:?need tag, e.g. 440M / 270M / 740M}"
N_DOCS=50

[ -f scripts/eval/evalset.json ] || python3 scripts/gen_eval_corpus.py
[ -f "$MODEL" ] || die "缺少模型：$MODEL"
require_adb
require_apk
# 本机 sideline 数据库副本（禁止固定临时路径）：mktemp -d + trap 清理
DB_DIR="$(mktemp -d "${TMPDIR:-/tmp}/zhibox-evaldb.XXXXXX")"
trap 'rm -rf "$DB_DIR"' EXIT

# 轮询 predicate：拉取设备数据库副本 → 统计 ready/failed 文档
import_done() {
  pull_db "$DB_DIR"
  READY=$(db_query "$DB_DIR/zhibox.sqlite3" "SELECT COUNT(*) FROM document WHERE status='ready'")
  FAIL=$(db_query "$DB_DIR/zhibox.sqlite3" "SELECT COUNT(*) FROM document WHERE status='failed'")
  log "  import: ready=$READY/$N_DOCS failed=$FAIL"
  [ "$READY" -ge "$N_DOCS" ] || [ "$FAIL" -gt 0 ]
}
# 轮询 predicate：eval_results 目录出现结果文件
eval_done() {
  local n
  n=$(app_ls files/eval_results | wc -l | tr -d ' ')
  log "  eval_results=$n"
  [ "$n" -ge 1 ]
}

echo "[1/6] install + wipe + push model ($TAG)"
reinstall_app
app_put "$MODEL" "files/models/$(basename "$MODEL")"

echo "[2/6] stage corpus (50 cards) + trigger import"
app_put_dir scripts/eval/corpus files/import-staging '*.txt'
echo "  已投放: $(app_ls files/import-staging | wc -l | tr -d ' ') 篇"
logcat_clear
force_stop_app; sleep 1
start_autostart autostart_import

echo "[3/6] wait import（轮询 20s，超时 8min；超时前不 force-stop）"
poll_until "import" 480 20 import_done || die "import 超时"
if [ "$READY" -lt "$N_DOCS" ]; then
  die "import incomplete (ready=$READY failed=$FAIL)"
fi

echo "[4/6] 投放评测集并触发跑批（scripts/eval/evalset.json 全量 262 条 × 三通道）"
app_put scripts/eval/evalset.json files/eval/evalset.json
app_rm files/eval_results
force_stop_app; sleep 1
start_autostart autostart_eval

echo "[5/6] 等待跑批完成（轮询 30s，超时 12min）"
poll_until "eval" 720 30 eval_done || die "eval 跑批超时（见 logcat）"

echo "[6/6] 拉取 + 计分"
mkdir -p docs/phase1/eval-results
F=$(latest_remote_file files/eval_results '.')
[ -n "$F" ] || die "未发现 eval_results 结果文件"
OUT="docs/phase1/eval-results/${TAG}_$F"
pull_result files/eval_results "$F" "$OUT"
python3 scripts/score_eval.py "$OUT" --tag "$TAG" | tee "docs/phase1/eval-results/${TAG}_score.json"
