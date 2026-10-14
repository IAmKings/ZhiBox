# SAG Android 端侧 PRD 深度可行性调查报告

- **文档版本**：1.0
- **调查日期**：2026-10-13
- **调查对象**：[SAG_Android_OnDevice_Feasibility_PRD.md](./SAG_Android_OnDevice_Feasibility_PRD.md)（v1.0，2026-10-13）
- **文档用途**：任务规划与立项决策凭证；Phase 0 决策门的修订依据
- **调查方法**：对 PRD 引用的全部外部事实（SAG 仓库、Google 官方博客、MediaPipe 官方文档、HF 模型仓库文件树、sqlite-vec/FTS5 Android 生态）逐一核验，并补充第一性原理性能核算
- **关联文档**：[Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md](./Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md)（最终项目目标）

---

## 总体结论

**PRD 的核心判断（"可行，但重写端侧数据引擎而非移植 Python 应用"）经独立核实后成立，且其所有可查证的外部事实全部准确。**

调查发现了 **三个 PRD 未覆盖或覆盖不足的新风险**（任务模板接口缺失、模型分发比预期复杂、中文分词问题），需要在 Phase 0 决策门中补充验证项。

**建议：批准 Phase 0 预研（按本报告第五节修订后的决策门，工期放宽至 5–7 个工作日），架构路线维持 PRD §14 的推荐不变。**

---

## 一、外部事实核查：PRD 前提全部属实

| PRD 声明 | 核查结果 |
|---|---|
| SAG = Next.js + FastAPI + Python `zleap-sag` 引擎，SQLite + LanceDB 默认后端，LLM 做事件/实体抽取 | ✅ 与仓库一致（zleap-sag 当前 pin 0.13.0，Python 3.11+，MIT） |
| Google 2026-10-06 博客：740M 参数、Pixel 11 Pro 文本约 191MB / 多模态约 567MB 活动内存 | ✅ 博客原文逐字吻合 |
| ML Kit "未来几周"提供，NPU 加速 + 模型自动更新 + 不增加 APK 体积 | ✅ 博客原话；**截至 2026-10-13（公告后一周）仍未发现 GA 的 API 页面**，PRD 不把它作为硬依赖的立场正确 |
| MediaPipe Universal Embedder 存在，`tasks-retrieval` 依赖（直接依赖 LiteRT-LM），官方示例模型 `embeddinggemma-2-text-vision-440m.litertlm` | ✅ 官方文档页存在（2026-10-06 更新），API 为 `UniversalEmbedder.createFromOptions` + `embedText/embedImage/embedAudio/embedContent`，示例工程在 mediapipe-samples 仓库 `examples/universal_embedder/android`，min SDK 24 |
| 旧版 Text Embedder 支持 `TextFormatContext` 任务类型 | ✅ `tasks-text` 依赖，`TextEmbedder.embed(text, formatContext)`，支持 l2_normalize / quantize |
| sqlite-vec 需自带 SQLite 构建，不能加载进 Android 系统 SQLite | ✅ 且比 PRD 预想的更成熟：expo-sqlite SDK 54 已预编译分发、OP-SQLite、Flutter 生态静态链接（增量约 350KB）均有生产案例；需注意 Android 15+ 的 16KB 页对齐要求 |

PRD 对自身结论的把握度（"不建议直接打包 Python SAG"、"LanceDB 不上 Android"、"向量层可替换"）与工程最佳实践一致，无需修正。

---

## 二、新发现的风险（PRD 未覆盖，本报告核心增量）

### R1（高）：Universal Embedder 的公开 API 没有任务模板接口——非对称检索质量存疑

本次调查最重要的发现，与最终目标 PRD 里 SAG 服务端的 query/document prompt 问题**同构**。

EmbeddingGemma 2 的检索质量依赖非对称 prompt（查询用 `SearchQuery`，文档用 `title: {title} | text: {content}` 格式）。但核查结果显示：

