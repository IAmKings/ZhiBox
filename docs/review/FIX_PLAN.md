# ZhiBox P0/P1 修复计划（FIX_PLAN）

> 依据：[`docs/CODE_QUALITY_REVIEW.md`](../CODE_QUALITY_REVIEW.md)（P0×3 / P1×10 类 / P2×25）
> 范围：**全部 P0 + 全部 P1 主题**。P2 仅在"修复 P0/P1 必须触碰"时顺带处理，其余不在本轮。
> 基线快照：`.fix-baseline/`（修复前源码/构建/脚本副本，供变更审查 diff）
> 修复前 HEAD：`9ca20d6` + 未提交工作区状态

---

## 1. 修复目标（Definition of Done）

| 级别 | 项 | 完成判据（可验证） |
|---|---|---|
| P0-1 | 备份/恢复数据安全 | ① 破坏包/中途失败导入：原库保持不变（事务回滚）；② 导入恢复 `embedding_meta`；③ 导出→导入→再导出的 vectors 头 count 与 spaces 数不降；④ 有对应单元/集成测试 |
| P0-2 | 主线程重活 | ① `SearchService` 扫描、`EmbeddingRuntime` 引擎构建、`IngestRepository` 文件 IO 均离开 Main；② debug StrictMode 下主流程无 diskRead/diskWrite 告警（代码审查级验证） |
| P0-3 | 实验隔离与发布 | ① benchmark 包与 BenchmarkScreen 移入 `src/debug`，release 无实验入口/无法经 Intent 触发；② 首页实验按钮与 autostart 仅在 debuggable 构建生效且一次性消费；③ `assembleRelease` 成功产出（R8+shrink）；④ `allowBackup=false` |
| P1-1 | 检索一致性 | 两条候选查询 join `document.status='ready'`；失败/取消路径清理派生数据；维度校验先于入库；`failed` 文档 0 命中测试 |
| P1-2 | 取消/失败语义 | `CancellationException` 重排队并重抛、不算业务失败；失败有重试入口（attempt 上限）；取消竞态不再覆写 cancelled |
| P1-3 | 运行时生命周期 | 重建失败后 `shared` 置空可自愈；GPU 试探超时关闭泄漏引擎；`shutdown` 加锁 |
| P1-4 | 导入/重嵌入并发 | 单文件异常不再造成"有 job 无 worker"；入队无丢唤醒；Reembed 锁粒度降为每批 + 逐批进度 |
| P1-5 | 检索扩展性 | v4 索引 `(embeddingSpaceId, role)`；向量矩阵缓存 + 写路径版本失效；N+1 改批量查询 |
| P1-6 | 空间指纹/元数据 | `modelRevision` 写入 descriptor 并参与空间指纹；meta 的 accelerator/quantization/taskTemplate 由 descriptor 生成；删除死常量 `SPACE_ID_C`；`parserId/sizeBytes` 正确落库 |
| P1-7 | 备份完整性/模型供应链 | 校验 format_version、包内存量与 SHA-256、zip 尺寸上限；统一 `ModelIntegrity.verify` 用于下载/SAF 启用/备份导入；修复 preview 端序与"在位判定 false-negative"；验证门接入或删除 |
| P1-8 | UI 状态/导航 | ViewModel + `rememberSaveable` 覆盖 5 个产品屏；`BackHandler` 返回上一屏；证据页返回不丢检索结果；`runProbe` 等异常边界修复；用户可见文案迁 `strings.xml` |
| P1-9 | 工程质量门禁 | Room schema 2/3/4 入库；lint 0 error 并设 `abortOnError`；新增 CI workflow（assembleDebug+unit+lint）；关键路径新增单测（备份校验/空间指纹/取消语义/检索过滤） |
| P1-10 | 隐私与安全 | `allowBackup=false`；`networkSecurityConfig` 显式 TLS + 域名白名单；下载器校验 https/host；SAF 目录操作最小化（只删已知模型、权限异常不崩溃） |

**总验收门禁**：`compileDebugKotlin` ✅、`testDebugUnitTest` ✅（新增用例通过）、`lintDebug` ✅（0 error）、`assembleDebug` ✅、`assembleRelease` ✅、`compileDebugAndroidTestKotlin` ✅。

---

## 2. 工作流拆分与文件所有权（防冲突）

