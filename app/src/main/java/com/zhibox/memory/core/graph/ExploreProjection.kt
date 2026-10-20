package com.zhibox.memory.core.graph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * P1.3：Explore 3D→2D 相机投影（纯 JVM、无 Android 依赖、确定性）。
 *
 * 坐标系：场景坐标 [-1,1]，相机在 +Z 方向看向原点，先绕 Y 轴 yaw 再绕 X 轴 pitch；
 * 透视投影到 width×height（fovDegrees 可调）。返回结果按**远→近**排序（painter's algorithm 绘制顺序），
 * 每个节点带 depth（相机空间距离）与 scale（近大远小缩放）供 UI 绘制/命中。
 */
class ExploreProjection(
    val yawRadians: Double = 0.0,
    val pitchRadians: Double = 0.0,
    val cameraDistance: Double = 3.0,
    val fovDegrees: Double = 60.0,
) {
    /** 投影后的节点（屏幕坐标 + 深度/缩放）。 */
    data class ProjectedNode(
        val node: ExploreNode,
        val screenX: Float,
        val screenY: Float,
        /** 相机空间深度；越大越远（绘制时先画远的）。 */
        val depth: Double,
        /** 近大远小所需缩放（focal / depth）。 */
        val scale: Float,
    )

    /**
     * 投影节点；width/height ≤0 或 nodes 为空返回空列表。
     * 相机后方（depth ≤ [NEAR_PLANE]）的节点被剔除；返回列表按 depth 降序（远→近）、同 depth 按 node.id。
     */
    fun project(nodes: List<ExploreNode>, width: Int, height: Int): List<ProjectedNode> {
        if (width <= 0 || height <= 0 || nodes.isEmpty()) return emptyList()
        val distance = cameraDistance.coerceAtLeast(0.1)
        val fov = fovDegrees.coerceIn(10.0, 150.0)
        val focal = (height.toDouble() / 2.0) / tan(fov * PI / 360.0)
        val cosYaw = cos(yawRadians)
        val sinYaw = sin(yawRadians)
        val cosPitch = cos(pitchRadians)
        val sinPitch = sin(pitchRadians)
        val centerX = width / 2.0
        val centerY = height / 2.0

        return nodes.mapNotNull { node ->
            val p = node.position
            // yaw around Y
            val x1 = p.x * cosYaw + p.z * sinYaw
            val z1 = -p.x * sinYaw + p.z * cosYaw
            // pitch around X
            val y2 = p.y * cosPitch - z1 * sinPitch
            val z2 = p.y * sinPitch + z1 * cosPitch
            val depth = distance - z2
            if (depth <= NEAR_PLANE) return@mapNotNull null
            val scale = focal / depth
            ProjectedNode(
                node = node,
                screenX = (centerX + x1 * scale).toFloat(),
                screenY = (centerY - y2 * scale).toFloat(),
                depth = depth,
                scale = scale.toFloat(),
            )
        }.sortedWith(
            compareByDescending<ProjectedNode> { it.depth }.thenBy { it.node.id },
        )
    }

    /**
     * 命中最近节点：屏幕距离 ≤ [thresholdPx] 的节点中取屏幕最近；距离并列时取 depth 更小（更近）者，
     * 再按 node.id 决定顺序（确定性）。空列表/无命中返回 null。
     */
    fun hitTest(
        projected: List<ProjectedNode>,
        x: Float,
        y: Float,
        thresholdPx: Float = DEFAULT_HIT_THRESHOLD_PX,
    ): ProjectedNode? {
        if (projected.isEmpty() || thresholdPx < 0f) return null
        val maxDistanceSq = thresholdPx.toDouble() * thresholdPx.toDouble()
        var best: ProjectedNode? = null
        var bestDistanceSq = Double.MAX_VALUE
        for (candidate in projected) {
            val dx = (candidate.screenX - x).toDouble()
            val dy = (candidate.screenY - y).toDouble()
            val distanceSq = dx * dx + dy * dy
            if (distanceSq > maxDistanceSq) continue
            val better = distanceSq < bestDistanceSq ||
                (distanceSq == bestDistanceSq && betterTie(candidate, best))
            if (best == null || better) {
                best = candidate
                bestDistanceSq = distanceSq
            }
        }
        return best
    }

    private fun betterTie(candidate: ProjectedNode, current: ProjectedNode?): Boolean {
        if (current == null) return true
        if (candidate.depth != current.depth) return candidate.depth < current.depth
        return candidate.node.id < current.node.id
    }

    companion object {
        /** 近裁剪：depth ≤ 该值视为相机后方/过近，剔除。 */
        const val NEAR_PLANE: Double = 0.05

        /** 默认命中半径（像素）。 */
        const val DEFAULT_HIT_THRESHOLD_PX: Float = 28f

        /** 便捷方法：单位球/任意 Vec3 的 yaw+pitch 旋转（供测试/诊断）。 */
        fun rotate(position: Vec3, yawRadians: Double, pitchRadians: Double): Vec3 {
            val x1 = position.x * cos(yawRadians) + position.z * sin(yawRadians)
            val z1 = -position.x * sin(yawRadians) + position.z * cos(yawRadians)
            val y2 = position.y * cos(pitchRadians) - z1 * sin(pitchRadians)
            val z2 = position.y * sin(pitchRadians) + z1 * cos(pitchRadians)
            return Vec3(x1, y2, z2)
        }
    }
}
