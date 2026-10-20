# P1.1b 报告：OCR 全页/逐页与质量、PDF 密码进程重启恢复

- **日期**：2026-10-20
- **状态**：**代码/JVM/真机验证完成**（K60U 重连后补跑；真实 ML Kit、25 页扫描件、Keystore/进程杀停恢复均通过）
- **对应债务**：OCR 仅前 20 页 + 复杂版式/低分辨率质量有限；密码仅进程内、进程死亡重输

## 1. OCR 全页/逐页

- `PdfOcrExtractor.OcrLimits.maxPages` 默认 **0=全部页**（>0 仅诊断/降级）；PDF 文本层侧页上限由 500 提升到 **2000**，并继续受 50MB/2M 字符预算/取消保护。
- 新增页级 `PdfPageOcr.ocrPages`：`PdfTextExtractor` 逐页抽文本层，**只对空页 OCR**；有文本页保持原文，任一页 OCR → `pdf-ocr-v1`，纯文本 → `pdf-text-v1`；扫描/文本混合 PDF 不再整篇回退/跳过。
- 质量：
  - 渲染宽 **2400px、源页 3x 适配**（高度≤4096 防 OOM）；
  - ML Kit block/line `boundingBox` 按行聚类 + 行内 left 排序；
  - 文本过少时按页面方向尝试 90°/180° 二遍，取文本更多者（每页≤2遍）；
  - 逐页空白跳过、Bitmap 回收、取消原样传播、离线无日志。
- 测试：fake 30 页全 OCR/无 20 截断、maxPages 配置、只 OCR 指定空页、混合 PDF parserId、阅读顺序、旋转二遍、取消/预算/资源回收；真实 ML Kit instrumentation 更新为 2 页高分辨率图片 PDF（本轮未跑设备，K60U 断开）。
- **残余边界**：手写体识别受 ML Kit 印刷体模型能力限制；多栏/表格按视觉行近似排序；超长页按高度缩放；单页 2400×4096 瞬时~30MB（逐页回收）。

## 2. 密码进程重启恢复

- 新增 `PdfPasswordStore`：
  - Android Keystore **AES/GCM/NoPadding**，随机 IV、独立 alias；
  - 密文/IV/createdAt Base64 存 SharedPreferences `pdf_password_store`，**无明文/无日志**；TTL 24h；
  - `allowBackup=false`，不在 KnowledgeBackup 中；解密失败/损坏/过期自动清理。
- `IngestRepository`：
  - retry 先加密保存副本再排队；processDocument **Vault 优先、store 次之**（模拟新进程可解密）；
  - 解析成功/密码错误（PDF_ENCRYPTED）→ 清 store；取消/IO/进程被杀 → 保留供 worker 自动恢复；
  - 显式取消/`deleteSource` 清理对应密文；UI 清零原 CharArray 不影响。
- 测试：store 13/13（fake cipher：往返/覆盖/TTL/损坏/无明文/Unicode）；PDFBox user-password 集成 5/5（**清空 Vault 模拟新进程→store 解密→解析成功→清理**；错误密码清空；IO 保留）；真实 Keystore instrumentation 6 例编译通过，待设备跑。
- UI 文案更新：「密码会使用系统密钥加密暂存在本机（24 小时内），导入成功或密码错误后自动删除，不会上传」。
- **残余边界**：retry 的 DB 重排队与 store.put 非原子，极小 crash 窗口会再次要求输入；Keystore key 失效/跨设备恢复按无密码处理；24h TTL 后重输（安全边界，不视为遗漏）。

## 3. 验证

| 检查 | 结果 |
|---|---|
| JVM debug / release | **678 / 0**、**659 / 0** |
| text | 16 类 141 tests / 0 failures |
| lint | 0 errors / 55 warnings |
| 设备 | K60U：真实 ML Kit OCR 2 页 instrumentation **1/1**；25 页扫描件真实导入 `pdf-ocr-v1` ready（含 PAGE 25）；Keystore **6/6**；密码重试后 0.7s 杀进程→重启自动恢复 ready |

## 4. 真机证据

- [P1.1b-ocr-25p-k60u.txt](evidence/P1.1b-ocr-25p-k60u.txt)、[P1.1b-ocr-mlkit-instrumented-k60u.txt](evidence/P1.1b-ocr-mlkit-instrumented-k60u.txt)
- [P1.1b-keystore-instrumented-k60u.txt](evidence/P1.1b-keystore-instrumented-k60u.txt)、[P1.1b-password-process-kill-k60u.txt](evidence/P1.1b-password-process-kill-k60u.txt)

## 5. 签名候选

- `dist/zhibox-0.1.2-fc8e8d7-release-signed.apk`（69,436,597 bytes，SHA-256 `7ce113779eff38169f8e3c38edcf12f43817a414245a4e5175732c90b5127c3b`；未发布/tag；设备 smoke 待 K60U 重连）。

## 6. 后续

- 重连 K60U 后跑 `PdfOcrInstrumentedTest`、`PdfPasswordStoreKeystoreTest`，补 25 页真实扫描件导入/进程杀停恢复 E2E。
- 手写/多栏如需更高精度，评估 PaddleOCR/RapidOCR 等离线引擎（会显著增加 APK/集成成本，需单列）。