- **Universal Embedder**（EGemma 2 的官方 Android 路线）的文档和示例代码中，`embedText(String)` 之外**没有任何 prompt / 任务模板 / 查询-文档区分的参数**，连 MRL 维度截断（768→256/128）也未见暴露；
- **旧版 Text Embedder** 反而有 `TextFormatContext` 任务类型机制，但那条路线对应的是 EmbeddingGemma **v1**（纯文本旧模型），不是 2 代。

PRD §4.1 设计的 `EmbeddingDescriptor.queryTaskTemplate / documentTaskTemplate` 字段，目前在 EGemma 2 官方端侧 API 上**可能无处安放**。风险链：若 `embedText` 内部统一套用某个模板（或无模板），查询与文档向量会落在次优的对称空间，中文召回可能明显低于 HF 原版模型表现——而 PRD 的检索质量验收（Recall@5 等）会因此不达标，且团队可能误判为"模型不行"。

**缓解（应写入 Phase 0）**：用同一批文本对照三种方式生成的向量——HF Sentence Transformers 原版（带正确 prompt）、Universal Embedder 原文、Universal Embedder 手工拼接 prompt 前缀——与 HF 参考向量算余弦：

- 若手工前缀能对齐参考向量（余弦 ≥ 0.99），说明运行时不加模板，可在应用层拼接 prompt；
- 若对不齐（运行时强加了自己的模板），则 EGemma 2 端侧非对称检索暂时无解，MVP 应回退 Text Embedder + EmbeddingGemma v1 基线（PRD §5.1 的路线恰好可用），EGemma 2 等待 ML Kit API。

### R2（中高）：模型分发的真实状况比 PRD 复杂

`litert-community/embeddinggemma-2-740m-litert-lm` 仓库的完整文件树：**只有 740M 全模态变体，共 11 个文件（485–895MB/个）**——包括按芯片编译的 NPU 专用构建（Google Tensor G5/G6、Qualcomm SM8550/8650/8750/8850、MediaTek MT6991/6993、Intel PTL）。三个推论：

1. 模型卡表格里的 "Text 270M / 165MB" 变体**在该仓库没有对应文件**；纯文本 MVP 可能被迫携带 388MB 的 text+vision 440M 文件（靠 on-demand modality loading 只激活文本）。270M 纯文本版是否存在需在 Phase 0 实查。
2. 每芯片一份的 NPU 构建意味着**自建 NPU 路线需要按设备 SoC 型号选择并分发文件**——印证了 PRD 把 NPU 留给 ML Kit（托管模型分发）的决策是对的。
3. 官方示例把模型放 `assets` 打进 APK 的做法对 388MB 模型不可取；PRD §8 的"下载校验 / 断点续传"设计需落实为 Phase 1 任务（Play Asset Delivery 或应用内下载器），而非 Phase 3。

### R3（中高）：中文关键词检索的分词问题——对最终 K12 目标是必经之路

PRD §4.2 提到"FTS5 是否可用取决于构建，启动时检测"，正确但不完整。真实状况：

- Android 框架 SQLite 只保证 FTS3/FTS4（Room 也只有 `@Fts4` 注解），FTS5 约从 Android 11（API 30）起被编入但非契约保证；
- 更关键的是 **FTS 默认分词器（unicode61/porter）不做中文分词**——CJK 文本会被拆成单字或整串，中文关键词检索质量会很差。

考虑到最终目标是中文 K12 内容，这不是边缘情况。可选方案：

- 入库前用分词器（jieba 类移植或 HanLP 轻量版）预分词，存空格分隔文本；
- 或做 character-bigram 索引；
- 或自定义 SQLite tokenizer（需自带 SQLite，可与 sqlite-vec 的 NDK 构建合并解决）。

**建议作为 Phase 0/1 的独立验证项**，并与 R2 的"自带 SQLite"决策联动（自带 SQLite 后 FTS5 和 sqlite-vec 一起解决）。

### R4（中）：GPU delegate 脆弱性有实证

