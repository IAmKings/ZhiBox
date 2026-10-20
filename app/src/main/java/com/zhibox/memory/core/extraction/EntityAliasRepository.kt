package com.zhibox.memory.core.extraction

import androidx.room.withTransaction
import com.zhibox.memory.core.sag.SagSupport
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityFtsEntity

/**
 * B15.1（schema v9）：实体别名管理（用户维护，离线）。
 *
 * 职责：
 * - 校验 + 归一化 + 持久化别名；拒绝空/过短/过长/控制字符/等于 canonical 名称/重复（normalized 唯一）；
 * - 每次增删后**事务内刷新该 entity 的 entity_fts**（文本 = name + description + 全部 aliases，
 *   与 [SagFtsRebuilder]/日常抽取写入同一口径），从而"按别名检索"可经精确/子串/FTS 命中；
 * - 只改 `entity_alias` + 派生 `entity_fts`：**不修改 entity 本体 / event / event_entity**（append-only 语义不变）。
 *
 * 失败语义：校验失败抛可读 [IllegalArgumentException]（message 可直接展示给用户）；
 * DB 异常原样抛出（由调用方按 IO/未知失败处理）。
 */
class EntityAliasRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 新增别名并刷新实体 FTS。
     *
     * @return 持久化后的别名行（含自增 id）
     * @throws IllegalArgumentException 实体不存在、别名长度非法、含控制字符、与实体名称相同、
     *   该 normalizedAlias 已被占用（任何实体）、或该实体别名数量达到上限
     */
    suspend fun addAlias(entityId: Long, alias: String): EntityAliasEntity = db.withTransaction {
        val trimmed = alias.trim()
        require(trimmed.length in MIN_ALIAS_LENGTH..MAX_ALIAS_LENGTH) {
            "别名长度需为 $MIN_ALIAS_LENGTH-$MAX_ALIAS_LENGTH 个字符"
        }
        require(trimmed.none { it.isISOControl() }) { "别名不能包含控制字符" }
        val normalized = SagSupport.normalizeEntityName(trimmed)
        require(normalized.isNotEmpty()) { "别名不能为空" }

        val entity = db.entityDao().byId(entityId)
            ?: throw IllegalArgumentException("实体不存在（id=$entityId）")
        require(normalized != entity.normalizedName) {
            "别名不能与实体名称相同（${entity.name.ifBlank { entity.normalizedName }}）"
        }
        // 别名归一化后也不能等于**其它实体**的 canonical 名称，否则 exact 查询会出现
        // “别名指向 A / 实体名指向 B”的双重绑定；唯一映射优先。
        require(db.entityDao().byNormalizedName(normalized) == null) {
            "该别名与已有实体名称冲突，请换一个别名"
        }
        require(db.entityAliasDao().byNormalizedAlias(normalized) == null) { "该别名已存在" }
        val existingCount = db.entityAliasDao().byEntity(entityId).size
        require(existingCount < MAX_ALIASES_PER_ENTITY) {
            "单个实体最多 $MAX_ALIASES_PER_ENTITY 个别名"
        }

        val row = EntityAliasEntity(
            entityId = entityId,
            alias = trimmed,
            normalizedAlias = normalized,
            createdAt = now(),
        )
        val id = db.entityAliasDao().insertIgnore(row)
        require(id > 0) { "该别名已存在" }
        val inserted = row.copy(id = id)
        refreshEntityFtsLocked(entityId)
        inserted
    }

    /** 按别名 id 删除并刷新 FTS；返回是否删除。 */
    suspend fun removeAlias(aliasId: Long): Boolean = db.withTransaction {
        val existing = db.entityAliasDao().byId(aliasId) ?: return@withTransaction false
        val deleted = db.entityAliasDao().deleteById(aliasId) > 0
        if (deleted) refreshEntityFtsLocked(existing.entityId)
        deleted
    }

    /** 按 entityId + 原始别名删除（内部 normalize 后匹配）；返回是否删除。 */
    suspend fun removeAlias(entityId: Long, alias: String): Boolean = db.withTransaction {
        val normalized = SagSupport.normalizeEntityName(alias)
        if (normalized.isEmpty()) return@withTransaction false
        val deleted = db.entityAliasDao().deleteByEntityAndNormalized(entityId, normalized) > 0
        if (deleted) refreshEntityFtsLocked(entityId)
        deleted
    }

    /** 批量读取别名（按 entityId 分组；entityIds 为空返回空 map；无别名实体不出现在结果中）。 */
    suspend fun listFor(entityIds: List<Long>): Map<Long, List<EntityAliasEntity>> {
        if (entityIds.isEmpty()) return emptyMap()
        return db.entityAliasDao().byEntities(entityIds.distinct()).groupBy { it.entityId }
    }

    /** 显式刷新某实体 FTS（供诊断/迁移后修复；日常增删别名已自动刷新）。 */
    internal suspend fun refreshEntityFts(entityId: Long) = db.withTransaction {
        refreshEntityFtsLocked(entityId)
    }

    /** 事务内：name + description + 全部 aliases → bigram entity_fts（先删后插，保证单实体单行）。 */
    private suspend fun refreshEntityFtsLocked(entityId: Long) {
        val entity = db.entityDao().byId(entityId) ?: return
        val aliases = db.entityAliasDao().byEntity(entityId).map { it.alias }
        db.entityDao().deleteFtsByEntity(entityId)
        db.entityDao().insertFts(
            EntityFtsEntity(
                entityId = entity.id,
                text = SagExtractionSupport.toFtsText(
                    SagExtractionSupport.entityFtsText(
                        name = entity.name.ifBlank { entity.normalizedName },
                        description = entity.description,
                        aliases = aliases,
                    ),
                ),
            ),
        )
    }

    companion object {
        /** 别名长度下限（单字别名噪声大，且与查询侧单字过滤口径一致）。 */
        const val MIN_ALIAS_LENGTH: Int = 2

        /** 别名长度上限（与 entity 名称上限/展示宽度同量级）。 */
        const val MAX_ALIAS_LENGTH: Int = 64

        /** 单实体别名数量上限（防 UI/索引膨胀）。 */
        const val MAX_ALIASES_PER_ENTITY: Int = 20
    }
}
