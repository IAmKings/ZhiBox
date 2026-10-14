# Personal Memory AI + K12 Learning Assistant
## 可执行 PRD 与技术设计规格 v1.0

- **文档状态**：Draft for implementation / 可进入技术验证与 MVP 开发
- **版本**：v1.0
- **编写日期**：2026-10-09
- **目标形态**：本地优先（local-first）的单用户知识系统；后续可扩展多用户
- **核心组件**：SAG、Google EmbeddingGemma 2、本地生成式 LLM、China-K12-knowledge-taxonomy、FastAPI、SQLite
- **适用读者**：产品、架构、后端、AI/ML、测试、客户端工程师

> **重要说明**：本文件是可执行的 PRD + 技术设计，而非声明所有功能已由上游项目现成提供。SAG 与 EmbeddingGemma 2 的连接、K12 数据结构导入、学习状态/测评和助手编排均须按本文实现并通过验收。仓库 `main` 分支会变化；正式开发时必须记录 Git commit SHA、模型 revision、依赖锁文件与数据文件 SHA-256，不应仅依赖分支名。

---

## 0. 文档导航

1. 产品概述与目标
2. 范围、用户故事与非目标
3. 依赖项目现状与技术决策
4. 系统架构与运行拓扑
5. 模块设计与职责边界
6. EmbeddingGemma 2 本地推理服务规格
7. SAG embedding 适配规格
8. K12 知识图谱导入与映射
9. 数据库设计与 SQL DDL
10. API 契约
11. 检索、问答与学习流程
12. 配置、部署、隐私与安全
13. 测试策略与第一版验收标准
14. 里程碑、任务拆解与交付物
15. 风险、降级策略与待验证事项
16. 附录：示例配置、数据样例与上游参考

---

# 1. 产品概述与目标

## 1.1 产品定义

构建一个本地优先的 AI 知识平台，共享模型推理、文档解析、语义检索、来源追踪与基础存储，提供两个独立的产品工作区：

- **Personal Memory AI（个人知识记忆助手）**：导入个人文件、技术文档、读书笔记、网页和项目资料；通过语义搜索与带来源引用的问答，找回“我曾经在哪里看过这个问题”，并关联跨文档证据。
- **K12 Learning Assistant（中小学学习助手）**：将 China-K12-knowledge-taxonomy 作为可查询的知识点与先修依赖骨架，把知识点关联到教材、讲义、例题、错题和练习；根据学习者答题数据估计掌握情况并推荐下一步学习内容。

第一版采用共享基础设施，但严格隔离两类数据与检索范围。个人资料不能无意中进入学生学习上下文；学习者的答题和个人信息不能被通用记忆检索暴露。

## 1.2 产品原则

1. **本地优先**：Embedding、生成模型、应用数据库和 SAG 尽可能运行在本机或用户自控的局域网内。任何外部模型/解析服务必须显式配置并在 UI 中告知。
2. **证据先于生成**：模型回答应优先基于可定位的原文、知识点和题目数据；无证据时明确说明资料不足。
3. **检索模型与生成模型分离**：EmbeddingGemma 2 只负责向量表示与召回，不承担对话生成、数学推理或学习计划决策。
4. **图谱是结构数据，不是文本附件**：知识点 ID、先修边方向和关系类型必须保留为可查询的结构化记录，不能只转换成文本后交给向量检索。
5. **所有外部数据可追溯**：每个导入批次记录来源 URL/仓库、commit、文件 SHA、导入时间和校验结果。
6. **安全渐进交付**：先打通文本 RAG 和知识点导入，再开发错题诊断与个性化学习；第一版不追求端侧多模态和复杂智能体编排。

## 1.3 MVP 目标

- 在本地启动 SAG、EmbeddingGemma 2 文本 embedding 服务、一个可替换的本地生成模型服务以及应用 API。
- 将 EmbeddingGemma 2 作为 SAG 的文档向量模型，并使查询向量与文档向量维度及模型配置一致。
- 建立端到端的“导入文档 → 处理完成 → 语义检索 → 返回原文来源 → LLM 回答并附引用”闭环。
- 导入 K12 知识图谱核心数据，保留知识点主键、学科、年级、描述/掌握标准、领域簇、先修边和关联边。
- 提供可查询知识点、获取先修/后继链路、将文档关联到知识点、记录学生作答与掌握度估计的 API。
- 提供第一版自动化验收测试与可复现的本地开发启动步骤。

## 1.4 MVP 非目标

- 不重新实现 SAG 的文档索引、事件/实体检索和引用系统。
- 不对 SAG 内部数据库做直接写入或依赖未公开内部表结构。
- 不在第一版开发自有向量数据库；优先复用 SAG 默认 SQLite + LanceDB 后端。
- 不默认导入、复制或重新发布第三方教材 PDF。教材获取与再分发须单独核验版权/许可证。
- 不承诺 LLM 对学习结果的判断是教育测评意义上的权威诊断；第一版掌握度是可解释的启发式估计。
- 不提供公开多租户 SaaS、教师班级管理、支付、家长监控、云同步或手机端完整离线多模态能力。
- 不将 K12 图谱的知识点描述当成完整教材；教学解释需检索合法可用的课程内容并由 LLM 组织。

## 1.5 目标用户与核心场景

### 个人知识记忆

- 开发者：找回此前保存的 Android、Flutter、MQTT、BLE、RAG、模型部署等资料。
- 阅读者：通过自然语言搜索电子书、论文、Markdown 笔记中的概念和段落。
- 项目维护者：从 PRD、技术设计、日志和代码说明中查找设计决定与相关上下文。

### K12 学习助手

- 学生：询问知识点、获取分步讲解、做针对性练习。
- 家长/辅导者：查看某主题的先修路径和学生近期作答概况。
- 开发/内容维护者：查看知识点来源、数据版本、关系完整性和被关联教材/例题。

---

# 2. 范围、用户故事与需求优先级

优先级定义：**P0** 为发布阻断项；**P1** 为第一版应交付；**P2** 为后续迭代。

- **FR-001 · P0 — 本地 embedding 服务**：支持健康检查、模型状态查询和批量生成向量；输出数量、维度正确，所有值均为 finite，禁止 NaN/Inf。
- **FR-002 · P0 — SAG 接入 EmbeddingGemma 2**：SAG 必须实际使用指定服务为文档/事件/实体建立索引；以实际导入、Ready 和检索验证，不以单次 curl 成功代替。
- **FR-003 · P0 — 向量兼容性**：查询向量与索引向量必须使用兼容的模型、版本、维度和归一化策略；query/document 任务前缀经测试确认。
- **FR-004 · P0 — 文档生命周期**：支持文档导入、处理状态查询和语义检索；文档 Ready 后可命中并定位原文。
- **FR-005 · P0 — K12 图谱导入**：可重复执行导入，保留来源 ID 与关系方向；无重复、无悬空边，统计与锁定的源文件一致。
- **FR-006 · P0 — 先修路径方向**：关系方向遵守 `prereq → dependent`；测试不得出现把后继知识误作先修的反向推荐。
- **FR-007 · P0 — API 合约**：提供输入校验、统一错误格式和自动化测试；`400/404/409/422/500` 行为稳定。
- **FR-008 · P0 — 本地隐私配置**：数据路径、外部模型调用和日志行为有明确配置；默认绑定 localhost，不得静默调用云端。
- **FR-009 · P1 — 带来源问答**：个人知识助手返回答案和检索证据；引用必须能定位到真实 SAG source/chunk，无法定位时明确声明。
- **FR-010 · P1 — 知识点查询**：可查 K12 知识点详情、先修项、后继项及关联资料；ID、名称、学科和关系完整。
- **FR-011 · P1 — 学习记录**：保存练习作答、正确性、知识点、算法版本与时间，并提供掌握度估计。
- **FR-012 · P1 — 人工绑定资料**：内容文档可关联一个或多个 K12 知识点，记录置信度、来源与人工确认状态。
- **FR-013 · P1 — 评估工具**：提供回归评估脚本与检索基准集，一键输出 Recall@K/MRR、导入统计和 API 测试报告。
- **FR-014 · P2 — 多模态检索**：支持图片、音频、视频的向量检索；另设专项设计与数据治理验收。
- **FR-015 · P2 — 客户端与同步**：扩展 Android/Flutter、账号同步和多学习者；另行评估端侧内存、权限与同步安全。

## 2.1 用户故事

1. 作为个人用户，我可以导入 Markdown / TXT / PDF，稍后使用自然语言检索内容，而不用记住准确的文件名。
2. 作为个人用户，我希望答案附上原始资料引用，以便核实模型没有编造结论。
3. 作为开发者，我希望替换 embedding 模型时能够检测维度/版本不一致，并被明确要求重建索引，而不是生成静默错误。
4. 作为学习者，我可以查看一个知识点需要先掌握哪些知识，以及它关联的教材或练习。
5. 作为辅导者，我可以为某个知识点关联教材段落和练习题，并区分“模型建议”与“人工确认”。
6. 作为系统维护者，我可以导入新版本知识图谱，并知道新增、修改、删除、无效边和失败记录分别有多少。

---

# 3. 上游项目现状与技术决策

## 3.1 SAG

上游仓库：<https://github.com/Zleap-AI/SAG>

公开文档描述 SAG 为本地优先、单用户知识库应用，具备文档解析/切分/embedding、事件与实体抽取、检索、来源追踪、带引用问答、REST/OpenAPI、MCP，以及 `zleap-sag` Python 包。当前默认后端以 SQLite + LanceDB 为主，另有 PostgreSQL/pgvector 部署选项。SAG 的自托管 API base 为 `/api/v1`，API 文档通常可在 `/docs` 查看。

公开开发文档中可见的 `EngineConfig` / `EmbeddingConfig` 支持配置 embedding model，并可分别设置 `api_key`、`base_url`、`model`。Web 应用也提到可配置可选 Query embedding 连接。因此本项目优先验证 OpenAI-compatible embeddings 方式接入，不先 fork SAG。

**注意事项**：SAG 在写入时除 chunk embedding 外，还进行 LLM 事件/实体抽取；embedding 连接可用不代表完全离线。必须单独配置并验证 LLM 服务。SAG 上游接口和配置字段会变动，所以需锁定发布版本/commit，并在升级时执行兼容性测试。

## 3.2 EmbeddingGemma 2

官方模型文档：

- 模型卡：<https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2>
- Sentence Transformers 推理：<https://ai.google.dev/gemma/docs/embeddinggemma/inference-embeddinggemma-with-sentence-transformers>

按当前官方文档，模型总参数约 740M；可选择性关闭 vision/audio encoder，文本模式约 270M；原生输出 768 维，支持 Matryoshka 截断为 512/256/128 维；上下文长度为 8192 tokens。官方推荐 `bfloat16` 或 `float32`，明确不建议 `float16`。检索任务使用 query/document 不同的 task prefix；文档输入建议格式为 `title: {title} | text: {content}`，无标题时使用 `title: none | text: {content}`。向量截断后必须重新归一化。

## 3.3 China-K12-knowledge-taxonomy

上游仓库：<https://github.com/bewho/China-K12-knowledge-taxonomy>

公开 README 描述原始数据 `_raw/tokenmap-data.json` 包含约 10 个学科、6,657 个知识点、9,297 条关系，关系包括 7,396 条先修依赖和 1,901 条关联；知识点包含学科/册次/单元、描述和掌握标准等信息。构建脚本 `build_graph.mjs` 将原始数据转换为浏览器展示用的 `site/data/graph.json`，其中加入 subject、cluster、layout 和教材资源等派生节点。

原始依赖边约定为 `[src_idx, tgt_idx, type]`：`type=1` 时 `src` 是后学 dependent、`tgt` 是先学 prerequisite。上游构图时把它翻转为 `prerequisite -> dependent` 方向；`type=0` 为关联边。**本系统必须显式执行方向转换并以测试锁定，不得按字段名猜测方向。**

## 3.4 架构决策记录（ADR）

### ADR-001：SAG 保持独立服务

- **决定**：不把 SAG 内部数据表作为本项目自有数据表使用；通过 HTTP API 或受支持的 Python API 交互。
- **原因**：避免依赖上游私有 schema，降低升级与迁移成本。
- **影响**：学习进度、K12 图谱和文档-KP 映射存入本项目自己的 SQLite；SAG 负责检索内容和 source/chunk 证据。

