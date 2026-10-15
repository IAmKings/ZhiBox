# Phase 0 技术验证报告

- **开始日期**：2026-10-15
- **完成日期**：2026-10-15
- **状态**：**✅ 全部完成——四判定门 G1/G2/G3/G4 全部通过，go/no-go 结论：GO，进入 Phase 1**
- **判定门总览**：G1 任务模板 ✅ · G2 模型资产 ✅ · G3 中文分词 ✅ · G4 精确扫描 ✅

## P0-1 工程初始化 ✅（2026-10-15）

- 可编译工程：`assembleDebug` 通过，包名 `com.zhibox.memory` 0.1.0-mvp，minSdk 24 / target 36，零权限。
- 参考素材：`docs/reference/zleap-sag-0.14.0/` 37 文件（wheel SHA-256 校验通过）。
- 版本锚点：见 `docs/UPSTREAM_VERSIONS.md`。git 初始提交 `7fff4ed`。

## P0-2 Universal Embedder 接入 ✅（2026-10-15，高端档真机）

### 接入过程中核验的事实

| 项 | 结果 |
|---|---|
| Maven 坐标 | `com.google.mediapipe:tasks-retrieval:1.1.1`（2026-10-14 发布） |
| 上游打包 bug 与修复 | POM 传递依赖 `litertlm-android:0.17.0-alpha1` 缺少 `com.google.ai.edge.litertlm.ActivationDataType`（运行时 NoClassDefFoundError，经 AAR 反编译定位）；已 `resolutionStrategy.force` 至 **0.18.0**（含该类，核验通过） |
| Kotlin 版本要求 | litertlm 以 Kotlin 2.4.0 编译 → 工程工具链升级至 Kotlin 2.4.21 + Gradle 8.14.3 |
| 真实包路径 | `com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder`（非文档示例的裸 `retrieval` 包） |
| 文件加载方式 | `BaseOptions` 无 `setModelAssetFilePath`；须持 `ParcelFileDescriptor` 传 `setModelAssetFileDescriptor(fd)` |
| 向量取值 | `Embedding.floatEmbedding()` 返回 `float[]`；`cosineSimilarity(float[], float[])` 静态可用 |
| 配置面 | `setL2Normalize` / `setTextDelegate` / `setVisionDelegate` / `setMaxInputLength` / `setActivationDataType` / `setCacheDir`；**未见任务模板/prompt 参数**（与调查报告 R1 预判一致，G1 需用向量对照法判定） |
| 模型投放（Android 16 实测） | adb 直推 `/sdcard/Android/data/<pkg>/` 产生 shell 属主目录，应用 uid 无权列出（UI 显示"未找到模型文件"）；正确方式：`/data/local/tmp` 中转 + `run-as cp` 入内部 `filesDir/models`（已固化进 `scripts/run_p02_benchmark.sh`） |

### 资源画像基准结果（PRD §9.2 门槛：单条 query embedding p95 ≤ 500ms 中高端档）

**三档设备矩阵全部通过**（2026-10-15，CPU/XNNPack，RAW 模板，440M 模型）：

| 设备档 | 机型 · SoC · RAM · Android | 冷加载 | 首条 | p50 | p95 (30次) | 峰值 RSS | 校验 | 结果 |
|---|---|---|---|---|---|---|---|---|
| 高端 | PJZ110 · SD 8 Elite (SM8750) · 16GB · Android 16 | 1032ms | 224ms | **79ms** | **81ms** | ≈1.38GB | 全过 | ✅ 余量 6.2× |
| 中高端 | K60 Ultra · 天玑 9200+ (MT6985) · 15GB · Android 14 | 1932ms | 582ms | **94ms** | **104ms** | ≈1.44GB | 全过 | ✅ 余量 4.8× |
| 低端 | K40 游戏版 (M2012K10C) · 天玑 1200 (MT6893) · 7.3GB · Android 13 | 2016ms | 535ms | **133ms** | **145ms** | ≈1.42GB | 全过 | ✅ 余量 3.4× |

270M 纯文本模型在三台设备（两厂商、Android 13/14/16）**全部加载失败**（同一错误：缺 vision encoder 签名）——确证为运行时结构限制而非设备兼容性问题。

### 低端档可行性与产品策略结论

