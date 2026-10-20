package com.zhibox.memory.core.text

import kotlinx.coroutines.CancellationException
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * P1 审查（B14.3）：Office OOXML XML 的安全 SAX 解析与文本处理器。
 *
 * 为什么从正则改为 SAX：
 * - 正则对畸形 XML/大文档/实体处理不可靠，且无法在解析过程中中断（大文档 CPU DoS 风险）；
 * - SAX 流式处理有明确的安全开关（DTD/外部实体）、可在每个事件回调做取消检查与输出预算。
 *
 * 安全策略（纵深防御）：
 * 1. **显式拒绝 DTD/实体声明**：解析前扫描 `<!DOCTYPE` / `<!ENTITY`（大小写不敏感）——不依赖
 *    Android/JDK 解析器对某个 feature 的支持差异，杜绝 XXE/实体扩展攻击；
 * 2. SAX 工厂关闭外部通用/参数实体与外部 DTD 加载（不支持该 feature 的解析器忽略，
 *    由第 1 条兜底）；`FEATURE_SECURE_PROCESSING` 开启；
 * 3. EntityResolver 对任何外部实体返回空内容（第三层兜底）；
 * 4. SAX 以 namespaceAware 模式解析（OOXML 前缀可任意，处理器按 localName 匹配 w:t/a:t/si/c/row 等；
 *    非法未绑定前缀的畸形 XML 会作为可读解析失败，符合"畸形即失败"预期）。
 *
 * 取消/预算：handler 在 start/end 元素回调调用 [onCheck]（DocumentParser 传入协程取消检查，
 * 抛 [CancellationException] 原样传播）；文本输出超过预算抛内部 [OfficeOutputBudgetReached]，
 * 抽取层捕获后用已收集前缀文本（保持既有"截断"语义）。
 */
internal object OfficeXmlSax {

    /**
     * 解析 XML 字符串并交给 [handler]；安全/结构错误统一转可读 [DocumentParseException]。
     *
     * @param parserId 对应格式的 parserId（docx/pptx/xlsx-ooxml-v1），便于导入诊断
     */
    fun parse(xml: String, handler: DefaultHandler, parserId: String) {
        if (containsDtdOrEntityDeclaration(xml)) {
            throw DocumentParseException(
                "Office 文档 XML 包含不允许的 DTD/实体声明（安全限制），已拒绝解析：" +
                    "请用 Office/WPS 重新另存后再导入",
                parserId,
            )
        }
        val parser = try {
            newSecureParser()
        } catch (e: Exception) {
            throw DocumentParseException(
                "Office XML 解析器初始化失败：请重启应用后重试",
                parserId,
            )
        }
        try {
            parser.parse(InputSource(StringReader(xml)), handler)
        } catch (e: OfficeOutputBudgetReached) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SAXException) {
            throw DocumentParseException(
                "Office 文档 XML 结构损坏或不合法（${e.javaClass.simpleName}）：文件可能已损坏，请重新导出后重试",
                parserId,
            )
        } catch (e: java.io.IOException) {
            throw DocumentParseException(
                "Office 文档 XML 读取失败：文件可能已损坏，请重新导出后重试",
                parserId,
            )
        }
    }

    /** DTD/实体声明检测（大小写不敏感；解码后的 XML 字符串上执行）。 */
    fun containsDtdOrEntityDeclaration(xml: String): Boolean =
        xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)

    private fun newSecureParser(): SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        // 安全 feature 为 best-effort：Android Expat / 不同 JDK 支持的 feature 集合不同，
        // 单个 setFeature 失败不阻断解析（前面"显式拒绝 DTD/实体声明"是确定性兜底）。
        bestEffortFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        bestEffortFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        bestEffortFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        bestEffortFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        bestEffortFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val parser = try {
            factory.newSAXParser()
        } catch (e: ParserConfigurationException) {
            throw e
        }
        // 第三层兜底：任何外部实体解析请求返回空内容（不读本地文件/网络）
        parser.xmlReader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
        return parser
    }

    private fun bestEffortFeature(factory: SAXParserFactory, feature: String, value: Boolean) {
        runCatching { factory.setFeature(feature, value) }
    }
}

/** SAX 元素名（命名空间处理关闭时 localName 可能为空，用 qName 去掉前缀兜底）。 */
internal fun saxElementName(localName: String?, qName: String?): String =
    localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfterLast(':')

/** 输出预算触顶（内部信号；抽取层捕获后返回已收集的前缀文本）。 */
internal class OfficeOutputBudgetReached : RuntimeException("office output budget reached")

/**
 * 带预算/取消的 SAX handler 基类：文本追加超过 [maxChars] 抛 [OfficeOutputBudgetReached]；
 * 每 start/end 元素调用 [onCheck]（取消检查，抛 [CancellationException] 原样传播）。
 */
