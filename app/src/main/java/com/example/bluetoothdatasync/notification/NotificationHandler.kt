package com.example.bluetoothdatasync.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.bluetoothdatasync.MainActivity
import com.example.bluetoothdatasync.R
import com.example.bluetoothdatasync.language.LanguageManager
import java.text.SimpleDateFormat
import java.util.*

class NotificationHandler(private val context: Context) {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "BluetoothSyncChannel"
        const val NOTIFICATION_ID = 1
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "蓝牙同步服务通道",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    // 关键修复：更新函数签名以接受资源 ID
    fun buildNotification(statusResId: Int, lastResultResId: Int, lastResultArg: String?, nextScanTime: Long): Notification {
        val localizedContext = getLocalizedContext()

        val notificationIntent = Intent(localizedContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            localizedContext,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 使用本地化上下文来获取翻译后的字符串
        val status = localizedContext.getString(statusResId)
        val lastResult = if (lastResultArg != null) {
            localizedContext.getString(lastResultResId, lastResultArg)
        } else {
            localizedContext.getString(lastResultResId)
        }

        val nextScanTimeFormatted = if (nextScanTime == 0L) localizedContext.getString(R.string.next_scan_none)
        else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextScanTime))

        val style = NotificationCompat.BigTextStyle()
            .setBigContentTitle(localizedContext.getString(R.string.notification_big_title))
            .bigText("""
                ${localizedContext.getString(R.string.notification_status_label)} $status
                ${localizedContext.getString(R.string.notification_last_result_label)} $lastResult
                ${localizedContext.getString(R.string.notification_next_scan_label)} $nextScanTimeFormatted
            """.trimIndent())

        return NotificationCompat.Builder(localizedContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(localizedContext.getString(R.string.notification_title))
            .setContentText("${localizedContext.getString(R.string.notification_status_label)} $status")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun getLocalizedContext(): Context {
        val lang = LanguageManager.getCurrentLanguage(context)
        if (lang == "default") {
            return context
        }
        val locale = Locale(lang)
        val config = context.resources.configuration
        val newConfig = android.content.res.Configuration(config)
        newConfig.setLocale(locale)
        return context.createConfigurationContext(newConfig)
    }

    fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
