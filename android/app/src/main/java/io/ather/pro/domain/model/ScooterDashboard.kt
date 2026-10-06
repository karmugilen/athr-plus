package io.ather.pro.domain.model

enum class ConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

enum class ScooterModel(
    val displayName: String,
    val nominalCapacityWh: Double,
    val usableCapacityWh: Double,
    val referenceCycleRangeKm: Double
) {
    ATHER_450X_3_7("450X (3.7 kWh)", 3700.0, 3240.0, 85.0),
    ATHER_450X_2_9("450X (2.9 kWh)", 2900.0, 2610.0, 70.0),
    ATHER_APEX("450 Apex (3.7 kWh)", 3700.0, 3240.0, 85.0),
    ATHER_RIZTA_3_7("Rizta (3.7 kWh)", 3700.0, 3240.0, 100.0),
    ATHER_RIZTA_2_9("Rizta (2.9 kWh)", 2900.0, 2610.0, 80.0),
    ATHER_450S("450S (2.9 kWh)", 2900.0, 2610.0, 70.0)
}

data class VehicleProfile(
    val scooterId: String? = null,
    val modelType: String? = null,
    val modelCode: String? = null,
    val generation: String? = null,
    val bikeType: String? = null,
    val platform: String? = null,
    val colour: String? = null
) {
    val resolvedModel: ScooterModel?
        get() {
            val type = modelType.orEmpty().lowercase()
            val code = modelCode.orEmpty().lowercase()
            val bike = bikeType.orEmpty().lowercase()
            return when {
                "apex" in type || "apex" in code || "apex" in bike -> ScooterModel.ATHER_APEX
                "rizta" in type || "rizta" in code || "rizta" in bike -> {
                    if (code.contains("lr") || bike.contains("lr")) ScooterModel.ATHER_RIZTA_2_9
                    else ScooterModel.ATHER_RIZTA_3_7
                }
                type == "450s" || code == "450s" || bike.contains("450s") -> ScooterModel.ATHER_450S
                code == "xhr" || bike.contains("xhr") -> ScooterModel.ATHER_450X_3_7
                code == "xlr" || bike.contains("xlr") -> ScooterModel.ATHER_450X_2_9
                type == "450x" -> null
                else -> null
            }
        }

    val displayName: String
        get() {
            val base = when (modelType?.lowercase()) {
                "450x" -> "450X"
                "450s" -> "450S"
                "apex", "450 apex" -> "450 Apex"
                "rizta" -> "Rizta"
                else -> modelType?.takeIf(String::isNotBlank)?.let { it.uppercase() }
                    ?: "Scooter"
            }
            val range = when (modelCode?.lowercase()) {
                "xhr" -> "HR"
                "xlr" -> "LR"
                else -> null
            }
            val gen = generation?.takeIf(String::isNotBlank)?.let { "Gen $it" }
            return listOfNotNull(base, range, gen).joinToString(" · ")
        }
}

enum class RemoteCommandPhase {
    IDLE,
    SENDING,
    ACCEPTED,
    CONFIRMED,
    ERROR
}

data class RemoteChargingCommand(
    val action: String? = null,
    val phase: RemoteCommandPhase = RemoteCommandPhase.IDLE,
    val message: String? = null,
    val requestedAt: Long? = null
)

data class ModeRange(
    val rawRangeKm: Double? = null,
    val predictedRangeKm: Double? = null,
    val derivedWhPerKm: Double? = null,
    val kmPerKWh: Double? = null
) {
    fun mergeWith(delta: ModeRange): ModeRange {
        return ModeRange(
            rawRangeKm = delta.rawRangeKm ?: this.rawRangeKm,
            predictedRangeKm = delta.predictedRangeKm ?: this.predictedRangeKm,
            derivedWhPerKm = delta.derivedWhPerKm ?: this.derivedWhPerKm,
            kmPerKWh = delta.kmPerKWh ?: this.kmPerKWh
        )
    }
}

