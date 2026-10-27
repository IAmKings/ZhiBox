package com.zhibox.memory.ui

import androidx.annotation.StringRes
import com.zhibox.memory.R

/**
 * 设置搜索索引与纯逻辑。
 *
 * 约束：
 * - 高级与诊断的 17 个 section **必须全部覆盖**（测试断言），新增设置项必须补 entry；
 * - entry 只做导航（页面 + 锚点），不直接执行开关/危险动作；
 * - 标题/页面名用资源 ID，关键词是搜索元数据（不直接展示）。
 */
data class SettingsSearchEntry(
    val id: String,
    val page: SettingsPage,
    @StringRes val titleRes: Int,
    val keywords: List<String> = emptyList(),
    /** 高级页锚点（IntelligenceSection.itemKey）。 */
    val sectionKey: String? = null,
    /** 聚焦页内部滚动锚点（item key，见 [settingsPageItemKeys]）。 */
    val itemKey: String? = null,
)

object SettingsSearchIndex {

    private fun advanced(
        id: String,
        section: IntelligenceSection,
        @StringRes titleRes: Int,
        vararg keywords: String,
    ) = SettingsSearchEntry(
        id = id,
        page = SettingsPage.ADVANCED,
        titleRes = titleRes,
        keywords = keywords.toList(),
        sectionKey = section.itemKey,
    )

    val ENTRIES: List<SettingsSearchEntry> = buildList {
        // 高级与诊断 17 项（顺序与设置页一致）
        add(advanced("embedding", IntelligenceSection.Embedding, R.string.settings_section_embedding, "模型", "路径", "自动", "切换"))
        add(advanced("model_store", IntelligenceSection.ModelStore, R.string.settings_section_store, "下载模型", "商店", "440M", "270M", "740M"))
        add(advanced("model_shared", IntelligenceSection.ModelStoreShared, R.string.settings_store_shared_title, "共享", "目录", "模型"))
        add(advanced("gpu", IntelligenceSection.Gpu, R.string.settings_section_gpu, "GPU", "加速", "CPU"))
        add(advanced("model_files", IntelligenceSection.ModelFiles, R.string.settings_section_models, "模型文件", "删除"))
        add(advanced("vector_space", IntelligenceSection.VectorSpace, R.string.settings_section_spaces, "向量", "空间", "索引"))
        add(advanced("llm", IntelligenceSection.Llm, R.string.settings_section_llm, "LLM", "Base URL", "API Key", "密钥"))
        add(advanced("llm_behavior", IntelligenceSection.LlmBehavior, R.string.settings_llm_behavior_title, "SAG", "问答", "禁止网络", "联网"))
        add(advanced("budget", IntelligenceSection.Budget, R.string.settings_budget_section, "检索", "预算", "OCR", "扫描件"))
        add(advanced("sag", IntelligenceSection.Sag, R.string.settings_sag_section, "抽取", "并发", "补抽取", "重试"))
        add(advanced("migration", IntelligenceSection.Migration, R.string.settings_section_migration, "备份", "导入", "导出", "迁移"))
        add(advanced("reembed", IntelligenceSection.Reembed, R.string.settings_reembed_section, "旧数据", "同步", "重嵌入"))
        add(advanced("knowledge", IntelligenceSection.Knowledge, R.string.settings_section_kb, "知识库", "资料", "文档"))
        add(advanced("diagnostics", IntelligenceSection.Diagnostics, R.string.settings_section_diagnostics, "索引", "诊断", "检查", "修复"))
        add(advanced("trace", IntelligenceSection.Trace, R.string.settings_trace_section, "trace", "查询记录", "导出"))
        add(advanced("quality", IntelligenceSection.Quality, R.string.settings_quality_section, "质量", "抽取", "覆盖率"))
        add(advanced("privacy_statement", IntelligenceSection.Privacy, R.string.settings_section_privacy, "隐私", "离线", "声明"))

        // 聚焦页与常用动作（导航到对应页面/卡片；不执行动作）
        add(SettingsSearchEntry("home_manage_models", SettingsPage.MODELS, R.string.settings_home_manage, listOf("模型", "管理", "切换", "下载"), itemKey = "models-hero"))
        add(SettingsSearchEntry("home_current_model", SettingsPage.MODELS, R.string.settings_hero_current_model, listOf("当前模型", "备份"), itemKey = "models-hero"))
        add(SettingsSearchEntry("home_import", SettingsPage.LIBRARY, R.string.settings_home_import, listOf("导入", "文件", "资料"), itemKey = "library-hero"))
        add(SettingsSearchEntry("home_export", SettingsPage.LIBRARY, R.string.settings_home_export, listOf("导出", "备份", "压缩包"), itemKey = "library-hero"))
        add(SettingsSearchEntry("restore_backup", SettingsPage.LIBRARY, R.string.settings_restore_latest, listOf("恢复", "备份"), itemKey = "library-hero"))
        add(SettingsSearchEntry("library_overview", SettingsPage.LIBRARY, R.string.settings_hero_library_overview, listOf("资料库", "文档", "片段"), itemKey = "library-hero"))
        add(SettingsSearchEntry("network_page", SettingsPage.NETWORK, R.string.settings_home_network_title, listOf("联网", "问答", "LLM", "配置"), itemKey = "network-hero"))
        add(SettingsSearchEntry("network_config", SettingsPage.NETWORK, R.string.settings_home_network_config, listOf("配置", "服务", "Base URL", "API Key"), itemKey = "network-hero"))
        add(SettingsSearchEntry("privacy_page", SettingsPage.PRIVACY, R.string.settings_home_privacy_title, listOf("隐私", "关于", "版本"), itemKey = "privacy-hero"))
        add(SettingsSearchEntry("privacy_text", SettingsPage.PRIVACY, R.string.settings_section_privacy, listOf("隐私", "离线", "声明"), itemKey = "section-10"))
        add(SettingsSearchEntry("check_update", SettingsPage.PRIVACY, R.string.update_row_title, listOf("更新", "检查更新", "升级"), itemKey = "update-row"))
        add(SettingsSearchEntry("update_settings", SettingsPage.PRIVACY, R.string.update_settings_title, listOf("更新设置", "自动检查", "Beta", "预发布"), itemKey = "update-settings"))
        add(SettingsSearchEntry("about_version", SettingsPage.PRIVACY, R.string.settings_privacy_version, listOf("版本", "关于", "App"), itemKey = "privacy-hero"))
    }
}

