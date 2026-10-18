package com.zhibox.memory.ui

import com.zhibox.memory.core.sag.SagHit
import com.zhibox.memory.core.search.SearchService

/**
 * B10：SAG 检索结果 → 证据页（[EvidenceScreen]）的映射。
 *
 * 证据页复用既有 `SearchHit` 展示（chunk 原文/定位/来源回溯），SAG 的命中只是多带了
 * eventId/entityIds/channels/score 等检索元数据，因此映射为一等公民字段：
 * - `fusedScore` 优先用 SAG 融合分，缺失时退回 `score`；
 * - SAG 不区分向量/关键词名次（`vectorRank/keywordRank` 置 null）；
 * - `channels` 集合拼接为可读标记（如 "vector+event"）。
 */
fun SagHit.toEvidenceSearchHit(): SearchService.SearchHit = SearchService.SearchHit(
    chunkId = chunkId,
    documentId = documentId,
    ordinal = ordinal,
    content = content,
    charStart = charStart,
    charEnd = charEnd,
    docTitle = docTitle,
    sourceTitle = sourceTitle,
    sourceUri = sourceUri,
    vectorRank = null,
    keywordRank = null,
    fusedScore = fusedScore ?: score,
    channels = channels.joinToString("+"),
)
