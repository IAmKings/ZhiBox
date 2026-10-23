// SettingsBenchmark：设置页帧耗时（防回归“设置页卡顿/快速滑动掉帧”）。
//
// 流程（setupBlock，不计入测量）：
//   冷启动 → 首页「更多设置」按钮 → 菜单「设置」→ 等设置页标题「设置」出现并等列表稳定。
// 测量（measureBlock）：
//   对设置页 LazyColumn 做 3 次快速上下 swipe（steps=10 即快速滑动/快速 fling 手感），
//   用 FrameTimingMetric 采集帧耗时：frameDurationCpuMs P50/P90/P95/P99 + frameOverrunMs。
//
// 为什么导航放在 setupBlock：本用例的回归目标是“设置列表滚动卡顿”，
// 页面切换/首帧开销由 StartupBenchmark 覆盖，避免两件事混在同一个指标里。
//
// 编译模式：None()（无 AOT）便于本地快速跑；CI 基线建议换成 CompilationMode.Partial()，
// 与 StartupBenchmark 的 Partial 口径保持一致。
//
// 指标选择：FrameTimingGfxInfoMetric（基于 `dumpsys gfxinfo framestats`）。
// 不用 FrameTimingMetric（perfetto trace）的原因：部分 OEM ROM（实测 Xiaomi 23078PND5G /
// Android 14 / MIUI）的 trace 里 RenderThread 线程没有名字（thread.name = NULL），
// FrameTimingMetric 依赖 "RenderThread" 线程切片定位帧数据，会抛
//   IllegalStateException: Observed no renderthread slices in trace
// （此时 trace 里实际有 691 条 actual_frame_timeline_slice，滑动也生效，属设备 tracing 细节）。
// gfxinfo 口径不依赖线程命名，且与 Lead 手工排查用的数据源一致，跨设备更稳。

package com.zhibox.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun settingsScrollFrames() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingGfxInfoMetric()),
            compilationMode = CompilationMode.None(),
            // 注意：必须用 WARM，不能用 COLD——COLD 会在 setupBlock 结束后 force-stop 被测应用
            // （为测量冷启动），导致 setup 里导航好的设置页丢失、measureBlock 里的 swipe 落在
            // 桌面上 → FrameTimingMetric 报 “Observed no renderthread slices in trace”。
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                openSettingsFromHome(device)
            },
        ) {
            // measureBlock：只测滑动帧耗时（3 次快速上/下/上 swipe）
            val width = device.displayWidth
            val height = device.displayHeight
            val centerX = width / 2
            val topY = (height * 0.28f).toInt()
            val bottomY = (height * 0.78f).toInt()
            // steps 越小滑动越快：20 步 ≈ 快速 fling 手感（真机实测每步约 1~2 帧）
            val steps = 20
            repeat(3) { index ->
                if (index % 2 == 0) {
                    device.swipe(centerX, bottomY, centerX, topY, steps)
                } else {
                    device.swipe(centerX, topY, centerX, bottomY, steps)
                }
            }
        }
    }
}
