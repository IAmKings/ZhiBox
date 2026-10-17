# ZhiBox × SAG 应用功能 PRD（v1.0）

- **文档状态**：**v1.0 已评审（2026-10-18）**；作为 B8–B12 的产品基准（本次只交付规划，不实现代码）
- **日期**：2026-10-18
- **适用产品**：ZhiBox Android 端个人知识应用（现有 Phase 1 MVP + B1–B7 底座）
- **上游依据**：
  - SAG 应用 README-CN：<https://github.com/Zleap-AI/SAG/blob/main/README-CN.md>（repo main commit `a6f6a00f36ce0cbe43cd2fc0445b479e4ae4fff6`，README-CN blob `6b0df82e0a90457dc8f83efbd258b20d0b2b4596`，2026-10-16）
  - 论文：**SAG: SQL-Retrieval Augmented Generation with Query-Time Dynamic Hyperedges**，arXiv:2606.15971v2（2026-08-25）
  - 本地参考：`zleap-sag==0.14.0`（`docs/reference/zleap-sag-0.14.0/`，只读）
  - 当前实现：Phase 1 MVP + B1–B7（见 `docs/phase1/`、`docs/review/B*_FIX_REPORT.md`；当前 HEAD `0003d76`）
- **已确认决策**：
  1. **混合优先**：本地离线检索保底；SAG 的 LLM 环节（事件/实体抽取、查询实体识别、精排/上下文选择、带引用问答）默认接**用户显式配置的远程 OpenAI-compatible LLM**；可一键禁网；本地 LLM 后置。
  2. **P0 核心闭环**：事件-实体索引 + SAG 双路径/扩展/精排 + 带引用问答 + 事件-实体图谱/溯源；PDF/Office/网页导入放 P1。
- **抽取语义以论文/本 PRD 为准**：参考 `zleap-sag 0.14.0` 的 `extract_document.yaml` 允许一个 chunk 聚合/拆分多个事项、children 与 references，**不直接照搬**；本项目 P0 固定“1 chunk ≤1 个 event”，提示词需按此重写并评测（B9）。
- **阅读顺序**：本 PRD 定义"做什么/验收什么"；`docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md` 定义"按什么批次做"；`docs/review/SAG_APP_ALIGNMENT_REPORT.md` 给出"当前底座与 SAG 的差距证据"。

---

## 1. 产品定位与原则

### 1.1 定位
ZhiBox 是**本地优先的 Android 端个人知识库**：文档导入 → 结构化索引（chunk + event/entity）→ 多跳检索 → 原文证据 → 带引用问答/图谱查看。它不移除现有离线 chunk 检索，而是在其上增加 SAG 的 **event-entity 索引与查询时动态超边**能力；LLM 只在用户授权时用于抽取、查询理解、精排和生成。

### 1.2 产品原则
1. **证据先于生成**：所有答案/图谱节点最终落到原文 chunk；无证据时明确"资料不足"，禁止用模型常识补写。
2. **离线可检索**：已完成的索引在飞行模式下可检索；远程 LLM 不是搜索的前置条件。
3. **显式联网**：SAG 增强/问答开启与每次调用可见；禁网开关必须能测试"零请求"。
4. **append-only 结构**：event/entity 按 chunk 增量写入，不重建全局图；不追求完整实体消歧（论文同款限制）。
5. **可追溯**：抽取器/模型/提示词/空间版本进入数据与备份；每条证据可定位。
6. **渐进交付**：P0 只承诺本 PRD 的验收项；README 全功能（Agent/探索/集成）分层后置。

### 1.3 非目标（P0 明确不做）
- 不做服务端多用户/账号体系、云同步、班级/多租户。
- 不重建全局知识图谱；不实现 3D 探索模式/复杂 Agent 工具编排（P1/P2）。
- 不实现 PDF/Office/网页/OCR 解析（P1），不实现图片/音视频多模态（P2）。
- 不做本地生成式 LLM 落地（P2 评估）；不做 MCP/REST/OpenAI 兼容/Dify/dsh-sag（P2）。
- 不改动 K12 学习助手产品线（独立 PRD：`docs/Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md`）。

---

## 2. 用户与核心场景

### 2.1 目标用户
- **个人知识工作者/开发者**：存放笔记、文档、项目资料，需要"我以前在哪看到过"和跨文档关联。
- **研究者/学生（个人用途）**：需要多跳证据链（A→B→C）而非单段相似度。
- **Agent 使用者（后续）**：通过 API/MCP 把同一知识库暴露给外部 Agent（P2）。

### 2.2 核心用户旅程（P0）
1. 用户在设置中配置远程 LLM（或保持未配置）→ 导入 TXT/Markdown → chunk 索引就绪（可立即离线搜索）。
2. 若 LLM 已配置：后台抽取每 chunk 的 1 个 event + 多个 typed entities，写入 event/entity 索引；状态可见、可重试/取消/补抽取。
3. 用户选择检索模式（`vector` / `multi_es_fast` / `multi`），可限定 source。
4. 多跳问题：系统做 seed（entity 路径 + event 向量路径）→ 1 跳 SQL 扩展 → 候选排序；`multi` 再由 LLM 选择证据事件。
5. 结果回到原始 chunk；用户打开证据并核对。
6. 用户发起带引用问答：系统用检索证据调用远程 LLM，流式返回带可点击引用的答案；引用不合法/无证据时拒绝或标注。
7. 用户在来源页查看 event-entity 图谱，点 event 查看实体与原文 chunk。

