# P1.3 计划：Explore/3D 图谱

- **日期**：2026-10-20
- **状态**：开工（B15.1/B15.2 已完成）
- **目标**：在现有 2D 图谱/事件详情上增加“探索”模式：全库 event-entity 漫游、3D 投影渲染、来源/关键词筛选；保留列表/2D 作为无障碍替代。
- **原则**：不引入 3D 引擎/大依赖；用 Compose Canvas + 确定性 3D 投影；节点上限+筛选可解释；不改 append-only 数据。

## 1. 数据/布局契约

- `ExploreNode`：id（`e:{eventId}` / `t:{entityId}`）、kind、label、subtitle、entityType、3D position（归一化 [-1,1]）、eventId/entityId/sourceId/documentId。
- `ExploreEdge`：event→entity（无向渲染）；去重。
- 布局：
  - 实体在单位球面按确定性 hash/Fibonacci 分布；
  - 事件在关联实体位置均值附近（确定性抖动），无关联事件在另一球面；
  - 节点上限：事件 ≤200、实体 ≤400；超限 `truncated=true`，UI 提示缩小筛选。
- 筛选：来源（现有 source filter）+ 关键词（event title/summary/content 或实体 name/alias 命中；内存过滤，数据量 ≤上限）。
- `ExploreGraphRepository.load(sourceId, query, maxEvents)`：只读 DAO 查询 + builder；空查询/空数据可解释；不做网络。

## 2. 3D 渲染（Compose Canvas）

- 投影：yaw/pitch 旋转 + 透视缩放；画边（低 alpha）、节点（事件/实体不同形状/颜色），近大远小；拖拽旋转、双击复位；自动慢速旋转可关闭（无障碍/省电）。
- 选中节点：点击最近投影节点 → 显示 label/subtitle/类型/来源；事件节点可打开既有事件详情；实体节点可显示关联事件列表/跳转。
- 列表/2D 替代：同一数据切换“列表”模式；保留现有 2D 事件卡片/详情，不强制 3D。
- 性能：Canvas 只画上限节点；拖拽只重算投影；数据加载 IO；节点上限提示。

## 3. 测试/门禁

- Builder 确定性/节点/边去重/上限/空数据/关键词过滤；投影边界/旋转/深度排序/命中测试。
- UI 编译 + JVM mapping/gesture helper；Compose instrumentation 环境不稳定不强加失败测试。
- K60U：来源页→探索→3D 渲染/拖拽/筛选/列表切换/点事件详情；空库/超上限提示。
- 不改 schema/备份；本批只读展示。

## 4. 状态

- **首版完成**（core/UI/K60U E2E）；见 `P1_3_EXPLORE_3D_REPORT.md`。后续优化：FTS 搜索、节点文字/更多边类型、真机大库性能。

## 5. 顺序

1. core：ExploreGraphBuilder/Projection/Repository + 测试（data-reviewer/core-fixer）。
2. ui：Graph Explore mode/Canvas/列表切换/筛选（ui-fixer）。
3. K60U 门禁、报告、签名候选（不自动发布）。
