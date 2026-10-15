package com.zhibox.memory.benchmark

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import com.zhibox.memory.core.embedding.TaskTemplatePolicy
import com.zhibox.memory.core.embedding.UniversalEmbedderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.sqrt

/**
 * G4 判定门（实施计划 P0-6）：精确余弦扫描三实现对照基准。
 *
 * 三种实现：
 *  a) SQLITE_BLOB  —— 逐行 Cursor 读 BLOB（朴素实现基线）
 *  b) PRELOADED    —— 启动时预载 FloatArray 内存扫描
 *  c) MMAP_FLAT    —— 平铺二进制文件 + mmap 只读视图
 *
 * 数据：10,000 × 768d（前 50 条为真实 embedding，其余为固定种子随机单位向量；
 * 扫描性能取决于内存布局与浮点吞吐，与向量语义无关——对计划"真实+随机 8:2"的
 * 务实近似，已在 PHASE0_REPORT 记录）。查询向量 = 一条真实 embedding。
 *
 * 门槛（PRD §9.2）：1k p95 ≤ 150ms；10k p95 ≤ 800ms。
 * 正确性：三种实现（double 累加）Top-10 必须一致。
 */
object VectorScanBenchmark {

    private const val DIM = 768
    private val SIZES = intArrayOf(1000, 5000, 10000)
    private const val TOP_K = 10
    private const val WARMUP = 3
    private const val RUNS = 30

    data class ScanResult(
        val impl: String, val n: Int,
        val coldMs: Double, val p50Ms: Double, val p95Ms: Double,
        val top10: List<Int>,
    )

    suspend fun run(context: Context): File = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        val workDir = File(appContext.filesDir, "scan_bench").apply { mkdirs() }
        val dbFile = File(workDir, "scan.db")
        val flatFile = File(workDir, "vectors.flat")

        // ── 数据准备 ──────────────────────────────────────────
        val n = SIZES.max()
        val vectors = Array(n) { FloatArray(DIM) }
        val modelDir = File(appContext.filesDir, "models")
        // 优先已验证可加载的 440M；270M 等不可加载文件逐个降级尝试，全部失败则用纯随机向量
        val candidates = modelDir.listFiles { f -> f.extension == "litertlm" }
            ?.sortedByDescending { it.name.contains("440m") } ?: emptyList()
        var realCount = 0
        for (candidate in candidates) {
            val provider = runCatching {
                UniversalEmbedderProvider(appContext, candidate, TaskTemplatePolicy.RAW, false)
            }.getOrNull() ?: continue
            try {
                // 50 条真实向量（样本文本循环取用）
                val texts = EmbedderBenchmark.SAMPLE_TEXTS
                for (i in 0 until 50) {
                    vectors[i] = provider.embedQuery(texts[i % texts.size])
                }
                realCount = 50
                break
            } finally {
                provider.close()
            }
        }
        val rnd = ThreadLocalRandom.current()
        for (i in realCount until n) {
            var norm = 0.0
            for (j in 0 until DIM) {
                vectors[i][j] = rnd.nextFloat() * 2 - 1
                norm += vectors[i][j].toDouble() * vectors[i][j].toDouble()
            }
            val s = 1.0 / sqrt(norm)
            for (j in 0 until DIM) vectors[i][j] = (vectors[i][j].toDouble() * s).toFloat()
        }
        val query = vectors[0].clone() // 一条真实向量作为查询

        // ── 构建三种存储 ──────────────────────────────────────
        val buildTimes = JSONObject()
        val rssAfterData = readVmHwmKb()

        val t0 = SystemClock.elapsedRealtime()
        dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("CREATE TABLE vec(id INTEGER PRIMARY KEY, v BLOB)")
        db.beginTransaction()
        val insert = db.compileStatement("INSERT INTO vec(id, v) VALUES(?, ?)")
        for (i in 0 until n) {
            insert.bindLong(1, i.toLong())
            insert.bindBlob(2, toBlob(vectors[i]))
            insert.executeInsert()
        }
        db.setTransactionSuccessful()
        db.endTransaction()
        buildTimes.put("sqlite_blob_ms", SystemClock.elapsedRealtime() - t0)

