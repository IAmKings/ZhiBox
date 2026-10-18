package com.zhibox.memory.core.search

import android.content.Context
import com.zhibox.memory.core.embedding.EmbeddingProvider
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.core.text.TextNormalizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.VectorSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 离线检索服务（W2，实施计划 P1-W2-2/3）：
 * 向量精确扫描（G4 选型 PRELOADED：一次载入展平 FloatArray 矩阵）
 * + FTS4 bigram 关键词（G3 选型）
 * + RRF 融合与 chunk 级去重（§9.1-6）
 * + 证据回溯字段（chunk → document → source，charStart/End 原文定位，§9.1-6）
 * + 空间不匹配拒绝检索（§9.1-3/7）：当前模型的空间无向量时拒绝并给出可切换提示。
 *
 * 全程离线：唯一重资源（嵌入引擎）经由 EmbeddingRuntime 串行复用。
 *
 * **线程边界（P0-2）**：公开入口 [search]/[searchChannels] 整体 `withContext(Dispatchers.Default)`，
 * 调用方（Compose 的 Main scope）只挂起等待；矩阵构建/点积/FTS 重排等 CPU 密集工作不在 Main。
 *
 * **一致性（P1-1）**：候选向量与 FTS 查询在 DAO 层 JOIN `document.status='ready'`，
 * 半成品（导入中/失败/取消）文档不会进入结果；矩阵缓存只存已发布向量。
 *
 * **扩展性（P1-5）**：`(embeddingSpaceId, role)` 索引 + [VectorMatrixCache] 进程内矩阵缓存
 * + 证据回填批量查询（消除 N+1）+ 查询/IN 参数上限防护。
 *
 * **B10（SAG 检索）**：[search] 增加 `sourceIds` 可选过滤（默认空=全库，旧调用零改动），
 * [SearchHit] 增加 `sourceId`（document.sourceId），供 `core/sag` 映射 SagHit 与范围过滤。
 *
 * **检索质量/健壮性（B2）**：
 * - B2-1 关键词通道不再按 rowid 截断：取回全部 FTS 命中（安全上限 [KEYWORD_FULL_HIT_LIMIT]），
 *   Kotlin 侧按 bigram 覆盖分排序后再截候选（此前高频 bigram 的长尾 chunk 永远进不了候选）；
 * - B2-2 覆盖分与入库分词口径统一（[BigramTokenizer.bigrams]），跨空格/换行的 bigram 不再漏计；
 * - B2-3 查询入口先做与入库一致的 [TextNormalizer.normalize]（NFC/CRLF），再做 trim/截断；
 * - B2-10 矩阵缓存有 64MB 上限 + LRU，单矩阵超限不常驻（见 [VectorMatrixCache]）。
 */
