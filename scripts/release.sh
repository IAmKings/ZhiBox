#!/usr/bin/env bash
# ============================================================================
# B4-6 发布链路：构建 APK ->（可选）签名 -> 验签/manifest 检查 -> 归档 dist/ + sha256
#
# 用法：
#   ./scripts/release.sh              # release：构建 (unsigned 或 ~/.gradle/gradle.properties
#                                     #          中 ZHIBOX_* 已配置时由 AGP 直接签名)
#   ./scripts/release.sh --debug      # debug：构建内部验收包（debug key 自动签名）
#
# WITH 签名（用户自备密钥，仅环境变量注入，绝不入库/写入脚本）：
#   ANDROID_KEYSTORE_FILE=/abs/path/zhibox-release.jks \
#   ANDROID_KEYSTORE_PASSWORD=*** ANDROID_KEY_ALIAS=zhibox ANDROID_KEY_PASSWORD=*** \
#   ./scripts/release.sh
#   -> 对 unsigned release APK 执行 zipalign + apksigner sign，产物名带 -signed
#
# 产物命名（spec B4-6）：
#   dist/zhibox-<versionName>-<gitSha7>-<release|debug>[-signed].apk
#   dist/...apk.sha256   （SHA-256 校验和，与 APK 同目录）
#
# 退出码：0 成功归档；1 构建/校验失败；2 参数错误。
# 前置：JAVA_HOME 指向 JDK 17+（未设置则用 PATH 中 java）；Android SDK 经
#       ANDROID_HOME/ANDROID_SDK_ROOT 或 local.properties 的 sdk.dir 指定。
# 详细密钥 provision / OEM 安装 / 验收 / 回滚见 docs/RELEASE.md。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="release"
for arg in "$@"; do
  case "$arg" in
    --debug) MODE="debug" ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数: ${arg}（支持 --debug / --help）" >&2; exit 2 ;;
  esac
done

log()  { printf '\n[release] %s\n' "$*"; }
warn() { printf '\n[WARN] %s\n' "$*" >&2; }
die()  { printf '\n[ERROR] %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 0) 环境：JDK 与 Android SDK / build-tools
# ---------------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  warn "JAVA_HOME 未设置，使用 PATH 中的 java（需 JDK 17+）"
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1 | sed 's/\\:/:/g')"
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "未找到 Android SDK：请设置 ANDROID_HOME/ANDROID_SDK_ROOT，或在本机 local.properties 写 sdk.dir"
[ -x ./gradlew ] || die "缺少 ./gradlew（工作目录应为仓库根：$(pwd)）"

# 取最新 build-tools（用版本排序；BSD sort 无 -V 时退化为字典序）
BT_LIST="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sed 's:/$::' || true)"
[ -n "$BT_LIST" ] || die "Android SDK 下没有 build-tools（$SDK/build-tools）"
if sort -V </dev/null >/dev/null 2>&1; then
  BUILD_TOOLS="$(printf '%s\n' "$BT_LIST" | sort -V | tail -1)"
else
  BUILD_TOOLS="$(printf '%s\n' "$BT_LIST" | sort | tail -1)"
fi
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
log "Android SDK: $SDK"
log "Build tools: $BUILD_TOOLS"

# ---------------------------------------------------------------------------
# 1) 版本信息：versionName（来自 app/build.gradle.kts）+ git sha
# ---------------------------------------------------------------------------
VERSION_NAME="$(grep -E '^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"' app/build.gradle.kts \
  | head -1 | sed -E 's/.*"([^"]+)".*/\1/' || true)"
[ -n "$VERSION_NAME" ] || die "无法从 app/build.gradle.kts 解析 versionName"
GIT_SHA="$(git rev-parse --short=7 HEAD 2>/dev/null || echo unknown)"
GIT_DIRTY=""
# 仅把"已跟踪文件改动"视为 dirty；未跟踪文件（如本地 Trellis/IDE 目录）不应污染发布包名
if [ -n "$(git status --porcelain --untracked-files=no 2>/dev/null | head -1 || true)" ]; then
  GIT_DIRTY="-dirty"
  warn "工作区有未提交改动：产物标记为 ${GIT_SHA}${GIT_DIRTY}（发布请使用干净工作区）"
