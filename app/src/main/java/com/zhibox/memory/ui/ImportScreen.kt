package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.IngestJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * P1-W1 导入管线最小界面（W2 将按五屏设计重构，此界面保证 W1 验收可操作）：
 * 暂存目录扫描 → 全部导入 → 任务状态实时列表 → 完成后导出状态 JSON（adb 验收用）。
 *
 * 无头入口：adb shell am start -n com.zhibox.memory/.MainActivity --ez autostart_import true
 * 语料投放：adb push files → /data/local/tmp → run-as cp 至 files/import-staging/
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    autoStart: Boolean = false,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(context) }
    val stagingDir = remember { File(context.filesDir, "import-staging") }
    var stagingCount by remember { mutableStateOf(-1) }
    var jobs by remember { mutableStateOf(listOf<IngestJobEntity>()) }
    var stats by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }

    fun refreshStaging() {
        stagingCount = stagingDir.listFiles {
            it.isFile && (it.extension.equals("md", true) || it.extension.equals("txt", true))
        }?.size ?: 0
    }

    fun exportStatus(jobList: List<IngestJobEntity>) {
        scope.launch {
            // W1 冒烟的可检索证据：固定查询走 bigram MATCH
            val smokeHits = runCatching {
                db.chunkDao().searchFts(
                    com.zhibox.memory.core.text.BigramTokenizer.toMatchQuery("向量检索"), 20,
                ).size
            }.getOrDefault(-1)
            val json = JSONObject()
                .put("ready_documents", db.documentDao().readyCount())
                .put("chunks", db.chunkDao().count())
                .put("fts_rows", runCatching { db.chunkDao().ftsCount() }.getOrDefault(-1))
                .put("embeddings", db.embeddingDao().count())
                .put("smoke_fts_hits", smokeHits)
                .put("jobs", org.json.JSONArray().apply {
                    jobList.forEach {
                        put(JSONObject().put("id", it.id).put("stage", it.stage)
                            .put("progress", it.progress).put("error", it.error ?: JSONObject.NULL))
                    }
                })
            withContext(Dispatchers.IO) {
                File(context.filesDir, "import_status.json").writeText(json.toString(2))
            }
        }
    }

    fun importAll() {
        if (importing) return
        importing = true
        scope.launch {
            IngestRepository(context).importStaged()
            importing = false
            refreshStaging()
        }
    }

    LaunchedEffect(Unit) {
        refreshStaging()
        db.ingestJobDao().observeAll().collectLatest { list ->
            jobs = list
            exportStatus(list)
        }
    }

    LaunchedEffect(autoStart) {
        if (autoStart && stagingCount == -1) {
            // 等 staging 扫描完成后自动触发一次
            kotlinx.coroutines.delay(300)
            refreshStaging()
        }
    }

    LaunchedEffect(autoStart, stagingCount) {
        if (autoStart && stagingCount > 0 && jobs.isEmpty()) importAll()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入管线 · P1-W1") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (stagingCount > 0) "暂存待导入：$stagingCount 个文件"
                        else if (stagingCount == 0) "暂存目录为空（files/import-staging/）"
                        else "扫描中…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { importAll() }, enabled = !importing && stagingCount > 0) {
                        Text(if (importing) "导入中…" else "全部导入")
                    }
                    if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(jobs) { job ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${job.id} · ${job.documentId ?: "-"}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "阶段 ${job.stage} · ${job.progress}%",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (job.stage !in setOf("done", "failed")) {
                                LinearProgressIndicator(
                                    progress = { job.progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (job.error != null) {
                                Text("错误: ${job.error}", color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
