package com.zhibox.memory.core.text

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** 网页抓取结果内容（标题 + 纯文本正文；不暴露原始 HTML）。 */
data class WebPageContent(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    /** 已抽取/清洗后的正文纯文本（超过 [TextExtractionLimits.MAX_TEXT_CHARS] 截断）。 */
    val text: String,
    /** 建议的 staging 文件名（安全清洗后的标题 + `.txt`；UI 可直接使用）。 */
    val suggestedFileName: String,
)

/** 网页抓取结果（值语义，不抛业务异常；取消是例外：CancellationException 原样传播）。 */
sealed interface WebPageResult {
    data class Success(val page: WebPageContent) : WebPageResult
    data class Failure(val code: String, val message: String) : WebPageResult
}

/** 网页抓取稳定错误码（UI 按 code 分支；message 可读且不含正文/敏感信息）。 */
object WebPageErrorCodes {
    /** 网络访问被禁用（默认）：直接失败，**零网络**。 */
    const val OFFLINE: String = "WEBPAGE_OFFLINE"

    /** 非 HTTPS（含 http://、file:// 等）：直接拒绝，防止明文传输。 */
    const val INSECURE_URL: String = "WEBPAGE_INSECURE_URL"

    /** URL 畸形/缺主机/含账号密码。 */
    const val INVALID_URL: String = "WEBPAGE_INVALID_URL"

    /** 连接或读取超时。 */
    const val TIMEOUT: String = "WEBPAGE_TIMEOUT"

    /** DNS/连接被拒/TLS 等网络失败。 */
    const val NETWORK: String = "WEBPAGE_NETWORK"

    /** 非 2xx（含 3xx：不自动跟随重定向，避免 https→http 降级）。 */
    const val HTTP: String = "WEBPAGE_HTTP"

    /** 响应体超过 2MB 上限。 */
    const val TOO_LARGE: String = "WEBPAGE_TOO_LARGE"

    /** 页面没有可提取的正文文本。 */
    const val EMPTY: String = "WEBPAGE_EMPTY"

    /** 响应 Content-Type 不是 HTML/XHTML/纯文本（如二进制/PDF/图片）。 */
    const val UNSUPPORTED_CONTENT_TYPE: String = "WEBPAGE_UNSUPPORTED_CONTENT_TYPE"
}

/**
 * P1：网页正文抓取（HTTPS-only、超时、2MB 上限、标题+正文）。
 *
 * 安全/隐私约定：
 * - **禁网零请求**：先看 `networkAllowed`，false 时直接失败、不解析 URL、不创建连接；
 * - **只允许 HTTPS**（`https://`，禁止 userinfo），**不跟随重定向**（防止降级到 http / 跳到不可信主机）；
 * - 响应体最多 [MAX_PAGE_BYTES]（2MB），Content-Length 预检 + 流式读取双重限制；
 * - 不写日志；错误只含状态码/可读原因，不含网页正文/URL 凭据；
 * - 超时 = [timeoutMillis]（连接与读取分别设置）；取消（CancellationException）原样传播并关闭连接。
 *
 * 调用方（UI）只需传 `networkAllowed`（实时读设置），**不允许 UI 自行联网**。
 */
object WebPageFetcher {

    /** 网页响应体上限：2MB。 */
    const val MAX_PAGE_BYTES: Int = TextExtractionLimits.MAX_WEBPAGE_BYTES

    /** 默认连接/读取超时（毫秒）。 */
    const val DEFAULT_TIMEOUT_MS: Int = 15_000

    private const val MAX_ERROR_BODY_BYTES: Int = 8 * 1024
    private const val USER_AGENT: String = "ZhiBox/0.1 (Android)"

    /** 测试可注入的连接工厂（仅 internal；生产走 [WebPageConnectionOpener.Default]）。 */
    internal fun interface WebPageConnectionOpener {
        fun open(url: URL): HttpURLConnection

        companion object {
            val Default: WebPageConnectionOpener = WebPageConnectionOpener { url ->
                url.openConnection() as HttpURLConnection
            }
        }
    }

    /**
     * 抓取网页并抽取标题+正文。
     *
     * @param url 用户输入的网页地址（必须 https://）
     * @param networkAllowed 是否允许联网（UI 传设置值；false 时零请求）
     * @param timeoutMillis 连接/读取超时（毫秒，默认 [DEFAULT_TIMEOUT_MS]）
     */
    suspend fun fetch(
        url: String,
        networkAllowed: Boolean,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    ): WebPageResult = fetchWithOpener(url, networkAllowed, timeoutMillis, WebPageConnectionOpener.Default)

