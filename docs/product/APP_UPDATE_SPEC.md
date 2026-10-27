# APP_UPDATE_SPEC —— 应用内更新服务（全局）

> 状态：2026-10-27 实施；托管方式：**GitHub Release + 公开仓库**（匿名 API）。
> 本文档是 `core/update` 与 `ui/update` 的行为契约；验收证据见文末 §9。

## 0. 目标与边界

- 冷启动（每进程一次）自动查询 GitHub 最新 Release，**24h 节流**；
- 有新版本时弹出符合 `DESIGN.md` 的全局更新框：展示版本/大小/日期/更新内容；
- 用户确认后下载 APK 并展示进度；**下载/校验期间弹窗不可取消**；
- 下载完成 → SHA-256 + 包名/versionCode/签名校验 → **自动拉起系统安装器**；
- 设置 → 隐私与关于 → 「检查更新」：**取消在途的启动检查并重新生成任务**；
- 只支持 stable release（GitHub `releases/latest`，天然排除 draft/prerelease）。

非目标：后台/WorkManager 下载、通知栏进度、增量更新、灰度/强制更新、自动检查开关、预发布通道。

## 1. 托管与网络

| 项 | 值 |
|---|---|
| 版本源 | `GET https://api.github.com/repos/IAmKings/ZhiBox/releases/latest` |
| 认证 | **无**（要求仓库 public） |
| APK 资产 | `zhibox-<version>-<sha7>-release-signed.apk`（`release.yml` 产物） |
| 校验值 | 资产 JSON `digest: "sha256:<hex>"`；为空时回退 `<apk>.sha256` 资产 |
| 下载 | `browser_download_url`（github.com）→ 手动跟随重定向（objects.githubusercontent.com / release-assets.githubusercontent.com）；直连 CDN 在部分网络不可达时按 `UPDATE_DOWNLOAD_MIRRORS`（默认 `ghproxy.net`、`gh-proxy.com`）回退 |
| 传输 | 只允许 HTTPS；`network_security_config` 禁明文 |
| 隐私 | 只发送 API 请求（IP/User-Agent），不发送知识库/个人数据；不参与知识库备份 |

请求头：`Accept: application/vnd.github+json`、`X-GitHub-Api-Version: 2022-11-28`、
`User-Agent: ZhiBox/<versionName> (+https://github.com/IAmKings/ZhiBox)`。

## 2. 版本与资产解析

- `tag_name` 形如 `v0.1.6`；`parseAppVersion` 去 `v`、按 `.` 取数字段、忽略 `-rc1`/`+build` 后缀；
- 远端版本与 `BuildConfig.VERSION_NAME` 比较；**只提示严格更新**（相等/更低 = 已是最新）；
- `latest` 返回 `draft/prerelease` 时防御性拒绝；
- 资产选择：名称以 `-release-signed.apk` 结尾；大小 `size` 用于展示与磁盘预检；
- 更新说明：`body` 经 markdown→纯文本（去 `#/**/\`/链接/引用`）截断 4000 字符；完整内容可跳 `html_url`。

## 3. 状态机

`UpdatePhase`：`Idle / Checking(trigger) / UpToDate(trigger, version) / Available(trigger, info) /
Downloading(info, downloaded, total) / Verifying(info) / NeedInstallPermission(info) /
CheckFailed(trigger, failure) / DownloadFailed(info, failure)`。

```
进入应用 → 节流决策
  ├ 缓存命中且更新且未被取消 → Available(AUTO)（不发网络）
  ├ 距上次尝试 <1h 或 距上次成功 <24h → Skip
  └ Check → Checking(AUTO) → Available(AUTO)（弹窗）/ Idle（无更新，静默）/ Idle（失败，静默）
设置「检查更新」（MANUAL）
  └ 取消在途 CheckJob + taskId++ → Checking(MANUAL)
       ├ Available(MANUAL) → 弹窗
       ├ UpToDate(MANUAL) → 设置行内联「已是最新」
       └ CheckFailed(MANUAL) → 设置行内联「检查失败，点击重试」
Available → 用户点「立即更新」→ Downloading → Verifying → 安装器
                                              ├ Launched → Idle
                                              ├ PermissionRequired → NeedInstallPermission → 去设置/返回自动续装
                                              └ Failed → DownloadFailed（可关闭/重试）
Available → 取消/外部点击 → Idle + dismissedVersion（自动不再提示该版本）
Downloading/Verifying：弹窗不可取消（返回键/外部点击无效，无按钮）
```

