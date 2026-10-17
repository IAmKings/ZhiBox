# B1 修复报告（P2 批次 B1：数据层正确性与 schema v5）

- **日期**：2026-10-17
- **范围**：`docs/review/P2_FIX_PLAN.md` 批次 B1（B1-1…B1-12，共 12 项）
- **执行方式**：Lead 完成 schema v5 地基（实体 FK/默认值 + MIGRATION_4_5 + 5.json）→ 3 路并行（B1-5 枚举/DAO、B1-9 备份闭包、B1-8/12 测试）→ B1-1/4/6/10/11 语义修复（core-fixer）→ 集成门禁 → 真机验收 → 双轴独立审查
- **用户决策**：按计划启动 B1；允许依赖升级窗口（后续 B4）；保留已跟踪 phase1 结果；不 provision release 签名；引入 Robolectric（B5）

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| B1 完成度 | **12 / 12 项完成**（B1-1…B1-12；2 项计划偏差已文档化，见第 5 节） |
| schema | **v5**：8 条外键（CASCADE）+ 列默认值 + 索引/迁移记录；`5.json` 已生成并入库（git add） |
| JVM 测试 | **153 tests / 0 failed**（B1 前 106 → +47：状态机矩阵、FTS/checksum 去重、revision 回填、旧空间采纳、备份闭包、v1 fixture、状态枚举契约等） |
| Android Lint | **0 errors / 21 warnings**（`abortOnError=true`） |
| 构建 | `assembleDebug` ✅、`assembleRelease`（R8）✅ |
| 真机 instrumentation | **20 / 20 PASS**（OnePlus PJZ110 / Android 16 / arm64；含新增 ImportDedupDbTest） |
| 真机旧库升级 | **v3 → v5 链式迁移 PASS**：83 chunk 无损、version=5、schema_migrations=5、`foreign_key_check` 空、revision 回填、单 active |
| 迁移后检索回归 | **无回退**：262 查询 0 refused / 0 error；R@1=0.964 / R@5=0.991 / R@10=1.000；p50=49ms / p95=57ms |
| 独立审查 | Standards（3 硬性 + 5 判断性）+ Spec（4 partial + 1 wrong）→ **全部处置并复验**（见第 6 节） |

---

## 2. 逐项完成说明

### B1-1 FTS 去重与注释修正
- `Entities.kt`：`chunk_fts` 注释修正——rowid 由 SQLite 自增，**不与** chunk id 对齐（业务键为 chunkId）；FTS 为 chunk.content 纯派生数据。
- `IngestRepository`：新增 `dedupeFtsByChunkId(rows)` 纯函数；`replaceChunks` 与 `rebuildFtsIndex`（跨批 `seen` 集合）写入前去重；返回实际写入行数。
- FTS5 external content **仅评估不替换**：Room 仅支持 `@Fts3/@Fts4`，FTS5 需绕过 Room 手工建表 + 自定义迁移，收益不足以承担本轮 schema 风险（结论写入代码注释）。
- 验收：重复导入/重建不产生重复召回行；`ftsHealthCheck` 仍可发现历史重复。
- **审查后澄清**：真正防重是写入前 delete/rebuild；`dedupeFtsByChunkId` 定位为防御性兜底（注释与测试名已修正）。

### B1-2 v5 迁移（外键 + 默认值 + 迁移记录 + 孤儿清理）
- `Entities.kt`：8 条 FK（document→source、chunk→document、chunk_embedding→chunk、event→chunk、event_entity→event/entity、chunk_entity→chunk/entity，均 `ON DELETE CASCADE`）；`document.sizeBytes` DEFAULT 0、`embedding_meta.accelerator` DEFAULT 'cpu'、`quantization` DEFAULT 'f32'。
- `AppDatabase.MIGRATION_4_5`：孤儿清理（先子后父，覆盖"document 缺失"与"document.source 缺失"两类父缺失）→ 父表重建（document/embedding_meta）→ chunk → chunk_embedding → event → event_entity/chunk_entity；迁移末尾 `PRAGMA foreign_key_check` 非空即抛错回滚；写入 `schema_migrations` 1..5。
- Room 迁移事务内不能关闭 `foreign_keys`，通过"drop 父表时子表尚未带 FK"的顺序保证安全（注释已说明）。
- `app/schemas/.../5.json` 已生成并与迁移 DDL 对齐（8 FK / 14 索引 / 默认值），已 `git add`。

