package com.zhibox.memory.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简单首页 / 二级页的「术语黑名单」门禁（产品文档 §6 验收第 1 条）。
 *
 * 为什么是文件级测试：仓库没有截图/无障碍自动门禁，`scripts/check_docs_consistency.sh`
 * 也不检查设置页文案；把黑名单固化成 JVM 单测后，CI 的 `testDebugUnitTest` 即可阻断回归。
 *
 * 只扫描"普通用户首屏与二级页"使用的 key 前缀：
 * - `settings_home_*`：简单首页 4 卡 + 高级入口；
 * - `settings_page_*`：二级页标题；
 * - `settings_tier_*`：模型档位标签；
 * - `settings_hero_*`：二级页结论卡；
 * - 以及若干 `_simple` / `_only` 的简化展示文案。
 * 高级与诊断页保留完整技术文案，不在本门禁范围内。
 */
class SettingsCopyAuditTest {

    private val blacklist = listOf(
        "嵌入",
        "向量",
        "分块",
        "重嵌入",
        "FTS",
        "SAG",
        "GPU",
        "LLM",
        "并发",
        "孤儿",
        "queryHash",
        "litertlm",
        "B3-14",
        "错误码",
    )

    private val scannedPrefixes = listOf(
        "settings_home_",
        "settings_page_",
        "settings_tier_",
        "settings_hero_",
    )

    private val scannedKeys = setOf(
        "settings_model_size_only",
        "settings_spaces_count_simple",
        "settings_shared_count_simple",
        "settings_legacy_spaces_simple",
    )

    @Test
    fun simpleModeCopyContainsNoImplementationJargon() {
        val strings = parseStrings(locateStringsXml())
        val scanned = strings.filter { (key, _) ->
            scannedPrefixes.any { key.startsWith(it) } || key in scannedKeys
        }
        assertTrue("未扫描到任何简单模式文案，key 前缀可能改名", scanned.size >= 20)

        val violations = scanned.mapNotNull { (key, value) ->
            val hit = blacklist.filter { value.contains(it) }
            if (hit.isEmpty()) null else "$key = $value（命中：${hit.joinToString("、")}）"
        }
        assertTrue(
            "简单模式文案出现实现术语：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun simpleModeCopyKeysExist() {
        val strings = parseStrings(locateStringsXml()).keys
        listOf(
            "settings_home_ai_title",
            "settings_home_library_title",
            "settings_home_network_title",
            "settings_home_privacy_title",
            "settings_home_advanced_title",
            "settings_page_models",
            "settings_page_library",
            "settings_page_network",
            "settings_page_privacy",
            "settings_page_advanced",
            "settings_tier_270",
            "settings_tier_440",
            "settings_tier_740",
        ).forEach { key ->
            assertTrue("缺少文案 key: $key", key in strings)
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private fun parseStrings(file: File): Map<String, String> {
        val text = file.readText()
        val regex = Regex(
            pattern = """<string\s+name="([^"]+)"[^>]*>(.*?)</string>""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        return regex.findAll(text).associate { match ->
            val key = match.groupValues[1]
            // 去掉转义换行/占位符后再匹配术语（占位符不承载文案）。
            val value = match.groupValues[2]
                .replace("\\n", " ")
                .replace(Regex("""%[0-9]+\$[sdf]"""), " ")
                .replace(Regex("""%[sd]"""), " ")
            key to value
        }
    }

    /**
     * 单元测试工作目录因 Gradle/IDE 不同可能是仓库根或 `app/`；向上查找直到命中
     * `app/src/main/res/values/strings.xml`。
     */
    private fun locateStringsXml(): File {
        val relative = "app/src/main/res/values/strings.xml"
        File(System.getProperty("user.dir")).absoluteFile.let { start ->
            var dir: File? = start
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { return it }
                dir = dir.parentFile
            }
        }
        val direct = listOf(
            File("src/main/res/values/strings.xml"),
            File("../app/src/main/res/values/strings.xml"),
            File(relative),
        ).firstOrNull { it.isFile }
        return requireNotNull(direct) {
            "无法定位 strings.xml（user.dir=${System.getProperty("user.dir")}）"
        }
    }
}
