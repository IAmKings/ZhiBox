# ZhiBox 代码质量复审报告 R3（修复验证轮）

- **审查对象**：R2（[`CODE_QUALITY_REVIEW_R2.md`](CODE_QUALITY_REVIEW_R2.md)，2026-10-19 @ `0139f62`）发现的全部问题的修复验证 + 修复后整体复审
- **审查日期**：2026-10-19（R2 报告同日，修复后）
- **基线**：R2 基线 `0139f62` + 本次修复（工作区未提交，27 文件 +325/−213）
- **结论一句话**：**R2 的 P1×2 与 P2×16 共 18 项全部修复并经双盲复审验证（核心 9/9、UI 8/8 通过）；复审发现的 3 个修复引入的回归 + 1 微瑕已当场二次修复；全部门禁全绿（520 单测 / lint 0 error / debug+release+androidTest 编译通过）。**

---

## 0. 门禁实测（修复后）

| 检查 | 结果 |
|---|---|
| `compileDebugKotlin` | ✅ 0 警告 |
| `compileDebugAndroidTestKotlin` / `compileReleaseKotlin` | ✅ 通过（DAO 签名变更的 androidTest 已同步） |
| `testDebugUnitTest` | ✅ **520 用例 / 0 失败 / 0 错误**（与修复前持平，无测试破坏） |
| `lintDebug`（abortOnError=true） | ✅ **0 errors** / 41 warnings |
| 依赖校验 | ✅ `lifecycle-runtime-compose` 2.10.0 已在 verification-metadata（viewmodel-compose 传递依赖，7 处登记），无需重建元数据 |
| 改动规模 | 27 文件 +325/−213 |

> lint 41 条 warning 中 +1 为新增 `lifecycle-runtime-compose` 的"2.11.0 可用"提示——与既有 40 条同类（toml 头部已注明钉 2.10.0 的原因：2.11.0 需 compileSdk 37 + AGP 9.1.0），非缺陷。
> androidTest 仅编译验证；真机执行待下一设备窗口（SagRetrieverTest/B8DaoRoundTripTest 已随 API 更名同步）。

## 1. R2 问题修复清单（18 项全部 FIXED）

