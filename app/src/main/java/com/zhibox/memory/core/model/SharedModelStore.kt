package com.zhibox.memory.core.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 共享模型目录（W4 模型商店）：SAF 文档树承载的"卸载不丢"模型冷存储。
 *
 * 口径：
 * - 共享目录（SAF）= 持久仓：卸载不删，重装后授权失效但文件仍在，重选一次目录即恢复；
 * - `cacheDir/models-link` = 运行缓存：从共享仓"启用"时复制而来，是真实本地 File，
 *   PathPreferences.candidates 把它与 filesDir/models 一并纳入（启用即用，删除即失效）；
 * - 下载经 cacheDir 中转：复用 ModelDownloader（断点续传+流式 SHA-256），校验通过后
 *   copy 进 SAF（SAF 流不支持随机读写，续传只能在本地文件系统做）再清临时；
 * - 无授权/失效时本类全部只读操作返回空/失败，PathPreferences 回落本地行为，互不影响。
 *
 * P1-7 / P1-10 加固：SAF 目录可被用户/其它应用写入，**"同名 + 等长"不代表完整可信**。
 * - `enable` 复用运行缓存前必须过 ModelIntegrity（catalog 已知时 SHA-256 + 精确尺寸）；
 * - `downloadEntry` 跳过下载前同样重新计算 SHA-256；
 * - `clearAll` 只删 `PathPreferences.isKnownModel()` 命中的文件（此前会删目录内所有 .litertlm/.task）；
 * - `setTreeUri` 捕获 `takePersistableUriPermission` 的 SecurityException（部分 OEM/云盘不支持持久授权）。
 */
object SharedModelStore {

    private const val PREFS = "shared_model_store"
    private const val KEY_TREE_URI = "tree_uri"

    // ---- 授权状态 ----

    enum class State { NOT_CONFIGURED, CONNECTED, PERMISSION_LOST }

    fun state(context: Context): State {
        val uri = treeUri(context) ?: return State.NOT_CONFIGURED
        return if (hasPermission(context, uri)) State.CONNECTED else State.PERMISSION_LOST
    }

