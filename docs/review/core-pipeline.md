# 核心管线审查

> 审查对象：当前文件系统上的未提交代码（Phase 1 W4 收尾态），非 git HEAD。
> 范围：`core/ingest/**`、`core/embedding/**`、`core/search/**`、`core/text/**`，以及关联 DAO/UI/Worker 调用方。
> 方法：逐文件通读 + grep 追踪调用方 + `sqlite3` 本地复现 FTS4 边界（未运行 gradle / 未连接设备）。

## 总评

管线的分层与文档化在个人项目里属于少见的高水准：状态机、幂等策略、空间隔离、根因记录都写在代码注释里，W2/W3/W4 验收数据也能对上实现。核心设计（进程单例 `EmbeddingRuntime` + mutex 串行、bigram FTS + 精确向量扫描 + RRF、chunk 级证据回溯）方向正确，且已被 220/262 条评测集验证到 R@10=1.0。

但“当前态”存在一个贯穿导入与检索的结构性缺口：**写入不是事务性的、检索不区分文档状态**。chunk/FTS 在 embedding 之前落库，而向量扫描与 FTS 查询都不 join `document.status`——失败/取消/处理中的文档会以“半成品”形态进入检索结果，这是 P0 级的数据正确性问题。其次，取消与异常分类没有区分 `CancellationException`，会得到“文档 failed + 部分数据残留 + 队列悬挂”的组合；`EmbeddingRuntime` 重建失败时还会把已关闭的 provider 留在 `shared` 上，导致运行时永久不可用（直到进程重启）。

工程面上还有两处与 W4-5 “ANR 防护”结论矛盾的热点：导入的文件遍历/整文件读取/SHA-256 跑在 Main；检索的矩阵构建与全量点积也跑在 Main。当前库小（评测 45–50 篇）掩盖了它们，扩库后是明确的卡顿/ANR 风险。测试侧只有 34 个 JVM @Test，且全部集中在纯文本/融合/目录这些无副作用模块；导入状态机、检索服务、嵌入运行时、Worker 行为都**没有**任何单测保护。

结论：**不建议以当前形态直接合并 W4 收尾**。先修 P0-1/P0-2，再补 P1-1/P1-2/P1-3 三个“失败后不可恢复”的问题；其余 P1 可在 W5 排期。总体判断：架构 B+，当前实现健壮性 C。

## 量化数据

| 项 | 数值 | 说明 |
|---|---|---|
| 审查主文件 | 11 个 / 1523 行 | ingest 3（491）、embedding 5（565）、search 1（309）、text 2（158） |
| 含关联文件 | 1768 行 | 另含 DocumentParser 29、TextNormalizer 16、Daos 261、Entities 161 |
| 最大文件 | IngestRepository.kt 328 行、SearchService.kt 309 行 | 两者也是职责最重、风险最集中处 |
| 状态机 | `document.status` 5 态 × `ingest_job.stage` 9 态 | 两套状态无一致性约束（见 P2-6） |
| 活动态判定 | `activeCount()` 覆盖 5 个 stage | 用于导入互斥、删除拒绝、重嵌入让路 |
| 关键常量 | RRF_K=60、CANDIDATE_FACTOR=3、FTS limit=topK×12、EMBED_BATCH=16、Chunk 默认 600 字、PATH_B_MAX_CHARS=600 | 分散硬编码；600/600 分支实际等价（P2-10） |
| JVM 单测 | 3 文件 / 34 个 @Test | TextLayerTest 18、ModelCatalogTest 12、CandidateFusionTest 4 |
| 本领域被测类 | 4 / 15 | Chunker/文本、CandidateFusion 有测；**IngestRepository、IngestBatchWorker、ReembedWorker、EmbeddingRuntime、3 个 Provider、SearchService、FTS 覆盖重排 0 覆盖** |
| androidTest | 1 文件（MigrationTest） | 覆盖 schema 迁移，不覆盖管线 |
| 检索内存量级 | 2 × n × dim × 4 B | n×dim FloatArray + rows 的 n×dim ByteArray 双份；1k chunk≈6.1MB，10k chunk≈61MB，全部在调用方上下文分配 |
| 检索候选 | 向量 topK×3；关键词 topK×12 | 融合前截断，见 P2-1 |
| 本轮复现 | sqlite3 FTS4：空短语/标点 MATCH 未报错，返回空 | 说明 FTS 注入/标点崩溃不是本轮问题；超长表达式未验证（P2-2 标“待验证”） |

## 发现

### P0（数据正确性 / 核心功能 / 并发正确性）

#### P0-1 【P0】检索不按文档状态过滤：失败/取消/导入中的文档 chunk 会进入结果

**位置**：`core/search/SearchService.kt:70`、`SearchService.kt:190`、`data/db/Daos.kt:158-162`、`Daos.kt:121-124`、`core/ingest/IngestRepository.kt:134-150,186-193,195-197`

**证据**：
- 向量通道 `vectorsForSpace` 只查 `chunk_embedding`（`Daos.kt:158-162`），没有 join `chunk`/`document`；关键词通道 `searchFtsOrdered` 只查 `chunk_fts`（`Daos.kt:121-124`）——两条路径都**没有** `document.status = 'ready'` 约束，`SearchService.kt:70,190` 也未见任何状态过滤。
- 写入顺序是先写 chunk+FTS、后写向量、最后才置 ready：`db.chunkDao().deleteFtsByDocument / deleteByDocument / deleteByDocument` → `insertAll(chunks)` → `insertFts(...)`（`IngestRepository.kt:134-150`）→ `withProvider { ... 逐批 insert 向量（155-183）→ 维度校验（186-188）}` → `docDao.updateStatus(documentId, "ready")`（`IngestRepository.kt:193`）。
- 失败分支只改状态、**不清理已写入的 chunk/FTS/向量**：`catch (t: Throwable) { docDao.updateStatus(documentId, "failed"); jobDao.markFailed(...); throw t }`（`IngestRepository.kt:195-199`）。
- 因此存在两条真实可达路径把“非 ready 文档”送进检索：
  1. 维度校验在**全部向量插入之后**才执行（`IngestRepository.kt:186`），一旦不匹配，文档 failed，但 chunk+FTS+全部向量已落库；
  2. 导入过程中，chunk+FTS 在取引擎锁之前写入，而检索在引擎锁释放窗口即可命中（一批 100 篇导入会产生大量窗口）。

