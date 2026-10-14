# SAG Android 端侧移植可行性与实施 PRD

- 文档版本：1.0
- 日期：2026-10-13
- 状态：可进入技术预研 / MVP 立项评审
- 项目对象：Zleap-AI/SAG 与 `zleap-sag` 引擎
- 目标平台：Android 原生客户端（Kotlin 优先，必要时 JNI/C++）
- 核心目标：在设备本地完成文档导入、结构化索引、语义检索与证据追溯；Embedding 可完全离线运行。生成式回答可作为独立、可选模块。

## 1. 执行摘要

### 1.1 结论

**可行，但不建议把现有 Python SAG 应用直接“打包进 Android”。建议移植 SAG 的检索数据模型与算法思想，重写端侧数据引擎。**

原因：
1. SAG 仓库的完整应用由 Next.js、FastAPI、Python `zleap-sag` 引擎组成；现有本地默认引擎使用 SQLite + LanceDB。Python 运行时、后台任务、解析器、LanceDB Python 依赖及服务端 API 并不是 Android 原生应用可直接使用的组件。
2. LanceDB 不应被当作 Android 上的可直接替换依赖。即使某个底层组件理论上可经 JNI 构建，也必须额外维护 NDK 构建、ABI、文件锁、升级、崩溃恢复与数据迁移；对 MVP 来说风险和成本不划算。
3. SAG 的核心价值不等同于 LanceDB：其事件（event）/实体（entity）索引、SQL 关联扩展、查询时动态超边与原文证据追溯可以独立实现。向量索引是其中可替换的一层。
4. `EmbeddingConfig(api_key, base_url, model)` 是远程服务配置，不是通用的本地模型抽象。应新增 `EmbeddingProvider` 接口，由远程 OpenAI-compatible Provider、MediaPipe Text Embedder / Universal Embedder、未来 ML Kit Provider 分别实现。
5. MediaPipe Text Embedder 可以用于 Android 本地文本向量化，但要求模型与任务格式兼容；EmbeddingGemma 2 更适合通过官方 Universal Embedder / LiteRT-LM 或未来正式发布的 ML Kit API 接入。不能仅因模型名字相似，就把它当成任意 embedding 模型的透明替换件。
6. 更换 Embedding 模型会改变向量空间。**旧向量不能与新模型向量混合比较**；必须记录模型 ID、版本、维度、归一化和任务模板，并在模型变化时全量或分批重建索引。

### 1.2 推荐方案

采用 **Kotlin + Room/SQLite + 本地向量检索层 + EmbeddingProvider 抽象**：
- 元数据、文档、chunk、event、entity、event-entity 关系、任务状态：Room/SQLite。
- 向量检索 MVP：先做内存/SQLite 持久化的精确余弦 Top-K；对小规模个人知识库（建议首期 1,000–10,000 chunks）验证性能。
- 向量检索规模扩大后：增加可替换的 ANN 索引（例如经过 Android/NDK 构建验证的 sqlite-vec 或自研 HNSW/IVF）；不要把向量索引绑定到 SAG 领域模型。
- Embedding：首选 MediaPipe Text Embedder 做可运行基线；评估 EmbeddingGemma 2 时优先采用官方 Universal Embedder / LiteRT-LM 路径。ML Kit EmbeddingGemma 2 适配层作为后续模块，等官方 API、依赖、设备覆盖和许可条件正式可用后再接入。
- LLM：与 Embedding 分离。MVP 可以先支持远程 OpenAI-compatible LLM；若要求真正全离线，再单独增加 LiteRT-LM / llama.cpp 等本地生成模型适配器。Embedding 模型本身不负责生成答案，也不能替代 LLM 的事件抽取、查询理解与答案生成。
- 端侧优先实现 `vector` 检索；`multi` 检索需要事件/实体扩展、候选融合，若 SAG 原流程依赖 LLM reranking 或 query understanding，则应定义“端侧无 LLM 降级策略”，不能声称完全复刻服务端 `multi`。

### 1.3 可行性评级

