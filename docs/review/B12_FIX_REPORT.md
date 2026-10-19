# B12 实施报告（图谱/设置/备份/验收与发布候选）

- **日期**：2026-10-19
- **依据**：ROADMAP §7 B12、PRD FR-07/FR-09/FR-10/FR-11/§11
- **状态**：**环境受限通过**（用户接受“高端+中端、缺低端”）；代码/自动门禁/签名 RC/单机验收完成，低端矩阵未提供且不伪造。

## 0. 最终状态（环境受限通过）

- 代码/自动门禁：JVM debug **520/0**、release **501/0**；lint **0/40**；PJZ110 instrumentation **36/36**；BackupV3 **7/7**、Migration **11/11**。
- 图谱：source 过滤/分页≤200/空态/详情 chunk 原文/entity 共享连接；10k in-memory+持久盘 p95 ≤12ms（PJZ110）、K60U ≤21ms、K40 ≤62ms（目标 1.5s）。
- Settings：预算三档、抽取并发 1–4、trace 导出/errorCode、隐私、备份 v3/诊断说明；备份 v3 UI 文本走查。
- Release candidate：`dist/zhibox-0.1.0-mvp-0c08286-b12-release.apk`，release 签名 + apksigner PASS；PJZ110 clean install/启动（170ms）/UI；不自动发布。
- 设备矩阵：PJZ110 高端 RC；K60U 高端 debug 非 UI；K40 中端 debug 非 UI+手动 UI；**低端 4–6GB 未提供，用户 2026-10-19 明确接受环境受限验收**。
- 证据/报告：`B12_ACCEPTANCE.md`、`B12-*` evidence、README/RELEASE_NOTES/用户指南。

## 1. 已交付（第一批）

### 1.0 第二批判定（2026-10-19，entity 连接/10k/trace）
- Graph 事件详情新增 **entity 共享连接「相关事件」**：activeEventIdsByEntityIds + 批量 byIds，排除自身、去重、上限 20，可点击跳转详情（embedded/独立两种模式）。
- Graph 10k 基线（in-memory Room + 真实 DAO）：count all p95 **8ms**、page offset0 p95 2ms、offset5000 p95 3ms、offset9950 p95 3ms、source filter p95 3ms；全部远低于 1.5s 目标（下限参考，非持久盘）；seed active=10000/inactive=500。
- QA trace `timingJson` 增 `errorCode/errorStage`（失败可归因；无正文）。
- 门禁：JVM **520/0**、release **501/0**；lint 0/40；PJZ110 串行 instrumentation **36/36**（Graph10k in-memory 1 + persistent 1 + extraction concurrency 3 + 原 31）；BackupV3 7/7、Migration 11/11。
- 独立审查修复：Graph 切源/loadMore 竞态、Settings 预算写入竞态、详情嵌套 Scaffold、错误+空态叠加、隐私文案；签名 RC 已完成（apksigner PASS + clean install/launch）；三档设备矩阵按环境受限口径记录（PJZ110 高端 RC + K60U 高端 debug + K40 中端 debug；低端缺，用户接受）。

### 1.0.1 第一批判定（2026-10-19）
- Graph DAO：`EventDao.activeGraphPage/countActiveGraph`；GraphDaoTest 4/4（PJZ110）。
- Graph UI：`ui/GraphViewModel.kt`/`GraphScreen.kt` + Sources 图谱 tab；GraphUiSmokeTest 6/6（PJZ110，两次 clean）。
- Settings/备份/诊断：备份 v3 UI 审计补缺（格式计数/旧包补抽取/隐私说明）；检索/问答 trace 计数+脱敏导出。
- 预算档位：`SagBudgetTier`/`SagBudgetPreferences` + Search/Chat 接线；Settings 三档 chips；SagBudgetTierTest 9/9。
- 门禁：JVM debug 513/0、release 494/0；lint 0/37；assembleDebug/Release 成功；PJZ110 串行 instrumentation 31/31。
- **release candidate**：使用本地 release keystore 构建并 **apksigner verify PASS**（v1/v2，证书 SHA-256 `b1d136ba…`）；产物 `dist/zhibox-0.1.0-mvp-0c08286-b12-release.apk`（49385689 bytes）；SHA-256 `3abd4d2ca321993188b5c94ab68ea17238ca7d2591bcd1389cba33da2de5f036`；PJZ110 卸载 debug 后 clean install 成功（versionCode=1/0.1.0-mvp，cold launch 170ms，无 FATAL；home/来源/图谱空态/Settings UI 走查通过）。
- 设备回归：BackupV3RoundTrip **7/7**、MigrationTest/MigrationV1→V7 **11/11**（PJZ110，串行）；Graph10kBaseline 1/1；总串行 instrumentation 32/32。
- 独立 Standards/Spec 审查发现：Graph 切源/loadMore 竞态、Settings 预算写入竞态、详情嵌套 Scaffold、错误+空态叠加、隐私文案矛盾、RC 不可签/不可复现；**已分别修复**（请求代际/Job 取消、budgetSaving 禁用、embedded 详情、错误时不显示空态、备份文案），并已用 release keystore 重新构建/验签 RC。三档矩阵/抽取并发控制/持久盘 p95/备份 UI 视觉仍缺。
- 详见 `B12_ACCEPTANCE.md`。


### 1.1 预算档位领域
- `core/sag/SagModels.kt`：`SagBudgetTier`（HIGH/MID/LOW，按 PRD §7.2 映射预算）。
- `core/sag/SagBudgetPreferences.kt`：档位持久化（默认 HIGH）。
- SearchViewModel/ChatViewModel：每次请求读取档位并构造 `SagQuery(budget=...)`。

### 1.2 批次过程（历史）
- Sources 图谱/事件详情（DAO + ViewModel/UI）。
- Settings/备份/诊断审计与预算档位 UI。
- 测试/验收矩阵/发布候选/文档。

## 2. 验收证据

- Sources 图谱：source 过滤、分页/上限≤200、event 详情 entities/chunk 原文、无抽取空态；PJZ110 Compose。
- 预算档位：设置切换后检索/问答请求预算变化；测试覆盖 HIGH/MID/LOW。
- 备份 v3 UI：导出/导入/预览/依赖提示/旧备份补抽取提示；chat/trace 不导出。
- 诊断：trace 最近 50/脱敏导出/错误可读。
- 三档真机矩阵、多跳/单跳回归、迁移/备份/删除/禁网/超时；release candidate。

*后续批次更新。*
