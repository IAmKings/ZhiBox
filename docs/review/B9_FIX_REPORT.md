# B9 实施报告（事件/实体抽取与索引）

- **日期**：2026-10-18
- **依据**：`docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md` §4（B9）、`PRD_ZhiBox_SAG_App_v1.0.md`（FR-01/FR-02/§6 schema/§11 验收）
- **性质**：实现 SAG 增强的**写入侧**：LLM 抽取 → event/entity/incidence → FTS/SAG 向量；检索（B10）、问答（B11）、图谱（B12）未实现。
- **约束**：无 LLM / SAG 开关关闭 / 禁网时任务为 `not_indexed`，文档仍 ready 可离线原文检索；现有导入/检索/备份 v1/v2 行为零回归。

---

## 1. 结论

| 项 | 结果 |
|---|---|
| schema v7 | 4 个新索引/唯一约束（event `(chunkId,active)`、event_entity 唯一对、extraction_job chunk 唯一、sag_vector 四元组唯一）；`MIGRATION_6_7` 先去重再建索引；`7.json`（identityHash `a5dba149b7c50a52f678cf84691e5509`） |
| 抽取执行 | 每 chunk 独立 job；Remote LLM 抽取（单 chunk 单 event、11 类实体、严格 JSON）；成功时单事务写 event/entity/incidence/FTS/向量并 supersede 旧版本；失败分类/重试上限/取消/进程被杀回收 |
| 向量/FTS | `SagEmbeddingGateway` 走现有 embedding runtime；role 空间指纹 `event_content`/`entity_description`；event/entity FTS 使用既有 bigram；FTS 全量重建供备份导入 |
| 调度/Worker | 导入 ready 后创建 job 并触发 `zhibox-sag-extraction`（网络+电量约束）；无 LLM→not_indexed；补抽取/失败重试/取消；不阻塞 ready |
| LLM | `LlmCompletionProvider` + OpenAI-compatible 非流式 completion + `RemoteLlmEventEntityExtractor`；禁网零请求、HTTPS-only、不跟重定向、key/正文不入错误 |
| UI | 设置页抽取五态计数 + 补抽取/重试；来源页逐文档状态；导入页 ready 后抽取摘要（抽取失败不误报导入失败） |
| 备份 | v3 导入后重建 event/entity FTS（v2 保持空）；extraction_job 全字段回环；FTS 仍不导出 |
| JVM 测试 | **379 / 0 failed**（debug）、**360 / 0**（release unit） |
| 真机 instrumentation | **79 / 79 PASS**（K40 非 UI 全量，含 v6→v7、pipeline/scheduler/备份 FTS/LLM） |
| Compose UI | B8 基线 PJZ110 **5/5 PASS**；B9 期间 PJZ110 未连接，本轮未重跑（B9 未改 Compose smoke 覆盖的 Home/Search/Evidence 业务） |
| 真机迁移 | K40 原 v3 DB 启动后链式迁移到 **v7**：16 chunks 保留、4 索引齐全、`foreign_key_check` 空 |
| lint/构建 | **0 errors / 34 warnings**；assembleDebug/Release 绿；release APK **49,208,940 B**（+56.7KB vs B8） |
| 100 chunk 自动门槛 | fake extractor/embedding：**100/100 done、100 active event、100 event FTS、101 SAG 向量、1 entity 复用**（instrumentation 通过） |
| **真实 LLM 质量抽检** | **未执行/未关闭**：测试设备未配置真实 LLM endpoint/API key（见 §5）；30–50 chunk 忠实性/实体类型/覆盖率需用户配置后执行 |

**结论**：B9 代码与自动门禁（除真实 LLM 质量抽检）完成；`ROADMAP B9` 标为“代码完成、质量门禁待执行”，不宣称 B9 验收全部关闭。

---

## 2. 逐模块交付

