# ZhiBox P2 修复计划（P2_FIX_PLAN）

- **输入**：[`CODE_QUALITY_REVIEW.md`](../CODE_QUALITY_REVIEW.md) 第 4 节（25 项）+ 四份分领域报告的全部 P2（共 47 条）+ 真机验收新发现（`DEVICE_ACCEPTANCE.md` 第 6 节 D1–D6）+ P0/P1 审查遗留（autostart 收敛、SHA 工具抽取）
- **去重原则**：逐条核对当前冻结态代码，**剔除已在 P0/P1 修复中顺带闭环的项**（见第 2 节），避免重复排期
- **范围**：本计划只覆盖 P2；P0/P1 已完成（见 `FIX_REPORT.md`），真机验收已完成（见 `DEVICE_ACCEPTANCE.md`）
- **执行方式建议**：按 B1→B5 批次推进，每批结束跑一次全量门禁；schema 相关集中在 B1 一次性完成，避免多次迁移

---

## 1. 现状盘点（P2 总账）

| 状态 | 数量 | 说明 |
|---|---:|---|
| P0/P1 期间已顺带修复 | 17 | 见第 2 节（不重复排期） |
| 排期修复（含 10 项 partial 收尾） | 51 | 见第 3 节：B1 12 + B2 9 + B3 12 + B4 11 + B5 3 + B6 4 |
| 明确不排期 / 需产品决策 | 7 | 见第 6 节 |

> 明细可回溯：`docs/review/{data-layer,core-pipeline,ui-layer,infra}.md` 的 P2 小节 + `DEVICE_ACCEPTANCE.md` D1–D6。

---

## 2. 已在 P0/P1 闭环的 P2（供审计，不再排期）

| 来源 | 项 | 闭环证据 |
|---|---|---|
| data P2-9 | preview `vectorCount` 端序错误 | `KnowledgeBackup` LE 修复 + `BackupValidatorTest` 断言 |
| data P2-11 | `buildHits` N+1 | `SearchService` 改 `byIds` 三表批量 |
| core P2-2 | 查询长度无上限 | 512 字截断 / OR bigram ≤64 / IN 分批 400（`SearchLimitsTest`） |
| core P2-4 | meta accelerator/quantization/taskTemplate 失真 | `registerSpace` 由 descriptor 生成（真机验证 GPU 空间字段） |
| core P2-5 | parserId 恒 pending、sizeBytes 恒 0 | `updateParserAndSize` + 真机导入验证 |
| core P2-11 | `shutdown()` 无锁 | 改 `suspend + Mutex`（`EngineCache`） |
| core P2-12（部分） | 重嵌入/导入孤儿向量污染缓存 | 写路径 `VectorMatrixCache.invalidate()` + ready 签名 |
| ui P2-1 | 产品屏 249 处硬编码中文 | 164 条 `strings.xml`，产品屏中文字面量 0 |
| ui P2-2 | 六屏重复 Scaffold/TopAppBar | `ZhiBoxTopBar/ZhiBoxScreen` 抽取 |
| ui P2-12 | SourcesScreen 每次重组重建 Flow | ViewModel `stateIn` |
| infra P2-1 | 无 networkSecurityConfig/host 校验 | 新增配置 + `ModelDownloader.validateUrl` |
| infra P2-4 | ModelDownloader Range/进度边界 | 206+Content-Range 校验、失败重置 |
| infra P2-5 | SharedModelStore 边界风险 | setTreeUri 捕获异常、clearAll 只删已知模型文件、残缺文件清理 |
| infra P2-6 | 硬编码文案 | 同 ui P2-1 |
| 审查 §4-7 | 备份格式丢字段（source.uri/sizeBytes/role/vectorNorm/createdAt/app_kv） | v2 格式补齐 + v1 兼容（`BackupValidatorTest`/真机回环） |
| 审查 §4-17 | `documentId` 为 null 的 job 导致 Worker 死循环 | Worker 对该 job 标 failed 后 continue（`IngestBatchWorker`） |
| ui P2-14（部分） | autostart 300ms 时序脆弱 | 一次性消费已加；delay/多 extra 收敛仍待做 → **B3-11** |

---

## 3. 待修复项与批次计划

### 批次 B1：数据层正确性与 schema v5 演进（12 项，含 v5 迁移）✅ 已完成（2026-10-17，见 `B1_FIX_REPORT.md`）

> **一次性做 v5 迁移**，把 FK/列语义/默认值一次收敛。**可行性审查后的关键约束**：
> 1) **DDL 顺序（父→子）**：先清理孤儿行（`chunk.documentId`/`chunk_embedding.chunkId`/Phase2 关系行）→ 重建无 FK 依赖的父表（source/document/embedding_meta/ingest_job）→ 重建 chunk（FK→document）→ chunk_embedding（FK→chunk）→ entity → event（FK→chunk）→ event_entity/chunk_entity；迁移前后 `PRAGMA foreign_key_check`，结束后必须为空；
> 2) **CHECK 约束不可行**（Room 无 Check 注解，fresh 库不会生成 CHECK，会与迁移库不一致）→ 状态合法性用 Kotlin enum/DAO 校验 + 单测，不加 DB CHECK；
> 3) **迁移在 Room 事务内执行**，失败整体回滚；生产"迁移前快照"不可行（Room 打开即迁移）→ 以真实 v4 DB 快照做 instrumentation 迁移测试 + 事务回滚验收替代；
> 4) 存量 FTS 的 `rowid` **不做迁移对齐**（FTS 可从 chunk.content 重建），改为修正注释 + 写入去重；FTS5 external content 仅评估。
> 依赖：无（可最先做）。工作量合计约 **4.5–6 人日**。

