package com.zhibox.memory.core.llm

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** OpenAI-compatible `/chat/completions` 路径（B8 连通性测试用）。 */
internal const val CHAT_COMPLETIONS_PATH: String = "/chat/completions"

/** 响应读取上限（防止异常/恶意服务端返回超大 body 撑爆内存；连通性测试只需极小响应）。 */
internal const val MAX_RESPONSE_BYTES: Int = 64 * 1024

/** 非 2xx 错误体读取上限与详情截断长度（错误展示需单行、可读、不含密钥）。 */
internal const val MAX_ERROR_BODY_BYTES: Int = 8 * 1024
internal const val MAX_ERROR_DETAIL_CHARS: Int = 200

/** 共享 Json 实例：连接测试只做 DOM 解析（不启用 @Serializable 编译器插件，避免额外构建插件依赖）。 */
internal val LLM_JSON: Json = Json { ignoreUnknownKeys = true }

/**
 * OpenAI-compatible 远程 LLM Provider（B8：仅连通性测试；B11 再扩展生成/流式）。
 *
 * 安全与隐私约定（PRD FR-08 / B8 门禁）：
 * - **禁网零请求**：先问 [NetworkPolicy.isAllowed]，不允许时直接 Failure([LlmErrorCode.OFFLINE])，
 *   不创建连接、不解析 URL；
 * - **仅 HTTPS**：baseUrl 必须 `https://`（[LlmErrorCode.INSECURE_URL]），不跟随重定向
 *   （避免 https → http 降级）；
 * - **最小请求**：POST `{baseUrl.trimEnd('/')}/chat/completions`，`max_tokens=1`、单条 "ping"；
 * - **不记录 key/正文**：本类不写任何日志；错误文案只含 HTTP 状态与截断后的服务端 error.message，
 *   请求头中的 Authorization 不进入异常/结果；
 * - 超时 = [LlmConfig.timeoutSeconds]（连接与读取分别设置）。
 */
class OpenAiCompatibleLlmProvider(
    private val networkPolicy: NetworkPolicy,
    /** 可注入时钟（测试用）；默认系统毫秒。 */
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : LlmProvider {

    override suspend fun testConnection(config: LlmConfig): LlmTestResult = withContext(Dispatchers.IO) {
        // 1) 配置校验（不发请求）
        validateConfig(config)?.let { return@withContext it }

        // 2) 禁网检查：直接失败，零请求（PRD 要求可测试"禁网不发请求"）
        if (!networkPolicy.isAllowed()) {
            return@withContext LlmTestResult.Failure(
                LlmErrorCode.OFFLINE,
                "网络访问已禁用：请先在设置中允许联网（离线检索不受影响）",
            )
        }

        // 3) HTTPS-only + URL 校验（不发请求）
        val endpoint = endpointOf(config.baseUrl)
            ?: return@withContext LlmTestResult.Failure(
                LlmErrorCode.INSECURE_URL,
                "仅支持 HTTPS 的 LLM 服务地址（baseUrl 必须以 https:// 开头且不含用户名/密码）",
            )

        val startedAt = clockMillis()
        var connection: HttpURLConnection? = null
        try {
            val requestBody = buildTestRequestJson(config).toString().toByteArray(Charsets.UTF_8)
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.timeoutSeconds * 1000
                readTimeout = config.timeoutSeconds * 1000
                doOutput = true
                useCaches = false
                // 不跟随重定向：防止服务端把 https 重定向到明文 http / 其他不可信主机
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("User-Agent", "ZhiBox/${UA_VERSION} (Android)")
                // 注意：**不要**用 setFixedLengthStreamingMode —— JDK HttpURLConnection 在
                // 固定长度流式 POST 收到 401 时会丢弃响应体（getErrorStream/getInputStream 均为 null），
                // 导致 "invalid api key" 这类服务端 detail 丢失（已用本地 mock 实测，JDK 21）。
                // 连接测试请求体仅 ~100B，默认内存缓冲即可，且可正常读取 401 错误体。
            }
            connection.outputStream.use { it.write(requestBody) }

            val status = connection.responseCode
            if (status !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                val detail = readErrorDetail(connection)
                return@withContext httpFailure(status, detail)
            }

            val body = connection.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES) }
            val responseModel = parseTestResponseModel(body)
                ?: return@withContext LlmTestResult.Failure(
                    LlmErrorCode.PARSE,
                    "LLM 响应无法解析（缺少 choices[0]）：请确认 baseUrl 指向 OpenAI-compatible /chat/completions 接口",
                )
            val latencyMs = (clockMillis() - startedAt).coerceAtLeast(0L)
            LlmTestResult.Success(latencyMs = latencyMs, model = responseModel.ifBlank { config.model })
        } catch (e: CancellationException) {
            // 协程取消必须原样重抛（错误处理规范：不得吞 CancellationException）
            throw e
        } catch (e: SocketTimeoutException) {
            LlmTestResult.Failure(
                LlmErrorCode.TIMEOUT,
                "连接 LLM 超时（${config.timeoutSeconds}s）：请检查网络/代理，或调大超时时间",
            )
        } catch (e: IOException) {
            LlmTestResult.Failure(
                LlmErrorCode.NETWORK,
                "无法连接 LLM 服务（${e.javaClass.simpleName}）：请检查网络、代理与 baseUrl",
            )
        } catch (e: IllegalArgumentException) {
            LlmTestResult.Failure(
                LlmErrorCode.INVALID_CONFIG,
                "LLM 配置不合法：请检查 baseUrl / model / 超时设置",
            )
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        /** 生产便捷构造：禁网策略实时读取设置（默认禁网）。 */
        fun from(context: Context): OpenAiCompatibleLlmProvider =
            OpenAiCompatibleLlmProvider(NetworkPolicy.from(context))
    }
}

