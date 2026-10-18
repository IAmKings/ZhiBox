package com.zhibox.memory.core.sag

import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.core.text.TextNormalizer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * B10 检索纯函数工具（参数归一化/哈希/评分/通道映射/轨迹 JSON）。
 *
 * 独立对象便于纯 JVM 单测（不依赖 Android/DB）；retriever 与 lexic 抽取器共用，
 * 避免同一口径（NFC/大小写/空白/哈希）在多处各写一份。
 */
internal object SagSupport {

    /** 查询上界（与 SearchService 同口径，embedding 与 FTS 共用） */
    const val MAX_QUERY_CHARS: Int = 512

    /** 查询 bigram OR 串上限（与 SearchService 一致，避免 SQLite 表达式过深） */
    const val MAX_OR_GRAMS: Int = 64

    /** 事件向量 seed 余弦阈值（τ=0.4，task-47 契约） */
    const val EVENT_VECTOR_TAU: Double = 0.4

    /** 事件向量 seed 全量扫描上限（MVP 规模保护；超出按 id 截断并在注释/trace 说明） */
    const val EVENT_VECTOR_SCAN_LIMIT: Int = 5_000

    /** 查询轨迹保留条数（query_trace keepRecent） */
    const val TRACE_KEEP: Int = 50

    private val WHITESPACE = Regex("\\s+")

    /** 与入库侧一致的 query 规范化：NFC/CRLF→LF + trim + 截断（不改变大小写，向量/关键词共用）。 */
    fun normalizeQuery(raw: String): String =
        TextNormalizer.normalize(raw).trim().take(MAX_QUERY_CHARS)

    /**
     * 实体名归一化去重键（与 B9 `SagExtractionSupport.normalizeEntityName` / entity.normalizedName 一致）：
     * NFC + trim + lowercase + 连续空白合一。LLM 抽取实体也用此口径写入 normalizedName。
     */
    fun normalizeEntityName(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")

    /** query 文本 SHA-256 小写 hex（写入 query_trace.queryHash；不落正文）。 */
    fun queryHash(normalizedQuery: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(normalizedQuery.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 查询 bigram（去重 + 上限），用于实体 FTS 召回。 */
    fun queryGrams(query: String): List<String> =
        BigramTokenizer.bigrams(query).distinct().take(MAX_OR_GRAMS)

    /** FTS4 OR MATCH 串（引号包裹、空格连接）；空返回 null。与 SearchService 同口径。 */
    fun ftsOrMatch(grams: List<String>): String? =
        if (grams.isEmpty()) null
        else grams.joinToString(" OR ") { gram -> "\"${gram.replace("\"", "")}\"" }

    /**
     * 泛词过滤（实体种子去噪）：疑问词/连接词/空泛名词不应触发实体扩展，
     * 否则任意问句都会命中大量弱实体、放大候选。列表刻意保守（宁缺毋滥）。
     */
    private val GENERIC_ENTITY_NAMES: Set<String> = setOf(
        "什么", "怎么", "如何", "为什么", "哪些", "哪个", "是否", "多少", "几个",
        "介绍", "内容", "知识", "方法", "问题", "学习", "东西", "时候", "可以",
        "需要", "一个", "这个", "那个", "以及", "或者", "因为", "所以", "但是",
        "如果", "关于", "相关", "信息", "一些", "具体", "主要", "一般", "其中",
    )

    fun isGenericEntityName(normalizedName: String): Boolean =
        normalizedName.isBlank() || normalizedName in GENERIC_ENTITY_NAMES

    /**
     * 事件候选融合分（确定性、可解释、单测可锁定）：
     * - 向量通道权重 0.7，实体通道权重 0.3（向量语义为主，实体扩展为补充）；
     * - 单通道候选也参与排序：仅向量最高 0.7，仅实体最高 0.3；
     * - 缺失通道按 0 计，clamp 到 [0,1]。
     */
    fun fuseEventScore(vectorScore: Double?, entityScore: Double?): Double {
        val v = (vectorScore ?: 0.0).coerceIn(0.0, 1.0)
        val e = (entityScore ?: 0.0).coerceIn(0.0, 1.0)
        return (0.7 * v + 0.3 * e).coerceIn(0.0, 1.0)
    }

    fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

    /** SearchService.SearchHit.channels（中文文案）→ SagChannels 集合。 */
    fun channelsFromSearchHit(channels: String): Set<String> {
        val out = LinkedHashSet<String>(2)
        if (channels.contains("向量")) out += SagChannels.VECTOR
        if (channels.contains("关键词")) out += SagChannels.KEYWORD
        if (out.isEmpty()) out += SagChannels.VECTOR
        return out
    }

    /**
     * 轨迹阶段 JSON（query_trace.timingJson；只含阶段名/计数/耗时，不含正文）。
     *
     * 手写 JSON（不用 org.json）：本方法可被纯 JVM 单测调用，而 unit test 的 android.jar stub
     * 会让 org.json 抛 "not mocked"。字段固定为 name/count/ms，name 由常量/调用方控制，
     * 这里仍做最小转义（引号/反斜杠/控制字符）以防未来的自由字符串。
     */
    fun encodeTimingJson(stages: List<SagTraceStage>): String {
        val builder = StringBuilder(64 + stages.size * 48)
        builder.append("{\"stages\":[")
        stages.forEachIndexed { index, stage ->
            if (index > 0) builder.append(',')
            builder.append("{\"name\":\"").append(escapeJson(stage.name)).append("\",")
            builder.append("\"count\":").append(stage.count).append(',')
            builder.append("\"ms\":").append(stage.ms).append('}')
        }
        builder.append("]}")
        return builder.toString()
    }

    /** JSON 字符串最小转义（引号/反斜杠/常见控制字符；阶段名/常量为主）。 */
    internal fun escapeJson(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
            }
        }
        return out.toString()
    }
}
