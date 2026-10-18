# B12 实施报告（图谱/设置/备份/验收与发布候选）

- **日期**：2026-10-19（进行中）
- **依据**：ROADMAP §7 B12、PRD FR-07/FR-09/FR-10/FR-11/§11
- **状态**：**进行中**；本报告随批次更新，未完成前不宣称发布候选。

## 1. 已交付（第一批）

### 1.0 第一批判定（2026-10-19）
- Graph DAO：`EventDao.activeGraphPage/countActiveGraph`；GraphDaoTest 4/4（PJZ110）。
- Graph UI：`ui/GraphViewModel.kt`/`GraphScreen.kt` + Sources 图谱 tab；GraphUiSmokeTest 6/6（PJZ110，两次 clean）。
- Settings/备份/诊断：备份 v3 UI 审计补缺（格式计数/旧包补抽取/隐私说明）；检索/问答 trace 计数+脱敏导出。
- 预算档位：`SagBudgetTier`/`SagBudgetPreferences` + Search/Chat 接线；Settings 三档 chips；SagBudgetTierTest 9/9。
- 门禁：JVM debug 513/0、release 494/0；lint 0/37；assembleDebug/Release 成功；PJZ110 串行 instrumentation 31/31。
- 构建产物：**unsigned internal build**（未配 `ZHIBOX_*` 签名材料，不可分发，**不称 release candidate**；SHA-256/size 见 `evidence/B12-RELEASE_CANDIDATE.json`）；签名/验签与三档矩阵待完成。
- 设备回归：BackupV3RoundTrip **7/7**、MigrationTest/MigrationV1→V7 **11/11**（PJZ110，串行）。
- 独立 Standards/Spec 审查发现：Graph 切源/loadMore 竞态、Settings 预算写入竞态、详情嵌套 Scaffold、错误+空态叠加、隐私文案矛盾、RC 实质为旧 unsigned 包；**本轮已修前 5 项**（请求代际/Job 取消、budgetSaving 禁用、embedded 详情、错误时不显示空态、备份文案）；RC 改为“unsigned internal build”并待签名；三档矩阵/抽取并发/trace errorCode/entity 连接聚合仍缺。
- 详见 `B12_ACCEPTANCE.md`。


### 1.1 预算档位领域
- `core/sag/SagModels.kt`：`SagBudgetTier`（HIGH/MID/LOW，按 PRD §7.2 映射预算）。
- `core/sag/SagBudgetPreferences.kt`：档位持久化（默认 HIGH）。
- SearchViewModel/ChatViewModel：每次请求读取档位并构造 `SagQuery(budget=...)`。

### 1.2 进行中
- Sources 图谱/事件详情（DAO + ViewModel/UI）。
- Settings/备份/诊断审计与预算档位 UI。
- 测试/验收矩阵/发布候选/文档。

## 2. 验收标准（待补证据）

- Sources 图谱：source 过滤、分页/上限≤200、event 详情 entities/chunk 原文、无抽取空态；PJZ110 Compose。
- 预算档位：设置切换后检索/问答请求预算变化；测试覆盖 HIGH/MID/LOW。
- 备份 v3 UI：导出/导入/预览/依赖提示/旧备份补抽取提示；chat/trace 不导出。
- 诊断：trace 最近 50/脱敏导出/错误可读。
- 三档真机矩阵、多跳/单跳回归、迁移/备份/删除/禁网/超时；release candidate。

*后续批次更新。*
