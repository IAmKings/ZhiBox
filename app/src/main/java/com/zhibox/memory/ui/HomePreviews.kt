package com.zhibox.memory.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.zhibox.memory.AppSkeleton
import com.zhibox.memory.ui.theme.ZhiBoxTheme

/**
 * B3-7：Home（首页 AppSkeleton 在 MainActivity.kt，本任务写范围不含 MainActivity）
 * 的 @Preview 集中放在 ui 包：浅色不显示实验入口（release 形态），深色显示（debug 形态）。
 */
@Preview(showBackground = true, name = "Home · Light")
@Composable
private fun HomeLightPreview() {
    ZhiBoxTheme(darkTheme = false) {
        AppSkeleton(onOpenBenchmark = null)
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    name = "Home · Dark (debug entry)",
)
@Composable
private fun HomeDarkPreview() {
    ZhiBoxTheme(darkTheme = true) {
        AppSkeleton(onOpenBenchmark = {})
    }
}
