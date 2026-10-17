# ZhiBox P0/P1 修复报告（FIX_REPORT）

> 依据：`docs/CODE_QUALITY_REVIEW.md`（3 个 P0 / 10 类 P1）→ 修复计划 `docs/review/FIX_PLAN.md`
> 执行方式：Lead 地基（schema v4/DAO/迁移测试/lint 前置）+ 4 路并行修复（backup / build / core / ui）+ 统一集成门禁 + 独立双轴审查
> 基线：修复前工作区快照 `.fix-baseline/`（HEAD `9ca20d6` + 未提交 WIP）；本报告所有结论以**最终冻结态**为准
> 日期：2026-10-17

---

## 1. 执行摘要

| 项目 | 结果 |
|---|---|
| P0 修复 | **3 / 3 完成**（备份数据安全、主线程重活、实验隔离与发布通道） |
| P1 修复 | **10 / 10 类完成**（P1-1…P1-10，详见第 3 节） |
| 新增/改写代码 | 47 个文件相对基线变化（含 debug/release 双 source set 迁移） |
| 新增单元测试 | **72 个**（34 → **106**），全部纯 JVM，`0 failed` |
| 构建门禁 | `compileDebugKotlin` / `compileReleaseKotlin` / `compileDebugUnitTestKotlin` / `compileDebugAndroidTestKotlin` ✅ |
| Lint 门禁 | `lintDebug`（`abortOnError=true`）✅ **0 errors / 21 warnings**（原 6 errors） |
| 打包门禁 | `assembleDebug` ✅；`assembleRelease`（R8+shrinkResources）✅ 48.6MB / 1 dex（原 debug 62.4MB / 12 dex） |
| Release 实证 | 无 `application-debuggable`、`allowBackup=false`、networkSecurityConfig 生效、dex 内无 benchmark/jieba/破坏性 runner |
| 独立审查 | Standards + Spec 双轴：16 条意见，**12 条本轮已修**、4 条记录为后续（P2/环境限制），结果见第 5 节 |

**结论：本轮修复目标全部达成，工程门禁与独立审查均已闭环；剩余风险集中在"无 arm64 真机、R8/签名材料、CI 未实跑"三项环境限制，以及 2 项有意的行为变更（见第 6 节）。**

---

## 2. P0 修复明细（3/3）

### P0-1 备份/恢复数据安全 ✅

| 原风险 | 修复 |
|---|---|
| 导入先 `clearAllTables` 再非事务写库，失败即清空知识库 | **先全量读取+语义校验 → 模型供应链校验 → 导入前自动快照 → 单事务替换**；`clearAllTables` 改为事务内显式 `deleteAll()`；任一步失败整体回滚，原库不变（`KnowledgeBackup.kt:635-747`） |
| 导入不恢复 `embedding_meta`，恢复后再导出静默丢全部向量 | v2 包写全 meta 字段（accelerator/quantization/modelRevision/normalization/active/…）与 app_kv；导入恢复 meta/app_kv；导出端"未注册空间向量/孤立向量"从静默 skip 改为显式失败；兼容 v1 包（`KnowledgeBackup.kt:707-746`、`FORMAT_VERSION=2`） |
| 其余字段丢失 | 备份补齐 `source.uri/document.sizeBytes/vector.role/vectorNorm/createdAt/app_kv`；FTS 仍从 chunk.content 重建（不绑定索引格式） |
| 大知识库内存峰值/SAF 半包 | 导出改**流式分页**（`Dao.page(limit,offset)`，每页 ~200 行）；SAF 目标先写 `cacheDir` 临时文件再 copy；导入 entry/总量/数量上限（`BackupValidator`） |
| 失败无安全网 | 导入前自动导出快照到 `cacheDir/auto-backup-<ts>.zip`（保留最近 2 份） |

**证据**：新增 `BackupValidator`（19 测试）+ `BackupModelPresence`（3 测试）；事务/校验代码见上；`export→import→再导出` 语义由 meta 恢复 + 显式失败策略保证。

### P0-2 主线程重活 ✅