fi
log "versionName=$VERSION_NAME  git=${GIT_SHA}${GIT_DIRTY}  mode=$MODE"

# ---------------------------------------------------------------------------
# 2) 构建
# ---------------------------------------------------------------------------
if [ "$MODE" = "debug" ]; then
  TASK=":app:assembleDebug"
else
  TASK=":app:assembleRelease"
fi
# 审查修复：先清理上次 release 产物，避免 assembleRelease 为 UP-TO-DATE 时把旧 signed 包
# （可能是旧签名/旧版本）误当成本次产物归档。
rm -f app/build/outputs/apk/release/app-release.apk \
      app/build/outputs/apk/release/app-release-unsigned.apk
log "构建 $TASK"
./gradlew "$TASK"

# ---------------------------------------------------------------------------
# 3) 定位构建产物（release 可能是 signed(app-release.apk) 或 unsigned）
# ---------------------------------------------------------------------------
APK=""
ALREADY_SIGNED=0
if [ "$MODE" = "debug" ]; then
  APK="app/build/outputs/apk/debug/app-debug.apk"
  ALREADY_SIGNED=1   # debug 包由 debug keystore 自动签名
  [ -f "$APK" ] || die "未找到 debug APK：$APK"
else
  if [ -f app/build/outputs/apk/release/app-release.apk ]; then
    APK="app/build/outputs/apk/release/app-release.apk"
    ALREADY_SIGNED=1 # 由 ~/.gradle/gradle.properties 的 ZHIBOX_* signingConfig 签名
    SIGNED_SUFFIX="-signed"
    log "检测到 AGP 已签名 release APK（ZHIBOX_* signingConfig）"
  elif [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
    APK="app/build/outputs/apk/release/app-release-unsigned.apk"
  else
    die "未找到 release APK（app/build/outputs/apk/release/）"
  fi
fi
log "构建产物: $APK"

# ---------------------------------------------------------------------------
# 4) 签名（仅 release 且当前 unsigned 时）
# ---------------------------------------------------------------------------
FINAL_APK="$APK"
SIGNED_SUFFIX=""
WORKDIR=""
cleanup() { [ -z "${WORKDIR:-}" ] || rm -rf "$WORKDIR"; return 0; }
trap cleanup EXIT

if [ "$MODE" = "release" ] && [ "$ALREADY_SIGNED" -eq 0 ]; then
  KEYSTORE="${ANDROID_KEYSTORE_FILE:-}"
  if [ -n "$KEYSTORE" ]; then
    : "${ANDROID_KEYSTORE_PASSWORD:?已提供 ANDROID_KEYSTORE_FILE，但缺少 ANDROID_KEYSTORE_PASSWORD}"
    : "${ANDROID_KEY_ALIAS:?已提供 ANDROID_KEYSTORE_FILE，但缺少 ANDROID_KEY_ALIAS}"
    : "${ANDROID_KEY_PASSWORD:?已提供 ANDROID_KEYSTORE_FILE，但缺少 ANDROID_KEY_PASSWORD}"
    [ -f "$KEYSTORE" ] || die "keystore 不存在: ${KEYSTORE}（密钥不入库，见 docs/RELEASE.md）"
    [ -x "$ZIPALIGN" ] || die "缺少 zipalign: $ZIPALIGN"
    [ -x "$APKSIGNER" ] || die "缺少 apksigner: $APKSIGNER"

    WORKDIR="$(mktemp -d)"
    log "zipalign + apksigner 签名（密钥仅来自环境变量，不落盘）"
    "$ZIPALIGN" -p -f 4 "$APK" "$WORKDIR/aligned.apk"
    # 口令通过 env: 前缀读取，避免出现在进程命令行/日志
    "$APKSIGNER" sign \
      --ks "$KEYSTORE" \
      --ks-key-alias "$ANDROID_KEY_ALIAS" \
      --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
      --key-pass env:ANDROID_KEY_PASSWORD \
      --out "$WORKDIR/signed.apk" \
      "$WORKDIR/aligned.apk"
    FINAL_APK="$WORKDIR/signed.apk"
    SIGNED_SUFFIX="-signed"
    ALREADY_SIGNED=1
  else
    warn "未提供 ANDROID_KEYSTORE_FILE（且 gradle.properties 无 ZHIBOX_* 签名配置）："
    warn "release APK 为 unsigned，不能对外分发。签名方法见 docs/RELEASE.md。"
  fi
fi

# 统一产物后缀：release 且已签名（AGP 或 apksigner）→ -signed；debug 保持原命名
if [ "$MODE" = "release" ] && [ "$ALREADY_SIGNED" -eq 1 ]; then
  SIGNED_SUFFIX="-signed"
else
  SIGNED_SUFFIX=""
fi

# ---------------------------------------------------------------------------
# 5) 校验：manifest（debuggable/version/package）+ 签名
# ---------------------------------------------------------------------------
if [ -x "$AAPT2" ]; then
  BADGING="$("$AAPT2" dump badging "$FINAL_APK" 2>/dev/null || true)"
  PKG_LINE="$(printf '%s\n' "$BADGING" | grep -m1 '^package:' || true)"
  TARGET_LINE="$(printf '%s\n' "$BADGING" | grep -m1 '^targetSdkVersion:' || true)"
  log "APK: ${PKG_LINE:-<no package line>} ${TARGET_LINE}"
  if printf '%s\n' "$BADGING" | grep -q 'application-debuggable'; then
    if [ "$MODE" = "release" ]; then
      die "release APK 标记为 debuggable——发布阻断（检查 buildTypes.release.isDebuggable / Manifest）"
    fi
  elif [ "$MODE" = "release" ]; then
    log "OK: release APK 非 debuggable"
  fi
else
  warn "未找到 aapt2（${AAPT2}），跳过 manifest 校验"
fi

if [ "$ALREADY_SIGNED" -eq 1 ]; then
  [ -x "$APKSIGNER" ] || die "缺少 apksigner，无法验签"
  log "apksigner verify --print-certs"
  "$APKSIGNER" verify --print-certs "$FINAL_APK" | sed -n '1,6p'
  log "OK: 签名校验通过"
else
  warn "产物未签名：跳过 apksigner verify（apksigner verify 对 unsigned APK 会失败，这是预期）"
fi

# ---------------------------------------------------------------------------
# 6) 归档到 dist/ + sha256
# ---------------------------------------------------------------------------
mkdir -p dist
OUT="dist/zhibox-${VERSION_NAME}-${GIT_SHA}${GIT_DIRTY}-${MODE}${SIGNED_SUFFIX}.apk"
cp -f "$FINAL_APK" "$OUT"

if command -v shasum >/dev/null 2>&1; then
  SHA256="$(shasum -a 256 "$OUT" | awk '{print $1}')"
else
  SHA256="$(sha256sum "$OUT" | awk '{print $1}')"
fi
printf '%s  %s\n' "$SHA256" "$(basename "$OUT")" > "$OUT.sha256"

log "归档: $OUT"
log "sha256: $SHA256"
log "校验和文件: $OUT.sha256"
if [ "$MODE" = "release" ] && [ "$ALREADY_SIGNED" -eq 0 ]; then
  warn "交付物为 UNSIGNED release APK：仅可内部验证，禁止对外分发"
fi
log "下一步：按 docs/RELEASE.md 的验收清单做真机 smoke + OEM 安装引导；分发时附上 .sha256"
