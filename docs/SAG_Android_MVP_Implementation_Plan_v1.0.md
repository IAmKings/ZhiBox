# SAG Android MVP 开发实施计划

- **文档版本**：1.0
- **编写日期**：2026-10-14
- **状态**：已批准执行（Phase 0 即日可启动）
- **计划范围**：Phase 0（技术验证）+ Phase 1（离线搜索 MVP）详细任务分解；Phase 2/3 仅路线图
- **团队假设**：1 名熟练 Android 工程师（Kotlin/Compose/NDK 经验）；工期合计 **22 个工作日**（Phase 0 = 7 天，Phase 1 = 15 天），约 4.5 周
- **关联文档**：
  - [SAG_Android_OnDevice_Feasibility_PRD.md](./SAG_Android_OnDevice_Feasibility_PRD.md)（需求与架构依据，下称"Android PRD"）
  - [SAG_Android_OnDevice_Feasibility_Investigation_Report_v1.0.md](./SAG_Android_OnDevice_Feasibility_Investigation_Report_v1.0.md)（可行性调查，含附录 A/B，下称"调查报告"）
  - [Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md](./Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md)（最终项目目标）
- **版本锚点（三件套，实施期间不追新）**：

| 锚点 | 值 |
|---|---|
| SAG 主仓库参考 commit | `56f073c1c0c300b46707c7249d48732730656cb0`（2026-10-09） |
| 参考引擎包 | `zleap-sag==0.14.0`（PyPI） |
| 引擎 wheel SHA-256 | `c58d12f1af0c07010a966da16112337ce92734b164832af7a708f049ad7bc429` |

---

## 1. 目标与范围

### 1.1 MVP 定义（模式 A：Offline Search）

TXT/Markdown 导入 → 本地分块 → 端侧 embedding → SQLite 存储与精确余弦检索 → 原文证据回溯。全程飞行模式可用（模型已安装前提下）。SAG 的六向量角色中 MVP 仅实现 chunk 通道；事件/实体通道留待 Phase 2。

### 1.2 非目标（本计划不覆盖）

- 事件/实体抽取与关系扩展（Phase 2）
- 生成式回答、本地/远程 LLM（Phase 3）
- PDF/Office/OCR 解析、多模态索引
- 云同步、多用户、ANN 索引（触发条件见第 7 节与调查报告附录 B）

---

## 2. 技术决策基线（已定，实施期间不再讨论）

| 决策项 | 结论 | 依据 |
|---|---|---|
| 语言/UI | Kotlin + Jetpack Compose | Android PRD §1.2 |
| 元数据与关系存储 | Room + 框架 SQLite；FTS4 起步（框架保证），FTS5 不依赖 | 调查报告 R3 |
| 向量检索 | 精确余弦扫描；ANN 推迟至 >5 万条或 p95 超标 | 调查报告附录 A/B |
| 核心抽象 | `EmbeddingProvider` / `VectorIndex` / `EventEntityExtractor`（接口定义按 Android PRD §4.1） | Android PRD §4.1 |
| Embedding 基线 | **双路径，由 Phase 0 判定门 G1 决定**：A = Universal Embedder + EmbeddingGemma 2；B = Text Embedder + EmbeddingGemma v1 | 调查报告 R1 |
| 推理 delegate | 默认 CPU（XNNPack）；GPU 仅作实验记录，不进默认配置 | 调查报告 R4 |
| SDK/ABI | minSdk 24（官方示例下限）、targetSdk 36；arm64-v8a 优先 | 官方示例 |
| 向量规格 | 768d f32 起步；256d（MRL 截断）在 W3 评测后决策（若采纳则内存降 3×） | 调查报告附录 B.3 |
| 数据一致性 | SQLite 为唯一 source of truth；一切索引（含未来 ANN、FTS）均为可重建派生工件 | 调查报告附录 A.7 |
| 距离语义 | 无损余弦分数（禁止任何量化），对齐 SAG multi 检索阈值语义 | 调查报告附录 A.5 证据 1 |
| 参考版本 | 见文档头锚点三件套；升级 SAG 版本须单独 PR 并 diff 参考素材 | — |

---

## 3. Phase 0 — 技术验证（D1–D7，共 7 个工作日）

每个 Spike 有唯一编号（G1–G4 判定门）、明确方法与通过标准。**G1 失败不阻断 Phase 1**，仅切换基线路径。

### P0-1 · D1：工程初始化与参考素材入库

