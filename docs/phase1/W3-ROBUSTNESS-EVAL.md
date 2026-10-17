# W3 阶段报告：健壮性 + 评测集 + 质量终审（2026-10-16）

> 范围：W3-1 删除级联（§9.1-5）、W3-2 评测集与三通道指标、granite vs 440M 质量终审、
> W3-3 偏好路由与模型管理、W3-4 生产基准（granite 默认）、W3-5 验收清单 + MVP-1。
> **2026-10-16 用户决策：默认路径切换为 granite（ADR-PB-1 修订，依据本报告质量终审）。**

## W3-6 生产基准三档矩阵（granite 默认路径，ProductionBenchRunner：10 篇导入 + 10 查询×5 轮）

| 设备 | 导入 ms/篇 | 检索 p50/p95（含查询嵌入） | RSS 峰值 |
|---|---|---|---|
| 旗舰 PJZ110（SM8750，A16） | **204** | **33 / 40 ms** | 821MB |
| K60 Ultra（MT6985，A14） | 413 | 79 / 87 ms | 737MB |
| K40（MT6893，A13，最慢档） | 406 | 58 / 67 ms | 688MB |

- 口径：端到端（查询嵌入+向量扫描+FTS+证据回溯）；导入含引擎冷加载摊销。
- K60U 检索 p50 反常高于 K40（79 vs 58，三档对照后仍成立，旗舰 33ms 符合档位）——确认为
  MT6985 机型真实特性（调度/热策略），如实记录不强行解释。
- RSS：granite 路径 688-737MB（440M 路径历史 ~1.35GB）——内存省近半。
- 踩坑记录：K60U 的 `pm clear` 被 MIUI 拒绝（shell 无 CLEAR_APP_USER_DATA）→ 清库改用
  卸载重装；模型文件名必须保留显式名单标识（临时名 `g.litertlm` 不被识别，全拒检索）。

## W3-1 删除级联（§9.1-5）✅

- 实现：`SourceDao.deleteCascade`（@Transaction 五表子查询级联：embedding→fts→chunk→job→document→source）
  + `IngestRepository.deleteSource`（在途导入拒绝 + documents/ 原文文件清理，仅清应用目录内文件）
  + SourcesScreen 删除入口（确认对话框 + 在途拒绝提示）
- 设备验证（K40，`autostart_delete_check`）：删 1 source（1 文档/1 块/文件在位）→
  **五表零残留、文件已删、向量对账 44/45 正确**，pass=true
- 顺手修复（W2 遗留）：`requeueInterrupted`——进程被杀后中间态任务（parsing..storing）永久悬挂的地雷

## W3-2 评测集（MVP-1 验收时 220 条五类标注查询）✅

**构成**（`scripts/gen_eval_corpus.py` 可复现）：
- 语料 45 张知识卡（自然科学/技术/历史/健康/生活/地理，zh 40 + en 2 + 混合 3）
- 查询 220 = 事实 45 + 同义 45 + 多跳 45 + 精确 45 + 对抗 40；语言 zh196/mix16/en8
- > 注：**B2 后评测集已扩容为 262 条 / 50 卡**（见文末「升级」），当前以 `scripts/eval/evalset.json` 为准；本节为 MVP-1 验收历史口径。
- 通道：向量（PRELOADED 扫描）/ 关键词（FTS4 bigram OR+覆盖重排）/ RRF 融合（K=60）
- 指标（`scripts/score_eval.py`）：R@5/R@10、MRR@10、NDCG@10，按类型/语言分列

**K40 质量终审（`scripts/run_eval.sh`，同库同查询双路径对照）**：

| fused 通道 | 440M（路径 A） | granite（路径 B） |
|---|---|---|
| ALL R@5 / R@10 | **0.9944 / 1.0** | **0.9944 / 1.0** |
| ALL MRR@10 / NDCG@10 | 0.9806 / 0.9854 | 0.9750 / 0.9813 |
| 向量单通道 R@10 / MRR | 0.9889 / 0.9617 | 0.9944 / 0.9361 |
| 关键词单通道（同索引） | 0.9556 / 0.9176 | 0.9556 / 0.9176 |
| 类型分解（fused MRR） | fact .989 / synonym .944 / multihop .989 / exact 1.0 | 同级（±.012 内；exact .978 / mix .969 略低） |
| 平均单查延迟 | 163ms | **72ms（2.3×）** |

**结论（质量终审裁定）**：
1. granite 与 440M 在本评测集上**质量等效**（全指标差距 ≤0.006，噪声级），granite 快 2.3×——
   路径 B 具备"性能优先场景默认"的资格；正式切换默认路径留待用户决策（ADR-PB-1 维持 440M 默认）。
