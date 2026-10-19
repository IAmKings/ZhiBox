# ZhiBox 代码质量复审报告 R2

- **审查对象**：ZhiBox 端侧个人知识记忆 + SAG 问答（Android / Kotlin / Compose / Room / MediaPipe-LiteRT / WorkManager / OpenAI-compatible LLM）
- **审查基线**：`main` @ `0139f62`（工作区含 `.trellis`/`.agents` 等未跟踪工具配置，不影响产品源码）
- **审查日期**：2026-10-19
- **与 R1 的关系**：R1（[`docs/CODE_QUALITY_REVIEW.md`](CODE_QUALITY_REVIEW.md)，2026-10-17 @ `9ca20d6`）给出 P0×3 / P1×10 类 / P2×25；其修复路线图（B1–B12 批次）已执行完毕。**本报告两项任务**：① 逐条核验 R1 问题的闭环状态；② 审查 R1 之后新增的 B8–B12 代码（SAG 检索/抽取管线、LLM Provider、Chat 问答、事件图谱、备份 v3——约 1.5 万行，R1 从未见过）。
- **审查方式**：Lead 精读核心层（备份/导入/检索/嵌入运行时/引擎缓存/构建/清单）+ 2 个并行只读审查组（UI 层；SAG·LLM·Chat·抽取层）；全程未修改任何产品源码，仅新增本文档。
- **验证手段**：Gradle 编译、单元测试、Android Lint、git 元数据、关键 file:line 逐条回读核验（子代理引用与实际行号有 2 处偏差，已修正，见附录 B）。
- **结论一句话**：**R1 的全部 P0/P1 已按"重构到位 + 单测覆盖"标准闭环，B8–B12 新代码无 P0、仅 2 个 P1（均为"个人小库规模假设"问题）——当前状态可签名发布；P1 两项在事件数 < 5000 的目标场景下不触发。**

---

## 0. 结论速览

### 0.1 质量评级（对比 R1）

| 维度 | R1 | R2 | 依据 |
|---|---|---|---|
| 架构与模块边界 | B+ | **A−** | VM+StateFlow 全覆盖；备份 v3 引入 BackupValidator/BackupClosure 单一事实源；引擎生命周期抽纯 JVM EngineCache；仍存 ingest↔embedding 常量级耦合（已收敛为 `SUPPORTED_EMBEDDING_PROVIDERS`） |
| 算法与数值严谨性 | A− | **A−** | 维持：RRF/流式扫描/top-K 有界、维度与有限值校验齐全；SAG 桥接救援分可解释性欠佳（P2-7） |
| 可读性与注释 | B+ | **A** | 中文注释继续记录"为什么"（实测根因/取舍/审查修复编号），1 处孤儿 KDoc（P2-16） |
| 正确性与错误处理 | C+ | **A−** | 备份先校验后写库+单事务+快照；取消/中断/重试三态分离；CancellationException 全链重抛；剩余 P1×2 见 §3 |
| 并发与生命周期 | C | **A−** | 双状态机同事务迁移、原子 claim、NonCancellable 清理、引擎锁内 shutdown；剩余为抽取 worker 队首竞争等小项（P2） |
| 性能上限 | C+ | **B+** | 矩阵缓存+索引+流式扫描+批量回填落地；SAG 层新引入 2 处规模假设缺陷（§3）与若干 N+1（P2） |
| 测试与质量门禁 | C− | **A−** | 520 JVM + 139 instrumentation；lint 门禁（abortOnError=true）0 error；CI 双 workflow + 依赖 SHA-256 锁定；无 detekt/ktlint |
| 安全与隐私 | C− | **A−** | allowBackup=false、networkSecurityConfig、真 AndroidKeyStore 存 API key、异常/日志全链脱敏；SSE 有界读取滞后一处（P2-5） |
| 发布工程 | D | **A−** | release 签名 + R8 + shrinkResources + tag 自动发布 workflow；交付链可追溯（BUILD_GIT_SHA/SOURCE_DATE_EPOCH） |

### 0.2 本次实测验证结果

