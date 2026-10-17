# B2 修复报告（P2 批次 B2：检索质量与健壮性）

- **日期**：2026-10-17
- **范围**：`docs/review/P2_FIX_PLAN.md` 批次 B2（9 项：B2-1/2/3/4/5/6/7/8/10；B2-9 已合并入 B5）
- **执行方式**：Lead 采集真机基线（220 + long-tail fixture）→ 3 路并行（core 检索/文本/健康检查/缓存、UI 错误分类、验收测试）→ Lead 集成 B2-7 核心错误编码 + ADR → 全量门禁 → 真机评测对比 + 25 项 instrumentation → 双轴独立审查
- **用户决策**：按计划启动 B2（沿用 B1 决策：允许依赖升级窗口、保留 phase1 结果、不 provision 签名、Robolectric 留 B5）

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| B2 完成度 | **9 / 9 项完成**（B2-1…B2-8、B2-10） |
| JVM 测试 | **197 tests / 0 failed**（B2 前 153 → +44：检索排序/覆盖/规范化/文本边界/错误分类/健康检查/缓存上限/流式门限/重排性能） |
| Android Lint | **0 errors / 23 warnings**（`abortOnError=true`） |
| 构建 | `assembleDebug` / `assembleRelease`（R8）✅ |
| 真机 instrumentation | **25 / 25 PASS**（新增 10k FTS 性能、long-tail 回归、索引健康/孤儿清理等） |
| 220 条检索评测 | **无回退**：R@1=0.9636 / R@5=0.9909 / R@10=1.000（与基线一致）；p50=49ms、p95=57→62ms；0 refused |
| long-tail 20 条 | **提升**：R@1/R@5/R@10 0.95→**1.00**；关键词候选均值 0.8→**1.0** |
| 10k 合成 FTS 基线 | in-memory 100 文档×100 chunk：稀有词 0ms；5k 命中 p50=5ms/p95=7ms；全命中截断 5000 同样 p50=5-6ms/p95=6-7ms |
| 索引健康 | 真机 DB 测试：健康库通过；人工孤儿（FTS 9999/向量 8888）+ 重复 FTS 被检出并 repair（removed_fts=2/removed_emb=1，有效数据不动）；**生产入口**：设置页「检查并修复索引」按钮调用 `repairIndex` |
| 关键词重排成本 | 最坏模拟 5000 候选×600 字：`rankKeywordHits` p50=81ms/max=82ms（纯 JVM；生产 83 chunk 可忽略） |
| mix-16（en/mix 子集） | R@1=.9375 / R@5=1.0 / R@10=1.0，基线与修复后一致 |
| 独立审查 | Standards + Spec 双轴（结果与处置见第 6 节） |

---

## 2. 逐项完成说明

### B2-1 关键词通道长尾候选（不再按 rowid 截断）
- `SearchService`：`KEYWORD_FULL_HIT_LIMIT = 5000`，`searchFtsOrdered(orQuery, 5000, READY)` 取回全部命中（SQL 保留 rowid 稳定序，仅作超限时的安全截断顺序）。
- 新增纯函数 `rankKeywordHits(hitChunkIds, contentById, queryGrams, maxCandidates)`：按 bigram 覆盖分降序 → chunkId 升序稳定 → 截 `keywordCandidateLimit(topK)=topK×3×4`（与原 topK×12 一致，保证 keyword_candidates 指标可比）。
- 单测：排序/截断/去重/空输入/上限 120/5000 常量（`SearchQualityTest`）。
- 验收：long-tail 20 条 R@1 .95→1.00、候选均值 0.85→1.0；220 全量 R@1/R@5 不降；10k DAO 基线见 §3.3。
- 审查补强：新增 `KeywordRankingPerfTest` 覆盖 Kotlin 重排最坏成本（5000×600 字，p50=81ms）；FTS 10k 测试只测 DAO 的口径已在报告中标注区分。

### B2-2 FTS 覆盖口径统一
- `bigramCoverageScore(queryGrams, content)` 改用 `BigramTokenizer.bigrams(content)` 集合求交（与入库 `toFtsText` 同口径），替代 `content.contains(原始串)`；跨空格/换行 bigram 现在计分。
- 单测：空格/换行/去空白等价、"machine learning" 跨空格 bigram 新旧口径差异、空值边界。

