package com.zhibox.memory.core.update

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** GitHub Release 查询抽象（便于 manager 单测注入 fake）。 */
interface UpdateApi {
    /**
     * 查询最新版本。
     * - [UpdateChannel.STABLE]：`releases/latest`；
     * - [UpdateChannel.BETA]：`releases?per_page=N` 中取最高 semver（含 prerelease）。
     */
    suspend fun fetchLatest(channel: UpdateChannel = UpdateChannel.STABLE): Result<UpdateInfo>
}

/**
 * 匿名 GitHub API 客户端。
 *
 * 设计约束（见 docs/product/APP_UPDATE_SPEC.md）：
 * - 只允许 HTTPS + 固定 api.github.com 地址（测试可注入 base URL）；
 * - 响应体上限 1MB（beta 通道的 releases 列表包含多个 release body）；
 * - 403/429 区分「限流」与普通错误；404 = 无 Release；
 * - 阻塞 IO 全部在 Dispatchers.IO，取消时在关键点 ensureActive。
 */
class HttpUpdateApi(
    private val releasesApiBase: String = UpdateConfig.releasesApiBase,
    private val userAgent: String = UpdateConfig.userAgent,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    /** 仅测试用：非空时把 update.json 的 host 白名单限制为该集合。 */
    private val testMetaHosts: Set<String> = emptySet(),
    /** update.json 直连不可达时的镜像前缀（与 APK 下载共用配置；测试可注入）。 */
    private val metaMirrors: List<String> = UpdateConfig.downloadMirrors,
) : UpdateApi {

    override suspend fun fetchLatest(
        channel: UpdateChannel,
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val endpoint = when (channel) {
            UpdateChannel.STABLE -> "$releasesApiBase/latest"
            UpdateChannel.BETA -> "$releasesApiBase?per_page=$BETA_PER_PAGE"
        }
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(URL(endpoint)).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", userAgent)
            }
            val code = connection.responseCode
            currentCoroutineContext().ensureActive()
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND ->
                    Result.failure(UpdateException(UpdateFailure.NoRelease, "release 404"))

                code == 403 || code == 429 -> {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    val rateLimited = remaining == "0" || code == 429
                    Result.failure(UpdateException(UpdateFailure.Http(code, rateLimited)))
                }

                code !in 200..299 ->
                    Result.failure(UpdateException(UpdateFailure.Http(code)))

                else -> {
                    val body = connection.inputStream.use { readLimited(it, MAX_BODY_BYTES) }
                    currentCoroutineContext().ensureActive()
                    val info = when (channel) {
                        UpdateChannel.STABLE -> UpdateReleaseParser.parseLatestRelease(body)
                        UpdateChannel.BETA -> UpdateReleaseParser.parseReleaseList(body)
                    }
                    Result.success(applyReleaseMeta(info))
                }
            }
        } catch (e: UpdateException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(UpdateException(UpdateFailure.Timeout))
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateFailure.Network))
        } catch (e: Exception) {
            Result.failure(
                (e as? UpdateException) ?: UpdateException(UpdateFailure.Parse, e.message),
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 读取 Release 资产中的 `*-update.json`（灰度/强制元数据）。
     * 失败时不阻断更新：保持 100% / 非强制默认值。
     */
    private fun applyReleaseMeta(info: UpdateInfo): UpdateInfo {
        val metaUrl = info.metaUrl ?: return info
        val direct = runCatching { URL(metaUrl) }.getOrNull() ?: return info
        if (!direct.protocol.equals("https", ignoreCase = true)) return info
        val allowedHosts = if (testMetaHosts.isNotEmpty()) testMetaHosts else META_ALLOWED_HOSTS
        val mirrorPrefixes = metaMirrors.filter { it.startsWith("https://") && it.endsWith("/") }
        val mirrorHosts = mirrorPrefixes.mapNotNull {
            runCatching { URL(it).host?.lowercase() }.getOrNull()
        }.toSet()

        // 与 APK 下载一致：直连（GitHub 资产 CDN）不可达时按镜像前缀回退。
        // 每条线路都加 cache-buster + no-cache，保证灰度/强制元数据调整后立即生效。
        val candidates = buildList {
            add(metaUrl to false)
            mirrorPrefixes.forEach { prefix -> add(prefix + metaUrl to true) }
        }
        for ((candidate, mirrored) in candidates) {
            val url = runCatching { URL(candidate) }.getOrNull() ?: continue
            if (!url.protocol.equals("https", ignoreCase = true)) continue
            val host = url.host?.lowercase()
            if (mirrored) {
                if (host == null || host !in mirrorHosts) continue
            } else {
                if (host == null || host !in allowedHosts) continue
            }
            val cacheBusted = runCatching {
                URL(candidate + (if ('?' in candidate) "&" else "?") + "t=" + System.currentTimeMillis())
            }.getOrNull() ?: url
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(cacheBusted).apply {
                    requestMethod = "GET"
                    connectTimeout = if (mirrored) 8_000 else 5_000
                    readTimeout = if (mirrored) 10_000 else 8_000
                    // GitHub Release 资产 URL 会 302 到 objects.githubusercontent.com；
                    // 元数据很小，跟随重定向（初始 host 已白名单校验）。
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (connection.responseCode !in 200..299) continue
                val body = connection.inputStream.use { readLimited(it, META_MAX_BODY_BYTES) }
                return UpdateMetaParser.apply(info, UpdateMetaParser.parse(body))
            } catch (e: Exception) {
                // 尝试下一条线路
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
        return info
    }

    private companion object {
        val META_ALLOWED_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
        const val META_MAX_BODY_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_BODY_BYTES = 1_024 * 1_024
        const val BETA_PER_PAGE = 10
    }
}

/** 读取至多 [limit] 字节；超出即判失败。 */
internal fun readLimited(input: InputStream, limit: Int): String {
    val buffer = ByteArray(16 * 1024)
    val output = java.io.ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) {
            throw UpdateException(UpdateFailure.Parse, "响应体超过 ${limit}B")
        }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}
