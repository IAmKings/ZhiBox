package com.zhibox.memory.core.embedding

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 路径 B：LiteRT-LM EmbeddingEngine（litertlm-android 0.18.0 公共 API）+ .litertlm 嵌入包。
 *
 * **B7 收敛后路径 B 只服务 EGemma2-270M**（轻量档；440M/740M 走路径 A
 * [UniversalEmbedderProvider]）。granite/LFM 路线已随技术栈收敛下线（B7）：
 * - 运行时即路径 A 已强制钉住的 litertlm-android 0.18.0，零新增依赖；
 * - EGemma2-270M `.litertlm`（768d）：曾被路径 A UE 拒载，故保留本引擎通道作为轻量兜底；
 * - 对称无模板契约（上游 README 实测：加 query/passage 前缀反而降低检索指标），
 *   query 与 document 均按 RAW 编码——与路径 A 的应用层前缀策略互斥，切换即换向量空间；
 * - 同线程契约：非线程安全，由调用方串行（EmbeddingRuntime 全局 Mutex）。
 *
 * @param modelFile .litertlm 嵌入包（EmbeddingEngine 直接按路径加载，无需 ParcelFileDescriptor）
 */
class LiteRtLmEmbeddingProvider(
    context: Context,
    modelFile: File,
    useGpu: Boolean = false,
    useNpu: Boolean = false,
) : EmbeddingProvider {

    private val appContext = context.applicationContext
    private val engine: EmbeddingEngine
    private val closed = AtomicBoolean(false)

    override var descriptor: EmbeddingDescriptor = EmbeddingDescriptor(
        providerId = PROVIDER_ID,
        modelId = modelFile.nameWithoutExtension,
        // P1-6：权重版本进空间指纹——同名换文件不再静默复用旧空间
        modelRevision = ModelFingerprint.revision(modelFile),
        dimensions = -1,
        // 对称模型无任务模板；策略名固定为 RAW 供空间隔离指纹使用
        queryTaskTemplate = TaskTemplatePolicy.RAW.name,
        documentTaskTemplate = TaskTemplatePolicy.RAW.name,
        accelerator = if (useNpu) "npu" else if (useGpu) "gpu" else "cpu",
    )

    init {
        val backend = when {
            useNpu -> Backend.NPU()   // SOC 特化版专用（验证门后启用；未证，GPU 挂死前车之鉴）
            useGpu -> Backend.GPU()
            else -> Backend.CPU()
        }
        val config = EmbeddingEngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            cacheDir = appContext.cacheDir.absolutePath,
        )
        // P1-3 防御：initialize() 抛错时引擎实例必须关闭（否则 native 内存/fd 泄漏）
        val created = EmbeddingEngine(config)
        try {
            created.initialize()
        } catch (t: Throwable) {
            runCatching { created.close() }
            throw t
        }
        engine = created
    }

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) {
            texts.map { text ->
                currentCoroutineContext().ensureActive()
                encode(text)
            }
        }

    override suspend fun embedQuery(text: String): FloatArray =
        withContext(Dispatchers.Default) { encode(text) }

    private fun encode(rawText: String): FloatArray {
        check(!closed.get()) { "provider already closed" }
        val response = engine.computeEmbedding(
            listOf(InputData.Text(rawText)),
            EmbeddingOptions(normalize = true),
        )
        val vector = response.embedding
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
            runCatching { engine.close() }
        }
    }

    companion object {
        const val PROVIDER_ID = "litertlm-engine"
    }
}