| 检查 | 命令 | 结果 |
|---|---|---|
| Debug 编译 | `./gradlew :app:compileDebugKotlin` | ✅ **0 Kotlin 编译警告**（仅 1 条 Gradle 版本弃用提示，非代码） |
| 单元测试 | `./gradlew :app:testDebugUnitTest` | ✅ **520 用例 / 0 失败 / 0 错误**（R1 时 34 个） |
| Android Lint | `./gradlew :app:lintDebug`（`abortOnError=true`） | ✅ **0 errors** / 40 warnings（非阻塞，多为依赖过期提示） |
| Room schema | `git ls-files app/schemas` | ✅ **1–7 全部 7 个版本入库**（R1 时仅 1.json） |
| CI | `.github/workflows/` | ✅ ci.yml（build+test+lint+verification）+ release.yml（tag 签名发布） |
| 依赖供应链 | `gradle/verification-metadata.xml` | ✅ 613 个组件 SHA-256 strict 校验 |
| Release 通道 | `app/build.gradle.kts` | ✅ 签名材料外部注入 + R8 + shrinkResources + keep 规则 |

### 0.3 规模对比（R1 → R2）

| 项目 | R1（10-17） | R2（10-19） |
|---|---|---|
| 主源码 | 7,211 行 / 43 文件 | **22,670 行 / 102 文件**（benchmark 已整体迁出 main → `app/src/debug`） |
| JVM 单测 | 34 用例 | **520 用例** |
| androidTest | 3 用例 | **139 用例**（迁移链 v1→v7、备份回环、真机 UI/评测门） |
| schema 版本 | v3（2/3 未入库） | v7（全部入库，6 段迁移 + 链式测试） |
| lint | 6 errors（门禁失效） | **0 errors**（门禁生效） |

---

## 1. R1 问题闭环核验（P0×3 + P1×10 → 全部 FIXED）