| ID | 来源 | 问题（证据） | 方案 | 工作量 | 验收 |
|---|---|---|---|---|---|
| B1-1 | data P2-1 | `chunk_fts` contentful 重复存 bigram、注释称 rowid 与 chunkId 对齐但实现未对齐、无 UNIQUE 可能重复召回（`Entities.kt:57-64`、`IngestRepository.kt:147-150`） | 修正注释（明确 rowid 自增、chunkId 为业务键）；`insertFts`/`rebuildFtsIndex` 前按 chunkId 去重；评估 FTS5 external content（**仅评估，不换**） | S | 重复导入/重建不产生重复 FTS 行；注释与实现一致；评估结论记录 |
| B1-2 | data P2-2（合并原 B1-3/4/5/7/10 的 DDL 部分） | 13 表 0 外键；手工级联只覆盖 5 表；v5 需同时改列语义/默认值 | **单一 v5 迁移**：按上述父→子顺序加 FK `ON DELETE CASCADE`（chunk/document、chunk_embedding/chunk、event/chunk、event_entity/event/entity、chunk_entity/chunk/entity）；同迁移补 `@ColumnInfo(defaultValue)` 对齐；迁移前后 `foreign_key_check`；写入 `schema_migrations` 记录；`MigrationTestHelper` 覆盖 v1→v5 全链 | L | v1→v5 全链迁移测试通过；`foreign_key_check` 空；删除 source 后 Phase2 表无孤儿；新建 v5 库与迁移库 `PRAGMA table_info` 一致 |
| B1-3 | data P2-3 | `schema_migrations` 有表无写入 | 并入 B1-2 迁移：`INSERT OR IGNORE` 记录 v1..v5（或删表并文档说明） | S | 迁移后表记录 5 行（或表已删且文档更新） |
| B1-4 | data P2-4 | `embedding_meta.active` 无单一 active 语义，`activeSpace()` 死查询 | **不删列**（minSdk 24 无 DROP COLUMN，且 `KnowledgeBackup.kt:304` 等在用）：在 `registerSpace` 同事务维护"仅一个 active"不变式；删除/修复 `activeSpace()` 死查询并加单测 | S | 任意时刻最多 1 行 active；`activeSpace()` 有调用方或已删 |
| B1-5 | data P2-5 | 状态列 String、无枚举/校验，状态集在注释/DAO/UI 重复 | **Kotlin 侧单源**：`enum class DocStatus/JobStage/SourceStatus` + 常量映射；DAO/UI 全部引用枚举；不加 DB CHECK（见约束 2） | M | main 源码 grep 状态字面量仅出现在 enum/迁移/兼容层；非法值读写单测 |
| B1-6 | data P2-6 | 同一文件重复导入无去重（checksum 算了不用） | `importFile` 前按 checksum+status 查重：命中跳过/提示"已存在"；按需加索引 | S | 重复导入同一文件不新增 document/向量；单测覆盖 |
| B1-7 | data P2-7 | 迁移 DDL 有 DEFAULT 但实体无 `@ColumnInfo(defaultValue)` | 实体补 `@ColumnInfo(defaultValue=...)`，并在 v5 迁移中同步（并入 B1-2） | S | 新建 v5 库与迁移库 `dflt_value` 一致（自动化断言） |
| B1-8 | data P2-8 [partial] | 迁移测试只断言 2 表；缺 v1 备份 fixture（备份回环 instrumentation 已有） | 迁移测试补 chunk/chunk_fts/chunk_embedding 行断言；新增 v1 备份 fixture 导入测试（`androidTest`）；真实 v4 快照迁移测试 | M | 迁移全表断言 + v1 兼容 + 真机 v4→v5 通过 |
| B1-9 | data P2-10 | 导出未过滤文档状态；**子表（chunk/FTS/向量）也需同步过滤**，否则恢复出无父 chunk，B1-2 加 FK 后导入会失败 | 导出仅含 `status='ready'` document **及其 chunk/FTS/向量**（引用闭包一致）；sources 口径一致 | S | 备份包内子表仅含 ready 文档引用；恢复后 FK 校验通过、无死行 |
| B1-10 | core P2-6 | `document.status` 与 `ingest_job.stage` 双状态机无一致性约束（`Entities.kt:38,142`） | 选 `ingest_job` 为唯一事实源，`document.status` 由同一 `@Transaction` 派生；抽组合更新函数；非法组合矩阵测试 | M | 状态迁移矩阵单测；所有 document 状态更新走统一入口 |
| B1-11 | device D4 | `meta.modelRevision` 只在写入路径回填；只读搜索/启动不触发回填 | v5 迁移内按 spaceId 回填 catalog sha（未收录留空）；或启动后惰性回填（app_kv 标记完成） | S | 升级后未导入时 meta.revision 已回填；单测覆盖 |
| B1-12 | 完整性审查 / 风险 5 | 旧 id 采纳仅真机验证 440M GPU；路径 C/740M/NPU 未覆盖 | 构造旧格式 meta 组合做 JVM/迁移测试矩阵；真机补路径 C `.task` 冒烟 | S | 4 类空间旧 id 采纳测试通过 |

### 批次 B2：检索质量与健壮性（9 项，含 2 项 partial 收尾）✅ 已完成（2026-10-17，见 `B2_FIX_REPORT.md`）

> 依赖 B1（B1-1 FTS 变更会影响本批）。工作量约 **3–4.5 人日**；检索语义类改动必须带 220 条评测回归。

| ID | 来源 | 问题（证据） | 方案 | 工作量 | 验收 |
|---|---|---|---|---|---|
| B2-1 | core P2-1 | 关键词通道按 rowid 截断候选（topK×12），高频 bigram 长尾召回被截断（`Daos.kt:searchFtsOrdered`、`SearchService.kt:190-200`） | **实测 FTS4 matchinfo 不可在 SQL 内直接排序**（BLOB 且无自定义函数）→ 方案改为：全命中 chunkId 取回（安全上限如 5k），Kotlin 侧按 bigram 覆盖分排序后取候选；候选上限与库规模关联；10k 库 p95 门槛见 B5-3 | M | 长尾查询召回提升（固定 fixture）；10k 合成库关键词通道 p95 有基线；R@1/R@5 不降 |
| B2-2 | core P2-3 | FTS 覆盖数统计与入库分词口径不一致：入库去空白后 bigram，重排用原始 content.contains（`SearchService.kt:194-199` vs `BigramTokenizer.kt:10-15`） | 重排用同一规范化串（预计算 normalized content）或直接用 `BigramTokenizer.bigrams(content)` 求交 | S | en/mix 16 条查询 R@5 不低于改前基线（真机 eval 导出对比）；新增单测 |
| B2-3 | core P2-8 | 检索 query 未做与入库一致的 NFC/CRLF 规范化（`SearchService.kt:61`、`TextNormalizer`） | `TextNormalizer.normalize(query)` 两侧共用 | S | NFC/NFD 输入命中一致；单测 |
| B2-4 | core P2-7 | Chunker 硬切/二元组可能劈开代理对（emoji/生僻字）（`Chunker.kt:53-60,120-125`、`BigramTokenizer.kt:10-15`） | 硬切边界回退到 code point；bigram 用 code point 迭代 | S | emoji/代理对单测；chunk 不含孤立 surrogate |
| B2-5 | core P2-10 | `chunkerFor(pathB)` 与默认等价（均 600），死分支且注释矛盾（"保守 300 字" vs 600） | 删分支或收敛单常量；路径 A/B 块长统一说明 | S | 代码无死分支；注释与实现一致 |
| B2-6 | core P2-9 | 分块无重叠，跨块答案被截断（设计选择未显式记录） | 先补 ADR/实现文档记录风险；Phase 2 评估 50 字重叠（**ordinal 唯一约束不受影响**；需接受 chunk 区间重叠并重跑评测） | S（文档）/ M（实现） | 文档落地；若实现须重跑评测确认 R@1/R@5 不降 |
| B2-7 | core P2-13 [partial] | 异常/错误分类不足，UI 仅展示 message（`readableMessage` 已有） | `sealed class PipelineError`（模型缺失/损坏/空间不匹配/取消/IO）；`job.error` 改结构化编码（如 `{code, detail}` JSON 或 `CODE:detail` 前缀，保留人可读 detail）；UI 按 code 给"重试/换模型/重导入" | M | 错误 code 单测；旧数据（纯文本 error）向后兼容解析；UI 按钮按 code 出现 |
| B2-8 | core P2-12 [partial] | `chunk_embedding` 无 FK，重嵌入与导入并发仍可能产生孤儿向量（B1-2 已加 FK，本项清理存量孤儿） | 启动/健康检查清理孤儿向量（`ftsHealthCheck` 扩展为三表计数+孤儿检查） | S | 孤儿向量可检出并清理 |
| B2-10 | FIX_REPORT 风险 9 | 单空间矩阵缓存常驻（10k×768≈30MB；100k≈300MB），无上限/分片策略 | 设置最大缓存字节上限（如 64MB）+ LRU 淘汰；超限走分片/流式扫描门限 | M | 100k 合成库下缓存占用 ≤ 上限 ×(1+30%) 且不 OOM；缓存上限/淘汰单测 |

