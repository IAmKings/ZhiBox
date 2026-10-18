# ZhiBox × SAG 后续应用功能开发计划（Roadmap v1.0）

- **日期**：2026-10-18
- **产品基准**：`docs/product/PRD_ZhiBox_SAG_App_v1.0.md`（下称 PRD）
- **对齐证据**：`docs/review/SAG_APP_ALIGNMENT_REPORT.md`
- **上游锚点**：SAG README-CN（repo main `a6f6a00`，README blob `6b0df82`）、论文 arXiv:2606.15971v2、参考 `zleap-sag==0.14.0`
- **当前底座**：Phase 1 MVP + B1–B7（HEAD `0003d76`）；本计划从 **B8** 开始，不改动已完成的 B1–B7 行为契约。
- **本文件性质**：开发计划；执行 B8 前需 PRD 批准/基线确认。实现批次可独立开卡，但必须按依赖顺序推进。

---

## 1. 交付目标与总体验收

1. **B8–B12 完成 P0 闭环**：导入→SAG 事件/实体索引→三模式检索→带引用问答→事件-实体图谱/溯源，且满足 PRD §11 门禁。
2. 全程保持现有离线 chunk 检索可用（未配置 LLM/禁网时不回退成不可用产品）。
3. 每次批次独立可构建、可测试、可回滚；发布物仅 `release candidate`（不自动 tag/发布，沿用现有发布流程）。
4. 完成时更新用户文档/发布说明，并保留 `docs/review/B8…B12_FIX_REPORT.md` 证据。

**总门禁（P0 发布前，同 PRD §5 FR-11）**
- 多跳中文评测集 R@5 ≥ 当前融合基线 +5pp 且 ≥0.70；单跳 262 集回退 ≤0.5pp。
- 引用有效性 100%、抽样证据支持率 ≥90%、无证据问题拒答正确。
- 禁网零请求；密钥不入库/日志/备份；v5→v6 迁移与备份 v3 回环通过。
- 三档真机（PJZ110/K60U/K40）延迟/内存/稳定性达标；B1–B7 回归全绿。

---

## 2. 批次总览与依赖

```
B8 数据/基础 ──► B9 抽取/索引 ──► B10 结构化检索 ──► B11 LLM 精排+引用问答 ──► B12 图谱/发布
   └──────────────────────────────► B11（provider/隐私骨架在 B8；候选集依赖 B10）
```

| 批次 | 主题 | 主要 FR | 估算（单人日，含评审/测试） | 关键依赖 |
|---|---|---|---|---|
| B8 | 数据模型/LLM 基础/迁移 | FR-01/02/08/10 基础 | 2–3 | 无（基于当前底座） |
| B9 | 抽取与索引管线 | FR-01/02/09 | 3–4 | B8 |
| B10 | 结构化检索/评测 | FR-03/04/05/11 | 3–5 | B9（事件索引）；B8 |
| B11 | LLM 选择/引用问答/Chat/隐私 | FR-06/08/09 | 3–5 | B8 provider；B10 候选集（不可在 B10 前完成） |
| B12 | 图谱/设置/备份/真机验收 | FR-07/09/10/11 | 2–3 | B9/B10/B11 |
| **合计** | P0 闭环 | — | **13–20 人日** | 不含 LLM 服务/评测标注外部时间 |

> FR 覆盖：B8 提供 FR-01/02/08/10 的基础与契约；B9 完成 FR-01/02/09 的抽取索引；B10 完成 FR-03/04/05/11；B11 完成 FR-06/08/09；B12 完成 FR-07/09/10/11。FR-09（trace）贯穿 B9–B12；FR-11 由 B10 建门禁、B12 做最终验收。

> 估算基于现有 1–2 人节奏、已有 B1–B7 工程设施；若引入 PDF 解析（P1）或本地 LLM（P2）另计。每批结束必须可独立演示并记录报告。

---