### 2.1 数据层 schema v7
- event 增加 `(chunkId,active)` 索引（供 active event 检索/回填）。
- event_entity 增加唯一 `(eventId,entityId)`（同一 event-entity 只保留一条关系；supersede 历史关系保留）。
- extraction_job.chunkId 唯一（每 chunk 一条任务；重试复用 job id/版本）。
- sag_vector 唯一 `(ownerType,ownerId,role,spaceId)`（多 role/空间合法；同四元组幂等）。
- `MIGRATION_6_7`：先按 `MIN(id)`/`MIN(rowid)` 清理历史重复 → 重建/创建索引 → `schema_migrations` → FK 自检；纯 DELETE/CREATE INDEX，无网络/重嵌入。

### 2.2 抽取管线（`core/extraction`）
- `SagExtractionPipeline`：claim→加载 chunk/doc→`EventEntityExtractor.extract`→严格校验（单 event、title/content 非空、11 类、isValid）→事务外计算 event/entity 向量→单事务写 event（active、extractionVersion=job.id、prompt/model 版本）、实体（normalizedName 去重、insertIgnore/update）、event_entity（weight/description）、event/entity FTS、SAG 向量；supersede 同 chunk 旧 active 并清理旧 FTS/向量；job→done 同事务。
- 失败分类：限流/网络/超时/5xx 可重试（attempt<3→queued，否则 failed）；AUTH/PARSE/INVALID_OUTPUT 终态；错误 detail 脱敏。
- 取消/进程被杀：Worker 启动 `recoverInterrupted`（RUNNING→QUEUED）；取消时 NonCancellable 回收；文档非 ready/被取消整篇取消；单 chunk 失败隔离。
- `SagExtractionScheduler`：`onDocumentReady` 每 chunk 建 job（queued/not_indexed，幂等）；`backfill` 补缺失/重排 not_indexed/ cancelled；`retryFailed`；`cancelDocument`；`enqueue` unique work（CONNECTED+batteryNotLow）。
- `SagExtractionWorker`：循环 `processNext`；构造 Remote LLM extractor + EmbeddingSagGateway；可取消。
- `EmbeddingSagGateway`：现有 `EmbeddingRuntime.withProvider` + `PathPreferences` 模型；基础 space + `SagVectorSpace.spaceIdFor` 派生空间；`embedding_meta` active=false（可复用）；float32 LE+L2 norm；role 缓存。
- `SagFtsRebuilder`：清空后按 active event + all entity 重建 bigram FTS（备份导入调用）。
- `SagAvailability`：`sagEnabled && networkAllowed && store.load()!=null`；Keystore 损坏按不可用；不加载模型。

### 2.3 LLM 生成与 Remote 抽取器
- `LlmCompletionProvider.complete(config, systemPrompt, userPrompt, maxTokens=1024): LlmCompletionResult`；`OpenAiCompatibleLlmProvider` 同时实现 testConnection+completion。
- `RemoteLlmEventEntityExtractor`：store 读取配置（null→LLM_NOT_CONFIGURED；异常→LLM_STORE_ERROR）；`ExtractionPrompts.systemPrompt/userPrompt`；严格 `ExtractionJson.parse`；错误透传 code/可读 message，不含 key/正文。
- 安全边界沿用 B8：禁网零请求、HTTPS-only、不跟随重定向、不记录正文/key。

### 2.4 导入/调度/UI
- `IngestRepository.processDocument` ready 成功后调用 `SagExtractionScheduler.onDocumentReady + enqueue`；异常不回滚/不阻塞导入（文档 ready 保留；可补抽取）。
- 导入页：五态抽取摘要（jobs 去抖刷新），失败提示“文档已 ready，可离线检索，抽取失败与导入无关”。
- 来源页：每文档抽取状态（完成 n/n、进行中、失败 f/total、未索引），不替换原有导入状态。
- 设置页：SAG 抽取状态卡（queued/running/done/failed/not_indexed）+「补抽取」「重试失败」；`sagAvailable=false` 时禁用并提示先配置 LLM/开 SAG/关禁网。

