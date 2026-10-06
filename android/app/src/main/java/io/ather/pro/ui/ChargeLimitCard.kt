package io.ather.pro.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.charging.ChargeLimitSession
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.ScooterDashboardState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun ChargeLimitCard(
    snapshot: ChargeLimitController.Snapshot,
    dashboard: ScooterDashboardState,
    onEnabledChange: (Boolean) -> Unit,
    onPercentChange: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by rememberSaveable(snapshot.percent) { mutableIntStateOf(snapshot.percent) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val active = ChargingControl.isActivelyCharging(dashboard.telemetry)
    val reportedAt = dashboard.batteryReportedAt ?: dashboard.batteryUpdatedAt
    val unapplied = selected != snapshot.percent
    val shown = ChargeLimitSession.shown(
        snapshot = snapshot,
        telemetry = dashboard.telemetry,
        selectedPercent = selected,
        capacityWh = dashboard.usablePackWh,
        reportedAtMs = reportedAt,
        nowMs = now,
        liveRate = dashboard.chargingRatePercentPerMinute,
        liveMinutes = dashboard.chargingRateMinutes
    )
    val estimate = shown.estimate
    val clockFormat = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    fun at(time: Long) = clockFormat.format(Date(time))
    fun remaining(until: Long): String {
        val minutes = ceil((until - now).coerceAtLeast(0L) / 60_000.0).toInt()
        return if (minutes == 0) "now" else if (minutes < 60) "about $minutes min" else
            "about ${minutes / 60} h ${minutes % 60} min"
    }
    val pending = snapshot.status == ChargeLimitController.Status.PENDING
    val status = when (snapshot.status) {
        ChargeLimitController.Status.DISABLED -> "Off"
        ChargeLimitController.Status.MONITORING -> "Monitoring"
        ChargeLimitController.Status.PENDING -> "Waiting for scooter confirmation"
        ChargeLimitController.Status.CONFIRMED -> "Stop confirmed"
        ChargeLimitController.Status.ERROR -> "Needs attention"
    }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic charge limit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (snapshot.enabled) "${snapshot.percent}% · $status" else status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (snapshot.status == ChargeLimitController.Status.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
                if (snapshot.enabled) TextButton(onClick = { onEnabledChange(false) }) { Text("Turn off") }
            }
            Text("Stop at $selected%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Slider(value = selected.toFloat(), onValueChange = { selected = it.roundToInt() },
                valueRange = ChargeLimitController.MIN_PERCENT.toFloat()..ChargeLimitController.MAX_PERCENT.toFloat(),
                steps = ChargeLimitController.MAX_PERCENT - ChargeLimitController.MIN_PERCENT - 1,
                modifier = Modifier.semantics { contentDescription = "Charge target percent" })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 70, 80, 90, 100).forEach { target ->
                    FilterChip(selected = selected == target, onClick = { selected = target }, label = { Text("$target%") })
                }
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (estimate != null) {
                        Text("Estimated $selected%: ${at(estimate.targetAtMs)}",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${remaining(estimate.targetAtMs)} · ${estimate.accuracyPercent}% accuracy",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(estimate.basisLabel, style = MaterialTheme.typography.bodySmall)
                        val timerArmed = shown.timerArmed
                        Text((if (timerArmed) "Scheduled Pause: " else "Pause preview: ") +
                            "${at(estimate.stopAtMs)} (${remaining(estimate.stopAtMs)})",
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (timerArmed) "Timer armed" else if (!snapshot.enabled || unapplied)
                            "Preview only — apply the limit to arm it" else if (pending)
                            "Stop requested — waiting for confirmation" else "Timer not armed",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (timerArmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!active) Text("If charging starts now. The timer waits for reported charging.",
                            style = MaterialTheme.typography.bodySmall)
                        else if (snapshot.enabled && !unapplied && snapshot.estimate == null) {
                            Text("Waiting for a newer charging reading to arm the timer.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (active && reportedAt != null && now - reportedAt > 30_000L) {
                            val age = ceil((now - reportedAt).coerceAtLeast(0L) / 60_000.0).toInt()
                            Text("Based on a $age min old battery reading; assumes charging continued.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Approximate. Fallback stops slightly early; final charge may differ from $selected%.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No charging speed yet. After one charge, the time starts from that speed and gets closer on later charges.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = { onPercentChange(selected, snapshot.chargerPowerW) }, modifier = Modifier.fillMaxWidth(),
                enabled = !pending && (!snapshot.enabled || unapplied)) {
                Text(if (snapshot.enabled) "Apply $selected% limit" else "Enable $selected% limit")
            }
            if (snapshot.enabled && unapplied) Text("New target has not been applied.", style = MaterialTheme.typography.bodySmall)
            snapshot.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (snapshot.status == ChargeLimitController.Status.ERROR) {
                OutlinedButton(onClick = onRetry) { Text("Retry stop at ${snapshot.percent}%") }
            }
            Text("Checks every 5 seconds. Sends Pause when a fresh reading reaches your target or the estimated fallback time arrives. Keep this phone online; turn the limit off to end monitoring.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.enabled) Text("Turn the limit off before resuming a charge above ${snapshot.percent}%.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
