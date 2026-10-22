package com.zhibox.memory.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状系统（DESIGN.md Shapes 章节：「pronounced, friendly curves」）。
 *
 * 数值来源优先级：`design/main_screen/code.html`（实际渲染稿）> DESIGN.md 文字描述。
 * 参考稿卡片用 Tailwind `rounded-2xl` = 16dp，浮岛用 `rounded-[28px]`，徽标方块 16dp，
 * 药丸/发送键 `rounded-full`。DESIGN.md 写「rounded-3xl 1.5–2rem」，二者冲突，
 * 这里以能复现截图的 16dp 为准，并在 [ZhiBoxShapes] 备注；如需放大只改这一处。
 */
object ZhiBoxRadius {
    /** 卡片圆角（参考稿 rounded-2xl）。 */
    val card = 16.dp

    /** 内容徽标方块圆角（参考稿 56dp 方块 + rounded-2xl）。 */
    val badge = 16.dp

    /** 悬浮输入岛圆角（参考稿 rounded-[28px]）。 */
    val omniBar = 28.dp

    /** 附件按钮圆角（参考稿 rounded-2xl）。 */
    val attachment = 16.dp

    /** 药丸/圆形按钮。 */
    val pill = 9999.dp
}

val ZhiBoxShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(ZhiBoxRadius.card),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(ZhiBoxRadius.omniBar),
)