### B1-3 schema_migrations 写入
- v5 迁移内 `INSERT OR IGNORE INTO schema_migrations(version, appliedAt)` 记录 1..5（此前该表有表无写入）。
- 真机验证：`SELECT COUNT(*) FROM schema_migrations` = 5。

### B1-4 embedding_meta.active 语义
- 保留 `active` 列（minSdk 24 无 DROP COLUMN、备份格式依赖）：`registerSpace` 在同一 `db.withTransaction` 内先 `deactivateOthers()` 再 upsert 当前空间；注释明确 active = "当前默认空间"，**不等于可检索性**。
- 删除死查询 `EmbeddingDao.activeSpace()`（grep 0 调用）。
- 备份恢复侧补 `normalizeActiveSpaces()`：旧包多 active 行恢复时归一为最多一个 active。
- 真机验证：迁移+检索后 meta 为 `gpu=1 / legacy=0`（单 active）。

### B1-5 状态枚举单源 + DAO 参数化
- 新增 `data/db/StatusEnums.kt`：`SourceStatus` / `DocStatus` / `JobStage`（value 与存量数据一致），`fromValue()` 兼容未知值（读取原样展示），跨列语义集合 `DocStatus.ACTIVE_VALUES`、`JobStage.ACTIVE_VALUES/INTERRUPTED_VALUES/TERMINAL`。
- `Daos.kt`：状态/阶段查询全部参数化，`@Query` 内无状态字面量；新增 `DocumentDao.byChecksum(checksum)`；`sourceDao.observeAll(activeStatus)` 等。
- 调用方迁移 14 个文件（core ingest/search/embedding、ui、debug benchmark）；保留 4 个兼容重载供 androidTest。
- 验收 grep：main+debug 中状态字面量仅剩 StatusEnums、注释、WorkManager progress key（"done"）、parserId 占位（"pending"），无业务逻辑散落字面量。
- **审查后补强**：① 新增 `data/db/StatusEnumsTest`（5 用例：value 兼容、fromValue 未知值、跨列语义集合、唯一性）；② 备份恢复路径状态改为 `SourceStatus/DocStatus.fromValue` 校验，未知状态在写库前报错（不再绕过单源）。

### B1-6 checksum 重复导入去重
- `importFile` 返回 `sealed interface ImportResult { Enqueued(documentId) / Duplicate(existingDocumentId) }`（仅 `importStaged` 调用，已 grep 无其他调用方）。
- 流程：流式 SHA-256 → `byChecksum` → 过滤 PENDING/PROCESSING/READY → 命中再显式复查 checksum → 删除 staging 副本、不移动文件、不写 DB、不产生 job；cancelled/failed 允许重导。
- `importStaged` 对 Duplicate 不计数、不触发多余入队，返回类型不变。
- 单测：`FtsAndImportDedupTest`（去重 6 例 + 状态/结果 3 例）；**审查后补强**：新增 instrumentation `ImportDedupDbTest`（2 用例：同内容只登记一次且删 staging 副本、cancelled 允许重导）。

### B1-7 列默认值 schema/迁移同源
- 实体补 `@ColumnInfo(defaultValue=...)`；v5 迁移重建表时写入相同 DEFAULT；`5.json` 与迁移 DDL 一致。
- 迁移测试断言 `PRAGMA table_info` 默认值（sizeBytes=0 / accelerator='cpu' / quantization='f32'）。

### B1-8 迁移测试补强
- 更新既有 MigrationTest（Room builder 补 `MIGRATION_4_5`）。
- 新增 `MigrationV4ToV5Test`（5 用例）：schema/FK 8 条/默认值/14 索引/schema_migrations/12 表代表性数据无损/孤儿清理两例。
- 新增 `MigrationV1ToV5Test`（1 用例）：v1→v2→v3→v4→v5 链式迁移 + 数据/FTS/默认值断言。
- 新增 `LegacyBackupV1FixtureTest`（2 用例）：手工构造 v1 zip（无 app_kv/sizeBytes/uri、v1 3 段式向量行）preview+import 兼容；截断包拒绝且不写库。