| 项目 | 结论 | 主要条件 |
|---|---|---|
| Android 原生端侧知识库 | 可行 | 需要重写应用/数据引擎，不是直接复用 Python 应用 |
| SQLite 元数据层 | 高可行 | Room/SQLite 足够承担关系数据与事务 |
| LanceDB 直接上 Android | 不建议作为 MVP | 需要验证官方支持、NDK/ABI、生命周期和维护成本 |
| 用 SQLite 替代 LanceDB | 可行 | 向量检索需另选精确扫描或 ANN 索引 |
| MediaPipe Text Embedder | 可行 | 选择兼容模型；注意其模型格式与任务模板 |
| EmbeddingGemma 2 本地推理 | 可行性高 | 使用官方模型格式和 Universal Embedder/LiteRT-LM 路径并实机验证 |
| EmbeddingGemma 2 的 ML Kit API | 规划为后续适配 | Google 2026-10-06 公告称“未来几周”提供；在正式 API 发布前不能作为 MVP 硬依赖 |
| 本地 LLM 替换 EmbeddingConfig | 不成立（概念混淆） | LLM 与 Embedding 是不同任务，应分别抽象 |
| 全离线端侧 SAG | 有条件可行 | 还需本地文档解析、事件/实体抽取和生成模型，RAM/速度/功耗是主要风险 |

## 2. 目标与非目标

### 2.1 目标（MVP）
1. 用户可在 Android 设备上导入 TXT、Markdown，后续扩展 PDF/Office。
2. 本地完成文本规范化、分块、Embedding 生成与索引写入。
3. 支持语义 Top-K 检索、关键词过滤、来源过滤与原文 chunk 定位。
4. 保留 SAG 的 event/entity 数据模型与 SQL 关联扩展思想。
5. 在无网络条件下完成索引和语义检索（前提是模型已安装）。
6. 支持更换 Embedding Provider；模型变更时拒绝混用向量并提供重建索引流程。
7. 数据库升级、任务恢复、取消任务、删除来源和清理向量索引具备可测试行为。

### 2.2 非目标（MVP）
- 直接运行 SAG 的 Next.js/FastAPI 桌面应用。
- 兼容 LanceDB 文件格式或直接读取桌面端 LanceDB 数据目录。
- 首期实现多用户、云同步、MCP Server、REST API 全功能。
- 宣称复现论文中所有基准结果或与服务端 `multi` 策略完全等价。
- 首期支持所有 PDF/OCR/Office 格式与多模态索引。
- 把 Embedding 模型当作聊天/生成模型使用。

## 3. 现状与关键架构判断

### 3.1 SAG 现有结构（基于公开仓库说明）
- Web：Next.js / React。
- API：FastAPI。
- 引擎：Python 包 `zleap-sag`，由应用适配层调用。
- 默认持久化：应用元数据 SQLite；知识引擎 SQLite + LanceDB。
- 其他后端：文档列出了 PostgreSQL + pgvector，以及 Elasticsearch 等选项。
- 数据处理链路：来源/文档 → 解析为 Markdown → chunking → embedding → event/entity 抽取 → 检索和证据追溯。
- 检索策略：`vector` 快速语义检索；`multi` 结合事件/实体关系与更复杂的候选扩展/排序。

### 3.2 为什么不能只替换数据库就结束
SAG 引擎依赖的不仅是向量相似度，还包括：
- chunk 与原始来源的可追溯映射；
- 一个 chunk 对应的事件信息；
- 多个索引实体与事件之间的关系；
- 查询时基于 SQL 关系的候选扩展；
- 候选去重、排序、证据聚合；
- 抽取与索引任务的状态和增量更新。

因此端侧实现应保留领域层和检索算法层，隔离向量存储实现。若只把 LanceDB 改成 SQLite，却丢掉事件/实体关联，则只是普通向量 RAG，不是 SAG 检索思想的完整移植。

## 4. 推荐端侧架构

```text
Android UI (Compose)
  ├── Library / Import / Search / Evidence / Settings
  └── ViewModel + UseCases
        ├── IngestionPipeline
        │     ├── DocumentParser
        │     ├── TextNormalizer + Chunker
        │     ├── EmbeddingProvider
        │     └── EventEntityExtractor (MVP: pluggable / staged)
        ├── RetrievalService
        │     ├── VectorRetriever
        │     ├── KeywordRetriever (FTS5, if available)
        │     ├── SQL EventEntityExpander
        │     ├── CandidateFusion + Deduplication
        │     └── EvidenceResolver
        ├── Repository layer
        │     ├── Room/SQLite repositories
        │     └── VectorIndex interface
        └── Optional AnswerGenerator
              ├── Remote OpenAI-compatible LLM
              └── Local LLM runtime (later phase)
```

