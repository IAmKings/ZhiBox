# B9 真实 LLM 抽取质量与成功率结果

- **日期**：2026-10-18
- **设备**：K40（`6D8H6TXSDYXC85PZ`，Android 16，arm64）
- **模型配置**：OpenCode Go，`baseUrl=https://opencode.ai/zen/go/v1`，`model=deepseek-v4-flash`（API key 不记录）
- **方式**：临时 instrumentation 探针直接 seed **ready docs/chunks**（不经过 SAF 导入，以隔离抽取质量）→ 真实 `RemoteLlmEventEntityExtractor` + `EmbeddingSagGateway` → `SagExtractionPipeline.processNext()`；结果导出到 App files 后经 adb 拉取。
- **说明**：本报告为**内测/AI 辅助人工标注**；样本为自建中文事实语料（100 chunk），不是用户真实知识库；发布前建议再做一次独立人工抽检。

## 1. 100 chunk 成功率

| 指标 | 结果 |
|---|---|
| ready chunks | 100 |
| extraction jobs | 100 queued |
| done | **99** |
| failed | **1** |
| not_indexed / cancelled | 0 / 0 |
| **成功率** | **99 / 100 = 99.0%**（门槛 ≥99%，通过） |
| active event | 99 |
| entity incidence | 353 |
| event FTS / entity FTS / SAG 向量 | 99 / 353 / 452 |

唯一失败：`doc-b9-eval2-3` chunk 244，`EXTRACT_MISSING_FIELD`（LLM 返回缺少 `isValid` 字段），attempt=1；属输出格式偶发问题，未达重试上限前可作为可重试类在后续版本优化（本次按终态记录）。

## 2. 30–50 chunk 人工抽检（实际 50 chunk）

抽检前 50 个 chunk（208 个 entity incidence），event content 与 chunk 原文逐字一致；实体类型按 11 类逐条标注，错误 11 个（如“助理教授”标 product、“65岁患者”标 person、“世界文化遗产名录”标 subject 等）。

| 指标 | 结果 | 门槛 | 结论 |
|---|---|---|---|
| event 事实忠实性 | **50/50 = 100%** | ≥90% | PASS |
| 实体类型准确率 | **197/208 = 94.7%** | ≥85% | PASS |
| 每 event ≥1 entity 覆盖率 | **50/50 = 100%** | 100% | PASS |
| event→chunk 可追溯 | 100/100（event.chunkId + chunk 原文） | 100% | PASS |

评分脚本：`python3 scripts/score_sag_extraction.py --samples <annotated.json> --out score.json` → `passed=true`。

## 3. 失败模式与后续

1. **LLM 偶发缺字段**（1/100）：`isValid` 缺失导致解析失败；建议 B10/B11 将 `EXTRACT_MISSING_FIELD/INVALID_JSON` 纳入有限重试（当前为终态）。
2. **实体类型边界**：product 被用于职位/功能/航班号，subject 被用于赛事/名录/动作；实体类型准确率仍高于门槛，但 B10 可用类型约束提示/后处理提升。
3. **事件内容**：模型倾向直接复制 chunk 作为 event content，忠实性高但摘要/标题的抽象质量一般；B11 问答前可调 prompt。
4. **样本规模**：真实 100 chunk + 50 抽检已过门禁；发布前建议在真实用户语料上复跑（脚本与协议见 `B9_EXTRACTION_EVAL.md`）。

## 4. 结论

**B9 质量门禁（本报告口径）通过**：100 chunk 成功率 99%（≥99%）、50 chunk 抽检三项指标全部达标。B9 可标记为“自动+质量门禁完成”，保留上述限制与发布前独立复抽建议。