### ADR-002：embedding 服务采用 OpenAI-compatible HTTP 入口

- **决定**：提供 `POST /v1/embeddings`，请求结构兼容常见 OpenAI embeddings 客户端；以 `model` alias 区分 `document` 与 `query` 任务，最终使用同一实际模型权重。
- **原因**：SAG 的公开配置支持 `base_url` / `model`；可减少侵入 SAG 的代码修改。
- **必要验证**：在锁定的 SAG 版本中证明 document embedding 和 query embedding 的调用参数可分别配置；模型 alias 不能影响维度，也不能在索引和查询端错误互换。
- **失败回退**：如果 SAG 版本无法配置不同的 query embedding connection 或 alias，增加极薄的 `SAG EmbeddingProvider` 扩展/小型 fork，让内部明确传入 `purpose=document|query`。不得把 prompt 逻辑长期藏在不可控的请求顺序或全局状态中。

### ADR-003：首版向量维度用 256 还是 768，由本地基准决定

- **初始默认**：先以 768 维做正确性基准，再对 256 维做对照实验；若 256 维在中文召回评估中不低于目标阈值且存储/延迟收益明显，再作为默认配置。
- **禁止**：同一索引混用不同维度；切换维度后不重建索引；截断后不归一化。

### ADR-004：K12 图谱以原始 tokenmap 数据为主源

- **决定**：优先从 `_raw/tokenmap-data.json` 导入学科、知识点和关系；`graph.json` 仅作为校验/展示参考，不把布局属性存为领域逻辑。
- **原因**：`graph.json` 含渲染坐标和额外派生节点，可能包含生成规则变化；原始文件更接近内容事实。
- **回退**：如果运行环境拿不到原始文件，才允许从 `graph.json` 解析 `type=kp` / `kind=pre|rel` 数据，并在导入报告标注 source_mode=derived_graph。

### ADR-005：K12 掌握度与 SAG 分离存储

- **决定**：学习者/答题/掌握度/复习计划保存在应用 SQLite；不塞进 SAG 文档实体抽取结果。
- **原因**：掌握度属于可变、可审计的学习状态，不是从文档中抽取的客观实体。

---

# 4. 系统架构与运行拓扑

## 4.1 逻辑架构

```text
┌────────────────────────────────────────────────────────────────┐
│                        UI / Client                              │
│  Personal Memory UI                 K12 Learning UI              │
└───────────────────────┬─────────────────────────┬───────────────┘
                        │                         │
                        ▼                         ▼
┌────────────────────────────────────────────────────────────────┐
│                  App API / Orchestrator (FastAPI)               │
│  auth/config | memory routes | taxonomy | learning | evaluation │
└──────┬──────────────────────┬──────────────────────┬─────────────┘
       │                      │                      │
       ▼                      ▼                      ▼
┌─────────────┐      ┌────────────────┐     ┌─────────────────────┐
│ SAG API     │      │ Embedding API  │     │ Local Generation API│
│ ingest/search│     │ Gemma 2 / ST   │     │ OpenAI-compatible    │
└─────┬───────┘      └────────────────┘     └─────────────────────┘
      │
      ▼
┌─────────────────────────┐       ┌──────────────────────────────┐
│ SAG SQLite + LanceDB     │       │ App SQLite                   │
│ documents/chunks/events  │       │ taxonomy/mastery/attempts     │
│ entities/graph/citations │       │ mappings/import runs/config  │
└─────────────────────────┘       └──────────────────────────────┘
```

## 4.2 容器/进程清单

- **`sag-api`**：上游 SAG Docker Compose API；本机默认端口 `8000`；数据卷由 SAG 配置管理；负责文档导入、检索、来源追踪和事件/实体关系。
- **`sag-web`（可选）**：上游 Web UI；本机默认端口 `3000`；用于调试与后台管理，产品首版 UI 可不依赖它。
- **`embedding-service`**：Python + FastAPI + Sentence Transformers；本机默认端口 `8081`；保存模型缓存，提供 EmbeddingGemma 2 推理与 OpenAI-compatible API。
- **`llm-service`**：选定的本地模型运行时；端口按运行时配置（例如 `11434` 或 `8082`）；负责事件/实体抽取、问答、讲解和练习生成。
- **`app-api`**：Python + FastAPI；本机默认端口 `8090`；SQLite 文件建议为 `./data/app/app.sqlite3`；负责 K12 导入/查询、学习记录和业务编排。
- **`eval-runner`**：非常驻 Python CLI；无监听端口；输出保存到 `./data/eval`；负责检索基准和端到端验收报告。

端口仅为开发默认值，应通过环境变量覆盖。容器间互相调用使用 Compose service name（如 `http://embedding-service:8081`），不可错误使用容器内的 `localhost` 指向另一服务。

## 4.3 建议技术栈

- Python 3.11（具体版本须以 SAG 当前锁定版本和 Sentence Transformers/Transformers 依赖矩阵做 CI 验证；通过 `uv.lock` 或等价锁文件锁定所有依赖）。
- FastAPI + Pydantic v2 + Uvicorn。
- Sentence Transformers + Transformers + PyTorch；首版仅启用文本 encoder。
- SQLite 3 + SQLAlchemy 2.x 或 SQLModel（二选一；建议 SQLAlchemy 2.x，DDL/迁移更清晰）。
- Alembic 管理应用数据库迁移。
- pytest + httpx + pytest-asyncio（按项目 async 方式选用）。
- Docker Compose v2 管理服务；SAG 上游 Compose 文件作为独立依赖或在本项目 compose 中覆盖，不直接复制其内部实现。

## 4.4 建议代码仓库结构

```text
personal-memory-k12/
├── README.md
├── PRD.md
├── .env.example
├── compose.yaml
├── pyproject.toml
├── uv.lock
├── services/
│   ├── embedding_service/
│   │   ├── app.py
│   │   ├── model_runtime.py
│   │   ├── schemas.py
│   │   └── tests/
│   └── app_api/
│       ├── main.py
│       ├── config.py
│       ├── db.py
│       ├── models/
│       ├── schemas/
│       ├── routers/
│       │   ├── memory.py
│       │   ├── taxonomy.py
│       │   ├── learning.py
│       │   └── admin.py
│       ├── services/
│       │   ├── sag_client.py
│       │   ├── llm_client.py
│       │   ├── taxonomy_importer.py
│       │   ├── prerequisite_path.py
│       │   ├── mastery.py
│       │   └── retrieval.py
│       ├── migrations/
│       └── tests/
├── data/
│   ├── app/
│   ├── sag/
│   ├── models/
│   ├── taxonomy-source/
│   └── eval/
├── scripts/
│   ├── import_taxonomy.py
│   ├── reindex_sag.py
│   ├── eval_retrieval.py
│   └── backup.sh
└── tests/
    ├── integration/
    └── e2e/
```

---

# 5. 模块设计与职责边界

## 5.1 Embedding Runtime

- 单例加载模型，启动时只初始化一次；禁止每次请求重新加载权重。
- 首版设置 `vision_config=None`、`audio_config=None`，仅加载文本 encoder。
- 暴露健康状态、模型版本、维度、dtype、device 和支持的 task alias。
- 批量处理输入，限制单次 `input` 项数和字符/token 规模，支持超时与最大并发。
- 输出必须是有限数值；发现 NaN/Inf 应返回服务错误并写结构化日志，不可把损坏向量交给 SAG。
- 模型 revision、维度、prompt policy 发生变化时，更新 embedding profile id，触发索引兼容性检查。

## 5.2 SAG Client

职责：

- 统一处理 SAG auth token、请求超时、HTTP 错误和重试；
- 建立/复用 source；
- 以支持的 `/documents/ingest` 或文件上传接口导入内容；
- 查询文档处理状态，Ready 之后才允许其参与检索；
- 调用 source-scoped/global search，并把 SAG 原始 source/chunk/citation id 映射到应用内部返回结构；
- 不直接读写 SAG 的 SQLite/LanceDB 文件。

重试规则：

- 对 GET/健康检查可指数退避重试；
- 对有副作用的 POST 不可盲目重放；优先使用客户端生成的 idempotency key，若上游接口不支持，则应用端记录请求指纹与任务状态，重放前检查文档是否已存在；
- 遇到 401/403 不重试，应刷新或重新登录；遇到 429/5xx 可退避重试最多 3 次。

## 5.3 K12 Taxonomy Service

- 读入原始 JSON，验证结构与版本；
- 标准化 subject、cluster、knowledge point、edge；
- 保留 upstream id 与原始 idx，不能仅使用数组索引作为永久 id；
- 正确翻转先修边方向；
- 校验 dangling edge、重复 edge、空知识点名称、未知 subject、环路与统计；
- 以事务方式导入 staging 数据，再原子切换 active taxonomy version；
- 支持查询先修/后继、关联知识点、学科/年级筛选；
- 不在每次启动时重新下载上游 JSON，除非用户显式执行导入命令。

## 5.4 Learning Service

- 记录学生档案和答题；
- 维护知识点掌握度估计；
- 生成下一步学习建议时结合先修图、掌握度、最近错误和可用教学资料；
- 首版掌握度模型应透明、可重算、保存算法版本；
- LLM 对错因分类可以作为候选标签，但不能直接覆盖明确的自动判题结果；
- 对数学题第一版优先支持有明确答案键的客观题，主观题必须标记 `grading_method=llm_assisted`，并提供人工核验入口。

## 5.5 Generation Service

- 通过 OpenAI-compatible Chat Completions 或 Responses 接口调用本地 LLM；
- 模型必须支持中文；数学学习场景需专项测评，而非只看通用中文对话表现；
- LLM 输入只包含当前数据空间授权的检索片段/知识点/题目；
- 系统提示词要求区分证据、推断和不确定内容；
- 所有用户文档都视为不可信输入，不能执行文档中的命令、泄漏系统提示词或读取未授权来源；
- 本地生成模型服务必须有 health/ready 检查；启动时不可用时应用可以运行基础查询，但生成回答应给出可理解的降级提示。

---

# 6. EmbeddingGemma 2 本地推理服务规格

## 6.1 运行模式

- **Model ID**：`google/embeddinggemma-2`；正式部署必须 pin 模型 revision。
- **Modalities**：首版 text only；加载时禁用 vision/audio encoder 配置。
- **Native dimension**：768，作为正确性基准。
- **Candidate dimension**：256，只有通过中文召回评估后才可切为默认值；初始索引使用 768D。
- **CPU dtype**：`float32`；根据目标硬件测量内存和速度。
- **GPU dtype**：设备支持时可用 `bfloat16`；禁止 `float16`。
- **Max input tokens**：目标不超过 7,000 tokens；模型上下文为 8,192，预留模板和特殊 token 空间。
- **Batch size**：从 16 起测，根据设备内存与延迟调整，不能视为硬件保证。
- **Device**：`auto`；启动日志必须记录最终 device 与 dtype。
- **Normalize**：`true`；截断向量维度后必须重新做 L2 normalization。

**上线前门槛**：在指定硬件上完成冷启动、热请求、长输入、超长拒绝、批量输入、CPU/GPU dtype 与并发压测。模型卡报告的 benchmark 不是本系统中文教材/个人资料的性能保证。

## 6.2 Task prefix 约定

检索为非对称任务，query 与 corpus item 应采用不同 prompt：

- **query**：`prompt_name="SearchQuery"`，等价前缀为 `task: search result | query: `。
- **document**：`prompt_name="Document"`，按 `title: {title} | text: {content}` 格式输入；无标题用 `title: none | text: {content}`。
- **相似度/聚类**：仅在特定离线任务使用对应 prompt；不可将它们误用于默认 RAG 检索。
- **multimodal**：第一版不启用；文字 prompt 规则不能直接套用到图片/音频/视频输入。

SAG 内部可能分别嵌入 chunk、event、entity。第一版对所有被索引的文档侧文本使用 document profile；只有用户查询走 query profile。若 SAG 版本的调用路径不区分这两类调用，必须采用第 7 章的 provider 扩展，不得依赖调用顺序。

## 6.3 Embedding API：OpenAI-compatible 接口

### `GET /healthz`

