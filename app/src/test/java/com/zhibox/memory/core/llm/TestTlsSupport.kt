package com.zhibox.memory.core.llm

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * B11 测试专用 TLS mock 基础设施（JVM 单测，无外网）：
 * - 内嵌自签 PKCS12（密码仅测试用），用于本地 HTTPS ServerSocket；
 * - [MockHttpsServer] 在构造/关闭时替换并恢复 `HttpsURLConnection` 默认 SSL 工厂与 HostnameVerifier，
 *   使真实 `HttpsURLConnection` 能完成 TLS 握手（Provider 强制 HTTPS，plain HTTP mock 无法覆盖）。
 *
 * 与 `LlmProviderMockHttpsTest` 内私有实现同源；本文件供 B11 SSE 测试复用（测试代码重复可接受，
 * 避免改动已验证的 B8/B9 测试文件）。
 */
internal object TestTlsSupport {

    const val KEYSTORE_PASSWORD: String = "zhibox-mock"

    private val KEYSTORE_B64: String = """
        MIIJ8AIBAzCCCZoGCSqGSIb3DQEHAaCCCYsEggmHMIIJgzCCBaoGCSqGSIb3DQEHAaCCBZsEggWXMIIFkzCCBY8GCyqGSIb3DQEM
                    CgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFEYhCU1V/1yVU8MSEUGIvwRXH39uAgInEAIBIDAMBggq
                    hkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQQgPhDmoOK7YeWsZZmPXnegSCBNC30UlhDA6BQGwGEbPkwyRsw09OjMndSuw2akeBOFol
                    CxBOzJSVPBNYUlr6nuWfqzFgVlp0OlIFzL/HFZ2ZKNDFQWErfJzlIfOok3qfniiF4F70+hfmdqxB63QnFnnBT+TskNjJ4FrIMByW
                    JFnicFsrhbzw0hXDeh6XKG0JQ4Uw5DGp4VR7CX/1NqPmsQGwAwrfgv7p8tRq9+UVmgKqMNNcLvCW6/JfulkicLe0TWvnPdJ6op1t
                    RYAIP1rfHYR36vd25PGZ52kOVMDkx+CFjLbacxD17Fq7PvNeQC297hI9G/q+WfBWPq1yEgC1hfMKLvFZbUqSlzFrDGAixA87Xu22
                    BXvoq1MF83tQlQBjnzbdZ7PRZS1AToENForxx6cs/JGB9K0385OqreTd30CEfmNsVH1tjUiuUaxqjDBRBvKoLzialaPpjoGmS2+c
                    1BN0ZgGP571DBRh7xFz/TIJkKfWz++x2curG+qjftobIhNvl3B71USl76ZrEmnJViyS4oyh5/4wrPRpyMW+yuWCFhwhd+E0ZiOH1
                    e4t2+y2QqeWWORodM/yNbADzQXhcIgtKPyhXbW+sAk3OMETBDIOWAJVCBZp2vGe2Cac7hjDn1m3YJB0G0wC2aF+PZUmCAJmNR/5G
                    VfxwePV57p3XKAK9uVeDncnUWTN8zM+zJFR3pQr+lz5eAL/aBHBjhowQAI/Ski9zCltf/dN3NJqztkZbqscydDHPqndycVUBTJCE
                    OjY7ys4E6Kr+um7nH2A1dEJBUDpUmSi2fwWbXVBzPQuFV0QAIQrHJ5JLGU0mQZa+XY5+dPR+A0tfZyjnNvHdrNB2V4eAeqWQt/sR
                    Se97xoHZvV/jUhUcWLr1YMaG0pI6e+kZa1PmtgMuriOV9QiI2aIs+Skoz2xMQ6zcbjNa+CkKCQaNiWJ2cgrYp9FzW9W+hErjjcqM
                    k4nHlIZXe96U4YcDHc4ZM1M6ZLG8UYbF9S4kTx3H5JEODzq6KgF/yYSmadBVyTZRidpVUoaljbvO4BdqRtA7jbbzzONOJBCVXTft
                    rvPPkoIRAYecquyeA7pUpUhGXZAcwF9NVSsvC8tzhVre1Z8bAgLypR0UcW7ql8gEgUJLfbKnILJs97gyhEFFHKFRY+Yq/fLCYjv3
                    NTL1WxNuvBoSeja5PCYVpOGp2gLgDKJaY4U8UNWoDb4B0lG5C/RJCA+nvOxnePftNXiPaNlkgmbzCkRtyWSIGzq7NL1xVDPlNWAk
                    Hsa4FNpSzR3GfM7H8hnCpq7Ru2NZTeC5oIkYmyLxU5tvTBYVm80qMElh5Fwol2BPGy3zMSjPScR6uCd2k+A2hZ/Mx2RBq07dd3En
                    xULMFPFN4VkC8a8nQwX/LyO9aynN5s9fdUlFBAixv0lsRaSBsZNYfuYO/mBkjiv1LyFHM4JS6m4NHru1pwGmrY+9xMJQa2odeZyJ
                    mTFaoXgej8nEacTPH6+ezLnlBWoxvpZSQ9x8a32Fe9cftroIrFOPFMel8koa+64NfLCjvkYnpC8ciOnCnivUN7+yyzexMPgg97zx
                    X/UZCLL0MBSCa8RdDlwm6hPp3hbHShW2vRAaaZ1uJuKcwdNuxTTpUZ1RW1YSg2FkUCrfkXavmdrSQvnPNImhsTldUHILnTpBRhDs
                    3DE8MBcGCSqGSIb3DQEJFDEKHggAbQBvAGMAazAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzkyMjYxMjY2NjE4MIID0QYJKoZIhvcN
                    AQcGoIIDwjCCA74CAQAwggO3BgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFPVmzL59FcbSEvy6I3qs
                    rKmpFdWjAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQwS0CzCJwmptcvQuNIkowe4CCA0AH4V7rSCppbU6ZiR1k
                    3spunvzUWw+VYFZNT2CK7lic5oNU1hileZnY0Xbr0AoW26jNRh/zDNkbEhGVmnNcxdj2VzZEBOLCVD70ufOiSxPOHnUG81vKWsGO
                    qYFHR8keJSXjo6TWUIodAX+bPD5bvh07ZA/iqFyKG6lb8t5HMQVkYgWhgaVM1YwTwoOL0osFdwxOkNvTkICil+WwbT4OyggiZcQK
                    rHMNS58Vm8iAj5RiaZRMMMcs5RpeZ+zBCuIcgIey7BY8JwVhLGrF1mLHJBnLWS7I9t38vT6hSAIH2S8WYGuKPFmIkgblyK0kUqJU
                    zmWX/BdCAnz+Hw25L+tMFN5VrRxRJ273eLtkvWsLCfSrG5Lsmv7JdHxOSDWTeZzIRFXa1IkrxO9JMnvWRxRu7051d8N6YqYYuXck
                    paVDmqZuaUAd/LAD0SfL/RyIJuJK+5r+LxDigwjJGX5u1ta75Q4+gn90tqbgQ/B+HTfZay9lOLYuw2iTfe3YtZSRQUs9ATB5eiwd
                    aKXU5vuCW4z7YyxUaXH5Sf2mwHKgP27qx3/+rawgAv1RsqRoJZntAw4O40TwUoB03w9OEuZZhKRq16/paNwG2+mkjha9g10C45/B
                    3j/OQ/U5KmnzJxb68ohK2HBpDww8aeNOPrJ/66usUDyr515sOCMTCpS2yOr/IFoBInMRCO1rdiNKfxJobt1dHVhaeDOkQwysK7Nk
                    O2eBfESKMWMI/VwP9SStTYgjgIxr1asZvaAhrCmKryYTyqZ6c9p2zXCn0Fcd8tpCDSd3xDXs9eK1DLCr0kNHAOneyQ7YlWr5i1CX
                    F+zYHTdMD8Xe51YfUfT6lZC/3gsfcW+WaGuy6PBtLngtXuBxcfiTeU7dVIqaSR/a1KJw0zMEJYboQgxIGH1AeIv9Tt/zgMey2Vec
                    xo70lKuIsGd1Viu7UtRbcqJ4MMJuuGJaOTsqEIMfqIWyQ7GfVxjnQnr/+bxWHNeHdslgSfAKmZn9r40M/Wgq6owqtUh6bHXExJf3
                    uM8ql5Zf7XGgv82sUF6nAPC5FMvc/QGZ6AC3E6Hj465uMstLlg2YvBRDmXDnSnptVLOs5+BxWXpvp7YU0yKjU/RXFl2fME0wMTAN
                    BglghkgBZQMEAgEFAAQgU4X0BVal8vVn2swpxO5RVnFiboun+Hw9NoBpPVHyaGIEFCWWthrj1ZtRoxmcH+OQkioLEqzfAgInEA==
    """.trimIndent()