**影响**：检索结果混入导入失败/已取消/仅写了一半的文档内容，用户看到的是“知识库里显示失败，但搜索能搜到”的幽灵证据；`evidence` 回溯会指到 failed 文档。也违背 §9.1-5 “删除/失败后数据不再命中”的语义边界。当前评测集是干净的 ready 库，所以 W2/W3 的 R@10=1.0 无法暴露此问题。

**建议**（S/M）：
1. 两条查询都加状态约束（最小修复）：
```sql
-- vectorsForSpace
SELECT e.chunkId, e.vectorBlob FROM chunk_embedding e
JOIN chunk c ON c.id = e.chunkId
JOIN document d ON d.id = c.documentId
WHERE e.embeddingSpaceId = :spaceId AND e.role = 'content' AND d.status = 'ready'
-- searchFtsOrdered
SELECT f.chunkId FROM chunk_fts f
JOIN chunk c ON c.id = f.chunkId
JOIN document d ON d.id = c.documentId
WHERE chunk_fts MATCH :matchQuery AND d.status = 'ready'
ORDER BY f.rowid LIMIT :limit
```
2. 中期把“文档 chunk+FTS+向量”写成一次 Room `@Transaction`（或引入 `is_published` 标记：全部写完后再原子置位，检索只读已发布行），失败路径负责清理。
3. 补一条集成测试：构造 failed 文档 + chunk/FTS/向量 → 检索必须 0 命中。

---

#### P0-2 【P0】取消/停止被当成业务失败：文档误标 failed、部分数据残留、Worker 吞掉 CancellationException

**位置**：`core/ingest/IngestRepository.kt:195-199`、`core/ingest/IngestBatchWorker.kt:32-34`、`core/embedding/UniversalEmbedderProvider.kt:84-90`（`ensureActive` 位置）

**证据**：
- Provider 批量接口内逐条 `currentCoroutineContext().ensureActive()`（`UniversalEmbedderProvider.kt:87`、`LiteRtLmEmbeddingProvider.kt:68`、`TextEmbedderProvider.kt:65`），因此 WorkManager 停止 Worker 时会在嵌入循环中抛 `CancellationException`。
- `processDocument` 的 `catch (t: Throwable)` 不区分类型（`IngestRepository.kt:195`），会先把文档置 `failed`、job 置 `failed`（195-197），再把取消异常向上抛；`IngestBatchWorker` 又用 `runCatching` 吞掉它并继续循环（`IngestBatchWorker.kt:32`）。
- `runCatching` 同样吞 `OutOfMemoryError` 等 `Error`，之后继续在同一进程/引擎上跑下一篇。

**影响**：① 用户取消/系统停止（含 WorkManager 10 分钟量级的执行上限、JobScheduler 停止；本项目未 `setForeground` 也未处理 `isStopped`——grep 确认 ingest 路径无 `setForeground`/`isStopped`）时，当前文档被永久标成 failed，而它其实是被中断、应当 requeue；② 中断点前的 chunk/FTS/向量残留并通过 P0-1 进入检索；③ failed 是终态，`requeueInterrupted` 只回收 parsing..storing（`Daos.kt:238-239`），所以这批数据永不重做；④ 若取消发生在“已 claim 未完成”之间，job 会停在中间态，见 P1-6。

**建议**（S）：
```kotlin
// IngestRepository.processDocument
} catch (c: kotlinx.coroutines.CancellationException) {
    jobDao.requeueJob(jobId, now())   // 新增：stage 置回 queued，供下次 Worker 重做
    throw c
} catch (e: Exception) {
    docDao.updateStatus(documentId, "failed")
    jobDao.markFailed(jobId, "${e.javaClass.simpleName}: ${e.message}", now())
    throw e
}
// IngestBatchWorker
try { repository.processDocument(docId, job.id) }
catch (c: CancellationException) { throw c }
catch (e: Exception) { /* 已落库，继续下一篇 */ }
```
并显式 `catch (e: Error) { throw e }`，不要把 OOM 当业务失败。

---

### P1（重要，影响可用性/正确性但非直接数据损坏）

#### P1-1 【P1】重建失败后 `shared` 残留已关闭的 provider，运行时永久返回 “provider already closed”

**位置**：`core/embedding/EmbeddingRuntime.kt:52-54,89-98,100`

**证据**：
```kotlin
if (shared == null || sharedKey != key) {
    shared?.close()          // 旧实例被关闭，但 shared 仍指向它
    sharedKey = key          // 新 key 已写入
    ...
    if (tryGpu) {
        try { shared = buildAndProbe(..., true) }
        catch (t: Throwable) { if (!autoFallback) throw t; ... }  // 显式 on 直接抛
    } else { shared = buildAndProbe(...) }                        // 建引擎抛异常同样逃逸
}
return@withLock block(shared!!)   // 下次调用 shared != null && sharedKey == key → 直接返回已关闭实例
```
- 触发条件：曾成功建过 provider（`shared != null`），随后 key 变化（切 GPU on / 换模型 / 引擎构建抛 OOM）且新构建失败。之后 `sharedKey == key`，不会重建，`block(shared!!)` 每次都在 encode 里被 `check(!closed.get())`（`UniversalEmbedderProvider.kt:96` 等）打回。
- 该状态跨导入/检索全部失败，只能杀进程（或再改偏好使 key 再变）恢复。