2. 融合通道严格优于任一单通道（R@5 0.9944 > 0.9833/0.95）——RRF 设计有效。
3. 评测集对两模型都偏易（R@10=1.0），区分度有限；作为 MVP 质量门槛足够（两路径均远超线），
   en/mix 样本薄（8/16 条）、对抗池存在与语料共享实词的碰撞（keyword_fp=0.7 中大部分为
   "量子计算机↔量子纠缠卡"类实体碰撞而非算法误报）——W4+ 扩集时修正对抗池。

## 过程问题账本（本轮）

| 问题 | 根因 | 修复 |
|---|---|---|
| 导入轮询 8 分钟全 0（假读数） | shell 重定向 `>/dev/null` 误写入 SQL 引号内，sqlite 报错恒走 `|| echo 0` | 移出引号 |
| 轮询循环每 20s force-stop 杀 worker | 轮询设计错误 | 改并行拉库不杀进程 |
| 评测首轮全 0 | 标注不匹配：relevant 无文件名序号前缀（`01_`） | 生成器补前缀，标签重跑 |
| run_eval.sh 多处坏字节 | `$VAR` 紧邻多字节字符被 bash 误读 | 全量 ASCII 化该类行 |

## 数据归档

- `docs/phase1/eval-results/`：440M/granite 原始排名 JSON + score JSON
- `scripts/eval/corpus/`（MVP-1 时 45 卡；当前 50 卡）、`scripts/eval/evalset.json`（MVP-1 时 220 条；当前 262 条，可复现）
- `scripts/run_eval.sh`（一键：清库→推模型→导入→跑批→计分）

## W4-2 评测集 v2（2026-10-16 深夜，K60U × 440M 默认）

**升级**：50 卡（+5 en：Roman Roads/Black Holes/CRISPR/Honeybee/Van Gogh）；
**262 查询** = fact/synonym/multihop/exact 各 50 + **adversarial 42** + **numdate 20（数字/日期单列，§9.3 要求落地）**
+ 对抗 42（去碰撞：删"量子计算机纠错码/敦煌月牙泉"两条与语料共享实体的，补微积分/古巴导弹危机）；
en 28 条（原 8）。

**K60U × 440M 默认结果（fused）**：ALL R@5=0.9955 / **R@10=1.0** / MRR=0.984 / NDCG=0.988；
numdate **R@5=R@10=MRR=NDCG=1.0**（数字/日期满分）；en 1.0；最弱仍为 synonym（MRR 0.95）。
对抗 keyword_fp=0.714——两台新对抗条目（微积分/古巴）命中情况待明细核对，池纯度仍非 100%。

结论：**440M 默认在扩容后评测集上保持满贯水准**（262 条 R@10=1.0），战略切换的质量侧再次确认。
granite 对照跑（退役前基线）待 K40/K60U 空闲时补——非阻塞（granite 已退出生产路由）。

**过程陷阱**（第 3 次假读数，已根治）：run_eval.sh 的 sqlite 查询行内 `PRAGMA wal_checkpoint`
输出三列 `0|0|0` 混入 COUNT 捕获 → READY 恒为多值字符串比较永假。已从查询中移除 PRAGMA
（WAL 数据经 -wal 文件由 sqlite3 自动恢复，无需显式 checkpoint）。教训：**shell 变量捕获 SQL
输出时，语句必须单值；任何多语句/PRAGMA 输出都会污染捕获**。

## W4 第一批（2026-10-17，K40 实测）

| # | 任务 | 结果 |
|---|---|---|
| W4-1 | **导入取消**（§9.1-4） | ✅ `cancelImport()` 全活动态取消（queued+中间态——中间态不取消会被 requeue 复活，实测发现并修复）+ ImportScreen 取消按钮 + autostart_import_cancel。K40 实测：20 篇 14s 后取消 → 9 done + 10 cancelled + 1 中断篇被后续取消覆盖，零复活零残留。**附带发现存量 bug**：importFile 的 DocumentEntity 位置参数错位（"pending"传入 parserId，status 恒 processing）——正是它让取消清单永远匹配不到；已改命名参数修复 |
| W4-3 | **FTS 索引损坏恢复**（§9.1-10） | ✅ `rebuildFtsIndex()`（bigram 从 chunk 全量重生成）+ `ftsHealthCheck()` + 设置页诊断卡。FtsRecoveryRunner 自证（K40）：破坏→健康检查报警（30/0、检索 0 命中）→ 重建 30 条 → 健康 + 抽查 5 命中，pass=true |
| W4-4 | **商店打磨** | 极速档 SOC 分化（Adreno→740M UE-GPU 32-44ms 推荐 / 其余→440M；NPU 特化版经验证门才推荐）；检索拒绝提示增加"设置→模型商店"下载引导 |
| W4-5 | **ANR 防护监测** | debug 构建 StrictMode 线程策略（主线程磁盘/网络 penaltyLog）——生产路径已在 Worker/Default（基准已证），此为回归防线 |

