package com.zhibox.memory.core.ingest

import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-1 FTS 写入去重 + B1-6 重复导入判定（纯函数单测，无需 Room）。
 */
class FtsAndImportDedupTest {

    private fun ftsRow(chunkId: Long, text: String = "ab") =
        ChunkFtsEntity(chunkId = chunkId, bigramContent = text)

    // ---- B1-1 ----

    @Test
    fun `FTS 行按 chunkId 去重并保留首次出现顺序`() {
        val rows = listOf(
            ftsRow(1, "first"),
            ftsRow(2),
            ftsRow(1, "duplicate"),
            ftsRow(3),
            ftsRow(2, "duplicate-2"),
        )
        val deduped = IngestRepository.dedupeFtsByChunkId(rows)
        assertEquals(listOf(1L, 2L, 3L), deduped.map { it.chunkId })
        assertEquals("first", deduped[0].bigramContent) // 保留首次出现
    }

    @Test
    fun `空列表与单行原样返回`() {
        assertTrue(IngestRepository.dedupeFtsByChunkId(emptyList()).isEmpty())
        val single = listOf(ftsRow(42))
        assertEquals(single, IngestRepository.dedupeFtsByChunkId(single))
    }

    @Test
    fun `防御性去重：同一 chunk 出现两次时只保留一行`() {
        val chunkIds = (1L..5L).toList()
        val firstPass = chunkIds.map { ftsRow(it) }
        val secondPass = chunkIds.map { ftsRow(it) }
        val deduped = IngestRepository.dedupeFtsByChunkId(firstPass + secondPass)
        assertEquals(chunkIds, deduped.map { it.chunkId })
        assertEquals(chunkIds.size, deduped.size) // 幂等：与单次写入行数一致
    }

    // ---- B1-6 ----

    @Test
    fun `pending processing ready 视为重复导入`() {
        assertTrue(IngestRepository.isDuplicateDocumentStatus(DocStatus.PENDING))
        assertTrue(IngestRepository.isDuplicateDocumentStatus(DocStatus.PROCESSING))
        assertTrue(IngestRepository.isDuplicateDocumentStatus(DocStatus.READY))
        assertEquals(
            setOf(DocStatus.PENDING, DocStatus.PROCESSING, DocStatus.READY),
            IngestRepository.DEDUP_DOC_STATUSES,
        )
    }

    @Test
    fun `cancelled failed 与未知状态允许重导`() {
        assertFalse(IngestRepository.isDuplicateDocumentStatus(DocStatus.CANCELLED))
        assertFalse(IngestRepository.isDuplicateDocumentStatus(DocStatus.FAILED))
        assertFalse(IngestRepository.isDuplicateDocumentStatus(null))
    }

    @Test
    fun `ImportResult 携带既有文档 id 且类型可区分`() {
        val enqueued: ImportResult = ImportResult.Enqueued("doc_new")
        val duplicate: ImportResult = ImportResult.Duplicate("doc_existing")
        assertTrue(enqueued is ImportResult.Enqueued)
        assertTrue(duplicate is ImportResult.Duplicate)
        assertEquals("doc_new", (enqueued as ImportResult.Enqueued).documentId)
        assertEquals("doc_existing", (duplicate as ImportResult.Duplicate).existingDocumentId)
    }
}