---

## 3. 范围与优先级

| 优先级 | 定义 | 范围 |
|---|---|---|
| **P0** | 发布阻断，必须由 B8–B12 交付 | §5 的 FR-01～FR-11 |
| **P1** | 第一版后应交付 | PDF/Office/网页导入、Explore/3D 图谱、多轮 Agent/工具、别名表、抽取质量专项 |
| **P2** | 后续按触发条件 | MCP/REST/OpenAI 兼容/Dify/dsh-sag、本地 LLM、云同步/多用户、多模态、ANN |

P0 成功定义：用户可在离线模式完成 chunk 检索；在配置 LLM 后完成"导入→事件/实体索引→多跳检索→带引用问答→图谱核对"的端到端闭环，且满足 §11 验收门。

---

## 4. 术语与 SAG 机制映射

| SAG/论文术语 | 本项目含义 |
|---|---|
| event | 每个 chunk 抽取出的 1 条语义完整事实陈述（保留 n 元关系，不拆三元组） |
| entity | 用于索引/扩展的 11 类实体：time/location/person/organization/group/subject/work/product/action/metric/tags |
| latent hyperedge | 一个 event 与其全部 entities 的 incidence 集合（不物化全局图） |
| seed 双路径 | Path A：query entities → entity 向量相似 → SQL 共享实体 join；Path B：query 向量 → event 向量（低阈值） |
| expansion | seed events → 共享 entities（frontier）→ 未见 events（默认 1 跳，`L=1`） |
| contextual selection | LLM 读取 top candidates，联合选择 ≤Kevent 个互补事件（不是逐条 pointwise rerank） |
| dual-path output | 结构路径 events→chunks（≤5）+ 语义路径 chunk 向量补足，最终 Kout=10 |
| 证据 | 最终输出始终为原 chunk 及 charStart/End，不是 event 文本 |

论文关键默认值（作为 PRD 基线，移动端可降档但需评测校准）：`L=1`、`Kseed=50`、frontier=50、`Kcand=100`、`Kevent≤5`、`Kout=10`、entity 相似阈值 `τent=0.9`、event 相似阈值 `τevt=0.4`。

---

## 5. 功能需求（P0）

> FR 编号为后续路线图/测试/验收的单一引用源。每条 FR 必须满足"行为 + 失败/降级 + 验收"三要素。

### FR-01 事件-实体索引与抽取
- **来源**：论文 §3.1/§3.2；README-CN 知识导入；参考 `prompts/extract_document.yaml`。
- **行为**：
  - 每个 ready chunk 最多 1 个 event；event 含 `title/summary/content/language`；关联 ≥1 个实体；实体类型必须来自 11 类封闭枚举，名称+描述+类型。
  - 抽取在**后台任务**执行，不阻塞 chunk 索引/离线检索；每 chunk 独立失败隔离；记录 `extractorVersion/modelId/promptVersion/createdAt`。
  - 支持状态：`NOT_INDEXED / QUEUED / RUNNING / DONE / FAILED / CANCELLED`；失败可重试、可取消、可"补抽取"。
  - 抽取器通过 `EventEntityExtractor` 接口抽象；P0 实现远程 LLM 版本；无 LLM 配置时保持 `NOT_INDEXED`，UI 明示"SAG 增强未启用"。
  - event/entity 数据只追加（append-only）；同一 chunk 重抽取产生新版本并使旧版本失活，不静默覆盖历史证据。
- **失败/降级**：LLM 未配置/离线 → 不调用网络，chunk 检索照常；LLM 错误/超时/JSON 非法 → 该 chunk 标 FAILED，可重试；部分实体无效 → 过滤该实体并记录 warning（不吞掉整个 event 除非 event 无效）。
- **验收**：100 chunk 测试集抽取成功率 ≥99%（网络正常），失败项有可读错误；取消/重试/进程被杀恢复通过；event→chunk 100% 可追溯；**30–50 chunk 人工抽检**：event 事实忠实性 ≥90%、实体类型准确率 ≥85%、每 event ≥1 entity 覆盖率 100%（B9 首次校准，可调高不可调低）。

### FR-02 向量与词法索引扩展
- **行为**：
  - chunk 保留现有 content 向量与 FTS4 bigram；新增 **event content** 向量、**entity name+description** 向量（独立 role/空间指纹，不与 chunk 混用）。
  - event/entity FTS（名称/内容）用于实体精确/词法召回；entity 名称先做与 chunk 一致的 NFC 规范化。
  - 支持批量 backfill（老文档补抽取后建立向量/FTS）；空间不匹配拒绝（沿用现有空间指纹策略）。
  - 删除 source/document 时级联删除 event/entity/sag 向量/FTS，保证"删除后不再出现在检索/图谱"。
