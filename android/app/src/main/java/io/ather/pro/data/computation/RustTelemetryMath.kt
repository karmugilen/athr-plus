package io.ather.pro.data.computation

import io.ather.pro.domain.computation.TelemetryMath

/** JNI uses primitive arrays. Account credentials never cross into Rust. */
object RustTelemetryMath : TelemetryMath {
    init { System.loadLibrary("ather_math") }
    external override fun historyIndices(timestamps: LongArray, values: DoubleArray, start: Long, end: Long): IntArray
    external override fun scaleRange(current: Double, mode: Double, active: Double): Double
    external override fun chargeEstimate(soc: Double, target: Double, capacity: Double, tariff: Double,
        range: Double, eta80: Double, eta100: Double): DoubleArray
    external override fun learnedChargeNumbers(liveRate: Double, liveMinutes: Double, learnedRate: Double, learnedMinutes: Double): DoubleArray
}
