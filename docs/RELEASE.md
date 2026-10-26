# ZhiBox 发布手册（Release Runbook）

> 适用版本：`versionName = 0.1.4`（`app/build.gradle.kts`；v0.1.3 已发布）
> 对应批次：B8–B12 / R2–R3 质量修复；发布链路沿用 P2 B4（构建/依赖/发布）。相关实现：`app/build.gradle.kts`、`scripts/release.sh`、
> `gradle/wrapper/gradle-wrapper.properties`、`gradle/verification-metadata.xml`。
> 原则：**任何密钥/口令都不入仓库**；发布物必须可验签、可追溯、可回滚。

---

## 0. 构建/依赖基线（B4 升级后）

| 项 | 版本 | 说明 |
|---|---|---|
| compileSdk / targetSdk | 36 / 36 | AGP 8.13.2 支持上限内 |
| minSdk | 24 | 仅 arm64-v8a |
| AGP | 8.13.2 | 本批次不升级（AGP 9.x 需整体迁移） |
| Kotlin / KSP | 2.4.21 / 2.3.12 | 配对；Kotlin 2.5 与 KSP 2.3.12 未配对 |
| R8 | 9.1.29 | Kotlin 2.4 metadata 最低兼容版；版本在 `libs.versions.toml`，由 root `build.gradle.kts` 的 buildscript 覆盖 AGP 内置版本 |
| Compose BOM | 2026.06.01 | compose 1.11.4；2026.09.00 需 compileSdk 37 + AGP 9.1.0，不可用 |
| core-ktx | 1.18.0 | 1.19.x 需 compileSdk 37 + AGP 9.1.0，不可用 |
| lifecycle | 2.10.0 | 2.11.0 的 viewmodel-compose 需 compileSdk 37 + AGP 9.1.0，不可用 |
| activity-compose | 1.13.0 | 兼容（activity 1.13.0 需 compileSdk 36 + AGP 8.9.1） |
| mediapipe tasks-retrieval | 1.1.1 | EGemma2 440M/740M 的 UniversalEmbedder 通道；**tasks-text 已于 B7 下线**（旧 EmbeddingGemma `.task`/Path C 移除） |
| Gradle wrapper | 8.14.3 | `distributionSha256Sum` 已锁定 |

**体积提示（B7 起）**：旧 Path C（tasks-text / `.task`）下线后，release APK 从 64.55MB 降到
**≈49.07MB**（移除 `libmediapipe_tasks_textgenai_jni.so` 15.48MB）；保留 EGemma2 三模型路线所需的
tasks-retrieval + litertlm。模型资产本身不进入 APK（运行时投放）。

**升级/旧数据注意（B7）**：
- 旧 Granite / 旧 EmbeddingGemma `.task` / LFM2.5 模型空间已停用；升级后设置页会提示"检测到 N 个旧模型空间"，
  相关文档需**重新导入**才能被 EGemma2 检索（数据不会自动删除）。
- 含旧模型空间的旧备份包导入会被拒绝（可读错误提示），请用新模型重新导入原始文档后再导出备份。
- 新导出的备份包会把"仅有旧模型向量"的文档标记为 failed（`ready` 但无受支持向量的文档不再静默出现）：
  恢复后可在导入页看到失败任务，重新导入原始文件即可重建 EGemma2 向量（failed 文档允许同 checksum 重导）。

---

## 1. 签名密钥 provision（用户自备，不入库）

ZhiBox 的 release 签名材料从不入库；仓库只读取**环境变量**或**用户级 Gradle 属性**。
两种方式二选一：

### 1.1 生成 keystore（一次性，自行安全备份）

