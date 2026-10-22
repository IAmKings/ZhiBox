# B12 设计

## 图谱/事件详情
- 数据：`EventDao.activeGraphPage(sourceId?, limit, offset)` + `countActiveGraph(sourceId?)`（JOIN chunk/document，active=1）；详情用 EventDao.byId、EventEntityDao.byEvents、EntityDao.byIds、ChunkDao.chunksByIds、DocumentDao.byIds、SourceDao.byIds。
- UI：SourcesScreen 增加「来源 / 图谱」切换；GraphViewModel 懒 DB、source 过滤、分页（页 50/上限 200）、event detail overlay；无抽取空态；entity chips(≤8+N)。
- 性能：不物化全局图；按 source/分页查询；单页 ≤50，总渲染 ≤200；避免超过预算的节点/边。

## Settings/预算
- `SagBudgetTier`（HIGH/MID/LOW）→ PRD §7.2 预算；`SagBudgetPreferences` 存档位；Search/Chat 每次请求读取；Settings chips + 当前预算展示。
- 诊断：复用 QueryTraceDao recent 50，脱敏 JSON（hash/timing/degraded/citation状态），无正文；错误可读/可重试。
- 备份 v3 UI：现有 KnowledgeBackup export/import/preview，补模型/空间依赖、旧备份补抽取提示、chat/trace 不导出说明；不改 backup 内部。

## 验收
- JVM/instrumentation/Compose；PJZ110 + K40（如可用）真机；三档矩阵/多跳/单跳/备份回环/删除/禁网；发布候选 assembleRelease（签名沿现有流程）。
