package io.ather.pro.ui.maps

import kotlin.math.atan2
import kotlin.math.hypot

/** Android-independent heading rules shared by the map and its sensor adapter. */
internal object CompassHeading {
    const val SENSOR_MAX_AGE_NS = 1_000_000_000L
    const val LOCATION_MAX_AGE_NS = 120_000_000_000L
    // Product limit: an estimate wider than 20 degrees is too coarse for heading-up.
    const val MAX_HEADING_ERROR_DEGREES = 20f

    fun validCoordinates(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun isFresh(timestampNs: Long, nowNs: Long, maxAgeNs: Long): Boolean =
        timestampNs > 0L && nowNs >= timestampNs && nowNs - timestampNs <= maxAgeNs

    fun magneticAzimuth(displayMatrix: FloatArray): Float? {
        if (displayMatrix.size != 9 || displayMatrix.any { !it.isFinite() }) return null
        // Project the screen's top edge (Y) onto Earth's east/north plane.
        val east = displayMatrix[1].toDouble()
        val north = displayMatrix[4].toDouble()
        // Within roughly 6 degrees of vertical the top edge has no stable compass direction.
        if (hypot(east, north) < 0.1) return null
        return normalize(Math.toDegrees(atan2(east, north)).toFloat())
    }

    fun trueNorth(magneticDegrees: Float?, declination: Float?, accuracy: Int,
        headingErrorDegrees: Float? = null): Float? {
        if (accuracy <= 0 || magneticDegrees == null || declination == null ||
            !magneticDegrees.isFinite() || !declination.isFinite()) return null
        if (headingErrorDegrees != null && (!headingErrorDegrees.isFinite() ||
                headingErrorDegrees < 0f || headingErrorDegrees > MAX_HEADING_ERROR_DEGREES)) return null
        return normalize(magneticDegrees + declination)
    }

    fun headingErrorDegrees(values: FloatArray): Float? = values.getOrNull(4)
        ?.takeIf { it.isFinite() && it >= 0f }
        ?.let { Math.toDegrees(it.toDouble()).toFloat() }

    private fun normalize(degrees: Float): Float = (degrees % 360f + 360f) % 360f
}

internal data class CompassReading(
    val magneticDegrees: Float?,
    val accuracy: Int,
    val headingErrorDegrees: Float?,
    val timestampNs: Long
)

/** Limit bridge traffic by time, never by angle: small real changes must arrive too. */
internal class HeadingSampleGate {
    private var lastPublishedNs: Long? = null

    fun shouldPublish(timestampNs: Long): Boolean {
        val previous = lastPublishedNs
        // Leave margin around GAME's nominal 20 ms period so clock jitter cannot halve the rate.
        if (previous != null && timestampNs >= previous && timestampNs - previous < 16_000_000L) {
            return false
        }
        lastPublishedNs = timestampNs
        return true
    }

    fun reset() { lastPublishedNs = null }
}
