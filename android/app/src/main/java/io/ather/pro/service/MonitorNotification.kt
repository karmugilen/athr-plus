package io.ather.pro.service

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.ather.pro.MainActivity

object MonitorNotification {
    const val CHANNEL = "charging_monitor_silent"
    const val ID = 1201
    fun build(context: Context, message: String): Notification = build(context, io.ather.pro.domain.monitoring.MonitorNotice(
        title = message.substringBefore(" · ").ifBlank { "Athr+" },
        detail = message,
        socPercent = null,
        countdownToMs = null
    ))

    fun build(context: Context, notice: io.ather.pro.domain.monitoring.MonitorNotice): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Charging status (silent)", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 1, Intent(context, ScooterMonitorService::class.java).setAction("io.ather.pro.STOP_MONITORING"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val countdown = notice.countdownToMs
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(notice.title)
            .setContentText(notice.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.detail))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, notice.socPercent ?: 0, notice.socPercent == null)
            .addAction(0, "Stop monitoring", stop)
        if (countdown != null) {
            builder.setWhen(countdown).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setWhen(System.currentTimeMillis()).setShowWhen(false).setUsesChronometer(false)
        }
        return builder.build()
    }
}