### 4.1 关键接口建议

```kotlin
interface EmbeddingProvider : Closeable {
    val descriptor: EmbeddingDescriptor
    suspend fun embedDocuments(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(text: String): FloatArray
}

data class EmbeddingDescriptor(
    val providerId: String,          // mediapipe-text / mediapipe-universal / mlkit
    val modelId: String,
    val modelRevision: String?,
    val dimensions: Int,
    val normalization: String,       // l2 / none / model-defined
    val queryTaskTemplate: String?,  // e.g. query/document prefix or task context
    val documentTaskTemplate: String?,
    val modality: String             // text / multimodal
)

interface VectorIndex : Closeable {
    suspend fun upsert(id: String, vector: FloatArray, metadata: VectorMetadata)
    suspend fun search(query: FloatArray, topK: Int, filter: VectorFilter? = null): List<VectorHit>
    suspend fun deleteByIds(ids: List<String>)
    suspend fun rebuild(model: EmbeddingDescriptor, vectors: Flow<EmbeddedChunk>)
}

interface EventEntityExtractor {
    suspend fun extract(chunk: Chunk): ExtractedKnowledge
}
```

**接口原则：**
- `EmbeddingConfig(api_key, base_url, model)` 保留为远程 Provider 的配置 DTO，不作为所有 Provider 的共同接口。
- 不把 API Key 放进本地模型配置；本地 Provider 的配置应为模型资产位置、执行后端、线程数、delegate、批大小和任务模板。
- query 与 document embedding 必须使用同一兼容模型和规定的任务格式。
- 对每个向量记录 `embedding_space_id` 或 `model_revision`；检索查询只可访问同一向量空间。
- 批量接口要支持分批、取消、背压和失败重试，避免一次性把大文档集装入内存。

### 4.2 SQLite 表结构（建议初版）

- `source(id, title, uri, mime_type, created_at, status)`
- `document(id, source_id, title, checksum, parser_id, created_at, status)`
- `chunk(id, document_id, ordinal, content, char_start, char_end, token_estimate)`
- `embedding_meta(id, provider_id, model_id, model_revision, dimensions, normalization, task_template_hash, created_at, active)`
- `chunk_embedding(chunk_id, embedding_space_id, vector_blob, vector_norm, created_at)`（精确扫描阶段；Float32 little-endian BLOB）
- `event(id, chunk_id, canonical_text, confidence)`
- `entity(id, normalized_name, entity_type)`
- `event_entity(event_id, entity_id, role)`
- `chunk_entity(chunk_id, entity_id)`
- `ingest_job(id, source_id, stage, progress, error, updated_at)`
- `schema_migrations(version, applied_at)`

注意：
- 不建议把高维向量直接存为 JSON 文本。
- SQLite BLOB 方案便于 MVP，但对大量向量全表扫描的性能并不保证；必须做真实设备基准。
- 如果采用 ANN，索引文件与 SQLite 事务并非天然原子一致，需设计索引版本、重建、崩溃恢复与校验流程。
- FTS5 是否可用取决于 Android SQLite 构建/版本及实际集成方式；启动时检测，无法使用时退回关键词倒排实现或纯 SQL LIKE（仅作为降级）。

## 5. Embedding 方案评估

### 5.1 MediaPipe Text Embedder

**可作为 MVP 基线，但不能把“支持 Android”理解成“任意模型都能加载”。**

优点：
- 官方提供 Android Tasks API 和示例。
- 处理文本输入、tokenization/预处理、向量输出，支持 L2 归一化及可选 scalar quantization。
- 对支持的 EmbeddingGemma 文本模型可传递 `TextFormatContext` 指定任务类型。

限制：
- 必须选择 Text Embedder 支持的模型格式、tokenizer 和输入模板。
- 官方旧版 Text Embedder 文档主要围绕文本任务；不能默认它支持 EmbeddingGemma 2 的所有文本/图像/音频能力。
- MediaPipe 任务 API、Universal Embedder 与 LiteRT-LM 是相关但不同的集成层，不应混为同一个 SDK。
- 模型输出维度、归一化、query/document 指令模板必须与索引构建保持一致。

适用：先打通纯文本端侧 embedding、检索和持久化；或者在设备/模型支持矩阵验证后继续用于指定的文本模型。

### 5.2 EmbeddingGemma 2 + Universal Embedder / LiteRT-LM