- **失败/降级**：嵌入失败 → 该对象标失败可重试；空间缺失 → 检索拒绝并提示"需重新抽取/重新嵌入"；向量矩阵超限沿用流式扫描。
- **验收**：删除级联五表+新表零残留；模型切换后旧空间不被静默混用；backfill 后 event/entity 检索可用。

### FR-03 三模式检索
- **行为**：
  - `vector`：**现有 chunk 混合检索（chunk 向量 + FTS + RRF）**，不依赖 event 层；这是对 README 纯 dense `vector` 的向后兼容近似，UI/文档须标注“混合”而非纯向量。
  - `multi_es_fast`：**event 向量 seed（Path B）+ entity 词法/向量匹配（Path A）+ 1 跳 SQL 扩展** + 确定性排序融合（无 LLM 精排）；chunk 向量只用于语义补位/事件层缺失降级；事件层缺失时自动降级为 `vector` 并提示。
  - `multi`：在 `multi_es_fast` 候选集上由 LLM 做上下文选择/精排（≤Kevent events），结构路径映射 chunk + 语义路径补足，Kout=10；无 LLM/禁网时拒绝该模式并提示切换 fast/vector。
  - 检索请求支持 `query`、`mode`、`topK`、`sourceIds`（空=全局）、设备档位预算；返回逐阶段 trace（seed entities/events、expanded、candidates、selected、timings）。
- **失败/降级**：实体/事件索引为空 → fast 降级 vector；SQL 超时/候选超预算 → 截断并记录；LLM 超时/限流 → `multi` 降级 fast 并标注 degraded。
- **验收**：三模式在空库/单文档/多文档/source 限定下行为一致（模式×范围矩阵测试）；vector 不依赖 event；fast 在事件层存在时使用 event seed，缺失时降级 vector 并提示；multi 无 LLM 时拒绝并提示；降级路径有可读原因；trace 可在设置/诊断页导出（脱敏）；fast p95 按 §9.1 档位。

### FR-04 查询理解
- **行为**：`multi`/`multi_es_fast` 的 query entity 来源：
  - 有 LLM 且开启：LLM 输出 query entities（封闭 schema，最多 N 个）；
  - 无 LLM/禁网/fast 模式：确定性词法匹配（entity normalized name/FTS 精确/别名表精确），不调用网络；
  - 两种来源都进入统一 `QueryEntities` 结构并写入 trace。
- **失败/降级**：LLM 返回非法实体 → 只使用词法结果；实体过泛（如"时间"）→ 丢弃并记录。
- **验收**：离线 fast 模式零网络请求；LLM mock 返回坏 JSON/超时/空实体时 fallback 词法匹配且检索可用；`multi` 的 query entity 阶段 p95 ≤2s（远端，不含检索）。

### FR-05 双路径输出与证据闭环
- **行为**：
  - 结构路径：LLM 选择 ≤Kevent events → 映射到原 chunk（每个 event 一个 owning chunk），去重；
  - 语义路径：chunk 向量/FTS 排序补足 Kout=10（排除已选 chunk）；
  - 每个结果含 `chunkId/documentId/sourceId/title/charStart/charEnd/eventId?/entityIds/通道来源/分数`；
  - 同一 chunk 多路命中只返回一次，保留最强通道与事件关联。
- **验收**：所有结果 100% 可打开原文；去重后数量 ≤Kout；event→chunk 映射与 DB 一致；SQL 扩展候选事件全部有 owning chunk。

### FR-06 带引用问答
- **行为**：
  - 入口：搜索结果页/独立 Chat 屏；先检索（默认 `multi`，可切换模式），再把**证据 chunk 原文**作为上下文调用远程 LLM；流式输出。
  - **P0 范围：单轮问答**（每次回答只使用本轮 query + 证据，不注入历史上下文；多轮上下文/Agent 工具放 P1）。会话/消息/引用持久化，source scope 随消息保存；历史可查看/重开，但不作为下一问的隐式上下文。
  - 引用格式：答案内 `[S1]…[Sn]`（或等价稳定 ID），映射到 evidence list；UI 可点击打开 chunk/原文；
  - 校验：引用必须存在于本次证据；不存在的引用被移除并在 UI 标注"未通过证据校验"；无证据/证据不足 → 明确回复"资料不足，无法回答"（不得生成未引用断言）。
  - 会话/消息/引用持久化（P0 单轮；多轮上下文 P1）；source scope 随消息保存、重试复用；停止生成；失败可重试。
  - 无 LLM/禁网：不发起网络请求，提示"当前为离线检索；生成回答需配置 LLM"。
- **验收**：引用有效性 100%；抽样 50 条答案证据支持率 ≥90%；无证据问题不产生编造答案；断网/超时/限流有可读错误；停止/重试行为正确。

### FR-07 事件-实体图谱与事件详情
- **行为**：
  - Source 页增加"图谱"视图：以 event 为节点、共享 entity 连接（按 entity 聚合/展开），支持按 source 过滤；P0 为 2D 列表/图，节点/边有上限与分页；点击 event 显示 title/summary/entities/chunk 原文入口。
  - 图谱数据直接来自 SQL incidence，不物化全局图；超过渲染预算提示"缩小范围/按实体筛选"。
  - 无抽取数据的 source 显示"SAG 增强未启用/无事件"空态。
