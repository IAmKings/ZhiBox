package com.zhibox.memory.core.text

/**
 * 分块器——策略对照 zleap-sag pipeline/chunk.py（默认 strategy=standard，
 * max_tokens=800、overlap=0、标题感知边界），按 MVP 的 TXT/MD 输入务实移植：
 * - 以字符数为 token 近似（CJK≈1 token/字符，Latin≈4 字符/token）；
 * - "块" = 连续非空非标题行聚合，或单个标题行（Markdown 标题强制开新块）；
 * - 块聚合到目标上限即产出 chunk；超长块按上限硬切；
 * - 不变量（单测锁定）：chunk.content == 原文.substring(charStart, charEnd)。
 */
class Chunker(
    private val options: Options = Options(),
) {
    /**
     * maxChars 默认 600：运行时输入上限实测为 1024 token（litertlm 0.18.0 签名限制，
     * 非 8192 模型上下文），中文 ≈1.35 token/字 + 任务前缀，800 字块会越界（1080 token）。
     * 600 字 × 1.35 + 前缀 ≈ 830 token，留有安全余量。
     */
    data class Options(val maxChars: Int = 600)

    data class Chunk(
        val ordinal: Int,
        val content: String,
        val charStart: Int,
        val charEnd: Int,
        val tokenEstimate: Int,
    )

    private class Block(val start: Int, val end: Int, val isHeading: Boolean) {
        val length: Int get() = end - start
    }

    fun chunk(normalizedText: String): List<Chunk> {
        if (normalizedText.isEmpty()) return emptyList()
        val blocks = splitBlocks(normalizedText)
        val out = ArrayList<Chunk>()

        fun emit(content: String, start: Int, end: Int) {
            out += Chunk(out.size, content, start, end, estimateTokens(content))
        }

        var bufStart = -1
        var bufEnd = -1

        fun flush() {
            if (bufStart >= 0) {
                emit(normalizedText.substring(bufStart, bufEnd), bufStart, bufEnd)
            }
            bufStart = -1; bufEnd = -1
        }

        for (b in blocks) {
            if (b.length > options.maxChars) {
                flush()
                var s = b.start
                while (s < b.end) {
                    var e = minOf(s + options.maxChars, b.end)
                    // B2-4：硬切边界不得劈开 surrogate pair（emoji/CJK 扩展区等）。
                    // 仅当切点确实落在 pair 中间时回退 1 个 UTF-16 char；BMP 文本的切分边界
                    // 与旧实现逐位一致（220 评测基线不受影响）。maxChars=1 的退化配置无法保证。
                    if (e < b.end && e > s + 1 &&
                        normalizedText[e - 1].isHighSurrogate() && normalizedText[e].isLowSurrogate()
                    ) {
                        e -= 1
                    }
                    emit(normalizedText.substring(s, e), s, e)
                    s = e
                }
                continue
            }
            if (bufStart >= 0) {
                // 跨度按 bufEnd-bufStart 计算（含块间换行），保证 chunk 内容 ≤ maxChars
                val projectedSpan = b.end - bufStart
                if (b.isHeading || projectedSpan > options.maxChars) flush()
            }
            if (bufStart < 0) bufStart = b.start
            bufEnd = b.end
            if (bufEnd - bufStart >= options.maxChars) flush()
        }
        flush()
        return out
    }

    /** 行级扫描聚块：标题行独占一块；连续非空非标题行聚为一块；空行为块边界 */
    private fun splitBlocks(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        var i = 0
        val n = text.length
        var openStart = -1
        var openEnd = -1

        fun closeOpen() {
            if (openStart >= 0) {
                blocks += Block(openStart, openEnd, isHeading(text.substring(openStart, openEnd)))
                openStart = -1; openEnd = -1
            }
        }

        while (i < n) {
            var j = text.indexOf('\n', i)
            if (j == -1) j = n
            val line = text.substring(i, j)
            if (line.isBlank()) {
                closeOpen()
            } else if (isHeading(line)) {
                closeOpen()
                blocks += Block(i, j, isHeading = true)
            } else if (openStart >= 0) {
                openEnd = j
            } else {
                openStart = i; openEnd = j
            }
            i = j + 1
        }
        closeOpen()
        return blocks
    }

    private fun isHeading(line: String): Boolean {
        val t = line.trimStart()
        if (!t.startsWith("#")) return false
        val rest = t.dropWhile { it == '#' }
        return rest.isNotEmpty() && rest[0] == ' '
    }

    companion object {
        /**
         * token 估算：CJK ≈1.35 token/字（按 800 字→1080 token 越界实测校准），其余≈4 字符/token。
         * B2-4：按 code point 计数（surrogate pair 计 1 个字符，与分词/语义一致）。
         */
        fun estimateTokens(text: String): Int {
            var cjk = 0
            var other = 0
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                if (codePoint > 0x2E80) cjk++ else other++
                i += Character.charCount(codePoint)
            }
            return Math.ceil(cjk * 1.35).toInt() + (other + 3) / 4
        }
    }
}
