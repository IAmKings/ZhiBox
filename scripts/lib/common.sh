#!/usr/bin/env bash
# scripts/lib/common.sh —— 真机实验脚本公共函数库（B6-2）
#
# 用法（各 scripts/run_*.sh 顶部）：
#   set -euo pipefail
#   cd "$(dirname "$0")/.."
#   source scripts/lib/common.sh
#
# 约定：
# - ANDROID_HOME：SDK 根（缺省 macOS 常规路径）；ANDROID_SERIAL 由 adb 原生识别（多设备时生效）；
# - APK 缺省 app/build/outputs/apk/debug/app-debug.apk，包名 com.zhibox.memory；
# - 主机临时目录一律 mktemp -d + 调用脚本 trap 清理（禁止固定 /tmp/xxx）；
# - 等条件统一 poll_until（超时打印 logcat 错误并返回 1，由调用脚本 die）；
# - 模型/语料投放到应用私有目录统一走 app_put（/data/local/tmp 中转 + run-as cp，
#   规避 Android 10+ scoped storage 下 /sdcard/Android/data 属主问题）。
# shellcheck shell=bash

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="${PKG:-com.zhibox.memory}"

log()  { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_adb() { [ -x "$ADB" ] || die "未找到 adb：$ADB（请设置 ANDROID_HOME）"; }
adb() { "$ADB" "$@"; }
device_serial() { adb get-serialno 2>/dev/null | tr -d '\r'; }
device_tag() { adb get-serialno 2>/dev/null | tr -d '\r'; }
device_model() { adb shell getprop ro.product.model 2>/dev/null | tr -d '\r'; }

require_apk() { [ -f "$APK" ] || die "缺少 APK：$APK（先 ./gradlew app:assembleDebug）"; }
build_apk_if_missing() {
  [ -f "$APK" ] && return 0
  export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
  log "[build] assembleDebug（$APK 不存在）"
  ./gradlew app:assembleDebug
}

install_apk() { adb install -r "$APK" | tail -1; }
uninstall_app() { adb uninstall "$PKG" >/dev/null 2>&1 || true; }
# MIUI 拒绝 shell pm clear（CLEAR_APP_USER_DATA）→ 卸载重装清库
reinstall_app() { install_apk; uninstall_app; install_apk; }

force_stop_app() { adb shell "am force-stop $PKG"; }
start_app() { adb shell am start -n "$PKG/.MainActivity" "$@"; }
start_autostart() { start_app --ez "$1" "${2:-true}"; }
wake_and_stayon() {
  adb shell "input keyevent KEYCODE_WAKEUP" 2>/dev/null || true
  adb shell "svc power stayon true" 2>/dev/null || true
}
release_stayon() { adb shell "svc power stayon false" 2>/dev/null || true; }
logcat_clear() { adb logcat -c 2>/dev/null || true; }
logcat_errors() { adb logcat -d -s AndroidRuntime:E 2>/dev/null | tail -15; }

app_mkdir() { adb shell "run-as $PKG mkdir -p '$1'" >/dev/null; }
app_rm() { adb shell "run-as $PKG rm -rf '$1'" 2>/dev/null || true; }
app_ls() { adb shell "run-as $PKG ls '$1' 2>/dev/null" | tr -d '\r'; }
app_cat() { adb shell "run-as $PKG cat '$1'" 2>/dev/null | tr -d '\r'; }

# 本机文件 → 应用私有目录（/data/local/tmp 中转，run-as cp）
app_put() {
  local src="$1"
  local rel="$2"
  local tmp
  tmp="/data/local/tmp/zxb_put_$$_$(basename "$rel")"
  [ -f "$src" ] || die "缺少文件：$src"
  adb push "$src" "$tmp" >/dev/null
  adb shell "run-as $PKG mkdir -p '$(dirname "$rel")' && run-as $PKG cp '$tmp' '$rel' && rm -f '$tmp'" >/dev/null
}

# 整目录批量投放：app_put_dir <host-dir> <app-dir> [glob, 默认 *]
app_put_dir() {
  local src_dir="$1" app_dir="$2" pattern="${3:-*}" f
  app_rm "$app_dir"; app_mkdir "$app_dir"
  for f in "$src_dir"/$pattern; do [ -f "$f" ] && app_put "$f" "$app_dir/$(basename "$f")"; done
}

# 拉取应用数据库（含 WAL/SHM）到本机目录；调用方负责 mktemp -d + trap 清理
pull_db() {
  local dest="$1" f
  mkdir -p "$dest"
  for f in zhibox.sqlite3 zhibox.sqlite3-wal zhibox.sqlite3-shm; do
    adb shell "run-as $PKG cat databases/$f" > "$dest/$f" 2>/dev/null || true
  done
}
db_query() { sqlite3 "$1" "$2" 2>/dev/null || echo 0; }

# 远端目录中匹配 pattern 的最新文件（按文件名排序；无匹配返回空）
latest_remote_file() { app_ls "$1" | { grep -E "$2" || true; } | sort | tail -1; }
# 拉取远端结果文件到本机
pull_result() { app_cat "$1/$2" > "$3"; }

# 轮询直到 predicate 函数返回 0：poll_until <描述> <超时秒> <间隔秒> <predicate-fn>
poll_until() {
  local desc="$1" timeout="$2" interval="$3" predicate="$4" waited=0
  while :; do
    if "$predicate"; then log "  [ok] $desc（${waited}s）"; return 0; fi
    if [ "$waited" -ge "$timeout" ]; then
      log "  [超时] $desc（${timeout}s）"; logcat_errors; return 1
    fi
    sleep "$interval"; waited=$((waited + interval)); log "  [poll ${waited}s] $desc"
  done
}
