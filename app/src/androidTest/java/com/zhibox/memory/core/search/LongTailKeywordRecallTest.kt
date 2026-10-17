package com.zhibox.memory.core.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.core.text.BigramTokenizer
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChunkEntity
import com.zhibox.memory.data.db.ChunkFtsEntity
import com.zhibox.memory.data.db.DocStatus
import com.zhibox.memory.data.db.DocumentEntity
import com.zhibox.memory.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-B2 long-tail 关键词召回回归（instrumentation；不依赖嵌入模型，可在无模型资产真机上跑）。
 *
 * 目的：固定 B2-1（关键词通道取回全部命中，不按 topK×3×4 截断）与 B2-2（bigram 覆盖口径）
 * 的**召回下限**：在 baseline 语料上，20 条 long-tail 罕见 bigram 查询每条都必须至少返回
 * 1 个关键词候选，且命中其标注文档对应的 chunk。
 *
 * 数据来源（均已内联，测试不读 docs/）：
 * - 查询：[docs/review/b2/longtail-queries.json]（20 条，rare bigram 如 "原隆"/"升青"）；
 * - 语料：[scripts/eval/corpus/01..50]（50 篇，文档 = 标题 + 正文，单 chunk；与
 *   docs/review/b2/baseline-longtail.json 的 hit 片段（每文档 1 chunk，char 0-N）一致）；
 *   另加 40 条与全部查询 bigram 无重叠的 filler chunk，凑满任务要求的 90 chunk 库规模
 *   （filler 不参与断言，只影响 FTS 表规模）；
 * - 基线指标：docs/review/b2/baseline-longtail-metrics.json（R@1=0.95、keyword_candidates_avg=0.8）。
 *
 * 10k 级性能基线见同目录 [Fts10kPerfTest]；本用例只断言召回正确性 + 输出命中数。
 */
class LongTailKeywordRecallTest {

    private lateinit var db: AppDatabase

    private data class LongTailQuery(val query: String, val expectedDoc: String)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun everyLongTailQuery_hasKeywordCandidateAndHitsExpectedDoc() = runBlocking {
        val expectedChunkByDoc = seedBaselineCorpus()
        assertEquals("基线语料应全部 seed 成功", CORPUS.size, expectedChunkByDoc.size)
        val chunkCount = db.chunkDao().count()
        val ftsCount = db.chunkDao().ftsCount()
        assertEquals("90 chunk 库口径：50 基线语料 + 40 filler", EXPECTED_CHUNK_COUNT, chunkCount)
        assertEquals("seed 后 chunk_fts 行数应与 chunk 一致（B1-1 去重口径）", chunkCount, ftsCount)
        println(
            "B2_LONGTAIL corpus_docs=${CORPUS.size} filler_chunks=$FILLER_CHUNK_COUNT " +
                "corpus_chunks=$chunkCount fts_rows=$ftsCount",
        )

        var withCandidate = 0
        var candidateTotal = 0
        for (case in LONG_TAIL_QUERIES) {
            // 与检索关键词通道同口径：BigramTokenizer OR MATCH + READY 过滤 + 全部命中（上限 5000）
            val orQuery = BigramTokenizer.toOrMatchQuery(case.query)
            val candidates = db.chunkDao().searchFtsOrdered(
                orQuery, MAX_KEYWORD_CANDIDATES, DocStatus.READY.value,
            )
            val expectedChunkId = expectedChunkByDoc[case.expectedDoc]
                ?: error("fixture 缺少标注文档：${case.expectedDoc}")
            val hit = candidates.contains(expectedChunkId)
            println(
                "B2_LONGTAIL query=${case.query} expected=${case.expectedDoc} " +
                    "keyword_candidates=${candidates.size} expected_hit=$hit",
            )
            assertEquals(
                "long-tail 查询必须至少返回 1 个关键词候选：${case.query}",
                1,
                if (candidates.isNotEmpty()) 1 else 0,
            )
            assertTrue(
                "long-tail 查询应命中标注文档 chunk：${case.query} -> ${case.expectedDoc}",
                hit,
            )
            if (candidates.isNotEmpty()) withCandidate++
            candidateTotal += candidates.size
        }
        assertEquals("20 条 long-tail 均应命中", LONG_TAIL_QUERIES.size, withCandidate)
        println(
            "B2_LONGTAIL_SUMMARY queries=${LONG_TAIL_QUERIES.size} all_with_candidate=true " +
                "avg_candidates=${candidateTotal.toDouble() / LONG_TAIL_QUERIES.size}",
        )
    }