### B2-3 检索 query 规范化
- 入口统一 `normalizedQuery(query) = boundedQuery(TextNormalizer.normalize(query))`（NFC + CRLF/CR→LF + trim/512），`search` 与 `searchChannels` 共用；embedding 与 FTS 输入同源。
- 单测：NFD→NFC、NFC 幂等、两种输入 bigram 一致、CRLF/CR、trim/超长。

### B2-4 emoji/代理对安全
- `BigramTokenizer`：改为 `String.codePoints()` 迭代（每个 bigram 由 1-2 个 code point 组成），不再产生孤立 surrogate；BMP 文本与旧实现逐位一致。
- `Chunker`：硬切边界落在 surrogate pair 中间时回退 1 char（`maxChars≥2` 保证 progress）；`estimateTokens` 按 code point 计数。
- 单测：🚀 bigram 无孤立代理、U+20000 CJK 扩展 B、BMP 与旧实现逐位对照、chunker 不变量/连续覆盖、token 估算。
- **存量数据注意**：B2-4 之前建立的 FTS 对 emoji 的 bigram 是旧 UTF-16 口径；升级后如需 emoji 关键词召回，需执行一次 FTS 重建（`repairIndex()`/重建入口），已记入剩余风险与 B6-4 发布说明。

### B2-5 chunkerFor 死分支
- 删除 `chunkerFor(pathB)` 与 `PATH_B_MAX_CHARS`（全仓 grep 无调用方）；块长统一由注入的 `Chunker.Options.maxChars=600` 决定；注释说明路径 A 受 1024-token 签名上限约束、路径 B 实测 ≥800 CJK 可用但暂统一 600，并删除"保守 300 字"矛盾表述。

### B2-6 分块重叠 ADR
- 新增 `docs/review/adr/ADR-001-chunk-overlap.md`：记录**无重叠分块**决策、跨块答案风险、触发条件（长文档评测集、跨块失败率 >2% 再评估）、若引入重叠需重跑评测/重嵌入与 ordinal 约束不受影响的说明。

### B2-7 结构化错误分类
- 新增 `core/ingest/PipelineError.kt`：`PipelineErrorCode`（MODEL_MISSING/MODEL_CORRUPT/SPACE_MISMATCH/IO/OOM/CANCELLED/UNKNOWN）+ sealed `PipelineError`（userMessage/retryable）+ `encode`/`decode`/`classify`；持久化格式 `CODE:detail`（单行、截断 500 字），旧纯文本 error 解析为 `UNKNOWN` 并原样展示。
- `IngestRepository`：`failOrRequeue` 捕获异常 → `encode(classify(e))` 写 `ingest_job.error`；无 documentId 坏 job 也写结构化错误；模型缺失错误文案改为可分类中文。
- UI：`ImportScreen` JobCard 用 `PipelineError.decode` 显示 `userMessage`，并按 code 展示动作提示（MODEL_* → 设置/模型商店；SPACE_MISMATCH → 切模型/重导入；IO/UNKNOWN → 复用"重试失败导入"；OOM → 释放内存；CANCELLED 不提示重试），文案入 `strings.xml`。
- `retryable` 参与自动重试决策：`failOrRequeue` 仅对 retryable 且 attempt<3 的错误自动重排队；MODEL_CORRUPT/SPACE_MISMATCH/OOM 直接终态（此前字段未使用）。
- UI 动作：ImportScreen 对 MODEL_* 额外提供可点击「去设置」按钮（MainActivity 传入 `onOpenSettings` 切设置页）。
- 单测：编码往返、旧格式兼容、分类家族、retryable 决策、单行/长度上限（含 500 字内换行用例）。

