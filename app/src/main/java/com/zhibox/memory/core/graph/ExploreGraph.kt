package com.zhibox.memory.core.graph

import kotlin.math.sqrt

/** P1.3：3D 图谱节点位置（[-1,1] 空间；单位球/次级球面）。 */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun length(): Double = sqrt(x * x + y * y + z * z)

    /** 归一到单位长度；零向量返回 null（调用方给 fallback）。 */
    fun normalizedOrNull(): Vec3? {
        val len = length()
        if (len < 1e-9) return null
        return Vec3(x / len, y / len, z / len)
    }

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    fun scaled(factor: Double): Vec3 = Vec3(x * factor, y * factor, z * factor)

    companion object {
        val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0)
    }
}

/** P1.3：Explore 图节点类型。 */
enum class ExploreNodeKind { EVENT, ENTITY }

/**
 * P1.3：Explore 3D 图节点（只读展示数据；label/subtitle 为展示文案，不含正文 chunk）。
 *
 * id 约定：`e:{eventId}` / `t:{entityId}`（稳定、可用于 UI key/选中态）。
 */
data class ExploreNode(
    val id: String,
    val kind: ExploreNodeKind,
    val label: String,
    val subtitle: String,
    val entityType: String?,
    val position: Vec3,
    val eventId: Long? = null,
    val entityId: Long? = null,
    val sourceId: String? = null,
    val documentId: String? = null,
)

/** P1.3：Explore 图边（事件↔实体；from/to 为 ExploreNode.id，已去重）。 */
data class ExploreEdge(
    val from: String,
    val to: String,
)

/** P1.3：Explore 图谱快照（确定性布局；truncated=结果被上限裁剪）。 */
data class ExploreGraph(
    val nodes: List<ExploreNode>,
    val edges: List<ExploreEdge>,
    val truncated: Boolean,
    val totalEvents: Int,
    val maxEvents: Int,
    /** 本次查询的 offset（分页控件回显；越界页面时仍为请求 offset）。 */
    val pageOffset: Int = 0,
    /** 是否还有下一页（offset + 当前页事件数 < totalEvents，且未被候选上限截断）。 */
    val hasMore: Boolean = false,
) {
    val eventNodes: List<ExploreNode> get() = nodes.filter { it.kind == ExploreNodeKind.EVENT }
    val entityNodes: List<ExploreNode> get() = nodes.filter { it.kind == ExploreNodeKind.ENTITY }

    companion object {
        fun empty(
            maxEvents: Int = ExploreGraphBuilder.MAX_EVENTS,
            pageOffset: Int = 0,
            totalEvents: Int = 0,
            truncated: Boolean = false,
        ): ExploreGraph = ExploreGraph(
            nodes = emptyList(),
            edges = emptyList(),
            truncated = truncated,
            totalEvents = totalEvents,
            maxEvents = maxEvents,
            pageOffset = pageOffset,
            hasMore = false,
        )
    }
}

/** Builder 输入：一条 active event 的展示/关联信息（repository 只加载这些列，不加载 chunk 正文）。 */
data class ExploreEventInput(
    val eventId: Long,
    val title: String,
    val summary: String,
    val sourceId: String?,
    val documentId: String?,
    val entityIds: List<Long>,
)

/** Builder 输入：一个 entity 的展示信息。 */
data class ExploreEntityInput(
    val entityId: Long,
    val name: String,
    val entityType: String?,
)