官方示例的 manifest 显式声明 `libOpenCL.so`，注释说明是为了规避部分 Qualcomm 驱动上 WebGPU fallback 不稳定——第一方示例都要绕坑，说明 GPU 路线跨设备一致性差。默认 CPU/XNNPack（Pixel 11 Pro 上文本仍达 8.3ms）是更稳的 MVP 基线。PRD §12 的设备分档测试应把"GPU 开关逐机型验证"列为显式项。

### R5（战略层，PRD 已部分自知）：Phase 1 交付物本质是"标准向量搜索应用"

PRD 诚实承认（模式 A 只是离线语义搜索），但需向决策层挑明：SAG 的差异化价值——事件/实体索引与查询时动态超边——在端侧 MVP 中依赖 **Phase 2 的规则版抽取器**，其质量与 SAG 用 LLM 抽取的效果差距是未知数，很可能是数量级的。

风险不是"做不出来"，而是 Phase 2 验收时发现关系扩展对 Recall/MRR 的增益测不出来。PRD §Phase 2 要求"确认关系层有可测收益"，这个决策门要严格执行：若增益不显著，应坦然接受"这是一个高质量离线搜索应用"，把关系层留到有本地 LLM 之后。

---

## 三、性能与验收指标核算：可达成，且有余量

用官方数据 + 第一性原理核算 PRD §9.2 的门槛：

| PRD 指标 | 核算 | 结论 |
|---|---|---|
| 查询 embedding p95 ≤ 500ms（中高端） | Pixel 11 Pro TPU 文本 8.3ms、CPU 内存仅 112–128MB（模型卡实测）；中端机 CPU 跑 INT4 270M 估计数十至一三百毫秒 | 达标无忧；低端机需实测（PRD 已要求分档） |
| 1k chunks 精确扫描 Top-10 p95 ≤ 150ms | 1k × 768d ≈ 3MB，计算量微小 | 宽松 |
| 10k chunks 精确扫描 Top-10 p95 ≤ 800ms | 29MB BLOB + 约 1500 万次乘加；SIMD 计算本身 <50ms，瓶颈在 SQLite 逐行 BLOB 读取与 JNI 拷贝；认真实现（预载 FloatArray 或整块 mmap）可达 100–400ms | 达标；**精确扫描先行、ANN 后置的决策正确，10k 规模内不需要 ANN** |
| 内存 | 10k 向量预载约 29MB 无压力；50k（146MB）在 4GB 设备吃紧 | PRD 把 MVP 定在 1k–10k、50k 只进测试矩阵，边界划得准 |
| 导入吞吐 | 1k chunks × 数十至数百 ms/条 = 分钟级后台任务 | WorkManager 方案合理；注意批量导入时的温控降频 |

---

## 四、工期评估

| 阶段 | PRD 估算 | 本报告评估 | 说明 |
|---|---|---|---|
| Phase 0 | 3–5 天 | **5–7 天** | 应追加 R1 任务模板对照、R3 中文分词两个 spike |
| Phase 1 | 1–2 周 | **2–3 周** | 200 条中英混合人工标注评测集本身是 2–4 天工作；加上导入管线、任务恢复/取消、Compose UI |
| Phase 2 | 1–2 周 | 1–2 周（但质量不可控） | 能出"可测的东西"，但"测出增益"不保证，见 R5 |
| Phase 3 | 2–4 周 | 2–4 周 | 按范围 |
| **整体** | 5–9 周 | **6–11 周** | 一名熟练 Android 工程师 |

---

## 五、Phase 0 决策门修订（本报告的可执行输出）

在 PRD §11 Phase 0 决策门基础上增加三项判定：

1. **任务模板判定（对应 R1）**：Universal Embedder 输出向量 vs HF 参考向量的余弦一致性 ≥ 0.99（原文、手工前缀两种方式任一对齐即通过）；不通过 → MVP 基线改用 Text Embedder + EmbeddingGemma v1，EGemma 2 等待 ML Kit。
2. **模型资产判定（对应 R2）**：确认 270M 纯文本 .litertlm 是否可获取；不可获取则接受 440M 文件 + 按需加载，并把下载分发方案（Play Asset Delivery 或应用内下载器）列入 Phase 1。
3. **中文检索判定（对应 R3）**：选定中文分词方案并在评测集中文子集上跑通关键词基线；同步决策是否自带 SQLite（若 ANN/FTS5 都要走自带路线，尽早统一为单一次策）。

