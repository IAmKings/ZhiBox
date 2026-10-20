# B14.3 报告：Office SAX 流式解析与取消

- **日期**：2026-10-20
- **状态**：**完成**（正则替换为 SAX、XXE/DOCTYPE 拒绝、取消/预算、设备 E2E）
- **目标**：消除 Office XML `.*?` 正则的超线性 CPU/可被畸形输入拖死风险，并让取消在 Office 解析中生效。

## 1. 实现

- 新增 `core/text/OfficeXmlSax.kt`：
  - `SAXParserFactory` + `DefaultHandler`；namespaceAware=true；
  - 安全 feature best-effort（disallow-doctype-decl、external-general/parameter-entities=false、load-external-dtd=false、secure-processing）+ `EntityResolver` 返回空；
  - 解析前显式拒绝 `<!DOCTYPE`/`<!ENTITY`（不依赖 feature 支持，确定性兜底）。
  - 4 个 handler：DOCX / PPTX / SharedStrings / Sheet。
- `OfficeTextExtractor` 全部 `.*?` 正则替换为 SAX；保留 zip 条目/解压/字符预算；新增 `onCheck` 在 SAX 每个 start/end 与 zip 8KB 读取调用；超 MAX_TEXT_CHARS 抛可读失败（不静默截断）。
- `DocumentParser.parse(context,file)` Office 分支注入协程取消检查（旧 InputStream 入口 no-op 兼容）。
- 文本语义保持：DOCX 段落/tab/br/单元格/行；PPTX slide 数字序；XLSX sharedStrings/s/inlineStr/数值，忽略公式。

## 2. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **617 / 0**、**598 / 0** |
| lint | 0 errors / 47 warnings |
| core.text | 12 类 **95 tests / 0 failures**；含 `OfficeSaxSecurityTest` 12（XXE/DOCTYPE/畸形/未闭合 2000 标签/取消/3M 字符预算） |
| K60U Office E2E | **3/3 ready**：真实 DOCX/PPTX/XLSX 经 SAX 解析 → chunk/embedding；内容核验通过（证据 `B14.3-office-sax-k60u.txt`） |
| 旧测试 | OfficeTextExtractorTest 14 全绿；PPTX fixture 仅补 xmlns:p（namespace-aware 必需） |

## 3. 行为变化/限制

- **Office 超 200 万字符由“截断成功”改为“可读失败”**：按 B14.3 契约，避免静默丢内容/OOM；txt/md/html 仍截断。UI 显示 `DOCUMENT_PARSE` 可读错误。
- namespaceAware=true 后，未绑定命名空间前缀的畸形 OOXML 会失败；符合“畸形即失败”。
- SAX feature 在个别 OEM 可能不支持；已由显式 DOCTYPE/ENTITY 拒绝兜底（OOXML 规范无 DTD）。
- 真机 E2E 为调试包 + 本机模型；release R8/模型签名 smoke 仍待发布批次。
- 未改 UI/DB/gradle/docs；B14.4/OCR/用户密码 PDF 等按计划。

## 4. 文件

- `core/text/OfficeXmlSax.kt`（新增）
- `core/text/OfficeTextExtractor.kt`（重写）
- `core/text/DocumentParser.kt`（Office 取消注入）
- 测试：`OfficeSaxSecurityTest.kt`、`OfficeTextExtractorTest.kt`、`DocumentParserDispatchTest.kt`