| R1 编号 | R1 结论 | 现状 | 修复证据（file:line 以 `0139f62` 为准） |
|---|---|---|---|
| P0-1a | 导入先清库后非事务写入，失败即清空知识库 | **FIXED** | `KnowledgeBackup.kt:1290-1327`：readArchive 全量读取 → parsePackage 完整语义校验（任何失败不触库）→ 自动快照（`:1389-1404`，保留 2 份，快照失败中止导入）→ `writeDatabase` 单事务显式 DELETE + 批量插入（`:1421-1490`） |
| P0-1b | 导入不恢复 embedding_meta，恢复后再导出丢全部向量 | **FIXED** | `KnowledgeBackup.kt:1462-1466` 恢复全字段 meta（含 accelerator/quantization/modelRevision/active 归一 `:1414-1419`）；导出 meta/requirements 按引用裁剪并校验 `written == count`（`:497-547` 五处一致性 check）；回环测试 `BackupV3RoundTripTest`/`BackupLegacySpaceTest` 覆盖 |
| P0-2 | 检索向量扫描/首模型加载/导入 IO 在 Main 线程 | **FIXED** | `SearchService.kt:95,373` 整体 `withContext(Default)`；`EmbeddingRuntime.kt:79` 引擎构建+block 统一 Default；`IngestRepository.kt:83-85,209,269,336` 编排层整体 IO；文件哈希流式化（`Sha256.hex`） |
| P0-3 | 实验代码 37.6% 在 main、exported 后门、无 release 通道 | **FIXED** | benchmark 13 文件 + BenchmarkScreen 迁入 `app/src/debug`（release 为空 stub）；`MainActivity.kt:58-75` autostart 加 FLAG_DEBUGGABLE 门禁 + `savedInstanceState==null` + `replaceExtras(Bundle())` 一次性消费；`app/build.gradle.kts:89-115` release 签名外部注入 + R8 + 资源裁剪；jieba 降为 `debugImplementation` |
| P1-1 | 失败/取消的半成品文档可被检索 | **FIXED** | 检索 DAO 全部 JOIN `status='ready'`（`SearchService.kt:116,255-257,293-294,332`）；发布语义"全部派生数据写完最后置 ready"（`IngestRepository.kt:516-540` publishReady 单事务）；失败/取消路径 `deleteDerivedRows` 同事务清理（`:632-636`）；维度校验提前到首批向量入库前（`:395-405`） |
| P1-2 | CancellationException 被当业务失败、失败无重试 | **FIXED** | `processDocument` 三分支：取消→NonCancellable 清理+重排队+重抛（`:436-440`）、Error→直抛（`:441-443`）、Exception→`PipelineError.classify` 分类重试 3 次或终态（`:444-451,546-578`）；UI 层 `runCatchingCancellable` 模式贯穿（`KnowledgeBackup.kt:121-122`） |
| P1-3 | 引擎一次失败永久不可用 / GPU 试探泄漏 / shutdown 无锁 | **FIXED** | 纯 JVM `EngineCache.kt:37-61`：先清引用再关闭、构建失败保持空自愈、shutdown 与 access 同锁；`probeGpuWithTimeout`（`:74-91`）超时回收已构建 GPU 实例；单测 `EngineCacheTest` 覆盖 |
| P1-4 | importStaged 异常死局 / WorkManager 丢唤醒 / 重嵌入分钟级锁 | **FIXED** | `importStagedInternal`：逐文件 try/catch + `finally` 登记过即入队（`:223-248`）+ activeCount>0 时补入队（`:215-218`）；`ensureIngestWorker` 改 `APPEND_OR_REPLACE`（`:938-945`）；ReembedWorker 锁粒度每批 + 进度上报（`ReembedWorker.kt`） |
| P1-5 | 每次查询全表载入向量、无缓存、缺索引、N+1 | **FIXED** | `(embeddingSpaceId, role)` 索引（`Entities.kt:125`）；`VectorMatrixCache`（64MB LRU + 签名失效）+ 超限流式扫描安全阀（`SearchService.kt:220-243,281-317`）；证据回填三表批量 `IN` 分批（`:410-417`）；写路径主动失效缓存（`IngestRepository.kt:420`；导入后 `KnowledgeBackup.kt:1488`） |
| P1-6 | modelRevision 恒 null、accelerator/quantization 失真、SPACE_ID_C 死常量、sizeBytes 恒 0 | **FIXED** | `registerSpace` 成为 meta 唯一写入口，全部字段由 descriptor 生成（`EmbeddingRuntime.kt:197-245`）；同名换文件自动 `@rev` 新空间（`:267-294`）+ 历史 meta 惰性回填（`:315-365`）；`SPACE_ID_C` 已随 Path C 下线删除；`importFile` 写 sizeBytes、解析后回写 parserId（`:308-311,354`） |
| P1-7 | 备份无完整性校验、模型供应链断裂、verified 门只写不读 | **FIXED** | `BackupValidator`（读档/格式版本/尺寸上限/向量头校验）+ `ModelIntegrity.verify`（SHA-256+精确尺寸）用于导入前（`KnowledgeBackup.kt:1356-1386`，含 catalog_sha256 版本比对与按模型去重避免重复哈希）；SAF/备份在位判定改真实候选路径 `PathPreferences.candidates()`（`:1246-1267`） |
| P1-8 | 无 ViewModel/BackHandler/saveable、autostart 重放、runProbe 卡死 | **FIXED** | 6 屏 ViewModel + UiState + StateFlow 落地；`MainActivity.kt:79,93` screen 可保存 + BackHandler 回首页；证据选中入 VM（证据返回不再丢状态）；BenchmarkScreen 统一 activeRun + try/finally 复位（debug 源集） |
| P1-9 | lint 失败、schema 未入库、无 CI、测试空洞 | **FIXED** | 见 §0.2 实测表；另有 `testOptions` 注释记录 Robolectric 内存配置取舍 |
| P1-10 | allowBackup=true、无网络安全配置、SAF clearAll 过宽 | **FIXED** | `AndroidManifest.xml:21-22`：`allowBackup="false"` + `networkSecurityConfig`（禁明文、仅放行 hf-mirror.com）；跨设备迁移走产品自带 KnowledgeBackup |

> R1 的 P2×25 中，抽查确认大部分已随批次消化（FTS 去重、查询上限、NFC 规范化、续传 206 校验、`schema_migrations` 语义等）；未逐条复核的剩余项不构成本次复审范围（见 §7）。

