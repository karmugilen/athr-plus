package io.ather.pro.domain.location

import kotlin.math.*

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val deltaLat = Math.toRadians(lat2 - lat1); val deltaLon = Math.toRadians(lon2 - lon1)
    val a = (sin(deltaLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(deltaLon / 2).pow(2)).coerceIn(0.0, 1.0)
    return 6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val deltaLon = Math.toRadians(lon2 - lon1)
    val a = Math.toRadians(lat1); val b = Math.toRadians(lat2)
    return (Math.toDegrees(atan2(sin(deltaLon) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(deltaLon))) + 360) % 360
}
