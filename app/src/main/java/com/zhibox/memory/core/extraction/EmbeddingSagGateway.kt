package com.zhibox.memory.core.extraction

import android.content.Context
import com.zhibox.memory.core.embedding.EmbeddingProvider
import com.zhibox.memory.core.embedding.EmbeddingRuntime
import com.zhibox.memory.core.model.ModelCatalog
import com.zhibox.memory.core.sag.SagVectorRole
import com.zhibox.memory.core.sag.SagVectorSpace
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EmbeddingMetaEntity
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * B9：SAG 语义向量网关生产实现——复用端侧 [EmbeddingRuntime]（与文档检索同一引擎/空间体系）。
 *
 * 流程（每次 [embed]）：
 * 1. 解析当前模型（[EmbeddingRuntime.findModel]）→ 缺模型抛 EMBEDDING_UNAVAILABLE（可重试）；
 * 2. `EmbeddingRuntime.withProvider` 串行编码（与导入/检索共用一把锁，引擎非线程安全）；
 * 3. 基础空间经 `EmbeddingRuntime.registerSpace` 解析（含 revision 换文件检测），
 *    派生空间 = [SagVectorSpace.spaceIdFor](base, role)；
 * 4. 派生空间在 `embedding_meta` 登记一行 **active=false**（同一 provider/model/dims/模板，
 *    若已存在复用）——SAG 空间不参与"当前默认空间"语义，避免污染 chunk 检索的 active 不变式；
 * 5. 输出 float32 LE + 实际 L2 范数。
 *
 * 线程约定：pipeline 串行调用本类；空间缓存只服务同一 Worker 生命周期，**非线程安全**。
 */
class EmbeddingSagGateway(context: Context) : SagEmbeddingGateway {

    private val appContext: Context = context.applicationContext
    private val db: AppDatabase = AppDatabase.get(appContext)

    /** 同一 Worker 内同一角色的派生空间缓存（模型路径/revision/维度变化时失效重建） */
    private val spaceCache: MutableMap<SagVectorRole, CachedSpace> = HashMap()

    private data class CachedSpace(
        val modelPath: String,
        val modelRevision: String?,
        val dimensions: Int,
        val spaceId: String,
    )

    override suspend fun embed(role: SagVectorRole, texts: List<String>): List<SagEmbeddedVector> {
        if (texts.isEmpty()) return emptyList()
        val model = EmbeddingRuntime.findModel(appContext)
            ?: throw SagEmbeddingException(
                SagExtractionErrorCodes.EMBEDDING_UNAVAILABLE,
                "端侧嵌入模型缺失（filesDir/models 无可用模型），无法生成 SAG 向量",
                retryable = true,
            )

        return EmbeddingRuntime.withProvider(appContext, model) { provider ->
            val vectors = try {
                provider.embedDocuments(texts)
            } catch (c: CancellationException) {
                throw c // 协程取消语义：不包装为业务失败
            } catch (e: Exception) {
                throw SagEmbeddingException(
                    SagExtractionErrorCodes.EMBEDDING_FAILED,
                    "端侧嵌入执行失败（${e.javaClass.simpleName}）",
                    retryable = true,
                    cause = e,
                )
            }
            if (vectors.size != texts.size) {
                throw SagEmbeddingException(
                    SagExtractionErrorCodes.EMBEDDING_FAILED,
                    "端侧嵌入返回数量不匹配（${vectors.size}/${texts.size}）",
                    retryable = true,
                )
            }

            val descriptor = provider.descriptor
            // embedDocuments 首次调用后 descriptor.dimensions 已回填；未收录模型回退 catalog 默认维度
            val dimensions = descriptor.dimensions.takeIf { it > 0 } ?: ModelCatalog.dimsFor(model.name)
            val spaceId = resolveDerivedSpace(role, model, provider, dimensions)

            vectors.map { vector ->
                SagEmbeddedVector(
                    vectorBlob = toBlob(vector),
                    vectorNorm = l2Norm(vector),
                    spaceId = spaceId,
                    dimensions = dimensions,
                )
            }
        }
    }

    /**
     * 解析（或复用缓存中的）派生空间 id；首次解析时登记 active=false 的 embedding_meta 行。
     * 与 [SagVectorSpace.requireMatches] 同一生成规则——写入/检索两侧不得各自拼字符串。
     */
    private suspend fun resolveDerivedSpace(
        role: SagVectorRole,
        model: File,
        provider: EmbeddingProvider,
        dimensions: Int,
    ): String {
        val descriptor = provider.descriptor
        val cached = spaceCache[role]
        if (cached != null &&
            cached.modelPath == model.absolutePath &&
            cached.modelRevision == descriptor.modelRevision &&
            cached.dimensions == dimensions
        ) {
            return cached.spaceId
        }
        // registerSpace：按当前模型解析基础空间（含同名换文件 @revision 规则）并维护 active 不变式
        val baseSpaceId = EmbeddingRuntime.registerSpace(appContext, provider, model)
        val spaceId = SagVectorSpace.spaceIdFor(baseSpaceId, role)
        ensureDerivedSpaceMeta(
            spaceId = spaceId,
            providerId = descriptor.providerId,
            modelId = descriptor.modelId,
            modelRevision = descriptor.modelRevision,
            dimensions = dimensions,
            normalization = descriptor.normalization,
            taskTemplate = EmbeddingRuntime.taskTemplateFor(descriptor),
            accelerator = descriptor.accelerator,
            quantization = descriptor.quantization,
        )
        spaceCache[role] = CachedSpace(
            modelPath = model.absolutePath,
            modelRevision = descriptor.modelRevision,
            dimensions = dimensions,
            spaceId = spaceId,
        )
        return spaceId
    }

    /** 首次登记派生空间 meta（active=false；已存在则复用，不改写其它字段） */
    private suspend fun ensureDerivedSpaceMeta(
        spaceId: String,
        providerId: String,
        modelId: String,
        modelRevision: String?,
        dimensions: Int,
        normalization: String,
        taskTemplate: String,
        accelerator: String,
        quantization: String,
    ) {
        if (db.embeddingDao().meta(spaceId) != null) return
        db.embeddingDao().upsertMeta(
            EmbeddingMetaEntity(
                id = spaceId,
                providerId = providerId,
                modelId = modelId,
                modelRevision = modelRevision,
                dimensions = dimensions,
                normalization = normalization,
                taskTemplate = taskTemplate,
                createdAt = System.currentTimeMillis(),
                active = false,
                accelerator = accelerator,
                quantization = quantization,
            ),
        )
    }

    private fun toBlob(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (f in vector) putFloat(f)
        }.array()

    private fun l2Norm(vector: FloatArray): Double {
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v.toDouble()
        return sqrt(sum)
    }
}
