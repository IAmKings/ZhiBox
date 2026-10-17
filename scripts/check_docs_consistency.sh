#!/usr/bin/env bash
# ============================================================================
# B6-1 文档一致性门禁（CI 用）：校验关键"事实"文档与代码/构建配置不漂移
#
# 校验项（任一失败 → exit 1）：
#   1. README 的 Gradle 版本 = gradle/wrapper/gradle-wrapper.properties 实际版本，
#      且不残留旧版本 "Gradle 8.11" 描述；
#   2. README 权限描述与 AndroidManifest 的 INTERNET 权限一致
#      （Manifest 有 INTERNET 时 README 不得声称"不申请任何权限"）；
#   3. docs/UPSTREAM_VERSIONS.md 的 AGP 版本 = gradle/libs.versions.toml 的 agp；
#   4. docs/UPSTREAM_VERSIONS.md 的 270M 模型体积 = ModelCatalog.kt 的 sizeBytes
#      （允许精确字节或 MiB 换算文本；若写了精确字节则必须一致）；
#   5. 评测规模从 scripts/eval/{evalset.json,corpus/} 实际派生（总数/语料卡数），README/
#      W3/MVP 文档必须与当前规模一致（禁止把历史 MVP-1 口径写成当前口径）；
#   6. docs/RELEASE_NOTES.md 存在并包含 B1–B7 关键行为关键词。
#
# 用法：./scripts/check_docs_consistency.sh   （任意 cwd 均可；脚本自行定位仓库根）
# 依赖：bash/grep/awk/sed（CI ubuntu-latest 与 macOS 自带即可），不依赖 Gradle/Android SDK。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

README="README.md"
WRAPPER="gradle/wrapper/gradle-wrapper.properties"
MANIFEST="app/src/main/AndroidManifest.xml"
TOML="gradle/libs.versions.toml"
UPSTREAM="docs/UPSTREAM_VERSIONS.md"
MODEL_CATALOG="app/src/main/java/com/zhibox/memory/core/model/ModelCatalog.kt"
W3_DOC="docs/phase1/W3-ROBUSTNESS-EVAL.md"
MVP_DOC="docs/phase1/MVP1-ACCEPTANCE.md"
RELEASE_NOTES="docs/RELEASE_NOTES.md"

FAILURES=0
CHECKS=0

pass() { CHECKS=$((CHECKS + 1)); printf '  [PASS] %s\n' "$*"; }
fail() { CHECKS=$((CHECKS + 1)); FAILURES=$((FAILURES + 1)); printf '  [FAIL] %s\n' "$*" >&2; }
section() { printf '\n== %s ==\n' "$*"; }

require_file() {
  local f="$1"
  [ -f "$f" ] || fail "缺少文件：$f"
}

# ---------------------------------------------------------------------------
# 0) 必需输入文件存在
# ---------------------------------------------------------------------------
section "0) 输入文件存在性"
for f in "$README" "$WRAPPER" "$MANIFEST" "$TOML" "$UPSTREAM" "$MODEL_CATALOG" "$W3_DOC" "$MVP_DOC"; do
  require_file "$f"
done
if [ "$FAILURES" -ne 0 ]; then
  printf '\n缺少必需输入文件，终止后续检查。\n' >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 1) README Gradle 版本 vs wrapper 实际版本
# ---------------------------------------------------------------------------
section "1) README Gradle 版本"
WRAPPER_GRADLE="$(sed -nE 's|^distributionUrl=.*gradle-([0-9]+\.[0-9]+(\.[0-9]+)?)-bin\.zip.*|\1|p' "$WRAPPER" | head -1)"
if [ -z "$WRAPPER_GRADLE" ]; then
  fail "$WRAPPER 中无法解析 distributionUrl 的 Gradle 版本"
else
  if grep -qF "$WRAPPER_GRADLE" "$README"; then
    pass "README 含实际 Gradle 版本 $WRAPPER_GRADLE"
  else
    fail "README 未出现 wrapper 实际 Gradle 版本 '$WRAPPER_GRADLE'（请更新 README 构建说明）"
  fi
  # 旧版本残留（历史踩坑文案"本机 Gradle 8.11 不支持…"）
  if grep -Eq 'Gradle[[:space:]]*8\.11' "$README"; then
    fail "README 仍引用旧版本 'Gradle 8.11'（实际 wrapper 为 ${WRAPPER_GRADLE}）"
  else
    pass "README 无 'Gradle 8.11' 残留"
  fi