Google 于 2026-10-06 公布 EmbeddingGemma 2：约 740M 参数的多模态 embedding 模型，将文本、图像、视频帧和音频映射到统一向量空间。官方博客给出 Pixel 11 Pro 上约 191 MB 的文本权重活动 RAM、约 567 MB 的完整多模态模型活动 RAM（这是官方特定设备/工作负载口径，不应直接当作所有 Android 设备的 RAM 预算）。

官方 Android 路线已提供 MediaPipe `tasks-retrieval` 的 Universal Embedder 示例，示例模型资产名包括 `embeddinggemma-2-text-vision-440m.litertlm`，并通过 LiteRT-LM 推理。该路线更适合验证 EmbeddingGemma 2，而不是强行把它塞入旧 Text Embedder 的模型接口。

建议：
1. 在实验分支先用 Universal Embedder 跑通 `embedText()`。
2. 明确是否只启用文本 encoder；如果模型包包含多模态组件，测量实际 APK/下载体积、冷启动、峰值 RSS、CPU/GPU/NPU 可用性。
3. 以真实中文、英文和混合文档构建离线评测集，测试检索 Recall@K、MRR、NDCG 与端侧延迟。
4. 将模型文件版本、任务格式和向量维度写入 `embedding_meta`。
5. 不要假设模型在所有 Android 芯片上均可用 NPU；设备、驱动、运行时和 delegate 组合必须逐机验证。

### 5.3 EmbeddingGemma 2 的 ML Kit 版本

截至本 PRD 日期，Google 2026-10-06 官方博客称其将“在未来几周”通过 ML Kit 提供 Android 服务/API。这属于发布计划，不等于 API 已正式 GA，也不应将尚未发布的依赖作为 MVP 阻塞项。

设计建议：
- 现在定义 `MlKitEmbeddingProvider` 的接口边界，但不提交假定存在的依赖坐标或 API 调用代码。
- 先用 Universal Embedder / LiteRT-LM 完成可运行原型。
- 等 ML Kit 正式发布后核实：最低 Android API、设备覆盖、是否动态下载、离线首次可用性、NPU 支持范围、模型生命周期、授权/条款、应用体积、版本兼容和后台推理限制。
- 若 ML Kit 提供托管模型和更广设备加速，可作为生产默认 Provider；同时保留 Universal Embedder Provider 作为可控的兼容路径。

### 5.4 本地 LLM 能否替换 EmbeddingConfig？

不应直接替换。LLM 与 Embedding 模型的职责不同：
- Embedding 模型：将文本/图像等映射为向量，用于相似度检索。
- 生成式 LLM：事件/实体抽取、查询理解、重排（若设计为生成式重排）和最终回答。
- 一个模型家族可能同时提供生成模型和 embedding 模型，但这不代表生成模型的 hidden state 可以不经验证直接作为检索向量。

推荐拆成：
- `EmbeddingProvider`：本地 MediaPipe/Universal Embedder、未来 ML Kit、远程 API。
- `KnowledgeExtractor`：规则/轻量 NER、远程 LLM、未来本地 LLM。
- `AnswerGenerator`：远程 LLM 或本地 LLM。
- `QueryInterpreter/Reranker`：可选；无 LLM 模式需有确定性降级策略。

若追求真正离线，EmbeddingGemma 2 只解决向量化这一环。事件/实体抽取和回答仍需要本地模型或简化策略，否则只能实现“离线语义搜索”，不能宣称完整的全离线 SAG Agent。

## 6. 向量存储与检索方案比较

| 方案 | Android 适配 | 规模/性能 | 工程风险 | 建议 |
|---|---|---|---|---|
| SQLite BLOB + 精确余弦扫描 | 高 | 适合小规模；随 N×维度线性增长 | 低 | MVP 首选，先量化真实设备性能 |
| SQLite + 自定义 HNSW/ANN | 中 | 中大型语料更合适 | 中高：索引持久化、崩溃恢复、删除与重建 | 通过基准门槛后增加 |
| sqlite-vec / SQLite 扩展 | 需实机验证 Android NDK/ABI 与维护状态 | 取决于扩展版本和索引能力 | 中高：原生扩展分发、SQLite 版本/加载策略 | 候选，不作为未经验证的前提 |
| LanceDB 直接移植 | 不推荐 MVP | 桌面/服务端生态较成熟；Android 原生路径需单独证明 | 高：构建、ABI、生命周期、兼容与维护 | 不采用，除非后续有明确收益 |
| 本地 HTTP Python SAG 服务 | 仅开发/原型可用 | 依赖 Python 运行时和服务生命周期 | 高：包体、启动、后台限制、耗电 | 不作为正式端侧架构 |
| 远程 SAG 服务 + Android UI | 高 | 由服务端决定 | 低端侧成本，但不是离线 | 作为云端/同步可选模式 |

