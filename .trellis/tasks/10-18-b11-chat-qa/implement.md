# B11 实施批次

1. Lead：契约（LlmStreamingProvider/AnswerEvent/Evidence/CitationValidator/ChatRepository）——已落。
2. core-fixer（task-52）：SSE provider + Prompt + OpenAiAnswerGenerator + 测试。
3. ui-fixer（task-53）：ChatViewModel/ChatScreen/导航/Settings 问答开关/隐私 consent/strings。
4. data-reviewer（task-54）：CitationValidator/JVM、ChatRepository instrumentation、伪流式生成器测试；后续异常矩阵/评测。
5. Lead：集成接线、冲突解决、B11 报告、ROADMAP/PRD。
6. ui-reviewer/PJZ110：Compose 真机 5 类用例 + 小屏滚动/引用/停止。
7. 真实 LLM：20 条问答 + 50 条抽样支持率（需 K40 模型/证据与有效 LLM key），记录限制。
8. 独立 Standards/Spec 审查，修复后门禁/commit/CI。
