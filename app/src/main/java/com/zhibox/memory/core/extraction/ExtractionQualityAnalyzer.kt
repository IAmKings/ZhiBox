package com.zhibox.memory.core.extraction

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * B15.2：抽取质量汇总中"空字段"计数（同一 event 可同时计入多个字段）。
 *
 * 仅计数，不含任何正文。
 */
data class BlankEventFields(
    val title: Int,
    val summary: Int,
    val content: Int,
)

/**
 * B15.2：SAG 抽取质量只读诊断报告（不含正文/errorDetail/chunk 原文）。
 *
 * 语义口径：
 * - [eventsActive]：`event.active = 1` 的当前有效事件；[eventsDiscarded] = total - active
 *   （含 superseded 历史行与 active=0 行，作为"被替代/丢弃"计数）；
 * - [blankEventFields]：分别统计 title/summary/content 为空白（TRIM 后为空）的 event 行数；
 * - [entitiesWithAliases]：至少有一条 `entity_alias` 行的 entity 数；[aliasesTotal]：别名行数；
 * - [entityTypeCounts]：按 entity.entityType 聚合，null/空白归为 "unknown"，键按字典序；
 * - [documentsWithEvents]：至少有一条 event（含历史 superseded 行）的 document 数
 *   （event.chunkId→chunk.documentId 去重），
 *   [documentsWithoutEvents] = documentsTotal - documentsWithEvents（0 保护）；
 * - [jobsByStatus]/[jobsByErrorCode]：extraction_job.status / errorCode 聚合（errorCode 只取非空值，
 *   **绝不导出 errorDetail**），键按字典序；
 * - [extractionCoverage] = documentsWithEvents/documentsTotal（0 时 0.0）；
 *   [aliasCoverage] = entitiesWithAliases/entitiesTotal（0 时 0.0）。
 *
 * 隐私：本报告只有计数/比率/枚举分布；[toJson] 也只输出这些字段，不含 event 正文、chunk 内容、
 * errorDetail、API Key 等任何敏感信息。
 */
data class ExtractionQualityReport(
    val documentsTotal: Int,
    val readyDocuments: Int,
    val eventsTotal: Int,
    val eventsActive: Int,
    val eventsDiscarded: Int,
    val blankEventFields: BlankEventFields,
    val entitiesTotal: Int,
    val entitiesWithAliases: Int,
    val aliasesTotal: Int,
    val entityTypeCounts: Map<String, Int>,
    val eventEntityLinks: Int,
    val documentsWithEvents: Int,
    val documentsWithoutEvents: Int,
    val extractionJobsTotal: Int,
    val jobsByStatus: Map<String, Int>,
    val jobsByErrorCode: Map<String, Int>,
) {
    /** 文档级抽取覆盖率（0 保护）。 */
    val extractionCoverage: Double
        get() = if (documentsTotal <= 0) 0.0 else documentsWithEvents.toDouble() / documentsTotal

    /** 实体别名维护覆盖率（0 保护）。 */
    val aliasCoverage: Double
        get() = if (entitiesTotal <= 0) 0.0 else entitiesWithAliases.toDouble() / entitiesTotal

    /**
     * 稳定键顺序的脱敏 JSON（单行；比率保留 4 位小数）。
     *
     * 字段顺序固定；entityTypeCounts/jobsByStatus/jobsByErrorCode 的键已按字典序（analyzer 保证）。
     * 不包含任何正文/errorDetail/chunk/密钥。
     */
    fun toJson(): String = buildString {
        append('{')
        appendJsonField("documentsTotal", documentsTotal)
        append(',')
        appendJsonField("readyDocuments", readyDocuments)
        append(',')
        appendJsonField("eventsTotal", eventsTotal)
        append(',')
        appendJsonField("eventsActive", eventsActive)
        append(',')
        appendJsonField("eventsDiscarded", eventsDiscarded)
        append(',')
        append("\"blankEventFields\":{")
        appendJsonField("title", blankEventFields.title)
        append(',')
        appendJsonField("summary", blankEventFields.summary)
        append(',')
        appendJsonField("content", blankEventFields.content)
        append('}')
        append(',')
        appendJsonField("entitiesTotal", entitiesTotal)
        append(',')
        appendJsonField("entitiesWithAliases", entitiesWithAliases)
        append(',')
        appendJsonField("aliasesTotal", aliasesTotal)
        append(',')
        append("\"entityTypeCounts\":")
        appendJsonIntMap(entityTypeCounts)
        append(',')
        appendJsonField("eventEntityLinks", eventEntityLinks)
        append(',')
        appendJsonField("documentsWithEvents", documentsWithEvents)
        append(',')
        appendJsonField("documentsWithoutEvents", documentsWithoutEvents)
        append(',')
        appendJsonField("extractionJobsTotal", extractionJobsTotal)
        append(',')
        append("\"jobsByStatus\":")
        appendJsonIntMap(jobsByStatus)
        append(',')
        append("\"jobsByErrorCode\":")
        appendJsonIntMap(jobsByErrorCode)
        append(',')
        append("\"extractionCoverage\":")
        append(formatRatio(extractionCoverage))
        append(',')
        append("\"aliasCoverage\":")
        append(formatRatio(aliasCoverage))
        append('}')
    }

    private fun StringBuilder.appendJsonField(name: String, value: Int) {
        append('"').append(name).append("\":").append(value)
    }

    private fun StringBuilder.appendJsonIntMap(map: Map<String, Int>) {
        append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) append(',')
            first = false
            appendJsonString(key)
            append(':').append(value)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private fun formatRatio(value: Double): String =
        String.format(Locale.ROOT, "%.4f", value)
}

