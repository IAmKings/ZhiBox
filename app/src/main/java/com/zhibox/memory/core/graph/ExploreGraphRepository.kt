package com.zhibox.memory.core.graph

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * P1.3：Explore 只读图谱加载（read-only，不写 DB、不加载 chunk 正文）。
 *
 * 数据来源（聚合/有界 SQL）：
 * - 事件：仅 `event.active = 1`，经 chunk→document 关联来源；sourceId 可选过滤；
 * - 关键词 query：匹配 event.title/summary/content、关联 entity.name/normalizedName、
 *   entity_alias.alias/normalizedAlias（**alias 参与过滤**）；query trim 后为空 = 全部；
 * - 实体：只取命中事件经 `event_entity` 关联的实体，最多 400；事件最多 200（默认）；
 * - `totalEvents` = 命中 active event 总数（COUNT DISTINCT，不随 limit 截断）；
 *   `truncated` = 事件总数超 limit 或关联实体超 400。
 *
 * 边界：
 * - 空库/无命中 → 空图（totalEvents=0，truncated=false）；
 * - 加载严格有界：事件 SQL LIMIT、实体 IN 列表 ≤400，不做全库 event/entity `all()`；
 * - 只读：全部 SELECT；不写库/FTS/向量；
 * - 取消：IO 线程执行，查询前后 `ensureActive()`；CancellationException 原样传播。
 */
object ExploreGraphRepository {

    /** 默认/最大事件数（与 Builder 一致）。 */
    const val DEFAULT_MAX_EVENTS: Int = ExploreGraphBuilder.MAX_EVENTS

    suspend fun load(
        db: AppDatabase,
        sourceId: String? = null,
        query: String = "",
        maxEvents: Int = DEFAULT_MAX_EVENTS,
    ): ExploreGraph = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val limit = maxEvents.coerceIn(1, ExploreGraphBuilder.MAX_EVENTS)
        val normalizedSource = sourceId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedQuery = query.trim().takeIf { it.isNotEmpty() }
        val where = buildWhere(normalizedSource, normalizedQuery)
        val readable: SupportSQLiteDatabase = db.openHelper.readableDatabase

        val totalEvents = countEvents(readable, where)
        if (totalEvents <= 0) {
            currentCoroutineContext().ensureActive()
            return@withContext ExploreGraph.empty(limit)
        }

        val eventRows = queryEventRows(readable, where, limit)
        if (eventRows.isEmpty()) {
            currentCoroutineContext().ensureActive()
            return@withContext ExploreGraph.empty(limit)
        }
        currentCoroutineContext().ensureActive()

        val eventIds = eventRows.map { it.id }
        val linksByEvent: Map<Long, List<Long>> = queryLinks(readable, eventIds)
        val allEntityIds = linksByEvent.values.flatten().distinct().sorted()
        val entityIds = allEntityIds.take(ExploreGraphBuilder.MAX_ENTITIES)
        val entityIdsSet = entityIds.toHashSet()
        val entityRows = queryEntityRows(readable, entityIds)

        currentCoroutineContext().ensureActive()
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
        )
        currentCoroutineContext().ensureActive()
        graph.copy(
            truncated = graph.truncated ||
                totalEvents > limit ||
                allEntityIds.size > ExploreGraphBuilder.MAX_ENTITIES,
        )
    }

    // ---- SQL（只 SELECT 展示所需最小列；event.content 仅用于 WHERE 匹配，不读取正文） ----

    private const val FROM_EVENTS =
        " FROM event e JOIN chunk c ON c.id = e.chunkId JOIN document d ON d.id = c.documentId"

    private const val LIKE_ESCAPE = "ESCAPE '\\'"

    private data class WhereClause(val sql: String, val args: List<Any?>)

    private data class EventRow(
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
    ): List<EventRow> =
        db.query(
            "SELECT DISTINCT e.id, e.title, e.summary, c.documentId, d.sourceId" +
                "$FROM_EVENTS WHERE ${where.sql} ORDER BY e.id ASC LIMIT ?",
            (where.args + limit).toTypedArray(),
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
        val placeholders = eventIds.joinToString(",") { "?" }
        return db.query(
            "SELECT eventId, entityId FROM event_entity WHERE eventId IN ($placeholders) " +
                "ORDER BY eventId ASC, entityId ASC",
            eventIds.toTypedArray(),
        ).use { cursor: Cursor ->
            val result = LinkedHashMap<Long, MutableList<Long>>()
            while (cursor.moveToNext()) {
                result.getOrPut(cursor.getLong(0)) { ArrayList() } += cursor.getLong(1)
            }
            result
        }
    }

    private fun queryEntityRows(
        db: SupportSQLiteDatabase,
        entityIds: List<Long>,
    ): List<EntityRow> {
        if (entityIds.isEmpty()) return emptyList()
        val placeholders = entityIds.joinToString(",") { "?" }
        return db.query(
            "SELECT id, name, entityType FROM entity WHERE id IN ($placeholders) ORDER BY id ASC",
            entityIds.toTypedArray(),
        ).use { cursor: Cursor ->
            val rows = ArrayList<EntityRow>(entityIds.size)
            while (cursor.moveToNext()) {
                rows += EntityRow(
                    id = cursor.getLong(0),
                    name = cursor.getString(1).orEmpty(),
                    entityType = cursor.getString(2),
                )
            }
            rows
        }
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