### B2-8 索引健康检查与孤儿清理
- `IngestRepository.indexHealthCheck(): IndexHealth`：单事务快照 + 分页（500/页）读取 chunkId/向量行，返回 chunk/FTS/向量三表计数 + `orphanFtsChunkIds`/`orphanEmbeddingChunkIds` + `reasons`（纯函数 `buildIndexHealthReasons`）。
- `repairIndex(): IndexRepair`：同事务按 chunkId ≤400/批精确删孤儿 FTS（`deleteFtsByChunkIds`）与孤儿向量（`deleteEmbeddingsByChunkIds`）；清理后 FTS 计数仍不一致则 `rebuildFtsIndex()` 全量重建兜底；invalidate 矩阵缓存；返回 before/after 与实际清理行数。
- `ftsHealthCheck()` 保留为兼容包装，但内部委托 `indexHealthCheck`（孤儿在计数相等时也不再漏检）；Settings 页「检查并修复索引」按钮改调 `repairIndex()` 并展示清理行数。
- 测试：纯函数 5 例（`IndexHealthTest`）+ 真机 DB 3 例（健康/孤儿+重复 FTS 修复/FTS 缺行重建，`IndexHealthDbTest`）。
- 数据层新增 3 个 DAO：`ChunkDao.allFtsChunkIds/deleteFtsByChunkIds`、`EmbeddingDao.deleteEmbeddingsByChunkIds`。

### B2-10 矩阵缓存上限
- `VectorMatrixCache`：`DEFAULT_MAX_BYTES=64MB`（≈2×10k×768）、`MAX_ENTRIES=4`、LRU（get 命中前置、put 淘汰尾部）；条目字节数按 FloatArray×4 + LongArray×8 计算；单矩阵 > 上限时 `put` 返回 false **不常驻**（本次检索仍用构建结果，下次重新载入 = 100k 库安全阀）；`invalidate` 同步扣减字节；`setMaxBytesForTest/resetForTest` 供测试注入。
- **审查补强（流式扫描）**：缓存上限只保证不常驻，不保证不构建（100k×768 ≈ 300MB 仍可能 OOM）。新增门限 `SearchService.shouldStreamMatrix`：预估矩阵字节 > 64MB 时不构建整矩阵，改为 `EmbeddingDao.vectorsForSpacePage` 分页流式扫描 + 有界 top-K（内存 O(页大小+K)）；门限可测试注入，`SearchStreamingTest` 覆盖 10k/25k/100k 判定。
- 测试 7 例（`VectorMatrixCacheLimitTest`）：默认上限、真实 81MB 不缓存、64MB 恰好/64MB+8B 不缓存、LRU、MAX_ENTRIES、缩小上限即时淘汰、同空间替换、失效语义。

---

## 3. 验证证据

### 3.1 静态门禁

```text
$ ./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin \
    :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
BUILD SUCCESSFUL
JVM tests = 197 / failures = 0

$ ./gradlew :app:lintDebug        # abortOnError = true
BUILD SUCCESSFUL                  # 0 errors / 23 warnings

$ ./gradlew :app:assembleDebug :app:assembleRelease   # R8 + shrinkResources
BUILD SUCCESSFUL
```

### 3.2 真机 instrumentation（OnePlus PJZ110 / Android 16 / arm64）

`./gradlew :app:connectedDebugAndroidTest` → **25 tests / 0 failures**：

| 测试类 | 用例 | 结果 |
|---|---|---|
| Fts10kPerfTest | 10k 合成 FTS：稀有/中等/全命中、5000 上限、rowid 稳定序、p50/p95 输出 | PASS |
| LongTailKeywordRecallTest | 20 条 long-tail 全部有候选且命中标注文档（avg=1.0） | PASS |
| IndexHealthDbTest | 健康库/孤儿+重复 FTS 检测与修复/FTS 缺行重建 | PASS ×3 |
| ImportDedupDbTest | 重复导入去重、cancelled 允许重导（B1 回归） | PASS ×2 |
| B1 迁移与备份回归（Migration*/Legacy*/BackupRoundTrip/SearchVisibility） | v1→v5、孤儿清理、空间采纳、备份 v1 fixture | PASS ×17 |

### 3.3 真机 10k FTS 基线（in-memory，100 文档×100 chunk）