// ---------------------------------------------------------------------------
// 纯逻辑辅助（internal 以便 JVM 单测直接覆盖，且被 data-reviewer 的 mock server 测试复用）
// ---------------------------------------------------------------------------

/** 配置合法性检查；返回 null 表示可继续（仅本地判断，不发请求）。 */
internal fun validateConfig(config: LlmConfig): LlmTestResult.Failure? = when {
    config.baseUrl.isBlank() -> LlmTestResult.Failure(
        LlmErrorCode.INVALID_CONFIG,
        "未填写 baseUrl（例如 https://api.openai.com/v1）",
    )
    config.model.isBlank() -> LlmTestResult.Failure(
        LlmErrorCode.INVALID_CONFIG,
        "未填写模型名（例如 gpt-4o-mini）",
    )
    config.apiKey.isBlank() -> LlmTestResult.Failure(
        LlmErrorCode.NOT_CONFIGURED,
        "未配置 API Key：请先在设置中保存密钥",
    )
    config.timeoutSeconds !in LlmConfig.MIN_TIMEOUT_SECONDS..LlmConfig.MAX_TIMEOUT_SECONDS ->
        LlmTestResult.Failure(
            LlmErrorCode.INVALID_CONFIG,
            "超时时间需在 ${LlmConfig.MIN_TIMEOUT_SECONDS}–${LlmConfig.MAX_TIMEOUT_SECONDS} 秒之间",
        )
    config.maxTokens !in LlmConfig.MIN_MAX_TOKENS..LlmConfig.MAX_MAX_TOKENS ->
        LlmTestResult.Failure(
            LlmErrorCode.INVALID_CONFIG,
            "maxTokens 需在 ${LlmConfig.MIN_MAX_TOKENS}–${LlmConfig.MAX_MAX_TOKENS} 之间",
        )
    else -> null
}

/**
 * 拼接 `/chat/completions` 并校验 HTTPS。
 * 返回 null 表示不可用（非 https/畸形/含 userinfo）。
 */