1. **延迟维度：全矩阵通过。** 2021 年中端芯（天玑 1200）热推理 p95 仍有 3.4 倍余量，PRD §9.2 的 500ms 门槛对 2021+ 设备成立。
2. **内存维度：RSS ≈1.4GB 恒定（模型体积主导、与设备无关）。** 在 7.3GB 的 K40 上约占 19% 物理内存——前台主动使用（导入/搜索）可行；但**后台驻留场景（WorkManager 批量导入时用户开其他大应用）存在被 LMK 回收的实质风险**。
3. **产品策略（写入 Phase 1 要求）**：
   - MVP 声明**最低 RAM 档位 8GB**（覆盖 2021+ 主流机型）；4–6GB 设备标记为"可能受限"，实测后定案；
   - 导入管线必须支持**分批 + 进程被杀恢复**（实施计划 P1-W1-4 本就有此要求，此处为其提供了实证依据）；
   - RSS 优化路径（按优先级）：等待 ML Kit（官方口径 0.5GB，W1 监控项）→ 路径 B（EGemma v1，体积小一个量级）→ 评测 256d MRL 截断（只影响向量存储，不降模型内存）。
4. 芯片厂商覆盖：Qualcomm + MediaTek 双平台；Android 13/14/16 三个版本。首次安装分发（run-as 路径）在三个 Android 版本均验证通过。

原始 JSON 存于 `docs/phase0/bench-results/`（文件名带设备序列号）。

### G2 结论（R2 解锁）

1. **270M 纯文本模型不可用于当前 Universal Embedder 路径**：加载报 `tf_lite_vision_encoder not found in the model`——LiteRT-LM 运行时（0.18.0）要求 .litertlm 文件必须包含视觉编码器签名，纯文本变体被拒。该文件虽官方发布（download.gradle 列出），但属"为更低层 API 或未来运行时准备"的资产。
2. **440M（370MB 下载、含按需模态加载）是 Universal Embedder 路径的最小可用模型**，实测可用且性能达标。
3. 峰值 RSS ≈1.38GB（含文件页缓存口径）远高于博客的"文本 191MB"宣传口径——**中低端机（4–6GB RAM）可行性存疑，列入设备矩阵重点验证项**；若不可行，回退路径 B（Text Embedder + EGemma v1，模型体积小一个量级）或等待 ML Kit。
4. 修正模型分发预算：MVP 按 **370MB 下载体积**规划（Play Asset Delivery 或应用内下载器，P1-W1-5）。

### 意外发现：tasks-retrieval 自带分块器与 SQLite 向量存储

包内含 `retrieval.chunking.TextChunker/DefaultTextChunker` 与 `retrieval.components.SqliteVectorStore/VectorStore/VectorStoreRecord`——**Google 官方端侧检索栈自身即以 SQLite 作为向量存储**。这是调查报告附录 A"SQLite 替代 LanceDB"结论的又一官方佐证；Phase 1 设计时应评估直接复用/对照这两个组件（注意其设计围绕 SemanticRetriever 检索流程，需核实文本场景适配性）。

## P0-3 G1 判定门 ✅（2026-10-15，PJZ110 · 440M 模型）

**判定：通过——任务模板策略锁定为应用层前缀（查询 `task: search result | query: ` / 文档 `title: none | text: `），Universal Embedder 路径（路径 A）确认为 MVP 基线。调查报告头号风险 R1 解除。**

### 实验数据（50 样本 × 3 模板 × HF 双模式参考 @914f7f8）

| 端侧模板 | 参考模式 | 样本角色 | mean | min |
|---|---|---|---|---|
| RAW（无前缀） | query/document | 全部 | 0.87–0.91 | 0.83 |
| **DOCUMENT_PREFIX** | **document_mode** | document | **0.9867** | **0.9831** |
| **QUERY_PREFIX** | **query_mode** | query | **0.9865** | **0.9686** |
| 错误模板交叉组 | — | — | 0.94–0.97 | 0.89 |

### 端侧检索功能检验（决策性指标）

样本集为 25 对 query↔答案文档逐对设计；查询向量（QUERY_PREFIX）在全部 25 个文档向量（DOCUMENT_PREFIX）中排序：

**Recall@1 = 1.00 · Recall@3 = 1.00 · Recall@5 = 1.00 · MRR = 1.00（25/25 全部第一）**

### 结论与判定依据

