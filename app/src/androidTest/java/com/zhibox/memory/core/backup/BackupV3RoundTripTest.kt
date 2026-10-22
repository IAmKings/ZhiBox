package com.zhibox.memory.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.extraction.EntityAliasRepository
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.AppKvEntity
import com.zhibox.memory.data.db.ChatConversationEntity
import com.zhibox.memory.data.db.ChatMessageEntity
import com.zhibox.memory.data.db.ChatRole
import com.zhibox.memory.data.db.ChunkEmbeddingEntity
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import com.zhibox.memory.data.db.EntityAliasEntity
import com.zhibox.memory.data.db.EntityEntity
import com.zhibox.memory.data.db.EventEntity
import com.zhibox.memory.data.db.EventEntityRel
import com.zhibox.memory.data.db.EventStatus
import com.zhibox.memory.data.db.ExtractionJobEntity
import com.zhibox.memory.data.db.ExtractionJobStatus
import com.zhibox.memory.data.db.MessageCitationEntity
import com.zhibox.memory.data.db.QueryTraceEntity
import com.zhibox.memory.data.db.SagOwnerType
import com.zhibox.memory.data.db.SagVectorEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * B8 备份 v3 回环（instrumentation，真实 Room + 真实 zip 包）：
 * 1) v3 导出→清库→导入：chunk/event/entity/event_entity/sag_vector/extraction_job 完整恢复；
 *    event_fts/entity_fts 由 B9 `SagFtsRebuilder` 重建（event 仅 active、entity 全量）；
 *    chat/query_trace 不导出（P0 隐私）；
 * 2) 导入不清理已存在的 chat/query_trace（本地隐私数据与知识库恢复解耦）；
 * 3) 坏包（event.chunkId 失真 / sag 空间未注册）在写库前拒绝，原库数据不变；
 *    缺 extraction_jobs.jsonl 视为空 entry 可导入；
 * 4) v2 最小包仍可导入且 SAG 表为空（向后兼容）。
 *
 * 走 `importFromFileForTest` 跳过"本机模型在位"校验（测试机无模型资产）。
 */
