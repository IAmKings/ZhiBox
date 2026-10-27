package com.zhibox.memory.core.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zhibox.memory.MainActivity
import com.zhibox.memory.R

/** 应用更新通知（进度/完成），全部为本地通知。 */
object UpdateNotifications {

    const val CHANNEL_ID = "app_update"
    private const val PROGRESS_NOTIFICATION_ID = 4101
    private const val COMPLETE_NOTIFICATION_ID = 4102
    private const val REQUEST_INSTALL = 4103

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_notification_channel_desc)
                setShowBadge(false)
            },
        )
    }

    /** 前台服务进度通知。 */
    fun progressNotification(
        context: Context,
        info: UpdateInfo,
        downloadedBytes: Long,
        totalBytes: Long,
    ): Notification {
        ensureChannel(context)
        val total = totalBytes.takeIf { it > 0 }
        val pct = if (total != null) {
            (downloadedBytes * 100 / total).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.update_notification_progress_title))
            .setContentText(
                context.getString(
                    R.string.update_notification_progress_text,
                    info.versionName,
                    pct,
                ),
            )
            .setProgress(100, pct, total == null)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun notifyProgress(context: Context, info: UpdateInfo, downloadedBytes: Long, totalBytes: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            NotificationManagerCompat.from(context).notify(
                PROGRESS_NOTIFICATION_ID,
                progressNotification(context, info, downloadedBytes, totalBytes),
            )
        }
    }

    fun cancelProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID) }
    }

    /** 后台下载完成通知：点击进入应用并触发安装。 */
    fun notifyDownloadComplete(context: Context, info: UpdateInfo) {
        runCatching {
            ensureChannel(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_INSTALL,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.update_notification_complete_title))
                .setContentText(
                    context.getString(
                        R.string.update_notification_complete_text,
                        info.versionName,
                    ),
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(context).notify(COMPLETE_NOTIFICATION_ID, notification)
        }
    }

    fun cancelComplete(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(COMPLETE_NOTIFICATION_ID) }
    }
}