---

## 2. B8–B12 新代码审查

### 2.1 总体判断

新增约 1.5 万行（core/sag、core/extraction、core/llm、core/chat、core/extract、备份 v3、图谱 UI）质量**高于典型业务 Android 代码**：

- **安全基线扎实**：`KeystoreLlmConfigStore` 为真 AndroidKeyStore AES-256/GCM（iv+ciphertext 分存，commit 报错不吞）；全模块 0 处 Log/println；异常与错误 detail 均不含 key/正文；`LlmConfig.toString` 脱敏；NetworkPolicy 默认禁网且每次实时读取；仅 HTTPS、不跟重定向、禁 userinfo、响应体有界读取。
- **并发设计正确**：抽取管线"原子 claim + 先算向量后单事务写入 + 事务内二次守卫 + NonCancellable 恢复"；`CancellationException` 在所有层级原样重抛；WorkManager 停止不误判业务失败。
- **解析层严格**：ExtractionJson/parseSseLine/CitationValidator 不抛异常、字段封闭校验、中文标点/代理对边界与 prompt 注入防护显式考虑。
- **备份 v3 是全仓库最扎实模块**（见 §1 P0-1 行）。

### 2.2 P1（2 条，建议下个迭代修复）

#### P1-R2-1 SAG 事件向量扫描按 id 截断 5000 条，超限后新事件静默不可召回

- **位置**：`core/sag/SagRetriever.kt:340-346` + `data/db/Daos.kt:832-835`（`bySpace ... ORDER BY id LIMIT :limit`）、上限常量 `core/sag/SagSupport.kt:27`（`EVENT_VECTOR_SCAN_LIMIT = 5_000`）
- **证据**：事件向量 seed 查询按 `id` 升序取前 5000 行——超限后**最新**事件（id 最大）恰好被截掉；且截断发生时不写任何 degradedReason/trace 标注，multi_es_fast/multi 模式对最新内容整体"失明"而无提示。
- **影响**：事件数（含 superseded 历史行）超 5000 后检索质量静默退化。注意 event 含历史版本行，活跃文档 1-2 千篇即可能触达。
- **建议**：① 按 `createdAt` 降序或活跃度选取；② 分块扫描（复用 `SearchService` 流式 top-K 模式）；③ 至少在截断发生时写 trace 的 degradedReason。
- **工作量**：S（0.5 人日）

#### P1-R2-2 抽取热路径全量加载整篇 chunk，长文档 O(N²)

- **位置**：`core/extraction/SagExtractionPipeline.kt:494-499`（`previousContext` 用 `chunkDao.byDocument(documentId)` 全列加载整篇、Kotlin 侧过滤 ordinal-1）；同类：`SagExtractionScheduler.kt:46`（建任务全量加载 chunk 只用 id/count）
- **影响**：每 chunk 抽取任务重复反序列化整篇文档全部 chunk（含正文 BLOB），N chunk 文档 O(N²) 次行物化； Scheduler 每文档一次全量加载。
- **建议**：DAO 增加 `byDocumentAndOrdinal(documentId, ordinal)` 单行查询；Scheduler 用 `countByDocument`/id-only 查询。
- **工作量**：S（0.25 人日）

### 2.3 P2（16 条，按领域归类）

