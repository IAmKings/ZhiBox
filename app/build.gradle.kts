import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// P0-3 发布通道：release 签名材料从 Gradle 属性或环境变量读取，密钥一律不入库。
// 解析优先级：Gradle 属性（~/.gradle/gradle.properties 或命令行 -P）> 环境变量。
// 四项缺任意一项即视为「未配置签名」：assembleRelease 仍会成功，但产物是
// app/build/outputs/apk/release/app-release-unsigned.apk（不可直接分发）。
// 配置示例（写入 ~/.gradle/gradle.properties，切勿写入仓库；*.jks/*.keystore 已在 .gitignore）：
//   ZHIBOX_STORE_FILE=/absolute/path/zhibox-release.jks
//   ZHIBOX_STORE_PASSWORD=******
//   ZHIBOX_KEY_ALIAS=zhibox
//   ZHIBOX_KEY_PASSWORD=******
// 交付前须用 `apksigner verify --print-certs` 验签，release 包才可作为 dist 产物。
// ---------------------------------------------------------------------------
val zhSecret: (String) -> String? = { name ->
    (project.findProperty(name) as? String)?.takeIf(String::isNotBlank)
        ?: System.getenv(name)?.takeIf(String::isNotBlank)
}
val zhStoreFile = zhSecret("ZHIBOX_STORE_FILE")
val zhStorePassword = zhSecret("ZHIBOX_STORE_PASSWORD")
val zhKeyAlias = zhSecret("ZHIBOX_KEY_ALIAS")
val zhKeyPassword = zhSecret("ZHIBOX_KEY_PASSWORD")
val zhHasReleaseSigning = listOf(zhStoreFile, zhStorePassword, zhKeyAlias, zhKeyPassword)
    .all { !it.isNullOrBlank() }

