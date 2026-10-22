# B11 设计

## 分层
- `core/llm/LlmStreamingProvider`：SSE 流式契约（Delta/Completed/Failed），OpenAiCompatible 实现；禁网零请求，取消关闭连接，不写正文/key 日志。
- `core/chat/ChatContracts`：ChatEvidence（S1..Sn）、AnswerRequest、AnswerEvent、AnswerGenerator；纯数据，不依赖 UI/DB。
- `core/chat/ChatPrompt` + `OpenAiAnswerGenerator`：本轮 query + ≤10 证据（每证据 ≤1200 字），系统提示强制只使用证据/引用/资料不足拒答；生成后强制 CitationValidator。
- `core/chat/CitationValidator`：纯逻辑；非法引用替换标记；有效引用去重/排序；unverified 标示。
- `core/chat/ChatRepository` + `RoomChatRepository`：现有 ChatDao；会话/消息/引用；sourceScope 以逗号分隔来源 id 存储；无正文重复存储。
- `ui/ChatViewModel`：SagRetriever 取证据 → AnswerGenerator 流 → 引用校验 → Room 持久化；单轮、不注入历史；停止取消 Flow；重试同 query/scope；QA trace mode=qa（hash/timing）。
- `ui/ChatScreen`：会话列表/新建、消息、引用 chip → EvidenceScreen、停止/重试、错误/资料不足/未校验警示、离线/未配置提示、首次联网 consent。
- Settings：问答生成开关 + 隐私说明/首用同意；禁网/LLM 配置沿用。

## 关键不变量
- 无证据不调用 LLM；失败不持久化半成品最终答案；
- 任何 assistant 文本入库前经过 CitationValidator；
- Prompt/日志/trace 不包含 API key；不发送文件路径/库全文；
- P0 会话历史不进入下一问请求。

## 测试
JVM：CitationValidator、ChatPrompt、AnswerGenerator fake、SSE parser（Mock TLS/本地 server）。
Instrumentation：ChatRepository、ChatViewModel fake generator、Compose Chat（PJZ110）。
真机：真实 LLM 20 条问答 + 50 条抽样支持率；引用有效性 100%。
