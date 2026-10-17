package com.zhibox.memory.core.search

import com.zhibox.memory.benchmark.EvalQueryInput
import com.zhibox.memory.benchmark.SearchEvalRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3-10 验收（纯 JVM）：评测输入统一解析契约。
 *
 * 被测（debug 源集）：
 * - `SearchEvalRunner.parseQueries(bytes): List<String>`（委托 `EvalQueryInput`）；
 * - `EvalQueryInput.candidatePaths`：主 `search/queries.json`、兼容 `eval/evalset.json`。
 *
 * 三种 schema：`{"queries":["q1"]}` / `{"queries":[{"q":"q1"}]}` / 裸数组（对象数组支持 `q|query` 回退）。
 * 运行依赖：`testImplementation("org.json:json:20231013")` 提供 JVM 可用的 org.json（真实执行，不 skip）。
 */
class EvalQueryParsingTest {

    @Test
    fun `schema1 queries 字符串数组`() {
        val json = """{"queries":["青藏高原平均海拔","蜂鸟悬停机制"]}""".toByteArray()
        assertEquals(
            listOf("青藏高原平均海拔", "蜂鸟悬停机制"),
            SearchEvalRunner.parseQueries(json),
        )
    }

    @Test
    fun `schema2 queries 对象数组取 q 字段`() {
        val json = """{"queries":[{"q":"青藏高原","id":"q1"},{"q":"蜂鸟","id":"q2"}]}""".toByteArray()
        assertEquals(listOf("青藏高原", "蜂鸟"), SearchEvalRunner.parseQueries(json))
    }

    @Test
    fun `schema3 裸字符串数组`() {
        assertEquals(
            listOf("青藏高原", "蜂鸟"),
            SearchEvalRunner.parseQueries("""["青藏高原","蜂鸟"]""".toByteArray()),
        )
    }

    @Test
    fun `连字符 schema 裸对象数组支持 query 字段回退`() {
        assertEquals(
            listOf("青藏高原", "蜂鸟"),
            SearchEvalRunner.parseQueries("""[{"query":"青藏高原"},{"q":"蜂鸟"}]""".toByteArray()),
        )
    }

    @Test
    fun `空白条目被过滤且保留原始顺序`() {
        val json = """{"queries":["  ","青藏高原","  蜂鸟  ",""]}""".toByteArray()
        // 实现会 trim 后过滤空文本（与 UI/检索入口一致）
        assertEquals(listOf("青藏高原", "蜂鸟"), SearchEvalRunner.parseQueries(json))
    }

    @Test
    fun `空文件抛出可读错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SearchEvalRunner.parseQueries(ByteArray(0))
        }
        assertTrue("错误信息应说明为空：${e.message}", e.message!!.contains("为空"))
    }

    @Test
    fun `坏 JSON 抛出可读错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SearchEvalRunner.parseQueries("""{"queries":["未闭合""".toByteArray())
        }
        assertTrue(
            "错误信息应可读（schema/JSON 提示）：${e.message}",
            listOf("无法识别", "schema", "JSON", "解析").any { e.message!!.contains(it) },
        )
    }

    @Test
    fun `缺少 queries 字段抛出可读错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SearchEvalRunner.parseQueries("""{"foo":1}""".toByteArray())
        }
        assertTrue("错误信息应说明 schema：${e.message}", e.message!!.contains("schema") || e.message!!.contains("无法识别"))
    }

    @Test
    fun `queries 为空数组抛出可读错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SearchEvalRunner.parseQueries("""{"queries":[]}""".toByteArray())
        }
        assertTrue("错误信息应说明无有效条目：${e.message}", e.message!!.contains("无有效查询"))
    }

    @Test
    fun `全部条目无 q 字段抛出可读错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SearchEvalRunner.parseQueries("""{"queries":[{"id":"only"},{"type":"fact"}]}""".toByteArray())
        }
        assertTrue("错误信息应说明无有效条目：${e.message}", e.message!!.contains("无有效查询"))
    }

    @Test
    fun `输入路径优先级：主 search queries 在前 兼容 eval evalset 在后`() {
        assertEquals(
            listOf("search/queries.json", "eval/evalset.json"),
            EvalQueryInput.candidatePaths,
        )
    }
}
