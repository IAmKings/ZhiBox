package com.zhibox.memory.core.sag

import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EntityEntity

/**
 * B10：离线词法查询实体抽取（零网络）——SAG 实体种子的默认实现。
 *
 * B15.1 起三路召回（结果按 id 去重、score 降序、id 升序，确定性）：
 * 1. **精确匹配**：query 归一化后与 `entity.normalizedName` 全等 → score=1.0，source="exact"；
 * 1b. **别名精确/子串**：query 与其子串候选同时解析 `entity_alias.normalizedAlias` →
 *    canonical entity，source="entity_alias"（与 canonical 命中去重，canonical exact 优先）；
 * 2. **FTS 召回**：query bigram OR MATCH `entity_fts`（entity FTS 已包含 aliases，B15.1），
 *    按 rowid 命中顺序名次衰减 score=0.9-rank*0.01（下限 0.5），source="entity_fts"。
 *
 * 无别名时行为/排序与 v8 完全一致（多查空表，不改变结果）。
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

        // 1a) 别名精确命中：alias 全局唯一 → 直接解析 canonical entity（source=entity_alias；
        //     若 canonical 名称已命中（更高优先级 exact）则不重复覆盖）
        runCatching { db.entityAliasDao().byNormalizedAlias(normalizedName) }.getOrNull()?.let { aliasRow ->
            runCatching { db.entityDao().byId(aliasRow.entityId) }.getOrNull()?.let { entity ->
                out.putIfAbsent(entity.id, toQueryEntity(entity, score = 1.0, source = "entity_alias"))
            }
        }

        // 1b) **查询子串精确命中**：自然语言问句不是实体名本身（"张三所在组织..."），
        //     必须从 query 的 n-gram 候选中回查 entity.normalizedName，再确认是该子串。
        //     仅做整句全等会让 query entity 退化为 FTS 泛召回，桥接扩展锚点跑偏。
        val substringCandidates = substringCandidates(normalizedName)
        if (substringCandidates.isNotEmpty()) {
            runCatching { db.entityDao().byNormalizedNames(substringCandidates) }
                .getOrNull()
                .orEmpty()
                .filter { it.normalizedName.isNotBlank() && normalizedQuery.contains(it.normalizedName) }
                .forEach { entity ->
                    out.putIfAbsent(entity.id, toQueryEntity(entity, score = 1.0, source = "exact"))
                }
        }

        // 1c) 别名子串命中：query 含某 alias（如"张三的公司" 含别名"张三"）→ canonical entity；
        //     canonical/alias 精确命中已占用的实体不重复（putIfAbsent 保留更强来源）。
        if (substringCandidates.isNotEmpty()) {
            runCatching {
                db.entityAliasDao().byNormalizedAliases(substringCandidates)
                    .filter { alias ->
                        alias.normalizedAlias.length >= MIN_ENTITY_NAME_LENGTH &&
                            normalizedQuery.contains(alias.normalizedAlias)
                    }
            }.getOrDefault(emptyList()).let { matchedAliases ->
                if (matchedAliases.isNotEmpty()) {
                    val entityIds = matchedAliases.map { it.entityId }.distinct()
                    runCatching { db.entityDao().byIds(entityIds) }
                        .getOrDefault(emptyList())
                        .forEach { entity ->
                            out.putIfAbsent(
                                entity.id,
                                toQueryEntity(entity, score = 1.0, source = "entity_alias"),
                            )
                        }
                }
            }
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

    /** 查询中所有 2..12 字候选（有界），用于 entity.normalizedName 的精确子串回查。 */
    private fun substringCandidates(normalizedQuery: String): List<String> {
        if (normalizedQuery.length < MIN_ENTITY_NAME_LENGTH) return emptyList()
        val out = LinkedHashSet<String>(MAX_SUBSTRING_CANDIDATES)
        val maxLen = minOf(12, normalizedQuery.length)
        for (len in MIN_ENTITY_NAME_LENGTH..maxLen) {
            for (start in 0..normalizedQuery.length - len) {
                out += normalizedQuery.substring(start, start + len)
                if (out.size >= MAX_SUBSTRING_CANDIDATES) return out.toList()
            }
        }
        return out.toList()
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

        /** 子串候选上限（避免长 query 的 O(n²) 候选撑爆 SQL IN） */
        private const val MAX_SUBSTRING_CANDIDATES = 128
    }
}
