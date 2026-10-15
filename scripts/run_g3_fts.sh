#!/usr/bin/env bash
# G3 判定门一键实验（实施计划 P0-5）：中文分词三方案对照
# 语料：docs/ 下四份中文规划文档（约 200KB）→ 切块 ≥200 chunks
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_g3_fts.sh
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.zhibox.memory"
TMP_DIR="/data/local/tmp/zhibox-g3"

cd "$(dirname "$0")/.."

if [ ! -f "$APK" ]; then
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    ./gradlew assembleDebug
fi

echo "[1/4] 安装并投放语料"
"$ADB" install -r "$APK" | tail -1
"$ADB" shell "rm -rf $TMP_DIR" 2>/dev/null || true
"$ADB" shell "run-as $PKG rm -rf files/g3 files/g3_results" 2>/dev/null || true
"$ADB" shell "mkdir -p $TMP_DIR"
for f in docs/Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md \
         docs/SAG_Android_OnDevice_Feasibility_PRD.md \
         docs/SAG_Android_OnDevice_Feasibility_Investigation_Report_v1.0.md \
         docs/SAG_Android_MVP_Implementation_Plan_v1.0.md \
         docs/PHASE0_REPORT.md \
         docs/UPSTREAM_WATCH.md \
         docs/UPSTREAM_VERSIONS.md \
         docs/reference/zleap-sag-0.14.0/README.md; do
    "$ADB" push "$f" "$TMP_DIR/$(basename "$f")" >/dev/null
done
"$ADB" shell "run-as $PKG mkdir -p files/g3/corpus"
for f in Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md \
         SAG_Android_OnDevice_Feasibility_PRD.md \
         SAG_Android_OnDevice_Feasibility_Investigation_Report_v1.0.md \
         SAG_Android_MVP_Implementation_Plan_v1.0.md \
         PHASE0_REPORT.md \
         UPSTREAM_WATCH.md \
         UPSTREAM_VERSIONS.md \
         README.md; do
    "$ADB" shell "run-as $PKG cp $TMP_DIR/$f files/g3/corpus/$f"
done
"$ADB" shell "rm -rf $TMP_DIR"

echo "[2/4] 触发 G3 对照（三方案 FTS4 索引 + 50 查询）"
"$ADB" shell "am force-stop $PKG"; sleep 1
"$ADB" shell am start -n "$PKG/.MainActivity" --ez autostart_g3 true
echo "  等待完成（约 1-2 分钟）..."
for i in $(seq 1 24); do
    sleep 5
    DONE=$("$ADB" shell "run-as $PKG ls files/g3_results 2>/dev/null | grep -c fts_bench_v1.json" | tr -d '\r')
    [ "$DONE" = "1" ] && { echo "  完成（$((i*5))s）"; break; }
done
[ "${DONE:-0}" = "1" ] || { echo "超时"; "$ADB" logcat -d -s AndroidRuntime:E | tail -10; exit 1; }

echo "[3/4] 拉取结果"
mkdir -p docs/phase0/g3-results
DEV_TAG=$("$ADB" get-serialno 2>/dev/null | tr -d '\r')
"$ADB" shell "run-as $PKG cat files/g3_results/fts_bench_v1.json" | tr -d '\r' > "docs/phase0/g3-results/${DEV_TAG}_fts_bench_v1.json"

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