- **验收**：任意 event 能定位 entities 与 owning chunk；删除 source/document 后节点/边不再出现；10k event 下来源页打开不 OOM、可交互；图谱单页节点 ≤200、边 ≤1000，超出分页/筛选；图谱查询 p95 ≤1.5s（不含首帧渲染）。

### FR-08 LLM 与隐私设置
- **行为**：
  - 设置页新增 LLM：base URL（HTTPS）、model、API key、超时、最大 tokens；"测试连接"显示模型/维度/延迟；保存前不清空已存 key。
  - API key 存 Android Keystore/EncryptedSharedPreferences，不出现在日志/备份/导出；UI 不明文回显。
  - 开关：`SAG 增强`（抽取）与 `问答生成`独立开关；`禁止网络`总开关；每次联网调用前 UI 明示目标域与用途（首次确认）。
  - 只发送完成任务所需的最小数据：query、候选 event/chunk 文本、必要元数据；不上传原文件路径/本地库全文；日志脱敏。
  - 网络配置：HTTPS only；现有 `network_security_config` 的 base-config 已默认**允许任意 HTTPS、禁止明文**，LLM endpoint 不需要改网络安全配置；模型下载仍只允许 hf-mirror HTTPS 白名单；**禁止 HTTP/明文**，不做证书固定（P0）；域名变更保存即生效，首次调用显示目标 host。
- **验收**：抓包/单测证明禁网模式零请求；密钥不入库/日志/备份；错误可读；LLM 未配置时全部非 LLM 功能可用。

### FR-09 状态、诊断与可观测
- **行为**：导入/来源页显示 chunk 索引与 SAG 抽取两个状态（已抽取/待抽取/失败/未启用）；**每次检索/问答必须生成 trace**（seed→expanded→candidates→selected→citation）、耗时、降级原因；trace 仅保留最近 N=50 条脱敏记录，不含正文。
- **验收**：失败可定位到 chunk/阶段；trace 可导出脱敏 JSON；**无 trace 的检索请求视为实现缺陷**；无正文日志；记录只保留 hash/计数/耗时/错误码。

### FR-10 备份/迁移/数据生命周期
- **行为**：
  - Room schema v6：新增/扩展 event、entity、event_entity、sag_vector、event/entity FTS、chat/message/citation、extraction_job；保留 v5 全部表与迁移链。
  - 备份 v3：包含 chunk/event/entity/incidence/sag 向量/空间 meta/抽取版本/`extraction_job` 元数据；**P0 默认不含 chat 会话与 query trace**（隐私/体积；P1 可加可选导出）；原文文件仍不随备份迁移（沿用 B1 规则）；导入校验空间/模型/抽取兼容性，不兼容时提示重抽取。
  - v5 库升级 v6：老文档 `NOT_INDEXED`，不自动调用网络；用户可在线补抽取；旧备份导入后 SAG 结构为空但不阻塞 chunk 检索。
  - 删除文档/来源级联新表；模型空间切换不静默复用。
- **验收**：v5→v6 迁移测试；备份 v3 导出→清库→导入回环；旧备份导入不崩；删除级联零残留；禁网升级可用。

### FR-11 评测与回归门
- **行为**：新增中文多跳评测集（≥120 条、2–4 跳、人工校验证据），覆盖多文档/单文档、source 限定、无答案问题；脚本输出 R@5/10、MRR、citation validity/support、延迟/内存；CI 只跑 mock/纯逻辑，真机跑 LLM 验收。
- **门禁**（P0 发布前）：
  - 多跳 R@5 ≥ 当前融合基线 +5pp 且绝对值 ≥0.70；
  - 单跳 262 集 R@5 回退 ≤0.5pp；
  - 引用有效性 100%，抽样答案证据支持率 ≥90%；
  - 禁网零请求；迁移/备份/取消/失败路径回归通过；
  - 延迟按 §9 分档（高/中/低端）。
- **说明**：论文的英文基准（MuSiQue/HotpotQA/2Wiki）与服务器 LLM 配置不能直接横比；上述门槛为本机语料/设备初始校准值，允许在 B10 基线产出后由 Lead 批准调整一次，但不得降低方向（安全/引用/隐私门禁不调整）。

---

## 6. 数据模型与迁移（目标 schema v6）

> 具体 Kotlin 类型/DAO 在 B8 实现；本节定义字段语义与约束，避免实现期再做决定。

