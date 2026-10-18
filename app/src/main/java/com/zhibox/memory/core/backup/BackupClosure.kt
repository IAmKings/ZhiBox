package com.zhibox.memory.core.backup

import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.SagOwnerType

/**
 * 备份引用闭包（B1-9）：导出只带"`status='ready'` 文档 + 其 sources / chunks / vectors"，
 * 导入在解析阶段校验闭包完整性。
 *
 * 为什么需要：
 * - v5 起子表带 FK（document→source、chunk→document、chunk_embedding→chunk），包里只要出现
 *   "子行在、父行不在"的孤儿，导入单事务就会 FK 失败并整体回滚——失败原因还是底层 SQL 错误；
 * - `failed/cancelled/pending/processing` 文档本身没有完整派生数据（可能半套 chunk/向量），
 *   导出到新设备只会变成死数据，且可能把"半成品"带进检索库；
 * - 空间注册表（embedding_meta）也按实际引用的空间裁剪，避免把"只有模型需求但没有向量"的
 *   空间写进 `model_requirements.json`，让导入端误报缺模型。
 *
 * 本对象纯 JVM（不碰 Android / Room / org.json）：
 * - 导出端逐页 feed [BackupExportClosure]（流式，内存峰值 = 一页 + 已接受行的 id 集合）；
 * - 导入端解析后统一走 [validateImportClosure]；
 * - 两条路径的判定逻辑由同一组单测覆盖（"非 ready 文档排除、子表无孤儿、引用缺失报错"）。
 */
internal object BackupClosure {

    /** 可进备份的文档状态：直接引用 B1-5 的状态单一事实源（data/db/StatusEnums.kt） */
    val STATUS_READY: String = DocStatus.READY.value

    /** 导出闭包根判定：只有 ready 文档才进入备份 */
    fun isExportableDocument(status: String): Boolean = status == STATUS_READY

    /** 导入端解析结果的最小引用视图（不含业务字段，避免纯校验器依赖 Room 实体） */
    data class DocumentRef(val id: String, val sourceId: String)
    data class ChunkRef(val id: Long, val documentId: String)

    // ---- v3（B8）：SAG 层引用视图 ----

    /** event：chunkId 必在包内；extractionVersion 参与 (chunkId, extractionVersion) 唯一约束校验 */
    data class EventRef(val id: Long, val chunkId: Long, val extractionVersion: String = "")

    /** entity：normalizedName 全局唯一（DB 唯一索引；包内重复会让单事务写库失败，需提前报可读错误） */
    data class EntityRef(val id: Long, val normalizedName: String = "")

    /** event_entity 关联：两端都必须在包内 */
    data class EventEntityRef(val eventId: Long, val entityId: Long)

    /**
     * sag_vector：多态 owner（EVENT→event.id / ENTITY→entity.id）必须被包含。
     * v7 起 `(ownerType, ownerId, role, spaceId)` 唯一：role/spaceId 由生产映射传入参与预检；
     * 默认空值仅为兼容既有调用/测试（空值时不参与重复判定）。
     */
    data class SagVectorRef(
        val ownerType: String,
        val ownerId: Long,
        val role: String = "",
        val spaceId: String = "",
    )

    /** extraction_job：document/chunk 都必须在包内（v6 FK） */
    data class ExtractionJobRef(val id: String, val documentId: String, val chunkId: Long)

