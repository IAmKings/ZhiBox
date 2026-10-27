# SETTINGS_SEARCH —— 设置搜索（索引维护与交互）

> 2026-10-27 实施；代码：`ui/SettingsSearch.kt`（索引 + 纯逻辑）、`ui/SettingsSearchScreen.kt`（UI）。

## 1. 入口与交互

- 设置首页顶栏右侧搜索图标（`ZhiBoxTopBar.actions`）→ 屏内搜索页；
- 输入即搜（无需提交），标题完全匹配 > 标题包含 > 关键词包含；最多 30 条；
- 结果只导航，不执行开关/危险动作：
  - 高级与诊断条目 → 进入高级页并复用 `deepLinkTarget`（滚动 + 1.2s 高亮）；
  - 聚焦页条目 → 进入对应页并 `scrollToItem(itemKey)`（`settingsPageItemKeys` 定义 emit 顺序）；
- 空查询不展示结果；无结果展示空态；返回键先关闭搜索。

## 2. 索引维护规则（必须遵守）

1. 高级与诊断的 **17 个 section 必须全部有 entry**（`SettingsSearchTest` 断言）；
2. 新增设置卡片/二级页动作时：
   - 在 `SettingsSearchIndex.ENTRIES` 增加 entry：`page`、`titleRes`、`keywords`，
     ADVANCED 填 `sectionKey`，聚焦页填 `itemKey`（必须与 `SettingsPageContent` 的 item key 一致）；
   - 若聚焦页 item 顺序变化，同步更新 `settingsPageItemKeys`；
   - 运行 `:app:testDebugUnitTest --tests "com.zhibox.memory.ui.SettingsSearch*"`。
3. 关键词是搜索元数据，可包含术语（如 GPU/LLM/API Key），但标题与展示文案仍走 strings.xml。

## 3. 覆盖范围（当前）

| 页面 | 覆盖 |
|---|---|
| 高级与诊断 | 17 个 section：嵌入路径/模型商店/共享目录/GPU/模型文件/向量空间/LLM/问答开关/预算与 OCR/SAG 抽取/迁移/旧数据同步/知识库/索引检查/查询记录/抽取质量/隐私声明 |
| 模型管理 | 当前模型/管理模型 |
| 资料库 | 资料库概览/导入/导出/恢复备份 |
| 联网问答 | 联网问答/配置服务 |
| 隐私与关于 | 隐私页/检查更新/更新设置（自动检查、Beta）/版本 |

## 4. 测试

- `SettingsSearchTest`（JVM）：17 section 全覆盖、entry id 唯一、page 归属、标题优先、大小写、空查询、未知查询、聚焦页下标；
- `SettingsSearchRobolectricTest`：输入「更新」出现结果、点击回调；无结果空态。
