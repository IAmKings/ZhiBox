# B6 修复报告（P2 B6：文档/脚本一致性）

- **日期**：2026-10-17
- **范围**：`docs/review/P2_FIX_PLAN.md` 批次 B6（4 项：B6-1 文档漂移 + CI 校验、B6-2 脚本重构、B6-3 仓库策略、B6-4 发布说明/迁移章节）
- **执行方式**：ui-reviewer（B6-1 文档）、build-fixer（B6-1 CI/校验脚本）、data-reviewer（B6-2/B6-3 脚本/策略）、Lead（B6-4 发布说明、集成校验、报告）
- **约束**：不改产品行为/构建产物；不 git commit；不 provision 签名；不跑真机实验（脚本仅静态校验）

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| B6-1 文档漂移 | README 状态/Gradle/权限、UPSTREAM AGP/270M/440M/740M、评测规模 **220/45 → 实际 262/50** 等 **8+ 处**同步；`scripts/check_docs_consistency.sh`（**14 项断言，评测规模从 evalset/corpus 派生**）接入 CI fail-fast |
| B6-2 脚本重构 | 新增 `scripts/lib/common.sh`（104 行）；9 个 `run_*.sh` **634→522 行（−17.7%）**；固定 `/tmp/evaldb`、≥100s sleep 清零；`score_eval.py` 118→99 行（argparse+死代码清理，输出逐字节一致） |
| B6-3 仓库策略 | 新增 `docs/review/REPOSITORY_POLICY.md`；`.gitignore` 增补结果/临时/`.review`/`__pycache__` 等；不删用户/历史数据 |
| B6-4 发布说明 | 新增 `docs/RELEASE_NOTES.md`（97 行，7 项行为变更 + B1/B2/B7 补充 + 升级/回滚）；README 新增"升级与迁移（B1–B7）"章节 |
| 静态门禁 | `bash -n` **12/12 OK**；docs 一致性 **14/14 PASS**；`shellcheck 0.11.0` **error=0 / warning=0**；`py_compile` OK；CI YAML 可解析 |
| 产品改动 | **无**（文档/脚本/CI 配置/忽略规则；构建与 APK 不变） |

---

## 2. 逐项完成说明

### 2.1 B6-1 文档漂移同步（7+ 处）
| # | 漂移 | 修正 |
|---|---|---|
| 1 | README 状态"Phase 0 · P0-1" | 改为 Phase 1 收尾 / MVP-1 内部发布就绪（§9.1 8 通过 + 2 部分通过遗留，220 条评测），B1–B7 完成，`versionName=0.1.0-mvp`（诚实注明仓库未打 tag） |
| 2 | README "Gradle 8.11" | **8.14.3**（wrapper，含 `distributionSha256Sum`）；补 strict metadata 613 components 说明 |
| 3 | README "不申请任何权限（含网络）" | 与 manifest 一致：仅 `INTERNET`，仅用于用户显式模型下载（网络配置禁明文、仅 hf-mirror HTTPS 白名单、URL 写死 + SHA-256 校验；其它链路离线） |
| 4 | UPSTREAM AGP 8.9.1 | **8.13.2**（与 libs.versions.toml 一致） |
| 5 | UPSTREAM 270M 体积 157MB / 旧字节数 | **164,626,432 B（157 MiB / 164.6 MB）**；440M=387,710,976、740M=484,622,336，均与 `ModelCatalog.sizeBytes` 逐字节一致 |
| 6 | UPSTREAM Compose BOM 旧版本/缺 KSP | BOM **2026.06.01** + KSP 2.3.12；granite/LFM 段标 B7 历史；删除悬空 `SPACE_ID_B` 行 |
| 7 | 评测规模/文档链接 | **修正为实际 262 条/50 卡**（fact/synonym/multihop/exact 各 50 + adversarial 42 + numdate 20；README/W3/MVP1 均标注 MVP-1 历史 220/45）；补 `RELEASE_NOTES.md`/`RELEASE.md`/MVP1 验收/`B6_FIX_REPORT.md` 链接；UPSTREAM_WATCH 更新核查日期与 B7 状态 |
| 8 | UPSTREAM 体积单位 | 426/353/518/317/175 标注为 **MiB**（与精确字节对应），440M 387.7MB |
| 9 | 失败重试语义 | “自动重试 3 次”改为 **最多 3 次总尝试（首次 + 最多 2 次自动重试）**；RELEASE_NOTES/README/门禁同步 |

