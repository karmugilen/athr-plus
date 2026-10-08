package io.ather.pro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.charging.ChargingControl
import io.ather.pro.domain.model.RemoteChargingCommand
import io.ather.pro.domain.model.RemoteCommandPhase
import io.ather.pro.domain.model.ScooterTelemetry
import kotlinx.coroutines.delay

@Composable
fun ChargingActions(
    telemetry: ScooterTelemetry?,
    command: RemoteChargingCommand,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLatch: () -> Unit = {}
) {
    // Recompose while a latch is pending so confirm timeouts unlock buttons
    // even if WebSocket telemetry is quiet.
    var clockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(command.phase, command.requestedAt) {
        if (command.phase != RemoteCommandPhase.SENDING &&
            command.phase != RemoteCommandPhase.ACCEPTED
        ) {
            return@LaunchedEffect
        }
        while (true) {
            delay(1_000L)
            clockMs = System.currentTimeMillis()
        }
    }
    val view = ChargingControl.resolveView(telemetry, command, nowMs = clockMs, confirmFromTelemetry = false)
    ChargingActions(
        view = view,
        onPauseCharging = onPauseCharging,
        onResumeCharging = onResumeCharging,
        onRetryLatch = onRetryLatch,
        modifier = modifier
    )
}

@Composable
private fun ChargingActions(
    view: ChargingControl.View,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    onRetryLatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isCharging = view.activelyCharging
    val chargerConnected = view.pluggedIn
    val normalizedStatus = view.statusLabel

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Charging Actions status: $normalizedStatus"
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Charging",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (chargerConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = colorScheme.background,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "PLUGGED IN",
                                color = colorScheme.secondary,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = colorScheme.secondary),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isCharging) colorScheme.secondary else colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = normalizedStatus.uppercase(),
                        color = if (isCharging) colorScheme.secondary else colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isCharging) colorScheme.secondary else colorScheme.primary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Every deliberate tap is actionable. The dispatcher replaces a stalled
            // pending request, while requestedAt matching ignores its late callback.
            val stopEnabled = !view.commandPending
            val startEnabled = !view.commandPending

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPauseCharging,
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Stop or Pause Charging"
                        },
                    enabled = stopEnabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                        disabledContainerColor = colorScheme.background,
                        disabledContentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = if (!stopEnabled) BorderStroke(1.dp, colorScheme.outline) else null
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pause",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onResumeCharging,
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Start or Resume Charging"
                        },
                    enabled = startEnabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.secondary,
                        contentColor = colorScheme.onSecondary,
                        disabledContainerColor = colorScheme.background,
                        disabledContentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = if (!startEnabled) BorderStroke(1.dp, colorScheme.outline) else null
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Resume",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }

            when (view.command.phase) {
                RemoteCommandPhase.SENDING -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Sending…",
                        color = colorScheme.tertiary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                RemoteCommandPhase.ACCEPTED -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Waiting for scooter confirmation…",
                        color = colorScheme.tertiary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(onClick = onRetryLatch) {
                        Text("Allow another attempt", color = colorScheme.secondary)
                    }
                }
                RemoteCommandPhase.ERROR -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Request failed · try again",
                        color = colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(onClick = onRetryLatch) {
                        Text("Dismiss error", color = colorScheme.secondary)
                    }
                }
                RemoteCommandPhase.CONFIRMED -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Confirmed",
                        color = colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                RemoteCommandPhase.IDLE -> Unit
            }
        }
    }
}
