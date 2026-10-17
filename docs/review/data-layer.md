# 数据层审查

审查对象：Room schema v3 / DAO / 迁移 / 备份恢复（当前文件系统上的未提交代码，git diff 辅助理解意图）。
逐文件通读：`data/db/{AppDatabase,Entities,Daos}.kt`、`core/backup/KnowledgeBackup.kt`、`schemas/…/{1,2,3}.json`、`androidTest/.../MigrationTest.kt`，并用 grep 追踪了 DAO 的全部调用方（IngestRepository/IngestBatchWorker/ReembedWorker/SearchService/SettingsScreen/ImportScreen/SourcesScreen/多个 benchmark runner）。

## 总评

数据层“地基”（schema v1→v3、迁移、DAO 语义）质量高于 MVP 平均水平：迁移与 schema JSON 逐列一致、有真机迁移测试、无破坏性回退、FTS bigram 方案有实测依据、删除级联集中且幂等，**没有发现 schema/迁移本身的错误**。真正的风险集中在 W4+ 新加入的**备份/恢复**：导出→恢复这条链路存在 2 个 P0——导入是“先清库、后解析写库”的非原子流程（一次损坏包/中断就清空用户现有知识库），且导入完全不恢复 `embedding_meta`（恢复后再导出会把全部向量静默丢弃，备份链断裂），二者都直接对应数据丢失。另有 6 个 P1 集中在恢复的校验/互斥/兼容性判定与检索热路径的性能上限。健康度判断：**schema/DAO 可发布；备份功能建议按“实验性”处理，修复 2 个 P0 与 P1-1/P1-2/P1-3 后再对用户开放**（当前已在设置页以正式入口暴露，见 SettingsScreen.kt:99/216）。DAOs 没有任何 rawQuery 或字符串拼接，注入面干净；13 表 0 外键是明确的手工级联取舍，MVP 可接受。

## 量化数据

- 审查 Kotlin 代码 1066 行：AppDatabase 95 / Entities 161 / Daos 261 / KnowledgeBackup 350 / MigrationTest 199；schema JSON 3 份（v1 20.2KB、v2 20.6KB、v3 21.6KB）。
- v3 schema：**13 张表**（12 实体表 + `chunk_fts` FTS4 虚表）、**13 个索引**（其中 3 个唯一：`chunk(documentId,ordinal)`、`chunk_embedding(chunkId,spaceId,role)`、`entity(normalizedName)`）、**0 个外键**；4 张 Phase 2 空表（event/entity/event_entity/chunk_entity）、1 张有表无写入（schema_migrations）、v3 新增 app_kv。
- DAO：7 个 DAO、**46 个 @Query**、11 个 @Insert、3 个 Flow 查询、**1 个 @Transaction**（全仓库唯一，`Daos.kt:51`）；0 rawQuery／0 SQL 拼接（迁移 DDL 除外）。
- 迁移：**2 次**（v1→v2、v2→v3，当前 `version = 3`），迁移测试 **3 个用例**（1→2、2→3、1→3 链式），`addMigrations(MIGRATION_1_2, MIGRATION_2_3)`，**未使用 fallbackToDestructiveMigration**（全仓库 grep 无）。
- 检索热路径：每次 `search()` 1 次 `vectorsForSpace` 全表载入 + 最多 `topK*3*4` 条 FTS 候选 + N+1 回填（≤2×topK 次单行查询）。
- 备份：1 个 object、6 个公开入口（Uri/File × export/preview/import）、6 类 zip entry、格式版本 1、**备份自动化测试 0 个**；导出时 `allChunks()`+`allVectors()`+`joinToString` 全量进内存。
- 路径安全：zip 不落盘解压，无 ZipSlip 面；备份为明文（不含 2.8GB 模型文件，模型走`model_requirements.json`引用，这点正确）。

## 发现

### P0-1 导入是“先清库、后解析写库”的非原子流程，失败即丢失现有知识库

