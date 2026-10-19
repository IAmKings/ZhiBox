# B12 P0 验收矩阵（环境受限通过）

- **日期**：2026-10-19
- **范围**：ROADMAP §7 B12 / PRD §11 P0 门禁
- **结论**：**环境受限通过**（用户 2026-10-19 明确接受“高端+中端、缺低端”）。代码/自动门禁、entity 连接、10k in-memory/持久盘基线、trace errorCode、抽取并发、签名 RC clean install/launch、备份 UI 文本走查均已完成。低端 4–6GB 设备未提供，矩阵中如实标注“未提供”，不伪造通过；发布动作仍由用户 tag/Release 决定，不自动发布。

## 1. 设备矩阵

| 档位 | 设备 | 状态 | 证据/备注 |
|---|---|---|---|
| 高端 | PJZ110 `1fc6056b` | 可用（RC） | signed RC clean install/cold launch/UI 走查；B11 real QA/support 通过 |
| 高端 | K60U `6D8H6TXSDYXC85PZ`（Dimensity 9200+，15.7GB，Android 14） | 可用（debug；MIUI 输入/后台启动受限） | B12 非 UI instrumentation 18/18；Graph 10k in-memory p95≤17ms、持久盘 p95≤21ms；debug app 可从桌面启动（home 已截图）；Compose instrumentation 被 HyperOS 拒绝后台启动/输入注入 |
| 中端 | K40 `eipzae799dkfpffq`（M2012K10C，Dimensity 1200，7.7GB，Android 13） | 可用（debug；RC 未覆盖安装以保留数据） | B12 非 UI instrumentation 18/18；Graph 10k in-memory p95≤62ms、持久盘 p95≤57ms；UI 手动走查 home/来源/图谱空态/Settings 预算·并发·隐私 |
| 低端 | 未提供（用户已接受环境受限） | 缺失 | 需 4–6GB 设备；本矩阵不伪造通过，标记环境受限 |

## 2. P0 门禁清单（截至本轮）

| 门禁 | 结果 | 证据 |
|---|---|---|
| 导入→chunk→事件/实体→检索→问答→图谱闭环 | 代码/自动测试通过；真实 B11 QA citation 100%/support 98% | B9/B10/B11 报告；PJZ110 |
| 多跳 R@5 ≥0.70 / +5pp | 通过（B10） | B10 报告/证据 |
| 单跳 262 回退 ≤0.5pp | 通过（B10） | B10-262 evidence |
| 引用有效性 100%、支持率≥90% | 通过（B11） | B11 real QA/support evidence |
| 禁网零请求/超时/429/坏 JSON | JVM/TLS mock 通过；真实网络 1 次 timeout 可读 | B11 报告 |
| 迁移/备份/删除/取消/失败回归 | 备份 v3 回环 **7/7**、迁移 **11/11**（PJZ110）；删除/取消/失败沿用 B1–B11 测试，本轮未逐一复跑 | `B12-backupv3-pjz110.txt`、`B12-migration-pjz110.txt` |
| 图谱：source 过滤/分页≤200/详情/chunk 原文/空态/entity 连接 | DAO 4/4；GraphUI 6/6；相关事件（共享实体）；PJZ110 串行 **36/36** | `B12-instrumentation-pjz110.txt` |
| 图谱 query p95 ≤1.5s（10k） | 通过（in-memory + file-backed 两套）：in-memory count p95 8ms/page 2–3ms；file-backed count p95 4–5ms、page p95 ≤12ms、offset9950 p95 10ms | `B12-graph10k-{in-memory,persistent}-pjz110.txt` |
| 预算档位 | 9/9 JVM；Settings UI 三档 + 数值展示；Search/Chat 下一次请求读取 | `SagBudgetTierTest` |
| 抽取并发 | `SagExtractionPreferences` 1..4 + Worker 并发 claim + Settings UI；JVM 7/7、设备 3/3 | `SagExtractionPreferencesTest`、`SagExtractionClaimConcurrencyTest` |
| backup v3 UI/依赖提示/旧包补抽取提示/隐私说明 | UI 审计补缺+PJZ110 RC/K40 UI 文本走查（备份/诊断/预算/并发/隐私） | `B12-rc-settings-backup.xml`、`B12-k40-settings.xml` |
| trace 诊断导出（最近 50/脱敏/无正文/errorCode） | Settings 新增计数+导出；QA timingJson 增 errorCode/errorStage | task-61 + 本轮 |
| JVM/lint/assemble | debug **520/0**、release **501/0**、lint **0/40**、assembleDebug/Release 成功 | 最终门禁日志 |
| release candidate | **通过**：release keystore 签名、apksigner PASS；PJZ110 clean install/cold launch/UI 已验；不自动发布 | `dist/zhibox-0.1.0-mvp-0c08286-b12-release.apk`，SHA-256 `3abd4d2ca321993188b5c94ab68ea17238ca7d2591bcd1389cba33da2de5f036`；见 `evidence/B12-RELEASE_CANDIDATE.json` |

## 3. 环境受限与遗留（用户已接受）

1. **低端 4–6GB 设备未提供**：三档矩阵缺低端；不伪造通过，按“环境受限通过”记录。
2. **K60U/K40 使用 debug 包做功能矩阵**：为避免覆盖已有数据/配置，未卸载安装 signed RC；PJZ110 已完成 signed RC clean install。
3. **K60U 的 HyperOS 限制**：UI instrumentation/输入注入被 `INJECT_EVENTS`/后台 Activity 限制拒绝，仅完成非 UI 与手动 home 启动；K40/PJZ110 可手动 UI 走查。
4. **性能基线采样**：10k 为单次采样、cold 为同进程首查；低端设备 p95 未测（设备缺失）；目标 1.5s 在当前高端/中端均远满足。
5. **发布**：RC 已构建/验签/clean install，但未 tag/Release；按约束不自动发布。

## 4. 结论与后续

- B12 P0 门禁在“高端+中端、缺低端”环境受限口径下通过；代码/自动门禁/RC/文档完成。
- 若后续取得 4–6GB 低端设备，按现有脚本补一台低端 RC（或 debug）安装、非 UI/UI/性能记录即可把矩阵补全；不阻塞本次环境受限验收。