    fun buildServerContext(): SSLContext {
        val keyBytes = Base64.getDecoder().decode(KEYSTORE_B64.filterNot { it.isWhitespace() })
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(keyBytes.inputStream(), KEYSTORE_PASSWORD.toCharArray())
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray())
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(trustAll), null)
        }
    }

    fun installClientDefaults(context: SSLContext): Pair<SSLSocketFactory, HostnameVerifier> {
        val previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        return previousFactory to previousVerifier
    }

    fun restoreClientDefaults(previousFactory: SSLSocketFactory, previousVerifier: HostnameVerifier) {
        HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier)
    }
}

internal data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class MockResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
)

internal class MockHttpsServer(
    private val responder: (RecordedRequest) -> MockResponse,
) : Closeable {

    private val context: SSLContext = TestTlsSupport.buildServerContext()
    private val serverSocket: SSLServerSocket =
        (context.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            as SSLServerSocket)
    private val previousDefaults: Pair<SSLSocketFactory, HostnameVerifier> =
        TestTlsSupport.installClientDefaults(context)

    val requests: CopyOnWriteArrayList<RecordedRequest> = CopyOnWriteArrayList()
    @Volatile
    private var closed = false
    private val acceptThread: Thread = Thread({ acceptLoop() }, "b11-sse-mock-https").apply {
        isDaemon = true
        start()
    }

    fun baseUrl(path: String = "/v1"): String = "https://127.0.0.1:${serverSocket.localPort}$path"

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                break
            }
            socket.use { s ->
                s.soTimeout = 5_000
                try {
                    val request = readRequest(s.getInputStream())
                    requests += request
                    writeResponse(s.getOutputStream(), responder(request))
                } catch (e: IOException) {
                    // 客户端取消/超时：忽略写失败（测试只关心 Provider 事件）
                }
            }
        }
    }

    private fun readRequest(input: InputStream): RecordedRequest {
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: throw IOException("empty request")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        // 用 ISO-8859-1 逐字节读（与 header 同流），再按 UTF-8 还原 body：
        // 直接 String(charArray) 会把 UTF-8 中文按 Latin-1 解释成 mojibake（断言 prompt 会失败）。
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read()
            if (n < 0) break
            bodyBytes[read++] = n.toByte()
        }
        val parts = requestLine.split(" ")
        return RecordedRequest(
            method = parts.getOrNull(0).orEmpty(),
            path = parts.getOrNull(1).orEmpty(),
            headers = headers,
            body = String(bodyBytes, 0, read, Charsets.UTF_8),
        )
    }

    private fun writeResponse(out: OutputStream, response: MockResponse) {
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val extraHeaders = response.headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
        val head = "HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            extraHeaders + "\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        acceptThread.join(3_000)
        TestTlsSupport.restoreClientDefaults(previousDefaults.first, previousDefaults.second)
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        401 -> "Unauthorized"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Response"
    }
}