| 原风险 | 修复 |
|---|---|
| 检索在主线程解码/扫描全量向量、首次模型加载阻塞 Main | `SearchService.search/searchChannels` 整体 `withContext(Dispatchers.Default)`（`SearchService.kt:70,272`）；`EmbeddingRuntime.withProvider` 内部 Default（`EmbeddingRuntime.kt:61,69`）；`IngestRepository` 全入口 `withContext(IO)`（`:69,113,167,349,369,380,391`）；整文件 `readBytes` 改 64KB 流式 SHA-256 |
| 导入文件遍历/哈希在主线程 | 同上，importStaged/importFile 全 IO |

**证据**：类注释显式声明线程边界；Compile/lint 通过；真机 StrictMode 复测列为剩余风险（无 arm64 设备）。

### P0-3 实验隔离与发布通道 ✅

| 原风险 | 修复 |
|---|---|
| 实验代码（37.6% 主源码）与破坏性 runner 进 release、可被任意 App Intent 触发 | `benchmark/` 13 文件 + `BenchmarkScreen.kt` 整体迁入 `app/src/debug/java/`（git mv 保留历史）；release 侧仅 41 行同签名空 stub；`MainActivity` 的 20 个 autostart extra + 首页实验按钮仅在 `FLAG_DEBUGGABLE` 构建生效，且 `savedInstanceState==null` 一次性消费 + `replaceExtras` 清空 |
| release 通道不存在、交付 debug 包 | `signingConfigs.release`（`ZHIBOX_*` 属性/env，缺省 unsigned 不阻塞）；`isMinifyEnabled=true`、`isShrinkResources=true`、保守 proguard keep；`assembleRelease` 成功；交付物为 release 包（当前 unsigned，配置密钥后签名） |
| 实验依赖随包 | `jieba-analysis` 改 `debugImplementation` |

**证据**：release APK dex 中 `com.zhibox.memory.benchmark` / `DeleteCascadeRunner` / `FtsRecoveryRunner` / `ConnectivityProbe` / jieba 命中数均为 0（`BenchmarkScreen` 仅 release 空 stub）；`aapt2` 确认无 `application-debuggable`；临时 keystore 签名链路实测通过（`apksigner verify`），随后恢复 unsigned。

---

## 3. P1 修复明细（10/10）

### P1-1 检索一致性 ✅
- `Daos.vectorsForSpace` / `searchFtsOrdered` 均 `JOIN chunk + document` 且 `d.status='ready'`（`Daos.kt:163-173,225-232`）。
- `processDocument` 改为**发布语义**：`pending→processing→(全部写完)ready`；维度校验提前到首批向量入库前；失败/取消统一 `cleanupDerivedData()` 单事务清理 chunk/FTS/向量（`IngestRepository.kt:175,223-227,267-315`）；chunk+FTS 单事务重写。
- 效果：失败/取消/导入中的半成品文档**不可被检索**，错误维度向量不再毒化空间。

### P1-2 取消/失败语义 ✅
- `CancellationException`：`NonCancellable` 内清理 + job 重排队 + 文档回 pending + 重抛（`IngestRepository.kt:258-266`）；`Error` 直接重抛。
- 普通异常：attempt++，`attempt<3` 自动 requeue，否则终态 failed；新增 `retryFailedJobs()` 并由 ImportScreen 的「重试失败导入（N）」接入。
- 全部进度更新改 `*IfNotCancelled` 守卫；`cancelImport` 只取消 queued，在途文档跑完置 cancelled 并清理；Worker 区分取消/Error/普通异常，空 documentId 标 failed 防死循环。

### P1-3 运行时生命周期 ✅
- 新增纯 JVM `EngineCache`：重建前清引用再 close，构建失败保持空 → **下次调用自愈**；`shutdown()` 改 `suspend + Mutex` 消除 use-after-close。
- `probeGpuWithTimeout`：超时/取消/探针失败均 `close()` 泄漏 provider（避免 2× 模型内存）。
- `@VisibleForTesting` provider factory 可注入；Provider init 失败关 fd/engine。
- 单测：`EngineCacheTest` 8 例（缓存/换 key/构建失败自愈/超时泄漏/探针失败）。