- 创建 Android 工程：minSdk 24 / targetSdk 36 / Kotlin 2.x / Compose BOM / arm64-v8a；git 仓库初始化。
- 从已解包的 `zleap-sag==0.14.0` wheel 与 SAG 主仓库提取参考素材到 `docs/reference/zleap-sag-0.14.0/`（只读参考，清单见第 9 节）。
- 建立 `docs/UPSTREAM_VERSIONS.md`：记录锚点三件套、参考素材来源路径、提取日期。
- **产出**：可编译空工程 + 参考素材入库 + 版本记录文档。

### P0-2 · D2：Universal Embedder demo 跑通与资源画像

- 以官方 `mediapipe-samples/examples/universal_embedder/android` 为起点：接入 `com.google.mediapipe:tasks-retrieval`，下载 `embeddinggemma-2-text-vision-440m.litertlm`（388MB）。
- 冒烟：`embedText()` 正常返回，维度/有限值校验。
- 记录（高、中端机各一）：模型冷加载时间、热推理单条延迟、峰值 RSS、CPU/GPU 两档对照（GPU 数据仅记录）。
- **产出**：`docs/PHASE0_REPORT.md` 初稿（资源画像节）。

### P0-3 · D3：Spike-1 / 判定门 G1（R1 任务模板对照）★ 头号验证

- **样本**：50 条（中文 25 / 英文 15 / 混合与代码 10），query 与 document 语义各半。
- **参考向量**：桌面端 Python + sentence-transformers + `google/embeddinggemma-2`，按官方规范生成两组参考向量（query 用 `SearchQuery` prompt；document 用 `title: none | text: ...` 格式），导出 JSON（float32 全精度）。
- **端侧对照**：对每条样本，Universal Embedder 分别以三种方式编码——(a) 原文直接 `embedText`；(b) 手工前置 `title: none | text: ` 模板；(c) 手工前置查询模板——与对应参考向量算余弦。
- **判定**：任一方式在 query 参考组与 document 参考组上均 ≥ 0.99 → **G1 通过**，锁定该方式为 Provider 的任务模板策略；(a)(b)(c) 全部不达标 → **G1 失败**，基线切换路径 B（Text Embedder + EmbeddingGemma v1 + TextFormatContext），写 ADR 记录。
- **注意**：(b)(c) 的目的是检测运行时是否自加模板（对不齐即运行时套了别的模板，存在双重拼接风险）。维度截断能力（MRL）若 API 暴露则一并验证，未暴露则记录"MVP 固定 768d"。
- **产出**：G1 结论 + 向量对照数据表 + ADR（若失败）。

### P0-4 · D3–D4：Spike-2 / 判定门 G2（R2 模型资产盘点）

- 核实 270M text-only `.litertlm` 是否在任何官方仓库发布（litert-community 各 repo 文件树逐一确认）。
- 若无：接受 440M 文件 + on-demand modality loading（记录实际文本模式 RSS，对照 D2 数据）；同步盘点路径 B 的 Text Embedder 模型（EGemma v1 对应 .tflite/task 文件）体积与加载方式。
- 产出模型分发设计结论：**应用内下载器 + SHA-256 校验 + 断点续传**（MVP 不打包 388MB 进 assets；Play Asset Delivery 评估结论一并记录）。
- **判定**：G2 通过 = 存在明确可下载、可校验、体积可接受的模型资产与分发方案。
- **产出**：G2 结论 + 模型资产清单（URL、体积、SHA-256、许可）。

### P0-5 · D4：Spike-3 / 判定门 G3（R3 中文分词方案）

- 三方案原型，各用 50 条中文关键词查询在小型中文语料（≥200 chunks，可取 SAG 文档与自带笔记）上对照：
  - (a) FTS4 原样 unicode61（对照组，预期最差）；
  - (b) **bigram 预分词**（入库与查询两侧统一 bigram 化，空格连接）；
  - (c) 分词器预分词（评估 1–2 个可嵌入 Android 的轻量分词器）。
- 指标：关键词命中率（预期文档是否进入 FTS 结果）与误报率；记录索引体积增幅。
- **判定**：G3 通过 = 选定一个方案，其在命中率上显著优于 (a) 且误报可控（bigram 通常即达标；分词器仅在 bigram 不达标或索引体积不可接受时引入）。
- 附带决策：自带 SQLite（统一 FTS5/sqlite-vec）**推迟到 ANN 阶段**，MVP 维持框架 SQLite + FTS4。
- **产出**：G3 结论 + 分词方案决策记录 + 对照数据。

### P0-6 · D4–D5：Spike-4 / 判定门 G4（精确扫描基准）

