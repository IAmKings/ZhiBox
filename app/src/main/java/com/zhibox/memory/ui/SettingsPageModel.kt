package com.zhibox.memory.ui

import androidx.annotation.StringRes
import com.zhibox.memory.R

/**
 * 设置页简化（简单模式 + 高级与诊断）的纯逻辑模型。
 *
 * 设计约束（见 docs/product/设置页简化方案（简单模式 + 详细模式）.txt 的审查结论）：
 * - **不新增路由**：二级页是设置屏内部的页面状态（与 SearchScreen 的证据详情页同构），
 *   MainActivity 仍只有一条 `"settings"` 路由；
 * - **不改高级页顺序**：高级页按现有的 17 个 section 顺序 emit，`SettingsItemOrder` /
 *   深链下标 / `SettingsItemOrderTest` 保持不变；
 * - 本文件不依赖 Android 运行时，便于纯 JVM 单测覆盖页面归属、模型档位与首页摘要。
 */
enum class SettingsPage(@StringRes val titleRes: Int) {
    /** 默认设置页：4 张主卡 + 高级入口。 */
    HOME(R.string.settings_title),

    /** 模型管理：hero（三档 + 下载/切换）+ 模型商店/共享目录/GPU/模型文件。 */
    MODELS(R.string.settings_page_models),

    /** 资料库：hero（资料/备份概览）+ 迁移/旧数据同步/知识库。 */
    LIBRARY(R.string.settings_page_library),

    /** 联网问答：hero（允许联网问答开关）+ LLM 服务配置。 */
    NETWORK(R.string.settings_page_network),

    /** 隐私与关于：完整隐私声明 + 版本。 */
    PRIVACY(R.string.settings_page_privacy),

    /** 高级与诊断：原 17 项全量（现有分组与顺序不变）+ 技术细节开关。 */
    ADVANCED(R.string.settings_page_advanced),
}

/** 高级页包含的 17 个 section（全量，不重排）。 */
private val ADVANCED_SECTION_KEYS: Set<String> =
    IntelligenceSection.entries.mapTo(LinkedHashSet()) { it.itemKey }

/**
 * 每个页面在自己的列表里 emit 哪些 section key（顺序由 `SettingsPageContent` 的代码顺序决定）。
 *
 * 说明：高级页是超集；聚焦页是精选子集。**同一张卡只有一份 UI 代码**（按页 emit），
 * 因此不存在两份实现漂移的问题。
 */
val SettingsPage.sectionKeys: Set<String>
    get() = when (this) {
        SettingsPage.HOME -> emptySet()
        SettingsPage.MODELS -> setOf(
            IntelligenceSection.ModelStore.itemKey,
            IntelligenceSection.ModelStoreShared.itemKey,
            IntelligenceSection.Gpu.itemKey,
            IntelligenceSection.ModelFiles.itemKey,
        )
        SettingsPage.LIBRARY -> setOf(
            IntelligenceSection.Migration.itemKey,
            IntelligenceSection.Reembed.itemKey,
            IntelligenceSection.Knowledge.itemKey,
        )
        SettingsPage.NETWORK -> setOf(IntelligenceSection.Llm.itemKey)
        SettingsPage.PRIVACY -> setOf(IntelligenceSection.Privacy.itemKey)
        SettingsPage.ADVANCED -> ADVANCED_SECTION_KEYS
    }

/** 当前页面是否渲染某个 section（非 section 的标题/条件项由页面代码显式控制）。 */
fun SettingsPage.shows(sectionKey: String): Boolean = sectionKey in sectionKeys

/** 深链目标：任何 section 深链先进入「高级与诊断」，再按现有锚点滚动并高亮。 */
fun settingsPageFor(@Suppress("UNUSED_PARAMETER") section: IntelligenceSection): SettingsPage =
    SettingsPage.ADVANCED

/** 系统返回 / 顶栏返回的纯决策结果，便于 JVM 单测。 */
enum class SettingsBackAction { EXIT_SETTINGS, TO_HOME }

/**
 * 返回决策：
 * - 简单首页 → 退出设置（下载中仍由 UI 层弹离开确认）；
 * - 深链直接进入的高级页 → 退出设置（保持「从状态中枢进设置，返回即回首页」的肌肉记忆）；
 * - 其它二级页 → 回简单首页（不退出）。
 */
fun settingsBackAction(page: SettingsPage, enteredViaDeepLink: Boolean): SettingsBackAction =
    when {
        page == SettingsPage.HOME -> SettingsBackAction.EXIT_SETTINGS
        page == SettingsPage.ADVANCED && enteredViaDeepLink -> SettingsBackAction.EXIT_SETTINGS
        else -> SettingsBackAction.TO_HOME
    }

