package com.zhibox.memory.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B8 备份 v3 纯 JVM 契约（不依赖 Android/Room）：
 * - 格式版本门（1–3 可读，4+ 拒绝）与 KNOWN_ENTRIES=12（v2 的 7 个 + v3 的 5 个）；
 * - `sag_vectors.jsonl` 的 base64 编解码（往返/长度不符/非法字符/空）；
 * - `BackupClosure.validateImportClosure` 的 v3 引用闭包：event→chunk、entity normalizedName 唯一、
 *   event (chunkId, extractionVersion) 唯一、event_entity 双端、sag 多态 owner、extraction_job 双 FK；
 *   **v2 空参调用必须向后兼容通过**；
 * - `BackupExportClosure` 的 v3 闭包：event 随 chunk、entity 随被引用 event_entity、sag owner 按
 *   EVENT/ENTITY 区分，未知 ownerType 一律不导出。
 */
class BackupV3ContractTest {

    // ---- 格式版本 / entry 名单 ----

    @Test
    fun formatVersionGateAcceptsV1ToV3AndRejectsNewer() {
        BackupValidator.checkFormatVersion(1)
        BackupValidator.checkFormatVersion(2)
        BackupValidator.checkFormatVersion(3)
        assertTrue(
            assertThrows(IllegalStateException::class.java) { BackupValidator.checkFormatVersion(4) }
                .message!!.contains("不受支持"),
        )
        assertTrue(
            assertThrows(IllegalStateException::class.java) { BackupValidator.checkFormatVersion(0) }
                .message!!.contains("不受支持"),
        )
    }

    @Test
    fun knownEntriesHasTwelveIncludingV3Set() {
        val v3Entries = setOf(
            BackupValidator.ENTRY_EVENTS,
            BackupValidator.ENTRY_ENTITIES,
            BackupValidator.ENTRY_EVENT_ENTITIES,
            BackupValidator.ENTRY_SAG_VECTORS,
            BackupValidator.ENTRY_EXTRACTION_JOBS,
        )
        assertEquals("v2 的 7 个 entry + v3 的 5 个", 12, BackupValidator.KNOWN_ENTRIES.size)
        v3Entries.forEach { entry ->
            assertTrue("KNOWN_ENTRIES 缺少 v3 entry：$entry", entry in BackupValidator.KNOWN_ENTRIES)
        }
        listOf(
            BackupValidator.ENTRY_META,
            BackupValidator.ENTRY_REQUIREMENTS,
            BackupValidator.ENTRY_SOURCES,
            BackupValidator.ENTRY_DOCUMENTS,
            BackupValidator.ENTRY_CHUNKS,
            BackupValidator.ENTRY_APP_KV,
            BackupValidator.ENTRY_VECTORS,
        ).forEach { entry ->
            assertTrue("KNOWN_ENTRIES 缺少 v2 entry：$entry", entry in BackupValidator.KNOWN_ENTRIES)
        }
    }

    // ---- SAG 向量 base64 编解码 ----

    @Test
    fun blobBase64RoundTrip() {
        val blob = ByteArray(12) { (it * 7).toByte() }
        val encoded = BackupValidator.encodeBlobBase64(blob)
        assertTrue(encoded.isNotBlank())
        assertTrue(BackupValidator.decodeBlobBase64(encoded, blob.size, "test").contentEquals(blob))
    }

    @Test
    fun blobBase64RejectsWrongLength() {
        val encoded = BackupValidator.encodeBlobBase64(ByteArray(8) { it.toByte() })
        val error = assertThrows(IllegalStateException::class.java) {
            BackupValidator.decodeBlobBase64(encoded, 16, "sag_vectors.jsonl 第 1 行")
        }
        assertTrue(error.message!!, error.message!!.contains("长度"))
        assertTrue(error.message!!, error.message!!.contains("16B"))
    }

