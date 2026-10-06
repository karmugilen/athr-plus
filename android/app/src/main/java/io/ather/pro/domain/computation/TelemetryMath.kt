package io.ather.pro.domain.computation

/**
 * Numeric seam. The phone installs Rust. Tests use [JvmTelemetryMath] when Rust is not installed.
 * Two adapters: Rust in the app process, JVM for tests.
 */
interface TelemetryMath {
    fun historyIndices(timestamps: LongArray, values: DoubleArray, start: Long, end: Long): IntArray
    fun scaleRange(current: Double, mode: Double, active: Double): Double
    fun chargeEstimate(soc: Double, target: Double, capacity: Double, tariff: Double,
        range: Double, eta80: Double, eta100: Double): DoubleArray

    /**
     * Learned charge speed. Returns `[percentPerMinute, basisCode, accuracyPercent]`.
     * A non-finite rate means that side is absent. Accuracy is 10–95.
     */
    fun learnedChargeNumbers(liveRate: Double, liveMinutes: Double, learnedRate: Double, learnedMinutes: Double): DoubleArray
}

object TelemetryComputation {
    @Volatile var engine: TelemetryMath? = null

    fun engine(): TelemetryMath {
        engine?.let { return it }
        return JvmTelemetryMath.also { engine = it }
    }
}