    /**
     * 导入解析阶段的引用闭包校验（B1-9 + B8/v3）。
     *
     * 基础闭包：`document.sourceId ∈ sources`、`chunk.documentId ∈ documents`；
     * v3 闭包（参数非空时校验，v1/v2 包传空即跳过）：
     * - `event.chunkId ∈ chunks`，且 `(chunkId, extractionVersion)` 不重复（v6 唯一索引）；
     * - `event_entity.eventId ∈ events`、`event_entity.entityId ∈ entities`，且
     *   `(eventId, entityId)` 不重复（v7 唯一索引）；
     * - `sag_vector.ownerType ∈ {EVENT, ENTITY}` 且 owner 在对应集合内，且
     *   `(ownerType, ownerId, role, spaceId)` 不重复（v7 唯一索引；role/spaceId 已知时预检）；
     * - `extraction_job.chunkId ∈ chunks`、`extraction_job.documentId ∈ documents`，且每个
     *   chunk 至多一条 job（v7 唯一索引 chunkId）；
     * - entity 的 id / normalizedName 包内唯一（v6 唯一索引）。
     *
     * 以上唯一约束若不在写库前预检，会以 SQLite UNIQUE 约束错误形式让整个导入事务回滚，
     * 错误不可读；因此这里统一提前给出可读错误（B9/v7）。
     *
     * 向量段的两条引用（`vector.chunkId ∈ chunks`、空间索引合法）由
     * [BackupValidator.decodeVectors] 在解码时同步校验（同为纯 JVM）。
     *
     * 违反时抛出带字段定位的可读错误——必须先于写库（`parsePackage` 内）调用，
     * 保证坏包不会进入单事务、原库保持不变。
     */
    fun validateImportClosure(
        sourceIds: Collection<String>,
        documents: List<DocumentRef>,
        chunks: List<ChunkRef>,
        events: List<EventRef> = emptyList(),
        entities: List<EntityRef> = emptyList(),
        eventEntities: List<EventEntityRef> = emptyList(),
        sagVectors: List<SagVectorRef> = emptyList(),
        extractionJobs: List<ExtractionJobRef> = emptyList(),
    ) {
        val sources = sourceIds.toHashSet()
        documents.forEach { d ->
            check(d.sourceId in sources) {
                "备份包引用闭包不完整：documents.jsonl 的文档 ${d.id} 引用了不存在的 source：${d.sourceId}——" +
                    "包已损坏或由不一致的版本导出"
            }
        }
        val docs = documents.mapTo(HashSet()) { it.id }
        chunks.forEach { c ->
            check(c.documentId in docs) {
                "备份包引用闭包不完整：chunks.jsonl 的 chunk ${c.id} 引用了不存在的 document：${c.documentId}——" +
                    "包已损坏或由不一致的版本导出"
            }
        }
        if (events.isEmpty() && entities.isEmpty() && eventEntities.isEmpty() &&
            sagVectors.isEmpty() && extractionJobs.isEmpty()
        ) {
            return
        }
        validateSagClosure(chunks, documents, events, entities, eventEntities, sagVectors, extractionJobs)
    }