### 6.1 精确检索的估算方法

如果向量维度为 `d`，每个维度 Float32 占 4 字节，N 条 chunk 的裸向量体积约为：

`N × d × 4 bytes`

例如 d=768：
- 10,000 条约 29.3 MiB（不含 SQLite 页、元数据和索引开销）。
- 50,000 条约 146.5 MiB。
- 100,000 条约 293 MiB。

全量精确扫描的计算量与 `N × d` 成正比。是否可用不能只根据向量文件大小判断，必须测量冷/热缓存、并发、设备温控后的延迟和电量。对个人移动知识库，先用 1k/5k/10k 三档测量，再决定是否引入 ANN。

### 6.2 检索流程

1. `EmbeddingProvider.embedQuery(query)`。
2. `VectorIndex.search(queryVector, topK)` 取语义候选。
3. 可选 FTS/关键词候选。
4. 根据候选 chunk 的 entity 关联，通过 SQL 扩展到相关 event/chunk。
5. 以可配置权重融合语义分数、关键词分数、事件/实体关联分数；对相同 chunk 去重。
6. 返回 `chunk_id`、原文片段、source/document 引用、得分与可解释的命中原因。
7. 如启用回答生成，将证据交给 `AnswerGenerator`；生成结果必须附引用，不允许把无证据的模型回答当作检索结果。

## 7. 全离线边界与降级策略

建议产品定义三个明确模式：

### 模式 A：Offline Search（MVP）
- 本地解析 TXT/MD。
- 本地 Embedding。
- 本地 SQLite 与向量检索。
- 本地事件/实体抽取先采用规则/词法策略或显式关闭关系扩展。
- 不生成自由文本答案，返回检索片段和来源。

### 模式 B：Hybrid Assistant
- 文档、向量和检索在设备端。
- 事件/实体抽取或答案生成调用远程 LLM。
- UI 清楚标示何时发送文本、发送哪些数据，并提供仅离线模式。

### 模式 C：Full Offline SAG-like Assistant（后续）
- 本地 Embedding + 本地知识抽取 + 本地答案生成。
- 需要针对目标设备选取小型生成模型、量化级别和推理后端。
- 必须测量峰值内存、首 token 延迟、token/s、热降频和后台恢复；未达标设备自动降级到模式 A。

## 8. 安全、隐私和数据一致性

- 文档与向量默认只存应用私有目录；支持用户主动导出/删除。
- 远程 LLM Provider 发送前必须展示隐私提示，并提供离线禁用开关。
- 不在日志中记录完整文档、API Key、访问令牌或模型输入。
- API Key 使用 Android Keystore 保护的加密存储；不写入 SQLite 明文。
- 内容 URI 使用 Storage Access Framework；只复制到私有目录的必要文件，不绕过用户授权。
- 删除文档必须级联删除 chunks、embeddings、events、relations，并同步更新 ANN 索引。
- 索引重建采用 generation/version 双缓冲：新向量空间构建成功并通过校验后再切换 active generation；失败时保留旧索引。
- 处理模型下载校验、SHA-256、断点续传、磁盘空间不足、应用升级与进程被杀后的恢复。
- 对恶意文档/超大文件设定大小、页数、文本长度和处理时间上限。

## 9. 性能目标与验收指标

以下是**项目验收目标，不是对所有 Android 设备的既有性能承诺**。必须在指定实体机上实测，且至少覆盖一个低端/中端/高端档位。