### 批次 B3：UI/UX 与真机新发现（12 项，含 3 项 partial 收尾）✅ 已完成（2026-10-17，见 `B3_FIX_REPORT.md`）

> 依赖 B2 的检索行为稳定（B2-7 错误类型影响 UI）。工作量约 **3.5–5 人日**。含真机验收新发现 D1/D2/D5。

| ID | 来源 | 问题（证据） | 方案 | 工作量 | 验收 |
|---|---|---|---|---|---|
| B3-1 | device D1 | **主线程 StrictMode 磁盘读**：`ImportViewModel.<init>` 调 `getFilesDir()`（真机 37ms DiskReadViolation，`ImportViewModel.kt:37`） | staging 路径不在 VM 构造期获取：改为惰性 `by lazy` 且首次使用在 IO；或改用 `File(context.filesDir, ...)` 在 `Dispatchers.IO` 初始化 | S | 真机 StrictMode 0 违规（打开导入屏复测） |
| B3-2 | device D2 | 冷启动首次检索 7.0s（模型加载），仅进度条无阶段提示 | 首查加载文案（"正在加载端侧模型…"）+ 预估耗时；评估引擎预热（内存代价，默认关） | S/M | 首查有阶段反馈；warm 40ms 不变 |
| B3-3 | ui P2-8 | 深色主题只覆盖 6 个 color role（`ui/theme/Color.kt`），XML 固定 Light（`themes.xml`） | 补全 dark color scheme（surface/container/outline 等），XML `values-night` | M | 5 产品屏深色截图；正文对比度 WCAG AA ≥4.5:1（用对比度工具核验） |
| B3-4 | ui P2-9 [partial] | allowBackup 已修；仍无 adaptive icon（仅 vector drawable） | 增加 `mipmap-anydpi-v26` 自适应图标（前景/背景） | S | `aapt2 badging` 显示 adaptive icon |
| B3-5 | ui P2-7 [partial] | 空态/加载/错误反馈不均衡（各屏已加错误提示，缺统一组件） | 统一 `LoadingState/EmptyState/ErrorState` 组件；导入/来源/设置/检索接入 | M | 四屏状态矩阵手工验收（真机） |
| B3-6 | ui P2-3/5/6 + 审查 §4-18 [partial] | BenchmarkScreen（debug，约 780 行）1 个 Composable 22 状态、卡片同构（10 处 `Card(`）、Column 套 LazyColumn 小屏可能 0 高、JSON 解析未保护；Settings/Sources 仍有多段同构 Card | 拆 `ExperimentCard/SectionCard` 并复用到 Settings；修复滚动嵌套、JSON `runCatching`；随 debug 源集不影响 release | M | 小屏（<=640dp）真机/预览可滚动；坏 JSON 不崩溃；重复卡片模板行数下降 ≥50%（以 `Card(` 段计） |
| B3-7 | ui P2-11 | Preview 仅 1 处、无 Compose UI 测试 | 关键屏加 `@Preview`（浅/深/空/错误态）；评估 Compose UI test（见 B5-2） | S/M | 预览覆盖 5 产品屏；CI 无新增失败 |
| B3-8 | ui P2-13 | 文案字符串控制流程 + locale 敏感格式化（`String.format` 在 SearchScreen 已用 ROOT；其余检查） | 流程控制改枚举/状态；数字/日期统一 `Locale.ROOT`/`getDefault` 显式化 | S | grep `String.format(` 全部显式 Locale；无文案 switch |
| B3-9 | ui P2-10 [partial] | 死状态/验收产物残留（import_status.json 已 debug-only；import 列表 101 卡片无分页） | 清理死状态；导入任务列表分页/只显示活动+最近 50 条 | S | 101 任务时列表首帧 <500ms（真机 measure）；只渲染 ≤50 行 |
| B3-10 | device D5 | 两套评测输入约定：`SearchEvalRunner` 读 `files/search/queries.json`，脚本/设备有 `files/eval/evalset.json` | 统一为 `files/search/queries.json`（或 runner 兼容两路径），脚本/文档同步 | S | 两种输入均可跑；文档只写一种 |
| B3-11 | ui P2-14 + Standards S4 | autostart 仍依赖 `delay(300)` 串联两个 `LaunchedEffect`，脆弱；18 个 `autoStart*` Boolean 贯穿 MainActivity/双 BenchmarkScreen，新增 runner 需改 4 处 | 单一 `AutoStart` 数据类/路由表（保留 20 个 EXTRA 常量名兼容 scripts）；去除 delay 串联，改为状态驱动/事件一次性触发 | M/L（约 1.5 人日） | 新增 runner 只改 1 处注册表；冷启动/重建不重放、不依赖 sleep；autostart smoke 通过 |
| ~~B3-14~~ | B2 审查遗留（B2 已部分完成） | **✅ B3 已完成**：Settings 展示 indexHealthCheck 三表/孤儿/reasons/healthy + repairIndex before/after | — | — | 真机可看到明细；修复后前后计数一致 |
| ~~B3-15~~ | B2 审查遗留 | **✅ B2 审查后已完成**：MainActivity 传 `onOpenSettings`，ImportScreen 对 MODEL_* 显示「去设置」按钮 | — | — | 模型缺失/损坏时可一键进入设置页 |
| B3-13 | B1 审查遗留 | 重复导入（`ImportResult.Duplicate`）已有数据层语义，但 `importStaged` 仍只返回 `List<String>`，UI 无法提示"已存在 N 个"（B1-6 已知限制） | `importStaged` 返回带计数的结果类型（内部 API），ImportViewModel 展示"新导入 X / 已存在 Y" | S | 重复导入 UI 有可读提示；`importStaged` 调用方同步 |
| B3-12 | ui P2-4 | composition 内 IO/副作用：`BenchmarkScreen.kt:84` 的 `remember { discoverModels(context) }` 在组合期 `listFiles`（debug 屏）；Settings/Import 侧已顺带修但未系统性排查 | 全局排查 `remember { ... }` 内的 IO/DB 调用，改 `LaunchedEffect + Dispatchers.IO` 或 VM 预计算；Benchmark 随 debug | S | grep 组合期 `listFiles/readText/File(` 清零（debug 源集含）；真机 StrictMode 0 违规 |

