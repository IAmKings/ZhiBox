# P1.3c 报告：标签碰撞避让、FTS+LIKE union、更大库性能观测

- **日期**：2026-10-21
- **状态**：**完成**（K60U 250 事件 UI + 5000 事件 benchmark）
- **目标**：关闭 Explore 后续：标签重叠、FTS 部分命中不 union、缺连续性能观测。

## 1. FTS+LIKE union

- query 非空时三路合并：`event_fts`、`entity_fts`（含 alias）→event_entity、有界 LIKE（event title/summary/content + entity name/alias；仅 SELECT id）。
- 上限：FTS 事件 5000、实体 2000、LIKE 5000；超限 truncated；FTS 有命中仍合并 LIKE；FTS 空仍走 LIKE；空 query 纯分页。
- 不加载正文；total/hasMore/offset 语义不变。

## 2. 性能观测

- `ExplorePerfMonitor`（core/graph）：最近 64 次成功 load 环形样本（offset/pageSize/result/total/durationMs/usedFts/usedLike/truncated，无 query/正文）；`stats()` p50/p95/max/last；reset。
- Graph UI 显示「最近 Xms · p50 · p95 · N 次」+「最近通道：FTS/LIKE/FTS+LIKE/—」；失败保留最近成功指标。

## 3. 标签碰撞避让

- 实测文本宽高生成 rect；选中优先，其余近→远；clamp 画布、padding 重叠跳过、最多 12；Canvas 每帧重算。
- 纯 JVM `placeExploreLabels` 8 个测试（选中优先/重叠/边界/超大/上限/padding）。

## 4. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **770 / 0**、**751 / 0** |
| lint | 0 errors / 57 warnings, 2 hints |
| graph JVM | 46/46（union/分页/上限/monitor/p50/p95/并发） |
| UI JVM | GraphLabelLayout 8/8 |
| K60U UI | 250 事件分页/标签/搜索 250 FTS+LIKE 6 命中/性能行 |
| K60U 5000 benchmark | loads=29 p50=22ms p95=216ms max=241ms，OK |
| 证据 | `evidence/P1.3c-perf-k60u.txt`、截图/XML |

## 5. 签名候选

- `dist/zhibox-0.1.2-89b1929-release-signed.apk`（69,515,201 bytes，SHA-256 `2034e83f242b2dcfb0b34b9aa92cdc787a23c82d21bcbb1370633b7b778ec174`；未发布/tag）。

## 6. 边界

- union 每次非空 query 多一次有界 LIKE 扫描；超大库 p95 会增长（5000 库实测 216ms）。
- total/hasMore 受候选上限约束，truncated 提示，不是无限全量。
- monitor 仅最近 64 次成功样本、进程内；失败/取消不计；nearest-rank。
- 标签仅避让标签-标签，不避节点遮挡；密集图仍可能少量边缘标签偏离。
- 5000 库 seed/FTS rebuild 耗时 1.3s/7s 未作为门禁，仅 load 延迟。