### 9.1 功能验收
1. 飞行模式下，已安装模型可完成 100 条文本的 embedding、入库与检索。
2. 对同一输入重复 embedding，输出维度固定、无 NaN/Inf；余弦计算结果范围合理。
3. 对 query/document 使用相同 embedding space 和正确任务模板；模型不匹配时明确报错，不允许静默检索。
4. 文档导入可暂停/取消；进程被杀后可以恢复或明确标记失败。
5. 删除来源后，其 chunk、embedding、event/entity 关系不再出现在检索结果中。
6. 每条检索结果能回到来源与原文位置；Top-K 去重。
7. 切换模型后必须触发重建流程；在重建完成前不混用两个向量空间。
8. 没有配置 LLM 时，搜索仍可用；需要生成回答时给出明确提示而非崩溃。
9. 远程 LLM 模式可通过开关彻底禁用网络请求。
10. 数据库迁移、索引损坏恢复、磁盘空间不足、模型文件缺失均有自动化测试。

### 9.2 性能验收（初始门槛，可经预研调整）
- 1,000 chunks：精确向量检索 Top-10 的 p95 ≤ 150 ms（不含 query embedding）。
- 10,000 chunks：精确向量检索 Top-10 的 p95 ≤ 800 ms（不含 query embedding）。
- 已加载模型后，单条 query embedding 在中高端测试机上 p95 ≤ 500 ms；需按模型/设备分档记录。
- 导入 100 个中等长度文本时不发生 OOM、ANR；UI 主线程不执行模型推理、数据库全表扫描或文件解析。
- 首次启动、模型冷加载、模型热推理分别记录；不能只报热缓存平均值。
- 峰值内存、APK 增量、模型下载大小、CPU/GPU/NPU 后端、功耗/温度均写入测试报告。

### 9.3 检索质量验收
- 建立至少 200 条人工标注的中英文/混合语言查询集，覆盖事实查找、同义表达、多跳关联、精确名称、数字/日期。
- 指标：Recall@5、Recall@10、MRR@10、NDCG@10；分别报告向量、关键词、融合、事件实体扩展结果。
- 与服务端 SAG 或人工基线对照；没有实验结果前，不宣称“等同 SAG”或复现论文 SOTA。
- 变更 embedding 模型后，必须重新跑全量评测集；维度一致也不代表向量空间兼容。

## 10. 测试矩阵

| 维度 | 最低覆盖 |
|---|---|
| Android | 最低支持 API、当前主流 API；至少三档实体设备 |
| ABI | arm64-v8a 为首要目标；如需要再评估 armeabi-v7a/x86_64 |
| 运行时 | MediaPipe Text Embedder；Universal Embedder/LiteRT-LM；未来 ML Kit 单独验证 |
| 网络 | 在线、弱网、无网、模型下载中断 |
| 数据量 | 100 / 1,000 / 10,000 / 50,000 chunks |
| 内容 | 中文、英文、中英混合、代码、数字/日期、长文档、空文本、异常编码 |
| 生命周期 | 前台、后台、旋转/重建、低内存杀进程、重启、升级 |
| 数据安全 | 权限拒绝、URI 失效、删除、导出、密钥存储 |
| 一致性 | 模型升级、维度变化、向量重建中断、ANN 索引损坏 |

## 11. 实施计划与阶段门

### Phase 0 — 技术验证（3–5 个工作日）
- 建立 Android demo：Room/SQLite + MediaPipe Text Embedder。
- 用 1k/5k/10k chunks 测量向量存储与精确检索。
- 单独验证 EmbeddingGemma 2 Universal Embedder 的模型加载、文本向量、冷启动、RAM 与设备加速。
- 验证中文/英文召回；形成模型与设备兼容矩阵。
- 决策门：若 10k chunks 的检索目标不达标，再引入 ANN；若 Universal Embedder 兼容性/资源成本不达标，先选 Text Embedder 兼容模型。

### Phase 1 — Offline Search MVP（1–2 周）
- Kotlin/Compose 基础 UI：导入、来源列表、搜索、证据详情、设置。
- Room schema、迁移、chunker、EmbeddingProvider、精确向量检索。
- 离线任务队列、进度、取消与恢复。
- 200 条检索评测集与设备 benchmark。

### Phase 2 — SAG-like 关系检索（1–2 周）
- event/entity schema 与关系扩展 SQL。
- 先以规则或可插拔 extractor 生成结构化数据。
- 候选融合、去重、引用追踪。
- 与纯向量基线比较 Recall/MRR/NDCG，确认关系层有可测收益。

### Phase 3 — 可选生成与生产化（2–4 周，按范围调整）
- 远程 LLM 答案生成适配器；默认明确提示联网行为。
- 若需要全离线，单独评估本地 LLM runtime 与模型档位。
- 增加导出/备份、索引重建、诊断页、性能遥测（仅本地或明确同意）。
- ML Kit Provider 在官方 API 正式发布并完成评估后接入。

