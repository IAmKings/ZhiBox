package com.zhibox.memory.core.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.withTransaction
import com.zhibox.memory.core.extraction.EntityAliasRepository
import com.zhibox.memory.core.extraction.SagFtsRebuilder
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.model.ModelIntegrity
import com.zhibox.memory.core.model.PathPreferences
import com.zhibox.memory.core.search.VectorMatrixCache
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.AppKvEntity
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.JobStage
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SagVectorEntity
import com.zhibox.memory.data.db.SourceEntity
import com.zhibox.memory.data.db.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * W4+-2 知识库导出/导入（跨设备迁移）：单 zip 包。
 *
 * 包结构（FORMAT_VERSION=3；v1/v2 保持可读）：
 * - meta.json                — format_version / 导出时间 / 空间注册表（embedding_meta 全字段）/ counts
 * - model_requirements.json  — 每空间所需模型（fileName/sha256/size/accelerator）——导入端据此校验
 * - sources/documents/chunks.jsonl — 行级 JSON（FTS 可从 chunk 重生成，不随包）
 * - app_kv.jsonl             — 通用键值状态（v2 新增；v1 包缺失时按空处理）
 * - vectors.bin              — chunk 向量二进制（LE；头 count:i32；v2 起行：
 *   spaceIdx:i16 / chunkId:i64 / len:i32 / roleLen:i16 / role / norm:f64 / createdAt:i64 / blob）
 * - v3（B8）SAG 层新增（v1/v2 包没有这些 entry → 导入按空处理）：
 *   events.jsonl / entities.jsonl / event_entities.jsonl / entity_aliases.jsonl（可选，v9 别名） /
 *   sag_vectors.jsonl（多态 owner 的向量，blob 用 base64 承载） / extraction_jobs.jsonl
 *   （event_fts/entity_fts 是派生数据不导出；chat/query_trace 属本地隐私数据，P0 不导出）
 *
 * 与 v1 的差异（P0-1b / P1-7 / data-layer P1-7）：
 * - 空间表补全 accelerator/quantization/modelRevision/normalization/active/createdAt；
 * - sources 补 uri、documents 补 sizeBytes、向量补 role/vectorNorm/createdAt（恢复后证据页可区分
 *   来源 URL provenance（B14.2 可选 originUrl，旧包缺字段=null）；
 *   "原文未随备份迁移"与"文件损坏"；Phase 2 多角色向量不再被降级成 content）；
 * - 导入同时兼容 v1 包（缺失字段用默认值）。
 *
 * 数据安全（P0-1a）：
 * - **先全量读取 + 完整语义校验，全部通过后才进入写库**（格式版本、entry/总尺寸上限、vectors 头
 *   count 与字节数、blob 长度 == dim×4、chunkId 引用存在、空间索引/重复行校验）；
 * - 写库 = 单个 Room 事务内显式 DELETE + 批量插入（不再用 clearAllTables()）——任何一步失败整体
 *   回滚，原库保持不变；
 * - 导入前自动导出一份 `filesDir/backup_snapshots/auto-backup-<ts>.zip` 快照
 *   （校验通过后才拍，最多保留 2 份，可从设置页恢复）；
 * - 导出流式分页（每页 ~200 行），SAF 目标先写 cacheDir 临时文件再 copy（避免用户可见的半包）。
 *
 * 模型供应链（P1-7）：导入写库前用 [ModelIntegrity] 对在位模型做 SHA-256 + 精确尺寸校验，
 * 并把包内 `catalog_sha256` 与本机 catalog 版本比对（版本不一致时拒绝导入——向量空间语义可能错位）。
 *
 * 引用闭包（B1-9）：
 * - 导出只带 `document.status='ready'` 的文档及其 sources/chunks/vectors（[BackupExportClosure]），
 *   FTS 不进包（导入由 chunk.content 重建）、embedding_meta 按实际引用的空间裁剪；
 * - 导入解析阶段用 [BackupClosure.validateImportClosure] 校验闭包，缺父行即报可读错误（写库前失败）；
 * - 用户可见导出 = ready-only；导入前自动快照 = 全文档（回滚安全网要完整，父子仍成对）。
 *
 * 流入口参数化（OutputStream/InputStream）：SAF Uri 版（UI）与 File 版（自动化回环/自动备份）
 * 共用同一 zip 读写实现。6 个公开入口签名保持不变（UI / benchmark 在用）。
 */
object KnowledgeBackup {

    /**
     * v3（B8）：在 v2 基础上新增 SAG 层 entry（events/entities/event_entities/sag_vectors/
     * extraction_jobs）+ meta.counts；`vectors.bin` 行布局不变。读取端兼容 v1/v2（缺失字段/entry
     * 用默认值/空处理）。
     */
    const val FORMAT_VERSION = BackupValidator.FORMAT_VERSION

    private const val PAGE_SIZE = 200
    /** SQLite 绑定参数批量上限的安全值（老版本 SQLite 上限 999，ID 批量查父行时分批） */
    private const val SQLITE_BATCH = 500
    private const val SNAPSHOT_PREFIX = "auto-backup-"
    private const val SNAPSHOT_KEEP = 2
    private const val SNAPSHOT_DIR = "backup_snapshots"

    /** Recoverable work that failed only after the restored database was committed. */
    enum class ImportWarning { SAG_FTS_REBUILD_FAILED }

    /** Successful import payload; [warning] never means the restored rows were rolled back. */
    data class ImportOutcome(
        val restoredCount: Int,
        val warning: ImportWarning? = null,
        /** Sanitized diagnostic detail for the warning; never contains document text or credentials. */
        val warningDetail: String? = null,
    )

    // entry 名单单一事实源：定义在 BackupValidator（标准审查修复：此前两文件各自维护
    // 7 个 entry 名，新增 entry 需同改两处、易漂移）。
    private const val ENTRY_META = BackupValidator.ENTRY_META
    private const val ENTRY_REQUIREMENTS = BackupValidator.ENTRY_REQUIREMENTS
    private const val ENTRY_SOURCES = BackupValidator.ENTRY_SOURCES
    private const val ENTRY_DOCUMENTS = BackupValidator.ENTRY_DOCUMENTS
    private const val ENTRY_CHUNKS = BackupValidator.ENTRY_CHUNKS
    private const val ENTRY_APP_KV = BackupValidator.ENTRY_APP_KV
    private const val ENTRY_VECTORS = BackupValidator.ENTRY_VECTORS
    // B8/v3：SAG 层 entry（v1/v2 包无 → 导入端视为空）
    private const val ENTRY_EVENTS = BackupValidator.ENTRY_EVENTS
    private const val ENTRY_ENTITIES = BackupValidator.ENTRY_ENTITIES
    private const val ENTRY_EVENT_ENTITIES = BackupValidator.ENTRY_EVENT_ENTITIES
    private const val ENTRY_SAG_VECTORS = BackupValidator.ENTRY_SAG_VECTORS
    private const val ENTRY_EXTRACTION_JOBS = BackupValidator.ENTRY_EXTRACTION_JOBS
    // B15.1（v9）：可选实体别名 entry（旧包缺省 = 空；不提升 FORMAT_VERSION）
    private const val ENTRY_ENTITY_ALIASES = BackupValidator.ENTRY_ENTITY_ALIASES