原 PRD 决策门（10k 检索性能不达标再引入 ANN；Universal Embedder 资源成本不达标先用 Text Embedder 兼容模型）维持不变。

---

## 六、与最终目标的战略衔接

采用端侧 Kotlin 引擎后，最终目标 PRD（Personal_Memory_AI_K12）里的 K12 图谱导入、掌握度、推荐逻辑（原设计为 Python app-api + 服务端 SAG）将来要么**在 Kotlin 侧重写一遍**，要么维持"手机端记忆 + 桌面/服务端 K12"的双引擎形态。

本 PRD 的 `EmbeddingProvider` / `VectorIndex` 抽象和 `embedding_space_id` 设计为复用留了缝。**建议在 Phase 1 就把 taxonomy 相关表设计（canonical_id、版本化快照，见服务端 PRD §9）抄进来预留**，避免两条数据模型日后无法对齐。

---

## 七、调查来源（核验于 2026-10-13）

1. Google 开发者博客（2026-10-06）：EmbeddingGemma 2 发布——<https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/>
2. MediaPipe Universal Embedder（Android）官方文档——<https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder/android>
3. MediaPipe Text Embedder（Android）官方文档——<https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/android>
4. Universal Embedder Android 官方示例——<https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/universal_embedder/android>
5. HF 模型仓库文件树：litert-community/embeddinggemma-2-740m-litert-lm——<https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm>
6. sqlite-vec Android 支持跟踪 issue——<https://github.com/asg017/sqlite-vec/issues/68>
7. AndroidX SQLite release notes（框架 SQLite 版本史）——<https://developer.android.com/jetpack/androidx/releases/sqlite>
8. Room @Fts4 参考文档——<https://developer.android.com/reference/androidx/room/Fts4>
9. Zleap-AI/SAG 上游仓库——<https://github.com/Zleap-AI/SAG>

> 注意：第二节所列风险中的官方 API 状态（Universal Embedder 能力、ML Kit 发布）基于 2026-10-13 的快照。Google AI Edge 生态迭代很快，正式进入 Phase 0 前应重新复核第 1–4 项来源是否已有更新。

---

# 附录 A：LanceDB 在 SAG 中的可替代性分析（源码级核验）

- **核验日期**：2026-10-13
- **核验方法**：克隆 SAG 主仓库（main 分支）+ 从 PyPI 下载 `zleap-sag==0.14.0` wheel 解包，grep 全部 LanceDB 调用点并逐项分析；非依赖文档推测
- **回答的问题**：LanceDB 没有安卓版本——在 SAG 项目中，它的作用是否真的可以全部被 SQLite 技术栈替代？

## A.1 结论

**在端侧 MVP 规模（≤1 万 chunks）下，可以等价替代，且替代方案在一致性上更优。**

源码证据显示：LanceDB 在 SAG 中的实际作用 = **"对 4 张固定 collection 做精确余弦 KNN + BM25 全文检索 + 标量过滤 + upsert 的本地嵌入式存储"**——没有任何一项超出 SQLite 技术栈（SQLite + FTS + BLOB 向量 + 原生索引）的能力范围。SAG 自身的工程决策（小规模保持暴力扫描、刻意拒绝有失真的量化 ANN）直接佐证了这一点。

唯一真实缺口：**超过约 5 万条向量后**的无损 ANN 索引（IvfFlat 等价物）。但这在 MVP 规模之外，且 SAG 自己也将其定位为"行数够多才启用"的可选优化。

## A.2 关键架构事实：SAG 的关系半边本来就是 SQLite

zleap-sag 的适配器注册表（`core/adapters/registry.py`）定义了六类适配器：`llm / vlm / embedding / rerank / vector / relational`。默认零基础设施栈为：

