#!/usr/bin/env bash
# G3 判定门一键实验（实施计划 P0-5）：中文分词三方案对照
# 语料：docs/ 下四份中文规划文档（约 200KB）→ 切块 ≥200 chunks
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_g3_fts.sh
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/common.sh

build_apk_if_missing
require_adb

DOCS=(
  docs/Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md
  docs/SAG_Android_OnDevice_Feasibility_PRD.md
  docs/SAG_Android_OnDevice_Feasibility_Investigation_Report_v1.0.md
  docs/SAG_Android_MVP_Implementation_Plan_v1.0.md
  docs/PHASE0_REPORT.md
  docs/UPSTREAM_WATCH.md
  docs/UPSTREAM_VERSIONS.md
  docs/reference/zleap-sag-0.14.0/README.md
)

echo "[1/4] 安装并投放语料"
install_apk
app_rm files/g3
app_rm files/g3_results
app_mkdir files/g3/corpus
for f in "${DOCS[@]}"; do
  [ -f "$f" ] || die "缺少语料文件：$f"
  app_put "$f" "files/g3/corpus/$(basename "$f")"
done

echo "[2/4] 触发 G3 对照（三方案 FTS4 索引 + 50 查询）"
logcat_clear
force_stop_app; sleep 1
start_autostart autostart_g3
echo "  轮询等待完成（每 5s，超时 2min）..."
g3_done() { app_ls files/g3_results | grep -q 'fts_bench_v1.json'; }
poll_until "G3 结果" 120 5 g3_done || die "G3 实验超时（见 logcat）"

echo "[3/4] 拉取结果"
mkdir -p docs/phase0/g3-results
DEV_TAG=$(device_serial)
pull_result files/g3_results fts_bench_v1.json "docs/phase0/g3-results/${DEV_TAG}_fts_bench_v1.json"

echo "[4/4] 摘要"
python3 - << 'EOF'
import json, glob
for p in sorted(glob.glob("docs/phase0/g3-results/*.json")):
    d = json.load(open(p))
    print(f"\n{p}")
    print(f"  语料: {d['corpus_files']} 文件 → {d['chunks']} chunks；查询 {d['queries']} 条")
    for b in d["build"]:
        print(f"  build {b['scheme']:7} {b['build_ms']}ms  内容字节 {b['content_bytes']}")
    for r in d["results"]:
        print(f"  {r['scheme']:7} hit={r['hit_rate']:.2f}  平均返回={r['avg_results']:.1f}  误报率={r['false_positive_rate']:.2f}  索引膨胀={r['index_bloat']}x")
    v = d["verdict"]
    print(f"  选定方案: {v['chosen_scheme']}  G3_PASS = {v['g3_pass']}")
EOF