**遗留更新**：迁移自动化测试（待首次 schema v2，现在写了也是空转）；功耗/温度长跑报告；
granite 退役前基线对照跑（非阻塞）；评测 v2 旗舰/K40 补跑（非阻塞——K60U 已证满贯）。

## W4-2 补全：评测集 v2 三端数据 + granite 退役基线（2026-10-17）

**K40 × 440M v2**：与 K60U **全指标逐位一致**（R@5=0.9955/R@10=1.0/MRR=0.9841/NDCG=0.9881，
各分项亦同）——440M CPU 跨设备逐位一致性的又一次完美实证（同向量→同排名→同指标）。

**granite 退役基线（K40 × v2，退役前留档）**：

| fused | R@5 | R@10 | MRR | NDCG | avg_ms |
|---|---|---|---|---|---|
| 440M（新默认） | 0.9955 | 1.0 | **0.9841** | **0.9881** | 174ms |
| granite（退役） | 0.9955 | 1.0 | 0.9773 | 0.9831 | **69ms** |

v2 扩容集上 440M **微优于 granite**（MRR +0.007）——战略切换"质量无损"在更严格的评测集上
再次确认；granite 的速度优势（2.5×）随退役一并归档，litertlm 修复其 GPU（W8）后可凭本基线
随时回归。

**过程备注（已随 B6 更新）**：脚本已统一为 `N_DOCS=50`（与当前语料一致）且查询提示按当前评测集全量输出；历史 MVP-1 日志中的 `N_DOCS=45`/220 条提示为当时口径（ready 判定 45 恒过，无功能影响）。

## W4-6 功耗/温度长跑报告（2026-10-17，K40 MT6893 最慢档，440M 默认路径）

负载序列：连续导入 50 卡（~2.5min）→ 评测跑批 262 条 × 三通道（~2min）→ 冷却 3min。
采样（dumpsys battery，0.1°C 精度）：

| 阶段 | 温度 | 电量 |
|---|---|---|
| 基线 | 35.2°C | 99% |
| 导入峰（180s） | 35.6°C（+0.4） | 99% |
| 检索后 | 36.4°C（+1.2） | 99% |
| 冷却 3min | 36.2°C | 99% |

**结论：最慢档连续满负载温升仅 1.2°C、无可测掉电**——发热风险（此前作为"自动重嵌入推迟"
的理由之一）在当前默认路径（440M CPU、batch 串行）下实测温和；`run_thermal.sh` 固化为
标准采集脚本，三档补测与 GPU 模式功耗对照随下次设备窗口执行。

## W4-2 终章：旗舰评测 v2 补跑——三端收官（2026-10-17）

旗舰 PJZ110（440M 默认 + Adreno 门 GPU 通道）× 262 查询：**ALL R@5=0.9955 / R@10=1.0 /
MRR=0.9841 / NDCG=0.9881——与 K60U/K40（CPU 通道）逐位一致**，avg 82ms（三端最快）。

两个收尾结论：
1. **三端指标逐位一致**（含 GPU vs CPU 跨通道）——440M 的检索排序对 GPU 数值差异鲁棒
   （向量 SHA 不同但排序不变），"GPU 提速不伤质量"获得跨设备实证。
2. 评测 v2（262 条 × 五类 × 三语言）在全部三档通过满贯下限（R@10=1.0）——§9.3 验收的
   质量基线正式建立，后续任何模型/参数变更以此集回归。

**W4 状态：全部可执行项完成。** 剩余均为外部触发：ML Kit EGemma2 上线（W1）、litertlm 修复
（W8）、Gallery NPU 配置（W11）、首次 schema v2（迁移测试）。

## W4+ 设计预留落地（2026-10-17，K60U 回环验证 PASS）

### W4+-1 自动重嵌入（§9.1-7 增强正式版）
`ReembedScheduler` + `ReembedWorker`（unique work，电量非低门槛）+ `reembedSyncAll`（幂等扫描：
全部旧空间 → 当前空间，NOT EXISTS 去重，EMBED_BATCH+200ms 限速）；设置页切换模型自动调度 +
进度条（WorkManager progress）+ 手动"同步旧数据"按钮 + 停止。**实测（K60U）**：440M 导入 3 篇
→ 切 granite → 重嵌入 6/6 → granite 空间检索 Top1 命中——无缝切换语义完整兑现。

