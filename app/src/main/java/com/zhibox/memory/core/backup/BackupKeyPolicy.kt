package com.zhibox.memory.core.backup

/**
 * 知识库备份的键策略（`docs/product/INTELLIGENCE_HUB_SPEC.md` §15「采纳建议 C」）。
 *
 * 背景：`app_kv` 会随知识库备份一起迁移（v2 起），其中大部分是跨设备仍然有意义的状态
 * （如 `reembed_last_done`）。但**纯本机 UI 提示状态**（"某个引导条是否看过"）不应该跟着
 * 备份走——否则把知识库恢复到一台没有模型的新设备时，"已看过引导条"会让新设备永远看不到
 * 下载模型的提示，与"新设备首次使用"的直觉相反。
 *
 * 约定：这类键统一加前缀 [UI_HINT_KEY_PREFIX]，导出与导入两侧都过滤（导入侧过滤是为了
 * 兼容历史/手工构造的备份包）。
 */
const val UI_HINT_KEY_PREFIX = "ui_hint_"

/** 是否属于"不随知识库备份迁移"的本机 UI 提示键。 */
fun isUiHintKey(key: String): Boolean = key.startsWith(UI_HINT_KEY_PREFIX)
