package com.zhibox.memory.core.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.embedding.EmbeddingDescriptor
import com.zhibox.memory.core.embedding.EmbeddingProvider
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-B1-12 / B7：EGemma2 旧空间 id **DB 采纳分支**（instrumentation）。
 *
 * 纯函数矩阵见 `LegacySpaceAdoptionMatrixTest`（test 源集）；本测试补上只有查库才能覆盖的
 * `EmbeddingRuntime.resolveSpaceId` 决策：
 * 1. 升级前旧行（无 @revision 后缀）只要存在 → 采纳旧 id（不因新规则"消失"）；
 * 2. 首次 registerSpace 回填 revision 后，同一文件继续用旧 id；
 * 3. 同名换文件（meta.modelRevision 与新文件 revision 不同）→ 才生成 `@rev` 新空间；
 * 4. 无存量行 → 新空间 id 带 @revision。
 *
 * B7：仅覆盖 EGemma2（440M GPU/NPU、270M、740M）；Granite/Path C/LFM 空间已下线，
 * 由 `legacySpacesReport()` 报告（见 LegacySpacesReportDbTest），不再参与采纳矩阵。
 *
 * 设备风险说明：使用应用真实数据库（与 BackupRoundTripTest 相同口径），@Before/@After 清库，
 * 不与其它测试并行执行。
 */
class LegacySpaceAdoptionDbTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private fun descriptor(
        providerId: String,
        modelId: String,
        accelerator: String = "cpu",
        revision: String? = null,
    ) = EmbeddingDescriptor(
        providerId = providerId,
        modelId = modelId,
        modelRevision = revision,
        accelerator = accelerator,
    )

    private fun provider(d: EmbeddingDescriptor) = object : EmbeddingProvider {
        override val descriptor: EmbeddingDescriptor = d
        override suspend fun embedDocuments(texts: List<String>): List<FloatArray> = emptyList()
        override suspend fun embedQuery(text: String): FloatArray = FloatArray(0)
        override fun close() {}
    }

    private fun oldFormatId(d: EmbeddingDescriptor): String =
        EmbeddingRuntime.spaceIdResolved(d.copy(modelRevision = null), knownRevision = null)

    private suspend fun seedOldMeta(descriptor: EmbeddingDescriptor) {
        val oldId = oldFormatId(descriptor)
        db.embeddingDao().upsertMeta(
            EmbeddingMetaEntity(
                id = oldId,
                providerId = descriptor.providerId,
                modelId = descriptor.modelId,
                modelRevision = null, // 升级前旧行：revision 尚未回填
                dimensions = 2,
                normalization = "l2",
                taskTemplate = "raw-symmetric",
                createdAt = 1,
                active = true,
                accelerator = descriptor.accelerator,
                quantization = "f32",
            ),
        )
        assertEquals("旧行种子未生效", oldId, db.embeddingDao().meta(oldId)?.id)
    }

    /** 模拟 registerSpace 首次回填 modelRevision（保留 createdAt）。 */
    private suspend fun backfillRevision(oldId: String, revision: String?) {
        val meta = db.embeddingDao().meta(oldId) ?: error("meta 不存在：$oldId")
        db.embeddingDao().upsertMeta(meta.copy(modelRevision = revision))
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
    }

    @Test
    fun `四类_EGemma2_旧格式空间均被采纳_换文件才产生新空间`() = runBlocking {
        val newRevision = "999999999999"
        val cases = listOf(
            "GPU-440M" to descriptor(UniversalProvider, M440, accelerator = "gpu", revision = "111111111111"),
            "NPU-440M" to descriptor(UniversalProvider, M440, accelerator = "npu", revision = "111111111111"),
            "270M" to descriptor(LiteRtLmProvider, M270, revision = "111111111111"),
            "740M" to descriptor(UniversalProvider, M740, revision = "111111111111"),
        )
        for ((label, d) in cases) {
            val oldId = oldFormatId(d)
            seedOldMeta(d)

            // 1) 旧行在 → 采纳旧 id（即便描述符已带 revision）
            assertEquals("$label：旧格式空间必须被采纳", oldId, EmbeddingRuntime.resolveSpaceId(context, provider(d)))

            // 2) registerSpace 回填 revision 后，同一文件仍用旧 id
            backfillRevision(oldId, d.modelRevision)
            assertEquals("$label：同一文件不得新建空间", oldId, EmbeddingRuntime.resolveSpaceId(context, provider(d)))

            // 3) 同名换文件 → 才产生 @rev 新空间
            val swapped = EmbeddingRuntime.resolveSpaceId(context, provider(d.copy(modelRevision = newRevision)))
            assertNotEquals("$label：换文件必须切新空间", oldId, swapped)
            assertTrue("$label：新空间应带 @revision（实际 $swapped）", swapped.endsWith("@$newRevision"))
            assertTrue("$label：新空间应以旧 id 为前缀", swapped.startsWith(oldId))

            db.clearAllTables()
        }
    }

    @Test
    fun `无存量行时新空间id带revision`() = runBlocking {
        val d = descriptor(UniversalProvider, M440, accelerator = "gpu", revision = "abcdef123456")
        assertNull("前置：库应为空", db.embeddingDao().meta(oldFormatId(d)))
        val resolved = EmbeddingRuntime.resolveSpaceId(context, provider(d))
        assertEquals(EmbeddingRuntime.spaceIdResolved(d, knownRevision = null), resolved)
        assertTrue(resolved.endsWith("@abcdef123456"))
        assertTrue(resolved.contains("-gpu@"))
    }

    @Test
    fun `440M通用CPU兼容分支_旧id采纳与换文件退化`() = runBlocking {
        val rev = "111111111111"
        val newRev = "222222222222"
        // 440M 通用 CPU：旧 id = SPACE_ID（B7 唯一保留的存量兼容空间）
        val egemma = descriptor(UniversalProvider, M440, revision = rev)
        db.embeddingDao().upsertMeta(
            EmbeddingMetaEntity(
                id = IngestRepository.SPACE_ID, providerId = egemma.providerId,
                modelId = egemma.modelId, modelRevision = null, dimensions = 2, normalization = "l2",
                taskTemplate = "app-prefix-v1", createdAt = 1, active = true,
                accelerator = "cpu", quantization = "f32",
            ),
        )
        assertEquals(IngestRepository.SPACE_ID, EmbeddingRuntime.resolveSpaceId(context, provider(egemma)))
        backfillRevision(IngestRepository.SPACE_ID, rev)
        assertEquals(
            "同文件必须继续用兼容空间",
            IngestRepository.SPACE_ID,
            EmbeddingRuntime.resolveSpaceId(context, provider(egemma)),
        )
        val egemmaSwapped =
            EmbeddingRuntime.resolveSpaceId(context, provider(egemma.copy(modelRevision = newRev)))
        assertNotEquals(IngestRepository.SPACE_ID, egemmaSwapped)
        assertTrue(egemmaSwapped.endsWith("@$newRev"))
    }

    @Test
    fun `270M_与_440M_通用_CPU_是两个不同空间`() = runBlocking {
        // 270M（litertlm 引擎）不是 legacy：无存量行时解析为带 revision 的新空间，不得落到 SPACE_ID
        val d270 = descriptor(LiteRtLmProvider, M270, revision = "abcdef123456")
        val resolved = EmbeddingRuntime.resolveSpaceId(context, provider(d270))
        assertTrue(resolved.contains("embeddinggemma-2-text-270m"))
        assertTrue(resolved.endsWith("@abcdef123456"))
        assertNotEquals(IngestRepository.SPACE_ID, resolved)
        assertNull(db.embeddingDao().meta(IngestRepository.SPACE_ID))
    }

    private companion object {
        const val UniversalProvider = "mediapipe-universal"
        const val LiteRtLmProvider = "litertlm-engine" // 270M 走 litertlm EmbeddingEngine（路径 B）
        const val M440 = "embeddinggemma-2-text-vision-440m"
        const val M270 = "embeddinggemma-2-text-270m"
        const val M740 = "embeddinggemma-2-740m"
    }
}
