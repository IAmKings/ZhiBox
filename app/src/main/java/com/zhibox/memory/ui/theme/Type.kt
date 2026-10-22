package com.zhibox.memory.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zhibox.memory.R

/**
 * 排版系统（DESIGN.md Typography 章节）。
 *
 * - 标题族 **Plus Jakarta Sans**（600/700）：几何、亲切、编辑感；仅随包提供 Latin 子集，
 *   中文回落系统中文字体（Android 默认 Noto Sans CJK），行高已按 1.35–1.45 预留裕量。
 * - 正文族 **Inter**（400/500/600）：高密度扫描与证据阅读。
 * - 品牌标题「智匣」使用 [BrandSerif]：DESIGN.md 未指定中文字体，参考稿用 Noto Serif SC；
 *   为避免打进 ~10MB CJK 字体，这里映射到系统 serif（Android 自带 Noto Serif CJK），
 *   在绝大多数设备上可复现参考稿的宋体观感，缺失时安全回落默认字体。
 *
 * 字号/行高逐条对齐 DESIGN.md 的 typography 令牌（名称 → M3 role 映射见每段注释）。
 */

/** 标题字体：Plus Jakarta Sans（600/700）。 */
val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_600, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.plus_jakarta_sans_700, FontWeight.Bold, FontStyle.Normal),
)

/** 正文字体：Inter（400/500/600）。 */
val InterSans = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.inter_500, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.inter_600, FontWeight.SemiBold, FontStyle.Normal),
)

/**
 * 中文品牌标题专用：「智匣」两字的 Noto Serif SC 子集（约 2.4KB）。
 * 参考稿用 Noto Serif SC 700；整族 CJK 字体 ~10MB+，这里只子集品牌名两个码位，
 * 缺字时 Compose 自动回落系统字体，不会缺字方块。
 */
val BrandSerif: FontFamily = FontFamily(
    Font(R.font.noto_serif_sc_brand_700, FontWeight.Bold, FontStyle.Normal),
)

private fun headline(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Double,
) = TextStyle(
    fontFamily = PlusJakartaSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.em,
)

private fun body(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = InterSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.em,
)

val ZhiBoxTypography = Typography(
    // headline-xl 32/40/700/-0.02em（大屏主标题）
    displaySmall = headline(32, 40, FontWeight.Bold, -0.02),
    // headline-xl-mobile 26/34/700/-0.01em（移动端主标题）
    headlineLarge = headline(26, 34, FontWeight.Bold, -0.01),
    // headline-lg 24/32/600/-0.01em
    headlineMedium = headline(24, 32, FontWeight.SemiBold, -0.01),
    // headline-md 18/24/600
    titleLarge = headline(18, 24, FontWeight.SemiBold, 0.0),
    // headline-sm 16/22/600
    titleMedium = headline(16, 22, FontWeight.SemiBold, 0.0),
    titleSmall = headline(14, 20, FontWeight.SemiBold, 0.0),

    // body-lg 16/24/400
    bodyLarge = body(16, 24),
    // body-md 14/20/400
    bodyMedium = body(14, 20),
    // body-sm 13/18/400
    bodySmall = body(13, 18),

    // label-lg 14/20/500
    labelLarge = body(14, 20, FontWeight.Medium),
    // label-md 12/16/500
    labelMedium = body(12, 16, FontWeight.Medium),
    // label-sm 11/14/500
    labelSmall = body(11, 14, FontWeight.Medium),
)