    @Test
    fun blobBase64RejectsInvalidText() {
        val error = assertThrows(IllegalStateException::class.java) {
            BackupValidator.decodeBlobBase64("@@not-base64@@", 8, "sag_vectors.jsonl 第 2 行")
        }
        assertTrue(error.message!!, error.message!!.contains("base64"))
        assertTrue(error.message!!, error.message!!.contains("第 2 行"))
    }

    @Test
    fun blobBase64RejectsEmpty() {
        val error = assertThrows(IllegalStateException::class.java) {
            BackupValidator.decodeBlobBase64("", 8, "sag_vectors.jsonl 第 3 行")
        }
        assertTrue(error.message!!, error.message!!.contains("为空"))
    }

    // ---- 导入引用闭包（v3 与 v2 兼容） ----

    private val sources = setOf("s1")
    private val documents = listOf(BackupClosure.DocumentRef("d1", "s1"))
    private val chunks = listOf(BackupClosure.ChunkRef(1L, "d1"))
    private val events = listOf(BackupClosure.EventRef(10L, 1L, "uuid-1"))
    private val entities = listOf(BackupClosure.EntityRef(20L, "张三"))
    private val eventEntities = listOf(BackupClosure.EventEntityRef(10L, 20L))
    private val sagVectors = listOf(
        BackupClosure.SagVectorRef("EVENT", 10L),
        BackupClosure.SagVectorRef("ENTITY", 20L),
    )
    private val extractionJobs = listOf(BackupClosure.ExtractionJobRef("job-1", "d1", 1L))

    private fun validate(
        docs: List<BackupClosure.DocumentRef> = documents,
        chunkList: List<BackupClosure.ChunkRef> = chunks,
        eventList: List<BackupClosure.EventRef> = events,
        entityList: List<BackupClosure.EntityRef> = entities,
        eventEntityList: List<BackupClosure.EventEntityRef> = eventEntities,
        sagVectorList: List<BackupClosure.SagVectorRef> = sagVectors,
        jobList: List<BackupClosure.ExtractionJobRef> = extractionJobs,
    ) = BackupClosure.validateImportClosure(
        sourceIds = sources,
        documents = docs,
        chunks = chunkList,
        events = eventList,
        entities = entityList,
        eventEntities = eventEntityList,
        sagVectors = sagVectorList,
        extractionJobs = jobList,
    )

    @Test
    fun compliantV3ClosurePasses() {
        validate()
    }

    @Test
    fun v2EmptySagArgumentsStayBackwardCompatible() {
        BackupClosure.validateImportClosure(
            sourceIds = sources,
            documents = documents,
            chunks = chunks,
        )
    }

    @Test
    fun documentMissingSourceAndChunkMissingDocumentAreRejected() {
        val sourceError = assertThrows(IllegalStateException::class.java) {
            validate(docs = listOf(BackupClosure.DocumentRef("d1", "missing-src")))
        }
        assertTrue(sourceError.message!!, sourceError.message!!.contains("不存在的 source"))

        val docError = assertThrows(IllegalStateException::class.java) {
            validate(chunkList = listOf(BackupClosure.ChunkRef(1L, "missing-doc")))
        }
        assertTrue(docError.message!!, docError.message!!.contains("不存在的 document"))
    }