### B1-9 备份导出引用闭包一致
- 新增 `core/backup/BackupClosure.kt`（纯 JVM）：`BackupExportClosure`（流式：只有 ready 文档及其子表进入闭包）、`validateImportClosure`（导入解析期校验 document.sourceId/chunk.documentId 引用，向量引用仍由 `BackupValidator.decodeVectors` 校验）。
- `KnowledgeBackup.exportZip` 重写：documents→sources→chunks→vectors 全按引用闭包过滤；`embedding_meta`/`model_requirements` 按实际引用空间裁剪；FTS 不进包（导入重建）；两遍分页扫描保证向量头 count 精确。
- 快照路径 `includeNonReadyDocuments=true`（回滚安全网保留全部文档，父子仍成对）。
- `FORMAT_VERSION` 仍 2、6 个公开入口签名不变、v1/v2 包读取兼容（旧包含非 ready 文档仍可导入）。
- **审查后补强**：恢复旧包中的 pending/processing 文档归一为 failed（无对应 ingest_job，避免"永远处理中"僵尸行）；failed/cancelled 保持原状。
- 测试 +7：非 ready 排除、闭包无孤儿、快照父子成对、缺父可读报错、合规闭包、向量引用缺失。

### B1-10 双状态机统一
- 新增 `core/ingest/IngestStateMachine.kt`（纯函数）：`ingest_job` 为唯一事实源，document.status 派生；合法组合矩阵（8×5=40 组合中 12 合法）；`doc=cancelled + job=DONE/FAILED/QUEUED` 明确非法。
- `IngestRepository` 状态写入收敛到事务 helper：`claimJob/transition/publishReady/failOrRequeue/requeueAfterInterruption/finishCancelled/recoverInterrupted/cancelImport/retryFailedJobs`（`db.withTransaction` + 现有 DAO，无需新增 DAO）。
- `IngestBatchWorker` 不再直接写状态列。
- 单测 `IngestStateMachineTest` 7 例（含全枚举矩阵）。
- **审查后补强**：`INTERMEDIATE` 集合改为复用 `JobStage.INTERRUPTED_VALUES` 单一事实源（消除重复手写集合的漂移风险）。

### B1-11 modelRevision 惰性回填
- `resolveSpaceId` 决策完成后调用 `ensureMetaLazyMaintenance`：`AtomicBoolean` + `app_kv("meta_lazy_maintenance_v1")` 双守卫，每进程最多一次、跨进程一次、失败可重试，不阻塞检索。
- 单事务内：回填空 revision（catalog sha 前 12，按 modelId 反查）+ active 收敛。
- 时点限制注释：只能按文件名猜测历史权重版本，升级前已替换同名文件的混用不可追溯（发布说明见 B6-4）。
- 单测 `MetaRevisionBackfillTest` 8 例；真机验证 GPU 空间 revision 回填为 `92dcbea10889`。

### B1-12 旧空间采纳测试矩阵
- 纯 JVM `LegacySpaceAdoptionMatrixTest`（12 用例）：GPU/NPU/PathC/740M 旧格式 id 采纳、`@rev` 新空间、granite/440M 兼容分支、CPU/GPU 隔离。
- instrumentation `LegacySpaceAdoptionDbTest`（3 用例）：DB 采纳分支（旧行在则采纳、回填后仍旧 id、同名换文件才新空间、无存量行新 id 带 @rev）。
- 真机旧库（v3，含旧 GPU 空间 id）升级后检索仍使用旧 id、0 refused → 与测试矩阵一致。

---

## 3. 验证证据

### 3.1 JVM / 静态门禁

```text
$ ./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin \
    :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
BUILD SUCCESSFUL
JVM tests = 153 / failures = 0

$ ./gradlew :app:lintDebug      # abortOnError = true
BUILD SUCCESSFUL                # 0 errors / 21 warnings

$ ./gradlew :app:assembleDebug :app:assembleRelease   # R8 + shrinkResources
BUILD SUCCESSFUL
app-debug.apk 62,513,793 B；app-release-unsigned.apk 48,604,274 B
```

### 3.2 真机 instrumentation（OnePlus PJZ110 / Android 16 / arm64）

`./gradlew :app:connectedDebugAndroidTest` → **20 tests / 0 failures / 0 errors**：