```bash
# 在仓库之外的目录执行；口令自行设定并妥善保管（丢失将无法升级已发布版本）
keytool -genkeypair -v \
  -keystore ~/keystores/zhibox-release.jks \
  -alias zhibox \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

要求：
- 文件权限收紧：`chmod 600 ~/keystores/zhibox-release.jks`；
- **备份 keystore + 口令**（换机/重装后丢失即无法发布升级包）；
- 不放入仓库、不放入 `dist/`、不贴进 issue/日志；`.gitignore` 已忽略 `*.jks`/`*.keystore`。

### 1.2 方式 A（推荐）：`scripts/release.sh` + 环境变量

脚本在构建 unsigned APK 后用 `zipalign + apksigner` 签名；口令以 `env:` 形式传给 apksigner，
不出现在进程命令行：

```bash
export ANDROID_KEYSTORE_FILE="$HOME/keystores/zhibox-release.jks"
export ANDROID_KEYSTORE_PASSWORD='***'
export ANDROID_KEY_ALIAS='zhibox'
export ANDROID_KEY_PASSWORD='***'
./scripts/release.sh
```

未设置 `ANDROID_KEYSTORE_FILE` 时脚本会保留 unsigned 产物并醒目告警（仅可内部验证）。

### 1.3 方式 B：Gradle signingConfig（`ZHIBOX_*`）

写入**用户级** `~/.gradle/gradle.properties`（不是仓库的 `gradle.properties`）：

```properties
ZHIBOX_STORE_FILE=/Users/<you>/keystores/zhibox-release.jks
ZHIBOX_STORE_PASSWORD=***
ZHIBOX_KEY_ALIAS=zhibox
ZHIBOX_KEY_PASSWORD=***
```

之后 `./gradlew :app:assembleRelease` 直接产出已签名 `app-release.apk`；
`scripts/release.sh` 会识别并跳过二次签名。四项缺任意一项时 AGP 不签名（unsigned，不阻塞构建）。

> 本项目 2026-10-18 已生成 release keystore（本地路径 `~/.zhibox-release/zhibox-release.jks`，
> `credentials.properties` 同目录、权限 600，证书 SHA-256：
> `B1:D1:36:BA:DC:C7:E0:32:A3:9D:85:04:FB:05:0F:98:F4:44:01:7A:EB:5F:5C:FC:E6:62:56:AF:EC:1D:B1:D1`）。
> **必须离线备份 keystore + 口令**；丢失后无法覆盖升级已安装用户（只能换包名/卸载重装）。

### 1.4 CI tag 自动签名发布（B4-6）

release keystore 通过 **GitHub Actions Secrets** 注入，tag 推送时自动构建、验签并创建 GitHub Release：

| Secret | 内容 |
|---|---|
| `ZHIBOX_STORE_BASE64` | release keystore 的 base64（单文件 PKCS12/JKS） |
| `ZHIBOX_STORE_PASSWORD` | keystore 口令 |
| `ZHIBOX_KEY_ALIAS` | key alias（本项目 `zhibox`） |
| `ZHIBOX_KEY_PASSWORD` | key 口令 |

配置（值只从本地文件读，不贴入聊天/仓库；`gh` 已登录上游仓库）：

```bash
KS="$HOME/.zhibox-release/zhibox-release.jks"
base64 < "$KS" | tr -d '\n' | gh secret set ZHIBOX_STORE_BASE64
sed -n 's/^ZHIBOX_STORE_PASSWORD=//p' "$HOME/.zhibox-release/credentials.properties" | gh secret set ZHIBOX_STORE_PASSWORD
printf '%s' zhibox | gh secret set ZHIBOX_KEY_ALIAS
sed -n 's/^ZHIBOX_KEY_PASSWORD=//p' "$HOME/.zhibox-release/credentials.properties" | gh secret set ZHIBOX_KEY_PASSWORD
```

发版流程（workflow：`.github/workflows/release.yml`）：

```bash
# 1) 确认 versionName 与 tag 一致（workflow 会强校验 v<versionName>）
# 2) 打 tag 并推送（触发 Release workflow）
git tag -a v0.1.4 -m "ZhiBox v0.1.4"
git push origin v0.1.4
# 3) 等待 GitHub Actions "Release (signed APK)" 全绿；会自动创建 Release 并上传：
#    zhibox-<version>-<sha7>-release-signed.apk / .apk.sha256 / -mapping.txt
```

workflow 校验点：tag 与 versionName 一致、keystore 可解码、`apksigner verify --print-certs` 有证书 SHA-256、
非 debuggable、`aapt2 dump badging` 成功；任一失败不会创建 Release。

### 1.5 验签

```bash
apksigner verify --print-certs dist/zhibox-<version>-<sha>-release-signed.apk
# 期望：DOES NOT ERROR / 输出 Signer #1 certificate DN + SHA-256
```

---

## 2. 构建与归档

```bash
# 内部验收（debug，debug key 自动签名，允许 run-as/调试器）
./scripts/release.sh --debug

# 发布候选（release；有密钥则签名，无则为 unsigned 并告警）
./scripts/release.sh

# 纯 Gradle 方式
./gradlew :app:assembleRelease

# 发布前 ARM64 真机稳定回归（默认排除 @LargeTest；每项最多 3 分钟，整项最多 15 分钟）
./gradlew :app:connectedDebugAndroidTest

