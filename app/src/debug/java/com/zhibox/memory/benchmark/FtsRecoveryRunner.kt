package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.core.text.BigramTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * W4-3 索引损坏恢复自证（§9.1-10）：破坏 FTS → 健康检查报警 → 重建 → 恢复 + 关键词检索抽查。
 * 输出：filesDir/probe_results/fts_recovery_<ts>.json
 * 自动化：am start --ez autostart_fts_recovery true
 */
object FtsRecoveryRunner {

    suspend fun run(context: Context): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val db = AppDatabase.get(appContext)
        val repo = IngestRepository(appContext)

        val chunksBefore = db.chunkDao().count()
        // 1. 破坏：清空 FTS（模拟索引损坏）
        db.chunkDao().deleteAllFts()
        val brokenHealth = repo.ftsHealthCheck()
        val brokenSearch = db.chunkDao().searchFtsOrdered(
            BigramTokenizer.toMatchQuery("导入取消"), 5, DocStatus.READY.value,
        )

        // 2. 重建
        val rebuilt = repo.rebuildFtsIndex()
        val recoveredHealth = repo.ftsHealthCheck()

        // 3. 关键词检索抽查：用 chunk 内容里的词验证 FTS 召回恢复
        val sample = db.chunkDao().allChunks().firstOrNull()
        val spotQuery = sample?.content?.take(6) ?: "导入"
        val spotHits = db.chunkDao().searchFtsOrdered(
            BigramTokenizer.toOrMatchQuery(spotQuery), 5, DocStatus.READY.value,
        )

        val pass = brokenHealth != null && recoveredHealth == null &&
            rebuilt == chunksBefore && spotHits.isNotEmpty()

        val out = JSONObject()
            .put("experiment", "fts_recovery")
            .put("chunks_before", chunksBefore)
            .put("after_break", JSONObject()
                .put("health_report", brokenHealth?.let { "chunk=${it.first}/fts=${it.second}" } ?: "healthy(异常：破坏未检出)")
                .put("search_hits", brokenSearch.size))
            .put("after_rebuild", JSONObject()
                .put("rebuilt", rebuilt)
                .put("health_report", recoveredHealth?.let { "chunk=${it.first}/fts=${it.second}" } ?: "healthy")
                .put("spot_query", spotQuery)
                .put("spot_hits", spotHits.size))
            .put("pass", pass)

        val dir = File(appContext.filesDir, "probe_results").apply { mkdirs() }
        val outFile = File(dir, "fts_recovery_${System.currentTimeMillis()}.json")
        outFile.writeText(out.toString(2))
        outFile
    }
}