| 测试类 | 用例 | 结果 |
|---|---|---|
| MigrationV4ToV5Test | schema/FK/默认值/索引/迁移记录；12 表数据无损；孤儿行清理；孤儿 document 链；孤儿 FTS 行 | PASS ×5 |
| MigrationV1ToV5Test | v1→v5 链式迁移 + 数据/FTS/默认值 | PASS |
| MigrationTest / MigrationV2ToV3Test / MigrationV3ToV4Test | 既有迁移回归 | PASS ×4 |
| LegacySpaceAdoptionDbTest | 4 类旧 id 采纳 / 回填 / 换文件 / 无存量行 | PASS ×3 |
| LegacyBackupV1FixtureTest | v1 包 preview+import 默认值；截断包拒绝不写库 | PASS ×2 |
| BackupRoundTripTest | 导出→导入→再导出；坏包原库不变 | PASS ×2 |
| SearchVisibilityTest | 只检索 ready 文档 | PASS |
| ImportDedupDbTest | 同内容只登记一次、staging 副本删除；cancelled 允许重导（B1-6） | PASS ×2 |

> 期间发现并修复一个测试代码问题：androidTest 中反引号测试名含空格会在 D8（DEX < 040）报 `Space characters in SimpleName`，已把 3 处命名改为无空格（`LegacySpaceAdoptionDbTest`）。

### 3.3 真机旧库升级（v3 → v5 链式）

- 设备原始数据：schema v3、81 文档 / 83 chunk / 2 个 embedding 空间（含旧 GPU id）。
- 安装 v5 APK 后触发迁移，force-stop 后拉取库（main+WAL）校验：

```
PRAGMA user_version                = 5
chunk 行数                          = 83（无损）
schema_migrations 行数              = 5（版本 1..5）
PRAGMA foreign_key_check            = 空（无违规）
embedding_meta:
  mediapipe-universal/...-gpu  modelRevision=92dcbea10889  active=1  accelerator=cpu
  mp-universal/.../app-prefix-v1 modelRevision=92dcbea10889 active=0  accelerator=cpu
```

- 迁移后检索评测（262 查询）：**0 refused / 0 error**；R@1=0.964、R@5=0.991、R@10=1.000；p50=49ms、p95=57ms；使用旧 GPU 空间 id（采纳逻辑生效）。

### 3.4 设备恢复

- 验收后重装 debug 包（`DEBUGGABLE`），恢复原始 v3 DB（81 文档）+ 440M 模型 + 旋转设置，清理 `/data/local/tmp` 临时文件。

---

## 4. 计划偏差与偏离说明（已文档化）

| # | 计划原文 | 实际实现 | 原因/影响 |
|---|---|---|---|
| 1 | B1-2 "FK 覆盖 7 条" | **8 条**（event_entity/chunk_entity 各 2 条，计 8） | 计划计数笔误；测试按 8 条精确断言；schema/迁移一致 |
| 2 | B1-1 "显式 rowid=chunkId 对齐" | **不对齐**：修正注释 + 写入去重；FTS5 仅评估（评估结论已写入 `IngestRepository` 注释） | 存量 FTS 迁移 rowid 需重建虚拟表，风险高且 FTS 可从 content 重建；业务键 chunkId 已足够 |
| 3 | B1-5 "DB CHECK 约束" | **不加 CHECK**，仅 Kotlin 枚举/DAO 校验 | Room 无 `@Check` 注解，fresh 库不会生成 CHECK → 与迁移库不一致且难验证（可行性审查结论） |
| 4 | B1-7 "列默认值" | 已实现（实体 default + 迁移重建） | 与计划一致 |
| 5 | B1-10 "新增 DAO @Transaction 组合方法" | 用 `db.withTransaction{}` + 现有 DAO helper（未新增 DAO 方法） | 等价语义，减少 DAO 面积/避免与枚举改动冲突 |
| 6 | B1-6 `importFile` 返回 String | 改 sealed `ImportResult` | 仅内部调用方；`importStaged` 返回类型不变；已在 task/报告记录 |

---

## 5. 剩余风险与未覆盖项

