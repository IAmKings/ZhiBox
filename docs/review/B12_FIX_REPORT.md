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
- release candidate：unsigned APK 已生成（无 release keystore）；签名/验签与三档矩阵待完成。详见 `B12_ACCEPTANCE.md`。


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