## 3. B8 — 数据模型、LLM 基础与迁移（✅ 已完成 2026-10-18，见 `docs/review/B8_FIX_REPORT.md`）

> 状态：schema v6/6.json、7 DAO、抽取契约、SAG role/space 契约、LLM 安全基础、设置骨架、备份 v3 骨架已落地；JVM 328/0、K40 非 UI instrumentation 57/57、**PJZ110 Compose UI 5/5**；K40 Compose 因显示栈挂起，UI 门禁以 PJZ110 为准。
> B9 交接：event/entity FTS 重建、document 级 NOT_INDEXED 标记/补抽取 job、event_entity/job 唯一约束与 active 索引（schema v7 或事务保证）、query_trace 写入。

**目标**：落地 schema v6 与可替换的 LLM/抽取骨架，保证不配置 LLM 时现有产品零回归。

**范围/任务**
1. Room schema v6（additive）：扩展 `event/entity/event_entity`；新增 `sag_vector`、`event_fts/entity_fts`、`extraction_job`、`chat_conversation/chat_message/message_citation`、`query_trace`；索引/FK/唯一约束；DAOs。
2. 迁移 v5→v6 + schema JSON 导出 + MigrationTest；老文档 `NOT_INDEXED`。
3. 备份 v3：导出/导入新表与 sag 向量/extraction_job 元数据（P0 不含 chat/query trace）；空间/模型校验；旧 v2 备份兼容；B7 legacy 规则不变。
4. LLM 基础：`LlmProvider` 接口 + OpenAI-compatible HTTP 客户端（流式/非流式）、超时/重试/限流、错误码；`LlmConfig`（Keystore 密钥存储，禁止日志/备份）；`OfflineGuard`。
5. 抽取接口/数据契约：`EventEntityExtractor`、`ExtractionResult`、JSON schema、提示词版本常量；Mock 实现。
6. 设置页骨架：LLM 配置/测试连接/开关（可先无真实问答）；隐私说明。
7. 空间/角色策略：`chunk_content/event_content/entity_description` 的 spaceId 指纹与查询空间校验；不改变现有 chunk 空间兼容。

**写入范围**：`app/src/main/java/com/zhibox/memory/data/db/**`、`core/model/**`、`core/llm/**`（新）、`core/extraction/**`（新）、`core/backup/**`、`ui/Settings*`、`app/schemas/**`、测试目录。
**门禁/验收**：v5→v6 迁移测试通过；备份 v3 导出→清库→导入回环（含/不含事件）通过；未配置 LLM 时全部现有测试/检索零回归；LLM mock 连接/错误路径单测；lint/assemble 绿。
**风险**：schema 迁移破坏老数据 → additive+迁移测试+备份先行；密钥存储 → 专项安全单测；网络库引入 → 维持依赖 strict 校验。

---

## 4. B9 — 事件/实体抽取与索引

**目标**：LLM 配置后，导入/补抽取能稳定生成 event/entity 并建立向量/FTS；离线可查。

**范围/任务**
1. 导入联动：chunk ready 后创建 `extraction_job`（按批次/优先级）；不阻塞 ready；无 LLM/开关关闭 → `NOT_INDEXED`。
2. 抽取执行器：调用 Remote LLM（抽取 prompt：中文指令 + 11 类实体 + **单 chunk 单 event** + 严格 JSON schema）；**不要照搬参考 0.14.0 `extract_document.yaml` 的“多事项聚合/拆分/children”语义**（与论文/PRD FR-01 不一致），只复用其忠实性/实体原则；校验/截断/异常分类；每 chunk 独立事务写 event/entity/incidence。
3. 向量/FTS：event content + entity description 通过现有 embedding provider 写入 `sag_vector`，FTS 写入；空间指纹/失败重试/backfill。
4. 状态/控制：取消、重试、补抽取、重抽取（版本化/失活旧版本）；任务限速（并发/电量/温度/网络门槛，可复用现有 Worker 策略）。
5. UI：导入/来源页显示抽取两阶段状态；失败可操作；设置显示 SAG 增强开关/模型状态。
6. 抽取评测：30–50 chunk 人工抽检（正确性/忠实性/实体类型/覆盖率），记录失败模式；冻结 `promptVersion/extractorVersion`。

