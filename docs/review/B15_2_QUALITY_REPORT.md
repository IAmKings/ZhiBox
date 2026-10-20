# B15.2 报告：抽取质量只读诊断

- **日期**：2026-10-20
- **状态**：**完成**（分析器/JSON/设置卡/SAF 导出/K60U E2E）
- **目标**：为“抽取质量专项”提供可观测性，不伪造质量结论；不在本批引入 prompt/模型质量评测（需 LLM key 窗口）。

## 1. 分析器

`core/extraction/ExtractionQualityAnalyzer.kt`：
- `ExtractionQualityReport`：文档/ready、事件/有效/丢弃、空字段计数、实体/含别名/别名数、类型分布、event-entity 关联、有/无事件文档、任务总数/状态/错误码、派生 `extractionCoverage`/`aliasCoverage`；
- `analyze(db)`：14 条只读聚合 SQL（COUNT/SUM/GROUP BY，readableDatabase），不 SELECT event/chunk 正文/标题/摘要，不读 errorDetail；IO/取消；空库全 0；
- `toJson()`：固定键序、比率 4 位小数、type/status/errorCode 字典序；无正文/实体名/errorDetail/密钥。

## 2. UI/导出

- Settings「索引诊断」卡下新增「抽取质量（只读）」：计数、覆盖率、空字段、任务状态/错误码、实体类型分布（截断 + 总数）；
- 「刷新质量报告」/「导出质量 JSON」；SAF `CreateDocument("application/json")` 默认 `zhibox-extraction-quality.json`；空/失败可读；
- 进入设置异步刷新，不阻塞其他设置；隐私说明“仅计数/比率/枚举分布”。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **708 / 0**、**689 / 0** |
| lint | 0 errors / 56 warnings |
| analyzer JVM | 4/4：计数/覆盖率/类型/任务错误码；空库；JSON 脱敏/键序；只读快照不变 |
| UI JVM | 4/4：覆盖率 0 保护/夹取、空/非空报告换算 |
| K60U E2E | 质量卡显示正确；SAF 导出 426B JSON；无正文/标题/摘要/实体名/errorDetail |
| 证据 | `evidence/P1.2B-quality-e2e-k60u.txt`、`P1.2B-quality-export-k60u.json`、`P1.2B-quality-card-k60u.xml` |

## 4. 签名候选

- `dist/zhibox-0.1.2-452f3c5-release-signed.apk`（69,467,669 bytes，SHA-256 `c610ed579370610313e161d5de8077f4826af78f36894021b1cd7ff1742b07b2`；未发布/tag）。

## 5. 边界/后续

- 覆盖率按“有 event 的文档”统计（含 superseded 历史 event）；active-only 可改一行 SQL。
- 大库全表 COUNT/GROUP BY 为后台 suspend/IO，UI 显示刷新态；无分页。
- 手写聚合 SQL 对 schema 无编译期保护，迁移时需同步测试。
- 真正的 prompt/模型质量对比仍需 LLM key 与人工抽检；本批只提供可观测性，不声称质量达标。
- 后续：Explore/3D 图谱；发布前 signed RC smoke/tag。
