#!/usr/bin/env bash
# G4 判定门一键基准（实施计划 P0-6）：三实现精确扫描 1k/5k/10k
# 前置：filesDir/models/ 已有 .litertlm（真实向量来源；无模型时用全随机向量，结果记 real=0）
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_g4_scan.sh
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.zhibox.memory"

cd "$(dirname "$0")/.."

if [ ! -f "$APK" ]; then
    echo "[构建] APK..."
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    ./gradlew assembleDebug
fi

echo "[1/4] 安装并清理旧结果"
"$ADB" install -r "$APK" | tail -1
"$ADB" shell "run-as $PKG rm -rf files/scan_bench" 2>/dev/null || true

echo "[2/4] 触发扫描基准（10k 向量构建 + 3 实现 × 3 规模 × 33 轮）"
"$ADB" logcat -c 2>/dev/null || true
"$ADB" shell "input keyevent KEYCODE_WAKEUP" 2>/dev/null || true
"$ADB" shell "svc power stayon true" 2>/dev/null || true
"$ADB" shell "am force-stop $PKG"; sleep 1
"$ADB" shell am start -n "$PKG/.MainActivity" --ez autostart_scan true
echo "  轮询等待完成（最长 8 分钟）..."
for i in $(seq 1 48); do
    sleep 10
    DONE=$("$ADB" shell "run-as $PKG ls files/scan_bench" 2>/dev/null | grep -c "scan_bench_v1.json" | tr -d '\r')
    [ "$DONE" = "1" ] && { echo "  完成（$((i*10))s）"; break; }
done

if [ "${DONE:-0}" != "1" ]; then
    echo "超时未完成；logcat 摘要:"
    "$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | tail -10
    exit 1
fi

echo "[3/4] 拉取结果"
mkdir -p docs/phase0/g4-results
DEV_TAG=$("$ADB" get-serialno 2>/dev/null | tr -d '\r')
"$ADB" shell "run-as $PKG cat files/scan_bench/scan_bench_v1.json" | tr -d '\r' > "docs/phase0/g4-results/${DEV_TAG}_scan_bench_v1.json"
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