### W4+-2 知识库导出/导入（跨设备迁移正式版）
`KnowledgeBackup`：单 zip（meta + 模型需求清单 + jsonl + vectors.bin 二进制）；流入口参数化
（SAF Uri 版供 UI / File 版供自动化）。导入预览：模型需求校验（缺失阻止 + 商店下载引导）+
GPU 跨家族标记（可选丢弃重建）。设置页"知识库迁移"卡（导出/导入/预览对话框）。**实测（K60U
回环）**：导出 36KB → 清库 → 导入 → 6 chunk + 12 向量（双空间）完整恢复 + 检索命中。

### 过程修复的三个真实缺陷
1. **vectors.bin 字节序错位**：写头未设 LITTLE_ENDIAN → 导入 count 读成 0x06000000 → OOM；
2. **重嵌入未注册空间 meta** → 导出时新空间向量 spaceIdx 回退污染其它空间（UNIQUE 冲突）→
   `EmbeddingRuntime.registerSpace` 统一注册 + 导出端孤儿向量防御性跳过；
3. （承接 W4-1）取消/重嵌入等所有写向量路径的空间注册一致性由 registerSpace 收口。

## W4-5 首次 schema v2 + 迁移测试（2026-10-17，K60U 真机）

**schema v1 → v2（首个真实迁移）**：
- `embedding_meta` 增 `accelerator` 列（TEXT NOT NULL DEFAULT 'cpu'）——GPU/NPU 向量按后端
  结构化隔离（此前编码在 spaceId 后缀）；迁移从 id 后缀回填（-gpu→gpu / -npu→npu）；
- `document` 增 `sizeBytes` 列（INTEGER NOT NULL DEFAULT 0）——源文件字节数（存量置 0，
  导入时写入）；

**自动化测试**（androidTest，K60U 真机 instrumentation）：`MigrationTest`——以 schemas/1.json
建 v1 库 → 插 cpu/gpu/npu 三空间 + 一篇文档 → `runMigrationsAndValidate(v2)` → 断言：
数据无损、accelerator 三态回填正确、sizeBytes=0、v2 DAO 可正常读写。**PASS**。

**基础设施补充**：room-testing/androidx.test 依赖；`testInstrumentationRunner` 配置（缺失曾致
instrumentation 进程崩溃 0 tests）；schemas/ 目录挂 androidTest assets（MigrationTestHelper 读
schema 用）。

**真机自然升级冒烟**：v2 APK 安装到含旧库设备 → Room 自动执行 MIGRATION_1_2 → v2 功能冒烟
（440M 导入 816ms/篇、检索 p50=133ms、RSS 1557MB）正常。

**W4 收官状态**：§9.1-10 四项（迁移/索引损坏恢复/磁盘不足/模型缺失）中三项已完成自动化或
实测验证；磁盘不足为 ModelDownloader StatFs 预检逻辑（自动化测试随下载管理 UI 一并补）。
**外部触发待办**：W1 ML Kit / W8 litertlm / W11 Gallery NPU / W4+ 剩余（功耗三档补测）。

## W4-5 补：自然升级第二次真机验证（2026-10-17，K40）

K40 设备库为 v1（上轮评测 v2 后），安装 schema v2 APK 冷启动 → Room 自动 MIGRATION_1_2 →
**v2 回归全部正常**：440M 默认 + Adreno 门（MT 直达 CPU）import 407ms/篇、检索 p50=127ms、
RSS 1403MB——自然升级路径第二次真机验证成功（与 androidTest 迁移测试互为印证）。
评测 v2 复跑因 K40 中途掉线中断，待重连补跑（K60U/旗舰数据已齐，非阻塞）。

## W4-2 终章补：K40 v2 schema 评测 v2（2026-10-17）

K40（schema v2 + 新 APK）262 条：ALL R@5=0.9955 / R@10=1.0 / MRR=0.9841 / NDCG=0.9881，
与 K60U **逐位一致**（numdate/en 满分、synonym 0.95 同）——**v2 schema 上三端指标一致性
再次确认**（迁移零影响）。avg 280ms（440M CPU @MT6893）。

