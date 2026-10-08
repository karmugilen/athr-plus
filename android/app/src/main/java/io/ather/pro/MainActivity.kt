package io.ather.pro

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.ather.pro.data.update.GithubAppUpdateRepository
import io.ather.pro.presentation.AppUpdateViewModel
import io.ather.pro.ui.update.AppUpdateDialog
import io.ather.pro.ui.update.AppUpdateBanner
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ather.pro.data.auth.AuthStep
import io.ather.pro.presentation.AtherDashboardViewModel
import io.ather.pro.presentation.AuthViewModel
import io.ather.pro.ui.AtherAppShell
import io.ather.pro.ui.auth.AuthScreen
import io.ather.pro.ui.theme.AtherProTheme
import io.ather.pro.util.ChargingNotificationManager

class MainActivity : ComponentActivity() {
    private val updates by lazy { GithubAppUpdateRepository.getInstance(this) }
    private val updateViewModel: AppUpdateViewModel by viewModels { AppUpdateViewModel.Factory(updates) }
    private var openUpdates by mutableStateOf(false)
    private var canInstallUpdates by mutableStateOf(false)
    private var openInsightsRequest by mutableStateOf(0)


    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    private val sessionStore get() = appContainer.sessionStore
    private val authApi get() = appContainer.authApi
    private val repository get() = appContainer.repository

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(sessionStore, authApi, repository)
    }

    private val dashboardViewModel: AtherDashboardViewModel by viewModels {
        AtherDashboardViewModel.Factory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openUpdates = intent.getBooleanExtra(GithubAppUpdateRepository.OPEN_UPDATES, false)
        if (intent.getBooleanExtra("open_insights", false)) openInsightsRequest++
        enableEdgeToEdge()

        ChargingNotificationManager.getInstance(applicationContext).createNotificationChannels()

        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }

        setContent {
            AtherProTheme {
                io.ather.pro.ui.AppRefreshRate()
                val appUpdate by updateViewModel.state.collectAsStateWithLifecycle()
                val authState by authViewModel.ui.collectAsStateWithLifecycle()
                val dashboard by dashboardViewModel.dashboard.collectAsStateWithLifecycle()
                val chargeLimit by dashboardViewModel.chargeLimit.collectAsStateWithLifecycle()
                val insights by dashboardViewModel.insights.collectAsStateWithLifecycle()
                val monitoring by appContainer.monitoring.state.collectAsStateWithLifecycle()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (authState.step != AuthStep.READY || authState.session?.isComplete != true) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(
                                    WindowInsets.statusBars
                                        .union(WindowInsets.navigationBars)
                                        .union(WindowInsets.displayCutout)
                                )
                        ) {
                            AppUpdateBanner(appUpdate) { openUpdates = true }
                            Box(Modifier.weight(1f)) {
                                AuthScreen(
                                    state = authState,
                                    onPhoneChanged = authViewModel::onPhoneChanged,
                                    onCountryChanged = authViewModel::onCountryChanged,
                                    onOtpChanged = authViewModel::onOtpChanged,
                                    onRequestOtp = authViewModel::requestOtp,
                                    onVerifyOtp = authViewModel::verifyOtp,
                                    onSelectScooter = authViewModel::selectScooter,
                                    onRetryScooters = authViewModel::retryScooters,
                                    onBackToPhone = authViewModel::backToPhone
                                )
                            }
                        }
                    } else {
                        AtherAppShell(
                            updateState = appUpdate,
                            onCheckUpdate = { updateViewModel.check() },
                            onOpenUpdate = { openUpdates = true },
                            session = requireNotNull(authState.session),
                            dashboard = dashboard,
                            chargeLimit = chargeLimit,
                            monitoring = monitoring,
                            onMonitoringChange = appContainer.monitoring::setAlwaysEnabled,
                            onRefresh = dashboardViewModel::refresh,
                            onModelChange = dashboardViewModel::setScooterModel,
                            onTariffChange = dashboardViewModel::setTariffRate,
                            onClearTrips = dashboardViewModel::clearTripHistory,
                            onPauseCharging = { dashboardViewModel.pauseCharging() },
                            onResumeCharging = { dashboardViewModel.resumeCharging() },
                            onClearRemoteChargingLatch = dashboardViewModel::clearRemoteChargingLatch,
                            onChargeLimitEnabledChange = { enabled ->
                                dashboardViewModel.setChargeLimit(enabled, chargeLimit.percent)
                            },
                            onChargeLimitPercentChange = { percent, power ->
                                dashboardViewModel.setChargeLimit(true, percent, power)
                            },
                            onChargeLimitRetry = dashboardViewModel::retryChargeLimit,
                            insights = insights,
                            onDailyPlanChange = dashboardViewModel::updateDailyPlan,
                            onTyreRemindersChange = dashboardViewModel::setTyreReminders,
                            openInsightsRequest = openInsightsRequest,
                            onLogout = authViewModel::logout
                        )
                    }
                }
                if (openUpdates && appUpdate.release != null) AppUpdateDialog(
                    state = appUpdate,
                    canInstall = canInstallUpdates,
                    onDownload = updateViewModel::download,
                    onInstall = {
                        lifecycleScope.launch {
                            updates.installationUri()?.let { uri ->
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                } catch (_: Exception) { updates.reportInstallError() }
                            }
                        }
                    },
                    onAllowInstall = {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    },
                    onReleaseDetails = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appUpdate.release!!.pageUrl))) },
                    onDismiss = { openUpdates = false }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canInstallUpdates = packageManager.canRequestPackageInstalls()
        updateViewModel.check(force = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(GithubAppUpdateRepository.OPEN_UPDATES, false)) openUpdates = true
        if (intent.getBooleanExtra("open_insights", false)) openInsightsRequest++
    }

    override fun onStart() {
        super.onStart()
        appContainer.monitoring.onVisible()
    }

    override fun onStop() {
        appContainer.monitoring.onHidden()
        super.onStop()
    }

}