    /**
     * runCatching 的取消安全版本（审查修复 P1-2）：CancellationException 必须向上传播，
     * 不能被 Result.failure 静默吞掉——否则"取消导入/导出"会被当成业务失败返回给 UI。
     */
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) throw it }

    // ---- 公开入口（签名保持：UI 与 benchmark 在用，只允许加带默认值的参数） ----

    suspend fun export(context: Context, destUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            // SAF 目标不是可随机写的 File：先写 cacheDir 临时文件，成功后再 copy 到目标。
            // 直接写目标的话，中途异常/取消会在用户可见位置留下截断 zip——用户会把它当有效备份，
            // 恢复时正好触发 P0-1 的"坏包清库"链路。
            val staging = File(context.cacheDir, "backup-${System.currentTimeMillis()}.zip.tmp")
            try {
                val count = exportZipFile(context, staging)
                context.contentResolver.openOutputStream(destUri)?.buffered()?.use { out ->
                    staging.inputStream().buffered().use { it.copyTo(out) }
                } ?: error("无法写入导出目标")
                count
            } finally {
                staging.delete()
            }
        }
    }

    suspend fun exportToFile(context: Context, dest: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val target = dest.absoluteFile
            val parent = target.parentFile ?: error("导出目标路径非法：${dest.path}")
            // 同目录临时文件 + 原子改名：失败/取消时旧文件保持完整
            val tmp = File(parent, target.name + ".tmp")
            try {
                val count = exportZipFile(context, tmp)
                if (target.isFile && !target.delete()) error("无法覆盖导出目标：${target.name}")
                check(tmp.renameTo(target)) { "导出文件重命名失败：${target.name}" }
                count
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun preview(context: Context, srcUri: Uri): Result<ImportPreview> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            context.contentResolver.openInputStream(srcUri)?.buffered()?.use { ins ->
                ZipInputStream(ins).use { zip -> readPreview(context, zip) }
            } ?: error("无法读取导入源")
        }
    }

    suspend fun previewFromFile(context: Context, src: File): Result<ImportPreview> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                src.inputStream().buffered().use { ins ->
                    ZipInputStream(ins).use { zip -> readPreview(context, zip) }
                }
            }
        }

    suspend fun import(context: Context, srcUri: Uri, dropIncompatibleGpuVectors: Boolean): Result<ImportOutcome> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                context.contentResolver.openInputStream(srcUri)?.buffered()?.use { ins ->
                    ZipInputStream(ins).use { zip -> importZip(context, zip, dropIncompatibleGpuVectors) }
                } ?: error("无法读取导入源")
            }
        }

    suspend fun importFromFile(
        context: Context, src: File, dropIncompatibleGpuVectors: Boolean,
    ): Result<ImportOutcome> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            src.inputStream().buffered().use { ins ->
                ZipInputStream(ins).use { zip -> importZip(context, zip, dropIncompatibleGpuVectors) }
            }
        }
    }

    /**
     * 仅 instrumentation 回环测试使用（[VisibleForTesting]）：跳过"本机模型在位/完整性"校验，
     * 让无 arm64 模型资产的测试设备也能验证"导出→清库→导入→再导出"的向量/空间保持。
     * 生产入口（[import]/[importFromFile]）始终执行完整供应链校验，本方法不对外暴露。
     */
    @VisibleForTesting
    internal suspend fun importFromFileForTest(
        context: Context, src: File, dropIncompatibleGpuVectors: Boolean = false,
        rebuildSagFts: suspend (AppDatabase) -> Unit = SagFtsRebuilder::rebuildAll,
    ): Result<ImportOutcome> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            src.inputStream().buffered().use { ins ->
                ZipInputStream(ins).use { zip ->
                    importZip(
                        context,
                        zip,
                        dropIncompatibleGpuVectors,
                        verifyModelIntegrity = false,
                        rebuildSagFts = rebuildSagFts,
                    )
                }
            }
        }
    }

    // ---- 导出（流式分页） ----

    /**
     * @param includeNonReadyDocuments true = 导入前快照模式（保留非 ready 文档行，回滚安全网要尽量完整；
     *   父子行仍成对，闭包一致）。用户导出恒为 false（B1-9：只带 ready 文档）。
     */
    private suspend fun exportZipFile(
        context: Context,
        dest: File,
        includeNonReadyDocuments: Boolean = false,
    ): Int {
        dest.parentFile?.mkdirs()
        return ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            exportZip(context, zip, includeNonReadyDocuments)
        }
    }

    private suspend fun exportZip(
        context: Context,
        zip: ZipOutputStream,
        includeNonReadyDocuments: Boolean,
    ): Int {
        val db = AppDatabase.get(context)
        requireNoActiveJobs(context)
        // 导出只读：reader connection + DEFERRED 事务提供一致快照，避免导出期间写事务阻塞导入/抽取。
        return db.useReaderConnection { transactor ->
            transactor.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {

        // B7：只导出受支持 EGemma2 空间（旧路线 Path C/granite/LFM 的 meta/向量跳过）；
        // 仅旧模型向量的 ready 文档在包内降级为 failed（documents/chunks 保留，见第 3 步），
        // 保证恢复后不会出现静默零向量的 ready 文档。
        val metas = db.embeddingDao().allMeta()
            .filter { IngestRepository.isSupportedSpace(it.providerId, it.modelId) }
        val registered = metas.mapTo(HashSet()) { it.id }
        val closure = BackupExportClosure(includeNonReadyDocuments)

        fun writeEntry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }

        /** 分页写 JSONL：每页 ~[PAGE_SIZE] 行，父闭包判定与计数阶段共用同一谓词 */
        suspend fun <T> writePagedJsonl(
            name: String,
            page: suspend (offset: Int) -> List<T>,
            include: (T) -> Boolean,
            toJson: (T) -> JSONObject,
        ): Int {
            zip.putNextEntry(ZipEntry(name))
            var pageOffset = 0
            var writtenLocal = 0
            while (true) {
                val rows = page(pageOffset)
                if (rows.isEmpty()) break
                val sb = StringBuilder()
                for (row in rows) {
                    if (!include(row)) continue
                    sb.append(toJson(row).toString()).append('\n')
                    writtenLocal++
                }
                if (sb.isNotEmpty()) zip.write(sb.toString().toByteArray())
                pageOffset += rows.size
            }
            zip.closeEntry()
            return writtenLocal
        }

        // 1) 文档闭包：先只喂闭包（内容稍后写——需要先知道哪些文档有受支持向量，才能
        //    把"仅旧模型向量"的 ready 文档降级为 failed，避免恢复后静默零向量）。
        var offset = 0
        while (true) {
            val page = db.documentDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (d in page) closure.acceptDocument(d.id, d.sourceId, d.status)
            offset += page.size
        }

        // 2) sources.jsonl：只导出被闭包文档引用的来源，并验证其真实存在（父行缺失=孤儿，禁止入包）
        val referencedSourceIds = closure.sourceIds()
        val sourceRows = referencedSourceIds.chunked(SQLITE_BATCH).flatMap { db.sourceDao().byIds(it) }
        val foundSourceIds = sourceRows.mapTo(HashSet()) { it.id }
        val missingSourceIds = referencedSourceIds.filter { it !in foundSourceIds }
        check(missingSourceIds.isEmpty()) {
            "备份数据不一致：文档引用了不存在的来源（${missingSourceIds.take(5).joinToString()}）——" +
                "请先修复数据/重建索引后再备份"
        }
        zip.putNextEntry(ZipEntry(ENTRY_SOURCES))
        if (sourceRows.isNotEmpty()) {
            zip.write(
                sourceRows.sortedBy { it.id }
                    .joinToString("") { sourceJson(it).toString() + "\n" }
                    .toByteArray()
            )
        }
        zip.closeEntry()

        // 2) chunks.jsonl：只导出闭包内文档的 chunk（父文档不在包内的 chunk 一律丢弃，防子表孤儿）。
        //    同时记录"拥有受支持向量"的文档集合，供下一步文档状态降级判断。
        val supportedChunkIds = HashSet<Long>()
        offset = 0
        while (true) {
            val page = db.embeddingDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (v in page) {
                if (v.embeddingSpaceId in registered) supportedChunkIds += v.chunkId
            }
            offset += page.size
        }
        val documentsWithSupportedVectors = HashSet<String>()
        zip.putNextEntry(ZipEntry(ENTRY_CHUNKS))
        offset = 0
        while (true) {
            val page = db.chunkDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            val sb = StringBuilder()
            for (c in page) {
                if (!closure.acceptChunk(c.id, c.documentId)) continue
                if (c.id in supportedChunkIds) documentsWithSupportedVectors += c.documentId
                sb.append(chunkJson(c).toString()).append('\n')
            }
            if (sb.isNotEmpty()) zip.write(sb.toString().toByteArray())
            offset += page.size
        }
        zip.closeEntry()

        // 3) documents.jsonl：闭包内的文档。B7 迁移：ready 但**没有任何受支持向量**的文档
        //    （旧路线只有 granite/Path C/LFM 向量）降级为 failed —— 旧向量已被过滤，若仍写
        //    ready，恢复后会是"ready 但零向量"的静默不可检索数据；failed 可见、可删除，
        //    且同 checksum 重新导入不会被去重拦截（importFile 只对 pending/processing/ready 判重）。
        zip.putNextEntry(ZipEntry(ENTRY_DOCUMENTS))
        offset = 0
        var documentCount = 0
        var downgradedDocuments = 0
        while (true) {
            val page = db.documentDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            val sb = StringBuilder()
            for (d in page) {
                if (!closure.includesDocument(d.id)) continue
                val readyWithoutVectors = d.status == DocStatus.READY.value &&
                    d.id !in documentsWithSupportedVectors
                if (readyWithoutVectors) downgradedDocuments++
                val out = if (readyWithoutVectors) d.copy(status = DocStatus.FAILED.value) else d
                sb.append(documentJson(out).toString()).append('\n')
                documentCount++
            }
            if (sb.isNotEmpty()) zip.write(sb.toString().toByteArray())
            offset += page.size
        }
        zip.closeEntry()

        // app_kv 体积小（几十行），一次性读写即可（DAO 无分页接口）
        val appKvRows = db.appKvDao().all()
        zip.putNextEntry(ZipEntry(ENTRY_APP_KV))
        zip.write(appKvRows.joinToString("") { appKvJson(it).toString() + "\n" }.toByteArray())
        zip.closeEntry()

        // 4) 向量段头要写"过滤后的精确 count"，所以先扫一遍：只统计 chunk 在包内的向量，
        //    同时收集实际用到的空间（meta/requirements 按引用裁剪——避免"只有模型需求没有向量"
        //    的空间让导入端误报缺模型）。两遍都不缓存 BLOB，内存峰值仍是一页。
        val usedSpaceIds = LinkedHashSet<String>()
        var vectorCount = 0
        offset = 0
        while (true) {
            val page = db.embeddingDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (v in page) {
                if (!closure.includesChunk(v.chunkId)) continue
                // B7：只统计受支持空间（meta 已过滤）的向量；旧路线/未注册孤儿空间直接跳过，
                // documents/chunks 仍然导出（旧向量不可被新模型复用，迁移方式是重新导入原文）。
                if (v.embeddingSpaceId !in registered) continue
                usedSpaceIds += v.embeddingSpaceId
                vectorCount++
            }
            offset += page.size
        }

        // ---- B8/v3：SAG 层闭包（分页计数 + 收集闭包 id，BLOB 不常驻内存） ----
        // event：仅闭包 chunk 的 event（含 superseded 历史行，保留重抽取审计链）；
        // event_entity：event 在包内才导出，同时得到 entity 闭包（只带被引用实体，不做全库）；
        // extraction_job：仅导出 chunk 在包内的任务（v6 FK document/chunk）。
        var eventCount = 0
        offset = 0
        while (true) {
            val page = db.eventDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (e in page) if (closure.acceptEvent(e.id, e.chunkId)) eventCount++
            offset += page.size
        }
        var eventEntityCount = 0
        offset = 0
        while (true) {
            val page = db.eventEntityDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (link in page) if (closure.acceptEventEntity(link.eventId, link.entityId)) eventEntityCount++
            offset += page.size
        }
        var entityCount = 0
        offset = 0
        while (true) {
            val page = db.entityDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (e in page) if (closure.includesEntity(e.id)) entityCount++
            offset += page.size
        }
        // B15.1：只统计包内 entity 的别名（entity 闭包之外不导出）。
        var entityAliasCount = 0
        offset = 0
        while (true) {
            val page = db.entityAliasDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (alias in page) if (closure.includesEntity(alias.entityId)) entityAliasCount++
            offset += page.size
        }
        val chunkIds = closure.chunkIds()
        var extractionJobCount = 0
        offset = 0
        while (true) {
            val page = db.extractionJobDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (job in page) if (job.chunkId in chunkIds) extractionJobCount++
            offset += page.size
        }
        // sag_vector：多态 owner 在包内才导出；空间必须"受支持 + 已注册 embedding_meta"，
        // 且向量维度与空间维度一致（SAG 向量与 chunk 向量共用空间注册表，导入端据此校验模型/维度）。
        // 不受支持/未注册的空间按 B7 规则跳过（与 chunk 向量一致）。
        val metasById = metas.associateBy { it.id }
        val sagUsedSpaceIds = LinkedHashSet<String>()
        var sagVectorCount = 0
        offset = 0
        while (true) {
            val page = db.sagVectorDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (v in page) {
                if (!closure.includesSagOwner(v.ownerType, v.ownerId)) continue
                // B7 规则沿用：只导出受支持空间（meta 已过滤）的向量；旧路线/未注册孤儿空间跳过
                // ——与 chunk 向量一致（旧向量不可被新模型复用，迁移方式是重新导入原文/重抽取）。
                val meta = metasById[v.spaceId] ?: continue
                check(v.dimensions == meta.dimensions) {
                    "SAG 向量维度与空间不一致：${v.spaceId}（向量 ${v.dimensions} / 空间 ${meta.dimensions}）"
                }
                sagUsedSpaceIds += v.spaceId
                sagVectorCount++
            }
            offset += page.size
        }

        // 5) meta/requirements：chunk 向量空间 ∪ SAG 向量空间，按引用裁剪（保持 allMeta 顺序）
        val allUsedSpaces = LinkedHashSet<String>().apply {
            addAll(usedSpaceIds)
            addAll(sagUsedSpaceIds)
        }
        val exportedMetas = metas.filter { it.id in allUsedSpaces }
        val counts = BackupCounts(
            sources = sourceRows.size,
            documents = documentCount,
            downgradedDocuments = downgradedDocuments,
            chunks = closure.chunkCount(),
            chunkVectors = vectorCount,
            events = eventCount,
            entities = entityCount,
            eventEntities = eventEntityCount,
            sagVectors = sagVectorCount,
            extractionJobs = extractionJobCount,
            appKv = appKvRows.size,
        )
        writeEntry(ENTRY_META, buildMetaJson(exportedMetas, counts))
        writeEntry(ENTRY_REQUIREMENTS, buildRequirementsJson(exportedMetas))
        val spaceIdx = exportedMetas.withIndex().associate { (i, m) -> m.id to i }

        // 6) vectors.bin：按闭包写行（与第 4 步同一谓词，保证头 count == 实际行数）
        zip.putNextEntry(ZipEntry(ENTRY_VECTORS))
        zip.write(BackupValidator.encodeVectorHeader(vectorCount))
        var written = 0
        offset = 0
        while (true) {
            val page = db.embeddingDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (v in page) {
                if (!closure.includesChunk(v.chunkId)) continue
                // B7：与第 4 步同谓词——受支持空间之外的行跳过（保证头 count == 实际行数）
                val idx = spaceIdx[v.embeddingSpaceId] ?: continue
                zip.write(
                    BackupValidator.encodeVectorRow(
                        spaceIdx = idx, chunkId = v.chunkId, blob = v.vectorBlob,
                        formatVersion = FORMAT_VERSION, role = v.role,
                        vectorNorm = v.vectorNorm, createdAt = v.createdAt,
                    )
                )
                written++
            }
            offset += page.size
        }
        check(written == vectorCount) {
            "导出期间向量数量发生变化（$written/$vectorCount）——请稍后重试"
        }
        zip.closeEntry()

        // 7) v3 新 entry：分页 JSONL（与计数阶段同一谓词，保证 meta counts 与实际一致）。
        //    FTS 是派生数据不导出（导入端清空后由 B9 重建）；chat/query_trace 属本地隐私数据，P0 不导出。
        val eventsWritten = writePagedJsonl(
            ENTRY_EVENTS, { db.eventDao().page(PAGE_SIZE, it) },
            { closure.includesEvent(it.id) }, ::eventJson,
        )
        check(eventsWritten == eventCount) { "导出期间 event 数量发生变化（$eventsWritten/$eventCount）——请稍后重试" }
        val entitiesWritten = writePagedJsonl(
            ENTRY_ENTITIES, { db.entityDao().page(PAGE_SIZE, it) },
            { closure.includesEntity(it.id) }, ::entityJson,
        )
        check(entitiesWritten == entityCount) { "导出期间 entity 数量发生变化（$entitiesWritten/$entityCount）——请稍后重试" }
        // B15.1：别名行只带包内 entity（写入顺序在 entities 之后；导入端同样先 entity 后 alias）。
        val aliasesWritten = writePagedJsonl(
            ENTRY_ENTITY_ALIASES, { db.entityAliasDao().page(PAGE_SIZE, it) },
            { closure.includesEntity(it.entityId) }, ::entityAliasJson,
        )
        check(aliasesWritten == entityAliasCount) {
            "导出期间 entity_alias 数量发生变化（$aliasesWritten/$entityAliasCount）——请稍后重试"
        }
        val linksWritten = writePagedJsonl(
            ENTRY_EVENT_ENTITIES, { db.eventEntityDao().page(PAGE_SIZE, it) },
            { closure.includesEvent(it.eventId) }, ::eventEntityJson,
        )
        check(linksWritten == eventEntityCount) {
            "导出期间 event_entity 数量发生变化（$linksWritten/$eventEntityCount）——请稍后重试"
        }
        val jobsWritten = writePagedJsonl(
            ENTRY_EXTRACTION_JOBS, { db.extractionJobDao().page(PAGE_SIZE, it) },
            { it.chunkId in chunkIds }, ::extractionJobJson,
        )
        check(jobsWritten == extractionJobCount) {
            "导出期间 extraction_job 数量发生变化（$jobsWritten/$extractionJobCount）——请稍后重试"
        }
        // sag_vectors 单独写：BLOB→base64 膨胀 4/3，避免整表行常驻内存
        zip.putNextEntry(ZipEntry(ENTRY_SAG_VECTORS))
        offset = 0
        var sagWritten = 0
        while (true) {
            val page = db.sagVectorDao().page(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (v in page) {
                if (!closure.includesSagOwner(v.ownerType, v.ownerId)) continue
                if (v.spaceId !in sagUsedSpaceIds) continue // 与计数阶段同谓词
                zip.write(sagVectorJson(v).toString().toByteArray())
                zip.write('\n'.code)
                sagWritten++
            }
            offset += page.size
        }
        zip.closeEntry()
        check(sagWritten == sagVectorCount) {
            "导出期间 SAG 向量数量发生变化（$sagWritten/$sagVectorCount）——请稍后重试"
        }
            vectorCount
            }
        }
    }

    /**
     * meta.counts（v3）：包内实际条目数。
     * 用途：诊断/回环测试断言（"恢复后再导出计数不降"），也便于排查"过滤闭包后少了什么"；
     * 导入端**不强制**校验 counts（否则历史包/手工包会被误拒），只作为参考信息。
     */
    private data class BackupCounts(
        val sources: Int,
        val documents: Int,
        val downgradedDocuments: Int,
        val chunks: Int,
        val chunkVectors: Int,
        val events: Int,
        val entities: Int,
        val eventEntities: Int,
        val sagVectors: Int,
        val extractionJobs: Int,
        val appKv: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("sources", sources)
            .put("documents", documents)
            .put("downgradedDocuments", downgradedDocuments)
            .put("chunks", chunks)
            .put("chunkVectors", chunkVectors)
            .put("events", events)
            .put("entities", entities)
            .put("eventEntities", eventEntities)
            .put("sagVectors", sagVectors)
            .put("extractionJobs", extractionJobs)
            .put("appKv", appKv)
    }

    private fun buildMetaJson(metas: List<EmbeddingMetaEntity>, counts: BackupCounts): String {
        val spaces = JSONArray()
        for (m in metas) {
            spaces.put(
                JSONObject()
                    .put("id", m.id).put("providerId", m.providerId).put("modelId", m.modelId)
                    .put("modelRevision", m.modelRevision ?: JSONObject.NULL)
                    .put("dimensions", m.dimensions).put("normalization", m.normalization)
                    .put("taskTemplate", m.taskTemplate).put("createdAt", m.createdAt)
                    .put("active", m.active).put("accelerator", m.accelerator)
                    .put("quantization", m.quantization)
            )
        }
        return JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put("exported_at", System.currentTimeMillis())
            .put("app", "zhibox-memory")
            .put("spaces", spaces)
            .put("counts", counts.toJson())
            .toString(1)
    }

    private fun buildRequirementsJson(metas: List<EmbeddingMetaEntity>): String {
        val reqs = JSONArray()
        for (m in metas) {
            val entry = catalogEntryForModelId(m.modelId)
            reqs.put(
                JSONObject()
                    .put("spaceId", m.id).put("modelId", m.modelId)
                    .put("accelerator", m.accelerator)
                    .put("catalog_file", entry?.fileName ?: JSONObject.NULL)
                    .put("catalog_sha256", entry?.sha256 ?: JSONObject.NULL)
                    .put("size_bytes", entry?.sizeBytes ?: 0L)
            )
        }
        return JSONObject().put("requirements", reqs).toString(1)
    }

    private fun sourceJson(s: SourceEntity): JSONObject = JSONObject()
        .put("id", s.id).put("title", s.title).put("uri", s.uri)
        .put("mimeType", s.mimeType ?: JSONObject.NULL)
        .put("createdAt", s.createdAt).put("status", s.status)
        // B14.2/v3 兼容：可选字段；null 用 JSON null（旧读取端/旧包缺字段均视为 null）
        .put("originUrl", s.originUrl ?: JSONObject.NULL)

    private fun documentJson(d: DocumentEntity): JSONObject = JSONObject()
        .put("id", d.id).put("sourceId", d.sourceId).put("title", d.title)
        .put("checksum", d.checksum).put("parserId", d.parserId)
        .put("createdAt", d.createdAt).put("status", d.status)
        .put("sizeBytes", d.sizeBytes)

    private fun chunkJson(c: ChunkEntity): JSONObject = JSONObject()
        .put("id", c.id).put("documentId", c.documentId)
        .put("ordinal", c.ordinal).put("content", c.content)
        .put("charStart", c.charStart).put("charEnd", c.charEnd)
        .put("tokenEstimate", c.tokenEstimate)

    private fun appKvJson(kv: AppKvEntity): JSONObject = JSONObject()
        .put("key", kv.key).put("value", kv.value).put("updatedAt", kv.updatedAt)

    // ---- v3（B8）：SAG 层 JSON 映射（导出端；导入端在 parse* 函数） ----

    private fun eventJson(e: EventEntity): JSONObject = JSONObject()
        .put("id", e.id).put("chunkId", e.chunkId)
        .put("canonicalText", e.canonicalText).put("confidence", e.confidence)
        .put("title", e.title).put("summary", e.summary).put("content", e.content)
        .put("language", e.language).put("status", e.status)
        .put("extractorVersion", e.extractorVersion)
        .put("extractionVersion", e.extractionVersion)
        .put("modelId", e.modelId).put("promptVersion", e.promptVersion)
        .put("active", e.active)
        .put("supersededBy", e.supersededBy ?: JSONObject.NULL)
        .put("createdAt", e.createdAt)

    private fun entityJson(e: EntityEntity): JSONObject = JSONObject()
        .put("id", e.id).put("normalizedName", e.normalizedName)
        .put("entityType", e.entityType ?: JSONObject.NULL)
        .put("name", e.name).put("description", e.description)
        .put("createdAt", e.createdAt)

    /** B15.1：实体别名行（只导出包内 entity 的别名；id/entityId/alias/normalizedAlias/createdAt）。 */
    private fun entityAliasJson(a: EntityAliasEntity): JSONObject = JSONObject()
        .put("id", a.id).put("entityId", a.entityId)
        .put("alias", a.alias).put("normalizedAlias", a.normalizedAlias)
        .put("createdAt", a.createdAt)

    private fun eventEntityJson(rel: EventEntityRel): JSONObject = JSONObject()
        .put("id", rel.id).put("eventId", rel.eventId).put("entityId", rel.entityId)
        .put("role", rel.role ?: JSONObject.NULL)
        .put("weight", rel.weight)
        .put("description", rel.description ?: JSONObject.NULL)

    private fun sagVectorJson(v: SagVectorEntity): JSONObject = JSONObject()
        .put("ownerType", v.ownerType).put("ownerId", v.ownerId)
        .put("role", v.role).put("spaceId", v.spaceId)
        .put("dimensions", v.dimensions)
        // base64（kotlin.io.encoding.Base64，API24 可用）；解码/长度校验在 BackupValidator
        .put("blobBase64", BackupValidator.encodeBlobBase64(v.vectorBlob))
        .put("vectorNorm", v.vectorNorm).put("createdAt", v.createdAt)

    /** B9：extraction_job 全字段导出（status/attempt/errorCode/errorDetail/createdAt/updatedAt），
     *  与抽取管线写入语义一致（失败状态/错误码在恢复后仍可诊断/重试）。 */
    private fun extractionJobJson(job: ExtractionJobEntity): JSONObject = JSONObject()
        .put("id", job.id).put("documentId", job.documentId).put("chunkId", job.chunkId)
        .put("status", job.status).put("attempt", job.attempt)
        .put("errorCode", job.errorCode ?: JSONObject.NULL)
        .put("errorDetail", job.errorDetail ?: JSONObject.NULL)
        .put("createdAt", job.createdAt).put("updatedAt", job.updatedAt)

    // ---- 包解析与校验（写库之前完成，失败不触碰数据库） ----

    /** model_requirements.json 的一条需求 */
    private data class Requirement(
        val spaceId: String,
        val modelId: String,
        val accelerator: String,
        val catalogFile: String?,
        val catalogSha256: String?,
        val sizeBytes: Long,
    )

    /** 已通过格式/引用/尺寸校验的包内容（此时尚未写库） */
    private class ParsedBackup(
        val formatVersion: Int,
        val spaces: List<EmbeddingMetaEntity>,
        val requirements: List<Requirement>,
        val sources: List<SourceEntity>,
        val documents: List<DocumentEntity>,
        val chunks: List<ChunkEntity>,
        val appKv: List<AppKvEntity>,
        val vectorPayload: BackupValidator.VectorPayload,
        // v3（B8）：SAG 层（v1/v2 包为空列表）
        val events: List<EventEntity> = emptyList(),
        val entities: List<EntityEntity> = emptyList(),
        val eventEntities: List<EventEntityRel> = emptyList(),
        val sagVectors: List<SagVectorEntity> = emptyList(),
        val extractionJobs: List<ExtractionJobEntity> = emptyList(),
        val entityAliases: List<EntityAliasEntity> = emptyList(),
    )

    private fun readPreview(context: Context, zip: ZipInputStream): ImportPreview {
        // 预览也走完整结构校验（preview 不校验模型 SHA，那是 import 的职责——
        // 大模型全量哈希不该在一次"点开看看"里发生）。
        val entries = BackupValidator.readArchive(zip)
        val parsed = parsePackage(entries, dropIncompatibleGpuVectors = false, collectRows = false)
        return buildPreview(context, parsed)
    }

    private fun parsePackage(
        entries: Map<String, ByteArray>,
        dropIncompatibleGpuVectors: Boolean,
        collectRows: Boolean,
    ): ParsedBackup {
        val metaBytes = entries[ENTRY_META]
            ?: error("不是有效的知识库导出包（缺 meta.json）")
        val meta = try {
            JSONObject(metaBytes.toString(Charsets.UTF_8))
        } catch (e: JSONException) {
            error("meta.json 不是合法 JSON：${e.message}")
        }
        // P1-7：format_version 真正参与门禁（此前只展示不校验）
        val formatVersion = meta.optInt("format_version", 0)
        BackupValidator.checkFormatVersion(formatVersion)

        val spaces = parseSpaces(meta)
        val spaceIds = spaces.mapTo(HashSet()) { it.id }
        val requirements = parseRequirements(entries[ENTRY_REQUIREMENTS], spaceIds)
        val sources = parseSources(entries[ENTRY_SOURCES])
        val documents = parseDocuments(entries[ENTRY_DOCUMENTS])
        val chunks = parseChunks(entries[ENTRY_CHUNKS])
        val appKv = parseAppKv(entries[ENTRY_APP_KV])
        val chunkIds = chunks.mapTo(HashSet()) { it.id }
        val dropped = if (dropIncompatibleGpuVectors) {
            requirements.filter { it.accelerator != "cpu" }.mapTo(HashSet()) { it.spaceId }
        } else {
            emptySet()
        }

        // v3（B8）：SAG 层 entry 仅在 format_version>=3 时解析；v1/v2 包缺这些 entry → 空列表
        // （旧备份导入后 SAG 结构为空、chunk 检索照常，符合 PRD"旧备份不崩、可在线补抽取"）。
        val v3 = formatVersion >= 3
        val events = if (v3) parseEvents(entries[ENTRY_EVENTS]) else emptyList()
        val entities = if (v3) parseEntities(entries[ENTRY_ENTITIES]) else emptyList()
        // B15.1：别名按包内 entity 闭包校验（entityId 必须存在；normalizedAlias 包内唯一）。
        val entityAliases = if (v3) {
            parseEntityAliases(entries[ENTRY_ENTITY_ALIASES], entities.mapTo(HashSet()) { it.id })
        } else {
            emptyList()
        }
        val eventEntities = if (v3) parseEventEntities(entries[ENTRY_EVENT_ENTITIES]) else emptyList()
        val extractionJobs = if (v3) {
            parseExtractionJobs(entries[ENTRY_EXTRACTION_JOBS], chunkIds, documents.mapTo(HashSet()) { it.id })
        } else {
            emptyList()
        }
        val sagVectors = if (v3) {
            // 与 chunk 向量一致：GPU/NPU 空间跨设备不逐位一致，用户勾选"丢弃不兼容 GPU 向量"时
            // 同步丢弃这些空间的 SAG 向量（空间 meta 仍保留，导入后由重抽取/重嵌入重建）。
            parseSagVectors(entries[ENTRY_SAG_VECTORS], spaces.associateBy { it.id })
                .filterNot { it.spaceId in dropped }
        } else {
            emptyList()
        }

        // B1-9：引用闭包校验（纯 JVM 实现在 BackupClosure，可单测）——父行缺失即报可读错误，
        // 不进入单事务（否则 v5/v6 FK 会让导入在事务内失败、错误信息变成底层 SQL 报错）。
        // v3：event/entity/event_entity/sag 向量 owner/extraction_job 的 FK 闭包一并校验（B8）。
        // 向量段的两条引用（chunkId ∈ chunks、空间索引合法）由 decodeVectors 同步校验。
        BackupClosure.validateImportClosure(
            sourceIds = sources.map { it.id },
            documents = documents.map { BackupClosure.DocumentRef(it.id, it.sourceId) },
            chunks = chunks.map { BackupClosure.ChunkRef(it.id, it.documentId) },
            events = events.map { BackupClosure.EventRef(it.id, it.chunkId, it.extractionVersion) },
            entities = entities.map { BackupClosure.EntityRef(it.id, it.normalizedName) },
            eventEntities = eventEntities.map { BackupClosure.EventEntityRef(it.eventId, it.entityId) },
            sagVectors = sagVectors.map {
                BackupClosure.SagVectorRef(it.ownerType, it.ownerId, it.role, it.spaceId)
            },
            extractionJobs = extractionJobs.map {
                BackupClosure.ExtractionJobRef(it.id, it.documentId, it.chunkId)
            },
        )

        val vectorPayload = BackupValidator.decodeVectors(
            payload = entries[ENTRY_VECTORS] ?: error("备份包缺少 vectors.bin"),
            formatVersion = formatVersion,
            spaces = spaces.map { BackupValidator.SpaceSpec(it.id, it.dimensions) },
            chunkIds = chunkIds,
            droppedSpaceIds = dropped,
            collectRows = collectRows,
        )
        return ParsedBackup(
            formatVersion = formatVersion,
            spaces = spaces,
            requirements = requirements,
            sources = sources,
            documents = documents,
            chunks = chunks,
            appKv = appKv,
            vectorPayload = vectorPayload,
            events = events,
            entities = entities,
            eventEntities = eventEntities,
            sagVectors = sagVectors,
            extractionJobs = extractionJobs,
            entityAliases = entityAliases,
        )
    }

    private fun parseSpaces(meta: JSONObject): List<EmbeddingMetaEntity> {
        val arr = meta.optJSONArray("spaces") ?: JSONArray()
        check(arr.length() <= BackupValidator.MAX_SPACES) {
            "meta.json 空间数超过上限（${BackupValidator.MAX_SPACES}）"
        }
        val seen = HashSet<String>()
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: error("meta.json spaces[$i] 不是对象")
            val id = o.optString("id", "")
            check(id.isNotEmpty()) { "meta.json spaces[$i] 缺少 id" }
            check(seen.add(id)) { "meta.json 空间 id 重复：$id" }
            val dimensions = o.optInt("dimensions", 0)
            check(dimensions in 1..BackupValidator.MAX_DIMENSIONS) {
                "meta.json 空间 $id 维度非法：$dimensions（合法 1..${BackupValidator.MAX_DIMENSIONS}）"
            }
            EmbeddingMetaEntity(
                id = id,
                providerId = o.optString("providerId", ""),
                modelId = o.optString("modelId", ""),
                modelRevision = o.optStringOrNull("modelRevision"),
                dimensions = dimensions,
                normalization = o.optString("normalization", "l2"),
                taskTemplate = o.optString("taskTemplate", ""),
                createdAt = o.optLong("createdAt", 0L),
                active = o.optBoolean("active", true),
                // v1 包没有这些字段：默认值保持旧行为（accelerator 旧实现按 spaceId 后缀推断，
                // 这里用默认 cpu —— v1 记录本就没有 GPU 隔离信息，交由导入后重嵌入重建）
                accelerator = o.optString("accelerator", "cpu"),
                quantization = o.optString("quantization", "f32"),
            )
        }.also { spaces ->
            // B7：旧版本备份可能包含已下线模型路线（Path C embedding_gemma.task / granite / LFM）
            // 的空间。这类向量无法被新模型复用，若照常导入会成为不可检索的死数据且混淆空间语义，
            // 因此在 **parse 阶段（任何 DB 写入之前）** 直接拒绝，并列出 modelId 供用户识别。
            val unsupported = spaces.filterNot {
                IngestRepository.isSupportedSpace(it.providerId, it.modelId)
            }
            if (unsupported.isNotEmpty()) {
                val listed = unsupported.take(8).joinToString("、") { meta ->
                    val model = meta.modelId.ifBlank { "(缺失 modelId)" }
                    val provider = meta.providerId.ifBlank { "(缺失 providerId)" }
                    "$model($provider)"
                }
                val suffix = if (unsupported.size > 8) " 等 ${unsupported.size} 个空间" else ""
                error(
                    "备份包含已下线的模型/向量空间，无法导入（B7 起仅支持 EGemma2）：" +
                        "$listed$suffix。请使用新版 App 重新导入原始文档" +
                        "（旧路线 Path C/granite/LFM 的向量无法复用）"
                )
            }
        }
    }

    private fun parseRequirements(bytes: ByteArray?, spaceIds: Set<String>): List<Requirement> {
        bytes ?: error("备份包缺少 model_requirements.json")
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (e: JSONException) {
            error("model_requirements.json 不是合法 JSON：${e.message}")
        }
        val arr = root.optJSONArray("requirements") ?: error("model_requirements.json 缺少 requirements")
        val seen = HashSet<String>()
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: error("model_requirements.json requirements[$i] 不是对象")
            val spaceId = o.optString("spaceId", "")
            check(spaceId.isNotEmpty()) { "model_requirements.json requirements[$i] 缺少 spaceId" }
            check(seen.add(spaceId)) { "model_requirements.json 空间重复：$spaceId" }
            check(spaceId in spaceIds) {
                "model_requirements.json 引用了 meta.json 中不存在的空间：$spaceId"
            }
            Requirement(
                spaceId = spaceId,
                modelId = o.optString("modelId", ""),
                accelerator = o.optString("accelerator", "cpu"),
                catalogFile = o.optStringOrNull("catalog_file"),
                catalogSha256 = o.optStringOrNull("catalog_sha256"),
                sizeBytes = o.optLong("size_bytes", 0L),
            )
        }
    }

    /** 恢复路径状态校验（B1-5 审查修复）：未知状态直接拒绝，避免绕过枚举单源。 */
    private fun requireSourceStatus(raw: String, id: String): SourceStatus =
        SourceStatus.fromValue(raw) ?: error("sources.jsonl 存在未知状态：$raw（id=$id）")

    /** 恢复路径状态校验（B1-5 审查修复）。 */
    private fun requireDocStatus(raw: String, id: String): DocStatus =
        DocStatus.fromValue(raw) ?: error("documents.jsonl 存在未知状态：$raw（id=$id）")

    /**
     * 恢复快照里的非 ready 文档没有 ingest_job：pending/processing 原样恢复会成为"永远处理中"
     * 的僵尸行（B1-9 审查修复）→ 归一为 failed（终态，可删除/重导）；failed/cancelled 保持。
     */
    private fun normalizeRestoredDocStatus(status: DocStatus): DocStatus = when (status) {
        DocStatus.PENDING, DocStatus.PROCESSING -> DocStatus.FAILED
        else -> status
    }

    private fun parseSources(bytes: ByteArray?): List<SourceEntity> {
        val seen = HashSet<String>()
        return parseJsonLines(ENTRY_SOURCES, bytes) { o ->
            val id = o.optString("id", "")
            check(id.isNotEmpty()) { "sources.jsonl 记录缺少 id" }
            check(seen.add(id)) { "sources.jsonl id 重复：$id" }
            SourceEntity(
                id = id,
                title = o.optString("title", ""),
                // v1 包没有 uri：原文文件本来就不随包迁移，空值让 UI 显示"原始文件未随备份迁移"
                uri = o.optString("uri", ""),
                mimeType = o.optStringOrNull("mimeType"),
                createdAt = o.optLong("createdAt", 0L),
                // B1-5 审查修复：恢复路径也只接受枚举；未知/损坏状态在写库前报错，避免绕过状态单源。
                status = requireSourceStatus(o.optString("status", SourceStatus.ACTIVE.value), id).value,
                // B14.2：v1/v2/v3 旧包可能没有 originUrl 字段 → optStringOrNull 返回 null（合法）。
                originUrl = o.optStringOrNull("originUrl"),
            )
        }
    }

    private fun parseDocuments(bytes: ByteArray?): List<DocumentEntity> {
        val seen = HashSet<String>()
        return parseJsonLines(ENTRY_DOCUMENTS, bytes) { o ->
            val id = o.optString("id", "")
            check(id.isNotEmpty()) { "documents.jsonl 记录缺少 id" }
            check(seen.add(id)) { "documents.jsonl id 重复：$id" }
            // sourceId 的引用闭包（sourceId ∈ sources）由 parsePackage 里的 BackupClosure 统一校验（B1-9）
            DocumentEntity(
                id = id,
                sourceId = o.optString("sourceId", ""),
                title = o.optString("title", ""),
                checksum = o.optString("checksum", ""),
                parserId = o.optString("parserId", ""),
                createdAt = o.optLong("createdAt", 0L),
                // B1-5 审查修复：只接受枚举状态；未知状态写库前报错。
                // B1-9 审查修复：恢复的 pending/processing 文档没有对应 ingest_job，原样恢复会变成
                // "永远处理中"的僵尸行 → 归一为 failed（终态，可删可重导）；failed/cancelled 保持原状。
                status = normalizeRestoredDocStatus(requireDocStatus(o.optString("status", DocStatus.READY.value), id)).value,
                sizeBytes = o.optLong("sizeBytes", 0L), // v1 包缺失 → 0
            )
        }
    }

    private fun parseChunks(bytes: ByteArray?): List<ChunkEntity> {
        val seenIds = HashSet<Long>()
        val seenOrdinals = HashSet<String>()
        return parseJsonLines(ENTRY_CHUNKS, bytes) { o ->
            val id = o.optLong("id", 0L)
            check(id > 0) { "chunks.jsonl 记录 id 非法：$id" }
            check(seenIds.add(id)) { "chunks.jsonl id 重复：$id" }
            // documentId 的引用闭包（documentId ∈ documents）由 parsePackage 里的 BackupClosure 统一校验
            val documentId = o.optString("documentId", "")
            val ordinal = o.optInt("ordinal", -1)
            check(ordinal >= 0) { "chunks.jsonl chunk $id ordinal 非法：$ordinal" }
            check(seenOrdinals.add("$documentId|$ordinal")) {
                "chunks.jsonl 重复 (documentId, ordinal)：$documentId / $ordinal"
            }
            ChunkEntity(
                id = id,
                documentId = documentId,
                ordinal = ordinal,
                content = o.optString("content", ""),
                charStart = o.optInt("charStart", 0),
                charEnd = o.optInt("charEnd", 0),
                tokenEstimate = o.optInt("tokenEstimate", 0),
            )
        }
    }

    private fun parseAppKv(bytes: ByteArray?): List<AppKvEntity> {
        val seen = HashSet<String>()
        return parseJsonLines(ENTRY_APP_KV, bytes) { o ->
            val key = o.optString("key", "")
            check(key.isNotEmpty()) { "app_kv.jsonl 记录缺少 key" }
            check(seen.add(key)) { "app_kv.jsonl key 重复：$key" }
            AppKvEntity(
                key = key,
                value = o.optString("value", ""),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }

    // ---- v3（B8）：SAG 层解析（写库前完成，坏包直接抛可读错误） ----

    /** 恢复路径状态校验（B8）：未知 event.status 直接拒绝，避免绕过枚举单源。 */
    private fun requireEventStatus(raw: String, id: Long): EventStatus =
        EventStatus.fromValue(raw) ?: error("events.jsonl 存在未知 status：$raw（event=$id）")

    /** 恢复路径状态校验（B8）：未知 extraction_job.status 直接拒绝。 */
    private fun requireExtractionJobStatus(raw: String, id: String): ExtractionJobStatus =
        ExtractionJobStatus.fromValue(raw) ?: error("extraction_jobs.jsonl 存在未知 status：$raw（job=$id）")

    /** 恢复路径 ownerType 校验（B8）：多态 owner 只允许 EVENT/ENTITY。 */
    private fun requireSagOwnerType(raw: String): SagOwnerType =
        SagOwnerType.fromValue(raw) ?: error(
            "sag_vectors.jsonl ownerType 未知：$raw（合法值：${SagOwnerType.VALUES.joinToString()}）"
        )

    private fun parseEvents(bytes: ByteArray?): List<EventEntity> {
        val seen = HashSet<Long>()
        val parsed = parseJsonLines(ENTRY_EVENTS, bytes) { o ->
            val id = o.optLong("id", 0L)
            check(id > 0) { "events.jsonl 记录 id 非法：$id" }
            check(seen.add(id)) { "events.jsonl id 重复：$id" }
            val chunkId = o.optLong("chunkId", -1L)
            check(chunkId > 0) { "events.jsonl event $id 的 chunkId 非法：$chunkId" }
            EventEntity(
                id = id,
                chunkId = chunkId,
                canonicalText = o.optString("canonicalText", ""),
                confidence = o.optDouble("confidence", 0.0),
                title = o.optString("title", ""),
                summary = o.optString("summary", ""),
                content = o.optString("content", ""),
                language = o.optString("language", ""),
                status = requireEventStatus(o.optString("status", EventStatus.ACTIVE.value), id).value,
                extractorVersion = o.optString("extractorVersion", ""),
                extractionVersion = o.optString("extractionVersion", ""),
                modelId = o.optString("modelId", ""),
                promptVersion = o.optString("promptVersion", ""),
                active = o.optBoolean("active", true),
                supersededBy = if (!o.has("supersededBy") || o.isNull("supersededBy")) {
                    null
                } else {
                    o.optLong("supersededBy", 0L).takeIf { it > 0 }
                },
                createdAt = o.optLong("createdAt", 0L),
            )
        }
        check(parsed.size <= BackupValidator.MAX_EVENTS) {
            "events.jsonl 行数超过上限（${BackupValidator.MAX_EVENTS}）"
        }
        // supersededBy 是可选的"新版本指针"：若指向本次未导出的 event（如旧包/部分包），
        // 归一为 null（悬空指针不影响数据完整性；不因此拒绝整包）。
        val ids = parsed.mapTo(HashSet()) { it.id }
        return parsed.map { e ->
            if (e.supersededBy != null && e.supersededBy !in ids) e.copy(supersededBy = null) else e
        }
    }

    private fun parseEntities(bytes: ByteArray?): List<EntityEntity> {
        val seen = HashSet<Long>()
        val parsed = parseJsonLines(ENTRY_ENTITIES, bytes) { o ->
            val id = o.optLong("id", 0L)
            check(id > 0) { "entities.jsonl 记录 id 非法：$id" }
            check(seen.add(id)) { "entities.jsonl id 重复：$id" }
            val normalizedName = o.optString("normalizedName", "")
            check(normalizedName.isNotEmpty()) { "entities.jsonl entity $id 缺少 normalizedName" }
            EntityEntity(
                id = id,
                normalizedName = normalizedName,
                entityType = o.optStringOrNull("entityType")?.also { raw ->
                    require(com.zhibox.memory.core.extract.EntityType.fromWire(raw) != null) {
                        "entities.jsonl 存在未知 entityType：$raw（允许值见 EntityType 11 类）"
                    }
                },
                name = o.optString("name", ""),
                description = o.optString("description", ""),
                createdAt = o.optLong("createdAt", 0L),
            )
        }
        check(parsed.size <= BackupValidator.MAX_ENTITIES) {
            "entities.jsonl 行数超过上限（${BackupValidator.MAX_ENTITIES}）"
        }
        return parsed
    }

    /**
     * B15.1：entity_aliases.jsonl 解析/校验（可选 entry，旧包缺失=空）。
     *
     * 规则（与生产 [EntityAliasRepository] 一致，避免坏包进入单事务后 FK/UNIQUE 报底层错）：
     * - id/entityId > 0，id 包内唯一；entityId ∈ 包内 entities 闭包；
     * - alias trim 后长度在 MIN_ALIAS_LENGTH..MAX_ALIAS_LENGTH；normalizedAlias 非空且长度受限；
     * - normalizedAlias 包内全局唯一（DB 唯一索引）；行数 ≤ MAX_ENTITY_ALIASES。
     *
     * @param entityIds 已解析的包内 entity id 集合（闭包外别名拒绝整包）
     */
    private fun parseEntityAliases(bytes: ByteArray?, entityIds: Set<Long>): List<EntityAliasEntity> {
        val seenIds = HashSet<Long>()
        val seenNormalized = HashSet<String>()
        val parsed = parseJsonLines(ENTRY_ENTITY_ALIASES, bytes) { o ->
            val id = o.optLong("id", 0L)
            check(id > 0) { "entity_aliases.jsonl 记录 id 非法：$id" }
            check(seenIds.add(id)) { "entity_aliases.jsonl id 重复：$id" }
            val entityId = o.optLong("entityId", 0L)
            check(entityId > 0) { "entity_aliases.jsonl $id 的 entityId 非法：$entityId" }
            check(entityId in entityIds) {
                "entity_aliases.jsonl $id 的 entityId 不在包内实体闭包：$entityId——" +
                    "包已损坏或导出不完整"
            }
            val alias = o.optString("alias", "").trim()
            check(alias.isNotEmpty()) { "entity_aliases.jsonl $id 的 alias 为空" }
            check(
                alias.length in EntityAliasRepository.MIN_ALIAS_LENGTH..EntityAliasRepository.MAX_ALIAS_LENGTH,
            ) {
                "entity_aliases.jsonl $id 的 alias 长度非法（${alias.length}；允许 " +
                    "${EntityAliasRepository.MIN_ALIAS_LENGTH}..${EntityAliasRepository.MAX_ALIAS_LENGTH}）"
            }
            val normalizedAlias = o.optString("normalizedAlias", "").trim()
            check(normalizedAlias.isNotEmpty()) { "entity_aliases.jsonl $id 缺少 normalizedAlias" }
            check(normalizedAlias.length <= EntityAliasRepository.MAX_ALIAS_LENGTH) {
                "entity_aliases.jsonl $id 的 normalizedAlias 过长（${normalizedAlias.length}）"
            }
            check(seenNormalized.add(normalizedAlias)) {
                "entity_aliases.jsonl normalizedAlias 重复：$normalizedAlias（DB 唯一索引，拒绝整包）"
            }
            EntityAliasEntity(
                id = id,
                entityId = entityId,
                alias = alias,
                normalizedAlias = normalizedAlias,
                createdAt = o.optLong("createdAt", 0L),
            )
        }
        check(parsed.size <= BackupValidator.MAX_ENTITY_ALIASES) {
            "entity_aliases.jsonl 行数超过上限（${BackupValidator.MAX_ENTITY_ALIASES}）"
        }
        return parsed
    }

    private fun parseEventEntities(bytes: ByteArray?): List<EventEntityRel> {
        val seen = HashSet<Long>()
        val parsed = parseJsonLines(ENTRY_EVENT_ENTITIES, bytes) { o ->
            val id = o.optLong("id", 0L)
            check(id > 0) { "event_entities.jsonl 记录 id 非法：$id" }
            check(seen.add(id)) { "event_entities.jsonl id 重复：$id" }
            val eventId = o.optLong("eventId", 0L)
            val entityId = o.optLong("entityId", 0L)
            check(eventId > 0) { "event_entities.jsonl $id 的 eventId 非法：$eventId" }
            check(entityId > 0) { "event_entities.jsonl $id 的 entityId 非法：$entityId" }
            EventEntityRel(
                id = id,
                eventId = eventId,
                entityId = entityId,
                role = o.optStringOrNull("role"),
                weight = o.optDouble("weight", 1.0),
                description = o.optStringOrNull("description"),
            )
        }
        check(parsed.size <= BackupValidator.MAX_EVENT_ENTITIES) {
            "event_entities.jsonl 行数超过上限（${BackupValidator.MAX_EVENT_ENTITIES}）"
        }
        return parsed
    }

    private fun parseExtractionJobs(
        bytes: ByteArray?,
        chunkIds: Set<Long>,
        documentIds: Set<String>,
    ): List<ExtractionJobEntity> {
        val seen = HashSet<String>()
        val parsed = parseJsonLines(ENTRY_EXTRACTION_JOBS, bytes) { o ->
            val id = o.optString("id", "")
            check(id.isNotEmpty()) { "extraction_jobs.jsonl 记录缺少 id" }
            check(seen.add(id)) { "extraction_jobs.jsonl id 重复：$id" }
            val documentId = o.optString("documentId", "")
            val chunkId = o.optLong("chunkId", -1L)
            check(chunkId > 0) { "extraction_jobs.jsonl job $id 的 chunkId 非法：$chunkId" }
            // 这里只做"可读的早失败"；闭包完整性由 BackupClosure 统一校验（重复一次无妨，
            // 但错误信息更贴近本文件字段）
            check(chunkId in chunkIds) {
                "extraction_jobs.jsonl job $id 引用了不存在的 chunk：$chunkId"
            }
            check(documentId in documentIds) {
                "extraction_jobs.jsonl job $id 引用了不存在的 document：$documentId"
            }
            ExtractionJobEntity(
                id = id,
                documentId = documentId,
                chunkId = chunkId,
                status = requireExtractionJobStatus(
                    o.optString("status", ExtractionJobStatus.NOT_INDEXED.value), id,
                ).value,
                attempt = o.optInt("attempt", 0).coerceAtLeast(0),
                errorCode = o.optStringOrNull("errorCode"),
                errorDetail = o.optStringOrNull("errorDetail"),
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
        check(parsed.size <= BackupValidator.MAX_EXTRACTION_JOBS) {
            "extraction_jobs.jsonl 行数超过上限（${BackupValidator.MAX_EXTRACTION_JOBS}）"
        }
        return parsed
    }

    private fun parseSagVectors(
        bytes: ByteArray?,
        spacesById: Map<String, EmbeddingMetaEntity>,
    ): List<SagVectorEntity> {
        val parsed = parseJsonLines(ENTRY_SAG_VECTORS, bytes) { o ->
            val ownerType = requireSagOwnerType(o.optString("ownerType", ""))
            val ownerId = o.optLong("ownerId", 0L)
            check(ownerId > 0) { "sag_vectors.jsonl 记录 ownerId 非法：$ownerId" }
            val spaceId = o.optString("spaceId", "")
            check(spaceId.isNotEmpty()) { "sag_vectors.jsonl 记录缺少 spaceId（owner=${ownerType.value}:$ownerId）" }
            // 空间必须在 meta.json 注册（模型/维度/量化口径都来自空间）；缺失则写库前拒绝
            val meta = spacesById[spaceId] ?: error(
                "sag_vectors.jsonl 引用了 meta.json 中不存在的空间：$spaceId（owner=${ownerType.value}:$ownerId）"
            )
            val dimensions = o.optInt("dimensions", meta.dimensions)
            check(dimensions == meta.dimensions) {
                "sag_vectors.jsonl dimensions($dimensions) 与空间 $spaceId 维度(${meta.dimensions})不一致"
            }
            val role = o.optString("role", "")
            require(com.zhibox.memory.core.sag.SagVectorRole.fromWire(role) != null) {
                "sag_vectors.jsonl role 未知：$role（合法值：event_content / entity_description）"
            }
            check(role.isNotEmpty()) { "sag_vectors.jsonl 记录缺少 role（space=$spaceId）" }
            val vectorNorm = o.optDouble("vectorNorm", 1.0)
            check(vectorNorm.isFinite()) { "sag_vectors.jsonl 记录 vectorNorm 非法：$vectorNorm" }
            val blob = BackupValidator.decodeBlobBase64(
                text = o.optString("blobBase64", ""),
                expectedBytes = meta.dimensions * 4,
                context = "sag_vectors.jsonl（space=$spaceId, role=$role）",
            )
            SagVectorEntity(
                ownerType = ownerType.value,
                ownerId = ownerId,
                role = role,
                spaceId = spaceId,
                dimensions = dimensions,
                vectorBlob = blob,
                vectorNorm = vectorNorm,
                createdAt = o.optLong("createdAt", 0L),
            )
        }
        check(parsed.size <= BackupValidator.MAX_SAG_VECTORS) {
            "sag_vectors.jsonl 行数超过上限（${BackupValidator.MAX_SAG_VECTORS}）"
        }
        return parsed
    }

    private fun <T> parseJsonLines(
        entryName: String,
        bytes: ByteArray?,
        transform: (JSONObject) -> T,
    ): List<T> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        val result = ArrayList<T>()
        var lineNo = 0
        for (rawLine in bytes.toString(Charsets.UTF_8).lineSequence()) {
            lineNo++
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val obj = try {
                JSONObject(line)
            } catch (e: JSONException) {
                error("$entryName 第 $lineNo 行不是合法 JSON：${e.message}")
            }
            result.add(transform(obj))
        }
        return result
    }

    // ---- 预览 / 模型在位判定 ----

    data class SpaceRequirement(
        val spaceId: String,
        val modelId: String,
        val accelerator: String,
        val catalogFile: String?,
        val present: Boolean,
        val catalogDownloadable: Boolean,
    )

    data class ImportPreview(
        val formatVersion: Int,
        val spaces: List<SpaceRequirement>,
        val sourceCount: Int,
        val documentCount: Int,
        val chunkCount: Int,
        val vectorCount: Int,
        val missingModels: List<SpaceRequirement>,
        val incompatibleGpu: List<SpaceRequirement>,
        // v3（B8）：SAG 层计数（v1/v2 包为 0；UI 不展示也不影响，后续备份 UI 可显示）
        val eventCount: Int = 0,
        val entityCount: Int = 0,
        val eventEntityCount: Int = 0,
        val sagVectorCount: Int = 0,
        val extractionJobCount: Int = 0,
    ) {
        val blocked: Boolean get() = missingModels.isNotEmpty()
    }

    private fun buildPreview(context: Context, parsed: ParsedBackup): ImportPreview {
        // P1-7 修复：在位判定必须用真实候选路径（filesDir/models + cacheDir/models-link）。
        // 旧实现对 entry≠null 固定查 filesDir/models/$file——共享仓"启用"后模型在 models-link，
        // 合法备份会被判 missing 而永远无法导入（false-negative）。
        val candidates = PathPreferences.candidates(context)
        val spaces = parsed.requirements.map { r -> previewOf(r, candidates) }
        return ImportPreview(
            formatVersion = parsed.formatVersion,
            spaces = spaces,
            sourceCount = parsed.sources.size,
            documentCount = parsed.documents.size,
            chunkCount = parsed.chunks.size,
            vectorCount = parsed.vectorPayload.declaredCount,
            missingModels = spaces.filter { !it.present },
            incompatibleGpu = spaces.filter { it.accelerator != "cpu" },
            eventCount = parsed.events.size,
            entityCount = parsed.entities.size,
            eventEntityCount = parsed.eventEntities.size,
            sagVectorCount = parsed.sagVectors.size,
            extractionJobCount = parsed.extractionJobs.size,
        )
    }

    private fun previewOf(r: Requirement, candidates: List<File>): SpaceRequirement {
        val entry = r.catalogFile?.takeIf { it.isNotBlank() }?.let(ModelCatalog::byFileName)
        val local = localModelFor(r, candidates)
        // 未收录模型（entry==null）退化为"同名候选文件存在即可"；否则还要过尺寸吻合
        val present = local != null && (entry == null || ModelCatalog.sizeMatches(entry, local))
        return SpaceRequirement(
            spaceId = r.spaceId,
            modelId = r.modelId,
            accelerator = r.accelerator,
            catalogFile = r.catalogFile,
            present = present,
            catalogDownloadable = entry != null,
        )
    }

    /** 按包内 catalog_file（优先）或 modelId 匹配本机候选中实际存在的模型文件 */
    private fun localModelFor(r: Requirement, candidates: List<File>): File? =
        BackupModelPresence.find(r.catalogFile, r.modelId, candidates)

    // ---- 导入（先全量校验 → 快照 → 单事务替换） ----

    private suspend fun importZip(
        context: Context,
        zip: ZipInputStream,
        dropIncompatibleGpuVectors: Boolean,
        verifyModelIntegrity: Boolean = true,
        rebuildSagFts: suspend (AppDatabase) -> Unit = SagFtsRebuilder::rebuildAll,
    ): ImportOutcome {
        // 0) 前置：导入/重嵌入进行中拒绝导入（避免与 worker 写入交错成混合数据）
        requireNoActiveJobs(context, action = "导入")

        // 1) 全量读取 + 完整语义校验（任何一项失败都不会触碰现有数据库）
        val entries = BackupValidator.readArchive(zip)
        val parsed = parsePackage(entries, dropIncompatibleGpuVectors, collectRows = true)

        val preview = buildPreview(context, parsed)
        if (verifyModelIntegrity) {
            check(!preview.blocked) {
                "缺少模型：${preview.missingModels.joinToString("、") { it.modelId }}（请先在模型商店下载）"
            }
            // 2) 模型供应链校验（P1-7）：在位模型 SHA-256 + 精确尺寸 + 与包内 catalog 版本比对
            verifyModelsInPlace(context, parsed.requirements)
        } else {
            // 测试回环路径：跳过模型校验（见 importFromFileForTest 注释）
        }

        // 3) 导入前自动快照（P0-1a）：失败即中止——没有安全网时不允许替换用户知识库
        snapshotBeforeImport(context)

        // 4) 单事务替换（任何一步异常整体回滚 → 原库保持不变）
        val restored = writeDatabase(context, parsed)

        // 5) B9：SAG FTS（event/entity）是纯派生数据，v3 包不携带 → 事务提交后重建。
        //    放在事务外：FTS 重建失败不应让已恢复的主数据不可用（事务已提交，数据已恢复）；
        //    失败时返回可恢复警告。v1/v2 包没有 SAG 数据 → 保持清空、不调用重建。
        val rebuildFailure = if (parsed.formatVersion >= 3) {
            rebuildSagFtsAfterImport(context, rebuildSagFts)
        } else {
            null
        }
        return ImportOutcome(
            restoredCount = restored,
            warning = rebuildFailure?.let { ImportWarning.SAG_FTS_REBUILD_FAILED },
            warningDetail = rebuildFailure?.let {
                (it.message ?: it.javaClass.simpleName).take(300)
            },
        )
    }

    /**
     * v3 导入成功后重建 event/entity FTS（B9）。
     *
     * 为什么在事务外：FTS 是派生数据（[SagFtsRebuilder] 先清空再分批重建），失败不应连带知识库
     * 回滚——此时主事务已提交，数据已恢复；失败作为 [ImportOutcome.warning] 返回给 UI。
     * 重试入口：再次导入 / 设置页索引维护 / 直接调用 SagFtsRebuilder.rebuildAll。
     */
    /** @return null on success, otherwise the sanitized cause (never document content). */
    private suspend fun rebuildSagFtsAfterImport(
        context: Context,
        rebuild: suspend (AppDatabase) -> Unit,
    ): Throwable? {
        val db = AppDatabase.get(context)
        try {
            return runCatchingCancellable { rebuild(db) }.exceptionOrNull()?.also { failure ->
                // 保留原因用于 Settings 警告/诊断；只记录异常，不记录文档内容或密钥。
                Log.w("KnowledgeBackup", "SAG FTS rebuild failed after import", failure)
            }
        } finally {
            // 恢复包内 queued 抽取任务必须唤醒 Worker；WorkManager 未初始化（Robolectric 等）
            // 时不能把已成功的导入改判失败，任务保留 queued 由 App 启动/补抽取入口兜底。
            runCatching {
                com.zhibox.memory.core.extraction.SagExtractionScheduler.enqueue(context)
            }
        }
    }

    private suspend fun verifyModelsInPlace(context: Context, requirements: List<Requirement>) {
        val candidates = PathPreferences.candidates(context)
        // 同一模型可能被多个空间引用（如 CPU/GPU 双空间）：按 (路径, 期望sha, 期望size) 去重，
        // 避免对 300-500MB 模型重复做全量 SHA-256。
        val verified = HashMap<String, String?>()
        for (r in requirements) {
            val local = localModelFor(r, candidates) ?: continue // 缺失已由 preview.blocked 拦截
            val entry = r.catalogFile?.takeIf { it.isNotBlank() }?.let(ModelCatalog::byFileName)
            // Locale.ROOT（B4-3）：哈希/标识符比较不受默认 locale 影响（土耳其 locale "I"→"ı"）；
            // 当前 SHA-256 十六进制只含 a-f 虽不受影响，但统一口径避免未来换非 hex 标识符时回归。
            val catalogSha = entry?.sha256?.lowercase(Locale.ROOT)
            val packageSha = r.catalogSha256?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
            // catalog_sha256 真正参与校验：包声明版本与本机 catalog 版本不一致 = 模型被换版，
            // 用新模型加载旧向量空间会造成检索语义静默错位 → 拒绝。
            if (catalogSha != null && packageSha != null && catalogSha != packageSha) {
                error(
                    "备份包模型版本与本机模型目录不一致：${local.name}" +
                        "（备份 ${packageSha.take(12)}… / 本机 ${catalogSha.take(12)}…）——" +
                        "请安装与备份匹配的模型版本后再导入"
                )
            }
            val expectedSha = catalogSha ?: packageSha
            val expectedSize = entry?.sizeBytes ?: r.sizeBytes.takeIf { it > 0 }
            val key = "${local.absolutePath}|$expectedSha|$expectedSize"
            if (verified.containsKey(key)) continue
            ModelIntegrity.verify(local, expectedSha, expectedSize).getOrElse {
                error("模型完整性校验失败（${local.name}）：${it.message}")
            }
            verified[key] = expectedSha
        }
    }

    /** 导入前快照：保留最近 [SNAPSHOT_KEEP] 份，保证误导入后用户仍可回滚 */
    private suspend fun snapshotBeforeImport(context: Context): File {
        val dir = snapshotDir(context).apply { mkdirs() }
        val snapshot = File(dir, "$SNAPSHOT_PREFIX${System.currentTimeMillis()}.zip")
        // 快照 = 回滚安全网：保留全部文档行（含非 ready），父子行仍成对（闭包一致）。
        runCatchingCancellable { exportZipFile(context, snapshot, includeNonReadyDocuments = true) }.onFailure {
            snapshot.delete()
            error("导入前自动备份失败，已中止导入（原库未改动）：${it.message}")
        }
        runCatchingCancellable {
            dir.listFiles { f -> f.isFile && f.name.startsWith(SNAPSHOT_PREFIX) && f.name.endsWith(".zip") }
                ?.sortedByDescending { it.name }
                ?.drop(SNAPSHOT_KEEP)
                ?.forEach { it.delete() }
        }
        return snapshot
    }

    /** 最新可恢复的自动快照；结构损坏文件不会遮蔽更早的有效快照。 */
    fun latestSnapshot(context: Context): File? = snapshotDir(context)
        .listFiles { f ->
            f.isFile && f.length() > 0 && f.name.startsWith(SNAPSHOT_PREFIX) && f.name.endsWith(".zip")
        }
        ?.sortedByDescending { it.name }
        ?.firstOrNull(::hasSnapshotEntries)

    private fun hasSnapshotEntries(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            BackupValidator.KNOWN_ENTRIES
                .asSequence()
                .filter { it != ENTRY_ENTITY_ALIASES }
                .all { zip.getEntry(it) != null }
        }
    }.getOrDefault(false)

    private fun snapshotDir(context: Context): File = File(context.filesDir, SNAPSHOT_DIR)

    /**
     * 恢复时保证 embedding_meta 最多一个 active（P2-B1-4）。
     *
     * 与运行时 [EmbeddingRuntime.deactivateOthers] 的唯一差异：恢复时**没有"当前模型空间"概念**，
     * 因此采用确定性策略——保留 createdAt 最早的一行（没有 active 行则保持全 false），其余置 false；
     * 运行时 registerSpace 则保留"本次解析出的当前空间"。两者都满足"至多一个 active"不变式，
     * 最终默认空间由后续 registerSpace/惰性维护按当前模型收敛。
     */
    internal fun normalizeRestoredActiveSpaces(spaces: List<EmbeddingMetaEntity>): List<EmbeddingMetaEntity> {
        val activeIds = spaces.filter { it.active }.sortedBy { it.createdAt }.map { it.id }
        if (activeIds.size <= 1) return spaces
        val keep = activeIds.first()
        return spaces.map { if (it.id == keep) it else it.copy(active = false) }
    }

    private suspend fun writeDatabase(context: Context, parsed: ParsedBackup): Int {
        val db = AppDatabase.get(context)
        val embeddings = parsed.vectorPayload.rows.map {
            ChunkEmbeddingEntity(
                chunkId = it.chunkId,
                embeddingSpaceId = it.spaceId,
                role = it.role,
                vectorBlob = it.blob,
                vectorNorm = it.vectorNorm,
                createdAt = it.createdAt,
            )
        }
        // FTS 不进包（避免索引格式绑定），导入时从 chunk.content 重算——与入库同一 bigram 口径
        val ftsRows = parsed.chunks.map {
            ChunkFtsEntity(chunkId = it.id, bigramContent = BigramTokenizer.toFtsText(it.content))
        }
        db.withTransaction {
            // 显式 DELETE 而非 clearAllTables()：后者不能在 Room 事务内调用，且会清掉
            // schema_migrations 等与知识数据无关的表（迁移历史必须保留）。
            //
            // v6 SAG 层清空顺序（B8）：
            // - sag_vector 无 FK（owner 是多态引用），必须显式清空；
            // - entity 无父表，显式清空（其子表 event_entity/chunk_entity 由 FK CASCADE 清理）；
            // - event/event_entity 通过 chunk 删除级联清空（event.chunkId FK CASCADE；
            //   Room 生成代码在 onOpen 执行 PRAGMA foreign_keys = ON，已核验）；
            // - extraction_job 既有 FK 级联也有 deleteAll，显式清空更直观；
            // - event_fts/entity_fts 无 FK，必须 clearFts（派生数据，B9 重新写入）。
            db.extractionJobDao().deleteAll()
            db.sagVectorDao().deleteAll()
            db.entityAliasDao().deleteAll()
            db.entityDao().deleteAll()
            db.embeddingDao().deleteAll()
            db.chunkDao().deleteAllFts()
            db.chunkDao().deleteAll()
            db.ingestJobDao().deleteAll()
            db.documentDao().deleteAll()
            db.sourceDao().deleteAll()
            db.embeddingDao().deleteAllMeta()
            db.appKvDao().deleteAll()
            db.eventDao().clearFts()
            db.entityDao().clearFts()

            // P0-1b：恢复 embedding_meta（此前导入不写 meta → 恢复后再导出向量全丢）
            // P2-B1-4：旧备份可能带多行 active；恢复时归一为"最多一个 active"，
            // 避免破坏 registerSpace 维护的默认空间不变式（active 仅表示默认空间，不代表可检索性）。
            if (parsed.spaces.isNotEmpty()) {
                db.embeddingDao().upsertMetaAll(normalizeRestoredActiveSpaces(parsed.spaces))
            }
            if (parsed.sources.isNotEmpty()) db.sourceDao().upsertAll(parsed.sources)
            if (parsed.documents.isNotEmpty()) db.documentDao().upsertAll(parsed.documents)
            if (parsed.chunks.isNotEmpty()) {
                db.chunkDao().insertAll(parsed.chunks)
                db.chunkDao().insertFts(ftsRows)
            }
            // v3 SAG 层：父行（chunk）已写入后再写 event/entity/event_entity/sag_vector/extraction_job，
            // 保证 FK 约束满足；v1/v2 包这些列表为空 → 恢复后 SAG 结构为空（PRD 预期，可在线补抽取）。
            if (parsed.events.isNotEmpty()) db.eventDao().upsertAll(parsed.events)
            if (parsed.entities.isNotEmpty()) db.entityDao().upsertAll(parsed.entities)
            // B15.1：entity 之后写别名（FK 满足）；FTS 重建在事务外，会读取 alias 生成 entity_fts。
            if (parsed.entityAliases.isNotEmpty()) db.entityAliasDao().upsertAll(parsed.entityAliases)
            if (parsed.eventEntities.isNotEmpty()) db.eventEntityDao().upsertAll(parsed.eventEntities)
            if (parsed.sagVectors.isNotEmpty()) db.sagVectorDao().insertAll(parsed.sagVectors)
            if (parsed.extractionJobs.isNotEmpty()) db.extractionJobDao().upsertAll(parsed.extractionJobs)
            if (embeddings.isNotEmpty()) db.embeddingDao().insertAll(embeddings)
            if (parsed.appKv.isNotEmpty()) db.appKvDao().putAll(parsed.appKv)
        }
        // 跨模块一致性（P1-5 检索矩阵缓存 × P0-1 整库替换）：导入成功后必须主动失效进程内
        // VectorMatrixCache。缓存签名 = (spaceId, rowCount, maxVectorId)，"导入另一份同空间
        // 同规模备份"时签名可能恰好不变，首次检索会命中旧矩阵（旧 chunkId → 检索结果错乱）。
        // 缓存不可用也不应让导入失败，因此 runCatching 兜底（纯内存操作，实际不会抛）。
        runCatching { VectorMatrixCache.invalidate() }
        return parsed.chunks.size
    }

    // ---- 公共小工具 ----

    private suspend fun requireNoActiveJobs(context: Context, action: String = "导出") {
        val db = AppDatabase.get(context)
        val ingestActive = db.ingestJobDao().activeCount(JobStage.ACTIVE_VALUES)
        val extractionActive = db.extractionJobDao().activeCount(ExtractionJobStatus.ACTIVE_VALUES)
        check(ingestActive == 0 && extractionActive == 0) {
            "有 $ingestActive 个导入/重嵌入任务、$extractionActive 个 SAG 抽取任务正在进行，" +
                "请等待完成后再$action（避免备份/恢复数据交错）"
        }
    }

    /** modelId → catalog entry：先精确匹配，再按最长文件名前缀回退（防止 SOC 特化版误配通用版） */
    private fun catalogEntryForModelId(modelId: String): ModelCatalog.Entry? {
        if (modelId.isBlank()) return null
        fun stem(fileName: String) = fileName.removeSuffix(".litertlm").removeSuffix(".task")
        ModelCatalog.ENTRIES.firstOrNull { stem(it.fileName) == modelId }?.let { return it }
        return ModelCatalog.ENTRIES
            .filter { modelId.startsWith(stem(it.fileName)) }
            .maxByOrNull { stem(it.fileName).length }
    }

    /** v1 包没有的字段返回 null（JSON null / 缺失 / 空串统一口径） */
    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name, "").takeIf { it.isNotEmpty() }
    }
}
