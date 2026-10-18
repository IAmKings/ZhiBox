package com.zhibox.memory.core.sag

/**
 * B10：离线确定性上下文选择器（默认实现，零网络、可复现）。
 *
 * 排序键（全序，锁定测试）：
 * 1. `score` 降序（融合分/向量余弦，0..1）；
 * 2. `eventId` 升序（无 event 的纯向量命中排最后，用 Long.MAX_VALUE 归并）；
 * 3. `chunkId` 升序（最终稳定 tie-break）。
 *
 * 去重：同 chunkId 只保留首个（候选若跨通道重复出现，按上述排序取最优）。
 * Retriever 会再按 `SagBudget.kEvent` 截断；本选择器不读预算。
 */
class DeterministicSagSelector : SagContextSelector {

    override suspend fun select(query: String, candidates: List<SagHit>): List<SagHit> =
        candidates
            .distinctBy { it.chunkId }
            .sortedWith(
                compareByDescending<SagHit> { it.score }
                    .thenBy { it.eventId ?: Long.MAX_VALUE }
                    .thenBy { it.chunkId },
            )
}
