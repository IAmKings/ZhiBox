# ZhiBox 代码质量审查报告

- **审查对象**：ZhiBox 端侧个人知识记忆 MVP（Android / Kotlin / Compose / Room / MediaPipe-LiteRT / WorkManager）
- **审查基线**：`main` @ `9ca20d6` + 工作区未提交改动（18 个已跟踪文件改动 +157 个未跟踪文件，2026-10-17）
- **审查日期**：2026-10-17
- **审查方式**：4 个并行只读审查小组（数据层 / 核心管线 / UI 层 / 工程基建与安全）+ Lead 架构与跨层复核；全程未修改任何产品源码
- **验证手段**：Gradle 编译、单元测试、androidTest 编译、Android Lint、Git/Gradle 元数据检查、APK 产物检查
- **结论一句话**：**这是一个"研究功底扎实、架构骨架清晰、工程化与数据安全边界尚未收口"的 MVP——技术决策与文档质量显著高于平均水平，可继续演进；但不能按当前状态交付，需先修 3 个 P0 与 10 类 P1 问题。**

---

## 0. 结论速览

### 0.1 质量评级

| 维度 | 评级 | 依据 |
|---|---|---|
| 架构与模块边界 | **B+** | 五层结构（ui / core / data / benchmark / model）清晰；引擎单例、空间指纹、导入状态机是好设计；但 `ingest ↔ embedding` 存在循环依赖与模型路由启发式 |
| 算法与数值严谨性 | **A−** | bigram-FTS + 精确向量扫描 + RRF 有 G1–G4 实测依据；float32 LE、L2 归一化、维度/有限值校验齐全 |
| 可读性与注释 | **B+** | 中文注释记录"为什么"（实测根因、取舍），同类项目少见；缺点是部分注释已与实现脱节、少量死代码/死常量 |
| 正确性与错误处理 | **C+** | 主链路可跑通，但有"失败/取消留下半成品并可被检索""备份恢复丢向量"等正确性洞；异常分类粗、无日志 |
| 并发与生命周期 | **C** | Mutex 串行化引擎方向正确，但存在重建失败不可恢复、超时泄漏、锁粒度分钟级、WorkManager 入队竞态 |
| 性能上限 | **C+** | 当前 45–50 篇语料无感；检索每次全表载入向量 + 主线程扫描，扩库后必然劣化 |
| 测试与质量门禁 | **C−** | 34 个 JVM 单测全绿，但只覆盖纯逻辑；无 CI、无静态检查，**当前 `lintDebug` 本身失败（6 error）** |
| 安全与隐私 | **C−** | 无日志泄漏、下载链有 SHA-256 是亮点；但 `allowBackup=true`、模型校验链断裂、实验后门可被外部触发 |
| 发布工程 | **D** | release 无签名、无 release 产物；交付物是 `application-debuggable` 的 62.4MB debug APK；37.6% 主源码是实验代码 |

### 0.2 本次实测验证结果

