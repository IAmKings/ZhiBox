# P1.4 / B17 Agent 质量评测协议（v1）

- **日期**：2026-10-21
- **状态**：数据/评测骨架完成；**真实质量评测未执行**（缺用户 LLM key + 人工抽检窗口）
- **适用范围**：B16 Agent 工具规划（route/fallback/evidence）+ B11 带引用问答的质量评测
- **关联**：`docs/review/P1_4_AGENT_EVAL_PLAN.md`（总计划）、`core/agent/AgentPrompt.PROMPT_VERSION=b16-agent-tools-v1`

> **核心原则**：mock/脚本化 harness 通过 **只代表 route/契约回归通过，不是真实质量分数**。
> 未配置真实 LLM key、未完成人工抽检时，**禁止**对外宣称 Agent/问答质量达标。

---

## 1. 评测对象与版本固定

| 项 | 要求 |
|---|---|
| 模型 | 固定 `LlmConfig.model`（记录到结果 `run.model`，评测期间不得切换） |
| Planner 提示词 | `AgentPrompt.PROMPT_VERSION`（当前 `b16-agent-tools-v1`，变更必须升版本并重评） |
| 工具集 | `search_knowledge`、`lookup_entities`；只读本地工具，默认最多 2 次工具调用+1 次 final |
| 最终回答 | B11 `AnswerGenerator` + `CitationValidator`（引用只来自本轮 evidence） |
| App/设备 | 记录 `app_version`、设备型号、日期（`run.device`/`run.date`） |
| 知识库 fixture | 固定导入的评测语料（同一 checksum/空间/模型 revision），记录 fixture 版本 |

## 2. 评测集

- 文件：`scripts/eval/agent_eval_cases.json`（当前 **20 条**，可扩展 20–50 题）
- 字段：`id/query/history/expectedToolRoute/expectedFallback/expectedCitationIds/notes`
- 覆盖：事实、多跳、多轮指代、工具路由（search/entity/final）、无证据拒答、错误工具/坏 JSON/工具错误回退、引用有效性
- **不含真实答案正文**（避免答案泄漏/硬编码）；`notes` 只写考点
- 离线 harness：`app/src/test/java/com/zhibox/memory/core/eval/AgentEvalHarnessTest.kt`
  - 使用 fake `LlmCompletionProvider`/`LlmConfigStore`/`AgentTool` 按 case 脚本化规划
  - 断言：route、fallback 原因码、fallback evidence 为空、evidence 按 chunkId 去重、最终引用契约（全部来自本轮 evidence、去重、顺序稳定）
  - 运行：`./gradlew :app:testDebugUnitTest --tests 'com.zhibox.memory.core.eval.*'`
  - 输出：`B17_AGENT_EVAL_OFFLINE ... contract_only=true real_quality=false`
  - **该测试通过不等于真实质量通过。**

## 3. 真实评测前置条件

1. **用户配置 LLM key**：通过 App 设置页写入（Android Keystore），评测期间只读使用；
   key **不得**写入仓库/结果 JSON/日志/截图/备份。
2. 已确认 `allowBackup=false`、`KnowledgeBackup` 不包含 key/prefs（见 B14/B11 安全设计）。
3. 固定模型/提示词/工具集/知识库 fixture（见 §1）。
4. 断网/联网策略符合产品设置：Agent 需要联网的 LLM；评测时记录网络策略状态。
5. **人工抽检窗口**：至少 2 名评审（或 1 名独立评审+1 名复核）参与支持性抽检；无法安排时只能报告"未抽检"。

## 4. 真实执行步骤

1. 准备评测语料并导入（记录 fixture checksum/空间/model revision）。
2. 在 App 设置中配置真实 LLM key；确认 UI 不回显完整 key、日志无 key。
3. 新建结果文件（可放 `docs/review/evidence/`，**注意不提交含隐私/真实回答的敏感数据**），按 schema
   `scripts/eval/agent_eval_result_schema.json` 填写 `run` 元数据（`key_configured=true`、model、prompt_version、date、device）。
4. 逐条执行 20–50 个 case（Agent 开关开启；记录每步工具 route/fallback、引用、拒答、错误码、延迟）。
5. **记录原始回答与引用**（`answer`/`citations`/`citation_valid`/`refused`/`error_code`），用于人工抽检；结果文件本地保存，
   不提交真实 key/个人隐私正文。
6. 人工抽检：至少 `max(10, 20% × 总题数)` 题；对每题标注
   `support`（有证据且回答正确）/`partial`（部分正确/证据不足）/`unsupported`（错误或不被证据支持）。
7. 运行计分：`python3 scripts/eval/score_agent_eval.py --cases scripts/eval/agent_eval_cases.json --results <result.json> --json-out <summary.json>`
8. 按 §5 门禁判定；只有 `QUALITY_CLAIM_ALLOWED=true` 才可报告"质量达标"，并同时披露模型/日期/样本量/失败项。