| # | 领域 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| 1 | SAG | `SagRetriever.kt:377-380,476-479` | 桥接锚点逐个 `loadEventEntityIds(listOf(eventId))` N+1 查询；anchor 又单独重查 | 一次批量传入全部锚点 id |
| 2 | SAG | `SagRetriever.kt:485-501` | 桥接救援把命中分硬抬到常量 `BRIDGE_RESCUE_SCORE`(0.985)，UI/trace 无法与真实融合分区分 | 单列 rescue 标记而非改写 score |
| 3 | 抽取 | `SagExtractionWorker.kt:66-80` | parallelism>1 时 N 个协程同取队首 `nextQueued(1)`，N-1 次 claim 必败 + delay(20) 重试（惊群） | DAO 支持跳过在途 id 或错位取队列 |
| 4 | 抽取 | `SagExtractionPipeline.kt:103-114` + `Daos.kt:970-982` | `markNotIndexed` 的 WHERE 无 status 守卫：A 协程读 QUEUED 后、B 协程 claim 成 RUNNING 的窗口内，A 可把 RUNNING 踩成 not_indexed（卡到 backfill） | `markNotIndexed` 加 `AND status='queued'`（同族：`KnowledgeBackup.export` 的 `requireNoActiveJobs` 检查与后续写入间同样存在微小 TOCTOU 窗口，快照+事务已兜底，可不修） |
| 5 | LLM | `LlmPromptJson.kt:19-28` | `stripJsonFence` 对单行 `` ```json {...}``` `` 输出：`substringAfter('\n', "")` 返回空串 → B10 选择/实体抽取误降级；与 `ExtractionJson.stripCodeFence`（正则版，行为正确）口径不一 | 两处合一，用正则版 |
| 6 | LLM | `OpenAiCompatibleLlmProvider.kt:328-336` | `MAX_STREAM_CHARS`/单行上限在 `readLine()` 返回**之后**检查：恶意服务端可先用单条超大行撑大内存 | 有界 readLine（逐 char/定长缓冲） |
| 7 | Chat | `ChatRepository.kt:87-88` | 借 `renameConversation(id, 读出的旧 title, now)` 回写 updatedAt：与并发改名竞态会还原用户改名，且非专用语义 | 改专用 `touch(id, now)` UPDATE |
| 8 | UI | `SettingsViewModel.kt:172-175` | Settings VM 仅 `init{refresh()}`，无进场刷新（手动导航下 VM 常驻 Activity）：导入/删源后再进设置页计数陈旧；Chat/Graph/Sources/Import 均已做 onScreenStarted | 补 onScreenStarted→refresh |
| 9 | UI | `ui/*.kt`（实测 `collectAsState()` ×26、`collectAsStateWithLifecycle` ×0） | 后台仍收集重组；如 `ChatScreen.kt:58`、`GraphScreen.kt:50` | 统一替换为 WithLifecycle 变体 |
| 10 | UI | `SearchViewModel.kt:164-190` ≈ `SettingsViewModel.kt:1047-1073` | 查询 trace 导出两处重复约 40 行（JSON 组装+落盘） | 抽公共函数 |
| 11 | UI | `SettingsViewModel.kt:1081` | 死代码：`traceTotalCount = _state.value.traceTotalCount` 自赋值 | 删除 |
| 12 | UI | `SourcesViewModel.kt:66-72` | 每次 documents 发射逐文档 N 次 `byDocument` 查询且无去抖，文档多时 IO 放大 | 聚合 SQL（`WHERE documentId IN`）或 debounce |
| 13 | UI | `SettingsViewModel.refresh()` | 无代际守卫：多个操作各触发全量 `loadSnapshot`（含 `indexHealthCheck` 全表孤儿扫描）并发跑，旧快照可能后到覆盖新状态 | 仿 `GraphViewModel.kt:95,107-114` 的 requestSeq 模式 |
| 14 | UI | `SourcesScreen.kt:91` | 图谱/来源 tab 用 `remember`（`pendingDelete` 同 `:64`），旋转后回退默认 tab | `rememberSaveable` |
| 15 | UI | `ChatViewModel.kt:266,587`、`GraphViewModel.kt:400`、`ChatScreen.kt:80` 等约 59 行 | 硬编码中文文案残留（多为异常分支；strings.xml 已有 366 条，主流程已资源化） | 迁 strings.xml |
| 16 | 管线 | `IngestRepository.kt:672-677` | 孤儿 KDoc：描述已删除的"模型路由"函数，挂在 `deleteSource` 头上，误导读者 | 删除该注释块 |

---

## 3. 遗留事项（非代码缺陷）