响应 `200`：

```json
{
  "status": "ok",
  "model_loaded": true,
  "model_id": "google/embeddinggemma-2",
  "revision": "<pinned-revision>",
  "native_dimension": 768,
  "default_dimension": 768,
  "supported_dimensions": [768, 256],
  "device": "cpu",
  "dtype": "float32"
}
```

如果模型没有加载完成，返回 `503`，`status="starting"` 或 `status="unavailable"`，不要谎报 ready。

### `GET /v1/models`

响应采用 OpenAI-compatible `list` 风格，至少公开以下 alias（可由配置控制）：

- `embeddinggemma2-doc-768-v1`
- `embeddinggemma2-query-768-v1`
- 对照评估时可额外提供 `embeddinggemma2-doc-256-v1` 与 `embeddinggemma2-query-256-v1` alias。

Alias 选择 profile，不表示模型权重不同。任何 alias 的真实维度需通过服务配置固定，不能由请求临时更改。

### `POST /v1/embeddings`

请求：

```json
{
  "model": "embeddinggemma2-doc-768-v1",
  "input": [
    "MQTT 是一种基于发布订阅模式的消息协议。",
    "蓝牙断线后客户端应执行受限重连。"
  ],
  "encoding_format": "float"
}
```

查询请求示例：

```json
{
  "model": "embeddinggemma2-query-768-v1",
  "input": "蓝牙断开后如何恢复连接？",
  "encoding_format": "float"
}
```

> 客户端应传入原始 query/text，任务格式由服务端统一处理。对于 `...-doc-768-v1`，服务将每条原始文本格式化为 `title: none | text: {content}` 后编码；对于 `...-query-768-v1`，服务按 `SearchQuery` prompt 编码。不得要求 SAG 先拼接模板后再由服务重复拼接。标准 OpenAI-compatible 请求暂不接受独立标题字段；未来若上游客户端确实能传标题，再扩展可选 `titles` 字段，但不能破坏基本契约。

成功响应：

```json
{
  "object": "list",
  "model": "embeddinggemma2-doc-768-v1",
  "data": [
    {"object": "embedding", "index": 0, "embedding": [0.012, -0.031]},
    {"object": "embedding", "index": 1, "embedding": [0.024, 0.017]}
  ],
  "usage": {"prompt_tokens": 24, "total_tokens": 24}
}
```

示例中的向量数组为截断示意，真实响应长度必须与请求 alias 的配置维度完全一致：`...-768-v1` 必须返回 768 个数字，`...-256-v1` 必须返回 256 个数字。

### 请求/响应规则

- `input` 支持单字符串或字符串数组；返回 `data` 总数与输入项数量一致，顺序一致。
- `model` 必填，且必须命中 allowlist。未知模型返回 `400 invalid_model`。
- 输入为空字符串、批量数量超限、单项超出长度上限时返回 `422` 或 `400`，具体错误码稳定。
- 默认 `encoding_format=float`；MVP 不实现 base64 返回。
- 输出 `embedding` 必须是有限数字数组，长度严格等于 profile dimension。
- 应用返回 `model` alias；日志记录实际模型 revision、profile、dimension、duration_ms，不记录完整用户文本。
- 服务内部对文档原文统一应用 `title: none | text: {content}` 模板，再以文档侧配置编码；对 query 原文统一应用 `SearchQuery` prompt。适配层必须把 SAG 实际发来的输入当作原始 chunk/query，若锁定版本的 SAG 会预先拼接模板，需在适配层明确选择“上游拼接”或“服务端拼接”中的一种，禁止两边同时拼接。
- 支持 `Authorization: Bearer <EMBEDDING_API_KEY>`（本地开发可由配置允许无 token，但默认关闭跨主机访问）。

### 错误格式

```json
{
  "error": {
    "code": "invalid_input",
    "message": "input must be a string or an array of strings",
    "request_id": "req_01..."
  }
}
```

建议错误码：`invalid_model`、`invalid_input`、`input_too_long`、`batch_too_large`、`model_not_ready`、`model_inference_failed`、`unauthorized`、`internal_error`。

## 6.4 模型调用伪代码/实现要求

以下为实现参考，不代表完整可运行文件；工程实现应补齐日志、异常映射、token 长度估算、密钥中间件和测试。

```python
from sentence_transformers import SentenceTransformer

MODEL_ID = "google/embeddinggemma-2"
model = SentenceTransformer(
    MODEL_ID,
    config_kwargs={"vision_config": None, "audio_config": None},
    # GPU 支持 bf16 时使用 bfloat16；CPU 通常用 float32。
    model_kwargs={"torch_dtype": "float32"},
)

def encode_documents(texts: list[str], titles: list[str] | None, dim: int):
    formatted = []
    for i, text in enumerate(texts):
        title = titles[i] if titles and titles[i] else "none"
        formatted.append(f"title: {title} | text: {text}")
    # 已手工按 title/text 规范格式化，因此这里不再传 prompt_name="Document"，避免自动添加第二个 title:none 前缀。
    return model.encode(
        formatted,
        truncate_dim=dim,
        normalize_embeddings=True,
        convert_to_numpy=True,
    )

def encode_queries(queries: list[str], dim: int):
    return model.encode(
        queries,
        prompt_name="SearchQuery",
        truncate_dim=dim,
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
```

**实现提醒**：`torch_dtype` 应根据实际设备能力配置；若调用的 Sentence Transformers/Transformers 版本接受的初始化参数不同，以锁定版本的官方 API 和 CI 测试为准。不要在 CPU 上强制 `bfloat16`，不要使用 `float16`。生产实现须确保服务内存里的模型只加载一次，并在应用关闭时正确释放资源。

## 6.5 并发、批处理和资源保护

- 初始 `max_batch_items=32`、`max_input_chars=20000`，通过配置可调；最终 token 长度以 tokenizer 计算为准。
- 每个进程只加载一个模型实例。以 asyncio semaphore 或线程池限制推理并发，避免多个请求同时大量占用 RAM/VRAM。
- 超长输入返回明确错误；不要静默截去任意尾部并声称完成了完整编码。
- 限定请求超时；模型推理超时需返回 504/503 并记录 request_id。
- CPU OOM、GPU OOM 后应让服务变为 not-ready，记录错误并防止每个请求无限重试。
- 禁止将原始 embedding 向量或个人内容写入普通 info 日志。

---

# 7. SAG Embedding 适配规格

## 7.1 目标

在不修改 SAG 核心检索算法的前提下，尽量通过其公开 embedding connection 接口调用本地 EmbeddingGemma 2。适配需要解决两件事：

1. SAG 是否能把 embedding 调用发到本地 OpenAI-compatible `/v1/embeddings`；
2. **文档侧与查询侧能否应用不同任务 prompt**，同时保证 vector dimension 和 model revision 一致。

## 7.2 推荐接入顺序

### Step A：先验证上游原生配置

- 锁定 SAG commit/tag 与 `zleap-sag` 版本；
- 检查该版本 `EmbeddingConfig` 的 `base_url` 拼接逻辑、请求体字段、返回 JSON 解析和 query embedding 的配置入口；
- 用 `POST /v1/embeddings` 的 mock server 验证 SAG 实际发送的 JSON；
- 确认模型切换策略、索引重建和查询模型配置可以落地。

### Step B：OpenAI-compatible alias 方案

建议 profile：

- 基准 Index/document profile：`embeddinggemma2-doc-768-v1`
- 基准 Query profile：`embeddinggemma2-query-768-v1`
- 若本地中文语料评估通过，再切换为对应的 256 维 alias。

二者在服务端映射到同一个 `google/embeddinggemma-2` 权重，维度与 revision 相同，但使用不同 prompt。SAG 写入端使用 doc profile，查询 embedding 连接使用 query profile。

**限制**：上游 UI/配置层是否允许不同 alias 与连接，以及其 Query embedding 连接是否仅作用于用户 query，需要在 Spike 中核对。不能仅因文档提到可选 Query embedding 就假定所有事件/实体或各类型查询已自动使用正确 profile。

### Step C：如果上游配置不能传递任务差异

按优先级依次处理：

1. 向 `zleap-sag` 的 embedding provider 增加明确的 `purpose` 参数，分别调用 `encode_document()` / `encode_query()`；
2. 若暂不能上游贡献，则维护最小 patch，并用独立测试证明与原 provider 接口兼容；
3. 仅在开发验证中可用“同一 prompt”作为临时模式做对照，不可视为最终生产配置，除非中文离线基准证明该方案达到验收目标。

禁止让适配器根据某个不稳定的字段顺序、输入长度或请求先后猜测 query/document 角色。

## 7.3 配置映射

示例配置（实际环境变量名应以锁定版本 `.env.example` 和 Settings schema 为准）：

```dotenv
# Embedding service: OpenAI-compatible base URL
EMBEDDING_BASE_URL=http://embedding-service:8081/v1
EMBEDDING_API_KEY=local-embedding-secret
EMBEDDING_DOCUMENT_MODEL=embeddinggemma2-doc-768-v1
EMBEDDING_QUERY_MODEL=embeddinggemma2-query-768-v1
EMBEDDING_DIMENSION=768

# LLM connection: must be a separate local generation service
SAG_LLM_BASE_URL=http://llm-service:11434/v1
SAG_LLM_API_KEY=local-llm-secret
SAG_LLM_MODEL=replace-with-selected-local-chat-model
```

> 变量名称仅为本项目的规范化命名示例，不应未经确认就直接复制到 SAG 的环境文件；启动时需要将本项目配置映射到上游当前支持的配置键或 UI/API 设置。必须检查实际生效的 SAG model config，而不只检查 `.env` 文本。

## 7.4 SAG 数据导入 API 合约（外部接口）

开发阶段优先采用 SAG 自托管 REST API。公开 README 当前列出：

- API base：`http://localhost:8000/api/v1`
- 登录：`POST /auth/login`
- 新建 source：`POST /sources`
- 导入文本：`POST /sources/{source_id}/documents/ingest`
- source-scoped search：`POST /sources/{source_id}/search`
- 系统检查：`GET /system/health`、`GET /system/ready`、`GET /system/capabilities`

示例：

```bash
BASE=http://localhost:8000/api/v1
TOKEN="<SAG_TOKEN>"

curl -s -X POST "$BASE/sources" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"K12 Learning Materials"}'

curl -s -X POST "$BASE/sources/<SOURCE_ID>/documents/ingest" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"异分母分数加法","text":"异分母分数相加时，先将分数通分为相同分母，再把分子相加。"}'

curl -s -X POST "$BASE/sources/<SOURCE_ID>/search" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"为什么异分母分数要先通分？","strategy":"multi","top_k":5}'
```

SAG 的导入为后台处理任务；提交成功不代表马上可搜索。客户端必须轮询相应 document/job 状态（按锁定版本 OpenAPI 的实际字段），直到 Ready 或失败。禁止把上述 API 示例中的占位符当作真实 ID。

## 7.5 索引配置兼容性与重建规则

创建 `embedding_profile`（应用侧表）记录：

- `profile_id`、`provider`、model id、model revision、dimension、dtype、normalize、document prompt、query prompt、tokenizer revision、created_at；
- `compatibility_hash`：由 model revision + dimension + pooling + normalization + document prompt policy 组成；
- `status`：active/deprecated/testing。

索引与检索必须共享兼容性 hash 的约束。若 `model revision`、`dimension`、pooling、normalize 或 document/query prompt 发生不兼容变更，应创建新 profile 并重建相应 SAG source 的索引。重建采用新 source 暂存、抽样评估、切换 active source、最后删除旧 source 的蓝绿流程；上游不支持完整事务时，应用侧保存 migration state，失败可恢复。

---

# 8. K12 图谱导入与映射

## 8.1 数据源选择

首选原始数据：

```text
China-K12-knowledge-taxonomy/_raw/tokenmap-data.json
```

上游 README 描述的数据结构关键点：

- `subjects` 是学科目录，知识点记录的 subject 是索引/引用；
- `nodes` 的每一项在当前上游构建脚本中按数组读取，已观察字段位置为：`t[0]=id`、`t[1]=name`、`t[2]=subject index`、`t[6]=volume`、`t[7]=unit`、`t[8]=description`、`t[9]=standard`；
- `edges` 每项形如 `[src_idx, tgt_idx, type]`，`type=1` 为先修依赖，`type=0` 为关联；
- 导入器不得只依赖这些数组位置而不做 schema validation；上游格式变化时必须失败并给出清楚错误，不能静默错位导入。