class BackupV3RoundTripTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val tmpFiles = mutableListOf<File>()

    private val supportedSpace =
        "mediapipe-universal/embeddinggemma-2-text-vision-440m/raw-symmetric-v1-gpu@abcdef123456"
    private val blob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(0.5f).putFloat(0.25f).array()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
        tmpFiles.forEach { it.delete() }
    }

    @Test
    fun exportV3RoundTripRestoresSagLayerAndExcludesChatTrace() = runBlocking {
        val seed = seedLibrary()

        val pkg = tmp("b8-v3-roundtrip.zip")
        val exportedChunkVectors = KnowledgeBackup.exportToFile(context, pkg).getOrThrow()
        assertEquals("只导出受支持空间 chunk 向量", 1, exportedChunkVectors)

        // 包内 entry 名单：v2 7 个 + v3 5 个；chat/query_trace 不得出现
        val entries = readZip(pkg)
        val expectedEntries = setOf(
            BackupValidator.ENTRY_META, BackupValidator.ENTRY_REQUIREMENTS,
            BackupValidator.ENTRY_SOURCES, BackupValidator.ENTRY_DOCUMENTS,
            BackupValidator.ENTRY_CHUNKS, BackupValidator.ENTRY_APP_KV,
            BackupValidator.ENTRY_VECTORS,
            BackupValidator.ENTRY_EVENTS, BackupValidator.ENTRY_ENTITIES,
            BackupValidator.ENTRY_EVENT_ENTITIES, BackupValidator.ENTRY_SAG_VECTORS,
            BackupValidator.ENTRY_EXTRACTION_JOBS, BackupValidator.ENTRY_ENTITY_ALIASES,
        )
        assertEquals("v3 包应恰好包含 13 个已知 entry（含 B15.1 可选 entity_aliases）", expectedEntries, entries.keys)

        val preview = KnowledgeBackup.previewFromFile(context, pkg).getOrThrow()
        assertEquals("v3 导出", 3, preview.formatVersion)
        assertEquals(1, preview.sourceCount)
        assertEquals(1, preview.documentCount)
        assertEquals(2, preview.chunkCount)
        assertEquals(1, preview.vectorCount)
        assertEquals(2, preview.eventCount)
        assertEquals(1, preview.entityCount)
        assertEquals(2, preview.eventEntityCount)
        assertEquals(2, preview.sagVectorCount)
        assertEquals(2, preview.extractionJobCount)

        // 清库后导入：知识库条目完整恢复
        db.clearAllTables()
        val imported = KnowledgeBackup.importFromFileForTest(context, pkg)
        assertTrue("v3 包应可导入：${imported.exceptionOrNull()?.message}", imported.isSuccess)

        assertEquals(1, db.sourceDao().all().size)
        assertEquals(
            "B14.2：备份恢复应保留 source.originUrl",
            "https://example.com/b8-source?x=1",
            db.sourceDao().get("src1")!!.originUrl,
        )
        val doc = db.documentDao().get("doc1")!!
        assertEquals(DocStatus.READY.value, doc.status)
        assertEquals(2, db.chunkDao().count())
        assertEquals("chunk FTS 由 content 重建", 2, db.chunkDao().ftsCount())
        assertEquals(1, db.embeddingDao().count())
        assertEquals(1, db.embeddingDao().allMeta().size)
        assertEquals(supportedSpace, db.embeddingDao().allMeta().single().id)

        // event：两条都恢复（含 superseded 历史行），extractor/extraction 版本字段保留
        val events = db.eventDao().all().filter { it.chunkId == seed.chunkIds[0] }
        assertEquals(2, events.size)
        val activeEvent = events.single { it.active }
        assertEquals(seed.eventId, activeEvent.id)
        assertEquals("张三在上海学习数学", activeEvent.title)
        assertEquals("egemma-sag-extract-v1", activeEvent.extractorVersion)
        assertEquals("uuid-1", activeEvent.extractionVersion)
        assertEquals("gpt-4o-mini", activeEvent.modelId)
        assertEquals("v1", activeEvent.promptVersion)
        val superseded = events.single { !it.active }
        assertEquals(EventStatus.SUPERSEDED.value, superseded.status)
        assertEquals(seed.eventId, superseded.supersededBy)

        // entity / event_entity（weight/description 保留）
        val entity = db.entityDao().byId(seed.entityId)!!
        assertEquals("张三", entity.name)
        assertEquals("person", entity.entityType)
        assertEquals("学生", entity.description)
        // B15.1：别名与派生 entity_fts 均恢复（FTS 由导入后重建，文本含 alias）
        val restoredAliases = db.entityAliasDao().byEntity(seed.entityId)
        assertEquals("别名应随备份恢复", 1, restoredAliases.size)
        assertEquals("小张同学", restoredAliases.single().alias)
        assertEquals("小张同学", restoredAliases.single().normalizedAlias)
        val aliasHits = db.entityDao().searchFts(BigramTokenizer.toOrMatchQuery("小张同学"), 10)
        assertTrue("entity_fts 重建应包含别名：$aliasHits", seed.entityId in aliasHits)
        val links = db.eventEntityDao().byEvent(seed.eventId)
        assertEquals(1, links.size)
        assertEquals(1.0, links.single().weight, 0.0001)
        assertEquals("核心人物", links.single().description)

        // sag_vector：owner/role/space/blob/norm 完整恢复（id 由库重新分配）
        val eventVectors = db.sagVectorDao().byOwner(SagOwnerType.EVENT.value, seed.eventId)
        assertEquals(1, eventVectors.size)
        assertEquals("event_content", eventVectors.single().role)
        assertEquals(supportedSpace, eventVectors.single().spaceId)
        assertEquals(2, eventVectors.single().dimensions)
        assertTrue("SAG 向量 blob 应逐字节恢复", eventVectors.single().vectorBlob.contentEquals(blob))
        assertEquals(1.0, eventVectors.single().vectorNorm, 0.0001)
        assertEquals(1, db.sagVectorDao().byOwner(SagOwnerType.ENTITY.value, seed.entityId).size)

        // extraction_job：状态/attempt/错误字段恢复
        assertEquals(1, db.extractionJobDao().countByStatus(ExtractionJobStatus.FAILED.value))
        assertEquals(1, db.extractionJobDao().countByStatus(ExtractionJobStatus.DONE.value))
        val jobs = db.extractionJobDao().all().sortedBy { it.id }
        assertEquals(listOf("job-1", "job-2"), jobs.map { it.id })
        assertEquals("doc1", jobs.first().documentId)
        assertEquals(seed.chunkIds[0], jobs.first().chunkId)
        assertEquals(ExtractionJobStatus.FAILED.value, jobs.first().status)
        assertEquals(ExtractionJobStatus.DONE.value, jobs.last().status)

        // B9：FTS 不随包导出，v3 导入成功后由 SagFtsRebuilder 重建：
        // event 仅 active（1 行；superseded 历史行不进索引），entity 全量（1 行）
        assertEquals("导入后 event FTS 应由 active event 重建", 1, db.eventDao().countFts())
        assertEquals("导入后 entity FTS 应由全量 entity 重建", 1, db.entityDao().countFts())
        // 隐私数据不导出：清库后导入不会恢复 chat/query_trace
        assertTrue(db.chatDao().recentConversations(10).isEmpty())
        assertEquals(0, db.queryTraceDao().count())
        println(
            "B8_BACKUP_V3_ROUNDTRIP entries=${entries.size} events=${preview.eventCount} " +
                "entities=${preview.entityCount} sag=${preview.sagVectorCount} jobs=${preview.extractionJobCount}",
        )
    }

    @Test
    fun importV3DoesNotDeleteExistingChatAndTraceRows() = runBlocking {
        seedLibrary()
        val pkg = tmp("b8-v3-keep-chat.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        // 不改库直接导入：知识库表被整库替换，但 chat/query_trace 不参与备份/恢复
        val imported = KnowledgeBackup.importFromFileForTest(context, pkg)
        assertTrue(imported.isSuccess)
        assertEquals("会话应保留", 1, db.chatDao().recentConversations(10).size)
        assertEquals("消息应保留", 1, db.chatDao().messages("conv1").size)
        assertEquals("引用应保留", 1, db.chatDao().citations("m1").size)
        assertEquals("诊断轨迹应保留", 1, db.queryTraceDao().count())
    }

    @Test
    fun corruptEventChunkReferenceIsRejectedWithoutTouchingDb() = runBlocking {
        val seed = seedLibrary()
        val pkg = tmp("b8-v3-good.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val entries = readZip(pkg).toMutableMap()
        val eventsText = entries.getValue(BackupValidator.ENTRY_EVENTS).toString(Charsets.UTF_8)
        val corrupted = eventsText.replaceFirst(
            "\"chunkId\":${seed.chunkIds[0]}",
            "\"chunkId\":999999",
        )
        assertTrue("fixture 应命中 chunkId 替换：$eventsText", corrupted != eventsText)
        entries[BackupValidator.ENTRY_EVENTS] = corrupted.toByteArray(Charsets.UTF_8)
        val badPkg = tmp("b8-v3-bad-event-chunk.zip")
        writeZip(badPkg, entries)

        val before = counts()
        val result = KnowledgeBackup.importFromFileForTest(context, badPkg)
        assertTrue("event 引用不存在的 chunk 必须拒绝导入", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误应定位 events.jsonl：$message", message.contains("events.jsonl"))
        assertTrue("错误应说明 chunk 不存在：$message", message.contains("不存在的 chunk"))
        assertEquals("失败导入不得改动原库", before, counts())
    }

    @Test
    fun missingExtractionJobsEntryIsTreatedAsEmpty() = runBlocking {
        seedLibrary()
        val pkg = tmp("b8-v3-missing-jobs.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val entries = readZip(pkg).toMutableMap()
        entries.remove(BackupValidator.ENTRY_EXTRACTION_JOBS)
        val trimmed = tmp("b8-v3-no-jobs.zip")
        writeZip(trimmed, entries)

        db.clearAllTables()
        val imported = KnowledgeBackup.importFromFileForTest(context, trimmed)
        assertTrue("缺 extraction_jobs.jsonl 视为空 entry 可导入：${imported.exceptionOrNull()?.message}", imported.isSuccess)
        assertEquals("无 job entry 时任务表为空", 0, db.extractionJobDao().all().size)
        assertEquals("其余 SAG 数据仍恢复", 2, db.eventDao().count())
        assertEquals(1, db.entityDao().count())
    }

    @Test
    fun missingRequiredVectorsEntryIsRejectedWithoutTouchingDb() = runBlocking {
        seedLibrary()
        val pkg = tmp("b8-v3-missing-vectors.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val entries = readZip(pkg).toMutableMap()
        entries.remove(BackupValidator.ENTRY_VECTORS)
        val broken = tmp("b8-v3-no-vectors.zip")
        writeZip(broken, entries)

        val before = counts()
        val result = KnowledgeBackup.importFromFileForTest(context, broken)
        assertTrue("缺必填 vectors.bin 必须拒绝导入", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误应指出缺失 entry：$message", message.contains("vectors.bin"))
        assertEquals("失败导入不得改动原库", before, counts())
    }

    @Test
    fun unregisteredSagVectorSpaceIsRejectedWithoutTouchingDb() = runBlocking {
        seedLibrary()
        val pkg = tmp("b8-v3-good2.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val entries = readZip(pkg).toMutableMap()
        val sagText = entries.getValue(BackupValidator.ENTRY_SAG_VECTORS).toString(Charsets.UTF_8)
        // org.json 可能转义 '/'；按 JSON 字段正则替换，避免依赖具体转义形式
        val corrupted = sagText.replace(
            Regex("\"spaceId\"\\s*:\\s*\"[^\"]*\""),
            "\"spaceId\":\"unknown-space\"",
        )
        assertTrue("fixture 应命中 spaceId 替换", corrupted != sagText)
        entries[BackupValidator.ENTRY_SAG_VECTORS] = corrupted.toByteArray(Charsets.UTF_8)
        val badPkg = tmp("b8-v3-bad-sag-space.zip")
        writeZip(badPkg, entries)

        val before = counts()
        val result = KnowledgeBackup.importFromFileForTest(context, badPkg)
        assertTrue("sag 向量空间未在 meta 注册必须拒绝导入", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误应说明空间未注册：$message", message.contains("meta.json 中不存在的空间"))
        assertTrue("错误应包含空间 id：$message", message.contains("unknown-space"))
        assertEquals("失败导入不得改动原库", before, counts())
    }

    @Test
    fun missingEntityAliasesEntryIsTreatedAsEmpty() = runBlocking {
        seedLibrary()
        val pkg = tmp("b8-v3-alias-full.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val entries = readZip(pkg).toMutableMap()
        entries.remove(BackupValidator.ENTRY_ENTITY_ALIASES)
        val trimmed = tmp("b8-v3-no-alias.zip")
        writeZip(trimmed, entries)

        db.clearAllTables()
        val imported = KnowledgeBackup.importFromFileForTest(context, trimmed)
        assertTrue(
            "缺可选 entity_aliases.jsonl 应视为空 entry 可导入：${imported.exceptionOrNull()?.message}",
            imported.isSuccess,
        )
        assertEquals("无 alias entry 时别名表为空", 0, db.entityAliasDao().count())
        assertEquals("其余 entity 数据仍恢复", 1, db.entityDao().count())
        assertEquals("entity_fts 仍重建（无别名文本）", 1, db.entityDao().countFts())
    }

    @Test
    fun orphanAliasEntityIdIsRejectedWithoutTouchingDb() = runBlocking {
        val seed = seedLibrary()
        val pkg = tmp("b8-v3-alias-orphan-src.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val entries = readZip(pkg).toMutableMap()
        val aliasText = entries.getValue(BackupValidator.ENTRY_ENTITY_ALIASES).toString(Charsets.UTF_8)
        val corrupted = aliasText.replace(
            "\"entityId\":${seed.entityId}",
            "\"entityId\":999999",
        )
        assertTrue("fixture 应命中 alias entityId 替换：$aliasText", corrupted != aliasText)
        entries[BackupValidator.ENTRY_ENTITY_ALIASES] = corrupted.toByteArray(Charsets.UTF_8)
        val badPkg = tmp("b8-v3-alias-orphan.zip")
        writeZip(badPkg, entries)

        val before = counts()
        val result = KnowledgeBackup.importFromFileForTest(context, badPkg)
        assertTrue("别名 entityId 不在包内实体闭包必须拒绝导入", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误应定位 entity_aliases.jsonl：$message", message.contains("entity_aliases.jsonl"))
        assertTrue("错误应说明 entity 闭包：$message", message.contains("不在包内实体闭包"))
        assertEquals("失败导入不得改动原库", before, counts())
    }

    @Test
    fun duplicateNormalizedAliasIsRejectedWithoutTouchingDb() = runBlocking {
        val seed = seedLibrary()
        val pkg = tmp("b8-v3-alias-dup-src.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val duplicateRows = buildString {
            appendLine("""{"id":1,"entityId":${seed.entityId},"alias":"小张同学","normalizedAlias":"小张同学","createdAt":1}""")
            appendLine("""{"id":2,"entityId":${seed.entityId},"alias":"小张同窗","normalizedAlias":"小张同学","createdAt":2}""")
        }
        val entries = readZip(pkg).toMutableMap()
        entries[BackupValidator.ENTRY_ENTITY_ALIASES] = duplicateRows.toByteArray(Charsets.UTF_8)
        val badPkg = tmp("b8-v3-alias-dup.zip")
        writeZip(badPkg, entries)

        val before = counts()
        val result = KnowledgeBackup.importFromFileForTest(context, badPkg)
        assertTrue("normalizedAlias 包内重复必须拒绝导入", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误应说明 normalizedAlias 重复：$message", message.contains("normalizedAlias 重复"))
        assertEquals("失败导入不得改动原库", before, counts())
    }

    @Test
    fun tooLongAliasIsRejectedWithoutTouchingDb() = runBlocking {
        val seed = seedLibrary()
        val pkg = tmp("b8-v3-alias-long-src.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()

        val tooLong = "长".repeat(EntityAliasRepository.MAX_ALIAS_LENGTH + 1)
        val rows = """{"id":1,"entityId":${seed.entityId},"alias":"$tooLong","normalizedAlias":"$tooLong","createdAt":1}""" + "\n"
        val entries = readZip(pkg).toMutableMap()
        entries[BackupValidator.ENTRY_ENTITY_ALIASES] = rows.toByteArray(Charsets.UTF_8)
        val badPkg = tmp("b8-v3-alias-long.zip")
        writeZip(badPkg, entries)

        val before = counts()
        val result = KnowledgeBackup.importFromFileForTest(context, badPkg)
        assertTrue("alias 超长必须拒绝导入", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误应说明 alias 长度：$message", message.contains("alias 长度非法"))
        assertEquals("失败导入不得改动原库", before, counts())
    }

    @Test
    fun aliasOfEntityOutsideExportClosureIsNotExported() = runBlocking {
        seedLibrary()
        val isolatedEntityId = db.entityDao().upsert(
            EntityEntity(
                normalizedName = "局外实体",
                entityType = "person",
                name = "局外实体",
                description = "未关联任何事件",
                createdAt = 700,
            ),
        )
        db.entityAliasDao().upsertAll(
            listOf(
                EntityAliasEntity(
                    entityId = isolatedEntityId,
                    alias = "局外别名",
                    normalizedAlias = "局外别名",
                    createdAt = 701,
                ),
            ),
        )

        val pkg = tmp("b8-v3-alias-closure.zip")
        KnowledgeBackup.exportToFile(context, pkg).getOrThrow()
        val entries = readZip(pkg)
        val aliasText = entries.getValue(BackupValidator.ENTRY_ENTITY_ALIASES).toString(Charsets.UTF_8)
        assertTrue("包内应含闭包实体别名：$aliasText", aliasText.contains("小张同学"))
        assertFalse("闭包外实体别名不得导出：$aliasText", aliasText.contains("局外别名"))
        val entitiesText = entries.getValue(BackupValidator.ENTRY_ENTITIES).toString(Charsets.UTF_8)
        assertFalse("闭包外实体不得进入 entities.jsonl：$entitiesText", entitiesText.contains("局外实体"))

        db.clearAllTables()
        val imported = KnowledgeBackup.importFromFileForTest(context, pkg)
        assertTrue("闭包过滤后的包应可导入：${imported.exceptionOrNull()?.message}", imported.isSuccess)
        assertEquals("只恢复包内 entity 的 1 条别名", 1, db.entityAliasDao().count())
        assertEquals("闭包外实体未恢复", 1, db.entityDao().count())
    }

    @Test
    fun v2MinimalPackageStillImportsWithEmptySagLayer() = runBlocking {
        val pkg = tmp("b8-v2-minimal.zip")
        val meta = """{"format_version":2,"exported_at":1,"app":"zhibox-memory","spaces":[]}"""
        val requirements = """{"requirements":[]}"""
        val vectors = BackupValidator.encodeVectorHeader(0)
        writeZip(
            pkg,
            linkedMapOf(
                BackupValidator.ENTRY_META to meta.toByteArray(Charsets.UTF_8),
                BackupValidator.ENTRY_REQUIREMENTS to requirements.toByteArray(Charsets.UTF_8),
                BackupValidator.ENTRY_VECTORS to vectors,
            ),
        )

        val preview = KnowledgeBackup.previewFromFile(context, pkg).getOrThrow()
        assertEquals("v2 包版本应原样识别", 2, preview.formatVersion)
        assertEquals(0, preview.eventCount)
        assertEquals(0, preview.sagVectorCount)

        val imported = KnowledgeBackup.importFromFileForTest(context, pkg)
        assertTrue("v2 最小包仍应可导入：${imported.exceptionOrNull()?.message}", imported.isSuccess)
        assertEquals("v2 包 SAG 表为空", 0, db.eventDao().count())
        assertEquals(0, db.entityDao().count())
        assertEquals(0, db.sagVectorDao().count())
        assertEquals(0, db.extractionJobDao().all().size)
        assertEquals("v2 旧包无 entity_aliases entry → 别名表为空", 0, db.entityAliasDao().count())
        assertEquals("v2 包不触发 SAG FTS 重建（format<3）", 0, db.eventDao().countFts())
        assertEquals(0, db.entityDao().countFts())
    }

    // ---- seed ----

    private data class Seed(
        val chunkIds: List<Long>,
        val eventId: Long,
        val entityId: Long,
    )

    private suspend fun seedLibrary(): Seed {
        db.sourceDao().upsert(
            SourceEntity("src1", "B8 备份来源", "", null, 100, "active", originUrl = "https://example.com/b8-source?x=1"),
        )
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc1", sourceId = "src1", title = "B8 备份文档", checksum = "sum-b8",
                parserId = "txt-utf8-v1", createdAt = 200, status = DocStatus.READY.value, sizeBytes = 9,
            ),
        )
        val chunkIds = db.chunkDao().insertAll(
            listOf(
                ChunkEntity(documentId = "doc1", ordinal = 0, content = "第一段", charStart = 0, charEnd = 3, tokenEstimate = 3),
                ChunkEntity(documentId = "doc1", ordinal = 1, content = "第二段", charStart = 3, charEnd = 6, tokenEstimate = 3),
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
                taskTemplate = "raw-symmetric",
                createdAt = 300,
                active = true,
                accelerator = "gpu",
                quantization = "f32",
            ),
        )
        db.embeddingDao().insertAll(
            listOf(
                ChunkEmbeddingEntity(
                    chunkId = chunkIds[0],
                    embeddingSpaceId = supportedSpace,
                    role = "content",
                    vectorBlob = blob,
                    vectorNorm = 1.0,
                    createdAt = 400,
                ),
            ),
        )

        val eventId = db.eventDao().upsert(
            EventEntity(
                chunkId = chunkIds[0],
                canonicalText = "张三在上海学习数学",
                confidence = 0.9,
                title = "张三在上海学习数学",
                summary = "学习场景",
                content = "张三在上海学习数学。",
                language = "zh",
                status = EventStatus.ACTIVE.value,
                extractorVersion = "egemma-sag-extract-v1",
                extractionVersion = "uuid-1",
                modelId = "gpt-4o-mini",
                promptVersion = "v1",
                active = true,
                createdAt = 500,
            ),
        )
        val supersededEventId = db.eventDao().upsert(
            EventEntity(
                chunkId = chunkIds[0],
                canonicalText = "旧版本事件",
                confidence = 0.6,
                title = "旧版本事件",
                status = EventStatus.SUPERSEDED.value,
                extractionVersion = "uuid-0",
                active = false,
                supersededBy = eventId,
                createdAt = 450,
            ),
        )
        assertTrue("superseded event 应写入成功", supersededEventId > 0)

        val entityId = db.entityDao().upsert(
            EntityEntity(
                normalizedName = "张三", entityType = "person",
                name = "张三", description = "学生", createdAt = 600,
            ),
        )
        db.eventEntityDao().upsertAll(
            listOf(
                EventEntityRel(eventId = eventId, entityId = entityId, role = "person", weight = 1.0, description = "核心人物"),
                EventEntityRel(eventId = supersededEventId, entityId = entityId, role = "person", weight = 0.5),
            ),
        )
        db.entityAliasDao().upsertAll(
            listOf(
                EntityAliasEntity(
                    entityId = entityId,
                    alias = "小张同学",
                    normalizedAlias = "小张同学",
                    createdAt = 610,
                ),
            ),
        )
        db.sagVectorDao().insertAll(
            listOf(
                sagVector(SagOwnerType.EVENT.value, eventId, "event_content"),
                sagVector(SagOwnerType.ENTITY.value, entityId, "entity_description"),
            ),
        )
        db.extractionJobDao().upsertAll(
            listOf(
                extractionJob("job-1", "doc1", chunkIds[0], ExtractionJobStatus.FAILED, 500),
                extractionJob("job-2", "doc1", chunkIds[1], ExtractionJobStatus.DONE, 510),
            ),
        )

        // 隐私数据：不应进入备份包
        db.chatDao().upsertConversation(
            ChatConversationEntity(id = "conv1", title = "问答", sourceScope = null, createdAt = 700, updatedAt = 700),
        )
        db.chatDao().upsertMessage(
            ChatMessageEntity("m1", "conv1", ChatRole.USER.value, "问题", 701, null),
        )
        db.chatDao().upsertCitations(
            listOf(MessageCitationEntity(messageId = "m1", chunkId = chunkIds[0], rank = 0)),
        )
        db.queryTraceDao().insert(
            QueryTraceEntity(queryHash = "hash-1", mode = "keyword", timingJson = """{"kwMs":1}""", degradedReason = null, createdAt = 702),
        )
        db.appKvDao().put(AppKvEntity("marker", "keep", 703))

        return Seed(chunkIds = chunkIds, eventId = eventId, entityId = entityId)
    }

    private fun sagVector(ownerType: String, ownerId: Long, role: String) = SagVectorEntity(
        ownerType = ownerType,
        ownerId = ownerId,
        role = role,
        spaceId = supportedSpace,
        dimensions = 2,
        vectorBlob = blob,
        vectorNorm = 1.0,
        createdAt = 800,
    )

    private fun extractionJob(
        id: String,
        documentId: String,
        chunkId: Long,
        status: ExtractionJobStatus,
        createdAt: Long,
    ) = ExtractionJobEntity(
        id = id,
        documentId = documentId,
        chunkId = chunkId,
        status = status.value,
        attempt = 0,
        errorCode = null,
        errorDetail = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    // ---- 工具 ----

    private data class Counts(
        val sources: Int,
        val documents: Int,
        val chunks: Int,
        val embeddings: Int,
        val metas: Int,
        val events: Int,
        val entities: Int,
        val eventEntities: Int,
        val entityAliases: Int,
        val sagVectors: Int,
        val jobs: Int,
        val chatConversations: Int,
        val chatMessages: Int,
        val traces: Int,
        val marker: String?,
    )

    private suspend fun counts() = Counts(
        sources = db.sourceDao().all().size,
        documents = db.documentDao().all().size,
        chunks = db.chunkDao().count(),
        embeddings = db.embeddingDao().count(),
        metas = db.embeddingDao().allMeta().size,
        events = db.eventDao().count(),
        entities = db.entityDao().count(),
        eventEntities = db.eventEntityDao().count(),
        entityAliases = db.entityAliasDao().count(),
        sagVectors = db.sagVectorDao().count(),
        jobs = db.extractionJobDao().all().size,
        chatConversations = db.chatDao().recentConversations(100).size,
        chatMessages = db.chatDao().messages("conv1").size,
        traces = db.queryTraceDao().count(),
        marker = db.appKvDao().get("marker"),
    )

    private fun readZip(file: File): Map<String, ByteArray> {
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

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun tmp(name: String): File =
        File(context.cacheDir, name).also { it.delete(); tmpFiles += it }
}