### 批次 B4：工程化/发布/依赖（11 项）✅ 实现完成（2026-10-17，见 `B4_FIX_REPORT.md`；B4-8 CI 首绿为外部阻塞）

> 依赖 B1–B3 稳定后集中升级，避免边改边升。工作量约 **2.5–3 人日**（不含需用户决策项）。

| ID | 来源 | 问题（证据） | 方案 | 工作量 | 验收 |
|---|---|---|---|---|---|
| B4-1a | infra P1-7 遗留 | 全局 `resolutionStrategy.force` litertlm 0.18.0（`app/build.gradle.kts:126-130`） | 改 `constraints + because`（仅对 runtime classpath），保留注释证据；`dependencyInsight` 验证 | S | `dependencyInsight` 仅 0.18.0；`assembleRelease` 绿 |
| B4-1b | infra P1-7 遗留 | Compose BOM 2025.01 落后约 21 个月；两个 MediaPipe 制品版本独立（1.1.1 vs 0.10.35） | 季度升级窗口：先对齐 MediaPipe（统一到可用版本），再升 Compose BOM/AGP 小版本；每步跑真机 smoke | M | 升级后编译/单测/lint/真机检索均通过 |
| B4-1c | infra P1-7 遗留 | 无依赖锁定/校验（`verification-metadata.xml` 不存在） | 生成并提交 `gradle/verification-metadata.xml`；CI 校验 | S | CI 依赖校验通过；文档说明更新流程 |
| B4-2 | infra P2-2 | Gradle wrapper 来自 huaweicloud 镜像且无 `distributionSha256Sum` | 追加官方 SHA；文档记录切换方法 | S | wrapper 校验通过 |
| B4-3 | infra P2-3 [partial] | ModelCatalog 维护性：`isVerifiedForNpu` 已删；维度/名单口径分散（`EXPECTED_DIM=768` vs catalog dims 1024）、`lowercase()` 受 locale 影响 | 维度单一来源（catalog）；`lowercase(Locale.ROOT)`；名单收敛到 ModelRouter | M | 单测覆盖 LFM 1024d；无 `EXPECTED_DIM` 散落 |
| B4-4 | infra P2-8 | 交付产物不可追溯（无 build-info/git sha/构建元数据） | CI 注入 `buildInfo`（git sha/构建时间）到 assets 或 BuildConfig；dist 文件名带 tag | S | APK 内可读到 build-info |
| B4-5 | infra P2-7 [partial]（与 B6-3 去重） | 仓库卫生：缺 LICENSE/NOTICE（vendored MIT）、`.zcode/plans` 被跟踪；语料/结果策略见 B6-3 | 补 LICENSE + `docs/reference/README` MIT 声明；移除 `.zcode/plans` 跟踪；已跟踪结果文件的去留交用户决策 | S | 仓库根有 LICENSE；`git ls-files` 无计划文件 |
| B4-6 | device D3 | 发布链路：签名材料未 provision；OEM（OnePlus）全新安装弹"安装增强防护"引导，自动化安装需手工点 3 步 | 提供 `scripts/release.sh`（签名+verify+dist 命名）与 `docs/RELEASE.md`（密钥 provision、OEM 引导步骤、验收清单） | S | 按文档可产出 signed release；安装步骤脚本化/文档化 |
| B4-7 | device D6 | release 非 debuggable，验收脚本取证口径未文档化 | `DEVICE_ACCEPTANCE.md` 增补 debug/release 各自取证方式（run-as vs UI/logcat） | S | 文档更新 |
| B4-8 | infra P1-6 遗留 | CI 未在 GitHub 实跑；wrapper 镜像可达性未知 | 首次 push 验证 CI；必要时 wrapper 切官方源 + 缓存策略调整 | S | CI 首次绿灯记录（**外部阻塞：本机无 git remote，待用户提供远端；本地等价门禁已绿**） |
| B4-9 | Standards S3 | 流式 SHA-256 仍有 3 份实现（`ModelIntegrity` / `ModelFingerprint` / `IngestRepository`） | 抽 `core/model/Sha256.kt`（流式/文件/增量 digest 三种入口），三处复用；单测锁定结果一致 | S | 三处调用统一实现；`ModelIntegrityTest`/`ModelFingerprintTest` 保持绿 |

### 批次 B5：测试补强（3 项）

| ID | 来源 | 问题 | 方案 | 工作量 | 验收 |
|---|---|---|---|---|---|
| B5-1 | core P2-14 / infra P1-6 遗留 | 关键集成路径缺 JVM 级测试（Worker/DAO/备份需真机） | 引入 Robolectric（in-memory Room）覆盖导入状态机/取消/备份回环；若评估后不引入，须在计划附录写明原因与替代门禁并由 Lead 决策 | M | 产出至少 1 个 Robolectric 测试类（导入状态机/取消路径） |
| B5-2 | ui P2-11 | 无 Compose UI 测试 | 评估 `androidx.compose.ui:ui-test-junit4`（instrumentation）覆盖 5 屏关键路径（搜索→证据→返回、旋转） | M | 2–3 条 Compose UI 测试真机通过 |
| B5-3 | core P2-14 / 性能 | 10k/100k chunk 合成库性能回归缺失（真机现有 83 chunk 无代表性） | 合成库基准（JVM 纯逻辑 + 真机 instrumentation 可选），记录检索 p95/内存峰值作为回归基线 | M | 10k 档 p95 与内存有基线数据；超阈值 CI/人工预警 |