### P1-4 导入/重嵌入并发 ✅
- `importStaged` 逐文件 try/catch，`finally` 确保有登记就入队；`activeCount>0` 时补入队；`APPEND_OR_REPLACE` 消除丢唤醒窗口；`importFile` 先复制后 DB 单事务，失败回滚文件。
- `ReembedWorker` 锁粒度降为每批（锁内仅 embedDocuments，写库/delay 在锁外），每批 `setProgress`。

### P1-5 检索扩展性 ✅
- schema v4 增加 `chunk_embedding(embeddingSpaceId, role)` 索引（`MIGRATION_3_4` + `4.json`）。
- 新增 `VectorMatrixCache`（key=spaceId+rowCount+maxVectorId，写路径主动失效 + 签名兜底）；`buildHits` 三表 `byIds` 批量回填替代 N+1；查询 512 字截断、OR bigram≤64、IN 分批 400。
- 新增 `SearchLimitsTest` 6 例。

### P1-6 空间指纹/元数据 ✅
- 新增 `ModelFingerprint`（catalog SHA-256 前 12 位优先，未收录则流式 SHA-256 + path|size|mtime 缓存）；三个 Provider 写入 `modelRevision`。
- `spaceIdFor/resolveSpaceId` 纳入 revision；保留 granite CPU / 440M CPU 两个存量兼容空间 ID（零迁移）；同名换文件自动新空间。
- `registerSpace` 成为 `embedding_meta` 唯一写入口（accelerator/quantization/taskTemplate 由 descriptor 生成）；删除重复 `ensureSpace` 与死常量 `SPACE_ID_C`；`parserId/sizeBytes` 正确落库。
- 新增 `SpaceFingerprintTest` 7 例 + `ModelFingerprintTest` 6 例。

### P1-7 备份完整性/模型供应链 ✅
- `BackupValidator`：`format_version` 门、vectors 头 count/长度/引用/重复校验、entry/总量/数量上限、preview LE 端序修复。
- `ModelIntegrity.verify(file, sha256, size)` 统一用于 SAF 启用前、跳过下载判定前、备份导入前；`catalog_sha256` 真正参与比对；模型在位判定改用 `PathPreferences.candidates`（共享仓/未收录模型不再误判 blocked）。
- 验证门消费方落地：`PathPreferences.isVerified()` 被 resolve 使用；删除 `isVerifiedForNpu()` 死代码。
- 新增 `BackupValidatorTest` 19 例、`BackupModelPresenceTest` 3 例、`ModelIntegrityTest` 7 例、`ModelDownloaderPolicyTest` 6 例。

### P1-8 UI 状态/导航/生命周期 ✅
- 新增 4 个 ViewModel（Search/Import/Sources/Settings）+ `UiCommon`（`safeLaunch` 重抛 CancellationException、`readableMessage`、`ZhiBoxTopBar/ZhiBoxScreen`）；检索证据改屏内覆盖，返回不丢结果；导航 `rememberSaveable` + `BackHandler`；BenchmarkScreen `activeRun` + try/finally 修 `runProbe` 永久 disabled、`started*` rememberSaveable；产品屏用户可见文案迁 `strings.xml`（164 条，中文字面量 0）。

### P1-9 工程质量门禁 ✅
- `app/schemas/{2,3,4}.json` 入库（git 已 add）；`MIGRATION_3_4` + MigrationTest 3→4 用例；lint `abortOnError=true` 且 **0 error**；新增 `.github/workflows/ci.yml`（JDK21 / assembleDebug + testDebugUnitTest + lintDebug + androidTest 编译）；新增 72 个单测（34→106）；`.gitignore` 增补 dist/apk/.fix-baseline/结果目录。

### P1-10 隐私与安全 ✅
- `allowBackup=false`；新增 `network_security_config.xml`（默认禁明文，`hf-mirror.com` 白名单，仅 system CA）。
- `ModelDownloader`：HTTPS + host 白名单；Range 续传要求 206+Content-Range，否则重置。
- `SharedModelStore`：`setTreeUri` 捕获 SecurityException 并返回 Boolean；`clearAll` 只删已知模型；`enable` 复用缓存前做 SHA 校验；下载残留清理。

---

## 4. 验证记录（最终冻结态）