**写入范围**：`core/ingest/**`、`core/extraction/**`、`core/embedding/**`（角色/空间）、`data/db/**` 新 DAO、`ui/Import*`、`ui/Sources*`、测试/脚本。
**门禁/验收**：100 chunk 抽取成功率 ≥99%（网络正常）；**30–50 chunk 抽检：event 忠实性 ≥90%、实体类型准确率 ≥85%、每 event ≥1 entity 覆盖 100%**；取消/重试/进程被杀恢复；部分失败隔离；删除级联零残留；backfill 后 event/entity 可检索；抽检报告记录（质量不达标可调 prompt/模型，不阻塞 B10 的 fast 路径）。
**风险**：中文抽取质量/幻觉 → 严格 schema+忠实性抽检+失败可读；LLM 限流/费用 → 批大小/token 预算/可取消；长 chunk 超上下文 → 分片/截断策略。

---

## 4.1 B9 状态（2026-10-18）

**代码、自动门禁与真实 LLM 质量抽检已完成**（详见 `docs/review/B9_FIX_REPORT.md`、`docs/review/B9_EXTRACTION_EVAL_RESULT.md`）：
schema v7、抽取 pipeline/scheduler/worker、FTS/SAG 向量、补抽取/重试、UI 两阶段状态、备份导入 FTS 重建、LLM completion/Remote extractor 已落地；JVM 377/0、K40 非 UI instrumentation 79/79、v3→v7 真机迁移通过。真实 LLM：100 chunk 成功率 99/100=99%、50 chunk 抽检忠实性 100%/实体类型 94.7%/覆盖率 100%，质量门禁通过（自建语料 + AI 辅助标注，发布前建议独立复抽）；B10 可继续。

## 5. B10 — SAG 结构化检索与多跳评测（✅ 完成 2026-10-18，见 `docs/review/B10_FIX_REPORT.md`）

> 状态：三模式/预算/source scope/降级/trace/UI/测试已落地；真机多跳评测 真实 **multi** 多跳 R@5 **0.7681**（PRD 主门禁 ≥0.70 通过；OpenCode Go deepseek-v4-flash，0 refused/88 degraded，p50 7.15s/p95 9.38s）；fast R@5 0.7396（离线子门禁通过）、vector 0.1819；262 单跳 vector/fused 0 回退。保留限制：评测集受控合成未独立人工校验、multi 延迟未达高/中端档、4 跳 0.5375、10k 为 SQL/检索微基准；Compose UI 已在 PJZ110 5/5 通过。详见报告。详见报告。

**目标**：三模式落地，`multi_es_fast` 在离线可用；`multi` 具备 LLM 选择能力；建立可回归的中文多跳评测。

**范围/任务**
1. `SagRetriever`：query normalize/embed；`vector` 沿用现有 chunk 混合检索（并在 UI/文档标注“混合”而非纯 dense）；`multi_es_fast` 实现 **event 向量 seed + entity 词法/向量匹配 + 1 跳 SQL 扩展**（frontier 预算）+ 确定性融合，chunk 向量只做语义补位/事件层缺失降级；`multi` 接入选器接口（B11 完成真实 LLM，B10 可 mock）。
2. 预算/档位：Kpool/Kseed/frontier/Kcand/Kevent 配置与设备档位；trace 记录每阶段计数/耗时/降级。
3. 双路径输出：event→chunk 映射、去重、Kout=10；source scope 过滤（SQL/查询范围）。
4. 降级：无事件索引→vector；LLM 不可用→fast；SQL/超预算→截断并标注。
5. 新增中文多跳评测集（≥120 条、2–4 跳、**每条多证据 chunk ID**、人工校验）与评测脚本：R@5/10、MRR、citation placeholder、延迟/内存；现有 262 集（50 卡、每正例仅 1 个 relevant、R@10 已天花板）只作单跳回归，不能充当多跳门禁。
6. 消融实验：vector / fast / multi；无扩展/无事件层/无 selector 对照，写入报告。
7. 性能：10k chunk + 10k event 的 SQL/矩阵/FTS 基线；三档真机。