| 编号 | 修复内容 | 位置 | 复审判定 |
|---|---|---|---|
| P1-R2-1 | 事件向量扫描：`bySpace LIMIT 5000` → `bySpacePage` 分页全量扫描 + 有界 top-K（`scanEventVectors`/`eventCosine`），页 1000 行、候选 O(2×kSeed)，删除 `EVENT_VECTOR_SCAN_LIMIT` | `SagRetriever.kt`、`Daos.kt`、`SagSupport.kt` | ✅ VERIFIED（分页终止/排序语义/τ 过滤/取消传播与旧实现一致） |
| P1-R2-2 | 抽取热路径：`byDocumentAndOrdinal` 单行查询取上一 chunk；Scheduler 建任务改 `idsByDocument`（不物化正文列） | `SagExtractionPipeline.kt`、`SagExtractionScheduler.kt`、`Daos.kt` | ✅ VERIFIED（unique(documentId,ordinal) 索引保证单行确定性） |
| P2-1 | 桥接锚点实体单次批量 `loadEventEntityIds`；救援锚点实体集复用同批结果（anchor 恒为批量首元素） | `SagRetriever.kt` | ✅ VERIFIED（两分支等价性核验） |
| P2-2 | 桥接救援在 channels 打 `SagChannels.BRIDGE_RESCUE` 标记（分数提升保留为机制，标记提供可解释性） | `SagRetriever.kt`、`SagModels.kt` | ✅ VERIFIED（androidTest 全部 contains 断言，无精确集合依赖） |
| P2-3 | `processNext` 批量取 8 个候选逐个尝试 claim，消除 parallelism>1 惊群 | `SagExtractionPipeline.kt` | ✅ VERIFIED（与 worker 的 queuedCount/delay 协作收敛） |
| P2-4 | `markNotIndexed` 加 `AND status=:fromStatus` 守卫；两调用点分别传 QUEUED（claim 前）/RUNNING（失败收尾） | `Daos.kt`、`SagExtractionPipeline.kt` | ✅ VERIFIED（TOCTOU 关闭，取消/并发不互踩） |
| P2-5 | `stripJsonFence` 委托 `ExtractionJson.stripCodeFence`（internal 化，单一事实源）；单行 ```json {...}``` 不再清成空串 | `LlmPromptJson.kt`、`ExtractionJson.kt` | ✅ VERIFIED（单行/多行 fence 正则行为一致） |
| P2-6 | SSE 逐字符有界读取 `readSseLineBounded`（超 64KB 即时终止，不再先撑大内存再检查） | `OpenAiCompatibleLlmProvider.kt` | ✅ VERIFIED（见 §2 R3-2 的行尾补全） |
| P2-7 | `ChatDao.touch` 专用方法替代借 `renameConversation` 回写旧标题 | `Daos.kt`、`ChatRepository.kt` | ✅ VERIFIED（剩余 rename 调用均为真改名） |
| P2-8 | 设置页进场刷新 `onScreenStarted()` + `LaunchedEffect(Unit)`（与 Chat/Graph/Import 同款） | `SettingsViewModel.kt`、`SettingsScreen.kt` | ✅ VERIFIED（与 init 双触发由代际守卫消化） |
| P2-9 | 26 处 `collectAsState()` → `collectAsStateWithLifecycle()`（6 屏全量）；新依赖 `lifecycle-runtime-compose` 2.10.0（同版本锚点） | `ui/*.kt`、`libs.versions.toml`、`app/build.gradle.kts` | ✅ VERIFIED（无残留、import 正确） |
| P2-10 | trace 导出收敛到 `ui/TraceExport.kt`（两处 40 行重复删除；字段口径逐字一致） | `TraceExport.kt`（新）、`SearchViewModel.kt`、`SettingsViewModel.kt` | ✅ VERIFIED（错误处理/状态复位/refreshTraceStats 保留） |
| P2-11 | 死代码：`traceTotalCount` 自赋值删除；`displayTitle` 死属性删除（全仓零引用） | `SettingsViewModel.kt`、`ChatViewModel.kt` | ✅ VERIFIED |
| P2-12 | 来源页抽取状态：批量 `byDocumentIds`（分批 ≤400 + groupBy）替代逐文档 N 次查询 | `SourcesViewModel.kt`、`Daos.kt` | ✅ VERIFIED（无 job 文档保留空聚合条目，语义等价） |
| P2-13 | 设置页 refresh 代际守卫（`refreshSeq`），过期快照/迟到错误不覆盖新状态 | `SettingsViewModel.kt` | ✅ VERIFIED（见 §2 R3-3 的守卫补全） |
| P2-14 | 来源页 tab + 删除确认 `rememberSaveable`（Pair 可序列化） | `SourcesScreen.kt` | ✅ VERIFIED |
| P2-15 | 运行时硬编码文案资源化：新增 5 条 strings（`chat_conversation_missing`/`chat_no_result`/`chat_citation_label`/`chat_configured_llm`[translatable=false]/`graph_event_missing`） | `strings.xml`、`ChatViewModel.kt`、`GraphViewModel.kt`、`ChatScreen.kt` | ✅ VERIFIED（@Preview 样例数据按 R2 判定保留） |
| P2-16 | `IngestRepository` 孤儿 KDoc（描述已删除的模型路由函数）删除 | `IngestRepository.kt` | ✅ VERIFIED |

## 2. 复审新发现（修复引入的回归，已当场二次修复）

复审采用"验证修复 + 回归检查"双任务，2 个并行审查组对全部 diff 逐行核对后发现 3 个回归 + 1 微瑕，均已修复并复验：