- 数据：1k / 5k / 10k × 768d 向量（真实 chunk embedding 与随机向量 8:2 混合）。
- 三种实现对照（Kotlin/NDK 均可）：
  - (a) SQLite BLOB 逐行读取 + 逐条点积；
  - (b) 启动时预载 `FloatArray[]` 内存扫描；
  - (c) 平铺二进制向量文件 + mmap 顺序扫描。
- 记录：冷/热缓存 p95（Top-10）、内存峰值、实现复杂度。
- **判定门槛**（Android PRD §9.2）：1k ≤ 150ms、10k ≤ 800ms；至少一种实现达标即 **G4 通过**，选定为 Phase 1 `VectorIndex` 实现方案；同时记录该实现的可扩展上限（预判 5 万条的延迟外推，供 ANN 触发线参考）。
- **产出**：G4 结论 + 基准数据表 + 实现选型。

### P0-7 · D5–D7：判定门汇总与 go/no-go

- 完成 `docs/PHASE0_REPORT.md`：G1–G4 结论、基线路径锁定（A 或 B）、扫描实现选型、设备资源画像、风险表更新。
- **go/no-go 规则**：G2/G3/G4 任一失败 → 调整后重验（不预计发生；bigram 与 mmap 方案均有高度把握）；G1 失败 → 切路径 B，直接 go。
- **产出**：Phase 0 报告 + Phase 1 启动确认 + 更新后的风险表（本文档第 6 节）。

---

## 4. Phase 1 — 离线搜索 MVP（W1–W3，共 15 个工作日）

### 4.1 W1：数据层与导入管线（P1-W1-1 … P1-W1-5）

| 任务 | 内容 | 产出/验收 |
|---|---|---|
| P1-W1-1 Room schema | Android PRD §4.2 十表落地 + 修订：`chunk_embedding` 增加 `role` 列（对齐六向量角色协议，MVP 仅写 `content`，schema 预留）；`embedding_meta` 增加任务模板策略字段（按 G1 结论）；迁移机制用 Room Migration，`schema_migrations` 表记录版本 | schema v1 + 迁移测试（§9.1-10） |
| P1-W1-2 解析与分块 | `DocumentParser`（TXT/MD，SAF 内容 URI）→ `TextNormalizer`（NFC、换行统一，参照 wheel 内 `octx_vector_protocol` 的规范化定义）→ `Chunker`（分块策略从 wheel `pipeline/` 提取对照；记录 char_start/char_end） | 分块单测（边界：空文本、超长行、编码异常） |
| P1-W1-3 EmbeddingProvider | 接口 + EmbeddingDescriptor（含 embedding_space_id）+ 按 G1 结论的基线实现（路径 A 或 B）；批量、分批、取消、超时；输出 finite 校验 | Provider 单测（§9.1-2） |
| P1-W1-4 导入管线 | WorkManager 链式任务：解析→分块→embedding 分批→入库；`ingest_job` 状态机（queued/running/paused/failed/done）；进程被杀后恢复或明确标失败 | 飞行模式导入 100 条中等文本 → Ready → 可检索（§9.1-1/4）；无 OOM/ANR（§9.2） |
| P1-W1-5 模型分发 | 应用内下载器：URL + SHA-256 校验 + 断点续传 + 磁盘不足处理（按 G2 方案） | 下载/校验/恢复单测（§9.1-10 部分） |

### 4.2 W2：检索服务与 UI（P1-W2-1 … P1-W2-5）

| 任务 | 内容 | 产出/验收 |
|---|---|---|
| P1-W2-1 VectorIndex | 精确扫描实现（按 G4 选型）；查询前校验 embedding space 一致性，不匹配则明确报错拒绝检索 | 模型不匹配拒绝测试（§9.1-3） |
| P1-W2-2 KeywordRetriever | FTS4 + G3 选定分词方案；查询侧同规则分词 | 中文关键词检索可用 |
| P1-W2-3 融合与证据 | CandidateFusion（向量 + 关键词加权融合、可配置权重）、chunk 去重、EvidenceResolver（chunk→document→source 回溯 + 原文定位） | Top-K 去重 + 原文定位（§9.1-6） |
| P1-W2-4 Compose UI | 五屏：导入（进度/取消/失败原因）、来源列表（状态、删除）、搜索（结果+得分+命中原因）、证据详情（原文高亮 char 区间）、设置（模型与维度显示、隐私声明、离线开关说明） | 端到端手动走查；无 LLM 时搜索可用（§9.1-8） |
| P1-W2-5 隐私与安全 | 应用私有目录存储；日志不落正文与向量；无任何网络调用路径（MVP 无远程 Provider，代码层移除网络权限） | 权限清单审查 + 日志扫描（§9.1-9 等价项） |