// ---------------------------------------------------------------------------
// B4-4 构建可追溯：BUILD_GIT_SHA / BUILD_TIME 注入 BuildConfig（debug/release 均有）
//   · BUILD_GIT_SHA：providers.exec 执行 `git rev-parse --short=7 HEAD`；非 git 工作区、
//     无 git 可执行文件或命令失败时回退 "unknown"，绝不阻塞构建配置阶段。
//   · BUILD_TIME：ISO-8601（UTC, 带偏移）；若设置 SOURCE_DATE_EPOCH 则用它保证可复现构建。
//   注意：release 变体开启 R8 后，未被引用的 BuildConfig 常量可能被裁剪；对外交付的
//   可追溯口径以 versionName + git commit/tag + 归档 sha256 为准（见 docs/RELEASE.md）。
// ---------------------------------------------------------------------------
val buildGitSha: String = try {
    providers.exec {
        commandLine("git", "-C", rootDir.absolutePath, "rev-parse", "--short=7", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifBlank { "unknown" }
} catch (e: Exception) {
    // 非 git 工作区 / 无 git 可执行文件 / 进程启动失败：不阻塞构建，标记 unknown
    "unknown"
}
// 构建时间优先级：SOURCE_DATE_EPOCH（可复现构建）→ git 最后一次 commit 时间（同一次提交
// 可增量复用 BuildConfig）→ 当前 UTC 时间（非 git 工作区兜底）。
val buildTimeIso: String = System.getenv("SOURCE_DATE_EPOCH")
    ?.takeIf { it.isNotBlank() }
    ?.let { epoch -> runCatching { Instant.ofEpochSecond(epoch.toLong()).toString() }.getOrNull() }
    ?: runCatching {
        providers.exec {
            commandLine("git", "-C", rootDir.absolutePath, "log", "-1", "--format=%cI")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
    ?: OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

android {
    namespace = "com.zhibox.memory"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhibox.memory"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "0.1.9"

        // Phase 0/1 仅针对 arm64 设备矩阵（实施计划第 2 节）
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 默认真机门只跑稳定回归：评测/性能基准用 @LargeTest 标记后按需单独执行。
        // 单项失控最多 3 分钟，避免 UTP 默认近一年超时把 connectedDebugAndroidTest 永久挂住。
        testInstrumentationRunnerArguments["notAnnotation"] = "androidx.test.filters.LargeTest"
        testInstrumentationRunnerArguments["timeout_msec"] = "180000"

        // B4-4 构建可追溯字段（BuildConfig；见文件顶部 buildGitSha/buildTimeIso 的取值策略）
        buildConfigField("String", "BUILD_GIT_SHA", "\"$buildGitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeIso\"")
        // 应用内更新：GitHub Release 查询地址由 owner/repo 派生（公开仓库匿名 API）。
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"IAmKings\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"ZhiBox\"")
        // GitHub Release 资产直连在部分网络（国内常见）不可达时的下载镜像前缀（逗号分隔，
        // 逐个回退；只看到公开 APK，完整性由 SHA-256 + APK 签名校验保证）。
        buildConfigField(
            "String",
            "UPDATE_DOWNLOAD_MIRRORS",
            "\"https://gh-proxy.com/,https://ghfast.top/\"",
        )
    }

    signingConfigs {
        // 材料齐备才注册；缺失时 release 产物为 unsigned，不阻塞 assembleRelease
        if (zhHasReleaseSigning) {
            create("release") {
                storeFile = file(zhStoreFile!!)
                storePassword = zhStorePassword
                keyAlias = zhKeyAlias
                keyPassword = zhKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // P0-3：开启 R8 代码混淆/裁剪 + 资源裁剪（keep 规则见 app/proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 未配置签名材料时保持 unsigned；配置后走 signingConfigs.release
            signingConfig = if (zhHasReleaseSigning) signingConfigs.getByName("release") else null
        }

        // ------------------------------------------------------------------
        // Macrobenchmark 被测变体（:benchmark/com.android.test 的 targetProjectPath=":app"）。
        // 语义要求：与 release 同样的 R8 优化 + isDebuggable=false（=false 才能采集真实帧耗时），
        // 但必须可安装——release 在无签名材料时是 unsigned（装不上），因此这里固定用 debug 签名。
        // 仅新增变体，不改变 debug / release 既有语义；profileable 清单见
        // app/src/benchmark/AndroidManifest.xml（仅该变体合并）。
        // 运行方式：./gradlew :benchmark:connectedBenchmarkAndroidTest --offline
        // ------------------------------------------------------------------
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            // 目标工程缺少同名变体时回落 release（:benchmark 与这里都按名称匹配 "benchmark"）
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // B4-4：生成 BuildConfig（BUILD_GIT_SHA / BUILD_TIME 可追溯字段）
        buildConfig = true
    }

    sourceSets {
        // W4-5 迁移测试：Room 导出的 schema JSON 供 MigrationTestHelper 读取
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        // Macrobenchmark 变体：R8 + isDebuggable=false，语义等同 release，因此必须复用
        // src/release 里的非 debuggable 实现（BenchmarkScreen stub）——debug 版实验屏只在
        // src/debug，若不加这一行，MainActivity 引用 BenchmarkScreen 会编译失败。
        // 仅复用 release 源集，不引入 debug 源集；debug/release 既有语义不变。
        getByName("benchmark").java.srcDir("src/release/java")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // P1-9 质量门禁：error 级 lint 必须阻断构建（CI 同样生效）；warning 暂不失败，
        // 存量 19 条 warning（依赖过期/UseKtx 等）在后续迭代清理，避免一次性阻塞。
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // 报告由 CI 归档；不启用 baseline（避免把新问题藏进基线文件）
        htmlReport = true
        xmlReport = true
        // 应用名按语言区分：values/strings.xml = ZhiBox（默认），values-zh/strings.xml = 智匣。
        // 其余约 550 条文案目前只有中文；开启 MissingTranslation 会要求 values-zh 补齐全部条目
        // 而一次性报 530 个 error。产品做完整多语言时，删除本行并补齐 values-zh。
        disable += "MissingTranslation"
    }

    testOptions {
        unitTests {
            // B5：Robolectric 需要真实 Android 资源/清单（ApplicationProvider、资源、Room 等），
            // 否则 resource/manifest 相关调用在 JVM 单测中会失败。
            isIncludeAndroidResources = true
            // 未 mock 的 framework 方法返回默认值而非抛 "not mocked"（Robolectric 用例由 shadows 接管，
            // 不受影响；纯 JVM 用例遇到静态 framework 调用时更宽容）。
            isReturnDefaultValues = true
            // Robolectric（android-all + shadows + Room in-memory）内存开销大，
            // Gradle 默认 512m 容易 OOM；单测进程统一 2g。
            all { it.maxHeapSize = "2g" }
        }
    }
}

// Kotlin 2.4：kotlinOptions DSL 已移除，统一使用 compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room schema 导出（迁移测试与版本审计依据）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// B4-1c 依赖校验（Dependency Verification）说明
//
// 目标：锁定所有远程依赖的 SHA-256，防止仓库/镜像被篡改或依赖被静默替换
// （当前 wrapper 从 huaweicloud 镜像分发 Gradle，供应链风险已在 P1-7 记录）。
//
// 生成/更新命令（必须覆盖全部配置；由 Lead 在集成窗口执行，产物入库）：
//   ./gradlew --write-verification-metadata sha256 \
//       :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
//       :app:testReleaseUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
//   # 依赖升级后重跑同一条命令更新 gradle/verification-metadata.xml
//   # 只跑 assembleDebug 会漏掉 junit/lint/androidTest 专用 artifact，下一次全量门禁会失败
//   # 如需同时校验 PGP 签名：--write-verification-metadata sha256,pgp（需要配置 keyring）
//   # 平台差异：macOS 生成的 metadata 不含 aapt2 linux jar；CI（ubuntu）首次运行需在 Linux
//   # 重新生成/补充校验和后再提交（B4-8 已登记为外部阻塞）
//
// 提交策略：
//   · gradle/verification-metadata.xml 已生成并提交（613 components；strict 生效）；
//   · 文件一旦存在，Gradle 默认对全部 configuration 启用 strict 校验，任何未登记的
//     依赖/校验和不匹配都会让构建失败——这正是本项要达到的门禁效果；
//   · 临时绕过（仅排障用）：`./gradlew --dependency-verification=off ...`；
//   · 若决定本阶段不启用严格校验：删除 gradle/verification-metadata.xml 即可（无该文件
//     即等价关闭），但需在 FIX_REPORT/RELEASE 文档记录原因，待网络/镜像稳定后再启用。
//   · 已知限制：Gradle 校验不覆盖动态版本（本项目全部为精确版本）与本地 jar。
// ---------------------------------------------------------------------------

// Runner timeout cannot always interrupt an OEM system call; bound the host task as the final guard.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    timeout.set(Duration.ofMinutes(15))
}

// B4-1a：tasks-retrieval 1.1.1 的 POM 传递依赖 litertlm-android:0.17.0-alpha1，
// 但该 alpha 缺少运行时所需的 com.google.ai.edge.litertlm.ActivationDataType
// （运行时 NoClassDefFoundError，经 AAR 反编译核验 0.18.0 才包含该类）。
// 上游修复前用 dependency constraint 把解析版本抬到 0.18.0。
// 注意：从 P0 阶段的 `configurations.all { resolutionStrategy.force(...) }` 改为 constraint，
// 只作用于以 implementation 为根的依赖图（debug/release/unitTest 均继承），
// 不再无条件强制所有 configuration（避免 buildscript/工具链类路径被隐式改版本）。
dependencies {
    constraints {
        implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0") {
            because(
                "tasks-retrieval 1.1.1 传递 0.17.0-alpha1 缺 ActivationDataType；" +
                    "0.18.0 补该类（AAR 反编译核验），上游修复前锁定"
            )
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // P1-8（W4）：每屏 ViewModel（AndroidViewModel）+ Compose collectAsState 生命周期感知
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // R2（P2-9）：collectAsStateWithLifecycle（与 lifecycle 同版本锚点）
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    // P0-2: MediaPipe Universal Embedder（LiteRT-LM 推理），版本锚点见 docs/UPSTREAM_VERSIONS.md
    implementation(libs.mediapipe.tasks.retrieval)
    // B7（构建收敛）：旧 EmbeddingGemma `.task` / Path C（tasks-text）依赖已下线，不再引入。
    // 移除收益：不再打包 tasks-text dex 及其 libmediapipe_tasks_textgenai_jni.so
    // （arm64 未压缩约 15.5MB）。保留上面的 tasks-retrieval：EGemma2 440M/740M（Path A UE）
    // 与 270M（Path B LiteRT-LM 引擎）仍需要。
    // 迁移提示：旧 .task 模型与旧空间向量不再受支持，用户需重新导入
    // （core.legacySpacesReport + 设置页迁移提示，见 B7 core/UI 批次）。
    // P0-5 G3: 结巴分词纯 Java 移植（中文分词方案对照用；G3 已证劣于 bigram，Phase 1 检视移除）
    // P0-3：jieba 仅被 debug source set 的 benchmark（ChineseTokenizationBenchmark）使用，
    // 不得进入 release 包；ui-fixer 已把 benchmark 整体迁入 app/src/debug/java。
    debugImplementation(libs.jieba.analysis)
    // P1-W1: 数据层与导入管线
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    // W4 模型商店：SAF 共享目录（DocumentFile）
    implementation(libs.androidx.documentfile)
    // P1 导入扩展：PDF 文本抽取（com.tom-roush:pdfbox-android，Apache-2.0；传递 BouncyCastle 1.72）。
    // 本任务只引入依赖；PDF 解析/抽取代码由 P1 导入任务实现。
    // 版本、仓库 fallback（Aliyun，仅该 group）与 strict 校验元数据见 libs.versions.toml / settings.gradle.kts。
    implementation(libs.pdfbox.android)
    // P1.1 导入扩展：ML Kit 端侧中文 OCR（离线 bundled 模型；文本抽取失败/扫描件 PDF 回退用）。
    // 本任务只引入依赖；OCR 调用代码由 P1.1 导入任务实现（PdfOcrFallback）。
    // license = ML Kit Terms of Service（非 Apache）；分发声明见 docs/reference/THIRD_PARTY_NOTICES.md。
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit)
    // B3-10：JVM 单测需要可用 org.json（Android 的平台 org.json 在 JVM 是 stub）
    testImplementation("org.json:json:20231013")
    // B4-1b 回归修复：lifecycle 2.10 把 app runtime 的 kotlinx-serialization-core 约束在 1.7.3，
    // 而 room-testing/room-migration 2.8.5 的 GeneratedSerializer 编译于 1.8.1（含
    // GeneratedSerializer.typeParametersSerializers 抽象方法）；若 androidTest 无法解析 1.8.1，
    // MigrationTestHelper 会抛 AbstractMethodError。
    // 注意：必须放在 implementation（app runtime）而非仅 androidTest——Gradle consistent
    // resolution 会把 app runtime 的 1.7.3 严格版本约束复制到 androidTest，导致无法升到 1.8.1。
    // 代价：serialization-core 1.8.1 会进入 APK（约 1MB；lifecycle 原本已依赖 1.7.3，非新增体积大头）。
    implementation(libs.kotlinx.serialization.core)
    // B8：LLM Provider（OpenAI-compatible）JSON 解析。与 core 同版本 1.8.1；
    // 仅使用 Json DOM API（无 @Serializable/编译器插件），不新增构建插件与 KSP 负担。
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    // P1.4 真机评测：UiAutomator（跨应用 UI 测试 + 中文 setText 输入；仅 androidTest → test APK，
    // 不进入 app APK/release）。AAR 约 130KB + 传递 androidx.tracing。
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // 真机只保留设备能力测试（例如 ActivityScenario.recreate）；可移植 Compose UI 测试在 Robolectric。
    // 版本由 Compose BOM 管理（1.11.4）；ui-test-junit4 传递 androidx.test.ext:junit（AndroidJUnit4）。
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // B5：Robolectric（JVM Android 单测；仅 test classpath，不进 APK/release）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    // UI 测试宿主 Activity 的 manifest 也需进入 JVM test classpath（release unit test 同源）。
    testImplementation(libs.androidx.ui.test.manifest)
    // ui-test-junit4 传递的 test-storage 仍声明 jsr305 2.0.2；仓库已统一使用可获取的 3.0.2。
    testImplementation(libs.jsr305)
    debugImplementation(libs.androidx.ui.tooling)
    // B5：Compose 测试宿主 Activity（仅 debug 变体，release 不包含）
    debugImplementation(libs.androidx.ui.test.manifest)
}
