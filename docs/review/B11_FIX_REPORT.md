# B11 实施报告（LLM 上下文选择、带引用问答与 Chat/隐私）

- **日期**：2026-10-18（进行中）
- **依据**：ROADMAP §6 B11、PRD FR-06/FR-08/FR-09/§11.2/§11.3
- **结论**：**代码/自动门禁、Chat Compose/DB 真机测试通过；真实 20 条问答 + 50 条支持率主门禁尚未执行（当前 PJZ110 无 LLM 配置、K40 未连接），且引用内容级支持校验仍为部分；B11 暂标部分完成，不宣称验收通过。**

## 1. 交付

### 1.1 流式生成与隐私
- `core/llm/LlmStreamingProvider.kt`：`Flow<LlmStreamEvent>` 契约（Delta/Completed/Failed）。
- `OpenAiCompatibleLlmProvider`：OpenAI-compatible SSE（`stream=true`、`data:`/`[DONE]`、429/401/5xx/超时/坏 JSON/EOF）；禁网零请求、HTTPS-only、opencode session、取消即 `disconnect()`（`channelFlow + awaitClose`）；不写 key/正文日志。
- `LlmPreferences`：新增「问答生成」独立开关 + 首次联网同意；默认 false；SAG/联网互不污染。

### 1.2 带引用问答核心
- `core/chat/ChatContracts.kt`：ChatEvidence S1..Sn、AnswerRequest、AnswerEvent、AnswerGenerator；单轮、无历史注入。
- `core/chat/ChatPrompt.kt`：只含本轮 query + ≤10 证据（每条 ≤1200 字）；强制 `[S#]` 引用、只信证据、固定拒答「资料不足，无法回答」。
- `core/chat/OpenAiAnswerGenerator.kt`：无证据 → `NO_EVIDENCE` 零网络；真实流式；Completed 前强制 `CitationValidator`。
- `core/chat/CitationValidator.kt`：引用 ⊆ 证据；非法 token 替换 `[未通过证据校验]`；无有效引用 `unverified`；去重/顺序/rank。
- `core/chat/ChatRepository.kt`：会话/消息/引用 Room 持久化；sourceScope 仅 id；删除级联；无正文重复存储。

### 1.3 Chat UI / 导航 / 设置
- `ui/ChatViewModel.kt`：懒初始化 SagRetriever（真实 LlmQueryEntityExtractor + LlmSagContextSelector）、OpenAiAnswerGenerator、RoomChatRepository；默认 multi（LLM 就绪）/fast；流式 Delta、停止取消、重试、失败可读；Completed 才持久化 assistant+citations；QA trace mode="qa"（hash/timing，无正文）。
- `ui/ChatScreen.kt`：会话列表/新建/切换、消息、引用 chips→EvidenceScreen、停止/重试、资料不足/未校验/非法引用警示、离线/未配置提示、首次联网 consent。
- `MainActivity`：`chat` 路由 + 首页「问答」入口。
- Settings：问答生成开关、首次联网隐私说明/同意；strings。

### 1.4 评测
- `androidTest/.../B11QaEvalTest.kt`（`-e b11Eval 1`）：20 条（16 有证据：2/3/4 跳 6/5/5；4 KB 外无证据）；直接使用 B10 证据快照调真实生成器，不跑检索/模型。
- `scripts/eval/b11_qa_manifest.json`、`score_b11_qa.py`：citation validity、NO_EVIDENCE 拒答率、successRate、p50/p95、refusedByCode、可选人工/LLM support。
- `B11SupportEvalTest` + `b11_support_manifest.json`/`score_b11_support.py`：50 条有证据抽样（2/3/4 跳 18/16/16），输出 support 模板与 `fullyCitedRate/unreferencedCases`；真实 supportRate 需填模板。
- `CitationValidator` 增加规则版逐句 coverage：`unreferencedSentenceCount` / `fullyCited`（按句末/换行统计无引用断言；只警示不删正文）。
- `B11PrivacyPreferencesTest`、`LlmStreamingMockHttpsTest`（真实 TLS mock SSE 异常矩阵）。

## 2. 验证证据（当前工作区）

| 门禁 | 结果 |
|---|---|
| JVM debug / release | **504/0 / 485/0** |
| lint / assemble | **0 errors / 34 warnings；BUILD SUCCESSFUL** |
| 真机 instrumentation（PJZ110） | **21/21**（ChatRepository 9 + Compose B10 5 + Chat 7） |
| B11 Qa harness gate-closed（PJZ110） | 1/1 run；4/4 NO_EVIDENCE 拒答正确；16 条 KEY_MISSING 拒（**质量指标不可用**） |
| B11 Support 50 条 harness gate-closed | 1/1 run；50/50 KEY_MISSING 拒；`supportRate=null`（需真实 key + 人工/LLM judge） |
| SSE/引用/隐私 JVM | 61 chat tests + 18 privacy/TLS mock tests，0 failures |

