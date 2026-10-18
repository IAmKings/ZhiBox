package com.zhibox.memory.core.extraction

import com.zhibox.memory.core.sag.SagVectorRole

/**
 * B9：SAG 语义向量的通用载体（event content / entity description）。
 *
 * 与 `chunk_embedding` 解耦：本类型只描述"一段文本在某个派生空间中的 float32 LE 向量"，
 * 写入 `sag_vector` 表时仍需补 ownerType/ownerId/role/createdAt（见 pipeline）。
 *
 * @param vectorBlob float32 小端编码（与 chunk_embedding 完全一致）
 * @param vectorNorm L2 范数（Provider 已归一化时≈1.0；保留实际值便于检索侧校验/调试）
 * @param spaceId 派生空间 id（`SagVectorSpace.spaceIdFor(base, role)`，写入/检索必须一致）
 * @param dimensions 向量维度（应等于空间登记维度）
 */
data class SagEmbeddedVector(
    val vectorBlob: ByteArray,
    val vectorNorm: Double,
    val spaceId: String,
    val dimensions: Int,
) {
    // ByteArray 需要显式 equals/hashCode，避免测试断言/集合去重误判引用相等
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SagEmbeddedVector) return false
        return vectorNorm == other.vectorNorm &&
            spaceId == other.spaceId &&
            dimensions == other.dimensions &&
            vectorBlob.contentEquals(other.vectorBlob)
    }

    override fun hashCode(): Int {
        var result = vectorBlob.contentHashCode()
        result = 31 * result + vectorNorm.hashCode()
        result = 31 * result + spaceId.hashCode()
        result = 31 * result + dimensions
        return result
    }
}

/**
 * B9：SAG 向量生成网关（pipeline 依赖抽象，测试可注入 fake）。
 *
 * 契约：
 * - 一次调用只使用**一个** [SagVectorRole]（event 与 entity 分别调用），返回数量与 [texts] 一一对应；
 * - 实现负责在端侧嵌入运行时上串行执行（内部走 `EmbeddingRuntime.withProvider`）；
 * - 失败抛出 [SagEmbeddingException]（稳定 code + 可读 message，不含正文）；CancellationException
 *   必须向上传播（协程取消语义），不得包装成普通失败。
 */
interface SagEmbeddingGateway {
    suspend fun embed(role: SagVectorRole, texts: List<String>): List<SagEmbeddedVector>
}

/**
 * SAG 向量生成失败（稳定错误码 + 可读原因，不含正文；供 pipeline 分类重试/写 job.errorCode）。
 *
 * @param code 见 [SagExtractionErrorCodes]（EMBEDDING_UNAVAILABLE / EMBEDDING_FAILED）
 * @param retryable 是否属于"稍后可能恢复"的失败（模型暂缺/引擎瞬时错误 → 可重试）
 */
class SagEmbeddingException(
    val code: String,
    message: String,
    val retryable: Boolean = true,
    cause: Throwable? = null,
) : Exception(message, cause)
