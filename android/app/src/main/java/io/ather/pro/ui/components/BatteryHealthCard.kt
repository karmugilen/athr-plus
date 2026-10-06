package io.ather.pro.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.battery.EstimatedBatteryHealth
import io.ather.pro.domain.battery.rideEfficiencyKmPerUnit
import io.ather.pro.domain.model.ScooterDashboardState

@Composable
fun BatteryHealthCard(dashboard: ScooterDashboardState) {
    val capacity = dashboard.usablePackWh
    val health = remember(dashboard.reportedSohPercentage, dashboard.recentTrips, capacity) {
        EstimatedBatteryHealth.resolve(dashboard.reportedSohPercentage, dashboard.recentTrips, capacity)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Battery health · SoH", style = MaterialTheme.typography.titleMedium)
            when (health) {
                is EstimatedBatteryHealth.Display.ReportedBms -> {
                    Text("${number(health.percent, 1)}%", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Reported by the scooter’s battery telemetry.", style = MaterialTheme.typography.bodySmall)
                }
                is EstimatedBatteryHealth.Display.Estimated -> {
                    Text("≈ ${number(health.percent, 1)}% · Estimated", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${health.confidenceLabel} · ${health.sampleCount} usable ride samples", style = MaterialTheme.typography.bodySmall)
                    Text(health.methodLabel, style = MaterialTheme.typography.bodySmall)
                    Text("Relative to ${number(capacity / 1000, 2)} kWh usable capacity. Riding conditions and battery temperature affect this estimate; it is not a BMS measurement.", style = MaterialTheme.typography.bodySmall)
                }
                EstimatedBatteryHealth.Display.Unavailable -> {
                    Text("Learning from your rides", style = MaterialTheme.typography.titleLarge)
                    val references = dashboard.recentTrips.filter { it.isOfficialRide && it.energyConsumedWh > 0 && it.efficiencyWhPerKm in 8.0..120.0 }
                    val measured = dashboard.recentTrips.count { !it.isOfficialRide && it.distanceKm >= 3 && it.socConsumed >= EstimatedBatteryHealth.MIN_SOC_DROP_PERCENT }
                    Text("Reference rides: ${references.size}/3 · ${number(references.sumOf { it.distanceKm })}/20 km\nMeasured rides with at least 5% battery use: $measured/5", style = MaterialTheme.typography.bodyMedium)
                    Text("The current API readings do not include battery SoH. An estimate appears after enough measured rides can be compared with official ride energy. Missing data never becomes a made-up percentage.", style = MaterialTheme.typography.bodySmall)
                }
            }
            val official = dashboard.recentTrips.filter { it.isOfficialRide && it.energyConsumedWh > 0 }
            val efficiency = rideEfficiencyKmPerUnit(official)
            efficiency?.let { Text("Recent official ride efficiency · ${number(it, 1)} km / kWh", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
