# 第三方组件与许可证声明（B4-5）

本项目以 **MIT License** 发布（见仓库根 `LICENSE`）。运行时/构建期依赖的主要第三方组件与许可证如下；
完整传递依赖清单可通过 `./gradlew :app:dependencies` 与 `gradle/verification-metadata.xml` 复核。

| 组件 | 用途 | 许可证 | 说明 |
|---|---|---|---|
| AndroidX / Jetpack（Compose、Room、WorkManager、Lifecycle、Activity 等） | UI、数据库、后台任务 | Apache-2.0 | 通过 Google Maven 分发 |
| Kotlin / kotlinx.coroutines | 语言与协程 | Apache-2.0 | JetBrains |
| MediaPipe Tasks（tasks-retrieval / tasks-text） | 端侧嵌入推理 | Apache-2.0 | Google |
| LiteRT-LM（litertlm-android 0.18.0，constraints 固定） | LiteRT-LM 运行时 | Apache-2.0 | Google；上游 tasks-retrieval 传递 0.17.0-alpha1 缺 `ActivationDataType`，见 `app/build.gradle.kts` 证据注释 |
| jieba-analysis | 中文分词实验对照 | **Apache-2.0** | 仅 `debugImplementation`，不进入 release（上游 POM/README 为 Apache-2.0） |
| org.json:json（testImplementation） | JVM 单测 JSON 实现 | JSON License（宽松） | 仅单元测试类路径，不进入 APK |
| JUnit 4 / AndroidX Test | 测试 | EPL-1.0 / Apache-2.0 | 仅测试类路径 |
| kotlinx-serialization-core 1.8.1（implementation） | room-testing 迁移测试运行时依赖（经 consistent resolution 提升，见 build.gradle） | Apache-2.0 | 会进入 APK（约 1MB） |

## 合规说明

- 本项目源码采用 MIT；上述第三方组件保留各自许可证与版权声明，分发时需保留本 NOTICE 与 `LICENSE`。
- 模型资产（`.litertlm` / `.task`）由用户自行投放，不随仓库/APK 分发；模型许可证以模型提供方为准。
- 若后续引入 GPL/AGPL 类依赖，需在发布前单独评估；当前依赖均为宽松许可证。
