package com.zhibox.memory.core.backup

import com.zhibox.memory.data.db.DocStatus

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

    /**
     * 导入解析阶段的引用闭包校验（B1-9）。
     *
     * 校验 `document.sourceId ∈ sources`、`chunk.documentId ∈ documents`；
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
}