    /** seed 50 篇语料，每篇 1 个 ready 文档 + 1 个 chunk；返回 documentId → chunkId。 */
    private suspend fun seedBaselineCorpus(): Map<String, Long> {
        db.sourceDao().upsert(SourceEntity("src_corpus", "eval-corpus", "", null, 0, "active"))
        val titleToDoc = LinkedHashMap<String, String>()
        val documents = ArrayList<DocumentEntity>(CORPUS.size)
        var t = 0L
        for ((idx, doc) in CORPUS.withIndex()) {
            val documentId = "doc_%03d".format(idx)
            val title = doc.first
            titleToDoc[title] = documentId
            documents += DocumentEntity(
                id = documentId,
                sourceId = "src_corpus",
                title = title,
                checksum = "sum_%03d".format(idx),
                parserId = "txt-utf8-v1",
                createdAt = t++,
                status = DocStatus.READY.value,
            )
        }
        db.documentDao().upsertAll(documents)

        val chunks = CORPUS.mapIndexed { idx, doc ->
            val content = doc.first + "\n\n" + doc.second
            ChunkEntity(
                documentId = "doc_%03d".format(idx),
                ordinal = 0,
                content = content,
                charStart = 0,
                charEnd = content.length,
                tokenEstimate = content.length,
            )
        }
        val chunkIds = db.chunkDao().insertAll(chunks)
        db.chunkDao().insertFts(
            chunkIds.mapIndexed { i, id ->
                ChunkFtsEntity(chunkId = id, bigramContent = BigramTokenizer.toFtsText(chunks[i].content))
            },
        )
        seedFillerChunks()
        return titleToDoc.mapValues { (title, documentId) ->
            requireNotNull(chunkIds[documents.indexOfFirst { it.id == documentId }]) { title }
        }
    }

    /**
     * 40 条 filler chunk（凑满 90 chunk 库规模）：内容使用与 20 条查询 bigram 完全无重叠的
     * 干支字符，仅扩大 FTS 表、不参与 long-tail 召回断言。
     */
    private suspend fun seedFillerChunks() {
        db.documentDao().upsert(
            DocumentEntity(
                id = "doc_filler",
                sourceId = "src_corpus",
                title = "filler",
                checksum = "sum_filler",
                parserId = "txt-utf8-v1",
                createdAt = 9_999,
                status = DocStatus.READY.value,
            ),
        )
        val contents = (0 until FILLER_CHUNK_COUNT).map { i ->
            "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥 占位段$i"
        }
        val ids = db.chunkDao().insertAll(
            contents.mapIndexed { i, text ->
                ChunkEntity(
                    documentId = "doc_filler",
                    ordinal = i,
                    content = text,
                    charStart = 0,
                    charEnd = text.length,
                    tokenEstimate = text.length,
                )
            },
        )
        db.chunkDao().insertFts(
            ids.mapIndexed { i, id ->
                ChunkFtsEntity(chunkId = id, bigramContent = BigramTokenizer.toFtsText(contents[i]))
            },
        )
    }

