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
    suspend fun fetchLatest(): Result<UpdateInfo>
}

/**
 * 匿名 GitHub API 客户端：
 * `GET https://api.github.com/repos/<owner>/<repo>/releases/latest`。
 *
 * 设计约束（见 docs/product/APP_UPDATE_SPEC.md）：
 * - 只允许 HTTPS + 固定 api.github.com 地址（测试可注入 base URL）；
 * - 响应体上限 512KB，防止异常大响应拖垮内存；
 * - 403/429 区分「限流」与普通错误；404 = 无 Release；
 * - 阻塞 IO 全部在 Dispatchers.IO，取消时在关键点 ensureActive。
 */
class HttpUpdateApi(
    private val latestReleaseUrl: String = UpdateConfig.latestReleaseUrl,
    private val userAgent: String = UpdateConfig.userAgent,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : UpdateApi {

    override suspend fun fetchLatest(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(URL(latestReleaseUrl)).apply {
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
                    Result.failure(UpdateException(UpdateFailure.NoRelease, "releases/latest 404"))

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
                    Result.success(UpdateReleaseParser.parseLatestRelease(body))
                }
            }
        } catch (e: UpdateException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(UpdateException(UpdateFailure.Timeout))
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateFailure.Network))
        } catch (e: Exception) {
            // 解析异常等在 parser 内已转为 UpdateException；兜底按 Parse 处理。
            Result.failure(
                (e as? UpdateException) ?: UpdateException(UpdateFailure.Parse, e.message),
            )
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_BODY_BYTES = 512 * 1024
    }
}

/** 读取至多 [limit] 字节；超出即判失败（GitHub latest release JSON 远小于上限）。 */
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
