package com.zhibox.memory.core.text

/**
 * 字符二元组分词（G3 选定方案，hit=0.96 / 误报 0）。
 * 入库与查询两侧统一使用；FTS4 MATCH 串由 [toMatchQuery] 生成（AND 语义）。
 * 实现与 G3 判定实验（ChineseTokenizationBenchmark.bigramize）保持一致。
 */
object BigramTokenizer {

    fun bigrams(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), "")
        if (clean.length < 2) return if (clean.isEmpty()) emptyList() else listOf(clean)
        return ArrayList<String>(clean.length - 1).apply {
            for (i in 0 until clean.length - 1) add(clean.substring(i, i + 2))
        }
    }

    fun toFtsText(text: String): String = bigrams(text).joinToString(" ")

    /** FTS4 MATCH 安全：引号移除，各 bigram 以引号包裹后空格连接（隐式 AND） */
    fun toMatchQuery(text: String): String =
        bigrams(text).joinToString(" ") { gram -> "\"${gram.replace("\"", "")}\"" }
}