# 评测/性能/联网/真实 Activity 重建按需单独执行，避免拖慢或卡住默认回归
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w \
  -e annotation androidx.test.filters.LargeTest \
  -e timeout_msec 180000 \
  com.zhibox.memory.test/androidx.test.runner.AndroidJUnitRunner
```

产物命名（`scripts/release.sh` 自动归档到 `dist/`，该目录已在 `.gitignore`）：

```
dist/zhibox-<versionName>-<gitSha7>[-dirty]-release[-signed].apk
dist/zhibox-<versionName>-<gitSha7>[-dirty]-debug.apk
dist/...apk.sha256        # SHA-256 sidecar（分发时必须附带）
```

`versionCode` 位于 `app/build.gradle.kts` 的 `defaultConfig`；**每次对外发布必须递增**，
否则无法覆盖安装。发布前确认 `git status` 干净（脚本对 dirty 工作区会加 `-dirty` 标记并告警）。

---

## 3. 依赖校验与 Gradle 发行包完整性

- **Gradle 包完整性**：wrapper 已锁定 `distributionSha256Sum`（Gradle 8.14.3 官方值）。
  换版本必须同步更新；镜像重新打包导致校验失败时应改用官方源（见 wrapper 注释），
  **不要**通过删除校验绕过。
- **依赖校验（verification metadata）**：模板见 `gradle/verification-metadata.xml`。
  > ⚠️ 该文件存在即启用 strict 校验。首次/依赖变更后必须先生成再跑门禁：
  ```bash
  ./gradlew --write-verification-metadata sha256 :app:assembleDebug
  git add gradle/verification-metadata.xml
  ```
  之后任何未登记/被替换的依赖都会让构建失败。临时排障可加
  `--dependency-verification=off`；若决定暂不启用，删除该文件并记录原因。

---

## 4. 构建可追溯（BuildConfig）

`app/build.gradle.kts` 向 `BuildConfig` 注入：

- `BUILD_GIT_SHA`：`git rev-parse --short=7 HEAD`，失败/非 git 工作区回退 `unknown`；
- `BUILD_TIME`：ISO-8601 UTC；设置 `SOURCE_DATE_EPOCH` 时用于可复现构建。

注意：release 开启 R8 后，未被引用的 BuildConfig 常量可能被裁剪，因此**对外追溯口径**为：
`versionName`（APK badging）+ 归档文件名中的 `gitSha7` + `.sha256` + 发布记录里的 commit/tag。
debug 包可用 `apkanalyzer dex packages/code` 直接核对 `com.zhibox.memory.BuildConfig`。

---

## 5. 真机安装（OEM 增强防护引导）

OnePlus / ColorOS / OxygenOS（以及多数国产 ROM）会拦截“外部来源 APK”。按顺序尝试：

1. **允许安装未知应用**：设置 → 应用 → 特殊应用权限 → 安装未知应用 → 选择你要用的
   文件管理器/浏览器 → 允许。
2. **关闭安装校验/增强防护**：设置 → 安全（或“密码与安全/系统安全”）→ 找到
   “应用安装校验 / 安装前安全检测 / 增强防护” → 关闭（不同 OxygenOS 版本菜单名略有差异；
   部分机型在“支付保护/安全防护”二级菜单内）。
3. **兜底安装方式**：仍被拦截时，用 `adb install -r -d <apk>`（需开启开发者选项 USB 调试），
   或临时开飞行模式后从文件管理器安装（安装完恢复网络）。

安装后若“应用未安装/解析包错误”：先确认 ABI（仅 arm64-v8a，x86 模拟器不支持）、
再确认与已安装版本签名一致（换签名必须卸载旧版，**卸载前先导出知识库备份**）。

---

## 6. 发布验收清单（Checklist）

构建与产物

- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` 全绿
- [ ] `scripts/release.sh` 或等价流程产出 `dist/...apk`，命名含 versionName + gitSha7 + 模式
- [ ] 附带 `.sha256`，并与 `shasum -a 256 <apk>` 一致
- [ ] `apksigner verify --print-certs <apk>` 通过（signed 产物）

Manifest / 安全（P0-3、P1-10 回归）

- [ ] `aapt2 dump badging <apk>`：无 `application-debuggable`（release）；package/versionCode/versionName 正确；
      `native-code: 'arm64-v8a'`；`targetSdkVersion:'36'`
- [ ] `aapt2 dump xmltree --file AndroidManifest.xml <apk>`：`allowBackup=false`、
      `fullBackupContent`/`dataExtractionRules` 均存在且排除全部域；`networkSecurityConfig`
      全局 `cleartextTrafficPermitted=false`，模型下载域名策略为 `hf-mirror.com`
