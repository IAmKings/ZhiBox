package com.zhibox.memory.core.search

import com.zhibox.memory.AutoStart
import com.zhibox.memory.MainActivity
import com.zhibox.memory.ui.DebugRun
import com.zhibox.memory.ui.route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3-11 / B7 验收（纯 JVM）：autostart 契约（AutoStart 数据类 + 路由表 + 20 个 EXTRA 常量名）。
 *
 * B7 后 Path C（G1C）与 Granite（PATH_B）路线下线：
 * - `DebugRun` 从 18 项收敛为 **16 项**（路由表见 debug 源集 `BenchmarkScreen.kt`）；
 * - `AutoStart` 仍保留 20 个字段/20 个 `autostart_*` EXTRA 常量（scripts/验收文档兼容），
 *   其中 `pathB`/`g1c` 为**兼容字段**：不再映射任何 `DebugRun`（route()==null），但
 *   `anyBenchmark` 仍计入（旧命令会落到实验屏且不自动跑实验，属预期的兼容降级）。
 *
 * 覆盖：16 个实验布尔 → `DebugRun` 一一对应且无重复/全覆盖；requiresModels 契约（5 true/11 false）；
 * 多 extra 命中优先级；import/importCancel 不进实验路由；20 字段无遗漏/无重复；20 EXTRA golden 值锁定。
 */
class AutoStartContractTest {

    @Test
    fun `默认值全 false 落到首页`() {
        val auto = AutoStart()
        assertFalse("默认不应进入实验屏", auto.anyBenchmark)
        assertNull("默认无路由目标", auto.route())
        assertEquals("home", auto.initialScreen())
        assertEquals("默认字段数量 = 20（B7 仍保留兼容字段）", 20, declaredFieldNames().size)
    }

    @Test
    fun `16 个实验布尔经路由表分派到唯一 DebugRun`() {
        val expected = linkedMapOf(
            AutoStart(benchmark = true) to DebugRun.BENCHMARK,
            AutoStart(g1 = true) to DebugRun.G1,
            AutoStart(scan = true) to DebugRun.SCAN,
            AutoStart(g3 = true) to DebugRun.G3,
            AutoStart(len = true) to DebugRun.LEN,
            AutoStart(searchEval = true) to DebugRun.SEARCH_EVAL,
            AutoStart(evalSet = true) to DebugRun.EVAL_SET,
            AutoStart(deleteCheck = true) to DebugRun.DELETE_CHECK,
            AutoStart(prod = true) to DebugRun.PROD,
            AutoStart(prodGpu = true) to DebugRun.PROD_GPU,
            AutoStart(prodAuto = true) to DebugRun.PROD_AUTO,
            AutoStart(g1d = true) to DebugRun.G1D,
            AutoStart(g1e = true) to DebugRun.G1E,
            AutoStart(probe = true) to DebugRun.PROBE,
            AutoStart(ftsRecovery = true) to DebugRun.FTS_RECOVERY,
            AutoStart(w4plus = true) to DebugRun.W4_PLUS,
        )
        assertEquals("B7 后应有 16 个可路由实验布尔", 16, expected.size)
        expected.forEach { (autoStart, debugRun) ->
            assertEquals("路由分派不符：${fieldOf(autoStart)}", debugRun, autoStart.route())
            assertTrue("有 route 的 AutoStart 必须 anyBenchmark", autoStart.anyBenchmark)
        }
        // 16 个布尔 → 16 个互不相同的 DebugRun，且覆盖全部枚举值（无遗漏/无重复）
        val routed = expected.values.toList()
        assertEquals("16 个路由目标不得重复", 16, routed.toSet().size)
        assertEquals("路由表应覆盖 DebugRun 全部枚举值", DebugRun.entries.toSet(), routed.toSet())
        assertEquals("DebugRun 枚举条目数应为 16（B7 删除 PATH_B/G1C）", 16, DebugRun.entries.size)
        assertFalse("B7 已下线 PATH_B", DebugRun.entries.any { it.name == "PATH_B" })
        assertFalse("B7 已下线 G1C", DebugRun.entries.any { it.name == "G1C" })
    }

    @Test
    fun `pathB 与 g1c 为兼容字段：仍进实验屏但无路由目标（B7 下线）`() {
        val compatFlags = listOf(
            "pathB" to AutoStart(pathB = true),
            "g1c" to AutoStart(g1c = true),
        )
        compatFlags.forEach { (name, auto) ->
            assertTrue("$name 兼容字段仍计入 anyBenchmark（旧脚本落实验屏）", auto.anyBenchmark)
            assertEquals("$name 兼容字段应落到 benchmark 屏", "benchmark", auto.initialScreen())
            assertNull("$name 已下线，不应映射任何 DebugRun", auto.route())
        }
        // 18 个旧实验布尔 = 16 个可路由 + 2 个兼容字段
        assertEquals(18, 16 + compatFlags.size)
    }