| 检查 | 命令 | 结果 |
|---|---|---|
| Debug 编译 | `./gradlew :app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| 单元测试 | `./gradlew :app:testDebugUnitTest --rerun` | ✅ 8 个测试类 / **34 用例 / 0 失败** |
| 仪器测试编译 | `./gradlew :app:compileDebugAndroidTestKotlin` | ✅ 编译通过（MigrationTest 需 arm64 真机执行） |
| **Android Lint** | `./gradlew :app:lintDebug` | ❌ **BUILD FAILED：6 errors / 19 warnings / 6 hints**。6 个 error 均为 `NewApi`：`benchmark/VectorScanBenchmark.kt:114,115,177` 使用 `java.nio.channels.FileChannel` / `StandardOpenOption`（需 API 26，minSdk 24） |
| Release 产物 | `find app/build/outputs -name "*.apk"` | ❌ 只有 debug APK 与 androidTest APK，**无 release APK** |
| 交付物 | `dist/MVP-1/zhibox-mvp1-debug.apk` | ⚠️ 62,371,642B，与 `app-debug.apk` 同尺寸；`application-debuggable`，无混淆/无资源裁剪 |
| Room schema 入库 | `git ls-files app/schemas` | ⚠️ 仅 `1.json` 被跟踪，**2.json / 3.json 未入库**（迁移测试在 clean clone 必失败） |

### 0.3 Top 风险（按优先级）

| # | 风险 | 级别 | 一句话 |
|---|---|---|---|
| 1 | 备份/恢复链路两个数据丢失缺陷 | **P0** | 导入先清库后非事务写入（失败即清空知识库）；导入不恢复 `embedding_meta`（恢复后再导出静默丢掉全部向量） |
| 2 | 主线程重活 | **P0** | 检索的向量全量解码/点积、首次模型加载、导入的文件遍历/整文件哈希都在 Main 线程 → jank/ANR |
| 3 | 实验后门与发布通道缺失 | **P0** | 实验/诊断代码占主源码 37.6%，首页有入口，exported Activity 可被任意 App 用 Intent 触发删来源/清库等破坏性 runner；无 release 签名、无 release 产物 |
| 4 | 检索一致性缺口 | **P1** | 检索不按 `document.status` 过滤、写入非事务、失败不清理 → 失败/取消/导入中的半成品可被搜到 |
| 5 | 取消与失败不可恢复 | **P1** | `catch(Throwable)` 把 `CancellationException` 当业务失败；失败文档无重试入口；引擎重建失败后永久 `provider already closed` |
| 6 | 模型/空间元数据失真 | **P1** | `modelRevision` 恒 null、`accelerator`/`quantization` 恒默认值、`SPACE_ID_C` 是永不产出的死常量；同名模型换文件会静默混用向量 |
| 7 | 隐私与供应链 | **P1** | `allowBackup=true` 使个人知识库进 Android Auto Backup；SAF 启用/备份导入只比文件长度不校验 SHA-256；`verified` 验证门只写不读 |

---

## 1. 项目与审查画像

### 1.1 代码规模

| 项目 | 实测值 |
|---|---|
| 主源码 | 43 个 `.kt` / **7,211 行**（其中 benchmark 包 1,969 行 + `BenchmarkScreen.kt` 743 行 = **37.6%** 为实验代码） |
| 数据层 | `AppDatabase` 95 / `Entities` 161 / `Daos` 261 / `KnowledgeBackup` 350 行；13 表 / 13 索引 / 0 外键 / 46 个 `@Query` / **全仓库仅 1 个 `@Transaction`** |
| 核心管线 | 11 个主文件 1,523 行（ingest / embedding / search / text） |
| UI 层 | 6 屏 1,925 行；**64 个 `mutableStateOf`，全部 `remember`**；`ViewModel` / `rememberSaveable` / `BackHandler` / `NavHost` 全仓 0 命中 |
| 测试 | 4 文件 / 587 行 / **37 用例**（34 JVM + 3 instrumentation）；`IngestRepository`、`SearchService`、`EmbeddingRuntime`、`KnowledgeBackup` **0 覆盖** |
| 依赖 | 18 version / 22 library / 4 plugin；AGP 8.13.2 / Kotlin 2.4.21 / Gradle 8.14.3 / Room 2.8.5 |
| 工程脚本 | 11 个 `.sh` / 796 行（11/11 `set -euo pipefail`）+ 2 个 `.py` / 536 行 |
| Git 状态 | tracked 117 文件 / untracked 157 文件；`.git` 仅 2.4MB（无大二进制历史） |
| Lint | **6 errors / 19 warnings / 6 hints**（含 8 条依赖过期提示、6 条 Compose `AutoboxingStateCreation`） |

### 1.2 审查覆盖

- **数据层**（数据层审查 264 行 / 21 条）：schema v1→v3、迁移测试、DAO、备份恢复、事务与索引。
- **核心管线**（核心管线审查 392 行 / 26 条）：导入状态机、WorkManager、嵌入运行时、数值正确性、检索融合、文本边界。
- **UI 层**（UI 层审查 328 行 / 23 条）：状态管理、导航、MainActivity 实验入口、Compose 反模式、资源与可访问性。
- **工程基建**（工程基建审查 289 行 / 18 条）：构建/依赖/发布、权限与安全、模型管理、测试、脚本、仓库卫生。
- **Lead 复核**：架构分层与循环依赖、跨层调用链、本次实测构建/Lint/单测、关键 P0 交叉验证（备份 meta、主线程、运行时生命周期、`SPACE_ID_C` 死常量等）。

> 详细证据（file:line、代码引用、影响、修复建议、工作量）见 `docs/review/` 下四份分领域报告，本文只做去重、定级与排序。

---

## 2. P0 级问题（发布/交付前必须修复）

### P0-1 备份/恢复链路的两个数据丢失缺陷

**P0-1a 导入是"先清库、后解析写库"的非原子流程**
- 位置：`core/backup/KnowledgeBackup.kt:284-348`（清库 `:296-297`、逐表写入 `:299-347`、向量二进制解析 `:327-346`）
- 证据：`db.clearAllTables()` 之后才开始构造实体与解析向量；全仓库唯一事务在 `Daos.kt:51`，本流程无事务。清库后仍可能抛异常的点很多：`ArrayList<ChunkEmbeddingEntity>(count)`（损坏头可致 OOM）、`ByteArray(len)` / `buf.get`（负数长度/下溢）、JSON 缺字段、UNIQUE 冲突、SQLITE_BUSY。外层仅 `runCatching` 返回 `Result.failure`，**失败时用户库已被清空且无回滚**。
- 影响：一次损坏/截断的备份包、空间不足或导入中进程被杀 = 既有知识库全部丢失（源文件还在 `documents/`，但 chunk/向量/FTS 全无）。
- 建议：① `readAll` + 完整语义校验提前到清库前（校验 `vectorBlob.size == dimensions*4`、`chunkId ∈ chunkIds`、`count` 与剩余字节一致）；② 写库放进单事务（`db.withTransaction` 内用显式 `DELETE FROM` 代替 `clearAllTables`）；③ 导入前自动导出一份 `cacheDir/auto-backup-<ts>.zip` 快照。
- 工作量：**M（0.5–1 人日）**

**P0-1b 导入不恢复 `embedding_meta` → 恢复后再导出静默丢失全部向量**
- 位置：`KnowledgeBackup.kt:113-124`（导出只写 meta 子集）、`:163-179`（导出向量时按 meta 过滤）、`:284-348`（导入全流程无 `upsertMeta`）；验收要求 `docs/phase1/MVP1-ACCEPTANCE.md:60-61`
- 证据：导入后 `embedding_meta` 为空表 → 再次导出时 `metas = []` → `spaceIdx` 为空 → 所有向量被 `:169` 静默跳过，而 `:179` 仍返回 `vectors.size` 给 UI 报"已导出 N 条向量"；同时 `model_requirements.json` 为空，后续导入不再做模型在位校验。
- 影响：备份→恢复→再备份 = 向量全丢且无任何报错。当前检索用 `spaceIdFor(provider)` 实时计算空间，掩盖了该问题；W4+ 回环验证未覆盖"恢复后再导出"，因此漏检。
- 建议：导出 `meta.json` 写完整 `EmbeddingMetaEntity` 字段（含 `accelerator/quantization/modelRevision/normalization/active`）；导入时逐条 `upsertMeta`；把"meta 为空但向量非空/向量命中不了空间"从静默 skip 改为显式报错；补 `导出→导入→再导出` 回环测试，断言第二次包内 vectors 头 count 与 meta 空间数不降。
- 工作量：**M（0.5–1 人日）**

### P0-2 主线程重活：首次模型加载、向量扫描、导入编排（ANR 风险）

- 位置与证据：
  1. **检索路径无任何 `withContext`**：`ui/SearchScreen.kt:50,87-98` 用 `rememberCoroutineScope()`（Main）调用 `core/search/SearchService.kt:59`；`SearchService` 全文 0 处 `withContext`。`rankChannels`（`SearchService.kt:151-205`）在 Main 上执行：`FloatArray(n*dim)` 分配、全部 BLOB 反序列化（10k×768d ≈ 30MB）、n×dim 双精度点积（10k×768×2 ≈ 1,536 万次乘法）、`sortedByDescending`、FTS 候选重排。
  2. **首次检索会在 Main 上构建嵌入引擎**：`EmbeddingRuntime.withProvider`（`EmbeddingRuntime.kt:44-101`）无 `withContext`，首次调用时 `createProvider` 在调用方线程执行 `ParcelFileDescriptor.open` + `UniversalEmbedder.createFromOptions`（模型加载，秒级）；auto GPU 模式还包含 `withTimeout(15s)` 的 GPU 试探（`EmbeddingRuntime.kt:80-94`）。
  3. **导入编排在 Main 上做文件 IO**：`ui/ImportScreen.kt:96-104` → `core/ingest/IngestRepository.kt:44-83` 的 `listFiles` / `renameTo` / `copyTo` / `stored.readBytes()` / `sha256Hex` 均无 `withContext(IO)`。
- 对照：`Provider.embedDocuments/embedQuery` 内部有 `withContext(Dispatchers.Default)`（如 `UniversalEmbedderProvider.kt:84-93`），说明团队知道要切线程——**遗漏恰好在编排层（Orchestration）**。项目自己在 `MainActivity.kt:40-50` 加的 debug StrictMode ANR 防线，会持续命中这些路径。
- 影响：当前 45–50 篇评测库掩盖了问题；一旦库到 1k–10k chunk 或首次检索（模型加载），主线程出现秒级阻塞 → 掉帧、输入延迟、低端机 ANR。这是产品主流程，不依赖实验屏。
- 建议（S，1 处修复收益最大）：
  ```kotlin
  // SearchService.search / rankChannels 外层
  suspend fun search(...) = withContext(Dispatchers.Default) { /* 现实现 */ }
  // EmbeddingRuntime.withProvider：把 buildAndProbe / block 放 Dispatchers.Default（Mutex 仍串行）
  // IngestRepository.importStaged / importFile：withContext(Dispatchers.IO)
  ```
  并在 UI 侧保持状态赋值在 Main；用 StrictMode + `VectorScanBenchmark` 复测主线程耗时。
- 工作量：**S（0.5 人日）**

### P0-3 实验/诊断能力无门禁 + release 通道缺失，交付物是 debuggable debug 包

- 位置与证据：
  - 实验代码在 **main** sourceSet：`benchmark/` 13 文件 1,969 行 + `ui/BenchmarkScreen.kt` 743 行（占主源码 37.6%）；`app/build.gradle.kts:46-49` 只有 androidTest assets 配置，无 debug sourceSet/Flavor 隔离。
  - 首页固定渲染"实验/诊断"按钮（`MainActivity.kt:205`）；`MainActivity` 的 20 个 `autostart_*` extra（`:51-70,134-153`）直接进入实验/导入/评测流程；`MainActivity` 是 `exported="true"`（`AndroidManifest.xml:26-34`）且无 `BuildConfig.DEBUG`/`FLAG_DEBUGGABLE` 门禁 → **任意已安装应用可 `am start -n com.zhibox.memory/.MainActivity --ez autostart_delete_check true` 等触发删来源（`BenchmarkScreen.kt:550-561` `DeleteCascadeRunner`）、FTS 破坏重建、清库等破坏性操作**。
  - Release 通道：`app/build.gradle.kts:27-35` 无 `signingConfig`、`isMinifyEnabled = false`；`app/build/outputs` 无 release APK；`dist/MVP-1/zhibox-mvp1-debug.apk` 经 aapt2 实测为 `application-debuggable`（62.4MB），即 **MVP-1 交付物是 debug 包**。
  - 实验依赖随包分发：`jieba-analysis` 仅被 benchmark 使用却 `implementation`（`app/build.gradle.kts:94-95`），已知被 G3 淘汰仍进 release。
- 影响：可维护性（新增一个实验改 4 处、19 个 Boolean 参数）之外，更严重的是**发布包带后门与破坏性入口**；debug 包允许 `run-as`/调试器访问私有目录（个人知识库），无混淆、无资源裁剪、体积不可控。
- 建议（M，1–2 人日）：
  1. `benchmark/` + `BenchmarkScreen` 迁入 `app/src/debug/java`，main 只留空实现的诊断入口接口；首页按钮与 `autostart_*` 分支按 `FLAG_DEBUGGABLE` 门禁。
  2. 20 个 Boolean extra 收敛为单一 `EXTRA_RUN_MODE: String` + runner 注册表；autostart 一次性消费（`savedInstanceState == null`），避免 Activity 重建重放（见 P1-8）。
  3. 配置 release 签名（keystore 走 `gradle.properties`/环境变量，不入库）、开启 R8 + `shrinkResources`；补 MediaPipe/LiteRT JNI 的 keep 规则；交付物改为 release 包并记录 commit。
- 工作量：**M（1–2 人日）**

---

## 3. P1 级问题（一个迭代内处理，共 10 类）

### P1-1 检索与写入的一致性：半成品文档可被检索

- 位置：`core/search/SearchService.kt:70,190`、`data/db/Daos.kt:158-162,121-124`、`core/ingest/IngestRepository.kt:134-150,155-189,186-188,195-199`
- 证据：向量通道 `vectorsForSpace` 与关键词通道 `searchFtsOrdered` 都**不 join `document.status`**；写入顺序是"先 chunk+FTS → 再向量 → 最后 ready"；维度校验又在向量入库**之后**；`catch` 只改状态、不清理已写入数据。于是：① 维度校验失败时 chunk+FTS+全部向量已落库、文档标 failed，检索仍能命中；② 导入过程中 chunk+FTS 在取引擎锁前写入，检索可在窗口期命中；③ 批量导入时窗口大量存在。
- 影响：用户看到"来源页显示失败，但搜索能搜到"的幽灵证据；evidence 回溯指向 failed 文档；坏维度向量会让**整个空间**所有查询在 `check` 处抛错（`SearchService.kt:160-162`），用户无自愈入口。
- 建议（S/M）：两条 SQL 增加 `JOIN chunk JOIN document ... AND d.status='ready'`；导入写路径改为"全量写完后再原子置 ready"（或加 `is_published` 标记）；每批向量 insert 前先校验维度/有限值；失败分支删除该文档的 chunk/FTS/向量；补"failed 文档必须 0 命中"集成测试。
- 工作量：**M（1 人日）**

### P1-2 取消与失败语义：中断被当失败、失败不可重试、取消计数不可信

- 位置：`IngestRepository.kt:195-199`、`IngestBatchWorker.kt:31-34`、`Daos.kt:212-213,226-231,238-239`、`IngestRepository.kt:248-252`
- 证据：Provider 内逐条 `ensureActive()`（如 `UniversalEmbedderProvider.kt:87`），WorkManager 停止 Worker 时抛 `CancellationException`；`catch (t: Throwable)` 不区分类型，先把文档/job 标 failed 再抛；Worker 又用 `runCatching` 吞掉取消继续循环。`cancelImport` 把在途 stage 也置 cancelled，但 `processDocument` 的 `updateStage` 无 stage 守卫，下一次进度更新会把 cancelled 改回 embedding 并最终置 ready；反之若取消后进程被杀，cancelled 不在 `requeueInterrupted` 回收范围，任务悬挂。失败是终态（无 attempt 计数、无重试入口），Worker 恒返回 `Result.success()`。
- 影响：取消/系统停止会永久误标 failed 并残留半成品（叠加 P1-1 可检索）；瞬态错误（模型暂缺、OOM、文件被删）变成永久失败，用户只能重拷文件重导。
- 建议（S/M）：`catch (CancellationException) → 重排队（stage 回 queued）并重新抛出`；`catch (Exception) → failed + 清理`；`catch (Error) → 直接抛`；Worker 区分取消与普通失败；`ingest_job` 加 `attempt/lastError`，对可恢复错误返回 `Result.retry()`，来源页加"重试失败导入"；取消语义改为"只取消 queued + 在途文档跑完后清理并标 cancelled"。
- 工作量：**M（1 人日）**

### P1-3 EmbeddingRuntime 生命周期缺陷：一次失败即永久不可用 / 超时泄漏引擎

- 位置：`core/embedding/EmbeddingRuntime.kt:52-54,80-94,104-108,184-190`
- 证据：① 重建分支先 `shared?.close()` 但未置空，随后 `buildAndProbe` 抛异常时 `shared` 仍指向已关闭实例且 `sharedKey` 已是新 key → 下次调用走"缓存命中"，每次 `block(shared!!)` 都在 Provider 内被 `check(!closed)` 打回，表现为**全应用检索/导入永久 `provider already closed`**，只能杀进程。② auto GPU 试探用 `withTimeout(15s)` 包住阻塞 JNI，超时后协程取消但阻塞调用无法中断，构建出的 GPU provider 既未赋值也未 close → 与随后 CPU 引擎同时驻留，峰值内存接近 2× 模型。③ `shutdown()` 不加锁（benchmark 会调用），与 `withProvider` 并发存在 use-after-close。
- 建议（S）：重建前 `val old = shared; shared = null; sharedKey = null; runCatching { old?.close() }`，构建失败保持空并提供"下一次调用自愈重试一次"；GPU 试探放独立单线程 executor 用 `future.get(timeout)`，超时回调中 close；`shutdown` 改 suspend + mutex。
- 工作量：**S（0.5 人日）**

### P1-4 导入/重嵌入的并发死局与锁粒度

- 位置：`IngestRepository.kt:44-72,75-112`、`IngestBatchWorker.kt:24-34`、`ReembedWorker.kt:82-118,68`
- 证据：
  1. `importStaged()` 用 `files.map { importFile(it) }` 登记文件，`importFile` 内可能抛异常（磁盘满、超大文件 OOM、权限）；异常时已登记的 job 留在库里，而**入队 Worker 的代码永远不执行**；`activeCount() > 0` 守卫又让后续每次"全部导入"静默返回空 → 导入功能"消失"，只能手动取消队列自救。
  2. WorkManager 入队与 Worker 循环存在丢唤醒窗口：Worker 刚 `nextQueued() == null` 准备退出时，新 job 落库并 `beginUniqueWork(KEEP)` 看到旧 worker 仍 RUNNING 而忽略入队 → job 永久 queued（叠加守卫，导入功能卡死）。
  3. `ReembedWorker` 在**整个重嵌入期间**持有全局 provider mutex（分钟级，每批 `delay(200)`），期间检索/导入全部排队等待；`setProgress` 只在结束时调用一次，设置页运行中进度恒为 -1/0。
- 建议（S/M）：`importStaged` 每文件 try/catch + `finally` 确保有登记就入队；`activeCount>0` 时检查是否存在未完结 unique work，无则补入队；`Reembed` 把锁粒度降到每批（锁内只做 `embedDocuments`，写库与 delay 在锁外），每批 `setProgress`；WorkManager 改用 `APPEND_OR_REPLACE` 或入队后复查状态。
- 工作量：**M（1 人日）**

### P1-5 检索扩展性：每次查询全表载入向量，无缓存、缺索引

- 位置：`Daos.kt:157-162`、`SearchService.kt:70-71,151-186`、schema `chunk_embedding` 索引
- 证据：`vectorsForSpace` 是 `WHERE embeddingSpaceId=? AND role='content'` 的全表过滤；索引只有 `(chunkId)` 与 `(chunkId, spaceId, role)`（前导列 chunkId，**无法服务该查询**）。每次检索都把全量 BLOB 读入并复制成 `FloatArray`（内存双份），G4 设计的 "PRELOADED 一次载入" 实际是"每次查询载入"；`buildHits` 还有 ≤2×topK 次单行查询（N+1）。
- 影响：10k chunk ≈ 30MB/次，10 万 chunk ≈ 300MB/次 → 扫描与内存随库线性恶化，叠加 P0-2 成为 ANR 主因。
- 建议（M）：v4 迁移加 `Index("embeddingSpaceId", "role")`（或 `(embeddingSpaceId, chunkId)`）；按 `spaceId + 行数/版本号` 缓存 `(chunkIds, FloatArray)` 并在写入后失效；`buildHits` 改 `IN (:ids)` 批量回填；排序用 primitive top-K 减少装箱。
- 工作量：**M（1 人日）**

### P1-6 空间指纹与元数据失真

- 位置：`core/embedding/EmbeddingProvider.kt:29-31`、三个 Provider 的 descriptor 构造、`EmbeddingRuntime.kt:127-153,167-179`、`IngestRepository.kt:202-223,296-299`、`ui/SettingsScreen.kt:603`
- 证据：① `modelRevision`（定义是"文件 SHA-256 前 12 位或版本号"）在三个 Provider 中全部为 null，`spaceIdFor` 只用 providerId/modelId/accelerator → 用户用同名新版本/量化版覆盖模型文件后，新旧向量静默混在同一空间。② `ensureSpace`/`registerSpace` 未传 `accelerator`（恒 "cpu"）与 `quantization`（恒 "f32"）；`taskTemplate` 仅当 spaceId 等于 `SPACE_ID`/`SPACE_ID_C` 时记 `app-prefix-v1`，而 GPU/NPU 的 440M 空间实际走 app-prefix 却记为 `raw-symmetric`。③ `SPACE_ID_C` 在 `spaceIdFor` 中没有任何分支会产出（路径 C 实际空间为 `mediapipe-text/<modelId>/raw-symmetric-v1`），是**死常量**，`SettingsScreen.kt:603` 仍按它硬编码 768。④ `parserId` 恒 "pending"、`sizeBytes` 恒 0（`importFile` 已算出字节数却未写）。
- 建议（S/M）：Provider 初始化时流式计算/查 catalog SHA-256 写入 `modelRevision` 并纳入空间指纹；meta 字段直接由 descriptor 生成（不二次推断）；删除或接回 `SPACE_ID_C`；`importFile` 补 `sizeBytes`，`processDocument` 解析后回写 `parserId`。
- 工作量：**M（1 人日）**

### P1-7 备份完整性与模型供应链校验缺失

- 位置：`KnowledgeBackup.kt:49,198-225,251-291,327-346`、`SharedModelStore.kt:100-115,158-196`、`PathPreferences.kt:59-66`、`ModelCatalog.kt:167-168`
- 证据：① 备份包无包级/entry 级 SHA-256、无尺寸上限（`zip.readBytes()` 可 zip bomb）、`format_version` 只展示不校验、`vectors.bin` 头 count 与字节数不校验；② 模型完整性只在下载路径校验：SAF `enable()` 复用缓存只比长度（`:105`）、共享目录"同名等长"直接跳过下载（`:168`）、备份导入只 `sizeMatches`（1% 容差，`:257-261`）而导出写入的 `catalog_sha256` 从未被比对；③ `verified` 验证门只写不读（`verifiedSha()` 无消费方），`isVerifiedForNpu()` 恒 false → 已知会挂死的 NPU 特化模型可被一键启用；④ `buildPreview` 的模型在位判定用固定 `filesDir/models/$file`，而合法模型可能在 `cacheDir/models-link`（共享仓启用后）或未收录于 catalog → **合法备份被 blocked 无法导入**；⑤ `preview` 的 `vectorCount` 用默认 BIG_ENDIAN 读 LE 头（`KnowledgeBackup.kt:275`），计数显示错误。
- 建议（S/M）：抽 `ModelIntegrity.verify(file, entry)`（流式 SHA-256 + 尺寸）统一用于下载后/SAF 启用前/备份导入前；导入校验 `format_version`、`catalog_sha256`、向量头长度；entry 尺寸上限（如 512MB）；`present` 判定改用 `PathPreferences.candidates()`；实现或删除 `verified` 门；修正 preview 端序并抽公共头解析函数。
- 工作量：**M（1 人日）**

### P1-8 UI 状态、导航与生命周期

- 位置：`MainActivity.kt:51-70,73-87,88-128`、`SearchScreen.kt:50-54`、`ImportScreen.kt:96-116`、`SettingsScreen.kt:119-128,143-147,257-259,564-570`、`BenchmarkScreen.kt:53-73,77-97,502-561`
- 证据与影响：
  1. **无 ViewModel / rememberSaveable**：64 个 `remember` 状态，旋转/分屏/进程重建即回首页，检索结果、导入进度、实验摘要全丢；业务逻辑与 Compose 生命周期强耦合、无法 JVM 单测。
  2. **手写字符串导航 + 无 BackHandler**：任何子屏按系统返回键直接退出 App；从检索结果打开证据详情会使 `SearchScreen` 离开组合（`MainActivity.kt:83-87` 提前 return），返回后 query/结果全丢。
  3. **autostart 实验随 Activity 重建重放**：`onCreate` 每次重读 intent，而 `started` 标志是 `remember` → 旋转会重跑实验，含 `DeleteCascadeRunner`（删来源）与 `FtsRecoveryRunner`（破坏重建）→ 数据丢失链。
  4. **长期任务绑定 `rememberCoroutineScope`**：离屏即取消且无提示（下载、迁移）；`importAll` 无 try/finally（失败后按钮恒"导入中"且异常崩溃）；`runCatching` 吞 `CancellationException` 当业务失败；`runProbe` 不复位 `running` → 点一次"连通矩阵"后整屏按钮/返回永久 disabled（确定性 bug，`BenchmarkScreen.kt:520-529`）。
  5. **资源**：249 处硬编码中文 vs `strings.xml` 仅 1 条；6 屏重复 Scaffold/返回栏；`BenchmarkScreen` 743 行 1 个 Composable、22 个状态。
- 建议（S/M，分两步）：止血——`screen`/检索状态改 `rememberSaveable`、加 `BackHandler`、`try/finally` 复位、修 `runProbe`、`catch (CancellationException) { throw it }`；规范——引入 `lifecycle-viewmodel-compose`（单依赖，不必上 Hilt），每屏 `ViewModel + StateFlow<UiState>`，长任务交给 WorkManager/Application scope 并以 Flow 暴露进度；抽 `ZhiBoxTopBar/ZhiBoxScreen` 与 `SectionCard/ExperimentCard`；用户可见文案迁 `strings.xml`。
- 工作量：**M（2–3 人日，可拆两批）**

### P1-9 工程质量门禁缺失（当前 lint 失败、schema 未入库、测试空洞）

- 位置：构建/测试/CI（仓库根）、`app/build.gradle.kts:46-49`、`app/schemas/`、`app/src/test/**`、`app/src/androidTest/**`
- 证据与影响：
  1. **Lint 当前失败**：6 个 `NewApi` error（`benchmark/VectorScanBenchmark.kt:114,115,177`，minSdk 24 用了 API 26 的 `FileChannel.open`），说明 lint 从未作为门禁；此外 19 warnings（8 条依赖过期、2 条 `Recycle` 未关闭资源、5 条 `UseKtx`、1 条硬编码 `/sdcard/`）与 6 条 Compose `AutoboxingStateCreation` 提示。
  2. **Room schema 2/3 未入库**：`git ls-files app/schemas` 仅 `1.json`，而 `MigrationTest.kt:136,189` 需要 2/3 → clean clone/CI 迁移测试必失败。
  3. **无 CI、无 lint/detekt/ktlint/editorconfig**；37 个用例只覆盖纯逻辑（CandidateFusion/ModelCatalog/文本层），导入状态机、检索、运行时、备份、下载器、Compose UI **0 覆盖**；`androidTest` 强依赖 arm64 真机（ABI 仅 arm64-v8a，x86_64 模拟器跑不了）。
  4. `coroutines-test` 已声明但测试中 0 处 `runTest`。
- 建议（M）：先提交 2/3.json 并加 lint 门禁（`lint { abortOnError = true }`，修 6 个 NewApi）；建最小 CI（`assembleDebug + testDebugUnitTest + lint + detekt`）；引入 detekt/ktlint + `.editorconfig`；按 `docs/review/core-pipeline.md` P2-14 的清单补集成测试（优先"failed 文档不命中""取消恢复""备份回环"）。
- 工作量：**M（1–2 人日）**

### P1-10 隐私与安全边界

- 位置：`AndroidManifest.xml:9,11-16`、`ModelCatalog.kt:33-34`、`ModelDownloader.kt:95-101`、`SharedModelStore.kt:40-57,136-147`
- 证据与影响：
  1. `allowBackup="true"` 且无 `fullBackupContent`/`dataExtractionRules`（`res/xml/` 不存在）→ 个人知识库 SQLite（原文 chunk、向量）与 SharedPreferences（SAF 授权、偏好）默认进入 Android Auto Backup，与"本地优先/离线"定位冲突。
  2. 无 `networkSecurityConfig`；下载域名为 `hf-mirror.com` 第三方镜像（SHA-256 可防内容篡改，但策略未写进代码/配置）；`ModelDownloader` 不校验 scheme/host。
  3. `SharedModelStore.clearAll()` 会删除所选 SAF 目录内**所有** `.litertlm/.task` 文件（不限 catalog 条目）；`setTreeUri` 的 `takePersistableUriPermission` 未捕获 `SecurityException`（部分 OEM/云盘会崩）。
- 建议（S）：`allowBackup=false`（跨设备迁移走产品自带导出）；加 `network_security_config` 显式 `cleartextTrafficPermitted=false` + 域名白名单，`ModelDownloader` 校验 `https` 与 host；`clearAll` 仅删已知模型并二次确认；`setTreeUri` 包 `runCatching` 并提示。
- 工作量：**S（0.5 人日）**

---

## 4. P2 级问题清单（技术债，按领域归类）

| # | 领域 | 位置 | 问题 | 建议 | 工作量 |
|---|---|---|---|---|---|
| 1 | 数据 | `Entities.kt:57-64`、`IngestRepository.kt:147-150` | FTS4 注释称"rowid 与 chunkId 对齐"，实际 rowid 自增、chunkId 另列；contentful 重复存 bigram；无 UNIQUE(chunkId) 可能重复召回 | 显式 `rowid = chunkId` 或修正注释；插入前去重；长期评估 FTS5 external content | S |
| 2 | 数据 | `Entities.kt` 全表 | 13 表 0 外键；手工级联只覆盖 5 表，Phase 2 关系表未纳入 | v4 加 FK ON DELETE CASCADE，或先纳入 deleteCascade | M |
| 3 | 数据 | `Entities.kt:149-153`、`Daos.kt:257-261` | `schema_migrations` 有表无写入（DAO 0 调用） | 迁移内 `INSERT OR IGNORE` 记录版本，或删表说明 | S |
| 4 | 数据 | `Daos.kt:139-140`、`ensureSpace` | `embedding_meta.active` 无单一 active 语义，`activeSpace()` 死查询 | 写新空间时置旧为 0，或删列/查询 | S |
| 5 | 数据 | `Entities.kt:24,38,142` | 状态列 String 无 CHECK/枚举，状态集在注释/DAO/UI 三处重复 | enum + TypeConverter（v4）+ 常量收敛 | M |
| 6 | 数据 | `IngestRepository.kt:89-104` | checksum 算了不用：同一文件重复导入无去重 | 按 checksum 查重跳过/提示；按需加索引 | S |
| 7 | 数据 | `KnowledgeBackup.kt:142-177,299-344` | 备份丢 `source.uri/document.sizeBytes/role/vectorNorm/createdAt/app_kv`；恢复后证据原文不可回溯 | `FORMAT_VERSION=2` 补齐字段并兼容 v1；UI 标注原文未迁移 | S/M |
| 8 | 数据 | `SearchService.kt:113-118,233-241` | buildHits / searchChannels 的 N+1 单行查询 | `IN (:ids)` 批量回填 | S |
| 9 | 数据 | `Entities.kt:39-40,80,83` | 迁移 DDL 有 DEFAULT 但实体无 `@ColumnInfo(defaultValue)`，新建库与迁移库 PRAGMA 不一致 | 实体补 defaultValue，保持 schema/迁移同源 | S |
| 10 | 数据 | `schemas` / MigrationTest | 迁移测试只断言 2 表；备份/恢复 0 自动化测试 | 补 chunk/fts/vector 断言 + 备份回环/破坏包测试 | M |
| 11 | 管线 | `Daos.kt:121-124`、`SearchService.kt:190-200` | FTS 按 rowid 截断候选（topK×12），高频 bigram 长尾召回被截断；覆盖数用原始 content 而入库已去空格，跨空格 bigram 计 0 | 用 matchinfo/全命中再 limit；重排用同一规范化串 | M/S |
| 12 | 管线 | `SearchService.kt:187-199` | 查询长度无上限：超长 OR MATCH 串与 `IN` 参数可能触 SQLite 上限 | 查询 bigram 上限 64、`chunksByIds` 分批（>500） | S |
| 13 | 管线 | `TextNormalizer`、`SearchService.kt:61` | 检索 query 未做与入库一致的 NFC/换行规范化 | `TextNormalizer.normalize(query)` 两侧共用 | S |
| 14 | 管线 | `Chunker.kt:53-60,120-125`、`BigramTokenizer.kt:10-15` | 硬切/二元组可能劈开 surrogate pair（emoji/生僻字） | 在 code point 边界回退；补 emoji 单测 | S |
| 15 | 管线 | `IngestRepository.kt:281-282,303-305` | `chunkerFor(pathB)` 与默认等价（均 600），死分支且注释自相矛盾（"保守 300 字"vs 600） | 删分支或收敛单常量；修注释 | S |
| 16 | 管线 | `Chunker.kt` | 分块无重叠（跨块答案会被截断），设计选择但风险未显式记录 | 在 PRD/实现文档记录；Phase 2 评估 50 字重叠 | S |
| 17 | 管线 | `IngestBatchWorker.kt:28-29` | `documentId == null` 时 `continue` 会反复取到同一 queued job → 死循环（防御性缺陷） | 将该 job 标 failed 并继续 | S |
| 18 | UI | `BenchmarkScreen.kt:625-695`、`SettingsScreen.kt:286-597` | 7 段/9 段同构卡片复制（~140 行）；单 Composable 22/27 个状态 | 抽 `ExperimentCard/SectionCard` + data class UiState | M |
| 19 | UI | 6 屏 `Scaffold` | 返回栏/TopAppBar 复制约 66 行 | 抽 `ZhiBoxTopBar/ZhiBoxScreen` | S |
| 20 | UI | `Theme.kt`、`Color.kt` | 深色主题只覆盖 6 个 role，XML 固定 Light；Preview 仅 1 处 | 补深色映射与关键屏 Preview | S |
| 21 | 工程 | `ModelDownloader.kt:41-50` | 服务端忽略 Range 返回 200 时按追加写、进度 total 重复加 resumeFrom；SHA 兜底但白耗流量 | 续传要求 `206 + Content-Range`，否则重置 | S |
| 22 | 工程 | `SharedModelStore.kt:158-196` | 下载失败可能遗留同名残缺文件，`createFile` 生成 `name (1)`，`enable` 可能命中残缺副本 | 先删/改名再创建，或下载前清理 | S |
| 23 | 工程 | `app/build.gradle.kts:70-78`、`libs.versions.toml` | 全局 `resolutionStrategy.force` 无测试锁定；Compose BOM 2025.01 已约 21 个月；两个 MediaPipe 制品版本独立（1.1.1 vs 0.10.35） | 改 constraints + because；季度升级窗口；对齐/约束 MediaPipe 版本 | S/M |
| 24 | 工程 | `docs/*`、`scripts/*` | 文档与实现 7 处实测不一致（README 权限/Gradle 版本/Phase 状态、UPSTREAM AGP、270M 体积、评测集规模等）；11 个脚本重复 ADB/样板、固定 `/tmp/evaldb`、`sleep 150`；`score_eval.py` 死代码/无 argv 保护 | 修文档；抽 `scripts/lib/common.sh`；`mktemp` + 轮询；argparse | M |
| 25 | 工程 | `.gitignore`、`dist/`、`docs/phase1`、`scripts/eval` | `dist/`（62MB APK）、评测结果、语料、schema 2/3 均未 ignore/未入库，157 个 untracked | 补 ignore；确定"语料入库/结果不入库"策略；补 LICENSE/NOTICE（vendored MIT 源码） | S |

> 完整 P2 及证据见四份分领域报告；上表为去重后的行动清单。

---

## 5. 优化路线图（建议排期）

| 阶段 | 内容 | 预估 | 验收标准 |
|---|---|---|---|
| **Sprint 0 止血**（0.5 人日） | 提交 `schemas/2.json`、`3.json`；修 6 个 lint error；`snapshot`/`dist`/`.review` 等补 `.gitignore`；同步 README/UPSTREAM 版本与权限声明 | 0.5d | `lintDebug` 通过；clean clone 后 `compileDebugAndroidTestKotlin` 通过 |
| **Sprint 1 P0**（2–3 人日） | P0-1 备份原子化 + meta 恢复；P0-2 三处 `withContext`；P0-3 benchmark 移 debug、autostart 门禁、release 签名/R8；`allowBackup=false` | 2–3d | 破坏包导入失败原库不变；恢复→再导出向量数不降；StrictMode 无主线程 IO 告警；`assembleRelease` 产出可安装 APK 且首页无实验入口 |
| **Sprint 2 P1 正确性**（3–4 人日） | P1-1 检索状态过滤 + 写入原子化；P1-2 取消/重试语义；P1-3 运行时生命周期；P1-4 导入死局/锁粒度；P1-6 空间指纹；P1-9 测试补强 | 3–4d | 新增"failed 文档 0 命中""取消后重排队""引擎构建失败可自愈""导入异常后仍可继续"4 组集成测试 |
| **Sprint 3 P1 体验/性能**（3–4 人日） | P1-5 向量矩阵缓存 + v4 索引；P1-8 ViewModel/BackHandler/saveable/runProbe；P1-7 备份与模型完整性校验；P1-10 网络安全配置 | 3–4d | 10k 向量检索主线程无阻塞；旋转不丢状态；系统返回键回上一屏；校验失败有明确 UI |
| **Sprint 4 工程化**（2–3 人日） | CI（build+unit+lint+detekt）；detekt/ktlint/editorconfig；依赖升级窗口与 verification-metadata；脚本公共库；文档同步 | 2–3d | CI 绿灯；`assembleDebug + testDebugUnitTest + lint` 全过 |
| **持续** | P2 清单按模块顺手清理；每次改 schema 必须带迁移测试与 schema JSON | — | — |

**给 Phase 2 的三点架构建议**：
1. **不要急着上 Hilt/Navigation**：当前依赖简单，先引入 `lifecycle-viewmodel-compose` 一个依赖 + 手写 `AppContainer` 即可解决 80% 状态问题；导航用 `sealed class Screen` + `BackHandler` 止血，等页面 >8 个再上 Navigation。
2. **把"模型路由/空间指纹"从 ingest 与 embedding 中抽成独立模块**（如 `core/model/ModelRouter` + `EmbeddingSpace`）：目前 `EmbeddingRuntime` 反向 import `IngestRepository` 的 `isPathA/BModel`、`SPACE_ID_*`、`findModelIn`，形成循环依赖；模型选择靠文件名 `contains("440m"/"granite"/"270m"/"lfm2.5")` 散落 5 处，新增模型极易漏改。
3. **给"派生数据"建立发布协议**：chunk/FTS/向量都是可从文档重建的派生数据，建议统一为 `document.status: pending→processing→ready` 的原子发布 + 失败清理，并把 `ftsHealthCheck` 扩展为 chunk↔FTS↔embedding 三表一致性检查——这一条同时解决 P1-1/P1-2/P1-4/P1-9 的根源。

---

## 6. 做得好的地方（建议保留并写进工程规范）

1. **技术决策可追溯**：G1–G4 判定门、bigram-FTS、PRELOADED 精确扫描、RRF 都有实测数据与 ADR 依据；连 litertlm 0.18.0 的 `resolutionStrategy.force` 都附了 AAR 反编译级证据。
2. **注释记录"为什么"**：双入队防护、AppleDouble 过滤、命名参数错位、GPU 编译缓存目录、维度校验时机——都是从根因写到修复，维护价值高。
3. **嵌入空间隔离是结构性兜底**：`spaceIdFor` 把模型/GPU/NPU 拆成不同空间，空间不匹配时明确拒绝检索并列出已有空间，防住了"换模型不重建向量"这一常见 RAG 数据事故。
4. **数值层严谨**：float32 LE 编解码两侧对称、L2 normalize 交给 Provider、逐条 finite 校验、维度漂移检测、量化/加速器字段预留。
5. **Room 迁移被认真对待**：v1/v2/v3 schema 导出、1→2/2→3/1→3 链式迁移测试、`runMigrationsAndValidate` 真校验、无破坏性回退、加列用 `NOT NULL DEFAULT` 正确回填。
6. **删除级联与检索可恢复性**：五表单事务删除 + 导入中拒绝删除；FTS 可从 chunk.content 全量重建并有 healthCheck，为后续"发布协议"改造留了好基础。
7. **离线/联网边界清晰**：唯一联网路径集中在 ModelCatalog/ModelDownloader/SharedModelStore；URL 全 HTTPS；全项目 **0 处 `Log.`/`println`/`printStackTrace`**（无日志泄漏）。
8. **模型下载器与资产台账**：断点续传、流式 SHA-256、磁盘预检、原子 rename、416 处理；`models/SHA256SUMS` + `UPSTREAM_VERSIONS.md` 记录 8 个模型来源/SHA/体积，实测一致。
9. **脚本纪律**：11/11 shell 脚本 `set -euo pipefail`，`ANDROID_SERIAL`/`ANDROID_HOME` 可参数化，无硬编码设备序列号。
10. **测试策略方向正确**：纯函数（CandidateFusion/Chunker/Bigram/Normalizer）有单测，整体检索有 220/262 条无头评测集与宿主机打分脚本——只是尚未覆盖状态机与数据一致性。

---

## 7. 审查边界与待验证事项

- 真机 ANR 阈值、GPU 试探超时后的实际 RSS/引擎回收行为、Compose skippability 与冷启动时序，需真机/基准复测（本报告按代码路径定性）。
- `Lint` 的 19 warnings 中 `Recycle`（`SharedModelStore.kt:106`）需人工确认是否误报（代码有 `use{}`）。
- 备份 meta 缺失、`SPACE_ID_C` 死常量、主线程路径、运行时 closed-provider 残留均经 Lead 独立复核代码确认；其余结论来自四份分领域报告的逐文件通读与调用方 grep。
- 本报告未修改任何产品代码；仅新增 `docs/CODE_QUALITY_REVIEW.md` 与 `docs/review/*.md`。

---

## 8. 详细报告索引

| 报告 | 内容 | 发现数 |
|---|---|---|
| [`docs/review/data-layer.md`](review/data-layer.md) | Room schema/DAO/迁移/备份恢复 | 21（P0×2、P1×8、P2×11） |
| [`docs/review/core-pipeline.md`](review/core-pipeline.md) | 导入/嵌入/检索/文本处理 | 26（P0×2、P1×11、P2×13） |
| [`docs/review/ui-layer.md`](review/ui-layer.md) | Compose/状态管理/导航/资源 | 23（P0×1、P1×8、P2×14） |
| [`docs/review/infra.md`](review/infra.md) | 构建/依赖/模型管理/安全/测试/脚本 | 18（P0×1、P1×9、P2×8） |

> 说明：分报告中的 P0/P1 定级按各领域独立给出；本文跨领域重新校准后为 **P0×3 / P1×10 类 / P2×25**（部分同源问题已合并）。

---

## 附录 A：本报告使用的验证命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest   # ✅ BUILD SUCCESSFUL, 34 tests
./gradlew :app:compileDebugAndroidTestKotlin               # ✅ 编译通过（需 arm64 真机执行）
./gradlew :app:lintDebug                                   # ❌ 6 errors / 19 warnings / 6 hints
find app/build/outputs -name "*.apk"                       # 仅 debug + androidTest
git ls-files app/schemas                                   # 仅 1.json（2/3 未入库）
git status --porcelain -uall | grep -c '^??'               # 157 个未跟踪文件
```

Lint 关键错误：

```
benchmark/VectorScanBenchmark.kt:114: Error: Call requires API level 26, or core library
  desugaring (current min is 24): java.nio.channels.FileChannel#open [NewApi]
benchmark/VectorScanBenchmark.kt:115: ... StandardOpenOption#TRUNCATE_EXISTING [NewApi]
benchmark/VectorScanBenchmark.kt:177: ... FileChannel#open [NewApi]
```

## 附录 B：给下一位维护者的"先做这 10 件小事"

1. `git add app/schemas/*`（2.json / 3.json）——迁移测试在 clean clone 才有意义。
2. 修 `VectorScanBenchmark.kt:114/115/177`（改用 `FileOutputStream`/`RandomAccessFile` 或加 API 26 守卫），让 `lintDebug` 转绿。
3. `KnowledgeBackup.importZip`：先完成全部校验再 `clearAllTables`，并在写库外层包单事务。
4. `KnowledgeBackup`：导入时逐条 `upsertMeta`，导出对"未注册空间向量"显式报错；补"导出→导入→再导出"测试。
5. `SearchService`：`search()` 外层 `withContext(Dispatchers.Default)`；`IngestRepository.importStaged()` 外层 `withContext(Dispatchers.IO)`。
6. `EmbeddingRuntime.withProvider`：重建前清空 `shared/sharedKey`，构建失败保持空并可重试；`shutdown` 加锁。
7. `processDocument`：`catch CancellationException` 重排队并重抛；失败分支清理 chunk/FTS/向量；维度校验提前到首批向量入库前。
8. `SearchService` 两条候选查询加 `JOIN document ... AND d.status='ready'`。
9. `importStaged`：逐文件 try/catch，`finally` 确保有登记就入队；`activeCount>0` 时检查/补 worker。
10. 首页"实验/诊断"按钮与 20 个 `autostart_*` 分支加 `FLAG_DEBUGGABLE` 门禁；`runProbe` 补 `finally { running = false }`。

---

*报告生成：2026-10-17 · 审查以当前工作区文件为准（含未提交改动）。如需，我可以直接按 Sprint 0/1 的范围提交修复 patch。*
