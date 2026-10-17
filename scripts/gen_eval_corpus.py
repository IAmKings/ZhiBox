#!/usr/bin/env python3
"""
W3 评测集生成器：40 张知识卡语料 + 200 条五类标注查询（事实/同义/多跳/精确/对抗）。

产出：
  scripts/eval/corpus/NN_标题.txt   —— 导入用语料（文档标题=文件名去扩展）
  scripts/eval/evalset.json         —— {"queries":[{id,q,type,lang,relevant:[title...]}]}

标注原则：每条查询只应命中其标注卡片（实体/数字均跨卡唯一）；对抗类来自语料外主题池。
复现：python3 scripts/gen_eval_corpus.py
"""
import json
import os
import random

random.seed(42)

# (文件名标题, 正文, [(type, lang, query), ...])
CARDS = [
    # ── 自然科学 ──────────────────────────────────────────────
    ("青藏高原隆升", "青藏高原是印度板块与欧亚板块持续碰撞的产物，自约五千万年前开始隆升，至今平均海拔超过四千米，被称为世界屋脊。高原阻挡了来自印度洋的水汽，深刻改变了亚洲的季风格局。科学家通过花岗岩低温热年代学测定隆升历史，发现其加速隆升期集中在距今一千五百万年至八百万年之间。", [
        ("fact", "zh", "青藏高原平均海拔是多少"),
        ("synonym", "zh", "世界屋脊是怎么形成的，有多高"),
        ("multihop", "zh", "科学家用什么方法测定高原隆升历史，测得的加速期是哪段时间"),
        ("exact", "zh", "印度板块与欧亚板块碰撞"),
    ]),
    ("蜂鸟悬停机制", "蜂鸟是唯一能长时间悬停的鸟类，其翅膀在水平面内划出八字形轨迹，产生近似恒定的升力。悬停时心率可达每分钟一千二百次，新陈代谢率是人类的七十七倍。为支撑这种消耗，蜂鸟每天要访问上千朵花，摄入相当于自身体重两倍的花蜜。", [
        ("fact", "zh", "蜂鸟悬停时心率达到多少"),
        ("synonym", "zh", "哪种鸟可以在空中停留不动，代谢为什么那么高"),
        ("multihop", "zh", "蜂鸟为什么每天要访问上千朵花"),
        ("exact", "zh", "八字形轨迹"),
    ]),
    ("深海热泉生态", "一九七七年，科学家在加拉帕戈斯裂谷两千五百米深处发现了不依赖阳光的生命系统。深海热泉喷口的硫化氢滋养了化学自养细菌，它们构成管虫与盲虾等动物的食物链基础，推翻了阳光是一切生命能量来源的假设。喷口温度可达四百度。", [
        ("fact", "zh", "深海热泉喷口温度最高多少度"),
        ("synonym", "zh", "海底黑烟囱周围没有阳光为什么还有生物"),
        ("multihop", "zh", "一九七七年的哪个发现推翻了生命依赖阳光的假设"),
        ("exact", "zh", "硫化氢 化学自养细菌"),
    ]),
    ("闪电形成原理", "闪电源于云内冰晶与霰粒碰撞导致的电荷分离，云上部带正电、下部带负电。当电位差达到每厘米三万伏的击穿阈值，空气电离形成导电通道，回击电流可达三万安培，瞬间将通道加热到三万摄氏度，激波轰鸣即雷声。全球每秒约发生四十四次闪电。", [
        ("fact", "zh", "闪电回击电流有多少安培"),
        ("synonym", "zh", "打雷时的闪光是怎么产生的，为什么那么响"),
        ("multihop", "zh", "云里冰晶碰撞和雷声之间是什么关系"),
        ("exact", "zh", "每厘米三万伏"),
    ]),
    ("候鸟迁徙导航", "斑尾塍鹬保持不间断飞行世界纪录：从阿拉斯加到新西兰连续飞行约一万两千公里，历时十一天。研究表明候鸟综合运用地磁感应、太阳方位与星象进行导航，喙部含磁铁矿晶体的神经元可能感知磁场方向。迁徙前它们将体重增加近一倍作为能量储备。", [
        ("fact", "zh", "斑尾塍鹬不间断飞行多少公里"),
        ("synonym", "zh", "候鸟长途飞行靠什么辨别方向"),
        ("multihop", "zh", "斑尾塍鹬迁徙前为什么要增重近一倍"),
        ("exact", "zh", "磁铁矿晶体"),
    ]),
    ("火山指数VEI", "火山爆发烈度采用爆炸指数衡量，从零到八级，每升一级喷发体积增加十倍。一九九一年皮纳图博火山爆发为六级，向平流层注入两千万吨二氧化硫，全球平均气温因此下降约零点五度。史上最大的六级以上喷发还包括一八一五年坦博拉火山，它造成了一八一二至一八一七年间全球气候异常。", [
        ("fact", "zh", "皮纳图博火山爆发是几级"),
        ("synonym", "zh", "衡量火山喷发强度的等级怎么算"),
        ("multihop", "zh", "哪次火山爆发让全球气温下降了半度，喷发等级是多少"),
        ("exact", "zh", "坦博拉火山 一八一五年"),
    ]),
    ("睡眠周期研究", "人类睡眠由约九十分钟一个的周期构成，每周期经历浅睡、深睡与快速眼动三个阶段。深睡期生长激素分泌达到高峰，负责身体修复；快速眼动期则与记忆巩固和情绪调节相关。成年人每晚需要四到六个完整周期，长期低于六小时睡眠会使杏仁核反应增强百分之六十。", [
        ("fact", "zh", "人的一个睡眠周期大约多少分钟"),
        ("synonym", "zh", "睡觉时身体在什么阶段自我修复"),
        ("multihop", "zh", "长期睡眠不足六小时对情绪中枢有什么影响"),
        ("exact", "zh", "快速眼动 记忆巩固"),
    ]),
    ("大堡礁白化", "大堡礁是世界最大的珊瑚礁系统，绵延两千三百公里。珊瑚白化源于水温持续偏高导致共生虫黄藻排出，珊瑚失去百分之九十的能量来源。二零一六、二零一七与二零二零年三次大规模白化使浅水区珊瑚死亡率过半。水温超过三十度持续两周即触发白化。", [
        ("fact", "zh", "大堡礁绵延多少公里"),
        ("synonym", "zh", "珊瑚失去颜色变白是什么原因"),
        ("multihop", "zh", "虫黄藻离开珊瑚后为什么珊瑚会饿死"),
        ("exact", "zh", "二零一六年 大规模白化"),
    ]),
    # ── 技术 ──────────────────────────────────────────────
    ("TLS握手流程", "TLS一点三将握手从两次往返压缩到一次：客户端在首个消息中直接发送密钥交换素材，服务器返回密钥与加密后的证书验证。前向安全由临时迪菲赫尔曼密钥交换保证，即使服务器私钥日后泄露，历史会话也无法解密。TLS一点三删除了RSA密钥交换与弱加密套件。", [
        ("fact", "zh", "TLS 1.3 握手需要几次往返"),
        ("synonym", "zh", "https 建立加密连接比老协议快在哪里"),
        ("multihop", "zh", "为什么服务器私钥泄露不影响历史会话安全"),
        ("exact", "zh", "临时迪菲赫尔曼"),
    ]),
    ("SQLite并发模型", "SQLite采用库级锁实现并发：多读单写。写入时通过回滚日志或预写日志保证原子性，预写日志模式下读与写可真正并行，代价是检查点合并开销。官方建议单写者场景使用，高并发写入应选择客户端服务器架构的数据库。自旋锁竞争激烈时性能急剧下降。", [
        ("fact", "zh", "SQLite 预写日志模式下读写能并行吗"),
        ("synonym", "zh", "嵌入式数据库怎么处理多个连接同时写"),
        ("multihop", "zh", "预写日志带来了什么代价"),
        ("exact", "zh", "多读单写"),
    ]),
    ("向量量化PQ", "乘积量化将高维向量切成若干子段，每段独立聚类为码本，原始向量只需存储码本索引，七百六十八维向量可压缩三十二倍。代价是量化误差使召回率下降，通常配合倒排索引与重排使用：粗量化定位簇，簇内精确距离重排。这是十亿级向量检索的常见方案。", [
        ("fact", "zh", "乘积量化能把七百六十八维向量压缩多少倍"),
        ("synonym", "zh", "大规模向量检索怎么省内存"),
        ("multihop", "zh", "为什么乘积量化之后还要做重排"),
        ("exact", "zh", "码本 倒排索引"),
    ]),
    ("协程调度器", "Kotlin协程调度器将协程映射到线程池：调度器通过限制并发数实现背压，调度队列采用工作窃取算法平衡负载。挂起点是协作式的，协程主动让出线程而非被抢占，因此不存在线程上下文切换的内核开销。切换成本约纳秒级，比线程切换低三个数量级。", [
        ("fact", "zh", "协程切换成本比线程低几个数量级"),
        ("synonym", "zh", "轻量级线程是怎么做到不经过内核切换的"),
        ("multihop", "zh", "工作窃取算法在调度器里起什么作用"),
        ("exact", "zh", "工作窃取"),
    ]),
    ("电池健康管理", "锂电池老化主要来自电极表面固体电解质界面膜增厚与活性锂损失。放电深度越深、倍率越高，循环寿命越短；在百分之二十到八十区间浅充浅放可将循环寿命延长三到五倍。高温存储同样加速衰减，四十度下满电存放一年容量损失约百分之三十五。", [
        ("fact", "zh", "浅充浅放能延长电池寿命多少倍"),
        ("synonym", "zh", "手机电池怎么用才耐用"),
        ("multihop", "zh", "高温满电存放为什么伤电池，一年损失多少"),
        ("exact", "zh", "固体电解质界面膜"),
    ]),
    ("HTTP3与QUIC", "HTTP三是基于QUIC协议的新一代传输层，QUIC运行在UDP之上，把传输层握手与加密握手合并，首次连接只需一次往返。连接迁移靠连接标识而非四元组，从无线网络切换到蜂窝网络时连接不断。队头阻塞在流之间被隔离，丢包只阻塞所在流。", [
        ("fact", "zh", "QUIC 首次连接需要几次往返"),
        ("synonym", "zh", "新一代网页传输协议怎么解决换网络断线"),
        ("multihop", "zh", "HTTP 3 的队头阻塞和 HTTP 2 有什么不同"),
        ("exact", "zh", "连接标识 UDP"),
    ]),
    ("差分隐私", "差分隐私通过在查询结果中加入校准噪声保护个体：隐私预算伊普西龙控制噪声与效用的平衡，预算越小隐私越强。苹果在键盘联想、谷歌在浏览器统计中部署了本地化差分隐私，数据在设备端加噪后才上传。组合定理保证多次查询的预算可累加控制。", [
        ("fact", "zh", "差分隐私的隐私预算符号是什么"),
        ("synonym", "zh", "统计用户数据又不泄露个人信息的数学方法"),
        ("multihop", "zh", "设备端加噪后上传为什么能保护隐私"),
        ("exact", "zh", "隐私预算"),
    ]),
    ("编译器优化层次", "现代编译器优化分三个层次：前端做语言无关的常量折叠与死代码消除；中端做过程间分析与循环不变量外提；后端针对指令集做寄存器分配与指令调度。链接时优化打破编译单元边界，让内联跨文件生效。减去热路径上百分之二十指令通常来自内联与逃逸分析联动。", [
        ("fact", "zh", "链接时优化的作用边界是什么"),
        ("synonym", "zh", "编译器把重复计算消除掉发生在哪个阶段"),
        ("multihop", "zh", "为什么内联要配合逃逸分析才有效"),
        ("exact", "zh", "循环不变量外提"),
    ]),
    # ── 历史 ──────────────────────────────────────────────
    ("丝绸之路简史", "丝绸之路正式开辟于西汉张骞两次出使西域之后，从长安经河西走廊、西域绿洲到达地中海，全长约七千公里。除丝绸外，造纸术、佛教与葡萄、苜蓿经此传播。唐代是鼎盛期，沿线驿站与商贸城市密布，威尼斯商人马可波罗即沿此路东行。", [
        ("fact", "zh", "丝绸之路全长约多少公里"),
        ("synonym", "zh", "古代中国去欧洲的商路是谁开辟的"),
        ("multihop", "zh", "张骞出使之后哪些物产沿这条通道传播"),
        ("exact", "zh", "河西走廊 张骞"),
    ]),
    ("活字印刷", "北宋庆历年间毕昇发明胶泥活字，早于古登堡金属活字约四百年。活字印刷对雕版的优势在于复用：一部大书雕版需数年，活字排版数周即成。但汉字数量庞大，单套活字需二十万枚以上，反而制约了普及，明清官方大规模印书仍以雕版为主。", [
        ("fact", "zh", "毕昇发明活字比古登堡早多少年"),
        ("synonym", "zh", "可以重复使用的印刷字模是谁先做的"),
        ("multihop", "zh", "活字印刷明明更快为什么明清还用雕版"),
        ("exact", "zh", "胶泥活字 庆历"),
    ]),
    ("阿波罗计划", "阿波罗计划一九六一年启动，一九六九年阿波罗十一号实现载人登月，尼尔阿姆斯特朗成为第一个踏上月球的人。整个计划共六次成功登月，十二名宇航员留下月面足迹。土星五号火箭高一百一十米，起飞推力三千四百吨，至今仍是最大的运载火箭。", [
        ("fact", "zh", "阿波罗计划一共有几次成功登月"),
        ("synonym", "zh", "第一个踩上月球的人乘坐哪艘飞船"),
        ("multihop", "zh", "土星五号为什么至今仍是最大火箭"),
        ("exact", "zh", "土星五号 一百一十米"),
    ]),
    ("大航海时代", "一四八八年迪亚士抵达好望角，一四九八年达伽马绕行非洲抵达印度，一五一九年麦哲伦船队开始环球航行。航海革命的技术前提是卡拉维尔帆船与星盘、象限仪的普及。新旧大陆物种交换改变了全球饮食：马铃薯、玉米传入旧大陆，小麦、马匹进入新大陆。", [
        ("fact", "zh", "麦哲伦船队哪一年开始环球航行"),
        ("synonym", "zh", "欧洲人绕过非洲南端去印度是谁完成的"),
        ("multihop", "zh", "大航海靠哪两项技术进步才成为可能"),
        ("exact", "zh", "卡拉维尔帆船"),
    ]),
    ("工业革命蒸汽机", "瓦特并非蒸汽机发明者，他一七六五年发明的分离冷凝器使纽科门机的煤耗降低百分之七十五，热效率从百分之一提升到百分之三。蒸汽动力摆脱了对水力位置依赖，工厂得以进入城市。一八三零年利物浦至曼彻斯特铁路通车，标志铁路时代开始。", [
        ("fact", "zh", "瓦特的分离冷凝器把煤耗降低多少"),
        ("synonym", "zh", "工厂为什么能从河边搬进城市"),
        ("multihop", "zh", "蒸汽机热效率提升和城市化有什么联系"),
        ("exact", "zh", "分离冷凝器 一七六五"),
    ]),
    ("敦煌与藏经洞", "一九零零年道士王圆箓在莫高窟发现藏经洞，内藏四至十一世纪文书约五万件。斯坦因、伯希和等探险队以低价购走大批文书，现分散于伦敦、巴黎等地。敦煌学因此成为国际显学。莫高窟现存洞窟七百三十五个，壁画四万五千平方米。", [
        ("fact", "zh", "藏经洞内大约藏有多少件文书"),
        ("synonym", "zh", "莫高窟那个装满古书的小洞是谁发现的"),
        ("multihop", "zh", "为什么说敦煌学是国际显学，文书现在哪里"),
        ("exact", "zh", "王圆箓 一九零零"),
    ]),
    # ── 健康 ──────────────────────────────────────────────
    ("近视防控", "近视的主要环境风险是户外光照不足：视网膜多巴胺在强光下分泌增加，抑制眼轴增长。每天两小时以上户外活动可使儿童近视发生率降低约百分之四十。低浓度阿托品与角膜塑形镜是循证医学认可的干预手段，成年后激光手术只改变角膜形态而非眼轴。", [
        ("fact", "zh", "每天户外活动多久能显著降低近视发生率"),
        ("synonym", "zh", "孩子预防近视最有效的免费办法"),
        ("multihop", "zh", "户外光照为什么能抑制近视发展"),
        ("exact", "zh", "视网膜多巴胺"),
    ]),
    ("抗生素耐药", "抗生素耐药源于选择压力：不规范疗程留下耐药菌株，耐药基因通过质粒在细菌间水平转移。耐甲氧西林金黄色葡萄球菌与耐碳青霉烯肠杆菌已被列为重点威胁。世界卫生组织估计到二零五零年耐药感染每年可能致千万人死亡。避免滥用与完成疗程是关键措施。", [
        ("fact", "zh", "耐药基因靠什么在细菌之间传播"),
        ("synonym", "zh", "为什么药吃着吃着就不管用了"),
        ("multihop", "zh", "不规范使用抗生素如何催生超级细菌"),
        ("exact", "zh", "耐甲氧西林金黄色葡萄球菌"),
    ]),
    ("血糖指数", "血糖指数衡量食物升糖速度，葡萄糖为一百。低GI食物低于五十五，如燕麦、豆类；白米饭约八十属高GI。烹饪与加工显著改变GI：米饭放冷后抗性淀粉增加，GI下降。糖尿病患者饮食管理的第一原则是控制总碳水量而非单纯追求低GI。", [
        ("fact", "zh", "血糖指数的基准参照是多少"),
        ("synonym", "zh", "哪些主食升糖慢适合糖尿病人"),
        ("multihop", "zh", "米饭放凉了吃为什么升糖变慢"),
        ("exact", "zh", "抗性淀粉"),
    ]),
    ("运动与乳酸", "乳酸并非疲劳废物，而是可被肌肉与心脏氧化供能的燃料，也是肝脏糖异生的原料。血乳酸阈对应运动强度从有氧过渡到无氧供能的分界，训练有素的马拉松选手乳酸阈可达最大摄氧量的百分之八十五。运动后酸痛主要由离心收缩的微损伤引起，与乳酸无关。", [
        ("fact", "zh", "训练有素的马拉松选手乳酸阈能到多少"),
        ("synonym", "zh", "运动后肌肉酸痛是乳酸堆积吗"),
        ("multihop", "zh", "乳酸在肝脏里参与什么代谢过程"),
        ("exact", "zh", "糖异生"),
    ]),
    ("疫苗接种原理", "疫苗通过模拟病原体训练免疫系统：减毒活疫苗产生接近自然感染的免疫记忆，灭活疫苗安全性更高但需多剂次加强。mRNA疫苗将编码刺突蛋白的信使核糖核酸送入细胞，由人体自行合成抗原，研发周期缩短到数月。群体免疫阈值取决于基本传染数。", [
        ("fact", "zh", "mRNA疫苗让人体细胞合成什么"),
        ("synonym", "zh", "打疫苗是怎么让身体提前认识病毒的"),
        ("multihop", "zh", "为什么灭活疫苗往往要打好几针"),
        ("exact", "zh", "减毒活疫苗"),
    ]),
    ("咖啡因代谢", "咖啡因半衰期约五小时，由肝脏细胞色素酶代谢，个体基因差异使代谢速度相差四倍。腺苷受体阻断是其提神机制，长期摄入会使受体上调，停用后出现头痛等戒断反应。下午两点后摄入的咖啡因仍有一半在晚七点存留，影响深睡比例。", [
        ("fact", "zh", "咖啡因半衰期大约多久"),
        ("synonym", "zh", "为什么下午喝咖啡晚上睡不着"),
        ("multihop", "zh", "咖啡因戒断头痛和受体有什么关系"),
        ("exact", "zh", "腺苷受体 细胞色素"),
    ]),
    # ── 生活技能 ──────────────────────────────────────────────
    ("低温慢煮", "低温慢煮将食材密封后恒温水浴，牛排五十三度一小时中心恰好达到三分熟，温度均匀性远胜明火。蛋白质变性在特定温度区间完成：肌球蛋白五十度、肌动蛋白六十六度，超过七十度胶原蛋白才水解为明胶。真空袋防止水分流失，损耗率低于百分之五。", [
        ("fact", "zh", "三分熟牛排低温慢煮用多少度"),
        ("synonym", "zh", "恒温水浴煮牛排为什么嫩"),
        ("multihop", "zh", "胶原蛋白在什么温度下变成明胶"),
        ("exact", "zh", "五十三度 肌球蛋白"),
    ]),
    ("衣物污渍处理", "蛋白类污渍如血渍需冷水清洗，热水会使蛋白质变性固着；油脂污渍相反需要温水和表面活性剂。单宁类如红酒先用盐吸附再冷水处理。含酶洗衣粉的蛋白酶只对蛋白污渍有效，氯漂白剂会损伤蛋白质纤维如羊毛蚕丝，只适用于棉麻。", [
        ("fact", "zh", "血渍应该用冷水还是热水洗"),
        ("synonym", "zh", "衣服沾了红酒第一时间怎么办"),
        ("multihop", "zh", "为什么氯漂白剂不能洗羊毛"),
        ("exact", "zh", "蛋白酶 表面活性剂"),
    ]),
    ("汽车冬季养护", "冬季应更换低粘度机油，五零粘度等级在零下三十五度仍可泵送；玻璃水须用防冻型，普通水结冰会胀裂水壶与管路。电瓶容量在零下十八度衰减百分之五十，启动前可先通电预热油泵。胎压随气温每降十度降低约零点一巴，需及时补充。", [
        ("fact", "zh", "电瓶容量在零下十八度衰减多少"),
        ("synonym", "zh", "冬天早上车子难启动该怎么办"),
        ("multihop", "zh", "为什么冬天气温下降胎压也跟着降"),
        ("exact", "zh", "零点一巴 防冻型"),
    ]),
    ("家庭网络布线", "千兆内网建议六类网线，六类线在五十五米内可跑万兆。路由器摆放避开微波炉与金属柜，两点四吉赫频段穿墙好但干扰多，五吉赫快但衰减大。光猫桥接加软路由拨号可避免运营商设备性能瓶颈。Mesh组网的回程走独立频段时损耗最小。", [
        ("fact", "zh", "六类网线多长以内能跑万兆"),
        ("synonym", "zh", "家里无线信号死角怎么解决"),
        ("multihop", "zh", "光猫改桥接为什么能提升网速"),
        ("exact", "zh", "六类网线 Mesh回程"),
    ]),
    ("记忆力技巧", "间隔重复利用遗忘曲线安排复习：学习后一天、三天、七天、二十一天各复习一次，记忆保持率可超过百分之九十。主动回忆比重读有效两倍，测试效应是认知心理学最稳健的发现之一。记忆宫殿法将抽象信息绑定到熟悉空间位置，适合有序列表。", [
        ("fact", "zh", "间隔重复的复习节点是怎么安排的"),
        ("synonym", "zh", "怎么背东西不容易忘"),
        ("multihop", "zh", "为什么考试自测比反复看书记得牢"),
        ("exact", "zh", "遗忘曲线 记忆宫殿"),
    ]),
    ("基础理财原则", "应急金应为三到六个月支出，存于随时可取的货币基金。五零三零二零预算法将税后收入分为必要开支、储蓄投资与弹性消费。指数基金定投利用波动摊薄成本，长期年化收益预期百分之七左右；复利公式为本金乘以一加利率的期数次方。", [
        ("fact", "zh", "应急金应该准备几个月的支出"),
        ("synonym", "zh", "工资到手怎么分配才合理"),
        ("multihop", "zh", "定投为什么能摊薄持仓成本"),
        ("exact", "zh", "五零三零二零 货币基金"),
    ]),
    # ── 地理 ──────────────────────────────────────────────
    ("撒哈拉之眼", "理查特结构位于毛里塔尼亚撒哈拉沙漠，直径约五十公里，从太空清晰可见，曾长期被当作陨石坑。地质研究显示它是深度侵蚀的对称背斜，同心圆状岩层由差异风化造成。该区域古老岩层最老达二十亿年，是研究西非克拉通的窗口。", [
        ("fact", "zh", "撒哈拉之眼的直径有多大"),
        ("synonym", "zh", "沙漠里那个从太空能看到的大圆圈是什么"),
        ("multihop", "zh", "为什么说撒哈拉之眼不是陨石坑"),
        ("exact", "zh", "毛里塔尼亚 背斜"),
    ]),
    ("贝加尔湖", "贝加尔湖是世界上最深的湖泊，最深处一千六百四十二米，淡水储量占全球液态淡水五分之一。湖龄两千五百万年，由裂谷形成，仍在以每年两厘米扩展。特有物种贝加尔海豹是唯一完全生活在淡水中的海豹，其祖先沿河流溯入。", [
        ("fact", "zh", "贝加尔湖最深多少米"),
        ("synonym", "zh", "世界上淡水最多的湖在哪里"),
        ("multihop", "zh", "淡水里怎么会有海豹"),
        ("exact", "zh", "一千六百四十二 裂谷"),
    ]),
    ("马里亚纳海沟", "马里亚纳海沟最深处为挑战者深渊，约一万零九百米，压强相当于一千一百个大气压。一九六零年雅克皮卡德与唐沃尔什乘的里雅斯特号首次载人下潜。海沟底部热液喷口生存着以化能合成细菌为基础的生态系统，狮子鱼最深见于八千余米。", [
        ("fact", "zh", "挑战者深渊大约多深"),
        ("synonym", "zh", "海洋最深处叫什么名字压强多大"),
        ("multihop", "zh", "的里雅斯特号一九六零年完成了什么"),
        ("exact", "zh", "一万零九百 的里雅斯特"),
    ]),
    ("富士山与樱花", "富士山海拔三七七六米，是日本最高峰，最近一次喷发在一七零七年。山顶冬季积雪至次年六月。樱花前线从南向北推进，东京平均在三月下旬开花，花期仅约一周。江户时代培育的染井吉野占如今樱花的八成，为无性繁殖的单株后代。", [
        ("fact", "zh", "富士山最近一次喷发是哪一年"),
        ("synonym", "zh", "日本最高山多高山顶雪到几月"),
        ("multihop", "zh", "为什么说染井吉野樱花都是一家的复制品"),
        ("exact", "zh", "三七七六 染井吉野"),
    ]),
    ("格陵兰冰盖", "格陵兰冰盖面积一百七十一万平方公里，最厚处超过三公里，全部融化将使全球海平面上升约七米。冰盖通过冰川入海与表面融化两种方式损失质量，二零一九年单年融化量达五千三百亿吨创纪录。冰芯记录可回溯十二万年前的气候。", [
        ("fact", "zh", "格陵兰冰盖全部融化海平面升多少"),
        ("synonym", "zh", "北极那块大冰层有多厚"),
        ("multihop", "zh", "科学家怎么用冰芯研究古代气候"),
        ("exact", "zh", "五千三百亿吨 冰芯"),
    ]),
    ("English: Panama Canal", "The Panama Canal connects the Atlantic and Pacific Oceans through a 82-kilometer waterway, saving ships a 13,000-kilometer journey around South America. Gatun Lake sits 26 meters above sea level, so locks lift ships in stages. The 2016 expansion doubled capacity with new wider locks for neopanamax vessels. Annual traffic is about fourteen thousand transits.", [
        ("fact", "en", "How long is the Panama Canal"),
        ("synonym", "en", "Which shortcut lets ships avoid rounding South America"),
        ("multihop", "en", "Why does the canal need locks if sea level is the same at both ends"),
        ("exact", "en", "Gatun Lake 26 meters"),
    ]),
    ("混合 京都庭园", "京都龙安寺的石庭是枯山水代表：十五块石头分组置於白砂之上，任何角度最多同时看到十四块。禅意庭园以砂纹模拟水波，静止构图引导观想。桂离宫则融合回游式池泉与书院造建筑，被柯布西耶与格罗皮乌斯称为 the essence of Japanese architecture，是 modernism 的重要参照。", [
        ("fact", "mix", "龙安寺石庭有多少块石头"),
        ("synonym", "mix", "zen garden 里用砂子代替水的做法叫什么"),
        ("multihop", "mix", "为什么西方建筑大师推崇桂离宫"),
        ("exact", "mix", "枯山水 桂离宫"),
    ]),
    ("混合 茶马古道", "茶马古道以茶马互市得名，主干道从云南普洱经丽江、香格里拉入藏，海拔落差逾三千米。酥油茶提供高原必需的 fat 与热量，tea 中的多酚帮助消化 meat-heavy 饮食。马帮铜铃制度以铃声传递会车信号，窄崖路段单侧让行。抗战期间滇藏道成为物资生命线。", [
        ("fact", "mix", "茶马古道主干道从哪里到哪里"),
        ("synonym", "mix", "古代用茶叶换马的商路经过哪些地方"),
        ("multihop", "mix", "为什么酥油茶适合高原饮食"),
        ("exact", "mix", "茶马互市 马帮"),
    ]),
    ("English: Fibonacci Sequence", "The Fibonacci sequence starts with zero and one, each subsequent number being the sum of the previous two. Ratios of consecutive terms converge to the golden ratio, approximately 1.618. Sunflower seed spirals typically follow 34 and 55 count helices, both Fibonacci numbers. In 1202 Leonardo of Pisa published the sequence in Liber Abaci, introducing Hindu-Arabic numerals to Europe.", [
        ("fact", "en", "What do consecutive Fibonacci ratios converge to"),
        ("synonym", "en", "Which number pattern appears in sunflower seeds"),
        ("multihop", "en", "What else did the man who wrote about Fibonacci numbers bring to Europe"),
        ("exact", "en", "Liber Abaci 1202"),
    ]),
    ("English: Roman Roads", "The Roman road network stretched over eighty thousand kilometers, connecting Rome to provinces from Britain to Syria. Roads were built in four layers, crowned with polygonal stone slabs, and cambered for drainage. Milestones marked distances to Rome, and chariots could travel over eighty kilometers per day on them, a speed unmatched until the railway era.", [
        ("fact", "en", "How long was the Roman road network in total"),
        ("synonym", "en", "How fast could ancient travelers move across the empire by road"),
        ("multihop", "en", "What made Roman roads drain rainwater effectively"),
        ("exact", "en", "milestones polygonal stone slabs"),
    ]),
    ("English: Black Holes", "A black hole forms when matter collapses beyond its Schwarzschild radius, where escape velocity exceeds the speed of light. The first image of a black hole shadow, in galaxy M87, was released in 2019 by the Event Horizon Telescope, which combined radio dishes into an Earth-sized virtual aperture. Stellar-mass black holes weigh several times the sun, while supermassive ones reach billions of solar masses.", [
        ("fact", "en", "In which year was the first black hole image released"),
        ("synonym", "en", "What is the boundary where nothing can escape a collapsed star"),
        ("multihop", "en", "How did the Event Horizon Telescope achieve Earth-sized resolution"),
        ("exact", "en", "Schwarzschild radius M87"),
    ]),
    ("English: CRISPR", "CRISPR-Cas9 acts as programmable molecular scissors: a guide RNA matches a DNA sequence and the Cas9 enzyme cuts there. It was adapted from a bacterial immune system that stores fragments of invading viruses. Emmanuelle Charpentier and Jennifer Doudna won the 2020 Nobel Prize in Chemistry for its development, and base-edited therapies reached patients in 2022.", [
        ("fact", "en", "Who won the 2020 Nobel Prize for CRISPR"),
        ("synonym", "en", "How do scientists cut DNA at a chosen location"),
        ("multihop", "en", "What natural system was CRISPR adapted from"),
        ("exact", "en", "guide RNA base-edited"),
    ]),
    ("English: Honeybee Dance", "Honeybees communicate food locations through the waggle dance: the angle of the dance relative to vertical encodes direction relative to the sun, and dance duration encodes distance. Karl von Frisch decoded this language and won a Nobel Prize in 1973. A waggle run of one second roughly corresponds to a one-kilometer flight.", [
        ("fact", "en", "What does one second of waggle dance correspond to"),
        ("synonym", "en", "How do bees tell each other where flowers are"),
        ("multihop", "en", "Which scientist decoded the bee dance and when"),
        ("exact", "en", "waggle dance von Frisch"),
    ]),
    ("English: Van Gogh", "Vincent van Gogh painted The Starry Night in June 1889 from his asylum room in Saint-Remy, working from memory and imagination rather than direct observation. He sold very few paintings during his lifetime and died in 1890 at thirty-seven. His letters to his brother Theo, over eight hundred of them, form a detailed record of his artistic development.", [
        ("fact", "en", "When did van Gogh paint The Starry Night"),
        ("synonym", "en", "Which troubled painter almost never sold his work while alive"),
        ("multihop", "en", "What source documents record van Gogh's artistic growth"),
        ("exact", "en", "Saint-Remy asylum Theo"),
    ]),
    ("混合 量子纠缠通信", "quantum entanglement 中两粒子状态关联，measure 一方立即确定另一方，爱因斯坦称之为 spooky action at a distance。墨子号卫星实现了千公里级 entangled photon distribution，配合 BB84 协议可构建 eavesdropping 可察觉的 quantum key distribution。城际 quantum network 已在 Beijing-Shanghai 干线试运行，金融与政务 data 传输率先应用。", [
        ("fact", "mix", "墨子号卫星实现了什么级别的纠缠分发"),
        ("synonym", "mix", "量子密钥为什么能发现窃听者"),
        ("multihop", "mix", "爱因斯坦怎么评价量子纠缠"),
        ("exact", "mix", "BB84 Beijing-Shanghai"),
    ]),
    ("混合 高铁轮轨", "中国高铁采用轮轨而非磁悬浮 maglev 作为干线技术，crucial 因素是 network 兼容性：列车可下既有线进站。CR400 复兴号运营时速 350 公里，world record 试验速度 605 公里。转向架 pivot 与 air spring 抑制蛇行失稳，pantograph 离线率低于百分之零点一保证受流质量。", [
        ("fact", "mix", "复兴号运营时速是多少"),
        ("synonym", "mix", "为什么干线高铁选轮轨不选磁悬浮"),
        ("multihop", "mix", "受电弓离线率影响什么"),
        ("exact", "mix", "CR400 605 公里"),
    ]),
]

