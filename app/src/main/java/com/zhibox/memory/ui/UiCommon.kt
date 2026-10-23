package com.zhibox.memory.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhibox.memory.R
import com.zhibox.memory.ui.theme.zhiBoxColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * P1-8/B3 UI 公共设施：
 * - [isDebuggable]：P0-3 实验隔离的运行时门禁（不依赖 BuildConfig，release 恒 false）。
 * - [readableMessage]：把异常转成用户可读的单行文案（不向用户抛堆栈）。
 * - [safeLaunch]：统一的异常边界——`CancellationException` 必须重抛（取消不是业务失败），
 *   其余异常交给调用方转成可读提示；loading 复位由调用方 `try/finally` 完成。
 * - [ZhiBoxTopBar] / [ZhiBoxScreen]：六屏重复的 Scaffold + TopAppBar + 返回按钮收敛为一处。
 * - [LoadingState] / [EmptyState] / [ErrorState]（B3-5）：统一加载/空态/错误展示，
 *   消除四屏各自的重复实现；均为无状态 composable，可直接被 @Preview 渲染。
 */

fun Context.isDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

fun Throwable.readableMessage(): String =
    message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(200)
        ?: javaClass.simpleName

/**
 * ViewModel 统一协程入口：长任务挂在 [viewModelScope]（屏幕离开组合不取消），
 * 取消异常原样重抛，其它异常转给 [onError]。
 */
fun ViewModel.safeLaunch(
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        onError(t)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZhiBoxTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        // 顶栏透明，让 Scaffold 的 paper 底色透出（含状态栏区域），二级页与首页连成整块底色。
        // scrolledContainerColor 必须同步透明：滚动容器（scrollBehavior/nested scroll）激活时
        // M3 会切换到该颜色，若沿用默认 surfaceContainer 就会在滚动中重新出现白/灰条带。
        // 注意：只能用 colorScheme/extended 令牌，不写裸色值（透明是唯一例外）。
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
    )
}

@Composable
fun ZhiBoxScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    // Scaffold 默认 containerColor = colorScheme.surface（卡片白），会导致二级页画布发白；
    // 这里显式用与首页同源的 paper 令牌。enableEdgeToEdge 下 Scaffold 铺满窗口，
    // 其 Surface 底色同时覆盖状态栏区域，配合透明顶栏 → 全屏一个底色。
    Scaffold(
        containerColor = MaterialTheme.zhiBoxColors.paper,
        topBar = { ZhiBoxTopBar(title, onBack, backEnabled) },
    ) { padding ->
        content(padding)
    }
}

// ───────────────────────── B3-5 统一状态组件 ─────────────────────────

/**
 * 加载态：可选的阶段文案（如"首次检索需加载端侧模型…"）。
 * 默认横向进度条形式，适配任意高度容器；`circular = true` 用于整屏居中场景。
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
    circular: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (circular) {
            CircularProgressIndicator()
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 空态：标题 + 可选说明 + 可选下一步动作（如"去导入"）。
 * 无状态，供检索/导入/来源/设置四屏复用，也便于 @Preview 单独渲染。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 错误态：错误卡片（标题/原因/提示）+ 可选动作（重试、去设置等）。
 * 原因默认使用 error 色；hint 用 primary 色给出下一步操作。
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
