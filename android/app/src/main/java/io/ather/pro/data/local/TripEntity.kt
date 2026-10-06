package io.ather.pro.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.ather.pro.domain.model.TripRecord

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val socConsumed: Double,
    val energyConsumedWh: Double,
    val efficiencyWhPerKm: Double,
    val electricityCostInr: Double,
    val startOdoKm: Double,
    val endOdoKm: Double,
    val estimatedPackCapacityWh: Double? = null,
    val isOfficialRide: Boolean = false,
    val durationSeconds: Double? = null,
    val averageSpeedKmh: Double? = null,
    val topSpeedKmh: Double? = null,
    val encodedPolyline: String? = null,
    /** Comma-separated km/h samples. Blank and non-finite values become a null list. */
    val routeSpeeds: String? = null
) {
    fun toDomain(): TripRecord = TripRecord(
        id = id,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        distanceKm = distanceKm,
        socConsumed = socConsumed,
        energyConsumedWh = energyConsumedWh,
        efficiencyWhPerKm = efficiencyWhPerKm,
        electricityCostInr = electricityCostInr,
        startOdoKm = startOdoKm,
        endOdoKm = endOdoKm,
        estimatedPackCapacityWh = estimatedPackCapacityWh,
        isOfficialRide = isOfficialRide,
        durationSeconds = durationSeconds,
        averageSpeedKmh = averageSpeedKmh,
        topSpeedKmh = topSpeedKmh,
        encodedPolyline = encodedPolyline,
        routeSpeedsKmh = routeSpeeds.toSpeedList()
    )

    companion object {
        fun fromDomain(trip: TripRecord): TripEntity = TripEntity(
            id = trip.id,
            startTimeMs = trip.startTimeMs,
            endTimeMs = trip.endTimeMs,
            distanceKm = trip.distanceKm,
            socConsumed = trip.socConsumed,
            energyConsumedWh = trip.energyConsumedWh,
            efficiencyWhPerKm = trip.efficiencyWhPerKm,
            electricityCostInr = trip.electricityCostInr,
            startOdoKm = trip.startOdoKm,
            endOdoKm = trip.endOdoKm,
            estimatedPackCapacityWh = trip.estimatedPackCapacityWh,
            isOfficialRide = trip.isOfficialRide,
            durationSeconds = trip.durationSeconds,
            averageSpeedKmh = trip.averageSpeedKmh,
            topSpeedKmh = trip.topSpeedKmh,
            encodedPolyline = trip.encodedPolyline,
            routeSpeeds = trip.routeSpeedsKmh.toSpeedColumn()
        )

        private fun String?.toSpeedList(): List<Double>? {
            if (isNullOrBlank()) return null
            return split(',')
                .mapNotNull { token -> token.trim().toDoubleOrNull()?.takeIf(Double::isFinite) }
                .takeIf { it.isNotEmpty() }
        }

        private fun List<Double>?.toSpeedColumn(): String? {
            if (isNullOrEmpty()) return null
            return filter(Double::isFinite).takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    }
}
