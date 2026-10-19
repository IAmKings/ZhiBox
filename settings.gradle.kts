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
    }
}

rootProject.name = "ZhiBox"
include(":app")