data class BatteryHealthStats(
    val sohPercentage: Double,
    val estimatedLossPercentage: Double,
    val cycleCount: Double,
    val usableCapacityKWh: Double,
    val nominalCapacityKWh: Double,
    val uncertaintyPercentage: Double = 1.8,
    val empiricalTripCount: Int = 0
)

data class GpsData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Double? = null,
    val heading: Double? = null,
    val speed: Double? = null
) {
    fun mergeWith(delta: GpsData): GpsData {
        return GpsData(
            latitude = delta.latitude ?: this.latitude,
            longitude = delta.longitude ?: this.longitude,
            altitudeMeters = delta.altitudeMeters ?: this.altitudeMeters,
            accuracyMeters = delta.accuracyMeters ?: this.accuracyMeters,
            heading = delta.heading ?: this.heading,
            speed = delta.speed ?: this.speed
        )
    }
}

data class TpmsData(
    val frontPressurePsi: Double? = null,
    val rearPressurePsi: Double? = null,
    val frontTemperatureC: Double? = null,
    val rearTemperatureC: Double? = null
) {
    val hasPressure: Boolean
        get() = frontPressurePsi != null || rearPressurePsi != null

    fun mergeWith(delta: TpmsData): TpmsData {
        return TpmsData(
            frontPressurePsi = delta.frontPressurePsi ?: this.frontPressurePsi,
            rearPressurePsi = delta.rearPressurePsi ?: this.rearPressurePsi,
            frontTemperatureC = delta.frontTemperatureC ?: this.frontTemperatureC,
            rearTemperatureC = delta.rearTemperatureC ?: this.rearTemperatureC
        )
    }
}

data class TripRecord(
    val id: String,
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
    val routeSpeedsKmh: List<Double>? = null
)

data class ScooterTelemetry(
    val batterySoc: Double? = null,
    val rangeKm: Double? = null,
    val odoKm: Double? = null,
    val vehicleState: String? = null,
    val mode: String? = null,
    val charging: Boolean? = null,
    val chargerConnected: Boolean? = null,
    val chargingStatus: String? = null,
    val timeToFullChargeMin: Double? = null,
    val timeToEightyChargeMin: Double? = null,
    val savingsInr: Double? = null,
    val gps: GpsData? = null,
    val modeRanges: Map<String, ModeRange> = emptyMap(),
    val batteryHealth: BatteryHealthStats? = null,
    val reportedSohPercent: Double? = null,
    val tpms: TpmsData? = null,
    val chargerType: String? = null,
    val softwareVersion: String? = null,
    val connectivityStrength: Int? = null,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val remoteChargingAction: String? = null,
    /** Scooter/cloud source time, distinct from when the phone received a snapshot. */
    val sourceTimestampMs: Long? = null
) {
    val isAntiTheftArmed: Boolean?
        get() = featureFlags["anti_theft"] ?: featureFlags["theft_protection"]

    val isVacationModeActive: Boolean?
        get() = featureFlags["vacation_mode"]
            ?: vehicleState?.takeIf { it.contains("vacation", ignoreCase = true) }?.let { true }

    val displaySoftwareVersion: String?
        get() = softwareVersion?.takeIf { it.isNotBlank() }

    fun mergeWith(delta: ScooterTelemetry): ScooterTelemetry {
        val mergedModeRanges = this.modeRanges.toMutableMap()
        for ((key, deltaModeRange) in delta.modeRanges) {
            val existing = mergedModeRanges[key]
            mergedModeRanges[key] = existing?.mergeWith(deltaModeRange) ?: deltaModeRange
        }

        val mergedGps = when {
            delta.gps != null && this.gps != null -> this.gps.mergeWith(delta.gps)
            delta.gps != null -> delta.gps
            else -> this.gps
        }

        val mergedTpms = when {
            delta.tpms != null && this.tpms != null -> this.tpms.mergeWith(delta.tpms)
            delta.tpms != null -> delta.tpms
            else -> this.tpms
        }

        val mergedFeatureFlags = if (delta.featureFlags.isNotEmpty()) {
            this.featureFlags + delta.featureFlags
        } else {
            this.featureFlags
        }

        val chargingAware = io.ather.pro.domain.charging.ChargingControl.mergeChargingFields(this, delta)

        return ScooterTelemetry(
            batterySoc = delta.batterySoc ?: this.batterySoc,
            rangeKm = delta.rangeKm ?: this.rangeKm,
            odoKm = delta.odoKm ?: this.odoKm,
            vehicleState = delta.vehicleState ?: this.vehicleState,
            mode = delta.mode ?: this.mode,
            charging = chargingAware.charging,
            chargerConnected = chargingAware.chargerConnected,
            chargingStatus = chargingAware.chargingStatus,
            timeToFullChargeMin = chargingAware.timeToFullChargeMin,
            timeToEightyChargeMin = chargingAware.timeToEightyChargeMin,
            savingsInr = delta.savingsInr ?: this.savingsInr,
            gps = mergedGps,
            modeRanges = mergedModeRanges,
            batteryHealth = delta.batteryHealth ?: this.batteryHealth,
            reportedSohPercent = delta.reportedSohPercent ?: this.reportedSohPercent,
            tpms = mergedTpms,
            chargerType = chargingAware.chargerType,
            softwareVersion = delta.softwareVersion ?: this.softwareVersion,
            connectivityStrength = delta.connectivityStrength ?: this.connectivityStrength,
            featureFlags = mergedFeatureFlags,
            remoteChargingAction = chargingAware.remoteChargingAction,
            sourceTimestampMs = delta.sourceTimestampMs ?: this.sourceTimestampMs
        )
    }
}

