# P1.2 计划：实体别名表 + 抽取质量专项（B15）

- **日期**：2026-10-20
- **选择依据**：P1 backlog 按价值/成本排序，先做实体别名（直接影响实体扩展召回），再做抽取质量诊断；Explore/3D 作为后续独立批次。
- **原则**：别名只影响查询扩展/索引，不改 append-only event 语义；不重写历史抽取；所有持久化字段走 schema 迁移/备份/回滚预案。

## 1. 实体别名（B15.1）

### 数据/契约
- schema **v9** 新增 `entity_alias`（`id/entityId/alias/normalizedAlias/createdAt`）：
  - FK `entityId` → entity.id CASCADE；`normalizedAlias` 唯一；`entityId` 索引；
  - alias 归一化复用 `SagSupport.normalizeEntityName`（NFC+trim+lower+空白合一）；
  - 不新增/修改 event/entity 既有列；alias 可删可增，不改 append-only。
- DAO：`insertIgnore/upsertAll/byEntity/byNormalizedAlias/byNormalizedAliases/delete/deleteAll/page/count`。
- 查询扩展：
  - `LexicalQueryEntityExtractor` 精确/子串候选同时查 alias → canonical entity；命中 source 记为 `entity_alias`；
  - `entity_fts` 增加 alias 行（或重建包含 alias），alias 删除后重建派生 FTS；entity 名称本体语义不变。
- 写入/管理：
  - Graph 事件详情实体行展示别名；用户可新增/删除别名（HTTPS/无特殊校验；长度/数量上限，拒绝重复/空/自指）；
  - 抽取管线**不自动写 alias**（先由用户/后续 prompt 版本引入；避免 LLM 幻觉别名污染索引）。
- 备份：v3 新增可选 entry `entity_aliases.jsonl`，只导出包内 entity 的别名；旧包缺 entry→空；未知 entry 继续跳过；FORMAT_VERSION 保持 3（additive）。
- 迁移/回滚：v8→v9 只建表 + schema_migrations=9；旧库 alias 空；v9 库回滚 v8 前先备份（旧代码忽略未知表/entry）。

### 门禁
- schema/migration/DAO/备份回环/查询扩展/别名 UI 测试；JVM/lint/release；K60U 真机：事件详情新增 alias→按 alias 检索命中→删除 alias 不再命中；备份导出→清库→导入保留 alias。
- 不改变无 alias 时的检索结果（回归）。

## 2. 抽取质量专项（B15.2，别名后）

- 诊断数据（只读）：实体类型分布、alias 覆盖率、抽取失败/未配置计数、event/entity 空值率；
- 设置/诊断页只读卡片 + 导出脱敏 JSON；不含正文；
- 中文 prompt 迭代/模型对比只在有 LLM key 窗口做抽检，不阻塞离线。
- 触发：B9 抽检未达质量线或用户反馈实体缺失；本批先提供可观测性，不伪造质量结论。

## 3. 状态

- B15.1（alias）代码/JVM 完成（`P1_2_ALIAS_REPORT.md`）；设备门禁待 K60U 重连。
- B15.2（抽取质量诊断）未开工，待 alias 设备门禁关闭后开启。

## 4. 顺序

1. B15.1 core：schema v9/DAO/迁移/查询扩展/FTS（core-fixer）。
2. B15.1 backup（data-reviewer）与 UI（ui-fixer）并行（core 契约冻结后）。
3. 设备门禁 + 报告；之后 B15.2 质量诊断。
