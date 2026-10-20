# P1.3 报告：Explore/3D 图谱

- **日期**：2026-10-20
- **状态**：**完成首版**（只读 Explore/3D + 列表替代；K60U 真机验证）
- **目标**：在既有 2D 图谱/事件详情上提供全库 event-entity 漫游、3D 投影、来源/关键词筛选；不引入 3D 引擎/大依赖。

## 1. 数据/布局

- `core/graph/ExploreGraph.kt`：节点/边/图模型（事件/实体节点、来源信息、truncated/maxEvents）。
- `ExploreGraphBuilder`（纯 JVM 确定性）：
  - 实体 Fibonacci 单位球；关联事件=实体位置均值+确定性抖动；无关联事件次级球面；
  - 上限事件 200/实体 400；超限 truncated；边去重/只连存在节点；空输入空图；同快照可复现。
- `ExploreGraphRepository.load(db, sourceId, query, maxEvents)`：
  - 只读 active event + source 过滤；关键词匹配 event title/summary/content、entity name、**alias**；LIKE 转义；有界加载；COUNT(DISTINCT) totalEvents；不加载正文。
- `ExploreProjection`：yaw/pitch 旋转、透视/远→近排序/depth/scale、hitTest 最近节点。

## 2. UI

- 来源页→图谱顶部「列表 / 3D 探索」切换；来源 chips 共用。
- 探索：关键词（400ms 防抖）、自动旋转开关、节点/边/事件摘要、truncated 提示、空/错误/loading、查看列表模式兜底。
- Canvas：边低 alpha、事件/实体颜色/半径差异、近大远小、选中描边；拖拽旋转；点击 hitTest；contentDescription 摘要。
- 选中卡：label/subtitle/类型/来源（source title 优先）；事件节点打开既有详情；实体节点提示并回列表。
- 列表/2D 既有事件卡片、详情、相关事件、别名行为不变。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **741 / 0**、**722 / 0** |
| lint | 0 errors / 57 warnings, 2 hints |
| core graph JVM | 30/30：builder 确定性/边界/上限、projection 排序/hitTest、repository source/query/alias/limit/空库/只读 |
| UI JVM | GraphExploreUiTest 3/3（近大远小/半径夹取/非法输入） |
| K60U E2E | 3D 渲染/拖拽旋转/自动旋转/点事件节点/详情/列表切换全部通过 |
| 证据 | `evidence/P1.3-explore-e2e-k60u.txt`、截图/XML |

## 4. 签名候选

- `dist/zhibox-0.1.2-7e58866-release-signed.apk`（69,493,668 bytes，SHA-256 `24b16d299766f741b364eb1f342b4bb8ba8f439c9391c2428724464e07eeb481`；未发布/tag）。

## 5. 边界/后续

- 首版节点上限 200 事件/400 实体；超限提示缩小来源/关键词；非全库无限渲染。
- 关键词为有界 LIKE 扫描，非 FTS；UI 400ms 防抖；大库后续可接 FTS/分页。
- Canvas 不直接绘制节点文字（选中卡/a11y/列表模式提供文本）；hitTest 阈值 28px 为默认，真机小屏可再调。
- 仅 event↔entity 边，无多跳边类型；无 3D 遮挡修正。
- 后续：真实大库性能/可访问性测试、Explore 搜索 FTS、发布前 signed RC smoke/tag。