data class ScooterSettings(
    val selectedModel: ScooterModel = ScooterModel.ATHER_450X_3_7,
    val tariffRatePerKWh: Double = 8.0,
    val petrolPricePerLitre: Double = 103.0
)

data class ChargeCostEstimate(
    val remainingKWh: Double,
    val costInr: Double,
    val tariffRate: Double,
    val currentSoc: Double
) {
    val formattedCost: String
        get() = String.format(java.util.Locale.US, "₹%.2f", costInr)

    val formattedKWh: String
        get() = String.format(java.util.Locale.US, "%.2f kWh", remainingKWh)

    val summaryText: String
        get() = String.format(java.util.Locale.US, "₹%.2f to 100%% (%.2f kWh)", costInr, remainingKWh)
}

enum class TimeWindow(val label: String, val durationMs: Long?) {
    LIVE("LIVE", 60_000L),
    MIN_5("5m", 5 * 60_000L),
    MIN_15("15m", 15 * 60_000L),
    MIN_30("30m", 30 * 60_000L),
    HOUR_1("1h", 60 * 60_000L),
    HOUR_6("6h", 6 * 60 * 60_000L),
    HOUR_24("24h", 24 * 60 * 60_000L)
}

data class TelemetrySample(
    val timestamp: Long = System.currentTimeMillis(),
    val speedKmh: Double,
    val batterySoc: Double,
    val mode: String
)

/**
 * Observed live-ride metrics only — never invents missing SoC/efficiency.
 * Efficiency is taken from Ather mode-range derived Wh/km or the last completed trip.
 * Delta SoC is the measured drop across telemetry history samples in [windowMs].
 */
data class LiveRideObservation(
    val deltaSocPercent: Double? = null,
    val efficiencyWhPerKm: Double? = null,
    val efficiencySourceLabel: String? = null,
    val sampleCount: Int = 0,
    val windowMs: Long = 0L
) {
    val hasObservedDelta: Boolean get() = deltaSocPercent != null
    val hasEfficiency: Boolean get() = efficiencyWhPerKm != null
}