fi

# ---------------------------------------------------------------------------
# 2) README 权限描述 vs AndroidManifest INTERNET
# ---------------------------------------------------------------------------
section "2) README 权限描述 vs Manifest INTERNET"
if grep -q 'android.permission.INTERNET' "$MANIFEST"; then
  if grep -Eq '不申请任何权限|不申请[^。\n]*权限|无任何权限|不含网络|不使用网络' "$README"; then
    fail "Manifest 声明 INTERNET，但 README 仍声称不申请任何权限/含网络"
  else
    pass "README 未声称'不申请任何权限'（与 INTERNET 一致）"
  fi
  if grep -Eq 'INTERNET|网络权限' "$README"; then
    pass "README 已说明网络权限（INTERNET）"
  else
    fail "Manifest 声明 INTERNET，但 README 未说明网络权限（INTERNET）"
  fi
else
  if grep -Eq 'INTERNET|网络权限' "$README"; then
    fail "Manifest 未声明 INTERNET，但 README 声称存在网络权限（不一致）"
  else
    pass "Manifest 无 INTERNET 且 README 未声称网络权限"
  fi
fi

# ---------------------------------------------------------------------------
# 3) UPSTREAM_VERSIONS AGP 版本 vs libs.versions.toml
# ---------------------------------------------------------------------------
section "3) UPSTREAM_VERSIONS AGP 版本"
TOML_AGP="$(sed -nE 's|^agp[[:space:]]*=[[:space:]]*"([^"]+)".*|\1|p' "$TOML" | head -1)"
if [ -z "$TOML_AGP" ]; then
  fail "$TOML 中无法解析 agp 版本"
else
  AGP_LINE="$(grep -m1 'Android Gradle Plugin' "$UPSTREAM" || true)"
  if [ -z "$AGP_LINE" ]; then
    fail "$UPSTREAM 缺少 'Android Gradle Plugin' 行"
  else
    # 只取表格行第 3 个字段（版本列），避免行内出现多个 semver 时误取首个
    UPSTREAM_AGP="$(printf '%s\n' "$AGP_LINE" | awk -F'|' '{gsub(/^[[:space:]]+|[[:space:]]+$/, "", $3); print $3}' | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1 || true)"
    if [ "$UPSTREAM_AGP" = "$TOML_AGP" ]; then
      pass "UPSTREAM AGP 版本 = toml agp = $TOML_AGP"
    else
      fail "$UPSTREAM 的 AGP 版本 '$UPSTREAM_AGP' ≠ libs.versions.toml agp '$TOML_AGP'（请同步文档）"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 4) UPSTREAM_VERSIONS 270M 体积 vs ModelCatalog sizeBytes
#     ModelCatalog 结构：Entry(fileName = "...270m...", ..., sizeBytes = 164_626_432, ...)
# ---------------------------------------------------------------------------
section "4) UPSTREAM_VERSIONS 270M 体积 vs ModelCatalog"
SIZE_270="$(awk '
  /fileName[[:space:]]*=[[:space:]]*"[^"]*270m[^"]*"/ { in_entry=1; next }
  in_entry && /sizeBytes[[:space:]]*=[[:space:]]*[0-9_]+/ {
    line=$0; gsub(/_/, "", line)
    match(line, /sizeBytes[[:space:]]*=[[:space:]]*[0-9]+/)
    s=substr(line, RSTART, RLENGTH); gsub(/[^0-9]/, "", s)
    print s; exit
  }
' "$MODEL_CATALOG")"
if [ -z "$SIZE_270" ]; then
  fail "无法从 $MODEL_CATALOG 解析 270M 的 sizeBytes"
else
  SIZE_270_MIB="$(awk -v b="$SIZE_270" 'BEGIN { printf "%.3f", b / 1048576 }')"
  SIZE_270_MIB_INT="$(awk -v b="$SIZE_270" 'BEGIN { printf "%d", (b / 1048576) }')"
  # 精确字节的常见格式：164,626,432 / 164_626_432 / 164626432
  # （用 awk 手工插入千分位，避免 BSD sed 不支持 `:a; ...; ta` 单行标签）
  SIZE_270_COMMA="$(awk -v n="$SIZE_270" 'BEGIN {
    s = ""
    while (length(n) > 3) {
      s = "," substr(n, length(n) - 2) s
      n = substr(n, 1, length(n) - 3)
    }
    print n s
  }')"
  if grep -qF "$SIZE_270" "$UPSTREAM" \
    || grep -qF "$SIZE_270_COMMA" "$UPSTREAM" \
    || grep -Eq "(^|[^0-9])${SIZE_270_MIB_INT}(\\.0+)?[[:space:]]*(MiB|MB)([^0-9]|$)" "$UPSTREAM"; then
    pass "UPSTREAM 270M 体积写法与 sizeBytes 一致（${SIZE_270}B = ${SIZE_270_MIB}MiB）"
  else
    fail "UPSTREAM 未出现 270M 的精确字节 $SIZE_270 / $SIZE_270_COMMA 或 MiB 换算 ${SIZE_270_MIB_INT} MiB"
  fi
  # 矛盾守卫：若 270M 相关行写了"精确字节"（如 164,892,672B），必须与 sizeBytes 相等
  BAD_BYTES="$(grep -E '270[mM]' "$UPSTREAM" | grep -oE '([0-9]{1,3}(,[0-9]{3})+|[0-9]{9,})[[:space:]]*B' | tr -d ' B' | tr -d ',' | while read -r n; do
    [ -n "$n" ] && [ "$n" != "$SIZE_270" ] && echo "$n"
  done | sort -u || true)"
  if [ -n "$BAD_BYTES" ]; then
    fail "UPSTREAM 270M 行内的精确字节数（$(printf '%s' "$BAD_BYTES" | tr '\n' ' ')）≠ ModelCatalog sizeBytes $SIZE_270"
  else
    pass "UPSTREAM 270M 行内无矛盾精确字节数"
  fi