- **级别**：P0
- **位置**：`app/src/main/java/com/zhibox/memory/core/backup/KnowledgeBackup.kt:284-348`（清库 :296-297，DAO 写入 :299-347，向量二进制解析 :327-346）；全仓库唯一事务在 `Daos.kt:51`，本流程无事务。
- **证据**：
  ```kotlin
  val db = AppDatabase.get(context)
  db.clearAllTables()                      // :297 先清空用户现库
  db.sourceDao().upsertAll(...)            // :299 之后才开始按 JSON 构造实体
  ...
  val buf = ByteBuffer.wrap(c.vectorBytes).order(ByteOrder.LITTLE_ENDIAN)  // :327
  val count = buf.int
  repeat(count) { val len = buf.int; val blob = ByteArray(len); buf.get(blob) ... } // :331-337
  ```
  清库后仍可能抛异常的点很多：`ArrayList<ChunkEmbeddingEntity>(count)`（:330，损坏头可致 OOM）、`ByteArray(len)`/`buf.get`（:335-336，BufferUnderflow/负数长度）、JSON 缺字段的 `getString`（:314-321）、`insertAll` 的 UNIQUE 冲突、Bigram 重算、SQLITE_BUSY。外层只是 `runCatching` 返回 `Result.failure`（:78-85），此时用户库已被清空且无回滚。
- **影响**：一次损坏/截断的备份包、空间不足或导入中进程被杀 = 现有知识库全部丢失（源文件仍在 documents/ 但 chunk/向量/FTS 全无），核心功能“恢复”变“清库”。这同时也是 P1-1 的放大器（校验不足的包会走到这里）。
- **建议**：
  1. 把 `readAll` + 完整语义校验提前到清库前（见 P1-1）：逐条构造实体、校验 `vectorBlob.size == dimensions*4`、`chunkId ∈ chunkIds`、`count` 与字节数匹配；
  2. 写库阶段放进单事务：`db.withTransaction { sourceDao.deleteAll(); ...; upsertAll(...) }`（`clearAllTables` 不能在 Room 事务内调用，需改用显式 `DELETE FROM` DAO 或 `db.openHelper.writableDatabase.execSQL`）；
  3. 失败时保证库仍是导入前状态；导出端配合写临时文件（P1-6）。
- **工作量**：M

### P0-2 导入不恢复 `embedding_meta` → 恢复后再导出会静默丢失全部向量（备份链断裂）

- **级别**：P0
- **位置**：`KnowledgeBackup.kt:113-124`（导出 meta 只写空间子集）、`:163-179`（导出向量）、`:284-348`（导入全流程无 `upsertMeta`）；`Daos.kt:137/140-146`（仅 upsertMeta/meta 可写 meta，导入未调用）；验收要求 `docs/phase1/MVP1-ACCEPTANCE.md:60-61`。
- **证据**：
  ```kotlin
  // 导出：meta.json 只有 5 个字段，没有 accelerator/quantization/modelRevision/normalization/active
  .put("id", m.id).put("providerId", m.providerId).put("modelId", m.modelId)
  .put("dimensions", m.dimensions).put("taskTemplate", m.taskTemplate)      // :118-123
  // 导出向量：space 必须命中 metas，否则静默 skip
  val known = vectors.filter { it.embeddingSpaceId in spaceIdx }            // :166
  vectors.forEach { if (v.embeddingSpaceId !in spaceIdx) return@forEach ... } // :168-169
  // 导入：没有任何 db.embeddingDao().upsertMeta(...)
  ```
  恢复后 `embedding_meta` 为空表 → **再次导出时 `metas = []`（:104）→ `spaceIdx` 为空（:164）→ 所有向量被 `:169` 跳过，包内 vectors 数量 0，且 `:179` 仍返回 `vectors.size` 给 UI 报“已导出 N 条向量”**。同时 `model_requirements.json` 也为空，下一次导入不再做任何模型在位校验（`missingModels` 恒空）。验收文档明确要求“导出包必须附带 embedding_meta（空间注册表）+ 模型需求清单”。
- **影响**：备份→恢复→再备份 = 向量全丢，且没有任何报错/计数提示（静默数据丢失）；恢复后的设备上设置页空间列表为空（SettingsScreen.kt:159-160）、模型依赖校验失效。当前 `SearchService` 用 `EmbeddingRuntime.spaceIdFor(provider)` 计算 spaceId（SearchService.kt:69），所以“恢复后立即检索”仍可用，掩盖了此问题（W4PlusRunner 的回环验证也因此 PASS，未覆盖“恢复后再导出”）。
- **建议**：
  1. 导出 `meta.json` 写完整 `EmbeddingMetaEntity` 字段并带 `format_version`；导入时逐条 `embeddingDao().upsertMeta(...)`（`createdAt` 原样保留、`active` 按原值或统一置 true）；
  2. 导出端把“meta 为空但向量非空”“向量命中不了空间”从静默 skip 改为 `check`/错误（宁可失败也不产生缺向量备份）；
  3. 补回环测试：导出→导入→再导出，断言第二次包内 vectors 头 count 与首次一致、meta spaces 数一致。
- **工作量**：M

### P1-1 备份包无完整性校验、无格式版本门、无尺寸上限