| 工作流 | 负责人 | 独占写入范围（其他成员不得修改） |
|---|---|---|
| **W0 地基** | Lead | `data/db/Entities.kt`、`data/db/AppDatabase.kt`、`data/db/Daos.kt`(初始化)、`androidTest/.../MigrationTest.kt`、`app/schemas/**`、`gradle.properties` |
| **W1 备份/模型完整性** | backup-fixer | `core/backup/**`、`core/model/ModelIntegrity.kt`(新)、`core/model/ModelDownloader.kt`、`core/model/SharedModelStore.kt`、`core/model/PathPreferences.kt`、`core/model/ModelCatalog.kt` |
| **W2 构建/发布/门禁** | build-fixer | `app/build.gradle.kts`、`gradle/libs.versions.toml`、根 `build.gradle.kts`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/**`、`.github/**`、`.gitignore`、`app/src/debug/AndroidManifest.xml`（如需） |
| **W3 核心管线** | core-fixer | `core/search/**`、`core/embedding/**`、`core/ingest/**`、`data/db/Daos.kt`(W0 之后)、`data/db/Entities.kt`(仅注释/无需变更时不动) |
| **W4 UI/状态** | ui-fixer | `MainActivity.kt`、`ui/**`（含把 `BenchmarkScreen.kt` 移入 debug）、`app/src/debug/java/**`、`app/src/release/java/**`、`res/values/strings.xml`、`ui/theme/**` |
| **W5 集成/审查** | Lead | 全局集成、冲突裁决、构建门禁、独立 reviewer 派发、最终报告 |

### 接口契约（跨工作流，先定后写）

1. **EmbeddingRuntime**（W3）：`registerSpace(context, provider, model, spaceId)` 成为 embedding_meta 唯一写入口；`withProvider` 内部切 `Dispatchers.Default`；`shutdown()` 改 `suspend`；新增 `@VisibleForTesting` provider factory 注入（供单测）。
2. **ModelIntegrity**（W1）：`object ModelIntegrity { suspend fun verify(file: File, expectedSha256: String?, expectedSize: Long?): Result<Unit> }`；W1 负责接入 W1 范围内 3 条链路，W3 不依赖。
3. **Backup v2 格式**（W1）：`FORMAT_VERSION=2`；导入同时兼容 v1（缺失字段用默认值）；W1 内部闭环，不要求 W4 改 UI（设置页保持现有入口）。
4. **DAO 增量**（W0 提供，W3 可继续扩展）：批量查询、guarded update、retry/attempt、clear/deleteAll、app_kv all；W1 只使用 W0 提供的 DAO，不改 `Daos.kt`。
5. **Benchmark 隔离**（W4 先移动，W2 后修 lint）：`src/debug/java/.../benchmark/**` + `ui/BenchmarkScreen.kt` 移动由 W4 完成；`release` 侧提供空实现 `ui/DiagnosticsRoute.kt`；W2 在移动完成后修 `VectorScanBenchmark` 的 NewApi。
6. **UI 依赖**（W2 先加）：`lifecycle-viewmodel-compose`（version ref = lifecycle）；W4 代码可先写，依赖由 W2 添加。

---

## 3. 任务分解与执行顺序

### Phase 0：地基（Lead，串行，先完成才派工）
- T0.1 `Entities`：`chunk_embedding` 增 `Index(embeddingSpaceId, role)`；`ingest_job` 增 `attempt`。
- T0.2 `AppDatabase` v4 + `MIGRATION_3_4`；生成 `4.json`；MigrationTest 增 3→4。
- T0.3 `Daos`：批量查询（documents/sources）、guarded update（stage/status）、requeue/attempt/retryFailed、deleteAll（source/document/chunk/chunk_embedding/ingest_job/embedding_meta）、app_kv `all()`、`updateParserAndSize`。
- T0.4 编译 + 既有单测 + androidTest 编译绿；`git add app/schemas`。
- **Gate G0**：`compileDebugKotlin` ✅、`testDebugUnitTest` ✅、`compileDebugAndroidTestKotlin` ✅、`4.json` 存在。

### Phase 1：四路并行（W1–W4）
- **W1 backup-fixer**：P0-1、P1-7、P1-10（模型/SAF 部分）。任务：T1.1 备份事务化+meta 恢复+v2 字段；T1.2 完整性校验/尺寸上限/端序/在位判定；T1.3 ModelIntegrity 统一校验 + 验证门；T1.4 SharedModelStore/Downloader 加固；T1.5 单测（校验器/SHA/往返）。
- **W2 build-fixer**：P0-3（构建侧）、P1-9、P1-10（Manifest/网络）。任务：T2.1 ViewModel 依赖 + jieba 改 debugImplementation；T2.2 release 签名/R8/proguard；T2.3 Manifest `allowBackup=false` + `networkSecurityConfig`；T2.4 CI workflow + lint 门禁；T2.5（依赖 W4 移动完成）修 NewApi lint；T2.6 `assembleRelease` 验证。
- **W3 core-fixer**：P0-2（核心侧）、P1-1/2/3/4/5/6。任务：T3.1 Runtime 生命周期 + 引擎构建切 Default + 可注入 factory；T3.2 空间指纹 + meta 单一写入口 + 删除 SPACE_ID_C；T3.3 检索状态过滤 + 批量回填 + 缓存 + 主线程边界；T3.4 导入事务/发布语义/失败清理/维度前置；T3.5 取消语义 + 重试 + Worker 竞态；T3.6 Reembed 锁粒度/进度；T3.7 单测（spaceId/filter/runtime）。
- **W4 ui-fixer**：P0-3（UI 侧）、P1-8、P1-2（UI 错误边界）。任务：T4.1 benchmark 包+BenchmarkScreen 移 debug + release stub（**最先做**）；T4.2 MainActivity autostart 一次性消费 + debuggable 门禁 + BackHandler + rememberSaveable；T4.3 五屏 ViewModel（Search/Import/Sources/Settings + Main）与 StateFlow；T4.4 异常边界/取消/下载返回提示；T4.5 文案迁 strings.xml；T4.6 修 `runProbe` 等确定性 bug。

### Phase 2：集成（Lead）
- T5.1 合并冲突裁决、编译错误修复；
- T5.2 `compileDebugKotlin` / `testDebugUnitTest` / `lintDebug` / `assembleDebug` / `assembleRelease` / `compileDebugAndroidTestKotlin`；
- T5.3 修复集成问题并复跑。

### Phase 3：独立审查（Lead + code-review skill）
- T6.1 以 `.fix-baseline/` 为基线，对全部修复变更做 Standards + Spec 双轴审查（独立 reviewer 子代理）；
- T6.2 修复审查发现的问题；
- T6.3 输出 `docs/review/FIX_REPORT.md`（修复清单/验证记录/剩余风险）。

---

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| 并发改同一文件 | 文件所有权矩阵 + 共享任务 write_scopes；跨契约改动先由 Lead 落接口 |
| Room schema 与迁移不一致 | W0 生成 4.json 后，任何人不得再改 Entities/AppDatabase；如需变更回到 Lead |
| 4 个成员同时跑 Gradle 冲突 | 各成员只在"完成时"跑一次 `compileDebugKotlin`；集成门禁由 Lead 统一跑全量 |
| R8 破坏 MediaPipe/Room 反射 | proguard 保守 keep（`com.google.mediapipe.**`、`com.google.ai.edge.litertlm.**`、`com.google.flatbuffers.**`、Room/Compose 默认规则）；`assembleRelease` 仅验证构建，不承诺未真机跑 |
| ViewModel 重构面积过大 | 只引入 `lifecycle-viewmodel-compose` 单依赖；每屏 VM 用 `AndroidViewModel`，不引入 Hilt/Navigation |
| 无 arm64 设备 | 仪器测试只保证编译；功能验证以 JVM 单测 + 代码审查 + 逻辑推演为准，真机项写入"剩余风险" |
| P1 无法全部达标 | 按 P1-1…P1-10 逐项验收；未达标项在 FIX_REPORT 明确标注并附原因，不虚报 |

---

## 5. 验证命令（每阶段复跑）

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --rerun
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## 6. 交付物

- 代码修复（工作区）
- `docs/review/FIX_PLAN.md`（本文件）
- `docs/review/FIX_REPORT.md`（修复清单、逐项验收证据、验证命令输出、剩余风险）
- 新增单测与 CI workflow
- 独立审查记录（Standards/Spec 两轴结论）
