package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.backup.KnowledgeBackup
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.ingest.reembedSyncAll
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * W4+ 设计预留验证（设备回环，全自动；B7 适配）：
 * 1. 自动重嵌入：440M 空间导入 → 切 B·EGemma2-270M（litertlm 引擎）→ reembedSyncAll →
 *    新空间出现等量向量 → 在新空间检索命中（跨模型切换语义）；
 * 2. 导出→导入回环：exportToFile → 清库 → importFromFile → chunk/向量/检索恢复一致。
 *
 * B7：Granite/LFM 与 Path C（.task）已下线；原"切 granite（偏好 B）"步骤中的模型改为
 * EGemma2-270M（偏好 B 现指向 270M），不再引用 SPACE_ID_B/granite 常量。
 * 前置：filesDir/models 有 EGemma2-440M 与 EGemma2-270M；filesDir/import-staging 有 3 篇语料。
 * 自动化：am start --ez autostart_w4plus true
 */
object W4PlusRunner {

    /** 重嵌入目标模型名提示（偏好 B = EGemma2-270M，catalog 收敛后的唯一 litertlm 文本模型） */
    private const val TARGET_MODEL_HINT = "270m"

    suspend fun run(context: Context): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val db = AppDatabase.get(appContext)
        val repo = IngestRepository(appContext)
        val search = SearchService(appContext)
        val log = JSONArray()

        suspend fun step(name: String, obj: suspend JSONObject.() -> Unit) {
            val o = JSONObject()
            o.put("step", name)
            o.obj()
            log.put(o)
        }

        // ---- 1. 导入 3 篇（440M 默认空间）----
        // 前置重置：上轮偏好残留会使导入直写非预期空间（实测踩中），幂等测试前提
        com.zhibox.memory.core.model.PathPreferences.set(
            appContext, com.zhibox.memory.core.model.PathPreferences.AUTO,
        )
        com.zhibox.memory.core.embedding.EmbeddingRuntime.shutdown()
        step("import_440m") {
            repo.importStaged()
            var n = 0
            repeat(40) {
                kotlinx.coroutines.delay(2000)
                n = db.documentDao().readyCount(DocStatus.READY.value)
                if (db.ingestJobDao().activeCount(JobStage.ACTIVE_VALUES) == 0) return@repeat
            }
            put("ready", n)
        }

        // ---- 2. 切 B（EGemma2-270M，litertlm 引擎）→ 自动重嵌入 ----
        step("switch_270m_reembed") {
            setPreferenceB(context)
            val (done, total) = reembedSyncAll(appContext)
            // B7：不再硬编码 SPACE_ID_B，按 embedding_meta.modelId 动态定位新空间向量数
            val targetVectors = db.embeddingDao().allMeta()
                .filter { it.modelId.contains(TARGET_MODEL_HINT, ignoreCase = true) }
                .sumOf { db.embeddingDao().countBySpace(it.id) }
            put("reembedded", done).put("total", total).put("target_vectors", targetVectors)
        }

        // ---- 3. 新空间检索验证（切换后无缝可搜）----
        step("search_270m") {
            val r = search.search("导入取消场景验证", topK = 3)
            put("hits", r.hits.size).put("space", r.spaceId)
                .put("refused", r.refused ?: "")
                .put("top1", r.hits.firstOrNull()?.docTitle ?: "")
        }

        // ---- 4. 导出 → 清库 → 导入回环 ----
        val backup = File(appContext.filesDir, "w4plus_backup.zip")
        step("export") {
            val r = KnowledgeBackup.exportToFile(appContext, backup)
            put("ok", r.isSuccess).put("bytes", backup.length())
        }
        step("wipe") {
            db.clearAllTables()
            put("chunks", db.chunkDao().count())
        }
        step("import") {
            val r = KnowledgeBackup.importFromFile(appContext, backup, dropIncompatibleGpuVectors = false)
            put("ok", r.isSuccess).put("error", r.exceptionOrNull()?.message ?: "")
                .put("chunks", db.chunkDao().count())
                .put("vectors", db.embeddingDao().count())
        }
        step("search_after_import") {
            val r = search.search("导入取消场景验证", topK = 3)
            put("hits", r.hits.size).put("space", r.spaceId)
        }

        // ---- 汇总判定 ----
        val steps = log
        fun stepObj(name: String): JSONObject {
            for (i in 0 until steps.length()) {
                if (steps.getJSONObject(i).getString("step") == name) return steps.getJSONObject(i)
            }
            error("step $name not found")
        }
        val targetVectors = stepObj("switch_270m_reembed").getInt("target_vectors")
        val searchTarget = stepObj("search_270m").getInt("hits")
        val reembedOk = targetVectors >= 3 && searchTarget > 0
        val roundtripOk = stepObj("import").getInt("chunks") > 0 &&
            stepObj("import").getInt("vectors") >= 6 &&
            stepObj("search_after_import").getInt("hits") > 0

        // zip 保留供主机侧诊断（OOM/结构问题时拉取分析）
        val out = JSONObject()
            .put("experiment", "w4plus_design_reserve")
            .put("reembed", JSONObject().put("pass", reembedOk).put("target_vectors", targetVectors))
            .put("roundtrip", JSONObject().put("pass", roundtripOk))
            .put("steps", log)
            .put("pass", reembedOk && roundtripOk)

        val dir = File(appContext.filesDir, "probe_results").apply { mkdirs() }
        val outFile = File(dir, "w4plus_${System.currentTimeMillis()}.json")
        outFile.writeText(out.toString(2))
        outFile
    }

    /** 偏好 B（B7 后 = EGemma2-270M，litertlm 引擎）设置（无 UI 依赖的自动化路径） */
    private suspend fun setPreferenceB(context: Context) {
        com.zhibox.memory.core.model.PathPreferences.set(
            context, com.zhibox.memory.core.model.PathPreferences.B,
        )
        // 引擎缓存失效：EmbeddingRuntime 按 modelPath key 缓存，偏好变化经 findModel 反映
        com.zhibox.memory.core.embedding.EmbeddingRuntime.shutdown()
    }
}
