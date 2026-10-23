// StartupBenchmark：冷启动帧耗时 + 启动耗时（防回归“启动变慢/首帧卡”）。
//
// 指标说明：
//   · FrameTimingMetric   —— measureBlock 期间每一帧的 CPU/Draw 耗时与 frameOverrunMs，
//                            冷启动窗口内的首帧/过渡帧卡顿会直接体现在 P90/P95/P99。
//   · StartupTimingMetric —— timeToInitialDisplay / timeToFullDisplay（含首帧与完全显示）。
//
// 编译模式：
//   · None()    —— 不重置/不施加 AOT，模拟“冷装在设备上第一次启动”的最差情况，跑得快。
//   · Partial() —— 用设备上已有 profile 做 speed-profile 编译，接近真实长期使用状态，
//                  适合作为 CI 基线（需要 shell 权限，Macrobenchmark 通过 UiAutomation 获取）。
// 每次 measureRepeated 迭代由 benchmark 库负责 force-stop + 冷启动，measureBlock 只做
// startActivityAndWait()，避免把设置动作混入启动时间。

package com.zhibox.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** 最差情况冷启动（无 AOT 编译/无 profile）。 */
    @Test
    fun startupColdCompilationNone() = startup(CompilationMode.None())

    /** 接近真实长期使用状态的冷启动（设备上已有 profile 时做 speed-profile 编译）。 */
    @Test
    fun startupColdCompilationPartial() = startup(CompilationMode.Partial())

    private fun startup(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric(), StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            // 每轮测量前回到桌面并清理状态；benchmark 库负责 force-stop 被测应用。
            setupBlock = { pressHome() },
        ) {
            // measureBlock：冷启动到首帧可见
            startActivityAndWait()
        }
    }
}