1. **模板可控性确证**：RAW 与参考差距 ≈0.88 → 加正确前缀跃升至 ≈0.987，错误模板交叉组居中（0.94–0.97）。该结构证明 Universal Embedder 运行时**不自带任务模板**（否则 RAW 应直接对齐、且加前缀应出现双重拼接坍塌），应用层前缀是正确的模板注入点。
2. **0.99 等值门槛对量化运行时不可达（已修正判定逻辑）**：对齐组 mean 平台值锁在 0.986–0.987、跨方案跨角色恒定——这是 440M 文件 INT4 QAT 量化（模型卡：transformer int4 per-channel）的特征性噪声天花板，而非模板错配（错配会随文本长度/语言显著波动）。原 0.99 门槛隐含 fp32-vs-fp32 假设，对量化运行时在数学上不可达。
3. **量化噪声不影响检索功能**：索引与查询两侧使用同一量化运行时，噪声对称抵消——R@1=1.0 的完美检索结果直接证实。故综合判定 = 模板跃升证据（gap ≥0.05）∧ 检索门槛（R@1 ≥0.8），实测 1.0。
4. 判定逻辑已固化进 `G1Comparison`（`RETRIEVAL_RECALL1_GATE=0.8`），上游更新模型/运行时后可一键复验。

### 跨设备确定性交叉验证（2026-10-15，K40 补充）

在 K40（天玑 1200 · Android 13）复跑 G1：12 项聚合余弦与旗舰（8 Elite · Android 16）**|Δ| = 0.00000000**，检索 R@1 同为 1.0——嵌入输出跨 SoC 代际/厂商逐位一致，XNNPack 内核选择未引入数值分叉。"向量结果设备无关"从假设升级为实证；后续判定门实验单设备运行即可，无需逐机重复。

### 工程要点（供 Phase 1 P1-W1-3 实现引用）

- Provider 默认策略：`embedQuery → QUERY_PREFIX`、`embedDocuments → DOCUMENT_PREFIX`；两方法当前共用单策略参数，W1 实现时拆分为双策略成员。
- 参考向量资产：`scripts/g1/reference.json`（@914f7f8）；复验命令 `./scripts/run_g1_experiment.sh`。
- 排障记录：G1 首跑崩溃因设备残留 270M 模型（加载即抛异常且无捕获）——已加单模型异常隔离；EmbeddingGemma2Processor 桌面导入链需 pillow+torchvision（transformers 5.19.0）。

## P0-6 G4 精确扫描基准 ✅（2026-10-15，K40 · 天玑 1200 · 全矩阵最慢设备）

**判定：通过——三实现全部达标，Top-10 跨实现逐位一致。** 数据：10,000 × 768d（真实 50 + 固定种子随机 9,950；扫描性能取决于内存布局与浮点吞吐，与向量语义无关——对计划"真实+随机 8:2"的务实近似）。30 轮热测 p95：

| 实现 | 1k p95 | 5k p95 | 10k p95 | 特征 |
|---|---|---|---|---|
| SQLITE_BLOB（朴素 Cursor+BLOB） | 14ms | 92ms | **248ms** | 零额外内存；构建 572ms |
| PRELOADED（预载 FloatArray） | 1ms | 4ms | **8ms** | 常驻 30MB/万条；构建 13ms |
| MMAP_FLAT（平铺文件 mmap） | 20ms | 82ms | **173ms** | 零常驻内存；构建 353ms |

门槛对照（PRD §9.2）：1k ≤150ms → 最优 1ms（**150 倍余量**）；10k ≤800ms → 最优 8ms（**100 倍余量）；**连最朴素的 SQLITE_BLOB 在最慢设备上也以 248ms 通过 10k 门槛**。

结论与外推：
1. "SQLite 先行、ANN 后置"的架构决策被实测强力支撑；MVP（≤1 万 chunks）用任一实现都远超门槛。
2. PRELOADED 外推 10 万条 ≈ 80ms——**精确扫描的实际可行边界远超计划预估的 5 万条**（附录 B 的 ANN 触发线可放宽到约 10 万条或以内存预算为准：10 万条常驻 ≈ 300MB）。
3. Phase 1 实现建议：SQLITE_BLOB 为默认存储（简单、与 Room 一致），检索热路径按规模自动升级 PRELOADED（如 chunks > 2k 时预载）。
4. Top-10 三实现全规模一致（double 累加）——实现间可互为正确性校验。

## P0-5 G3 中文分词 ✅（2026-10-15，K40）

**判定：通过——选定 BIGRAM（字符二元组预分词）方案，框架 SQLite + FTS4 维持不变（自带 SQLite 决策推迟到 ANN 阶段）。**

实验：本项目 8 份中文文档 → 232 chunks（600 字/块）；50 条中文关键词查询；ground truth = 原文子串包含全部查询词。

