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

已实现（v0.1.7）：WorkManager 后台下载 + 通知栏进度 + 跨进程续传、自动检查开关、
预发布（Beta）通道、灰度分桶、强制更新（`minSupportedVersion`）、下载线路健康监控。
不实现：增量更新（bsdiff，APK 签名约束，见 §10）。
跨进程续传范围：`cacheDir/updates/*.part` + WorkManager 重调度。

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

> 内测发布的人工验收流程（普通/灰度/强制/Beta/网络降级）见
> [`APP_UPDATE_MANUAL_QA.md`](APP_UPDATE_MANUAL_QA.md)。

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


### 9.2 二期真机验收（v0.1.7，2026-10-27）

| 项 | 结果 |
|---|---|
| release→release 完整升级 | ✅ 设备安装 v0.1.6 release → 自动发现 v0.1.7 → 镜像下载 67MB → SHA-256 → 系统安装器 → **v0.1.7 (versionCode 8) 安装成功** |
| 设置搜索 | ✅ 顶栏入口、输入 GPU 命中「GPU 加速（高级与诊断）」、点击跳转并定位；截图 `impl-settings-search.png` / `impl-settings-search-target.png` |
| 更新设置开关 | ✅ 自动检查 / Beta 两开关展示；截图 `impl-update-settings.png` |
| 后台下载 + 通知 | ✅ 临时 debug 版本：WorkManager 任务、`POST_NOTIFICATIONS` 授权、`app_update` 渠道进度通知（0%→…），退出应用后继续 |
| 跨进程续传 | ✅ 下载中 `force-stop` 后重新进入：`.part` 从 2,179,072 B 继续增长到 4,001,454 B（下载态/通知恢复），最终完成 |
| 后台完成 → 通知安装 | ✅ 完成后发「更新已下载 / 点击安装 0.1.8-test2」通知；点通知进入应用并拉起系统安装器（0.1.7→0.1.8） |
| 临时 tiny APK 安装 | ⚠️ 手工裁剪的小体积测试 APK 被 MIUI 判定「与系统不兼容」（缺 native 库/dx 的制品问题，非更新逻辑）；installer 已正确显示 0.1.7→0.1.8 |

> 临时测试 Release 与工作流已删除；正式 Release 仍为 v0.1.7（latest）。


### 9.3 Android 17 真机验收（OPPO PJZ110 / Android 17，2026-10-28）

| 项 | 结果 |
|---|---|
| v0.1.6 → v0.1.7 真实升级 | ✅ 手动检查发现 v0.1.7；下载 67MB → SHA-256 → 系统安装器；Android 17 首次需「安装未知应用」+ OPPO 风险确认（勾选已知悉风险）→ versionCode 8 安装成功 |
| 更新设置页 | ✅ Android 17 显示自动检查/Beta 开关与检查更新行 |
| v0.1.7 → v0.1.8 后台下载 | ✅ 确认更新后请求 `POST_NOTIFICATIONS`；允许后 WorkManager 后台下载 67MB；退出应用后完成 |
| 后台完成通知 | ✅ Android 17 通知渠道 `app_update` 显示「更新已下载 / 点击安装 0.1.8」 |
| 点通知安装 | ✅ 通知 → MainActivity → 系统安装器 → OPPO 风险确认 → **v0.1.8 (versionCode 9) 安装成功** |
| 设置搜索 | ✅ Android 17 输入 GPU 命中「GPU 加速（高级与诊断）」；截图 `impl-settings-search-android17.png` |
| 备注 | 本次 Android 17 网络直连 GitHub CDN 可用，未触发镜像回退与续传分支（续传在 Android 14 已验证） |

> v0.1.8 的「直连失败回退镜像保留 `.part` 续传」修复未在 Android 17 网络复现触发条件，由 Android 14 真机与单测覆盖。


### 9.4 二期 Beta / 灰度演练（Android 17，2026-10-28）

环境：OPPO PJZ110 / Android 17，客户端 v0.1.9（含 update.json 重定向修复）。

