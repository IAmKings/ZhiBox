#!/usr/bin/env bash
# 长度 × delegate 延迟矩阵基准（CPU+GPU 两遍，4 档长度）
# 用法: ANDROID_SERIAL=<serial> ./scripts/run_latency_matrix.sh
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.zhibox.memory"

cd "$(dirname "$0")/.."

if [ ! -f "$APK" ]; then
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    ./gradlew assembleDebug
fi

echo "[1/4] 安装并确认模型在位"
"$ADB" install -r "$APK" | tail -1
MODELS=$("$ADB" shell "run-as $PKG ls files/models 2>/dev/null" | tr -d '\r')
echo "$MODELS" | grep -q "440m" || { echo "缺少 440M 模型"; exit 1; }
"$ADB" shell "run-as $PKG rm -rf files/lenbench" 2>/dev/null || true

echo "[2/4] 触发（CPU→GPU 两遍串行）"
"$ADB" shell "input keyevent KEYCODE_WAKEUP" 2>/dev/null || true
"$ADB" shell "svc power stayon true" 2>/dev/null || true
"$ADB" shell "am force-stop $PKG"; sleep 1
"$ADB" shell am start -n "$PKG/.MainActivity" --ez autostart_lenbench true >/dev/null

echo "[3/4] 轮询（最长 6 分钟）"
for i in $(seq 1 36); do
    sleep 10
    N=$("$ADB" shell "run-as $PKG ls files/lenbench 2>/dev/null | grep -c _v1.json" | tr -d '\r')
    [ "$N" = "2" ] && { echo "  完成（$((i*10))s）"; break; }
done
N=$("$ADB" shell "run-as $PKG ls files/lenbench 2>/dev/null | grep -c _v1.json" | tr -d '\r')
[ "$N" = "2" ] || { echo "超时（$N/2 个结果）；logcat:"; "$ADB" logcat -d -s AndroidRuntime:E | tail -8; exit 1; }

echo "[4/4] 拉取与汇总"
mkdir -p docs/phase1/lenbench-results
DEV_TAG=$("$ADB" get-serialno 2>/dev/null | tr -d '\r')
for m in cpu gpu; do
    "$ADB" shell "run-as $PKG cat files/lenbench/${m}_v1.json" | tr -d '\r' > "docs/phase1/lenbench-results/${DEV_TAG}_${m}_v1.json"
done
python3 - << EOF
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
"$ADB" shell "svc power stayon false" 2>/dev/null || true