| # | 风险 | 说明 | 处置 |
|---|---|---|---|
| 1 | 迁移前无法自动快照 | Room 打开即迁移；应用层无可靠 pre-migrate 钩子 | 迁移在事务内、失败回滚；真实 v4 DB 快照 + 真机 v3→v5 已验证；发布说明提示用户先导出（B6-4） |
| 2 | 备份恢复仍直接写 document.status | 未纳入 B1-10 统一事实源（跨模块） | 记录后置；恢复数据为备份快照，状态由备份时决定 |
| 3 | Duplicate 无 UI 文案 | `importStaged` 返回类型未变，UI 暂看不到"已存在 N 个" | 已登记为 P2 计划 **B3-13** |
| 4 | `document.checksum` 无索引 | 每文件一次全表 checksum 扫描（当前数据量小） | 若 10k 级文档再评估 schema v6 索引 |
| 5 | 旧库历史 FTS 同 chunkId 重复行不去重 | 迁移不处理（不属 FK 范围）；`ftsHealthCheck` 可发现、`rebuildFtsIndex` 可修复 | 记录；需要时执行重建 |
| 6 | 无 FK 的 orphan `ingest_job` 不清理 | 按设计 `ingest_job.sourceId/documentId` 未加 FK | 由 `recoverInterrupted`/`claim` 状态机兜底；如需可 v6 加 FK |
| 7 | 惰性 revision 回填触发点为首次检索/导入 | 非 Application.onCreate | 行为可接受（不影响检索）；如需更早可加启动钩子（B3/B6） |
| 8 | GPU 空间 accumulator 显示 cpu | 旧 meta accelerator 列为历史值（spaceId 后缀才是 GPU 证据） | 历史数据不重写；B6-4 发布说明/后续可做迁移回填 |
| 9 | Robolectric 尚未引入 | 用户已批准 B5；本轮未做 | B5-1 按计划执行 |

---

## 6. 独立审查（Standards + Spec）与处置

### 6.1 Standards 轴

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | 备份恢复把 JSON `status` 原样写入（默认值 "active"/"ready" 也是字面量），绕过 B1-5 枚举单源，未知状态可入库 | **已修**：`requireSourceStatus/requireDocStatus` 校验，未知状态写库前报错；默认值改用枚举 `.value` |
| S2 | 硬性 | `Daos.kt` 删除级联注释仍称"无外键级联"，与 v5 事实相反 | **已修**：注释更新为"v5 已有 FK CASCADE，手工子查询级联保留为幂等防御" |
| S3 | 硬性 | `StatusEnums` 声称由 `StatusEnumsTest` 覆盖但测试不存在 | **已修**：新增 `StatusEnumsTest`（5 用例，JVM 153 绿） |
| S4 | 判断性 | FTS 去重 helper 近似 no-op，测试名过强 | **已修**：注释明确"防御性兜底、真正防重是写入前 DELETE"；测试名改为"防御性去重：同一 chunk 出现两次时只保留一行" |
| S5 | 判断性 | `IngestStateMachine.INTERMEDIATE` 与 `JobStage.INTERRUPTED_VALUES` 重复维护 | **已修**：直接复用 `INTERRUPTED_VALUES.mapNotNull(fromValue)`，消除重复集合 |
| S6 | 判断性 | 运行时 `deactivateOthers` 与备份 `normalizeActiveSpaces` 语义/命名近似、职责边界模糊 | **已修**：重命名 `normalizeRestoredActiveSpaces` 并注释两者差异（恢复无"当前空间"概念→确定性保留最早 createdAt） |
| S7 | 判断性 | 零调用兼容重载 `activeCount()`；备份注释称其在用（实际已改 all()+过滤） | **已修**：删除零调用重载；`KnowledgeBackup` 改用 `activeCount(JobStage.ACTIVE_VALUES)` 并更新注释 |
| S8 | 判断性 | 陈旧注释：`ensureMetaRevisionsBackfilled`（不存在）、`ACTIVE_VALUES` 用途描述错误 | **已修**：注释更正为 `ensureMetaLazyMaintenance` / `*IfNotCancelled` guard 是 CANCELLED；FTS5 评估结论补充进 `IngestRepository` 注释 |

### 6.2 Spec 轴