- [ ] release dex 中无 `com.zhibox.memory.benchmark` / 实验 UI（`apkanalyzer dex packages`）

功能 smoke（真机，arm64）

- [ ] 冷启动 → 首页无“实验/诊断”入口（release）
- [ ] 导入一个文件 → 文档 ready → 关键词/向量检索命中 → 证据页返回不丢结果
- [ ] 备份导出 → 导入恢复（v2 格式）→ 再导出 vectors 数不降
- [ ] 模型路线（B7 后仅 EGemma2）：Path A 440M/740M（UniversalEmbedder）与 Path B 270M（litertlm EmbeddingEngine）
      分别加载并输出有限向量；三档强度推荐（270M/440M/740M）可切换
- [ ] 旧数据兼容：含旧模型空间的库升级后「设置 → 资料库」（或 高级与诊断）出现"旧模型空间"警告；旧备份导入被可读拒绝；删除旧文档后重新导入可恢复检索
- [ ] 空间切换/重建向量流程可用；「设置 → 高级与诊断 → 模型商店」下载 SHA 校验通过

可追溯与回滚

- [ ] 记录本次发布 commit/tag、versionCode/versionName、APK sha256
- [ ] 上一发布版 APK + 对应 commit 已归档（回滚用）
- [ ] 崩溃排查材料：保留 `app/build/outputs/mapping/release/mapping.txt` 与 commit 对应

---

## 7. 回滚说明

1. **先保数据**：回滚/卸载前让用户执行“导出知识库备份”（设置 → 资料库 → 导出备份），避免个人知识库丢失。
2. **同 versionCode 不能降级安装**：Android 不允许 versionCode 回退。
   - 若只是回退代码但保持/提高 versionCode：用旧代码 + 更高 versionCode 重新构建发布（推荐）；
   - 若必须装回旧包：先 `adb uninstall com.zhibox.memory`（会清数据！）再安装旧 APK。
3. **数据库迁移不可逆**：Room 迁移已升级的库无法自动降级。回滚到旧 schema 前必须用
   备份导出→导入方式迁移数据；发布前确认迁移测试（androidTest）在目标版本上通过。
4. **签名一致性**：回滚包必须与已安装版本同签名（同一 keystore），否则只能卸载重装。
5. 回滚后验证：`apksigner verify` → 安装 → 冷启动 → 导入/检索 smoke → 比对 sha256 与发布记录。

---

## 8. 常见问题

| 现象 | 原因/处理 |
|---|---|
| `apksigner verify` 失败/缺 META-INF | 产物是 unsigned：按第 1 节配置密钥后重新签；unsigned 禁止对外分发 |
| 安装提示签名不一致 | 与已装版本 keystore 不同：用户卸载（先导出备份）或沿用原 keystore |
| 安装被 OEM 拦截 | 见第 5 节 3 步；兜底 `adb install -r -d` |
| `Dependency requires compileSdk 37 / AGP 9.1.0` | 依赖版本超出兼容上限（见第 0 节表格），回退到兼容版本或整体迁移 AGP 9.x + compileSdk 37 |
| Gradle wrapper sha256 校验失败 | 镜像包与官方不一致：切换官方 `services.gradle.org` 源，勿删除校验 |
| release 崩溃但 debug 正常 | 用对应 commit 的 `mapping.txt` 反混淆；核对 proguard keep（MediaPipe/LiteRT-LM/Room/WorkManager）是否覆盖新依赖 |

---

## 9. 2026-10-22 质量修复后 RC 状态

- 重建产物：`dist/zhibox-0.1.2-a28a3f8-release-signed.apk`
- gitHead：`a28a3f8`；size：70,164,019 bytes
- SHA-256：`46cb20eaab2cf9700f8952cea47c1c2158d7c88ffae6b781032d91a0683e1619`
- apksigner：PASS；signer cert SHA-256 `b1d136ba…1db1d1`（与 v0.1.1 同 release key）
- 设备 smoke：官方 signed RC 已在 PJZ110 上 `adb install -r` 覆盖升级（同 release key，保留数据/LLM），主页/检索/设置/问答入口 smoke 通过（设置页走查路径：简单首页 → 高级与诊断）。
- **clean-install smoke：K40（M2012K10C）`adb install` 全新安装 PASS**；cold start 主页空态/检索/设置均正常，模型缺失状态正确提示；未做 destructive import/delete。
- 未做：模型下载/LLM/聊天功能 smoke（需 key/model）；R5 并发压力仍按豁免记录。
- 结论：signed RC 已完成 clean-install + upgrade smoke；仍未 tag/未发布，Agent 质量声明仍为 false。

