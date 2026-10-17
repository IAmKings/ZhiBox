package com.zhibox.memory.core.model

import android.content.Context
import com.zhibox.memory.core.ingest.IngestRepository
import java.io.File

/**
 * 嵌入模型偏好与路由（W3-3 / W4 修订 / **B7 收敛**）：filesDir/models 显式名单 + 用户偏好。
 *
 * B7（保守版 A1）后只保留 EGemma2 三强度 + SM8750 NPU 变体：
 * - 偏好值：auto（默认，440M→270M→名称序）/ A（440M UE）/ B（270M litertlm 引擎）/
 *   D（740M UE，性能上限）；旧偏好值 "C"（已下线）读取时迁移为 auto；
 * - 已下线：Path C（`.task` / MediaPipe TextEmbedder）、Granite、LFM2.5——对应扫描、偏好 C、
 *   `.task` 扩展名与 granite/LFM 标签全部移除；旧库数据由
 *   [IngestRepository.legacySpacesReport] 识别，UI 提示"重新导入"（旧向量不能跨路线复用）。
 * - 候选只扫描 `filesDir/models` 与 `cacheDir/models-link` 下的 `.litertlm` 文件。
 * 偏好缺失回退 auto；切换模型空间后旧数据需重嵌入/重导入。
 */
object PathPreferences {

    const val AUTO = "auto"
    const val A = "A"
    /** 270M（litertlm EmbeddingEngine 路径 B）；granite 已下线，B 语义改为 EGemma2-270M */
    const val B = "B"
    /** 740M（UniversalEmbedder，性能上限；实验模型，用户显式选择） */
    const val D = "D"

    /** 允许的显式偏好值（旧值 C/非法值读取时回退 AUTO） */
    private val VALID_PREFERENCES = setOf(AUTO, A, B, D)

    /** GPU 策略：auto（默认，试 GPU 失败自动回退 CPU）/ off（强制 CPU）/ on（强制 GPU，失败即报错） */
    const val GPU_AUTO = "auto"
    const val GPU_OFF = "off"
    const val GPU_ON = "on"

    private const val PREFS = "embedding_paths"
    private const val KEY = "preferred"
    private const val GPU_KEY = "gpu_mode"

