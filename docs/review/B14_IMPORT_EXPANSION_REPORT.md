# B14 P1 导入扩展首版报告（PDF/Office/网页）

- **日期**：2026-10-20
- **状态**：**P1 导入扩展首版完成**（master v0.1.2 开发中；v0.1.1 已发布/tag，不含本批）
- **范围**：TXT/MD 之外的 PDF（文本型）、DOCX/PPTX/XLSX、HTML/网页 URL；SAF 多选文件进入 staging；不做 OCR/扫描件、不做旧 `.doc/.ppt/.xls` 二进制格式。

## 1. 交付

### 1.1 解析（core/text）
- `DocumentParser`：扩展名/MIME 分派 txt/md/docx/pptx/xlsx/html/htm/pdf；`parse(context, file)` suspend 入口（PDFBox 初始化/逐页取消），旧 InputStream 入口兼容。
- `PdfTextExtractor`：PDFBox-Android 2.0.27.0 `PDFTextStripper` 逐页抽取；50MB/500 页/200 万字符预算；加密/扫描件/损坏可读失败；逐页取消；`PDFBoxResourceLoader.init` 幂等。
- `OfficeTextExtractor`：OOXML 纯 JVM 抽取（DOCX 段落/实体/tab、PPTX 按 slide 数字序、XLSX sharedStrings/inline/数值）；zip 条目数/单条目/总解压预算/路径穿越防护；非匹配条目也计预算。
- `HtmlTextExtractor`/`WebPageFetcher`：去 script/style、块级换行、实体解码；网页 HTTPS-only、禁网零请求、不跟重定向、2MB 双上限、15s 连接/读取超时、标题+正文；Content-Type 仅 HTML/XHTML/纯文本；拒绝 localhost/私网/非 443（基础 SSRF 防护）。
- 导入入口：PDF/Office/HTML 解析经 `IngestRepository.processDocument`；staging 白名单扩展；单文件入口复用白名单。

### 1.2 UI
- Import 屏新增「选择文件」：SAF `OpenMultipleDocuments`（`*/*`，按扩展名白名单后置过滤）；URI → `import-staging`（IO、隐藏文件/路径穿越/重名/50MB 流式上限、先写隐藏 `.part` 再 rename）。
- 「网页 URL 导入」：HTTPS/网络开关前置校验，core 抓取 → staging `.txt`（标题+正文 provenance）；与「全部导入」互斥，避免半写文件入库。

## 2. 安全/预算修复（审查后）

- Office 所有非目录条目按条目/总量预算读取，防止 zip-bomb/跳过未匹配条目仍解压；Office 源文件 32MB 上限；DOCX `<w:tab/>` 分派顺序修复。
- `DocumentParseException.errorCode` 接入 `PipelineError.DOCUMENT_PARSE`：加密/扫描件/损坏/超限确定性失败**不再自动重试 3 次**（仅 PDF 初始化瞬时失败可重试）。
- UI 暂存与导入互斥；流式字节上限；原子暂存；URL 抓取与暂存互斥。
- Web Content-Type 白名单/私网拦截；PDF `LinkageError`（JPX 可选解码器缺失）转为可读失败，避免 release 进程崩溃。
- R8：`-dontwarn com.gemalto.jp2.**`（文本抽取不解码 JP2 图片）；依赖 strict 校验含 PDFBox/BouncyCastle；第三方 notices 已补。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug | **573 / 0 failed** |
| JVM release | **554 / 0 failed** |
| Android Lint | **0 errors / 47 warnings** |
| `core.text` 测试 | Office 14、HTML 6、WebFetcher 11、PDF Robolectric 8、DocumentParser 分派等全绿 |
| K60U 非 UI instrumentation | **31/31** |
| K60U PDF Android runtime instrumented | **2/2**（真实 PDFBox AAR 资源 + DocumentParser Context/File + 损坏 PDF 可读错误） |
| K60U 真机导入 E2E（debug autostart staging） | **5/5 文档 ready**：PDF/DOCX/PPTX/XLSX/HTML 各 1；job done 5、chunk 5、SAG event 5；内容与 parserId 已核验（`evidence/B14-import-k60u-e2e.txt`、`B14-import-k60u.xml`） |
| release 构建 | **signed RC**：`dist/zhibox-0.1.2-b887d11-release-signed.apk`（55,479,692 bytes，SHA-256 `3647df0f7a5d2ca2d3007b6710d3ca82c8dfeafe557a0396e87db669d77ae966`；apksigner PASS）；v0.1.1 49.39MB → v0.1.2 约 55.48MB（PDFBox assets/BC 增量约 +6MB） |

## 4. 限制/未做

- **扫描件/图片 PDF 不做 OCR**：返回 `PDF_NO_TEXT` 可读失败；加密/密码保护返回 `PDF_ENCRYPTED`；owner-password 文本可读但本版本一律拒绝。
- Office 使用正则抽 OOXML 文本，复杂/畸形 XML 有 CPU 风险（已有大小/条目预算）；旧 `.doc/.ppt/.xls` 二进制不支持；图表/批注/页眉页脚不全量抽取。
- URL 导入不保留原始 URL 到 source/document（仅标题入正文/文件名）；不做 URL 去重/网页信源管理；网页仅 HTML/纯文本，不做 JS 渲染。
- HTML/Office 输出超 200 万字符截断（非静默日志，按预算）。
- **K60U 已通过 staging→导入→解析→chunk/embedding→ready→SAG 抽取真机 E2E**（autostart debug 入口）；但 **SAF 文件选择器/网页 URL 点击**未测：K60U HyperOS 仍拒绝 `INJECT_EVENTS`，K40 锁屏（图案密码）；UI 编译/单测已覆盖，待输入权限窗口补。
- **签名 RC 已生成但未 tag/发布**；K60U/K40 当前为 debug 包，PJZ110 离线，因此 signed RC 的 SAF/网页真机安装 smoke 仍未执行。

## 5. 限制处理方案

逐项处理方案/验收/触发条件见 **[B14_LIMITATIONS_PLAN.md](B14_LIMITATIONS_PLAN.md)**；不把限制静默标成完成。

## 6. 后续

1. 可用设备上做 SAF 多选 + PDF/DOCX/PPTX/XLSX/HTML 导入 + 网页 URL 的真实 smoke。
2. 评估 PDFBox OCR/加密策略、URL provenance/去重、Office 流式解析与取消。
3. 按 P1 backlog 继续 Explore/3D、多轮 Agent/工具、实体别名/抽取质量。
