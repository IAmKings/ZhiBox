# 仓库策略（REPOSITORY POLICY）

本文件约定「什么该入库、什么只留在本机/CI 工件、什么必须清理」，用于评审（P2 B6-3）后的仓库卫生。
原则：**入库只保留可复现的源资产（代码/语料/脚本/文档/评审基线）；运行产物与临时数据一律不入库；
清理只针对临时/生成物，不删除用户数据与历史交付。**

## 1. 评测语料（入库）

- `scripts/eval/**` **入库**：`scripts/eval/corpus/*.txt`（50 卡语料）与 `scripts/eval/evalset.json`
  由 `scripts/gen_eval_corpus.py`（固定随机种子）生成；语料与生成脚本同源提交。
- 复现命令：`python3 scripts/gen_eval_corpus.py`（不得手工编辑生成物；需要调整语料时改脚本并重跑）。
- 其他实验输入（如 `scripts/g1/reference.json`、脚本内嵌查询集）随脚本入库；大模型文件不入库（见 §5）。

## 2. 运行结果 / 基准输出（不入库）

以下为本机/真机运行产物，**不入库**（需要留存时归档到 CI artifacts 或 release 归档目录，并在交付说明中记录）：

| 路径 | 内容 |
|---|---|
| `docs/phase0/*-results/` | G1/G3/G4/基准/bench 原始输出 |
| `docs/phase1/*-results/` | eval / lenbench / probe / search 等评测输出 |
| `docs/phase1/prodbench/` | 生产基准原始 JSON |
| `docs/phase1/thermal/` | 温度/电量采样报告 |
| `dist/` | 发布归档（APK/AAB/交付 zip） |

**例外（明确入库）**：
- `docs/review/b*/baseline-*.json`、`docs/review/b*/*-metrics.json` 等**评审锁定基线**：用于回归对照，
  随评审报告入库；
- `docs/review/**/*.md` 评审报告、`docs/review/device-acceptance/**` 真机验收证据截图：作为交付证据入库。

**历史已跟踪结果（锁定例外）**：B1–B7 修复前已入库的 `docs/phase1/lenbench-results/`、`w1-acceptance-*.json`
等验收证据保持 git 跟踪（`.gitignore` 对已跟踪文件不生效，也不会删除它们）；§2 规则只约束**新增**运行产物。
如需清理历史结果，按本文 §6 流程移动归档并在交付说明中注明，不得直接删除。

## 3. 临时目录与脚本规范（新增/修改脚本必须遵守）

- 主机临时目录一律 `mktemp -d "${TMPDIR:-/tmp}/zhibox-xxx.XXXXXX"` + `trap 'rm -rf "$DIR"' EXIT`，
  **禁止固定路径**（如历史 `sleep 150` + `/tmp/evaldb`）。
- 设备临时中转目录使用 `/data/local/tmp/…`（脚本结束/失败时清理）；应用私有目录通过
  `run-as` 访问（scoped storage 下不要直接写 `/sdcard/Android/data/<pkg>`）。
- 等待条件统一用 `scripts/lib/common.sh` 的 `poll_until`（含超时 + logcat 错误输出）；
  **禁止用长 `sleep` 等结果**（少量 `sleep 1` 用于 force-stop→start 的间隔除外）。
- 公共函数（ADB/ANDROID_HOME/ANDROID_SERIAL、install/start、app_put、poll、pull 等）统一从
  `scripts/lib/common.sh` 引用，不在各脚本重复实现。
- 运行产物不得写入仓库目录；需要落盘的报告写到 §2 的不入库路径。

## 4. `.fix-baseline/` 与 `.review/`（本机中间产物，不入库）

- `.fix-baseline/`：修复前基线快照（本地 diff 审查用），**不入库**；可随时删除/重建；
  不属于用户数据，也不得把用户知识库/文档/模型放入其中。
- `.review/`：评审过程草稿/脚本临时输出，**不入库**；最终评审报告以
  `docs/review/*_FIX_REPORT.md`、`docs/review/*.md` 形式入库。
- `__pycache__/`、`*.pyc`：Python 运行缓存，不入库。

## 5. 用户数据与历史交付（不清理）

- 用户的模型文件（`models/`、共享模型仓）、知识库数据库/原文、评测语料与历史报告**不得删除**；
  清理动作只允许针对 §2/§3 的临时/运行产物。
- 大文件（模型）经 hf-mirror 下载，SHA/版本记录在 `docs/UPSTREAM_VERSIONS.md`，不随仓库分发。
- 如需清理历史结果，先移动到 `dist/archive/` 或 CI artifact 并在评审/交付说明中注明，不得直接删除。

## 6. 本地校验清单（脚本改动后）

```bash
# shell 语法（本机无 shellcheck 时至少跑 bash -n；CI 可选安装 shellcheck）
for f in scripts/*.sh scripts/lib/*.sh; do bash -n "$f" || exit 1; done
# Python 语法
python3 -m py_compile scripts/*.py
# 禁止固定临时路径/长 sleep（thermal 的采样节奏属实验设计，见脚本内注释）
grep -RIn "/tmp/evaldb" scripts && exit 1 || true
grep -RInE "sleep (1[0-9]{2,}|[2-9][0-9]{2,})" scripts && exit 1 || true
# 生成物不入库：提交前确认 git status 未包含 §2 路径
git status --porcelain | grep -E "docs/(phase0|phase1)/|dist/|\.review/|__pycache__" && exit 1 || true
```
