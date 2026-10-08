package io.ather.pro.ui.chargingmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import io.ather.pro.data.chargingmap.ChargerLocationCache
import io.ather.pro.data.chargingmap.ChargingMapApi
import io.ather.pro.domain.chargingmap.ChargerLocation
import io.ather.pro.ui.maps.MapTiles
import io.ather.pro.domain.chargingmap.ChargingMapLoadState
import io.ather.pro.domain.chargingmap.WalletSnapshot
import io.ather.pro.ui.theme.AtherAccent
import io.ather.pro.ui.theme.AtherBackground
import io.ather.pro.ui.theme.AtherCard
import io.ather.pro.ui.theme.AtherError
import io.ather.pro.ui.theme.AtherGreen
import io.ather.pro.ui.theme.AtherText
import io.ather.pro.ui.theme.AtherTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Self-contained public charger map + wallet surface for the final integrator.
 * Does not wire into the main dashboard or navigation.
 *
 * Uses the encrypted-session JWT supplied by the caller. Shows honest
 * loading / empty / error states and never fabricates balances or chargers.
 */
@Composable
fun ChargingMapScreen(
    sessionToken: String?,
    modifier: Modifier = Modifier,
    api: ChargingMapApi = remember { ChargingMapApi() },
    /** Optional map center override (e.g. scooter GPS). When null, uses device location if permitted. */
    preferredCenterLat: Double? = null,
    preferredCenterLng: Double? = null,
    radiusKm: Int = ChargingMapApi.DEFAULT_RADIUS_KM,
    limit: Int = ChargingMapApi.DEFAULT_LIMIT,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    var walletState by remember {
        mutableStateOf<ChargingMapLoadState<WalletSnapshot>>(ChargingMapLoadState.Idle)
    }
    var chargersState by remember {
        mutableStateOf<ChargingMapLoadState<List<ChargerLocation>>>(ChargingMapLoadState.Idle)
    }
    var selected by remember { mutableStateOf<ChargerLocation?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var centerLat by remember { mutableStateOf(preferredCenterLat) }
    var centerLng by remember { mutableStateOf(preferredCenterLng) }
    var locationNote by remember { mutableStateOf<String?>(null) }
    var chargersNote by remember { mutableStateOf<String?>(null) }
    var tilesFailedMessage by remember { mutableStateOf<String?>(null) }
    val chargerCache = remember { ChargerLocationCache(File(context.cacheDir, "saved-chargers.json")) }

    fun refresh() {
        val token = sessionToken?.takeIf { it.isNotBlank() }
        if (token == null) {
            walletState = ChargingMapLoadState.Error("Sign in required for wallet and chargers")
            chargersState = ChargingMapLoadState.Error("Sign in required for wallet and chargers")
            return
        }

        walletState = ChargingMapLoadState.Loading
        if (chargersState !is ChargingMapLoadState.Ready) chargersState = ChargingMapLoadState.Loading
        selected = null

        scope.launch {
            val walletResult = withContext(Dispatchers.IO) { api.fetchWallet(token) }
            walletState = walletResult.fold(
                onSuccess = { ChargingMapLoadState.Ready(it) },
                onFailure = { ChargingMapLoadState.Error(it.message ?: "Wallet request failed") },
            )

            val resolved = resolveMapCenter(
                context = context,
                preferredLat = preferredCenterLat,
                preferredLng = preferredCenterLng,
            )
            centerLat = resolved.lat
            centerLng = resolved.lng
            locationNote = resolved.note

            if (resolved.lat == null || resolved.lng == null) {
                chargersState = ChargingMapLoadState.Error(
                    resolved.note ?: "Location unavailable — enable location or provide a map center"
                )
                return@launch
            }

            val cached = withContext(Dispatchers.IO) { chargerCache.loadNear(resolved.lat, resolved.lng) }
            if (cached != null) {
                chargersState = ChargingMapLoadState.Ready(cached)
                chargersNote = "Saved chargers · updating"
            } else if (chargersState !is ChargingMapLoadState.Ready) {
                chargersState = ChargingMapLoadState.Loading
            }

            val locationsResult = withContext(Dispatchers.IO) {
                api.fetchLocations(
                    token,
                    ChargingMapApi.LocationsQuery(
                        latitude = resolved.lat,
                        longitude = resolved.lng,
                        radiusKm = radiusKm,
                        limit = limit,
                    ),
                )
            }
            locationsResult.fold(
                onSuccess = { list ->
                    chargersNote = null
                    chargersState = if (list.isEmpty()) {
                        ChargingMapLoadState.Empty("No public chargers in this area")
                    } else {
                        withContext(Dispatchers.IO) { chargerCache.save(resolved.lat, resolved.lng, list) }
                        ChargingMapLoadState.Ready(list)
                    }
                },
                onFailure = {
                    if (chargersState is ChargingMapLoadState.Ready) {
                        chargersNote = "Showing saved chargers"
                    } else {
                        chargersState = ChargingMapLoadState.Error(it.message ?: "Charger request failed")
                    }
                },
            )
        }
    }

    LaunchedEffect(sessionToken, preferredCenterLat, preferredCenterLng, radiusKm, limit) {
        refresh()
    }

    // Push markers into the WebView when both map and data are ready.
    LaunchedEffect(mapReady, chargersState, centerLat, centerLng) {
        val view = webView ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        val lat = centerLat
        val lng = centerLng
        if (lat != null && lng != null) {
            view.evaluateJavascript(
                "if (window.setMapCenter) { window.setMapCenter($lat, $lng, 13); }",
                null,
            )
            view.evaluateJavascript(
                "if (window.setUserLocation) { window.setUserLocation($lat, $lng); }",
                null,
            )
        }
        when (val state = chargersState) {
            is ChargingMapLoadState.Ready -> {
                val payload = state.value.mapIndexed { index, ch ->
                    mapOf(
                        "id" to index.toString(),
                        "lat" to ch.latitude,
                        "lng" to ch.longitude,
                        "name" to (ch.name ?: "Public charger"),
                        "address" to ch.address,
                        "dbsAvailable" to ch.dbsAvailable,
                        "dbsTotal" to ch.dbsTotal,
                        "isOpenNow" to ch.isOpenNow,
                        "connectors" to ch.connectors.mapNotNull { it.displayText ?: it.standard },
                    )
                }
                val json = gson.toJson(payload)
                val encoded = Base64.encodeToString(
                    json.toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP,
                )
                view.evaluateJavascript(
                    "if (window.setChargers) { window.setChargers(JSON.parse(atob('$encoded'))); }",
                    null,
                )
            }
            is ChargingMapLoadState.Empty, is ChargingMapLoadState.Error -> {
                view.evaluateJavascript("if (window.clearChargers) { window.clearChargers(); }", null)
            }
            else -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AtherBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Public chargers & wallet",
            color = AtherText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )

        WalletCard(state = walletState)

        locationNote?.let { note ->
            Text(text = note, color = AtherTextMuted, fontSize = 12.sp)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AtherCard)
                .semantics { contentDescription = "Public charger map" },
        ) {
            ChargerMapWebView(
                onReady = { mapReady = true },
                onWebView = { webView = it },
                onChargerSelected = { id ->
                    val list = (chargersState as? ChargingMapLoadState.Ready)?.value.orEmpty()
                    selected = id.toIntOrNull()?.let { list.getOrNull(it) }
                },
                onTilesFailed = { reason ->
                    tilesFailedMessage = reason
                },
            )

            when (val mapState = chargersState) {
                ChargingMapLoadState.Loading, ChargingMapLoadState.Idle -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AtherGreen, modifier = Modifier.size(28.dp))
                    }
                }
                is ChargingMapLoadState.Error -> {
                    StatusOverlay(message = mapState.message, isError = true)
                }
                is ChargingMapLoadState.Empty -> {
                    StatusOverlay(message = mapState.message, isError = false)
                }
                is ChargingMapLoadState.Ready -> Unit
            }

            tilesFailedMessage?.let { tileMsg ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(AtherCard.copy(alpha = 0.92f))
                        .padding(8.dp),
                ) {
                    Text(
                        text = "$tileMsg — charger list below still works.",
                        color = AtherTextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // Always-visible list fallback (usable even when tiles fail).
        Text(
            text = when (val state = chargersState) {
                is ChargingMapLoadState.Ready -> if (chargersNote.isNullOrBlank()) {
                    "${state.value.size} chargers nearby"
                } else {
                    "${state.value.size} chargers nearby · $chargersNote"
                }
                is ChargingMapLoadState.Empty -> "Charger list"
                is ChargingMapLoadState.Error -> "Charger list unavailable"
                else -> "Charger list"
            },
            color = AtherTextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )

        when (val state = chargersState) {
            is ChargingMapLoadState.Ready -> {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = true),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.value, key = { "${it.latitude},${it.longitude},${it.name}" }) { charger ->
                        ChargerListRow(
                            charger = charger,
                            selected = charger == selected,
                            onClick = { selected = charger },
                        )
                    }
                }
            }
            is ChargingMapLoadState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AtherCard),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.message, color = AtherError, fontSize = 13.sp)
                        Text(
                            "Enable location or wait for scooter GPS, then retry. Wallet may still load.",
                            color = AtherTextMuted,
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = { refresh() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AtherAccent,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text("Retry chargers")
                        }
                    }
                }
                Spacer(Modifier.weight(1f, fill = true))
            }
            is ChargingMapLoadState.Empty -> {
                Text(state.message, color = AtherTextMuted, fontSize = 13.sp)
                Spacer(Modifier.weight(1f, fill = true))
            }
            else -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    CircularProgressIndicator(color = AtherGreen, modifier = Modifier.size(24.dp))
                }
            }
        }

        selected?.let { ChargerDetailsCard(it) }

        Button(
            onClick = {
                tilesFailedMessage = null
                refresh()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AtherAccent, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
            Text("Refresh")
        }
    }
}

