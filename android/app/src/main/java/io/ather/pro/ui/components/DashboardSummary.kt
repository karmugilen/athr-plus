package io.ather.pro.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.BatteryStd
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.ConnectionStatus
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.range.RangeEstimator
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal fun number(value: Double?, decimals: Int = 0): String =
    value?.takeIf(Double::isFinite)?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: "—"

@Composable
fun FreshnessLabel(state: ScooterDashboardState, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lastUpdated) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val readingAt = state.batteryReportedAt ?: state.telemetry?.sourceTimestampMs ?: state.batteryUpdatedAt ?: state.lastUpdated
    val age = readingAt?.let { ((now - it).coerceAtLeast(0) / 1000) }
    val fresh = state.connection == ConnectionStatus.CONNECTED && age != null && age < 60
    val text = when {
        fresh -> "Live · Updated ${age}s ago"
        age != null -> "Last received ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(readingAt!!))} · ${if (state.connection == ConnectionStatus.CONNECTED) "Waiting for scooter" else "Reconnecting"}"
        state.connection == ConnectionStatus.CONNECTING -> "Connecting to your scooter…"
        else -> "Waiting for scooter data · Tap refresh to retry"
    }
    Text(text, modifier, style = MaterialTheme.typography.bodySmall,
        color = if (fresh) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun EnergySummaryCard(state: ScooterDashboardState, limit: ChargeLimitController.Snapshot) {
    val soc = state.telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val range = RangeEstimator.current(state.telemetry, state.modelForRange)
    val charging = ChargingControl.isActivelyCharging(state.telemetry)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val readingAt = state.batteryReportedAt ?: state.telemetry?.sourceTimestampMs ?: state.batteryUpdatedAt
    val fresh = state.connection == ConnectionStatus.CONNECTED && readingAt != null &&
        now - readingAt in 0L..120_000L
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val muted = colors.onSurfaceVariant
    val status = when {
        charging && fresh -> "Charging"
        charging -> "Charging · saved"
        ChargingControl.isPaused(state.telemetry) -> "Paused"
        soc == null -> "Waiting for data"
        else -> state.telemetry?.chargingStatus?.takeIf(String::isNotBlank) ?: "Ready to ride"
    }
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()
        .clip(shape)
        .background(Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainer, colors.surface)))
        .border(1.dp, colors.outlineVariant, shape)) {
        val wide = maxWidth >= 540.dp
        val narrow = maxWidth < 340.dp
        Column(Modifier.padding(if (narrow) 18.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("YOUR ENERGY", color = muted, style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.8.sp)
                    Text(state.vehicleProfile?.displayName ?: state.settings.selectedModel.displayName,
                        color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
                    Icon(Icons.Outlined.BatteryStd, null, Modifier.padding(10.dp).size(18.dp), tint = accent)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = if (charging && fresh) accent.copy(alpha = 0.1f) else colors.surfaceContainerHigh,
                        shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (charging) Icon(Icons.Default.Bolt, null, Modifier.size(14.dp),
                                tint = if (fresh) accent else muted)
                            Text(status, style = MaterialTheme.typography.labelMedium,
                                color = if (charging && fresh) accent else muted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(number(soc), color = colors.onSurface, fontSize = if (narrow) 52.sp else 64.sp,
                            lineHeight = if (narrow) 56.sp else 68.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp, maxLines = 1)
                        if (soc != null) Text("%", Modifier.padding(start = 2.dp, bottom = 7.dp),
                            fontSize = 26.sp, color = muted, fontWeight = FontWeight.Medium)
                    }
                    Text(soc?.let { "${number(it, 2)}% reported" } ?: "Battery unavailable",
                        color = muted, style = MaterialTheme.typography.bodySmall)
                }
                EnergyBatteryVisual(soc, charging, fresh, limit.percent.takeIf { limit.enabled },
                    Modifier.size(if (wide) 208.dp else if (narrow) 128.dp else 156.dp))
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("RANGE", color = muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    Text("${number(range)} km", color = colors.onSurface,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CHARGE LIMIT", color = muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (limit.enabled) Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(colors.tertiary))
                        Text(if (limit.enabled) "${limit.percent}%" else "Off", color = if (limit.enabled) accent else muted,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (limit.enabled && limit.armed && limit.status == ChargeLimitController.Status.MONITORING) {
                limit.estimate?.let { estimate ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = muted)
                        Text("Est. pause ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(estimate.stopAtMs))} · Approximate",
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
    }
}

@Composable
fun ModeRangeCard(state: ScooterDashboardState) {
    val ranges = RangeEstimator.modes(state.telemetry, state.modelForRange)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val soc = state.telemetry?.batterySoc?.takeIf { it.isFinite() && it in 0.0..100.0 }
            Text(soc?.let { "Range at ${number(it)}% battery" } ?: "Range by ride mode",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (ranges.isEmpty()) Text("Mode ranges appear when reported by your scooter.", style = MaterialTheme.typography.bodySmall)
            ranges.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { mode ->
                        Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            color = if (mode.active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(12.dp)) {
                                Text(mode.name + if (mode.active) " · Current" else "", style = MaterialTheme.typography.labelMedium)
                                Text("${number(mode.km)} km", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Estimated remaining kilometres. Changes with riding conditions.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChargeEstimateCard(state: ScooterDashboardState, target: Int) {
    val estimate = RangeEstimator.target(state.telemetry, target,
        state.usablePackWh, state.settings.tariffRatePerKWh, state.modelForRange)
    val cloudMinutes = io.ather.pro.domain.charging.ChargeDuration.cloudMinutes(
        state.telemetry, target, state.usablePackWh, state.settings.tariffRatePerKWh, state.modelForRange)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Charge to $target%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (estimate == null) {
                Text("Waiting for a battery reading to estimate your charge.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val soc = state.telemetry?.batterySoc ?: 0.0
                Text("${number(soc)}% reported · ${number(estimate.remainingPercent)}% to go", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (soc / target.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    EstimateValue("Energy", "${number(estimate.energyKWh, 2)} kWh")
                    EstimateValue("Cost", "₹${number(estimate.costInr, 1)}")
                    EstimateValue("Range at target", "${number(estimate.rangeAtTargetKm)} km")
                }
                if (cloudMinutes != null) {
                    Text("About ${cloudMinutes.roundToInt()} min to target", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Estimates use your scooter’s range and pack size. Cost excludes charging losses.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EstimateValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
