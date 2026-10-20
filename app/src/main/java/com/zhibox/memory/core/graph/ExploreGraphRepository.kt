package com.zhibox.memory.core.graph

import android.database.Cursor
import androidx.annotation.VisibleForTesting
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhibox.memory.core.sag.SagSupport
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * P1.3b：Explore 只读图谱加载（FTS 优先 + LIKE 回退 + offset 分页）。
 *
 * 查询路径：
 * - `query` trim 后为空：全量 active event 的 `LIMIT/OFFSET` 分页（COUNT 得 totalEvents）；
 * - `query` 非空：优先 FTS4
 *   - `event_fts`（SagSupport.queryGrams/ftsOrMatch；SAG 抽取写入的 title/summary/content bigram）；
 *   - `entity_fts`（B15.1 起文本含 alias）命中 entity → `event_entity` 关联事件；
 *   两路 event id 去重、按 active/source 过滤后按 id 升序分页；候选用有界上限
 *   （事件 5000 / 实体 2000），超限 `truncated=true`；
 * - FTS 无召回（空索引/旧库/单字查询无法生成 bigram）→ 回退旧 LIKE 路径
 *   （event.title/summary/content + entity.name/normalizedName + entity_alias.alias）。
 *
 * 展示/隐私：事件只 SELECT id/title/summary + documentId/sourceId（**不加载 content**；
 * content 只出现在 LIKE 回退的 WHERE 中）；entity 只取 name/entityType；图里不含 chunk 正文。
 *
 * 分页语义：
 * - `offset` 为匹配事件中的偏移（稳定按 event.id 升序）；`maxEvents` 夹取 1..200（默认 100）；
 * - `totalEvents` = 全量匹配数量（FTS 路径为有界候选内精确数，候选上限时 truncated 标记）；
 * - `hasMore` = `offset + 当前页事件数 < totalEvents`（offset 越界返回空图且 hasMore=false）；
 * - `truncated` 只表示候选/实体/布局硬上限（分页还有页看 `hasMore`，不再因 total>单页误报）。
 *
 * 只读/取消：全部 SELECT；IO 线程 + 查询边界 ensureActive；CancellationException 原样抛。
 */
object ExploreGraphRepository {

    /** 默认单页事件数（P1.3b）。 */
    const val DEFAULT_MAX_EVENTS: Int = 100

    /** FTS 事件候选有界上限（超出 → truncated）。 */
    @VisibleForTesting
    internal const val EVENT_FTS_CANDIDATE_LIMIT: Int = 5_000

    /** FTS 实体候选有界上限（entity_fts 命中 → 关联事件；超出 → truncated）。 */
    @VisibleForTesting
    internal const val ENTITY_FTS_CANDIDATE_LIMIT: Int = 2_000

    private const val IN_QUERY_CHUNK = 500

    suspend fun load(
        db: AppDatabase,
        sourceId: String? = null,
        query: String = "",
        maxEvents: Int = DEFAULT_MAX_EVENTS,
        offset: Int = 0,
    ): ExploreGraph = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val limit = maxEvents.coerceIn(1, ExploreGraphBuilder.MAX_EVENTS)
        val pageOffset = offset.coerceAtLeast(0)
        val normalizedSource = sourceId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedQuery = query.trim().takeIf { it.isNotEmpty() }
        val readable: SupportSQLiteDatabase = db.openHelper.readableDatabase

