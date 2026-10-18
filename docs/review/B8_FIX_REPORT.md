# B8 修复报告（SAG 基础批次：schema v6 / 抽取契约 / LLM 基础 / 备份 v3 骨架）

- **日期**：2026-10-18
- **依据**：`docs/product/PRD_ZhiBox_SAG_App_v1.0.md`、`docs/product/ROADMAP_ZhiBox_SAG_App_v1.0.md` §3（B8）
- **前置**：B1–B7 底座（chunk 检索/证据/离线/备份）+ CI 首绿（run `39051725330`）
- **性质**：B8 是 SAG 的**基础批次**：只落数据/契约/LLM 基础设施/设置骨架/备份格式；**不实现**事件抽取执行管线（B9）、结构化检索（B10）、引用问答（B11）、图谱（B12）；未配置 LLM 时现有产品行为保持不变。
- **执行**：Lead（契约/集成/门禁/真机/报告）+ core-fixer（schema/DAO/迁移/抽取契约）+ build-fixer（LLM 基础/依赖）+ ui-fixer（设置页）+ backup-fixer（备份 v3）+ data-reviewer（测试）+ 独立审查。

---

## 1. 结论

| 项目 | 结果 |
|---|---|
| Room schema | **v6**：21 entities（v5 的 13 表结构不变，扩展 event/entity/event_entity + 8 新表）；7 个新 DAO；MIGRATION_5_6（纯 ALTER/CREATE）；`app/schemas/.../6.json` 已导出（identityHash `5bd38b67a7ea2b0651e158e0756328e3`） |
| 抽取契约 | `core/extract` 6 文件：11 类封闭枚举、单 chunk 单 event、严格 JSON 解析（不崩溃/围栏容忍/isValid=false）、Prompt v1（中文、忠实性、禁止多 event/children）、Mock extractor |
| LLM 基础 | `core/llm` 9 文件：LlmConfig/LlmTestResult/Provider/NetworkPolicy/LlmPreferences（**默认禁网**）/ConfigStore（**AndroidKeyStore AES-256/GCM 加密 apiKey**）/InMemory/OpenAI-compatible HttpURLConnection Provider（HTTPS-only、不跟随重定向、禁网零请求、错误码可读、401 detail 修复） |
| 设置页 | 「SAG 增强 / LLM」骨架：baseUrl/model/password(掩码)/timeout 1–120s、保存/测试/清除（二次确认）、SAG/问答/禁网三开关、隐私说明、无配置提示；明文 key 不进 UiState/日志/saved state |
| 备份 | **v3**：新增 events/entities/event_entities/sag_vectors(base64)/extraction_jobs 五个 entry；v1/v2 可读；导出闭包 + 导入写库前校验 + 单事务写库；**不含 chat/query_trace/event_fts/entity_fts** |
| JVM 测试 | **328 tests / 0 failed**（debug；既有 249 + 新增 79）；release unit **309 / 0** |
| 真机 instrumentation | **57 / 57 PASS**（非 UI 全量：迁移/DAO/备份 v3/Keystore/抽取/既有测试）；Compose UI 2 类在 K40 上挂起（见 §5，B5 曾在 PJZ110 通过，B8 未改其业务） |
| Lint / 构建 | lint **0 errors / 31 warnings**；assembleDebug/Release 绿；release APK **49,152,276 B**（+83.8KB vs B7，serialization-json+新代码） |
| 真机迁移 | K40 真机 v3→v6 链式迁移成功：user_version=6、8 新表齐全、foreign_key_check 空（详见 §4） |
| strict 依赖校验 | verification-metadata 重新生成（616 components，新增 serialization-json），strict 门禁通过 |

**未配置 LLM 时的零回归**：现有 JVM 测试全数保留并通过（B7 报告时点 243，B5/B6 后基线 249；新增 79 → 328）；非 UI instrumentation 既有 35 例 + 新增 22 例全部通过；检索/导入/备份 v2 行为未改。LLM/SAG 仅新增开关与配置，不接入抽取/检索/问答主链路（B9+）。

---

## 2. 逐模块交付

