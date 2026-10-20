# P1.4 计划：多轮 Agent/工具编排 + 质量评测

- **日期**：2026-10-21
- **状态**：开工（P1.3 系列完成）
- **目标**：在 B13 多轮问答/引用门禁上增加受控工具编排；同时建立可重复的质量评测，不以 mock 结果冒充真实质量。

## 1. B16 Agent/工具编排（v1）

### 工具（仅本地只读）
1. `search_knowledge(query=...)`：走现有 SagRetriever/Search，返回证据（chunk）；工具观察只为下一步规划，最终引用仍只来自本轮证据。
2. `lookup_entities(name=...)`：查 entity/alias/关联事件摘要，返回结构化观察（不直接当引用）。
3. 默认最多 **2 次工具调用 + 1 次最终回答**；不允许未注册工具、Shell/网络/写操作。

### 规划协议
- `LlmCompletionProvider.complete` + 固定 JSON schema：
  - `{"action":"search_knowledge","input":"..."}` / `{"action":"lookup_entities","input":"..."}` / `{"action":"final"}`
- 严格解析/长度/次数预算；坏 JSON/未配置/无 tool → **确定性回退**普通检索（不阻断问答）。
- 工具观察只进入下一轮规划；最终回答仍走 B11 `AnswerGenerator` + `CitationValidator`（引用只能来自本轮 evidence）。
- 不记录 query/工具结果正文到日志；trace 记工具名/次数/耗时/错误码，不记内容。

### UI
- Chat 增加「Agent 工具」开关（默认关）；开启时显示工具步骤（搜索/实体/最终），可停止；
- 证据/引用/拒答/验证警示沿用现有 UI；Agent 失败回退普通检索时明确提示。

### 门禁
- 纯 JVM：planner JSON/预算/回退/工具错误/无 key/未注册工具/循环上限/引用不变；
- 设备：真实 LLM key 窗口跑 Agent 典型用例；无 key 时仅离线回退与 mock 验证。

## 2. B17 质量评测

- 评测集：普通事实、多轮指代、工具路由（search/entity）、无证据拒答、错误工具、引用有效性；
- mock/脚本化结果用于回归（planner/tool 路由/引用），**不等同真实质量**；
- 真实评测需用户提供/配置 LLM key，跑固定 20–50 题 + 人工抽检支持率；记录模型/版本/日期/原始结果；
- 不以 mock 通过宣称质量达标；不做自动发布。

## 3. 状态

- B16 core/UI 完成；B17 离线评测骨架完成。
- 真实 Agent 质量评测阻塞：真实 LLM key 未配置、人工抽检 0 样本；`QUALITY_CLAIM_ALLOWED=false`。

## 4. 顺序

1. B16 core planner/tools + JVM tests（core agent 任务）。
2. B16 UI Chat 开关/步骤（ui 任务，core 契约冻结后）。
3. B17 eval harness/cases（data 任务）与真实 key 窗口；设备门禁。
4. 发布 smoke/tag 由用户决定。
