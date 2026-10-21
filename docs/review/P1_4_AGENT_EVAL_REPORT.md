# P1.4 报告：Agent/工具编排 v1 + 质量评测骨架

- **日期**：2026-10-21
- **状态**：Agent 工具编排代码/UI 完成（无真实 LLM key，未跑真实 Agent 质量）；质量评测离线骨架完成，真实评测受阻于 key/人工抽检
- **原则**：不内置任意 Shell/网络/写工具；最终引用仍由 AnswerGenerator + CitationValidator 强制；mock 不得冒充真实质量。

## 1. Agent/工具编排 v1

- `core/agent`（纯 JVM）：
  - 严格 planner JSON：`search_knowledge` / `lookup_entities` / `final`；工具白名单、input≤200、最多 2 次工具；
  - 未配置/坏 JSON/未知工具/provider 失败/工具异常/超预算 → 确定性 fallback（不阻断普通问答）；evidence 只来自工具、按 chunkId 去重；不生成答案/不日志/不持久化。
  - prompt version `b16-agent-tools-v1`；取消原样传播。
- Chat UI：
  - 「Agent 工具」开关默认关、仅 LLM 就绪可用；工具步骤卡（检索/实体/规划/回退原因），不展示 observation 正文；
  - 普通检索证据优先，Agent 工具 evidence 按 chunkId 去重合并、重新编号；最终仍走现有 CitationValidator，引用只来自本轮 evidence；
  - Agent 关闭/未就绪不调用 planner；工具异常/回退继续普通链路。

## 2. 质量评测（B17 骨架）

- `scripts/eval/agent_eval_cases.json` 20 条（route/fallback/无证据拒答/坏 JSON/工具异常/多轮等，无答案正文）；
- 离线 harness `AgentEvalHarnessTest`：20/20 route、11/11 fallback、20/20 citation contract；明确 **不是真实质量**；
- 真实协议 `P1_4_QUALITY_EVAL_PLAN.md`：key 只在 Keystore，20–50 题、citation 100%、无证据拒答 100%、人工 support≥90%、延迟；结果 schema/scorer；无 key/人工不足 `QUALITY_CLAIM_ALLOWED=False`。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **806 / 0**、**787 / 0** |
| lint | 0 errors / 57 warnings, 2 hints |
| Agent core | 30 tests；全量 JVM 绿 |
| B17 offline harness | 20/20 route、11/11 fallback、20/20 citation；contract_only=true |
| K60U UI | Agent 开关/提示可见；无 LLM key 时不可用，普通链路不受影响 |
| 真实 LLM 20 题（K60U） | **13 回答 / 7 超时（35%）**；保守 support 12+1 partial（已答 92.3%，全样本 60%）；citation 13/13；路由仅 1–2/20；两位 AI 评审、无人类双评 → 质量声明禁止 |
| 真实 LLM（PJZ110，早期） | 2/8 样本；回答有引用/工具成功；仅作早期观察 |
| 证据 | `P1.4-agent-toggle-k60u.*`、`P1.4-real-agent-pjz110.txt`、`P1.4-eval20-k60u-results.json`、评审 A/B JSON、`P1_4_EVAL20_SUMMARY.md` |

## 4. 签名候选

- `dist/zhibox-0.1.2-5284e13-release-signed.apk`（69,524,581 bytes，SHA-256 `5a4080dde62f90817ed7042b25550d7e5ba725ec156f73b351d967095a0b938c`；未发布/tag）。

## 5. 阻塞/后续

- 真实 LLM key 已连接；K60U 已采集 **20 题**（13 回答/7 超时），PJZ110 仅早期 2/8；K60U 结果见 `P1_4_EVAL20_SUMMARY.md`。
- **人工抽检**：需 2 名人类评审按协议抽检 ≥max(10,20%)；当前仅两位 AI 评审，**不计入人工门禁**。
- 取得完整 20–50 题与人工结果后再决定是否发布；当前 `QUALITY_CLAIM_ALLOWED=false`。
- Agent 工具失败当前整体回退（不继续换工具）；真实路由质量需 B17 实测。
