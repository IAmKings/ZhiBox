# P1.1 报告：扫描件 PDF OCR + user-password 重试

- **日期**：2026-10-20
- **状态**：**完成**（ML Kit 离线 OCR、默认设置开关、密码对话框/重试、真机 E2E）
- **目标**：关闭 B14 限制“扫描件无 OCR / user-password PDF 无法导入”。

## 1. OCR

- 依赖 `com.google.mlkit:text-recognition-chinese:16.0.1`（**bundled 离线模型**，无需 GMS 在线下载；ML Kit Terms of Service，非 Apache；strict verification metadata 649 components）。
- `PdfOcrPreferences`：`pdf_ocr_enabled`，**默认 true**；Settings 新增「扫描件 OCR（端侧）」开关。
- `PdfOcrExtractor`：文本层为空时用 `PdfRenderer` 渲染前 20 页（宽≤1600，白底 ARGB_8888）→ ML Kit 中文识别；逐页取消/字符预算；PDFRenderer/recognizer/bitmap 关闭回收。
- `PdfTextExtractor.extractWithMetadata`：文本层优先 `pdf-text-v1`；空文本且 OCR 开启 → `pdf-ocr-v1`；OCR 关闭保持可读 `PDF_NO_TEXT`；DocumentParser 写真实 parserId。
- 旧 `extract()` 保持纯文本层语义，避免旧测试/调用方行为变化。

## 2. user-password PDF

- `PdfPasswordVault` 仅进程内存（不 DB/不 prefs/不日志/不备份；take 一次性，调用方清零）。
- 导入失败（`PDF 需要打开密码`/`密码不正确`）时 Import 卡片出现「输入 PDF 密码」；对话框 `PasswordVisualTransformation`，确认后先清 UI 状态再传 CharArray；ViewModel finally 清零。
- `IngestRepository.retryPdfWithPassword`：事务 job FAILED→QUEUED / doc FAILED→PENDING、清 error；密码 **copyOf** 入 vault 后唤醒 worker；非 PDF/空密码/非 failed 可读拒绝。
- **E2E 发现并修复竞态**：原实现 vault 存调用方引用，ViewModel finally 清零后 worker 读到空密码；改为 `password.copyOf()`，新增回归测试。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **652 / 0**、**633 / 0** |
| lint | 0 errors / 49 warnings（新增 PDF OCR 2 个 KTX 风格 warning，非阻断） |
| Android 真实 OCR | `PdfOcrInstrumentedTest` **1/1 PASS**（K60U，离线 ML Kit） |
| K60U OCR 生产链路 | `p11_scanned` → `pdf-ocr-v1` ready，chunk “HELLO OCR 2026” |
| K60U 密码链路 | `p11_locked` 首次 failed 显示密码入口；输入 secret 重试 → `pdf-text-v1` ready，正文正确 |
| 旧测试 | text/ingest 全绿；B14.1-B14.3 不受影响 |

## 4. 成本/限制

- **APK 增加约 +13.8MB release / +15MB debug**（ML Kit native OCR 引擎 11MB + assets；arm64-only）；release ~69.4MB unsigned。
- OCR 仅前 20 页，复杂版式/手写/低分辨率质量有限；不自动重试 `PDF_OCR_FAILED`。
- user password 已用 Keystore 加密暂存（24h TTL，进程重启可恢复，成功/密码错误清理）；PDFBox `load` 需要短暂 String（JVM 限制，无明文持久化/日志）。
- ML Kit ToS 非 Apache；发布需保留 notices/ToS 文本（docs/reference/THIRD_PARTY_NOTICES.md）。

## 5. 后续修复（P1.1b）

- 全页/逐页 OCR 与 Keystore 密码恢复见 `P1_1B_DEBT_REPORT.md`；设备项待 K60U 重连补跑。

## 6. 下一步

- P1 下一项：Explore/3D 或实体别名/抽取质量。
- v0.1.2 发布前：重新签名 RC（含 ML Kit）、signed RC clean install smoke、最终 tag/Release（用户决定）。
