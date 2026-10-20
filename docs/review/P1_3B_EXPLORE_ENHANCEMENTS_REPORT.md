# P1.3b 报告：Explore FTS/分页、节点标签、大库与无障碍

- **日期**：2026-10-21
- **状态**：**完成**（K60U 真机与大库 instrumentation）
- **目标**：完成 Explore 后续：搜索接 FTS、offset 分页、节点标签、无障碍与真机大库性能。

## 1. FTS/分页

- `ExploreGraphRepository.load(..., maxEvents=100, offset=0)`：
  - query 非空优先 `event_fts` + `entity_fts`（含 alias）→ `event_entity` 关联事件；候选上限 5000/2000，超限 truncated；FTS 无结果回退 LIKE（旧库/索引缺失可搜）；
  - FTS 路径只取 id/title/summary + source/document，不加载 event.content；
  - 空 query 走稳定 id 排序 LIMIT/OFFSET；`totalEvents` 全量 count；`pageOffset/hasMore`；maxEvents 1..200。
- **truncated 语义修正**：只表示候选/实体/布局硬上限；普通分页（total>单页）不再误报，由 hasMore 表达。
- UI：第 X/共 Y 页、上一页/下一页、truncated 与 hasMore 分开展示；来源/关键词变化重置第一页。

## 2. 节点标签/无障碍

- Canvas 绘制标签：选中节点优先 + 按 depth 取近节点，最多 12；事件 title/实体 name，18 字省略；随旋转/缩放更新。
- Canvas contentDescription：页码、事件/实体/边数、可切列表；页控件/筛选/列表模式均有可读文本；保留列表无障碍替代。
- 标签未做碰撞避让（已知边界，密集图会重叠）。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **752 / 0**、**733 / 0** |
| lint | 0 errors / 57 warnings, 2 hints |
| graph JVM | 36/36（FTS/alias/回退/分页/越界/上限/只读） |
| UI JVM | GraphExplorePaging 5/5（页码/标签选择） |
| K60U 大库 instrumentation | 1000 events/entities/links，分页 4–75ms，OK(1 test) |
| K60U UI | 250 事件：第1/3页→第2页、搜索“250”命中6、标签渲染、a11y 摘要、列表兜底 |
| 证据 | `evidence/P1.3b-explore-followup-k60u.txt`、截图/XML/bigdb log |

## 4. 边界

- 候选上限后不再翻页（truncated 提示）；FTS 部分命中不与 LIKE union；FTS 依赖索引维护；候选内存分页上限 5000。
- 标签最多 12 个、未避让；页大小固定 100；大库 1000 实测快，更大库需持续观测。
- Explore 仍只读展示，不改 schema/备份。

## 5. 后续

- 可做标签碰撞避让、FTS 部分命中与 LIKE union、signed RC clean install smoke/tag；P1 剩余 Agent/工具编排与质量评测。