**写入范围**：`core/search/**`、`core/sag/**`（新）、`data/db/Daos.kt`、`ui/Search*`、`scripts/eval`/`docs/phase1` 评测材料、测试。
**门禁/验收**：多跳 R@5 ≥ baseline+5pp 且 ≥0.70（若未达，则 multi 标记实验、fast 可发，B10 报告记录）；262 集回退 ≤0.5pp；离线 fast 零网络；延迟按 PRD §9.1；trace 可复现；删除/空间失配路径通过。
**风险**：SQL 候选爆炸 → 预算/索引/分页；中文多跳集偏差 → 人工校验；event 抽取不足导致 fast 无效果 → 与 B9 质量联动，必要时降档 fast 功能范围。

---

## 6. B11 — LLM 上下文选择、带引用问答与 Chat/隐私（🟡 代码/自动门禁完成，真实 20 问答+50 支持率待设备 key）

> 状态：流式 provider/引用校验/Chat 持久化与 UI/隐私开关/异常矩阵已落地；JVM 497/0、PJZ110 ChatRepository+Compose 20/20。已修问答/SAG 开关耦合、sourceScope/模式 UI、引用 rank 重载错位、consent host、retry 门禁、取消竞态、主线程解密；引用内容级支持未做，真实 20 条 QA 与 50 条支持率主门禁尚未执行（PJZ110 无 LLM key、K40 未连接）；B11 暂不关闭。详见 `docs/review/B11_FIX_REPORT.md`。

**目标**：在 B10 候选集上完成 LLM 上下文选择/精排与带引用问答，并满足隐私/离线和引用校验门禁。

**P0 范围**：**单轮问答**（每问仅用本轮 query+证据，不注入历史上下文；会话/引用/scope 持久化可查看）。多轮上下文、工具调用、Agent 对话编排放 P1。

**范围/任务**
1. `ContextualSelector`：一次结构化调用（候选 event title/summary + query），返回选择索引+理由；`Kevent` 上限；错误/超时→fast 降级。
2. `AnswerGenerator`：证据 chunk 原文→流式回答；引用 `[S#]` 映射；系统提示强制"仅用证据/无证据拒答"；temperature/token 预算；停止/重试。
3. `CitationValidator`：引用 ID ⊆ 证据、可定位、逐引用内容校验；不合法引用移除/标注；无引用断言检测（抽样/规则），必要时拒答。
4. Chat UI：会话/消息/引用、source scope、模式选择、停止/重试、错误可读；会话持久化（不写正文日志）；**P0 不把历史消息注入下一问上下文**（避免范围蔓延/隐私面扩大）。
5. 隐私：禁网总开关、首次联网确认、payload 最小化、密钥安全、日志脱敏；真机网络抓包/测试断言。
6. 验收评测：20 条真机问答 + 50 条抽样支持率；LLM mock 异常矩阵（429/5xx/timeout/坏 JSON/引用幻觉）。
7. 文档：用户帮助/隐私说明；发布说明更新。

**写入范围**：`core/llm/**`、`core/sag/selector*`、`core/chat/**`（新）、`ui/Chat*`（新）、`ui/Search*`、`ui/Settings*`、`res/values/strings.xml`、测试。
**门禁/验收**：引用有效性 100%、支持率 ≥90%、无证据拒答；禁网零请求；密钥/日志/备份检查；首 token/总时长达标；错误/超时/限流可读且可恢复。
**风险**：LLM 编造引用 → validator+拒答策略；隐私投诉 → 显式开关/最小化；远程成本/延迟 → token 预算/超时/降级；不同 provider 兼容性 → 只承诺 OpenAI-compatible 子集。