/** 搜索：标题完全匹配 > 标题包含 > 关键词包含；最多 [limit] 条。 */
fun searchSettings(
    query: String,
    entries: List<SettingsSearchEntry> = SettingsSearchIndex.ENTRIES,
    limit: Int = 30,
    titleOf: (Int) -> String,
): List<SettingsSearchEntry> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        val title = titleOf(entry.titleRes)
        val score = when {
            title.equals(q, ignoreCase = true) -> 0
            title.contains(q, ignoreCase = true) -> 1
            entry.keywords.any { it.contains(q, ignoreCase = true) } -> 2
            else -> return@mapNotNull null
        }
        score to entry
    }
        .sortedWith(compareBy({ it.first }, { titleOf(it.second.titleRes) }))
        .take(limit)
        .map { it.second }
}

/**
 * 聚焦页内部 emit 顺序（用于搜索结果滚动定位）。ADVANCED 由深链锚点处理，返回空。
 */
fun settingsPageItemKeys(
    page: SettingsPage,
    state: SettingsUiState,
): List<String> = when (page) {
    SettingsPage.HOME -> emptyList()
    SettingsPage.MODELS -> listOf("models-hero", "section-1-0", "section-1-1", "section-2", "section-3")
    SettingsPage.LIBRARY -> buildList {
        add("library-hero")
        add("section-5-0")
        if (state.reembedTotal > 0 || state.lastReembed != null || state.legacySpaces.isNotEmpty()) {
            add("section-5-1")
        }
        if (state.legacySpacesError != null) add("legacy-spaces-error")
        if (state.legacySpaces.isNotEmpty()) add("legacy-spaces")
        add("section-7")
    }

    SettingsPage.NETWORK -> listOf("network-hero", "section-8-0")
    SettingsPage.PRIVACY -> listOf("privacy-hero", "update-row", "update-settings", "section-10")
    SettingsPage.ADVANCED -> emptyList()
}

fun settingsPageItemIndex(
    page: SettingsPage,
    itemKey: String,
    state: SettingsUiState,
): Int = settingsPageItemKeys(page, state).indexOf(itemKey)
