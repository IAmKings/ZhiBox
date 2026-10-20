# B14.1 小修报告（PDF owner-password / legacy Office / URL 输入）

- **日期**：2026-10-20
- **状态**：代码/自动测试完成；URL Compose 真机测试受 K60U HyperOS 限制未执行（编译通过）
- **范围**：B14_LIMITATIONS_PLAN 第 1.2、1.3、1.6 项中的可立即关闭部分

## 1. PDF owner-password
- `PDDocument.load(file, "")`；owner-password-only（user password 为空且允许 `canExtractContent`）可正常抽取。
- 需要 user password 的 PDF：`PDF_ENCRYPTED` 可读失败，不接收/不记录/不持久化密码。
- 加载成功但权限禁止复制/抽取：`PDF_ENCRYPTED`（owner 权限限制）。
- 测试：PDFBox `StandardProtectionPolicy` 生成 owner-only/user-password/禁复制三种 fixture；owner-only 成功、其余两类稳定失败。KDoc 同步。
- **Android runtime 2/2**：K60U 上 `PdfOwnerPasswordInstrumentedTest` 验证 owner-only 抽取成功、user-password 返回 `PDF_ENCRYPTED`（证据 `evidence/B14.1-pdf-encrypted-k60u.txt`）。

## 2. Legacy Office
- `.doc/.ppt/.xls` 扩展名与 `application/msword`/`vnd.ms-powerpoint`/`vnd.ms-excel` MIME → `OFFICE_LEGACY_UNSUPPORTED` / `office-legacy`，文案“请另存为 .docx/.pptx/.xlsx”；不再误当 OOXML 解析。
- OLE 魔数 `D0 CF 11 E0 A1 B1 1A E1` 兜底识别；删除“PDF 支持即将提供”过期文案。
- 测试：扩展名/MIME/OLE/批量单文件失败不中断/通用不支持提示。

## 3. URL 输入
- `OutlinedTextField` 增加 `KeyboardType.Uri` + `ImeAction.Go`，Go 与「抓取并导入」按钮同样调用 `onImportUrl`（空/抓取中不触发）。
- 新增 `ImportUrlInputTest`（6 用例）：输入回调、IME Go、按钮点击、空 URL 禁用、抓取中禁用、section 可见。
- K60U 真机执行结果为 `shortMsg=Process crashed.`（HyperOS 拒绝 Compose 测试宿主后台 Activity，非 App/逻辑崩溃）；UI 测试编译通过，待可用设备（PJZ110/K40）执行。此前 SAF 文件选择器已在 K60U 手动通过。

## 4. 验证
- JVM debug **582/0**、release **563/0**；lint 0 errors / 47 warnings。
- K60U Android runtime：PDF owner/user 密码 **2/2**。
- `core.text` 82 tests 全绿（含新增 PDF/legacy 测试）。
- 未改 UI 导入/ViewModel 行为；未 commit 前由 Lead 统一提交。

## 5. 未关闭
- 用户密码 PDF 输入（P1.1）、扫描件 OCR（P1.1）。
- URL Compose 测试真机执行（需 Compose 测试宿主可启动的设备；K60U HyperOS 限制）。
- URL 原始地址 schema v8（B14.2）。
- Office 流式解析（B14.3）。