- **vector provider**：`lancedb`（可选 `es / pgvector / paradedb / oceanbase`）
- **relational provider**：**`sqlite`**（SQLAlchemy + aiosqlite；可选 postgres/mysql/oceanbase）

也就是说：**SAG 的核心价值层——事件/实体数据模型、关系扩展 SQL、chunk↔document↔source 映射、任务状态——本来就运行在 SQLite 上，不在 LanceDB 里**。LanceDB 只承载向量与全文检索。端侧用 SQLite 复刻的不是"SAG 的一半"，而只是其中一层可替换的存储；事件/实体 SQL 推理层与 SAG 默认架构天然同构。

## A.3 LanceDB 在 SAG 中的确切调用面（zleap-sag 0.14.0 实测）

**4 张 collection（`core/storage/index_schemas.py`）**：

| collection | 向量列 | FTS(BM25) 列 | keyword 精确过滤列 |
|---|---|---|---|
| `source_chunks` | heading_vector, content_vector | content | heading + 多个 id/type 列 |
| `event_vectors_wide` | title_vector, content_vector | summary, content | title + id 列 |
| `entity_vectors` | vector | description | name, normalized_name + id/type 列 |
| `event_entity_vectors` | vector | — | description + id 列 |

对应六个 embedding 角色（chunk 标题/正文、event 标题/内容、entity 名称、event-entity 关系描述），由应用侧 `octx_vector_protocol.py` 的向量指纹机制管理版本兼容。

**VectorStore 契约（`lancedb_store.py`，约 17 个公开方法）**：

1. 建表/校验（固定维度、多向量列、typed schema）
2. `merge_insert("id")` upsert、`update`、`delete`、`count_documents(带过滤)`
3. 向量检索：`query().nearest_to(vec).distance_type("cosine").where(过滤).select(投影).limit(k)`——**精确余弦 + 预过滤 + 列投影**
4. 全文检索：`nearest_to_text(text, columns)`（Tantivy FTS，返回 `_score`），多列带 boost 合并取最大分
5. 索引创建：FTS() 全文索引、BTree 标量索引（消除 where/get_document 全表扫）、IvfFlat 向量索引
6. `optimize / ping / check_connection / close`

**未使用的 LanceDB 能力**：混合检索 reranker（SAG 的 rerank 是独立的 HTTP 适配器，与 LanceDB 无关）、版本 time-travel、多模态 blob 存储、列式训练数据访问——均不在 SAG 调用面内。

## A.4 逐项替代性对照

| LanceDB 功能（SAG 实际使用） | SQLite 技术栈等价物 | 等价性 |
|---|---|---|
| 精确余弦 KNN（nearest_to + cosine） | BLOB 向量表 + 全表精确扫描（或 sqlite-vec KNN） | ✅ 完全等价；MVP 规模内性能达标（见 A.6） |
| 余弦分数保真 | 精确扫描天然无损 | ✅ **优于量化 ANN**（见 A.5 证据 1） |
| 标量预过滤（where）+ BTree 索引 | SQL WHERE + SQLite 原生索引 | ✅ SQLite 的本职强项 |
| BM25 全文检索（Tantivy FTS） | SQLite FTS5 `bm25()`（框架内建则 FTS4） | ✅ 功能等价；中文分词两边都需要额外处理（见 A.5 证据 2） |
| 多向量列/表 | 关系建模（chunk_id, role, vector_blob）或宽表 | ✅ 关系建模反而更自然 |
| upsert（merge_insert on id） | SQLite `INSERT ... ON CONFLICT DO UPDATE` | ✅ 原生支持 |
| IvfFlat ANN（行数≥阈值才建） | 无现成稳定等价物（sqlite-vec 的 IVF/DiskANN 尚在实验目录） | ⚠️ **唯一真实缺口**，仅影响 >5 万规模 |
| 跨表事务一致性 | — | ✅ **SQLite 方案更优**：SAG 的关系库(SQLite)与向量库(LanceDB)本就是两个独立存储、无跨库 ACID；端侧合并为单库后，向量/关系/任务状态共享同一 WAL 事务 |

