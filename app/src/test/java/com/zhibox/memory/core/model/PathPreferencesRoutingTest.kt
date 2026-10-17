package com.zhibox.memory.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 模型选型/验证门纯逻辑单测（P1-7 / B7 / B7 审查修复）。
 *
 * `PathPreferences.resolve` 依赖 Context（SharedPreferences + 目录扫描），JVM 单测无法构造；
 * 因此把"验证门优先、实验兜底"的排序抽成 [PathPreferences.pickModel] 纯函数在这里覆盖——
 * 它正是"验证门只写不读"修复的消费方核心。
 *
 * B7 审查修复语义（本测试锁定）：
 * - `PathPreferences.D` = 740M（UE 性能上限档），显式偏好 A（440M）/ B（270M）/ D（740M）
 *   在**全量候选**中查找，不受验证门池限制（否则 440M 在位时显式 B/D 永远选不到）；
 * - AUTO 模式仍走"通过验证门的池"（不自动选实验模型）；
 * - 旧持久化值 "C"/非法值在 [PathPreferences.get] 层回退 AUTO（纯函数层：`pickModel` 收到
 *   未知 preference 时按 auto 语义处理，测试以此模拟回退后的行为）。
 *
 * 参数 gatePassNames 的口径 = `resolve` 里 `passesGate` 的结果：
 * catalog verified=true / 未收录进 catalog 的用户投放模型 / 有本机验证门记录的文件名。
 */
class PathPreferencesRoutingTest {

    private fun f(name: String) = File(name)
    private val generic440 = "embeddinggemma-2-text-vision-440m.litertlm"
    private val lg440 = "embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm"
    private val exp270 = "embeddinggemma-2-text-270m.litertlm"
    private val exp740 = "embeddinggemma-2-740m.litertlm"
    private val npu740 = "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm"

    // ---- AUTO 模式：仍受验证门约束 ----

    @Test
    fun `有已通过验证门的模型时不自动选实验模型`() {
        val picked = PathPreferences.pickModel(
            files = listOf(f(exp270), f(exp740), f(generic440)),
            preference = PathPreferences.AUTO,
            gatePassNames = setOf(generic440),
        )
        assertEquals(generic440, picked?.name)
    }

    @Test
    fun `仅有实验模型时兜底（auto 顺序：通用 440M → 270M → 名称序）`() {
        val picked = PathPreferences.pickModel(
            files = listOf(f(exp270), f(exp740)),
            preference = PathPreferences.AUTO,
            gatePassNames = emptySet(),
        )
        // B7 顺序：无通用 440M 时，路径 B（270M）优先于名称序 → 270M
        assertEquals(exp270, picked?.name)
    }

    @Test
    fun `SOC 特化实验版在通用 440M 过门时不被自动选中`() {
        val picked = PathPreferences.pickModel(
            files = listOf(f(lg440), f(generic440)),
            preference = PathPreferences.AUTO,
            gatePassNames = setOf(generic440),
        )
        assertEquals(generic440, picked?.name)
    }

    @Test
    fun `auto 无通用 440M 时 270M 优先于 740M（路径 B 通道优先）`() {
        val picked = PathPreferences.pickModel(
            files = listOf(f(exp740), f(exp270)),
            preference = PathPreferences.AUTO,
            gatePassNames = setOf(exp270, exp740),
        )
        assertEquals(exp270, picked?.name)
    }

    // ---- 显式偏好 A/B/D：全量候选（不受验证门池限制） ----

    @Test
    fun `显式偏好 B 在 440M 在位且仅 440M 过门时仍选 270M`() {
        // B7 审查修复回归：旧实现在 passedPool 里找 B，440M 在位时 B 被门禁过滤 → 永远选不到
        val picked = PathPreferences.pickModel(
            files = listOf(f(generic440), f(exp270)),
            preference = PathPreferences.B,
            gatePassNames = setOf(generic440),
        )
        assertEquals("显式 B 必须能选到未过门的 270M", exp270, picked?.name)
    }

    @Test
    fun `显式偏好 D 在 440M 在位且仅 440M 过门时仍选 740M`() {
        val picked = PathPreferences.pickModel(
            files = listOf(f(generic440), f(exp740)),
            preference = PathPreferences.D,
            gatePassNames = setOf(generic440),
        )
        assertEquals("显式 D 必须能选到未过门的 740M", exp740, picked?.name)
    }

    @Test
    fun `偏好 D 选通用 740M 而非 NPU 特化版（候选按文件名排序）`() {
        // candidates() 按文件名排序：generic ".litertlm" 在 "_Qualcomm..." 之前
        val picked = PathPreferences.pickModel(
            files = listOf(f(exp740), f(npu740)),
            preference = PathPreferences.D,
            gatePassNames = setOf(exp740),
        )
        assertEquals("D 偏好应命中通用 740M", exp740, picked?.name)
    }

    @Test
    fun `偏好 A 命中通用 440M 而非 SOC 特化版`() {
        val picked = PathPreferences.pickModel(
            files = listOf(f(lg440), f(generic440)),
            preference = PathPreferences.A,
            gatePassNames = setOf(generic440, lg440),
        )
        assertEquals(generic440, picked?.name)
    }

    @Test
    fun `偏好 B 目标不在候选时回退 auto 顺序（通用 440M）`() {
        val picked = PathPreferences.pickModel(
            files = listOf(f(lg440), f(generic440)),
            preference = PathPreferences.B,
            gatePassNames = setOf(generic440),
        )
        assertEquals(generic440, picked?.name)
    }

    // ---- 旧持久化值 "C"/非法值：get() 回退 AUTO（纯函数层模拟） ----

    @Test
    fun `旧偏好值 C 与非法值在 pickModel 层按 auto 语义处理`() {
        val files = listOf(f(generic440), f(exp270))
        val gate = setOf(generic440)
        val auto = PathPreferences.pickModel(files, PathPreferences.AUTO, gate)
        for (legacy in listOf("C", "c", "INVALID", "")) {
            assertEquals(
                "旧/非法偏好值应等价于 AUTO（get() 已回退 AUTO 后的行为）：$legacy",
                auto,
                PathPreferences.pickModel(files, legacy, gate),
            )
        }
        assertEquals("auto 语义在仅 440M 过门时选 440M", generic440, auto?.name)
    }

    @Test
    fun `偏好常量契约：AUTO A B D（C 已下线）`() {
        assertEquals("auto", PathPreferences.AUTO)
        assertEquals("A", PathPreferences.A)
        assertEquals("B", PathPreferences.B)
        assertEquals("D", PathPreferences.D)
    }

    @Test
    fun `空候选返回 null`() {
        assertNull(PathPreferences.pickModel(emptyList(), PathPreferences.AUTO, emptySet()))
        assertNull(PathPreferences.pickModel(emptyList(), PathPreferences.D, emptySet()))
    }
}
