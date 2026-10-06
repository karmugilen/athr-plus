package io.ather.pro.domain.ride

data class RidePoint(
    val latitude: Double,
    val longitude: Double
)

/** Google encoded polyline at 1e5 precision. Null, blank, or malformed input is empty. */
object RidePolyline {
    private const val PRECISION = 1e5

    fun decode(encoded: String?): List<RidePoint> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching { decodePolyline(encoded) }.getOrDefault(emptyList())
    }

    private fun decodePolyline(encoded: String): List<RidePoint> {
        val points = ArrayList<RidePoint>()
        var index = 0
        var latitudeE5 = 0
        var longitudeE5 = 0
        while (index < encoded.length) {
            val latitudeDelta = readDelta(encoded, index)
            index = latitudeDelta.nextIndex
            val longitudeDelta = readDelta(encoded, index)
            index = longitudeDelta.nextIndex
            latitudeE5 += latitudeDelta.delta
            longitudeE5 += longitudeDelta.delta
            val latitude = latitudeE5 / PRECISION
            val longitude = longitudeE5 / PRECISION
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                points.add(RidePoint(latitude, longitude))
            }
        }
        return points
    }

    private data class Delta(val delta: Int, val nextIndex: Int)

    private fun readDelta(encoded: String, start: Int): Delta {
        var index = start
        var result = 0
        var shift = 0
        while (true) {
            if (index >= encoded.length || shift > 32) error("malformed polyline")
            val group = encoded[index].code - 63
            index += 1
            result = result or ((group and 0x1f) shl shift)
            shift += 5
            if (group < 0x20) break
        }
        val delta = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        return Delta(delta, index)
    }
}