**影响**：一次本可回退的模型加载失败，升级成“应用内嵌入永久不可用”，交互上表现为搜索报 `检索失败：provider already closed`、导入全部失败。

**建议**（S）：重建前先清引用，失败时清 key：
```kotlin
val old = shared
shared = null; sharedKey = null
runCatching { old?.close() }
try {
    shared = buildAndProbe(...)
    sharedKey = key
} catch (t: Throwable) {
    shared = null; sharedKey = null
    throw t
}
```
并在 `block` 前加 `check(shared != null && !closed)` 的防御或 `ensureProvider()` 自愈重试一次。

---

#### P1-2 【P1】GPU 试探超时会泄漏引擎实例（可能 2 份模型同时驻留）

**位置**：`core/embedding/EmbeddingRuntime.kt:80-94,104-108`、`core/embedding/UniversalEmbedderProvider.kt:53-82`

**证据**：auto 模式用 `withTimeout(15_000) { buildAndProbe(..., useGpu = true) }`（`EmbeddingRuntime.kt:82-84`）。但 `buildAndProbe` 内部 `createProvider` 是**阻塞 JNI**（`UniversalEmbedderProvider` 的 `init`：`UniversalEmbedder.createFromOptions`，`UniversalEmbedderProvider.kt:81`），随后还有一次同步 `embedQuery("gpu 探针")`（`EmbeddingRuntime.kt:106`）。超时发生时协程已取消，但阻塞调用不会被中断，最终返回的 provider 既未赋给 `shared` 也未被 `close()`（异常路径 89-94 直接去建 CPU provider）。

**影响**：被放弃的 GPU 引擎持有 fd/native 内存，CPU 路径再建一份 → 峰值内存接近 2× 模型（440M 约 388MB 文件、实测 K40 Mali 编译 RSS 曾到 2.6GB）。7~8GB 机型有 OOM 风险；且泄漏实例无清理入口。**待验证**：真机上超时路径的实际 RSS 与引擎是否被 GC/finalize 回收。

**建议**（M）：把 GPU 试探放到独立单线程 executor，用 `future.get(timeout)` 控制，超时后在线程回调里 `provider.close()`；或至少保存“待清理引用”并在超时 catch 中 `runCatching { leaked?.close() }`。另建议把 probe 失败/超时原因写入 `gpuFallbackReason` 之外的持久日志（当前只在内存，重启即丢）。

---

#### P1-3 【P1】`importStaged` 单文件异常会留下“有 queued job 但无 Worker”的死局

**位置**：`core/ingest/IngestRepository.kt:44-72,75-112`、`ui/ImportScreen.kt:99-104`

**证据**：
- `if (db.ingestJobDao().activeCount() > 0) return emptyList()`（47-48）是所有导入入口的前置守卫；
- 登记阶段 `val documentIds = files.map { importFile(it) }`（57）没有 try/catch。`importFile` 内有整文件 `readBytes()`、`sha256Hex`、`renameTo/copyTo`（81-89），任一文件抛异常（磁盘满、超大文件 OOM、权限）都会中断整批，**Worker 入队（59-67）永远不执行**；
- 但异常前已登记的 source/document/job 行留在库里（91-109），于是 `activeCount() > 0` 恒成立，后续每次点“全部导入”都在 47 行静默返回 `emptyList()`，表现为“按钮没反应”。`ImportScreen.kt:99-104` 的 `importAll()` 不检查返回值，也无错误提示。
- 唯一自救路径是 UI 的取消按钮（`ImportScreen.kt:167-170` 仅在 `jobs.any { stage == "queued" }` 时出现）把 queued 置 cancelled——但用户未必知道。

**影响**：一次普通 IO 异常可让导入功能对用户“消失”，直到手动取消。与“幂等/可恢复”的设计目标相悖。

**建议**（S/M）：
1. `files.map` 改逐个 try/catch，记录失败文件并在 finally 里**确保 Worker 已入队**（只要有已登记 job 就 enqueue）；
2. `importStaged` 在 `activeCount > 0` 时不要直接返回：检查是否存在未完结 unique work，若无则补一次 enqueue（修复“有 job 无 worker”）；返回值/异常透出到 UI；
3. `importFile` 先写 DB 后移文件或失败回滚，避免“文件已移走、DB 无记录”。

---

#### P1-4 【P1】导入的文件遍历/整文件读入/SHA-256 运行在 Main 线程（ANR 风险）

**位置**：`ui/ImportScreen.kt:58,99-104`、`core/ingest/IngestRepository.kt:50-57,81-89,127`、`core/text/DocumentParser.kt:10-12`

**证据**：`ImportScreen.importAll()` 用 `scope.launch { IngestRepository(context).importStaged() }`，`scope = rememberCoroutineScope()`（`ImportScreen.kt:58,99-100`）→ Main dispatcher。`importStaged` 内 `staging.listFiles{...}`（50-56）、`importFile` 内 `renameTo/copyTo`（83-86）、`stored.readBytes()`（88）、`sha256Hex(bytes)`（289-291）全部同步执行；`DocumentParser.parse` 还会再 `readBytes()` 一次（`DocumentParser.kt:11`）。文件中没有一处 `withContext(Dispatchers.IO)`。
同文件 `exportStatus` 反而正确用了 `withContext(Dispatchers.IO)`（`ImportScreen.kt:91-92`），说明是遗漏而非取舍。

**影响**：100 篇导入时 Main 上要读约 10MB+、做 100 次 SHA-256 与大字符串分配；单文件几 MB 的 md 会直接造成秒级卡顿，StrictMode（W4-5 的 debug 防线）会持续报警；大文件 `readBytes` 无大小上限，还叠加 OOM 风险。W4-5 报告称“生产路径已在 Worker/Default”，此处与结论不符。

