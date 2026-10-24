package com.zhibox.memory.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ZhiBox 品牌色板（UI 改版：暖纸极简 + 触感现代 / Warm Cream Paper Minimalism）。
 *
 * 令牌来源与取舍（三份设计物料曾互相冲突，这里固定唯一事实源）：
 * - 品牌骨架（画布/墨色/呼吸绿/卡片/描边）：根目录 `DESIGN.md` 的 Colors 章节；
 * - 组件级色值（徽标底/徽标字/药丸/时间戳）：`design/main_screen/code.html`（该文件是
 *   `design/main_screen/screen.png` 的实际渲染源，像素级对齐截图）。
 *
 * 与参考稿的**刻意偏差**（可访问性修复，参考稿本身不达标）：
 * 1. 时间戳/元信息由 `#9E948A`(对比度 2.98:1) 改为 [InkWarm400] `#736B63`(5.23:1)；
 *    11px 正文文字需 ≥4.5:1。
 * 2. 徽标文字色整体加深一档（见下方 Badge*Content），参考稿 3.16–4.05:1 不满足小字号 AA。
 * 色相不变，仅压暗明度，视觉上仍是同一套暖色标签。
 */

// ── 品牌基色（DESIGN.md） ──────────────────────────────────────────
/** 画布：暖米纸底（DESIGN.md `paper-cream`）。 */
val PaperCream = Color(0xFFF7F5F0)

/** 主强调/操作填充：曜石黑（DESIGN.md `obsidian`），用于激活药丸、发送键。 */
val Obsidian = Color(0xFF121212)

/** 呼吸绿（DESIGN.md `breathing-green`）：仅用于离线就绪、活跃节点等状态语义。 */
val BreathingGreen = Color(0xFF2E7D32)

/** 卡片/浮岛底色（DESIGN.md `card-pure`）。 */
val CardPure = Color(0xFFFFFFFF)

/** 极细描边（DESIGN.md `border-subtle`）。 */
val BorderSubtle = Color(0xFFE5E1DA)

// ── 文本层级（参考稿 warm-gray 阶） ───────────────────────────────
/** 标题/正文主墨色（参考稿 `brand-dark` #1C1A18）。 */
val Ink = Color(0xFF1C1A18)

/** 次级正文（参考稿 warm-gray-500）。 */
val InkSecondary = Color(0xFF736B63)

/**
 * 时间戳/元信息/脚注。
 * 参考稿用 warm-gray-400 `#9E948A`（对白卡仅 2.98:1，不达 AA），此处压到
 * warm-gray-500 `#736B63`（5.23:1）。需要更弱的地方用 [InkFaint]，但不得用于正文信息。
 */
val InkMeta = InkSecondary

/** 仅装饰用极弱色（分隔线、禁用态、图标描边），禁止承载可读信息。 */
val InkFaint = Color(0xFF9E948A)

/** 正文段（卡片摘要）。 */
val InkBody = Color(0xFF4A443E)

/** 未激活药丸底色（参考稿 `#EFECE6`）。 */
val PillInactive = Color(0xFFEFECE6)

/** 未激活药丸文字（参考稿 `#4A443E`，8.14:1）。 */
val PillInactiveText = Color(0xFF4A443E)

/** 告警/需处理（浅色）：DESIGN.md secondary 系的暖琥珀，白字对比 5.06:1。 */
val WarningAmber = Color(0xFFB45309)

/** 告警/需处理（深色）：同一色相提亮，保证在 #121212 上可见（6.8:1）。 */
val WarningAmberDark = Color(0xFFD98A4F)

/** 分隔线（参考稿 `#E8E3DA` 80% → 与画布混色后近似值）。 */
val Divider = Color(0xFFE8E3DA)

// ── 四类内容徽标（底 / 字） ───────────────────────────────────────
/** 念头 / 灵感：杏色。文字由参考稿 #B87834(3.16:1) 压暗为 #95591C(4.90:1)。 */
val BadgeNoteContainer = Color(0xFFF6EEE3)
val BadgeNoteContent = Color(0xFF95591C)

/** 知识库 / 端侧切片：青绿。文字 #2B8367 → #1F6B52(5.60:1)。 */
val BadgeKnowledgeContainer = Color(0xFFE5F3EE)
val BadgeKnowledgeContent = Color(0xFF1F6B52)

/** 智盒探答 / 追溯：薰衣草紫。文字 #6854A4 已达标(5.26:1)，保留原值。 */
val BadgeInsightContainer = Color(0xFFEFEAFC)
val BadgeInsightContent = Color(0xFF6854A4)

/** 网页快照 / 归档：赭石。文字 #8E7356 → #6E563E(5.87:1)。 */
val BadgeSnapshotContainer = Color(0xFFF5ECE3)
val BadgeSnapshotContent = Color(0xFF6E563E)

/** 页脚标签：#灵感（参考稿 #B5732D → #96581F，5.14:1）。 */
val TagInspirationContainer = Color(0xFFFAF3EC)
val TagInspirationContent = Color(0xFF96581F)

/** 页脚标签：#本地脱敏（mint）。 */
val TagLocalContainer = Color(0xFFEDF7F2)
val TagLocalContent = Color(0xFF2A6E51)

/** 页脚标签：#置信度（淡紫）。 */
val TagConfidenceContainer = Color(0xFFF3EFFF)
val TagConfidenceContent = Color(0xFF6B57AE)

/** 页脚标签：#知识归档（中性）。 */
val TagArchiveContainer = Color(0xFFF3EFEA)
val TagArchiveContent = Color(0xFF4A443E)

