# 第三方组件与许可证声明（B4-5）

本项目以 **MIT License** 发布（见仓库根 `LICENSE`）。运行时/构建期依赖的主要第三方组件与许可证如下；
完整传递依赖清单可通过 `./gradlew :app:dependencies` 与 `gradle/verification-metadata.xml` 复核。

| 组件 | 用途 | 许可证 | 说明 |
|---|---|---|---|
| AndroidX / Jetpack（Compose、Room、WorkManager、Lifecycle、Activity 等） | UI、数据库、后台任务 | Apache-2.0 | 通过 Google Maven 分发 |
| Kotlin / kotlinx.coroutines | 语言与协程 | Apache-2.0 | JetBrains |
| MediaPipe Tasks（tasks-retrieval / tasks-text） | 端侧嵌入推理 | Apache-2.0 | Google |
| LiteRT-LM（litertlm-android 0.18.0，constraints 固定） | LiteRT-LM 运行时 | Apache-2.0 | Google；上游 tasks-retrieval 传递 0.17.0-alpha1 缺 `ActivationDataType`，见 `app/build.gradle.kts` 证据注释 |
| PDFBox-Android（com.tom-roush:pdfbox-android 2.0.27.0） | P1 PDF 文本抽取（仅文本型 PDF，不做 OCR/图片解码） | Apache-2.0 | AAR 资源含 cmap/glyphlist；JPX 可选图片解码器不引入，R8 `-dontwarn com.gemalto.jp2.**` |
| BouncyCastle（bcprov/bcpkix/bcutil-jdk15to18 1.72） | PDFBox 的加密/签名传递依赖 | Bouncy Castle Licence（MIT 风格） | 随 PDFBox 进入 APK |
| ML Kit 端侧中文 OCR（`com.google.mlkit:text-recognition-chinese:16.0.1` 及其 Play Services 传递依赖） | P1.1 扫描件 PDF 的离线 OCR（文本抽取失败时回退；模型随 AAR 分发，不联网下载） | **ML Kit Terms of Service**（https://developers.google.com/ml-kit/terms，**非 Apache-2.0**） | AAR 内置 gocr/taser 中文+拉丁 OCR 模型（APK 内 `assets/mlkit-google-ocr-models/` 2,490,310 B）与 native `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so`（11,064,544 B）；实测 APK 增量 debug +15.0MB / release +13.8MB（2026-10-20）。AAR 根目录 `third_party_licenses.txt` 不会自动打包进 APK，分发文档须保留并遵守 ML Kit ToS 及其列明的第三方许可证 |
| jieba-analysis | 中文分词实验对照 | **Apache-2.0** | 仅 `debugImplementation`，不进入 release（上游 POM/README 为 Apache-2.0） |
| org.json:json（testImplementation） | JVM 单测 JSON 实现 | JSON License（宽松） | 仅单元测试类路径，不进入 APK |
| JUnit 4 / AndroidX Test | 测试 | EPL-1.0 / Apache-2.0 | 仅测试类路径 |
| kotlinx-serialization-core 1.8.1（implementation） | room-testing 迁移测试运行时依赖（经 consistent resolution 提升，见 build.gradle） | Apache-2.0 | 会进入 APK（约 1MB） |

## 合规说明

- 本项目源码采用 MIT；上述第三方组件保留各自许可证与版权声明，分发时需保留本 NOTICE 与 `LICENSE`。
- 模型资产（`.litertlm` / `.task`）由用户自行投放，不随仓库/APK 分发；模型许可证以模型提供方为准。
- 例外：P1.1 的 ML Kit 中文 OCR 使用 **ML Kit Terms of Service**（非 Apache-2.0），其 AAR 内置 OCR 模型随 APK 分发；发布前须复核 ML Kit ToS 义务，且 AAR 内 `third_party_licenses.txt` 列明的第三方许可证需一并保留。
- 若后续引入 GPL/AGPL 类依赖，需在发布前单独评估；其余当前依赖均为宽松许可证。