### 2.2 B6-1 CI 校验
- 新增 `scripts/check_docs_consistency.sh`（13 项断言）：README Gradle 版本/无旧版本、权限与 manifest 一致、UPSTREAM AGP == toml、270M 体积 == ModelCatalog、评测 220/45 构成求和、RELEASE_NOTES 7 关键词。
- `.github/workflows/ci.yml` 新增第 2 步（Checkout 后、JDK/Gradle 前）：`bash -n` 全部 `scripts/*.sh` + `scripts/lib/common.sh`（存在时）+ 门禁脚本自身 + docs 一致性；失败阻断。

### 2.3 B6-2 脚本重构
- 新增 `scripts/lib/common.sh`：ADB/`ANDROID_HOME`/`ANDROID_SERIAL` 解析、APK 检查/构建、install/uninstall/reinstall、force-stop/start/autostart、logcat、`app_put`（/data/local/tmp + run-as cp，规避 scoped storage）、`pull_db`/`db_query`、`poll_until`（超时打印 logcat）、结果拉取。
- 9 个 `run_*.sh` source common、去重设备头/安装/投放/等待；**固定 `/tmp/evaldb` → `mktemp -d` + trap 清理**；`sleep 120/100/75` 等改 `poll_until`（各脚本超时 120–900s，超时失败退出）。
- `score_eval.py`：删除未调用 `metrics()`/`dcg()`/未用变量，改 argparse（兼容旧位置参数）；同一 fixture 新旧输出 diff 为空。
- 保留：`run_thermal.sh` 的 30/60s **采样节奏**（负载曲线采样，非等待，注释说明）；`ANDROID_SERIAL` 改由 adb 原生环境变量承载（与原 `-s` 等价）。

### 2.4 B6-3 仓库策略
- `docs/review/REPOSITORY_POLICY.md`：评测语料（`scripts/eval/**`）入库；结果 JSON（`docs/phase0|1/*-results/`、prodbench/thermal、`dist/`）不入库，评审锁定基线/报告例外；临时目录 `mktemp+trap`、禁固定路径与长 sleep；`.fix-baseline/`/`.review/`/`__pycache__` 不入库；**只清理临时/生成物，不删用户数据/历史**。
- `.gitignore` 增补：`docs/phase0/*-results/`、`docs/phase1/prodbench/`、`docs/phase1/thermal/`、`__pycache__/`、`*.pyc`、`.review/`；`git check-ignore` 验证命中且未误伤语料/基线。

### 2.5 B6-4 发布说明与迁移章节
- 新增 `docs/RELEASE_NOTES.md`：版本/兼容矩阵（schema v5、备份 v2、EGemma2 三模型、APK ≈49.07MB）、**7 项行为变更**（`@revision` 空间/备份 v2/失败重试 3 次/原文不随备份迁移/同名换文件检测/ready-only 导出+导入归一+单 active 空间/emoji-FTS 重建+NFC+`CODE:detail`）、B7 路线收敛影响、升级步骤、回滚步骤、已知限制、批次历史摘要。
- README 新增"升级与迁移（B1–B7）"章节（与 RELEASE_NOTES §2/§3 对应），文档导航补充 RELEASE_NOTES/B6 报告/MVP1 验收/RELEASE。

---

## 3. 验证证据