**任务代际**：`taskId` 每次检查 +1；旧任务在写状态前比对 `taskId`，过期结果（含错误）一律丢弃。
**节流持久化**：`SharedPreferences("app_update")`：`last_success_at`、`last_attempt_at`、
`dismissed_version`、缓存的最新 Release 字段。自动失败退避 1h；成功后 24h。

## 4. 下载与安装安全链

1. 初始下载 URL：`https` + host `github.com` + path `/IAmKings/ZhiBox/releases/download/…`；
2. 手动跟随重定向（≤5 跳），**每一跳**校验 `https` + host ∈
   `{github.com, objects.githubusercontent.com, release-assets.githubusercontent.com}`；
   直连失败（Network/Timeout）时按镜像前缀回退（host 必须在镜像白名单内、URL 必须包含本仓库
   release 资产路径）；镜像只看到公开 APK，SHA-256 + 签名校验兜底；
3. `.part` 续传：仅接受 `206 + Content-Range 起点一致`；否则丢弃半包重下；
4. 磁盘预检 `apkSize*2 + 50MB`；整体超时 10 分钟；connect 10s / read 30s；
5. 整文件流式 SHA-256 校验（API digest 优先，`.sha256` 资产回退）；失败删除 `.part`；
6. 校验通过后原子 rename 到 `cacheDir/updates/zhibox-<version>.apk`；
7. `ApkValidator`：包名必须是 `com.zhibox.memory`、`versionCode > 当前`、签名证书 SHA-256 与已安装应用一致；
8. `FileProvider`（authority `${applicationId}.updates`，仅暴露 `cacheDir/updates`）+
   `ACTION_VIEW` + MIME `application/vnd.android.package-archive`；
9. Android 8+ 若 `canRequestPackageInstalls() == false` → `NeedInstallPermission`，
   「去设置」打开 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`；`ON_RESUME` 检测已授权后自动继续安装。

## 5. UI 契约

- 全局弹窗挂载于 `MainActivity` 根部 `Box`，覆盖所有屏；`AlertDialog` + `ZhiBoxRadius.card`（16dp）+
  `zhiBoxColors.card`，默认轻阴影（≤8dp），不与描边叠加（DESIGN Flat Surfaces）。
- 取消规则：`Available / DownloadFailed / NeedInstallPermission` 可点外部/返回键关闭；
  `Downloading / Verifying` 不可取消。
- 设置行：`ZhiBoxCard` + 整行 48dp 可点；状态内联：
  当前版本 / 正在检查 / 已是最新 / 发现新版本 / 正在下载 N% / 正在校验 / 等待权限 / 检查失败 / 下载失败。
  下载/校验中行不可点；其余点击 = `manager.checkNow()`（取消旧任务 + 新任务）。
- 所有文案走 `values/strings.xml`（`update_*`），无硬编码用户文案。

## 6. 代码结构

```
core/update/
  UpdateModels.kt    版本比较/UpdateInfo/失败模型/状态/Release JSON 解析/markdown 清洗/配置
  UpdateApi.kt       UpdateApi + HttpUpdateApi（匿名 GitHub API）
  UpdateDownloader.kt UpdateDownloader + HttpUpdateDownloader（白名单/重定向/续传/SHA）
  UpdateInstaller.kt ApkValidator + AndroidApkValidator + UpdateInstaller + AndroidUpdateInstaller
  UpdatePreferences.kt UpdateStore + SharedPrefsUpdateStore
  UpdatePolicy.kt    节流/缓存/取消决策（纯函数）
  AppUpdateManager.kt 全局单例 + 状态机 + 代际任务 + 下载/安装编排