```text
$ ./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin \
    :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL

$ ./gradlew :app:testDebugUnitTest --rerun
BUILD SUCCESSFUL
unit tests total=106 failures=0     # 34 基线 + 72 新增
# 测试类：BackupValidator(19) BackupModelPresence(3) EngineCache(8) ModelFingerprint(6)
#   SpaceFingerprint(7) IngestRetryDecision(2) ModelIntegrity(7) ModelDownloaderPolicy(6)
#   PathPreferencesRouting(8) SearchLimits(6) + 既有 ModelCatalog/PathRouting/SpaceIsolation/
#   CandidateFusion/text 层(34)

$ ./gradlew :app:lintDebug          # abortOnError=true
BUILD SUCCESSFUL                     # 0 errors / 21 warnings（原 6 errors / 19 warnings）

$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL                     # app-debug.apk 62.3MB

$ ./gradlew :app:assembleRelease    # R8 + shrinkResources
BUILD SUCCESSFUL                     # app-release-unsigned.apk 48.6MB / 1 dex
```

Release APK 实证（aapt2 / dex grep）：

```text
allowBackup=false
networkSecurityConfig=@0x7f0c0000（res/xml/network_security_config.xml 已打包）
application-debuggable 标记 = 0
dex 中 com.zhibox.memory.benchmark / DeleteCascadeRunner / FtsRecoveryRunner = 0
dex 中 huaban(jieba) = 0；BenchmarkScreen 仅 release 空 stub
```

Room schema：`app/schemas/` 下 1/2/3/4.json 全部入库（git status: A 2/3/4.json）；`MigrationTest` 覆盖 1→2、2→3、3→4、1→3 链式（仪器测试仅保证编译，需 arm64 真机执行）。

---

## 5. 独立审查（Standards + Spec 双轴）

> 审查方式：以 `.fix-baseline/`（修复前快照）为固定点，对全部修复 diff 做**并行双轴独立审查**（Standards / Spec 两个独立审查代理，互不污染上下文），再由 Lead 逐条复核与处置。
> 审查输入：`docs/review/FIX_PLAN.md`（Spec/DoD + 接口契约）、仓库既有约定（中文解释性注释、分层、0 日志、公开 API 稳定）+ Fowler smell 基线、约 14.6k 行修复 diff。
> 审查结论：**两轴共 16 条意见：硬性问题 2 + 判断性 smell 4 + Spec 缺口/偏差 8 + 范围提示 2**；其中 12 条已在本轮修复，4 条记录为后续事项（见 5.3）。

### 5.1 Standards 轴（摘要）

| # | 类型 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | `IngestRepository.kt:95,100`、`IngestBatchWorker.kt:54` 新增 3 处 `Log.w`，违反仓库"0 日志"约定（基线 0 处，避免路径/内容泄漏） | **已修**：删除 3 处日志与相应 import/常量；错误仍结构化落在 `ingest_job.error` |
| S2 | 硬性 | `IngestRepository.kt:92` 逐文件 `catch (Exception)` 会把 `CancellationException` 当单文件失败继续循环（P1-2 语义缺口） | **已修**：新增 `catch (c: CancellationException) { throw c }` 分支；`KnowledgeBackup` 6 个公开入口与 `ImportViewModel` 的 `runCatching` 统一改取消安全版本（`runCatchingCancellable`） |
| S3 | 判断性 | 流式 SHA-256 实现 4 份（ModelIntegrity / ModelFingerprint / IngestRepository / ModelDownloader） | **部分修**：ModelDownloader 删除内联 SHA，改为下载完成后统一调 `ModelIntegrity.verify`（P1-7 契约达成）；其余 2 份属不同 IO 形态（文件/流/增量），记录为后续抽取 `Sha256` 工具 |
| S4 | 判断性 | 18 个 `autoStart*` Boolean 贯穿 MainActivity/双 BenchmarkScreen（新增 runner 需改 4 处） | **记录为后续**：本轮为保证 20 个 extra 常量名与 scripts 兼容未做 `EXTRA_RUN_MODE` 收敛（属 P2 技术债） |
| S5 | 判断性 | 死代码：`PathPreferences.isVerified()`、`Daos.maxVectorIdBySpace()`、`updateStatus()`、`allVectors()`；debug runner 2 处未用 import | **已修**：删除 4 处死代码 + 2 个未用 import（`updateStatus` 还能覆写 cancelled，删除同时消除隐患） |
| S6 | 判断性 | entry 名常量在 `BackupValidator` 与 `KnowledgeBackup` 重复维护 | **已修**：`BackupValidator.ENTRY_*` 成为单一事实源，`KnowledgeBackup` 改为别名引用 |
| S7 | 判断性 | `ModelFingerprint` 注释称"文件不可读返回 null"，实现对 catalog 内模型返回目录哈希（注释与实现不符） | **已修**：注释改为准确描述"catalog 信任哈希 / 未收录按实际内容计算" |

