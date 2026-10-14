package com.zhibox.memory.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// minSdk 24：不启用动态取色（Material You 需 API 31+），使用固定色板。
private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    surface = SurfaceLight
)

private val DarkColors = darkColorScheme(
    primary = PrimaryContainer,
    onPrimary = OnPrimaryContainer,
    secondary = SecondaryContainer,
    onSecondary = OnSecondaryContainer,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark
)

@Composable
fun ZhiBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
