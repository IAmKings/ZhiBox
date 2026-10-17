#!/usr/bin/env bash
# W2 检索验收（§9.1-6 Top-K+原文定位+去重 / §9.1-8 无 LLM 检索可用）
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_search_eval.sh [queries.json]
# 输入缺省使用内置 8 条中文查询（需机上已有导入数据；命中率取决于库内容）
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

require_adb
require_apk

QUERIES="${1:-}"
QUERIES_DIR=""
if [ -z "$QUERIES" ]; then
  # 临时查询集：mktemp -d + trap 清理（避免 mktemp 原文件泄漏；禁止固定 /tmp 文件名）
  QUERIES_DIR=$(mktemp -d -t zhibox_queries)
  QUERIES="$QUERIES_DIR/queries.json"
  trap '[ -n "${QUERIES_DIR:-}" ] && rm -rf "$QUERIES_DIR"' EXIT
  cat > "$QUERIES" << 'EOF'
{"queries": ["加速度和位移是什么关系", "古代东西方贸易通道", "Java 内存怎么回收", "复利是怎么计算的", "蓝牙断开怎么恢复", "光合作用在哪里发生", "爱因斯坦的名言", "HTTP 404 是什么意思"]}
EOF
fi

echo "[1/4] 安装 APK"
install_apk

echo "[2/4] 投放查询集"
app_rm files/search
app_rm files/search_results
app_put "$QUERIES" files/search/queries.json

echo "[3/4] 触发无头检索验收（首查含引擎冷加载，约 30-90s）"
logcat_clear
force_stop_app; sleep 1
start_autostart autostart_search_eval
echo "  轮询等待结果（每 10s，超时 5min）..."
search_done() { [ -n "$(app_ls files/search_results)" ]; }
poll_until "检索验收结果" 300 10 search_done || die "检索验收超时（见 logcat）"

echo "[4/4] 拉取与摘要"
mkdir -p docs/phase1/search-results
DEV_TAG=$(device_serial)
F=$(latest_remote_file files/search_results '.')
[ -n "$F" ] || die "未发现 search_results 结果文件"
pull_result files/search_results "$F" "docs/phase1/search-results/${DEV_TAG}_$F"
python3 - << 'EOF'
import json, glob
p = sorted(glob.glob("docs/phase1/search-results/*.json"))[-1]
d = json.load(open(p))
print(f"\n{p}")
print(f"  queries={d['n_queries']}  total_hits={d['total_hits']}  refused={d['refused']}")
for r in d["rows"]:
    if r.get("refused"):
        print(f"  [{r['query']}] 拒绝: {r['refused'][:60]}…")
        continue
    if r.get("error"):
        print(f"  [{r['query']}] 错误: {r['error'][:80]}")
        continue
    top = r.get("hits") or []
    first = top[0] if top else None
    print(f"  [{r['query']}] {r['elapsed_ms']}ms 命中{len(top)} "
          f"候选v{r['vector_candidates']}/k{r['keyword_candidates']} "
          f"| Top1: {first['doc'] if first else '—'} {first['channels'] if first else ''} {first['char'] if first else ''}")
EOF