### 5.2 Spec 轴（摘要）

| # | 项 | 结论 | 处置 |
|---|---|---|---|
| P0-1 | 备份安全 | partial：代码齐（全量校验→单事务→meta 恢复→快照），缺"坏包原库不变 + 导出→导入→再导出"自动化测试 | **已补**：新增 instrumentation `BackupRoundTripTest`（回环向量/空间数不降 + 坏包原库不变）；临时真机未跑（见第 6 节） |
| P0-2 | 主线程 | implemented | — |
| P0-3 | 实验隔离/发布 | implemented；审查时 release/lint "未复验" | Lead 已统一复验：lint 0 error、assembleRelease 成功、APK 实测无 benchmark/jieba |
| P1-1 | 检索一致性 | partial：JOIN ready、失败清理、维度前置均实现，缺"failed 文档 0 命中"测试 | **已补**：新增 instrumentation `SearchVisibilityTest`（failed/processing 文档的 chunk/FTS/向量均不可检索） |
| P1-2 | 取消语义 | partial：Worker 主路径正确，但 `importStaged` 逐文件 catch 吞取消 | **已修**（见 S2） |
| P1-3 | 运行时 | implemented | — |
| P1-4 | 并发 | implemented | — |
| P1-5 | 扩展性 | implemented | — |
| P1-6 | 空间指纹 | partial：`quantization` 硬编码 f32；**存量非 legacy 空间升级后向量会不可检索** | **已修**：`EmbeddingDescriptor` 新增 `quantization` 字段并由 descriptor 写入 meta；`resolveSpaceId` 增加存量空间**采纳逻辑**（优先复用升级前旧 id 并回填 revision，仅同名换文件才建新空间） |
| P1-7 | 完整性/供应链 | partial：下载器内联 SHA，与 `ModelIntegrity.verify` 未统一 | **已修**：下载完成统一走 `ModelIntegrity.verify`（校验失败删除 `.part`） |
| P1-8 | UI 状态 | partial：ViewModel 已覆盖状态，但证据页选中项仍用 `remember` | **已修**：`selectedHit` 提升到 `SearchViewModel`，旋转后仍停留在证据页 |
| P1-9 | 门禁 | partial：缺检索过滤/取消/回环测试 | **已补**：2 个 instrumentation 回归测试（见 P0-1/P1-1）；取消语义由 `IngestRetryDecisionTest` + Worker 代码审查覆盖（无 Robolectric 环境下无法 JVM 级验证） |
| P1-10 | 隐私/安全 | **wrong**：`clearAll` 仅按子串名单过滤，SAF 目录中名字含 `440m/granite/*.task` 的普通文件会被误删 | **已修**：新增 `PathPreferences.isKnownModelFile()`（名单 + `.litertlm/.task` 扩展名双校验），`clearAll/listNames/linkedFiles` 全部改用；下载器 https/host、Manifest 配置经复核无误 |
| 范围 | — | 工作区另有 `docs/UPSTREAM_*.md`、`.zcode/plans` 改动 | **非本轮引入**：这些是修复前就存在的 Phase 1 文档 WIP（基线快照中已存在），未在本轮触碰 |

### 5.3 审查后增量修复（本轮闭环）

