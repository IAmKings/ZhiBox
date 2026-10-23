pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // P1 导入扩展（PDFBox-Android / BouncyCastle）：本机网络对 Maven Central 该 group 返回 403，
        // 因此加 Aliyun central mirror 作为**限定 group 的 fallback**：
        // - 顺序保持 mavenCentral 优先（CI 正常可达 Central，走 Central 可解析）；
        // - 实测 Gradle 在 Central 403 时会继续尝试下一个 repository（仅本地 403 触发镜像）；
        // - content filter 只放行 com.tom-roush / org.bouncycastle，不影响其它依赖与 plugins。
        // 校验和：镜像与 Central 同内容，verification-metadata 的 SHA-256 在 CI 侧同样匹配。
        maven("https://maven.aliyun.com/repository/central") {
            content {
                includeGroup("com.tom-roush")
                includeGroup("org.bouncycastle")
            }
        }
        // Macrobenchmark（:benchmark）的 androidx.benchmark 1.5.0 传递依赖 com.squareup.wire /
        // com.squareup.okio / org.jspecify 在本机同样被 Maven Central 403 拦截；且本机离线缓存
        // 是用 Aliyun public 镜像预热的——Gradle 的离线依赖元数据按「仓库 ID（URL）」记录，
        // 用上面的 /central 地址匹配不到。因此再追加一个**限定 group** 的 public 镜像 fallback：
        // - 顺序依然 Central 优先（CI 可达 Central 时不走镜像）；
        // - content filter 只放行 com.squareup.* / org.jspecify，不影响其它依赖；
        // - 校验和：镜像与 Central 同内容，verification-metadata 的 SHA-256 在 CI 侧同样匹配。
        maven("https://maven.aliyun.com/repository/public") {
            content {
                includeGroupByRegex("com\\.squareup.*")
                includeGroup("org.jspecify")
            }
        }
    }
}

rootProject.name = "ZhiBox"
include(":app")

// Macrobenchmark 独立测试模块（com.android.test，targetProjectPath=":app"）：
// 只产出 benchmark 变体 test APK，不进入 app 产物。依赖见 benchmark/build.gradle.kts。
include(":benchmark")