### 批次 B6：文档/脚本一致性（4 项）

| ID | 来源 | 问题 | 方案 | 工作量 | 验收 |
|---|---|---|---|---|---|
| B6-1 | infra P1-8 | 文档与实现 7 处漂移（README 权限/Gradle 版本/Phase 状态、UPSTREAM AGP/270M 体积、评测集规模） | 逐条同步；关键数字改为脚本可校验（CI grep 对照） | S | 7 处全部一致；CI 校验脚本 |
| B6-2 | infra P1-9 | 11 个脚本重复 ADB 头/样板、固定 `/tmp/evaldb`、`sleep 150`、score_eval 死代码 | 抽 `scripts/lib/common.sh`（选 adb/install/poll/pull）；`mktemp -d`；sleep 改轮询；argparse 化 | M | 脚本行数下降；无固定临时路径；`shellcheck` 通过 |
| B6-3 | infra P2-7 | 评测语料/结果入库策略、`.review`/`.fix-baseline` 清理 | 明确策略并落 `.gitignore`/README；清理临时目录（已 ignore） | S | 策略文档 + grep 校验 |
| B6-4 | FIX_REPORT 风险 5/6 + D4 + B1 审查 | 缺少面向用户/后续维护的发布说明：非 legacy 空间 ID 加 `@revision` 的迁移影响、备份格式 v2（旧版 App 不可读）、失败自动重试 3 次、原文不随备份迁移、同名换文件检测时点；**B1 补充**：导出改为 ready-only（旧 App 导入新包会丢弃非 ready 文档；新 App 导入旧包会把 pending/processing 归一为 failed）、恢复时多 active 归一；**B2 补充**：emoji/代理对分词修复后建议重建一次 FTS（`indexHealthCheck/repairIndex`）、检索 query 会先 NFC 规范化、错误码格式 `CODE:detail` | 新增 `docs/RELEASE_NOTES.md` + README 迁移章节；升级/回滚步骤写清 | S | 发布说明覆盖 7 项行为变更；文档评审通过 |

---

## 4. 批次依赖与排期建议

```
B1 (schema v5：孤儿清理→父表→子表 FK→校验)  ──► B2 (检索质量) ──► B3 (UI/UX + 真机发现)
                                                │
                                                └──► B5 (测试补强，可与 B3 并行)
B4 (工程化/发布/依赖，B4-1b 升级需 B1 schema 冻结后)
B6 (文档/脚本) 可并行；B6-1 文档漂移同步放到 B1–B4 行为稳定后（Iter 4 收尾）
```

工作量合计：**约 19–25.5 人日**（B1 4.5–6d；B2 3–4d；B3 4.5–6d；B4 3.5–4.5d；B5 2–3d；B6 1.5–2d）。

| 迭代 | 内容 | 预估 | 门禁 |
|---|---|---|---|
| **Iter 1** | B1（v5 迁移）+ B6-2/B6-3（脚本/策略） | 5.5–7.5 人日 | 迁移全链/`foreign_key_check`/真机 v4→v5 + 备份子表一致性 |
| **Iter 2** | B2（检索质量，逐项对比评测） | 3–4 人日 | R@1/R@5 + 长尾 fixture + 10k 关键词 p95 基线；R@10 已饱和仅作守卫 |
| **Iter 3** | B3（UI/UX + D1/D2/D5）+ B5（并行） | 6–8.5 人日 | 真机 StrictMode 0 + 深色 WCAG AA + 小屏滚动 + 1 个 Robolectric/2–3 条 Compose UI 测试 |
| **Iter 4** | B4（工程化/发布/依赖）+ B6-1/B6-4（发布说明） | 5–6.5 人日 | `assembleRelease` + 签名 verify + CI 首绿 + 真机 release smoke + 文档一致性 |

**统一验收门禁**：`compileDebugKotlin/compileReleaseKotlin/compileDebugUnitTestKotlin/compileDebugAndroidTestKotlin`、`testDebugUnitTest --rerun`、`lintDebug`、`assembleDebug`、`assembleRelease`、`connectedDebugAndroidTest`（真机）、以及第 5 节真机 smoke 清单。

---

## 5. 真机验收 smoke 清单（每批结束复跑）

1. 旧库升级：安装新包 → 打开检索 → 旧数据可检索（R@1/R@5 不降、0 refused）；v5 后执行 `PRAGMA foreign_key_check`（空）与 `integrity_check`（ok）；
2. 导入 20 篇：101/101 ready、向量全覆盖、attempt=0、StrictMode 0；
3. UI：检索→证据→返回结果保留、旋转保留、深色模式、返回键回首页；
4. release：无实验入口、导入 5/5、检索 Top5、0 FATAL/UnsatisfiedLink；
5. 备份：导出→清库→导入→再导出（instrumentation 自动覆盖）+ UI 入口手工一次；**校验包内不含非 ready 文档的子表行**（B1-9）；
6. 迁移失败回滚：用 `MigrationTestHelper` 注入失败场景验证事务回滚、原 v4 库不变（B1-2 验收项）。

---

## 6. 明确不做 / 需产品决策（不默认排期）

| 项 | 原因 | 建议 |
|---|---|---|
| FTS4 → FTS5 迁移 | 迁移成本/风险高，当前检索质量已达标（R@10=1.0 真机） | 仅在需要 bm25/长尾优化时评估（B2-1 可先用 matchinfo 解决） |
| 分块重叠（B2-6 实现部分） | 改变检索语义，需重跑评测与重新导入全部向量 | 先记风险，Phase 2 决策 |
| 引入 Hilt/Navigation 库 | 当前 4 个 ViewModel + 手写导航已够用，Hilt 增加编译期成本 | 页面 >8 个或团队 >2 人时再评估 |
| 已跟踪 `docs/phase1/lenbench-results` 移出版本库 | 涉及他人已有数据，需用户确认 | 由用户决定（B4-5 已标注） |
| 依赖大版本升级（AGP 9/Kotlin 2.5/Compose 新 BOM） | 需整轮真机回归，收益/风险比低 | 放在发布后独立迭代 |
| 下载完成后再全文件哈希一次（统一 ModelIntegrity，~1–2s/400MB） | 统一校验的代价；换流的复杂度不值 | 接受（FIX_REPORT 风险 15） |
| GPU 超时引擎"尽力关闭" | JNI 永久挂死无法真正中断；auto 白名单已排除高风险组合 | 接受（FIX_REPORT 风险 7） |
| 生产"迁移前自动快照" | Room 打开即迁移，应用层无可靠 pre-migrate 钩子；v5 用事务回滚 + 真实 v4 快照测试替代 | 接受（风险表第 1 条对策） |
| FTS5 bm25 排序 | FTS4 已满足真机 R@10=1.0；换 FTS5 迁移成本/风险高于收益 | B2-1 先用 Kotlin 覆盖分；换 FTS5 仅评估 |

