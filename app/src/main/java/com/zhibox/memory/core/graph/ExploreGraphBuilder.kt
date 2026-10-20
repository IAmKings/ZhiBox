package com.zhibox.memory.core.graph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * P1.3：确定性 Explore 图布局（纯 JVM，无 Android/Room/随机数）。
 *
 * 布局规则：
 * - entity：按 entityId 升序排列后使用 **Fibonacci 球面**均匀分布在单位球上（确定性）；
 * - event 有关联 entity：位置 = 关联 entity 位置均值 + 基于 eventId 的确定性抖动，
 *   归一化到单位球（保持"靠近关联实体方向"）；抖动幅度小，节点不会重叠；
 * - event 无（包内）关联 entity：分布在**另一个次级球面**（半径 [UNASSOCIATED_RADIUS]），
 *   方向由 eventId 的确定性 hash 决定；
 * - 所有坐标落在 [-1,1]（单位球/次级球面均满足）。
 *
 * 上限（产品硬约束）：event ≤ [MAX_EVENTS]=200、entity ≤ [MAX_ENTITIES]=400；
 * 超限 `truncated=true`（totalEvents 仍为输入总数，供 UI 提示）。
 */
object ExploreGraphBuilder {

    const val MAX_EVENTS: Int = 200
    const val MAX_ENTITIES: Int = 400

    /** 无关联事件的次级球面半径（与单位球区分，仍落在 [-1,1]）。 */
    const val UNASSOCIATED_RADIUS: Double = 0.55

    private const val GOLDEN_ANGLE: Double = 2.399963229728653
    private const val JITTER_SCALE: Double = 0.18

    /**
     * @param events 候选事件（可含超过上限的输入；内部按 eventId 升序截断）
     * @param entities 候选实体（可含超过上限的输入；内部按 entityId 升序截断）
     * @param maxEvents 事件上限（默认/夹紧到 [MAX_EVENTS]）
     * @param maxEntities 实体上限（默认/夹紧到 [MAX_ENTITIES]）
     * @param totalEvents 输入事件总数（repository 传入 DB 匹配总数；默认 events.size）
     */
    fun build(
        events: List<ExploreEventInput>,
        entities: List<ExploreEntityInput>,
        maxEvents: Int = MAX_EVENTS,
        maxEntities: Int = MAX_ENTITIES,
        totalEvents: Int = events.size,
    ): ExploreGraph {
        val eventLimit = maxEvents.coerceIn(1, MAX_EVENTS)
        val entityLimit = maxEntities.coerceIn(1, MAX_ENTITIES)
        if (events.isEmpty() && entities.isEmpty()) {
            return ExploreGraph.empty(eventLimit)
        }

        val sortedEvents = events.sortedBy { it.eventId }
        val sortedEntities = entities.sortedBy { it.entityId }
        val selectedEvents = sortedEvents.take(eventLimit)
        val selectedEntities = sortedEntities.take(entityLimit)
        val truncated = events.size > eventLimit || entities.size > entityLimit

        val entityPositions: Map<Long, Vec3> = fibonacciSphere(selectedEntities.size)
            .mapIndexed { index, position -> selectedEntities[index].entityId to position }
            .toMap()
        val entityNodes = selectedEntities.mapIndexed { index, entity ->
            ExploreNode(
                id = entityNodeId(entity.entityId),
                kind = ExploreNodeKind.ENTITY,
                label = entity.name.ifBlank { "实体 ${entity.entityId}" },
                subtitle = entity.entityType.orEmpty(),
                entityType = entity.entityType,
                position = entityPositions.getValue(entity.entityId),
                entityId = entity.entityId,
            )
        }

        val eventNodes = selectedEvents.map { event ->
            val linkedPositions = event.entityIds
                .distinct()
                .mapNotNull { entityPositions[it] }
            val position = if (linkedPositions.isNotEmpty()) {
                val mean = linkedPositions.reduce(Vec3::plus).scaled(1.0 / linkedPositions.size)
                val jitter = Vec3(
                    x = (hash01(event.eventId, salt = 11L) - 0.5) * 2.0 * JITTER_SCALE,
                    y = (hash01(event.eventId, salt = 23L) - 0.5) * 2.0 * JITTER_SCALE,
                    z = (hash01(event.eventId, salt = 37L) - 0.5) * 2.0 * JITTER_SCALE,
                )
                val nearLinked = (mean + jitter).normalizedOrNull()
                    ?: deterministicDirection(event.eventId, salt = 51L)
                // 单位球方向；"实体位置均值附近"由方向保持（抖动小）
                nearLinked
            } else {
                // 另一球面：次级半径，方向由 eventId 确定性 hash 决定
                deterministicDirection(event.eventId, salt = 71L).scaled(UNASSOCIATED_RADIUS)
            }
            ExploreNode(
                id = eventNodeId(event.eventId),
                kind = ExploreNodeKind.EVENT,
                label = event.title.ifBlank { "事件 ${event.eventId}" },
                subtitle = event.summary,
                entityType = null,
                position = position,
                eventId = event.eventId,
                sourceId = event.sourceId,
                documentId = event.documentId,
            )
        }

        val eventNodeIds = eventNodes.mapTo(HashSet()) { it.id }
        val entityNodeIds = entityNodes.mapTo(HashSet()) { it.id }
        val edgeKeys = LinkedHashSet<Pair<String, String>>()
        selectedEvents.forEach { event ->
            val from = eventNodeId(event.eventId)
            if (from !in eventNodeIds) return@forEach
            event.entityIds.distinct().sorted().forEach { entityId ->
                val to = entityNodeId(entityId)
                if (to in entityNodeIds) edgeKeys += from to to
            }
        }
        val edges = edgeKeys
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (from, to) -> ExploreEdge(from = from, to = to) }

        return ExploreGraph(
            nodes = eventNodes + entityNodes,
            edges = edges,
            truncated = truncated,
            totalEvents = max(totalEvents, 0),
            maxEvents = eventLimit,
        )
    }

    fun eventNodeId(eventId: Long): String = "e:$eventId"

    fun entityNodeId(entityId: Long): String = "t:$entityId"

    // ---- 确定性布局工具 ----

    /** Fibonacci 球面：n 个点在单位球上近似均匀（确定性；n=0 → 空）。 */
    private fun fibonacciSphere(n: Int): List<Vec3> {
        if (n <= 0) return emptyList()
        return (0 until n).map { index ->
            val z = 1.0 - 2.0 * (index + 0.5) / n
            val radius = sqrt(max(0.0, 1.0 - z * z))
            val phi = index * GOLDEN_ANGLE
            Vec3(x = cos(phi) * radius, y = sin(phi) * radius, z = z)
        }
    }

    /** eventId 哈希出的确定性单位方向。 */
    private fun deterministicDirection(seed: Long, salt: Long): Vec3 {
        val theta = hash01(seed, salt) * 2.0 * PI
        val u = hash01(seed, salt + 1L) * 2.0 - 1.0
        val radius = sqrt(max(0.0, 1.0 - u * u))
        return Vec3(x = cos(theta) * radius, y = sin(theta) * radius, z = u)
    }

    /** 稳定 hash → [0,1)（SplitMix64 变体；纯确定性，不依赖平台 Random）。 */
    private fun hash01(seed: Long, salt: Long): Double {
        var z = seed + salt * -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