工期为工程估算，受文档解析范围、团队熟悉度、设备矩阵与模型可用性影响。

## 12. 主要风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 把 SAG 完整 Python 引擎直接塞进 Android，导致包体/启动/维护成本过高 | 高 | 移植领域模型和检索算法，不移植 FastAPI/Next.js 服务栈 |
| 把 LanceDB 当作 Android 原生可用依赖 | 高 | MVP 使用 SQLite + 精确扫描，ANN 作为可替换扩展 |
| EmbeddingGemma 2 API/模型接口变化 | 中高 | 先用官方当前可运行的 Universal Embedder 路线；ML Kit 仅作后续适配 |
| 不同 embedding 模型的向量混用 | 高 | embedding space 元数据、强制隔离、版本化重建 |
| 端侧事件/实体抽取质量不足 | 高 | 分阶段交付，建立标注集；明确与完整 SAG 的差距 |
| 低端机 RAM、热降频与耗电 | 高 | 分档设备测试、分批推理、后台 WorkManager、资源不足降级 |
| ANN 索引与 SQLite 事务不一致 | 中高 | generation 双缓冲、校验、重建与恢复 |
| 文档解析/OCR 成本失控 | 中 | MVP 先限 TXT/MD，PDF/OCR 后续单独评估 |
| 远程 LLM 意外泄露本地资料 | 高 | 默认离线搜索；联网调用显式授权、最小上下文和日志脱敏 |

## 13. 交付物

1. Android Studio 工程与可安装 debug APK。
2. `EmbeddingProvider` 与 `VectorIndex` 接口及至少一个可用实现。
3. Room schema、迁移脚本与数据清理逻辑。
4. MediaPipe Text Embedder 原型；EmbeddingGemma 2 Universal Embedder 评估报告。
5. 端侧检索质量评测集、benchmark 脚本与三档设备测试报告。
6. 数据模型和检索流程架构图。
7. 已知限制文档，明确与 SAG 服务端完整能力的差异。
8. PR/代码评审记录、自动化测试与验收清单。

## 14. 最终决策建议

**批准“端侧 SAG-like 引擎”预研，不批准“直接把 SAG + LanceDB 原样搬到 Android”的实现路线。**

推荐优先级：
1. Kotlin + Room/SQLite + 精确向量检索；
2. MediaPipe Text Embedder 打通通用接口；
3. 用 Universal Embedder / LiteRT-LM 实测 EmbeddingGemma 2；
4. 事件/实体 SQL 扩展和证据追溯；
5. 基准证明有必要后再引入 ANN；
6. ML Kit EmbeddingGemma 2 API 正式可用后再做 Provider；
7. 最后再评估本地 LLM 与完整离线回答。

核心原则：**先保留 SAG 的检索数据模型与证据链，再逐步增加本地模型能力；不要让数据库或模型运行时成为领域层的硬耦合。**

## 15. 参考资料

以下为方案制定时应优先复核的官方/一手资料（发布状态会变化）：

1. SAG GitHub：<https://github.com/Zleap-AI/SAG>
2. SAG 中文 README：<https://github.com/Zleap-AI/SAG/blob/main/README-CN.md>
3. Google AI Edge — Android Text Embedder：<https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/android>
4. Google AI Edge — Android Universal Embedder：<https://developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder/android>
5. Google Developers Blog — EmbeddingGemma 2（2026-10-06）：<https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/>
6. Google AI Edge — Text Embedder overview：<https://developers.google.com/edge/mediapipe/solutions/text/text_embedder>
7. Android Room：<https://developer.android.com/training/data-storage/room>
8. SQLite：<https://www.sqlite.org/>
9. sqlite-vec（需自行验证 Android NDK、ABI、扩展加载与版本兼容）：<https://github.com/asg017/sqlite-vec>

### 参考资料使用说明
- SAG 的仓库结构、默认后端与引擎配置应以当前仓库实际版本为准；本 PRD 不承诺对所有历史版本兼容。
- Google 对 EmbeddingGemma 2 的 ML Kit 支持在 2026-10-06 公告中描述为“未来几周”；正式开发时必须重新检查 API 状态、版本号与支持矩阵。
- 所有性能指标均为待验证验收门槛，不是对所有 Android 设备的既有性能保证。
