# B13 P1 多轮问答首版报告

- **日期**：2026-10-20
- **状态**：**P1 多轮上下文首版完成**（post-v0.1.1 RC；不包含在已构建的 v0.1.1 签名 APK 中）
- **范围**：在 B11 单轮带引用问答上增加会话历史上下文；**不含**工具调用/Agent 编排（P1 后续）。

## 1. 实现

- `AnswerRequest.history: List<ChatTurn>`（默认空，保持单轮兼容）。
- `ChatPrompt`：
  - 系统规则第 7 条：历史只用于理解指代/上下文，**不是证据**；不得引用历史中的 `[S#]`；
  - 历史最多 6 条 USER/ASSISTANT（默认 3 轮），每条最多 600 字；
  - 历史中的旧引用 token 被移除，避免模型把上一轮证据当本轮证据；
  - 提示词版本升级为 `b13-multiturn-v1`。
- `OpenAiAnswerGenerator`：透传 history；证据仍只来自本轮检索，`CitationValidator` 仍只校验本轮证据。
- `ChatViewModel`：每轮发送前从持久化消息构造历史（retry 时剔除当前 user 重复）；trace `timingJson` 增加 `historyTurns`。
- Chat 输入提示更新为“支持多轮上下文；只基于本轮检索证据回答”。

## 2. 引用继承语义

- 历史会话中的引用 chips 仍由 Room 持久化并在 UI 中显示（“可回看历史引用”）；
- 生成本轮答案时**不复用旧引用编号**，只允许引用本轮证据 `[S1]..[Sn]`；避免旧编号错位/伪造证据。
- 旧 assistant 文本进入上下文前会剥离 `[S#]`，事实依据仍只来自本轮证据。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug | **524 / 0 failed**（+4 多轮测试） |
| JVM release | **505 / 0 failed** |
| Android Lint | **0 errors / 41 warnings** |
| `ChatPromptTest`/`AnswerGeneratorTest`/`OpenAiAnswerGeneratorTest` | 36 tests pass |
| K60U 非 UI instrumentation | **31/31**（ChatRepository/SagRetriever/Graph/抽取并发等） |
| K40 Compose UI | 本轮设备不在线，未重跑；ChatUiSmoke 仅字符串资源变更 |

## 4. 限制/未做

- 未做真实 LLM 多轮质量评测（需设备/API key 窗口）。
- 未做工具调用、Agent 编排、threads 管理以外的多轮高级能力。
- 历史预算为固定 6 条/600 字；后续可按 token 预算/模型上下文窗口调整。
- 本轮改动不包含在 `dist/zhibox-0.1.1-...apk` 中；如需发布需重新构建下一版本（建议 v0.1.2）。

## 5. 文件

- `core/chat/ChatContracts.kt`
- `core/chat/ChatPrompt.kt`
- `core/chat/OpenAiAnswerGenerator.kt`
- `ui/ChatViewModel.kt`
- `res/values/strings.xml`
- 测试：`ChatPromptTest.kt`、`AnswerGeneratorTest.kt`