    private companion object {
        const val MAX_KEYWORD_CANDIDATES = 5000
        const val FILLER_CHUNK_COUNT = 40
        const val EXPECTED_CHUNK_COUNT = 90

        /** long-tail 20 条（来源 docs/review/b2/longtail-queries.json，内联避免测试读 docs/）。 */
        val LONG_TAIL_QUERIES = listOf(
        LongTailQuery("原隆", "01_青藏高原隆升"),
        LongTailQuery("升青", "01_青藏高原隆升"),
        LongTailQuery("鸟悬", "02_蜂鸟悬停机制"),
        LongTailQuery("停机", "02_蜂鸟悬停机制"),
        LongTailQuery("泉生", "03_深海热泉生态"),
        LongTailQuery("态一", "03_深海热泉生态"),
        LongTailQuery("电形", "04_闪电形成原理"),
        LongTailQuery("成原", "04_闪电形成原理"),
        LongTailQuery("鸟迁", "05_候鸟迁徙导航"),
        LongTailQuery("徙导", "05_候鸟迁徙导航"),
        LongTailQuery("山指", "06_火山指数VEI"),
        LongTailQuery("发烈", "06_火山指数VEI"),
        LongTailQuery("眠周", "07_睡眠周期研究"),
        LongTailQuery("期研", "07_睡眠周期研究"),
        LongTailQuery("礁白", "08_大堡礁白化"),
        LongTailQuery("化大", "08_大堡礁白化"),
        LongTailQuery("手流", "09_TLS握手流程"),
        LongTailQuery("流程", "09_TLS握手流程"),
        LongTailQuery("发模", "10_SQLite并发模型"),
        LongTailQuery("模型", "10_SQLite并发模型"),
        )

        // 基线语料 50 篇（来源 scripts/eval/corpus 目录，内联避免测试读仓库/设备文件）。
        // 注：不能用 KDoc/块注释包含 "corpus/*.txt"——其中的 "/*" 会开启 Kotlin 嵌套块注释。
        val CORPUS: List<Pair<String, String>> = listOf(
        "01_青藏高原隆升" to "青藏高原隆升\n\n青藏高原是印度板块与欧亚板块持续碰撞的产物，自约五千万年前开始隆升，至今平均海拔超过四千米，被称为世界屋脊。高原阻挡了来自印度洋的水汽，深刻改变了亚洲的季风格局。科学家通过花岗岩低温热年代学测定隆升历史，发现其加速隆升期集中在距今一千五百万年至八百万年之间。",
        "02_蜂鸟悬停机制" to "蜂鸟悬停机制\n\n蜂鸟是唯一能长时间悬停的鸟类，其翅膀在水平面内划出八字形轨迹，产生近似恒定的升力。悬停时心率可达每分钟一千二百次，新陈代谢率是人类的七十七倍。为支撑这种消耗，蜂鸟每天要访问上千朵花，摄入相当于自身体重两倍的花蜜。",
        "03_深海热泉生态" to "深海热泉生态\n\n一九七七年，科学家在加拉帕戈斯裂谷两千五百米深处发现了不依赖阳光的生命系统。深海热泉喷口的硫化氢滋养了化学自养细菌，它们构成管虫与盲虾等动物的食物链基础，推翻了阳光是一切生命能量来源的假设。喷口温度可达四百度。",
        "04_闪电形成原理" to "闪电形成原理\n\n闪电源于云内冰晶与霰粒碰撞导致的电荷分离，云上部带正电、下部带负电。当电位差达到每厘米三万伏的击穿阈值，空气电离形成导电通道，回击电流可达三万安培，瞬间将通道加热到三万摄氏度，激波轰鸣即雷声。全球每秒约发生四十四次闪电。",
        "05_候鸟迁徙导航" to "候鸟迁徙导航\n\n斑尾塍鹬保持不间断飞行世界纪录：从阿拉斯加到新西兰连续飞行约一万两千公里，历时十一天。研究表明候鸟综合运用地磁感应、太阳方位与星象进行导航，喙部含磁铁矿晶体的神经元可能感知磁场方向。迁徙前它们将体重增加近一倍作为能量储备。",
        "06_火山指数VEI" to "火山指数VEI\n\n火山爆发烈度采用爆炸指数衡量，从零到八级，每升一级喷发体积增加十倍。一九九一年皮纳图博火山爆发为六级，向平流层注入两千万吨二氧化硫，全球平均气温因此下降约零点五度。史上最大的六级以上喷发还包括一八一五年坦博拉火山，它造成了一八一二至一八一七年间全球气候异常。",
        "07_睡眠周期研究" to "睡眠周期研究\n\n人类睡眠由约九十分钟一个的周期构成，每周期经历浅睡、深睡与快速眼动三个阶段。深睡期生长激素分泌达到高峰，负责身体修复；快速眼动期则与记忆巩固和情绪调节相关。成年人每晚需要四到六个完整周期，长期低于六小时睡眠会使杏仁核反应增强百分之六十。",
        "08_大堡礁白化" to "大堡礁白化\n\n大堡礁是世界最大的珊瑚礁系统，绵延两千三百公里。珊瑚白化源于水温持续偏高导致共生虫黄藻排出，珊瑚失去百分之九十的能量来源。二零一六、二零一七与二零二零年三次大规模白化使浅水区珊瑚死亡率过半。水温超过三十度持续两周即触发白化。",
        "09_TLS握手流程" to "TLS握手流程\n\nTLS一点三将握手从两次往返压缩到一次：客户端在首个消息中直接发送密钥交换素材，服务器返回密钥与加密后的证书验证。前向安全由临时迪菲赫尔曼密钥交换保证，即使服务器私钥日后泄露，历史会话也无法解密。TLS一点三删除了RSA密钥交换与弱加密套件。",
        "10_SQLite并发模型" to "SQLite并发模型\n\nSQLite采用库级锁实现并发：多读单写。写入时通过回滚日志或预写日志保证原子性，预写日志模式下读与写可真正并行，代价是检查点合并开销。官方建议单写者场景使用，高并发写入应选择客户端服务器架构的数据库。自旋锁竞争激烈时性能急剧下降。",
        "11_向量量化PQ" to "向量量化PQ\n\n乘积量化将高维向量切成若干子段，每段独立聚类为码本，原始向量只需存储码本索引，七百六十八维向量可压缩三十二倍。代价是量化误差使召回率下降，通常配合倒排索引与重排使用：粗量化定位簇，簇内精确距离重排。这是十亿级向量检索的常见方案。",
        "12_协程调度器" to "协程调度器\n\nKotlin协程调度器将协程映射到线程池：调度器通过限制并发数实现背压，调度队列采用工作窃取算法平衡负载。挂起点是协作式的，协程主动让出线程而非被抢占，因此不存在线程上下文切换的内核开销。切换成本约纳秒级，比线程切换低三个数量级。",
        "13_电池健康管理" to "电池健康管理\n\n锂电池老化主要来自电极表面固体电解质界面膜增厚与活性锂损失。放电深度越深、倍率越高，循环寿命越短；在百分之二十到八十区间浅充浅放可将循环寿命延长三到五倍。高温存储同样加速衰减，四十度下满电存放一年容量损失约百分之三十五。",
        "14_HTTP3与QUIC" to "HTTP3与QUIC\n\nHTTP三是基于QUIC协议的新一代传输层，QUIC运行在UDP之上，把传输层握手与加密握手合并，首次连接只需一次往返。连接迁移靠连接标识而非四元组，从无线网络切换到蜂窝网络时连接不断。队头阻塞在流之间被隔离，丢包只阻塞所在流。",
        "15_差分隐私" to "差分隐私\n\n差分隐私通过在查询结果中加入校准噪声保护个体：隐私预算伊普西龙控制噪声与效用的平衡，预算越小隐私越强。苹果在键盘联想、谷歌在浏览器统计中部署了本地化差分隐私，数据在设备端加噪后才上传。组合定理保证多次查询的预算可累加控制。",
        "16_编译器优化层次" to "编译器优化层次\n\n现代编译器优化分三个层次：前端做语言无关的常量折叠与死代码消除；中端做过程间分析与循环不变量外提；后端针对指令集做寄存器分配与指令调度。链接时优化打破编译单元边界，让内联跨文件生效。减去热路径上百分之二十指令通常来自内联与逃逸分析联动。",
        "17_丝绸之路简史" to "丝绸之路简史\n\n丝绸之路正式开辟于西汉张骞两次出使西域之后，从长安经河西走廊、西域绿洲到达地中海，全长约七千公里。除丝绸外，造纸术、佛教与葡萄、苜蓿经此传播。唐代是鼎盛期，沿线驿站与商贸城市密布，威尼斯商人马可波罗即沿此路东行。",
        "18_活字印刷" to "活字印刷\n\n北宋庆历年间毕昇发明胶泥活字，早于古登堡金属活字约四百年。活字印刷对雕版的优势在于复用：一部大书雕版需数年，活字排版数周即成。但汉字数量庞大，单套活字需二十万枚以上，反而制约了普及，明清官方大规模印书仍以雕版为主。",
        "19_阿波罗计划" to "阿波罗计划\n\n阿波罗计划一九六一年启动，一九六九年阿波罗十一号实现载人登月，尼尔阿姆斯特朗成为第一个踏上月球的人。整个计划共六次成功登月，十二名宇航员留下月面足迹。土星五号火箭高一百一十米，起飞推力三千四百吨，至今仍是最大的运载火箭。",
        "20_大航海时代" to "大航海时代\n\n一四八八年迪亚士抵达好望角，一四九八年达伽马绕行非洲抵达印度，一五一九年麦哲伦船队开始环球航行。航海革命的技术前提是卡拉维尔帆船与星盘、象限仪的普及。新旧大陆物种交换改变了全球饮食：马铃薯、玉米传入旧大陆，小麦、马匹进入新大陆。",
        "21_工业革命蒸汽机" to "工业革命蒸汽机\n\n瓦特并非蒸汽机发明者，他一七六五年发明的分离冷凝器使纽科门机的煤耗降低百分之七十五，热效率从百分之一提升到百分之三。蒸汽动力摆脱了对水力位置依赖，工厂得以进入城市。一八三零年利物浦至曼彻斯特铁路通车，标志铁路时代开始。",
        "22_敦煌与藏经洞" to "敦煌与藏经洞\n\n一九零零年道士王圆箓在莫高窟发现藏经洞，内藏四至十一世纪文书约五万件。斯坦因、伯希和等探险队以低价购走大批文书，现分散于伦敦、巴黎等地。敦煌学因此成为国际显学。莫高窟现存洞窟七百三十五个，壁画四万五千平方米。",
        "23_近视防控" to "近视防控\n\n近视的主要环境风险是户外光照不足：视网膜多巴胺在强光下分泌增加，抑制眼轴增长。每天两小时以上户外活动可使儿童近视发生率降低约百分之四十。低浓度阿托品与角膜塑形镜是循证医学认可的干预手段，成年后激光手术只改变角膜形态而非眼轴。",
        "24_抗生素耐药" to "抗生素耐药\n\n抗生素耐药源于选择压力：不规范疗程留下耐药菌株，耐药基因通过质粒在细菌间水平转移。耐甲氧西林金黄色葡萄球菌与耐碳青霉烯肠杆菌已被列为重点威胁。世界卫生组织估计到二零五零年耐药感染每年可能致千万人死亡。避免滥用与完成疗程是关键措施。",
        "25_血糖指数" to "血糖指数\n\n血糖指数衡量食物升糖速度，葡萄糖为一百。低GI食物低于五十五，如燕麦、豆类；白米饭约八十属高GI。烹饪与加工显著改变GI：米饭放冷后抗性淀粉增加，GI下降。糖尿病患者饮食管理的第一原则是控制总碳水量而非单纯追求低GI。",
        "26_运动与乳酸" to "运动与乳酸\n\n乳酸并非疲劳废物，而是可被肌肉与心脏氧化供能的燃料，也是肝脏糖异生的原料。血乳酸阈对应运动强度从有氧过渡到无氧供能的分界，训练有素的马拉松选手乳酸阈可达最大摄氧量的百分之八十五。运动后酸痛主要由离心收缩的微损伤引起，与乳酸无关。",
        "27_疫苗接种原理" to "疫苗接种原理\n\n疫苗通过模拟病原体训练免疫系统：减毒活疫苗产生接近自然感染的免疫记忆，灭活疫苗安全性更高但需多剂次加强。mRNA疫苗将编码刺突蛋白的信使核糖核酸送入细胞，由人体自行合成抗原，研发周期缩短到数月。群体免疫阈值取决于基本传染数。",
        "28_咖啡因代谢" to "咖啡因代谢\n\n咖啡因半衰期约五小时，由肝脏细胞色素酶代谢，个体基因差异使代谢速度相差四倍。腺苷受体阻断是其提神机制，长期摄入会使受体上调，停用后出现头痛等戒断反应。下午两点后摄入的咖啡因仍有一半在晚七点存留，影响深睡比例。",
        "29_低温慢煮" to "低温慢煮\n\n低温慢煮将食材密封后恒温水浴，牛排五十三度一小时中心恰好达到三分熟，温度均匀性远胜明火。蛋白质变性在特定温度区间完成：肌球蛋白五十度、肌动蛋白六十六度，超过七十度胶原蛋白才水解为明胶。真空袋防止水分流失，损耗率低于百分之五。",
        "30_衣物污渍处理" to "衣物污渍处理\n\n蛋白类污渍如血渍需冷水清洗，热水会使蛋白质变性固着；油脂污渍相反需要温水和表面活性剂。单宁类如红酒先用盐吸附再冷水处理。含酶洗衣粉的蛋白酶只对蛋白污渍有效，氯漂白剂会损伤蛋白质纤维如羊毛蚕丝，只适用于棉麻。",
        "31_汽车冬季养护" to "汽车冬季养护\n\n冬季应更换低粘度机油，五零粘度等级在零下三十五度仍可泵送；玻璃水须用防冻型，普通水结冰会胀裂水壶与管路。电瓶容量在零下十八度衰减百分之五十，启动前可先通电预热油泵。胎压随气温每降十度降低约零点一巴，需及时补充。",
        "32_家庭网络布线" to "家庭网络布线\n\n千兆内网建议六类网线，六类线在五十五米内可跑万兆。路由器摆放避开微波炉与金属柜，两点四吉赫频段穿墙好但干扰多，五吉赫快但衰减大。光猫桥接加软路由拨号可避免运营商设备性能瓶颈。Mesh组网的回程走独立频段时损耗最小。",
        "33_记忆力技巧" to "记忆力技巧\n\n间隔重复利用遗忘曲线安排复习：学习后一天、三天、七天、二十一天各复习一次，记忆保持率可超过百分之九十。主动回忆比重读有效两倍，测试效应是认知心理学最稳健的发现之一。记忆宫殿法将抽象信息绑定到熟悉空间位置，适合有序列表。",
        "34_基础理财原则" to "基础理财原则\n\n应急金应为三到六个月支出，存于随时可取的货币基金。五零三零二零预算法将税后收入分为必要开支、储蓄投资与弹性消费。指数基金定投利用波动摊薄成本，长期年化收益预期百分之七左右；复利公式为本金乘以一加利率的期数次方。",
        "35_撒哈拉之眼" to "撒哈拉之眼\n\n理查特结构位于毛里塔尼亚撒哈拉沙漠，直径约五十公里，从太空清晰可见，曾长期被当作陨石坑。地质研究显示它是深度侵蚀的对称背斜，同心圆状岩层由差异风化造成。该区域古老岩层最老达二十亿年，是研究西非克拉通的窗口。",
        "36_贝加尔湖" to "贝加尔湖\n\n贝加尔湖是世界上最深的湖泊，最深处一千六百四十二米，淡水储量占全球液态淡水五分之一。湖龄两千五百万年，由裂谷形成，仍在以每年两厘米扩展。特有物种贝加尔海豹是唯一完全生活在淡水中的海豹，其祖先沿河流溯入。",
        "37_马里亚纳海沟" to "马里亚纳海沟\n\n马里亚纳海沟最深处为挑战者深渊，约一万零九百米，压强相当于一千一百个大气压。一九六零年雅克皮卡德与唐沃尔什乘的里雅斯特号首次载人下潜。海沟底部热液喷口生存着以化能合成细菌为基础的生态系统，狮子鱼最深见于八千余米。",
        "38_富士山与樱花" to "富士山与樱花\n\n富士山海拔三七七六米，是日本最高峰，最近一次喷发在一七零七年。山顶冬季积雪至次年六月。樱花前线从南向北推进，东京平均在三月下旬开花，花期仅约一周。江户时代培育的染井吉野占如今樱花的八成，为无性繁殖的单株后代。",
        "39_格陵兰冰盖" to "格陵兰冰盖\n\n格陵兰冰盖面积一百七十一万平方公里，最厚处超过三公里，全部融化将使全球海平面上升约七米。冰盖通过冰川入海与表面融化两种方式损失质量，二零一九年单年融化量达五千三百亿吨创纪录。冰芯记录可回溯十二万年前的气候。",
        "40_English: Panama Canal" to "English: Panama Canal\n\nThe Panama Canal connects the Atlantic and Pacific Oceans through a 82-kilometer waterway, saving ships a 13,000-kilometer journey around South America. Gatun Lake sits 26 meters above sea level, so locks lift ships in stages. The 2016 expansion doubled capacity with new wider locks for neopanamax vessels. Annual traffic is about fourteen thousand transits.",
        "41_混合 京都庭园" to "混合 京都庭园\n\n京都龙安寺的石庭是枯山水代表：十五块石头分组置於白砂之上，任何角度最多同时看到十四块。禅意庭园以砂纹模拟水波，静止构图引导观想。桂离宫则融合回游式池泉与书院造建筑，被柯布西耶与格罗皮乌斯称为 the essence of Japanese architecture，是 modernism 的重要参照。",
        "42_混合 茶马古道" to "混合 茶马古道\n\n茶马古道以茶马互市得名，主干道从云南普洱经丽江、香格里拉入藏，海拔落差逾三千米。酥油茶提供高原必需的 fat 与热量，tea 中的多酚帮助消化 meat-heavy 饮食。马帮铜铃制度以铃声传递会车信号，窄崖路段单侧让行。抗战期间滇藏道成为物资生命线。",
        "43_English: Fibonacci Sequence" to "English: Fibonacci Sequence\n\nThe Fibonacci sequence starts with zero and one, each subsequent number being the sum of the previous two. Ratios of consecutive terms converge to the golden ratio, approximately 1.618. Sunflower seed spirals typically follow 34 and 55 count helices, both Fibonacci numbers. In 1202 Leonardo of Pisa published the sequence in Liber Abaci, introducing Hindu-Arabic numerals to Europe.",
        "44_English: Roman Roads" to "English: Roman Roads\n\nThe Roman road network stretched over eighty thousand kilometers, connecting Rome to provinces from Britain to Syria. Roads were built in four layers, crowned with polygonal stone slabs, and cambered for drainage. Milestones marked distances to Rome, and chariots could travel over eighty kilometers per day on them, a speed unmatched until the railway era.",
        "45_English: Black Holes" to "English: Black Holes\n\nA black hole forms when matter collapses beyond its Schwarzschild radius, where escape velocity exceeds the speed of light. The first image of a black hole shadow, in galaxy M87, was released in 2019 by the Event Horizon Telescope, which combined radio dishes into an Earth-sized virtual aperture. Stellar-mass black holes weigh several times the sun, while supermassive ones reach billions of solar masses.",
        "46_English: CRISPR" to "English: CRISPR\n\nCRISPR-Cas9 acts as programmable molecular scissors: a guide RNA matches a DNA sequence and the Cas9 enzyme cuts there. It was adapted from a bacterial immune system that stores fragments of invading viruses. Emmanuelle Charpentier and Jennifer Doudna won the 2020 Nobel Prize in Chemistry for its development, and base-edited therapies reached patients in 2022.",
        "47_English: Honeybee Dance" to "English: Honeybee Dance\n\nHoneybees communicate food locations through the waggle dance: the angle of the dance relative to vertical encodes direction relative to the sun, and dance duration encodes distance. Karl von Frisch decoded this language and won a Nobel Prize in 1973. A waggle run of one second roughly corresponds to a one-kilometer flight.",
        "48_English: Van Gogh" to "English: Van Gogh\n\nVincent van Gogh painted The Starry Night in June 1889 from his asylum room in Saint-Remy, working from memory and imagination rather than direct observation. He sold very few paintings during his lifetime and died in 1890 at thirty-seven. His letters to his brother Theo, over eight hundred of them, form a detailed record of his artistic development.",
        "49_混合 量子纠缠通信" to "混合 量子纠缠通信\n\nquantum entanglement 中两粒子状态关联，measure 一方立即确定另一方，爱因斯坦称之为 spooky action at a distance。墨子号卫星实现了千公里级 entangled photon distribution，配合 BB84 协议可构建 eavesdropping 可察觉的 quantum key distribution。城际 quantum network 已在 Beijing-Shanghai 干线试运行，金融与政务 data 传输率先应用。",
        "50_混合 高铁轮轨" to "混合 高铁轮轨\n\n中国高铁采用轮轨而非磁悬浮 maglev 作为干线技术，crucial 因素是 network 兼容性：列车可下既有线进站。CR400 复兴号运营时速 350 公里，world record 试验速度 605 公里。转向架 pivot 与 air spring 抑制蛇行失稳，pantograph 离线率低于百分之零点一保证受流质量。",
        )
    }
}
