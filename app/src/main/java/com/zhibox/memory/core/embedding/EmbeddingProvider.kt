package com.zhibox.memory.core.embedding

import java.io.Closeable

/**
 * 嵌入提供方抽象。
 *
 * 依据 Android PRD §4.1 接口原则与实施计划 P1-W1-3（本文件在 P0-2 提前落地，
 * 供基准与 G1 判定门直接复用）：
 * - query 与 document 必须使用同一兼容模型与任务模板策略（由 descriptor 声明）；
 * - 调用方不得在传入前自行拼接任务模板，模板策略统一由 Provider 内部处理；
 * - 输出必须为有限值数组，长度等于 descriptor.dimensions（首次调用后可知）。
 */
interface EmbeddingProvider : Closeable {
    val descriptor: EmbeddingDescriptor

    suspend fun embedDocuments(texts: List<String>): List<FloatArray>

    suspend fun embedQuery(text: String): FloatArray
}

/**
 * 向量空间描述。任何两个 descriptor 的 (providerId, modelId, modelRevision,
 * dimensions, normalization, 模板策略, accelerator) 不完全一致时，向量不可混用比较。
 */
data class EmbeddingDescriptor(
    val providerId: String,                  // mediapipe-universal / litertlm-engine（B7 后仅此两种）
    val modelId: String,
    val modelRevision: String? = null,       // 文件 SHA-256 前 12 位或版本号
    val dimensions: Int = -1,                // 首次推理后回填
    val normalization: String = "l2",
    val queryTaskTemplate: String? = null,
    val documentTaskTemplate: String? = null,
    val modality: String = "text",
    val accelerator: String = "cpu",         // cpu / gpu / npu——GPU/NPU 编译随设备驱动而异，
                                             // 实测向量跨设备不逐位一致（G1E：旗舰 6864bf58 vs K60U acd0ba1f），
                                             // 必须并入空间指纹防混用
    val quantization: String = "f32",        // 向量存储格式（f32 / int8——int8 演进时由 Provider 声明，
                                             // P1-6 审查修复：embedding_meta 字段由 descriptor 生成而非硬编码）
)

/**
 * G1 判定门（实施计划 P0-3）的三种任务模板对照模式：
 * - RAW：原文直接编码（检测运行时默认行为与 HF 参考向量的差距）；
 * - DOCUMENT_PREFIX：手工前置文档模板（若运行时也套模板，会双重拼接，余弦将显著偏离 1）；
 * - QUERY_PREFIX：手工前置查询模板（SearchQuery 等价前缀，依据 EGemma 2 官方文档）。
 */
enum class TaskTemplatePolicy(val prefix: String?) {
    RAW(null),
    DOCUMENT_PREFIX("title: none | text: "),
    QUERY_PREFIX("task: search result | query: "),
}