| 项 | 原结论 | 处置 |
|---|---|---|
| B1-1 | implemented | 保留；审查提到的 FTS5 结论缺失 → 已补注释 |
| B1-2/3/4/7/8/11/12 | implemented | 无需修改 |
| B1-5 | partial（无 `StatusEnumsTest`） | **已补**：`StatusEnumsTest` + 备份恢复状态校验 |
| B1-6 | partial（无 DB 级去重测试、UI 无提示） | **已补**：`ImportDedupDbTest`（instrumentation 2 例）；UI 提示登记为 B3-13 |
| B1-9 | partial（旧 App 读新包无说明；旧包非 ready 文档成无 job 僵尸） | **已修**：恢复时 pending/processing 归一为 failed；旧 App/新包语义写入 P2 计划 B6-4 发布说明项 |
| B1-10 | partial（备份恢复旁路统一入口；矩阵仅测试覆盖） | **接受并记录**：备份恢复写的是备份快照状态（有意），非实时状态机；矩阵纯函数测试已锁定契约 |
| 范围/兼容 | 工作树含 B1 外改动 | **澄清**：`build.gradle/proguard/.github/UPSTREAM` 等属于上一轮 P0/P1 未提交变更，不是 B1 范围；本报告 §7 列出的是 B1 相对 P0/P1 终态的增量 |
| 计划偏差 | FK 8 vs 7、FTS rowid 不迁移 | 文档化于 §4；`P2_FIX_PLAN` 已补 B1 完成状态与 B6-4/B3-13 跟进项 |

### 6.3 审查后复验（最终）

- `compileDebugKotlin / compileReleaseKotlin / compileDebugUnitTestKotlin / compileDebugAndroidTestKotlin` ✅
- JVM `testDebugUnitTest`：**153 tests / 0 failed**（新增 StatusEnumsTest 5 例）
- `lintDebug`：**0 errors / 21 warnings**
- `assembleDebug` / `assembleRelease`（R8）✅
- 真机 `connectedDebugAndroidTest`：**20 tests / 0 failed**（新增 ImportDedupDbTest 2 例；期间修复 JUnit lifecycle 返回值非 void 导致的 initializationError）

---

## 7. 文件清单（B1 相对 P0/P1 终态）

**Lead 地基**：`data/db/Entities.kt`（8 FK + 默认值）、`data/db/AppDatabase.kt`（MIGRATION_4_5，v5）、`app/schemas/.../5.json`（新增并 git add）
**B1-5**：`data/db/StatusEnums.kt`（新）、`data/db/Daos.kt`、14 个调用方文件（core/ui/debug）
**B1-9**：`core/backup/BackupClosure.kt`（新）、`core/backup/KnowledgeBackup.kt`、`core/backup/BackupValidatorTest.kt`
**B1-1/4/6/10/11**：`core/ingest/IngestStateMachine.kt`（新）、`core/ingest/IngestRepository.kt`、`core/ingest/IngestBatchWorker.kt`、`core/embedding/EmbeddingRuntime.kt`、3 个新增 JVM 测试
**B1-8/12**：`androidTest/.../MigrationTest.kt`（更新）、`MigrationV4ToV5Test.kt`、`MigrationV1ToV5Test.kt`、`LegacySpaceAdoptionDbTest.kt`、`LegacyBackupV1FixtureTest.kt`（新）、`test/.../LegacySpaceAdoptionMatrixTest.kt`（新）
**审查后修复/新增**：`data/db/StatusEnums.kt`（注释）、`data/db/Daos.kt`（删除零参 activeCount/级联注释）、`core/backup/KnowledgeBackup.kt`（恢复状态枚举校验 + non-ready 归一 + `normalizeRestoredActiveSpaces` + activeCount 参数化）、`core/ingest/IngestStateMachine.kt`（复用 INTERRUPTED_VALUES）、`core/ingest/IngestRepository.kt`（FTS5 评估/去重定位注释）、`core/embedding/EmbeddingRuntime.kt`（注释）、`test/.../data/db/StatusEnumsTest.kt`（新）、`androidTest/.../core/ingest/ImportDedupDbTest.kt`（新）、测试名/断言修正
**集成修复**：`androidTest/.../LegacySpaceAdoptionDbTest.kt` 3 个测试名去空格（D8 兼容）、`Daos.kt` 删除 `activeSpace()`

---

*报告生成：2026-10-17 · B1 代码变更未提交；设备已恢复；完整门禁命令见第 3 节。*