    @Test
    fun eventMissingChunkIsRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            validate(eventList = listOf(BackupClosure.EventRef(10L, 999L, "uuid-1")))
        }
        assertTrue(error.message!!, error.message!!.contains("events.jsonl"))
        assertTrue(error.message!!, error.message!!.contains("不存在的 chunk"))
    }

    @Test
    fun duplicateEventIdAndVersionPairAreRejected() {
        val duplicateId = assertThrows(IllegalStateException::class.java) {
            validate(eventList = listOf(BackupClosure.EventRef(10L, 1L, "a"), BackupClosure.EventRef(10L, 1L, "b")))
        }
        assertTrue(duplicateId.message!!, duplicateId.message!!.contains("event id 重复"))

        val duplicatePair = assertThrows(IllegalStateException::class.java) {
            validate(
                eventList = listOf(
                    BackupClosure.EventRef(10L, 1L, "same-uuid"),
                    BackupClosure.EventRef(11L, 1L, "same-uuid"),
                ),
            )
        }
        assertTrue(duplicatePair.message!!, duplicatePair.message!!.contains("重复 (chunkId, extractionVersion)"))
    }

    @Test
    fun duplicateEntityIdOrNormalizedNameIsRejected() {
        val duplicateId = assertThrows(IllegalStateException::class.java) {
            validate(
                entityList = listOf(
                    BackupClosure.EntityRef(20L, "张三"),
                    BackupClosure.EntityRef(20L, "李四"),
                ),
            )
        }
        assertTrue(duplicateId.message!!, duplicateId.message!!.contains("entity id 重复"))

        val duplicateName = assertThrows(IllegalStateException::class.java) {
            validate(
                entityList = listOf(
                    BackupClosure.EntityRef(20L, "张三"),
                    BackupClosure.EntityRef(21L, "张三"),
                ),
            )
        }
        assertTrue(duplicateName.message!!, duplicateName.message!!.contains("normalizedName 重复"))
    }

    @Test
    fun eventEntityWithUnknownEndIsRejected() {
        val missingEvent = assertThrows(IllegalStateException::class.java) {
            validate(eventEntityList = listOf(BackupClosure.EventEntityRef(999L, 20L)))
        }
        assertTrue(missingEvent.message!!, missingEvent.message!!.contains("不存在的 event"))

        val missingEntity = assertThrows(IllegalStateException::class.java) {
            validate(eventEntityList = listOf(BackupClosure.EventEntityRef(10L, 999L)))
        }
        assertTrue(missingEntity.message!!, missingEntity.message!!.contains("不存在的 entity"))
    }

    @Test
    fun duplicateEventEntityPairIsRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            validate(
                eventEntityList = listOf(
                    BackupClosure.EventEntityRef(10L, 20L),
                    BackupClosure.EventEntityRef(10L, 20L),
                ),
            )
        }
        assertTrue(error.message!!, error.message!!.contains("重复 (eventId, entityId)"))
    }

    @Test
    fun sagVectorOwnerMustExistAndTypeMustBeKnown() {
        val missingOwner = assertThrows(IllegalStateException::class.java) {
            validate(sagVectorList = listOf(BackupClosure.SagVectorRef("EVENT", 999L)))
        }
        assertTrue(missingOwner.message!!, missingOwner.message!!.contains("不存在的 event owner"))

        val unknownType = assertThrows(IllegalStateException::class.java) {
            validate(sagVectorList = listOf(BackupClosure.SagVectorRef("BOGUS", 10L)))
        }
        assertTrue(unknownType.message!!, unknownType.message!!.contains("ownerType 未知"))
    }

    @Test
    fun duplicateSagQuadrupletIsRejectedButDifferentRoleOrSpaceIsAllowed() {
        val duplicate = assertThrows(IllegalStateException::class.java) {
            validate(
                sagVectorList = listOf(
                    BackupClosure.SagVectorRef("EVENT", 10L, role = "event_content", spaceId = "space-a"),
                    BackupClosure.SagVectorRef("EVENT", 10L, role = "event_content", spaceId = "space-a"),
                ),
            )
        }
        assertTrue(duplicate.message!!, duplicate.message!!.contains("重复 (ownerType, ownerId, role, spaceId)"))

        // 不同 role 或不同 space 属于合法多向量（v7 唯一键区分）
        validate(
            sagVectorList = listOf(
                BackupClosure.SagVectorRef("EVENT", 10L, role = "event_content", spaceId = "space-a"),
                BackupClosure.SagVectorRef("EVENT", 10L, role = "event_summary", spaceId = "space-a"),
                BackupClosure.SagVectorRef("EVENT", 10L, role = "event_content", spaceId = "space-b"),
            ),
        )
    }

    @Test
    fun extractionJobWithMissingParentIsRejected() {
        val missingChunk = assertThrows(IllegalStateException::class.java) {
            validate(jobList = listOf(BackupClosure.ExtractionJobRef("job-9", "d1", 999L)))
        }
        assertTrue(missingChunk.message!!, missingChunk.message!!.contains("不存在的 chunk"))

        val missingDocument = assertThrows(IllegalStateException::class.java) {
            validate(jobList = listOf(BackupClosure.ExtractionJobRef("job-9", "missing-doc", 1L)))
        }
        assertTrue(missingDocument.message!!, missingDocument.message!!.contains("不存在的 document"))

        val duplicateJob = assertThrows(IllegalStateException::class.java) {
            validate(
                jobList = listOf(
                    BackupClosure.ExtractionJobRef("job-1", "d1", 1L),
                    BackupClosure.ExtractionJobRef("job-1", "d1", 1L),
                ),
            )
        }
        assertTrue(duplicateJob.message!!, duplicateJob.message!!.contains("id 非法或重复"))
    }

    @Test
    fun sameChunkWithMultipleExtractionJobsIsRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            validate(
                jobList = listOf(
                    BackupClosure.ExtractionJobRef("job-1", "d1", 1L),
                    BackupClosure.ExtractionJobRef("job-2", "d1", 1L),
                ),
            )
        }
        assertTrue(error.message!!, error.message!!.contains("同一 chunk 存在多条 job"))
    }

    // ---- 导出闭包（v3） ----

    @Test
    fun exportClosureEventFollowsChunkAndEntityFollowsEventEntity() {
        val closure = BackupExportClosure()
        assertTrue(closure.acceptDocument("doc-1", "src-1", "ready"))
        assertTrue(closure.acceptChunk(1L, "doc-1"))

        assertFalse("父 chunk 未进包时 event 不得进包", closure.acceptEvent(10L, 2L))
        assertTrue(closure.acceptEvent(10L, 1L))
        assertTrue("event 已在包内", closure.includesEvent(10L))
        assertFalse(closure.includesEvent(11L))

        assertFalse("event 未进包时 event_entity 不得进包", closure.acceptEventEntity(11L, 20L))
        assertTrue(closure.acceptEventEntity(10L, 20L))
        assertTrue(closure.includesEntity(20L))
        assertEquals(setOf(10L), closure.eventIds())
        assertEquals(setOf(20L), closure.entityIds())
    }

    @Test
    fun exportClosureSagOwnerDependsOnOwnerType() {
        val closure = BackupExportClosure()
        closure.acceptDocument("doc-1", "src-1", "ready")
        closure.acceptChunk(1L, "doc-1")
        closure.acceptEvent(10L, 1L)
        closure.acceptEventEntity(10L, 20L)

        assertTrue(closure.includesSagOwner("EVENT", 10L))
        assertTrue(closure.includesSagOwner("ENTITY", 20L))
        assertFalse("未知 entity owner", closure.includesSagOwner("ENTITY", 10L))
        assertFalse("未知 event owner", closure.includesSagOwner("EVENT", 20L))
        assertFalse("未知 ownerType 一律不导出", closure.includesSagOwner("CHUNK", 10L))
    }

    @Test
    fun exportClosureExcludesNonReadyDocumentsAndTheirChildren() {
        val closure = BackupExportClosure()
        assertFalse(closure.acceptDocument("doc-failed", "src-1", "failed"))
        assertFalse(closure.acceptChunk(5L, "doc-failed"))
        assertFalse(closure.acceptEvent(50L, 5L))
        assertEquals(emptySet<String>(), closure.documentIds())
        assertEquals(emptySet<Long>(), closure.chunkIds())
        assertEquals(emptySet<Long>(), closure.eventIds())
    }
}
