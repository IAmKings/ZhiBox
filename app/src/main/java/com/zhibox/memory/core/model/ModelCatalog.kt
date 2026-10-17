package com.zhibox.memory.core.model

import android.os.Build
import java.io.File
import java.util.Locale

/**
 * 模型目录（W4 模型商店）：内置注册表 + SOC 感知推荐。
 *
 * **B7 核心收敛（保守版 A1）**：模型路线收敛为 EGemma2 三强度 + SM8750 NPU 变体——
 * - 440M（路径 A：MediaPipe UniversalEmbedder，默认/均衡档）
 * - 270M（litertlm EmbeddingEngine「路径 B」引擎，轻量档）
 * - 740M（路径 A UE，Adreno 旗舰质量上限档）
 * - 440M/740M 的 `_Qualcomm_SM8750` NPU 特化版（验证门后启用）
 *
 * 已下线的旧路线：EmbeddingGemma `.task`（Path C / MediaPipe TextEmbedder + tasks-text）、
 * Granite、LFM2.5（Path B 引擎的 granite/LFM 模型）。原因：APK 体积（64.5MB→目标 ≈49MB）、
 * 技术栈收敛（EGemma2 × UE + litertlm 引擎两条通道）与评测基线（440M 220 查询 R@1=.9636）。
 * 旧库中这些空间由 `IngestRepository.legacySpacesReport()` 识别，UI 提示"重新导入"即可迁移。
 *
 * Entry 三类状态：
 * - verified=true：三档真机验证通过（440M 通用），可直接启用；
 * - verified=false 且 backend=NPU_SOC：实验——验证门后启用（Backend.NPU 未证，GPU 挂死前车之鉴）；
 * - verified=false 且 backend=CPU_GENERIC：实验（270M/740M 可载性未全量验证）。
 *
 * 空间语义：特化版与通用版按文件名各自独立空间（NPU 与通用向量等价性未知，保守隔离待 G1D）。
 */
object ModelCatalog {

    /**
     * B4-3 维度唯一回退：catalog 未收录模型（用户自投放/改造）的期望输出维度。
     * 已收录模型一律以 [Entry.dims] 为准；调用方统一用 [dimsFor]，不要再各自硬编码 768。
     */
    const val DEFAULT_DIMS: Int = 768

    enum class Backend { CPU_GENERIC, NPU_SOC }

    data class Entry(
        val fileName: String,
        val url: String,          // hf-mirror resolve 直链
        val sha256: String,
        val sizeBytes: Long,
        val dims: Int,            // 期望输出维度（校验用；无记录时回退 [DEFAULT_DIMS]）
        val backend: Backend,
        val socTags: List<String> = emptyList(), // 归一化 SOC 标识（NPU 特化版）
        val verified: Boolean,
        val label: String,
        val description: String,
    )

    private const val HF = "https://hf-mirror.com/litert-community"

    val ENTRIES: List<Entry> = listOf(
        Entry(
            fileName = "embeddinggemma-2-text-vision-440m.litertlm",
            url = "$HF/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm",
            sha256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0",
            sizeBytes = 387_710_976,
            dims = 768,
            backend = Backend.CPU_GENERIC,
            verified = true,
            label = "EGemma2-440M",
            description = "G1 判定门基准模型；8 Elite 上 GPU 加速 6.2×（长文本性能上限）",
        ),
        Entry(
            fileName = "embeddinggemma-2-text-270m.litertlm",
            url = "$HF/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
            sha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
            sizeBytes = 164_626_432,
            dims = 768,
            backend = Backend.CPU_GENERIC,
            verified = false, // 实验：路径 B 引擎（text-only）可载性未证——曾被路径 A 拒载
            label = "EGemma2-270M（轻量）",
            description = "最小文本模型；未验证，启用前跑验证门",
        ),
        Entry(
            fileName = "embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm",
            url = "$HF/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m_Qualcomm_SM8750.litertlm",
            sha256 = "c5c05cb59833541a2d0d9374c7b50d2c3a406a4a84c85762775e5f9c6d96cd0c",
            sizeBytes = 446_611_456,
            dims = 768,
            backend = Backend.NPU_SOC,
            socTags = listOf("qualcomm_sm8750"),
            verified = false, // 实验：Backend.NPU 未验证（GPU 挂死前车之鉴）
            label = "EGemma2-440M · SM8750 NPU 特化",
            description = "骁龙 8 Elite NPU 编译版；仅 SM8750 可用，验证门后启用",
        ),
        Entry(
            fileName = "embeddinggemma-2-740m.litertlm",
            url = "$HF/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm",
            sha256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c",
            sizeBytes = 484_622_336,
            dims = 768,
            backend = Backend.CPU_GENERIC,
            verified = false, // 实验
            label = "EGemma2-740M",
            description = "最大通用版（text+vision+audio）；文本嵌入质量上限候选，未验证",
        ),
        Entry(
            fileName = "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm",
            url = "$HF/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m_Qualcomm_SM8750.litertlm",
            sha256 = "ac1973c0195dc6e28a621651f2f5e04d35fdc7a7b11cdbbe94f29f5dccb57996",
            sizeBytes = 543_522_816,
            dims = 768,
            backend = Backend.NPU_SOC,
            socTags = listOf("qualcomm_sm8750"),
            verified = false,
            label = "EGemma2-740M · SM8750 NPU 特化",
            description = "740M NPU 编译版；仅 SM8750，验证门后启用",
        ),
    )