### 4.3 W3：健壮性、评测与验收（P1-W3-1 … P1-W3-6）

| 任务 | 内容 | 产出/验收 |
|---|---|---|
| P1-W3-1 删除级联 | 删除 source → 级联 document/chunk/embedding/FTS；检索不再命中 | §9.1-5 |
| P1-W3-2 模型切换与重建 | generation 双缓冲：新 embedding space 构建成功并校验后切换 active；失败保留旧代；重建期间不混用向量空间 | §9.1-7 |
| P1-W3-3 健壮性测试 | 数据库迁移、FTS/派生索引损坏重建、磁盘不足、模型文件缺失/损坏 —— 全部自动化测试 | §9.1-10 |
| P1-W3-4 评测集与质量报告 | 200 条人工标注查询：事实查找 40 / 同义表达 40 / 多跳关联 40 / 精确名称 40 / 对抗 40；语言覆盖中文/英文/混合/代码/数字日期。分别报告向量、关键词、融合三通道的 Recall@5/10、MRR@10、NDCG@10 | 质量报告（阈值按 Android PRD §9.3 或书面记录差距）；顺带产出 768d vs 256d 对照（若 API 支持），作为 MRL 决策输入 |
| P1-W3-5 设备基准 | 三档实体机（高/中/低端）跑 §9.2 全指标：检索 p95（1k/10k）、单条 embedding p95、冷加载、RAM、APK 增量、模型体积、导入吞吐与温控表现 | 三档设备基准报告 |
| P1-W3-6 发布 | Phase 1 验收清单（第 5 节）逐项核对；已知限制文档；内部 debug APK（MVP-1） | MVP-1 发布记录 |

---

## 5. 验收标准映射（Android PRD §9.1 → 本计划任务）

| PRD 验收项 | 内容摘要 | 责任任务 |
|---|---|---|
| §9.1-1 | 飞行模式完成 embedding/入库/检索 | P1-W1-4, W2 全部 |
| §9.1-2 | 重复 embedding 维度固定、无 NaN/Inf | P1-W1-3 |
| §9.1-3 | 同一 embedding space 与任务模板；不匹配明确报错 | P0-3（G1）+ P1-W2-1 |
| §9.1-4 | 导入可暂停/取消；进程被杀可恢复或标失败 | P1-W1-4 |
| §9.1-5 | 删除后数据不再出现在检索结果 | P1-W3-1 |
| §9.1-6 | 结果回到来源与原文位置；Top-K 去重 | P1-W2-3 |
| §9.1-7 | 切换模型触发重建；不混用向量空间 | P1-W3-2 |
| §9.1-8 | 无 LLM 时搜索可用，需生成时明确提示 | P1-W2-4（MVP 无生成，UI 预留提示） |
| §9.1-9 | 远程模式可彻底禁网 | P1-W2-5（MVP 直接无网络权限，更强） |
| §9.1-10 | 迁移/损坏恢复/磁盘不足/模型缺失自动化测试 | P1-W1-1, P1-W3-3 |

---

## 6. 风险监控点（实施期）

| 风险 | 监控点 | 触发信号 | 回退动作 |
|---|---|---|---|
| R1 任务模板缺失（调查报告 §二） | D3 判定 G1；W2 Provider 冻结 | 三种前缀方式余弦全部 < 0.99 | 切路径 B（Text Embedder + EGemma v1），ADR 记录；EGemma 2 等 ML Kit |
| R2 模型资产（附录 B.2 前身） | D4 判定 G2；W1-5 下载器实现 | 270M 文件不存在且 440M 文本模式 RAM 超预算（> 400MB） | 接受 440M + 按需加载并记录；仍超则路径 B 模型体积更小 |
| R3 中文分词（调查报告 §二） | D4 判定 G3；W2-2 实现 | bigram 命中率不达标或误报失控 | 引入轻量分词器；仍不行则关键词通道降级、语义通道为主（记录限制） |
| R4 GPU delegate 脆弱 | D2 demo 期记录 | GPU 模式崩溃/结果异常（Qualcomm 设备） | 默认 CPU(XNNPack) 不变，GPU 开关不发布 |
| RAM/低端机 | W3-5 三档基准 | 低端机（4–6GB）峰值 RSS 不可接受 | 评测 256d(MRL) 截断方案；导入并发降档；最低设备规格写入已知限制 |
| 上游版本漂移 | 每周核对 SAG repo | 上游发布新 minor | 不跟随；升级走独立 PR + 参考素材 diff（见第 2 节基线） |
| 工期 | W1/W2 周末检查点 | W2 末五屏未齐 | 砍设置页高级项与多主题，保五屏主干；评测集砍至 120 条（五类各 24）并记录差距 |