class SearchService(
    context: Context,
    val db: AppDatabase = AppDatabase.get(context),
    private val contextRef: Context = context.applicationContext,
) {

    data class SearchHit(
        val chunkId: Long,
        val documentId: String,
        val ordinal: Int,
        val content: String,
        val charStart: Int,
        val charEnd: Int,
        val docTitle: String,
        val sourceTitle: String,
        val sourceUri: String,
        val vectorRank: Int?,      // 向量通道名次（1 起；null=未入向量榜）
        val keywordRank: Int?,     // 关键词通道名次
        val fusedScore: Double,    // RRF 融合分
        val channels: String,      // "向量+关键词" / "向量" / "关键词"
        /** B10：来源 id（document.sourceId）；供 SAG 检索按 sourceIds 过滤/回填 SagHit，默认空串兼容旧构造 */
        val sourceId: String = "",
    )

    data class SearchResult(
        val query: String,
        val spaceId: String?,
        val providerId: String?,
        val modelId: String?,
        val vectorCandidates: Int,
        val keywordCandidates: Int,
        val elapsedMs: Long,
        val hits: List<SearchHit>,
        /** 非空 = 拒绝检索（模型缺失/空间无数据），值为用户可读原因 */
        val refused: String?,
    )

    /**
     * 检索（向量 + 关键词 + RRF 融合）。
     *
     * @param sourceIds B10：来源范围过滤（空=全库）。在候选融合后按 document.sourceId 过滤；
     *   默认空列表保持旧行为（B9/UI/benchmark 调用点零改动）。
     */
    suspend fun search(
        query: String,
        topK: Int = 10,
        sourceIds: List<String> = emptyList(),
    ): SearchResult = withContext(Dispatchers.Default) {
        searchInternal(query, topK, sourceIds)
    }

    private suspend fun searchInternal(
        query: String,
        topK: Int,
        sourceIds: List<String>,
    ): SearchResult {
        val startedAt = System.currentTimeMillis()
        // B2-3：与入库一致的 query 规范化（NFC/CRLF→LF）；P2-2 防护：空查询早退 + 超长截断
        //（embedding 与 FTS 共用同一上界）
        val queryBounded = normalizedQuery(query)
        if (queryBounded.isEmpty()) {
            return emptyResult(queryBounded, "请输入检索内容")
        }
        val model = EmbeddingRuntime.findModel(contextRef)
            ?: return emptyResult(queryBounded, "未找到可用模型（filesDir/models）——请先下载或投放模型")

        return EmbeddingRuntime.withProvider(contextRef, model) { provider ->
            val spaceId = EmbeddingRuntime.resolveSpaceId(contextRef, provider)
            val signature = db.embeddingDao().readyVectorSignature(spaceId, DocStatus.READY.value)
            if (signature.rowCount == 0) {
                // 空间不匹配拒绝检索：列出已有空间引导切换（§9.1-3/7）
                val existingBuilder = StringBuilder()
                for (m in db.embeddingDao().allMeta()) {
                    val cnt = db.embeddingDao().countBySpace(m.id)
                    if (cnt > 0) existingBuilder.append("· ${m.id}（${cnt}条向量）\n")
                }
                val existing = existingBuilder.toString().trim()
                return@withProvider emptyResult(
                    queryBounded,
                    "当前模型 ${model.name} 的向量空间无数据（$spaceId）。\n" +
                        if (existing.isBlank()) "知识库为空——请先导入文档"
                        else "已有向量空间：\n$existing\n请切回对应模型（或将新文档导入当前空间）",
                    spaceId = spaceId,
                    providerId = provider.descriptor.providerId,
                )
            }

            // ---- 排序核心（向量扫描 + 关键词召回 + 融合；searchChannels 评测复用）----
            val ranking = rankChannels(provider, spaceId, signature, queryBounded, topK)
            val vectorRanked = ranking.vectorChunks
            val keywordRanked = ranking.keywordChunks

            // ---- 证据回溯（chunk → document → source，批量回填）----
            val allHits = buildHits(ranking.fused)
            // B10：来源范围过滤（buildHits 后过滤，保持 candidate 生成逻辑不变）
            val hits = if (sourceIds.isEmpty()) {
                allHits
            } else {
                val allowed = sourceIds.toHashSet()
                allHits.filter { it.sourceId in allowed }
            }
            SearchResult(
                query = queryBounded,
                spaceId = spaceId,
                providerId = provider.descriptor.providerId,
                modelId = provider.descriptor.modelId,
                vectorCandidates = vectorRanked.size,
                keywordCandidates = keywordRanked.size,
                elapsedMs = System.currentTimeMillis() - startedAt,
                hits = hits,
                refused = null,
            )
        }
    }

    private suspend fun buildHits(fused: List<CandidateFusion.Fused>): List<SearchHit> {
        if (fused.isEmpty()) return emptyList()
        // P1-5：三表全部批量回填（原实现每命中一条就 get 一次 document/source，≤2×topK 次 N+1）
        val chunks: Map<Long, ChunkEntity> =
            loadChunks(fused.map { it.chunkId }).associateBy { it.id }
        val docIds = chunks.values.map { it.documentId }.distinct()
        val docs = loadDocuments(docIds).associateBy { it.id }
        val sourceIds = docs.values.map { it.sourceId }.distinct()
        val sources = loadSources(sourceIds).associateBy { it.id }
        return fused.mapNotNull { f ->
            val c = chunks[f.chunkId] ?: return@mapNotNull null
            val d = docs[c.documentId]
            val s = d?.let { sources[it.sourceId] }
            SearchHit(
                chunkId = c.id,
                documentId = c.documentId,
                ordinal = c.ordinal,
                content = c.content,
                charStart = c.charStart,
                charEnd = c.charEnd,
                docTitle = d?.title ?: c.documentId,
                sourceTitle = s?.title ?: "",
                sourceUri = s?.uri ?: "",
                sourceId = s?.id ?: d?.sourceId ?: "",
                vectorRank = f.vectorRank,
                keywordRank = f.keywordRank,
                fusedScore = f.score,
                channels = when {
                    f.vectorRank != null && f.keywordRank != null -> "向量+关键词"
                    f.keywordRank != null -> "关键词"
                    else -> "向量"
                },
            )
        }
    }

    /** 分通道 chunk 排名（向量/关键词/融合），search 与 searchChannels 共用 */
    private data class ChannelRanking(
        val vectorChunks: List<Long>,
        val keywordChunks: List<Long>,
        val fused: List<CandidateFusion.Fused>,
    )

    private suspend fun rankChannels(
        provider: EmbeddingProvider,
        spaceId: String,
        signature: VectorSignature,
        query: String,
        topK: Int,
    ): ChannelRanking {
        // ---- 向量通道：PRELOADED 精确扫描（进程内矩阵缓存，P1-5）----
        val qvec = provider.embedQuery(query) // 同时回填 descriptor.dimensions
        val dim = provider.descriptor.dimensions
        var qNorm = 0.0
        for (k in 0 until dim) qNorm += qvec[k].toDouble() * qvec[k].toDouble()
        qNorm = sqrt(qNorm)

        // B2-10 安全阀（审查后补强）：矩阵全量构建的内存 = rowCount×dim×4B。
        // 超过缓存上限（默认 64MB ≈ 20k×768）时不再构建整矩阵，改为分页流式扫描 +
        // 有界 top-K，避免 100k 级库一次性分配 ~300MB 导致 OOM（缓存不常驻≠不构建）。
        val candidateCap = topK * CANDIDATE_FACTOR
        val vectorRanked: List<Long> =
            if (shouldStreamMatrix(signature.rowCount, dim)) {
                rankVectorsStreaming(spaceId, dim, qvec, qNorm, candidateCap)
            } else {
                val spaceMatrix = loadOrBuildMatrix(spaceId, signature, dim)
                    ?: return ChannelRanking(emptyList(), emptyList(), emptyList())
                val n = spaceMatrix.chunkIds.size
                val matrix = spaceMatrix.matrix
                val chunkIds = spaceMatrix.chunkIds
                val scores = DoubleArray(n)
                for (i in 0 until n) {
                    var dot = 0.0
                    val base = i * dim
                    for (k in 0 until dim) dot += matrix[base + k].toDouble() * qvec[k].toDouble()
                    scores[i] = if (qNorm == 0.0) 0.0 else dot / qNorm // 向量 L2 归一化，分母只需查询侧
                }
                (0 until n).sortedByDescending { scores[it] }
                    .take(candidateCap)
                    .map(chunkIds::get)
            }

        // ---- 关键词通道：FTS4 bigram OR 召回 + bigram 覆盖数重排（G3 的 AND 语义对自然语言
        //      问句过严——问句 bigram 数多，个别跨词 bigram 缺失即全空，实测 8 问句全 0）----
        val grams = limitedGrams(query)
        val keywordRanked = if (grams.isNotEmpty()) {
            val orQuery = orMatchQuery(grams) ?: ""
            // B2-1：取回**全部命中**（安全上限 5000；SQL 端仍按 rowid 稳定，仅在超限时作为截断顺序）。
            // FTS4 的 matchinfo 是 BLOB 且无自定义 SQL 函数，无法在 SQL 内按覆盖分排序，因此
            // 只能先取回 chunkId 列表（≤5000×8B，内存可忽略），Kotlin 侧算覆盖分后截候选。
            // 旧实现 limit=topK×12 按入库顺序截断，高频 bigram 的长尾 chunk 永远进不了候选
            //（long-tail 评测 keyword_candidates_avg=0.8 的根因之一）。
            val ftsIds = db.chunkDao().searchFtsOrdered(
                orQuery, KEYWORD_FULL_HIT_LIMIT, DocStatus.READY.value,
            )
            if (ftsIds.isEmpty()) {
                emptyList()
            } else {
                // 覆盖分需要 chunk.content：只取这个空间命中的行（批量 400/批），
                // 逐 chunk 构建 bigram 集合后与 query grams 求交（B2-2 口径统一）。
                val contentById = loadChunks(ftsIds).associate { it.id to it.content }
                rankKeywordHits(
                    hitChunkIds = ftsIds,
                    contentById = contentById,
                    queryGrams = grams.toHashSet(),
                    maxCandidates = keywordCandidateLimit(topK),
                )
            }
        } else emptyList()

        val fused = CandidateFusion.fuse(vectorRanked, keywordRanked, topK)
        return ChannelRanking(vectorRanked, keywordRanked, fused)
    }

    /**
     * B2-10 流式向量扫描（大空间安全阀）：分页读取 ready 向量，逐行算分并维护有界 top-K，
     * 内存 O(页大小 + K)，不构建整矩阵。排序语义与矩阵路径一致（分数降序，同分 chunkId 升序）。
     */
    private suspend fun rankVectorsStreaming(
        spaceId: String,
        dim: Int,
        qvec: FloatArray,
        qNorm: Double,
        cap: Int,
    ): List<Long> {
        if (cap <= 0) return emptyList()
        // 有界候选：按页追加后定期裁剪，保持 O(2×cap) 内存；分数相同按 chunkId 升序保证确定性
        val best = ArrayList<Pair<Double, Long>>(cap * 2)
        var offset = 0
        while (true) {
            val page = db.embeddingDao().vectorsForSpacePage(
                spaceId, DocStatus.READY.value, STREAM_PAGE_SIZE, offset,
            )
            if (page.isEmpty()) break
            for (row in page) {
                check(row.vectorBlob.size == dim * 4) {
                    "vector blob 尺寸与空间维度不符（dim=$dim）"
                }
                val buf = ByteBuffer.wrap(row.vectorBlob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var dot = 0.0
                for (k in 0 until dim) dot += buf.get(k).toDouble() * qvec[k].toDouble()
                val score = if (qNorm == 0.0) 0.0 else dot / qNorm
                best.add(score to row.chunkId)
            }
            if (best.size > cap * 2) {
                best.sortWith(compareByDescending<Pair<Double, Long>> { it.first }.thenBy { it.second })
                while (best.size > cap) best.removeAt(best.size - 1)
            }
            offset += page.size
        }
        return best
            .sortedWith(compareByDescending<Pair<Double, Long>> { it.first }.thenBy { it.second })
            .take(cap)
            .map { it.second }
    }

    /**
     * 载入空间矩阵：命中缓存直接复用，否则从 Room 读取 ready 向量并解码为展平 FloatArray。
     * 返回 null = 空间在签名读取后被清空（并发清理窗口，按无候选处理）。
     */
    private suspend fun loadOrBuildMatrix(
        spaceId: String,
        signature: VectorSignature,
        dim: Int,
    ): SpaceMatrix? {
        VectorMatrixCache.get(spaceId, signature.rowCount, signature.maxVectorId)?.let { cached ->
            if (cached.dim == dim) return cached
            VectorMatrixCache.invalidate(spaceId) // 模型维度变化（同名换空间失败兜底）→ 重建
        }
        val rows = db.embeddingDao().vectorsForSpace(spaceId, DocStatus.READY.value)
        if (rows.isEmpty()) {
            VectorMatrixCache.invalidate(spaceId)
            return null
        }
        check(dim > 0 && rows.all { it.vectorBlob.size == dim * 4 }) {
            "vector blob 尺寸与空间维度不符（dim=$dim）"
        }
        val n = rows.size
        val matrix = FloatArray(n * dim)
        val chunkIds = LongArray(n)
        for (i in 0 until n) {
            ByteBuffer.wrap(rows[i].vectorBlob).order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(matrix, i * dim, dim)
            chunkIds[i] = rows[i].chunkId
        }
        val built = SpaceMatrix(
            spaceId = spaceId,
            rowCount = signature.rowCount,
            maxVectorId = signature.maxVectorId,
            dim = dim,
            chunkIds = chunkIds,
            matrix = matrix,
        )
        // B2-10：超上限的单矩阵不会被缓存（put=false），本次仍用 built 完成检索；
        // 下一次查询会重新载入——这是 100k 级库的安全阀（避免常驻 300MB），分片扫描见 B5-3。
        VectorMatrixCache.put(built)
        return built
    }

    /**
     * 评测入口（W3 200 条评测集）：文档级分通道 Top-K（chunk 排名折叠到文档，保序去重）。
     * 指标计算在宿主机（scripts/score_eval.py），App 只输出原始排名。
     */
    data class ChannelDocRanking(
        val vectorDocs: List<String>,
        val keywordDocs: List<String>,
        val fusedDocs: List<String>,
    )

    suspend fun searchChannels(query: String, topK: Int = 10): Pair<ChannelDocRanking, String?> =
        withContext(Dispatchers.Default) {
            val queryBounded = normalizedQuery(query)
            if (queryBounded.isEmpty()) return@withContext ChannelDocRanking(emptyList(), emptyList(), emptyList()) to "空查询"
            val model = EmbeddingRuntime.findModel(contextRef)
                ?: return@withContext ChannelDocRanking(emptyList(), emptyList(), emptyList()) to "未找到可用模型"
            EmbeddingRuntime.withProvider(contextRef, model) { provider ->
                val spaceId = EmbeddingRuntime.resolveSpaceId(contextRef, provider)
                val signature = db.embeddingDao().readyVectorSignature(spaceId, DocStatus.READY.value)
                if (signature.rowCount == 0) {
                    return@withProvider ChannelDocRanking(emptyList(), emptyList(), emptyList()) to
                        "空间无数据：$spaceId"
                }
                val ranking = rankChannels(provider, spaceId, signature, queryBounded, topK)
                val chunks = loadChunks(
                    (ranking.vectorChunks + ranking.keywordChunks + ranking.fused.map { it.chunkId }).distinct()
                ).associateBy { it.id }
                // P1-5：文档标题一次批量取回（原实现每个 chunk 单查 document）
                val titles = loadDocuments(chunks.values.map { it.documentId }.distinct())
                    .associate { it.id to it.title }
                fun fold(chunkIds: List<Long>): List<String> {
                    val seen = LinkedHashSet<String>()
                    for (id in chunkIds) {
                        val documentId = chunks[id]?.documentId ?: continue
                        seen.add(titles[documentId] ?: documentId)
                    }
                    return seen.toList().take(topK)
                }
                ChannelDocRanking(
                    vectorDocs = fold(ranking.vectorChunks),
                    keywordDocs = fold(ranking.keywordChunks),
                    fusedDocs = fold(ranking.fused.map { it.chunkId }),
                ) to null
            }
        }

    // ---- P1-5：批量回填（SQLite 变量上限 999，按 400 分批）----

    private suspend fun loadChunks(ids: List<Long>): List<ChunkEntity> =
        ids.distinct().chunked(SQL_VARIABLE_BATCH).flatMap { db.chunkDao().chunksByIds(it) }

    private suspend fun loadDocuments(ids: List<String>): List<com.zhibox.memory.data.db.DocumentEntity> =
        ids.distinct().chunked(SQL_VARIABLE_BATCH).flatMap { db.documentDao().byIds(it) }

    private suspend fun loadSources(ids: List<String>): List<com.zhibox.memory.data.db.SourceEntity> =
        ids.distinct().chunked(SQL_VARIABLE_BATCH).flatMap { db.sourceDao().byIds(it) }

    private fun emptyResult(
        query: String,
        reason: String,
        spaceId: String? = null,
        providerId: String? = null,
    ) = SearchResult(
        query = query,
        spaceId = spaceId,
        providerId = providerId,
        modelId = null,
        vectorCandidates = 0,
        keywordCandidates = 0,
        elapsedMs = 0,
        hits = emptyList(),
        refused = reason,
    )

    companion object {
        /** 各通道候选放大倍数：保证融合后仍有 topK 条（融合前不丢跨通道命中） */
        private const val CANDIDATE_FACTOR = 3

        /** B2-10 流式扫描页大小（每页 ~1000×768×4B ≈ 3MB，可回收） */
        internal const val STREAM_PAGE_SIZE = 1_000

        /**
         * B2-10 矩阵构建门限：预估字节数超过该值时走流式扫描（默认与缓存上限一致 = 64MB）。
         * 测试可注入更小门限以在中小数据上覆盖流式路径；生产不在外部修改。
         */
        @Volatile
        internal var streamThresholdBytes: Long = VectorMatrixCache.DEFAULT_MAX_BYTES

        /** B2-10 纯函数：整矩阵预估字节数（rowCount×dim×4B）。 */
        internal fun estimatedMatrixBytes(rowCount: Int, dim: Int): Long =
            rowCount.toLong() * dim.toLong() * 4L

        /** B2-10：是否走流式扫描（避免一次性分配超大矩阵）。 */
        internal fun shouldStreamMatrix(rowCount: Int, dim: Int): Boolean =
            estimatedMatrixBytes(rowCount, dim) > streamThresholdBytes

        /** 测试钩子：注入流式门限；传 null 恢复默认。 */
        internal fun setStreamThresholdBytesForTest(bytes: Long?) {
            streamThresholdBytes = bytes ?: VectorMatrixCache.DEFAULT_MAX_BYTES
        }

        /** 超长查询上界（P2-2 防护）：查询 embedding 与 FTS 串共用，防止粘贴长文触发超长 MATCH */
        internal const val MAX_QUERY_CHARS = 512

        /** OR 串 bigram 上限（P2-2）：去重后最多 64 个 term，避免 SQLite 表达式过深/超慢 */
        internal const val MAX_OR_GRAMS = 64

        /** IN (:ids) 分批大小（SQLite 变量上限 999，留余量） */
        internal const val SQL_VARIABLE_BATCH = 400

        /** 查询规范化上界（纯函数，单测覆盖）：trim + 截断 */
        internal fun boundedQuery(query: String): String = query.trim().take(MAX_QUERY_CHARS)

        /**
         * B2-3：检索入口 query 规范化（与入库侧 `TextNormalizer.normalize` 一致：NFC + CRLF→LF），
         * 再 trim + 截断。NFD 粘贴文本与 NFC 文本将得到同一 bigram/embedding 输入。
         */
        internal fun normalizedQuery(query: String): String =
            boundedQuery(TextNormalizer.normalize(query))

        /**
         * B2-1 FTS 全命中安全上限：FTS4 无法在 SQL 内按覆盖分排序，只能全量取回 chunkId 后
         * Kotlin 侧排序。5000×8B ≈ 40KB。
         * **诚实说明（审查）**：超过 5000 命中时按 rowid 截断，可能丢掉覆盖分更高的长尾；
         * 且 rowid 只在未重建 FTS 时与入库顺序一致（`rebuildFtsIndex` 按 documentId,ordinal
         * 重写），因此"保序稳定"仅指无序保证确定候选集，不代表召回完整。生产目标库（≤10k）
         * 会触达该边界，后续 B5-3 分片/下调上限再评估。
         */
        internal const val KEYWORD_FULL_HIT_LIMIT = 5_000

        /** B2-1 关键词候选上限：保持与原实现一致的 topK×12（放大倍数用于融合前不丢召回） */
        internal fun keywordCandidateLimit(topK: Int): Int = topK * CANDIDATE_FACTOR * 4

        /**
         * B2-1/B2-2 纯函数：bigram 覆盖分 = query bigram 集合中被 chunk content **bigram 集合**
         * 覆盖的去重数量（口径与入库 `BigramTokenizer.toFtsText` 完全一致）。
         * 用 BigramTokenizer.bigrams(content) 而不是 String.contains(原始串)：
         * 入库先去空白再 bigram，因此跨空格/换行的 bigram（如 "machine learning" 的 "el"）
         * 在 content 侧也能命中（P2-3 审查发现的原实现丢分点）。
         */
        internal fun bigramCoverageScore(queryGrams: Set<String>, content: String?): Int {
            if (queryGrams.isEmpty() || content.isNullOrEmpty()) return 0
            // FTS4 simple tokenizer 对 ASCII 做大小写折叠（"Hello" 与 "hello" 同 token），
            // 若覆盖口径保留大小写，会出现"FTS 命中但 coverage=0 被过滤"的漏召回（审查 J5）。
            // bigram 数量级很小（≤内容长度），这里统一 lowercase 对齐 FTS 语义。
            val contentGrams = BigramTokenizer.bigrams(content)
                .mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
            if (contentGrams.isEmpty()) return 0
            return queryGrams.count { it.lowercase(Locale.ROOT) in contentGrams }
        }

        /**
         * B2-1 纯函数：关键词候选排序 —— 覆盖分降序，同分按 chunkId 升序（确定性可复现），
         * 取前 [maxCandidates] 个。`hitChunkIds` 先去重（历史库可能有重复 FTS 行）。
         * 注意：输入若已被 5000 上限按 rowid 截断，这里无法恢复被截掉的 chunk（见常量注释）。
         */
        internal fun rankKeywordHits(
            hitChunkIds: List<Long>,
            contentById: Map<Long, String>,
            queryGrams: Set<String>,
            maxCandidates: Int,
        ): List<Long> {
            if (hitChunkIds.isEmpty() || queryGrams.isEmpty() || maxCandidates <= 0) return emptyList()
            return hitChunkIds.asSequence()
                .distinct()
                .map { id -> id to bigramCoverageScore(queryGrams, contentById[id]) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first })
                .take(maxCandidates)
                .map { it.first }
                .toList()
        }

        /** bigram 去重 + 上限截断（纯函数，单测覆盖） */
        internal fun limitedGrams(query: String, maxGrams: Int = MAX_OR_GRAMS): List<String> =
            BigramTokenizer.bigrams(query).distinct().take(maxGrams)

        /** 由 bigram 列表生成 FTS4 OR MATCH 串；空列表返回 null（跳过关键词通道） */
        internal fun orMatchQuery(grams: List<String>): String? =
            if (grams.isEmpty()) null
            else grams.joinToString(" OR ") { gram -> "\"${gram.replace("\"", "")}\"" }
    }
}