如果只使用派生 `site/data/graph.json`，节点 `type=kp` 是知识点，`id` 形如 `kp|<upstream-id>`，`links` 使用 `{s, t, kind}`，`kind=pre` 应已表示 prerequisite → dependent，`kind=rel` 表示相关联。`graph.json` 还含 subject/cluster/textbook 等附加节点，需按 `type` 过滤。

## 8.2 导入流程

```text
下载/指定数据文件
   ↓
计算 SHA-256 + 记录仓库 commit
   ↓
JSON parse + Schema validation
   ↓
规范化 subjects / clusters / knowledge_points / edges
   ↓
源数据统计与异常检查
   ↓
导入 staging 版本（事务）
   ↓
运行关系方向/重复/悬空边/环路检查
   ↓
写入 import report
   ↓
将新版本标记 active
   ↓
可选：为 KP 生成 embedding，关联 SAG 文档
```

失败时回滚新版本，不影响当前 active 版本。若源数据发生删除，不直接物理删除被学习记录引用的 KP；旧版本作为 inactive 保留，或者以 status=deprecated 进入兼容层。

## 8.3 主键与映射规则

- `source_node_id`：上游知识点原始 ID，按字符串保存；导入后始终保留。
- `canonical_knowledge_point_id`：跨版本稳定的公开业务 ID，推荐 `k12:<dataset-id>:<source-node-id>`；不以数据库自增 ID 作为公开 API 主键。
- `knowledge_points.id`：某个 taxonomy version 内的快照 ID，推荐 `kpv:<taxonomy-version-id>:<source-node-id>`。同一知识点在新版本会形成新的快照行，但映射到相同 canonical ID。
- `source_index`：原始 `nodes` 数组中的索引，只用于 edge 数组解析，不作为长期业务 ID。
- `subject_code`、`subject_name`：保留上游 code/name；名称映射应在导入映射文件中显式维护，不靠自由文本模糊匹配。
- `volume`、`unit`：保留原始册次/单元，不在第一版过度推断年级。
- `grade_level`：依照上游册次映射规则产生的 1-12 估算级别；同时保存 `grade_label_raw` 和 `grade_mapping_version`。如果无法准确推断可存 NULL，不要把默认值 7 当真实学段。
- `description`、`mastery_standard`：原样保留，清洗只用于搜索副本；不得覆盖原始字段。

## 8.4 边方向映射规则（高优先级）

对于原始边 `edge=[src_idx, tgt_idx, 1]`：

- 上游定义：`src_idx = dependent（后学知识点）`；`tgt_idx = prerequisite（先学知识点）`。
- 本系统规范：`source_kp_id = prerequisite`，`target_kp_id = dependent`，`relation_type = prerequisite`。
- SQL 查询“某知识点的直接先修项”取 `target_kp_id = 当前 KP`、`relation_type=prerequisite`，返回 `source_kp_id`。
- SQL 查询“某知识点的后继项”取 `source_kp_id = 当前 KP`、`relation_type=prerequisite`，返回 `target_kp_id`。
- 对原始边 `type=0`：按原始双方存储为 `related`，不加入学习先后拓扑排序。

导入测试必须构造一个两节点模拟样本，断言方向转换正确；不可只用 6,657 条真实数据肉眼验证。

## 8.5 导入校验与输出报告

报告字段：

- `run_id`、开始/结束时间、状态；
- source repository、commit SHA、source file path、SHA-256、schema version；
- source node count、imported KP count、subject count、edge count by type；
- inserted/updated/unchanged/deprecated counts；
- invalid node count、unknown subject count、dangling edge count、duplicate edge count、cycle count；
- 数据版本 ID、日志文件位置、异常摘要。

基于当前公开数据的预期参考值是 10 门学科、约 6,657 个知识点、约 9,297 条关系（先修 7,396、关联 1,901）。这些是上游 README 的当前统计，不应硬编码为永远不变的常量。实际导入版本应以所锁定 source commit 中的真实计数为准；若偏差超过阈值，报告必须阻断 active 切换并要求人工确认。

建议阻断条件：

- JSON/schema parse 失败；
- 学科引用越界；
- 超过 0.1% 的关系为 dangling（目标应为 0）；
- prerequisite 类型未知；
- 相同业务关系重复率大于 0.1%；
- 先修图谱发现环路（如真实数据存在，则生成清单并阻断路径推荐功能，除非有批准的循环处理策略）。

## 8.6 将知识点关联到 SAG 文档

知识图谱的 node 是结构化数据；SAG 的 chunk 是内容检索数据。本项目通过映射表连接两者：

- 教材/笔记作为文档导入 SAG，并获得 `sag_source_id`、`sag_document_id`（字段以锁定版本 API 响应为准）；
- `document_knowledge_points` 保存应用侧 document_ref、知识点 ID、关联方法、置信度、是否人工确认和证据定位；
- 模型可建议关联，但 `link_status=proposed`；用户确认后才为 `confirmed`；
- 以知识点名称、描述、掌握标准作为检索文本可用于 KP search，但必须与教材段落区分类型。不能将模型自动总结的知识点说明伪装成教材原文。

自动关联策略 v1：

1. 先按 subject、grade、volume/unit 做硬过滤；
2. 对候选教材 chunk 与 `name + description + mastery_standard` 做语义检索；
3. 返回 Top 5 候选并附来源片段；
4. 低置信度默认只提出建议，不自动写入 confirmed mapping；
5. 记录算法版本、embedding profile 和关联证据，支持重新运行与撤销。

---

# 9. 数据库设计与 SQL DDL

## 9.1 数据库边界

本项目维护自己的 `app.sqlite3`，与 SAG 的数据库目录物理隔离：

- SAG 内部：只通过 SAG API / 官方 SDK 访问；
- App SQLite：存 K12 taxonomy、导入批次、学习者、答题、掌握度、文档-KP 映射、模型 profile 等；
- 原始知识图谱 JSON、原始个人文件与模型权重放在文件系统持久化目录，并记录 SHA/path/来源。

SQLite 开启 `foreign_keys=ON`，支持单机单用户。推荐设置 WAL mode 与合理 busy timeout；备份时使用 SQLite backup API 或在一致性安全的时点复制数据库，不能在写事务中随意复制 WAL 文件。

## 9.2 基础 DDL

以下 DDL 以 SQLite 3 为目标；正式项目应放入 Alembic migration。字段可以随着实际源 JSON 做小幅扩展，但不可省略 source provenance 和导入版本管理。

