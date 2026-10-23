package com.zhibox.memory.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * ZhiBox 线性图标集（24dp 视口）。
 *
 * 来源：`design/main_screen/code.html` 内联 SVG 的 path 数据逐条搬运——参考稿用的是
 * 2px 圆头线性图标，M3 的 material-icons 是填充风格，观感差异明显，因此不引入
 * `material-icons-extended`（体积大且风格不符），改为本地矢量。
 *
 * 约定：
 * - 颜色一律用 [Color.Black] 占位，实际渲染由 `Icon(tint = ...)` 覆盖；
 * - 线性图标 fill = null，只描边；`strokeWidth` 与参考稿 `stroke-[2]` / `stroke-[2.2]` 对齐；
 * - 命名 `ZhiBoxIcons.<Name>`，与 Material Icons 的 `Icons.Filled.<Name>` 用法一致。
 */
object ZhiBoxIcons {

    /** 搜索（参考稿 header 放大镜，stroke 2）。 */
    val Search: ImageVector by lazy {
        strokeIcon(
            name = "Search",
            pathData = "M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 " +
                "0010.607 10.607z",
        )
    }

    /** 更多（参考稿 header 竖三点）。 */
    val More: ImageVector by lazy {
        strokeIcon(
            name = "More",
            strokeWidth = 2.2f,
            pathData = "M12 6.75a.75.75 0 110-1.5.75.75 0 010 1.5z" +
                "M12 12.75a.75.75 0 110-1.5.75.75 0 010 1.5z" +
                "M12 18.75a.75.75 0 110-1.5.75.75 0 010 1.5z",
        )
    }

    /** 全部（参考稿「全部」药丸里 2×2 圆角方块，填充）。 */
    val Grid: ImageVector by lazy {
        filledIcon(
            name = "Grid",
            pathData = roundedRect(3f, 3f, 7f, 7f, 1.5f) +
                roundedRect(14f, 3f, 7f, 7f, 1.5f) +
                roundedRect(3f, 14f, 7f, 7f, 1.5f) +
                roundedRect(14f, 14f, 7f, 7f, 1.5f),
        )
    }

    /**
     * 念头（铅笔，stroke 2）。
     *
     * 设计稿原本用衬线右引号 `”` 表示「念头」，笔画细、留白多，与同列 book/globe 等线性图标
     * 视觉重量不一致。这里换成同规格（24 视口 / 2px 圆头线条）的铅笔：
     *   笔身 45° 对角铺满 + 笔尖 + 橡皮箍两道短分隔线，外接 15.9 × 15.9，
     *   与 Book(15.75 × 16.9) 密度相当；语义对应「随手记下念头」。
     *
     * 为什么不用气泡：应用自带「问答」功能（后续会放独立的对话按钮），气泡要留给对话语义。
     * 为什么不用便签纸：会与「文档 / 切片」的折角纸图标撞形。
     */
    val Pencil: ImageVector by lazy {
        strokeIcon(
            name = "Pencil",
            pathData = "M3.57,20.44L5.36,15.84L16.56,4.57A2.04,2.04 0 0 1 19.44,7.44L8.17,18.71Z" +
                // 笔尖分隔线
                "M5.36,15.84L8.17,18.71" +
                // 橡皮箍分隔线
                "M15.93,5.2L18.8,8.08",
        )
    }

    /** 图片（参考稿「图片」药丸，stroke 2）。 */
    val Image: ImageVector by lazy {
        strokeIcon(
            name = "Image",
            pathData = roundedRect(3f, 3f, 18f, 18f, 2f) +
                "M8.5 10a1.5 1.5 0 110-3 1.5 1.5 0 010 3z" +
                "M21 15l-5-5L5 21",
        )
    }

    /** 文档 / 切片（参考稿「文档 / 切片」药丸，stroke 2）。 */
    val Document: ImageVector by lazy {
        strokeIcon(
            name = "Document",
            pathData = "M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 " +
                "1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25" +
                "m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 " +
                "1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z",
        )
    }

    /** 图谱（参考稿「图谱」药丸：三节点连线，stroke 2）。 */
    val Graph: ImageVector by lazy {
        strokeIcon(
            name = "Graph",
            pathData = "M6 8.5a2.5 2.5 0 110-5 2.5 2.5 0 010 5z" +
                "M18 10.5a2.5 2.5 0 110-5 2.5 2.5 0 010 5z" +
                "M12 20.5a2.5 2.5 0 110-5 2.5 2.5 0 010 5z" +
                "M8 7.5l7.5 1.5M7.5 8l3 8M16.5 10l-3.5 6",
        )
    }

    /** 知识库（参考稿卡片 2 badge 的开卷图标，stroke 2）。 */
    val Book: ImageVector by lazy {
        strokeIcon(
            name = "Book",
            pathData = "M12 6.042A8.967 8.967 0 006 3.75c-1.052 0-2.062.18-3 .512v14.25" +
                "A8.987 8.987 0 016 18c2.305 0 4.408.867 6 2.292" +
                "m0-14.25a8.966 8.966 0 016-2.292c1.052 0 2.062.18 3 .512v14.25" +
                "A8.987 8.987 0 0018 18a8.967 8.967 0 00-6 2.292m0-14.25v14.25",
        )
    }