| 项 | 结果 |
|---|---|
| stable 通道看不到 prerelease | ✅ 手动检查显示「已是最新版本（0.1.9）」；`v0.1.10-beta.1` 未出现 |
| Beta 通道可见 prerelease | ✅ 打开「接收预发布版本（Beta）」后手动检查弹出「当前 0.1.9 → 0.1.10-beta.1」；截图 `impl-drill-beta-android17.png` |
| 关闭 Beta 恢复 stable | ✅ 再次检查回到「已是最新版本（0.1.9）」 |
| 灰度 0% 不弹窗 | ✅ `update.json rolloutPercent=0`：设置行显示「新版本 0.1.10-drill 正在分批推送（0%）」，无弹窗；截图 `impl-drill-rollout-pending-android17.png` |
| 灰度 100% 可更新 | ✅ 新建 `v0.1.11-drill`（rollout=100）后正常弹出更新框（0.1.9 → 0.1.11-drill）；截图 `impl-drill-rollout-available-android17.png` |
| 强制更新 | 未在真机演练（避免锁死测试机）；`UpdateRollout.isForced` + 强制弹窗分支由单测覆盖 |
| 通知被拒兜底 | v0.1.9 新增 `onAppResumed` 自动续装；单测覆盖。真机完整验证需下一个更高 versionCode 的正式包（drill APK 复用 v0.1.9，安装器版本校验不通过） |

> **运维经验**：`gh release upload --clobber` 覆盖同名 `update.json` 后，部分 CDN 边缘会短暂
> 返回旧内容（实测设备继续读到 rollout 0）。调整灰度建议新建 Release/资产，或等待缓存过期
> 再验证；不要在覆盖后立刻断言失败。
> 演练用临时 Release/工作流已删除，latest 恢复 v0.1.9。


### 9.5 强制更新 + 通知被拒真机演练（Redmi K60U / Android 14，2026-10-28）

环境：Redmi K60U（23078PND5G）/ Android 14；基础包 debug v0.1.11-beta.1（versionCode 12），
目标临时 Release v0.1.12-beta.1（versionCode 13，minSupported=0.1.12，rollout=100）。

| 项 | 结果 |
|---|---|
| 受限网络下元数据 | ✅ 直连 GitHub 资产超时，`update.json` 走镜像回退成功读取 minSupported（修复前会静默回退默认值） |
| 强制弹窗 | ✅ 标题「需要更新」+ 重要修复提示；无「稍后」；返回键/点外部不关闭 |
| 通知权限拒绝 | ✅ 点「立即更新」后系统通知权限选择「拒绝」；后台下载继续，无进度/完成通知 |
| 后台下载完成 | ✅ 下载完成且 `cache/updates/*.apk` 落盘校验通过；通知数量 0 |
| 回到前台自动安装 | ✅ 重新进入应用触发 `onAppResumed` → 自动校验并拉起系统安装器（无需点通知） |
| 安装结果 | ✅ 系统安装器完成 0.1.11-beta.1 → 0.1.12-beta.1（versionCode 13）安装 |

> 演练用临时 Release/工作流已删除；本轮同时验证了「强制更新不可取消」与「通知被拒时的
> 前台续装」两条此前只有单测的链路。强制失败 3 次后的「暂时退出」仍由单测覆盖。
> 测试用目标 APK 为 8.6KB 最小制品（仅验证安装器链路），非真实功能包。


### 9.6 v0.1.12 自更新误弹失败修复验证（2026-10-28）

| 项 | 结果 |
|---|---|
| v0.1.11 → v0.1.12 前台 O-04（另一台可访问 GitHub 真机） | ✅ 无「更新失败」弹窗；安装后版本 0.1.12，检查更新为最新 |
| 根因 | ≤v0.1.11 自更新后新进程重复消费 WorkManager SUCCEEDED；ApkValidator 因目标版本已安装、versionCode 不再更大判无效 |
| 修复 | 已安装目标版本时静默清理 APK/缓存并取消旧任务；校验通过后立即 cancel；迟到失败回调静默；回归单测覆盖 |
| 说明 | K60U 受限网络下旧 bug 未稳定复现（新进程 WorkManager 初始化清理旧任务）；以单测 + 上述真机回归为准 |