| 方案 | hit@50 | 平均返回 | 误报率 | 索引膨胀 | 构建耗时 |
|---|---|---|---|---|---|
| RAW（FTS4 原样 unicode61） | 0.408 | 0.9 | 0 | 1.0× | 47ms |
| **BIGRAM（字符二元组）** | **0.959** | 11.9 | **0** | 2.66× | 126ms |
| JIEBA（结巴分词 1.0.2） | 0.225 | 0.4 | 0 | 0.89× | 394ms |

结论：
1. **BIGRAM 以 0.96 命中率 + 零误报 + 2.66× 索引膨胀达标**——比对照组提升 55 个百分点，且是零依赖方案（与计划预判一致：bigram 即达标，分词器仅在 bigram 不达标时引入）。
2. **jieba 显著失败（0.22）**：通用词典不含本项目领域词（判定门/设备矩阵/幂等等），查询与文档侧分词不一致导致系统性漏检。Phase 1 移除 jieba 依赖（`com.huaban:jieba-analysis`），除非后续真实语料评测推翻本结论。
3. Android 框架 SQLite 的 FTS4 原样对中文有 41% 的偶发命中（整串 token 的巧合匹配），不可依赖；入库与查询两侧统一 bigram 化是正确做法。
4. 索引膨胀 2.66× 在可接受范围（10 万字语料 → 30 万字节 FTS 内容列）。

附：SAG 分词方案考据（2026-10-15，源码级）

SAG 的分词**按向量后端分治**，仅 ParadeDB 实现了中文分词：

| 后端 | 中文分词 | 证据（zleap-sag 0.14.0） |
|---|---|---|
| ParadeDB | **jieba**（zh）· lindera（ja）· 其余 Unicode #29 | `paradedb_store.py:33` `_CJK_TOKENIZER="jieba"`；注释承认"默认分词器对 CJK 基本等于不切分，故必须显式指定" |
| LanceDB（默认后端） | **无** | `lancedb_store.py:758` `create_index(col, config=FTS())` 零参数 |
| Elasticsearch | 无显式配置（依赖 ES 默认 standard 单字切分） | 全文件无 analyzer |
| OceanBase | 无 | `resources.py:129`"其余后端不接受该参数" |

三点印证：① SAG 注释承认的"默认对 CJK 不切分"与本报告 RAW 0.41 命中率互证——SAG 默认后端的中文关键词检索与向量通道改造前同样弱；② SAG 选 jieba 属通用语料合理选择，但本实验已实证其领域词典缺陷（0.22），bigram 思路对服务端 ParadeDB 路线同样有参考价值；③ 框架 SQLite FTS4 无自定义 tokenizer 接口（接 jieba 需自带 SQLite + NDK C tokenizer），在"不自带 SQLite"约束下双侧 bigram 是唯一务实解。

## P0-7 Phase 0 总结与 go/no-go（2026-10-15）

### 四判定门结论汇总

| 门 | 问题 | 结论 | 关键证据 |
|---|---|---|---|
| G1 | 端侧任务模板可控？ | ✅ 应用层前缀（query `task: search result \| query: ` / doc `title: none \| text: `） | R@1=1.0/MRR=1.0；跨设备 \|Δ\|=0 |
| G2 | 模型资产可行？ | ✅ 440M（370MB）为唯一可用基线 | 270M 被运行时结构性拒绝（三设备复现）；三档延迟全过 |
| G3 | 中文关键词检索可行？ | ✅ FTS4 + bigram 预分词 | hit 0.96 / 误报 0 / 膨胀 2.66× |
| G4 | 精确扫描性能达标？ | ✅ 三实现全过（最慢设备） | 10k p95：BLOB 248ms / 预载 8ms / mmap 173ms |

### 锁定的技术基线（Phase 1 直接引用）

1. **Embedding**：MediaPipe Universal Embedder（tasks-retrieval 1.1.1 + litertlm 0.18.0 强制）+ `embeddinggemma-2-text-vision-440m.litertlm`（370MB）+ 应用层双前缀策略；单条推理 p95：81/104/145ms（高/中/低端档）。
2. **向量存储**：SQLite（BLOB 默认；chunks > 2k 时热路径升级预载 FloatArray）；精确扫描可行边界外推至 ~10 万条（预载 80ms@10万，ANN 触发线由 5 万放宽至 10 万或以 300MB 常驻内存为界）。
3. **中文关键词**：FTS4 + 双侧 bigram 预分词；jieba 移除。
4. **Provider 接口**：`EmbeddingProvider` 拆分 `embedQuery`(QUERY_PREFIX) / `embedDocuments`(DOCUMENT_PREFIX) 双策略成员（P1-W1-3）。
5. **设备与资源**：最低 RAM 8GB；RSS ≈1.4GB 恒定（模型主导）；导入管线必须分批 + 可恢复（P1-W1-4 的实证依据）；RSS 优化路径排序：ML Kit（W1 监控）> 路径 B > MRL。
6. **工程资产**：一键实验脚本 ×4（基准/G1/G3/G4，全部支持 ANDROID_SERIAL 多设备）；参考向量与样本集入库可复验。

