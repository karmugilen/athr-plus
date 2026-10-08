package io.ather.pro.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.ather.pro.data.auth.AuthSession
import io.ather.pro.domain.update.AppUpdateState
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.domain.model.ScooterDashboardState
import io.ather.pro.domain.model.ScooterModel
import io.ather.pro.domain.monitoring.MonitoringState
import io.ather.pro.ui.components.ChargeEstimateCard
import io.ather.pro.ui.components.FreshnessLabel
import io.ather.pro.ui.update.AppUpdateBanner
import io.ather.pro.domain.insights.RiderInsights
import io.ather.pro.ui.insights.RiderInsightsScreen
import io.ather.pro.ui.insights.ChargingHistoryCard

private enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home), CHARGING("Charging", Icons.Default.BatteryChargingFull),
    MAP("Map", Icons.Default.Map), INSIGHTS("Insights", Icons.Default.Insights), SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtherAppShell(
    updateState: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit,
    session: AuthSession,
    dashboard: ScooterDashboardState,
    chargeLimit: ChargeLimitController.Snapshot,
    monitoring: MonitoringState,
    onMonitoringChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onModelChange: (ScooterModel) -> Unit,
    onTariffChange: (Double) -> Unit,
    onClearTrips: () -> Unit,
    onPauseCharging: () -> Unit,
    onResumeCharging: () -> Unit,
    onClearRemoteChargingLatch: () -> Unit,
    onChargeLimitEnabledChange: (Boolean) -> Unit,
    onChargeLimitPercentChange: (Int, Int) -> Unit,
    onChargeLimitRetry: () -> Unit,
    insights: RiderInsights,
    onDailyPlanChange: (Double, Int) -> Unit,
    onTyreRemindersChange: (Boolean) -> Unit,
    openInsightsRequest: Int = 0,
    onLogout: () -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(Destination.HOME) }
    LaunchedEffect(openInsightsRequest) { if (openInsightsRequest > 0) selected = Destination.INSIGHTS }
    val stateHolder = rememberSaveableStateHolder()
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("ATHR+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(if (selected == Destination.HOME) dashboard.vehicleProfile?.displayName
                        ?: dashboard.settings.selectedModel.displayName else selected.label,
                        style = MaterialTheme.typography.titleMedium)
                }
            }, actions = {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh scooter data") }
            })
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(selected = selected == destination, onClick = { selected = destination },
                        icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppUpdateBanner(updateState, onOpenUpdate)
            Box(Modifier.weight(1f)) {
                stateHolder.SaveableStateProvider(selected.name) {
                    when (selected) {
                        Destination.HOME -> AtherDashboardScreen(dashboard, chargeLimit,
                            onOpenCharging = { selected = Destination.CHARGING }, onOpenMap = { selected = Destination.MAP }, onClearTrips)
                        Destination.CHARGING -> LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { FreshnessLabel(dashboard) }
                            item { ChargeLimitCard(chargeLimit, dashboard, onChargeLimitEnabledChange, onChargeLimitPercentChange, onChargeLimitRetry) }
                            item { ChargingActions(dashboard.telemetry, dashboard.remoteChargingCommand,
                                onPauseCharging, onResumeCharging, onRetryLatch = onClearRemoteChargingLatch) }
                            item { ChargeEstimateCard(dashboard, chargeLimit.percent) }
                            item { ChargingHistoryCard(insights) }
                        }
                        Destination.MAP -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { FreshnessLabel(dashboard) }
                            item { MapSection(gps = dashboard.telemetry?.gps, gpsUpdatedAt = dashboard.gpsUpdatedAt) }
                        }
                        Destination.INSIGHTS -> RiderInsightsScreen(insights, dashboard, onDailyPlanChange,
                            onTyreRemindersChange)
                        Destination.SETTINGS -> SettingsScreen(session, dashboard, monitoring, chargeLimit.enabled,
                            updateState, onCheckUpdate, onOpenUpdate, onMonitoringChange, onModelChange, onTariffChange, onLogout)
                    }
                }
            }
        }
    }
}