- **级别**：P1
- **位置**：`KnowledgeBackup.kt:49`（`FORMAT_VERSION` 只在导出 :115 使用）、`:198-225`（readAll）、`:251-291`（preview/import 校验）、`:327-346`（二进制解析）。
- **证据**：包内没有包级或 entry 级 SHA256/CRC 校验；`meta.getInt("format_version")` 只用于展示（:270），`importZip` 从不比较版本；`readAll` 只检查 `meta.json`/`model_requirements.json` 存在（:217-219）；`preview.blocked` 仅由模型在位决定（:248、:289-291）；`vectors.bin` 头 count 与剩余字节数不做一致性校验（:327-346）；`zip.readBytes()` 无单 entry 大小上限（:206-212，zip bomb/OOM 面）。
- **影响**：损坏/被截断的包可以顺利通过 preview，进入 P0-1 的“清库后解析”分支；未来格式升级时旧代码会静默误读新包；恶意/异常包可造成 OOM。
- **建议**：`format_version != FORMAT_VERSION` 直接拒绝（preview 与 import 都查）；导出时对 `vectors.bin`/`chunks.jsonl` 计算 SHA-256 写入 meta，导入校验；解析向量前先 `check(buf.remaining() == count * (2+8+4+len))`、`check(len in 0..dim*4)`、限制每 entry 最大字节数（如 512MB）并检查 zip entry 数。
- **工作量**：M

### P1-2 导入/导出与后台 Worker 无互斥，导出快照不一致

- **级别**：P1
- **位置**：`KnowledgeBackup.kt:100-105`（导出 6 次独立 DAO 读，无事务）、`:296-297`（导入前无在途任务检查）；对照 `IngestRepository.kt:235`（deleteSource 有 `activeCount()==0` 守卫）、`Daos.kt:51`（全仓库唯一事务）。
- **证据**：`exportZip` 依次调用 `observeAll().first()`×2、`allChunks()`、`allMeta()`、`allVectors()`，各语句各自一个隐式事务；导入端不检查 `ingestJobDao().activeCount()`，也不取消/等待 `ingest-batch`、`reembed` 两个 unique work。
- **影响**：导入时若导入/重嵌入 worker 正在跑，`clearAllTables` 与 worker 写入交错 → 混合数据（刚导入的库混入旧 job 的 chunk/向量）或 SQLITE_BUSY 直接失败（叠加 P0-1 即清库失败）；导出时可能拿到“有 chunk 无向量/有 document 无 source”的半态包（导入端不校验引用，见 P1-1）。
- **建议**：导出/导入开头 `check(db.ingestJobDao().activeCount() == 0) { "导入/重嵌入进行中，请稍后" }`；导出读取放进 `db.withTransaction { ... }` 得到一致快照；导入在事务内完成（P0-1）。设置页按钮同时置灰。
- **工作量**：M

### P1-3 模型“在位”判定有 false-negative：共享模型仓/未收录模型的备份无法导入

- **级别**：P1
- **位置**：`KnowledgeBackup.kt:251-268`（buildPreview）；`PathPreferences.kt:73-81`（candidates 含 `cacheDir/models-link`）；`SharedModelStore.kt:15-16`（共享仓模型以本地副本落在 models-link）；`ModelCatalog.kt:181-186`（sizeMatches 只认 `filesDir/models/$file` 路径）。
- **证据**：
  ```kotlin
  val localModel = File(context.filesDir, "models/$file")          // :257
  val present = (file in localFileNames || localModel.isFile) &&
      (entry == null || ModelCatalog.sizeMatches(entry, localModel))  // :258-259
  ```
  `entry != null`（目录内模型）但文件实际在 `cacheDir/models-link` 时：`file in localFileNames` 为 true，但 `sizeMatches(entry, localModel)` 因 `localModel` 不存在返回 false → `present=false` → `blocked=true`，导入被拒绝；`entry == null`（未收录的实验模型/`.task` 变体）时 `catalog_file` 为 null → `file=""`，两条件均 false → 同样阻断，且 `catalogDownloadable=false`，用户无路可走。
- **影响**：启用 W4 共享模型仓的用户、以及用未收录模型的设备，**合法备份在本机都无法恢复**（明明模型可用）。这是备份功能的核心可用性问题。
- **建议**：用真实候选路径做匹配，而不是固定 filesDir 路径：
  ```kotlin
  val local = PathPreferences.candidates(context).firstOrNull { it.name == file }
  val present = local != null && (entry == null || ModelCatalog.sizeMatches(entry, local))
  ```
  `catalog_file` 为 null 时退化为“同名候选文件存在即可”；同时 UI 对“缺失但本地存在同名文件”给出区分提示。