```text
$ for f in scripts/*.sh scripts/lib/*.sh; do bash -n "$f"; done
→ 12/12 OK

$ ./scripts/check_docs_consistency.sh
→ 13 项全部 PASS，EXIT=0

$ shellcheck -S error scripts/*.sh scripts/lib/common.sh   → EXIT=0
$ shellcheck -S warning ...                                 → EXIT=0（修复 1 处 SC2318/SC2155 后）

$ grep -RIn "/tmp/evaldb" scripts        → none
$ grep -RInE "sleep [0-9]{3,}" scripts   → none（thermal 30/60s 采样除外，非等待）
$ python3 -m py_compile scripts/*.py     → OK
$ python3 -c "yaml.safe_load(open('.github/workflows/ci.yml'))" → OK
```

| 指标 | 值 |
|---|---|
| 9 个 run 脚本行数 | 634 → **522（−112，−17.7%）** |
| common.sh | 104 行（新增） |
| score_eval.py | 118 → 99 行；新旧同 fixture 输出 diff 空 |
| docs 一致性断言 | 13 项，失败 0 |
| shellcheck | 0.11.0 error/warning 均为 0 |

---

## 4. 计划偏差记录

| # | 计划/决策 | 实际实现 | 说明 |
|---|---|---|---|
| 1 | B6-2 目标"11 个脚本去重" | 9 个 `run_*.sh` 重构 + common.sh；`release.sh` 独立（发布流程/密钥，未纳入，避免扰动交付脚本） | release.sh 无重复 ADB 头；后续可再收敛 |
| 6 | `run_search_eval.sh` 临时查询文件 | `mktemp -d` 目录 + trap `rm -rf`（修复原 `mktemp` 文件泄漏） | 审查 S3 修复 |
| 2 | `sleep` 全改轮询 | 等待类 sleep 全部轮询；`run_thermal.sh` 30/60s 为**采样节奏**保留 | 改轮询会改变负载曲线实验语义；已注释 |
| 3 | shellcheck 通过 | 本机原未安装 → 用 brew 安装 0.11.0，修复 1 处 warning 后 error/warning 全 0 | 未引入 CI 依赖（CI 可选安装） |
| 4 | B6-3 "清理 .review/.fix-baseline" | 策略与 ignore 完善；`.fix-baseline/` 保留本地（忽略、2.2MB，供回溯），未删除 | 属本地临时/基线，删除不影响交付；策略已明确 |
| 7 | 已跟踪历史结果 vs .gitignore | 18 个历史验收结果仍被跟踪；REPOSITORY_POLICY 增**历史已跟踪结果锁定例外**（不删除，§2 只约束新增产物） | 审查 S2 修复 |
| 5 | B6-4 "tag/版本" | README 如实写 `versionName=0.1.0-mvp` + "仓库尚未打 tag" | 无 git tag（不虚报），发布流程见 RELEASE.md |

---

## 5. 风险与未覆盖项

| # | 风险 | 说明 | 处置 |
|---|---|---|---|
| 1 | 重构后脚本未真机执行 | 仅静态校验（bash -n/shellcheck/逐行对照） | 建议首次真机先跑 `run_probe.sh`/`run_g4_scan.sh` 验证 common.sh 路径；本报告如实说明 |
| 2 | docs 门禁会阻断未来文档改动 | 事实变化（版本/体积/评测规模）必须同步文档 | 设计如此；脚本失败项可读 |
| 3 | CI 尚未在远端跑过 | 无 git remote（B4-8 遗留） | 需用户提供 remote 后首绿；YAML 已本地解析 |
| 4 | shellcheck 未纳入 CI | 避免 CI 额外安装/版本漂移 | 可后续加 `apt-get install shellcheck` 或 pin 版本 |
| 5 | thermal 采样未轮询化 | 实验语义取舍 | 已在脚本注释与报告说明 |
| 6 | 旧脚本文档引用 | 已删除 run_g1b/g1c；其余文档引用已核对 | B7/B6 文档同步 |

---

## 6. 独立审查（Standards + Spec）与处置