data class ScooterDashboardState(
    val telemetry: ScooterTelemetry? = null,
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    val errorMessage: String? = null,
    val lastUpdated: Long? = null,
    val gpsUpdatedAt: Long? = null,
    val batteryUpdatedAt: Long? = null,
    /** Timestamp of the actual battery report, including cached reports. */
    val batteryReportedAt: Long? = null,
    val chargingRatePercentPerMinute: Double? = null,
    /** Minutes of this charge used to measure [chargingRatePercentPerMinute]. */
    val chargingRateMinutes: Double = 0.0,
    val chargingUpdatedAt: Long? = null,
    val settings: ScooterSettings = ScooterSettings(),
    val recentTrips: List<TripRecord> = emptyList(),
    val packetCount: Long = 0,
    val recentPacketTimestamps: List<Long> = emptyList(),
    val telemetryHistory: List<TelemetrySample> = emptyList(),
    val rideHistory: List<io.ather.pro.domain.battery.RideSample> = emptyList(),
    val vehicleProfile: VehicleProfile? = null,
    val remoteChargingCommand: RemoteChargingCommand = RemoteChargingCommand()
) {
    /** Detected scooter wins for range and ride modes. The saved choice remains only when the profile cannot name a model. */
    val modelForRange: ScooterModel
        get() = vehicleProfile?.resolvedModel ?: settings.selectedModel

    val costToFullCharge: ChargeCostEstimate
        get() {
            val soc = telemetry?.batterySoc ?: 0.0
            val neededPercent = (100.0 - soc).coerceIn(0.0, 100.0)
            val usableKWh = settings.selectedModel.usableCapacityWh / 1000.0
            val energyNeededKWh = (neededPercent / 100.0) * usableKWh
            val costInr = energyNeededKWh * settings.tariffRatePerKWh
            return ChargeCostEstimate(
                remainingKWh = energyNeededKWh,
                costInr = costInr,
                tariffRate = settings.tariffRatePerKWh,
                currentSoc = soc
            )
        }

    /** BMS SoH only when the API actually returned batteryHealth. */
    val reportedSohPercentage: Double?
        get() = (telemetry?.reportedSohPercent ?: telemetry?.batteryHealth?.sohPercentage)?.takeIf { it.isFinite() && it in 0.0..100.0 }

    fun liveRideObservation(windowMs: Long = DEFAULT_LIVE_WINDOW_MS): LiveRideObservation {
        val now = lastUpdated ?: System.currentTimeMillis()
        val windowStart = now - windowMs
        val samples = telemetryHistory.filter { it.timestamp >= windowStart }
        val deltaSoc = if (samples.size >= 2) {
            val start = samples.first().batterySoc
            val end = samples.last().batterySoc
            (start - end).takeIf { it.isFinite() }
        } else {
            null
        }

        val activeMode = telemetry?.mode
        val modeRanges = telemetry?.modeRanges.orEmpty()
        val modeEff = activeMode?.let { modeRanges[it]?.derivedWhPerKm }
            ?: modeRanges.values.firstOrNull { it.derivedWhPerKm != null }?.derivedWhPerKm
        val (eff, source) = when {
            modeEff != null && modeEff > 0.0 -> modeEff to "Mode range (Cloud)"
            else -> {
                val tripEff = recentTrips.firstOrNull { it.efficiencyWhPerKm > 0.0 }?.efficiencyWhPerKm
                if (tripEff != null) tripEff to "Last observed trip" else null to null
            }
        }

        return LiveRideObservation(
            deltaSocPercent = deltaSoc,
            efficiencyWhPerKm = eff,
            efficiencySourceLabel = source,
            sampleCount = samples.size,
            windowMs = windowMs
        )
    }

    companion object {
        const val DEFAULT_LIVE_WINDOW_MS = 15 * 60_000L
    }
}