- **工作量**：S

### P1-4 失败文档的向量残留可检索；维度校验晚于入库，错误维度会毒化整个空间

- **级别**：P1
- **位置**：`IngestRepository.kt:168-188`（分批 insert 在 :174，维度 check 在 :186）、`:194-197`（失败只改状态）；`SearchService.kt:160-162`（维度不符整空间抛错）、`:113-118`（检索不按 document.status 过滤）。
- **证据**：
  ```kotlin
  if (embeddings.size % batch == 0 || i == chunks.lastIndex) {
      db.embeddingDao().insertAll(embeddings.toList())   // :174 先落库
  }
  ...
  check(provider.descriptor.dimensions == expectedDim) {   // :186 后校验
      "unexpected dimensions ..." }
  ```
  `processDocument` 的 catch 只 `docDao.updateStatus(documentId, "failed")`（:196），不清理已写入的 chunk/chunk_fts/部分 embedding；`SearchService.rankChannels` 对空间内所有向量做 `check(dim > 0 && rows.all { it.vectorBlob.size == dim * 4 })`，任一坏向量会让**该空间所有查询报错**。
- **影响**：模型/目录元数据不一致（如 catalog dims 写错、模型换版）时，先写入的错误维度向量永久毒化当前空间，检索全面失效且用户无自愈入口；导入失败的文档会以半成品（部分 chunk、无完整向量、FTS 可能已删）出现在检索结果中。
- **建议**：① 每批 `embedDocuments` 返回后先校验维度/有限值，通过再 insert；② catch 中删除该 document 的 `chunk_embedding`/`chunk_fts`/`chunk`（复用已有 delete DAO）；③ `buildHits`/`rankChannels` 关联 document 过滤 `status='ready'`（或加 `includeIncomplete=false` 参数）。
- **工作量**：M

### P1-5 检索热路径每次全表载入向量；`embeddingSpaceId` 无可用索引，PRELOADED 缓存未实现

- **级别**：P1（性能上限；MVP 小语料不炸，但库变大即 ANR/OOM）
- **位置**：`Daos.kt:157-162`（`vectorsForSpace` 全表 filtered scan）、`SearchService.kt:70-71,153-186`（每次查询重新读全表 BLOB 并展平矩阵）；3.json 中 `chunk_embedding` 索引是 `(chunkId)` 与 `(chunkId, embeddingSpaceId, role)`，**无法服务 `WHERE embeddingSpaceId=?`**（前导列是 chunkId）。
- **证据**：`@Query("SELECT chunkId, vectorBlob FROM chunk_embedding WHERE embeddingSpaceId = :spaceId AND role = 'content'")`；`SearchService` 每次 `search()` 执行 `val rows = db.embeddingDao().vectorsForSpace(spaceId)` 后用 `FloatArray(n*dim)` 复制全量矩阵。G4 设计写的是“PRELOADED——一次载入”，实际是“每次查询载入”。
- **影响**：10 万 chunk×768d≈300MB BLOB，每次检索全表扫描+反序列化+复制，必然 ANR/OOM；索引缺失使过滤至少多扫一遍表。
- **建议**：v4 迁移加 `Index("embeddingSpaceId", "role")`（或唯一索引 `(embeddingSpaceId, chunkId)` 让扫描紧凑）；`SearchService` 按 `spaceId` 缓存 `(chunkIds, FloatArray)`，用 `embedding_meta` 的 `createdAt`/行数或 app_kv 版本号失效；长期可把向量落 mmap 文件。
- **工作量**：M（索引+缓存）/ L（mmap）

### P1-6 大知识库导出/导入内存峰值过高，导出非原子（SAF 目标可能留半包）

- **级别**：P1
- **位置**：`KnowledgeBackup.kt:99-111`（全量读表）、`:142-161`（`joinToString` 整份 JSONL 进内存）、`:163-179`（`allVectors()` 全量 BLOB 进内存后再逐条写）、`:198-225`（`zip.readBytes()` 全量读 entry）、`:51-61`（直接写目标 stream，无临时文件+rename）。
- **证据**：导出把 chunks/vectors 一次性载入内存再序列化；导入把整个 zip 的各 entry 读成 `ByteArray`/`StringBuilder` 再 `JSONObject` 化（:206-212），随后又构造完整的 Entity 列表（:314-321）。SAF `openOutputStream(destUri)` 直接写，异常/取消时目标文档留下截断 zip。
- **影响**：万级 chunk（约 30MB 向量 + 数倍 JSON 开销）在低端机有 OOM 风险；用户把截断包当有效备份，恢复时触发 P0-1。模型文件未进包（正确），但 DB 全量仍不小。
- **建议**：导出改为流式——`chunkDao`/`embeddingDao` 提供 `LIMIT/OFFSET` 或 `Flow` 分批查询，逐行写 zip entry（不 `joinToString`）；导出先写 `cacheDir/backup.tmp`，成功后 copy 到 SAF，失败删除；导入先落磁盘临时文件再两遍扫描（preview/import），避免全量内存。
- **工作量**：M