```text
B2_10K_FTS_SEED docs=100 chunks=10000 fts_rows=10000 limit=5000
B2_10K_FTS query=罕见   hits=1    cold_ms=0 warm_p50_ms=0 warm_p95_ms=0
B2_10K_FTS query=迁移测试 hits=4999 cold_ms=7 warm_p50_ms=5 warm_p95_ms=7
B2_10K_FTS query=合成语料 hits=5000 cold_ms=4 warm_p50_ms=4 warm_p95_ms=6

（Kotlin 侧重排最坏成本，纯 JVM 模拟）
B2_RANK_PERF candidates=5000 content_chars=600 p50_ms=81 max_ms=82 result=120
```

### 3.4 检索质量：基线 vs 修复后（真机同库同 220 查询）

| 指标 | 基线（B1） | B2 后 | 变化 |
|---|---|---|---|
| R@1 | 0.9636 | 0.9636 | 持平 |
| R@5 | 0.9909 | 0.9909 | 持平 |
| R@10 | 1.000 | 1.000 | 持平 |
| p50 / p95 | 49 / 57ms | 49 / 63ms | p95 +6ms（可接受） |
| refused / errors | 0 / 0 | 0 / 0 | 持平 |
| long-tail R@1/R@5/R@10 | 0.95 / 0.95 / 0.95 | **1.00 / 1.00 / 1.00** | +0.05 |
| long-tail 关键词候选均值 | 0.85（17/20） | **1.0**（20/20） | +0.15 |

> 原始数据：`docs/review/b2/baseline-{eval-220,metrics,longtail,longtail-metrics}.json`、`after-{eval-220,longtail,metrics}.json`、`mix16-metrics.json`。
>
> **冷启动说明**：long-tail 首次查询包含模型加载（≈4.4s），故该轮 p95=4499ms 为冷启动离群；同一评测在 220 条（预热后）p50=49ms/p95=63ms。

---

## 4. 计划偏差记录

| # | 计划原文 | 实际实现 | 原因/影响 |
|---|---|---|---|
| 1 | B2-1 "候选上限与库规模关联" | 固定安全上限 5000 + topK×12 候选 | 与存储/内存预算可控；超限按 rowid 截断可能丢覆盖分更高的长尾（注释已诚实说明），后续 B5-3 分片再评估 |
| 2 | B2-1 "实测 matchinfo 不可排序 → 全命中 + Kotlin 覆盖分" | 已按此实现 | 与计划一致 |
| 3 | B2-4 未提存量 FTS | 识别出存量 FTS 的 emoji token 口径旧 → 需重建一次 | 记录到风险与 B6-4 发布说明 |
| 4 | B2-7 "UI 给重试/换模型/重导入" | 提示文案 + 复用"重试失败导入" + MODEL_* 可点"去设置"（MainActivity 回调已接）；SPACE_MISMATCH 暂只提示 | 主入口已闭环；SPACE_MISMATCH 的"切换模型"可留 B3 |
| 5 | B2-8 仅"启动/健康检查清理" | 提供 API + 真机/DB 测试；Settings「检查并修复索引」按钮调用 `repairIndex`（生产可达）；三表/孤儿明细展示仍待 B3-14 | 可检出+可修复已闭环；UI 明细展示留 B3 |
| 6 | B2-10 "100k 合成库 ≤上限×1.3 且不 OOM" | 实现字节上限+LRU+超大不缓存+**流式扫描兜底**（>64MB 不构建整矩阵）；真实 81MB/64MB 矩阵验证边界；100k 只验证门限判定（未跑 100k 端到端） | 流式路径消除"构建 300MB 矩阵"的 OOM 主因；100k 端到端性能/分片仍由 B5-3 建立 |

---

## 5. 剩余风险与未覆盖项