# 数字/日期类（答案为明确数字或日期；与 fact 的区别是考核数值提取而非事实本身）
NUMDATE_QUERIES = [
    ("青藏高原隆升", "高原隆升大约开始于多少万年前"),
    ("蜂鸟悬停机制", "蜂鸟悬停时心率每分钟达到多少次"),
    ("深海热泉生态", "不依赖阳光的生命系统是哪一年发现的"),
    ("闪电形成原理", "全球每秒大约发生多少次闪电"),
    ("候鸟迁徙导航", "斑尾塍鹬不间断飞行了多少公里"),
    ("火山指数VEI", "坦博拉火山是哪一年喷发的"),
    ("睡眠周期研究", "长期睡眠低于几小时会使杏仁核反应增强"),
    ("大堡礁白化", "水温超过多少度持续两周会触发白化"),
    ("活字印刷", "毕昇的活字比古登堡早了约多少年"),
    ("阿波罗计划", "土星五号火箭有多高"),
    ("大航海时代", "麦哲伦船队哪一年开始环球航行"),
    ("工业革命蒸汽机", "分离冷凝器把纽科门机的煤耗降低了多少"),
    ("敦煌与藏经洞", "藏经洞文书大约有多少件"),
    ("近视防控", "每天户外活动两小时可使近视发生率降低多少"),
    ("抗生素耐药", "世界卫生组织预测耐药感染年死亡人数达千万是哪一年"),
    ("血糖指数", "白米饭的血糖指数大约是多少"),
    ("运动与乳酸", "训练有素的马拉松选手乳酸阈可达最大摄氧量的百分之多少"),
    ("低温慢煮", "肌动蛋白在多少摄氏度变性"),
    ("贝加尔湖", "贝加尔湖最深处的深度是多少米"),
    ("富士山与樱花", "富士山最近一次喷发是在哪一年"),
]