## 5. 指标与门禁（真实结果）

| 指标 | 门禁 |
|---|---|
| 含引用回答的引用有效性 `citation_valid` | **100%**（无伪造/越界引用、无非法 token） |
| 期望引用匹配 `expected_citations` | **100%**（评测集内可确定用例） |
| 无证据拒答 `refused`（expected_citations 为空且无预期 fallback 的用例） | **100%** |
| 工具路由契约 `actual_route == expected_route`（忽略末尾 final） | **100%** |
| fallback 契约 `fallback == expected_fallback` | **100%** |
| 人工抽检样本量 | ≥ `max(10, 20% × 总题数)` |
| 人工支持率 `support / checked` | **≥ 90%** |
| 延迟 | 记录 p50/p95/max（原始值报告；不达标时在结论中披露，不隐藏） |

- **`run.key_configured=false`、人工样本不足、或任一 100% 门禁未过 → `QUALITY_CLAIM_ALLOWED=false`**，
  只能报告"离线契约/回归通过"或"真实评测未通过"。
- 失败/回退 case 必须保留失败码（如 `AGENT_BAD_JSON`/`AGENT_TOOL_ERROR`/`LLM_TIMEOUT`），不得用 mock 结果补齐。

## 6. 结果 JSON schema 与示例

- Schema：`scripts/eval/agent_eval_result_schema.json`
- 示例（mock，仅演示结构）：`scripts/eval/agent_eval_result_example.json`（`key_configured=false`，scorer 会拒绝质量声明）
- 关键字段：
  - `run.model/prompt_version/date/device/key_configured`
  - `cases[].case_id/expected_route/actual_route/fallback/expected_citations/citations/citation_valid/unverified/refused/answer/latency_ms/error_code/manual_support/notes`

示例计分（mock 示例，**不是质量结果**）：

```bash
python3 scripts/eval/score_agent_eval.py --results scripts/eval/agent_eval_result_example.json
# → cases=3/20 ... QUALITY_CLAIM_ALLOWED=False
#   WARN: KEY_NOT_CONFIGURED / MANUAL_SAMPLE_INSUFFICIENT / MISSING_CASES
```

## 7. 当前缺失（阻塞真实质量结论）

| 缺失项 | 影响 | 需要的动作 |
|---|---|---|
| **真实 LLM key 未配置** | 无法执行真实评测；当前只有离线 fake harness | 用户在运行设备/模拟器配置 key（不入库/日志）；Lead 安排窗口 |
| **人工抽检窗口未安排** | 无支持率样本，不能判定质量 | 2 名评审按 §4.6 抽检 ≥10/20% 题并回填 `manual_support` |
| 评测语料 fixture 版本未锁定到本次运行 | 跨运行不可比 | 记录 fixture checksum/空间/model revision 到 `run.notes` |
| 真实延迟/路由数据未采集 | 只能报告离线契约 | 真实运行后由 scorer 输出 p50/p95/max |

**当前可报告结论**：离线 harness 20/20 case 的 route/fallback/evidence 去重/引用契约通过；
**不可报告真实回答质量、支持率或延迟达标**。

## 8. 命令速查

```bash
# 离线 harness（无需 key/网络）
./gradlew :app:testDebugUnitTest --tests 'com.zhibox.memory.core.eval.*'

# 真实结果计分（无 key 时会拒绝质量声明）
python3 scripts/eval/score_agent_eval.py \
  --cases scripts/eval/agent_eval_cases.json \
  --results <真实结果.json> \
  --json-out <summary.json>

# 语法自检
python3 -m py_compile scripts/eval/score_agent_eval.py
```

> 变更本协议（模型/提示词/阈值/评测集）需在结果 `run.notes` 中记录并重新评测；不得用旧结果冒充新版本质量。

---

## 4. 本轮执行记录（2026-10-21，K60U）

- 真实 20 题已采集：`docs/review/P1_4_EVAL20_SUMMARY.md`、`docs/review/evidence/P1.4-eval20-k60u-results.json`。
- 结果：13 回答 / 7 超时（35%）；A/B 两位 **独立 AI 评审**（非人类）：保守 support 12 + partial 1（已答 92.3%，全样本 60%；纯质量用例仅 2/9）、citation 13/13；route 1–2/20；fallback 0/11、拒答未按预期。
- 结论：`QUALITY_CLAIM_ALLOWED=false`；仍需 2 名人类评审与修复 35% 超时/空 query/错误分类等问题后重跑。
- 评审文件：`evidence/P1.4-eval20-review-a.json`、`review-b.json`（AI review；不计入人工门禁）。