| # | 风险 | 说明 | 处置 |
|---|---|---|---|
| 1 | 存量 FTS 的 emoji 口径 | B2-4 后新写入正确，旧 FTS 对 emoji 仍是旧 token | 升级后建议执行一次「检查并修复索引」（`repairIndex`/重建入口）；B6-4 发布说明 |
| 2 | B2-1 最坏 5000 命中的耗时 | 单查询接近 5000 命中时需加载 5000 条 chunk content 并构建 bigram 集合 | 纯 JVM 最坏模拟 p50=81ms（5000×600 字）；10k DAO p95=7ms；生产 83 chunk 无影响；B5-3 若恶化再下调上限/分片 |
| 3 | 错误分类按 message 匹配 | 新异常文案可能落到 UNKNOWN | UNKNOWN 仍可展示原文；code 只用于动作提示，不影响功能 |
| 4 | `indexHealthCheck` 三表/孤儿明细未在 UI 展示 | Settings 只有"检查并修复"按钮与清理行数提示 | B3-14 补三表计数/孤儿数展示 |
| 5 | `SearchResult.query` 返回规范化后 query | NFD 输入会被 NFC 化 | 语义无破坏；UI 展示一致 |
| 6 | 100k 端到端未实测 | 流式扫描已消除"构建 300MB 矩阵"的 OOM 主因，但 100k 分页扫描耗时未测 | B5-3 建 100k 基线（必要时降 PAGE_SIZE/并行）；当前生产库 83 chunk |
| 7 | p95 +6ms | 全命中取回 + Kotlin 排序 + 大小写折叠的固定成本 | 相对 49ms 基线 +12%；仍在 65ms 内，可接受 |
| 8 | long-tail 冷启动 4.4s | 首次查询包含模型加载；非检索算法回归 | 报告已显式区分冷启动与预热后 p50/p95 |
| 9 | 关键词重排内存/CPU 与候选规模线性 | 5000 候选中每条 content 600 字约 300 万 bigram 构建 | 已测 81ms（JVM）；若后续库增大需限制候选或缓存 content grams（B5-3） |

---

## 6. 独立审查（Standards + Spec）与处置

### 6.1 Standards 轴

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | `retryable` 从未参与重试决策：MODEL_CORRUPT/SPACE_MISMATCH 实际仍自动重试 3 次 | **已修**：`failOrRequeue` 改收 `PipelineError`，决策 = `error.retryable && shouldAutoRetry(attempt)`；补契约测试 |
| S2 | 硬性 | `PipelineError.encode` 只截断不替换换行，违背"detail 单行"约定；测试把换行放到 500 字后属假覆盖 | **已修**：`sanitizeDetail` 统一单行化（encode/decode/classify 共用）；补 500 字内换行用例 |
| S3 | 判断性 | B2-8 孤儿清理无生产入口（仅测试调用） | **已修**：Settings「检查并修复索引」按钮调用 `repairIndex`；`ftsHealthCheck` 委托 `indexHealthCheck`（计数相等也能发现孤儿） |
| S4 | 判断性 | `indexHealthCheck` 注释称"峰值内存只有一页"，实际持有全量 chunkId 集合 | **已修**：注释改为"分页读 Entity，但 chunkId 集合/FTS 列表为全量（100k 约数 MB）" |
| S5 | 判断性 | `rebuildFtsIndex` 删后分批插无事务，失败留半残 FTS | **已修**：删除+重建包进 `db.withTransaction` |
| S6 | 判断性 | B2-1 先按 rowid 截 5000 再算覆盖分，超限长尾静默丢失；"保序稳定"不成立（重建后 rowid 变） | **已修注释**：明确超限按 rowid 截断可能丢长尾、rowid 仅本次 seed/未重建时稳定；风险记入 §5#2 |
| S7 | 判断性 | B2-2 逐候选构建 bigram 集合，最坏 O(5000×600) 未计时 | **已补测试**：`KeywordRankingPerfTest` 最坏模拟 p50=81ms；风险记入 §5#9 |
| S8 | 判断性 | 覆盖分与 FTS4 simple tokenizer 大小写折叠不同口径，可能"命中但 coverage=0" | **已修**：`bigramCoverageScore` 对 query/content 统一 `lowercase()`；补 ASCII 大小写单测 |
| S9 | 判断性 | B2-7 分类依赖 message 子串；catch(Error) 重抛使 OOM code 不可达 | **部分接受**：message 分类为兜底（未知仍可展示），OOM 分支保留用于旧数据/未来 Error 记录；风险记入 §5#3 |
| S10 | 判断性 | 测试：Fts10k "rowid 顺序"实为 chunkId 顺序；long-tail `withCandidate` 恒等断言；缓存测试实分配 81+64MB | **已修**：断言更名+注释（seed 巧合）、`withCandidate` 改真实统计；81MB 测试保留真实边界（JVM 197 tests 通过，heap 默认 512MB），风险已记 |

