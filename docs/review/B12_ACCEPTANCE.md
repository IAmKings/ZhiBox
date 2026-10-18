# B12 P0 验收矩阵（进行中）

- **日期**：2026-10-19
- **范围**：ROADMAP §7 B12 / PRD §11 P0 门禁
- **结论**：**验收不通过（进行中）**；代码/自动门禁、entity 连接、10k 查询基线、trace errorCode 与签名 RC 已完成；但三档设备矩阵仍缺失（仅 PJZ110），抽取并发控制/备份 UI 视觉/持久盘 p95 未闭合，不能宣称发布验收通过。

## 1. 设备矩阵

| 档位 | 设备 | 状态 | 证据/备注 |
|---|---|---|---|
| 高端 | PJZ110 `1fc6056b` | 可用 | Compose/instrumentation 串行 31/31；真实 LLM QA/Support（B11）通过 |
| 中端 | K40 `6D8H6TXSDYXC85PZ` | 本轮未连接 | B10/B11 期间有 440M/102 测试基线；B12 未复跑 |
| 低端 | 未提供 | 缺失 | 需 4–6GB 设备；不能伪造通过 |

## 2. P0 门禁清单（截至本轮）

| 门禁 | 结果 | 证据 |
|---|---|---|
| 导入→chunk→事件/实体→检索→问答→图谱闭环 | 代码/自动测试通过；真实 B11 QA citation 100%/support 98% | B9/B10/B11 报告；PJZ110 |
| 多跳 R@5 ≥0.70 / +5pp | 通过（B10） | B10 报告/证据 |
| 单跳 262 回退 ≤0.5pp | 通过（B10） | B10-262 evidence |
| 引用有效性 100%、支持率≥90% | 通过（B11） | B11 real QA/support evidence |
| 禁网零请求/超时/429/坏 JSON | JVM/TLS mock 通过；真实网络 1 次 timeout 可读 | B11 报告 |
| 迁移/备份/删除/取消/失败回归 | 备份 v3 回环 **7/7**、迁移 **11/11**（PJZ110）；删除/取消/失败沿用 B1–B11 测试，本轮未逐一复跑 | `B12-backupv3-pjz110.txt`、`B12-migration-pjz110.txt` |
| 图谱：source 过滤/分页≤200/详情/chunk 原文/空态/entity 连接 | DAO 4/4；GraphUI 6/6；新增相关事件（共享实体）；PJZ110 串行 32/32 | `B12-instrumentation-pjz110.txt`、`B12-graphui-pjz110.txt` |
| 图谱 query p95 ≤1.5s（10k） | 通过（in-memory 下限）：count p95 8ms、page p95 2–3ms、source filter 3ms | `B12-graph10k-pjz110.txt` |
| 预算档位 | 9/9 JVM；Settings UI 三档 + 数值展示；Search/Chat 下一次请求读取 | `SagBudgetTierTest` |
| backup v3 UI/依赖提示/旧包补抽取提示/隐私说明 | UI 审计补缺完成；编译通过；未做专项设备视觉 | Settings task-61 |
| trace 诊断导出（最近 50/脱敏/无正文/errorCode） | Settings 新增计数+导出；QA timingJson 增 errorCode/errorStage | task-61 + 本轮 |
| JVM/lint/assemble | debug 513/0、release 494/0、lint 0/37、assembleDebug/Release 成功 | `/tmp/b12-gate1.log` |
| release candidate | **通过（构建/验签）**：release keystore 签名，apksigner verify PASS；未覆盖安装 debug 包；三档安装矩阵仍缺 | `dist/zhibox-0.1.0-mvp-1aeea59-b12-release.apk`，SHA-256 `b9f41cc3a217369fd4e268c18c39242ebeda9d9855d704578fbda225eb0c7f6b`；见 `evidence/B12-RELEASE_CANDIDATE.json` |

## 3. 未完成项

1. **三档设备矩阵**：K40 未连接、低端设备缺失；不能宣称三档通过。
2. **release candidate 安装矩阵**：签名/验签已 PASS，但未在 clean 设备安装 RC（PJZ110 已装 debug 包，覆盖会签名冲突）；待 clean 设备安装验收。
3. **备份 v3 UI 视觉/回环**：B8/B9 已有备份回环测试，但 B12 UI 变更后未再跑专项设备回环；旧包/空 SAG 提示需视觉确认。
4. **独立 Standards/Spec 审查 B12 变更**：已执行；硬性 RC/竞态/嵌套/文案问题已修，剩余抽取并发、entity 聚合性能/持久盘、备份视觉等缺口。
5. **K40/B12 回归**：未复跑 B10/B11 全量真机；需设备可用时补。
6. **发布文档**：README/RELEASE_NOTES/用户指南 B12 章节待补。

## 4. 下一步

1. 串行跑备份 v3 导出→清库→导入回环 + 旧包/空 SAG 提示（PJZ110）。
2. 连接 K40/低端设备补三档矩阵；无设备则如实标注。
3. 在 clean 设备安装已签名 RC（或卸载 debug 后安装），完成安装矩阵。
4. B12 独立审查、报告/ROADMAP/PRD 完成。