@Composable
private fun WalletCard(state: ChargingMapLoadState<WalletSnapshot>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AtherCard),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Wallet", color = AtherTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            when (state) {
                ChargingMapLoadState.Idle, ChargingMapLoadState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = AtherGreen,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "  Loading wallet…",
                            color = AtherTextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
                is ChargingMapLoadState.Error -> {
                    Text(state.message, color = AtherError, fontSize = 13.sp)
                }
                is ChargingMapLoadState.Empty -> {
                    Text(state.message, color = AtherTextMuted, fontSize = 13.sp)
                }
                is ChargingMapLoadState.Ready -> {
                    val wallet = state.value
                    if (wallet.balance == null && wallet.credits == null) {
                        Text(
                            "Wallet returned no balance or credits",
                            color = AtherTextMuted,
                            fontSize = 13.sp,
                        )
                    } else {
                        wallet.balance?.let {
                            Text(
                                text = "₹${formatMoney(it)}",
                                color = AtherText,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        wallet.walletStatus?.let {
                            Text("Status: $it", color = AtherTextMuted, fontSize = 12.sp)
                        }
                        wallet.credits?.let {
                            Text("Credits: ${formatMoney(it)}", color = AtherGreen, fontSize = 13.sp)
                        }
                    }
                    if (wallet.transactions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Recent transactions", color = AtherTextMuted, fontSize = 11.sp)
                        wallet.transactions.take(5).forEach { txn ->
                            val amount = txn.amount?.let { formatMoney(it) } ?: "—"
                            val title = txn.title ?: txn.type ?: "Transaction"
                            Text(
                                text = "$title  ·  $amount",
                                color = AtherText,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargerListRow(
    charger: ChargerLocation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val availability = when {
        charger.dbsAvailable != null && charger.dbsTotal != null ->
            "${charger.dbsAvailable}/${charger.dbsTotal} free"
        charger.dbsAvailable != null -> "${charger.dbsAvailable} free"
        else -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AtherAccent.copy(alpha = 0.18f) else AtherCard,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = charger.name ?: "Public charger",
                color = AtherText,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            charger.address?.let {
                Text(it, color = AtherTextMuted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                availability?.let {
                    Text(it, color = AtherGreen, fontSize = 12.sp)
                }
                when (charger.isOpenNow) {
                    true -> Text("Open", color = AtherGreen, fontSize = 12.sp)
                    false -> Text("Closed", color = AtherError, fontSize = 12.sp)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChargerDetailsCard(charger: ChargerLocation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AtherCard),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                charger.name ?: "Charger details",
                color = AtherText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            charger.infraType?.let { Text(it, color = AtherTextMuted, fontSize = 12.sp) }
            charger.address?.let { Text(it, color = AtherText, fontSize = 13.sp) }
            if (charger.connectors.isNotEmpty()) {
                Text(
                    "Connectors: " + charger.connectors.joinToString { it.displayText ?: it.standard ?: "?" },
                    color = AtherText,
                    fontSize = 12.sp,
                )
            }
            if (charger.tariffLines.isNotEmpty()) {
                charger.tariffLines.forEach { line ->
                    val label = listOfNotNull(line.displayText, line.text).joinToString(": ")
                    if (label.isNotBlank()) {
                        Text(label, color = AtherTextMuted, fontSize = 12.sp)
                    }
                }
            }
            charger.closingIn?.let { Text("Closing in $it", color = AtherTextMuted, fontSize = 12.sp) }
            charger.nextOpening?.let { Text("Next opening: $it", color = AtherTextMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun StatusOverlay(message: String, isError: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AtherBackground.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = if (isError) AtherError else AtherTextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChargerMapWebView(
    onReady: () -> Unit,
    onWebView: (WebView) -> Unit,
    onChargerSelected: (String) -> Unit,
    onTilesFailed: (String) -> Unit = {},
) {
    val callbacks = remember {
        object {
            var ready: () -> Unit = {}
            var selected: (String) -> Unit = {}
            var tilesFailed: (String) -> Unit = {}
        }
    }
    callbacks.ready = onReady
    callbacks.selected = onChargerSelected
    callbacks.tilesFailed = onTilesFailed

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val jsBridge = ChargerMapJsBridge(
                mainHandler,
                object : ChargerMapJsBridge.Callbacks {
                    override fun onMapReady() {
                        callbacks.ready()
                    }

                    override fun onChargerSelected(id: String) {
                        callbacks.selected(id)
                    }

                    override fun onTilesFailed(reason: String) {
                        callbacks.tilesFailed(reason)
                    }
                },
            )
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mediaPlaybackRequiresUserGesture = true
                settings.allowFileAccess = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                addJavascriptInterface(jsBridge, "ChargerMapBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        MapTiles.intercept(view.context, request.url?.toString())

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Leaflet also notifies via bridge; this covers asset load completion.
                        callbacks.ready()
                    }
                }
                loadUrl("file:///android_asset/charger_map.html")
                onWebView(this)
            }
        },
        update = { onWebView(it) },
        onRelease = { view ->
            view.stopLoading()
            view.removeJavascriptInterface("ChargerMapBridge")
            view.removeAllViews()
            view.destroy()
        },
    )
}

private data class ResolvedCenter(
    val lat: Double?,
    val lng: Double?,
    val note: String?,
)

@SuppressLint("MissingPermission")
private fun resolveMapCenter(
    context: Context,
    preferredLat: Double?,
    preferredLng: Double?,
): ResolvedCenter {
    if (preferredLat != null && preferredLng != null &&
        preferredLat in -90.0..90.0 && preferredLng in -180.0..180.0
    ) {
        return ResolvedCenter(preferredLat, preferredLng, null)
    }

    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
        return ResolvedCenter(
            null,
            null,
            "Location permission not granted — pass a map center or enable location",
        )
    }

    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return ResolvedCenter(null, null, "Location services unavailable")

    val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
    }
    var best: Location? = null
    for (provider in providers) {
        val enabled = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        if (!enabled) continue
        val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
        if (best == null || loc.accuracy < best.accuracy) best = loc
    }
    val loc = best
    return if (loc != null && loc.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0) {
        // Coarse note only — do not surface raw coordinates in UI chrome.
        ResolvedCenter(loc.latitude, loc.longitude, "Using device location (last known)")
    } else {
        ResolvedCenter(null, null, "No recent device location available")
    }
}

private fun formatMoney(value: Double): String {
    val rounded = (value * 100.0).roundToInt() / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        String.format(Locale.US, "%.0f", rounded)
    } else {
        String.format(Locale.US, "%.2f", rounded)
    }
}