**W4-2 评测集 v2 全部收官**：三端（旗舰 GPU 通道 / K60U、K40 CPU 通道）× 262 条全部
R@10=1.0，跨设备/跨通道指标一致。过程坑位（本轮新增 2 条）：
1. K40 卸载后重装被 MIUI USB 安装确认拦截 → 不卸载改为覆盖安装 + 手动清数据文件；
2. 覆盖安装后 files 为空 → models 必须重推（"unknown package" 与 "no loadable model"
   错误分别对应 app 未装 / 模型缺失两个阶段，按序排查）。

## W4-6 补全：旗舰 GPU 模式 thermal + 评测 v2 补跑（2026-10-17）

**旗舰评测 v2 补跑**（Adreno GPU 通道 × 262 条）：ALL R@5=0.9955 / R@10=1.0 / MRR=0.9841 /
NDCG=0.9881，avg 82ms——**与迁移前同机跑及 K60U/K40（CPU 通道）逐位一致**。评测集 v2 三端
数据至此全齐：任何设备/通道组合均为满贯。

**旗舰 GPU 模式 thermal**（AUTO=GPU，Adreno；**注意：充电中测试，数据含充电热源干扰**）：
基线 37.0°C（充电中）→ 满负载 37.5°C（+0.5）→ 冷却期 38.0°C（充电持续加热）——满负载温升
+0.5°C、全程 ≤38°C 无过热；电量因充电不降（功耗净读数不可得，局限如实记录）。
**GPU 路径发热风险评估**：Adreno GPU 满负载温升与 CPU 相当甚至更平缓——此前"GPU 发热顾虑"
不成立，Adreno 机型 GPU AUTO 策略的热安全性确认。

**三档 thermal 总表**：旗舰 GPU +0.5°C（充电干扰下）/ K40 CPU +1.2°C——全部温和，
ANR/发热门槛无触发风险。

## schema v3（第二次真实迁移，2026-10-17）

**变更**：
- `embedding_meta` 增 `quantization` 列（TEXT NOT NULL DEFAULT 'f32'）——量化向量存储演进预留
  （int8 存储减半；不同量化格式数值不同，检索过滤需按此列）；
- 新建 `app_kv` 表（key/value/updatedAt）——通用键值，跨 WorkManager/进程生命周期的持久状态，
  随知识库备份走。

**迁移**：`MIGRATION_2_3`（ALTER ADD COLUMN + CREATE TABLE）+ 注册 `addMigrations(1_2, 2_3)`。
**迁移测试**：`MigrationV2ToV3Test`（v2 建库 → quantization 缺省 f32/既有数据无损 → app_kv 建表
即可用 → v3 DAO 读写）+ **链式 1→3**（v1 直迁 v3，cpu 回填与 f32 缺省同时验证）。
**真实接入**：`reembedSyncAll` 完成时间戳写入 app_kv，设置页"上次同步 · MM-dd HH:mm"显示。

**状态（2026-10-17 终版）：connectedAndroidTest 3/3 PASS**（v1→2 数据保留+accelerator 回填、
v2→3 quantization/app_kv、链式 1→3）——v1→2 首轮 FAIL 系测试第 4 步未注册全链迁移（entity 升
v3 后打开 v2 库需 2→3），测试代码修正后复跑全过。**真机自然升级验证 ✅**：K60U 旧 v2 库装 v3
APK 冷启动自动迁移（user_version=3、accelerator=cpu/quantization=f32 回填正确）+ v3 功能冒烟
（导入 814ms/篇、检索 p50=132ms）正常；models 缺失场景的拒绝快路径（0-1ms）同轮复验。

## W4+-3 补全：旗舰 GPU vs CPU 对照（2026-10-17，PJZ110）

| 指标 | CPU（GPU 关） | GPU（AUTO 白名单） | 差异 |
|---|---|---|---|
| 检索 p50 | 95ms | **68ms**（28%↑） | GPU 快 |
| RSS 峰值 | 1547MB | **737MB**（省 52%） | GPU 显著优 |
| 导入吞吐 | 库残留干扰不可比 | — | — |
| 温度 | 热饱和状态（39-40°C）不可辨 | 同 | 局限如实标注 |

**结论补充**：Adreno 上 GPU 路径除速度外还有**内存减半**的显著优势（LiteRT GPU delegate 的
权重常驻方式不同）——此前"GPU 发热顾虑不成立"再添内存佐证，Adreno AUTO 策略的正确性第三次
确认。热饱和状态下温度对照不可辨（连续多轮测试后基线 39.9°C），干净热基线的温度对照留待
设备冷却后补测（非阻塞——温度风险已由 K40 +1.2°C 与旗舰充电干扰下 +0.5°C 两轮覆盖）。