---

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| schema v5 迁移（FK 需重建表）破坏既有数据 | **迁移事务内完成，失败整体回滚**；迁移测试覆盖 v1→v5 全链 + 真实 v4 DB 快照 + 真机旧库升级 smoke；迁移前后 `foreign_key_check`；生产无法 pre-migrate 快照（见 §6），因此用"事务回滚 + 迁移矩阵"替代；v5 前建议用户手动导出备份（发布说明提示） |
| B1-5 状态枚举 + CHECK 需大范围改 DAO/UI | 分两步：先 Kotlin 侧单源常量，再 v5 加 CHECK；每步编译/测试门禁 |
| 检索语义改动（B2-1/B2-2）导致质量回退 | 每次改动跑 220 条评测对比 R@1/5/10 与 p95；回退即回滚 |
| 依赖升级（B4-1）引入 JNI/R8 问题 | 独立迭代 + release 真机 smoke（已有验收清单） |
| 无 arm64 设备时 CI 无法跑 instrumentation | CI 只编译；真机验收作为发布前手工门禁（已有 DEVICE_ACCEPTANCE 模板） |
| P2 范围蔓延 | 按批次 DoD 收口；B1–B6 之外新增需求进 P3 backlog，不混入本轮 |

---

## 8. 交付物

- 本计划：`docs/review/P2_FIX_PLAN.md`
- 执行时新增：`docs/review/P2_FIX_REPORT.md`（逐项完成情况 + 门禁证据 + 真机复跑记录）、schema `5.json` 与迁移测试、真实设备复验记录
- 代码变更按批次提交（建议 6 个 PR：B1/B2/B3/B4/B5/B6），每个 PR 附门禁输出与评测对比

---

## 9. 计划审查记录（双轴独立审查 + 处置）

> 审查方式：两名独立审查员并行只读审查（`可行性/依赖/风险` 轴、`完整性/可追溯性` 轴），不互相污染上下文；审查对象为本计划初稿 + 源报告 + 当前代码。结论：**修订后执行**。

### 9.1 完整性/可追溯性轴

| 审查意见 | 处置 |
|---|---|
| 缺漏 ui P2-4（composition 内 IO：`BenchmarkScreen.kt:84 remember { discoverModels }`） | 新增 **B3-12**（组合期 IO 排查，含 debug 源集） |
| 缺漏 P0/P1 风险 5（非 legacy 空间迁移仅 GPU 一条验证路径） | 新增 **B1-12**（4 类空间旧 id 采纳测试矩阵）+ B6-4 发布说明 |
| "已闭环"声明抽查 13 条全部与代码一致 | 无需修订（保留 §2 证据） |
| 重复排期：infra P2-7（B4-5/B6-3）、data P2-8（B1-8/B5-3）、core P2-14（B2-9 占位） | B4-5 与 B6-3 去重并互相引用；B5-3 改为"大库性能回归"；删除 B2-9 占位（统一到 B5-1/B5-3） |
| 统计错误：§1 已闭环 15/partial 8/批次合计 44≠48 | §1 修正为：已闭环 17、排期 51（B1 12+B2 9+B3 12+B4 11+B5 3+B6 4）、不排期 7 |
| 验收标准不可测（B2-2/B2-10/B3-3/B3-6/B3-9/B5-1 等） | 逐项量化：en/mix R@5 基线、缓存上限+30% 阈值、WCAG AA ≥4.5:1、重复模板行数降 ≥50%、列表首帧 <500ms、Robolectric 测试类产出；补 file:line 证据 |
| 工期不自洽（13–17 人日 vs Iter 9–12） | §4 重算为 19–25.5 人日，拆为 4 个 Iter |

### 9.2 可行性/依赖/风险轴

| 审查意见 | 处置 |
|---|---|
| **阻塞 1**：v5 子步骤未排序；FK 开启时重建父表会被子表阻塞；孤儿行会导致建 FK 失败 | B1-2 重写为**单一 v5 迁移**并写明父→子顺序、孤儿清理前置、迁移前后 `foreign_key_check`、失败回滚；B2-8 孤儿清理并入/前置 |
| **阻塞 2**：Room 无 Check 注解，CHECK 约束会造成 fresh/迁移库不一致 | B1-5 改为 Kotlin enum/DAO 校验（**不加 DB CHECK**）；B1-7 只做 `@ColumnInfo(defaultValue)` 对齐 |
| **阻塞 3**：B1-9 只过滤 document，子表仍全量导出 → 加 FK 后导入失败 | B1-9 改为"document + chunk/FTS/向量引用闭包同步过滤" |
| B1-4 删 `active` 断编译且 minSdk 24 无 DROP COLUMN | 改为**保留列** + `registerSpace` 单 active 不变式 + 修复死查询 |
| B1-1 未迁移存量 FTS rowid | 改为**不对齐 rowid**（FTS 可从 content 重建），修正注释 + 写入去重；FTS5 仅评估 |
| B2-1 matchinfo 无法在 SQL 内排序 | 改为"全命中（安全上限 5k）→ Kotlin 覆盖分排序"；补 10k 关键词 p95 基线 |
| "迁移前快照"不可执行（Room 打开即迁移） | §7 风险对策改为：迁移事务回滚 + 真实 v4 DB 快照迁移测试 + 发布说明提示用户先导出备份；§6 明示接受 |
| R@10 已饱和（=1.0），"不降"失效 | 门禁改为 R@1/R@5 + 长尾 fixture + p95；R@10 仅作守卫 |
| B4-1 三项打包为一个 M，估时偏低 | 拆为 **B4-1a（constraints）/B4-1b（BOM+MediaPipe 升级）/B4-1c（verification-metadata）** |
| B2-6"需改 ordinal 唯一约束"表述有误 | 修正为"ordinal 唯一约束不受影响；需接受 chunk 区间重叠并重跑评测" |
| B2-7 job.error 编码未定 | 明确结构化编码（`{code, detail}` 或 `CODE:detail`）并保留旧文本兼容 |
| B3-11 估时偏低 | 提升为 M/L（约 1.5 人日） |
| B4-8/B4-6 依赖外部条件（GitHub 仓库、签名密钥） | 标注"外部依赖"，不阻塞其他批次；未具备条件时以文档/脚本交付 |