### 2.1 数据层（schema v6）
- **扩展表**：
  - `event`：新增 `title/summary/content/language/status/extractorVersion/extractionVersion/modelId/promptVersion/active/supersededBy/createdAt`（保留 `canonicalText/confidence`）；索引 `chunkId`，唯一 `(chunkId, extractionVersion)`。
  - `entity`：新增 `name/description/createdAt`（保留 `normalizedName` unique、`entityType` nullable）。
  - `event_entity`：新增 `weight`（默认 1.0）、`description`（保留 role）。
- **新表**：`sag_vector`（EVENT/ENTITY 多态 owner、role、spaceId、dimensions、blob、norm）、`event_fts`/`entity_fts`（FTS4，B9 写入/可重建）、`extraction_job`（chunk↔document 双 FK cascade、claim/retry/回收状态）、`chat_conversation`/`chat_message`/`message_citation`、`query_trace`（P0 必录/仅保留最近 50 条，不含正文）。
- **DAO**：`EventDao`/`EntityDao`/`EventEntityDao`/`SagVectorDao`/`ExtractionJobDao`/`ChatDao`/`QueryTraceDao`（含 FTS 读写、supersede、抽取队列 claim/retry、备份需要的 `deleteAll/page`）。
- **迁移**：`MIGRATION_5_6` 38 条 DDL（无网络/重嵌入）；v5 历史重复 `(chunkId, extractionVersion='')` 不改语义，非最新行改写 `legacy-v5-<id>`（additive、不丢数据）；迁移后 `PRAGMA foreign_key_check` 自检；6.json 与迁移逐表核对（列/类型/default/索引/FK/FTS）。
- **状态枚举**：`EventStatus`/`ExtractionJobStatus`/`SagOwnerType`/`ChatRole`（value/fromValue/ACTIVE/TERMINAL）。
- **SAG 空间/角色契约（review 补）**：新增 `core/sag/SagVectorContract.kt`：`SagVectorRole {event_content, entity_description}` + `SagVectorSpace.spaceIdFor/requireMatches`；备份导入校验 sag role ∈ 两类；测试 fixture 已改用正式角色（不再使用 event_title/event_summary）。B9/B10 的写入/查询必须走该契约。

