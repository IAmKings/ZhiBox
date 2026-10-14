# 上游版本锚点记录

> 本文件是实施计划（docs/SAG_Android_MVP_Implementation_Plan_v1.0.md）第 2 节"参考版本"决策的落地记录。
> 规则：实施期间不追新；任何锚点变更须走独立 PR 并重新执行对照 diff（见 docs/reference/zleap-sag-0.14.0/README.md 升级流程）。

## 锚点三件套（2026-10-15 记录）

| 锚点 | 值 | 说明 |
|---|---|---|
| SAG 主仓库参考 commit | `56f073c1c0c300b46707c7249d48732730656cb0` | 2026-10-09 main 快照；`apps/api/pyproject.toml` 锁定 `zleap-sag==0.14.0` |
| 参考引擎包 | `zleap-sag==0.14.0` | PyPI；MIT；关系库默认 SQLite（SQLAlchemy + aiosqlite），向量库默认 LanceDB |
| 引擎 wheel SHA-256 | `c58d12f1af0c07010a966da16112337ce92734b164832af7a708f049ad7bc429` | 提取参考素材前已校验 |

## 本工程构建链版本（2026-10-15 记录）

| 组件 | 版本 | 备注 |
|---|---|---|
| Gradle | 8.11.1 | wrapper distributionUrl 指向华为云镜像（本机网络环境下官方源经 GitHub releases 不可达）；其他环境可改回 `services.gradle.org` |
| Android Gradle Plugin | 8.9.1 | 支持 compileSdk 36 |
| Kotlin | 2.1.10 | 含 compose compiler 插件 |
| Compose BOM | 2025.01.00 | — |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 | arm64-v8a 优先 |
| 构建用 JDK | Android Studio JBR 21（`/Applications/Android Studio.app/Contents/jbr`） | 系统默认 Java 25 不被 Gradle 8.11 支持，构建时通过 JAVA_HOME 指定 JBR 21 |

## 模型资产（Phase 0 判定后补充锁定）

| 资产 | 状态 |
|---|---|
| `embeddinggemma-2-text-vision-440m.litertlm`（路径 A，Universal Embedder） | 待 P0-2 下载后记录 URL/体积/SHA-256 |
| Text Embedder + EmbeddingGemma v1（路径 B 回退） | 待 G2 盘点后记录 |
| HuggingFace 参考权重 `google/embeddinggemma-2`（G1 对照用） | 待 P0-3 记录 revision |
