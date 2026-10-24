package com.zhibox.memory.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 深度层级令牌（DESIGN.md Elevation & Depth → Flat Surfaces 细则）。
 *
 * 设计原则：**扁平优先**——卡片用 1dp 发丝描边分层，不用阴影；
 * 只有真正浮在内容之上的元素（输入岛 / 模态面板）才允许轻阴影，且必须走这里的令牌，
 * 禁止在页面里写裸 `shadowElevation = 12.dp` 这类任意值。
 */
object ZhiBoxElevation {
    /** 卡片/列表容器：0 阴影，只靠描边（`ZhiBoxCard` 已内置）。 */
    val card = 0.dp

    /** 浮在内容之上的元素：输入岛等（≤8dp，配合 theme 的暖色 shadow 令牌）。 */
    val floating = 8.dp

    /** 模态面板（Bottom Sheet / Dialog）：M3 默认即可，这里给上限参考。 */
    val modal = 8.dp
}
