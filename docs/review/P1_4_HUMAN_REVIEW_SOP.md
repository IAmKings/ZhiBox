# P1.4 人类评审抽检 SOP（Agent / 带引用问答）

> 目的：把“真实 LLM 运行结果”转成可签字的人工质量结论。  
> 当前状态：已完成 20 题真实运行 + 2 位 **AI** 评审；**尚未进行人类双评**，因此仍不能宣称质量达标。

## 1. 前置条件（缺一不可）

- [ ] 冻结被测构建：记录 git commit、versionName/versionCode、APK SHA-256、签名。
- [ ] 冻结 LLM 配置：model、baseUrl（只记域名）、promptVersion、解码参数、key 已配置（**不记录 key**）。
- [ ] 冻结知识库：来源/文档数、文档 SHA 或 fixture checksum、是否使用真实用户数据。
- [ ] 冻结题集：`suite`、case id、query、期望行为；真实 QA 集与 fallback/故障注入契约集**分开**。
- [ ] 采集结果包含：answer、citations（含 evidence 正文快照）、latencyMs、failureCode、fallbackReason、run metadata。
- [ ] 结果/评审材料脱敏：不含 API key、完整私密文档、账号信息。

## 2. 抽样规则

- 题量：真实 QA **20–50 题**。
- 人类样本量：**N = max(10, ceil(总题数 × 20%))**；20 题时为 **10 题**。
- 抽样分层：
  1. **全部失败/超时/拒答**必须纳入（风险样本）；
  2. 其余从“有回答”中按题类（事实/多跳/多轮/无证据）分层随机抽取；
  3. 固定随机种子并记录，禁止人工挑简单的题。
- **2 名评审独立评同一批 N 题**（用于一致性/仲裁）；若组织上只能各评一半，必须额外保留 ≥3 题重叠。
- 评审人要求：中文母语、非本次实现作者、未参与采集脚本调试；两人不得互相看分数直至提交。

## 3. 评审材料包（Reviewer Packet）

每位评审收到同一份只读包：

1. `run metadata`：build/model/prompt/fixture/date/device；不包含 key。
2. `cases.csv`：case id、query、category、expected behavior。
3. `answers.json`：answer、citations、failureCode、latency。
4. `evidence.md`：每个 citation 的 evidence 正文快照（截断到协议上限），按 S1..Sn 编号。
5. 评分表模板：[P1_4_HUMAN_REVIEW_TEMPLATE.csv](evidence/P1_4_HUMAN_REVIEW_TEMPLATE.csv)。
6. 盲评说明：只看题、回答、引用与证据；不参考 AI 评审结论。

## 4. 逐题评分标准

| 字段 | 取值 | 定义 |
|---|---|---|
| `support` | `support` | 回答的全部事实断言均被本轮 evidence 支持；引用覆盖完整 |
| | `partial` | 有轻微推断/漏引/次要表述问题，但无重大编造 |
| | `unsupported` | 存在 material 无据断言、错误引用，或证据充分却错误拒答 |
| | `not_applicable` | 题本身无答案/合理拒答/无输出；另记 `refusal_correct` |
| `citation_valid` | `true/false` | 每个 `[S#]` 均映射到真实 evidence，且该句含义被 evidence 支持；无引用回答为 false |
| `refusal_correct` | `true/false/na` | 无证据/超域时是否正确拒答；有证据却拒答为 false |
| `failureCode` | `无 / TIMEOUT / LLM_* / AGENT_* / PRODUCT_ERROR` | 记录原始失败码，不把失败当 support |
| `notes` | 简短文本 | 指出具体断言/引用/证据缺口；不要粘贴私密正文 |

**硬门槛（按协议）**

- citation validity：抽检回答 **100%**；
- 有证据题预期 citation：**100%**（能核验时）；
- 无证据题正确拒答：**100%**；
- 人类 `support`（按抽检样本，`partial` 不计入 support）：**≥90%**；
- 超时/失败必须单列；若失败率过高，即使 support 高也不通过可用性门禁。

## 5. 执行流程（8 步）

1. **Freeze**：锁定上述构建/模型/题集/知识库，生成 run metadata。
2. **Collect**：运行真实 QA 采集（单题超时、失败码、延迟、引用、evidence 快照必须落盘）。
3. **Export**：导出脱敏结果；生成评审包与抽样清单；记录随机种子。
4. **Brief**：向两位评审说明目标、评分标准、禁止交流与隐私边界（15 分钟）。
5. **Review A**：评审 A 独立逐题填写评分表，提交 CSV/JSON + 签名/日期。
6. **Review B**：评审 B 在未看 A 结果的情况下独立填写。
7. **Reconcile**：
   - 计算一致率与 Cohen's κ（分类字段）；
   - 对 `support`/`citation_valid` 不一致的题，由**第三方仲裁者**看原始 evidence 裁定；
   - 以裁定后的保守结论计算 support%（`partial` 不计 support）。
8. **Sign-off**：填写 `P1_4_HUMAN_REVIEW_SIGNOFF.md`（评审人、日期、git sha、模型/prompt、抽样 seed、support%、citation%、失败率、结论 PASS/FAIL、遗留项）。

## 6. 判定与记录

- 只有**两位人类评审 + 仲裁 + 签字**齐全，才允许进入发布评审。
- AI 评审可作预检，但**不计入**人类样本量，不得替代签字。
- 若 FAIL：记录失败 case/错误码，修复后**重新冻结、重新抽样**；不得复用旧结果。
- 通过后仍需与 CI/离线契约测试分开报告：本次只是真实 LLM 质量门禁。

## 7. 建议的时间盒

| 阶段 | 时长 |
|---|---:|
| Brief + 材料阅读 | 15 min |
| 10 题独立评审（2 人并行） | 30–40 min/人 |
| 分歧仲裁 | 15 min |
| 签字/归档 | 10 min |

## 8. 本次（2026-10-21）可执行入口

- 最近真实 QA 结果：`docs/review/evidence/P1.4-qa20-k60u-results.json`（20 题，18 回答/2 超时；评审包按此生成）
- 修复后复跑：`P1.4-qa20-timeoutfix-k60u-results.json`、`P1.4-qa20-parseretry-k60u-results.json`（17 回答/3 无证据拒答，无 75s 超时）
- 评审 A/B（AI，仅预检）：`P1.4-qa20-review-a.json` / `review-b.json`
- 抽样清单与模板：`docs/review/evidence/P1_4_HUMAN_REVIEW_SAMPLE.csv`、`P1_4_HUMAN_REVIEW_TEMPLATE.csv`
- 需要用户/项目方指派 **2 名人类评审**并确定仲裁者；当前无人签到，故 `QUALITY_CLAIM_ALLOWED=false`。