internal fun endpointOf(baseUrl: String): URL? {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
    return try {
        val uri = URI(trimmed + CHAT_COMPLETIONS_PATH)
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        // 禁止把账号密码写进 URL（避免凭证经 URL 泄漏）
        if (uri.userInfo != null) return null
        uri.toURL()
    } catch (e: Exception) {
        // MalformedURLException / URISyntaxException 等：统一视为非法配置
        null
    }
}

/**
 * 构造最小连通性测试请求（不含用户正文；`max_tokens=1` 以最小化成本）。
 * 纯逻辑，便于单测断言字段与转义。
 */
internal fun buildTestRequestJson(config: LlmConfig): JsonObject = buildJsonObject {
    put("model", config.model)
    put(
        "messages",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                },
            )
        },
    )
    put("max_tokens", 1)
    put("stream", false)
}

/**
 * 解析 OpenAI-compatible 响应：要求存在 `choices[0].message`（内容可空）。
 *
 * @return 服务端返回的 `model` 字段（可能为空串）；解析失败返回 null。
 * 纯逻辑，便于用录制 JSON fixture 单测（忽略未知字段）。
 */
internal fun parseTestResponseModel(body: String): String? {
    return try {
        val root = LLM_JSON.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        first["message"]?.jsonObject ?: return null
        root["model"]?.jsonPrimitive?.contentOrNull ?: ""
    } catch (e: Exception) {
        // 非法 JSON/字段类型不符：返回 null，由调用方映射为 LLM_PARSE_ERROR（不崩溃）
        null
    }
}

/** 有界读取（最多 [maxBytes]，UTF-8 解码；防止超大/异常响应 OOM）。 */
internal fun readBounded(input: InputStream, maxBytes: Int): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total < maxBytes) {
        val read = input.read(buffer)
        if (read <= 0) break
        val allowed = minOf(read, maxBytes - total)
        out.write(buffer, 0, allowed)
        total += allowed
        if (allowed < read) break
    }
    return out.toString(Charsets.UTF_8.name())
}

/** 读取非 2xx 错误体并提取可读 error.message（截断、单行）；失败返回 null。 */
internal fun readErrorDetail(connection: HttpURLConnection): String? {
    return try {
        val stream = connection.errorStream ?: return null
        stream.use { parseErrorDetail(readBounded(it, MAX_ERROR_BODY_BYTES)) }
    } catch (e: IOException) {
        null
    }
}

/**
 * 从错误响应中提取 `error.message` / `message`（没有则 null）。
 * 安全：不包含 key；截断 [MAX_ERROR_DETAIL_CHARS] 并压成单行，避免日志/UI 注入换行。
 */
internal fun parseErrorDetail(body: String): String? = try {
    val root = LLM_JSON.parseToJsonElement(body).jsonObject
    val raw = (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        ?: root["message"]?.jsonPrimitive?.contentOrNull
    raw?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_ERROR_DETAIL_CHARS)?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/** 非 2xx → 结构化失败（错误码 + 可读动作建议；不包含 key/正文）。 */
internal fun httpFailure(status: Int, detail: String?): LlmTestResult.Failure {
    val code = when {
        status == 401 || status == 403 -> LlmErrorCode.AUTH
        status == 429 -> LlmErrorCode.RATE_LIMITED
        else -> LlmErrorCode.HTTP
    }
    val baseMessage = when {
        status == 401 || status == 403 ->
            "鉴权失败（HTTP $status）：请检查 API Key 是否正确、是否过期/有权限"
        status == 429 ->
            "请求被限流（HTTP $status）：请稍后重试"
        status == 404 ->
            "接口不存在（HTTP 404）：请确认 baseUrl 是 API 根地址" +
                "（例如 https://api.openai.com/v1），不要把 /chat/completions 写进 baseUrl"
        status in 500..599 ->
            "LLM 服务端错误（HTTP $status）：请稍后重试"
        else ->
            "LLM 服务返回异常（HTTP $status）"
    }
    val message = if (detail.isNullOrBlank()) baseMessage else "$baseMessage：$detail"
    return LlmTestResult.Failure(code, message)
}

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val UA_VERSION = "0.1"