    fun byFileName(fileName: String): Entry? = ENTRIES.firstOrNull { it.fileName == fileName }

    /**
     * B4-3 维度单一来源：已收录模型返回 catalog dims，未收录回退 [DEFAULT_DIMS]。
     * 导入期望维度校验与 embedding_meta.dimensions 回填必须都走这里，避免 768 硬编码分散。
     */
    fun dimsFor(fileName: String): Int = byFileName(fileName)?.dims ?: DEFAULT_DIMS

    // ---- SOC 检测与推荐 ----

    /** Build.SOC_MODEL/HARDWARE 归一化为 socTag 形态（如 "qualcomm_sm8750"）；小写+连续非字母数字合一 */
    fun detectSocTag(): String {
        val raw = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Build.SOC_MODEL, Build.HARDWARE, Build.MODEL)
        } else {
            listOf(Build.HARDWARE, Build.MODEL)
        }
        val normalized = raw.map { normalize(it) }.filter { it.isNotBlank() }
        // 优先返回含厂商前缀的最具体标识
        return normalized.firstOrNull { it.contains("sm8") || it.contains("mt") || it.contains("tensor") }
            ?: normalized.firstOrNull().orEmpty()
    }

    /** B4-3：Locale.ROOT 归一化，避免土耳其等 locale 下 "I"→"ı" 导致 SOC 标识/哈希比较漂移 */
    fun normalize(s: String): String =
        s.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')

    fun socMatches(entry: Entry, socTag: String): Boolean =
        entry.socTags.any { tag -> socTag.contains(tag) || tag.contains(socTag) && socTag.isNotBlank() }

    /** 本机可用的 NPU 特化 entries */
    fun npuEntriesFor(socTag: String): List<Entry> =
        ENTRIES.filter { it.backend == Backend.NPU_SOC && socMatches(it, socTag) }

    /** 性能偏好 */
    const val PERF_BALANCED = "balanced"
    const val PERF_FAST = "fast"
    const val PERF_LIGHT = "light"

    /**
     * 推荐下载 entry（B7 收敛后仅 EGemma2 三强度）：
     * - 均衡（默认）→ 440M 通用（EGemma2 × UE 通道，GPU 白名单已含）
     * - 极速 → NPU 验证门通过的特化版；否则 Adreno 命中 740M 通用（UE-GPU 两档真机验证（440M 通用/特化）+ 270M/740M 实验），
     *   非 Adreno 回退 440M（Mali GPU 收益为负，见 §九）
     * - 轻量 → 270M（litertlm 引擎通道）
     */
    fun isAdreno(socTag: String): Boolean = socTag.contains("sm8") || socTag.contains("sm_8")

    fun recommend(perfMode: String, socTag: String): Entry? = when (perfMode) {
        // 验证门（P1-7）：NPU 特化版只有 catalog 标记 verified=true（三档真机验证通过）才进推荐位。
        // 这里消费 Entry.verified 字段而不是恒 false 的死函数——当前 NPU 条目全为实验状态，
        // 因此等价于"特化版不推荐"（2026-10-16 W11 实测 NPU 挂死 >7min 的结论）。
        PERF_FAST -> npuEntriesFor(socTag).firstOrNull { it.dims == DEFAULT_DIMS && it.verified }
            ?: if (isAdreno(socTag)) byFileName("embeddinggemma-2-740m.litertlm")
            else byFileName("embeddinggemma-2-text-vision-440m.litertlm")
        PERF_LIGHT -> byFileName("embeddinggemma-2-text-270m.litertlm")
        else -> byFileName("embeddinggemma-2-text-vision-440m.litertlm")
    }

    /** 推荐 reason 文案（设置页显示） */
    fun recommendReason(entry: Entry, socTag: String): String = when {
        entry.backend == Backend.NPU_SOC ->
            "检测到 ${socTag} 的 NPU 特化版（实验——启用前需验证门）"
        entry.fileName.contains("740m") -> "Adreno 旗舰质量上限档：UE-GPU 32-44ms（两档真机验证（440M 通用/特化）+ 270M/740M 实验）"
        entry.fileName.contains("440m") -> "EGemma2 × UE 通道，GPU 两档真机验证（440M 通用/特化）+ 270M/740M 实验（旗舰 6.2×）；与 ML Kit NPU 演进对齐"
        entry.fileName.contains("270m") -> "最小体积轻量档（litertlm 引擎通道，未全量验证）"
        else -> "EGemma2 家族模型"
    }

    /** 文件在磁盘上的实际大小与 entry 是否吻合（容差 1%，防截断/换版） */
    fun sizeMatches(entry: Entry, file: File): Boolean {
        if (!file.isFile) return false
        val expected = entry.sizeBytes
        val actual = file.length()
        return actual in (expected * 99 / 100)..(expected * 101 / 100)
    }
}