### P1-7 备份格式丢关键字段，恢复后证据原文不可回溯、Phase2 多角色向量会降级丢失

- **级别**：P1
- **位置**：`KnowledgeBackup.kt:142-147`（sources 无 `uri`）、`:148-154`（documents 无 `sizeBytes`）、`:163-177`（向量无 `role`/`vectorNorm`/`createdAt`）、`:299-313`（导入 `uri=""`）、`:339-344`（硬编码 `role="content"`、`vectorNorm=1.0`）、`:113-124`（meta 字段子集）；`Entities.kt:39-40`（注释“导入时写入”）、`Entities.kt:155-161`（app_kv 注释“随知识库备份走”，实际未进包）。
- **证据**：导出 sources 只写 id/title/mimeType/createdAt/status（:144-146），import 写 `uri = ""`（:301）；向量行头只有 spaceIdx/chunkId/len/blob（:170-176）；Phase 2 六向量角色协议字段 `role` 在恢复时全部落为 `"content"`（:342），若备份含非 content 角色，会撞上唯一索引 `(chunkId, spaceId, role)` 或被静默丢弃。
- **影响**：恢复后 `SearchHit.sourceUri` 为空，证据页“打开原文/定位”不可用（原文文件本来也没进包，但 UI 无法区分“未随备份迁移”与“文件损坏”）；`sizeBytes` 归零影响来源页展示；`reembed_last_done` 等 app_kv 状态丢失导致设置页“上次重嵌入时间”消失；Phase 2 备份将静默降级。
- **建议**：`FORMAT_VERSION=2` 补齐 `uri/sizeBytes/role/vectorNorm/createdAt/app_kv`，读取端对 v1 包补默认值（保持向后兼容）；`sourceUri` 为空时 UI 显示“原始文件未随备份迁移，仅知识片段可检索”；`role` 写入 vectors.bin 行头（或每向量一个 JSON 行）。
- **工作量**：S/M

### P1-8 基准验证入口可清空真实数据库，且未校验备份成功

- **级别**：P1（仅开发/基准路径，但入口无 DEBUG 门控）
- **位置**：`app/src/main/java/com/zhibox/memory/benchmark/W4PlusRunner.kt:73-84`、`ui/BenchmarkScreen.kt:502-506`、`MainActivity.kt:89`（`"benchmark" -> BenchmarkScreen(...)`，无 `BuildConfig.DEBUG` 判断）。
- **证据**：
  ```kotlin
  step("export") { val r = KnowledgeBackup.exportToFile(...); put("ok", r.isSuccess) } // :74-77 不 check
  step("wipe") { db.clearAllTables(); put("chunks", db.chunkDao().count()) }          // :78-80 直接清真库
  ```
  `export` 失败也继续 `wipe`；benchmark 页面在任意构建里可进入（MainActivity 路由未加门控）。
- **影响**：误触或导出失败场景下清空用户知识库（与 P0-1 同性质的数据丢失，只是入口在调试路径）。另 `clearAllTables()` 也会清掉 `app_kv/schema_migrations/ingest_job`。
- **建议**：`if (!BuildConfig.DEBUG) return` 门控 benchmark 入口；`wipe` 前 `check(r.isSuccess)`，并把库文件先复制为 `zhibox.sqlite3.bak` 再清。
- **工作量**：S

### P2-1 FTS4 表设计：contentful 重复存 bigram 文本、rowid 未与 chunkId 对齐（注释与实现不符）

