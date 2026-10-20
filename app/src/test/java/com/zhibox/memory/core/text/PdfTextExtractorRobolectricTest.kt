package com.zhibox.memory.core.text

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * P1 PDF 文本抽取（Robolectric + PDFBox-Android，本地 JVM，无真机/无网络）：
 * 最小文本 PDF 抽取、DocumentParser 分派、损坏/无文本/文件/页数/字符超限、取消传播。
 *
 * 说明：PDFBox-Android 需要 Android Context 做资源初始化，故用 Robolectric。
 * P1.1 起：user-password 可带密码重试；扫描件文本层为空时 OCR fallback（JVM 用 fake 注入，
 * 真实 ML Kit 由 androidTest 覆盖；不联网）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfTextExtractorRobolectricTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun initPdfBoxResources() {
        // PDFBox-Android 从 assets 加载 fontbox 资源（AFM/cmap）；owner-only 加密 fixture 的
        // 生成与抽取都依赖同一初始化（生产由 PdfTextExtractor 幂等初始化）。
        PDFBoxResourceLoader.init(app)
    }

    @Test
    fun `extracts text from minimal text pdf`() = runBlocking {
        val text = PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Hello PDF"))))
        assertTrue("应抽取到文本：$text", text.contains("Hello PDF"))
    }

    @Test
    fun `document parser dispatches pdf to extractor`() = runBlocking {
        val file = pdfFile(buildPdf(listOf("Dispatch PDF")))
        val parsed = DocumentParser.parse(app, file, null, file.name)
        assertEquals(PdfTextExtractor.PARSER_ID, parsed.parserId)
        assertTrue(parsed.text.contains("Dispatch PDF"))
    }

    @Test
    fun `corrupt pdf fails with readable code`() {
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile("not a pdf at all".toByteArray())) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.CORRUPT, error.errorCode)
        assertTrue("错误应可读：${error.message}", error.message!!.contains("损坏"))
    }

    @Test
    fun `textless pdf fails no text`() {
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("")))) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.NO_TEXT, error.errorCode)
        assertTrue(error.message!!.contains("扫描件"))
    }

    @Test
    fun `file size limit enforced`() {
        val limits = PdfTextExtractor.Limits(maxFileBytes = 10)
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Hello"))), limits) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.TOO_LARGE, error.errorCode)
    }

    @Test
    fun `page limit enforced`() {
        val limits = PdfTextExtractor.Limits(maxPages = 1)
        val error = runCatching {
            runBlocking {
                PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Page one", "Page two"))), limits)
            }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.TOO_MANY_PAGES, error.errorCode)
    }

    @Test
    fun `text char limit enforced`() {
        val limits = PdfTextExtractor.Limits(maxTextChars = 5)
        val error = runCatching {
            runBlocking { PdfTextExtractor.extract(app, pdfFile(buildPdf(listOf("Hello PDF"))), limits) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.TEXT_TOO_LONG, error.errorCode)
    }

    @Test
    fun `cancellation is propagated`() {
        val file = pdfFile(buildPdf(listOf("Hello PDF")))
        val cancelledParent = Job().apply { cancel() }
        val error = runCatching {
            runBlocking {
                withContext(cancelledParent) { PdfTextExtractor.extract(app, file) }
            }
        }.exceptionOrNull()
        assertTrue("应为 CancellationException：$error", error is CancellationException)
    }

    // ---- 加密 PDF（B14.1：owner-password-only 支持；user password 仍拒绝） ----

    @Test
    fun `owner password only pdf extracts when extraction is allowed`() = runBlocking {
        val text = PdfTextExtractor.extract(
            app,
            pdfFile(encryptedPdf(userPassword = "", ownerPassword = "owner-secret", allowExtract = true)),
        )
        assertTrue("owner-only 允许抽取时应成功：$text", text.contains("Secret PDF"))
    }

    @Test
    fun `user password pdf fails encrypted`() {
        val error = runCatching {
            runBlocking {
                PdfTextExtractor.extract(
                    app,
                    pdfFile(encryptedPdf(userPassword = "user-secret", ownerPassword = "owner-secret")),
                )
            }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.ENCRYPTED, error.errorCode)
        assertTrue("应提示需要打开密码：${error.message}", error.message!!.contains("打开密码"))
    }

    @Test
    fun `owner password with extraction disabled fails encrypted`() {
        val error = runCatching {
            runBlocking {
                PdfTextExtractor.extract(
                    app,
                    pdfFile(encryptedPdf(userPassword = "", ownerPassword = "owner-secret", allowExtract = false)),
                )
            }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.ENCRYPTED, error.errorCode)
        assertTrue("应提示权限限制：${error.message}", error.message!!.contains("权限"))
    }

    @Test
    fun `document parser dispatches owner password only pdf`() = runBlocking {
        val file = pdfFile(encryptedPdf(userPassword = "", ownerPassword = "owner-secret", allowExtract = true))
        val parsed = DocumentParser.parse(app, file, null, file.name)
        assertEquals(PdfTextExtractor.PARSER_ID, parsed.parserId)
        assertTrue(parsed.text.contains("Secret PDF"))
    }

    // ---- P1.1：user password 重试（带密码成功/错误密码仍 PDF_ENCRYPTED） ----

    @Test
    fun `user password pdf extracts when password provided`() = runBlocking {
        val file = pdfFile(encryptedPdf(userPassword = "user-secret", ownerPassword = "owner-secret"))
        val result = PdfTextExtractor.extractWithMetadata(app, file, "user-secret".toCharArray())
        assertEquals(PdfTextExtractor.PARSER_ID, result.parserId)
        assertTrue("带正确密码应抽取成功：${result.text}", result.text.contains("Secret PDF"))
    }

    @Test
    fun `legacy extract with password still works`() = runBlocking {
        val file = pdfFile(encryptedPdf(userPassword = "user-secret", ownerPassword = "owner-secret"))
        val text = PdfTextExtractor.extract(app, file, "user-secret".toCharArray())
        assertTrue(text.contains("Secret PDF"))
    }

    @Test
    fun `wrong user password fails encrypted with retry hint`() {
        val file = pdfFile(encryptedPdf(userPassword = "user-secret", ownerPassword = "owner-secret"))
        val error = runCatching {
            runBlocking { PdfTextExtractor.extractWithMetadata(app, file, "wrong-pass".toCharArray()) }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.ENCRYPTED, error.errorCode)
        assertTrue("应提示密码不正确：${error.message}", error.message!!.contains("不正确"))
    }

    @Test
    fun `document parser accepts password for encrypted pdf`() = runBlocking {
        val file = pdfFile(encryptedPdf(userPassword = "user-secret", ownerPassword = "owner-secret"))
        val parsed = DocumentParser.parse(app, file, null, file.name, "user-secret".toCharArray())
        assertEquals(PdfTextExtractor.PARSER_ID, parsed.parserId)
        assertTrue(parsed.text.contains("Secret PDF"))
    }

    @Test
    fun `extractWithMetadata returns text layer parser id for normal pdf`() = runBlocking {
        val result = PdfTextExtractor.extractWithMetadata(app, pdfFile(buildPdf(listOf("Hello PDF"))))
        assertEquals(PdfTextExtractor.PARSER_ID, result.parserId)
        assertTrue(result.text.contains("Hello PDF"))
    }

    // ---- P1.1b：逐页混合 OCR（JVM fake；真实 ML Kit 见 androidTest） ----

    @Test
    fun `textless pdf falls back to ocr when enabled`() = runBlocking {
        val requested = mutableListOf<List<Int>>()
        val fakeOcr = PdfPageOcr { _, _, indices ->
            requested += indices.toList()
            indices.associateWith { "OCR 识别文本" }
        }
        val result = PdfTextExtractor.extractWithMetadata(
            context = app,
            file = pdfFile(buildPdf(listOf(""))),
            password = null,
            pageOcr = fakeOcr,
            ocrEnabledOverride = true,
        )
        assertEquals(PdfTextExtractor.OCR_PARSER_ID, result.parserId)
        assertTrue("应使用 OCR 文本：${result.text}", result.text.contains("OCR 识别文本"))
        assertEquals("纯扫描页应请求 OCR 全部空页", listOf(listOf(0)), requested)
    }

    @Test
    fun `mixed pdf only ocrs empty pages and fixes parserId`() = runBlocking {
        val requested = mutableListOf<Int>()
        val fakeOcr = PdfPageOcr { _, _, indices ->
            requested += indices.toList()
            indices.associateWith { "OCR-第${it + 1}页" }
        }
        val file = pdfFile(buildPdf(listOf("Page one", "", "Page three")))
        val result = PdfTextExtractor.extractWithMetadata(
            context = app,
            file = file,
            password = null,
            pageOcr = fakeOcr,
            ocrEnabledOverride = true,
        )
        assertEquals("混合 PDF 应标记 pdf-ocr-v1", PdfTextExtractor.OCR_PARSER_ID, result.parserId)
        assertTrue(result.text.contains("Page one"))
        assertTrue(result.text.contains("Page three"))
        assertTrue("应合并 OCR 页文本：${result.text}", result.text.contains("OCR-第2页"))
        assertEquals("只应对文本层为空的第 2 页 OCR", listOf(1), requested)
        assertTrue("有文本页不应重复 OCR", result.text.indexOf("Page one") < result.text.indexOf("OCR-第2页"))
    }

    @Test
    fun `25 plus pages all ocr-ed without page cap`() = runBlocking {
        val pageCount = 26
        val requested = mutableListOf<Int>()
        val fakeOcr = PdfPageOcr { _, _, indices ->
            requested += indices.toList()
            indices.associateWith { "SCAN-${it + 1}" }
        }
        val file = pdfFile(buildPdf(List(pageCount) { "" }))
        val result = PdfTextExtractor.extractWithMetadata(
            context = app,
            file = file,
            password = null,
            pageOcr = fakeOcr,
            ocrEnabledOverride = true,
        )
        assertEquals(PdfTextExtractor.OCR_PARSER_ID, result.parserId)
        assertEquals("应 OCR 全部 26 页（无 20 页截断）", pageCount, requested.size)
        assertTrue(result.text.contains("SCAN-1"))
        assertTrue(result.text.contains("SCAN-26"))
    }

    @Test
    fun `textless pdf with ocr disabled fails no text without calling ocr`() = runBlocking {
        var ocrCalls = 0
        val fakeOcr = PdfPageOcr { _, _, indices ->
            ocrCalls += indices.size
            indices.associateWith { "不应调用" }
        }
        val error = runCatching {
            PdfTextExtractor.extractWithMetadata(
                context = app,
                file = pdfFile(buildPdf(listOf(""))),
                password = null,
                pageOcr = fakeOcr,
                ocrEnabledOverride = false,
            )
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfExtractionErrorCodes.NO_TEXT, error.errorCode)
        assertEquals("OCR 关闭时不得调用 OCR", 0, ocrCalls)
    }

    @Test
    fun `mixed pdf with ocr disabled keeps text layer pages`() = runBlocking {
        var ocrCalls = 0
        val fakeOcr = PdfPageOcr { _, _, indices ->
            ocrCalls += indices.size
            emptyMap()
        }
        val result = PdfTextExtractor.extractWithMetadata(
            context = app,
            file = pdfFile(buildPdf(listOf("Page one", "", "Page three"))),
            password = null,
            pageOcr = fakeOcr,
            ocrEnabledOverride = false,
        )
        assertEquals("未走 OCR 应保持 pdf-text-v1", PdfTextExtractor.PARSER_ID, result.parserId)
        assertTrue(result.text.contains("Page one"))
        assertTrue(result.text.contains("Page three"))
        assertEquals(0, ocrCalls)
    }

    @Test
    fun `ocr failure is propagated readable`() {
        val fakeOcr = PdfPageOcr { _, _, _ ->
            throw DocumentParseException(
                "OCR 失败（测试注入）",
                PdfOcrExtractor.PARSER_ID,
                PdfOcrErrorCodes.FAILED,
            )
        }
        val error = runCatching {
            runBlocking {
                PdfTextExtractor.extractWithMetadata(
                    context = app,
                    file = pdfFile(buildPdf(listOf(""))),
                    password = null,
                    pageOcr = fakeOcr,
                    ocrEnabledOverride = true,
                )
            }
        }.exceptionOrNull() as DocumentParseException
        assertEquals(PdfOcrErrorCodes.FAILED, error.errorCode)
        assertTrue(error.message!!.contains("OCR"))
    }

    @Test
    fun `text layer success does not call ocr fallback`() = runBlocking {
        var ocrCalls = 0
        val fakeOcr = PdfPageOcr { _, _, indices ->
            ocrCalls += indices.size
            emptyMap()
        }
        val result = PdfTextExtractor.extractWithMetadata(
            context = app,
            file = pdfFile(buildPdf(listOf("Hello PDF"))),
            password = null,
            pageOcr = fakeOcr,
            ocrEnabledOverride = true,
        )
        assertEquals(PdfTextExtractor.PARSER_ID, result.parserId)
        assertEquals("文本层成功时不得 OCR", 0, ocrCalls)
    }

    // ---- fixtures ----

    private fun pdfFile(bytes: ByteArray): File =
        File(app.cacheDir, "p1-pdf-${UUID.randomUUID()}.pdf").apply { writeBytes(bytes) }

    /**
     * 用 PDFBox 生成加密 PDF fixture（基于已通过抽取测试的最小数 PDF 再 protect/save）：
     * - [userPassword] 为空 = owner-password-only（可打开，权限由 [allowExtract] 决定）；
     * - [userPassword] 非空 = 需要打开密码（本版本拒绝，PDF_ENCRYPTED）。
     * 仅测试用；不写入正式代码/不记录密码。
     */
    private fun encryptedPdf(
        userPassword: String,
        ownerPassword: String,
        allowExtract: Boolean = true,
    ): ByteArray {
        val permission = AccessPermission().apply { setCanExtractContent(allowExtract) }
        val policy = StandardProtectionPolicy(ownerPassword, userPassword, permission).apply {
            setEncryptionKeyLength(128)
        }
        PDDocument.load(buildPdf(listOf("Secret PDF")).inputStream()).use { document ->
            document.protect(policy)
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    /** 构造最小合法 PDF（Helvetica 文本页；content="" 生成无文本页）。 */
    private fun buildPdf(pageTexts: List<String>): ByteArray {
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>" // object 1
        objects += "" // object 2: pages（占位，稍后回填）
        val fontObjectNumber = 3 + pageTexts.size * 2
        val pageRefs = ArrayList<Int>()
        pageTexts.forEachIndexed { index, pageText ->
            val pageObjectNumber = 3 + index * 2
            val contentObjectNumber = pageObjectNumber + 1
            pageRefs += pageObjectNumber
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Contents $contentObjectNumber 0 R " +
                "/Resources << /Font << /F1 $fontObjectNumber 0 R >> >> >>"
            val content = if (pageText.isEmpty()) {
                ""
            } else {
                "BT /F1 12 Tf 72 720 Td (${escapePdfString(pageText)}) Tj ET"
            }
            val contentLength = content.toByteArray(Charsets.ISO_8859_1).size
            objects += "<< /Length $contentLength >>\nstream\n$content\nendstream"
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        objects[1] = "<< /Type /Pages /Kids [" +
            pageRefs.joinToString(" ") { "$it 0 R" } + "] /Count ${pageTexts.size} >>"

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>(objects.size)
        objects.forEachIndexed { index, body ->
            offsets += out.length
            out.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xrefStart = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { offset -> out.append(String.format("%010d 00000 n \n", offset)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        out.append("startxref\n$xrefStart\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun escapePdfString(raw: String): String =
        raw.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}