    /** v3 SAG 层闭包校验（拆出来保持主函数可读；v1/v2 包不会进入） */
    private fun validateSagClosure(
        chunks: List<ChunkRef>,
        documents: List<DocumentRef>,
        events: List<EventRef>,
        entities: List<EntityRef>,
        eventEntities: List<EventEntityRef>,
        sagVectors: List<SagVectorRef>,
        extractionJobs: List<ExtractionJobRef>,
    ) {
        val chunkIds = chunks.mapTo(HashSet()) { it.id }
        val eventIds = HashSet<Long>(events.size)
        val seenExtraction = HashSet<String>(events.size)
        events.forEach { e ->
            check(eventIds.add(e.id)) { "备份包引用闭包不完整：events.jsonl event id 重复：${e.id}" }
            check(e.chunkId in chunkIds) {
                "备份包引用闭包不完整：events.jsonl 的 event ${e.id} 引用了不存在的 chunk：${e.chunkId}"
            }
            // 无条件查重：空 extractionVersion 也受 DB 唯一索引约束；若此处放行，
            // 导入端的 upsertAll(REPLACE) 会静默覆盖第二条 event（审查 S1）。
            check(seenExtraction.add("${e.chunkId}|${e.extractionVersion}")) {
                "备份包引用闭包不合法：events.jsonl 重复 (chunkId, extractionVersion)：" +
                    "chunk=${e.chunkId} / extractionVersion=${e.extractionVersion}"
            }
        }
        val entityIds = HashSet<Long>(entities.size)
        val normalizedNames = HashSet<String>(entities.size)
        entities.forEach { e ->
            check(entityIds.add(e.id)) { "备份包引用闭包不完整：entities.jsonl entity id 重复：${e.id}" }
            if (e.normalizedName.isNotEmpty()) {
                check(normalizedNames.add(e.normalizedName)) {
                    "备份包引用闭包不合法：entities.jsonl normalizedName 重复：${e.normalizedName}"
                }
            }
        }
        val seenLinks = HashSet<String>(eventEntities.size)
        eventEntities.forEach { link ->
            check(link.eventId in eventIds) {
                "备份包引用闭包不完整：event_entities.jsonl 引用了不存在的 event：${link.eventId}"
            }
            check(link.entityId in entityIds) {
                "备份包引用闭包不完整：event_entities.jsonl 引用了不存在的 entity：${link.entityId}"
            }
            // v7：event_entity (eventId, entityId) 唯一；包内重复会让导入事务撞唯一约束 → 提前可读拒绝
            check(seenLinks.add("${link.eventId}|${link.entityId}")) {
                "备份包引用闭包不合法：event_entities.jsonl 重复 (eventId, entityId)：" +
                    "event=${link.eventId} / entity=${link.entityId}"
            }
        }
        val seenSagKeys = HashSet<String>(sagVectors.size)
        sagVectors.forEach { v ->
            // v7：同一 owner 的同一语义角色在同一空间只允许一条向量；role/spaceId 由生产映射提供
            // （空值来自旧调用/测试，不参与预检）。重复会在导入事务撞唯一约束 → 提前可读拒绝。
            if (v.role.isNotEmpty() && v.spaceId.isNotEmpty()) {
                check(seenSagKeys.add("${v.ownerType}|${v.ownerId}|${v.role}|${v.spaceId}")) {
                    "备份包引用闭包不合法：sag_vectors.jsonl 重复 (ownerType, ownerId, role, spaceId)：" +
                        "${v.ownerType}:${v.ownerId} / role=${v.role} / space=${v.spaceId}"
                }
            }
            when (SagOwnerType.fromValue(v.ownerType)) {
                SagOwnerType.EVENT -> check(v.ownerId in eventIds) {
                    "备份包引用闭包不完整：sag_vectors.jsonl 引用了不存在的 event owner：${v.ownerId}"
                }
                SagOwnerType.ENTITY -> check(v.ownerId in entityIds) {
                    "备份包引用闭包不完整：sag_vectors.jsonl 引用了不存在的 entity owner：${v.ownerId}"
                }
                null -> error(
                    "备份包引用闭包不合法：sag_vectors.jsonl ownerType 未知：${v.ownerType}" +
                        "（合法值：${SagOwnerType.VALUES.joinToString()}）"
                )
            }
        }
        val jobIds = HashSet<String>(extractionJobs.size)
        val jobChunkIds = HashSet<Long>(extractionJobs.size)
        val docs = documents.mapTo(HashSet()) { it.id }
        extractionJobs.forEach { job ->
            check(job.id.isNotEmpty() && jobIds.add(job.id)) {
                "备份包引用闭包不合法：extraction_jobs.jsonl id 非法或重复：${job.id}"
            }
            check(job.chunkId in chunkIds) {
                "备份包引用闭包不完整：extraction_jobs.jsonl 的 job ${job.id} 引用了不存在的 chunk：${job.chunkId}"
            }
            // v7：extraction_job.chunkId 唯一（每 chunk 一条，重抽取复用同一行）→ 重复可读拒绝
            check(jobChunkIds.add(job.chunkId)) {
                "备份包引用闭包不合法：extraction_jobs.jsonl 同一 chunk 存在多条 job：chunk=${job.chunkId}"
            }
            check(job.documentId in docs) {
                "备份包引用闭包不完整：extraction_jobs.jsonl 的 job ${job.id} 引用了不存在的 document：${job.documentId}"
            }
        }
    }
}