### 6.1 新增/扩展表
| 表 | 关键字段 | 约束/说明 |
|---|---|---|
| `event`（扩展） | id, chunkId FK, title, summary, content, language, status, extractorVersion, modelId, promptVersion, confidence, createdAt, active, supersededBy | 1 chunk 最多 1 条 active event（应用层事务保证；`unique(chunkId, extractionVersion)`，`(chunkId, active)` 普通查询索引）；`chunkId` 级联删除；`supersededBy` 指向新版本 |
| `entity`（扩展） | id, name, normalizedName unique, type, description, createdAt | 11 类枚举；`normalizedName` 唯一（沿用现有）；别名表 P1 |
| `event_entity`（扩展） | eventId, entityId, weight, description | 复合唯一（eventId, entityId）；两 FK 级联 |
| `sag_vector`（新） | id, ownerType(EVENT/ENTITY), ownerId, role, spaceId, dims, vectorBlob, createdAt | 与 chunk_embedding 分离；owner 级联/显式清理；`(spaceId, role, ownerType)` 索引 |
| `event_fts` / `entity_fts`（新） | rowid/ownerId, ftsText | FTS4 external content 或独立表；与 chunk_fts 一样可重建 |
| `extraction_job`（新） | id, documentId, chunkId, status, attempt, errorCode, errorDetail, createdAt/updatedAt | 每 chunk 一条；支持取消/重试；复用现有 job 状态语义 |
| `chat_conversation` / `chat_message` / `message_citation`（新） | conversationId, title, sourceScope, createdAt；messageId, role, content, model, createdAt；citation messageId, chunkId, rank | 引用可点击回原文；删除会话不影响知识库 |
| `query_trace`（P0 必需，本地诊断） | id, queryHash, mode, timingJson, degradedReason, createdAt | 每次检索/问答必须记录；仅保留最近 50 条；不含正文；可清空/脱敏导出 |

### 6.2 向量角色与空间
- 角色至少：`chunk_content`（现有）、`event_content`、`entity_description`；**P1 可加 `chunk_heading`**。
- 每个角色独立 spaceId 指纹（含 provider/model/dims/taskTemplate/role）；查询向量与索引空间必须匹配；模型切换/重抽取生成新空间，不混用。
- **输出语言**：抽取指令用中文，但 event title/summary/content 与 entity name/description **使用源文档语言**（中文文档输出中文，英文文档输出英文）；`language` 记录到 event；实体名保留原文，不在 P0 做翻译/别名合并。
- event/entity 向量维度与 chunk 相同（当前 EGemma2 768d；扩展模型走空间指纹与重抽）。
- 实体向量输入 = `name + description`（规范化后）；event 向量输入 = `title + content`；查询向量复用同一 embedding provider。

### 6.3 迁移与生命周期
- v5→v6 全部 additive（新表/新列/索引）；禁止在迁移中做网络/重嵌入；老文档 `NOT_INDEXED`。
- 旧备份（v2）导入后：chunk 检索照常；event/entity 为空；提示可在线补抽取。
- 备份 v3 导出只含受支持空间（沿用 B7 legacy 规则）；导入校验 embedding 模型/role/空间；LLM 不参与备份依赖。
- 空间/模型缺失/换文件沿用 `spaceId` 与修订指纹规则；不静默检索错空间。

---

## 7. 架构与数据流

```
导入(SAF) → 解析(txt/md) → chunk → chunk embedding/FTS → ready（现有）
                                  ↓（LLM 配置且开关开启）
                        extraction_job → 每 chunk 一次 LLM 抽取（1 event + entities）
                                  ↓
                  event/entity 行 + event/entity embedding + FTS + sag_vector
检索请求(mode, scope, budget)
  → query normalize/embed
  → [vector] chunk 向量 + chunk FTS + RRF
  → [fast] event 向量 seed + entity 匹配/SQL join + 1-hop expansion → 确定性融合
  → [multi] 以上 + LLM contextual selection → event→chunk + semantic fill
  → 证据 chunk/Kout/trace
问答：证据 chunk 原文 → 远程 LLM(stream) → 引用校验 → UI
```

### 7.1 核心接口（B8 实现，PRD 约束语义）
- `EventEntityExtractor`：输入 chunk+上下文+类型清单，输出结构化 event/entities 或结构化错误；实现可替换（RemoteLlm / Mock）。
- `QueryEntityExtractor`：输入 query，输出 entities；RemoteLlm + 词法 fallback。
- `SagRetriever`：输入 query/mode/scope/budget，输出 `SagSearchResult(hits, trace, degradedReason)`。
- `ContextualSelector`：输入 query+candidates，输出 ≤Kevent 的 event IDs+reason（JSON 校验）。
- `AnswerGenerator`：输入 query+evidence chunks，输出流式文本+引用 ID；不接触未返回证据。
- `CitationValidator`：校验引用集合⊆证据集合、每引用可定位、无引用断言标注。
- `LlmProvider`：OpenAI-compatible chat/completions（流式/非流式）、超时/重试/限流、token 统计；`OfflineGuard` 在禁网时拒绝。

### 7.2 预算与设备档位
| 档位 | Kpool | Kseed | frontier | Kcand | Kevent | Kout |
|---|---|---|---|---|---|---|
| 高端（旗舰/≥12GB） | 500 | 50 | 50 | 100 | 5 | 10 |
| 中端（8GB） | 300 | 30 | 30 | 60 | 4 | 10 |
| 低端（4–6GB） | 150 | 20 | 20 | 40 | 3 | 10 |

