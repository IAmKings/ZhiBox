package com.zhibox.memory.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * ZhiBox 主题入口（UI 改版：暖纸极简）。
 *
 * - M3 [ColorScheme] 承载通用语义 role；品牌特有语义（卡片底、极细描边、四类内容徽标）
 *   走 [LocalZhiBoxExtendedColors]，feature 代码不得直接写 `Color(0xFF...)`。
 * - minSdk 24：不启用动态取色（Material You 需 API 31+），固定品牌色板。
 * - 明暗双主题都必须可用（项目规范：缺暗色变体视为 bug；DESIGN.md 未定义暗色，
 *   这里按同一暖色相推导，见 Color.kt 的 [DarkExtendedColors]）。
 * - M3 的 surface* 一组必须全部显式给出：缺失时 Card/TopAppBar 会回落 M3 基线紫灰
 *   （B3-3 真机回归过的同类问题）。
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Obsidian,
    onPrimary = Color.White,
    primaryContainer = PillInactive,
    onPrimaryContainer = Ink,
    inversePrimary = Divider,
    secondary = BreathingGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F3E8),
    onSecondaryContainer = Color(0xFF14501A),
    tertiary = BadgeSnapshotContent,
    onTertiary = Color.White,
    tertiaryContainer = BadgeSnapshotContainer,
    onTertiaryContainer = Color(0xFF4A3A28),
    background = PaperCream,
    onBackground = Ink,
    // surface = 卡片白；画布 = background(paper)。两者分开，Card 默认底色才不会糊在米底上。
    surface = CardPure,
    onSurface = Ink,
    surfaceVariant = PillInactive,
    onSurfaceVariant = InkSecondary,
    surfaceTint = Obsidian,
    inverseSurface = Ink,
    inverseOnSurface = PaperCream,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFD5CDC4),
    outlineVariant = Divider,
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFFDF9),
    surfaceDim = Color(0xFFE8E2DA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF9F6),
    surfaceContainer = PaperCream,
    surfaceContainerHigh = Color(0xFFF4EFEA),
    // M3 Card 默认容器色读 surfaceContainerHighest；这里对齐设计令牌 card-pure，
    // 让设置/来源/图谱等页面的卡片与首页卡片同为纯白（画布仍是 paper，两色分开）。
    surfaceContainerHighest = CardPure,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = InkDark,
    onPrimary = Ink,
    primaryContainer = Color(0xFF3A3733),
    onPrimaryContainer = InkDark,
    inversePrimary = Obsidian,
    secondary = Color(0xFF8ED08F),
    onSecondary = Color(0xFF0B2E0F),
    secondaryContainer = Color(0xFF1E4A22),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = Color(0xFFD9C3A6),
    onTertiary = Color(0xFF3B2E1F),
    tertiaryContainer = Color(0xFF332C22),
    onTertiaryContainer = Color(0xFFF0DEC8),
    background = PaperCreamDark,
    onBackground = InkDark,
    surface = CardPureDark,
    onSurface = InkDark,
    surfaceVariant = PillInactiveDark,
    onSurfaceVariant = InkSecondaryDark,
    surfaceTint = InkDark,
    inverseSurface = InkDark,
    inverseOnSurface = Ink,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF6B655D),
    outlineVariant = BorderSubtleDark,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A2724),
    surfaceDim = PaperCreamDark,
    surfaceContainerLowest = Color(0xFF0D0D0C),
    surfaceContainerLow = Color(0xFF171614),
    surfaceContainer = CardPureDark,
    surfaceContainerHigh = Color(0xFF232120),
    // 深色同理：卡片用 card-pure-dark，比米色 pill 容器更接近设计稿的"卡片浮在墨底上"。
    surfaceContainerHighest = CardPureDark,
)

/**
 * 扩展色入口：`MaterialTheme.zhiBoxColors.badgeNoteContent`。
 * 与 `MaterialTheme.colorScheme` 对称，避免在 composable 里直接读 CompositionLocal。
 */
val MaterialTheme.zhiBoxColors: ZhiBoxExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalZhiBoxExtendedColors.current

@Composable
fun ZhiBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalZhiBoxExtendedColors provides
            if (darkTheme) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ZhiBoxTypography,
            shapes = ZhiBoxShapes,
            content = content,
        )
    }
}
