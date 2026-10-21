# P1.4 真实 Agent 20 题评测汇总（K60U + 真实 LLM）

- **日期**：2026-10-21
- **设备/版本**：K60U 6D8H6TXSDYXC85PZ，debug v0.1.2（versionCode 3），Agent 开关开启
- **数据**：合成评测语料 corpus1–3（3 个 chunk，覆盖光合作用/上海/张三）；模型 440M
- **LLM**：用户已在设备配置并连通；**运行元数据（model / prompt_version / key_configured / 精确时间）缺失，不能作为可复现发布证据**
- **题集**：`scripts/eval/agent_eval_cases.json` 20 题（事实/多跳/多轮/工具路由/fallback/拒答/引用）
- **原始结果**：[P1.4-eval20-k60u-results.json](evidence/P1.4-eval20-k60u-results.json)
- **评审**：[Review A](evidence/P1.4-eval20-review-a.json)、[Review B](evidence/P1.4-eval20-review-b.json)（**两位独立 AI 评审，不是人类双评**）

## 1. 运行结果

| 指标 | 结果 |
|---|---|
| 总题数 | 20 |
| 有回答 | **13** |
| 超时/无回答 | **7（35%）**，均约 75.16–75.28s，failureCode=`TIMEOUT` |
| 回答延迟（仅 13 条） | p50 **13.2s** / max **19.3s** |
| 有引用回答 | 13/13（citation 映射按结果；A/B 均判 13 valid、7 na） |
| 预期 fallback 用例 | 11；真实运行 **0 条按脚本契约回退**（多数正常回答，部分超时） |
| 预期拒答用例 | 2；评审 A 2 条有误报/超时，B 判 0 条正确（见分歧） |
| 工具路由与期望一致 | 仅 **1–2 条**（A 1/20，B 2/20） |

## 2. 评审 support（回填）

两位评审独立判读，存在 1 条分歧；保守汇总如下：

| 评审 | support | partial | unsupported | not_applicable | 已答支持率 | 全样本支持率 |
|---|---:|---:|---:|---:|---:|---:|
| A | 13 | 0 | 0 | 7 | 100% | 65% |
| B | 12 | 1 | 0 | 7 | 92.3% | 60% |
| **保守汇总（用于门禁）** | **12** | **1** | **0** | **7** | **92.3%** | **60%** |

- 无伪造/越界引用；但引用内容映射未逐条人工核验（B 指出 expectedCitation 仅 1 条可确认）。
- **可评估题支持率** 92.3%（保守），但 **全样本 60%**；7 条超时不能计为 support。
- 仅 **1 条（fact-single-hop）** 同时满足 support + route + citation；多数 fallback/refusal 用例契约未通过。

## 3. 质量门禁结论

**`QUALITY_CLAIM_ALLOWED = false`**，原因：

1. **35% 超时**，可用性/稳定性不达标；
2. 工具路由/fallback/拒答契约大面积未按预期（20 条中 clean pass 仅 1 条）；
3. 只有 **两位 AI 评审**，未完成协议要求的人工双评/抽检（≥max(10,20%)，support≥90%）；
4. 仅 20 题、合成语料 3 chunk，不能外推到真实知识库；
5. 缺 run 元数据（model/prompt_version/date/device/key_configured），不可复现。

**当前只能报告：20 题真实运行观察（13 成功、7 超时；已答内容保守 12 support+1 partial）、引用格式有效；不能宣称 Agent/问答质量达标或可发布。**

## 4. 评审发现的问题

1. **超时被 UI 提示为“资料不足/无证据”**：可用性/工具故障与“无证据拒答”混淆，用户会误判知识库缺数据；应按 `TIMEOUT/AGENT_PROVIDER_ERROR/AGENT_TOOL_ERROR` 区分文案。
2. **空 query 未早退**（case17）：真实运行仍调 search+lookup，75s 超时；入口应直接返回可读参数错误。
3. **fallback 契约题不可在真实运行复现**：这 11 条应作为故障注入/路由契约测试，不应混入 QA 质量样本；QA 集与契约集分开。
4. **路由成功率极低**：真实 LLM 未按 `expectedToolRoute` 执行；需调整 planner prompt/评测期望，或把“是否允许替代路线”写入 rubric。
5. **结果缺 run metadata**：后续采集必须写入 model/prompt_version/date/device/key_configured/fixture checksum。
6. **7 条超时中的 fallback 分布**：tool_error 4 / provider_error 2 / none 1（评审 B 数据）；需排查超时/网络/工具失败，而非只重试。

## 5. 下一步建议（按优先级）

1. **修复可复现 UI/错误分类 bug**：空 query 早退；超时/工具故障不要显示成“资料不足”。
2. **拆题集**：QA 20–50 题与 fallback/故障注入契约题分开；QA 题不要求固定工具路线，契约题单独 mock/故障注入。
3. **补 run metadata + 逐题 evidence 映射**，确保 citation/支持率可人工复核。
4. **重跑稳定采集**：减少/定位 75s 超时（超时上限/重试/工具回退），目标 answered≥90%。
5. **安排 2 名人类评审**按协议抽检 ≥10 题（当前 2 位 AI 评审不计入人工门禁）；通过后再决定发布。
