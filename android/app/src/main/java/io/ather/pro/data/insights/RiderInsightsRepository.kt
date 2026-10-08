package io.ather.pro.data.insights

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import io.ather.pro.MainActivity
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.insights.*
import io.ather.pro.domain.model.ScooterTelemetry
import io.ather.pro.domain.model.TripRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, per-scooter local records; runs on the owning repository's serial worker. */
class RiderInsightsRepository(private val context: Context?) {
    private val prefs = context?.getSharedPreferences("rider_insights_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private var vehicle: String? = null
    private val mutable = MutableStateFlow(RiderInsights())
    val state = mutable.asStateFlow()

    fun selectVehicle(uuid: String?) {
        vehicle = uuid
        mutable.value = uuid?.let { id ->
            runCatching {
                val saved = prefs?.getString(id, null) ?: return@runCatching RiderInsights()
                gson.fromJson(saved, RiderInsights::class.java).also { state ->
                    require(state.dailyDistanceKm.isFinite() && state.dailyDistanceKm in 1.0..300.0)
                    require(state.reservePercent in 0..50)
                    require(state.chargeSessions.size <= 60 && state.parkedPeriods.size <= 60 && state.observations.size <= 7_200)
                    require(state.rides.size <= 100)
                }
            }.getOrDefault(RiderInsights())
        } ?: RiderInsights()
    }

    private fun publish(next: RiderInsights) {
        if (next == mutable.value || vehicle == null) return
        mutable.value = next
        prefs?.edit()?.putString(vehicle, gson.toJson(next))?.apply()
    }

    fun updatePlan(distanceKm: Double, reserve: Int) {
        if (!distanceKm.isFinite() || distanceKm !in 1.0..300.0 || reserve !in 0..50) return
        publish(mutable.value.copy(dailyDistanceKm = distanceKm, reservePercent = reserve))
    }

    fun setTyreReminders(enabled: Boolean) = publish(mutable.value.copy(tyreReminders = enabled))

    fun observe(report: ScooterTelemetry, snapshot: Boolean, now: Long, limit: ChargeLimitController.Snapshot) {
        if (vehicle == null) return
        var next = RiderInsightRecorder.observe(mutable.value, report, now, limit)
        next = RiderInsightRecorder.observeStopped(next, report, snapshot, now)
        publish(next)
    }

    fun confirmCutoff(limit: ChargeLimitController.Snapshot) =
        publish(RiderInsightRecorder.confirmCutoff(mutable.value, limit))

    fun reviewTrips(trips: List<TripRecord>, now: Long) {
        var next = mutable.value.copy(rides = trips.filter { it.isOfficialRide }.take(100))
        if (!next.tyreReminders) { publish(next); return }
        val change = TyreEfficiencyReview.evaluate(trips, next.observations)
        if (change != null && change.rideId != next.lastEfficiencyAlertRide &&
            now - (trips.maxOfOrNull { it.endTimeMs } ?: 0L) in 0..24 * 60 * 60_000L &&
            now - next.lastEfficiencyAlertAt >= 7L * 24 * 60 * 60_000) {
            notify("Efficiency changed — check tyres", "Energy use rose across 3 comparable rides. Check cold tyre pressure; load, traffic and weather can also affect efficiency.", 9202)
            next = next.copy(lastEfficiencyAlertRide = change.rideId, lastEfficiencyAlertAt = now)
        }
        publish(next)
    }

    private fun notify(title: String, text: String, id: Int) {
        val app = context ?: return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("rider_insights", "Rider reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(app, id, Intent(app, MainActivity::class.java)
            .putExtra("open_insights", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, "rider_insights")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(intent).setAutoCancel(true).build()
        runCatching { manager.notify(id, notification) }
    }
}
