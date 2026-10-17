package com.zhibox.memory.benchmark

import android.content.Context
import com.zhibox.memory.core.ingest.IngestRepository
import com.zhibox.memory.core.search.SearchService
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.JobStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * W3-4 生产路径基准（§9.2）：同一 runner 覆盖导入与检索两段，跨三档设备可复现。
 *
 * 1. 导入吞吐：内置 10 篇中文文档（每篇 ~450 字，固定内容）→ importStaged → 轮询完成
 *    → 墙钟总时与篇均、块数；
 * 2. 资源：/proc/self/status 的 VmHWM（进程峰值 RSS）在导入后与检索后各读一次；
 * 3. 检索延迟：10 条固定查询 × 5 轮 → 每查询 p50 与总体 p50/p95（含查询嵌入）。
 *
 * 输出：filesDir/eval_results/prodbench_<ts>.json
 * 自动化：am start --ez autostart_prodbench true（每设备一次；同模型可比）
 */
object ProductionBenchRunner {

    private val DOCS: List<Pair<String, String>> = listOf(
        "区块链共识" to "区块链共识算法解决无中心环境下的记账一致性问题。工作量证明通过算力竞争出块，比特币将其间隔控制在约十分钟，能耗是主要代价。权益证明按持币量与时间随机选取验证者，能耗降低约百分之九十九点九。最终性指交易不可逆转的确认状态，混合共识先快速出块再异步敲定。分片与并行执行是扩容主线，但跨片通信与数据可用性仍是难点。",
        "新能源汽车" to "新能源汽车的三电系统指电池、电机与电控。磷酸铁锂电池能量密度约每公斤一百六十瓦时，循环寿命超过三千次但低温性能弱；三元电池密度更高但热稳定性差。永磁同步电机效率可达百分之九十六。热管理决定冬季续航，热泵空调比PTC加热节省约一半能耗。快充伤电池的观点被证明主要与深充深放相关而非充电速度本身。",
        "古希腊哲学" to "苏格拉底以诘问法追问定义，认为未经省察的人生不值得过。柏拉图建立学园，理念论主张可感世界只是理念世界的摹本，洞穴寓言说明认识的转向。亚里士多德百科全书式地分科研究，创立形式逻辑，中道伦理学主张德性是两个极端之间的合适状态。三人共同奠定西方理性传统的方法论基础。",
        "茶叶加工" to "茶叶按加工氧化程度分六大类：绿茶不发酵，白茶微发酵，乌龙半发酵，红茶全发酵，黑茶后发酵。茶多酚在多酚氧化酶作用下转化为茶黄素与茶红素，决定汤色与滋味。杀青以高温钝化酶活保留绿色。普洱生茶自然陈化数十年，滋味随年份转化，仓储湿度与通风是关键变量，过湿会霉变。",
        "城市规划" to "现代城市规划的邻里单位思想主张以小学服务半径划定居住单元，减少穿越交通。公共交通导向开发以站点为中心高密度混合布局。窄马路密路网比宽马路疏路网更利于步行与商业活力。城市热岛效应使城区气温比郊区高一到三度，增加绿化覆盖率与屋顶绿化可缓解，水体与通风廊道同样有效。",
        "免疫记忆" to "适应性免疫的核心是记忆：初次感染后，记忆B细胞与记忆T细胞长期驻留于骨髓与淋巴组织。再次遇到同一病原体时，记忆细胞迅速克隆扩增，抗体滴度在数天内升高数十倍，这就是疫苗加强针的原理。记忆细胞半衰期因病原体而异，麻疹可达终身，破伤风约十年，因此后者需要定期加强。",
        "摄影曝光" to "曝光三角指光圈、快门与感光度的组合。光圈控制进光量与景深，f值每减半进光翻倍；快门决定凝固或拖影，安全快门约为焦距倒数；感光度提升伴随噪点增加，现代全画幅在三千二百度仍可用。向右曝光策略主张在不过曝的前提下尽量提亮，为后期保留暗部信噪比。",
        "货币史" to "货币形态从贝壳、金属称量、铸币、纸币演进到电子支付。交子出现于北宋四川，是世界最早的纸币，起于铁钱笨重的民间凭证。金本位下货币发行受黄金储备约束，一九七一年布雷顿森林体系解体后进入信用货币时代。央行数字货币试图在数字时代重建可编程的法币，离线支付是关键技术挑战。",
        "声音掩蔽" to "人耳的听觉掩蔽效应指一个声音使另一个声音不可察觉：频域掩蔽发生在邻近频率，时域掩蔽可先于或后于强信号数十毫秒。MP3与AAC等感知编码据此丢弃被掩蔽的成分，把码率压缩十倍而听感几乎不变。安静环境下掩蔽减弱，低码率压缩的缺陷在深夜更易被察觉。",
        "登山适应" to "高海拔适应的核心是通气量提升与红细胞增多。进入海拔两千五百米以上后，外周化学感受器驱动呼吸加深加快，血液偏碱随后由肾脏代偿。促红细胞素在数天后升高，两到三周血红蛋白明显增加。上升过快则会患急性高山病，头痛恶心是信号，下降是根本治疗，乙酰唑胺可用于预防。",
    )