1. **lint 40 条 warnings**：多为依赖过期/UseKtx 提示。门禁只对 error 生效（`app/build.gradle.kts:139-148` 注释已说明取舍）；建议季度升级窗口清理。
2. **139 个未跟踪文件**：基本为 `.trellis/`、`.agents/` 工具配置。建议决策：工具配置入库（团队共享）或补 `.gitignore`。
3. **无 detekt/ktlint/.editorconfig**：编译零警告 + lint 门禁已覆盖大半；静态分析仍是增量项，非阻塞。
4. **B10/B11 门禁的过程性欠账**：git 历史显示部分真机 LLM 质量门曾因 API key 丢失被阻塞（如 `acc870b` "multi blocked by lost key"）——属测试环境问题而非代码缺陷，复测窗口需补。
5. **P1-R2-1 的规模验证**：需构造 >5000 event 的真机库验证截断行为与修复效果（可复用 `SagRetriever10kBaselineTest` 骨架）。

---

## 4. 做得好的（在 R1 §6 基础上新增，建议写进工程规范）

1. **备份 v3 的"先校验、后快照、再事务"三段式**：任何坏包/中途失败都不触碰原库；导出端五处 `written == counted` 一致性断言把"导出期间数据变化"从静默错包变为可读错误。
2. **状态机收敛**：`ingest_job.stage` 单一事实源 + `document.status` 派生 + 全部迁移走同事务统一入口，`updateStatusIfNotCancelled` 守卫使"取消被覆写回 ready"在 SQL 层不可能发生。
3. **纯函数抽取文化**：EngineCache/CandidateFusion/lazyMetaUpdates/spaceIdResolved/buildIndexHealthReasons/shouldAutoRetry 等全部可 JVM 单测——520 个单测的根基。
4. **安全默认值**：LLM key 进 AndroidKeyStore 而非 SharedPreferences；UiState 只持有掩码 key；`redactSecret` 兜底错误文案。
5. **注释继续记录"为什么"**：`EngineCache` 解释 close 顺序、`probeGpuWithTimeout` 解释 JNI 不可取消、`registerSpace` 解释 active 不变式——每处审查修复都带编号可追溯。
6. **依赖供应链**：613 个 SHA-256 strict 校验 + litertlm 用 dependency constraint（替代 resolutionStrategy.force）并注明 AAR 反编译证据。

---

## 5. 建议路线

| 优先级 | 内容 | 预估 |
|---|---|---|
| 本迭代 | P1-R2-1（截断标注最小修复：先写 degradedReason）；P2-4（markNotIndexed 加 status 守卫，1 行 SQL）；P2-5（fence 解析合一）；P2-11（删死代码） | 1 人日 |
| 下迭代 | P1-R2-2（byDocumentAndOrdinal）；P1-R2-1 完整方案（分块扫描）；P2-1/3/6/7（N+1/惊群/SSE 有界读/touch） | 1.5 人日 |
| 随手 | UI P2-8~15（WithLifecycle、进场刷新、saveable、代际守卫、文案迁移） | 1 人日 |
| 择机 | lint warnings 清理、detekt/ktlint、`.trellis` 工具文件入库决策 | — |

---

## 附录 A：本次验证命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin          # ✅ 0 warnings
./gradlew :app:testDebugUnitTest           # ✅ 520 tests / 0 failed（build/test-results 汇总）
./gradlew :app:lintDebug                   # ✅ 0 errors / 40 warnings
git ls-files app/schemas                   # ✅ 1..7.json 全部入库
git rev-parse --short HEAD                 # 0139f62（审查基线）
```

## 附录 B：引用核验说明

- 本报告全部 file:line 已对照 `0139f62` 工作区逐一回读核验；子代理原始报告中 2 处行号偏差已修正：`traceTotalCount` 自赋值实际在 `SettingsViewModel.kt:1081`（原报 1078）、来源页 tab `remember` 实际在 `SourcesScreen.kt:91`（原报 117）。
- 关键新发现（P1-R2-1/2、P2-4/5/6）由 Lead 独立回读源码确认，非仅采信子代理结论。
- 本报告未修改任何产品代码；仅新增本文档。

---

*R2 报告生成：2026-10-19 · 基线 `0139f62` · 上接 [`docs/CODE_QUALITY_REVIEW.md`](CODE_QUALITY_REVIEW.md)（R1，2026-10-17）*
