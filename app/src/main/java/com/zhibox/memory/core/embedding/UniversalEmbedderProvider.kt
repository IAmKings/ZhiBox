package com.zhibox.memory.core.embedding

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 路径 A：MediaPipe Universal Embedder（tasks-retrieval 1.1.1，底层 LiteRT-LM）。
 *
 * Phase 0 用途：资源画像基准 + G1 判定门三模板对照。
 * 生产化改造（分批、超时、并发保护）在 Phase 1 P1-W1-3 完成。
 *
 * API 备注（经 AAR javap 核验，1.1.1）：
 * - 模型从文件路径加载：BaseOptions 只有 assetPath / fileDescriptor / buffer 三种方式，
 *   本实现持有 ParcelFileDescriptor 并在 close 时释放；
 * - Embedding.floatEmbedding() 直接返回 float[]。
 *
 * @param policy 任务模板策略，见 [TaskTemplatePolicy]；索引侧与查询侧必须使用一致策略
 * @param useGpu 默认 false（CPU/XNNPack）。GPU 仅基准对照用，依据调查报告 R4 不进默认配置
 */
class UniversalEmbedderProvider(
    context: Context,
    modelFile: File,
    private val policy: TaskTemplatePolicy = TaskTemplatePolicy.RAW,
    useGpu: Boolean = false,
) : EmbeddingProvider {

    private val appContext = context.applicationContext
    private val modelFd: ParcelFileDescriptor
    private val embedder: UniversalEmbedder
    private val closed = AtomicBoolean(false)

    // 覆盖为 var：首次推理后回填 dimensions
    override var descriptor: EmbeddingDescriptor = EmbeddingDescriptor(
        providerId = PROVIDER_ID,
        modelId = modelFile.nameWithoutExtension,
        dimensions = -1,
        queryTaskTemplate = policy.name,
        documentTaskTemplate = policy.name,
    )

    init {
        modelFd = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetFileDescriptor(modelFd.fd)
                    .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
                    .build()
            )
            // 调查报告附录 A.5：SAG 检索语义依赖归一化余弦分数，统一 L2 归一化
            .setL2Normalize(true)
            .build()
        embedder = UniversalEmbedder.createFromOptions(appContext, options)
    }

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) { texts.map { encode(it) } }

    override suspend fun embedQuery(text: String): FloatArray =
        withContext(Dispatchers.Default) { encode(text) }

    private fun encode(rawText: String): FloatArray {
        check(!closed.get()) { "provider already closed" }
        val input = policy.prefix?.let { it + rawText } ?: rawText
        val vector = embedder.embedText(input).embeddings().first().floatEmbedding()
        check(vector.all { it.isFinite() }) { "embedding contains non-finite values" }
        if (descriptor.dimensions == -1) {
            descriptor = descriptor.copy(dimensions = vector.size)
        } else {
            check(vector.size == descriptor.dimensions) {
                "dimension drift: expected ${descriptor.dimensions}, got ${vector.size}"
            }
        }
        return vector
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { embedder.close() }
            runCatching { modelFd.close() }
        }
    }

    companion object {
        const val PROVIDER_ID = "mediapipe-universal"
    }
}