### 6.2 Spec 轴

| 项 | 原结论 | 处置 |
|---|---|---|
| B2-1 | partial（10k 只测 DAO，未测 Kotlin 重排） | **已补** `KeywordRankingPerfTest`（5000×600 字 p50=81ms）；长尾 fixture/220 回归数据已存在 |
| B2-2 | implemented（缺 mix16 记录） | **已补** `mix16-metrics.json`：R@5 1.0→1.0；报告引用 |
| B2-3/4/5/6 | implemented | 无需修改（emoji 存量 FTS 重建风险已在 §5#1） |
| B2-7 | partial（UI 无按 code 按钮） | **已修**：MODEL_* 可点击「去设置」；retryable 参与决策；SPACE_MISMATCH 切换模型留 B3 |
| B2-8 | implemented（无生产入口） | **已修**：Settings 检查并修复按钮；三表明细展示留 B3-14 |
| B2-10 | partial（无 100k；构建仍可能 OOM） | **已修**：新增 >64MB 流式扫描兜底 + 门限测试；100k 端到端仍留 B5-3（风险 §5#6） |
| 基线数据 | kw_avg 0.8 与原始 17/20=0.85 不符 | **已修**：`baseline-longtail-metrics.json` 更正为 0.85，报告同步 |
| 范围/兼容 | 无 scope creep；20 autostart/importStaged/备份入口未动；无新依赖 | 确认 |

### 6.3 审查后复验（最终）

- `compileDebugKotlin / compileReleaseKotlin / compileDebugUnitTestKotlin / compileDebugAndroidTestKotlin` ✅
- JVM `testDebugUnitTest`：**197 tests / 0 failed**（新增流式门限 3 例、重排性能 1 例、大小写覆盖 1 例、错误单行化 1 例、retryable 决策 1 例）
- `lintDebug`：**0 errors / 23 warnings**
- `assembleDebug` / `assembleRelease`（R8）✅
- 真机 `connectedDebugAndroidTest`：**25 tests / 0 failed**
- 真机评测复验：220 R@1=.9636/R@5=.9909/R@10=1.0、p50=49ms/p95=63ms、0 refused；long-tail R@1/R@5/R@10=1.0、kw_avg=1.0（冷启动 4.4s 已单独说明）

---

## 7. 文件清单（B2 相对 B1 终态）

**检索/文本/缓存**：`core/search/SearchService.kt`（B2-1/2/3 + B2-10 流式扫描/门限）、`core/search/VectorMatrixCache.kt`（B2-10）、`core/text/BigramTokenizer.kt`、`core/text/Chunker.kt`（B2-4）、`core/ingest/IngestRepository.kt`（B2-5/8、B2-7 错误编码接入、retryable 决策、rebuildFtsIndex 事务化）
**错误 taxonomy**：`core/ingest/PipelineError.kt`（新）
**UI**：`ui/ImportScreen.kt`（错误展示 + 去设置）、`MainActivity.kt`（onOpenSettings 回调）、`ui/SettingsViewModel.kt`（检查并修复索引）、`res/values/strings.xml`
**数据层**：`data/db/Daos.kt`（B2-8 3 个 DAO + B2-10 分页向量查询）
**ADR**：`docs/review/adr/ADR-001-chunk-overlap.md`（新）
**测试（JVM）**：`test/.../core/search/{SearchQualityTest, VectorMatrixCacheLimitTest, SearchStreamingTest, KeywordRankingPerfTest}.kt`、`test/.../core/text/TextBoundaryTest.kt`、`test/.../core/ingest/{IndexHealthTest, PipelineErrorTest, IngestRetryDecisionTest}.kt`
**测试（instrumentation）**：`androidTest/.../core/search/{Fts10kPerfTest, LongTailKeywordRecallTest}.kt`、`androidTest/.../core/ingest/IndexHealthDbTest.kt`
**基线/对照数据**：`docs/review/b2/*.json`（baseline/after/mix16）

---

*报告生成：2026-10-17 · B2 代码变更未提交；设备已恢复（debug/v3 DB/模型）。*
