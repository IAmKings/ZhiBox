package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.SourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * W3-1 删除级联设备验证（§9.1-5）：删除首个 source → 五表残留检查 + 原文文件清理检查。
 * 输出：filesDir/eval_results/delete_check_<ts>.json
 * 自动化：am start --ez autostart_delete_check true
 */
object DeleteCascadeRunner {

    suspend fun run(context: Context): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val db = AppDatabase.get(appContext)
        val repo = IngestRepository(appContext)

        val source = db.sourceDao().observeAll(SourceStatus.ACTIVE.value).first().firstOrNull()
            ?: throw IllegalStateException("无来源可删（先导入）")
        val sid = source.id
        val docIds = db.documentDao().observeAll().first()
            .filter { it.sourceId == sid }
            .map { it.id }
        val storedUri = source.uri

        val chunksBefore = docIds.sumOf { db.chunkDao().countByDocument(it) }
        val fileExisted = File(storedUri).isFile

        repo.deleteSource(sid)

        var chunkResidual = 0
        for (docId in docIds) chunkResidual += db.chunkDao().countByDocument(docId)
        val docResidual = docIds.count { db.documentDao().get(it) != null }
        val sourceResidual = if (db.sourceDao().get(sid) != null) 1 else 0
        val fileResidual = if (File(storedUri).isFile) 1 else 0
        val embeddingRemaining = db.embeddingDao().count()

        val out = JSONObject()
            .put("experiment", "delete_cascade_check")
            .put("before", JSONObject()
                .put("source", sid)
                .put("docs", docIds.size)
                .put("chunks", chunksBefore)
                .put("stored_file_existed", fileExisted))
            .put("after", JSONObject()
                .put("doc_residual", docResidual)
                .put("chunk_residual", chunkResidual)
                .put("source_residual", sourceResidual)
                .put("file_residual", fileResidual)
                .put("embedding_total_remaining", embeddingRemaining))
            .put("pass", docResidual == 0 && chunkResidual == 0 &&
                sourceResidual == 0 && fileResidual == 0)

        val dir = File(appContext.filesDir, "eval_results").apply { mkdirs() }
        val outFile = File(dir, "delete_check_${System.currentTimeMillis()}.json")
        outFile.writeText(out.toString(2))
        outFile
    }
}