```sql
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS taxonomy_versions (
  id TEXT PRIMARY KEY,
  dataset_name TEXT NOT NULL,
  source_repo TEXT NOT NULL,
  source_commit TEXT,
  source_path TEXT NOT NULL,
  source_sha256 TEXT NOT NULL,
  schema_version TEXT NOT NULL,
  imported_at TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('staging','active','inactive','failed')),
  source_mode TEXT NOT NULL CHECK(source_mode IN ('raw_tokenmap','derived_graph')),
  stats_json TEXT NOT NULL DEFAULT '{}',
  CHECK(length(source_sha256) = 64)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_taxonomy_active
ON taxonomy_versions(dataset_name) WHERE status = 'active';

CREATE TABLE IF NOT EXISTS taxonomy_import_runs (
  id TEXT PRIMARY KEY,
  taxonomy_version_id TEXT REFERENCES taxonomy_versions(id),
  status TEXT NOT NULL CHECK(status IN ('running','succeeded','failed','rejected')),
  started_at TEXT NOT NULL,
  finished_at TEXT,
  report_json TEXT NOT NULL DEFAULT '{}',
  error_summary TEXT
);

CREATE TABLE IF NOT EXISTS subjects (
  id TEXT PRIMARY KEY,
  taxonomy_version_id TEXT NOT NULL REFERENCES taxonomy_versions(id),
  source_code TEXT,
  name TEXT NOT NULL,
  source_index INTEGER,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  UNIQUE(taxonomy_version_id, name),
  UNIQUE(taxonomy_version_id, source_code)
);

CREATE TABLE IF NOT EXISTS canonical_knowledge_points (
  canonical_id TEXT PRIMARY KEY,
  dataset_name TEXT NOT NULL,
  source_node_id TEXT NOT NULL,
  first_seen_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  UNIQUE(dataset_name, source_node_id)
);

CREATE TABLE IF NOT EXISTS knowledge_points (
  id TEXT PRIMARY KEY,
  taxonomy_version_id TEXT NOT NULL REFERENCES taxonomy_versions(id),
  canonical_id TEXT NOT NULL REFERENCES canonical_knowledge_points(canonical_id),
  source_node_id TEXT NOT NULL,
  source_index INTEGER NOT NULL,
  subject_id TEXT NOT NULL REFERENCES subjects(id),
  name TEXT NOT NULL,
  volume TEXT,
  unit TEXT,
  grade_level INTEGER CHECK(grade_level BETWEEN 1 AND 13 OR grade_level IS NULL),
  grade_label_raw TEXT,
  description TEXT,
  mastery_standard TEXT,
  grade_mapping_version TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','deprecated')),
  raw_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(taxonomy_version_id, source_node_id),
  UNIQUE(taxonomy_version_id, source_index)
);

CREATE INDEX IF NOT EXISTS ix_kp_subject_grade
ON knowledge_points(subject_id, grade_level, status);
CREATE INDEX IF NOT EXISTS ix_kp_name
ON knowledge_points(name);

CREATE TABLE IF NOT EXISTS knowledge_edges (
  id TEXT PRIMARY KEY,
  taxonomy_version_id TEXT NOT NULL REFERENCES taxonomy_versions(id),
  source_kp_id TEXT NOT NULL REFERENCES knowledge_points(id),
  target_kp_id TEXT NOT NULL REFERENCES knowledge_points(id),
  relation_type TEXT NOT NULL CHECK(relation_type IN ('prerequisite','related')),
  source_edge_type INTEGER NOT NULL,
  raw_src_index INTEGER,
  raw_tgt_index INTEGER,
  created_at TEXT NOT NULL,
  CHECK(source_kp_id <> target_kp_id),
  UNIQUE(taxonomy_version_id, source_kp_id, target_kp_id, relation_type)
);

CREATE INDEX IF NOT EXISTS ix_edges_outbound
ON knowledge_edges(taxonomy_version_id, source_kp_id, relation_type);
CREATE INDEX IF NOT EXISTS ix_edges_inbound
ON knowledge_edges(taxonomy_version_id, target_kp_id, relation_type);

CREATE TABLE IF NOT EXISTS embedding_profiles (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  model_id TEXT NOT NULL,
  model_revision TEXT NOT NULL,
  dimension INTEGER NOT NULL CHECK(dimension IN (128,256,512,768)),
  dtype TEXT NOT NULL CHECK(dtype IN ('float32','bfloat16')),
  normalized INTEGER NOT NULL CHECK(normalized IN (0,1)),
  document_prompt TEXT NOT NULL,
  query_prompt TEXT NOT NULL,
  tokenizer_revision TEXT,
  compatibility_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('testing','active','deprecated')),
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS learning_profiles (
  id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  grade_level INTEGER CHECK(grade_level BETWEEN 1 AND 12 OR grade_level IS NULL),
  curriculum_region TEXT,
  preferences_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS student_mastery (
  learner_id TEXT NOT NULL REFERENCES learning_profiles(id) ON DELETE CASCADE,
  canonical_knowledge_point_id TEXT NOT NULL REFERENCES canonical_knowledge_points(canonical_id),
  mastery_score REAL NOT NULL CHECK(mastery_score >= 0 AND mastery_score <= 1),
  confidence REAL NOT NULL CHECK(confidence >= 0 AND confidence <= 1),
  status TEXT NOT NULL CHECK(status IN ('unknown','learning','needs_review','provisional_mastered','mastered')),
  evidence_count INTEGER NOT NULL DEFAULT 0 CHECK(evidence_count >= 0),
  last_assessed_at TEXT,
  algorithm_version TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(learner_id, canonical_knowledge_point_id)
);

CREATE TABLE IF NOT EXISTS questions (
  id TEXT PRIMARY KEY,
  canonical_knowledge_point_id TEXT NOT NULL REFERENCES canonical_knowledge_points(canonical_id),
  question_type TEXT NOT NULL CHECK(question_type IN ('single_choice','multiple_choice','fill_blank','numeric','short_answer')),
  prompt TEXT NOT NULL,
  options_json TEXT NOT NULL DEFAULT '[]',
  answer_key_json TEXT,
  explanation TEXT,
  difficulty INTEGER CHECK(difficulty BETWEEN 1 AND 5 OR difficulty IS NULL),
  source_document_ref TEXT,
  grading_method TEXT NOT NULL CHECK(grading_method IN ('exact','rule_based','llm_assisted','manual')),
  provenance_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS learning_attempts (
  id TEXT PRIMARY KEY,
  learner_id TEXT NOT NULL REFERENCES learning_profiles(id) ON DELETE CASCADE,
  question_id TEXT REFERENCES questions(id),
  canonical_knowledge_point_id TEXT NOT NULL REFERENCES canonical_knowledge_points(canonical_id),
  user_answer_json TEXT NOT NULL,
  is_correct INTEGER CHECK(is_correct IN (0,1) OR is_correct IS NULL),
  error_type TEXT,
  hint_count INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER CHECK(duration_ms >= 0 OR duration_ms IS NULL),
  grading_method TEXT NOT NULL,
  feedback TEXT,
  source_session_id TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_attempts_learner_kp_time
ON learning_attempts(learner_id, canonical_knowledge_point_id, created_at);

CREATE TABLE IF NOT EXISTS document_knowledge_points (
  id TEXT PRIMARY KEY,
  taxonomy_version_id TEXT NOT NULL REFERENCES taxonomy_versions(id),
  knowledge_point_version_id TEXT NOT NULL REFERENCES knowledge_points(id),
  sag_source_id TEXT NOT NULL,
  sag_document_id TEXT,
  source_chunk_ref TEXT,
  link_method TEXT NOT NULL CHECK(link_method IN ('manual','rule_based','embedding','llm_suggested')),
  confidence REAL CHECK(confidence >= 0 AND confidence <= 1 OR confidence IS NULL),
  status TEXT NOT NULL CHECK(status IN ('proposed','confirmed','rejected')),
  evidence_text TEXT,
  created_at TEXT NOT NULL,
  reviewed_at TEXT,
  UNIQUE(taxonomy_version_id, knowledge_point_version_id, sag_source_id, source_chunk_ref)
);

CREATE INDEX IF NOT EXISTS ix_doc_kp_kp
ON document_knowledge_points(knowledge_point_version_id, status);

CREATE TABLE IF NOT EXISTS api_idempotency (
  key TEXT PRIMARY KEY,
  route TEXT NOT NULL,
  request_sha256 TEXT NOT NULL,
  response_json TEXT,
  state TEXT NOT NULL CHECK(state IN ('processing','succeeded','failed')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

## 9.3 DDL 说明与后续扩展

- `taxonomy_versions` 的 active 唯一索引在正式实现时应与切换事务配合，导入时先把旧 active 改为 inactive，再把新版本改为 active；若失败必须 rollback。
- 本设计默认完整保留多个 taxonomy version 的节点快照。`canonical_knowledge_points` 提供跨版本稳定 ID；`knowledge_points.id` 与 `knowledge_edges` 则绑定到具体版本快照。所有读取知识点详情/关系的 API 必须先解析 active taxonomy version，避免把不同版本的边混在一起。
- `canonical_id` 的生成必须包含 dataset namespace，不能只使用 source node ID，以避免未来合并多个课程图谱时发生主键冲突。
- `document_knowledge_points.sag_*` 为字符串，不在 app 数据库直接建立跨库外键；上游删除/重建 source 时须提供清理/重建任务。
- `student_mastery` 要保存 `algorithm_version`，保证之后可以重新计算并解释掌握度变化。其 `canonical_knowledge_point_id` 对应跨版本稳定 ID，关系查询仍需映射到 active `knowledge_points.id`。
- `document_knowledge_points` 的 `knowledge_point_version_id` 指向某个明确 taxonomy snapshot；新版本激活后，旧 mapping 不会被假设自动迁移，需通过 canonical ID 对齐并重新验证。
- `questions.answer_key_json` 只存答案键，不要把答案直接送给学生端请求；服务端判题。
- 生产/多用户版需要增加 `tenant_id`、访问控制、审计日志和加密方案；MVP 不能在未隔离数据的情况下开放到公网。

## 9.4 掌握度 v1 算法（透明启发式）

第一版不用复杂的 Bayesian Knowledge Tracing。对一个知识点，采用最近最多 20 次有效作答，按时间衰减与提示次数做加权：

- 答对权重 +1.0；答错权重 0.0；若使用提示，根据提示次数将该次权重乘以 `max(0.4, 1 - 0.15 * hint_count)`；
- 最近 30 天内作答按指数时间衰减 `w_time = exp(-days_since / 30)`；
- `mastery_score = (weighted_correct + alpha) / (weighted_total + alpha + beta)`，初始 `alpha=1, beta=1`；
- `confidence = min(1, evidence_count / 10)`；少于 3 次有效作答时 status 最多为 `learning` 或 `needs_review`，不可置为 `mastered`；
- `mastered` 需要 score ≥ 0.85、confidence ≥ 0.7，且至少跨两个 session 有正确证据；阈值属于 MVP 产品策略，应通过试用数据调整。

所有这些值只作为系统推荐依据，不可宣称为教育心理学诊断。更换公式需更新算法版本并可重算。

---

# 10. API 契约

## 10.1 通用规则

- 应用 API 前缀：`/api/v1`。
- 内容类型：`application/json; charset=utf-8`。
- 时间：ISO-8601 UTC 字符串；UI 按本地时区显示。
- 主键：UUIDv7/ULID/随机 UUID 或明确的字符串 ID；不得暴露 SQLite rowid。
- 分页：使用 `limit`（默认 20，最大 100）和 `cursor`；不可一次返回整个 K12 图谱。
- API 认证：本地 MVP 可使用应用自己的 API token；服务默认监听 `127.0.0.1`。若容器网络需对其他容器暴露，通过内部网络访问并使用内部 secret；不得直接映射到公网。
- 幂等：写 API 接受可选 `Idempotency-Key`。对于导入、作答、文档绑定等有副作用操作，服务端检查 request hash，冲突返回 409。
- 错误统一格式：

```json
{
  "error": {
    "code": "knowledge_point_not_found",
    "message": "Knowledge point does not exist in the active taxonomy version.",
    "request_id": "req_01J...",
    "details": {}
  }
}
```

常见状态码：`400` 请求语义错误；`401` 未认证；`403` 无权限；`404` 资源不存在；`409` 幂等键/版本冲突；`413` 内容过大；`422` schema 校验失败；`429` 限流；`502` 上游 SAG/LLM 响应错误；`503` 模型未就绪；`504` 上游超时。

## 10.2 Health / capabilities

### `GET /api/v1/health`

响应：

```json
{
  "status": "ok",
  "services": {
    "app_db": "ok",
    "sag_api": "ok",
    "embedding_service": "ok",
    "llm_service": "ok"
  },
  "active_taxonomy_version": "k12-2026-10-09-a1b2c3",
  "embedding_profile": "embeddinggemma2-doc-768-v1"
}
```

健康检查不能包含 API key、secret、绝对路径或完整 prompt。依赖服务不可用时返回 503，并在 `services` 明确指出故障项。

### `GET /api/v1/capabilities`

返回支持的文件类型、活动 embedding profile、维度、是否启用 LLM、本地/外部服务标识、K12 taxonomy 版本与功能开关。

## 10.3 Personal Memory API

### `POST /api/v1/memory/sources`

用途：创建个人知识库在应用侧的逻辑 source，并可选创建映射到 SAG 的 source。

请求：

```json
{
  "name": "Android 技术资料",
  "description": "BLE、Kotlin 和性能优化资料",
  "privacy_scope": "personal"
}
```

响应：

```json
{
  "id": "memsrc_01J...",
  "name": "Android 技术资料",
  "sag_source_id": "<SAG_SOURCE_ID>",
  "status": "ready"
}
```

### `POST /api/v1/memory/documents`

第一版支持两种导入方式：

1. `multipart/form-data` 文件上传；应用把文件转交给 SAG 支持的文件导入 API，并记录摘要；
2. JSON 文本导入：

```json
{
  "source_id": "memsrc_01J...",
  "title": "BLE 重连策略",
  "text": "应用应限制重连次数，并在连接状态变更后更新连接状态机。",
  "external_ref": "notes/ble-reconnect.md",
  "metadata": {"category":"android","tags":["BLE","Bluetooth"]}
}
```

响应 `202 Accepted`：

```json
{
  "document_ref": "memdoc_01J...",
  "sag_document_id": "<SAG_DOCUMENT_ID>",
  "sag_source_id": "<SAG_SOURCE_ID>",
  "status": "queued"
}
```

导入后状态至少包括 `queued|processing|ready|failed|needs_ocr`。具体映射 SAG 状态字段时以运行版本 OpenAPI 为准。

### `GET /api/v1/memory/documents/{document_ref}`

返回状态、导入时间、原始文件哈希、SAG source/document ID、处理阶段和可展示的失败原因。不得返回模型 secret 或内部文件绝对路径。

### `POST /api/v1/memory/search`

请求：

```json
{
  "query": "我之前保存过哪些 Android BLE 断线重连方案？",
  "source_ids": ["memsrc_01J..."],
  "top_k": 5,
  "strategy": "multi",
  "include_evidence": true
}
```

响应：

```json
{
  "query": "我之前保存过哪些 Android BLE 断线重连方案？",
  "results": [
    {
      "rank": 1,
      "title": "BLE 重连策略",
      "snippet": "应用应限制重连次数……",
      "score": 0.82,
      "source": {"source_id":"<SAG_SOURCE_ID>","document_id":"<SAG_DOCUMENT_ID>","chunk_id":"<CHUNK_ID>"},
      "locator": {"page": null,"heading":"连接状态机"},
      "citation_url": null
    }
  ],
  "retrieval": {"strategy":"multi","embedding_profile":"embeddinggemma2-query-768-v1","latency_ms":125}
}
```

`score` 只有在 SAG 返回并且定义清楚时才填值；如果上游分数不是可比概率，不得对外称其为“置信度百分比”。

### `POST /api/v1/memory/ask`

请求：

```json
{
  "question": "为什么我当时认为 MQTT 适合游戏事件同步？",
  "source_ids": ["memsrc_01J..."],
  "conversation_id": null,
  "top_k": 6,
  "answer_style": "evidence_first"
}
```

响应非流式版本：

```json
{
  "conversation_id": "conv_01J...",
  "answer": "根据你保存的设计资料，MQTT 被考虑用于事件发布与订阅……",
  "citations": [
    {"index":1,"source_id":"<SAG_SOURCE_ID>","document_id":"<SAG_DOCUMENT_ID>","chunk_id":"<CHUNK_ID>","quote":"……","title":"UNO Connect Protocol Specification"}
  ],
  "evidence_status": "grounded",
  "warnings": []
}
```

`evidence_status` 允许值：`grounded|partial|insufficient_evidence`。证据不足时需明确表达限制，不可伪造 citation。SAG 自身的 cited chat 可以作为简化实现，应用层负责来源 scope 与返回 schema 统一。

## 10.4 K12 Taxonomy API

### `POST /api/v1/admin/taxonomy/import`

仅管理员/本地操作者可用。请求：

```json
{
  "source_repo": "https://github.com/bewho/China-K12-knowledge-taxonomy",
  "source_commit": "<required-commit-sha>",
  "source_path": "_raw/tokenmap-data.json",
  "source_file": "./data/taxonomy-source/tokenmap-data.json",
  "activate_on_success": true,
  "allow_derived_graph_fallback": false
}
```

响应 `202 Accepted`：

```json
{
  "run_id": "taxrun_01J...",
  "status": "running"
}
```

### `GET /api/v1/admin/taxonomy/import-runs/{run_id}`

返回统计报告、阻断条件、warning 和失败原因。导入失败不得影响 active 版本。

### `GET /api/v1/taxonomy`

查询参数：`subject`、`grade_min`、`grade_max`、`q`、`limit`、`cursor`。

响应：包含 active taxonomy version、知识点总数、subject 列表、查询结果及 next_cursor。

### `GET /api/v1/taxonomy/knowledge-points/{knowledge_point_id}`

响应：

```json
{
  "id": "k12:example:12345",
  "source_node_id": "12345",
  "name": "异分母分数加法",
  "subject": {"id":"math","name":"数学"},
  "grade_level": 5,
  "volume": "五年级下册",
  "unit": "分数加法和减法",
  "description": "……",
  "mastery_standard": "……",
  "taxonomy_version": "k12-2026-10-09-a1b2c3"
}
```

### `GET /api/v1/taxonomy/knowledge-points/{id}/prerequisites`

查询参数：`depth=1` 默认直接先修；最大 5。响应按拓扑路径返回 `knowledge_points[]`、`edges[]`。`depth>1` 使用递归查询或 Python BFS，并加入 `visited` 防止循环。

### `GET /api/v1/taxonomy/knowledge-points/{id}/successors`

与 prerequisites 相反，沿 `prerequisite → dependent` 方向返回后继知识点。

### `GET /api/v1/taxonomy/knowledge-points/{id}/related`

只返回 `relation_type=related`，不混入先修路径。

## 10.5 K12 学习 API

### `POST /api/v1/learners`

请求：

```json
{
  "display_name": "学习者 A",
  "grade_level": 5,
  "curriculum_region": "mainland-cn"
}
```

避免收集非必要的姓名、学校、生日等未成年人个人信息。展示名可使用别名。

### `GET /api/v1/learners/{learner_id}/mastery`

查询参数：`subject`、`grade_level`、`status`、`limit`、`cursor`。返回掌握分数、置信度、有效答题数、最后测评时间与算法版本。

### `POST /api/v1/learning/attempts`

请求：

```json
{
  "learner_id": "learner_01J...",
  "question_id": "question_01J...",
  "knowledge_point_id": "k12:example:12345",
  "user_answer": {"value":"5/6"},
  "hint_count": 0,
  "duration_ms": 42000,
  "session_id": "session_01J..."
}
```

响应：

```json
{
  "attempt_id": "attempt_01J...",
  "is_correct": true,
  "feedback": "先通分得到 3/6 + 2/6，再把分子相加。",
  "mastery": {
    "score": 0.67,
    "confidence": 0.3,
    "status": "learning",
    "algorithm_version": "heuristic-v1"
  }
}
```

如果题目没有可信的 answer key，则不允许返回 `is_correct=true/false` 作为事实；应返回 null 并标记 `llm_assisted` 或 `manual`。

### `POST /api/v1/learning/recommendations`

请求：

```json
{
  "learner_id": "learner_01J...",
  "target_knowledge_point_id": "k12:example:67890",
  "max_items": 5
}
```

响应返回建议学习项、推荐原因、先修依赖证据、当前掌握度、可用相关资料和 `recommendation_version`。推荐规则：

1. 获取目标节点全部先修项（限制深度）；
2. 过滤出掌握度低或缺少证据的节点；
3. 按依赖拓扑排序，先推最基础的可学习节点；
4. 只在有可用教材/例题的情况下推荐具体教学资源；
5. 每项给出可解释原因（例如“它是目标知识点的直接先修项，当前有效答题仅 1 次”）。

### `POST /api/v1/learning/tutor`

请求：

```json
{
  "learner_id": "learner_01J...",
  "knowledge_point_id": "k12:example:12345",
  "question": "为什么这里需要先通分？",
  "mode": "guided",
  "include_prerequisites": true,
  "top_k": 5
}
```

响应：

```json
{
  "answer": "可以把 1/2 想成 3/6……",
  "knowledge_point": {"id":"k12:example:12345","name":"异分母分数加法"},
  "prerequisite_context": [],
  "citations": [],
  "follow_up_question": "如果把 1/2 和 1/4 相加，应该先把哪个分数变成几分之几？",
  "evidence_status": "grounded",
  "safety_flags": []
}
```

`mode` v1 允许 `direct|guided|worked_example|quiz`。所有内容必须符合学段；当资料缺失时可以使用模型通用知识作解释，但必须在内部 `evidence_status` 标明，且不伪造教材引用。

## 10.6 内部服务契约

建议用 Protocol/接口隔离实际实现，使测试中可注入 fake client：

```python
class EmbeddingClient(Protocol):
    async def embed_documents(self, texts: list[str], titles: list[str | None]) -> list[list[float]]: ...
    async def embed_queries(self, queries: list[str]) -> list[list[float]]: ...