/**
 * 导出引用闭包构建器（流式）：导出端按页喂数据，父行先被接受、子行才可能被接受，
 * 因此产出的包天然"无子表孤儿"，与 [BackupClosure.validateImportClosure] 的校验口径一致。
 *
 * 内存：只保留已接受行的 id（documentId / sourceId / chunkId）；大库下 10 万 chunk 的 id 集合
 * 约数 MB，BLOB/文本内容仍逐页流式写出（P1-7 约束不回退）。
 */
internal class BackupExportClosure(
    /**
     * true = 不按状态过滤文档（仅"导入前快照"使用：回滚安全网要尽量完整保留现有库，
     * 包括非 ready 文档；父子行仍成对，闭包依然一致）。
     * 用户可见的导出（SAF/Uri、File、benchmark）一律 false —— 只带 ready 文档（B1-9）。
     */
    private val includeNonReadyDocuments: Boolean = false,
) {

    private val documentIds = LinkedHashSet<String>()
    private val sourceIds = LinkedHashSet<String>()
    private val chunkIds = LinkedHashSet<Long>()
    // v3（B8）：SAG 层闭包（event → 关联 entity → 多态 sag 向量 owner）
    private val eventIds = LinkedHashSet<Long>()
    private val entityIds = LinkedHashSet<Long>()

    /** 文档页：返回该文档是否进入备份；非 ready 文档（failed/cancelled/pending/processing）排除 */
    fun acceptDocument(id: String, sourceId: String, status: String): Boolean {
        if (!includeNonReadyDocuments && !BackupClosure.isExportableDocument(status)) return false
        documentIds += id
        if (sourceId.isNotEmpty()) sourceIds += sourceId
        return true
    }

    /** chunk 页：仅当父文档已进备份才接受（否则就是子表孤儿） */
    fun acceptChunk(id: Long, documentId: String): Boolean {
        if (documentId !in documentIds) return false
        chunkIds += id
        return true
    }

    fun includesDocument(documentId: String): Boolean = documentId in documentIds

    fun includesChunk(chunkId: Long): Boolean = chunkId in chunkIds

    fun documentIds(): Set<String> = documentIds

    /** 引用到的来源（排序保证包内容确定性；导出端据此批量查 source 行） */
    fun sourceIds(): List<String> = sourceIds.sorted()

    fun chunkIds(): Set<Long> = chunkIds

    fun documentCount(): Int = documentIds.size

    fun chunkCount(): Int = chunkIds.size

    // ---- v3（B8）：SAG 层闭包 ----

    /** event 页：仅当父 chunk 已进备份才接受（v6 FK chunkId；否则子表孤儿） */
    fun acceptEvent(id: Long, chunkId: Long): Boolean {
        if (chunkId !in chunkIds) return false
        eventIds += id
        return true
    }

    fun includesEvent(eventId: Long): Boolean = eventId in eventIds

    fun eventIds(): Set<Long> = eventIds

    /**
     * event_entity 关联：仅当 event 已进备份时接受，并记录被引用的 entity
     * （实体闭包 = 被导出 event 引用到的实体，不做全库导出）。
     */
    fun acceptEventEntity(eventId: Long, entityId: Long): Boolean {
        if (eventId !in eventIds) return false
        entityIds += entityId
        return true
    }

    fun includesEntity(entityId: Long): Boolean = entityId in entityIds

    fun entityIds(): Set<Long> = entityIds

    /** sag_vector：多态 owner 必须已在闭包内（EVENT→eventIds / ENTITY→entityIds） */
    fun includesSagOwner(ownerType: String, ownerId: Long): Boolean = when (ownerType) {
        SagOwnerType.EVENT.value -> ownerId in eventIds
        SagOwnerType.ENTITY.value -> ownerId in entityIds
        else -> false
    }
}
