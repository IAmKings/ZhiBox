package com.zhibox.memory.core.backup

import android.app.Application
import androidx.room.withTransaction
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.AppKvEntity
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.IngestJobEntity
import com.zhibox.memory.data.db.JobStage
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.SourceEntity
import com.zhibox.memory.core.text.BigramTokenizer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B5-1 Robolectric（JVM，真实 Room + Robolectric 临时目录）：知识库备份导出→导入回环。
 *
 * 覆盖：
 * - 小数据 seed（source/document(ready)/chunk/chunk_fts 由导入重建/受支持 EGemma2 空间 meta/手写向量 blob）；
 * - `KnowledgeBackup.exportToFile` → 断言导出向量数、包内空间/H/FTS 计数（preview）；
 * - clearAllTables 后 `importFromFileForTest` → 断言 document 状态/chunk/FTS/向量数、meta 空间兼容、
 *   手写向量 blob 逐字节一致；
 * - 临时文件/DB 清理，无网络/无模型文件依赖（`importFromFileForTest` 跳过模型在位校验）。
 *
 * 说明：`AppDatabase.get(context)` 是进程单例；Robolectric 每个测试方法有独立 Application/临时
 * 目录，因此通过反射清空/关闭单例保证测试隔离（仅测试代码，不改 main）。
 * 运行前提：`app/build.gradle.kts` 已加入 Robolectric 测试依赖（build-fixer 负责）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private val tmpFiles = mutableListOf<File>()

    private val supportedSpace =
        "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu@abcdef123456"

    /** 手写 2 维向量：0.5f, 0.25f（LE），用于逐字节回环断言。 */
    private val blob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(0.5f).putFloat(0.25f).array()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        db = AppDatabase.get(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        tmpFiles.forEach { it.delete() }
        File(app.filesDir, "backup_snapshots").deleteRecursively()
        resetDatabaseSingleton()
        app.deleteDatabase(DB_NAME)
    }

    @Test
    fun exportThenImport_roundTrip_preservesReadyDocumentVectorsAndSpace() = runBlocking {
        seedSmallLibrary()
        val pkg = tmp("robolectric-backup.zip")

        // 1) 导出：只有受支持空间/ready 文档进入包
        val exported = KnowledgeBackup.exportToFile(app, pkg).getOrThrow()
        assertEquals("应导出 2 行手写向量", 2, exported)
        assertTrue("导出包应存在", pkg.isFile && pkg.length() > 0)
        val preview = KnowledgeBackup.previewFromFile(app, pkg).getOrThrow()
        assertEquals("meta 只应包含受支持空间", 1, preview.spaces.size)
        assertEquals(supportedSpace, preview.spaces.single().spaceId)
        assertEquals("包内向量数", 2, preview.vectorCount)
        assertEquals("ready 文档数", 1, preview.documentCount)
        assertEquals("文档闭包 chunk 数", 2, preview.chunkCount)

        // 2) 清库后导入（跳过模型在位校验；不触发 WorkManager）
        withContext(Dispatchers.IO) { db.clearAllTables() }
        assertEquals(0, db.chunkDao().count())
        val imported = KnowledgeBackup.importFromFileForTest(app, pkg)
        assertTrue("导入应成功：${imported.exceptionOrNull()?.message}", imported.isSuccess)

        // 3) 回环断言：document 状态/标题、chunk、FTS 重建、向量、空间注册
        assertEquals("document 行数", 1, db.documentDao().all().size)
        val document = db.documentDao().get("doc_rt")!!
        assertEquals(DocStatus.READY.value, document.status)
        assertEquals("备份兼容文档", document.title)
        assertEquals("chunk 行数", 2, db.chunkDao().count())
        assertEquals("FTS 由 chunk.content 重建", 2, db.chunkDao().ftsCount())
        assertEquals("向量行数", 2, db.embeddingDao().count())
        assertEquals("embedding_meta 行数", 1, db.embeddingDao().allMeta().size)
        val meta = db.embeddingDao().meta(supportedSpace)!!
        assertEquals("mediapipe-universal", meta.providerId)
        assertEquals("embeddinggemma-2-text-vision-440m", meta.modelId)
        assertEquals(2, meta.dimensions)
        assertEquals("gpu", meta.accelerator)
        assertEquals("f32", meta.quantization)
        assertEquals("空间 id 兼容", supportedSpace, db.embeddingDao().allSpaceIds().single())

        // 手写向量逐字节一致
        val restoredVectors = db.embeddingDao().page(limit = 10, offset = 0)
        assertEquals(2, restoredVectors.size)
        assertTrue(
            "回环后向量 blob 必须逐字节一致",
            restoredVectors.all { it.vectorBlob.contentEquals(blob) },
        )
        assertTrue(restoredVectors.all { it.role == "content" && it.vectorNorm == 1.0 })

        // FTS 关键词可检索（bigram 预分词在导入时重建）
        val ftsHits = db.chunkDao().searchFtsOrdered(
            com.zhibox.memory.core.text.BigramTokenizer.toOrMatchQuery("备份回环"),
            10,
            DocStatus.READY.value,
        )
        assertEquals("关键词应命中两个 chunk 中至少一个", true, ftsHits.isNotEmpty())
        println(
            "B5_BACKUP_ROBOLECTRIC exported=$exported vectors=${db.embeddingDao().count()} " +
                "chunks=${db.chunkDao().count()} fts=${db.chunkDao().ftsCount()} space=$supportedSpace",
        )
    }

    @Test
    fun exportImport_roundTrip_restoresEntityAliasAndEntityFts() = runBlocking {
        seedSmallLibrary()
        val chunkId = db.chunkDao().page(limit = 1, offset = 0).single().id
        val entityId = db.entityDao().upsert(
            EntityEntity(
                normalizedName = "张三",
                entityType = "person",
                name = "张三",
                description = "学生",
                createdAt = 10,
            ),
        )
        val eventId = db.eventDao().upsert(
            EventEntity(
                chunkId = chunkId,
                canonicalText = "张三在上海学习数学",
                confidence = 0.9,
                title = "张三在上海学习数学",
                summary = "学习场景",
                content = "张三在上海学习数学。",
                language = "zh",
                status = EventStatus.ACTIVE.value,
                extractorVersion = "egemma-sag-extract-v1",
                extractionVersion = "ev-alias-1",
                promptVersion = "v1",
                active = true,
                createdAt = 11,
            ),
        )
        db.eventEntityDao().upsertAll(
            listOf(EventEntityRel(eventId = eventId, entityId = entityId, role = "person", weight = 1.0)),
        )
        db.entityAliasDao().upsertAll(
            listOf(
                EntityAliasEntity(
                    entityId = entityId,
                    alias = "小张同学",
                    normalizedAlias = "小张同学",
                    createdAt = 12,
                ),
            ),
        )

        val pkg = tmp("robolectric-backup-alias.zip")
        KnowledgeBackup.exportToFile(app, pkg).getOrThrow()

        // 导出端闭包：entity 被 event_entity 引用 → 其 alias 进包
        val entries = readZipEntries(pkg)
        val aliasBytes = entries[BackupValidator.ENTRY_ENTITY_ALIASES]
        assertTrue("导出包应包含 entity_aliases.jsonl", aliasBytes != null)
        val aliasText = aliasBytes!!.toString(Charsets.UTF_8)
        assertTrue("alias 行应导出：$aliasText", aliasText.contains("小张同学"))
        assertTrue("alias 只带包内 entity：$aliasText", aliasText.contains("\"entityId\":$entityId"))

        withContext(Dispatchers.IO) { db.clearAllTables() }
        val imported = KnowledgeBackup.importFromFileForTest(app, pkg)
        assertTrue("含 alias 的 v3 包应可导入：${imported.exceptionOrNull()?.message}", imported.isSuccess)

        val restored = db.entityAliasDao().byEntity(entityId)
        assertEquals("alias 行应恢复", 1, restored.size)
        assertEquals("小张同学", restored.single().alias)
        assertEquals("小张同学", restored.single().normalizedAlias)
        val entityFtsHits = db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("小张同学"), 10)
        assertTrue("entity_fts 重建应包含 alias：$entityFtsHits", entityId in entityFtsHits)
        println(
            "B15_BACKUP_ALIAS_ROBOLECTRIC aliases=${db.entityAliasDao().count()} " +
                "entities=${db.entityDao().count()} entityFts=${db.entityDao().countFts()}",
        )
    }

    @Test
    fun import_keepsPersistentSnapshot_andReportsDerivedIndexWarning() = runBlocking {
        seedSmallLibrary()
        val pkg = tmp("warning-backup.zip")
        KnowledgeBackup.exportToFile(app, pkg).getOrThrow()

        val outcome = KnowledgeBackup.importFromFileForTest(
            context = app,
            src = pkg,
            rebuildSagFts = { error("forced rebuild failure") },
        ).getOrThrow()

        assertEquals(2, outcome.restoredCount)
        assertEquals(KnowledgeBackup.ImportWarning.SAG_FTS_REBUILD_FAILED, outcome.warning)
        assertTrue(outcome.warningDetail.orEmpty().contains("forced rebuild failure"))
        val snapshot = requireNotNull(KnowledgeBackup.latestSnapshot(app))
        assertTrue(snapshot.isFile)
        assertEquals(File(app.filesDir, "backup_snapshots"), snapshot.parentFile)
        assertTrue(KnowledgeBackup.previewFromFile(app, snapshot).isSuccess)

        File(snapshot.parentFile, "auto-backup-9999999999999.zip").writeText("broken")
        assertEquals(snapshot, KnowledgeBackup.latestSnapshot(app))
    }

    @Test
    fun snapshotFailureLeavesLiveDatabaseUnchanged() = runBlocking {
        seedSmallLibrary()
        val beforeDocs = db.documentDao().all().size
        val beforeChunks = db.chunkDao().allChunks().size
        val pkg = tmp("snapshot-failure.zip")
        KnowledgeBackup.exportToFile(app, pkg).getOrThrow()

        // Make the persistent snapshot path unwritable by occupying it with a regular file.
        val blocker = File(app.filesDir, "backup_snapshots")
        blocker.writeText("not a directory")

        val result = runCatching {
            KnowledgeBackup.importFromFileForTest(
                context = app,
                src = pkg,
                rebuildSagFts = {},
            ).getOrThrow()
        }
        blocker.delete()

        assertTrue(result.isFailure)
        assertEquals(beforeDocs, db.documentDao().all().size)
        assertEquals(beforeChunks, db.chunkDao().allChunks().size)
    }

    @Test
    fun export_refusesActiveIngestJob() = runBlocking {
        seedSmallLibrary()
        db.ingestJobDao().upsert(
            IngestJobEntity(
                id = "active-ingest",
                sourceId = "src_rt",
                documentId = "doc_rt",
                stage = JobStage.QUEUED.value,
                progress = 0,
                error = null,
                createdAt = 10,
                updatedAt = 10,
            ),
        )

        val result = KnowledgeBackup.exportToFile(app, tmp("blocked-ingest.zip"))

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("导入"))
    }

    @Test
    fun export_refusesActiveReembeddingJob() = runBlocking {
        seedSmallLibrary()
        db.ingestJobDao().upsert(
            IngestJobEntity(
                id = "active-reembed",
                sourceId = "src_rt",
                documentId = "doc_rt",
                stage = JobStage.EMBEDDING.value,
                progress = 20,
                error = null,
                createdAt = 10,
                updatedAt = 10,
            ),
        )

        val result = KnowledgeBackup.exportToFile(app, tmp("blocked-reembed.zip"))

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("重嵌入"))
    }

    @Test
    fun export_refusesActiveExtractionJob() = runBlocking {
        seedSmallLibrary()
        val chunkId = db.chunkDao().page(limit = 1, offset = 0).single().id
        db.extractionJobDao().upsert(
            ExtractionJobEntity(
                id = "active-extraction",
                documentId = "doc_rt",
                chunkId = chunkId,
                status = ExtractionJobStatus.QUEUED.value,
                errorCode = null,
                errorDetail = null,
                createdAt = 10,
                updatedAt = 10,
            ),
        )

        val result = KnowledgeBackup.exportToFile(app, tmp("blocked.zip"))

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("抽取"))
    }

    @Test
    fun generatedLargeLibrary_exportImport_roundTripsCounts() = runBlocking {
        seedGeneratedLibrary(documentCount = 50, chunksPerDocument = 4)
        val docsBefore = db.documentDao().all().size
        val chunksBefore = db.chunkDao().allChunks().size
        val pkg = tmp("generated-large.zip")

        val exportedVectors = KnowledgeBackup.exportToFile(app, pkg).getOrThrow()
        assertEquals(docsBefore * 4, exportedVectors)
        assertEquals(200, exportedVectors)

        withContext(Dispatchers.IO) { db.clearAllTables() }
        val outcome = KnowledgeBackup.importFromFileForTest(
            context = app,
            src = pkg,
            rebuildSagFts = {},
        ).getOrThrow()

        assertEquals(200, outcome.restoredCount)
        assertEquals(docsBefore, db.documentDao().all().size)
        assertEquals(chunksBefore, db.chunkDao().allChunks().size)
        assertEquals(200, db.embeddingDao().vectorsForSpace(supportedSpace).size)
        assertTrue(KnowledgeBackup.previewFromFile(app, pkg).getOrThrow().sourceCount > 0)
    }

    /**
     * UI 提示类键（`ui_hint_*`）不随知识库备份迁移：
     * 1) 导出包内不得出现；
     * 2) 旧包/手工包里即使带了，导入也不恢复；
     * 3) 普通 app_kv（如 reembed_last_done）仍要正常往返。
     */
    @Test
    fun backup_excludesUiHintKeys_onExportAndImport() = runBlocking {
        seedSmallLibrary()
        withContext(Dispatchers.IO) {
            db.appKvDao().put(AppKvEntity("reembed_last_done", "111", 1L))
            db.appKvDao().put(AppKvEntity(UI_HINT_KEY_PREFIX + "intelligence_onboarded", "1", 2L))
        }
        val pkg = tmp("ui-hint-backup.zip")
        KnowledgeBackup.exportToFile(app, pkg).getOrThrow()

        val entries = readZipEntries(pkg)
        val kvText = entries.getValue(BackupValidator.ENTRY_APP_KV).toString(Charsets.UTF_8)
        assertFalse("UI 提示键不得进入备份包：$kvText", kvText.contains("intelligence_onboarded"))
        assertTrue("普通 app_kv 仍应导出：$kvText", kvText.contains("reembed_last_done"))

        // 模拟旧包：把提示键塞回 app_kv.jsonl，导入端必须忽略
        val patched = rewriteZipEntry(
            src = pkg,
            entryName = BackupValidator.ENTRY_APP_KV,
            newBytes = (kvText +
                """{"key":"ui_hint_intelligence_onboarded","value":"1","updatedAt":2}\n"""
                ).toByteArray(),
        )
        withContext(Dispatchers.IO) { db.clearAllTables() }
        val imported = KnowledgeBackup.importFromFileForTest(app, patched)
        assertTrue("导入应成功：${imported.exceptionOrNull()?.message}", imported.isSuccess)

        val restoredHint = withContext(Dispatchers.IO) {
            db.appKvDao().get(UI_HINT_KEY_PREFIX + "intelligence_onboarded")
        }
        assertNull("UI 提示键不得被恢复", restoredHint)
        assertEquals(
            "普通 app_kv 应正常恢复",
            "111",
            withContext(Dispatchers.IO) { db.appKvDao().get("reembed_last_done") },
        )
    }

    /** 重写 zip 中的单个 entry（其余 entry 原样复制），用于模拟"带 UI 提示键的旧备份包"。 */
    private fun rewriteZipEntry(src: File, entryName: String, newBytes: ByteArray): File {
        val dst = tmp("rewritten-${src.name}")
        ZipInputStream(src.inputStream().buffered()).use { zin ->
            ZipOutputStream(dst.outputStream().buffered()).use { zout ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val data = zin.readBytes()
                    zout.putNextEntry(ZipEntry(entry.name))
                    zout.write(if (entry.name == entryName) newBytes else data)
                    zout.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        return dst
    }

    private fun readZipEntries(file: File): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    // ---- seed / 工具 ----

    private suspend fun seedGeneratedLibrary(documentCount: Int, chunksPerDocument: Int) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.sourceDao().upsert(SourceEntity("src_gen", "生成来源", "file:///gen.txt", "text/plain", 1, "active"))
                val docs = (0 until documentCount).map { i ->
                    DocumentEntity(
                        id = "doc_gen_$i", sourceId = "src_gen", title = "生成文档 $i",
                        checksum = "gen-$i", parserId = "txt-utf8-v1", createdAt = i.toLong(),
                        status = DocStatus.READY.value, sizeBytes = 64,
                    )
                }
                db.documentDao().upsertAll(docs)
                db.embeddingDao().upsertMeta(
                    EmbeddingMetaEntity(
                        id = supportedSpace,
                        providerId = "mediapipe-universal",
                        modelId = "embeddinggemma-2-text-vision-440m",
                        modelRevision = "abcdef123456",
                        dimensions = 2,
                        normalization = "l2",
                        taskTemplate = "app-prefix-v1",
                        createdAt = 3,
                        active = true,
                        accelerator = "gpu",
                        quantization = "f32",
                    ),
                )
                val chunks = (0 until documentCount).flatMap { d ->
                    (0 until chunksPerDocument).map { c ->
                        ChunkEntity(
                            documentId = "doc_gen_$d", ordinal = c,
                            content = "生成文档 $d 第 $c 段内容", charStart = c * 8,
                            charEnd = c * 8 + 8, tokenEstimate = 8,
                        )
                    }
                }
                val ids = db.chunkDao().insertAll(chunks)
                db.chunkDao().insertFts(
                    ids.mapIndexed { index, id ->
                        ChunkFtsEntity(
                            chunkId = id,
                            bigramContent = BigramTokenizer.toFtsText(chunks[index].content),
                        )
                    },
                )
                db.embeddingDao().insertAll(
                    ids.map { id ->
                        ChunkEmbeddingEntity(
                            chunkId = id, embeddingSpaceId = supportedSpace, role = "content",
                            vectorBlob = blob, vectorNorm = 1.0, createdAt = 4,
                        )
                    },
                )
            }
        }
    }

    private suspend fun seedSmallLibrary() {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.sourceDao().upsert(SourceEntity("src_rt", "备份回环来源", "file:///rt.txt", "text/plain", 1, "active"))
                db.documentDao().upsert(
                    DocumentEntity(
                        id = "doc_rt", sourceId = "src_rt", title = "备份兼容文档",
                        checksum = "rt-checksum", parserId = "txt-utf8-v1",
                        createdAt = 2, status = DocStatus.READY.value, sizeBytes = 9,
                    ),
                )
                val chunkIds = db.chunkDao().insertAll(
                    listOf(
                        ChunkEntity(
                            documentId = "doc_rt", ordinal = 0, content = "备份回环第一段内容",
                            charStart = 0, charEnd = 9, tokenEstimate = 9,
                        ),
                        ChunkEntity(
                            documentId = "doc_rt", ordinal = 1, content = "备份回环第二段内容",
                            charStart = 9, charEnd = 18, tokenEstimate = 9,
                        ),
                    ),
                )
                db.embeddingDao().upsertMeta(
                    EmbeddingMetaEntity(
                        id = supportedSpace,
                        providerId = "mediapipe-universal",
                        modelId = "embeddinggemma-2-text-vision-440m",
                        modelRevision = "abcdef123456",
                        dimensions = 2,
                        normalization = "l2",
                        taskTemplate = "app-prefix-v1",
                        createdAt = 3,
                        active = true,
                        accelerator = "gpu",
                        quantization = "f32",
                    ),
                )
                db.embeddingDao().insertAll(
                    chunkIds.map { chunkId ->
                        ChunkEmbeddingEntity(
                            chunkId = chunkId,
                            embeddingSpaceId = supportedSpace,
                            role = "content",
                            vectorBlob = blob,
                            vectorNorm = 1.0,
                            createdAt = 4,
                        )
                    },
                )
            }
        }
        assertEquals(2, db.chunkDao().count())
    }

    private fun tmp(name: String): File =
        File(app.cacheDir, name).also { it.delete(); tmpFiles += it }

    /** 关闭并清空 AppDatabase 进程单例（Robolectric 每测试方法独立 Application/临时目录）。 */
    private fun resetDatabaseSingleton() {
        // Kotlin companion 属性的后备字段位于 outer class（AppDatabase.instance）；
        // 兜底再找 Companion 内部的同名 static 字段（不同 Kotlin 版本布局差异）。
        val outerField = runCatching {
            AppDatabase::class.java.getDeclaredField("instance")
        }.getOrNull()
        val companionField = runCatching {
            Class.forName("com.zhibox.memory.data.db.AppDatabase\$Companion")
                .getDeclaredField("instance")
        }.getOrNull()
        val field = outerField ?: companionField
        checkNotNull(field) {
            "无法定位 AppDatabase.instance 单例字段：Robolectric 测试隔离需要清空该单例"
        }
        val holder: Any? = if (field.declaringClass == AppDatabase::class.java) {
            null
        } else {
            // Companion.INSTANCE 静态实例
            field.declaringClass.getField("INSTANCE").get(null)
        }
        runCatching {
            field.isAccessible = true
            (field.get(holder) as? AppDatabase)?.let { runCatching { it.close() } }
            field.set(holder, null)
        }
    }

    private companion object {
        const val DB_NAME = "zhibox.sqlite3"
    }
}
