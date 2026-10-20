# P1.2 报告：实体别名表（B15.1）

- **日期**：2026-10-20
- **状态**：代码/JVM/设备验证完成（K60U 2026-10-20）
- **目标**：别名/缩写/全称合并，只影响索引/查询扩展；不改 append-only event/entity 语义。

## 1. 数据与契约

- schema **v9** 新增 `entity_alias`：
  - `id/entityId/alias/normalizedAlias/createdAt`；FK entity CASCADE；normalizedAlias 全局唯一；entityId 索引。
  - `MIGRATION_8_9` 建表 + schema_migrations=9 + FK 自检；`9.json` 由 KSP 生成。
- `EntityAliasRepository`：
  - normalize 复用 `SagSupport.normalizeEntityName`；长度 2..64；拒绝空/控制字符/等于 canonical/其它实体 canonical/重复/超 20 个别名；
  - 增删事务内刷新该 entity 的 `entity_fts`（name+description+全部 aliases，与 pipeline/rebuild 同一口径）；
  - 只改 alias + 派生 FTS，不改 entity/event/event_entity。
- 查询：`LexicalQueryEntityExtractor` 精确/子串 alias → canonical entity（source=`entity_alias`），FTS 可命中 alias；无 alias 回归不变。
- FTS：`SagFtsRebuilder` 与抽取 pipeline 均包含 aliases。
- 备份 v3：可选 entry `entity_aliases.jsonl`，只导出包内 entity 的 alias；旧包缺失为空；孤儿/重复/超长拒绝整包；导入后重建 FTS。
- UI：Graph 事件详情实体行展示「别名：…」+「别名」管理对话框（增删、可读错误、防连点、即时刷新）。

## 2. 测试

| 检查 | 结果 |
|---|---|
| JVM debug / release | **700 / 0**、**681 / 0** |
| lint | 0 errors / 55 warnings |
| core/extraction alias | repository 校验/上限/冲突/CRUD/FTS 测试 |
| core/sag alias | 精确/子串/FTS alias 召回 + 无 alias 回归 |
| backup | 回环恢复 alias+FTS；旧包/孤儿/重复/超长/闭包外 |
| androidTest 设备 | K60U **16/16**：MigrationV8→V9、EntityAliasDao、BackupV3RoundTrip、既有 MigrationTest |
| UI 设备 | Graph 详情→添加 alias 2026→DB/entity_fts 刷新；删除→DB/FTS 清理；GraphAliasMapping JVM 4/4 |

## 3. 设备验证（K60U）

- Android 测试 **16/16**：schema v8→v9、EntityAliasDao、BackupV3RoundTrip、MigrationTest。
- UI：事件详情→别名对话框→添加 `2026`→DB alias 行 + `entity_fts` 含 alias；删除→DB/FTS 清理；见 `evidence/P1.2-alias-e2e-k60u.txt`。
- 备份回环 alias+FTS 恢复由 `BackupV3RoundTripTest` 设备执行通过。
- 查询扩展 alias 精确/子串/FTS 由 JVM `LexicalQueryEntityAliasRobolectricTest` 覆盖。

## 4. 风险/边界

- alias 只影响查询扩展/派生 FTS；不重写历史抽取，不自动从 LLM 生成（避免幻觉污染）。
- 无 alias 的包仍写空 `entity_aliases.jsonl`（旧 App 跳过未知 entry，新 App 缺省空）。
- 别名规则/常量被备份校验复用，未来变更需同步备份兼容。
- 备份/迁移拒绝类用例仅编译，需设备执行。
- “抽取质量专项”（类型分布/覆盖率/失败回填诊断）作为 B15.2 后续，不与 alias 混批。