### 9.3 审查后仍未闭环（已显式接受，不隐藏）

1. **生产迁移前快照**：Room 打开即迁移，应用层无可靠 pre-migrate 钩子；以"迁移事务回滚 + 真实 v4 快照测试 + 发布说明提示手动备份"替代（§6/§7）。
2. **FTS5 / 分块重叠**：保持"仅评估/文档记录"（§6）。
3. **B4-6/B4-8 外部依赖**：签名密钥与 GitHub 仓库就绪前无法完成实跑，作为发布前置条件跟踪。

---

*计划审查完成日期：2026-10-17 · 审查基线：本计划 202 行版；修订后 211 行版 · 结论：修订后执行。*

---

## 10. B1 执行结果（2026-10-17）

| 项 | 结果 |
|---|---|
| B1-1…B1-12 | **12/12 完成**（2 项计划偏差已文档化：FK 实为 8 条、FTS rowid 不对齐改为注释+去重；无 DB CHECK） |
| schema | v5 已上线：8 条 FK（CASCADE）、列默认值、`schema_migrations` 1..5、`5.json` 入库 |
| JVM 测试 | **153 tests / 0 failed**（B1 前 106 → +47） |
| Android Lint | **0 errors / 21 warnings**（`abortOnError=true`） |
| 构建 | `assembleDebug` / `assembleRelease`（R8）均绿 |
| 真机 instrumentation | **20 tests / 0 failed**（含 v4→v5、v1→v5、孤儿清理、空间采纳、备份 v1 fixture、重复导入去重） |
| 真机旧库升级 | v3→v5 链式迁移通过：83 chunk 无损、`foreign_key_check` 空、revision 回填、单 active |
| 迁移后检索回归 | R@1=0.964 / R@5=0.991 / R@10=1.000，p50=49ms，0 refused（与 B1 前一致） |
| 独立审查 | Standards 3 硬性 + 5 判断性；Spec 4 partial + 1 wrong → 全部处置（见 `B1_FIX_REPORT.md` §6） |

**B1 关闭后剩余批次**：B2（检索质量）、B3（UI/UX + 真机发现）、B4（工程化/依赖升级，用户已允许）、B5（测试补强，用户已批准引入 Robolectric）、B6（文档/脚本/发布说明）。

---

## 11. B2 执行结果（2026-10-17）

| 项 | 结果 |
|---|---|
| B2-1…B2-10（9 项） | **9/9 完成** |
| JVM 测试 | **197 tests / 0 failed**（B2 前 153 → +44） |
| Android Lint | **0 errors / 23 warnings** |
| 构建 | `assembleDebug` / `assembleRelease`（R8）均绿 |
| 真机 instrumentation | **25 tests / 0 failed**（含 10k FTS 性能、long-tail 回归、索引健康/孤儿清理） |
| 220 条检索评测 | 无回退：R@1=0.9636 / R@5=0.9909 / R@10=1.000，p50=49ms、p95=62ms、0 refused |
| long-tail 20 条 | R@1/R@5/R@10 0.95→**1.00**；关键词候选均值 0.85→**1.0** |
| 10k FTS 基线 | in-memory 10k chunk：p50=5ms / p95=7ms（全命中截断 5000 同样水平）；Kotlin 重排最坏 5000×600 字 p50=81ms |
| mix-16 子集 | R@5 1.0→1.0（`docs/review/b2/mix16-metrics.json`） |
| 大空间安全阀 | 预估矩阵 >64MB 自动流式分页扫描（门限单测覆盖 10k/25k/100k） |
| 独立审查 | Standards + Spec 双轴 → 处置见 `B2_FIX_REPORT.md` §6 |

**B2 关闭后剩余批次**：B3（UI/UX + 真机发现 + B3-13/14/15）、B4（工程化/发布/依赖升级，用户已允许）、B5（测试补强，Robolectric 已批准）、B6（文档/发布说明）。

---

## 12. B3 执行结果（2026-10-17）

| 项 | 结果 |
|---|---|
| B3-1…B3-14（B3-15 已在 B2） | **14/14 完成**（B3-6/B3-11 部分验收按报告 §4 记录偏差） |
| JVM 测试 | **221 tests / 0 failed**（debug；release unit 同步绿） |
| Android Lint | **0 errors / 26 warnings** |
| 构建 | `assembleDebug` / `assembleRelease`（R8）均绿 |
| 真机 instrumentation | **28 tests / 0 failed** |
| StrictMode（导入屏） | 应用栈归属 **0**（4 条为 OnePlus UIFirst 系统 binder 注入） |
| 深色主题 | 5 屏截图（`docs/review/b3/after/b3_dark_*2.png`）；卡片 #30362E 品牌深绿；对比度 ≥5.76:1 |
| autostart | import / search_eval（20s 出结果）/ delete_check（5s 出结果）smoke 通过；20 EXTRA 常量保留 |
| 自适应图标 | aapt2 badging 显示 mipmap-anydpi-v26/ic_launcher.xml（adaptive） |
| 工程指标 | autostart 参数 19→2、LaunchedEffect 17→1、Benchmark 模板 82→29 行、任务列表 ≤50 行、组合期 IO 0、Preview 1→18 |
| 独立审查 | Standards 3 硬性 + 6 判断性 / Spec 7 partial → 全部处置或诚实记录（B3_FIX_REPORT §6） |

**B3 关闭后剩余批次**：B4（工程化/发布/依赖，用户已允许依赖升级窗口）、B5（测试补强，Robolectric 已批准）、B6（文档/发布说明/脚本一致性）。B3-6/B3-11 的辅助项（Settings 卡片模板化、单注册表 DSL）保留为后续可选优化。

---

## 14. B4 执行结果（2026-10-17）