- `Kpool`：初始 chunk/event 向量候选上限；`Kseed`：seed event 上限；`Kcand`：进入选择器的 events 上限。
- 档位自动检测（RAM/SoC）但允许高级设置覆盖；越档需提示性能影响。预算/阈值全部进入 trace。

### 7.3 失败模式与降级
| 场景 | 行为 |
|---|---|
| 未配置 LLM / 禁网 | vector 正常；fast 若无事件索引→vector；multi 拒绝并提示 |
| 抽取失败/部分失败 | chunk 可用；失败 chunk 可重试；fast/multi 只用已完成事件 |
| entity/event 索引为空 | 自动降级 vector（UI 标注） |
| LLM 超时/限流/JSON 非法 | 重试 ≤1 次 → 降级 fast（multi）或标 FAILED（抽取）；记录 trace |
| SQL/候选超预算 | 截断、记录；不静默返回空 |
| 模型空间缺失/换文件 | 拒绝对应通道并提示重新抽取/嵌入 |
| 存储不足 | 导入前预检；失败可读，不产生半 ready 文档 |

---

## 8. LLM 与隐私设计

### 8.1 Provider/接口
- 只支持 **OpenAI-compatible Chat Completions**（HTTPS）；用户可配置 base URL/model/key；P0 不支持自定义 headers/证书。
- 请求：`stream=true` 用于问答；抽取/选择可用非流式；统一 JSON Schema 校验。
- 超时/重试：连接/生成超时可配置（默认 30s/120s）；429/5xx 指数退避最多 1 次；不重试 4xx 鉴权错误。
- token 预算：抽取 prompt ≤剩余上下文，长 chunk 截断并按 chunk 分片；选择器只发送 Kcand 个 event 的 title/summary（不是全文）；问答发送 Top-K 证据 chunk 原文（≤Kout）。
- 提示词版本：`extract_v1 / query_entities_v1 / select_v1 / answer_v1`，写入 event 与 trace；变更需评测回归。

### 8.2 隐私与安全
- 默认**不联网**（除模型下载）；SAG 增强/问答分别由用户显式开启；首次启用显示目标域名、将发送的数据类别、关闭方法。
- 禁网总开关：所有 LLM 调用前置检查，测试断言零 socket；网络错误/离线不自动重试到联网。
- API key：Android Keystore/EncryptedSharedPreferences；不写日志、不进备份、不导出；UI 只显示末 4 位。
- 日志：默认不记录 query/正文/实体文本；诊断 trace 仅长度/hash/耗时/错误码；用户可主动导出脱敏 JSON。
- 权限：仅 INTERNET（已有）；不使用后台定位/存储等多余权限。
- 数据最小化：不上传原始文件/完整库；只发送 query 与候选证据文本；不做遥测/第三方统计。

---

## 9. 非功能需求

### 9.1 性能（P0 门禁，真机分档）
- 导入：chunk 就绪路径不因抽取阻塞；抽取后台并发受电量/温度/网络限制（分块限速）。
- 检索（不含 query embedding，high/mid/low）：`vector` p95 ≤300/500/800ms；`multi_es_fast` p95 ≤800/1500/3000ms；`multi`（远端）首 token p95 ≤3/5/8s，总时长 ≤30s；流式可停止。
- 内存：峰值 RSS 初始门禁为高/中/低端 ≤2.0/1.6/1.2GB（B10 用真实基线校准；不得高于现有 B5/Phase0 基线 20%）；10k chunk + 10k event 下检索不 OOM；矩阵按现有流式门限。
- 存储：event/entity/向量/FTS 新增索引附加存储 ≤ 原库大小 1.5×（不含新导入原文）；超限提示清理/降档；备份 v3 体积上限与分卷策略在 B8 定。

### 9.2 可靠性与可维护性
- 进程被杀/重启：抽取任务恢复或明确失败，不产生半 ready；幂等。
- 升级：v5→v6 迁移可回滚到旧 APK 前先备份；旧数据不被破坏。
- 错误码统一 `CODE:detail`（沿用 B2）；UI 可读；诊断可导出。
- 测试：JVM/Robolectric、instrumentation、Compose UI、LLM mock、禁网/超时/坏 JSON；迁移/备份/删除级联。
- 可访问性/国际化：中文/英文文案资源；TalkBack 可读；图谱有列表替代视图；CJK 分词沿用现有 bigram + NFC。

---

## 10. UX / 页面（P0）

| 页面 | 变更 |
|---|---|
| 搜索页 | 模式选择（vector/fast/multi）、source scope 选择、降级/网络提示；结果卡显示通道/event 关联 |
| 证据页 | 沿用现有证据；若来自 event，显示 event title/entities；引用高亮 |
| Chat（新） | 会话列表/新建；输入→流式答案；引用 chip 可点；停止/重试；source scope；"资料不足"态 |
| 来源页 | 列表/图谱切换；event-entity 图谱（2D）；event 详情抽屉/页；抽取状态筛选 |
| 导入页 | 两阶段状态（chunk/抽取）；失败重试/取消；"SAG 增强未启用"提示 |
| 设置页 | LLM 配置/测试、SAG 增强开关、联网总开关、抽取并发/预算档、诊断导出、隐私说明 |
| 首页 | 保留现有入口；知识库概览/Chat 入口（概览 UI 放 P1） |