/**
 * B15.2：抽取质量只读诊断器。
 *
 * 设计约束：
 * - **只读**：仅执行 SELECT 聚合（`readableDatabase`），不写 DB、不触发 FTS/向量重建；
 * - **不加载正文**：所有指标由 COUNT/SUM/GROUP BY 聚合得到，SQL 不 SELECT event/chunk 的 content/title/summary，
 *   也不读取 extraction_job.errorDetail；
 * - **确定性**：分组结果 ORDER BY 键；Map 使用 LinkedHashMap 保序；同一 DB 快照多次 analyze 结果一致；
 * - **可取消**：在 IO 线程执行，查询前后检查协程取消；CancellationException 原样抛出（不包装）。
 */
object ExtractionQualityAnalyzer {

    /** 质量报告格式版本（供 UI/脚本兼容判断；与备份 FORMAT_VERSION 无关）。 */
    const val REPORT_VERSION: Int = 1

    /** 便捷入口：等价于 [ExtractionQualityReport.toJson]（稳定键顺序/脱敏）。 */
    fun toJson(report: ExtractionQualityReport): String = report.toJson()

    suspend fun analyze(db: AppDatabase): ExtractionQualityReport = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val readable: SupportSQLiteDatabase = db.openHelper.readableDatabase

        val documentsTotal = queryCount(readable, SQL_DOCUMENTS_TOTAL)
        val readyDocuments = queryCount(readable, SQL_READY_DOCUMENTS)

        val (eventsTotal, eventsActive) = queryPair(readable, SQL_EVENTS)
        val eventsDiscarded = (eventsTotal - eventsActive).coerceAtLeast(0)

        val blank = queryTriple(readable, SQL_BLANK_EVENT_FIELDS)

        val entitiesTotal = queryCount(readable, SQL_ENTITIES_TOTAL)
        val entitiesWithAliases = queryCount(readable, SQL_ENTITIES_WITH_ALIASES)
        val aliasesTotal = queryCount(readable, SQL_ALIASES_TOTAL)
        val entityTypeCounts = queryIntMap(readable, SQL_ENTITY_TYPES)
        val eventEntityLinks = queryCount(readable, SQL_EVENT_ENTITY_LINKS)

        val documentsWithEvents = queryCount(readable, SQL_DOCUMENTS_WITH_EVENTS)
            .coerceAtMost(documentsTotal)
        val documentsWithoutEvents = (documentsTotal - documentsWithEvents).coerceAtLeast(0)

        val extractionJobsTotal = queryCount(readable, SQL_JOBS_TOTAL)
        val jobsByStatus = queryIntMap(readable, SQL_JOBS_BY_STATUS)
        val jobsByErrorCode = queryIntMap(readable, SQL_JOBS_BY_ERROR_CODE)