    @Test
    fun `import 与 importCancel 进入 import 屏 且 benchmark 优先`() {
        assertEquals("import", AutoStart(import = true).initialScreen())
        assertEquals("import", AutoStart(importCancel = true).initialScreen())
        assertEquals("import", AutoStart(import = true, importCancel = true).initialScreen())
        // 导入类 extra 不进入实验路由
        assertNull(AutoStart(import = true).route())
        assertNull(AutoStart(importCancel = true).route())
        // 同时命中实验与导入：实验屏优先（anyBenchmark 分支在前）
        assertEquals("benchmark", AutoStart(benchmark = true, import = true).initialScreen())
        assertEquals("benchmark", AutoStart(w4plus = true, importCancel = true).initialScreen())
    }

    @Test
    fun `多 extra 命中沿用旧 when 优先级`() {
        // 路由表 when 顺序：benchmark > g1 > scan > g3 > len > ... > w4plus
        assertEquals(DebugRun.BENCHMARK, AutoStart(benchmark = true, w4plus = true).route())
        assertEquals(DebugRun.BENCHMARK, AutoStart(benchmark = true, import = true).route())
        assertEquals(DebugRun.G1, AutoStart(g1 = true, scan = true).route())
        assertEquals(DebugRun.SCAN, AutoStart(scan = true, g3 = true).route())
        assertEquals(DebugRun.PROD, AutoStart(prod = true, prodGpu = true, prodAuto = true).route())
        assertEquals(
            "更早的 when 分支优先（FTS_RECOVERY 在 W4_PLUS 之前）",
            DebugRun.FTS_RECOVERY,
            AutoStart(ftsRecovery = true, w4plus = true).route(),
        )
        assertEquals(DebugRun.W4_PLUS, AutoStart(w4plus = true).route())
    }

    @Test
    fun `DebugRun requiresModels 契约（B7 后 5 true、11 false）`() {
        // 需要模型扫描完成后才自动触发（依赖具体模型资产）：BENCHMARK/G1/LEN/G1D/G1E
        val requiresModels = setOf(
            DebugRun.BENCHMARK, DebugRun.G1, DebugRun.LEN, DebugRun.G1D, DebugRun.G1E,
        )
        val actual = DebugRun.entries.filter { it.requiresModels }.toSet()
        assertEquals("requiresModels 集合不符（会改变 autostart 触发时机）", requiresModels, actual)
        assertEquals("requiresModels=true 应为 5 项", 5, actual.size)
        assertEquals("其余 11 项应无需模型", 11, DebugRun.entries.size - actual.size)
        // label 非空（UI 展示契约）
        assertTrue(DebugRun.entries.all { it.label.isNotBlank() })
    }

    @Test
    fun `AutoStart 字段无遗漏无重复（B7 兼容字段保留）`() {
        val expected = setOf(
            "benchmark", "g1", "scan", "g3", "len", "pathB", "g1c", "searchEval", "evalSet",
            "deleteCheck", "prod", "prodGpu", "prodAuto", "g1d", "g1e", "probe", "ftsRecovery",
            "w4plus", "import", "importCancel",
        )
        assertEquals("AutoStart 字段集合应与 18 实验/诊断 + 2 导入一致", expected, declaredFieldNames())
        assertEquals(20, declaredFieldNames().size)
    }

