# 质量审查修复记录（2026-10-22）

对应审查：`.trellis/tasks/10-21-fix-quality-audit-bugs/prd.md` 与两轴代码评审。

## 已修复 hard violations

| # | 问题 | 修复 | 验证 |
|---|---|---|---|
| 1 | R8 覆盖逻辑在 `settings.gradle.kts`，版本字面量绕过 catalog | 移到 root `build.gradle.kts` 的 `buildscript`；R8 版本进入 `libs.versions.toml`，版本由 catalog 提供 | `:app:assembleRelease` 通过；无 Kotlin metadata 告警 |
| 2 | `jsr305:3.0.2` 版本字面量 | 进入 catalog（`jsr305` version + library），构建脚本使用 `libs.jsr305` | `:app:testDebugUnitTest` / `testReleaseUnitTest` 通过 |
| 3 | SAG FTS 重建失败原因被 `isSuccess` 丢弃 | `ImportOutcome.warningDetail` 保留脱敏 cause；测试断言 detail | `BackupRobolectricTest` 通过 |
| 4 | “无快照”用 `error()` 异常控制流 | `previewLatestSnapshot` 改为显式空态分支 + `migrationMessage` | 编译/单测通过 |

## 已修复 R2（来源范围检索）

- `SearchService`：
  - `readyIdsBySources` 按 `SQL_VARIABLE_BATCH` 分批，避免 SQLite 变量上限；
  - FTS 来源查询按来源分批并去重/限量；
  - 流式向量通道新增按来源分页查询 `vectorsForSpaceSourcesPage`，不再依赖全量 chunkId HashSet 过滤；
  - 矩阵路径仍用分批得到的 allowed chunk ids，保持先 eligibility 后 topK。
- 回归：`SearchServiceSourceScopeTest`（真实 `SearchService.search` + fake provider，验证全库高分候选不会饿死范围内命中）、`SearchVisibilityTest.sourceScopedVectorPageFiltersBeforePaging`、`SearchLimitsTest` 纯函数用例。

## 已修复 R5（一致导出）

- `KnowledgeBackup.exportZip` 改用 Room **reader connection + DEFERRED 读事务**（`useReaderConnection` + `Transactor`），避免整个导出占用写事务；仍保持分页流式写入。
- 导出 guard 仍覆盖 ingest/re-embed（`JobStage.ACTIVE_VALUES`）与 SAG extraction（`ExtractionJobStatus.ACTIVE_VALUES`）。
- 回归：`export_refusesActiveExtractionJob`、原有导出回环/备份测试。

## 回归测试补充

- `snapshotFailureLeavesLiveDatabaseUnchanged`：快照目录不可写时导入失败且 DB 行数不变（R1 AC）。
- `sourceScopedVectorPageFiltersBeforePaging`：来源分页向量查询只返回范围内 chunk（R2）。
- `warningDetail` 断言：SAG 重建失败保留可诊断原因（R3）。
- 4 个 Compose UI 套件恢复到 `androidTest`（保留 `ComposeRecreateSmokeTest`），恢复 R6 原文要求；`compileDebugAndroidTestKotlin` 通过。

## 最终门禁

- `:app:testDebugUnitTest`：**835/0**
- `:app:testReleaseUnitTest`：**816/0**
- `:app:lintDebug`：**0 errors**
- `:app:compileDebugAndroidTestKotlin`：通过
- `:app:assembleRelease`：通过
- `scripts/check_docs_consistency.sh`：22 项通过

## 未处理（保留）

- R2 仍需真实 `SearchService.search` 端到端“越界高分候选不能饿死范围内命中”用例（当前为纯函数 + DAO 级）。
- R5 并发写入压力测试暂未补，记录风险豁免：已有 activity guard（extraction/ingest）回归 + reader 读事务；缺少长时间并发压力证据。发布前需在真机做一次导出期间并发导入/抽取的手工压力验证，或补确定性测试后再签字。
- R1 “newest valid” 目前只做 entry 结构存在性检查，内容损坏快照预览时才报错。
- R6 已恢复：4 个 Compose UI 套件回到 `androidTest`，androidTest 编译通过；但仅编译、未在 CI 真机执行（原 ARM 限制）。
- R4 merged manifest 已由 Lead 实测含两代规则；spec 文档未追加自动比较断言。