## A.5 三条源码级关键证据

**证据 1（最重要）——SAG 自己的注释直接背书精确扫描：** `lancedb_store.py` 索引创建逻辑（约 882–891 行）原文：

> "向量列：IVF_FLAT 需要训练样本，行数太少时保持 brute-force（**本身已足够快**）。刻意不用 IVF_PQ：PQ 量化会失真距离（实测 recall@20 仅 0.76，top1 分数 1.0 被压到 0.90），而 **multi 检索的 0.9/0.4 阈值直接吃分数，失真会改召回**。IVF_FLAT 保留精确距离（recall 0.99，**分数与 brute-force 一致**，延迟相同）。"

即：SAG 的 multi 检索对**分数保真**有硬要求，任何量化型 ANN 都不可接受，而精确扫描天然满足。端侧 SQLite 精确扫描不是妥协，是符合 SAG 语义的正确实现。

**证据 2——SAG 的中文 FTS 并未做特殊优化：** FTS 索引创建用默认配置 `FTS()`，未见中文分词器配置。Tantivy 默认分词对中文同样按整段切分，SAG 的中文关键词检索本身就很弱（主力是向量通道）。因此"换成 SQLite FTS 会丢中文能力"不成立——两边都需要在应用层做中文预处理（对应本报告 R3），SQLite 侧做好 bigram/预分词后**不劣于甚至优于**现状。

**证据 3——向量层早已是显式抽象：** registry 支持 5 种 vector provider 运行时注册替换（`register(kind="vector", ...)`）。SAG 项目自身已把"向量存储可替换"作为架构原则，端侧新增一个 SQLite provider 在概念上与现有 pgvector/es 适配器同级，不是对 SAG 架构的背离。

## A.6 性能与规模边界

- 1 万 chunks × 768d ≈ 29MB：精确扫描计算量 <50ms（SIMD），SQLite 逐行 BLOB 读取为实际瓶颈，实测目标 100–400ms，满足 PRD §9.2 的 800ms 门槛；
- 5 万条（146MB）：低内存设备吃紧，需要 sidecar 平铺二进制文件 + mmap 扫描（绕过 SQLite 行开销）或引入 ANN；
- 10 万条以上：必须 ANN。路径：(a) sqlite-vec 的实验性 IVF/DiskANN（尚未宣布可用，pre-v1）；(b) 自研 HNSW/IVF-Flat（PRD §6 已列为"基准门槛后增加"）；(c) 届时若 ML Kit 托管检索可用则外包。注意按证据 1 的约束：**只接受无损（IVF-Flat 式）ANN，拒绝重量化**，否则会破坏 SAG 阈值语义。

## A.7 对任务规划的直接影响

1. **"用 SQLite 替代 LanceDB"从"风险假设"升级为"源码级验证过的等价替换"**，PRD §1.1 结论第 2、3 条成立，无需修改架构路线。
2. VectorIndex 接口应参照 A.3 的 17 方法契约裁剪设计（MVP 需要的核心子集：建表校验、upsert、精确余弦+预过滤+投影、count、delete、ping），确保将来对照 SAG 行为做兼容测试时字段可对齐。
3. 中文 FTS 预处理（R3）应视为对 SAG 中文能力的**增强机会**而非功能追赶。
4. 规模护栏写入产品文案：首期支持个人知识库 1 千–1 万 chunks；5 万+ 需 ANN 专项（独立里程碑）。

---

# 附录 B：Android 端 ANN 向量引擎选型全景（5 万条以上场景）

- **核验日期**：2026-10-13
- **回答的问题**：除 SQLite 精确扫描外，还有哪些安卓客户端可用的高性能向量引擎支持 ANN（HNSW/IVF/DiskANN），可覆盖 5 万条以上向量？作为后期性能优化方向的选型凭证。

## B.1 结论

