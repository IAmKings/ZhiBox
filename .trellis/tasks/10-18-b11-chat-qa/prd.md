# B11 — LLM 上下文选择、带引用问答与 Chat/隐私

## 目标
在 B10 候选集上完成：真实 LLM contextual selector（复用现有 SagRetriever multi 路径）、单轮带引用问答、引用强制校验、Chat 会话/消息/引用持久化与 UI、source scope、禁网/首次联网确认/payload 最小化/密钥与日志脱敏、LLM 异常矩阵与 20 条真机问答/50 条抽样评测。

## P0 范围（固定）
- 单轮问答：每问只使用本轮 query + B10 证据，不注入历史消息；历史可查看/重开。
- 证据 → LLM 流式回答；答案内 `[S1]..[Sn]`；无证据直接拒答（不调用 LLM）。
- CitationValidator：引用 ⊆ 证据；非法 token 替换为 `[未通过证据校验]` 并在 UI 警示；无有效引用标记 unverified。
- 引用有效性 100%；抽样证据支持率 ≥90%；无答案问题不编造；断网/超时/429/5xx/坏 JSON 可读错误；停止/重试正确。
- 会话/消息/引用持久化；source scope 随会话/消息保存；不写正文日志/trace；密钥不入日志/备份。
- Chat UI：会话列表/新建、消息、引用 chip 可点回 Evidence、停止/重试、资料不足/校验警示、离线/未配置提示。
- Settings：问答生成独立开关；首次联网目标域/用途确认；禁网总开关沿用。

## 非目标
- 多轮上下文/工具/Agent（P1）；B12 图谱；本地 LLM；跨设备同步。

## 验收
JVM/Instrumentation/真机 Compose 门禁；mock SSE 异常矩阵；20 条真实问答 + 50 条支持率抽样；独立审查；B11 报告与 ROADMAP/PRD 更新。

## 依赖
B10 完成（SagRetriever 三模式/证据）；B8 LlmConfigStore/NetworkPolicy；现有 Room chat_conversation/chat_message/message_citation（v7，无需迁移）。
