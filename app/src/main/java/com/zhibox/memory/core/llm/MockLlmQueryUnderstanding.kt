package com.zhibox.memory.core.llm

import com.zhibox.memory.core.sag.QueryEntity
import com.zhibox.memory.core.sag.QueryEntityExtractor
import com.zhibox.memory.core.sag.SagContextSelector
import com.zhibox.memory.core.sag.SagHit

/**
 * B10 测试/预览用 mock 实现（**仅供测试**，不得接入生产路径；生产 LLM 实现见
 * [LlmQueryEntityExtractor] / [LlmSagContextSelector]）。
 *
 * 与真实实现一样是 suspend、不抛业务异常；额外暴露 `calls`/`lastQuery`/`lastCandidates`
 * 方便测试断言调用次数与入参。
 */

/** 固定返回给定实体的 [QueryEntityExtractor]（默认空列表 = 模拟"LLM 不可用/降级"）。 */
class MockLlmQueryEntityExtractor(
    private val result: List<QueryEntity> = emptyList(),
) : QueryEntityExtractor {
    var calls: Int = 0
        private set
    var lastQuery: String? = null
        private set

    override suspend fun extract(query: String): List<QueryEntity> {
        calls++
        lastQuery = query
        return result
    }
}

/**
 * 固定返回给定选择的 [SagContextSelector]。
 *
 * @param result 固定结果；null 表示"确定性回退"模式：返回 `candidates.take(maxEvents)`。
 */
class MockSagContextSelector(
    private val result: List<SagHit>? = null,
    private val maxEvents: Int = 5,
) : SagContextSelector {
    var calls: Int = 0
        private set
    var lastQuery: String? = null
        private set
    var lastCandidates: List<SagHit> = emptyList()
        private set

    override suspend fun select(query: String, candidates: List<SagHit>): List<SagHit> {
        calls++
        lastQuery = query
        lastCandidates = candidates
        return result ?: candidates.take(maxEvents)
    }
}