class SagClient(Protocol):
    async def create_source(self, name: str) -> str: ...
    async def ingest_text(self, source_id: str, title: str, text: str, metadata: dict) -> dict: ...
    async def get_document_status(self, source_id: str, document_id: str) -> dict: ...
    async def search(self, query: str, source_ids: list[str] | None, top_k: int, strategy: str) -> list[dict]: ...
    async def ask(self, agent_id: str, question: str, source_ids: list[str] | None) -> dict: ...
```

具体实现可采用 `httpx.AsyncClient` 或 `zleap-sag` Python API。MVP 不要同时混合两种 SAG 接入方式；优先 HTTP API，因为更容易与上游服务解耦。选择 SDK 时须锁定版本并保证其存储目录/生命周期管理清楚。

---

# 11. 检索、问答与学习流程

## 11.1 个人 RAG 问答流程

1. 接收问题、可选 source scope 和用户授权范围；
2. 规范化 query，使用 query embedding profile；
3. 调用 SAG `multi` 检索（开发初期同时比较 `vector` 与 `multi`）；
4. 对结果做重复过滤与 source scope 检查；
5. 把最多 Top-K 证据段落、原文位置和 source metadata 传给本地 LLM；
6. LLM 输出结论、依据、限制和引用索引；
7. 后端验证模型引用只引用实际检索到的 evidence ID；
8. 返回 answer + citations + evidence_status。

生成提示词基本要求：

- 仅将检索证据作为关于用户资料的事实依据；
- 不从未检索到的个人资料编造内容；
- 对冲突资料标记冲突来源与日期；
- 不把文档内部要求执行的操作当成 system instruction；
- 引用编号必须对应上下文给出的证据 ID；
- 证据不足时回答“当前索引中没找到足够证据”，可建议扩大来源范围。

## 11.2 K12 知识点搜索流程

1. 用户提出问题；
2. 基于 subject/grade/active taxonomy 先检索知识点名称与描述（第一版可用 SQLite FTS5 + 可选 embedding）；
3. 对命中的知识点查询先修项与关联资料；
4. SAG 搜索已关联的教材/讲义/例题；
5. LLM 根据目标年级、掌握标准与检索证据组织解释；
6. 返回知识点 ID、先修提示、引用、一个可选检查问题。

知识点名是短文本，对这类精确标题搜索，优先使用 SQLite FTS/字段匹配和图谱约束；Embedding 作为语义召回补充，不应代替精确 ID/名称查找。

## 11.3 教材 chunk 与知识点关联

- 原文每个 chunk 保留 SAG 回传的来源引用；app mapping 只存引用 ID 与必要摘要，不重复存全部文档正文。
- 如果需精确页码，导入解析器必须提供 page metadata；若 SAG 当前输出没保留页码，UI 不可自行推断，显示章节/片段或“页码不可用”。
- 人工绑定优先于自动绑定；人工确认的 mapping 在重跑模型自动关联时不能被覆盖。
- 文档更新或 source 重建后，相关 `document_knowledge_points` 状态转为 stale，需重新定位，不允许把旧 chunk ID 当作有效引用。

## 11.4 学习推荐规则 v1

输入：learner、目标知识点、active taxonomy、mastery、可用教材/题目。

规则：

1. 先找目标节点的先修闭包，深度上限 5，节点上限 100；超过上限需截断并在结果返回 warning。
2. 根据先修图做拓扑排序；关联边不参与排序。
3. 对每个前置知识点计算 gap = `max(0, target_threshold - mastery_score)`，若无作答记录则视为 unknown 而非 0 分确定不懂。
4. 推荐优先级可定义为 `priority = dependency_depth_weight * gap * confidence_adjustment * resource_availability`。所有系数配置化，返回推荐解释。
5. 没有教材/例题时只推荐知识点与官方/已导入资料搜索结果，不自动生成“来自教材”的内容。
6. 遇到 prerequisite cycle，停止自动排序，返回 `taxonomy_cycle_detected` 诊断，不产生可能不终止的学习链。

---

# 12. 配置、部署、隐私与安全

## 12.1 `.env.example`

```dotenv
APP_ENV=development
APP_HOST=127.0.0.1
APP_PORT=8090
APP_DATABASE_URL=sqlite:///./data/app/app.sqlite3
APP_API_TOKEN=change-me-long-random-token
APP_LOG_LEVEL=INFO

SAG_BASE_URL=http://sag-api:8000/api/v1
SAG_USERNAME=Local User
SAG_TOKEN=
SAG_SOURCE_MEMORY_DEFAULT=
SAG_SOURCE_K12_DEFAULT=
SAG_REQUEST_TIMEOUT_SECONDS=30

EMBEDDING_BASE_URL=http://embedding-service:8081/v1
EMBEDDING_API_KEY=change-me-embedding-key
EMBEDDING_MODEL_ID=google/embeddinggemma-2
EMBEDDING_MODEL_REVISION=replace-with-pinned-hf-commit
EMBEDDING_DIMENSION=768
EMBEDDING_DEVICE=auto
EMBEDDING_DTYPE=auto
EMBEDDING_MAX_BATCH_ITEMS=32
EMBEDDING_MAX_INPUT_CHARS=20000
EMBEDDING_ALLOW_REMOTE=false

LLM_BASE_URL=http://llm-service:11434/v1
LLM_API_KEY=change-me-llm-key
LLM_MODEL=replace-with-selected-local-chat-model
LLM_TIMEOUT_SECONDS=90
LLM_ALLOW_REMOTE=false

TAXONOMY_SOURCE_REPO=https://github.com/bewho/China-K12-knowledge-taxonomy
TAXONOMY_SOURCE_COMMIT=replace-with-pinned-git-commit
TAXONOMY_SOURCE_FILE=./data/taxonomy-source/tokenmap-data.json
TAXONOMY_ACTIVE_VERSION=
TAXONOMY_ALLOW_DERIVED_GRAPH_FALLBACK=false