### 2.5 备份
- v3 导入事务成功后、事务外调用 `SagFtsRebuilder.rebuildAll`（v1/v2 跳过保持空）；失败不影响已恢复数据，可读报错可重试。
- extraction_job status/attempt/errorCode/errorDetail 全字段回环；FTS 仍不导出。
- v7 唯一约束：v3 包重复 incidence/sag 四元组/同 chunk 多 job → 写库前可读拒绝（不静默合并丢数据）。

---

## 3. 验证证据

### 3.1 自动门禁
```text
JVM debug = 379 tests / 0 failed
release unit = 360 tests / 0 failed
lint = 0 errors / 33 warnings
compileDebug/Release/AndroidTest = SUCCESS
assembleDebug/Release = SUCCESS
release APK = 49,208,940 B
schema 7.json = version 7 / identityHash a5dba149... / 21 entities
```

新增测试：
- schema v7：MigrationV6ToV7Test（重复清理/唯一索引/全链 v1→v7）；
- pipeline：`SagExtractionPipelineTest` 14（成功/supersede/invalid/重试 attempt/AUTH/embedding 重试/not_indexed→恢复/取消/非 ready/recover/隔离/previousContext/100 chunk/FTS rebuild）；
- scheduler：`SagExtractionSchedulerTest` 6（幂等/not_indexed/重排/终态/cancel/不可用 backfill）；
- LLM：`LlmCompletionTest` 19、`RemoteLlmEventEntityExtractorTest` 8、TLS mock completion 2；
- support/availability：`SagExtractionSupportTest` 13、`SagAvailabilityRobolectricTest` 4；
- backup：`BackupV3RoundTripTest` FTS 期望更新、`BackupV3ContractTest` 21（v7 重复拒绝）。

### 3.2 真机
- **K40（6D8H6TXSDYXC85PZ）**：非 UI instrumentation **79/79 PASS**（39.5s；含 schema v7、pipeline、scheduler、备份 v3 FTS、LLM TLS mock）。
- **v3→v7 真实迁移**：恢复 K40 原 v3 DB（16 chunks）→ B9 debug 启动 Import 屏强制 DB 打开 → `user_version=7`、16 chunks 保留、4 索引齐全、FK 空。
- **Compose**：B8 基线 PJZ110 5/5；B9 期间 PJZ110 未连接，未重跑；B9 改动集中在设置/来源/导入页，Compose smoke 覆盖 Home/Search/Evidence 未改动业务。
- 设备数据：K40 已迁移到 v7（原数据保留）；无测试残留。

### 3.3 100 chunk 自动门槛
instrumentation fake extractor/embedding：100 chunks → 100 done、100 active event、100 event FTS、101 SAG vectors、1 entity 复用，成功率 100%（非真实 LLM 质量）。

---

## 4. 真实 LLM 质量抽检：未关闭（明确阻塞）

**原因**：测试设备（K40/PJZ110）未配置真实 OpenAI-compatible LLM endpoint/API key；项目按隐私要求不内置任何第三方 endpoint，也不会伪造真实质量数据。B9 的 30–50 chunk 人工抽检（event 忠实性 ≥90%、实体类型准确率 ≥85%、每 event ≥1 entity 100%）**尚未执行**。

**待执行清单**（任一方式）：
1. 用户在 App「设置 → SAG 增强 / LLM」保存 endpoint/model/key，开启 SAG、关闭禁网；导入/补抽取 30–50 chunk；Lead 导出 event/entity 样本并人工标注；或
2. 用户提供可用的测试 endpoint（优先内网/低配额）+ 明确授权测试数据，Lead 用脚本跑同一批 chunk 并记录报告。

**在质量抽检完成前，不宣称 B9 验收全部通过**；B10 的 `multi_es_fast` 可先基于已生成的结构数据开发，但上线前需补质量门禁。

