package io.ather.pro.ui.insights

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.insights.*
import io.ather.pro.domain.model.ScooterDashboardState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private fun number(value: Double, digits: Int = 1) = String.format(Locale.getDefault(), "%.${digits}f", value)
private fun date(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

@Composable
fun RiderInsightsScreen(state: RiderInsights, dashboard: ScooterDashboardState,
    onPlanChange: (Double, Int) -> Unit, onTyreReminders: (Boolean) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000L) } }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TomorrowCard(state, dashboard, now, onPlanChange) }
        item { ParkedDrainCard(state) }
        item { ChargingHistoryCard(state) }
        item { TyreReviewCard(state, onTyreReminders) }
    }
}

@Composable
private fun InsightCard(title: String, body: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body()
        }
    }
}

@Composable
private fun TomorrowCard(state: RiderInsights, dashboard: ScooterDashboardState, now: Long,
    onPlanChange: (Double, Int) -> Unit) {
    var distance by rememberSaveable(state.dailyDistanceKm) { mutableStateOf(number(state.dailyDistanceKm, 0)) }
    var reserve by rememberSaveable(state.reservePercent) { mutableStateOf(state.reservePercent.toString()) }
    val km = distance.replace(',', '.').toDoubleOrNull()
    val reservePercent = reserve.toIntOrNull()
    val valid = km != null && km.isFinite() && km in 1.0..300.0 && reservePercent != null && reservePercent in 0..50
    val fresh = dashboard.batteryReportedAt?.let { now - it in 0..12 * 60_000L } == true
    val estimate = remember(state.dailyDistanceKm, state.reservePercent, state.rides, dashboard.telemetry?.batterySoc, fresh, now) {
        TomorrowPlanner.estimate(state.dailyDistanceKm, state.reservePercent, dashboard.usablePackWh,
            dashboard.telemetry?.batterySoc.takeIf { fresh },
            state.rides.filter { now - it.endTimeMs in 0..90L * 24 * 60 * 60_000 })
    }
    InsightCard("Enough for tomorrow?") {
        when {
            estimate == null -> Text("Learning from your rides", color = MaterialTheme.colorScheme.onSurfaceVariant)
            estimate.targetPercent > 100 -> Text("Charging stop needed", style = MaterialTheme.typography.headlineSmall)
            else -> {
                Text("${ceil(estimate.targetPercent).toInt()}%", style = MaterialTheme.typography.displaySmall)
                Text(when (estimate.enough) {
                    true -> "Ready for tomorrow"
                    false -> "Charge before leaving"
                    null -> "Refresh battery to check"
                }, color = MaterialTheme.colorScheme.primary)
                Text("${number(state.dailyDistanceKm, 0)} km + ${state.reservePercent}% reserve",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(distance, { distance = it }, label = { Text("Daily km") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(reserve, { reserve = it }, label = { Text("Reserve (%)") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        TextButton(onClick = { if (valid) onPlanChange(km!!, reservePercent!!) },
            enabled = valid && (km != state.dailyDistanceKm || reservePercent != state.reservePercent)) { Text("Save") }
        if (!valid) Text("Enter 1–300 km and a 0–50% reserve.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ParkedDrainCard(state: RiderInsights) {
    val periods = listOfNotNull(state.activeParked?.takeIf { it.hours >= 2.0 }) + state.parkedPeriods
    InsightCard("While parked") {
        if (periods.isEmpty()) Text("No parked readings yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        periods.take(3).forEach { period ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(date(period.startedAt), style = MaterialTheme.typography.labelMedium)
                    Text("${number(period.startSoc)}% → ${number(period.endSoc)}% · ${number(period.hours)} h",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${if (period.dropPercent >= 0) "−" else "+"}${number(kotlin.math.abs(period.dropPercent))}%",
                    style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
fun ChargingHistoryCard(state: RiderInsights) {
    InsightCard("Recent charges") {
        if (state.chargeSessions.isEmpty()) Text("No charges recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.chargeSessions.take(5).forEachIndexed { index, session ->
            if (index > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${number(session.startSoc, 0)}% → ${number(session.endSoc, 0)}%", style = MaterialTheme.typography.titleMedium)
                    Text(date(session.startedAt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${number((session.lastSeenAt - session.startedAt) / 60_000.0, 0)} min",
                    style = MaterialTheme.typography.bodyMedium)
            }
            val result = when {
                session.interruptedObservation -> "Incomplete record"
                session.endedAt == null -> "Last seen charging"
                session.cutoffConfirmed -> "Auto-stop confirmed"
                else -> "Ended · auto-stop unconfirmed"
            }
            Text(listOfNotNull(session.target?.let { "Target $it%" }, result).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TyreReviewCard(state: RiderInsights, onReminders: (Boolean) -> Unit) {
    val change = remember(state.rides, state.observations) {
        TyreEfficiencyReview.evaluate(state.rides, state.observations)
    }
    val speedBand = remember(state.rides, state.observations) {
        TyreEfficiencyReview.efficientSpeedBand(state.rides, state.observations)
    }
    InsightCard("Tyres & efficiency") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Check reminders", modifier = Modifier.weight(1f))
            Switch(checked = state.tyreReminders, onCheckedChange = onReminders)
        }
        Text(if (change == null) "Learning your ride patterns"
            else "Check tyres · energy use up ${number(change.increasePercent, 0)}%")
        if (change != null) Text("Efficiency alert, not a pressure measurement", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (speedBand != null) {
            Text("Best average-speed band", style = MaterialTheme.typography.labelMedium)
            Text("${speedBand.fromKmh}–${speedBand.toKmh} km/h", style = MaterialTheme.typography.headlineSmall)
            Text("${number(speedBand.whPerKm)} Wh/km · ${speedBand.rides} rides", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