**有，且推荐"usearch（首选）/ hnswlib（备选）经 NDK+JNI 嵌入"作为后期 ANN 路线**，两者均 Apache-2.0、活跃维护、官方支持或验证过 Android/ARM，能与现有 SQLite 架构以"sidecar 索引文件"方式共存。商业路线 ObjectBox 是唯一"数据库级"开箱方案（HNSW + 官方 Kotlin API + 查询条件过滤），但原生核心为专有二进制，许可需商务核验。独立的 DiskANN / LanceDB / ScaNN-TFLite 均无 Android 落地路径（B.5）。

## B.2 候选全景（均已核验）

| 候选 | 索引/能力 | Android 支持 | 许可 | 状态与评估 |
|---|---|---|---|---|
| **usearch**（unum-cloud） | HNSW + 精确扫描；cosine/IP/L2 等 9 种+自定义；f32/bf16/f16/i8/u8；save/load、**mmap 只读视图**、运行时删除 | **官方列出 Android/iOS/WASM 平台**；提供 Java 原生绑定（无过滤谓词/自定义度量） | Apache-2.0 | 4.3k stars，持续活跃（2026-10 仍有提交）；~3K SLOC 单文件 C++，被 ClickHouse/DuckDB/Chroma 采用；官方宣称同 recall 下 HNSW 比 FAISS 快约 10×。**首选** |
| **hnswlib**（nmslib） | HNSW 参考实现；标记删除；索引序列化 | 无官方绑定，header-only，NDK+薄 JNI 是社区标准做法 | Apache-2.0 | 5.3k stars，2026-09 仍活跃（0.10.0rc2）；最简单、依赖最少，**备选/保底** |
| **FAISS CPU**（Meta） | IVF-Flat / HNSW-Flat（无损）+ IVFPQ 等 | 可 NDK 交叉编译（CPU-only），无官方打包 | MIT | 面向服务器，构建复杂、体积大；单用户移动端**过重**，仅在需要 IVF 家族时考虑 |
| **ObjectBox** | HNSW（neighborsPerNode/indexingSearchCount 可调）；cosine/欧氏/点积；超内存走磁盘；**向量查询可与普通查询条件组合**（预过滤路线图在推进） | **官方一等公民**：Android 5.0+，Java/Kotlin API 完整示例，APK 增量约 2.3MB（ABI 拆分后约 0.6MB） | Java 绑定仓库 Apache-2.0；**原生核心为专有二进制，商业许可需核验** | 唯一"嵌入式数据库级"方案（含存储/事务），但引入供应商绑定 |
| **sqlite-vec（实验 ANN）** | 仓库含 sqlite-vec-ivf.c / sqlite-vec-diskann.c / rescore | 同 sqlite-vec（需自带 SQLite） | Apache-2.0/MIT 双许可 | **pre-v1、未宣布可用**；与现有 SQLite 栈零架构成本衔接，值得持续观察，暂不可依赖 |
| **libSQL（Turso）** | 原生 `vector_top_k`，曾以 DiskANN 为后端（2024-07 博客） | 无一等 Android 发行；采用需整体替换 SQLite 核心 | MIT | 替换整个数据库核心与 Room 兼容层冲突，**不建议** |
| **ML Kit / Google 托管检索** | 视 ML Kit EmbeddingGemma 2 API 发布内容而定 | 待发布 | 待定 | 与附录 A 的策略一致：发布后再评估 |

## B.3 关键工程约束（选型必须满足）