**建议**（S）：`importStaged()` 整体包 `withContext(Dispatchers.IO)`（一次性改一处即可覆盖文件 IO；DB 调用本来就是 suspend 且 Room 自行切线程）；`importFile` 用流式 `MessageDigest.update` + 分块读，避免整文件入内存；导入前加单文件大小上限（如 20MB）与总数上限并给出提示。

---

#### P1-5 【P1】检索的矩阵构建与全量点积跑在调用方上下文（UI 为 Main）

**位置**：`ui/SearchScreen.kt:50-51,87-88`、`core/search/SearchService.kt:151-205`（全文无 `withContext`）、`SearchService.kt:70`

**证据**：`SearchScreen` 用 `rememberCoroutineScope()`（50）→ `scope.launch { result = runCatching { service.search(query) } }`（87-88）运行在 Main。`SearchService.search` → `rankChannels` 内：`provider.embedQuery(query)` 只是嵌入部分在 Provider 里 `withContext(Dispatchers.Default)`（`UniversalEmbedderProvider.kt:92-93`），**之后的** `check(rows.all{...})`、`FloatArray(n*dim)` 分配、逐行 ByteBuffer 解码（164-170）、n×dim 双精度点积（174-180）、`sortedByDescending`（181-183）、FTS 候选二次查询与 O(n×m) `content.contains` 覆盖统计（194-199）全部在 Main。
内存峰值 = `rows` 的 n×dim×4 ByteArray **加** matrix 的 n×dim×4 FloatArray（双份），没有任何缓存或复用。

**影响**：当前 45–50 篇（约几十 chunk）无感；到 1k–10k chunk（MVP 目标“个人知识库”中期规模）时，单次检索在 Main 上分配/扫描几十 MB，必然掉帧，最坏触发 ANR；搜索期间动画/输入全部卡住。这正是 W4-5 “ANR 防护”要防的场景。

**建议**（M）：
1. `rankChannels` 整体 `withContext(Dispatchers.Default)`（纯 CPU）并把 Room 查询留在 IO；
2. 用 `FloatArray` 缓存空间矩阵（key = `spaceId + rowCount + max(rowId)`，任一变化即重建），避免每次查询重新解码 ByteArray；或至少在扫描时边读边算、不同时保留两份；
3. 排序改为 `(0 until n).sortedByDescending` 的 primitive 版本（如手写 topK 选择），避免装箱。

---

#### P1-6 【P1】`cancelImport` 与在途 Worker 的竞态：取消计数不可信、复活/悬挂两种坏状态

**位置**：`core/ingest/IngestRepository.kt:248-252`、`data/db/Daos.kt:212-213,226-231`、`IngestRepository.kt:126,132,154,178,192-194`

**证据**：
- `cancelImport` 把 **所有**活动 stage（含 in-flight 的 parsing..storing）置 cancelled（`Daos.kt:226-227`），同时把文档置 cancelled（`Daos.kt:77-78`）；但 `processDocument` 的每次进度更新都是无 stage 守卫的 `UPDATE ingest_job SET stage=:stage`（`Daos.kt:212-213`），在途任务下一次 `updateStage` 就把 cancelled 改回 embedding，继续跑到 done，并把 doc 从 cancelled 改成 ready（`IngestRepository.kt:193`）。
- 反过来，如果取消后进程被杀，cancelled job 不在 `requeueInterrupted` 的回收范围（`Daos.kt:238-239`），文档停在 cancelled，而 documents/ 下原文、可能的部分 chunk/FTS 无清理入口（`cancelImport` 只改状态不清理）。
- 注释（`IngestRepository.kt:244-247`）自称“当前处理中的文档跑完”，与真实行为（先被标 cancelled 再被覆写）不一致，W4-1 验收记录的“1 中断篇被后续取消覆盖”正是这种中间态的痕迹。

**影响**：取消后 UI 的 cancelled 计数/进度可能先显示后翻转；中断篇要么复活为 done 与用户意图冲突，要么悬挂为 cancelled 且残留数据（叠加 P0-1 会进入检索）。

**建议**（M）：
1. 取消只处理 `queued`（`cancelAllQueued` 去掉中间态），在途文档由“取消标记”列（如 `cancel_requested`）决定：跑完后置 `cancelled` 并清理 chunk/FTS/向量；
2. 或让 `updateStage` 带 `AND stage <> 'cancelled'`，并给 `processDocument` 在每批之间检查 job 是否已 cancelled 提前 `return`；
3. `requeueInterrupted` 增加 “cancelled 且文档无 ready” 的清理分支（删除残留）。

---

#### P1-7 【P1】`ReembedWorker` 全程持有全局 provider 锁且无中间进度 → 锁期间检索/导入不可用

**位置**：`core/ingest/ReembedWorker.kt:82-118,68`、`ui/SettingsScreen.kt:117-128`

**证据**：`reembedSyncAll` 在 `EmbeddingRuntime.withProvider(...) { ... }` 内完成**全部空间、全部分批**的编码与入库（82-118），`withProvider` 的 mutex 在 `EmbeddingRuntime.kt:49` 起一直持有；`SearchService.search` 与 `IngestRepository.processDocument` 同样要拿这把锁（`SearchService.kt:68`、`IngestRepository.kt:155`）。重嵌入是分钟级任务（`EMBED_BATCH=16` + 每批 `delay(200)`，114 行），期间检索会一直挂起。
`setProgress` 只在 `doWork` 完成后调用一次（68 行），而 Settings 在 `WorkInfo.State.RUNNING` 时读取 `progress.getInt("total", -1)`/`done`（`SettingsScreen.kt:119-128`）→ 运行中永远显示 -1/0。

**影响**：切换模型后自动重嵌入（`SettingsScreen.kt:308`）会“锁住”检索数分钟且无进度反馈，用户体感是搜索转圈无响应；这还会被误判为 P1-5 的 ANR。

