# P1.4 QA-only 20 题真实评测（K60U + 真实 LLM）

- **日期**：2026-10-21
- **设备/版本**：K60U 23078PND5G，Android 14，debug v0.1.2（versionCode 3，app build sha 未记录）
- **模型/提示词**：`deepseek-v4-flash`，`keyConfigured=true`，`promptVersion=b16-agent-tools-v1`
- **语料**：合成 corpus1–3（3 chunk，覆盖光合作用/上海/张三等）；**不可外推到真实知识库**
- **题集**：`scripts/eval/agent_qa_cases.json`（QA-only 20 题；不强制工具路由、expectedCitationIds 留空）
- **采集**：`AgentEvalDriverTest`（`-e suite qa`），含 run metadata；每题新会话、Agent 开启、单题 75s 上限
- **原始结果**：[P1.4-qa20-k60u-results.json](evidence/P1.4-qa20-k60u-results.json)
- **评审**：[Review A](evidence/P1.4-qa20-review-a.json)、[Review B](evidence/P1.4-qa20-review-b.json)（**两位独立 AI 评审，不是人类双评**）

## 1. 运行结果

| 指标 | 结果 |
|---|---|
| 总题数 | 20 |
| 有回答 | **18/20（90%）** |
| 超时 | **2/20（10%）**；约 75.16s / 75.29s |
| 回答延迟 | p50 **16.5s** / p90 **21.8s** / max **25.5s** |
| 有引用回答 | 18/18 |
| 引用评审 | A：17 valid / 1 warning；B：18 valid；保守 **17 valid / 1 warning** |
| 拒答用例 | #14 天气（库外）正确拒答；#10 “上海有哪些大学”有证据但超时并提示“资料不足”，B 判拒答错误 |

## 2. 评审 support 回填

| 评审 | support | partial | unsupported | not_applicable | 已答支持率 | 全样本 |
|---|---:|---:|---:|---:|---:|---:|
| A | 17 | 1 | 0 | 2 | 94.4% | 85% |
| B | 17 | 1 | 0 | 2 | 94.4% | 85% |
| **保守汇总** | **17** | **1** | **0** | **2** | **94.4%** | **85%** |

- 唯一 partial：#2「光合作用需要什么？」——回答“叶绿素吸收的光能”，语料有叶绿体/叶绿素，但未直接陈述“需要光能”，属合理推断。
- 无 unsupported；13 条 QA 事实题回答与 corpus1–3 一致。
- **可评估已答题 support 94.4%**；但 2 条超时不计 support，全样本 85%。

## 3. 质量门禁结论

**`QUALITY_CLAIM_ALLOWED = false`**

1. 只有 **2 位 AI 评审**，未完成人类双评/人工抽检（协议要求 ≥max(10,20%)）；
2. 2/20 超时（10%），其中 #10 有明确证据却超时；可用性仍需修复；
3. #12 引用覆盖被 A 判 1 条 warning（2 句未标注引用），B 判 valid，存在分歧；
4. 仅 20 题 + 3 chunk 合成语料；不能外推真实知识库；
5. 缺 app version/build sha、解码参数；模型名为自报。

**可报告**：QA-only 20 题真实运行下 **18 回答 / 2 超时，17 support + 1 partial（已答 94.4%）**；**不可宣称真实质量达标或发布**。

## 4. 与契约 20 题对比

| 套件 | 回答 | 超时 | support（保守） | 说明 |
|---|---:|---:|---:|---|
| 旧混合 20 题（QA + fallback 契约） | 13 | 7（35%） | 12 support + 1 partial | fallback 契约不可在真实 LLM 复现 |
| **QA-only 20 题** | **18** | **2（10%）** | **17 support + 1 partial** | 拆分后回答率/support 明显改善 |

## 5. 发现与下一步

- **套件拆分有效**：QA 与 fallback/故障注入契约分离后，回答率从 65%→90%，support（已答）94.4%。
- **剩余超时**：2 题仍以 75s 收场；#10 有证据但被当作“资料不足”，需排查 Agent/生成超时与错误分类，不能简单归因语料。
- **遥测口径**：10/18 有回答仍带 `agentFallbackReason`；部分回答无工具调用却有引用，需澄清 fallback/引用注入语义。
- **引用覆盖**：#12 有 2 句未标注引用告警；应由人工复核是否影响门禁。
- **下一步**：修超时/告警分类 → 补 app build sha/解码参数 → 安排 2 名人类评审抽检 ≥10 题并回填 support → 再决定发布。

## 6. 超时修复后的复跑（2026-10-21，K60U）

- 代码修复：Agent planner 单次 20s 超时、工具 20s 超时、最终答案生成 45s 超时；超时不再无限“生成中”，给出可重试错误。
- 复跑结果：[P1.4-qa20-timeoutfix-k60u-results.json](evidence/P1.4-qa20-timeoutfix-k60u-results.json)
- 结果：**17 回答 / 3 错误**（无 75s 超时；错误在 5–25s 内返回）；回答 p50 **13.7s** / max **20.3s**。
  - `qa-photosynthesis-energy`：`LLM_PARSE_ERROR`（未返回内容）；
  - `qa-shanghai-universities`：无证据拒答（证据可答，检索/路由仍有问题）；
  - `qa-weather-refusal`：无证据拒答（预期行为）。
- 结论：超时/挂起问题已修复；回答数较修复前的 18 略降（1 条 LLM_PARSE_ERROR + 1 条证据漏检），需继续修生成解析与检索召回；质量声明仍禁止（人类评审未完成）。

## 7. 空内容自动重试后的复跑

- 代码：生成失败码 `LLM_PARSE_ERROR` 自动重试一次（最多 2 次），重试期间显示“模型未返回内容，正在重试…”。
- 结果：[P1.4-qa20-parseretry-k60u-results.json](evidence/P1.4-qa20-parseretry-k60u-results.json)
- **17 回答 / 3 无证据拒答**，无 75s 超时；回答 p50 **16.7s** / max **29.4s**。
  - `qa-photosynthesis-energy`：重试后仍转为 `NO_EVIDENCE_REFUSAL`（未恢复回答）；
  - `qa-shanghai-universities`：无证据拒答（题面要求校名列表，语料仅“上海有很多大学”，拒答可解释；不再按检索漏检归因）；
  - `qa-weather-refusal`：无证据拒答（预期）。
- 结论：自动重试消除了超时，但未解决 #2 的空回答；仍需模型/提示词层排查；质量声明继续禁止（人类评审未完成）。