        currentCoroutineContext().ensureActive()
        ExtractionQualityReport(
            documentsTotal = documentsTotal,
            readyDocuments = readyDocuments,
            eventsTotal = eventsTotal,
            eventsActive = eventsActive,
            eventsDiscarded = eventsDiscarded,
            blankEventFields = BlankEventFields(
                title = blank.first,
                summary = blank.second,
                content = blank.third,
            ),
            entitiesTotal = entitiesTotal,
            entitiesWithAliases = entitiesWithAliases,
            aliasesTotal = aliasesTotal,
            entityTypeCounts = entityTypeCounts,
            eventEntityLinks = eventEntityLinks,
            documentsWithEvents = documentsWithEvents,
            documentsWithoutEvents = documentsWithoutEvents,
            extractionJobsTotal = extractionJobsTotal,
            jobsByStatus = jobsByStatus,
            jobsByErrorCode = jobsByErrorCode,
        )
    }

    // ---- 聚合查询（只 SELECT 计数，不读取正文/errorDetail） ----

    private const val SQL_DOCUMENTS_TOTAL = "SELECT COUNT(*) FROM document"

    private const val SQL_READY_DOCUMENTS = "SELECT COUNT(*) FROM document WHERE status = 'ready'"

    private const val SQL_EVENTS =
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN active = 1 THEN 1 ELSE 0 END), 0) AS active " +
            "FROM event"

    private const val SQL_BLANK_EVENT_FIELDS =
        "SELECT " +
            "COALESCE(SUM(CASE WHEN TRIM(COALESCE(title, '')) = '' THEN 1 ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN TRIM(COALESCE(summary, '')) = '' THEN 1 ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN TRIM(COALESCE(content, '')) = '' THEN 1 ELSE 0 END), 0) " +
            "FROM event"

    private const val SQL_ENTITIES_TOTAL = "SELECT COUNT(*) FROM entity"

    private const val SQL_ENTITIES_WITH_ALIASES =
        "SELECT COUNT(DISTINCT entityId) FROM entity_alias"

    private const val SQL_ALIASES_TOTAL = "SELECT COUNT(*) FROM entity_alias"

    private const val SQL_ENTITY_TYPES =
        "SELECT CASE WHEN entityType IS NULL OR TRIM(entityType) = '' THEN 'unknown' " +
            "ELSE entityType END AS type, COUNT(*) " +
            "FROM entity GROUP BY type ORDER BY type"

    private const val SQL_EVENT_ENTITY_LINKS = "SELECT COUNT(*) FROM event_entity"

    private const val SQL_DOCUMENTS_WITH_EVENTS =
        "SELECT COUNT(DISTINCT c.documentId) FROM event e " +
            "JOIN chunk c ON c.id = e.chunkId"

    private const val SQL_JOBS_TOTAL = "SELECT COUNT(*) FROM extraction_job"

    private const val SQL_JOBS_BY_STATUS =
        "SELECT status, COUNT(*) FROM extraction_job GROUP BY status ORDER BY status"

    private const val SQL_JOBS_BY_ERROR_CODE =
        "SELECT errorCode, COUNT(*) FROM extraction_job " +
            "WHERE errorCode IS NOT NULL AND TRIM(errorCode) <> '' " +
            "GROUP BY errorCode ORDER BY errorCode"

    private fun queryCount(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun queryPair(db: SupportSQLiteDatabase, sql: String): Pair<Int, Int> =
        db.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getInt(1) else 0 to 0
        }

    private fun queryTriple(db: SupportSQLiteDatabase, sql: String): Triple<Int, Int, Int> =
        db.query(sql).use { cursor ->
            if (cursor.moveToFirst()) {
                Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
            } else {
                Triple(0, 0, 0)
            }
        }

    /** 两列（key,count）聚合 → 有序 LinkedHashMap（SQL 已 ORDER BY key）。 */
    private fun queryIntMap(db: SupportSQLiteDatabase, sql: String): Map<String, Int> =
        db.query(sql).use { cursor: Cursor ->
            val result = LinkedHashMap<String, Int>()
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) ?: continue
                result[key] = cursor.getInt(1)
            }
            result
        }
}