**建议**（S/M）：把锁粒度降到“每批”：
```kotlin
for (batch in pending.chunked(EMBED_BATCH)) {
    val vectors = EmbeddingRuntime.withProvider(context, model) { p -> p.embedDocuments(batch.map { it.content }) }
    // 写库在锁外，批次间 delay(200)
    setProgress(workDataOf("done" to done, "total" to total))
}
```
同时每批 `setProgress`（done/total），让设置页有真实进度。

---

#### P1-8 【P1】失败文档没有重试/恢复路径，Worker 永远返回 success

**位置**：`core/ingest/IngestBatchWorker.kt:31-34`、`core/ingest/IngestRepository.kt:195-199`、`data/db/Daos.kt:215-216`

**证据**：循环内 `runCatching { repository.processDocument(...) }`（31-32）后 `return Result.success()`（34）——即使全部文档失败，WorkManager 也认为成功，不会退避重试；失败只落 `IngestJobEntity.stage='failed'` + error 文本（`Daos.kt:215-216`），没有任何 attempt 计数、没有把 failed 重新置 queued 的入口。UI（`ImportScreen.kt:186-190`）只展示 stage/error，无“重试”。

**影响**：模型文件暂时缺失、OOM、文件被删等**可恢复**错误都会变成永久失败；用户只能重新拷贝文件再导入（而 staging 文件已被移走，导入失败后 documents/ 下的原文不会自动回 staging）。§9.1-4 的“进程被杀可恢复或标失败”对 failed 只做了一半。

**建议**（M）：`ingest_job` 增加 `attempt`/`lastError`；对可分类的瞬态异常（`IOException`、`OutOfMemoryError` 以外的 `RuntimeException` 视情况）返回 `Result.retry()`（只重试仍有 queued 的批次）并在 `doWork` 里把 failed 且 attempt<N 的 job 重排；设置页/来源页加“重试失败导入”入口（同时清理 P0-1 的残留）。

---

#### P1-9 【P1】chunk/FTS/向量非事务写入，维度校验又在写入之后

**位置**：`core/ingest/IngestRepository.kt:134-150,155-189,186-188`

**证据**：`DELETE FTS` → `DELETE embeddings` → `DELETE chunks` → `INSERT chunks`（139-146）→ `INSERT FTS`（147-150）→ 分批 `INSERT embeddings`（173-176）是 5 组独立事务，无 `@Transaction` 包裹（对比删除路径 `Daos.kt:51-59` 有 `@Transaction`）；维度断言 `check(provider.descriptor.dimensions == expectedDim)` 在循环**之后**（186-188），此时全部向量已入库。

**影响**：任一步失败/被杀都留下不一致的派生数据（chunk 有 FTS 无向量 / 向量数与 chunk 数不符），`ftsHealthCheck` 只比 chunk 与 FTS 计数（`IngestRepository.kt:273-277`），检测不到 chunk↔向量缺失；叠加 P0-1 会让半写文档可检索。

**建议**（M）：抽 `@Transaction suspend fun replaceDocumentChunks(...)`（chunks+FTS 同事务）；向量分批写保留，但先写 `document.status='processing'` 并在全部完成后事务内置 ready；维度校验提前到首批向量（`i==0`）处，失败即 abort 并清理。`ftsHealthCheck` 扩展成 chunk↔embedding↔fts 三表计数与 doc 状态一致性检查。

---

#### P1-10 【P1】空间指纹缺 modelRevision/权重哈希：同名模型换文件会静默混用向量

**位置**：`core/embedding/EmbeddingProvider.kt:29-31,44-51`、`core/embedding/UniversalEmbedderProvider.kt:44-51`、`LiteRtLmEmbeddingProvider.kt:41-49`、`TextEmbedderProvider.kt:37-44`、`core/embedding/EmbeddingRuntime.kt:167-179`

**证据**：`EmbeddingDescriptor.modelRevision` 定义为“文件 SHA-256 前 12 位或版本号”（`EmbeddingProvider.kt:29-31`），但三个 Provider 构造 descriptor 时都**没有**给 `modelRevision` 赋值（全为 null）；`spaceIdFor` 只用 providerId/modelId/accelerator（`EmbeddingRuntime.kt:167-179`），不含 revision。模型文件按文件名放在 `filesDir/models`（`PathPreferences.kt:73-81`），用户替换同名的升级/量化版本后：旧空间继续复用，旧向量与新向量混在同一 `embeddingSpaceId` 下。

**影响**：违反 §9.1-3/§9.1-7 “同一空间必须同一模型同一模板”的硬约束；换模型版本这种常见操作会造成检索质量静默下降（无任何报错），且备份导出（`KnowledgeBackup.kt:122` 记录 taskTemplate 但不记录 revision）也无法识别。

**建议**（M）：Provider 初始化时流式计算文件 SHA-256（或读 `ModelCatalog.sha256` 比对本机文件），写入 `descriptor.modelRevision`；`spaceIdFor` 对 revision 非空且与 `embedding_meta.modelRevision` 不一致时拒绝/建新空间。工作量大头是哈希耗时（388MB 文件约 1–2s，可在 Worker/异步预热，缓存到 app_kv）。

---

### P2（次要 / 边界 / 可维护性）

#### P2-1 【P2】关键词通道按 rowid 截断候选，覆盖数重排看不到真正的相关 chunk

**位置**：`data/db/Daos.kt:121-124`、`core/search/SearchService.kt:190-200`
**证据**：`searchFtsOrdered` 用 `ORDER BY rowid`（入库顺序）而非相关性，`SearchService` 再取 `topK * CANDIDATE_FACTOR * 4`（默认 topK=10 → 120 条）。高频 bigram（如“向量”）的 chunk 若入库晚于前 120 条，就被截断，Kotlin 侧覆盖数重排无从补救。
**影响**：词频高的查询关键词召回退化，长尾文档吃亏；与 W3 评测（50 篇小库）不矛盾但会随库增长恶化。
**建议**（M）：用 FTS4 `matchinfo` + 自写覆盖分（或先取回全命中 chunkId 再在 SQL 外层 limit）；或把候选 limit 与库规模关联（如 min(候选, 总命中)），并补长尾召回测试。

