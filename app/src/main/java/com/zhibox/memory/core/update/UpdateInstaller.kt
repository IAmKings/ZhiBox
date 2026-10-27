package com.zhibox.memory.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** APK 安装结果（manager 据此切换状态机）。 */
sealed interface InstallResult {
    /** 已拉起系统安装器（应用会进入后台）。 */
    data object Launched : InstallResult

    /** 缺少「安装未知应用」授权，需要用户去系统设置。 */
    data object PermissionRequired : InstallResult

    data class Failed(val failure: UpdateFailure) : InstallResult
}

/** APK 包名/版本号/签名校验（安装前的最后一道防线）。 */
interface ApkValidator {
    /** 通过返回 null；不通过返回对应失败原因。 */
    fun validate(apk: File): UpdateFailure?
}

/**
 * 系统安装器封装：
 * - Android 8+ 需要 `canRequestPackageInstalls()`（REQUEST_INSTALL_PACKAGES + 用户授权）；
 * - 通过 FileProvider（`${applicationId}.updates`）把 cacheDir/updates 下的 APK 交给安装器；
 * - 只允许安装本应用自身（包名/versionCode/签名在 [AndroidApkValidator] 校验）。
 */
interface UpdateInstaller {
    fun canInstallPackages(): Boolean
    fun install(apk: File): InstallResult
    fun installPermissionIntent(): Intent
}

class AndroidApkValidator(private val context: Context) : ApkValidator {

    override fun validate(apk: File): UpdateFailure? {
        if (!apk.isFile || apk.length() <= 0) return UpdateFailure.FileUnavailable
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = try {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            null
        } ?: return UpdateFailure.ApkInvalid

        if (archive.packageName != context.packageName) return UpdateFailure.ApkInvalid

        val archiveVersionCode = archive.longVersionCodeCompat()
        val currentVersionCode = try {
            pm.getPackageInfo(context.packageName, 0).longVersionCodeCompat()
        } catch (e: Exception) {
            return UpdateFailure.ApkInvalid
        }
        if (archiveVersionCode <= currentVersionCode) return UpdateFailure.ApkInvalid

        val archiveCerts = archive.signatureSha256Set()
        val currentCerts = try {
            pm.getPackageInfo(context.packageName, flags).signatureSha256Set()
        } catch (e: Exception) {
            return UpdateFailure.ApkInvalid
        }
        if (archiveCerts.isEmpty() || archiveCerts != currentCerts) return UpdateFailure.ApkInvalid
        return null
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signatureSha256Set(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            signatures.orEmpty()
        }
        return signatures.mapTo(LinkedHashSet()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {

    private val authority = "${context.packageName}.updates"

    override fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    override fun install(apk: File): InstallResult {
        if (!canInstallPackages()) return InstallResult.PermissionRequired
        return try {
            val uri: Uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.Launched
        } catch (e: Exception) {
            InstallResult.Failed(UpdateFailure.InstallFailed)
        }
    }

    override fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
