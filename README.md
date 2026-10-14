# ZhiBox Memory（端侧个人知识记忆 MVP）

本地优先的 Android 端侧 RAG 记忆系统骨架：文档导入 → 分块 → 端侧 embedding → SQLite 检索 → 原文证据回溯，全程可离线。架构移植自 SAG 的检索数据模型思想（Kotlin 重写，非直接复用 Python 引擎）。

当前状态：**Phase 0 · P0-1 工程初始化完成**。计划与决策依据见 `docs/`。

## 构建

```bash
# 本机 Gradle 8.11 不支持系统默认 Java 25，需指定 Android Studio JBR 21：
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

- minSdk 24 / targetSdk 36 / compileSdk 36，仅 `arm64-v8a`。
- MVP 不申请任何权限（含网络）；网络权限将随模型下载器（P1-W1-5）显式引入。
- `local.properties` 配置 SDK 路径（本机 `~/Library/Android/sdk`），不入库。

## 文档导航

| 文档 | 内容 |
|---|---|
| `docs/SAG_Android_MVP_Implementation_Plan_v1.0.md` | **实施计划**（Phase 0/1 任务分解、判定门 G1–G4、验收映射） |
| `docs/SAG_Android_OnDevice_Feasibility_PRD.md` | 端侧可行性 PRD（需求、架构、验收标准 §9） |
| `docs/SAG_Android_OnDevice_Feasibility_Investigation_Report_v1.0.md` | 可行性调查报告（含附录 A：LanceDB 可替代性分析；附录 B：ANN 引擎选型全景） |
| `docs/Personal_Memory_AI_K12_Learning_Assistant_PRD_v1.0.md` | 最终项目目标（服务端形态） |
| `docs/UPSTREAM_VERSIONS.md` | 上游版本锚点（SAG commit / zleap-sag wheel / 构建链版本） |
| `docs/reference/zleap-sag-0.14.0/` | 上游参考源码（只读对照，不参与编译） |

## 里程碑

- [x] M0 / P0-1：工程初始化 + 参考素材入库 + 版本锚点记录
- [ ] P0-2：Universal Embedder demo 与资源画像
- [ ] P0-3–P0-6：判定门 G1（任务模板）/ G2（模型资产）/ G3（中文分词）/ G4（精确扫描基准）
- [ ] M-P0：PHASE0_REPORT.md，go/no-go
- [ ] Phase 1（W1–W3）：离线搜索 MVP → MVP-1 内部发布