### 6.1 Standards 轴

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | 评测规模漂移：`scripts/eval/evalset.json` 实为 **262 条**（fact/synonym/multihop/exact 各 50 + adversarial 42 + numdate 20）、语料 **50 卡**，README/W3/MVP 仍写 220=45×4+40、45 卡；原门禁还把错误固化为断言 | **已修**：README/W3/MVP 改当前 262/50（注明 MVP-1 历史 220/45）；门禁第 5 项改为从 evalset.json/corpus 派生，14/14 PASS |
| S2 | 判断性 | `.gitignore` 的 phase0/phase1 `*-results/` 命中 18 个已跟踪历史结果，与 REPOSITORY_POLICY “不入库”表面矛盾 | **已修**：策略新增“历史已跟踪结果锁定例外”（.gitignore 不影响已跟踪文件，不删除历史证据；§2 只约束新增产物） |
| S3 | 判断性 | `run_search_eval.sh` 用 `$(mktemp -t …).json` 只删 .json，mktemp 原文件泄漏 | **已修**：改 `mktemp -d` + trap `rm -rf`；bash -n/shellcheck 通过 |
| S4 | 判断性 | 门禁脆弱：AGP 取行内首个 semver、MiB 子串匹配（1157 也通过）、README 评测总数未校验 | **已修**：AGP 取表格第 3 字段；MiB 加数字边界；第 5 项从 evalset 派生并校验 README/W3/MVP 与历史口径 |
| S5 | 判断性 | UPSTREAM 426/353/518MB 实为 MiB 却标 MB；RELEASE_NOTES “重试 3 次”实为 3 次总尝试（2 次自动重试） | **已修**：体积标 MiB（附精确字节）；“最多 3 次总尝试（首次 + 最多 2 次自动重试）”同步 RELEASE_NOTES/README/门禁关键词 |
| S6 | — | 作用域/CI 步骤位置/common.sh 语义/py_compile/YAML 未发现问题 | 保留 |

### 6.2 Spec 轴

| 项 | 原结论 | 处置 |
|---|---|---|
| B6-1 | partial（评测规模不符） | **已修**：以实际 evalset/corpus 同步三份文档，门禁派生校验 → implemented |
| B6-2 | implemented | 确认；另修 mktemp 泄漏与 shellcheck warning，bash -n 12/12、shellcheck warning=0 |
| B6-3 | partial（策略与已跟踪结果冲突） | **已修**：策略明确历史锁定例外 → implemented |
| B6-4 | implemented（7 项行为 + 版本历史） | 确认；修正“重试 3 次”语义为“最多 3 次总尝试” |

### 6.3 审查后复验

```text
$ for f in scripts/*.sh scripts/lib/*.sh; do bash -n "$f"; done   → 12/12 OK
$ shellcheck -S warning scripts/*.sh scripts/lib/common.sh        → EXIT=0
$ ./scripts/check_docs_consistency.sh                             → 14/14 PASS, EXIT=0
$ python3 -m py_compile scripts/*.py                              → OK
$ python3 -c "yaml.safe_load(open('.github/workflows/ci.yml'))"   → OK
```

---

## 7. 文件清单（B6）

**文档**：`README.md`、`docs/UPSTREAM_VERSIONS.md`、`docs/UPSTREAM_WATCH.md`、`docs/phase1/W3-ROBUSTNESS-EVAL.md`、`docs/phase1/MVP1-ACCEPTANCE.md`、`docs/RELEASE_NOTES.md`（新）、`docs/review/REPOSITORY_POLICY.md`（新）
**脚本**：`scripts/lib/common.sh`（新）、`scripts/run_eval.sh`、`run_g1_experiment.sh`、`run_g3_fts.sh`、`run_g4_scan.sh`、`run_latency_matrix.sh`、`run_p02_benchmark.sh`、`run_probe.sh`、`run_search_eval.sh`、`run_thermal.sh`、`scripts/score_eval.py`、`scripts/check_docs_consistency.sh`（新）
**CI/忽略**：`.github/workflows/ci.yml`、`.gitignore`
**校验**：本报告 §3 命令；`docs/review/b5` 基线不涉及

---

*报告生成：2026-10-17 · B6 无产品代码/构建产物变更；设备未改动。*