# 对抗池：语料外主题（与 45 卡内容无交集）
ADVERSARIAL = [
    "量子计算机的纠错码怎么实现", "红楼梦后四十回作者是谁", "相对论的场方程推导",
    "微积分基本定理的内容", "古巴导弹危机的起因",
    "民法典继承编的改动", "世界杯进球最多的球员", "古奥运会起源于哪一年",
    "Python 的 GIL 为什么难移除", "区块链双花攻击的案例", "股票 K 线锤子形态含义",
    "弗洛伊德的人格结构理论", "大熊猫栖息地面积变化", "月球背面的陨石坑数量",
    "北欧神话诸神黄昏结局", "梵高星月夜创作年份", "莫扎特安魂曲未完成部分",
    "拓扑学莫比乌斯带性质", "马斯克星舰试飞记录", "巴黎协定碳市场机制",
    "李白将进酒的写作背景", "苏轼水调歌头词牌格律", "京剧脸谱红色的含义",
    "针灸足三里的主治", "瑜伽体式串联起源", "咖啡烘焙一爆温度",
    "波尔多左岸酒庄分级", "瑞士银行保密制度历史", "迪拜哈利法塔层数",
    "亚马逊雨林退林速度", "南极科考站数量", "秦始皇兵马俑彩绘保护",
    "金字塔建造工人来源", "泰坦尼克号残骸深度", "硅谷名称由来",
    "贝尔电话专利之争", "莱特兄弟首飞距离", "马拉松战役的伤亡",
    "庞贝古城发掘时间", "特洛伊战争的历史真实性", "敦煌月牙泉水位下降原因",  # 注意：敦煌卡讲藏经洞，月牙泉不在卡内
    "居里夫人两次诺奖领域",
]