1. 删除新增日志（仓库 0 日志约定）并补 `CancellationException` 重抛（`importStaged` / `KnowledgeBackup` 6 入口 / `ImportViewModel`）。
2. `EmbeddingDescriptor.quantization` + `registerSpace` 由 descriptor 生成 meta 字段。
3. `resolveSpaceId` 存量空间采纳：GPU/NPU/路径 C/740M 等升级前的旧空间 id 不再"消失"，首次注册回填 revision。
4. `isKnownModelFile()` 双校验，杜绝 `clearAll` 误删 SAF 目录中非模型文件。
5. `ModelDownloader` 统一走 `ModelIntegrity.verify`（P1-7 契约真正达成）。
6. `SearchViewModel.selectedHit` 状态提升（旋转/重建不丢证据页）。
7. 删除 4 处死代码（`isVerified/maxVectorIdBySpace/updateStatus/allVectors`）、2 个未用 import、entry 常量去重、`ModelFingerprint` 注释修正。
8. 新增 2 个 instrumentation 回归测试（备份回环/坏包安全；检索只返回 ready 文档），当前仅编译验证，待 arm64 真机执行。

**审查后复验**：全部编译目标 ✅、`testDebugUnitTest` **106 tests / 0 fail** ✅、`lintDebug` **0 errors / 21 warnings** ✅、`assembleDebug` ✅、`assembleRelease` ✅。

---

## 6. 剩余风险与未覆盖项（如实记录）

| # | 风险 | 影响 | 建议 |
|---|---|---|---|
| 1 | ~~无 arm64 真机~~ **已解决**：2026-10-17 在 OnePlus PJZ110（Android 16/arm64）完成真机验收 | androidTest **7/7 PASS**、旧数据升级/检索/导入/UI/release 全链路通过 | 见 `DEVICE_ACCEPTANCE.md`；新发现 D1（ImportViewModel 主线程 getFilesDir）纳入 P2 |
| 2 | release R8 keep 规则未在真机 smoke | 理论上有 JNI/反射崩溃风险（已保守 keep + `-dontwarn` 定向） | 对外交付前真机跑模型加载/检索/导入/备份 |
| 3 | 签名材料未 provision | 当前 release 产物为 `app-release-unsigned.apk`（不可分发） | 配置 `ZHIBOX_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` 后重跑并 `apksigner verify` |
| 4 | CI 未在 GitHub 实跑 | workflow 逻辑与 Gradle 缓存未经远端验证；wrapper 指向 huaweicloud 镜像 | 首次 push 后确认 CI 绿；必要时切回官方源 |
| 5 | **空间 ID 行为变更（P1-6 有意）**：非兼容空间（GPU/NPU/740M/270M/LFM/.task）新 ID 带 `@<sha12>` | 升级后这些空间的存量向量不可检索，需 ReembedWorker/重导入迁移 | 发布说明写明；440M CPU/granite CPU 两个高频空间 ID 不变，零迁移 |
| 6 | 同名换文件检测的时间点限制 | 升级前已存在的兼容空间 meta.revision=null，首次 registerSpace 回填后才生效 | 发布说明；后续可加"文件哈希变更即提示重嵌入" |
| 7 | GPU 超时泄漏为"尽力关闭" | JNI 永久挂死场景无法真正中断（auto 白名单已排除 granite/Mali） | 保持 W8 监控项 |
| 8 | 缓存一致性跨模块 | 备份导入已补 `VectorMatrixCache.invalidate()`（审查修复已闭环） | 已完成，见 5.3 |
| 9 | 大库缓存上限 | 单空间矩阵 10k×768≈30MB 常驻；100k≈300MB 需分片 | P1-5 中期事项（原 P2 范围） |
| 10 | 无 Compose UI 自动化测试 | UI 重构靠代码审查+编译+lint 保障 | 后续引入 Robolectric/Compose UI test |
| 11 | backup v1 包兼容路径未做端到端构造测试 | 只有 validator 纯 JVM 单测覆盖 v1 行布局 | 下一迭代补 fixture 包测试 |
| 12 | P2 项未处理（25 项技术债） | 不影响本轮 P0/P1 目标 | 按 `docs/CODE_QUALITY_REVIEW.md` P2 清单排期 |
| 13 | ~~新增 instrumentation 测试未执行~~ **已解决**：真机 7/7 PASS（含备份回环/坏包安全/检索可见性/迁移） | 见 `DEVICE_ACCEPTANCE.md` 第 2 节 | 已闭环 |
| 14 | ~~存量空间采纳逻辑未真机验证~~ **已解决**：真机用 81 文档/v3 旧库验证 GPU 旧空间采纳 + 回填 | 见 `DEVICE_ACCEPTANCE.md` 3.1/3.3 | 路径 C/740M/NPU 组合验证列入 P2 B1-12 |
| 15 | 下载校验多一次全文件读取 | 统一 `ModelIntegrity.verify` 后，下载完成会再读一遍 `.part` 做 SHA（约 1-2s/400MB） | 可接受；若追求极致可恢复流式校验 + 复用 digest |