- **级别**：P2
- **位置**：`Entities.kt:57-64`（注释称“rowid 与 ChunkEntity.id 对齐由入库逻辑维护”）、`:60-63`（`rowid` autoGenerate，实际未显式赋值）；`IngestRepository.kt:147-150`（插入时只给 chunkId）；schema `chunk_fts` createSql = `CREATE VIRTUAL TABLE ... USING FTS4(chunkId INTEGER NOT NULL, bigramContent TEXT NOT NULL)`。
- **证据**：`ChunkFtsEntity(chunkId = it, bigramContent = ...)` 从未传 `rowid`，rowid 由 SQLite 自增；`searchFtsOrdered` 的 `ORDER BY rowid` 实际是“插入顺序”，删除重导后与 chunkId 顺序无关；FTS4 同时存储 bigramContent（CJK bigram 约为原文 2–3 倍字节）作为内容列，且 chunkId 也索引进 FTS；`chunk_fts` 无 `UNIQUE(chunkId)`，重复插入（无事务的 import/rebuild 中断重试）会产生重复召回。
- **影响**：存储放大（MVP 可接受）；注释误导后续维护（若未来按 rowid=chunkId 做 join/删除会出错）；重复 FTS 行会让关键词通道出现重复候选（融合前未去重）。无当前功能错误。
- **建议**：显式 `ChunkFtsEntity(rowid = chunkId, ...)` 使注释成立（Room FTS 允许指定 rowid），或修正注释；在 `insertFts` 前保证删除或用 `chunkId` 去重；长期评估 FTS5 external content 降存储。
- **工作量**：S

### P2-2 13 表 0 外键：手工级联只覆盖 5 表，Phase 2 关系表未纳入

- **级别**：P2
- **位置**：`Entities.kt` 全部实体无 `foreignKeys`；`Daos.kt:21-59`（deleteCascade 只删 chunk_embedding/chunk_fts/chunk/ingest_job/document/source）。
- **影响**：当前 Phase 2 的 event/event_entity/chunk_entity 为空，无实际错误；但 Phase 2 启用后 `deleteSource` 会留孤儿关系行，且任何新增删除路径漏写一步即产生静默孤儿（既无 FK 兜底也无法被 `ftsHealthCheck` 类检查发现）。`processDocument` 的删除也是手工三步（IngestRepository.kt:134-136），无事务。
- **建议**：v4 迁移给 `chunk.documentId`、`chunk_embedding.chunkId`、`event.chunkId`、关系表两端加 FK `ON DELETE CASCADE`（需对应索引），或最低成本先把 4 张 Phase2 表加进 `deleteCascade` 并在注释标注 Phase 2 责任。
- **工作量**：M

### P2-3 `schema_migrations` 有表无写入（与 SAG 要求不符），属死表死代码

- **级别**：P2
- **位置**：`Entities.kt:149-153`、`Daos.kt:257-261`（`SchemaMigrationDao.record`，全仓库 grep 0 调用）；要求见 `docs/SAG_Android_MVP_Implementation_Plan_v1.0.md:126`（“迁移机制用 Room Migration，`schema_migrations` 表记录版本”）。
- **影响**：该表永远是空表，无法回答“设备上实际应用过哪些迁移”；schema 里多一张无意义表，也使迁移测试多一个未覆盖对象。
- **建议**：在 `MIGRATION_1_2`/`MIGRATION_2_3` 的 `migrate()` 内 `INSERT OR IGNORE INTO schema_migrations(version, appliedAt) VALUES (2, ...)`（v1 建库时写 v1），或删除该表/DAO 并在文档中说明由 `room_master_table` 承担版本记录。
- **工作量**：S

### P2-4 `embedding_meta.active` 无单一 active 语义，`activeSpace()` 是死查询

- **级别**：P2
- **位置**：`Daos.kt:139-140`（`activeSpace()` 0 调用方）、`IngestRepository.kt:207-222`（ensureSpace 每个新空间都 `active = true`，从不置旧空间 false）、`EmbeddingRuntime.kt:127-153`（registerSpace 同样）。
- **影响**：`SELECT * FROM embedding_meta WHERE active = 1 LIMIT 1` 一旦被未来代码使用（SAG P1-W3-2 的双缓冲切换设计），会返回任意一个空间（无 ORDER BY，多个 active）；现在只是死代码，但字段语义误导。
- **建议**：在写入新空间事务里 `UPDATE embedding_meta SET active = 0` 再置新空间 active；或删除 active 列与 activeSpace()（检索本来就按 spaceId 精确匹配）。
- **工作量**：S

### P2-5 状态列用 String、无 CHECK/枚举，查询依赖字面量

- **级别**：P2（风格/健壮性，非当前 bug）
- **位置**：`Entities.kt:24`（source.status）、`:38`（document.status 五态）、`:142`（ingest_job.stage 七态）；查询字面量 `Daos.kt:77,218,226,238`。
- **影响**：拼写错误不会被编译器/DB 拦截（如 `"cancled"` 会让 cancelAllPending/nextQueued 静默失效），状态集在 Entities 注释、DAO、UI 三处重复维护。
- **建议**：定义 Kotlin enum + `@TypeConverter`，DB 侧加 `CHECK` 约束需重建表，可放到下一次 v4 迁移；至少把状态常量收敛到一处。
- **工作量**：M

