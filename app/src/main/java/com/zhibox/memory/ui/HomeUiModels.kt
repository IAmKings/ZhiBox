package com.zhibox.memory.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.zhibox.memory.R

/**
 * 首页 UI 模型（纯展示层，不含 Room 实体）。
 *
 * 分层约定：domain/DB 实体在 [HomeViewModel] 边界映射成这里的 UI 模型，
 * 文案一律预格式化（时间戳/摘要），composable 只负责排版，便于 @Preview 与测试。
 */

/** 时间线分类过滤（对应参考稿顶部的药丸栏；「图谱」是入口不是过滤，见 HomeScreen）。 */
enum class HomeFilter(@StringRes val labelRes: Int) {
    ALL(R.string.home_filter_all),
    NOTE(R.string.home_filter_note),
    IMAGE(R.string.home_filter_image),
    DOCUMENT(R.string.home_filter_document),
}

/** 内容分类：决定卡片左侧徽标的配色/图形与页脚标签。 */
enum class HomeCategory(
    @StringRes val typeLabelRes: Int,
    @StringRes val tagRes: Int,
) {
    /** 念头 / 文本笔记（md、txt）。 */
    NOTE(R.string.home_card_type_note, R.string.home_tag_note),

    /** 图片（当前导入链路不支持位图，保留分类以对齐设计稿）。 */
    IMAGE(R.string.home_card_type_image, R.string.home_tag_image),

    /** 知识库切片（pdf、docx、pptx、xlsx）。 */
    DOCUMENT(R.string.home_card_type_document, R.string.home_tag_document),

    /** 网页快照 / 归档（html、网页 URL 抓取）。 */
    SNAPSHOT(R.string.home_card_type_snapshot, R.string.home_tag_archive),
}

/** 非 ready 文档的状态提示（ready 为 null，不额外占位）。 */
enum class HomeStatus(@StringRes val labelRes: Int) {
    PROCESSING(R.string.home_status_processing),
    FAILED(R.string.home_status_failed),
    CANCELLED(R.string.home_status_cancelled),
}

/** 时间线分组标签。 */
enum class HomeGroup(@StringRes val labelRes: Int) {
    TODAY(R.string.home_group_today),
    YESTERDAY(R.string.home_group_yesterday),
    EARLIER(R.string.home_group_earlier),
}

/** 单张内容卡。 */
@Immutable
data class HomeCardItem(
    /** 文档 id（列表稳定 key；点击 → 来源页定位本次先不做，见 HomeScreen 注释）。 */
    val id: String,
    val category: HomeCategory,
    /** 创建时间（毫秒），用于按本地自然日分组；不直接上屏。 */
    val createdAt: Long,
    /** 预格式化时间戳，如 `5/7 19:05`。 */
    val timeLabel: String,
    val title: String,
    /** 首块正文摘要（最多 2 行）。 */
    val preview: String,
    /** 页脚左侧来源名。 */
    val sourceLabel: String,
    val status: HomeStatus?,
)

/** 一个日期分组。 */
@Immutable
data class HomeTimelineGroup(
    val label: HomeGroup,
    val items: List<HomeCardItem>,
)

/** 首页整屏状态（Loading / Loaded / Error 三态，无布尔动物园）。 */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Loaded(
        val filter: HomeFilter,
        val groups: List<HomeTimelineGroup>,
        /** 未过滤的文档总数：用于区分「库为空」与「当前分类为空」。 */
        val totalCount: Int,
        /** 当前过滤命中的条数。 */
        val shownCount: Int,
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}
