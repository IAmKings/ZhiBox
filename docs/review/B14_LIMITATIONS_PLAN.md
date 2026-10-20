# B14 导入限制处理方案（避免技术债）

- **日期**：2026-10-20
- **对应**：[B14_IMPORT_EXPANSION_REPORT.md](B14_IMPORT_EXPANSION_REPORT.md)
- **原则**：可实现的现在做；暂不实现的必须有明确错误码/触发条件/验收，不静默降级、不用临时 hack 掩盖。

## 1. 逐项处理

### 1.1 扫描件 / 图片 PDF 无 OCR
- **现状**：`PdfTextExtractor` 返回 `PDF_NO_TEXT`（可读、不重试）；不伪造“空成功”。
- **方案**：新增可选 `PdfOcrFallback`（P1.1）：先用 PDF 文本层；空页才用 `PdfRenderer` 栅格化 + 端侧 OCR（ML Kit Text Recognition v2 或等价离线模型），逐页预算/取消；OCR 结果标记 `ocr=true` 写入 parserId/诊断；默认关闭，用户显式选择“尝试 OCR”。
- **验收**：扫描 fixture（>0 页、无文本层）可抽正文；失败明确 `PDF_OCR_UNAVAILABLE/PDF_OCR_FAILED`；OCR 不引入网络（模型包离线校验）。
- **触发/归属**：P1.1（用户确认需要扫描件时）；不在 B14 静默支持。

### 1.2 加密 PDF 无解密
- **现状**：所有 `isEncrypted` 一律 `PDF_ENCRYPTED`；owner-password-only 文本 PDF 本可读。
- **方案（本批后续/先做）**：`PDDocument.load(file, "")` + `getCurrentAccessPermission().canExtractContent()`；仅当需要 user password 时拒绝；密码永不落库/日志/备份。若后续支持用户输入密码：导入时弹出临时密码对话框，仅内存使用，不持久化；失败可重试，不影响整批。
- **验收**：owner-password-only PDF 可抽取；user-password PDF 返回可读 `PDF_ENCRYPTED`；不产生持久密码字段。
- **状态**：**owner-password-only 已支持（B14.1）**；用户密码输入列 P1.1。

### 1.3 旧 `.doc/.ppt/.xls` 不支持
- **现状**：MIME 可能被误映射为 OOXML，最终报“压缩包损坏”，提示误导。
- **方案（本批小修）**：按 OLE 魔数 `D0 CF 11 E0` / MIME `application/msword`/`vnd.ms-powerpoint`/`vnd.ms-excel` 识别，返回 `OFFICE_LEGACY_UNSUPPORTED` + 明确“请另存为 .docx/.pptx/.xlsx”；不尝试伪解析。
- **可选**：Apache POI scratchpad（HWPF/HSLF/HSSF）在明确需求时引入（体积/兼容需评估）；不引入前不宣称支持。
- **验收**：旧格式稳定错误、批量导入不中断；UI 文案给出另存指引。

### 1.4 URL 原始地址未写入 source/document
- **方案（建议下一批做）**：schema **v8** 增加 `source.origin_url`（nullable）与索引；staging 旁车 JSON 保存 URL；`ImportViewModel.importUrl` 传入 `IngestRepository.importFile(originUrl=…)`；备份 v3 包含该字段；Sources/证据页显示可点击来源 URL。去重键：规范化 URL（scheme/host 小写、去 fragment、限长）。
- **验收**：网页导入后 source.origin_url 可查、备份回环保留、重复 URL 不重复建 source；不把 URL 塞进 title/uri 的临时方案。
- **状态**：**B14.2 已完成**（schema v8 `source.originUrl` + 迁移/备份/staging 旁车/Sources UI + K60U 真机 E2E；见 `B14_2_ORIGIN_URL_REPORT.md`）。

### 1.5 Office 正则解析的大小/预算限制
- **方案（建议下一批做）**：用流式 SAX/线性 tokenizer 替换 `.*?` 正则；逐段 `ensureActive()`；保留 zip 条目/解压/字符预算；畸形 XML fuzz 测试；错误归类 `DOCUMENT_PARSE` 不自动重试。
- **验收**：畸形 OOXML（大量未闭合标签）不 O(n²) 拖死 worker；取消及时；合法 DOCX/PPTX/XLSX 结果与当前一致。
- **状态**：**B14.3 core 任务**；当前已有大小/条目预算作为临时防线，但不作为最终方案。

### 1.6 SAF / URL UI 点击待输入权限窗口
- **现状**：K60U 开启 USB 调试后，SAF 选择器已可点击并成功暂存（本次实测）；`WebPageFetcher` 真实公网 HTTPS 抓取 1/1 通过；URL 文本输入受设备 IME 影响，尚未在 UI 完成端到端点击。
- **方案**：URL 输入补 `KeyboardType.Uri`/IME action；Compose UI 测试用 `performTextInput` 验证字段/按钮/回调，不依赖 adb 注入；真机 URL 导入用 autostart/测试数据旁路补一次（密码/网络开关）。SAF 文件选择器保持 `*/*` + 扩展名白名单。
- **验收**：SAF 多选→staging→全部导入→ready；URL 输入→抓取→staging→导入；错误码可读；不需要 adb 输入权限也可通过 Compose 测试回归。
- **状态**：**B14.1 已完成 URL 键盘/UI 测试代码 + JVM 兜底测试**；K60U HyperOS 仍拒绝 Compose instrumentation（`Process crashed`），SAF 真机已通过；URL 旁车/Sources 链接已用 autostart staging + UI dump 验证。

## 2. 执行顺序（不让限制混入下一步）

1. **B14.1 小修**：owner-password PDF、legacy Office 明确错误、URL 输入 `KeyboardType.Uri` + UI 测试（低风险，直接关闭）。
2. **B14.2 数据层**：`source.origin_url` schema v8 + 迁移/备份/UI（URL 溯源闭环）。
3. **B14.3 解析硬化**：Office 流式解析 + 取消 + fuzz。
4. **P1.1**（明确触发后）：OCR/用户密码 PDF；不提前引入大依赖。
5. 上述完成后再进入 P1 下一项（Explore/3D 或实体别名/抽取质量）；P2 按原触发条件。

## 3. 技术债红线

- 不允许把扫描 PDF 空文本当 ready；不允许把 URL 塞 title/文件名冒充溯源；不允许对畸形 XML 只加超时掩盖 O(n²)；不允许把 B14 未实现项标成完成。
- 每项完成需更新本文件 + B14 报告 + 测试证据；未完成项保持显式错误/触发条件。