internal abstract class OfficeBudgetedSaxHandler(
    protected val maxChars: Int,
    protected val onCheck: () -> Unit,
) : DefaultHandler() {

    protected val output = StringBuilder()

    fun result(): String = output.toString()

    protected fun appendToken(token: String) {
        appendChars(token)
    }

    protected fun appendChars(chars: CharArray, start: Int = 0, length: Int = chars.size) {
        if (maxChars <= 0) throw OfficeOutputBudgetReached()
        val remaining = maxChars - output.length
        if (remaining <= 0) throw OfficeOutputBudgetReached()
        val take = minOf(remaining, length)
        output.append(chars, start, take)
        if (output.length >= maxChars) throw OfficeOutputBudgetReached()
    }

    protected fun appendChars(token: String) = appendChars(token.toCharArray())
}

/** DOCX 文本处理器：w:t 文本；</w:p>/</w:tr> 换行、</w:tc> Tab、w:tab Tab、w:br/w:cr 换行。 */
internal class DocxSaxHandler(maxChars: Int, onCheck: () -> Unit) :
    OfficeBudgetedSaxHandler(maxChars, onCheck) {

    private var inText = false

    override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "t" -> inText = true
            "tab" -> appendToken("\t")
            "br", "cr" -> appendToken("\n")
        }
    }

    override fun endElement(uri: String?, localName: String, qName: String?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "t" -> inText = false
            "p", "tr" -> appendToken("\n")
            "tc" -> appendToken("\t")
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText) appendChars(ch, start, length)
    }
}

/** PPTX 单页处理器：a:t 文本；</a:p>/a:br 换行。 */
internal class PptxSaxHandler(maxChars: Int, onCheck: () -> Unit) :
    OfficeBudgetedSaxHandler(maxChars, onCheck) {

    private var inText = false

    override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "t" -> inText = true
            "br" -> appendToken("\n")
        }
    }

    override fun endElement(uri: String?, localName: String, qName: String?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "t" -> inText = false
            "p" -> appendToken("\n")
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText) appendChars(ch, start, length)
    }
}

/**
 * XLSX sharedStrings 处理器：每个 `<si>` 一个共享字符串；`<si>` 内多个 `<t>`（富文本 run）拼接。
 * 预算为累计共享字符串字符数（防超大 sharedStrings 占内存）。
 */
internal class SharedStringsSaxHandler(
    private val maxChars: Int,
    private val onCheck: () -> Unit,
) : DefaultHandler() {

    val strings: MutableList<String> = ArrayList()

    private val current = StringBuilder()
    private var inSi = false
    private var inText = false
    private var consumedChars = 0

    override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "si" -> {
                inSi = true
                current.setLength(0)
            }
            "t" -> if (inSi) inText = true
        }
    }

    override fun endElement(uri: String?, localName: String, qName: String?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "t" -> inText = false
            "si" -> if (inSi) {
                inSi = false
                if (consumedChars + current.length > maxChars) throw OfficeOutputBudgetReached()
                strings.add(current.toString())
                consumedChars += current.length
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inSi && inText) {
            current.append(ch, start, length)
            if (consumedChars + current.length > maxChars) throw OfficeOutputBudgetReached()
        }
    }
}

/**
 * XLSX worksheet 处理器：
 * - `<c t="s">` → sharedStrings 索引；`t="inlineStr"` → `<is><t>` 文本；其余取 `<v>` 数值/文本；
 * - 单元格间 Tab、行间换行；空/纯空白单元格丢弃（与旧正则行为一致）；忽略公式 `<f>`。
 */
internal class SheetSaxHandler(
    private val sharedStrings: List<String>,
    maxChars: Int,
    onCheck: () -> Unit,
) : OfficeBudgetedSaxHandler(maxChars, onCheck) {

    private val cells = ArrayList<String>()
    private val valueText = StringBuilder()
    private val inlineText = StringBuilder()
    private var inValue = false
    private var inInline = false
    private var inInlineText = false
    private var cellType = ""

    override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "c" -> {
                cellType = attributes?.getValue("t").orEmpty()
                valueText.setLength(0)
                inlineText.setLength(0)
                inValue = false
                inInline = false
                inInlineText = false
            }
            "v" -> inValue = true
            "is" -> inInline = true
            "t" -> if (inInline) inInlineText = true
        }
    }

    override fun endElement(uri: String?, localName: String, qName: String?) {
        onCheck()
        when (saxElementName(localName, qName)) {
            "t" -> inInlineText = false
            "is" -> inInline = false
            "v" -> inValue = false
            "c" -> {
                val text = when (cellType) {
                    "s" -> valueText.toString().trim().toIntOrNull()
                        ?.let { sharedStrings.getOrNull(it) }
                        .orEmpty()
                    "inlineStr" -> inlineText.toString()
                    else -> valueText.toString().trim()
                }
                if (text.isNotBlank()) cells.add(text)
            }
            "row" -> {
                if (cells.isNotEmpty()) {
                    if (output.isNotEmpty()) appendToken("\n")
                    appendToken(cells.joinToString("\t"))
                    cells.clear()
                }
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        when {
            inValue -> valueText.append(ch, start, length)
            inInlineText -> inlineText.append(ch, start, length)
        }
    }
}