    private val QUERIES = listOf(
        "权益证明为什么省电", "热泵空调冬天省多少能耗", "洞穴寓言讲了什么",
        "茶多酚转化成什么物质", "窄马路密路网有什么好处", "加强针的原理是什么",
        "向右曝光为什么要提亮", "最早的纸币出现在哪里", "MP3为什么能压缩十倍",
        "高原反应怎么预防和治疗",
    )

    private fun vmHwmKb(): Long =
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)?.toLongOrNull() ?: -1

    /** gpuMode：PathPreferences.GPU_OFF / GPU_ON / GPU_AUTO（写入偏好，导入与检索全程同 delegate） */
    suspend fun run(context: Context, gpuMode: String = com.zhibox.memory.core.model.PathPreferences.GPU_OFF): File =
        withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        com.zhibox.memory.core.model.PathPreferences.setGpuMode(appContext, gpuMode)
        com.zhibox.memory.core.embedding.EmbeddingRuntime.shutdown()
        val repo = IngestRepository(appContext)
        val search = SearchService(appContext)

        // ---- 1. 导入吞吐 ----
        val staging = File(appContext.filesDir, "import-staging").apply { mkdirs() }
        DOCS.forEachIndexed { i, (title, body) ->
            File(staging, "pb_${i + 1}_${title}.txt").writeText(title + "\n\n" + body + "\n")
        }
        val t0 = System.currentTimeMillis()
        repo.importStaged()
        var imported = 0
        while (true) {
            delay(2000)
            val db = repo.db
            imported = db.documentDao().readyCount(DocStatus.READY.value)
            val active = db.ingestJobDao().activeCount(JobStage.ACTIVE_VALUES)
            if (active == 0) break
        }
        val importMs = System.currentTimeMillis() - t0
        val chunkTotal = repo.db.chunkDao().count()
        val rssAfterImport = vmHwmKb()

        // ---- 2. 检索延迟（含查询嵌入）----
        val qRows = JSONArray()
        val all = mutableListOf<Long>()
        for (q in QUERIES) {
            val times = mutableListOf<Long>()
            repeat(5) {
                val s = System.currentTimeMillis()
                search.search(q, topK = 10)
                times += System.currentTimeMillis() - s
            }
            all += times
            qRows.put(JSONObject().put("query", q)
                .put("p50_ms", times.sorted()[2]))
        }
        val sortedAll = all.sorted()
        val rssAfterSearch = vmHwmKb()

        val out = JSONObject()
            .put("experiment", "production_bench")
            .put("model", com.zhibox.memory.core.embedding.EmbeddingRuntime.findModel(appContext)?.name)
            .put("gpu_mode", gpuMode)
            .put("gpu_effective", com.zhibox.memory.core.embedding.EmbeddingRuntime.effectiveGpu)
            .put("gpu_fallback", com.zhibox.memory.core.embedding.EmbeddingRuntime.gpuFallbackReason ?: "")
            .put("device", JSONObject()
                .put("model", android.os.Build.MODEL)
                .put("soc", if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "")
                .put("android", android.os.Build.VERSION.RELEASE))
            .put("import", JSONObject()
                .put("docs", DOCS.size)
                .put("chunks", chunkTotal)
                .put("wall_ms", importMs)
                .put("ms_per_doc", importMs / DOCS.size)
                .put("rss_hwm_kb_after_import", rssAfterImport))
            .put("search", JSONObject()
                .put("queries", QUERIES.size)
                .put("rounds", 5)
                .put("p50_ms", sortedAll[sortedAll.size / 2])
                .put("p95_ms", sortedAll[(sortedAll.size * 95 / 100).coerceAtMost(sortedAll.lastIndex)])
                .put("per_query", qRows)
                .put("rss_hwm_kb_after_search", rssAfterSearch))

        val dir = File(appContext.filesDir, "eval_results").apply { mkdirs() }
        val outFile = File(dir, "prodbench_${System.currentTimeMillis()}.json")
        outFile.writeText(out.toString(2))
        outFile
    }
}
