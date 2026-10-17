package com.zhibox.memory.core.text

/**
 * 字符二元组分词（G3 选定方案，hit=0.96 / 误报 0）。
 * 入库与查询两侧统一使用；FTS4 MATCH 串由 [toMatchQuery] 生成（AND 语义）。
 * 实现与 G3 判定实验（ChineseTokenizationBenchmark.bigramize）保持一致。
 *
 * **B2-4（code point 迭代）**：二元组按 Unicode **code point** 切分，而不是 UTF-16 char——
 * emoji/生僻字（CJK 扩展 B 等 surrogate pair）不会再产生 `"a\uD83D"` 这种孤立代理串，
 * 也不会把一对代理拆进不同 bigram 后各自丢失。对 BMP 文本（常规中英文）逐位等价于旧实现，
 * 220 条评测基线不受影响；whitespace 先移除的口径保持不变（与 G3 入库一致）。
 */
object BigramTokenizer {

    fun bigrams(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), "")
        if (clean.isEmpty()) return emptyList()
        // B2-4：按 code point 数组迭代（surrogate pair 在数组中是一个元素）
        val codePoints = clean.codePoints().toArray()
        if (codePoints.size < 2) return listOf(codePointString(codePoints, 0, codePoints.size))
        return ArrayList<String>(codePoints.size - 1).apply {
            for (i in 0 until codePoints.size - 1) add(codePointString(codePoints, i, 2))
        }
    }

    private fun codePointString(codePoints: IntArray, start: Int, count: Int): String {
        val sb = StringBuilder(count * 2)
        for (i in start until start + count) sb.appendCodePoint(codePoints[i])
        return sb.toString()
    }

    fun toFtsText(text: String): String = bigrams(text).joinToString(" ")

    /** FTS4 MATCH 安全：引号移除，各 bigram 以引号包裹后空格连接（隐式 AND） */
    fun toMatchQuery(text: String): String =
        bigrams(text).joinToString(" ") { gram -> "\"${gram.replace("\"", "")}\"" }

    /**
     * OR 语义 MATCH 串（W2 检索通道）：自然语言问句 bigram 数多，AND 会因个别
     * 跨词 bigram 缺失而全空（实测 8 问句关键词候选全 0）。候选召回用 OR，
     * 相关性排序由调用方按 bigram 覆盖数在 Kotlin 侧重排（FTS4 无 bm25）。
     */
    fun toOrMatchQuery(text: String): String =
        bigrams(text).distinct().joinToString(" OR ") { gram -> "\"${gram.replace("\"", "")}\"" }
}