---

## 4.1 接入修复：OpenCode Go 网关（2026-10-18）

用户实际配置 `https://opencode.ai/zen/go/v1` + OpenCode Go API key，测试连接报
`Request is missing x-opencode-session and cannot be routed`。

- **根因**：OpenCode Go 要求每个请求携带稳定 `x-opencode-session`（路由/prompt-cache 亲和），
  B8 的 OpenAI-compatible Provider 未发送该头；网关在鉴权前直接 400。
- **修复**：Provider 对官方 `opencode.ai` 域名（兼容 `/zen/go/` 与 `/inference/go/` 路径）
  自动注入持久化 UUID 会话头；非官方域名不注入，避免会话标识外泄；User-Agent 保持
  `ZhiBox/<version> (Android)`（符合“客户端自标识”）。
- **验证**：K40 设备读取用户已保存配置（baseUrl `/zen/go/v1`、model `deepseek-v4-flash`）真机探针：
  修复前 400 MissingSessionID；修复后错误变为 **401 "This API key has expired. Create a new API key..."**
  ——说明会话头已被网关接受；用户需在 OpenCode Console 重新生成 API key。
- **UI**：Base URL 下增加 OpenCode Go 提示（`https://opencode.ai/inference/go/openai/v1`，旧
  `/zen/go/v1` 也可；模型如 `glm-5.3`；会自动携带 session 头）。
- **测试**：`LlmCompletionTest` 新增 3 例（两个官方路径带稳定头、非官方域名不带头、session
  规范化/长度上限）；JVM 379/0。

## 5. 独立审查（Standards + Spec）与处置

| # | 轴/级别 | 发现 | 处置 |
|---|---|---|---|
| R1 | Standards 硬性 | 备份导入后 queued 抽取任务不唤醒（长期“排队中”） | **已修**：`KnowledgeBackup` 导入后调用 `SagExtractionScheduler.enqueue`；WorkManager 未初始化时捕获，不把成功导入改判失败 |
| R2 | Standards/判断 | 调度并发可 REPLACE 覆盖 RUNNING job | **已修**：新增 `ExtractionJobDao.insertIgnoreAll`（IGNORE），重复 chunk 不覆盖状态 |
| R3 | Standards 判断 | 失败收尾可覆盖 CANCELLED | **已修**：pipeline 失败/重试前重读 job 状态，CANCELLED 不再改回 queued/failed |
| R4 | Spec 错误 | 0 实体 event 仍可落库（违反每 event ≥1 entity） | **已修**：pipeline 在 prepareEntities 后为空 → INVALID_OUTPUT 终态 |
| R5 | Spec 错误 | 删除 source/document 后 event/entity FTS、sag_vector、共享 entity 残留 | **已修**：新增 `SagCleanup.cleanupOrphans`；`IngestRepository.deleteSource` 级联后清理 |
| R6 | Spec 错误 | 非法/未知类型实体导致整条 event 失败（PRD 要求过滤实体） | **已修**：`ExtractionJson.parseEntities` 过滤非法实体元素；空实体由 pipeline 拦截 |
| R7 | Spec 缺失 | 生产无“重抽取”入口；DONE 任务不可重新生成新版本 | **未修（如实记录）**：当前只有补抽取/失败重试；重抽取（新 extractionVersion + 旧版失活）列入 B10/B12 交接 |
| R8 | Standards 判断 | 备份 FTS 重建在事务提交后失败会报导入失败但数据已恢复 | **保留并披露**：主数据已恢复，错误文案明确可重试；FTS 是派生索引 |
| R9 | Standards 判断 | LLM 截断 `finish_reason=length` 未识别，可能落 PARSE 终态 | **未修（披露）**：B9 默认 maxTokens 1024；质量抽检时确认长输出，B10/B11 加截断重试/分片 |
| R10 | Standards 判断 | SAG 关闭/禁网/未配置统一记 `NOT_CONFIGURED`，UI 提示不够精确 | **部分**：pipeline 内部 detail 可区分；B10 细化错误码 |
| R11 | Standards 判断 | 重抽取幂等短路/requeueByDocument 未用 | 随 R7 列入 B10 交接 |
| R12 | Spec 部分 | 无真实 30–50 抽检样本/模板/脚本 | **已补**：`scripts/export_sag_samples.py`、`scripts/score_sag_extraction.py`、`B9_EXTRACTION_EVAL.md`；真实执行待 LLM 配置 |
| R13 | Spec 部分 | B9 三屏未跑 Compose；WorkManager enqueue/doWork 未测 | **披露**：B8 Compose 5/5 基线；需 work-testing/PJZ110 重跑，列入 B10/B12 |