### P2-6 同一文件重复导入无去重（checksum 算了不用）

- **级别**：P2（产品决策）
- **位置**：`IngestRepository.kt:89-104`（`checksum = sha256Hex(bytes)` 后仅存列）；全仓库无按 checksum 的查询。
- **影响**：重复导入同一文件会生成新的 source/document/chunk 与整套向量（重复 embedding 算力 + 存储 + 检索重复结果）。
- **建议**：`importFile` 前 `SELECT id FROM document WHERE checksum = :sum AND status IN ('pending','processing','ready') LIMIT 1`，命中则跳过或提示“已存在”；索引按需（当前表小可先不建）。
- **工作量**：S

### P2-7 迁移 SQL 的 DEFAULT 未写入实体 `@ColumnInfo(defaultValue)`，schema JSON 与迁移 SQL 不同源

- **级别**：P2（维护风险）
- **位置**：`AppDatabase.kt:48,57,70`（迁移 DDL 用 `NOT NULL DEFAULT 'cpu'/'f32'/0`）、`Entities.kt:40,80,83`（实体只有 Kotlin 默认值，无 `@ColumnInfo(defaultValue=...)`）；3.json 的 createSql 中对应列无 `DEFAULT`。
- **证据**：新建库（Room 直接 create v3）与迁移库的 `PRAGMA table_info.dflt_value` 不同（迁移库有 DEFAULT，新库无）。Room 2.8 的 schema 校验不比对 DEFAULT，所以 `runMigrationsAndValidate` 通过；但未来若依赖 DB DEFAULT（如省略列插入、或升级 Room 行为），新旧库行为不一致。当前 DAO 均显式绑定全部非空列，**无实际 bug**。
- **建议**：实体上补 `@ColumnInfo(defaultValue = "cpu")` 等，让 schema JSON 记录 DEFAULT 并与迁移保持同源；或迁移 DDL 去掉 DEFAULT 改为 `DEFAULT` 后立即 `UPDATE` 回填（对 NOT NULL 不可行，推荐前者）。
- **工作量**：S

### P2-8 迁移测试有数据断言缺口；备份/恢复 0 自动化测试

- **级别**：P2
- **位置**：`app/src/androidTest/java/com/zhibox/memory/data/db/MigrationTest.kt:30-108,174-198`；`grep KnowledgeBackup app/src/test app/src/androidTest` 无匹配。
- **证据**：迁移测试只插入 `embedding_meta` 与 `document` 行做数据无损断言，未插入 `chunk`/`chunk_embedding`/`chunk_fts` 行验证“迁移不触碰其它表”；未覆盖导入导出回环（P0-2 正是该缺口漏掉的）、未覆盖 v1 库仅注册部分迁移的负例。schema 结构校验由 `runMigrationsAndValidate(..., true)` 覆盖（这点做得好）。
- **建议**：加 ① 迁移后对 chunk/chunk_fts/chunk_embedding 行数与内容断言（FTS 用 MATCH 验证可查）；② `BackupRoundTripTest`（instrumentation：造 2 doc/3 chunk/2 空间 → exportToFile → wipe → importFromFile → 校验 chunk/fts/vector/meta 计数与内容 → 再 export 校验 vectors 数不降）；③ 破坏包（截断 vectors.bin、坏 chunk JSON）导入应失败且原库不变（对应 P0-1）。
- **工作量**：M

### P2-9 preview 的 `vectorCount` 端序错误

- **级别**：P2
- **位置**：`KnowledgeBackup.kt:275` vs 写入/导入处 `:167-176`、`:327`。
- **证据**：写入与导入都 `.order(ByteOrder.LITTLE_ENDIAN)`，但 preview 用 `ByteBuffer.wrap(c.vectorBytes).int`（默认 BIG_ENDIAN）读同一个头 → count=1 时显示 16777216，count=3 时显示 50331648。
- **影响**：导入确认弹窗/预览展示的向量数是错的（不影响实际导入逻辑）。当前 UI 未展示 vectorCount（SettingsScreen 预览只显示空间/模型），但 `ImportPreview.vectorCount` 是公开 API，基准/W4+ 报告会用到。
- **建议**：`.order(ByteOrder.LITTLE_ENDIAN).int`，或抽 `decodeVectorHeader()` 供 preview/import 共用。
- **工作量**：S