---

## 7. Phase 2/3 路线图（简述，另行立项）

- **Phase 2 关系检索（预计 1–2 周 + 质量不可控预留）**：event/entity schema 与 SQL 关系扩展（对照参考素材 `db/models.py`）、规则版 extractor（对照 `prompts/extract_document.yaml` 设计规则，或远程 LLM 混合）、候选融合引入关系分数。**增益验证门**：关系扩展对 Recall/MRR 的提升不显著则坦然接受"高质量离线搜索"定位，不强推（调查报告 R5）。
- **Phase 3 可选生成与生产化（2–4 周）**：远程 LLM AnswerGenerator（显式联网授权 + 引用校验）或本地 LLM 评估；备份/导出、诊断页、遥测（本地）。
- **ANN 演进触发条件**（调查报告附录 B.4）：chunks > 5 万或检索 p95 超标 → usearch（首选）/hnswlib（保底）NDK+JNI spike，验收门"与精确扫描对照 recall@10 ≥ 0.99 且分数一致"；同步观察 sqlite-vec ANN 成熟度与 ML Kit 发布。

---

## 8. 启动检查清单（Phase 0 开工前逐项确认）

- [ ] 版本锚点三件套已写入 `docs/UPSTREAM_VERSIONS.md`（P0-1 产出）。
- [ ] 三档实体设备到位：高端（旗舰 SoC / ≥12GB）、中端（次旗舰 / 8GB）、低端（入门 / 4–6GB），均 arm64。
- [ ] 模型下载 URL 可达：440M `.litertlm`（litert-community）；路径 B 的 Text Embedder 模型文件。
- [ ] 桌面端参考向量环境就绪：Python 3.11+、sentence-transformers、`google/embeddinggemma-2` 权重缓存、HF 访问（首次下载需联网）。
- [ ] Android Studio 当前稳定版 + 对应 SDK 36 / NDK（如需 c 实现扫描）；JDK 17+。
- [ ] 开发机磁盘预留：模型缓存 ≥ 5GB（多模型对照）。

---

## 9. 参考素材提取清单（P0-1 执行）

从 `zleap-sag==0.14.0` wheel（已解包核验）与 SAG 主仓库提取到 `docs/reference/zleap-sag-0.14.0/`，作为端侧实现的只读对照：

| 来源 | 文件 | 用途 |
|---|---|---|
| wheel | `zleap/sag/core/storage/index_schemas.py` | 4 张 collection 的字段/索引声明（chunk 双向量、FTS 列、keyword 列），端侧表设计对照 |
| wheel | `zleap/sag/db/models.py` | 关系表完整定义（data_source/kb_document/article/entity/EventEntity 等），Phase 2 关系层对照 |
| wheel | `zleap/sag/core/storage/lancedb_store.py` | `vector_search`/`search` 的过滤、投影、分数语义，端侧 VectorIndex 行为对照 |
| SAG repo | `apps/api/sag_api/sag/octx_vector_protocol.py` | 六向量角色 + 规范化 + 指纹设计，`chunk_embedding.role` 与 embedding space 管理对照 |
| wheel | `zleap/sag/pipeline/`（chunking 相关） | 分块策略对照（P1-W1-2） |
| wheel | `zleap/sag/prompts/extract_document.yaml`、`rerank.yaml` | Phase 2/3 备用，本期只读存档 |

> 提取时保留原文件路径结构与头部注释；不修改、不翻译。升级 SAG 参考版本时对新旧目录做 diff 并评估影响。

---

## 10. 里程碑与交付物汇总

| 里程碑 | 时点 | 交付物 |
|---|---|---|
| M0 工程就绪 | D1 末 | 空工程 + 参考素材 + UPSTREAM_VERSIONS.md |
| M-P0 技术验证完成 | D7 末 | PHASE0_REPORT.md（G1–G4 结论、基线锁定、选型、资源画像） |
| M-W1 数据层完成 | 第 8 工作日末（W1 末） | schema v1 + 导入管线可用（飞行模式导入→检索冒烟） |
| M-W2 检索与 UI 完成 | 第 13 工作日末 | 五屏 UI + 融合检索 + 证据回溯端到端可用 |
| **MVP-1 发布** | **第 22 工作日末** | 内部 debug APK + 三档设备基准报告 + 质量评测报告 + 已知限制文档 |

---

**文档结束。**