关键交互约束：任何联网动作前有可见状态；禁网时所有生成按钮点击给出明确提示；长任务可离开页面继续；引用不合法显示警示而非静默删除。

---

## 11. 验收与评测

### 11.1 P0 功能验收（合并 §5 FR 验收）
- 导入/抽取状态闭环；三模式；source scope；证据溯源；带引用问答；图谱；备份/迁移；隐私开关。
- 所有 P0 FR 的失败/降级路径有自动化或手测证据。

### 11.2 检索质量评测
- 新中文多跳集：≥120 条、2–4 跳、人工校验 evidence chunk IDs；覆盖多文档/单文档、同义/别名、无答案问题。
- **现有 262 集不能替代多跳证据**：`scripts/eval/evalset.json` 实测 n_docs=50，220 条正例均只有 1 个 relevant（含 50 条标为 multihop 的也各只有 1 个），42 条 adversarial relevant=0；**R@10=1.0（已到天花板）**。B10 必须另建**多证据 chunk** 的多跳集，不能用现有集充当主门禁。
- 指标：R@5/10、MRR@10、citation validity/support、拒答正确率、延迟/内存；**未引用断言**：答案事实句必须带 ≥1 有效引用（validator 规则 + 50 条抽样人工核查，未引用断言 = 0）。
- 对照：同一新多跳集上的**当前 chunk 融合基线**、`vector`、`multi_es_fast`、`multi`；消融：无扩展、无 LLM selector、无 event 层。
- 门禁：**主门禁针对 `multi`**（有 LLM，R@5 ≥ 同上基线 +5pp 且 ≥0.70）；`multi_es_fast` 目标 ≥ 基线（不得回退）且 ≥0.65，作为离线可用线；若 `multi` 未达门禁，B10 只发 fast 模式并记录 multi 实验，不阻塞离线检索。

### 11.3 LLM 行为测试
- Mock OpenAI server：正常流式、非流式、429/5xx、超时、坏 JSON、部分实体无效、引用幻觉；断言 fallback/错误可读。
- 真机：至少 20 条问答人工核验引用与证据；50 条抽样统计支持率。
- 禁网：网络层/测试断言零请求；开启后仅调用配置域名。

### 11.4 设备与回归
- 设备三档（现有测试机：PJZ110 旗舰、K60U 中端、K40 低端）；每档跑导入/检索/问答/迁移/备份矩阵。
- 回归：262 单跳集不回退；B1–B7 既有测试全绿；release APK 体积/权限无意外变化。

---

## 12. 路线图（摘要，详见 ROADMAP）

| 批次 | 主题 | 关键交付 | 门禁 |
|---|---|---|---|
| B8 | 数据/基础 | schema v6、LLM provider/密钥、抽取接口/prompt、备份 v3 骨架 | 迁移/备份/单测 |
| B9 | 抽取/索引 | 导入联动抽取、event/entity 向量/FTS、状态/重试/补抽取 | 抽取成功率/质量抽检 |
| B10 | 结构化检索 | 三模式、seed/扩展/排序/trace、多跳评测集 | 多跳 R@5/延迟门禁 |
| B11 | 精排+问答 | contextual selector、answer+citation、Chat/隐私 | 引用/证据/禁网门禁 |
| B12 | 图谱/发布 | 图谱/详情、设置/诊断、备份 v3、真机验收 | P0 全验收 |

P1：PDF/Office/网页导入、Explore/3D、多轮 Agent/工具、别名、抽取质量专项。
P2：MCP/REST/OpenAI/Dify/dsh-sag、本地 LLM、同步/多用户、多模态、ANN。

---

## 13. 假设、依赖与风险

### 13.1 假设
- 用户愿意为 SAG 增强/问答配置远程 OpenAI-compatible LLM；未配置时产品仍是有价值的离线检索。
- 当前 EGemma2 768d embedding 可同时服务 chunk/event/entity；若实测效果差，B9 可换模型并走空间重抽（不改架构）。
- Android 网络允许用户配置 HTTPS 端点（现有 base-config 默认允许任意 HTTPS、禁止明文；模型下载仍由 hf-mirror 白名单/SHA 校验约束；LLM 无需改网络安全配置）。
- 中文抽取提示词可按参考 `extract_document.yaml` 的规则中文化改写；不复制受版权限制内容（参考 MIT）。

### 13.2 依赖
- 现有 Room/空间/备份/证据/测试设施（B1–B7）稳定；B8 迁移不破坏。
- 远程 LLM 的可达性/费用由用户承担；无服务端账号体系。
- 评测语料/标注人力（≥120 条多跳）需在 B10 前就绪。

