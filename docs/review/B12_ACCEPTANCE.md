# B12 P0 验收矩阵（进行中）

- **日期**：2026-10-19
- **范围**：ROADMAP §7 B12 / PRD §11 P0 门禁
- **结论**：**进行中**；B12 代码/自动门禁第一批判定完成，三档设备矩阵与 release 签名/独立审查尚未闭合，不宣称发布候选通过。

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
| 迁移/备份/删除/取消/失败回归 | 既有 B8/B9/B10/B11 测试；B12 backup UI 文案审计；未在本轮重跑全部设备矩阵 | `app/src/androidTest/.../Migration*`、`BackupV3RoundTripTest` |
| 图谱：source 过滤/分页≤200/详情/chunk 原文/空态 | DAO 4/4；GraphUI 6/6；PJZ110 串行 | `B12-instrumentation-pjz110.txt`、`B12-graphui-pjz110.txt` |
| 预算档位 | 9/9 JVM；Settings UI 三档 + 数值展示；Search/Chat 下一次请求读取 | `SagBudgetTierTest` |
| backup v3 UI/依赖提示/旧包补抽取提示/隐私说明 | UI 审计补缺完成；编译通过；未做专项设备视觉 | Settings task-61 |
| trace 诊断导出（最近 50/脱敏/无正文） | Settings 新增检索/问答 trace 计数 + 导出 | task-61 |
| JVM/lint/assemble | debug 513/0、release 494/0、lint 0/37、assembleDebug/Release 成功 | `/tmp/b12-gate1.log` |
| release candidate | unsigned APK 已生成；签名材料缺失/未验签 | `dist/zhibox-b12-rc-cf44f9c-unsigned.apk`，SHA-256 `50eb1070e4e8c05686748f921cdc562ecdeece9d1d7ba631e4d599a59c42583a` |

## 3. 未完成项

1. **三档设备矩阵**：K40 未连接、低端设备缺失；不能宣称三档通过。
2. **release candidate 签名/验签**：无 release keystore（`ZHIBOX_*` 未配置），当前为 unsigned，不可分发；按 `docs/RELEASE.md` 由用户提供密钥后签名。
3. **备份 v3 UI 视觉/回环**：B8/B9 已有备份回环测试，但 B12 UI 变更后未再跑专项设备回环；旧包/空 SAG 提示需视觉确认。
4. **独立 Standards/Spec 审查 B12 变更**：未执行。
5. **K40/B12 回归**：未复跑 B10/B11 全量真机；需设备可用时补。
6. **发布文档**：README/RELEASE_NOTES/用户指南 B12 章节待补。

## 4. 下一步

1. 串行跑备份 v3 导出→清库→导入回环 + 旧包/空 SAG 提示（PJZ110）。
2. 连接 K40/低端设备补三档矩阵；无设备则如实标注。
3. 配 release keystore → `scripts/release.sh` 签名 RC → `apksigner verify`。
4. B12 独立审查、报告/ROADMAP/PRD 完成。