    /** 追溯（参考稿卡片 3 badge 的智能体头像，stroke 2）。 */
    val Insight: ImageVector by lazy {
        strokeIcon(
            name = "Insight",
            pathData = "M12 2a4 4 0 0 1 4 4v1a3 3 0 0 1 3 3v2a3 3 0 0 1-2 2.82V16" +
                "a4 4 0 0 1-4 4h-2a4 4 0 0 1-4-4v-1.18A3 3 0 0 1 5 12V9a3 3 0 0 1 3-3V6" +
                "a4 4 0 0 1 4-4z" +
                "M9 10h.01M15 10h.01M9.5 15a3.5 3.5 0 0 0 5 0",
        )
    }

    /** 网页快照（参考稿卡片 4 badge 的地球，stroke 2）。 */
    val Snapshot: ImageVector by lazy {
        strokeIcon(
            name = "Snapshot",
            pathData = "M12 21a9 9 0 110-18 9 9 0 010 18z" +
                "M3.6 9h16.8M3.6 15h16.8" +
                "M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18",
        )
    }

    /** 发送（参考稿悬浮岛右端的向上箭头，stroke 2.2）。 */
    val ArrowUp: ImageVector by lazy {
        strokeIcon(
            name = "ArrowUp",
            strokeWidth = 2.2f,
            pathData = "M12 19.5v-15m0 0l-6 6m6-6l6 6",
        )
    }

    /** 附件（参考稿悬浮岛左端的多媒体按钮，stroke 1.8）。 */
    val Attachment: ImageVector by lazy {
        strokeIcon(
            name = "Attachment",
            strokeWidth = 1.8f,
            pathData = roundedRect(3.5f, 3.5f, 17f, 17f, 3.5f) +
                "M8.5 10a1.5 1.5 0 110-3 1.5 1.5 0 010 3z" +
                "M20.5 15.5l-4.5-4.5-7.5 7.5",
        )
    }

    /**
     * 关闭（纯线条 ×，stroke 2、圆头）。
     *
     * 用于「一次性引导条」的关闭按钮。**不要**用 [Clear] 顶替：Clear 是"实心圆 + 挖空 ×"，
     * 在 16dp 下挖空只有 1~2px，抗锯齿后会糊成一团，且与整套线条图标风格不一致。
     */
    val Close: ImageVector by lazy {
        strokeIcon(
            name = "Close",
            pathData = "M6.5,6.5L17.5,17.5M17.5,6.5L6.5,17.5",
        )
    }

    /**
     * 清空输入（实心圆 + 挖空 ×，evenOdd）。
     *
     * 参考稿用的是 20×20 Material "cancel" 图标（圆心 10,10、半径 8），直接塞进 24 视口会
     * 整体偏左上且只有原尺寸 2/3，在小尺寸下看起来像"被切了一半的圆"。这里按 24 视口重绘：
     * 圆心 (12,12)、半径 8.6，× 用旋转 45° 的十字多边形挖空（单一路径，避免 evenOdd 重叠反填）。
     */
    val Clear: ImageVector by lazy {
        filledIcon(
            name = "Clear",
            fillType = PathFillType.EvenOdd,
            pathData = "M12,3.4 A8.6,8.6 0 1 0 12,20.6 A8.6,8.6 0 1 0 12,3.4 Z" +
                "M14.40,7.33 L16.67,9.60 L14.26,12.00 L16.67,14.40 L14.40,16.67 " +
                "L12.00,14.26 L9.60,16.67 L7.33,14.40 L9.74,12.00 L7.33,9.60 " +
                "L9.60,7.33 L12.00,9.74 Z",
        )
    }
}

// ── 构建辅助 ────────────────────────────────────────────────────────

private fun parse(pathData: String): List<PathNode> =
    PathParser().parsePathString(pathData).toNodes()

private fun strokeIcon(
    name: String,
    pathData: String,
    strokeWidth: Float = 2f,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = parse(pathData),
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = strokeWidth,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()

private fun filledIcon(
    name: String,
    pathData: String,
    fillType: PathFillType = PathFillType.NonZero,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = parse(pathData),
    pathFillType = fillType,
    fill = SolidColor(Color.Black),
    stroke = null,
).build()

/** 圆角矩形子路径（SVG path 语法，用于把参考稿的 <rect rx> 并入同一条 path）。 */
private fun roundedRect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radius: Float,
): String {
    val right = left + width
    val bottom = top + height
    return "M${left + radius},$top H${right - radius} " +
        "Q$right,$top $right,${top + radius} V${bottom - radius} " +
        "Q$right,$bottom ${right - radius},$bottom H${left + radius} " +
        "Q$left,$bottom $left,${bottom - radius} V${top + radius} " +
        "Q$left,$top ${left + radius},$top Z"
}