1. **距离无损（继承附录 A 证据 1）**：SAG multi 检索的 0.9/0.4 分数阈值语义要求无失真距离。可接受：HNSW-Flat / IVF-Flat（图/桶导航近似、距离计算精确）；不可接受：PQ 类压缩量化（会把 top1 分数 1.0 压到 ~0.90 改变召回）。usearch/hnswlib/ObjectBox 在 f32 下均满足；FAISS 只可用 IndexFlatIP/IndexHNSWFlat/IVFFlat 系。
2. **动态语料**：个人知识库持续插入+删除。HNSW 支持增量插入与标记删除（残留空洞需定期重建），与 PRD §8 的 generation 双缓冲重建机制天然配合。
3. **内存账本（768d f32）**：5 万条 ≈ 146MB、10 万条 ≈ 293MB（另计 HNSW 图约 1.3–2× 节点开销）。中端机（6–8GB）10 万条可行但偏紧。**关键杠杆：EmbeddingGemma 2 的 MRL 截断**——若 256d 通过中文召回评估（附录 A/服务端 PRD ADR-003 同一决策），10 万条裸向量降至约 100MB，端侧 10 万级完全打开。bf16/f16 存储可再省一半，但会轻微损失分数精度，需对照阈值语义实测后再用。
4. **过滤语义**：LanceDB 的 kNN 预过滤（where + BTree）在图索引上通常退化为后过滤或 SQLite 候选集求交。usearch 的 Java 绑定无过滤谓词；ObjectBox 的查询条件过滤最完善。**对 SAG 场景影响有限**（source_id 级过滤的候选集不大），实现上用"SQLite 取符合过滤的 id 集合 → ANN 结果求交/重排"即可。

## B.4 推荐演进路径

1. **MVP（1k–10k）**：SQLite 精确扫描（PRD 现方案），同时把 `VectorIndex` 接口的 `rebuild(model, vectors)` 语义做严——ANN 索引必须是**可从 SQLite 向量完全重建的派生工件**（source of truth 始终是 SQLite）。
2. **规模化触发（>5 万或检索 p95 超标）**：Phase 0 式小 spike 引入 **usearch**：NDK 编译 + JNI 封装实现 `VectorIndex`，sidecar 索引文件 + generation 双缓冲；以"与精确扫描对照 recall@10 ≥ 0.99、分数一致"作为验收门（对齐 SAG 语义）。hnswlib 作为 usearch 不可用时的保底。
3. **同步观察**：sqlite-vec ANN 成熟度（保持单库架构的最优解）、ObjectBox 许可谈判（若要数据库级方案）、ML Kit 发布内容。

## B.5 明确排除项

- **DiskANN（microsoft/DiskANN）独立移植**：无任何公开移动端移植；面向服务器 NVMe/大内存设计，与手机闪存 I/O 特性不匹配。DiskANN 思路在移动端只能经由 libSQL/sqlite-vec 的实验分支获得。
- **LanceDB Java SDK 上 Android**：docs 页面 404、GitHub API 查无公开 Java 仓库产物、无 AAR 发行——**无可用路径**（与附录 A 前提一致：LanceDB 不可上 Android）。
- **ScaNN + TFLite Text Searcher**：Google 官方模式为**离线构建索引并打包进 .tflite**（静态语料模式），不支持动态增长的个人知识库——仅适用于未来打包静态参考语料的场景。
- **Milvus Lite / Qdrant / Chroma embedded**：均无 Android 打包形态。

## B.6 来源（核验于 2026-10-13）

1. usearch 仓库（平台/绑定/许可/能力）——<https://github.com/unum-cloud/usearch>
2. hnswlib 仓库——<https://github.com/nmslib/hnswlib>
3. ObjectBox 向量搜索文档（HNSW/指标/过滤/平台）——<https://docs.objectbox.io/on-device-vector-search>
4. ObjectBox FAQ（Android 5.0+、APK 体积）——<https://docs.objectbox.io/faq>；objectbox-java 仓库许可（Apache-2.0）经 GitHub API 核验
5. sqlite-vec 仓库（pre-v1、IVF/DiskANN 实验文件）——<https://github.com/asg017/sqlite-vec>
6. Turso libSQL DiskANN 博客（2024-07）——<https://turso.tech/blog/approximate-nearest-neighbor-search-with-diskann-in-libsql>
7. FAISS 仓库——<https://github.com/facebookresearch/faiss>
8. Microsoft DiskANN（服务器定位，无移动移植）——<https://github.com/microsoft/diskann>
9. TFLite Text Searcher（ScaNN 离线索引模式）——<https://developers.google.com/edge/litert/libraries/modify/text_searcher>

---

**文档结束。**