RETRIEVAL_DEFAULT_STRATEGY=multi
RETRIEVAL_DEFAULT_TOP_K=5
RETRIEVAL_MAX_TOP_K=20
LOG_REDACT_USER_CONTENT=true
```

**不得把真实 token/key 提交到 Git。** 启动检查若遇到 `change-me-*` 默认值且服务暴露到非 localhost，应直接拒绝启动。

## 12.2 Docker Compose 逻辑要求

- 上游 SAG 自己的 Compose 与本项目 Compose 通过同一内部 network 互通；不要盲目把上游文件复制后忘记追踪差异。
- `embedding-service` 使用独立 `./data/models/embeddinggemma2` cache volume；不要求每次重启重新下载。
- `app-api` volume 持久化 SQLite、taxonomy source、导入报告和必要日志。
- 暴露到宿主机的端口默认只绑定 `127.0.0.1`。SAG API 也不应默认对局域网所有设备开放。
- 使用 healthcheck 控制启动顺序，但要注意 Compose `depends_on` 不能代替运行期重试。
- `llm-service` 由使用者选择具体运行时/模型；compose 中用 profile 或可替换服务配置，不固定要求单一厂商。
- 第一次启动模型加载可能耗时较长；API 用 readiness 表示真正加载完成，UI 展示“模型加载中”，不要假定容器进程在即代表模型可调用。

## 12.3 本地化与数据外发控制

- 所有外部连接集中在 config 模块；默认 embedding 与 LLM base URL 指向内部容器服务。
- 开发者需测试断网启动后的核心流程；首次下载模型之后关闭外网，验证导入/向量化/检索/生成是否依旧可用。
- 由于 SAG、PDF parser/OCR、网页抓取组件可能有独立外部提供者，部署说明必须列出具体调用路径。模型 API、OCR API、网页 URL 抓取均不得偷偷 fallback 到云端。
- 日志默认不记录完整文档正文、用户问题和儿童个人信息。调试时应通过短期显式开关开启，并有自动关闭时间。
- 如果未来将服务暴露到 LAN，至少启用 API token、CORS allowlist、访问日志脱敏、防止任意 URL fetch 的 SSRF 保护和速率限制。

## 12.4 未成年人数据保护

- 默认不收集真名、学校、生日、住址、照片等非必要个人信息；使用自定义昵称/本地 profile ID。
- 默认数据保存在本机；明确展示数据目录和删除/导出入口。
- 练习、答案、掌握度属于学习者敏感数据；不发送给未声明的外部模型服务。
- 生成学习建议要适龄、非羞辱性；不可给学习者贴固定智力标签。
- 需要共享给教师/家长时必须另设权限与同意机制，不把本地单用户 MVP 默认开放为家庭/课堂多人系统。

## 12.5 备份、删除与恢复

- 提供 `scripts/backup.sh` 备份 `app.sqlite3`（使用 SQLite backup API）、SAG 数据卷和 taxonomy source 文件；模型缓存可以单独备份/重新下载。
- 恢复演练应分别验证 SAG 原文/索引可用与 app 数据映射一致；若缺少 SAG source，app 映射必须标记 orphaned 而不是显示为有效引用。
- 删除个人文档时按配置删除 SAG 文档/来源、app mapping 和原始文件；默认不自动删除知识图谱原始数据。
- 删除学习者时应遵守明确策略：删除个人答题/掌握度，匿名化统计，或保留记录需用户清楚选择。

---

# 13. 测试策略与第一版验收标准

## 13.1 测试层级

1. **Unit**：embedding profile、输入验证、JSON 映射、边方向、路径查询、掌握度算法、错误码。
2. **Contract**：Embedding API 的 OpenAI-compatible 请求/响应；SAG 的已锁定 OpenAPI；LLM chat endpoint。
3. **Integration**：容器间连接、本地模型加载、SAG 导入/Ready、查询向量 profile 一致性、SQLite migration。
4. **E2E**：真实文档导入 → 检索 → LLM 答案引用；K12 知识点查询 → 先修路线 → 关联教材 → 作答 → 掌握度更新。
5. **Quality eval**：中文语义检索集、引用准确性、学习内容事实性、数学客观题自动判题正确率。
6. **Security/privacy**：API key 不出现在日志，未授权 source 不可检索，离线模式不尝试外部服务，敏感文件路径不泄漏。

## 13.2 Embedding 服务验收测试

- **EMB-001 — 健康状态**：模型未加载时 `/healthz` 返回 503；加载完成后返回 200 且 `model_loaded=true`。
- **EMB-002 — 单条向量**：`data.length=1`，向量维度正确，所有值均为 finite。
- **EMB-003 — 批量向量**：输出数量等于 input 数量，`index` 连续且顺序不变。
- **EMB-004 — query/document profile**：使用不同 prompt profile；测试能证明请求走了各自的编码函数。
- **EMB-005 — 维度与归一化**：profile 维度固定；256/768D 截断后 L2 norm 约为 1，误差容限 `1e-3`。
- **EMB-006 — 未知 alias**：返回 `400` 与 `invalid_model`。
- **EMB-007 — 非法输入**：空文本或非字符串返回 `400/422`，进程不崩溃。
- **EMB-008 — 超长输入**：拒绝并返回 `input_too_long`，不得静默截断。
- **EMB-009 — NaN/Inf 防护**：注入坏向量的单元测试必须证明 API 不会成功返回非法数值。
- **EMB-010 — FP16 禁用**：配置校验拒绝 `float16`；启动日志显式打印 dtype。
- **EMB-011 — 并发请求**：配置并发下无重复加载模型、无资源泄漏，异常可控。
- **EMB-012 — 离线启动**：权重已缓存时断网，服务仍可启动并完成请求。

## 13.3 SAG 适配验收测试

- **SAG-001 — Health/ready**：App API 能读取 SAG health/ready；服务未就绪时返回可解释错误。
- **SAG-002 — 本地 embedding**：SAG 请求实际命中本地 `/v1/embeddings`；外部网络验证中无 embedding 云请求。
- **SAG-003 — query/document profile**：文档索引使用 document profile，用户 query 使用 query profile；若上游不支持，必须通过已批准的兼容扩展测试。
- **SAG-004 — 维度固定**：服务输出维度与 SAG index dimension 完全一致。
- **SAG-005 — 新文档可检索**：文档 Ready 后，query Top-K 命中预期 chunk/来源。
- **SAG-006 — 引用可追溯**：每个 citation 映射真实 SAG source/chunk；无法定位时显示 locator unavailable，不得伪造链接。
- **SAG-007 — 切换模型保护**：profile 不匹配时拒绝检索或提示重建，不静默查询旧索引。
- **SAG-008 — 上游超时**：30 秒默认超时后返回 `502/504`，服务进程保持正常。
- **SAG-009 — 导入幂等**：重试同一 `Idempotency-Key` 不产生重复文档。
- **SAG-010 — 数据空间隔离**：只在授权 `source_ids` 内检索，越界 source 不得成为证据。

## 13.4 K12 数据导入验收测试

- **K12-001 — 原始 JSON 导入**：锁定的原始 JSON 通过 schema 校验，生成 taxonomy version 与 import report。
- **K12-002 — 节点计数**：统计必须与该 commit 的原始数据一致；公开统计约 10 个学科、6,657 个知识点，仅作为参考值。
- **K12-003 — 关系计数**：分别报告 prerequisite 与 related；计数与源数据 type 分布一致。
- **K12-004 — 关系方向 fixture**：`src=dependent,tgt=prereq,type=1` 必须转成 `prereq → dependent`。
- **K12-005 — 悬空边**：dangling edge 目标为 0；若非零，导入失败或明确阻断 active version 切换。
- **K12-006 — 幂等重导入**：相同 source commit + file hash 再次导入不产生重复数据，报告 unchanged/no-op。
- **K12-007 — 失败隔离**：新版本导入失败后 active version 不变，旧版本仍可查询。
- **K12-008 — prerequisites API**：先修接口返回入边（`source=prereq,target=current`）对应的知识点。
- **K12-009 — successors API**：后继接口返回出边（`source=current,target=dependent`）对应的知识点。
- **K12-010 — 环路处理**：检测并报告循环，路径计算不会无限循环。
- **K12-011 — 中文搜索**：FTS/关键词可查知识点名称，并返回 subject、grade 和 source version。
- **K12-012 — 来源追溯**：可查 commit、文件 SHA-256、导入时间和统计报告。

## 13.5 学习功能验收测试

- **EDU-001 — 创建学习者**：生成本地 profile，不要求真名、学校等非必要个人信息。
- **EDU-002 — 自动判题**：exact/rule-based 题目对正确答案和错误答案的判定均符合预期。
- **EDU-003 — 无答案键题目**：`is_correct=null`；不得将 LLM 估计冒充确定事实。
- **EDU-004 — 答题记录**：answer、question/KP、duration、hints、grading method 与时间字段完整入库。
- **EDU-005 — 掌握度可重复**：同一记录集与 `algorithm_version` 必须产生确定一致的结果。
- **EDU-006 — 证据不足**：作答量不足时，mastery status 不得标为 mastered。
- **EDU-007 — 学习推荐**：先修项按拓扑顺序输出，并解释所引用的依赖关系与掌握度数据。
- **EDU-008 — 辅导引用**：资料引用只能来自检索上下文；无资料时标记 insufficient/partial。
- **EDU-009 — 访问隔离**：查询/写入必须对 learner_id 做授权；不可只信任前端传入的 ID。
- **EDU-010 — 学段适配**：返回内容包含目标知识点与年级；人工抽查生成题目，无明显跨年级错配。

## 13.6 中文检索评估集

第一版建立至少 100 条人工标注 query：

- 40 条个人资料/技术语义查询；
- 30 条 K12 知识点名称、别名、描述和先修关系查询；
- 20 条中文教材/例题检索；
- 10 条对抗用例（文档中含指令、相似但错误知识点、无证据问题）。

每条评估数据保存：query、期望 source/document/chunk 或知识点 ID、允许的相关结果集合、category、语言/学段、标注人、数据版本。避免测试集直接暴露用户隐私。

建议 MVP 初始质量目标（需按真实语料校准，不是模型厂商保证）：

- taxonomy 精确名称/ID 查询命中率 ≥ 99%；
- 100 条检索 query 的 Recall@5 ≥ 0.80；
- 个人资料问题的答案至少有一个真实引用比例 ≥ 95%（对测试集中本来就有证据的问题）；
- 引用目标与答案所述句子一致性的人工抽查通过率 ≥ 90%；
- 资料中没有答案的问题，不伪造来源的比例 ≥ 95%；
- 不同模型/维度对比报告至少包含 Recall@1/5/10、MRR@10、延迟和内存。

如未达到阈值，先调整 chunking、source metadata、task prompt、query rewrite 或 retrieval strategy；不得直接通过降低测试阈值掩盖问题。

## 13.7 性能验收（暂定基准）

开发人员须记录执行机器、CPU/GPU 型号、RAM/VRAM、操作系统、模型 revision、dtype、维度、批量大小和测试日期。性能目标作为首版优化方向：

- 单条 query embedding（≤256 个中文字符、模型热启动）P95 目标 ≤ 1.5s（CPU/GPU 分别报告；若机器不达标需记录基线并优化）；
- embedding 32 条短文本 batch 无错误完成；
- app taxonomy 查询直接先修关系 P95 ≤ 100ms（本地 SQLite 热缓存，单节点关系查询）；
- 先修路径 BFS 最大深度 5、最多 100 节点，不得出现无限循环；
- 端到端 RAG 回答时延需分别报告检索时间与 LLM 生成时间，不用一个综合数掩盖性能瓶颈。

性能目标在基准硬件上测量；不把单次运行结果当作跨硬件 SLA。

## 13.8 发布阻断条件

出现任一情形不可宣布 MVP Ready：

- Embedding 服务仍使用 FP16，或者 embedding 含 NaN/Inf；
- query/document 维度或模型不兼容；
- SAG 检索没有实际使用指定本地 embedding 服务；
- K12 先修方向测试失败；
- 导入后存在未处理的 dangling edges；
- citations 与实际证据不匹配或存在伪造来源；
- 任意 API key 出现在 git、响应或普通日志中；
- 没有离线模式和外部调用清单；
- 测试只能手动运行且无法在 README 中复现。

---

# 14. 里程碑、任务拆解与交付物

## M0：仓库与版本冻结（0.5–1 天）

- [ ] 记录 SAG commit/tag、`zleap-sag` 版本、K12 repo commit、原始数据 SHA-256、EmbeddingGemma 2 revision。
- [ ] 保存上游 SAG `/openapi.json`；核对 EmbeddingConfig、query embedding、model settings 实际 schema。
- [ ] 创建 `README.md`、`.env.example`、`uv.lock` 与基础 CI。
- **交付**：`docs/UPSTREAM_VERSIONS.md`、源数据 checksum、依赖锁文件。

## M1：Embedding 服务 MVP（1–2 天）

- [ ] FastAPI 服务 health/models/embeddings 路由。
- [ ] 文本 encoder 选择性加载、query/document prompt profile、维度截断与归一化。
- [ ] 输入校验、限流/并发保护、错误码和结构化日志。
- [ ] 单元测试覆盖 EMB-001 至 EMB-010。
- **交付**：可运行的 embedding container、API 文档、benchmark 命令。

## M2：SAG 接入 Spike（1–2 天）

- [ ] 通过 mock server 检查 SAG 实际 embedding 请求和响应格式。
- [ ] 验证 doc/query profile 区分；记录是否可纯配置接通。
- [ ] 完成一份 Markdown 文档导入 → Ready → search → citation 的 smoke test。
- [ ] 如不支持任务区分，决定 provider patch，写回 ADR 并增加测试。
- **交付**：`docs/SAG_EMBEDDING_SPIKE.md`、可复现 compose 配置、SAG compatibility tests。

## M3：K12 导入管线（2–3 天）

- [ ] 建立应用 SQLite 和 Alembic migrations。
- [ ] 写 importer：raw JSON parser、schema validator、subject/KP/edge mapping。
- [ ] 实现事务性 staging + active switch + report。
- [ ] 验证 edge direction、dangling edge、counts、重复导入。
- **交付**：`scripts/import_taxonomy.py`、导入报告 JSON、API。

## M4：Personal Memory API（2–4 天）

- [ ] 创建 source/doc metadata abstraction。
- [ ] SAGClient：创建 source、ingest、poll status、search、ask。
- [ ] memory search/ask API，统一 citation schema。
- [ ] 对 source scope、重试、幂等、上游故障做集成测试。
- **交付**：API 文档、个人资料 smoke test、检索评估脚本。

## M5：K12 结构查询与学习状态（3–5 天）

- [ ] KP detail、prerequisites、successors、related APIs。
- [ ] Learner、questions、attempts、mastery 逻辑和算法版本。
- [ ] 学习推荐规则与引导式 tutor endpoint。
- [ ] 手工编制 20 个数学知识点/练习测试用例先作为小范围验证。
- **交付**：Learning API、算法说明、端到端测试。

## M6：质量、离线、安全与发布（2–4 天）

- [ ] 100 条中文检索 benchmark 和质量报告。
- [ ] 本地断网测试与外部出站流量检查。
- [ ] 数据备份/恢复演练、敏感日志扫描、依赖漏洞检查。
- [ ] 清理 README、故障排查、模型首次启动和升级手册。
- **交付**：MVP release candidate、验收报告、已知限制列表。

> 以上仅是任务切分，不构成对开发工期的保证；实际工作量受模型硬件、SAG 版本兼容性、文档解析质量和 UI 范围影响。

## 14.1 最小可执行发布清单

- [ ] 一条命令启动本项目可管理的服务（SAG 本体按上游支持方式启动）。
- [ ] `GET /api/v1/health` 可检查全部必需依赖。
- [ ] `pytest` 可运行 unit/contract tests。
- [ ] `python scripts/import_taxonomy.py --input ... --dry-run` 只校验不写 active version。
- [ ] `python scripts/import_taxonomy.py --input ... --activate` 可执行导入并输出报告。
- [ ] `python scripts/eval_retrieval.py --dataset data/eval/retrieval_zh.jsonl` 输出机器可读 JSON + Markdown 报告。
- [ ] 从空目录开始能按 README 重建服务与索引。
- [ ] 明确写明更换 embedding profile 需重建索引的操作步骤。

---

# 15. 风险、降级策略与待验证事项

## 15.1 风险表

- **SAG 无法区分 query/document prompt**：召回质量可能下降。先做技术 Spike；必要时扩展 `purpose` 参数，同 prompt 只能作为对照方案。
- **SAG 配置或 REST API 随上游变动**：可能导致部署失败或配置未生效。Pin commit、保存 OpenAPI、执行 contract test；升级必须经单独 PR。
- **EmbeddingGemma 2 内存或冷启动超预算**：本地体验变差。先启用 text-only、测试 256D、缓存权重，并针对目标硬件选 CPU/GPU。
- **中文资料召回不足**：RAG 回答不可靠。建立人工标注集，调整 chunking、前缀与 Top-K；不凭通用 MTEB 分数断言适配。
- **K12 原始 JSON schema 变化**：导入映射错误。加入 schema guard、pin commit、统计门槛；异常时拒绝切换 active version。
- **图谱先修关系有环或不完整**：学习路径可能错误。导入检查环路、路径 BFS 防循环，并在 UI 标出数据限制。
- **教材版权或来源不明**：可能违规存储/分发。首版只导入用户有权使用且许可清晰的内容，保留来源与归属信息。
- **LLM 错误讲解或判断错因**：可能误导学生。提供证据引用、客观题规则判题、仅将掌握度作为估计，并进行人工抽查。
- **用户文档包含 prompt injection**：可能造成越权或错误行为。资料视为不可信内容，限制工具调用并校验 source scope。
- **本地 UI 暴露到 LAN/公网**：可能泄漏隐私。默认 loopback、token、CORS allowlist、最小化日志，不默认开放公网。
- **切换模型/数据后旧向量残留**：召回发生错乱。采用 compatibility hash、蓝绿重建索引与旧 source 清理策略。
- **App 映射与 SAG source 不一致**：引用失效。做定期 reconciliation，标记 orphan，并禁止将失效引用展示为有效。

## 15.2 必须在技术 Spike 中回答的问题

1. 指定 SAG commit 的 `/v1/embeddings` client 是否允许自定义 base URL、传 `model` alias、解析标准 `data[].embedding`？
2. SAG 上游配置的 Query embedding 是否实际可以独立设置 base URL/model，且是否只影响 query，还是其他检索调用也会用到？
3. SAG index 是否对 embedding vector 维度有显式元数据/限制？切换 dimension 如何安全重建？
4. SAG 的文档 Ready 状态和删除/reprocess API 的真实字段与幂等语义是什么？
5. 当前 `tokenmap-data.json` 是否仍保持预期数组格式；字段缺失/扩展是否可由 importer 清楚阻断？
6. 源图谱中的 prerequisite cycle 数量是多少？若非零，哪些 endpoint 需要返回 warning 或排除循环？
7. 实际目标硬件上，EmbeddingGemma 2 文本模式的内存占用、冷启动、热请求时延分别是多少？
8. 所选本地生成模型在中文解释、数学步骤和“无证据时承认不知道”场景的评估结果如何？

在这些问题得到证据之前，不能把“本地完全离线”“无代码修改兼容”“学习诊断准确”等当作已实现事实。

## 15.3 故障降级矩阵

- **Embedding service down**：允许查看已保存的知识点与既有数据，并显示服务不可用；禁止静默切换到云端 embedding。
- **LLM service down**：允许返回原始检索结果、知识点详情和路径查询；禁止编造生成式答案。
- **SAG down**：允许查询 App SQLite 中的 K12 图谱与掌握度记录；禁止假装文档 RAG 正常或使用失效 source 引用。
- **Taxonomy importer failed**：继续使用旧 active taxonomy；禁止激活不完整 staging 版本。
- **Source document parse failed**：显示失败状态与原因；禁止标记 Ready 或混入不完整内容。
- **Citation validation failed**：降级为 partial/insufficient evidence；禁止展示未经验证的引用链接。

---

# 16. Definition of Done（第一版完成定义）

MVP 只有在以下全部条件满足时才可标记完成：

1. 源码、依赖和数据版本可追踪；完整 README 能从新环境复现启动。
2. EmbeddingGemma 2 文本模型在本地可启动；API 契约、错误处理、维度/归一化测试通过；没有 FP16 路径。
3. SAG 实际使用本地 embedding service 完成文档导入和查询；query/document profile 策略有测试证据。
4. 有至少一个带中文内容的个人资料样本，能通过语义 query 找到正确的 chunk，并返回可追溯引用。
5. K12 raw data importer 能从指定 commit 的 JSON 导入；报告中的节点/关系统计与数据源一致；先修方向单测通过；dangling edge 为零或有经批准的明确例外清单。
6. K12 prerequisite/successor/related 三种 API 行为不混淆，所有路径函数能处理 cycles。
7. 学习者 profile、问题、答题记录和 mastery v1 能通过集成测试；无答案键时不伪造确定对错。
8. 100 条中文检索评估数据可重复运行，并输出 Recall@K、MRR、引用与时延指标；结果达到第 13 章阈值，或经产品负责人书面接受差距。
9. 完成断网验证、backup/restore 演练、秘密扫描、source-scope 权限测试。
10. 已知问题、无法本地化的可选依赖、教材数据许可证和模型限制有明确文档。

---

# 附录 A. 建议 API 目录

- `GET /api/v1/health` — 应用及依赖健康状态。
- `GET /api/v1/capabilities` — 能力、模型和数据版本。
- `POST /api/v1/memory/sources` — 创建个人知识源。
- `POST /api/v1/memory/documents` — 导入文件或文本。
- `GET /api/v1/memory/documents/{ref}` — 查询导入状态。
- `POST /api/v1/memory/search` — 语义检索。
- `POST /api/v1/memory/ask` — 带引用问答。
- `POST /api/v1/admin/taxonomy/import` — 发起图谱导入。
- `GET /api/v1/admin/taxonomy/import-runs/{run_id}` — 读取导入进度与报告。
- `GET /api/v1/taxonomy` — 查询知识点列表。
- `GET /api/v1/taxonomy/knowledge-points/{id}` — 知识点详情。
- `GET /api/v1/taxonomy/knowledge-points/{id}/prerequisites` — 查询先修知识点。
- `GET /api/v1/taxonomy/knowledge-points/{id}/successors` — 查询后继知识点。
- `GET /api/v1/taxonomy/knowledge-points/{id}/related` — 查询关联知识点。
- `POST /api/v1/learners` — 创建学习者 profile。
- `GET /api/v1/learners/{id}/mastery` — 查询掌握度。
- `POST /api/v1/learning/attempts` — 提交答题记录。
- `POST /api/v1/learning/recommendations` — 获取下一步学习建议。
- `POST /api/v1/learning/tutor` — 学习问答与讲解。

---

\newpage

# 附录 B. 验收命令建议

```bash
# 1. 运行 Python 单元测试
pytest -q

