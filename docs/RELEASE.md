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
| 基线提交 | `f78d0c2`（tag `v0.1.5`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.4） |
| 签名 | 沿用既有 release keystore（与 v0.1.4 同证书，可覆盖升级） |
| 发布产物 | `zhibox-0.1.5-f78d0c2-release-signed.apk`（70,345,697 bytes）；SHA-256 `08009f7902efd41b212f882883cac87ad6ad9916d566ac0e75daad65aac5ea06`；同名 `.sha256` 与 `zhibox-0.1.5-f78d0c2-mapping.txt` 一并上传 |
| 发布通道 | tag 推送 → `.github/workflows/release.yml` 自动签名构建 + apksigner/aapt2 校验 + 创建 GitHub Release（https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.5） |

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

## 13. 应用内更新（v0.1.6 起）

- **仓库必须 public**：应用通过匿名 GitHub API
  `https://api.github.com/repos/IAmKings/ZhiBox/releases/latest` 查询最新版本；
  private 仓库会 404，Release 资产也无法直接下载。公开前需确认无密钥/凭据入库
  （见 `docs/product/APP_UPDATE_SPEC.md` §1）。
- **Release 资产契约**（release.yml 已满足）：`zhibox-<version>-<sha7>-release-signed.apk`
  + 同名 `.sha256` + `-mapping.txt`；API `digest` 字段可用时客户端优先使用。
- **下载容错**：直连 GitHub 资产 CDN（objects.githubusercontent.com）在部分网络不可达；
  客户端按 `BuildConfig.UPDATE_DOWNLOAD_MIRRORS`（默认 `gh-proxy.com`、`ghfast.top`）回退镜像，
  完整性由 SHA-256 + APK 签名校验保证。镜像不可用/全部失败时弹窗提示网络错误可重试。
- **Release body**：release.yml 现在只提取 `docs/RELEASE_NOTES.md` 的 `## 0. vX.Y.Z` 段落作为
  Release 说明（应用内更新弹窗直接展示）；旧版本（≤v0.1.5）body 仍是整份历史，客户端会截断
  并在弹窗里提供「查看完整说明」。