ui/update/AppUpdateDialog.kt  全局弹窗 + 设置行 + 失败文案映射
MainActivity.kt             根 Box 挂载弹窗、生命周期、onAppEntered
SettingsScreen/SettingsPageContent  隐私页新增「检查更新」行
```

## 7. 隐私与网络路径（第 4 条）

自动检查在冷启动时产生一次 `api.github.com` 请求（24h 内不重复）；下载仅在用户确认后发生。
`settings_privacy_text`、`AndroidManifest.xml` 网络注释、`network_security_config.xml` 注释、
README 网络说明均已同步。**不**受 LLM「禁止网络」开关约束（更新属于应用维护路径）。

## 8. 测试与门禁

- 纯 JVM：版本比较、Release 解析（digest/.sha256/缺资产）、节流策略、URL 白名单、manager 代际取消。
- Mock HTTPS（复用 `TestTlsSupport.MockHttpsServer`）：API 200/404/403/坏 JSON；下载进度/续传/SHA 不符。
- Robolectric：弹窗各 phase 文案/按钮/进度语义；设置行状态。
- 真机：冷启动无更新不打扰、手动检查、debug mock 弹窗走查（浅/深色、200% 字号、TalkBack）。
- 端到端跨版本安装需连续两个带更新服务的版本（v0.1.6 → v0.1.7）；v0.1.6 发布后由下一版本验证。

## 9. 验收清单（对应 PRD 需求）

- [x] 进入应用自动查询版本信息（24h 节流）。
- [x] 有新版本弹 DESIGN 规范弹窗，展示更新信息。
- [x] 确认后下载并展示进度；下载/校验期间弹窗不可取消。
- [x] 取消/外部空白可关闭（下载开始前）。
- [x] 设置「检查更新」项：取消在途启动检查并重新生成任务。
- [x] 下载完成自动进入系统安装（含未知来源权限引导）。
- [x] SHA-256 + 包名/versionCode/签名校验。
- [x] 公开仓库后匿名 API/资产可访问；隐私说明与文档同步。

### 9.1 真机验证（Redmi 23078PND5G / Android 14，2026-10-27）

| 项 | 结果 |
|---|---|
| 冷启动自动检查（公开仓库，当前=v0.1.5） | ✅ 无更新不弹窗；`shared_prefs/app_update.xml` 写入 `last_success_at`/`last_attempt_at` |
| 设置行状态（隐私与关于页） | ✅ 显示「检查更新 / 已是最新版本（0.1.5）」 |
| 手动检查 | ✅ 点击后重新查询并刷新时间戳（24h 节流被绕过） |
| 更新弹窗（debug mock 注入） | ✅ 版本行/大小/日期/更新内容/「查看完整说明」/「稍后」「立即更新」；截图 `impl-update-dialog-available.png` |
| 取消/返回键关闭（可用态） | ✅ 返回键关闭弹窗回首页 |
| 下载中不可取消 | ✅ `downloading` mock 下返回键无效、无操作按钮；截图 `impl-update-dialog-downloading.png` |
| 失败/权限态 | ✅ `failed`（关闭/重试）、`permission`（取消/去设置）；截图 `impl-update-dialog-failed/permission.png` |
| 设置行截图 | ✅ `impl-update-settings-row.png` |

> debug mock 通过 `autostart_update_mock=available|downloading|failed|permission` 注入（仅 debuggable 构建读取）。
>
> **真实下载链路（2026-10-27 追加）**：国内网络直连 `objects.githubusercontent.com` 30s 读超时；
> 回退镜像 `gh-proxy.com` 后 67MB APK 在 <60s 内下载完成并通过 SHA-256 校验，随后被
> `ApkValidator` 以「签名不一致」拒绝安装（测试机安装的是 debug 签名包，下载的是 release 签名包）——
> 这正是预期的安全拦截。release 签名设备上的完整安装链路待 v0.1.7 发布时从 v0.1.6 走查。
> 镜像顺序可通过 `BuildConfig.UPDATE_DOWNLOAD_MIRRORS` 调整（默认 `gh-proxy.com`、`ghfast.top`）。
