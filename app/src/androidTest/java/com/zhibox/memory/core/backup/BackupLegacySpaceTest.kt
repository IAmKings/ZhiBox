package com.zhibox.memory.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.ingest.ImportResult
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * B7 备份兼容验收（instrumentation）：
 * 1) **导出过滤 + 文档降级**：库中同时存在受支持 EGemma2 空间与已下线旧空间（granite/Path C/LFM）
 *    向量时，导出包只包含受支持空间向量；且「仅含旧模型向量的 ready 文档」在包内**降级为 failed**
 *    （有受支持向量的文档保持 ready）——恢复后不会出现"ready 但零向量"的静默不可检索数据；
 *    失败文档可重新导入（importFile 只对 pending/processing/ready 判重，failed 不拦截）；
 * 2) **导入拒绝**：含已下线模型空间的包在 parse 阶段（任何 DB 写入之前）被拒绝，错误信息列出
 *    modelId，原库数据保持不变；
 * 3) **受支持最小包行为不变**：同构的 EGemma2-only 最小包仍可正常导入。
 *
 * 走 `importFromFileForTest` 跳过"本机模型在位"校验（测试机不需要真实模型资产）；B7 空间拒绝
 * 发生在模型校验之前的 parse 阶段，因此该入口仍能验证拒绝语义。
 */
class BackupLegacySpaceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var staging: File
    private val tmpFiles = mutableListOf<File>()

    private val supportedSpace =
        "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu@abcdef123456"
    private val graniteSpace = "litertlm-engine/granite-311m/raw-symmetric-v1"
    private val pathCSpace = "mediapipe-text/embedding_gemma/raw-symmetric-v1"

    /** 仅旧模型向量的文档内容；checksum 必须与重新导入测试用文件字节一致。 */
    private val legacyOnlyContent = "旧模型专用文档内容：仅 legacy 向量，需要重新导入。"
    private val supportedDocContent = "受支持文档内容：440M 向量在位，保持 ready。"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
        staging = File(context.filesDir, "import-staging").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
        tmpFiles.forEach { it.delete() }
        staging.listFiles()?.forEach { it.delete() }
        // importFile 复制进 documents/ 的测试文件
        File(context.filesDir, "documents").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun export_downgradesLegacyOnlyReadyDocuments_toFailed_andAllowsReimport() = runBlocking {
        seedMixedLibraryWithLegacyOnlyDocument()

        val pkg = tmp("b7-export-downgrade.zip")
        val exported = KnowledgeBackup.exportToFile(context, pkg).getOrThrow()
        assertEquals("只应导出受支持空间的 1 行向量", 1, exported)

        val preview = KnowledgeBackup.previewFromFile(context, pkg).getOrThrow()
        assertEquals("meta 只应保留受支持空间", 1, preview.spaces.size)
        assertEquals(supportedSpace, preview.spaces.single().spaceId)
        assertEquals("包内向量数只含受支持空间", 1, preview.vectorCount)
        assertEquals("两类 ready 文档都应进包（旧-only 文档保留、降级）", 2, preview.documentCount)
        assertEquals("chunk 应保留（文档闭包）", 3, preview.chunkCount)

        // 包内 documents.jsonl 状态：仅旧向量文档 failed；有受支持向量文档保持 ready
        val exportedDocs = readJsonLines(pkg, BackupValidator.ENTRY_DOCUMENTS).associateBy {
            it.getString("id")
        }
        assertEquals(DocStatus.READY.value, exportedDocs.getValue("doc_supported").getString("status"))
        assertEquals(
            "仅旧模型向量的 ready 文档必须在包内降级为 failed",
            DocStatus.FAILED.value,
            exportedDocs.getValue("doc_legacy").getString("status"),
        )

        // 清库后重新导入：旧空间向量被过滤，文档状态保留（failed 可见、可重导）
        db.clearAllTables()
        val imported = KnowledgeBackup.importFromFileForTest(context, pkg)
        assertTrue("导出包应可被新 App 再导入：${imported.exceptionOrNull()?.message}", imported.isSuccess)
        assertEquals("恢复后仅受支持空间 meta", 1, db.embeddingDao().allMeta().size)
        assertEquals(supportedSpace, db.embeddingDao().allMeta().single().id)
        assertEquals("恢复后仅受支持空间向量", 1, db.embeddingDao().count())
        assertEquals("恢复后 chunk 保留", 3, db.chunkDao().count())
        assertEquals(DocStatus.READY.value, db.documentDao().get("doc_supported")?.status)
        assertEquals(
            "恢复后仅旧向量文档应为 failed（不是静默零向量的 ready）",
            DocStatus.FAILED.value,
            db.documentDao().get("doc_legacy")?.status,
        )
        assertFalse("不得恢复已下线空间", db.embeddingDao().allSpaceIds().contains(graniteSpace))

        // 关键兼容：同 checksum 的 failed 文档可重新导入（不被 checksum 去重拦截）
        val reimportFile = File(staging, "b7-legacy-reimport.md").apply { writeText(legacyOnlyContent) }
        val reimport = IngestRepository(context).importFile(reimportFile)
        assertTrue(
            "failed 文档同 checksum 重新导入应为 Enqueued（不得判定 Duplicate）：$reimport",
            reimport is ImportResult.Enqueued,
        )
        assertEquals("重导后应新增第 3 个 document", 3, db.documentDao().all().size)
        println(
            "B7_BACKUP_EXPORT_DOWNGRADE spaces=${preview.spaces.size} vectors=${preview.vectorCount} " +
                "docs=${preview.documentCount} chunks=${preview.chunkCount} statuses=" +
                exportedDocs.mapValues { it.value.getString("status") } + " reimport=enqueued",
        )
    }

    @Test
    fun import_rejectsLegacySpacePackage_beforeAnyDbWrite() = runBlocking {
        seedSupportedOnlyLibrary()
        val before = counts()

        val legacyPkg = tmp("b7-legacy-package.zip")
        writeMinimalPackage(legacyPkg, MiniSpace(graniteSpace, "litertlm-engine", "granite-embedding-311m-r2_wi8fc"))

        val result = KnowledgeBackup.importFromFileForTest(context, legacyPkg)
        assertTrue("含已下线空间的备份包必须导入失败", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误信息应说明空间已下线：$message", message.contains("已下线"))
        assertTrue("错误信息应列出 modelId：$message", message.contains("granite-embedding-311m-r2_wi8fc"))
        assertEquals("拒绝导入不得改动原库（行数）", before, counts())
        assertEquals(supportedSpace, db.embeddingDao().allMeta().single().id)
        println("B7_BACKUP_IMPORT_REJECT message=${message.lineSequence().first()}")
    }

    @Test
    fun import_acceptsMinimalSupportedSpacePackage() = runBlocking {
        val supportedPkg = tmp("b7-supported-package.zip")
        writeMinimalPackage(
            supportedPkg,
            MiniSpace(supportedSpace, "mediapipe-universal", "embeddinggemma-2-text-vision-440m"),
        )
        val result = KnowledgeBackup.importFromFileForTest(context, supportedPkg)
        assertTrue(
            "受支持空间的最小包应可导入（拒绝针对旧空间而非包结构）：${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
    }

    // ---- seed / 工具 ----

    private data class MiniSpace(val id: String, val providerId: String, val modelId: String)

    /** 供拒绝用例：1 文档 + 2 chunk + 2 受支持向量（行数断言用）。 */
    private suspend fun seedSupportedOnlyLibrary() {
        db.sourceDao().upsert(SourceEntity("src", "备份兼容来源", "", null, 0, "active"))
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc", sourceId = "src", title = "备份兼容文档", checksum = "sum",
                parserId = "txt-utf8-v1", createdAt = 0, status = DocStatus.READY.value,
            ),
        )
        val chunkIds = db.chunkDao().insertAll(
            (0 until 2).map { i ->
                ChunkEntity(
                    documentId = "doc", ordinal = i, content = "备份兼容内容$i",
                    charStart = 0, charEnd = 10, tokenEstimate = 10,
                )
            },
        )
        db.embeddingDao().upsertMeta(supportedMeta(supportedSpace))
        db.embeddingDao().insertAll(chunkIds.map { embedding(it, supportedSpace) })
    }

    /**
     * 混合库（降级用例）：
     * - doc_supported（ready）：2 chunk，chunk0 有受支持 440M 向量 → 保持 ready；
     * - doc_legacy（ready）：1 chunk，只有旧路线向量 → 包内降级 failed；
     * - doc_supported 的 chunk1 另挂一条旧向量（只影响过滤计数，不影响该文档保持 ready）。
     */
    private suspend fun seedMixedLibraryWithLegacyOnlyDocument() {
        db.sourceDao().upsert(SourceEntity("src", "B7 混合来源", "", null, 0, "active"))
        db.documentDao().upsertAll(
            listOf(
                DocumentEntity(
                    id = "doc_supported", sourceId = "src", title = "受支持文档",
                    checksum = sha256Hex(supportedDocContent.toByteArray()), parserId = "txt-utf8-v1",
                    createdAt = 0, status = DocStatus.READY.value,
                ),
                DocumentEntity(
                    id = "doc_legacy", sourceId = "src", title = "旧模型专用文档",
                    checksum = sha256Hex(legacyOnlyContent.toByteArray()), parserId = "txt-utf8-v1",
                    createdAt = 1, status = DocStatus.READY.value,
                ),
            ),
        )
        val chunkIds = db.chunkDao().insertAll(
            listOf(
                ChunkEntity(
                    documentId = "doc_supported", ordinal = 0, content = supportedDocContent,
                    charStart = 0, charEnd = supportedDocContent.length,
                    tokenEstimate = supportedDocContent.length,
                ),
                ChunkEntity(
                    documentId = "doc_supported", ordinal = 1, content = "受支持文档第二段",
                    charStart = 0, charEnd = 8, tokenEstimate = 8,
                ),
                ChunkEntity(
                    documentId = "doc_legacy", ordinal = 0, content = legacyOnlyContent,
                    charStart = 0, charEnd = legacyOnlyContent.length,
                    tokenEstimate = legacyOnlyContent.length,
                ),
            ),
        )
        assertEquals(3, chunkIds.size)
        db.embeddingDao().upsertMeta(supportedMeta(supportedSpace))
        db.embeddingDao().upsertMeta(
            legacyMeta(graniteSpace, "litertlm-engine", "granite-embedding-311m-r2_wi8fc"),
        )
        db.embeddingDao().upsertMeta(legacyMeta(pathCSpace, "mediapipe-text", "embedding_gemma"))
        db.embeddingDao().insertAll(
            listOf(
                // doc_supported 有受支持向量（保持 ready）
                embedding(chunkIds[0], supportedSpace),
                // doc_supported 的 chunk1 只有旧向量（不影响该文档有受支持向量）
                embedding(chunkIds[1], graniteSpace),
                // doc_legacy 仅旧向量 → 包内降级 failed
                embedding(chunkIds[2], pathCSpace),
            ),
        )
    }

    private fun supportedMeta(id: String) = EmbeddingMetaEntity(
        id = id,
        providerId = "mediapipe-universal",
        modelId = "embeddinggemma-2-text-vision-440m",
        modelRevision = "abcdef123456",
        dimensions = 2,
        normalization = "l2",
        taskTemplate = "app-prefix-v1",
        createdAt = 1,
        active = true,
        accelerator = "gpu",
        quantization = "f32",
    )

    private fun legacyMeta(id: String, providerId: String, modelId: String) = EmbeddingMetaEntity(
        id = id,
        providerId = providerId,
        modelId = modelId,
        modelRevision = null,
        dimensions = 2,
        normalization = "l2",
        taskTemplate = "raw-symmetric",
        createdAt = 2,
        active = false,
        accelerator = "cpu",
        quantization = "f32",
    )

    private fun embedding(chunkId: Long, spaceId: String) = ChunkEmbeddingEntity(
        chunkId = chunkId,
        embeddingSpaceId = spaceId,
        role = "content",
        vectorBlob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).putFloat(0f).array(),
        vectorNorm = 1.0,
        createdAt = 1,
    )

    private data class Counts(
        val sources: Int,
        val documents: Int,
        val chunks: Int,
        val embeddings: Int,
        val metas: Int,
    )

    private suspend fun counts() = Counts(
        sources = db.sourceDao().all().size,
        documents = db.documentDao().all().size,
        chunks = db.chunkDao().count(),
        embeddings = db.embeddingDao().count(),
        metas = db.embeddingDao().allMeta().size,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 读 zip 内 JSONL entry（导出包检查文档状态用）。 */
    private fun readJsonLines(pkg: File, entryName: String): List<JSONObject> {
        var found: ByteArray? = null
        pkg.inputStream().buffered().use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        found = zip.readBytes()
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val bytes = found ?: return emptyList()
        return bytes.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .map { JSONObject(it) }
            .toList()
    }

    /** 最小 v2 包：meta + requirements + 空 vectors.bin（0 行），用于验证 parse 阶段的空间校验。 */
    private fun writeMinimalPackage(dest: File, space: MiniSpace) {
        val meta = """{"format_version":2,"exported_at":1,"app":"zhibox-memory","spaces":[""" +
            """{"id":"${space.id}","providerId":"${space.providerId}",""" +
            """"modelId":"${space.modelId}","dimensions":2,""" +
            """"normalization":"l2","taskTemplate":"raw-symmetric","createdAt":1,""" +
            """"active":true,"accelerator":"cpu","quantization":"f32"}]}"""
        val requirements = """{"requirements":[{"spaceId":"${space.id}",""" +
            """"modelId":"${space.modelId}"}]}"""
        val vectors = BackupValidator.encodeVectorHeader(0)
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry(BackupValidator.ENTRY_META, meta.toByteArray(Charsets.UTF_8))
            entry(BackupValidator.ENTRY_REQUIREMENTS, requirements.toByteArray(Charsets.UTF_8))
            entry(BackupValidator.ENTRY_VECTORS, vectors)
        }
    }

    private fun tmp(name: String): File =
        File(context.cacheDir, name).also { it.delete(); tmpFiles += it }
}