fi

# ---------------------------------------------------------------------------
# 5) 评测规模：从 evalset.json/corpus 实际派生（禁止硬编码 220/45 漂移）
# ---------------------------------------------------------------------------
section "5) 评测规模（由 evalset.json/corpus 派生）"
EVALSET="scripts/eval/evalset.json"
CORPUS_DIR="scripts/eval/corpus"
if ! command -v python3 >/dev/null 2>&1; then
  fail "缺少 python3，无法从 evalset.json 派生评测规模"
elif [ ! -f "$EVALSET" ] || [ ! -d "$CORPUS_DIR" ]; then
  fail "缺少 $EVALSET 或 $CORPUS_DIR，无法派生评测规模"
else
  EVAL_INFO="$(python3 - "$EVALSET" "$CORPUS_DIR" <<'PYEVAL'
import json
import os
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
queries = data.get("queries", [])
cards = [f for f in os.listdir(sys.argv[2]) if os.path.isfile(os.path.join(sys.argv[2], f))]
print(len(queries), len(cards))
PYEVAL
)"
  EVAL_TOTAL="${EVAL_INFO%% *}"
  CORPUS_COUNT="${EVAL_INFO##* }"
  if [ -z "$EVAL_TOTAL" ] || [ -z "$CORPUS_COUNT" ]; then
    fail "无法从 evalset.json/corpus 派生评测规模（输出：$EVAL_INFO）"
  else
    for f in "$README" "$W3_DOC" "$MVP_DOC"; do
      if grep -Eq "(^|[^0-9])${EVAL_TOTAL}([^0-9]|$)" "$f"; then
        pass "$(basename "$f") 已提及当前评测集 ${EVAL_TOTAL} 条"
      else
        fail "$(basename "$f") 未提及当前评测集 ${EVAL_TOTAL} 条（scripts/eval/evalset.json 实际值，文档漂移）"
      fi
    done
    for f in "$README" "$W3_DOC"; do
      if grep -Eq "(^|[^0-9])${CORPUS_COUNT}[[:space:]]*(张知识卡|卡)" "$f"; then
        pass "$(basename "$f") 已提及当前语料 ${CORPUS_COUNT} 卡"
      else
        fail "$(basename "$f") 未提及当前语料 ${CORPUS_COUNT} 卡（scripts/eval/corpus/ 实际文件数）"
      fi
    done
    if grep -Eq '220[[:space:]]*=[[:space:]]*45[[:space:]]*[x×][[:space:]]*4' "$README" \
      && ! grep -q 'MVP-1' "$README"; then
      fail "README 将历史 220=45×4+40 写成当前口径且未标注 MVP-1（当前 ${EVAL_TOTAL} 条/${CORPUS_COUNT} 卡）"
    else
      pass "README 未把历史评测规模写成当前口径"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 6) RELEASE_NOTES 关键行为条目