// ── 暗色档（DESIGN.md 未定义暗色；按同一暖色相推导，满足项目「双主题」要求） ──
val PaperCreamDark = Color(0xFF121212)
val CardPureDark = Color(0xFF1B1A18)
val BorderSubtleDark = Color(0xFF37332E)
val InkDark = Color(0xFFEDEAE4)
val InkSecondaryDark = Color(0xFFB8B2A9)
val InkBodyDark = Color(0xFFD5CFC6)
val PillInactiveDark = Color(0xFF2A2724)
val PillInactiveTextDark = Color(0xFFD5CFC6)
val DividerDark = Color(0xFF37332E)

/**
 * 设计系统扩展色（M3 ColorScheme 之外的品牌语义令牌）。
 *
 * M3 的 role 集合只覆盖通用语义；卡片底、极细描边、四类内容徽标属于 ZhiBox 特有语义，
 * 通过 [LocalZhiBoxExtendedColors] 下发，feature 代码只消费令牌，不写裸色值。
 * 明暗双档必须成对提供（项目规范：缺暗色变体视为 bug）。
 */
@Immutable
data class ZhiBoxExtendedColors(
    val paper: Color,
    val card: Color,
    /** 底部悬浮输入岛底色：浅色下与卡片同白，暗色下需比画布抬一档否则整条不可见。 */
    val omniBar: Color,
    val border: Color,
    /** 浮层阴影色：暖黑低透明度（DESIGN.md：扩散、低透明度、暖色调）。卡片不使用。 */
    val shadow: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkBody: Color,
    val inkMeta: Color,
    val inkFaint: Color,
    val divider: Color,
    val pillInactive: Color,
    val pillInactiveText: Color,
    /** 告警/需处理（智能状态点的"部分就绪角标"与"异常"态）：可修复问题，不用错误红。 */
    val warning: Color,
    val badgeNoteContainer: Color,
    val badgeNoteContent: Color,
    val badgeKnowledgeContainer: Color,
    val badgeKnowledgeContent: Color,
    val badgeInsightContainer: Color,
    val badgeInsightContent: Color,
    val badgeSnapshotContainer: Color,
    val badgeSnapshotContent: Color,
    val tagInspirationContainer: Color,
    val tagInspirationContent: Color,
    val tagLocalContainer: Color,
    val tagLocalContent: Color,
    val tagConfidenceContainer: Color,
    val tagConfidenceContent: Color,
    val tagArchiveContainer: Color,
    val tagArchiveContent: Color,
)

val LightExtendedColors = ZhiBoxExtendedColors(
    paper = PaperCream,
    card = CardPure,
    omniBar = CardPure,
    border = BorderSubtle,
    shadow = Color(0x142D2319),
    ink = Ink,
    inkSecondary = InkSecondary,
    inkBody = InkBody,
    inkMeta = InkMeta,
    inkFaint = InkFaint,
    divider = Divider,
    pillInactive = PillInactive,
    pillInactiveText = PillInactiveText,
    warning = WarningAmber,
    badgeNoteContainer = BadgeNoteContainer,
    badgeNoteContent = BadgeNoteContent,
    badgeKnowledgeContainer = BadgeKnowledgeContainer,
    badgeKnowledgeContent = BadgeKnowledgeContent,
    badgeInsightContainer = BadgeInsightContainer,
    badgeInsightContent = BadgeInsightContent,
    badgeSnapshotContainer = BadgeSnapshotContainer,
    badgeSnapshotContent = BadgeSnapshotContent,
    tagInspirationContainer = TagInspirationContainer,
    tagInspirationContent = TagInspirationContent,
    tagLocalContainer = TagLocalContainer,
    tagLocalContent = TagLocalContent,
    tagConfidenceContainer = TagConfidenceContainer,
    tagConfidenceContent = TagConfidenceContent,
    tagArchiveContainer = TagArchiveContainer,
    tagArchiveContent = TagArchiveContent,
)

val DarkExtendedColors = LightExtendedColors.copy(
    paper = PaperCreamDark,
    card = CardPureDark,
    omniBar = Color(0xFF262320),
    border = BorderSubtleDark,
    shadow = Color(0x66000000),
    ink = InkDark,
    inkSecondary = InkSecondaryDark,
    inkBody = InkBodyDark,
    inkMeta = InkSecondaryDark,
    inkFaint = Color(0xFF7A736B),
    divider = DividerDark,
    pillInactive = PillInactiveDark,
    pillInactiveText = PillInactiveTextDark,
    warning = WarningAmberDark,
    // 暗色下徽标底压成同色相深底，文字用亮色，保持语义色相可辨
    badgeNoteContainer = Color(0xFF3A2C1B),
    badgeNoteContent = Color(0xFFF0C08A),
    badgeKnowledgeContainer = Color(0xFF17332A),
    badgeKnowledgeContent = Color(0xFF9FD9C3),
    badgeInsightContainer = Color(0xFF2A2540),
    badgeInsightContent = Color(0xFFC3B4F0),
    badgeSnapshotContainer = Color(0xFF332C22),
    badgeSnapshotContent = Color(0xFFD9C3A6),
    tagInspirationContainer = Color(0xFF3A2C1B),
    tagInspirationContent = Color(0xFFF0C08A),
    tagLocalContainer = Color(0xFF17332A),
    tagLocalContent = Color(0xFF9FD9C3),
    tagConfidenceContainer = Color(0xFF2A2540),
    tagConfidenceContent = Color(0xFFC3B4F0),
    tagArchiveContainer = Color(0xFF2A2724),
    tagArchiveContent = Color(0xFFD5CFC6),
)

/** 扩展色令牌入口：`LocalZhiBoxExtendedColors.current` 或 `MaterialTheme.zhiBoxColors`。 */
val LocalZhiBoxExtendedColors = staticCompositionLocalOf { LightExtendedColors }
