# B14.2 报告：source.originUrl（URL 溯源）

- **日期**：2026-10-20
- **状态**：**完成**（schema v8 / 迁移 / 备份 / staging 旁车 / Sources UI；设备验证通过）
- **目标**：把网页原始 URL 写入 source/document 来源元数据，替代“只保留标题”的临时方案。

## 1. 数据层

- `SourceEntity` 末尾新增 `originUrl: String? = null`（默认保持旧构造兼容）。
- Room **version 8**：`MIGRATION_7_8 = ALTER TABLE source ADD COLUMN originUrl TEXT`；`schema_migrations=8` + FK 自检；`app/schemas/.../8.json` 由 KSP 生成。
- 备份格式保持 **v3**（新增 nullable 字段，向后兼容）：`sourceJson` 写 `originUrl`，`parseSources` 可选读取（v1/v2/v3 旧包缺失→null）；`BackupV3RoundTripTest` 断言恢复。
- 迁移测试：`MigrationV7ToV8Test`（v7→v8、v1→v8 全链）+ 既有 MigrationTest 更新 builder；`SourceOriginUrlDaoTest` roundtrip/null/更新。

## 2. staging 旁车契约

- 命名：staging 同目录 `"<fileName>.origin"`（UTF-8 单行 URL；≤8KiB）。
- 读取：`SourceOriginUrls.read`；仅 HTTPS、无 userinfo、host/authority 非空、≤2048；非法 URL 视为无 URL 并删除旁车，不阻断导入。
- 清理：成功/去重后删除旁车；单文件异常/取消保留文件+旁车供重试；批次结束清理孤儿旁车。
- `importFile(staged, originUrl = null)` 默认 null；UI URL 导入在正文原子落盘后原子写旁车，旁车失败回滚正文并报可读错误。

## 3. UI

- `ImportViewModel.importUrl` 写 `<target>.origin`（core 读取/消费；UI 不直接写 DB）。
- Sources 来源卡显示「网页来源：<URL>」可点击（`LocalUriHandler`，长 URL ellipsis）；预览样例覆盖。

## 4. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **604 / 0**、**585 / 0** |
| lint | 0 errors / 47 warnings |
| Android DB/备份/迁移 | K60U **12/12**：MigrationV7→V8、SourceOriginUrlDao、BackupV3RoundTrip、MigrationTest |
| 真机 origin E2E | staging 旁车→导入后 source.originUrl=`https://example.com/article`；旁车已删除；Sources UI 展示链接（证据 `B14.2-origin-e2e-k60u.txt`、PNG/XML） |
| schema | 8.json 入库；v7→v8 旧行 originUrl=NULL |

## 5. 限制

- 旧版 App 读取新备份会忽略 originUrl 字段（v3 向后兼容）；新 App 读旧包→null。
- URL 旁车仅由 URL 导入写；SAF/TXT 文件没有 originUrl（符合语义）。
- Schema v8 需要 v7→v8 迁移；回滚到 v0.1.1（v7）需先备份，旧代码不识别新列（SQLite 加列向后兼容通常可读，但未承诺）。
- 真机 UI：K60U HyperOS Compose instrumentation 仍不稳定；URL 旁车/Sources 链接已通过 staging autostart + UI dump 验证，正式 UI 点击测试留稳定设备。

## 6. 文件/提交

- 主要文件：`data/db/{Entities,AppDatabase}.kt`、`app/schemas/.../8.json`、`core/backup/KnowledgeBackup.kt`、`core/ingest/IngestRepository.kt`、`core/ingest/SourceOriginUrls.kt`、`ui/{ImportViewModel,SourcesScreen}.kt`、`res/values/strings.xml`。
- 测试：`SourceOriginUrlsTest` 6、`StagingOriginSidecarRobolectricTest` 7、`OriginSidecarTest` 5、迁移/DAO/备份设备测试 12。
- 后续：B14.3 Office 流式解析；OCR/用户密码 PDF 按 P1.1 触发。