### 2.2 抽取契约（`core/extract`）
- `EntityType`：time/location/person/organization/group/subject/work/product/action/metric/tags（wire + 中文 label + fromWire，大小写兼容）。
- `ExtractedEvent(title,summary,content,language,entities,isValid)`、`ExtractedEntity(type,name,description)`、`ExtractionInput(chunkId,content,sourceTitle,previousContext?,entityTypes)`、`ExtractionResult.Success/Failure(code,message)`。
- `EventEntityExtractor.extract`（suspend）；`ExtractionPrompts.VERSION="v1"`：中文指令、**单 chunk 单 event**、忠实性、11 类、禁止 `events/children`、严格 JSON schema；**不照搬参考 0.14.0 的多事项/children 语义**。
- `ExtractionJson.parse`：空/坏 JSON/缺字段/类型错/未知实体类型 → Failure（不抛异常），容忍 ```json 围栏，`isValid=false` 仍 Success；`MockEventEntityExtractor` 记录 calls/inputs。

### 2.3 LLM 基础（`core/llm`）
- `LlmConfig(baseUrl, model, apiKey, timeoutSeconds=30, maxTokens=256)`：`toString` 已脱敏。
- `LlmTestResult.Success(latencyMs, model)/Failure(code,message)`，11 个稳定 `LLM_*` 错误码。
- `LlmProvider.testConnection`；`OpenAiCompatibleLlmProvider`：禁网 `LLM_OFFLINE` 零请求 → HTTPS-only（拒绝 userinfo、不跟随重定向）→ 最小 POST `/chat/completions`（`max_tokens=1`、ping）→ 超时/错误映射（401/403 AUTH、429 RATE_LIMITED、5xx/其它 HTTP、超时/IO/PARSE）→ serialization-json 解析 choices；**不记录 key/正文**。
- `KeystoreLlmConfigStore`：AndroidKeyStore **AES-256/GCM** 加密 apiKey；SharedPreferences 仅存 baseUrl/model/timeout/maxTokens + iv/ciphertext；API24 兼容；解密失败 `LlmStoreException` 可读、`maskedKey` 返回 null 不崩。
- `LlmPreferences`：SAG/问答/**networkAllowed 默认 false**（禁网默认）；`NetworkPolicy.from(context)` 实时读取。
- **JVM 联调 bugfix**：JDK `HttpURLConnection.setFixedLengthStreamingMode` 在 401 时丢弃 errorStream → 移除该优化，401 错误包含服务端 detail；API 未变。

### 2.4 设置页（B8 骨架）
- `SettingsUiState`：llm(掩码)/llmTesting/llmTestMessage/llmError/sagEnabled/answerEnabled/networkDisabled；Snapshot/refresh/loadSnapshot 从 store/prefs 读取（IO）。
- 动作：save（空 key 保留旧 key、错误脱敏）、testConnection（Provider 强制禁网策略、成功显示延迟/模型、AUTH/HTTPS/RATE_LIMITED/TIMEOUT/NETWORK hint、`llmTesting` finally 复位）、clear（二次确认）、三开关持久化。
- UI：密码 `PasswordVisualTransformation` + `remember`（不落 saved state）、1–120s 超时、清除二次确认、隐私说明、未配置提示；明文 key 不进 UiState/日志/错误/备份。

### 2.5 备份 v3（骨架）
- `FORMAT_VERSION=3`，v1/v2 可读；新增 entry（JSONL）：`events.jsonl`/`entities.jsonl`/`event_entities.jsonl`/`sag_vectors.jsonl`（blob base64）/`extraction_jobs.jsonl`；meta 增加 `counts`。
- 导出闭包：event ← 闭包 chunk；entity/event_entity ← 被引用；sag_vector ← owner 在包内 + 空间受支持/已注册/维度一致；extraction_job ← 闭包 chunk；`page(200)` 分页；空间 meta = chunk 向量空间 ∪ SAG 空间（B7 supported 过滤）；B7 旧空间/降级规则保留。
- 导入：写库前全量解析 + `BackupClosure` v3 引用校验（event.chunkId、event_entity 两端、SAG 多态 owner、job chunk/document、entity normalizedName/event 版本唯一）→ 快照 → 单事务清空/按 FK 顺序写入；v2/v1 缺新 entry 视为空；坏包原库不变；`dropIncompatibleGpuVectors` 同步丢弃对应 SAG 向量。
- **排除项**：chat/query_trace（P0 隐私）、event/entity FTS（派生，B9 重建）、`chunk_entity`（Phase 2 占位）。
- 已知风险（见 §5）：chat 旧引用悬空（B11/B12 处理）、导入全量 JSONL 内存（B9 后可落盘）、FTS 需 B9 重建。

---

## 3. 验证证据

### 3.1 静态/构建/测试
```text
./gradlew :app:compileDebugKotlin :app:compileReleaseKotlin :app:compileDebugAndroidTestKotlin \
          :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug \
          :app:assembleDebug :app:assembleRelease