### 13.3 风险与缓解
| 风险 | 缓解 |
|---|---|
| 端侧/远端混合达不到论文分数 | 门禁按本地数据设；报告如实标注不可横比；先发 fast/离线 |
| LLM 隐私/成本 | 显式开关、最小 payload、token 预算、禁网测试 |
| 中文抽取质量/实体别名 | 11 类封闭 schema、抽检、别名表 P1、论文局限照录 |
| 首次配置/网络失败 | 可读错误、fast 降级、重试/补抽取；不阻塞离线 |
| schema/备份迁移 | additive v6、迁移测试、旧备份兼容、回滚手册 |
| 评测集偏差 | 人工校验/证据 ID 锁定；基线先测后调门禁（只调一次） |
| 范围蔓延 | P0 固定本 PRD；P1/P2 触发条件与路线图分离 |

---

## 14. 可追溯矩阵（FR ↔ 来源 ↔ 批次 ↔ 验收）

| FR | 论文/README 依据 | 批次 | 验收摘要 |
|---|---|---|---|
| FR-01 | 论文 §3.1/§3.2、附录 I；README 知识导入 | B8/B9 | 抽取成功率/状态/溯源 |
| FR-02 | 论文 §3.1/§3.3；参考 index_schemas | B8/B9 | 向量角色/FTS/回填/删除级联 |
| FR-03 | 论文 §3.3/§3.4；README 三模式 | B10 | 三模式/降级/trace |
| FR-04 | 论文 §3.3 Path A；README 检索 | B10 | 离线零网络/坏 JSON 降级 |
| FR-05 | 论文 §3.4 式(5)；README 原文溯源 | B10 | 去重/Kout/映射一致 |
| FR-06 | 论文 §3.4/§5.2；README 带引用问答 | B11 | 引用有效性/支持率/拒答 |
| FR-07 | 论文 §3.1/§3.2；README 图谱/探索 | B12 | event→chunk/级联/性能 |
| FR-08 | README 模型配置/安全；论文 LLM 依赖 | B8/B11 | 密钥/禁网/连通性 |
| FR-09 | 论文 §3.4 trace；README 状态 | B9/B10/B11/B12 | trace 必录/可读/脱敏 |
| FR-10 | 现有 B1/B7 迁移/备份；论文 append-only | B8/B12 | v5→v6/备份回环 |
| FR-11 | 论文 §4/§5 指标；README 评测 | B10/B12 | 多跳门禁/回归 |

---

## 15. 附录

### 15.1 SAG 论文关键事实（实现对照）
- 表示：chunk→1 event + 多 entities；incidence 表 O(H×V) 追加；entity 不做全局消歧。
- 检索：entity seed（LLM + 向量 τ=0.9 + SQL join）∥ event 向量（τ=0.4）→ merge；1 跳扩展（frontier 预算）→ Kcand 粗排 → LLM 选 ≤5 events → chunk 映射 + 语义补足至 10。
- 结果：MuSiQue R@5 80.36（+11.52 vs dense 基线最强 NV-Embed-v2 的 68.84；vs 结构基线 HippoRAG 2 65.13 为 +15.23）；avg R@5/F1 90.07/72.96；NarrativeQA EM 12.29/F1 31.90。
- 消融：无扩展 −10.95 R@5；pointwise reranker 替换 −13.25；triple 化 −2.75；Kcand 50→100 +1.01 R@5，但 token +40.1%、**端到端延迟 +10.4%**（retrieval 时间 15.93s→18.67s，+17.2%）。
- 局限：实体别名不消解；append-only 不支持时效更新/版本化事件。
- 成本：MuSiQue 离线 4.57h/28.26M tokens；在线 retrieval **15.93s @ Kcand=50**（F1 60.41/8,236 tokens/e2e 22.68s）、**18.67s @ Kcand=100**（F1 61.15/11,536 tokens/e2e 25.03s）；Kcand=50 更省。

### 15.2 与现有文档关系
- `docs/SAG_Android_MVP_Implementation_Plan_v1.0.md` §7 的 Phase 2/3 被本 PRD + ROADMAP 细化/取代；旧文档保留为 Phase 0/1 历史。
- 现有 B1–B7 报告继续作为底座行为/迁移/发布依据；本 PRD 不修改其结论。
- K12 产品线独立，不在本 PRD 范围。

---

*维护：P0 范围/验收门变更需 Lead 评审并在 §11 记录；P1/P2 变更更新 ROADMAP backlog；外部来源版本变化更新 §0 锚点与对齐报告。*

---

## 16. 文档评审记录

- **2026-10-18 双轴评审**：
  - Standards：3 硬性（证据行号、FR 批次闭合、本地 LLM 归属）+ 5 判断性 → 已修复（证据改为章节引用/行号、FR-09/FR-11 补齐 B11/B12、本地 LLM 统一 P2、trace/备份/网络/事件 active 明确）。
  - Spec：4 missing / 6 partial / 6 wrong / 2 scope-creep → 已修复（P0 单轮问答、event active 字段、trace 必录、备份范围、HTTPS 策略、抽取质量阈值、多跳门禁模式/基线、RSS/存储指标、P1 触发条件、B11 依赖图）。
- 本 PRD 为 v1.0 规划基准；实现批次（B8–B12）的变更需在 ROADMAP 中记录并回写本文档。
