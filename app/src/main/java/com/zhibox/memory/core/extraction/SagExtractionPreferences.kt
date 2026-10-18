package com.zhibox.memory.core.extraction

import android.content.Context

/**
 * B12：SAG 抽取并发度本地偏好（进程/Worker 读取；默认 1=串行）。
 *
 * 设计：并发由 Worker 启动 N 个协作协程共同消费 QUEUED 任务；每个 job 仍由
 * `ExtractionJobDao.claim` 原子领取，数据库写入/embedding 运行时负责串行化，
 * 本偏好只控制“同时在途的远程 LLM 抽取数”。上限 4，避免远端限流/电量/费用失控。
 */
object SagExtractionPreferences {
    private const val PREFS = "sag_extraction_prefs"
    private const val KEY_PARALLELISM = "parallelism"
    const val DEFAULT_PARALLELISM: Int = 1
    const val MIN_PARALLELISM: Int = 1
    const val MAX_PARALLELISM: Int = 4

    fun parallelism(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PARALLELISM, DEFAULT_PARALLELISM)
            .coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)

    /** @return 实际保存值（已被夹在 [MIN_PARALLELISM]..[MAX_PARALLELISM]） */
    fun setParallelism(context: Context, value: Int): Int {
        val clamped = value.coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PARALLELISM, clamped)
            .commit()
        return clamped
    }
}