## 6. 已知限制/覆盖缺口

| # | 事项 | 说明 |
|---|---|---|
| 1 | WorkManager enqueue/Worker.doWork 真实路径未测 | 需 `androidx.work:work-testing` + WorkManagerTestInitHelper；当前覆盖 scheduler DB 状态/不可用路径 + pipeline recover；列入 B10/B12 测试补强 |
| 2 | 真实 LLM 端到端未跑 | 无 endpoint/key；Remote 抽取器与 pipeline 由 fake/mock 分别覆盖，真实链路待质量抽检 |
| 3 | Compose UI 未在 B9 重跑 | PJZ110 本轮未连接；B8 基线 5/5；B9 未改 Compose smoke 覆盖业务 |
| 4 | FTS rebuild 全量内存 | `SagFtsRebuilder` 用 `all()`；B10/B12 大库导入后再评估分页 |
| 5 | 长 chunk/上下文 | Prompt 侧已有截断/长度控制；B9 未做 600 字以上分片抽取（单 chunk 单 event，符合论文/PRD） |
| 6 | `isValid=false` 处理 | 按 B9 task 字面落 `failed`（SAG_INVALID_OUTPUT），不写 discarded 审计行；后续如需审计可扩展 |
| 7 | cancellation/费用 | 任务可取消、attempt 上限 3；无 token 预算/费用统计（B11 精排/问答再评估） |

---

## 7. 文件清单（B9 增量）

**核心**：`core/extraction/{SagExtractionPipeline,SagExtractionScheduler,SagExtractionWorker,EmbeddingSagGateway,SagEmbeddingGateway,SagAvailability,SagFtsRebuilder,SagExtractionSupport,SagExtractionErrors}.kt`
**LLM**：`core/llm/{LlmCompletionProvider,LlmCompletionResult,RemoteLlmEventEntityExtractor}.kt`、`OpenAiCompatibleLlmProvider`（completion 扩展）
**数据**：`data/db/{Entities,Daos,AppDatabase}.kt`、`app/schemas/7.json`
**集成**：`core/ingest/IngestRepository.kt`（ready 后调度）
**UI**：`ui/{ExtractionUi,SettingsViewModel,SettingsScreen,SourcesViewModel,SourcesScreen,ImportViewModel,ImportScreen}.kt`、`strings.xml`
**备份**：`core/backup/{KnowledgeBackup,BackupClosure}.kt`
**质量评测工具**：`scripts/export_sag_samples.py`、`scripts/score_sag_extraction.py`、`docs/review/B9_EXTRACTION_EVAL.md`
**测试**：`androidTest/{MigrationV6ToV7Test,SagExtractionPipelineTest,SagExtractionSchedulerTest,BackupV3RoundTripTest}`、`test/{LlmCompletionTest,RemoteLlmEventEntityExtractorTest,SagExtractionSupportTest,SagAvailabilityRobolectricTest,BackupV3ContractTest,...}`

*报告生成：2026-10-18 · B9 代码/自动门禁完成；真实 LLM 质量抽检待配置/执行。*