    fun get(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AUTO) ?: AUTO
        // B7 迁移：旧版本可能持久化 "C"（.task）等已下线值；非法值回退 auto，
        // 避免设置页出现"无 chip 选中"且用户不知道当前状态。
        return if (raw in VALID_PREFERENCES) raw else AUTO
    }

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }

    fun gpuMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GPU_KEY, GPU_AUTO) ?: GPU_AUTO

    fun setGpuMode(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(GPU_KEY, value).apply()
    }

    // ---- 性能偏好（W4 模型商店推荐）----

    fun perfMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("perf_mode", ModelCatalog.PERF_BALANCED) ?: ModelCatalog.PERF_BALANCED

    fun setPerfMode(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("perf_mode", value).apply()
    }

    /** 验证门通过记录：fileName → sha256（G1D 通过后写入；实验模型启用依据） */
    fun verifiedSha(context: Context, fileName: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("verified_$fileName", null)

    fun setVerifiedSha(context: Context, fileName: String, sha256: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("verified_$fileName", sha256).apply()
    }

    /**
     * 验证门实现（P1-7）：catalog verified 标记 + 本机验证门记录（记录须与 catalog sha 一致）。
     *
     * 说明：原公开 `isVerified()` 包装在修复后无外部消费方（唯一路径是 [passesGate]），
     * 为避免死代码已删除；`verifiedSha()` 由 [verifiedByRecord] 消费，验证门不再是"只写不读"。
     */
    private fun verifiedByRecord(context: Context, fileName: String, entry: ModelCatalog.Entry): Boolean {
        val recorded = verifiedSha(context, fileName) ?: return false
        return entry.sha256.equals(recorded, ignoreCase = true)
    }

    /**
     * 验证门：该候选是否可参与"已验证优先"排序。
     *
     * 只约束 **catalog 内 verified=false 且无本机验证门记录** 的实验模型；
     * 未收录进 catalog 的模型（用户自投放的历史 `.litertlm` 变体）不参与该排序——
     * 否则用户显式选择的非 catalog 模型会被静默替换成 440M（兼容性回归）。
     */
    private fun passesGate(context: Context, file: File): Boolean {
        val entry = ModelCatalog.byFileName(file.name) ?: return true
        return entry.verified || verifiedByRecord(context, file.name, entry)
    }

    /**
     * 候选模型清单（显式名单）：filesDir/models（正式仓）+ cacheDir/models-link（共享仓启用
     * 后的运行缓存）。B7 后只认 `.litertlm`（`.task` 路线已下线）；共享目录（SAF 冷存储）
     * 本身不在此列——需经 SharedModelStore.enable 复制进缓存后才可加载。同名本地优先。
     */
    fun candidates(context: Context): List<File> {
        fun scan(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && f.extension == "litertlm" }
                ?.filter { isKnownModel(it.name) }
                ?: emptyList()
        val formal = scan(File(context.filesDir, "models"))
        val linked = scan(File(context.cacheDir, "models-link"))
        return (formal + linked).distinctBy { it.name }.sortedBy { it.name }
    }

    /** 显式名单（B7：EGemma2 440M/270M/740M + NPU 变体）；不给字母序默认值留位置 */
    fun isKnownModel(name: String): Boolean = name.contains("440m") ||
        name.contains("740m") || // UE 通道（G1E 验证），路由见 EmbeddingRuntime.createProvider
        IngestRepository.isPathBModel(name) // 270M（litertlm 引擎）

    /**
     * 已知模型**文件**：名单命中且扩展名合法（B7 后仅 `.litertlm`）。
     * P1-10 审查修复：SAF 目录可能被用户选择为任意文件夹，clearAll/listNames 若只做子串
     * 匹配会误删/误列名字里恰好含 "440m"/"740m" 的普通文件（如笔记 txt），因此必须叠加扩展名约束。
     */
    fun isKnownModelFile(name: String): Boolean =
        name.endsWith(".litertlm") && isKnownModel(name)

    /** 模型标签（设置页展示）：B7 后仅 EGemma2 家族，无 granite/.task 文案 */
    fun pathLabel(fileName: String): String = when {
        fileName.contains("440m") && fileName.contains("_Qualcomm", true) ->
            "EGemma2-440M · SM8750 NPU"
        fileName.contains("440m") -> "A · EGemma2-440M（默认）"
        fileName.contains("740m") && fileName.contains("_Qualcomm", true) ->
            "EGemma2-740M · SM8750 NPU"
        fileName.contains("740m") -> "EGemma2-740M"
        IngestRepository.isPathBModel(fileName) -> "B · EGemma2-270M（轻量）"
        else -> "未知"
    }

    /** 按偏好解析当前模型：已验证模型优先，实验模型仅在无其它可选时兜底 */
    fun resolve(context: Context): File? {
        val files = candidates(context)
        if (files.isEmpty()) return null
        val gatePass = files.filter { passesGate(context, it) }.mapTo(HashSet()) { it.name }
        return pickModel(files, get(context), gatePass)
    }

    /**
     * 纯逻辑选型（抽出来供 JVM 单测覆盖验证门顺序——避免"实验模型被自动选中"回归）。
     *
     * 顺序：
     * 1. 候选池 = 通过验证门的模型；**只有全都不通过时**才降级为全部候选（实验模型兜底）；
     * 2. 显式偏好 A（440M）/ B（270M）/ D（740M）在**全量候选**中优先命中（用户显式选择，
     *    不受验证门限制；自动模式仍只从通过验证门的池中选）；
     * 3. 其余按 auto 顺序：通用 440M → 270M → 名称序。
     *
     * 保留实验模型兜底是为了兼容"用户机器上只有实验模型"的既有可用状态（不硬阻断启动），
     * 但把"有已验证模型却自动选实验模型"（会造成 ANR/挂死，如 SM8750 特化版）这条路堵死。
     */
    internal fun pickModel(files: List<File>, preference: String, gatePassNames: Set<String>): File? {
        if (files.isEmpty()) return null
        val passedPool = files.filter { it.name in gatePassNames }
        val pool = passedPool.ifEmpty { files }
        // B7 审查修复：显式偏好 A/B/D 在**全量候选**中查找（不受验证门池限制）——
        // 否则 440M 在位时，显式 B(270M)/D(740M) 永远选不到（实验模型被 gate 过滤后回退 auto）。
        // 自动模式仍走 passedPool 门禁，避免自动选中实验模型。
        val preferred = when (preference) {
            A -> files.firstOrNull { isGeneric440m(it.name) }
            B -> files.firstOrNull { IngestRepository.isPathBModel(it.name) }
            D -> files.firstOrNull { it.name.contains("740m") }
            else -> null
        }
        return preferred ?: pool.sortedWith(
            compareByDescending<File> { isGeneric440m(it.name) }
                .thenByDescending { IngestRepository.isPathBModel(it.name) }
                .thenBy { it.name },
        ).first()
    }

    /** 通用 440M（排除 SOC 特化版）：auto 首选/偏好 A 的口径 */
    private fun isGeneric440m(name: String): Boolean =
        name.contains("440m") && !name.contains("_Qualcomm", true) &&
            !name.contains("_MediaTek", true) && !name.contains("_Google_Tensor", true) &&
            !name.contains("_Intel", true)
}