/**
 * 候选融合（P1-W2-3）：Reciprocal Rank Fusion。
 * 纯函数（无 Android 依赖），单测覆盖于 CandidateFusionTest。
 */
object CandidateFusion {

    const val RRF_K = 60.0

    data class Fused(
        val chunkId: Long,
        val vectorRank: Int?,   // 1 起
        val keywordRank: Int?,  // 1 起
        val score: Double,
    )

    fun fuse(vectorIds: List<Long>, keywordIds: List<Long>, topK: Int): List<Fused> {
        val score = HashMap<Long, Double>()
        val vRank = HashMap<Long, Int>()
        val kRank = HashMap<Long, Int>()
        vectorIds.forEachIndexed { i, id ->
            vRank[id] = i + 1
            score[id] = (score[id] ?: 0.0) + 1.0 / (RRF_K + i + 1)
        }
        keywordIds.forEachIndexed { i, id ->
            kRank[id] = i + 1
            score[id] = (score[id] ?: 0.0) + 1.0 / (RRF_K + i + 1)
        }
        return score.entries
            .map { (id, s) -> Fused(id, vRank[id], kRank[id], s) }
            .sortedWith(compareByDescending<Fused> { it.score }.thenBy { it.chunkId }) // 分数并列按 chunkId 稳定
            .take(topK)
    }
}