#### P2-2 【P2】查询长度无上限：bigram→OR 串与 `IN (:ids)` 参数可能触 SQLite 上限

**位置**：`core/search/SearchService.kt:187-199`、`core/text/BigramTokenizer.kt:29-30`、`data/db/Daos.kt:126-127`
**证据**：查询未截断，`toOrMatchQuery` 把**全部** distinct bigram 拼成 `"a" OR "b" OR ...`；`chunksByIds(ftsIds)` 的 `IN` 参数随 `topK*12` 增长（topK 调大时）。本机 sqlite3 已验证空短语/标点 MATCH 不报错（不是注入问题），但超长 OR 表达式（数千 term）与 >999 个 SQL 变量未验证（**待验证**）。
**影响**：粘贴长文检索时可能抛 `SQLiteException: too many SQL variables`/表达式过深，UI 只显示“检索失败”；也可能明显变慢。
**建议**（S）：查询先 `take(128)` 或 bigram 去重后上限 64；`chunksByIds` 对 >500 分批查询；加超长查询用例。

#### P2-3 【P2】FTS 覆盖数统计与入库分词口径不一致，跨空格的 bigram 命中被丢弃

**位置**：`core/search/SearchService.kt:194-199` vs `core/text/BigramTokenizer.kt:10-15`
**证据**：入库/查询两侧 `bigrams()` 先 `text.replace(Regex("\\s+"), "")`（`BigramTokenizer.kt:11`），所以英文“machine learning”会生成跨空格 bigram（如 “nl”）；FTS 命中后重排却用**原始 content** 做 `content.contains(g)`（`SearchService.kt:196`）→ 只在 FTS 侧成立的跨空格 bigram 在 Kotlin 侧计 0。若一个 chunk 恰好只靠这类 bigram 命中，会被 `filter { it.second > 0 }`（198）直接丢掉。
**影响**：英文/中英混排查询的关键词召回损失（评测集 en/mix 仅 8/16 条，掩盖了该问题）。
**建议**（S）：重排用同一规范化串（预先 `val normalizedContent = content.replace(Regex("\\s+"), "")`），或直接用 `BigramTokenizer.bigrams(content)` 的交集计数；补 en/mix 单测。

#### P2-4 【P2】元数据 `accelerator`/`quantization`/`taskTemplate` 未按真实情况写入

**位置**：`data/db/Entities.kt:80-83`、`core/ingest/IngestRepository.kt:207-221`、`core/embedding/EmbeddingRuntime.kt:133-147`、`EmbeddingRuntime.kt:167-179`、`ui/SettingsScreen.kt:603`
**证据**：`ensureSpace`/`registerSpace` 构造 `EmbeddingMetaEntity` 时未传 `accelerator`（恒默认 `"cpu"`）、未传 `quantization`（恒 `"f32"`）；`taskTemplate` 仅当 `spaceId == SPACE_ID || spaceId == SPACE_ID_C` 时写 `app-prefix-v1`，否则 `raw-symmetric`（`IngestRepository.kt:216-217`）——而 GPU/NPU 的 440M、740M 等空间实际走 `UniversalEmbedderProvider` 的 `QUERY_PREFIX`/`DOCUMENT_PREFIX`（`UniversalEmbedderProvider.kt:33-34`），即 app-prefix，元数据却记 raw-symmetric；`SPACE_ID_C` 在 `spaceIdFor` 里**没有任何分支会产出**（`EmbeddingRuntime.kt:167-179`），是死常量，Settings 还按其硬编码 768（`SettingsScreen.kt:603`）。
**影响**：§9.1-3 空间指纹的“唯一事实源”在元数据层失真；备份导出 `taskTemplate`/accelerator（`KnowledgeBackup.kt:122,136`）会把错误信息带到跨设备导入；未来 int8 路径没有 `quantization` 过滤（`Daos.kt:158-162` 不筛）。
**建议**（S/M）：由 `descriptor.queryTaskTemplate`/`documentTaskTemplate`/`accelerator` 直接生成 meta 字段（不要二次推断）；删除或接回 `SPACE_ID_C`；`vectorsForSpace` 增加 `quantization` 过滤参数。

#### P2-5 【P2】文档元数据 `parserId` 恒为 “pending”、`sizeBytes` 恒为 0

**位置**：`core/ingest/IngestRepository.kt:97-105`、`data/db/Entities.kt:38-40`、`Daos.kt:73-74`
**证据**：`importFile` 写 `parserId = "pending"`（注释称“解析器在 processDocument 时确定”，102 行），但 `processDocument` 只 `updateStatus`（`Daos.kt:73-74`）从不回写 parserId；`DocumentEntity.sizeBytes`（schema v2，注释“导入时写入”，`Entities.kt:39-40`）在 `importFile` 中也未赋值（此时 `bytes` 已在 88 行算出）。
**影响**：来源页/备份里的解析器与大小信息永久失真；`DocumentEntity.sizeBytes` 为 0。
**建议**（S）：`importFile` 补 `sizeBytes = bytes.size`；`processDocument` 解析后新增 DAO 更新 parserId（或 importFile 直接按扩展名预判）。

#### P2-6 【P2】`document.status` 与 `ingest_job.stage` 双状态机无一致性约束

**位置**：`data/db/Entities.kt:38,142`、`data/db/Daos.kt:73-78,212-239`
**证据**：两套状态各自独立更新（`updateStatus`/`updateStage`），没有事务/约束保证组合合法性；实际可出现 `document=cancelled + job=done`（P1-6）、`document=processing + job=failed`、`document=ready + job=cancelled` 等组合；UI 展示用 job（`ImportScreen.kt:186-190`），来源页用 document，两处可能不一致。
**影响**：状态语义漂移，后续加“重试/清理”逻辑时难以判断事实源；也是 P0-1/P1-6 的温床。
**建议**（M）：选定 job 为唯一事实源（document.status 由 job 事务性派生），或加 Room `@Transaction` 的组合更新函数 + 单测枚举非法组合。

