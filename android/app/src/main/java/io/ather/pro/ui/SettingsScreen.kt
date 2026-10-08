package io.ather.pro.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import io.ather.pro.BuildConfig
import io.ather.pro.domain.update.AppUpdateState
import io.ather.pro.ui.update.AppUpdateCard
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.monitoring.MonitoringState

@Composable
internal fun MonitoringCard(state: MonitoringState, limitEnabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Charging in background", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.running) "Charging monitor active" else if (state.alwaysEnabled || limitEnabled) "Quiet checks while idle" else "Off",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Switch(checked = state.alwaysEnabled, onCheckedChange = onChange)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Enable monitoring notifications") }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Android battery & background settings") }

        }
    }
}

@Composable
internal fun SettingsScreen(
    session: AuthSession,
    dashboard: ScooterDashboardState,
    monitoring: MonitoringState,
    limitEnabled: Boolean,
    updateState: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit,
    onMonitoringChange: (Boolean) -> Unit,
    onModelChange: (ScooterModel) -> Unit,
    onTariffChange: (Double) -> Unit,
    onLogout: () -> Unit
) {
    var modelMenu by remember { mutableStateOf(false) }
    var rate by rememberSaveable(dashboard.settings.tariffRatePerKWh) { mutableStateOf(dashboard.settings.tariffRatePerKWh.toString()) }
    var signOut by remember { mutableStateOf(false) }
    val parsedRate = rate.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { AppUpdateCard(updateState, onCheckUpdate, onOpenUpdate) }
        item { MonitoringCard(monitoring, limitEnabled, onMonitoringChange) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Charge estimates", style = MaterialTheme.typography.titleMedium)
                    Text("Select the battery size used for energy and cost estimates.", style = MaterialTheme.typography.bodySmall)
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }) { Text(dashboard.settings.selectedModel.displayName) }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            ScooterModel.entries.forEach { model ->
                                DropdownMenuItem(text = { Text(model.displayName) }, onClick = { onModelChange(model); modelMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(value = rate, onValueChange = { rate = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Electricity rate · ₹ / kWh") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = parsedRate == null,
                        supportingText = { if (parsedRate == null) Text("Enter a rate from 0 to 100") })
                    Button(onClick = { parsedRate?.let(onTariffChange) }, enabled = parsedRate != null && parsedRate != dashboard.settings.tariffRatePerKWh) { Text("Save rate") }
                }
            }
        }
        item { ProjectSupportCard() }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(session.displayName?.takeIf(String::isNotBlank) ?: "Your Athr+ account", style = MaterialTheme.typography.titleMedium)
                    Text("Your sign-in is stored on this phone and kept when updating Athr+.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { signOut = true }) { Text("Sign out") }
                    Text("Independent companion app. Designed for smart EV scooters.", style = MaterialTheme.typography.bodySmall)
                    Text("Athr+ ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text("Sign out of Athr+?") },
        text = { Text("Monitoring will stop. You’ll need an OTP to sign in again.") },
        confirmButton = { TextButton(onClick = { signOut = false; onLogout() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } })
}