- **发版后验证**：
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' \
    https://api.github.com/repos/IAmKings/ZhiBox/releases/latest   # 200
  curl -sIL -o /dev/null -w '%{http_code}\n' \
    https://github.com/IAmKings/ZhiBox/releases/download/vX.Y.Z/zhibox-X.Y.Z-<sha7>-release-signed.apk  # 200
  ```
- **端到端安装验证**需要连续两个带更新服务的版本：v0.1.6 首次带服务，无法从 v0.1.5 触发；
  在 v0.1.7 发布时用 v0.1.6 安装包走查「检查 → 下载 → 校验 → 系统安装器」。

## 14. 2026-10-27 v0.1.6 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **7 / 0.1.6** |
| 基线提交 | `da561e5`（tag `v0.1.6`；含 GitHub 代理镜像回退修复，替代首版 `9cfb51a`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.5） |
| 签名 | 沿用既有 release keystore（与 v0.1.5 同证书，可覆盖升级） |
| 托管要求 | **GitHub 仓库 public**：应用匿名查询 `releases/latest` 并下载公开资产 |
| 发布产物 | `zhibox-0.1.6-da561e5-release-signed.apk`（70,399,448 bytes）；SHA-256 `a1207dd28215bf29fdd7bb24f4a5d3ee2dd7a61ec54171d0c620605891c089f9`；同名 `.sha256` 与 `zhibox-0.1.6-da561e5-mapping.txt` 一并上传 |
| 发布通道 | tag 推送 → `.github/workflows/release.yml`（signed + apksigner/aapt2 + Release body 仅当前版本段落）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.6 |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- 应用内更新服务：冷启动 24h 节流自动检查、DESIGN 更新弹窗、下载进度（下载/校验不可取消）、
  SHA-256 + 包名/versionCode/签名校验、自动拉起系统安装器、未知来源权限引导；
- 设置 → 隐私与关于 → 检查更新：取消在途检查并重新生成任务；
- 隐私声明新增第 4 条联网路径；GitHub 仓库改为 public 托管更新源；
- 新增 4 个测试类（模型/Manager/Mock HTTPS/Robolectric 弹窗），真机 mock 走查截图 5 张。

验收：CI（docs 一致性 + assembleDebug + 单测 + lint + androidTest 编译 + assembleRelease）通过后再打 tag；
tag 与 versionName 由 release workflow 强校验。端到端下载/安装需在 v0.1.7 发布时从 v0.1.6 走查。


## 15. 2026-10-27 v0.1.7 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **8 / 0.1.7** |
| 基线提交 | `8de29e1`（tag `v0.1.7`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.6） |
| 签名 | 沿用既有 release keystore（与 v0.1.6 同证书，可覆盖升级） |
| 发布政策 | `release/update-policy.json`（本版本 `rolloutPercent=100`、`minSupportedVersion=0.1.0`） |
| 发布产物 | `zhibox-0.1.7-8de29e1-release-signed.apk`（70,428,826 bytes）；SHA-256 `e47d79b4b0de3cc8389185217530212c548d36c1ecac5e795ad4c6a13a6843ee`；`.sha256`、mapping、`zhibox-0.1.7-8de29e1-update.json`（rollout 100 / minSupported 0.1.0）一并上传；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.7 |

### 灰度/强制更新发布操作

1. 发版前修改 `release/update-policy.json`：
   - `rolloutPercent`：0–100（默认 100 全量）；灰度期间可先设 10/50；
   - `minSupportedVersion`：低于该版本的客户端强制更新（默认 `0.1.0` 不强制）；
2. 提交 policy 后打 tag 推送；workflow 生成 `zhibox-<ver>-<sha7>-update.json` 并随 Release 上传；
3. 发布后调整灰度（不重发版）：
   `gh release upload vX.Y.Z dist/...-update.json --clobber`（重新上传同 schema 文件即可）；
4. **Beta 发布**：tag 用 `vX.Y.Z-beta.N`（`versionName` 必须一致），workflow 会创建
   GitHub prerelease，不会进入 stable 的 `releases/latest`；
5. 校验：`gh release view vX.Y.Z --json assets` 应包含 APK、`.sha256`、mapping、`update.json`；
   应用内 Beta 开关打开后应能看到 prerelease。

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- WorkManager 后台下载 + 通知栏进度 + 跨进程续传；
- 自动检查开关、Beta 通道、灰度/强制更新元数据；
- 下载线路健康监控、设置搜索；
- 不包含增量更新（APK 签名约束）。

### 发版后修复（进入下一个版本）

- `UpdateDownloader` 跨线路续传：直连失败回退镜像时保留 `.part` 并尝试 Range，
  镜像不支持 Range 再由 `attemptDownload` 丢弃半包重下。修复前「直连不可达 + 镜像」
  场景下进程被杀会从头下载；修复后真机验证 `.part` 2,179,072 B → 4,001,454 B 续传成功。
- 该修复不改变 v0.1.7 已发布产物，随下一版本（v0.1.8）发布。


## 16. 2026-10-27 v0.1.8 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **9 / 0.1.8** |
| 基线提交 | `049c249`（tag `v0.1.8`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.7） |
| 签名 | 沿用既有 release keystore（与 v0.1.7 同证书，可覆盖升级） |
| 发布政策 | `release/update-policy.json`（本版本 `rolloutPercent=100`、`minSupportedVersion=0.1.0`） |
| 发布产物 | `zhibox-0.1.8-049c249-release-signed.apk`（70,428,815 bytes）；SHA-256 `3d5b65a13e1d727f78bd275e7f260558452f6853e88f6e7f45edb481311f30c5`；`.sha256`、mapping、`zhibox-0.1.8-049c249-update.json`（rollout 100 / minSupported 0.1.0）一并上传；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.8 |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- 跨线路续传修复（回退镜像时保留 `.part`，Range 续传；镜像不支持则丢弃半包重下）；
- Android 17（OPPO PJZ110）真机验收补充；
- 无更新策略/界面变更（全量、非强制）。


## 17. 2026-10-28 v0.1.9 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **10 / 0.1.9** |
| 基线提交 | `68d06f0`（tag `v0.1.9`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.8） |
| 签名 | 沿用既有 release keystore（与 v0.1.8 同证书，可覆盖升级） |
| 发布政策 | `release/update-policy.json`（全量 100%，minSupported 0.1.0） |
| 发布产物 | `zhibox-0.1.9-68d06f0-release-signed.apk`（70,428,817 bytes）；SHA-256 `74b3dce4ba7250ab69a3d577a1b70c02166e9a82e6907c65eaccc8b581e7ba1a`；`.sha256`、mapping、`zhibox-0.1.9-68d06f0-update.json`（rollout 100 / minSupported 0.1.0）一并上传；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.9 |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- 修复 `update.json` 元数据不跟随 GitHub 资产 302 重定向（灰度/强制此前未生效）；
- 通知被拒时，后台下载完成回到前台自动续装；
- Android 17 Beta/灰度真机演练记录（APP_UPDATE_SPEC §9.4）。

### v0.1.9 发版后演练（Android 17，2026-10-28）

- Beta：stable 通道看不到 `v0.1.10-beta.1`；打开 Beta 开关后可见并可弹出更新框；关闭后恢复 stable。
- 灰度：`update.json rolloutPercent=0` 时设置行显示「新版本正在分批推送（0%）」、不弹窗；
  `rolloutPercent=100`（新 Release）时正常弹出更新框。
- 经验：`gh release upload --clobber` 覆盖同名 `update.json` 后，部分 CDN 边缘会短暂返回旧内容
  （实测设备仍读到 rollout 0）；调整灰度建议**新建 Release/资产**或等待 CDN 缓存过期，
  不要只覆盖同一资产后立刻验证。
- 强制更新本轮未在真机演练（避免把测试机锁在强制态）；由 `UpdateRollout/Manager` 单测覆盖。
- 通知被拒场景：v0.1.9 增加「回到前台自动续装」，单测覆盖；真机完整验证需要下一个
  更高 versionCode 的正式安装包（本轮 drill APK 复用 v0.1.9，版本号不满足安装器校验）。

## 18. 2026-10-28 v0.1.10 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **11 / 0.1.10** |
| 基线提交 | 版本号提交（tag `v0.1.10`）；发布后回填 |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.9） |
| 签名 | 沿用既有 release keystore（与 v0.1.9 同证书，可覆盖升级） |
| 发布政策 | `release/update-policy.json`（全量 100%，minSupported 0.1.0） |
| 发布产物 | `zhibox-0.1.11-27be156-release-signed.apk`（70,428,817 bytes）；SHA-256 `93fbdaf5b9d3cb21f1f5886227c04a0905e0c5dea96418d4e0e319787d43c919`；`.sha256`、mapping、`zhibox-0.1.11-27be156-update.json`（rollout 100 / minSupported 0.1.0）一并上传；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.11 |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- `update.json` 拉取加 cache-buster + `Cache-Control: no-cache`，灰度/强制元数据调整即时生效；
- 强制更新、通知被拒自动续装的真机演练记录（APP_UPDATE_SPEC §9.5）。

## 19. 2026-10-28 v0.1.11 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **12 / 0.1.11** |
| 基线提交 | `27be156`（tag `v0.1.11`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.10） |
| 签名 | 沿用既有 release keystore（与 v0.1.10 同证书，可覆盖升级） |
| 发布政策 | `release/update-policy.json`（全量 100%，minSupported 0.1.0） |
| 发布产物 | `zhibox-0.1.12-6110dfc-release-signed.apk`（70,428,809 bytes）；SHA-256 `4604bf0ad936e063a0a938827dfaeb3926ba6d39386ba41370ac60797bc93cba`；`.sha256`、mapping、`zhibox-0.1.12-6110dfc-update.json`（rollout 100 / minSupported 0.1.0）一并上传；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.12 |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- `update.json` 拉取镜像回退（受限网络下灰度/强制元数据不再静默失效）；
- 强制更新 + 通知被拒真机演练记录（APP_UPDATE_SPEC §9.5）。

## 20. 2026-10-28 v0.1.12 发布记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **13 / 0.1.12** |
| 基线提交 | `6110dfc`（tag `v0.1.12`） |
| 数据库 schema | v9（本版本无 schema 迁移，可覆盖升级 v0.1.11） |
| 签名 | 沿用既有 release keystore（与 v0.1.11 同证书，可覆盖升级） |
| 发布政策 | `release/update-policy.json`（全量 100%，minSupported 0.1.0） |
| 发布产物 | 由 tag 推送后的 Release workflow 构建；APK/.sha256/mapping/`update.json`；size/SHA-256 发布后回填 |

本版本内容概要（详见 `docs/RELEASE_NOTES.md` §0）：
- 修复自更新完成后误弹「更新失败」：已安装目标版本时静默清理旧 WorkManager SUCCEEDED；
  校验通过后取消唯一任务；迟到失败回调不再打扰。
- 回归测试 + 人工验收手册 O-04 预期更新。
- 根因与复现：v0.1.9 → v0.1.11 前台更新完成后，新进程消费旧 SUCCEEDED →
  ApkValidator 判 versionCode 不再更大 → DownloadFailed；关闭后才看到已更新。

### v0.1.12 修复验证（2026-10-28）

- 另一台可访问 GitHub 的真机按 O-04 流程从 **v0.1.11 前台更新到 v0.1.12**：
  **未再出现「更新失败」弹窗**；安装后版本正确，检查更新为最新。
- K60U 受限网络下旧 bug 未稳定复现（新进程 WorkManager 初始化时清理了旧任务）；
  修复以回归单测（旧 SUCCEEDED + 已安装版本 → Idle）与上述真机验证为准。
- 根因：≤v0.1.11 自更新完成后，新进程再次消费 WorkManager 的 SUCCEEDED 旧任务，
  `ApkValidator` 因目标版本已安装、versionCode 不再更大而判无效 → 弹「更新失败」。

## 21. 2026-10-28 v0.1.13-beta.1 内测 Beta 记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **14 / 0.1.13-beta.1** |
| 基线提交 | `0ab5ad0`（tag `v0.1.13-beta.1`） |
| 数据库 schema | v9（与 v0.1.12 同 schema，可覆盖升级） |
| 签名 | 沿用既有 release keystore（可覆盖升级） |
| 发布政策 | `release/update-policy.json`（rollout 100%，minSupported 0.1.0） |
| 产物 | `zhibox-0.1.13-beta.1-0ab5ad0-release-signed.apk`（70,428,827 bytes）；SHA-256 `8f8d260e2889cc696f452bae91de8739c542496318313ce641e176871094a8d1`；`.sha256`、mapping、`zhibox-0.1.13-beta.1-0ab5ad0-update.json`（channel=beta、rollout 100、minSupported 0.1.0）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.13-beta.1 |
| 用途 | Beta 预发布通道人工验收；stable 用户不可见；不用于灰度/强制演练 |

本版本内容：
- 与 v0.1.12 同功能，仅版本号提升；
- 验收：stable 看不到、开启 Beta 可见、可正常下载安装（**2026-10-28 人工验收通过**）。

## 22. 2026-10-28 v0.1.13-qa.1 灰度演练记录（rollout=0）

| 项 | 值 |
|---|---|
| versionCode / versionName | **15 / 0.1.13-qa.1** |
| 类型 | prerelease（Beta 通道可见） |
| 发布政策 | `rolloutPercent=0`、`minSupportedVersion=0.1.0` |
| 用途 | 灰度「未命中」边界：不弹窗、不下载、显示分批推送提示 |
| 产物 | `zhibox-0.1.13-qa.1-3d70b8d-release-signed.apk`（70,428,813 bytes）；SHA-256 `505867f0a1b88dd6919f00c2d9c9bf8d783491f3c4142fe51952afb609c3b2f8`；`.sha256`、mapping、`zhibox-0.1.13-qa.1-3d70b8d-update.json`（channel=beta、rollout 0、minSupported 0.1.0）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.13-qa.1 |
| 后续 | 验收通过后发 `v0.1.13-qa.2`（rollout=100）验证命中安装；完成后恢复 policy 100/0.1.0 |

本版本内容：与 v0.1.13-beta.1 同功能，仅版本号提升；stable latest 保持 v0.1.12。

## 23. 2026-10-28 v0.1.13-qa.2 灰度演练记录（rollout=100）

| 项 | 值 |
|---|---|
| versionCode / versionName | **16 / 0.1.13-qa.2** |
| 类型 | prerelease（Beta 通道可见；高于 qa.1） |
| 发布政策 | `rolloutPercent=100`、`minSupportedVersion=0.1.0` |
| 用途 | 灰度「命中」边界：弹窗、可下载安装；完成后检查更新为最新 |
| 产物 | `zhibox-0.1.13-qa.2-2a470ae-release-signed.apk`（70,428,821 bytes）；SHA-256 `51d800eac054e8432223c59cd2654366e4bc6aafbbd96185803ff035e45bbc9f`；update.json rollout=100；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.13-qa.2 |
| 后续 | 验收通过后恢复 policy 100/0.1.0；强制演练见 §24；再发正式稳定版 |

本版本内容：与 qa.1/beta 同功能，仅版本号提升；stable latest 保持 v0.1.12。

## 24. 2026-10-28 v0.1.14-force.1 强制演练记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **17 / 0.1.14-force.1** |
| 类型 | prerelease（Beta 通道可见，高于 qa.2） |
| 发布政策 | `minSupportedVersion=0.1.14`、`rolloutPercent=100` |
| 用途 | 强制更新边界：当前 < 0.1.14 → 弹「需要更新」不可取消；安装后解除 |
| 产物 | `zhibox-0.1.14-force.1-581491b-release-signed.apk`（70,428,821 bytes）；SHA-256 `b97454dacc1c48be7c1d7b44364f2a22af6917f1d71ba095a57daa61a7106ea4`；update.json minSupported=0.1.14；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.14-force.1 |
| 后续 | 验收发现两处重启行为问题（缓存丢失 forced / 历史 FAILED 回放）；修复见 §25 正式版 |

本版本：与 qa.2 同功能，仅版本号提升；stable latest 保持 v0.1.12。

## 25. 2026-10-28 v0.1.14 正式稳定版记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **18 / 0.1.14** |
| 基线提交 | `4ff01e7`（tag `v0.1.14`） |
| 数据库 schema | v9（可覆盖升级 v0.1.12 / QA/force 包） |
| 签名 | 沿用既有 release keystore（同证书，可覆盖升级） |
| 发布政策 | `release/update-policy.json`（rollout 100%，minSupported 0.1.0） |
| 产物 | `zhibox-0.1.14-4ff01e7-release-signed.apk`（70,428,828 bytes）；SHA-256 `9e3028219d3bcd9c5f923fa69b3d0cb723e32834bcb1ce9c60d3e6c236342422`；`.sha256`、mapping、`zhibox-0.1.14-4ff01e7-update.json`（channel=stable、rollout 100、minSupported 0.1.0）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.14 |
| 内容 | F-03 修复：冷启动缓存保留 forced；历史 WorkManager FAILED/CANCELLED 不再回放误弹更新失败；清理 QA/force 临时 Release |

本版本为正式稳定版；已清理 `v0.1.13-qa.1`、`v0.1.13-qa.2`、`v0.1.14-force.1` 临时 Release/tag；
`v0.1.13-beta.1` 作为 Beta 验收记录保留（stable 高于它，Beta 用户会收到 v0.1.14）。
下一步：用 v0.1.14 作为基线 + 新的强制目标包复测 F-03（重启后仍强制、不可取消、无历史失败回放）。

## 26. 2026-10-28 v0.1.15-force.1 F-03 复测记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **19 / 0.1.15-force.1** |
| 类型 | prerelease（Beta 通道可见） |
| 基线 | v0.1.14（含强制缓存 forced / 历史 FAILED 回放修复） |
| 发布政策 | `minSupportedVersion=0.1.15`、`rolloutPercent=100` |
| 复测点 | 3 次失败→暂时退出→重启：仍强制且不可取消；无历史「更新失败」回放 |
| 产物 | `zhibox-0.1.15-force.1-443f5f4-release-signed.apk`（70,428,822 bytes）；SHA-256 `e1efeceef47f1f5ebb60b6d52c53c894f843fcab1b927bdfd22429cf9395e18c`；update.json minSupported=0.1.15；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.15-force.1 |
| 复测结果 | 暴露缓存丢失 minSupportedVersion/rolloutPercent 的更深层根因（v0.1.14 修复不完整）；本包不作最终验收，修复见 §27 |
| 后续 | v0.1.15 修复后重发新强制目标复测；删除本 Release/tag |

## 27. 2026-10-29 v0.1.15 正式稳定版记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **20 / 0.1.15** |
| 基线提交 | `038699a`（tag `v0.1.15`） |
| 数据库 schema | v9（可覆盖升级 v0.1.14） |
| 签名 | 沿用既有 release keystore（可覆盖升级） |
| 发布政策 | `release/update-policy.json`（rollout 100%，minSupported 0.1.0） |
| 产物 | `zhibox-0.1.15-038699a-release-signed.apk`（70,428,828 bytes）；SHA-256 `13415073d98c2fbdee335c7bba9339321dda0fa37d06bfb7c2d0d86040d79909`；`.sha256`、mapping、`zhibox-0.1.15-038699a-update.json`（rollout 100 / minSupported 0.1.0）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.15 |
| 修复 | 缓存持久化补齐 minSupportedVersion/rolloutPercent/channel；冷启动重新判定 forced/灰度；旧缓存无元数据时联网刷新 |

F-03 最终验收需用 v0.1.15 作为基线 + 新强制目标包复测；完成后清理临时 Release。

## 28. 2026-10-29 v0.1.16-force.1 F-03 最终复测记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **21 / 0.1.16-force.1** |
| 类型 | prerelease（Beta 通道可见） |
| 基线 | v0.1.15（缓存元数据/冷启动灰度强制修复） |
| 发布政策 | `minSupportedVersion=0.1.16`、`rolloutPercent=100` |
| 复测点 | 3 次失败→暂时退出→重启：仍强制不可取消；无历史更新失败；缓存元数据不丢 |
| 产物 | `zhibox-0.1.16-force.1-70d08ab-release-signed.apk`（70,428,827 bytes）；SHA-256 `368cd3511b9693c91d47dee3de259f928ca99ff134cee4cea2137a5b428bda61`；update.json minSupported=0.1.16；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.16-force.1 |
| 结果 | **2026-10-29 人工验收通过**：重启后仍强制且不可取消，无「更新失败」 |
| 后续 | 已删除本临时 Release/tag；policy 恢复 100/0.1.0 |

## 29. 2026-10-29 收尾：内测验收结论

- **通过项**：普通更新、Beta 通道、灰度 0%/100%、强制更新、F-03 失败逃生/重启行为；
- **最终稳定版**：**v0.1.15（versionCode 20）**，latest stable；修复缓存元数据丢失与冷启动灰度/强制判定；
- **清理**：已删除 `v0.1.13-qa.1`、`v0.1.13-qa.2`、`v0.1.14-force.1`、`v0.1.15-force.1`、`v0.1.16-force.1` 临时 Release/tag；Beta 临时包 `v0.1.13-beta.1` 一并删除；
- **策略**：`release/update-policy.json` 恢复为 `rolloutPercent=100`、`minSupportedVersion=0.1.0`；
- **文档**：`RELEASE_NOTES.md` 仅保留稳定版本说明；临时验收记录保留在本文件 §21~§28；
- **后续**：以 v0.1.15 作为正式基线，后续发版走正常 `release/update-policy.json` 流程。

## 30. 2026-10-29 v0.1.16 稳定版记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **22 / 0.1.16** |
| 基线提交 | `0ea927d`（tag `v0.1.16`） |
| 数据库 schema | v9（可覆盖升级 v0.1.15） |
| 签名 | 沿用既有 release keystore（可覆盖升级） |
| 发布政策 | `release/update-policy.json`（rollout 100%，minSupported 0.1.0） |
| 修复 | 强制缓存后台校验；force Release 删除后清缓存/关闭弹窗；下载 404/410 视为版本下架并清缓存；强制缓存 24h 过期先校验 |
| 产物 | `zhibox-0.1.16-0ea927d-release-signed.apk`（70,429,284 bytes）；SHA-256 `8cb6985a36daba2ec3039c317167b6c75599bb1c5425570a25c625a8821b59e4`；`.sha256`、mapping、`zhibox-0.1.16-0ea927d-update.json`（rollout 100 / minSupported 0.1.0）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.16 |

本版本解决「删除 force Release 后，客户端仍按缓存强制、点击更新 404 死循环」。

## 31. 2026-10-29 v0.1.17-force.1 强制缓存失效复测记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **23 / 0.1.17-force.1** |
| 类型 | prerelease（Beta 通道可见） |
| 基线 | v0.1.16（强制缓存后台校验 + 404/410 下架清理） |
| 发布政策 | `minSupportedVersion=0.1.17`、`rolloutPercent=100` |
| 复测流程 | 强制→断网失败 3 次→暂时退出→删除本 Release→关飞行→重启（2026-10-29 已手动删除并验证通过） |
| 预期 | 重启后后台校验发现无该版本→清缓存/关闭弹窗；或点更新得到可关闭「版本已下架」 |
| 产物 | tag 推送后 Release workflow 构建；发布后回填 |
| 结果 | 用户手动删除 Release 后验证通过；临时 tag 已清理，policy 恢复 100/0.1.0 |

## 32. 2026-10-29 v0.1.17-beta.1 内测 Beta 记录

| 项 | 值 |
|---|---|
| versionCode / versionName | **24 / 0.1.17-beta.1** |
| 类型 | prerelease（Beta 通道；stable latest 仍 v0.1.16） |
| 基线 | v0.1.16 + 更新服务收尾修复（提交 `5f598bb`） |
| 发布政策 | rollout 100%、minSupported 0.1.0 |
| 修复 | 灰度缓存 6h 刷新；自动检查关闭不联网；线路 404/5xx 回退；5min 超时；P2 清理/重定向校验/防重入/下架节流重置/多资产失败安全 |
| 产物 | `zhibox-0.1.17-beta.1-5f598bb-release-signed.apk`（70,445,684 bytes）；SHA-256 `e14e45c92c95ddf191338e70cfa1cbeaf18ed23662b0908500b73b178ea276be`；`.sha256`、mapping、`zhibox-0.1.17-beta.1-5f598bb-update.json`（beta、rollout 100、minSupported 0.1.0）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.17-beta.1 |
| 后续 | 内测验收通过后发布正式 v0.1.17（versionCode 25），清理临时包 |

## 33. 2026-10-29 v0.1.17-beta.2 内测 Beta 记录（下载不可卡死）

| 项 | 值 |
|---|---|
| versionCode / versionName | **25 / 0.1.17-beta.2** |
| 类型 | prerelease（Beta 通道；stable latest 仍 v0.1.16） |
| 基线 | v0.1.17-beta.1 + 下载卡死保护（提交 `4c2a56c`） |
| 修复 | 移除 WorkManager Network/Storage 约束；Worker 网络预检；WAITING + 20s 看门狗；混合线路失败不误清缓存 |
| 发布政策 | rollout 100%、minSupported 0.1.0 |
| 产物 | `zhibox-0.1.17-beta.2-4c2a56c-release-signed.apk`（70,445,685 bytes）；SHA-256 `42cd1e3448f5d95041f366fb587774900b3999993c987a3a8c92edb51d91bd67`；`.sha256`、mapping、`zhibox-0.1.17-beta.2-4c2a56c-update.json`（beta、100%、0.1.0）；https://github.com/IAmKings/ZhiBox/releases/tag/v0.1.17-beta.2 |
| 后续 | 内测验收通过后发布正式 v0.1.17，清理 beta 临时包 |

## 34. 2026-10-29 v0.1.17-beta.3 内测 Beta 记录（第三方审查 8 项修复）

| 项 | 值 |
|---|---|
| versionCode / versionName | **26 / 0.1.17-beta.3** |
| 类型 | prerelease（Beta 通道；stable latest 仍 v0.1.16） |
| 基线 | v0.1.17-beta.2 + 第三方审查修复 |
| 修复 | 异步入队结果/看门狗、WorkRequest ID 归属、冷启动状态保护、半包续传、成功结果去重、SHA 混合错误分类、元数据坏内容换镜像、Worker 后台完成通知 |
| 发布政策 | rollout 100%、minSupported 0.1.0 |
| 产物 | Release workflow 构建；发布后回填 |
| 后续 | 内测验收通过后发布正式 v0.1.17，清理 beta 临时包 |
