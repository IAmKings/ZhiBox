package com.zhibox.memory.ui

import android.content.Context
import com.zhibox.memory.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R2 复审修复（P2-10）：query_trace 脱敏导出的单一实现。
 * 此前 SearchViewModel 与 SettingsViewModel 各复制约 40 行（JSON 组装 + 落盘），
 * 字段口径易漂移；收敛到本对象后两处仅保留各自的 UI 状态流转。
 *
 * 脱敏口径：只写 id/queryHash/mode/timingJson/degradedReason/createdAt——
 * 不含查询正文与文档内容；文件写应用私有 `filesDir/trace_exports/`（可 adb pull，不参与备份）。
 */
internal object TraceExport {

    /** 导出条数（与 UI 展示口径一致：最近 50 条）。 */
    const val EXPORT_LIMIT = 50

    private const val DIR = "trace_exports"

    /**
     * 导出最近 [EXPORT_LIMIT] 条 trace。
     * @return (导出条数, 文件绝对路径)；库为空时 (0, null)
     */
    suspend fun export(context: Context, db: AppDatabase): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            val rows = db.queryTraceDao().recent(EXPORT_LIMIT)
            if (rows.isEmpty()) return@withContext 0 to null
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("id", row.id)
                        .put("queryHash", row.queryHash)
                        .put("mode", row.mode)
                        .put("timingJson", row.timingJson)
                        .put("degradedReason", row.degradedReason ?: JSONObject.NULL)
                        .put("createdAt", row.createdAt),
                )
            }
            val dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, "query_trace_${System.currentTimeMillis()}.json")
            file.writeText(array.toString(2))
            rows.size to file.absolutePath
        }
}