/** 模型档位（首页/模型管理用用户语言，不暴露 A/B/D 与文件名）。 */
enum class SettingsModelTier {
    /** 未解析到任何可用模型。 */
    NONE,

    /** 270M（litertlm 引擎，实验）。 */
    LIGHT_270,

    /** 440M（默认/标准）。 */
    STANDARD_440,

    /** 740M（质量上限，实验）。 */
    STRONG_740,

    /** 非 catalog 的自定义模型。 */
    CUSTOM;

    /** 270M/740M 在 ModelCatalog 中 verified=false，UI 必须显式标注「实验」。 */
    val isExperimental: Boolean
        get() = this == LIGHT_270 || this == STRONG_740
}

/** 从当前生效模型文件名推断档位（只用于展示，不参与选型逻辑）。 */
fun settingsModelTier(fileName: String?): SettingsModelTier = when {
    fileName.isNullOrBlank() -> SettingsModelTier.NONE
    fileName.contains("270m", ignoreCase = true) -> SettingsModelTier.LIGHT_270
    fileName.contains("440m", ignoreCase = true) -> SettingsModelTier.STANDARD_440
    fileName.contains("740m", ignoreCase = true) -> SettingsModelTier.STRONG_740
    else -> SettingsModelTier.CUSTOM
}

/** 档位展示名（含「实验」后缀的语义由 UI 决定是否追加）。 */
@StringRes
fun SettingsModelTier.labelRes(): Int = when (this) {
    SettingsModelTier.NONE -> R.string.settings_tier_none
    SettingsModelTier.LIGHT_270 -> R.string.settings_tier_270
    SettingsModelTier.STANDARD_440 -> R.string.settings_tier_440
    SettingsModelTier.STRONG_740 -> R.string.settings_tier_740
    SettingsModelTier.CUSTOM -> R.string.settings_tier_custom
}

/**
 * 简单首页的展示摘要（纯数据，不含 Android 资源）。
 *
 * 首页只展示「结论」，不展示任何原始标识：文件名、spaceId、错误码、trace mode 等
 * 一律不上首页（术语黑名单见 `SettingsCopyAuditTest`）。
 */
data class SettingsHomeSummary(
    val loading: Boolean,
    val hasError: Boolean,
    val modelTier: SettingsModelTier,
    val modelCount: Int,
    val indexed: Boolean,
    val docsReady: Int,
    val chunks: Int,
    val backupAvailable: Boolean,
    val legacyCount: Int,
    val legacyError: Boolean,
    val reembedActive: Boolean,
    val downloading: Boolean,
    val downloadPct: Int,
    /** 读取完成前无法区分「未配置」与「未读取」，用 [llmLoaded] 区分。 */
    val llmLoaded: Boolean,
    val llmConfigured: Boolean,
    val answerEnabled: Boolean,
    val networkAllowed: Boolean,
    val consentGranted: Boolean,
) {
    /** 联网问答开关的 checked（两个偏好同时成立才真正可用）。 */
    val networkAnswerOn: Boolean get() = answerEnabled && networkAllowed

    /** 资料库是否有内容。 */
    val hasLibraryContent: Boolean get() = docsReady > 0 || chunks > 0
}

/** 由 UiState 派生首页摘要；无副作用、可纯 JVM 测试。 */
fun settingsHomeSummary(state: SettingsUiState): SettingsHomeSummary = SettingsHomeSummary(
    loading = state.loading,
    hasError = state.error != null || state.legacySpacesError != null,
    modelTier = settingsModelTier(state.model),
    modelCount = state.models.size,
    // 「已索引」的判据：库里有 chunk。仅找到模型不算索引完成。
    indexed = state.chunkCount > 0,
    docsReady = state.docReady,
    chunks = state.chunkCount,
    backupAvailable = state.hasAutoBackup,
    legacyCount = state.legacySpaces.size,
    legacyError = state.legacySpacesError != null,
    reembedActive = state.reembedTotal > 0 && state.reembedDone < state.reembedTotal,
    downloading = state.downloading,
    downloadPct = state.downloadPct,
    llmLoaded = state.llmLoaded,
    llmConfigured = state.llm != null,
    answerEnabled = state.answerEnabled,
    // networkDisabled 是「禁网」语义，取反后才是「允许联网」。
    networkAllowed = !state.networkDisabled,
    consentGranted = state.answerNetworkConsent,
)