#### P2-7 【P2】Chunker 硬切可能劈开代理对（emoji/生僻字），Bigram 窗口同样产生孤立 surrogate

**位置**：`core/text/Chunker.kt:53-60,120-125`、`core/text/BigramTokenizer.kt:10-15`
**证据**：超长块硬切 `minOf(s + options.maxChars, b.end)` 与 `substring(s, e)` 都按 UTF-16 **char** 计（`Chunker.kt:57-59`），边界落在 surrogate pair 中间时 chunk 会含孤立高/低代理；`bigrams()` 的 `substring(i, i + 2)`（`BigramTokenizer.kt:15`）在 `"a😀"` 上同样会产出 `"a\uD83D"` 这类非法串。`estimateTokens` 把 surrogate（code > 0x2E80）按 CJK 计（`Chunker.kt:123`）。现有测试无 emoji/代理对用例。
**影响**：嵌入输入被 tokenizer 替换为 U+FFFD、展示端出现“�”；charStart/End 仍满足 substring 不变量但内容已损坏。个人笔记含 emoji 很常见。
**建议**（S）：硬切边界 `if (e in 1 until b.end && text[e-1].isHighSurrogate()) e--`（或在 code point 边界回退 1 char）；`bigrams` 用 code point 迭代；补 emoji/代理对单测（`assertFalse(chunk.content.any { it.isHighSurrogate() && ... })`）。

#### P2-8 【P2】检索 query 未做与入库一致的文本规范化（NFC/换行）

**位置**：`core/search/SearchService.kt:61-62,158,187`、`core/text/TextNormalizer.kt:10-15`
**证据**：入库侧对解析文本做 NFC + CRLF 归一（`TextNormalizer.normalize`，`IngestRepository.kt:128`），检索侧只 `query.trim()`（`SearchService.kt:61`），embedding/FTS 都直接用原串。macOS 粘贴的 NFD 文本（é = e+◌́）与 NFC 文本 bigram 不同、向量也可能不一致。
**影响**：同一查询换输入法/来源后关键词与向量都失配，表现为“明明有却搜不到”。
**建议**（S）：`val q = TextNormalizer.normalize(query).trim()`，两处（向量/FTS）共用；补 NFC/NFD 用例。

#### P2-9 【P2】分块无重叠：答案跨块即被截断（设计选择，但需显式记录风险）

**位置**：`core/text/Chunker.kt:33-74`（无 overlap 参数）
**证据**：注释声明对齐 zleap-sag 的 `overlap=0`（1-9 行），实现无重叠窗口。`charStart/End` 不变量与 `ordinal` 唯一约束（`Entities.kt:45`）都依赖“不重叠”，加 overlap 会牵动 schema 与去重。
**影响**：跨块边界的答案在检索时只能命中半句；这是 W3 评测 R@10=1.0 也测不出的场景（评测卡普遍短于 600 字）。
**建议**（M）：作为 Phase 2 事项显式记录；若加 overlap，建议只重叠 1 个句子/约 50 字，并允许 chunk 区间重叠（去掉 ordinal 唯一约束或改 `(documentId, ordinal)` 语义）。

#### P2-10 【P2】`chunkerFor(pathB)` 分支等价于默认，属死代码/误导

**位置**：`core/ingest/IngestRepository.kt:281-282,303-305`、`core/text/Chunker.kt:19`
**证据**：`PATH_B_MAX_CHARS = 600` 与 `Chunker.Options.maxChars` 默认 600 相同；`chunkerFor(pathB)` 对 A/B 产生同样配置，`isPathBModel` 调用没有行为差异。
**影响**：维护者会误以为 A/B 有不同块长策略；未来改一处忘另一处。
**建议**（S）：要么删除分支并注释“路径 A/B 块长暂统一 600”，要么把 600 收敛到单常量（`ChunkOptions`），由 `findModel` 显式传入。

#### P2-11 【P2】`EmbeddingRuntime.shutdown()` 不加锁，存在 use-after-close 竞态

**位置**：`core/embedding/EmbeddingRuntime.kt:184-190`
**证据**：`shutdown()` 直接 `shared?.close(); shared = null`，不获取 `mutex`；若与 `withProvider` 的 block 并发（基准代码与 UI 同时运行、或未来在 onDestroy 调用），block 正在使用的引擎会被关闭。当前生产代码只有 benchmark 调用 `shutdown()`（`W4PlusRunner.kt:44,128`、`ProductionBenchRunner.kt:57`），风险低但 API 面是错的。
**影响**：真机基准与 UI 并发时可能崩溃/异常 close；不该由调用方承担。
**建议**（S）：`shutdown` 改为 `mutex.withLock { ... }` 的 suspend 函数（或至少加 `@Synchronized` 并标记仅供测试）。同时给 `withProvider` 的 block 加“引擎已关闭即重建一次”的自愈。

#### P2-12 【P2】`chunk_embedding` 无外键，重嵌入与导入并发可产生孤儿向量并污染候选名额

**位置**：`data/db/Entities.kt:86-101`、`core/ingest/ReembedWorker.kt:64-65,80,94-112`、`core/search/SearchService.kt:118-119`
**证据**：`chunk_embedding.chunkId` 无 `ForeignKey` 声明；重嵌入只在开始时查一次 `activeCount()`（`ReembedWorker.kt:64,80`），随后按**开场时**的 `pending` 列表（94）逐批写入；期间导入可能 `deleteByDocument`+重新插入 chunk（新 id）或删除 source。写回已删除的 chunkId 就成了孤儿行；`vectorsForSpace` 不 join chunk（`Daos.kt:158-162`）会把它当候选扫描，`buildHits` 又用 `mapNotNull` 静默丢弃（`SearchService.kt:118-119`）→ 候选名额浪费、`vectorCandidates` 统计虚高。
**影响**：并发窗口较窄（**待验证**真机复现难度），但一旦发生是永久残留（无清理入口），且 W4+ 切模型后大家都跑重嵌入，概率上升。
**建议**（M）：`chunk_embedding` 加 `ForeignKey(chunk, onDelete = CASCADE)`（需 schema v4 迁移）；重嵌入每批写前重查 `activeCount`/chunk 存在性；`vectorsForSpace` 改 INNER JOIN chunk；`ftsHealthCheck` 增加向量孤儿检查。

