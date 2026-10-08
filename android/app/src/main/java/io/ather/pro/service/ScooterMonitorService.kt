package io.ather.pro.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import io.ather.pro.appContainer
import io.ather.pro.domain.monitoring.MonitorNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/** One foreground service owns continuous cloud telemetry, including when the UI closes. */
class ScooterMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var previousNotice: MonitorNotice? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cutoffWakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appContainer.monitoring.stopFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote before session/database/network work to meet Android's FGS deadline.
        val notifications = getSystemService(NotificationManager::class.java)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Connecting to your scooter…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: RuntimeException) {
            appContainer.monitoring.reportFailure()
            stopSelf()
            return START_NOT_STICKY
        }
        appContainer.monitoring.onServiceStarted()
        if (!appContainer.monitoring.requested) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            observeNetwork()
            scope.launch {
                val clockTicks = flow {
                    while (isActive) {
                        emit(System.currentTimeMillis())
                        delay(60_000L)
                    }
                }
                combine(appContainer.repository.dashboard, appContainer.repository.chargeLimit, clockTicks) {
                    state, limit, _ -> state to limit
                }.collect { (state, limit) ->
                    keepCutoffAwake(limit.enabled && appContainer.monitoring.requested)
                    val notice = MonitorNotice.from(System.currentTimeMillis(), state, limit)
                    if (notice != previousNotice) {
                        notifications.notify(NOTIFICATION_ID, MonitorNotification.build(this@ScooterMonitorService, notice))
                        previousNotice = notice
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun observeNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                appContainer.repository.refresh()
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun notification(message: String): Notification = MonitorNotification.build(this, message)

    /** Keep fixed snapshot checks running with the screen off whenever the limiter is enabled. */
    private fun keepCutoffAwake(required: Boolean) {
        if (!required) {
            cutoffWakeLock?.takeIf { it.isHeld }?.release()
            return
        }
        val lock = cutoffWakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Athr+:ChargeCutoff")
            .apply { setReferenceCounted(false) }.also { cutoffWakeLock = it }
        val now = SystemClock.elapsedRealtime()
        if (!lock.isHeld || now - wakeLockRenewedAt >= 60_000L) {
            lock.acquire(120_000L)
            wakeLockRenewedAt = now
        }
    }

    override fun onDestroy() {
        scope.cancel()
        keepCutoffAwake(false)
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        appContainer.monitoring.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = MonitorNotification.ID
        private const val ACTION_STOP = "io.ather.pro.STOP_MONITORING"
    }
}