证据：`docs/review/evidence/B11-compose-pjz110.txt`、`B11-chatrepository-pjz110.txt`、`B11-qa-gateclosed-{answers,score}.json`。

### 1.5 审查修复（2026-10-18）

- **P0 问答/SAG 开关耦合**：ChatViewModel 仅当 `llmReady && SagAvailability.isAvailable` 才选 multi；只开问答/关 SAG 时自动走 fast/vector 证据，不再被 retriever refused。
- **P0 sourceScope + 模式 UI**：Chat 增加来源多选（active sources，空=全库），`SagQuery.sourceIds` 真实传入；检索模式 chips（自动/混合/fast/multi）；契约 sourceScope 统一为 `List<String>`（与数据层 source id 一致）。
- **P1 引用重载错位**：`CitationValidator.rank` 改为原始 token 编号-1（S2→1），重开会话按 rank 重建 S# 不再错位。
- **P1 consent 目标 host**：首次联网确认显示已配置 baseUrl 的 host（不含 key/path）。
- **P2 trace**：QA trace timingJson 增加 citationValid/invalidCitations（仍无正文）。
- 独立审查指出且保留的缺口：引用内容级支持/未引用断言检测未做（当前只保证 ID 子集与 unverified）；真实 20 QA/50 支持率未跑；TLS mock 非 chunked。

### 2.1 独立审查（Standards + Spec）处置

| 发现 | 处置 |
|---|---|
| P0 问答/SAG 开关耦合：只开问答时仍选 multi → refused | **已修**：Chat 仅在 `llmReady && SagAvailability.isAvailable` 时选 multi，否则 fast/vector |
| P0 sourceScope 无 UI/未传 SagQuery；Long/String 类型不一致 | **已修**：来源 chips（active source，空=全库）、`sourceIds` 真实传入、契约改 `List<String>` |
| P0 模式选择缺失 | **已修**：Chat 增加自动/混合/SAG 快速/SAG 精排 chips |
| P1 引用 rank 重载错位 | **已修**：Validator rank 改为原始 token 编号-1；重开会话 S# 不再错位 |
| P2 重试绕过 llmReady/consent、consent 后 input 不清 | **已修**：retry 复用同门禁、consent 后清 input、重试不重复 user 消息 |
| P1 consent 无目标 host | **已修**：首次联网确认显示 baseUrl host（不含 key/path） |
| P1 主线程 Keystore 解密 | **已修**：`store.load()` 移入 `Dispatchers.IO` |
| P1 取消早于连接 / 晚到连接 | **已修**：producer 打开前后检查 disconnected，晚到连接关闭 |
| P2 拒答被标 unverified | **已修**：模型输出固定拒答文案 → 按 NO_EVIDENCE Failed 语义处理，不持久化警示答案 |
| 内容级引用支持/未引用断言检测缺失 | **保留限制**：当前只保证 ID 子集、非法替换、unverified；真实支持率需人工/LLM judge |
| 真实 20 QA/50 支持率、TLS chunked 时序 | **未完成**：需设备 key/真实网络；TLS mock 为整包 |

## 3. 未完成/门禁阻塞

1. **真实 20 条问答 + 50 条支持率未跑**：当前连接的 PJZ110 无 LLM 配置；K40（有 key + 模型）未连接。需要用户在任一设备保存有效 key（并开启问答/联网/consent），或在 K40 运行 harness。支持 harness 已就绪但 gate-closed 只有 KEY_MISSING。
2. 引用有效性/支持率/拒答的真实质量结论不能由 gate-closed 结果替代；当前只验证了 4 条无证据题零网络拒答 100%。
3. sourceScope 有数据契约/持久化，但 Chat UI 暂无来源多选入口（空=全库）；`ChatEvidence.documentId/sourceId` 因契约 Long 而数据层 String，用 hash 占位（不参与引用/持久化）。
4. 真实 TLS mock 是整包响应，非 chunked 逐块/中途断流；真实 provider E2E 需真机网络验证。
5. Chat 停止只保留 partial 展示、不落库；重试已改为复用同 query/会话，不重复追加 user 消息。

## 4. 下一步

1. 连接 K40（或给 PJZ110 配置 key）后运行 `B11QaEvalTest` 20 条真实问答并计分；再抽 50 条支持率（人工/LLM judge），补 B11 报告。
2. 独立 Standards/Spec 审查 findings 修复。
3. B11 UI 设置/隐私端到端与真实网络错误路径复核。
4. ROADMAP/PRD 更新为 B11 完成（仅当主门禁通过）。
