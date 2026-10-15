plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zhibox.memory"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhibox.memory"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-mvp"

        // Phase 0/1 仅针对 arm64 设备矩阵（实施计划第 2 节）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin 2.4：kotlinOptions DSL 已移除，统一使用 compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// tasks-retrieval 1.1.1 的 POM 传递依赖 litertlm-android:0.17.0-alpha1，
// 但该 alpha 缺少运行时所需的 com.google.ai.edge.litertlm.ActivationDataType
// （运行时 NoClassDefFoundError，经 AAR 反编译核验 0.18.0 才包含该类）。
// 上游修复前强制解析到 0.18.0。
configurations.all {
    resolutionStrategy {
        force("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // P0-2: MediaPipe Universal Embedder（LiteRT-LM 推理），版本锚点见 docs/UPSTREAM_VERSIONS.md
    implementation(libs.mediapipe.tasks.retrieval)
    // P0-5 G3: 结巴分词纯 Java 移植（中文分词方案对照用）
    implementation(libs.jieba.analysis)
    debugImplementation(libs.androidx.ui.tooling)
}