# ---------------------------------------------------------------------------
section "6) RELEASE_NOTES 关键条目"
if [ ! -f "$RELEASE_NOTES" ]; then
  fail "缺少 ${RELEASE_NOTES}（应由 Lead 创建）"
else
  KEYWORDS=(
    '@revision'
    '备份格式'
    '3 次总尝试'
    '原文文件不随备份'
    '同名换文件'
    'ready-only'
    'CODE:detail'
  )
  MISSING=()
  for kw in "${KEYWORDS[@]}"; do
    grep -qF "$kw" "$RELEASE_NOTES" || MISSING+=("$kw")
  done
  if [ "${#MISSING[@]}" -eq 0 ]; then
    pass "RELEASE_NOTES 含全部 ${#KEYWORDS[@]} 个关键行为关键词"
  else
    fail "RELEASE_NOTES 缺少关键词：${MISSING[*]}"
  fi
fi

# ---------------------------------------------------------------------------
# 7) 后续规划文档存在性与结构（PRD/ROADMAP/对齐报告）
# ---------------------------------------------------------------------------
section "7) 后续规划文档（PRD/ROADMAP/对齐报告）"
PRD_DOC="docs/product/PRD_ZhiBox_SAG_App_v1.0.md"
ROADMAP_DOC="docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md"
ALIGN_DOC="docs/review/SAG_APP_ALIGNMENT_REPORT.md"
for f in "$PRD_DOC" "$ROADMAP_DOC" "$ALIGN_DOC"; do
  if [ -f "$f" ]; then pass "存在 $(basename "$f")"; else fail "缺少规划文档：$f"; fi
done
if [ -f "$PRD_DOC" ]; then
  MISSING_FR=""
  for n in 01 02 03 04 05 06 07 08 09 10 11; do
    grep -q "FR-$n" "$PRD_DOC" || MISSING_FR="$MISSING_FR FR-$n"
  done
  if [ -z "$MISSING_FR" ]; then pass "PRD 覆盖 FR-01..FR-11"; else fail "PRD 缺少需求编号：$MISSING_FR"; fi
fi
if [ -f "$ROADMAP_DOC" ]; then
  MISSING_B=""
  for b in 8 9 10 11 12; do grep -q "B$b" "$ROADMAP_DOC" || MISSING_B="$MISSING_B B$b"; done
  if [ -z "$MISSING_B" ]; then pass "ROADMAP 覆盖 B8–B12"; else fail "ROADMAP 缺少批次：$MISSING_B"; fi
fi
if [ -f "$ALIGN_DOC" ]; then
  if grep -q "总体不对齐" "$ALIGN_DOC" && grep -q "能力对齐矩阵" "$ALIGN_DOC"; then
    pass "对齐报告含结论与能力矩阵"
  else
    fail "对齐报告缺少'总体不对齐'结论或'能力对齐矩阵'章节"
  fi
fi

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
printf '\n────────────────────────────────────────────\n'
printf '文档一致性检查：共 %d 项，失败 %d 项\n' "$CHECKS" "$FAILURES"
if [ "$FAILURES" -ne 0 ]; then
  printf '结果：FAILED（请按上面 [FAIL] 项修正文档或代码；本门禁在 CI 中会阻断构建）\n' >&2
  exit 1
fi
printf '结果：OK ✅\n'