**有意的行为变更清单**（需在发布说明中告知）：
1. 非兼容 embedding 空间 ID 增加 revision 后缀（见风险 5）。
2. 备份格式升级 v2（v1 包可导入，v2 包旧版 App 不可读）。
3. 导入/导出前置拒绝"有在途导入任务"；导入前自动快照会占用 cacheDir（最多 2 份）。
4. 失败导入自动重试 3 次（维度错误等确定性失败也会重试，稍增耗时）。
5. 导入恢复的知识库不含原文文件（`source.uri` 仅存元数据），证据页需提示原文未随包迁移。

---

## 7. 变更文件清单（相对 `.fix-baseline/`，47 个文件变化 + 新增目录）

**Lead 地基**
`data/db/Entities.kt`（v4：ingest_job.attempt、chunk_embedding 空间索引）、`data/db/AppDatabase.kt`（v4 + MIGRATION_3_4）、`data/db/Daos.kt`（批量/分页/guarded/重试/清表等）、`androidTest/.../MigrationTest.kt`（3→4 用例）、`app/schemas/{2,3,4}.json`（入库）、`benchmark/VectorScanBenchmark.kt`（NewApi lint 修复）

**backup-fixer**
`core/backup/KnowledgeBackup.kt`（重写）、`core/backup/BackupValidator.kt`（新）、`core/model/{ModelIntegrity.kt(新),ModelCatalog,PathPreferences,ModelDownloader,SharedModelStore}.kt`、5 个新测试文件

**build-fixer**
`app/build.gradle.kts`、`gradle/libs.versions.toml`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/network_security_config.xml`（新）、`.github/workflows/ci.yml`（新）、`.gitignore`

**core-fixer**
`core/embedding/{EmbeddingRuntime(重写),EngineCache(新),ModelFingerprint(新),UniversalEmbedderProvider,LiteRtLmEmbeddingProvider,TextEmbedderProvider}.kt`、`core/search/{SearchService(重写),VectorMatrixCache(新)}.kt`、`core/ingest/{IngestRepository(重写),IngestBatchWorker,ReembedWorker}.kt`、`data/db/Daos.kt`、5 个新测试文件

**ui-fixer**
`MainActivity.kt`、`ui/{UiCommon,SearchViewModel,ImportViewModel,SourcesViewModel,SettingsViewModel}.kt`（新）、`ui/{Search,Import,Sources,Settings,Evidence}Screen.kt`（重写）、`ui/BenchmarkScreen.kt`（移 debug）、`app/src/release/.../BenchmarkScreen.kt`（新 stub）、`benchmark/` 13 文件（移 debug）、`res/values/strings.xml`（164 条）

---

## 8. 复现验证命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/pauldeman/Documents/ai_workspace/ZhiBox

./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin \
    :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest --rerun
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## 9. 结论

本轮完成了代码审查报告中全部 P0 与 P1 的修复：**3 个数据安全/发布阻断级问题、10 类正确性/性能/安全/工程化问题**全部落地，并新增 72 个单测与 CI/lint 门禁。最终冻结态通过编译、106 单测、lint（0 error）、debug/release 打包五项门禁；release 产物已具备实验隔离与隐私配置，只差签名材料与真机 smoke。

剩余事项均属**环境限制（无 arm64 真机/未 provision 签名/CI 未实跑）**或**有意的行为变更**，已在第 6 节逐条记录；P2 技术债按原报告清单后续排期。

---

*报告生成：2026-10-17 · 真机验收见 `DEVICE_ACCEPTANCE.md`；P2 计划见 `P2_FIX_PLAN.md` · 全部修复基于当前工作区（未提交），`.fix-baseline/` 保留修复前快照供审计。*