---

## 7. B12 — 图谱/事件详情、设置完善、备份与真机验收

**目标**：完成 P0 用户可见闭环与发布前验收；不改 release 自动化（签名/发布沿用 B4-6 流程）。

**范围/任务**
1. 来源页图谱/事件详情：event 节点 + entity 连接（2D）、按 source 过滤、分页/上限、event 详情（entities/chunk 原文）；无抽取空态。
2. 设置完善：SAG/问答开关、LLM 配置、预算档位、抽取并发/网络策略、诊断导出（脱敏）、隐私说明。
3. 备份 v3 UI 与兼容：导出/导入、模型/空间依赖提示、旧备份提示补抽取。
4. 诊断/可观测：抽取/检索/问答 trace、错误码、失败重试入口。
5. 评测/验收：三档真机跑 P0 矩阵；多跳/单跳回归；迁移/备份/删除/禁网/超时；P0 验收报告。
6. 文档/发布候选：README/RELEASE_NOTES/用户指南更新；准备 release candidate（不自动发布）。

**写入范围**：`ui/Sources*`、`ui/Settings*`、`ui/**` 新图谱组件、`core/backup/**`、`core/sag/**` 诊断、`docs/**`、测试。
**门禁/验收**：PRD §11 全项；三档设备矩阵通过；B1–B7 回归全绿；release candidate 可安装/验签（若走现有发布流程）；无新增 P0 缺陷。
**风险**：图谱大库卡顿 → 分页/上限/异步；验收周期 → 提前准备评测集/设备；范围蔓延 → P1/P2 不进入 B12。

---

## 8. P1 / P2 Backlog（不入 P0）

### P1（B13+，按价值/成本排序；每项需明确触发条件）
1. **PDF/Office/网页导入**：MarkItDown/AnyDoc/MinerU 等价能力、来源 URL；解析失败回退；导入进度/幂等。触发：用户非 TXT/MD 资料占比 ≥20% 或明确需求。
2. **Explore/3D 图谱**：全库 event-entity 漫游、3D 渲染、搜索/筛选；性能与可访问性替代列表。触发：P0 图谱使用/反馈证明需要全库漫游。
3. **多轮 Agent/工具**：threads、@source、历史上下文注入、工具调用、引用继承；需模型能力评估。触发：P0 单轮问答稳定且用户需要多轮上下文。
4. **实体别名表**：别名/缩写/全称合并（论文局限），只影响索引/扩展，不改 append-only。触发：抽检显示别名导致明显漏召回。
5. **抽取质量专项**：中文 prompt 迭代、模型对比、实体类型分布/覆盖率、失败回填。触发：B9 抽检未达 PRD FR-01 质量线或用户反馈实体缺失。
6. **P0 Chat 增强**：多轮上下文/工具/Agent 编排（并入第 3 项时独立评估）。

### P2（触发条件）
- **集成**：REST/OpenAPI、MCP、OpenAI-compatible、Dify、dsh-sag — 触发：用户明确需要外部 Agent 复用知识库，且 B12 P0 稳定。
- **本地 LLM**：LiteRT-LM/llama.cpp 适配，模型档位/内存/延迟/质量门；通过后再替换远程默认。触发：全离线生成/隐私要求不允许远程，且端侧模型可行。
- **云同步/多用户** — 触发：跨设备/家庭共享需求；需账号/加密/冲突设计。
- **K12 学习助手** — 独立产品线（已有 PRD）；触发：个人知识库 P0 发布后单独立项。
- **多模态**（图片/音视频） — 触发：明确数据源与模型（OCR/vision embedding）可行。
- **ANN** — 触发：chunk > 5 万或精确扫描 p95 超门禁（沿用调查报告附录 B.4 门禁）。

