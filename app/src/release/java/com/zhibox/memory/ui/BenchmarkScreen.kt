package com.zhibox.memory.ui

import androidx.compose.runtime.Composable
import com.zhibox.memory.AutoStart

/**
 * Release 变体占位实现（P0-3 实验隔离）。
 *
 * 真正的实验/诊断界面（debug `BenchmarkScreen` + `benchmark/` runner 包）只存在于
 * `app/src/debug/java/`，**不会**被打进 release 包。这里仅保留与 debug 版完全一致的公开
 * 函数签名（B3-11：`autoStart: AutoStart` + `onBack`），让产品代码（main source set 的
 * MainActivity）可以在 variant 无关的代码里引用该入口；release 构建不包含实验能力，
 * MainActivity 亦以 `FLAG_DEBUGGABLE` 门禁保证本入口不可达。
 *
 * 注意：修改 debug 版签名时必须同步本文件，否则 release 编译失败（这正是刻意保留的
 * 编译期契约，防止实验能力悄悄泄漏进发布包）。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BenchmarkScreen(
    autoStart: AutoStart = AutoStart(),
    onBack: () -> Unit = {},
) {
    // release 构建不包含实验能力：空实现，不可达（MainActivity 只在 debuggable 构建展示入口）。
}