        val t1 = SystemClock.elapsedRealtime()
        FileChannel.open(flatFile.toPath(), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
            .use { ch ->
                val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                head.putInt(n).putInt(DIM).flip()
                ch.write(head)
                val buf = ByteBuffer.allocate(DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    buf.clear()
                    for (f in vectors[i]) buf.putFloat(f)
                    buf.flip()
                    ch.write(buf)
                }
            }
        buildTimes.put("mmap_flat_ms", SystemClock.elapsedRealtime() - t1)

        val t2 = SystemClock.elapsedRealtime()
        val preloaded = FloatArray(n * DIM)
        for (i in 0 until n) System.arraycopy(vectors[i], 0, preloaded, i * DIM, DIM)
        buildTimes.put("preloaded_ms", SystemClock.elapsedRealtime() - t2)

        // ── 扫描基准 ──────────────────────────────────────────
        val results = JSONArray()
        val referenceTop10 = HashMap<Int, List<Int>>()

        fun record(impl: String, size: Int, times: MutableList<Double>, top10: List<Int>) {
            val sorted = times.sorted()
            results.put(
                JSONObject()
                    .put("impl", impl).put("n", size)
                    .put("cold_ms", round3(times.first()))
                    .put("p50_ms", round3(sorted[sorted.size / 2]))
                    .put("p95_ms", round3(sorted[((sorted.size - 1) * 0.95).toInt()]))
                    .put("top10", top10.joinToString(","))
            )
            referenceTop10[size] = top10
        }

        for (size in SIZES) {
            // a) SQLITE_BLOB
            run {
                val times = ArrayList<Double>(RUNS + WARMUP)
                var top10: List<Int> = emptyList()
                for (r in 0 until RUNS + WARMUP) {
                    val s = SystemClock.elapsedRealtime()
                    top10 = scanSqlite(db, query, size)
                    if (r >= WARMUP - 1) times.add((SystemClock.elapsedRealtime() - s).toDouble())
                }
                record("SQLITE_BLOB", size, times, top10)
            }
            // b) PRELOADED
            run {
                val times = ArrayList<Double>(RUNS + WARMUP)
                var top10: List<Int> = emptyList()
                for (r in 0 until RUNS + WARMUP) {
                    val s = SystemClock.elapsedRealtime()
                    top10 = scanArray(preloaded, query, size)
                    if (r >= WARMUP - 1) times.add((SystemClock.elapsedRealtime() - s).toDouble())
                }
                record("PRELOADED", size, times, top10)
            }
            // c) MMAP_FLAT
            run {
                val ch = FileChannel.open(flatFile.toPath(), java.nio.file.StandardOpenOption.READ)
                val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
                buf.int; buf.int // header
                val fb = buf.asFloatBuffer()
                val times = ArrayList<Double>(RUNS + WARMUP)
                var top10: List<Int> = emptyList()
                for (r in 0 until RUNS + WARMUP) {
                    val s = SystemClock.elapsedRealtime()
                    top10 = scanMmap(fb, query, size)
                    if (r >= WARMUP - 1) times.add((SystemClock.elapsedRealtime() - s).toDouble())
                }
                record("MMAP_FLAT", size, times, top10)
                ch.close()
            }
        }
        db.close()

        // 一致性 + 门槛判定
        val consistency = JSONObject()
        var allMatch = true
        for (size in SIZES) {
            val tops = (0 until results.length()).map { results.getJSONObject(it) }
                .filter { it.getInt("n") == size }.map { it.getString("top10") }
            val match = tops.toSet().size == 1
            consistency.put("n$size", match)
            allMatch = allMatch && match
        }
        fun p95Of(impl: String, size: Int) =
            (0 until results.length()).asSequence().map { results.getJSONObject(it) }
                .firstOrNull { it.getString("impl") == impl && it.getInt("n") == size }
                ?.getDouble("p95_ms") ?: -1.0

        val best1k = listOf("SQLITE_BLOB", "PRELOADED", "MMAP_FLAT").minOf { p95Of(it, 1000) }
        val best10k = listOf("SQLITE_BLOB", "PRELOADED", "MMAP_FLAT").minOf { p95Of(it, 10000) }

        val out = JSONObject()
            .put("experiment", "G4_exact_scan")
            .put("dim", DIM)
            .put("real_vectors", realCount).put("random_vectors", n - realCount)
            .put("runs", RUNS).put("warmup", WARMUP)
            .put("build_times", buildTimes)
            .put("rss_after_data_kb", readVmHwmKb())
            .put("rss_baseline_kb", rssAfterData)
            .put("results", results)
            .put("consistency_top10", consistency)
            .put("gates", JSONObject()
                .put("p95_1k_le_150ms", best1k <= 150.0)
                .put("p95_10k_le_800ms", best10k <= 800.0)
                .put("best_p95_1k", best1k).put("best_p95_10k", best10k))
            .put("g4_pass", allMatch && best1k <= 150.0 && best10k <= 800.0)

        val outFile = File(workDir, "scan_bench_v1.json")
        outFile.writeText(out.toString(2))
        outFile
    }

    // ── 三种扫描实现（double 累加保证跨实现一致性） ──────────────

    private fun scanSqlite(db: SQLiteDatabase, query: FloatArray, n: Int): List<Int> {
        val best = ArrayList<Pair<Double, Int>>(TOP_K + 1)
        val scratch = FloatArray(DIM)
        val bb = ByteBuffer.allocate(DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
        db.rawQuery("SELECT id, v FROM vec WHERE id < $n", null).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(1)
                bb.clear(); bb.put(blob).flip()
                bb.asFloatBuffer().get(scratch)
                var dot = 0.0
                for (j in 0 until DIM) dot += query[j].toDouble() * scratch[j].toDouble()
                insertTop(best, dot, c.getInt(0))
            }
        }
        return best.map { it.second }
    }

    private fun scanArray(arr: FloatArray, query: FloatArray, n: Int): List<Int> {
        val best = ArrayList<Pair<Double, Int>>(TOP_K + 1)
        for (i in 0 until n) {
            val off = i * DIM
            var dot = 0.0
            for (j in 0 until DIM) dot += query[j].toDouble() * arr[off + j].toDouble()
            insertTop(best, dot, i)
        }
        return best.map { it.second }
    }

    private fun scanMmap(fb: java.nio.FloatBuffer, query: FloatArray, n: Int): List<Int> {
        val best = ArrayList<Pair<Double, Int>>(TOP_K + 1)
        for (i in 0 until n) {
            val base = i * DIM
            var dot = 0.0
            for (j in 0 until DIM) dot += query[j].toDouble() * fb.get(base + j).toDouble()
            insertTop(best, dot, i)
        }
        return best.map { it.second }
    }

    private fun insertTop(best: ArrayList<Pair<Double, Int>>, score: Double, id: Int) {
        if (best.size == TOP_K && score <= best.last().first) return
        var idx = best.indexOfFirst { score > it.first }
        if (idx == -1) idx = best.size
        best.add(idx, score to id)
        if (best.size > TOP_K) best.removeAt(best.size - 1)
    }

    private fun toBlob(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) bb.putFloat(f)
        return bb.array()
    }

    private fun readVmHwmKb(): Long = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1L
        }
    } catch (_: Exception) { -1L }

    private fun round3(v: Double) = Math.round(v * 1000) / 1000.0
}