#### P2-13 【P2】异常/错误信息分类不足（通用 `check` + 字符串拼接的失败原因）

**位置**：`core/ingest/IngestRepository.kt:186-188,197`、`core/search/SearchService.kt:160-162`
**证据**：维度不符用 `check(...)` 抛 `IllegalStateException`（Ingest 186-188、Search 160-162），`markFailed` 只存 `"ClassName: message"`；UI 侧 `SearchScreen.kt:88-96` 与 `ImportScreen.kt:186-190` 直接展示 message。`SearchService.kt:160` 的 blob 尺寸校验失败时，用户看到的是“vector blob 尺寸与空间维度不符（dim=768）”，无可操作建议。
**影响**：可诊断性一般；错误分类缺失使 P1-8 的“哪些失败可重试”无法实现。
**建议**（S）：抽 `sealed class PipelineError`（模型缺失/文件损坏/OOM/空间不匹配/取消）落到 `job.error` 的结构化字段；UI 按类型给“重试/换模型/重导入”按钮。

#### P2-14 【P2】测试空白：状态机/检索/运行时 0 覆盖，边界用例缺失

**位置**：`app/src/test/java/com/zhibox/memory/core/**`（3 文件 34 @Test）、`app/src/androidTest/.../MigrationTest.kt`
**证据**：现有测试只覆盖 TextNormalizer/Bigram/Chunker/DocumentParser（`TextLayerTest.kt`，18 个）、CandidateFusion（4 个）、ModelCatalog（12 个）；`IngestRepository`、`IngestBatchWorker`、`ReembedWorker`、`EmbeddingRuntime`、Provider、`SearchService`（含 FTS 覆盖重排）无单测；Chunker 无 emoji/代理对/CRLF、Bigram 无长查询/空格混排、Search 无“失败文档不应命中”用例。
**影响**：P0-1/P0-2/P1-10 这类回归没有任何防线；而 `CandidateFusion` 之所以稳定恰恰因为有 4 条纯函数测试——正说明可测性改造有效。
**建议**（L）：把 `rankChannels` 抽成依赖注入（`VectorSource`/`FtsSource` 接口）后可纯 JVM 测试；导入状态机用 Robolectric + in-memory Room 测“失败清理/取消恢复/重复导入”；补 Chunker/Token 边界表驱动用例。优先补 P0-1 与取消语义 2 条集成测试。

## 做得好的地方

1. **幂等策略明确且注释了取舍**：`processDocument` 开头清旧 chunk/FTS/向量（`IngestRepository.kt:130-137`），“重做而非续做”的决策和理由写在 32-34 行；`requeueInterrupted` 回收进程被杀留下的中间态（`Daos.kt:233-239` + `IngestBatchWorker.kt:24-26`），这个坑被显式处理过。
2. **历史根因修复质量高**：双入队防护与 AppleDouble 过滤（`IngestRepository.kt:45-56`）、命名参数修 DocumentEntity 位置错位（94-106 注释）、GPU/NPU 空间后缀隔离（`EmbeddingRuntime.kt:160-179`）——都能从注释直接读出“为什么”，对后续维护价值很大。
3. **删除级联是五表单事务**（`Daos.kt:50-59`），且 `deleteSource` 拒绝导入进行中（`IngestRepository.kt:233-241`），方向正确（竞态见 P2-12 但骨架对）。
4. **引擎统一运行时**：进程单例 + 协程 `Mutex` 串行（`EmbeddingRuntime.kt:23-101`）把“1.4GB 模型只允许一份 + 引擎非线程安全”收敛到单一入口，导入/检索/基准共用，避免了三处各自建引擎的常见事故。
5. **数值层严谨**：float32 LE 编解码两侧对称（`IngestRepository.kt:284-287` / `SearchService.kt:166-170`），L2 normalize 交给 Provider（`UniversalEmbedderProvider.kt:79`），逐条 finite 校验 + 维度漂移检测（`UniversalEmbedderProvider.kt:98-106` 等），维度校验放在首次推理后（`IngestRepository.kt:184-188` 注释记录踩坑）。
6. **检索设计合理且有评测闭环**：精确扫描 + bigram FTS + RRF(K=60) 的组合有 ADR 依据（G3/G4），`CandidateFusion` 是纯函数且有 4 条单测（含并列稳定排序），W2/W3 用 220/262 条评测集验证到 R@10=1.0——测试策略（纯逻辑单测 + 无头评测）本身是对的，只是没覆盖状态机与数据一致性。
7. **FTS 可恢复性**：`rebuildFtsIndex` 保证 bigram 索引是 chunk.content 的纯派生数据（`IngestRepository.kt:259-270`），`ftsHealthCheck` 提供最小诊断；这为 P0-1 的“先发布后检索”改造留了好基础。
8. **中文注释与文档同步**：W2/W3/W4 验收记录能与代码逐项对上（取消/重嵌入/FTS 恢复/迁移），这种“文档-代码-实测”三位一体的习惯在个人项目里非常难得。

---

*说明：本轮未运行 gradle/设备命令（遵守审查约束），所有结论基于源码通读 + 调用方 grep + 本地 sqlite3 的 FTS4 行为验证；标注“待验证”的条目需真机/基准复现。审查只读，未修改 app 源码。*
