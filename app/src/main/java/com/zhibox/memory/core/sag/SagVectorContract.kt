package com.zhibox.memory.core.sag

/**
 * B8：SAG 向量角色与空间指纹契约（B9/B10 共用，避免 UI/测试各自写自由字符串）。
 *
 * 与 chunk_embedding 的 role 区分：SAG 层只定义 event/entity 两类语义角色；
 * chunk 仍用既有 `content` 角色。空间 = 基础 embedding space + role 派生指纹，
 * 查询/写入必须通过 [SagVectorSpace.spaceIdFor] 生成并通过 [SagVectorSpace.requireMatches] 校验，
 * 禁止把 event/entity 向量写入 chunk 空间或跨 role 混用。
 */
enum class SagVectorRole(val wire: String) {
    EVENT_CONTENT("event_content"),
    ENTITY_DESCRIPTION("entity_description"),
    ;

    companion object {
        val WIRES: List<String> = values().map { it.wire }
        private val byWire: Map<String, SagVectorRole> = values().associateBy { it.wire }

        fun fromWire(raw: String?): SagVectorRole? = raw?.let(byWire::get)
    }
}

object SagVectorSpace {
    private const val ROLE_SEPARATOR = "#role="

    /** 基础空间 id + role → 派生空间 id（稳定、可逆前缀；不含时间/随机量）。 */
    fun spaceIdFor(baseSpaceId: String, role: SagVectorRole): String {
        require(baseSpaceId.isNotBlank()) { "基础 embedding space 不能为空" }
        return "$baseSpaceId$ROLE_SEPARATOR${role.wire}"
    }

    /** 校验向量/查询使用的空间确实属于该 role；不匹配抛可读错误（由调用方转为降级/拒绝）。 */
    fun requireMatches(actualSpaceId: String, baseSpaceId: String, role: SagVectorRole) {
        val expected = spaceIdFor(baseSpaceId, role)
        require(actualSpaceId == expected) {
            "SAG 向量空间不匹配：role=${role.wire} 期望 $expected，实际 $actualSpaceId（模型/角色切换需重建向量）"
        }
    }
}