| 编号 | 问题 | 根因 | 二次修复 |
|---|---|---|---|
| R3-1 | `scanEventVectors` 中途页读取失败被 `safeSuspend→orEmpty` 吞成"空页=扫完"，**静默返回部分种子且无降级标注**（旧实现失败→整层 Unavailable 有提示） | 流式改造沿用 safeSuspend 惯例，未区分"空页"与"异常" | 页查询不再 safeSuspend；调用处 catch → `EventLayer.Unavailable("事件向量扫描失败")`，CancellationException 照常上抛（`SagRetriever.kt`） |
| R3-2 | `readSseLineBounded` 把孤立 `\r` 拼进下一行（readLine 视其为行终止符）——CR-only 行尾的服务端会多行拼接误触 64KB 上限 | 首版仅处理 `\n`，把 `\r` 当普通字符丢弃 | `\r`/`\r\n` 均按行终止符处理（mark/reset 吞掉 `\r\n` 的 `\n`），与 readLine 语义对齐（`OpenAiCompatibleLlmProvider.kt`） |
| R3-3 | SettingsViewModel refresh 的**内层 catch 无代际守卫**：旧轮 refresh 的迟到错误会覆盖新一轮已写入的成功状态（onError 已加守卫，不对称） | 代际守卫首版只覆盖 onError 与成功路径 | 内层 catch 补 `if (seq == refreshSeq)`（`SettingsViewModel.kt`） |
| R3-4（微瑕） | `chat_configured_llm` 为英文串混入中文默认 values | 文案资源化时未标注 | 加 `translatable="false"` |

**复审同时确认无回归的方面**：并发/取消/事务语义（claim 原子性、NonCancellable 回收、insertIgnoreAll 并发兜底）均未破坏；TraceExport 字段口径逐字一致；byDocumentIds 聚合语义等价；collectAsStateWithLifecycle 全量替换无遗漏。

## 3. 遗留观察（不修，已知即可）

1. **`bySpacePage` 深分页成本**：OFFSET 分页随事件量增长为 O(N×页数)——有 `(spaceId, role, ownerType)` 索引支撑、MVP 规模（≤10k 事件）可接受；若未来事件量上 5 万，再评估游标式（`WHERE id > :lastId`）分页。与 SearchService 的 chunk 向量流式扫描同口径。
2. **UI 通道 chip 显示原始 wire 值**（如 `bridge_rescue`）——与既有 `event_vector` 等通道的显示方式一致，本地化映射留给 UI 迭代。
3. lint 41 warnings（依赖过期类）与 R2 §3 遗留事项不变（`.trellis` 入库决策、detekt、B10 真机 key 复测窗口）。

## 4. 质量评级更新（相对 R2）

| 维度 | R2 | R3 | 说明 |
|---|---|---|---|
| 性能上限 | B+ | **B+** | 两处规模假设缺陷（5000 截断静默失明 / 抽取 O(N²)）消除；深分页成本为已知可接受项 |
| 正确性与错误处理 | A− | **A−** | TOCTOU/SSE 内存/fence 单行/会话标题竞态/迟到错误覆盖全部关闭 |
| 可读性与注释 | A | **A** | 每处修复延续"根因 + 复审编号"注释文化；死代码/孤儿注释清除 |
| UI 层 | — | **A−** | 状态收集随生命周期、进场刷新、saveable、文案资源化收尾完成 |

**总体判断**：R2 全部问题闭环且无新增 P1；当前工作区状态可作为下一次提交/发布候选。真机 androidTest（SagRetriever 三模式矩阵 + B8 DAO 回环 + 10k 基线）建议在下一设备窗口跑一轮作为最终确认。

## 附录：本轮验证命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:compileReleaseKotlin  # ✅
./gradlew :app:testDebugUnitTest   # ✅ 520 tests / 0 failed
./gradlew :app:lintDebug           # ✅ 0 errors / 41 warnings（+1 为新依赖版本提示）
git diff --stat                    # 27 files, +325/−213
```

---

*R3 报告生成：2026-10-19 · 修复基线 = R2 报告 @ `0139f62` + 本轮 18+4 项修复 · 上接 [R1](CODE_QUALITY_REVIEW.md) / [R2](CODE_QUALITY_REVIEW_R2.md)*
