package io.ather.pro.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface as DisplaySurface
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ather.pro.domain.model.GpsData
import io.ather.pro.ui.maps.StreetMapView
import io.ather.pro.ui.maps.CompassHeading
import io.ather.pro.ui.maps.CompassReading
import io.ather.pro.ui.maps.HeadingSampleGate
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled", "MissingPermission")
@Composable
fun MapSection(
    gps: GpsData?,
    modifier: Modifier = Modifier,
    gpsUpdatedAt: Long? = null
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapPreferences = remember { context.getSharedPreferences("ather_map_preferences", Context.MODE_PRIVATE) }
    val mapThemes = listOf("night" to "Night", "day" to "Day", "neon" to "Neon")
    var mapTheme by remember {
        mutableStateOf(mapPreferences.getString("theme", "night")
            ?.takeIf { name -> mapThemes.any { it.first == name } } ?: "night")
    }
    var mapMode by remember {
        mutableStateOf(mapPreferences.getString("mode", "heading")
            ?.takeIf { it == "heading" || it == "north" } ?: "heading")
    }

    val atherLat = gps?.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
    val atherLng = gps?.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
    val atherAcc = gps?.accuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }
    val hasScooterFix = atherLat != null && atherLng != null

    // Retain complete phone fixes, including their altitude and monotonic timestamp.
    var phoneLocation by remember { mutableStateOf<Location?>(null) }
    var compassReading by remember { mutableStateOf<CompassReading?>(null) }
    var compassAvailable by remember { mutableStateOf(false) }
    var showCompassHelp by remember { mutableStateOf(false) }
    var freshnessTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                freshnessTick += 1L
                delay(1_000L)
            }
        }
    }
    val nowNs = remember(phoneLocation, compassReading, freshnessTick) { SystemClock.elapsedRealtimeNanos() }
    // Retain the last position through reception gaps; label its age instead of hiding it.
    val phoneFix = phoneLocation
    val phoneFixAgeSeconds = phoneFix?.let { ((nowNs - it.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0L) }
    val phoneLat = phoneFix?.latitude
    val phoneLng = phoneFix?.longitude
    val phoneAcc = phoneFix?.takeIf { it.hasAccuracy() }?.accuracy
    val phoneAlt = phoneFix?.takeIf { it.hasAltitude() && it.altitude.isFinite() }?.altitude ?: 0.0
    var hasLocationPermission by remember { mutableStateOf(checkLocationPermission(context)) }
    var hasPreciseLocationPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var locationServicesOn by remember { mutableStateOf(checkLocationServicesEnabled(context)) }

    // Never substitute the scooter's position or (0,0) for the phone's north correction.
    val declination = remember(phoneLat, phoneLng, phoneAlt) {
        if (phoneLat != null && phoneLng != null) {
            GeomagneticField(phoneLat.toFloat(), phoneLng.toFloat(), phoneAlt.toFloat(),
                System.currentTimeMillis()).declination
        } else null
    }
    val freshReading = compassReading?.takeIf {
        CompassHeading.isFresh(it.timestampNs, nowNs, CompassHeading.SENSOR_MAX_AGE_NS)
    }
    val azimuthDegrees = CompassHeading.trueNorth(freshReading?.magneticDegrees, declination,
        freshReading?.accuracy ?: SensorManager.SENSOR_STATUS_UNRELIABLE, freshReading?.headingErrorDegrees)
    val compassStatus = when {
        !compassAvailable -> "Compass sensor unavailable · north up"
        freshReading == null -> "Waiting for compass · north up"
        freshReading.accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE ->
            "Compass unreliable · move away from magnets · north up"
        freshReading.magneticDegrees == null -> "Tilt the phone away from upright · north up"
        (freshReading.headingErrorDegrees ?: 0f) > CompassHeading.MAX_HEADING_ERROR_DEGREES ->
            "Compass error too large · tap the compass for help · north up"
        phoneFix == null -> "Waiting for phone location for true north · north up"
        freshReading.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
            "Compass accuracy low · tap the compass for help"
        else -> freshReading.headingErrorDegrees?.let {
            "True north · estimated compass error ±${kotlin.math.ceil(it.toDouble()).toInt()}°"
        } ?: "True north · compass error estimate unavailable"
    }

    DisposableEffect(lifecycleOwner, context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val accelSensor = if (rotationSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val magnetSensor = if (rotationSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null
        val sampleGate = HeadingSampleGate()
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val displayMatrix = FloatArray(9)
        var hasGravity = false
        var hasGeomagnetic = false
        var accelAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        var magnetAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

        fun clearCompass() {
            compassReading = null
            sampleGate.reset()
            hasGravity = false
            hasGeomagnetic = false
            accelAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
            magnetAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        }

        val compassListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val type = event.sensor.type
                val vector = type == Sensor.TYPE_ROTATION_VECTOR || type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
                if (!vector && type != Sensor.TYPE_ACCELEROMETER && type != Sensor.TYPE_MAGNETIC_FIELD) return
                if (event.values.size < 3 || (0..2).any { !event.values[it].isFinite() }) {
                    compassReading = null
                    return
                }
                val accuracy: Int
                val headingError: Float?
                if (vector) {
                    accuracy = event.accuracy
                    headingError = CompassHeading.headingErrorDegrees(event.values)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                } else {
                    val target = if (type == Sensor.TYPE_ACCELEROMETER) gravity else geomagnetic
                    val initialized = if (type == Sensor.TYPE_ACCELEROMETER) hasGravity else hasGeomagnetic
                    for (index in 0..2) {
                        target[index] = if (initialized) 0.8f * target[index] + 0.2f * event.values[index]
                            else event.values[index]
                    }
                    if (type == Sensor.TYPE_ACCELEROMETER) {
                        hasGravity = true
                        accelAccuracy = event.accuracy
                    } else {
                        hasGeomagnetic = true
                        magnetAccuracy = event.accuracy
                    }
                    if (!hasGravity || !hasGeomagnetic) return
                    accuracy = minOf(accelAccuracy, magnetAccuracy)
                    headingError = null
                    if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                        compassReading = null
                        return
                    }
                }

                @Suppress("DEPRECATION")
                val displayRotation = windowManager?.defaultDisplay?.rotation ?: DisplaySurface.ROTATION_0
                val (axisX, axisY) = when (displayRotation) {
                    DisplaySurface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    DisplaySurface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    DisplaySurface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                val magnetic = if (SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, displayMatrix)) {
                    CompassHeading.magneticAzimuth(displayMatrix)
                } else null
                // Publish loss of accuracy immediately; throttle only usable heading samples.
                if (accuracy <= 0 || magnetic == null || sampleGate.shouldPublish(event.timestamp)) {
                    compassReading = CompassReading(magnetic, accuracy, headingError, event.timestamp)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) compassReading = null
            }
        }

        fun startCompass() {
            sensorManager?.unregisterListener(compassListener)
            clearCompass()
            compassAvailable = if (rotationSensor != null && sensorManager != null) {
                sensorManager.registerListener(compassListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            } else if (sensorManager != null && accelSensor != null && magnetSensor != null) {
                val accelRegistered = sensorManager.registerListener(compassListener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
                val magnetRegistered = sensorManager.registerListener(compassListener, magnetSensor, SensorManager.SENSOR_DELAY_GAME)
                if (!accelRegistered || !magnetRegistered) sensorManager.unregisterListener(compassListener)
                accelRegistered && magnetRegistered
            } else false
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) startCompass()
            if (event == Lifecycle.Event.ON_PAUSE) {
                sensorManager?.unregisterListener(compassListener)
                clearCompass()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startCompass()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager?.unregisterListener(compassListener)
        }
    }

    // Phone GPS: watch permission + location toggle; subscribe the moment location is on.

    DisposableEffect(lifecycleOwner) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var subscribed = false

        fun clearPhoneFix() {
            phoneLocation = null
        }

        fun applyFix(location: Location) {
            if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
                !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return
            // Show Android's coordinates directly. Only ignore an older provider/cached callback.
            if (phoneLocation?.let { location.elapsedRealtimeNanos < it.elapsedRealtimeNanos } == true) return
            phoneLocation = Location(location)
        }

        lateinit var refreshSubscription: () -> Unit

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = applyFix(location)
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                locationServicesOn = true
                refreshSubscription()
            }
            override fun onProviderDisabled(provider: String) {
                locationServicesOn = checkLocationServicesEnabled(context)
                if (!locationServicesOn) {
                    if (subscribed && locationManager != null) {
                        runCatching { locationManager.removeUpdates(this) }
                        subscribed = false
                    }
                    clearPhoneFix()
                } else {
                    refreshSubscription()
                }
            }
        }

        fun unsubscribe() {
            if (!subscribed || locationManager == null) return
            runCatching { locationManager.removeUpdates(locationListener) }
            subscribed = false
        }

        @SuppressLint("MissingPermission")
        refreshSubscription = {
            hasLocationPermission = checkLocationPermission(context)
            hasPreciseLocationPermission = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            locationServicesOn = checkLocationServicesEnabled(context)
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !hasLocationPermission || !locationServicesOn || locationManager == null) {
                unsubscribe()
                if (!locationServicesOn || !hasLocationPermission) clearPhoneFix()
            } else {
                unsubscribe()
                try {
                    val fusedEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        runCatching { locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)
                    val providers = buildList {
                        if (fusedEnabled) add(LocationManager.FUSED_PROVIDER)
                        if (hasPreciseLocationPermission) add(LocationManager.GPS_PROVIDER)
                        add(LocationManager.NETWORK_PROVIDER)
                    }
                    val cached = mutableListOf<Location>()
                    for (provider in providers) {
                        val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
                        if (!enabled) continue
                        // Refresh fix age even while stationary; heading needs a current phone reference.
                        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val request = LocationRequest.Builder(1_000L)
                                .setMinUpdateIntervalMillis(1_000L)
                                .setMinUpdateDistanceMeters(0f)
                                .setMaxUpdateDelayMillis(0L)
                                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
                            locationManager.requestLocationUpdates(provider, request, context.mainExecutor, locationListener)
                        } else locationManager.requestLocationUpdates(provider, 1_000L, 0f, locationListener, Looper.getMainLooper())
                        } catch (_: IllegalArgumentException) { continue }
                        subscribed = true
                        val last = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                        val ageNs = last?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
                        if (last != null && ageNs != null && ageNs in 0L..300_000_000_000L) cached += last
                    }
                    cached.maxByOrNull { it.elapsedRealtimeNanos }?.let(::applyFix)
                } catch (_: SecurityException) {
                    hasLocationPermission = false
                    unsubscribe()
                    clearPhoneFix()
                }
            }
        }

        val providerReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshSubscription()
            }
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            providerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSubscription()
            }
            if (event == Lifecycle.Event.ON_PAUSE) unsubscribe()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        refreshSubscription()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { context.unregisterReceiver(providerReceiver) }
            unsubscribe()
        }
    }

    // Distance calculation between Phone and Ather (Haversine formula)
    val distanceMeters: Double? = remember(phoneLat, phoneLng, atherLat, atherLng) {
        if (phoneLat != null && phoneLng != null && atherLat != null && atherLng != null) {
            io.ather.pro.domain.location.distanceMeters(phoneLat, phoneLng, atherLat, atherLng)
        } else null
    }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(mapTheme, pageLoaded, webViewInstance) {
        if (!pageLoaded) return@LaunchedEffect
        webViewInstance?.evaluateJavascript(
            "window.setMapTheme('$mapTheme');", null
        )
    }
    LaunchedEffect(mapMode, pageLoaded, webViewInstance) {
        if (!pageLoaded) return@LaunchedEffect
        webViewInstance?.evaluateJavascript(
            "window.setMapMode('$mapMode');", null
        )
    }

    // Heading is the sole orientation source; GPS marker updates only carry position.
    LaunchedEffect(azimuthDegrees, pageLoaded, webViewInstance) {
        if (!pageLoaded) return@LaunchedEffect
        val heading = azimuthDegrees?.let { String.format(Locale.US, "%.4f", it) } ?: "null"
        webViewInstance?.evaluateJavascript("if (window.setMapHeading) { window.setMapHeading($heading); }", null)
    }

    LaunchedEffect(atherLat, atherLng, atherAcc, phoneLat, phoneLng, phoneAcc, pageLoaded, webViewInstance) {
        val view = webViewInstance ?: return@LaunchedEffect
        if (!pageLoaded) return@LaunchedEffect

        if (atherLat != null && atherLng != null) {
            val jsAther = String.format(
                Locale.US,
                "if (window.updateAtherMarker) { window.updateAtherMarker(%.6f, %.6f, %.1f); }",
                atherLat,
                atherLng,
                atherAcc ?: 0.0
            )
            view.evaluateJavascript(jsAther, null)
        } else {
            view.evaluateJavascript(
                "if (window.removeAtherMarker) { window.removeAtherMarker(); }",
                null
            )
        }

        if (phoneLat != null && phoneLng != null) {
            val jsPhone = String.format(
                Locale.US,
                "if (window.updatePhoneMarker) { window.updatePhoneMarker(%.6f, %.6f, %.1f); }",
                phoneLat!!,
                phoneLng!!,
                phoneAcc ?: 0.0f
            )
            view.evaluateJavascript(jsPhone, null)

        } else {
            view.evaluateJavascript(
                "if (window.removePhoneMarker) { window.removePhoneMarker(); }",
                null
            )
        }
    }

    if (showCompassHelp) {
        AlertDialog(
            onDismissRequest = { showCompassHelp = false },
            title = { Text("Improve compass accuracy") },
            text = { Text("Move away from magnetic mounts, metal, and the scooter. Slowly move your phone " +
                "in a figure eight, then hold it fairly flat and check the compass accuracy below the map. " +
                "This map uses true north, which can differ from a compass set to magnetic north.") },
            confirmButton = { TextButton(onClick = { showCompassHelp = false }) { Text("Got it") } }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Top Bar: Navigation Header + Mode Switch (Tactical Map vs Radar HUD)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = colorScheme.secondary
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "SCOOTER LOCATION",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Surface(
                    color = colorScheme.secondary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (mapMode == "heading" && azimuthDegrees != null) "HEADING UP" else "NORTH UP",
                        color = colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mapThemes.forEach { (id, label) ->
                    FilterChip(selected = mapTheme == id, onClick = {
                        mapTheme = id
                        mapPreferences.edit().putString("theme", id).apply()
                    }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("heading" to "Heading up", "north" to "North up").forEach { (id, label) ->
                    FilterChip(selected = mapMode == id, onClick = {
                        mapMode = id
                        mapPreferences.edit().putString("mode", id).apply()
                    }, label = { Text(label) })
                }
                androidx.compose.material3.TextButton(onClick = {
                    webViewInstance?.evaluateJavascript("window.followScooter();", null)
                }, enabled = hasScooterFix) { Text("Scooter") }
                androidx.compose.material3.TextButton(onClick = {
                    webViewInstance?.evaluateJavascript("window.fitBoth();", null)
                }) { Text("Show both") }
            }

            // 1. Full-Width Edge-to-Edge Circular Minimap (Utilizes all available width without wasted space)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val mapHeight = (maxWidth * 1.25f).coerceIn(340.dp, 520.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    // Layer 1: Live Leaflet Street Map (1:1 Touch Geometry - perfectly responsive dragging & panning)
                    StreetMapView(modifier = Modifier.matchParentSize()) { view ->
                        webViewInstance = view
                        pageLoaded = view != null
                    }

                }

                // Floating Zoom and Fit Controls Overlay (Right side of Map)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript("if (window.zoomIn) { window.zoomIn(); }", null)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Zoom In",
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript("if (window.zoomOut) { window.zoomOut(); }", null)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Zoom Out",
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = {
                                webViewInstance?.evaluateJavascript("if (window.followMe) { window.followMe(); }", null)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CenterFocusStrong,
                                contentDescription = "Follow my location",
                                modifier = Modifier.size(18.dp),
                                tint = colorScheme.secondary
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surface.copy(alpha = 0.90f),
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = { showCompassHelp = true },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "Improve compass accuracy"
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (azimuthDegrees == null || freshReading?.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                                    colorScheme.error else colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Text(
                text = "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/copyright"))) }
                }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = compassStatus,
                color = if (azimuthDegrees == null || freshReading?.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                    colorScheme.error else colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().clickable { showCompassHelp = true }
            )

            if (!hasPreciseLocationPermission || !locationServicesOn) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !hasLocationPermission -> "Location permission is off — allow location for this app"
                        !hasPreciseLocationPermission -> "Approximate location is enabled — tap to allow precise location"
                        else -> "Location is not turned on"
                    },
                    color = colorScheme.error,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = if (!hasPreciseLocationPermission) {
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            } else {
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                        .semantics {
                            contentDescription = "Open location settings"
                        }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Dual GPS Metrics & Live Accuracy Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Scooter GPS Dot status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "SCOOTER",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = atherAcc?.let {
                                val accuracy = if (it < 1.0) "${(it * 100).toInt()} cm" else "${String.format(Locale.US, "%.1f", it)} m"
                                "Accuracy: ±$accuracy"
                            } ?: if (hasScooterFix) "Accuracy unknown" else "Waiting for scooter GPS",
                            color = colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                }

                // Right: Phone GPS Arrow status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = colorScheme.tertiary
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "YOUR PHONE",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
                        )
                        val phoneStatus = when {
                            !hasLocationPermission -> "Permission off"
                            !locationServicesOn -> "Location off"
                            phoneFixAgeSeconds != null && phoneFixAgeSeconds > 15 ->
                                if (phoneFixAgeSeconds < 60) "Last position · ${phoneFixAgeSeconds}s ago"
                                else "Last position · ${phoneFixAgeSeconds / 60} min ago"
                            phoneLat != null && phoneAcc != null -> {
                                val a = phoneAcc!!
                                val accText = if (a < 1.0f) {
                                    "${(a * 100).toInt()} cm"
                                } else {
                                    String.format(Locale.US, "%.1f", a) + " m"
                                }
                                "Accuracy: ±$accText"
                            }
                            else -> "Acquiring fix…"
                        }
                        Text(
                            text = phoneStatus,
                            color = if (!hasLocationPermission || !locationServicesOn) {
                                colorScheme.error
                            } else {
                                colorScheme.onSurface
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                }
            }

            gpsUpdatedAt?.let { timestamp ->
                Text("Scooter location received " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp)),
                    modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant)
            }

            // Live distance guidance banner
            if (distanceMeters != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Straight-line Distance:",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                        )
                        val distFormatted = if (distanceMeters < 1000) {
                            "${distanceMeters.toInt()} meters away"
                        } else {
                            "${String.format(Locale.US, "%.2f", distanceMeters / 1000.0)} km away"
                        }
                        Text(
                            text = distFormatted,
                            color = colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // One-Tap Find My Scooter — Maps package, geo intent, then web fallback
            Button(
                onClick = {
                    if (atherLat == null || atherLng == null) {
                        Toast.makeText(context, "Scooter GPS location is not available yet", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val opened = openFindMyNavigation(context, atherLat, atherLng)
                    if (!opened) {
                        Toast.makeText(context, "Could not open navigation to scooter", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (hasScooterFix) {
                            "Find My Scooter. Open walking navigation to scooter location"
                        } else {
                            "Find My Scooter unavailable. Waiting for scooter GPS fix"
                        }
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary
                ),
                enabled = hasScooterFix,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NearMe,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (hasScooterFix) "Navigate to Scooter" else "Waiting for GPS fix",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onPrimary
                    )
                )
            }
        }
    }
}

/** Try Google Maps walking nav, then generic geo intent, then web directions. */
private fun openFindMyNavigation(context: Context, lat: Double, lng: Double): Boolean {
    val gmmIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("google.navigation:q=$lat,$lng&mode=w")
    ).apply {
        setPackage("com.google.android.apps.maps")
    }
    val geoIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:$lat,$lng?q=$lat,$lng(Scooter)")
    )
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=walking")
    )
    return listOf(gmmIntent, geoIntent, webIntent).any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

private fun checkLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun checkLocationServicesEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
        runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false))
}