BUILD SUCCESSFUL
JVM debug = 328 tests / 0 failed；release unit = 309 tests / 0 failed
lint = 0 errors / 31 warnings
release APK = 49,152,276 B
verification-metadata = 616 components（strict 门禁通过）
schema 6.json = version 6 / identityHash 5bd38b67... / 21 entities
```
新增 JVM 用例（79）：LLM 32（17 config/8 network/7 TLS mock）、抽取 21、备份契约 18、Robolectric 5、SAG role/space 契约 2、NetworkPolicy 等；instrumentation 新增 22（MigrationV5ToV6 2、B8Dao 9、BackupV3RoundTrip 7、Keystore 4）。

### 3.2 真机（K40，`6D8H6TXSDYXC85PZ`，Android 16）
- **非 UI 全量**：`am instrument -e notClass ComposeUiSmokeTest,ComposeRecreateSmokeTest` → **OK (57 tests)**（26.6s）。
- **迁移**：真实 v3 DB → 安装 v6 debug → 启动后 `user_version=6`，8 张新表全部创建，`foreign_key_check` 空；instrumentation `MigrationV5ToV6Test` 2 例通过（v5→v6 数据保留/默认值/索引/FK/唯一索引）。
- **Compose UI**：`ComposeUiSmokeTest`/`ComposeRecreateSmokeTest` 在 K40 上卡在 Activity 启动（WindowManager/BLASTSyncEngine；device 锁屏/显示栈问题）；B5 已在 PJZ110 通过，B8 未改其业务。已保留测试代码（未 @Ignore），B12 真机矩阵需在 PJZ110/解锁设备复跑。
- **设备恢复**：debug APK + 原 K40 DB（v3，待下次启动迁移）+ 440M 模型；未留测试数据。

### 3.3 未配置 LLM 零回归
- 现有 249 JVM 全保留（新增 79 后 328）；
- 既有 35 instrumentation + 新增 22（非 UI）全部通过；
- 未配置 LLM 时 Settings 只多出配置区，不触发网络；检索/导入/备份 v2 主链路无改动。

---

## 4. 计划偏差与已知限制

| # | 事项 | 说明/处置 |
|---|---|---|
| 1 | B8 备份 v3 为“骨架” | 新表导出/导入 + v2 兼容已实现；**event/entity FTS 不导出/导入后为空**（B9 重建）；`chunk_entity` 不在范围；chat/query_trace 明确排除 |
| 2 | Compose UI 测试 | K40 显示/锁屏栈导致 Activity 启动挂起；非 UI 57/57 通过；B5 在 PJZ110 已通过。建议 B12 在 PJZ110 复跑；本报告如实标注，不宣称全量 instrumentation 绿 |
| 3 | chat 引用悬空风险 | 备份导入不清理已有 chat/query_trace；旧 citation 可能指向新库同号 chunk。B8 无 chat 数据，风险实际为 0；B11/B12 需“导入时失效/清理 chat” |
| 4 | 导入内存 | v3 JSONL 全量解析 + base64；B8 量级 OK，B9 大数据后建议落盘临时文件两遍扫描 |
| 8 | 老文档 NOT_INDEXED | B8 只落 `extraction_job` 状态枚举/表，**未给 v5 老文档回填 document 级 NOT_INDEXED**（v5 无 event 数据，无实际遗留用户影响）；B9 导入/补抽取时创建 job 并显式标记，B8 报告如实披露 |
| 9 | 约束缺口 | event 无 `(chunkId,active)` 查询索引、event_entity 无 `(eventId,entityId)` 唯一、extraction_job 无每 chunk 唯一约束；B8 不写业务数据，B9 落地抽取写入时需补 schema v7 或应用层事务保证（已列入 B9 交接） |
| 10 | B8-4 范围 | LLM Provider 仅实现 `testConnection`（最小请求）；流式/重试/限流/生成留 B11，报告不再宣称完整 LLM 客户端 |
| 11 | query_trace/备份体积 | `query_trace` 仅建表+DAO，trace 写入在 B9/B10；备份 v3 体积/分卷上限未定（B9 后按真实数据评估） |
| 12 | Compose harness | B8 将 recreate 测试从 createEmptyComposeRule 改为 createAndroidComposeRule；K40 仍挂起（B5 于 PJZ110 通过），B12 需在 PJZ110/解锁设备复跑 UI 门禁 |
| 5 | 事件语言 | Prompt v1 用中文指令；event/entity 输出源语言（PRD §6.2）；B9 抽检时验证中文质量 |
| 6 | LLM 配置测试连接 | B8 只做连通性（最小 chat 请求）；生成/流式在 B11；禁网默认，UI 测试连接需先保存配置并打开联网开关 |
| 7 | UI 明文 key | 使用 `remember` 不落 saved state；旋转后需重输（安全优先）；B11 可评估更友好的安全输入方案 |

---

## 5. 独立审查（Standards + Spec）与处置

### 5.1 Standards 轴
| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| S1 | 硬性 | 备份导入只在 `extractionVersion` 非空时查重；空串同样受 DB 唯一索引约束，`EventDao.upsertAll(REPLACE)` 会静默覆盖第二条 event | **已修**：`BackupClosure.validateSagClosure` 无条件查重（含空串）；补说明 |
| S2 | 硬性 | B8 空间/角色契约缺失（role 自由字符串；无 spaceId 指纹/查询校验），Roadmap B8 交付未落地且报告未披露 | **已修**：新增 `core/sag/SagVectorContract.kt`（2 role + spaceIdFor/requireMatches）并接入备份 role 校验；B9/B10 交接明确；报告 §2.1 记录 |
| S3 | 判断性 | `maskSecret` 对 9–12 字符 key 仍露 7 位 | **已修**：`length < 16` 全遮（`****`） |
| S4 | 判断性 | 备份导入未校验 `entityType` 属 11 类 | **已修**：`KnowledgeBackup` 用 `EntityType.fromWire` 严格校验 |
| S5 | 判断性 | Compose recreate 测试在 K40 仍挂起；建议 waitUntil/Timeout/独立 shard/PJZ110 复跑 | **已记录**：测试已改用 createAndroidComposeRule；K40 显示栈问题非 B8 逻辑，B12 在 PJZ110/解锁设备复跑；非 UI 57/57 |
| S6 | — | 6.json/MIGRATION、Keystore、禁网、备份 v3、抽取契约、401 修复等未发现问题 | 保留 |

### 5.2 Spec 轴
| 项 | 原结论 | 处置 |
|---|---|---|
| schema/DAO/migration | 覆盖 | 确认；B9 需补 event/entity FTS 重建 |
| 抽取契约 | 覆盖 | 确认（11 类/单 event/Prompt v1） |
| LLM 基础 | partial（仅 testConnection/无流式） | **已披露**为 B8 偏差（B11 补流式/重试/限流/生成） |
| 设置骨架 | 覆盖 | 确认；全局隐私文案已修正为“默认离线、显式开启才联网” |
| 备份 v3 | 覆盖（v2 兼容、排除 chat/trace/FTS） | 确认；体积/分卷、chat 引用悬空、导入内存列入 B9/B11 风险 |
| B8-7 空间/角色 | missing | **已修**：契约+备份校验+B9 交接 |
| NOT_INDEXED 回填 | partial | **如实披露**：B9 在导入/补抽取创建 job 并标记，B8 无 v5 event 数据 |
| 约束缺口 | partial | **披露**：B9 落地写入时补 schema v7/事务约束（event_entity 唯一、job 每 chunk、active 索引） |
| Compose UI 门禁 | partial | **披露**：K40 显示栈挂起；B12 在 PJZ110/解锁设备复跑；B8 数据/LLM 验收不依赖 UI |
| 结论 | — | 无 B9–B12 功能越界；B8 验收以数据/LLM/备份/非 UI 57/57 为准，UI 遗留已记录 |

---

## 6. 文件清单（B8）

**数据层**：`data/db/{Entities.kt,Daos.kt,AppDatabase.kt,StatusEnums.kt}`、`app/schemas/com.zhibox.memory.data.db.AppDatabase/6.json`
**抽取**：`core/extract/{EntityType,ExtractionModels,ExtractionPrompts,ExtractionJson,EventEntityExtractor,MockEventEntityExtractor}.kt`
**SAG 契约**：`core/sag/SagVectorContract.kt`（role/space 指纹；B9/B10 共用）
**LLM**：`core/llm/{LlmConfig,LlmTestResult,LlmProvider,NetworkPolicy,LlmPreferences,LlmConfigStore,KeystoreLlmConfigStore,InMemoryLlmConfigStore,OpenAiCompatibleLlmProvider}.kt`
**备份**：`core/backup/{BackupValidator,BackupClosure,KnowledgeBackup}.kt`
**UI**：`ui/{SettingsViewModel,SettingsScreen}.kt`、`res/values/strings.xml`
**构建**：`gradle/libs.versions.toml`（serialization-json 1.8.1）、`app/build.gradle.kts`、`gradle/verification-metadata.xml`（616 components）
**测试**：`test/core/llm/*`、`test/core/extract/ExtractionContractTest.kt`、`test/core/backup/BackupV3ContractTest.kt`、`androidTest/data/db/{MigrationV5ToV6Test,B8DaoRoundTripTest}.kt`、`androidTest/core/backup/BackupV3RoundTripTest.kt`、`androidTest/core/llm/KeystoreLlmConfigStoreTest.kt`，以及既有 Migration builder 的 `MIGRATION_5_6` 适配

*报告生成：2026-10-18 · B8 代码/测试/文档随本次提交；设备已恢复 debug/v3 DB/模型。*