def main():
    base = os.path.join(os.path.dirname(__file__), "eval")
    corpus_dir = os.path.join(base, "corpus")
    os.makedirs(corpus_dir, exist_ok=True)
    for f in os.listdir(corpus_dir):
        os.remove(os.path.join(corpus_dir, f))

    queries = []
    qid = 0
    for i, (title, body, qs) in enumerate(CARDS, 1):
        fname = f"{i:02d}_{title}.txt"
        with open(os.path.join(corpus_dir, fname), "w") as fh:
            fh.write(title + "\n\n" + body + "\n")
        for typ, lang, q in qs:
            qid += 1
            queries.append({
                "id": f"q{qid:03d}", "q": q, "type": typ, "lang": lang,
                "relevant": [f"{i:02d}_{title}"],
            })
    for q in ADVERSARIAL:
        qid += 1
        queries.append({
            "id": f"q{qid:03d}", "q": q, "type": "adversarial", "lang": "zh",
            "relevant": [],
        })
    for title, q in NUMDATE_QUERIES:
        qid += 1
        queries.append({
            "id": f"q{qid:03d}", "q": q, "type": "numdate", "lang": "zh",
            "relevant": [f"{[c[0] for c in CARDS].index(title) + 1:02d}_{title}"],
        })
    random.shuffle(queries)  # 打乱类型顺序，避免同卡查询相邻
    for i, q in enumerate(queries, 1):
        q["id"] = f"q{i:03d}"

    with open(os.path.join(base, "evalset.json"), "w") as fh:
        json.dump({"queries": queries, "n_docs": len(CARDS)}, fh, ensure_ascii=False, indent=1)

    types = {}
    langs = {}
    for q in queries:
        types[q["type"]] = types.get(q["type"], 0) + 1
        langs[q["lang"]] = langs.get(q["lang"], 0) + 1
    print(f"corpus: {len(CARDS)} docs -> {corpus_dir}")
    print(f"queries: {len(queries)}  types={types}  langs={langs}")

if __name__ == "__main__":
    main()