| 项 | 结果 |
|---|---|
| B4-1a/1b/1c、B4-2…B4-7、B4-9 | **代码完成**（B4-1c/B4-2/B4-6 的外部验证项已尽量在本地执行：strict 门禁、sha 值核对、真实 unsigned 发布脚本） |
| B4-8 | **未完成（外部阻塞）**：无 git remote，无法产生 CI 首次绿灯；workflow 已加 wrapper-validation，本地等价门禁与 YAML 校验通过 |
| 依赖升级 | Compose BOM 2025.01.00→2026.06.01、core-ktx 1.18.0、lifecycle 2.10.0、activity-compose 1.13.0、tasks-text 1.1.1（最新目标需 compileSdk 37+AGP 9.1，取兼容上限；AGP/Kotlin/KSP 未升，原因已记录） |
| JVM 测试 | debug **235 tests / 0 failed**；release unit 215 tests 全绿 |
| strict 依赖校验 | **通过**（565 components + aapt2 linux 手工校验和；生成命令已补全全部配置） |
| Lint | 0 errors / 25 warnings |
| 构建 | assembleDebug/Release 绿；release dex 含 BUILD_GIT_SHA/BUILD_TIME（R8 keep 后验证） |
| 真机 | instrumentation 28/28；检索 smoke 0 refused/0 error、p50 51ms/p95 66ms |
| 发布链路 | `scripts/release.sh` 真实 unsigned 端到端：dist 命名 + sha256；签名路径待外部密钥（用户决策不 provision） |
| 许可证 | MIT LICENSE（用户确认）+ THIRD_PARTY_NOTICES（含 jieba Apache-2.0/serialization） |
| APK 体积 | debug 62.5→83.8MB、release 48.6→64.5MB（tasks-text 1.1.1 native so + serialization），已记录风险 |
| 独立审查 | Standards 4 硬性 + 6 判断性 / Spec 1 wrong + 4 partial → 全部修复或如实记录（B4_FIX_REPORT §6） |

**B4 关闭后剩余批次**：B5（测试补强：Robolectric/Compose UI test/10k 性能基线）、B6（文档/发布说明/脚本一致性）。外部待办：用户提供 git remote 后执行 CI 首跑；如需要 signed release，提供密钥后按 `docs/RELEASE.md` 执行。

---

## 15. B7 路线收敛执行结果（2026-10-17，用户决策）

> 用户确认：**仅保留 EGemma2 路线（3 种强度模型）**；采纳保守版 A1（删旧 EmbeddingGemma `.task`/Path C），
> 一并清理 Granite（及 LFM）；旧数据兼容按最小风险处理（不自动删除、可检出、可读拒绝）。

| 项 | 结果 |
|---|---|
| 下线路线 | 旧 EmbeddingGemma `.task`（Path C / tasks-text）、Granite（Path B 模型）、LFM2.5 |
| 保留路线 | EGemma2 440M/270M/740M + 2×SM8750 NPU；Path A（UE）与 Path B（litertlm 引擎） |
| APK | release **64,548,559 → 49,068,516 B（−23.98%）**，目标 ≈49MB 达成；debug ≈64.46MB |
| 测试 | JVM **243**（debug）/release 全绿；真机 instrumentation **35/35** |
| 检索回归 | 真机 220 评测 0 refused/0 error；p50=49ms、p95=64ms（与 B7 前一致） |
| 旧数据兼容 | `legacySpacesReport` + 设置页警告（不自动删除）；导出过滤旧空间向量；旧备份导入写库前可读拒绝且原库不变 |
| 文档/脚本 | README 模型路线、RELEASE 体积/验收/迁移、UPSTREAM 历史横幅；删除 run_g1b/run_g1c，probe/eval 脚本同步 |
| 独立审查 | Standards + Spec 双轴 → 处置见 `B7_FIX_REPORT.md` §6 |

**后续可选**：A2 再省 ~9MB（UE 未用 VectorStore/icing native，实验已验证检索正常，未启用）；旧模型文件清理入口；若重新支持 `.task` 需重新评估 +15.5MB。

---

## 16. B5 测试补强执行结果（2026-10-17）

| 项 | 结果 |
|---|---|
| B5-1 Robolectric | 引入 Robolectric 4.17；新增 3 个 JVM 用例（导入取消 2、备份回环 1），`testDebugUnitTest` 实际执行（非 skip） |
| B5-2 Compose UI test | 引入 `ui-test-junit4`（BOM 1.11.4）+ `ui-test-manifest`（debug）；新增 5 个 instrumentation 用例（搜索命中/拒绝关闭/证据返回/来源空态/重建），真机通过 |
| B5-3 性能基线 | 10k 向量扫描 p50/p95=4/4ms、矩阵 29MB、used heap 46–287MB；10k 关键词重排 p50/p95=416/436ms（多轮 401–436）；100k 流式门限（预估 292MB/100 页）；基线落盘 `docs/review/b5/10k-baseline.json`；真机 10k FTS 沿用 B2（p50 5/p95 7ms） |
| JVM 测试 | **249 tests / 0 failed**（含 Robolectric 3 + 合成基准 3） |
| 真机 instrumentation | **40 / 40 PASS**（原 35 + Compose 5） |
| Lint / 构建 | 0 errors / 25 warnings；assembleDebug/Release 绿；release APK 49,068,516 B（测试依赖不进 APK） |
| 依赖校验 | verification-metadata 重新生成（613 components），strict 通门禁 |
| 独立审查 | Standards + Spec 双轴 → 处置见 `B5_FIX_REPORT.md` §6 |

**说明**：Robolectric 首次运行需联网下载 android-all-instrumented；100k 仅验证流式门限（计划允许 JVM 纯逻辑 + 真机可选）。

---

## 17. B6 文档/脚本一致性执行结果（2026-10-17）

| 项 | 结果 |
|---|---|
| B6-1 文档漂移 | README 状态/Gradle 8.14.3/权限（INTERNET 白名单）、UPSTREAM AGP 8.13.2 + 270M=164,626,432B + 440M/740M 体积、评测规模按实际 **262 条/50 卡** 同步（MVP-1 历史 220/45 标注）；新增 `scripts/check_docs_consistency.sh`（14 项断言，评测规模从 evalset/corpus 派生） |
| B6-1 CI | `.github/workflows/ci.yml` 增加 docs 一致性 + shell 语法门禁（Checkout 后 fail-fast）；14/14 PASS |
| B6-2 脚本 | 新增 `scripts/lib/common.sh`；9 个 `run_*.sh` 634→522 行（−17.7%）；固定 `/tmp/evaldb`→`mktemp -d`；≥100s 等待→`poll_until`；`score_eval.py` 118→99 行（argparse/死代码，输出同构） |
| B6-2 校验 | `bash -n` 12/12；shellcheck 0.11.0 error=0/warning=0；无固定临时路径/长 sleep；py_compile OK |
| B6-3 策略 | `docs/review/REPOSITORY_POLICY.md`（语料入库/结果与临时目录不入库/只清理生成物）；`.gitignore` 补 6 类规则 |
| B6-4 发布说明 | `docs/RELEASE_NOTES.md`（97 行，7 项行为变更 + B1/B2/B7 + 升级/回滚）；README 新增升级迁移章节 |
| 产品改动 | 无（文档/脚本/CI/忽略规则；APK 不变） |
| 独立审查 | Standards + Spec 快速审查 → 处置见 `B6_FIX_REPORT.md` §6 |