### 9.7 v0.1.14 强制更新重启行为修复（2026-10-28）

F-03 验收发现两处问题，v0.1.14 修复：

| 问题 | 根因 | 修复 |
|---|---|---|
| 「暂时退出」后重启，强制弹窗变成可取消 | 冷启动 `ShowCached` 未重新计算 forced；`UpdatePolicy` 还会被 dismissed/自动检查关闭过滤 | 强制缓存优先 ShowCached；manager 展示缓存时重算 `UpdateRollout.isForced` |
| 再次重启直接弹「更新失败」 | WorkManager 持久化的历史 FAILED/CANCELLED 在进程重启后被回放，manager 用缓存信息还原为 DownloadFailed | 终端状态按进程会话过滤：本进程未跟踪下载则静默忽略并取消旧任务；同会话失败正常提示；处理完取消唯一任务，重试重新入队 |

回归测试：历史 FAILED 静默忽略；同会话 FAILED 仍提示；缓存强制不可取消。


### 9.8 v0.1.15 缓存元数据持久化修复（2026-10-29）

F-03 复测发现 v0.1.14 修复不完整的更深层根因：`SharedPrefsUpdateStore` 的
`saveCached/cachedInfo` 未持久化 `minSupportedVersion` / `rolloutPercent` / `channel`，
重启后缓存重建默认 `minSupported=null`、`rollout=100`，导致 forced 丢失、灰度可能被绕过。

v0.1.15 修复：
- 缓存补齐上述字段并增加 `cache_meta_schema`；旧缓存标记为未知，冷启动强制联网刷新一次；
- `UpdatePolicy` 冷启动展示缓存前重新判断 forced 与灰度资格；未命中灰度只显示分批推送；
- `AppUpdateManager` 的 ShowCached 分支同样防御性重算；
- 新增真实 SharedPreferences save→read 往返测试与旧缓存兼容测试。


## 10. 二期：后台下载 / 通知 / 跨进程续传（2026-10-27）

- 下载由 `AppUpdateDownloadWorker`（WorkManager 唯一任务 `app-update-download`）执行：
  - 约束：`NetworkType.CONNECTED` + `setRequiresStorageNotLow`；`REPLACE` 策略便于重试；
  - 前台服务：`setForeground(ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC))`，
    通知渠道 `app_update`（IMPORTANCE_DEFAULT，进度 `setOnlyAlertOnce`）；
  - 进度：Worker `setProgress(downloaded/total)` + 通知栏每秒更新；
  - 跨进程：进程被杀后 WorkManager 重新调度，`.part` Range 续传；安装包路径固定
    `cacheDir/updates/zhibox-<version>.apk`。
- 权限/清单：`POST_NOTIFICATIONS`（Android 13+ 首次下载时请求）、`FOREGROUND_SERVICE`、
  `FOREGROUND_SERVICE_DATA_SYNC`；WorkManager 的 `SystemForegroundService` 通过
  `tools:node="merge"` 声明 `foregroundServiceType="dataSync"`（Android 14+ 必需）。
- 自更新成功后的旧任务清理（v0.1.12 修复）：安装完成后新进程会再次收到 WorkManager
  的 SUCCEEDED；manager 以当前版本为真相，目标版本已安装则静默清理缓存并取消唯一任务，
  不再因 `versionCode` 不再更大而弹「更新失败」。
- 完成行为：前台 → 直接拉起安装器；后台 → 发「更新已下载，点击安装」通知，
  点击进入 MainActivity（`EXTRA_INSTALL_UPDATE`）或在下次进入应用时自动安装；
  **通知被拒/不可见时**：下载完成后 store 记录待安装 APK，用户回到前台
  （`onAppResumed`）自动校验并拉起安装器（v0.1.9 修复，此前需进程重启）。
- 后台不能直接启动 Activity（Android 限制），因此通知是后台完成的唯一自动路径；
  通知被拒时降级为「下次进入应用时提示安装」。
- 取消/失败：下载中弹窗仍不可取消；失败在弹窗内可重试。

## 11. 二期：自动检查开关 / Beta 通道 / 灰度 / 强制