    fun treeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /**
     * 设置页发起 OPEN_DOCUMENT_TREE 后回传，持久化授权。
     *
     * @return true = 授权已持久化；false = provider 拒绝持久授权（SecurityException，云盘/OEM 常见），
     *         此时**不写偏好**，避免下次启动显示"已连接"但实际无权限的假状态。
     */
    fun setTreeUri(context: Context, uri: Uri): Boolean {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return replaceTreeUri(
            old = treeUri(context),
            new = uri,
            hasNewPermission = hasPermission(context, uri),
            acquire = { target ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(target, flags)
                }.isSuccess
            },
            persist = { target ->
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_TREE_URI, target.toString()).commit()
            },
            release = { target ->
                context.contentResolver.releasePersistableUriPermission(
                    target,
                    flags,
                )
            },
        )
    }

    /** Acquire → persist → release keeps the old directory usable on every failure path. */
    internal fun replaceTreeUri(
        old: Uri?,
        new: Uri,
        hasNewPermission: Boolean,
        acquire: (Uri) -> Boolean,
        persist: (Uri) -> Boolean,
        release: (Uri) -> Unit,
    ): Boolean {
        if (old == new && hasNewPermission) return true
        if (!hasNewPermission && !acquire(new)) return false
        if (!persist(new)) {
            if (!hasNewPermission && old != new) runCatching { release(new) }
            return false
        }
        if (old != null && old != new) runCatching { release(old) }
        return true
    }

    private fun hasPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    private fun root(context: Context): DocumentFile? {
        val uri = treeUri(context) ?: return null
        if (!hasPermission(context, uri)) return null
        return runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    }

    private fun linkCache(context: Context): File = File(context.cacheDir, "models-link")

    // ---- 枚举 ----

    /** 共享目录中的已知模型文件名（同步核心；IO 自担，调用方避免主线程） */
    fun listNamesSync(context: Context): List<String> {
        val r = root(context) ?: return emptyList()
        return runCatching {
            r.listFiles().mapNotNull { it.name }
                .filter { PathPreferences.isKnownModelFile(it) }
                .sorted()
        }.getOrDefault(emptyList())
    }

    suspend fun listNames(context: Context): List<String> =
        withContext(Dispatchers.IO) { listNamesSync(context) }

    /** 目录内实际存在的 catalog entries（共享仓 + 本地仓合并口径，推荐位"已下载"判定） */
    fun presentEntries(context: Context): List<ModelCatalog.Entry> {
        val sharedNames = if (state(context) == State.CONNECTED) listNamesSync(context).toSet() else emptySet()
        val localNames = File(context.filesDir, "models").listFiles()?.map { it.name }?.toSet().orEmpty()
        return ModelCatalog.ENTRIES.filter { it.fileName in sharedNames || it.fileName in localNames }
    }

    // ---- 启用（共享仓 → 运行缓存） ----

    /**
     * 把共享目录中的模型复制进运行缓存（models-link），返回可直接加载的本地 File。
     * 已有同名副本时**先做完整性校验再复用**（"同名等长"不可信——SAF 目录内容可被替换）；
     * 校验失败删除副本重新复制，复制后再次校验（共享仓源文件本身也可能被替换）。
     */
    suspend fun enable(context: Context, fileName: String): File? = withContext(Dispatchers.IO) {
        val r = root(context) ?: return@withContext null
        val doc = runCatching { r.listFiles().firstOrNull { it.name == fileName } }.getOrNull()
            ?: return@withContext null
        val entry = ModelCatalog.byFileName(fileName)
        val cache = File(linkCache(context).apply { mkdirs() }, fileName)

        if (cache.isFile && cache.length() == doc.length() && verifyLocal(cache, entry)) {
            return@withContext cache
        }
        cache.delete()

        val input = context.contentResolver.openInputStream(doc.uri) ?: return@withContext null
        input.use { ins ->
            cache.outputStream().use { outs -> ins.copyTo(outs) }
        }
        if (cache.length() != doc.length() || cache.length() == 0L) {
            cache.delete()
            return@withContext null
        }
        if (!verifyLocal(cache, entry)) {
            cache.delete()
            return@withContext null
        }
        cache
    }

    /** 本地副本完整性：catalog 已知 → SHA-256 + 精确尺寸；未收录模型 → 只要求非空（长度已由调用方比对） */
    private suspend fun verifyLocal(file: File, entry: ModelCatalog.Entry?): Boolean {
        if (entry == null) return file.isFile && file.length() > 0
        return ModelIntegrity.verify(file, entry.sha256, entry.sizeBytes).isSuccess
    }

    /** 运行缓存中的模型 File（供设置页显示/删除） */
    fun linkedFiles(context: Context): List<File> =
        linkCache(context).listFiles { f -> f.isFile && PathPreferences.isKnownModelFile(f.name) }
            ?.sortedBy { it.name } ?: emptyList()

    /** 删除共享目录中的模型（同时清理运行缓存副本） */
    suspend fun delete(context: Context, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val r = root(context) ?: return@withContext false
        val doc = runCatching { r.listFiles().firstOrNull { it.name == fileName } }.getOrNull()
        val removed = doc?.delete() == true
        File(linkCache(context), fileName).delete()
        removed
    }

    /** 删除运行缓存副本（共享仓保留，随时可再启用） */
    fun unlink(context: Context, fileName: String): Boolean =
        File(linkCache(context), fileName).delete()

    /**
     * 清空共享目录全部模型 + 运行缓存（体积大，须用户显式确认后调用）。
     * P1-10：**只删显式名单内的模型文件**（PathPreferences.isKnownModelFile）——SAF 目录可能被用户
     * 放了其它文件，旧实现按扩展名全删属于越界删除。
     */
    suspend fun clearAll(context: Context): Int = withContext(Dispatchers.IO) {
        val r = root(context) ?: return@withContext 0
        var n = 0
        runCatching {
            r.listFiles().forEach { doc ->
                val name = doc.name ?: return@forEach
                if (PathPreferences.isKnownModelFile(name) && doc.delete()) n++
            }
        }
        linkCache(context).deleteRecursively()
        n
    }

    // ---- 下载（cacheDir 中转 → SAF） ----

    data class DownloadProgress(val downloaded: Long, val total: Long)

    /**
     * 下载 catalog entry 到共享目录：
     * ModelDownloader（断点+流式 SHA）→ cacheDir 临时 → 校验通过 → SAF copy → 清临时。
     * 共享目录已有同名等长文件时**先重新校验 SHA-256 再跳过**（P1-7：同名等长不可信）。
     */
    suspend fun downloadEntry(
        context: Context,
        entry: ModelCatalog.Entry,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val r = root(context) ?: error("共享目录未连接（先选择目录）")

            fun findExisting(): DocumentFile? = runCatching {
                r.listFiles().firstOrNull { it.name == entry.fileName }
            }.getOrNull()

            val existing = findExisting()
            if (existing != null && existing.length() == entry.sizeBytes && verifyShared(context, existing.uri, entry)) {
                return@runCatching
            }
            if (existing != null) {
                // 残缺/被替换/校验失败：先删掉，避免后续 createFile 生成 "xxx (1)" 假文件
                runCatching { existing.delete() }
            }

            // 磁盘预检：entry 实际体积 + 512MB 余量（ModelDownloader 内另有 600MB 固定预检兜底）
            val stat = android.os.StatFs(context.cacheDir.absolutePath)
            val need = entry.sizeBytes + 512L * 1024 * 1024
            check(stat.availableBytes >= need) {
                "存储空间不足：需要 ≥${need / 1024 / 1024}MB，可用 ${stat.availableBytes / 1024 / 1024}MB"
            }
            val staging = File(context.cacheDir, entry.fileName)
            try {
                ModelDownloader.download(
                    url = entry.url,
                    target = staging,
                    expectedSha256 = entry.sha256,
                ) { done, total -> onProgress(DownloadProgress(done, if (total > 0) total else entry.sizeBytes)) }

                // 下载成功后再清一次同名旧文件（下载期间可能被并发创建）
                findExisting()?.let { stale ->
                    check(runCatching { stale.delete() }.getOrDefault(false)) {
                        "清理共享目录旧文件失败：${entry.fileName}"
                    }
                }
                val target = r.createFile("application/octet-stream", entry.fileName)
                    ?: error("共享目录创建文件失败")
                context.contentResolver.openOutputStream(target.uri)?.use { out ->
                    staging.inputStream().use { it.copyTo(out) }
                } ?: error("共享目录写入失败")
                check(target.length() == entry.sizeBytes) {
                    "共享目录写入不完整（${target.length()}/${entry.sizeBytes}）"
                }
            } finally {
                staging.delete()
            }
            File(linkCache(context), entry.fileName).delete() // 旧缓存作废
        }
    }

    /** 对 SAF 文件流做 SHA-256 校验（拿不到可随机读的 File，只能开 InputStream） */
    private suspend fun verifyShared(context: Context, uri: Uri, entry: ModelCatalog.Entry): Boolean {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return false
        return input.use { ModelIntegrity.verifyStream(it, entry.sha256).isSuccess }
    }
}