    @Test
    fun `20 个 EXTRA 常量名逐一锁定且两两不同（scripts 兼容）`() {
        // golden：scripts/验收文档与 am start --ez 命令依赖的常量值，禁止改名（顺序与 MainActivity 一致）
        val expected = linkedMapOf(
            "EXTRA_AUTO_BENCHMARK" to "autostart_benchmark",
            "EXTRA_AUTO_G1" to "autostart_g1",
            "EXTRA_AUTO_SCAN" to "autostart_scan",
            "EXTRA_AUTO_G3" to "autostart_g3",
            "EXTRA_AUTO_LEN" to "autostart_lenbench",
            "EXTRA_AUTO_PATH_B" to "autostart_pathb",
            "EXTRA_AUTO_G1C" to "autostart_g1c",
            "EXTRA_AUTO_SEARCH_EVAL" to "autostart_search_eval",
            "EXTRA_AUTO_EVAL" to "autostart_eval",
            "EXTRA_AUTO_DELETE_CHECK" to "autostart_delete_check",
            "EXTRA_AUTO_PROD" to "autostart_prodbench",
            "EXTRA_AUTO_PROD_GPU" to "autostart_prodbench_gpu",
            "EXTRA_AUTO_PROD_AUTO" to "autostart_prodbench_auto",
            "EXTRA_AUTO_G1D" to "autostart_g1d",
            "EXTRA_AUTO_G1E" to "autostart_g1e",
            "EXTRA_AUTO_PROBE" to "autostart_probe",
            "EXTRA_AUTO_FTS" to "autostart_fts_recovery",
            "EXTRA_AUTO_W4PLUS" to "autostart_w4plus",
            "EXTRA_AUTO_IMPORT" to "autostart_import",
            "EXTRA_AUTO_IMPORT_CANCEL" to "autostart_import_cancel",
        )
        // 用 MainActivity 的常量实际值构造（编译期 const 内联；不实例化 Activity）
        val actual = mapOf(
            "EXTRA_AUTO_BENCHMARK" to MainActivity.EXTRA_AUTO_BENCHMARK,
            "EXTRA_AUTO_G1" to MainActivity.EXTRA_AUTO_G1,
            "EXTRA_AUTO_SCAN" to MainActivity.EXTRA_AUTO_SCAN,
            "EXTRA_AUTO_G3" to MainActivity.EXTRA_AUTO_G3,
            "EXTRA_AUTO_LEN" to MainActivity.EXTRA_AUTO_LEN,
            "EXTRA_AUTO_PATH_B" to MainActivity.EXTRA_AUTO_PATH_B,
            "EXTRA_AUTO_G1C" to MainActivity.EXTRA_AUTO_G1C,
            "EXTRA_AUTO_SEARCH_EVAL" to MainActivity.EXTRA_AUTO_SEARCH_EVAL,
            "EXTRA_AUTO_EVAL" to MainActivity.EXTRA_AUTO_EVAL,
            "EXTRA_AUTO_DELETE_CHECK" to MainActivity.EXTRA_AUTO_DELETE_CHECK,
            "EXTRA_AUTO_PROD" to MainActivity.EXTRA_AUTO_PROD,
            "EXTRA_AUTO_PROD_GPU" to MainActivity.EXTRA_AUTO_PROD_GPU,
            "EXTRA_AUTO_PROD_AUTO" to MainActivity.EXTRA_AUTO_PROD_AUTO,
            "EXTRA_AUTO_G1D" to MainActivity.EXTRA_AUTO_G1D,
            "EXTRA_AUTO_G1E" to MainActivity.EXTRA_AUTO_G1E,
            "EXTRA_AUTO_PROBE" to MainActivity.EXTRA_AUTO_PROBE,
            "EXTRA_AUTO_FTS" to MainActivity.EXTRA_AUTO_FTS,
            "EXTRA_AUTO_W4PLUS" to MainActivity.EXTRA_AUTO_W4PLUS,
            "EXTRA_AUTO_IMPORT" to MainActivity.EXTRA_AUTO_IMPORT,
            "EXTRA_AUTO_IMPORT_CANCEL" to MainActivity.EXTRA_AUTO_IMPORT_CANCEL,
        )
        assertEquals("EXTRA 常量数量应为 20（B7 保留 scripts 兼容）", 20, actual.size)
        assertEquals("EXTRA 常量名/值被改动（scripts 依赖）", expected, actual)
        val values = actual.values.toList()
        assertEquals("EXTRA 值不得重复", 20, values.toSet().size)
        assertTrue("所有 EXTRA 值应统一 autostart_ 前缀", values.all { it.startsWith("autostart_") })
    }

    // ---- 反射工具（仅锁定字段集合，不依赖 kotlin-reflect） ----

    private fun declaredFieldNames(): Set<String> =
        AutoStart::class.java.declaredFields
            // 过滤合成字段与 Compose 编译器注入的静态 `$stable` 字段（非数据字段）
            .filterNot {
                it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) ||
                    it.name.startsWith("$")
            }
            .map { it.name }
            .toSet()

    /** 路由断言失败时定位是哪个 boolean（取唯一为 true 的字段名）。 */
    private fun fieldOf(autoStart: AutoStart): String =
        AutoStart::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .firstOrNull {
                it.isAccessible = true
                it.getBoolean(autoStart)
            }
            ?.name ?: "<none>"
}