### 11.1 自动检查开关
`UpdateStore.autoCheckEnabled`（默认 true）：关闭后 `UpdatePolicy.decideOnLaunch` 直接 Skip，
不联网也不展示缓存；设置 -> 隐私与关于 -> 「更新设置」切换；手动检查始终可用。

### 11.2 Beta 通道
`UpdateStore.channel = STABLE | BETA`（默认 STABLE）。
- STABLE：`releases/latest`；BETA：`releases?per_page=10` 取最高 semver（含 prerelease）。
- semver 支持预发布排序：`0.1.7-beta.2 < 0.1.7-rc.1 < 0.1.7`。
- release workflow：tag 含 `-` 时 `gh release create --prerelease`，避免 Beta 成为 stable latest。
- 切换通道会清空缓存/节流/取消记录，下次检查按新通道执行。

### 11.3 灰度（staged rollout）
- Release 资产 `*-update.json`（由 `release/update-policy.json` 生成）：
  `{schema, versionName, tagName, channel, rolloutPercent, minSupportedVersion, publishedAt, htmlUrl, apk{name,size,sha256}}`；
- 客户端读取该资产（≤64KB，失败回退 100%/非强制；**必须跟随 GitHub 资产 302 重定向**，
  v0.1.11 起直连不可达时按镜像前缀回退；
  v0.1.8 及以前因未跟随重定向导致元数据被忽略，v0.1.9 修复；v0.1.10 起追加
  cache-buster + `Cache-Control: no-cache`，避免覆盖同名资产后 CDN 返回旧内容）；旧版本无该资产 → 默认全量；
- 分桶：`cohort = sha256(installId + "|" + versionName)` 前 8 hex → `% 100`；
  `installId` 为本地随机 UUID，不上报；`eligible = rolloutPercent>=100 || cohort<rolloutPercent`；
- 未命中：不弹窗；手动检查时设置行提示「新版本正在分批推送（N%）」；
- 调整灰度：发布后 `gh release upload <tag> update.json --clobber`（文案/步骤见 RELEASE.md）。

### 11.4 强制更新
- `minSupportedVersion`：当前版本低于它 → 弹窗 `forced=true`：
  不可取消（无「稍后」、点外部/返回无效）；绕过 dismissed 与灰度；
- 下载连续失败 3 次后允许「暂时退出」，避免永久锁死；重试新下载会重新计数；
- 只应用于关键版本（由 `release/update-policy.json` 在发版前设置）。

## 12. 二期：下载线路健康监控

- `RouteHealth`（direct / mirror host）：最近成功/失败时间、连续失败、延迟、冷却期；
- 选择顺序（`UpdateRouteSelector.plan` 纯函数）：直连未冷却优先；否则镜像优先；
  镜像按「未冷却 → 连续失败少 → 延迟低 → 配置顺序」；
- 连续 2 次传输失败（Network/Timeout）→ 冷却 6h；成功清零；全部冷却仍按顺序尝试；
- 超时分档：直连 8s/20s，镜像 10s/30s，避免每次启动空等 30s；
- 诊断：高级与诊断页（技术细节开启）显示「更新线路」与「重置线路状态」；
- 无额外探测/遥测，数据全部来自真实下载结果。

## 13. 设置搜索（详见 SETTINGS_SEARCH.md）

- 设置首页顶栏搜索按钮 → 屏内搜索页；跨 4 个聚焦页 + 高级 17 项 + 关于/更新设置索引；
- 结果只导航：ADVANCED 使用现有深链锚点滚动 + 高亮；聚焦页按 item key 滚动到目标卡片；
- 索引完整性由 `SettingsSearchTest` 断言（17 个 section 全覆盖）。

## 14. 增量更新（bsdiff）— Won't do

APK v2/v3 签名覆盖整个文件；应用内对已安装 APK 应用二进制补丁后，必须以 **release 私钥**
重新签名才能通过系统签名校验，而私钥只存在于 CI Secrets，应用内没有该能力。
Play In-App Updates / App Bundle 增量通道不适用于 GitHub 侧载。
因此保留全量 APK 下载，通过镜像、Range 续传、后台下载与通知优化传输体验；
后续如改为应用商店分发再评估增量能力。
