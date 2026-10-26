package com.zhibox.memory.ui

/**
 * 智能状态中枢的深链目标（对应设置页锚点）。
 *
 * 契约：首页状态点的面板 → 关闭面板 → 设置页 `initialSection` 指定目标 →
 * 首帧布局完成后再滚动到该 section（避免闪跳）。
 *
 * [itemKey] 是设置页 LazyColumn 的 item key，与 UI 层一一对应；顺序与设置页实际
 * 分组顺序解耦（[SettingsItemOrder] 负责顺序），避免深链因分组调整而失效。
 */
enum class IntelligenceSection(val itemKey: String) {
    /** 嵌入路径（含模型/路径偏好）。 */
    Embedding("section-0"),

    /** 模型商店（推荐 + 下载）。 */
    ModelStore("section-1-0"),

    /** 共享模型目录。 */
    ModelStoreShared("section-1-1"),

    /** GPU 加速。 */
    Gpu("section-2"),

    /** 模型文件。 */
    ModelFiles("section-3"),

    /** 向量空间注册表。 */
    VectorSpace("section-4"),

    /** 知识库迁移（备份导出/导入）。 */
    Migration("section-5-0"),

    /** 旧数据同步（重嵌入）。 */
    Reembed("section-5-1"),

    /** 索引诊断。 */
    Diagnostics("section-6-0"),

    /** 检索 / 问答 trace。 */
    Trace("section-6-1"),

    /** 抽取质量。 */
    Quality("section-6-2"),

    /** 知识库（清空数据）。 */
    Knowledge("section-7"),

    /** SAG 增强 / LLM 配置。 */
    Llm("section-8-0"),

    /** SAG 增强与问答开关。 */
    LlmBehavior("section-8-1"),

    /** 检索预算与 OCR。 */
    Budget("section-8-2"),

    /** SAG 抽取状态。 */
    Sag("section-9"),

    /** 隐私与离线声明。 */
    Privacy("section-10"),
}

/**
 * 设置页 item 的可见性快照（纯数据，便于 JVM 单测）。
 *
 * 设置页的部分 item 是条件渲染的（loading / error / 旧空间告警），深链索引必须
 * 按"当前实际会 emit 的 item"计算，否则会滚到错误位置。
 */
data class SettingsItemVisibility(
    val loading: Boolean = false,
    val hasError: Boolean = false,
    val hasLegacySpacesError: Boolean = false,
    val hasLegacySpaces: Boolean = false,
)

/**
 * 设置页 LazyColumn 的 item 顺序（含分组标题 / 条件项）。
 *
 * 与 `SettingsPageContent(page = ADVANCED, …)` 的 emit 顺序必须保持一致；[settingsItemIndex]
 * 用它算深链索引（设置页简化后高级页仍按原顺序全量 emit，聚焦页按 `SettingsPage.sectionKeys` emit 子集）。
 */
object SettingsItemOrder {

    const val HEADER_INTELLIGENCE = "header-intelligence"
    const val SUB_MODELS = "sub-models"
    const val SUB_ASK = "sub-ask"
    const val SUB_RETRIEVAL = "sub-retrieval"
    const val HEADER_DATA = "header-data"
    const val HEADER_DIAGNOSTICS = "header-diagnostics"
    const val HEADER_PRIVACY = "header-privacy"

    /** 条件项 key（仅当对应 [SettingsItemVisibility] 为 true 时 emit）。 */
    private const val KEY_LOADING = "loading"
    private const val KEY_ERROR = "error"
    private const val KEY_LEGACY_SPACES_ERROR = "legacy-spaces-error"
    private const val KEY_LEGACY_SPACES = "legacy-spaces"

    /** 固定顺序：智能（模型与算力 / 问答与增强 / 检索质量）→ 数据 → 诊断 → 隐私与关于。 */
    val order: List<String> = listOf(
        KEY_LOADING,
        KEY_ERROR,
        HEADER_INTELLIGENCE,
        SUB_MODELS,
        IntelligenceSection.Embedding.itemKey,
        IntelligenceSection.ModelStore.itemKey,
        IntelligenceSection.ModelStoreShared.itemKey,
        IntelligenceSection.Gpu.itemKey,
        IntelligenceSection.ModelFiles.itemKey,
        IntelligenceSection.VectorSpace.itemKey,
        SUB_ASK,
        IntelligenceSection.Llm.itemKey,
        IntelligenceSection.LlmBehavior.itemKey,
        SUB_RETRIEVAL,
        IntelligenceSection.Budget.itemKey,
        IntelligenceSection.Sag.itemKey,
        HEADER_DATA,
        IntelligenceSection.Migration.itemKey,
        IntelligenceSection.Reembed.itemKey,
        KEY_LEGACY_SPACES_ERROR,
        KEY_LEGACY_SPACES,
        IntelligenceSection.Knowledge.itemKey,
        HEADER_DIAGNOSTICS,
        IntelligenceSection.Diagnostics.itemKey,
        IntelligenceSection.Trace.itemKey,
        IntelligenceSection.Quality.itemKey,
        HEADER_PRIVACY,
        IntelligenceSection.Privacy.itemKey,
    )

    /** 判断某个 key 在当前可见性下是否真的被 emit。 */
    fun isEmitted(key: String, visibility: SettingsItemVisibility): Boolean = when (key) {
        KEY_LOADING -> visibility.loading
        KEY_ERROR -> visibility.hasError
        KEY_LEGACY_SPACES_ERROR -> visibility.hasLegacySpacesError
        KEY_LEGACY_SPACES -> visibility.hasLegacySpaces
        else -> true
    }

    /**
     * 深链索引：目标 section 在"当前实际 emit 的 item"列表中的下标。
     * 目标 section 无条件 emit，因此只要按顺序数已 emit 的前置项即可。
     */
    fun settingsItemIndex(section: IntelligenceSection, visibility: SettingsItemVisibility): Int {
        var index = 0
        for (key in order) {
            if (key == section.itemKey) return index
            if (isEmitted(key, visibility)) index++
        }
        // 契约兜底：section 永远在 order 里；返回 0 让深链仍可用（不崩溃）。
        return 0
    }
}