### P2-10 导出未过滤文档状态，恢复后产生“永远 pending/failed”的死行

- **级别**：P2
- **位置**：`KnowledgeBackup.kt:101-102`（sources 用 `observeAll()` 已过滤 active；documents 无过滤）、`:306-313`（原样恢复 status 但不恢复 ingest_job）。
- **影响**：取消/失败导入留下的 document（status=cancelled/failed/pending，无 chunk）会随备份恢复，且恢复后没有对应 job，UI 会永远显示待处理；若将来实现 source 软删（Entities.kt:24 注释有 "deleted" 状态，目前无代码写入），documents 无过滤会产生 sourceId 悬空的孤儿文档。
- **建议**：导出只包含 `status='ready'` 的 document（或恢复时把非 ready 归一为 failed 并在 UI 标注）；sources/documents 的过滤条件保持一致。
- **工作量**：S

### P2-11 `buildHits` 的 N+1 单行查询

- **级别**：P2（性能，MVP topK=10 无感）
- **位置**：`SearchService.kt:113-118`。
- **证据**：`docs = docIds.mapNotNull { db.documentDao().get(it) }`、`sources = sourceIds.mapNotNull { db.sourceDao().get(it) }`，每次搜索最多 2×topK 次单行查询；`searchChannels` 的 `docTitle` 同样是 N+1（SearchService.kt:233-241）。
- **影响**：检索路径多出 10–20 次微查询；与 P1-5 一起放大延迟。
- **建议**：DAO 加 `@Query("SELECT * FROM document WHERE id IN (:ids)")` / source 同理，批量回填（并注意 SQLite 变量上限）。
- **工作量**：S

## 做得好的地方

1. **迁移与 schema 一致性可靠**：逐列核对 1/2/3.json 与 `MIGRATION_1_2`/`MIGRATION_2_3`，列名/类型/NOT NULL/PK/索引全部一致；`app_kv` 迁移 DDL 与 3.json createSql 语义等价（仅 PK 写法不同）；`exportSchema=true` + `androidTest.assets` 接 schemas + `room-testing` 依赖齐备（app/build.gradle.kts:48,105），`runMigrationsAndValidate(..., true)` 真正校验 schema。
2. **无破坏性回退**：全仓库无 `fallbackToDestructiveMigration`，v1→v2→v3 链式迁移有真机测试（含 1→3 chained），版本不符会明确报错而不是清库。
3. **NOT NULL 回填策略正确**：两个新列都用 `ALTER TABLE ... NOT NULL DEFAULT` 一次完成，存量数据有默认值，符合 SQLite 加列约束，不需要临时表重建。
4. **删除级联集中且幂等**：`deleteCascade` 用 `@Transaction` 包住 5 表删除（Daos.kt:50-59），子查询顺序正确（先删除依赖后删主表），并配合 `IngestRepository.deleteSource` 的“导入进行中拒绝删除”守卫（IngestRepository.kt:233-242），实测有 DeleteCascadeRunner 自证。
5. **FTS 写入/查询两侧统一 bigram 化**，MATCH 串由 `BigramTokenizer.toOrMatchQuery` 生成（引号剥离，:29-30），无 SQL 注入或 FTS 语法注入面；FTS 可从 chunk.content 全量重建（rebuildFts），并有 healthCheck。
6. **DAO 层干净**：46 个 @Query 全部使用绑定参数，无 rawQuery/字符串拼接；写路径用 suspend，读路径用 Flow（3 处），没有主线程查询；`OnConflictStrategy` 按语义区分（REPLACE 用于 upsert，IGNORE 用于迁移记录）。
7. **导入状态机与并发防护有真实教训沉淀**：`claim` 原子认领、`requeueInterrupted` 回收中间态、`cancelAllQueued` 覆盖中间态、双入队用 DB `activeCount` 而不是仅 AtomicBoolean，注释里记录了实测根因（K40 双批次、命名参数错位等），这些是数据层最值得保留的资产。
8. **Room 单例实现正确**：`@Volatile` + 双重检查锁（AppDatabase.kt:81-93），使用 `applicationContext`，不会泄漏 Activity；库名固定，WAL 采用 Room 默认（未显式 setJournalMode，运行期可再验证）。
9. **备份的边界感不错**：不含 2.8GB 模型文件、FTS 不进包而从 chunk 重建（避免索引格式绑定）、模型需求清单含 fileName/sha256/accelerator、GPU 不兼容可选丢弃、向量 BLOB 用 LE 且导入解析端序正确（除 preview 的头）、zip 不落盘解压（无 ZipSlip）。