---

## 10. 2026-10-23 v0.1.3 signed RC / 发布准备

- 产物：`dist/zhibox-0.1.3-bdc8be2-release-signed.apk`
- versionCode 4 / versionName 0.1.3；SHA-256 `0f47cdffcea8eb2d4b626401904686393bc2275357f2a2d651070ee6fdf4a03b`
- apksigner PASS；PJZ110 同签名覆盖升级成功，数据/LLM 保留，启动 smoke 通过。
- 证据：`docs/review/evidence/V0.1.3-rc-pjz110-smoke.txt`、`V0.1.3-RELEASE_CANDIDATE.json`。
- 尚未打 tag/发布；下一步按本手册流程触发 tag Release。

---

## 11. 2026-10-24 v0.1.4 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **5 / 0.1.4** |
| 基线提交 | `364925a`（tag `v0.1.4`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.3） |
| 签名 | 沿用既有 release keystore（与 v0.1.3 同证书，可覆盖升级） |
| 发布产物 | `zhibox-0.1.4-364925a-release-signed.apk`（70,284,958 bytes）；SHA-256 `263fe529c210031707dd8138e375183cef8e901876bdbf9aef2b396ebbb2bcb7`；同名 `.sha256` 与 `zhibox-0.1.4-364925a-mapping.txt` 一并上传 |
| 发布通道 | tag 推送 → `.github/workflows/release.yml` 自动签名构建 + apksigner/aapt2 校验 + 创建 GitHub Release |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- 智能状态中枢 M1/M2/M3：真实四态状态点、引导条、Bottom Sheet 面板、设置分组与深链、无障碍修复；
- 首页：新内容胶囊、分类滚动位置记忆、念头图标改铅笔、启动页、输入岛贴键盘与"点外部收键盘"；
- 设置：LazyColumn + 稳定组合 + 重查询按需加载 + 超高卡片拆分（P95 滑动帧 9ms）、卡片色统一 card-pure；
- 备份：`ui_hint_` 前缀的本机 UI 提示不随备份迁移；
- 工程：`:benchmark` Macrobenchmark 模块（启动 + 设置页滑动基线）与依赖校验；
- 智能任务：下载/重嵌入进度共享 Flow，状态点快脉冲 + TalkBack 播报。

验收：CI（docs 一致性 + assembleDebug + 单测 + lint + androidTest 编译 + assembleRelease）通过后再打 tag；
tag 与 versionName 由 release workflow 强校验。

## 12. 2026-10-27 v0.1.5 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **6 / 0.1.5** |
| 基线提交 | 版本号提交（tag `v0.1.5`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.4） |
| 签名 | 沿用既有 release keystore（与 v0.1.4 同证书，可覆盖升级） |
| 发布产物 | `zhibox-0.1.5-<sha7>-release-signed.apk`（由 tag 推送后的 Release workflow 构建并上传；size/SHA-256 见 GitHub Release 资产，发布后回填） |
| 发布通道 | tag 推送 → `.github/workflows/release.yml` 自动签名构建 + apksigner/aapt2 校验 + 创建 GitHub Release |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- 设置页简化：默认「简单首页 4 卡 + 高级入口」，原 17 项能力全部迁入「高级与诊断」二级页
  （现有分组与顺序不变，`SettingsItemOrder` 深链下标零改动）；
- 新增二级页：模型管理（自动/轻量 270M/标准 440M/更强 740M，切档二次确认重建索引）、
  资料库、联网问答（复合开关+服务配置）、隐私与关于；
- 高级页：顶部「显示技术细节」开关（默认关，关闭时隐藏文件名/空间 ID/错误码/诊断计数）、
  长说明折叠、诊断卡标题去内部工单号；
- 无障碍：首页/二级页开关补 contentDescription，卡片行 48dp；
- 真机验收（Redmi 23078PND5G / Android 14）：浅/深色、200% 字号、深链落位高亮、
  深链返回退出、技术细节开关、开关语义标签；
- 基准：`SettingsBenchmark` 改为在「高级与诊断」页采集并重录基线
  （2026-10-27，P95 median 17ms / jank 6.85%）；
- 新增测试 33 例（页面模型/文案黑名单/首页与高级页 Robolectric），全量单测 947 例 0 失败。

验收：CI（docs 一致性 + assembleDebug + 单测 + lint + androidTest 编译 + assembleRelease）通过后再打 tag；
tag 与 versionName 由 release workflow 强校验。
