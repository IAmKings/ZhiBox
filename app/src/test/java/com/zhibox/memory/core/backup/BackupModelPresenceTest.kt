package com.zhibox.memory.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 备份"模型在位判定"纯逻辑单测（P1-7 ③）。
 *
 * 旧实现固定查 `filesDir/models/$file`，两个 false-negative：
 * 1. 共享模型仓"启用"后模型在 `cacheDir/models-link` → 明明可用却判 missing → 合法备份无法导入；
 * 2. 未收录进 catalog 的老模型（catalog_file=null）→ 两个条件都不满足 → 永久 blocked 且无可下载项。
 *
 * B7：模型目录收敛为 EGemma2（440M/270M/740M + SM8750 NPU），测试 fixture 同步替换下线的
 * Granite/`.task` 文件名；判定逻辑本身（candidates 名字匹配 / catalog_file=null 时按 modelId
 * 去后缀回退）保持不变。
 */
class BackupModelPresenceTest {

    private val modelsLink = File("/cache/models-link")
    private val filesDirModels = File("/files/models")

    private val model270 = "embeddinggemma-2-text-270m.litertlm"
    private val model740 = "embeddinggemma-2-740m.litertlm"

    @Test
    fun `候选路径同时覆盖正式仓与共享仓运行缓存`() {
        val candidates = listOf(
            File(filesDirModels, model740),
            File(modelsLink, "embeddinggemma-2-text-vision-440m.litertlm"),
        )
        val found = BackupModelPresence.find(
            "embeddinggemma-2-text-vision-440m.litertlm",
            "embeddinggemma-2-text-vision-440m",
            candidates,
        )
        // 关键回归：文件只在 models-link 也必须判定在位
        assertEquals(File(modelsLink, "embeddinggemma-2-text-vision-440m.litertlm"), found)
    }

    @Test
    fun `catalog_file 为 null 时按 modelId 去后缀回退匹配`() {
        val candidates = listOf(
            File(filesDirModels, "user-dropped-variant.litertlm"),
            File(filesDirModels, model270),
        )
        assertEquals(
            File(filesDirModels, model270),
            BackupModelPresence.find(null, "embeddinggemma-2-text-270m", candidates),
        )
        assertEquals(
            File(filesDirModels, "user-dropped-variant.litertlm"),
            BackupModelPresence.find(null, "user-dropped-variant", candidates),
        )
    }

    @Test
    fun `已下线模型仍可按 modelId 回退定位（导入时由空间校验拒绝）`() {
        // B7 后 Granite/.task 不在 catalog（catalog_file=null），但备份模型在位判定仍应能
        // 通过名字回退找到文件；"是否支持"由备份空间校验（isSupportedSpace）负责拒绝，职责分离。
        val candidates = listOf(
            File(filesDirModels, "granite-embedding-311m-r2_wi8fc.litertlm"),
            File(filesDirModels, "embedding_gemma.task"),
        )
        assertEquals(
            File(filesDirModels, "granite-embedding-311m-r2_wi8fc.litertlm"),
            BackupModelPresence.find(null, "granite-embedding-311m-r2_wi8fc", candidates),
        )
        assertEquals(
            File(filesDirModels, "embedding_gemma.task"),
            BackupModelPresence.find(null, "embedding_gemma", candidates),
        )
    }

    @Test
    fun `找不到返回 null`() {
        assertNull(BackupModelPresence.find("absent.litertlm", "absent", listOf(File(filesDirModels, model740))))
        assertNull(BackupModelPresence.find(null, "", listOf(File(filesDirModels, model740))))
        assertNull(BackupModelPresence.find(null, "no-such-model", listOf(File(filesDirModels, model740))))
    }
}