---

## 9. 团队分工与写入范围

| 角色 | 负责 | 写入范围（建议） |
|---|---|---|
| Lead | 架构/PRD/验收/集成/发布报告 | `docs/product/**`、`docs/review/B8–B12*`、跨模块复核 |
| core-fixer | schema/抽取/检索/LLM/chat 核心 | `core/**`、`data/db/**` |
| data-reviewer | 迁移/备份/检索评测/测试 | `app/src/test/**`、`androidTest/**`、`scripts/eval/**`、`docs/review/*data*` |
| ui-fixer | 搜索/导入/来源/Chat/设置 UI | `ui/**`、`res/values/strings.xml` |
| ui-reviewer | 图谱/组件/Compose 测试/无障碍 | `ui/**`、`app/src/androidTest/**` |
| build-fixer | 依赖/CI/构建脚本 | `gradle/**`、`app/build.gradle.kts`、`.github/workflows/**` |

规则：批次开工前建 shared task 并声明写入范围；跨模块 API 由 Lead 定契约后再并行；每批结束统一门禁（JVM/lint/assemble/instrumentation）与真机证据；不直接 commit 未评审的跨模块改动。

---

## 10. 测试与评测矩阵（每批必跑）

| 层级 | 内容 |
|---|---|
| JVM/Robolectric | schema/迁移、抽取 JSON 校验、SQL 扩展、预算/降级、引用校验、LLM mock |
| Instrumentation | 导入→抽取→检索→图谱→问答主链；删除级联；备份回环；禁网；三档设备 |
| Compose UI | 搜索模式/source scope、Chat 引用点击、图谱空态/分页、设置错误态 |
| 评测 | 262 单跳回归 + 新 120 多跳集；R@5/10、MRR、citation validity/support；基线/消融 |
| 性能 | 10k/100k 合成库（SQL/FTS/矩阵）、导入吞吐、抽取后台占用、三档延迟/内存 |
| 安全/隐私 | 禁网零请求、密钥存储、日志/备份/导出无密钥、HTTPS、LLM 错误处理 |

---

## 11. 发布/回滚与风险

- **发布**：B12 产出 release candidate；沿用 `scripts/release.sh` 与 B4-6 签名流程，不自动 tag/Release；用户验收后由发布负责人打 tag。
- **数据回滚**：升级前导出 v2/v3 备份；v6 迁移后旧 APK 不能直接读（沿用 v5 文档规则）；回滚需备份+旧 APK/清数据流程。
- **功能开关**：SAG/问答/禁网均可在设置关闭；关闭后保持当前产品行为。
- **主要风险**：远程 LLM 不稳定/费用、中文抽取质量、评测集偏差、schema/备份迁移、图谱性能、范围蔓延。缓解见 PRD §13 与各批风险条目。

---

## 12. 里程碑与交付物

| 里程碑 | 内容 | 交付物 |
|---|---|---|
| M1（B8 完成） | schema v6/LLM 基础/迁移备份 | B8 报告 + 迁移/备份测试 |
| M2（B9 完成） | 抽取索引可用 | B9 报告 + 抽检结果 |
| M3（B10 完成） | 三模式/多跳基线 | B10 报告 + 评测集/消融 |
| M4（B11 完成） | 引用问答/隐私 | B11 报告 + 问答验收 |
| M5（B12 完成） | 图谱/设置/真机/P0 | B12 报告 + P0 验收报告 + release candidate |

每个里程碑需更新 `docs/review/` 报告与 README/发布说明；未达门禁的功能标记 experimental，不进入 release notes 的"已交付"。

---

*维护：批次范围变更走 Lead 评审；P1/P2 触发条件变化时更新本文件；与 PRD/对齐报告保持双向链接。*
