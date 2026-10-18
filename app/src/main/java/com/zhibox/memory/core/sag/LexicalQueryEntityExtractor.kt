package com.zhibox.memory.core.sag

import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EntityEntity

/**
 * B10：离线词法查询实体抽取（零网络）——SAG 实体种子的默认实现。
 *
 * 两路召回（结果按 id 去重、score 降序、id 升序，确定性）：
 * 1. **精确匹配**：query 归一化后与 `entity.normalizedName` 全等 → score=1.0，source="exact"；
 * 2. **FTS 召回**：query bigram OR MATCH `entity_fts`（与 B9 写入口径一致），按 rowid 命中顺序
 *    名次衰减 score=0.9-rank*0.01（下限 0.5），source="entity_fts"。
 *
 * 去噪：单字名与泛词（疑问/连接/空泛名词，见 [SagSupport]）不返回，避免任意问句触发大规模
 * 实体扩展；数量上限 [MAX_ENTITY_SEEDS]（Retriever 还会再按 budget.kSeed 截断）。
 *
 * 契约：任何 DB/FTS 异常返回已得到的部分结果（不向检索抛业务异常）；CancellationException 向上抛。
 */
class LexicalQueryEntityExtractor(
    private val db: AppDatabase,
    private val maxEntities: Int = MAX_ENTITY_SEEDS,
) : QueryEntityExtractor {

    override suspend fun extract(query: String): List<QueryEntity> {
        val normalizedQuery = SagSupport.normalizeQuery(query)
        if (normalizedQuery.isBlank()) return emptyList()
        val normalizedName = SagSupport.normalizeEntityName(normalizedQuery)
        val out = LinkedHashMap<Long, QueryEntity>()

        // 1) 精确 normalizedName 命中（最强信号）
        runCatching { db.entityDao().byNormalizedName(normalizedName) }.getOrNull()?.let { entity ->
            out[entity.id] = toQueryEntity(entity, score = 1.0, source = "exact")
        }

        // 2) entity_fts bigram OR 召回（名次衰减；与 B9 写入同一 bigram 口径）
        val grams = SagSupport.queryGrams(normalizedQuery)
        val match = SagSupport.ftsOrMatch(grams)
        if (match != null) {
            val hitIds = runCatching {
                db.entityDao().searchFts(match, FTS_HIT_LIMIT)
            }.getOrDefault(emptyList())
            if (hitIds.isNotEmpty()) {
                val rowsById = runCatching {
                    db.entityDao().byIds(hitIds.distinct())
                }.getOrDefault(emptyList()).associateBy { it.id }
                hitIds.distinct().forEachIndexed { rank, id ->
                    val entity = rowsById[id] ?: return@forEachIndexed
                    if (entity.id in out) return@forEachIndexed
                    val score = (FTS_BASE_SCORE - rank * FTS_RANK_DECAY).coerceAtLeast(FTS_MIN_SCORE)
                    out[entity.id] = toQueryEntity(entity, score = score, source = "entity_fts")
                }
            }
        }

        return out.values
            .asSequence()
            .filter { it.normalizedName.length >= MIN_ENTITY_NAME_LENGTH }
            .filter { !SagSupport.isGenericEntityName(it.normalizedName) }
            .sortedWith(compareByDescending<QueryEntity> { it.score }.thenBy { it.id })
            .take(maxEntities)
            .toList()
    }

    private fun toQueryEntity(entity: EntityEntity, score: Double, source: String): QueryEntity =
        QueryEntity(
            id = entity.id,
            type = entity.entityType,
            name = entity.name.ifBlank { entity.normalizedName },
            normalizedName = entity.normalizedName,
            score = SagSupport.clamp01(score),
            source = source,
        )

    companion object {
        /** 词法抽取器单次返回上限（Retriever 侧还会按 budget.kSeed 截断） */
        internal const val MAX_ENTITY_SEEDS = 20

        /** 实体 FTS 命中读取上限（只取 rowid 前 N 名，按名次衰减） */
        private const val FTS_HIT_LIMIT = 100

        private const val FTS_BASE_SCORE = 0.9
        private const val FTS_RANK_DECAY = 0.01
        private const val FTS_MIN_SCORE = 0.5

        /** 单字实体噪声大（"唐"/"数"），不参与扩展 */
        private const val MIN_ENTITY_NAME_LENGTH = 2
    }
}
