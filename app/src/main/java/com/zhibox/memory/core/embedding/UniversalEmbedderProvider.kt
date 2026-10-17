package com.zhibox.memory.core.embedding

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 路径 A：MediaPipe Universal Embedder（tasks-retrieval 1.1.1，底层 LiteRT-LM）。
 *
 * P1-W1-3 生产化形态（Phase 0 G1 结论落地）：
 * - 双任务模板策略：embedQuery → QUERY_PREFIX，embedDocuments → DOCUMENT_PREFIX
 *   （应用层前缀，运行时确认不自带模板；检索 R@1=1.0）
 * - 批量编码支持协作取消（ensureActive）与逐条有限值校验
 * - 维度首次推理回填，维度漂移视为运行时异常
 *
 * 实验复验入口（ChineseTokenizationBenchmark 等基准）：构造时以 queryPolicy
 * 覆盖默认值即可重现 Phase 0 的单策略测量语义。
 *
 * @param useGpu 默认 false（CPU/XNNPack，调查报告 R4）
 */
class UniversalEmbedderProvider(
    context: Context,
    modelFile: File,
    private val queryPolicy: TaskTemplatePolicy = TaskTemplatePolicy.QUERY_PREFIX,
    private val documentPolicy: TaskTemplatePolicy = TaskTemplatePolicy.DOCUMENT_PREFIX,
    private val useGpu: Boolean = false,
    private val useNpu: Boolean = false,
) : EmbeddingProvider {

    private val appContext = context.applicationContext
    private val modelFd: ParcelFileDescriptor
    private val embedder: UniversalEmbedder
    private val closed = AtomicBoolean(false)

    override var descriptor: EmbeddingDescriptor = EmbeddingDescriptor(
        providerId = PROVIDER_ID,
        modelId = modelFile.nameWithoutExtension,
        // P1-6：权重版本进空间指纹——同名换文件不再静默复用旧空间
        modelRevision = ModelFingerprint.revision(modelFile),
        dimensions = -1,
        queryTaskTemplate = queryPolicy.name,
        documentTaskTemplate = documentPolicy.name,
        accelerator = if (useNpu) "npu" else if (useGpu) "gpu" else "cpu",
    )

    init {
        val fd = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        modelFd = fd
        // P1-3 防御：createFromOptions 抛错时 fd 不得泄漏（GPU/NPU 编译失败常见路径）
        embedder = try {
            createEmbedder(fd)
        } catch (t: Throwable) {
            runCatching { fd.close() }
            throw t
        }
    }

    private fun createEmbedder(modelFd: ParcelFileDescriptor): UniversalEmbedder {
        val baseBuilder = BaseOptions.builder()
            .setModelAssetFileDescriptor(modelFd.fd)
        when {
            // NPU（SOC 特化版）：对齐 Google AI Edge Gallery 的 OnDeviceEmbedder 通道——
            // Delegate.NPU + NpuOptions（dispatch/编译插件库目录指向 APK nativeLibraryDir）。
            // Gallery 同款运行通道可加载 EGemma2 .litertlm（GPU/CPU 已由我们路径 A 验证）。
            useNpu -> {
                val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
                baseBuilder.setDelegate(Delegate.NPU)
                    .setDelegateOptions(
                        com.google.mediapipe.tasks.core.BaseOptions.DelegateOptions.NpuOptions.builder()
                            .setDispatchLibraryDirectory(nativeLibDir)
                            .setCompilerPluginLibraryDirectory(nativeLibDir)
                            .build()
                    )
            }
            useGpu -> baseBuilder.setDelegate(Delegate.GPU)
            else -> baseBuilder.setDelegate(Delegate.CPU)
        }
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(baseBuilder.build())
            // 官方 demo（UniversalEmbedderHelper.kt）设置 LiteRT-LM 编译产物缓存目录，
            // 二次初始化可跳过重编译；demo 保真度对照差异①，2026-10-15 补齐
            .setCacheDir(appContext.cacheDir.absolutePath)
            .setL2Normalize(true)
            .build()
        return UniversalEmbedder.createFromOptions(appContext, options)
    }

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) {
            texts.map { text ->
                currentCoroutineContext().ensureActive()
                encode(text, documentPolicy)
            }
        }

    override suspend fun embedQuery(text: String): FloatArray =
        withContext(Dispatchers.Default) { encode(text, queryPolicy) }

    private fun encode(rawText: String, policy: TaskTemplatePolicy): FloatArray {
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