    internal suspend fun fetchWithOpener(
        url: String,
        networkAllowed: Boolean,
        timeoutMillis: Int,
        opener: WebPageConnectionOpener,
    ): WebPageResult = withContext(Dispatchers.IO) {
        if (!networkAllowed) {
            return@withContext WebPageResult.Failure(
                WebPageErrorCodes.OFFLINE,
                "网络访问已禁用：请先在设置中允许联网（离线检索不受影响）",
            )
        }
        val parsed = parseHttpsUrl(url)
            ?: return@withContext if (url.trim().startsWith("http://", ignoreCase = true)) {
                WebPageResult.Failure(
                    WebPageErrorCodes.INSECURE_URL,
                    "仅支持 HTTPS 网页地址（http:// 会明文传输，请改用 https://）",
                )
            } else {
                WebPageResult.Failure(
                    WebPageErrorCodes.INVALID_URL,
                    "网页地址不合法：请填写 https:// 开头的完整网址（不能包含账号密码）",
                )
            }
        if (timeoutMillis <= 0) {
            return@withContext WebPageResult.Failure(
                WebPageErrorCodes.INVALID_URL,
                "超时设置不合法：必须大于 0 毫秒",
            )
        }
        val host = parsed.host.orEmpty()
        if (isPrivateOrLocalHost(host) || (parsed.port != -1 && parsed.port != HTTPS_PORT)) {
            return@withContext WebPageResult.Failure(
                WebPageErrorCodes.INVALID_URL,
                "仅支持公网 HTTPS 标准端口（443）：请勿填写内网/回环地址或自定义端口",
            )
        }
        val deadlineTimeout = timeoutMillis
        var connection: HttpURLConnection? = null
        try {
            connection = opener.open(parsed).apply {
                requestMethod = "GET"
                connectTimeout = deadlineTimeout
                readTimeout = deadlineTimeout
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                setRequestProperty("Accept-Charset", "UTF-8")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val status = connection.responseCode
            if (status !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                return@withContext WebPageResult.Failure(
                    WebPageErrorCodes.HTTP,
                    httpFailureMessage(status, parsed),
                )
            }
            val contentType = connection.contentType
            if (!isSupportedContentType(contentType)) {
                return@withContext WebPageResult.Failure(
                    WebPageErrorCodes.UNSUPPORTED_CONTENT_TYPE,
                    "该地址不是 HTML/纯文本页面（Content-Type=${contentType ?: "未知"}）：请填写网页地址",
                )
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_PAGE_BYTES) {
                return@withContext WebPageResult.Failure(
                    WebPageErrorCodes.TOO_LARGE,
                    "网页内容过大（超过 ${MAX_PAGE_BYTES / (1024 * 1024)}MB）：请换用更精简的页面",
                )
            }
            val bytes = connection.inputStream.use { readBodyBounded(it, MAX_PAGE_BYTES) }
                ?: return@withContext WebPageResult.Failure(
                    WebPageErrorCodes.TOO_LARGE,
                    "网页内容过大（超过 ${MAX_PAGE_BYTES / (1024 * 1024)}MB）：请换用更精简的页面",
                )
            if (bytes.isEmpty()) {
                return@withContext WebPageResult.Failure(
                    WebPageErrorCodes.EMPTY,
                    "网页内容为空：请确认地址指向可访问的网页",
                )
            }
            val charset = charsetFromContentType(contentType)
            val isHtml = isHtmlContent(contentType, bytes)
            val extracted = if (isHtml) {
                HtmlTextExtractor.extract(bytes, charset)
            } else {
                val text = HtmlTextExtractor.decodePlainText(bytes, charset)
                HtmlTextExtractor.Extracted(title = "", text = text)
            }
            val text = ExtractedTextCleanup.truncate(
                ExtractedTextCleanup.normalize(extracted.text),
                TextExtractionLimits.MAX_TEXT_CHARS,
            )
            if (text.isBlank()) {
                return@withContext WebPageResult.Failure(
                    WebPageErrorCodes.EMPTY,
                    "网页没有可提取的正文文本：可能是脚本渲染页面或纯图片页",
                )
            }
            val host = parsed.host.orEmpty()
            val title = extracted.title.ifBlank { host }
            WebPageResult.Success(
                WebPageContent(
                    requestedUrl = url.trim(),
                    finalUrl = url.trim(),
                    title = title,
                    text = text,
                    suggestedFileName = suggestedFileName(title, host),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            WebPageResult.Failure(
                WebPageErrorCodes.TIMEOUT,
                "抓取网页超时（${deadlineTimeout / 1000}s）：请检查网络/代理，或稍后重试",
            )
        } catch (e: IOException) {
            WebPageResult.Failure(
                WebPageErrorCodes.NETWORK,
                "无法连接网页（${e.javaClass.simpleName}）：请检查网络、代理与网址",
            )
        } catch (e: IllegalArgumentException) {
            WebPageResult.Failure(
                WebPageErrorCodes.INVALID_URL,
                "网页地址不合法：请填写 https:// 开头的完整网址",
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 基础 SSRF 防护：拒绝 localhost/.local、私网/回环/链路本地/组播字面量。
     * 不在此处做 DNS 解析（避免测试/网络副作用）；DNS rebinding 的残余风险由 TLS 与用户显式输入缓解。
     */
    private fun isPrivateOrLocalHost(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        val literal = h.removePrefix("[").removeSuffix("]")
        if (IPV4_LITERAL.matches(literal)) {
            val parts = literal.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                val first = parts[0]
                val second = parts[1]
                return first == 0 || first == 10 || first == 127 ||
                    (first == 169 && second == 254) ||
                    (first == 172 && second in 16..31) ||
                    (first == 192 && second == 168) ||
                    (first == 100 && second in 64..127)
            }
        }
        if (literal.contains(':')) {
            val lower = literal.lowercase()
            if (lower == "::1" || lower == "::" ||
                lower.startsWith("fe80") || lower.startsWith("fc") || lower.startsWith("fd") ||
                lower.startsWith("ff")
            ) return true
        }
        return false
    }

    private fun isSupportedContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == "text/html" || type == "application/xhtml+xml" || type == "text/plain"
    }

    private fun parseHttpsUrl(raw: String): URL? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        return try {
            val uri = URI(trimmed)
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.host.isNullOrBlank()) return null
            if (uri.userInfo != null) return null
            uri.toURL()
        } catch (e: Exception) {
            null
        }
    }

    private fun readBodyBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun charsetFromContentType(contentType: String?): String? =
        contentType?.let { CONTENT_TYPE_CHARSET.find(it)?.groupValues?.get(1) }

    private fun isHtmlContent(contentType: String?, bytes: ByteArray): Boolean {
        if (contentType?.contains("html", ignoreCase = true) == true) return true
        if (contentType?.startsWith("text/plain", ignoreCase = true) == true) {
            return looksLikeHtml(bytes)
        }
        return looksLikeHtml(bytes)
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val prefix = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).lowercase()
        return prefix.contains("<html") || prefix.contains("<!doctype html") || prefix.contains("<body")
    }

    private fun httpFailureMessage(status: Int, url: URL): String = when {
        status in 300..399 ->
            "网页返回重定向（HTTP $status）：不支持自动跳转（防止 https 降级），请直接填写最终地址"
        status == 401 || status == 403 ->
            "网页拒绝访问（HTTP $status）：请确认无需登录/无权限限制"
        status == 404 ->
            "网页不存在（HTTP 404）：请检查网址是否正确"
        status == 429 ->
            "网页请求过于频繁（HTTP 429）：请稍后重试"
        status in 500..599 ->
            "网页服务端错误（HTTP $status）：请稍后重试"
        else ->
            "网页抓取失败（HTTP $status）"
    }

    /** staging 文件名：标题清洗（去掉路径分隔/控制字符），≤40 字符 + `.txt`；无标题用 host。 */
    internal fun suggestedFileName(title: String, host: String): String {
        val base = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
            .ifBlank { host.trim().ifBlank { "webpage" } }
            .take(40)
            .trim()
            .ifBlank { "webpage" }
        return "$base.txt"
    }

    private val CONTENT_TYPE_CHARSET = Regex("(?i)charset\\s*=\\s*[\"']?([^;\"'\\s]+)")
    private val IPV4_LITERAL = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private const val HTTPS_PORT = 443
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
}