# 2. 检查应用类型/格式（项目接入 ruff + mypy 后）
ruff check .
mypy services

# 3. 检查应用 API 文档
curl -fsS http://127.0.0.1:8090/api/v1/health
curl -fsS http://127.0.0.1:8090/docs >/dev/null

# 4. 检查 EmbeddingGemma 2 服务
curl -fsS http://127.0.0.1:8081/healthz
curl -fsS http://127.0.0.1:8081/v1/models

# 5. 用测试数据执行 taxonomy dry-run（命令参数由实现决定）
python scripts/import_taxonomy.py --input data/taxonomy-source/tokenmap-data.json --dry-run

# 6. 运行中文检索评估（参数格式在实现时固定）
python scripts/eval_retrieval.py --dataset data/eval/retrieval_zh.jsonl
```

以上命令中的脚本路径是本 PRD 要求实现的交付接口，生成源码时必须保证这些命令真实存在；不得把示例命令作为已经实现的证明。

---

# 附录 C. 参考资料与核验链接

以下链接于 2026-10-09 检查，用于技术设计依据。仓库分支及文档未来可能变化，开发时仍需 pin commit/revision。

1. SAG 上游仓库与 README：`https://github.com/Zleap-AI/SAG`
2. SAG 官方开发文档和 API / DataEngine 说明：通过上游 README 的 Developer Guide、API 文档与 `/openapi.json` 获取；自托管默认文档地址示例为 `http://localhost:8000/docs`。
3. China-K12-knowledge-taxonomy 仓库：`https://github.com/bewho/China-K12-knowledge-taxonomy`
4. K12 上游 README（数据统计、节点/边含义、原始文件路径）：`https://github.com/bewho/China-K12-knowledge-taxonomy/blob/main/README.md`
5. K12 上游构图脚本（数组字段和依赖方向）：`https://github.com/bewho/China-K12-knowledge-taxonomy/blob/main/build_graph.mjs`
6. EmbeddingGemma 2 官方模型卡：`https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2`
7. EmbeddingGemma 2 + Sentence Transformers 推理文档：`https://ai.google.dev/gemma/docs/embeddinggemma/inference-embeddinggemma-with-sentence-transformers`
8. EmbeddingGemma 2 多模态说明（未来阶段参考）：`https://ai.google.dev/gemma/docs/embeddinggemma/multimodal-embeddinggemma-with-sentence-transformers`

## 数据与许可证提醒

- SAG 仓库当前声明 MIT License；实际部署仍要保留 license/notice 文件。
- EmbeddingGemma 2 的模型使用条款和仓库许可证应在发布时再次核对，不因“开源模型”一词而省略许可证审查。
- China-K12-knowledge-taxonomy README 对数据来源包括 tokenmap 知识点组织及 ChinaTextbook 目录作了说明；不同源数据、图谱数据和教材 PDF 的许可可能不同。使用、公开发布和再分发前应分别核实对应许可证、归属要求和出版方版权，不应假定整个仓库都允许重新分发教材全文。

---

**文档结束。**