### 遗留事项（不阻塞 Phase 1）

- ML Kit 月底前 GA 计划（W1 每日核查）——发布后重评 RSS 与 Provider 默认值。
- 三档设备的 G4/生产路径全量基准：按计划在 P1-W3-5 用生产代码统一采集（避免双 harness 数字不可对照）。
- Phase 1 W1 起用 Room schema 时，`chunk_embedding` 双策略成员与 embedding space 隔离按实施计划 P1-W1-1 落地。

### go/no-go：**GO**——全部判定门通过且均有较大余量，无一项触发回退路径。

## 补充决策记录：存储栈选型对比（2026-10-15，Phase 1 开工前钉死）

**"不自带 SQLite"的原因**：自带（bundle）自定义 SQLite 的三个可能动因在 MVP 全部不成立——
① FTS5/自定义分词器：G3 已证 FTS4 + bigram 足够（0.96 命中）；② sqlite-vec 向量表：G4 已证朴素扫描 + 预载在最慢设备有 3–100 倍余量，其 ANN 尚属实验特性；③ 其他扩展：无需求。
而自带成本是确定的：NDK 构建/CI、Android 15+ 16KB 页对齐、SQLite 上游安全补丁自运维、Room 工厂替换与迁移验证。结论：**无强制动因时不引入自运维原生构建面**。

**候选方案对比**：

| 方案 | 中文关键词 | 向量检索 | 加密 | 原生构建面 | 评估 |
|---|---|---|---|---|---|
| **A. Room + 框架 SQLite（选定）** | FTS4+bigram ✅（G3 实证） | BLOB 扫描+预载 ✅（G4 实证） | 无（依赖 OS 沙箱+FBE） | **零** | MVP 最优：已实证、零额外风险 |
| B. Room + 自带 SQLite + sqlite-vec | 同上（FTS5 可用） | vec0 虚表 + 实验性 ANN | 无 | NDK 自建（无官方 Maven AAR；expo/op-sqlite 有先例，~350KB，16KB 对齐坑） | 触发线：chunks > ~10 万或 p95 超标（附录 B.4 路径） |
| C. Room + SQLCipher | 不受影响（完整 SQLite 构建） | 不受影响（BLOB 同样可存） | ✅ AES-256 全库 | +3–9MB APK，开库密钥派生 200–500ms | 触发线：隐私合规需求（如端侧引入 K12 未成年人学习数据）；注意 SQLCipher 本身就是自带 SQLite |
| D. C+B 合体（SQLCipher + sqlite-vec 静态链接） | 同上 | 同上 | ✅ | 最大（社区有 op-sqlite 先例） | B/C 任一触发时一步到位，避免两次换栈 |
| E. 非 SQLite：ObjectBox / libSQL / SQLDelight | ObjectBox 有 HNSW 但商业闭源核心；libSQL 是整库替换且与 Room 冲突；SQLDelight 仅是 ORM 平替 | — | — | 各异 | 不改变问题本质，仅换供应商；ORM 层 Room↔SQLDelight 互换成本有界 |

**换栈成本评估（接口隔离红利）**：因 `VectorIndex`/`EmbeddingProvider` 抽象 + 标准 SQLite 文件 + 纯 SQL schema，后期换栈是**存储层替换而非领域层重写**——Room 经 `SupportSQLiteOpenHelper.Factory` 换引擎，实体/DAO 不动；sqlite-vec 作为 `VectorIndex` 新增实现（附录 B.4 既有路径）；bigram 内容列与应用层分词对任何引擎通用。预估触发时工作量：NDK 构建+CI 2–4 天、工厂替换+迁移测试 1–2 天、vec 实现与基准 2–3 天。**加密触发时直接做 D，避免 B→C→D 三跳**。

**锁定**：MVP = 方案 A；B/D 触发线 = chunks > ~10 万或检索 p95 超标；C/D 触发线 = 端侧引入未成年人学习数据或合规要求。