        val graph = if (normalizedQuery == null) {
            loadSqlPage(readable, normalizedSource, query = null, limit = limit, pageOffset = pageOffset)
        } else {
            loadFtsPage(db, readable, normalizedSource, normalizedQuery, limit, pageOffset)
                ?: loadSqlPage(readable, normalizedSource, query = normalizedQuery, limit = limit, pageOffset = pageOffset)
        }
        currentCoroutineContext().ensureActive()
        graph
    }

    // ---- FTS 路径 ----

    /**
     * FTS 召回 + 分页；返回 null = FTS 无可用召回/无命中（调用方回退 LIKE）。
     * 调用方负责 IO 线程与取消检查。
     */
    private suspend fun loadFtsPage(
        db: AppDatabase,
        readable: SupportSQLiteDatabase,
        sourceId: String?,
        query: String,
        limit: Int,
        pageOffset: Int,
    ): ExploreGraph? {
        val grams = SagSupport.queryGrams(query)
        val match = SagSupport.ftsOrMatch(grams) ?: return null

        var truncated = false
        val eventFtsRaw = db.eventDao().searchFts(match, EVENT_FTS_CANDIDATE_LIMIT + 1)
        if (eventFtsRaw.size > EVENT_FTS_CANDIDATE_LIMIT) truncated = true
        val candidateEventIds = LinkedHashSet<Long>()
        candidateEventIds.addAll(eventFtsRaw.take(EVENT_FTS_CANDIDATE_LIMIT))

        val entityFtsRaw = db.entityDao().searchFts(match, ENTITY_FTS_CANDIDATE_LIMIT + 1)
        if (entityFtsRaw.size > ENTITY_FTS_CANDIDATE_LIMIT) truncated = true
        val entityIds = entityFtsRaw.take(ENTITY_FTS_CANDIDATE_LIMIT)
        if (entityIds.isNotEmpty()) {
            candidateEventIds.addAll(queryEventIdsByEntityIds(readable, entityIds))
        }
        if (candidateEventIds.size > EVENT_FTS_CANDIDATE_LIMIT) truncated = true
        val candidates = candidateEventIds.sorted().take(EVENT_FTS_CANDIDATE_LIMIT)
        if (candidates.isEmpty()) return null

        currentCoroutineContext().ensureActive()
        val rows = queryEventRowsByIds(readable, candidates, sourceId)
        if (rows.isEmpty()) return null // 索引命中但被 active/source 过滤掉：回退 LIKE（语义更稳）
        val total = rows.size
        val pageRows = rows.drop(pageOffset).take(limit)
        if (pageRows.isEmpty()) {
            return emptyGraph(limit, pageOffset, total, truncated = truncated)
        }
        val hasMore = pageOffset + pageRows.size < total
        val graph = assembleGraph(
            readable,
            pageRows,
            limit,
            pageOffset,
            totalEvents = total,
            hasMore = hasMore,
            // 分页 hasMore 负责“还有页”；truncated 只表示候选/实体/布局硬上限，
            // 不能因为 total>单页就把正常分页误报为裁剪。
            extraTruncated = truncated,
        )
        return graph
    }

    /** entity_fts 命中的 entity → 其关联事件 id（有界；结果去重由调用方处理）。 */
    private fun queryEventIdsByEntityIds(
        readable: SupportSQLiteDatabase,
        entityIds: List<Long>,
    ): List<Long> {
        val result = ArrayList<Long>()
        for (chunk in entityIds.chunked(IN_QUERY_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            readable.query(
                "SELECT DISTINCT eventId FROM event_entity WHERE entityId IN ($placeholders) " +
                    "ORDER BY eventId ASC",
                chunk.toTypedArray(),
            ).use { cursor: Cursor ->
                while (cursor.moveToNext()) result += cursor.getLong(0)
            }
        }
        return result
    }

    /** 按候选 id 有界分批查询 active/source 事件（只取 id/title/summary/document/source）。 */
    private fun queryEventRowsByIds(
        readable: SupportSQLiteDatabase,
        eventIds: List<Long>,
        sourceId: String?,
    ): List<EventRow> {
        val rows = LinkedHashMap<Long, EventRow>()
        for (chunk in eventIds.chunked(IN_QUERY_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<Any?>(chunk.size + 1)
            args.addAll(chunk)
            val sourceClause = if (sourceId != null) " AND d.sourceId = ?" else ""
            if (sourceId != null) args += sourceId
            readable.query(
                "SELECT e.id, e.title, e.summary, c.documentId, d.sourceId$FROM_EVENTS " +
                    "WHERE e.active = 1 AND e.id IN ($placeholders)$sourceClause ORDER BY e.id ASC",
                args.toTypedArray(),
            ).use { cursor: Cursor ->
                while (cursor.moveToNext()) {
                    val row = EventRow(
                        id = cursor.getLong(0),
                        title = cursor.getString(1).orEmpty(),
                        summary = cursor.getString(2).orEmpty(),
                        documentId = cursor.getString(3) ?: "",
                        sourceId = cursor.getString(4) ?: "",
                    )
                    rows[row.id] = row
                }
            }
        }
        return rows.values.sortedBy { it.id }
    }

    // ---- SQL（LIKE / 全量分页）路径 ----

    private fun loadSqlPage(
        readable: SupportSQLiteDatabase,
        sourceId: String?,
        query: String?,
        limit: Int,
        pageOffset: Int,
    ): ExploreGraph {
        val where = buildWhere(sourceId, query)
        val total = countEvents(readable, where)
        if (total <= 0) return ExploreGraph.empty(limit, pageOffset)
        val pageRows = queryEventRows(readable, where, limit, pageOffset)
        if (pageRows.isEmpty()) {
            return emptyGraph(limit, pageOffset, total, truncated = false)
        }
        val hasMore = pageOffset + pageRows.size < total
        return assembleGraph(
            readable,
            pageRows,
            limit,
            pageOffset,
            totalEvents = total,
            hasMore = hasMore,
            extraTruncated = false,
        )
    }

    // ---- 图装配（事件页 → 关联实体/边 → Builder 布局） ----

    private fun assembleGraph(
        readable: SupportSQLiteDatabase,
        eventRows: List<EventRow>,
        limit: Int,
        pageOffset: Int,
        totalEvents: Int,
        hasMore: Boolean,
        extraTruncated: Boolean,
    ): ExploreGraph {
        val linksByEvent = queryLinks(readable, eventRows.map { it.id })
        val allEntityIds = linksByEvent.values.flatten().distinct().sorted()
        val entityIds = allEntityIds.take(ExploreGraphBuilder.MAX_ENTITIES)
        val entityIdsSet = entityIds.toHashSet()
        val entityRows = queryEntityRows(readable, entityIds)
        val graph = ExploreGraphBuilder.build(
            events = eventRows.map { row ->
                ExploreEventInput(
                    eventId = row.id,
                    title = row.title,
                    summary = row.summary,
                    sourceId = row.sourceId,
                    documentId = row.documentId,
                    entityIds = linksByEvent[row.id].orEmpty().filter { it in entityIdsSet },
                )
            },
            entities = entityRows.map { row ->
                ExploreEntityInput(entityId = row.id, name = row.name, entityType = row.entityType)
            },
            maxEvents = limit,
            maxEntities = ExploreGraphBuilder.MAX_ENTITIES,
            totalEvents = totalEvents,
            pageOffset = pageOffset,
            hasMore = hasMore,
        )
        return graph.copy(
            truncated = graph.truncated ||
                extraTruncated ||
                allEntityIds.size > ExploreGraphBuilder.MAX_ENTITIES,
        )
    }

    private fun emptyGraph(
        limit: Int,
        pageOffset: Int,
        totalEvents: Int,
        truncated: Boolean,
    ): ExploreGraph = ExploreGraph.empty(
        maxEvents = limit,
        pageOffset = pageOffset,
        totalEvents = totalEvents,
        truncated = truncated,
    )

    // ---- 通用 SQL 片段/工具 ----

    private const val FROM_EVENTS =
        " FROM event e JOIN chunk c ON c.id = e.chunkId JOIN document d ON d.id = c.documentId"

    private const val LIKE_ESCAPE = "ESCAPE '\\'"

    private data class WhereClause(val sql: String, val args: List<Any?>)

    internal data class EventRow(
        val id: Long,
        val title: String,
        val summary: String,
        val documentId: String,
        val sourceId: String,
    )

    private data class EntityRow(
        val id: Long,
        val name: String,
        val entityType: String?,
    )

    private fun buildWhere(sourceId: String?, query: String?): WhereClause {
        val clauses = mutableListOf("e.active = 1")
        val args = mutableListOf<Any?>()
        if (sourceId != null) {
            clauses += "d.sourceId = ?"
            args += sourceId
        }
        if (query != null) {
            val like = "%${escapeLike(query)}%"
            clauses += (
                "(e.title LIKE ? $LIKE_ESCAPE OR e.summary LIKE ? $LIKE_ESCAPE " +
                    "OR e.content LIKE ? $LIKE_ESCAPE OR EXISTS (" +
                    "SELECT 1 FROM event_entity ee " +
                    "JOIN entity en ON en.id = ee.entityId " +
                    "LEFT JOIN entity_alias a ON a.entityId = en.id " +
                    "WHERE ee.eventId = e.id AND (" +
                    "en.name LIKE ? $LIKE_ESCAPE OR en.normalizedName LIKE ? $LIKE_ESCAPE " +
                    "OR a.alias LIKE ? $LIKE_ESCAPE OR a.normalizedAlias LIKE ? $LIKE_ESCAPE)))"
                )
            repeat(7) { args += like }
        }
        return WhereClause(clauses.joinToString(" AND "), args)
    }

    private fun countEvents(db: SupportSQLiteDatabase, where: WhereClause): Int =
        db.query(
            "SELECT COUNT(DISTINCT e.id)$FROM_EVENTS WHERE ${where.sql}",
            where.args.toTypedArray(),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun queryEventRows(
        db: SupportSQLiteDatabase,
        where: WhereClause,
        limit: Int,
        offset: Int,
    ): List<EventRow> =
        db.query(
            "SELECT DISTINCT e.id, e.title, e.summary, c.documentId, d.sourceId" +
                "$FROM_EVENTS WHERE ${where.sql} ORDER BY e.id ASC LIMIT ? OFFSET ?",
            (where.args + limit + offset).toTypedArray(),
        ).use { cursor: Cursor ->
            val rows = ArrayList<EventRow>()
            while (cursor.moveToNext()) {
                rows += EventRow(
                    id = cursor.getLong(0),
                    title = cursor.getString(1).orEmpty(),
                    summary = cursor.getString(2).orEmpty(),
                    documentId = cursor.getString(3) ?: "",
                    sourceId = cursor.getString(4) ?: "",
                )
            }
            rows
        }

    private fun queryLinks(
        db: SupportSQLiteDatabase,
        eventIds: List<Long>,
    ): Map<Long, List<Long>> {
        if (eventIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, MutableList<Long>>()
        for (chunk in eventIds.chunked(IN_QUERY_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.query(
                "SELECT eventId, entityId FROM event_entity WHERE eventId IN ($placeholders) " +
                    "ORDER BY eventId ASC, entityId ASC",
                chunk.toTypedArray(),
            ).use { cursor: Cursor ->
                while (cursor.moveToNext()) {
                    result.getOrPut(cursor.getLong(0)) { ArrayList() } += cursor.getLong(1)
                }
            }
        }
        return result
    }

    private fun queryEntityRows(
        db: SupportSQLiteDatabase,
        entityIds: List<Long>,
    ): List<EntityRow> {
        if (entityIds.isEmpty()) return emptyList()
        val rows = ArrayList<EntityRow>(entityIds.size)
        for (chunk in entityIds.chunked(IN_QUERY_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.query(
                "SELECT id, name, entityType FROM entity WHERE id IN ($placeholders) ORDER BY id ASC",
                chunk.toTypedArray(),
            ).use { cursor: Cursor ->
                while (cursor.moveToNext()) {
                    rows += EntityRow(
                        id = cursor.getLong(0),
                        name = cursor.getString(1).orEmpty(),
                        entityType = cursor.getString(2),
                    )
                }
            }
        }
        return rows.sortedBy { it.id }
    }

    /** SQLite LIKE 通配符转义（用户关键词按字面匹配，`\` 为 ESCAPE 字符）。 */
    private fun escapeLike(raw: String): String = buildString(raw.length + 8) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '%' -> append("\\%")
                '_' -> append("\\_")
                else -> append(ch)
            }
        }
    }
}
