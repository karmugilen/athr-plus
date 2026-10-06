package io.ather.pro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ather.pro.domain.battery.kmPerUnitOrNull
import io.ather.pro.domain.battery.rideEfficiencyKmPerUnit
import io.ather.pro.domain.model.TripRecord
import io.ather.pro.domain.ride.RidePolyline
import io.ather.pro.ui.maps.RideRouteMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

private const val COMPACT_TRIP_COUNT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryPanel(
    trips: List<TripRecord>,
    tariffRate: Double,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var selectedTrip by remember { mutableStateOf<TripRecord?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val rides = remember(trips) { io.ather.pro.domain.ride.RideLog.openable(trips) }
    val visibleTrips = if (expanded || rides.size <= COMPACT_TRIP_COUNT) {
        rides
    } else {
        rides.take(COMPACT_TRIP_COUNT)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AUTO TRIP LOGS",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (rides.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = colorScheme.background,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "${rides.size}",
                                color = colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (trips.isNotEmpty()) {
                    TextButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Clear all trip logs"
                        }
                    ) {
                        Text(
                            text = "Clear",
                            color = colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (rides.isEmpty()) {
                if (trips.isEmpty()) EmptyTripHistoryState()
                else Text(
                    "Rides with a recorded speed show here. Open one to see it on the map.",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                TripOwnershipSummaryCard(
                    trips = rides,
                    tariffRate = tariffRate
                )

                visibleTrips.forEach { trip ->
                    TripRecordCard(
                        trip = trip,
                        tariffRate = tariffRate,
                        onClick = { selectedTrip = trip }
                    )
                }

                if (rides.size > COMPACT_TRIP_COUNT) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .semantics {
                                contentDescription = if (expanded) {
                                    "Show less trip history"
                                } else {
                                    "See all ${rides.size} trips"
                                }
                            }
                    ) {
                        Text(
                            text = if (expanded) {
                                "Show less"
                            } else {
                                "See all (${rides.size})"
                            },
                            color = colorScheme.secondary,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = colorScheme.surface,
            title = {
                Text(
                    text = "Clear Trip Logs",
                    color = colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to clear all trip history logs? This action cannot be undone.",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClear()
                    }
                ) {
                    Text("Clear", color = colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    selectedTrip?.let { trip ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedTrip = null },
            sheetState = sheetState,
            containerColor = colorScheme.surface,
            contentColor = colorScheme.onSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colorScheme.outline) }
        ) {
            TripDetailSheetContent(
                trip = trip,
                tariffRate = tariffRate,
                onDismiss = { selectedTrip = null }
            )
        }
    }
}

@Composable
private fun EmptyTripHistoryState() {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colorScheme.background)
                .border(1.dp, colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Timeline,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No Trips Recorded",
            color = colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Ready to record. This sync stores a baseline; after your next ride, the parked sync adds distance, efficiency, SoC usage, and cost here automatically.",
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun TripOwnershipSummaryCard(
    trips: List<TripRecord>,
    tariffRate: Double,
    modifier: Modifier = Modifier
) {
    if (trips.isEmpty()) return

    val colorScheme = MaterialTheme.colorScheme
    val totalDistanceKm = trips.sumOf { it.distanceKm }
    val kmPerUnit = rideEfficiencyKmPerUnit(trips)
    val totalCost = trips.sumOf { calculateCost(it, tariffRate) }
    val mostRecentTrip = trips.maxByOrNull { it.endTimeMs } ?: trips.first()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Trip Insights Summary: Total Distance ${String.format(Locale.US, "%.1f", totalDistanceKm)} km, km per unit ${kmPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "unavailable"}, Total Cost ₹${String.format(Locale.US, "%.2f", totalCost)}"
            },
        colors = CardDefaults.cardColors(containerColor = colorScheme.background),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OWNERSHIP INTELLIGENCE",
                    color = colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "${trips.size} ${if (trips.size == 1) "trip" else "trips"}",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryMetricItem(
                    label = "Total Dist",
                    value = String.format(Locale.US, "%.1f km", totalDistanceKm),
                    valueColor = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                SummaryMetricItem(
                    label = "km/unit",
                    value = kmPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                    valueColor = colorScheme.primary,
                    modifier = Modifier.weight(1.2f)
                )
                SummaryMetricItem(
                    label = "Total Cost",
                    value = String.format(Locale.US, "₹%.2f", totalCost),
                    valueColor = colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                    alignment = Alignment.End
                )
            }

            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Most Recent",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    text = "${String.format(Locale.US, "%.1f km", mostRecentTrip.distanceKm)} · ${formatShortDateTime(mostRecentTrip.endTimeMs)}",
                    color = colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

@Composable
private fun SummaryMetricItem(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start
) {
    Column(
        modifier = modifier,
        horizontalAlignment = alignment
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun TripRecordCard(
    trip: TripRecord,
    tariffRate: Double,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val effectiveCost = calculateCost(trip, tariffRate)
    val durationText = durationLabel(trip)
    val averageChip = averageSpeedChip(trip.averageSpeedKmh)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                role = Role.Button
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Trip ${String.format(Locale.US, "%.1f", trip.distanceKm)} km, Cost ₹${String.format(Locale.US, "%.2f", effectiveCost)}"
            },
        colors = CardDefaults.cardColors(containerColor = colorScheme.background),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = String.format(Locale.US, "%.1f km", trip.distanceKm),
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (durationText.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = colorScheme.surface,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = durationText,
                                color = colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (averageChip != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = colorScheme.surface,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = averageChip,
                                color = colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = String.format(Locale.US, "₹%.2f", effectiveCost),
                    color = colorScheme.secondary,
                    style = MaterialTheme.typography.titleMedium.copy(color = colorScheme.secondary)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatShortDateTime(trip.endTimeMs),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val kmPerUnit = trip.kmPerUnitOrNull()
                    Text(
                        text = when {
                            kmPerUnit != null && trip.isOfficialRide ->
                                String.format(Locale.US, "%.2f km/unit · Synced ride", kmPerUnit)
                            kmPerUnit != null ->
                                String.format(
                                    Locale.US,
                                    "%.2f km/unit (-%.1f%% SoC)",
                                    kmPerUnit,
                                    trip.socConsumed
                                )
                            trip.isOfficialRide -> "km/unit unavailable · Synced ride"
                            else ->
                                String.format(Locale.US, "km/unit unavailable (-%.1f%% SoC)", trip.socConsumed)
                        },
                        color = colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium.copy(color = colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open details",
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TripDetailSheetContent(
    trip: TripRecord,
    tariffRate: Double,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val effectiveCost = calculateCost(trip, tariffRate)
    val durationText = durationLabel(trip)
    val routePoints = remember(trip.encodedPolyline) { RidePolyline.decode(trip.encodedPolyline) }
    val averageSpeed = speedKmhText(trip.averageSpeedKmh)
    val topSpeed = speedKmhText(trip.topSpeedKmh)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 36.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TRIP TELEMETRY",
                    color = colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "Ride Details",
                    color = colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close sheet", tint = colorScheme.onSurfaceVariant)
            }
        }

        if (routePoints.size >= 2) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.background),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, colorScheme.outline)
            ) {
                RideRouteMap(
                    points = routePoints,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                )
            }
        } else if (trip.isOfficialRide) {
            Text(
                text = "Route was not supplied for this ride.",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.background),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TOTAL DISTANCE",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f km", trip.distanceKm),
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "ELECTRICITY COST",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = String.format(Locale.US, "₹%.2f", effectiveCost),
                        color = colorScheme.secondary,
                        style = MaterialTheme.typography.headlineSmall.copy(color = colorScheme.secondary)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.background),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ENERGY & EFFICIENCY",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                DetailMetricRow(
                    icon = Icons.Default.Speed,
                    label = "km/unit",
                    value = trip.kmPerUnitOrNull()?.let {
                        String.format(Locale.US, "%.2f km/unit", it)
                    } ?: "Unavailable (need distance & energy)",
                    valueColor = colorScheme.primary,
                    subValue = "Distance ÷ energy · 1 unit = 1 kWh"
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                DetailMetricRow(
                    icon = Icons.Default.Bolt,
                    label = "Energy Consumed",
                    value = String.format(Locale.US, "%.1f Wh", trip.energyConsumedWh),
                    subValue = String.format(Locale.US, "%.3f kWh", trip.energyConsumedWh / 1000.0)
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                DetailMetricRow(
                    icon = Icons.Default.BatteryChargingFull,
                    label = "SoC Consumed",
                    value = if (trip.isOfficialRide) "Not supplied by rides API" else String.format(Locale.US, "%.1f%%", trip.socConsumed),
                    valueColor = colorScheme.primary
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                DetailMetricRow(
                    icon = Icons.Default.CurrencyRupee,
                    label = "Tariff Rate",
                    value = String.format(Locale.US, "₹%.2f / kWh", tariffRate),
                    valueColor = colorScheme.secondary
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.background),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ODOMETER & TIMELINE",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                DetailMetricRow(
                    icon = Icons.Default.Navigation,
                    label = "Start Odometer",
                    value = if (trip.isOfficialRide) "Not supplied" else String.format(Locale.US, "%.1f km", trip.startOdoKm)
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                DetailMetricRow(
                    icon = Icons.Default.Navigation,
                    label = "End Odometer",
                    value = if (trip.isOfficialRide) "Not supplied" else String.format(Locale.US, "%.1f km", trip.endOdoKm)
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                DetailMetricRow(
                    icon = Icons.Default.AccessTime,
                    label = "Start Time",
                    value = formatFullDateTime(trip.startTimeMs)
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                DetailMetricRow(
                    icon = Icons.Default.AccessTime,
                    label = "End Time",
                    value = formatFullDateTime(trip.endTimeMs)
                )
                if (durationText.isNotEmpty()) {
                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    DetailMetricRow(
                        icon = Icons.Default.Timeline,
                        label = "Duration",
                        value = durationText
                    )
                }
                if (averageSpeed != null) {
                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    DetailMetricRow(
                        icon = Icons.Default.Speed,
                        label = "Average speed",
                        value = averageSpeed
                    )
                }
                if (topSpeed != null) {
                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    DetailMetricRow(
                        icon = Icons.Default.Speed,
                        label = "Top speed",
                        value = topSpeed
                    )
                }
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                DetailMetricRow(
                    icon = Icons.Default.Info,
                    label = "Trip ID",
                    value = trip.id,
                    valueColor = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailMetricRow(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = null, tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(label, color = colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = valueColor)
            )
            if (subValue != null) {
                Text(
                    text = subValue,
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun calculateCost(trip: TripRecord, tariffRate: Double): Double {
    return if (trip.electricityCostInr > 0.0) {
        trip.electricityCostInr
    } else {
        (trip.energyConsumedWh / 1000.0) * tariffRate
    }
}

private fun formatShortDateTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--"
    val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}

private fun formatFullDateTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--"
    val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}

private fun durationLabel(trip: TripRecord): String {
    val reported = trip.durationSeconds
    if (reported != null && reported.isFinite() && reported > 0.0) return formatDurationSeconds(reported)
    return formatDuration(trip.startTimeMs, trip.endTimeMs)
}

private fun formatDurationSeconds(seconds: Double): String {
    val totalSeconds = seconds.roundToLong().coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val remainder = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${remainder}s"
        else -> "${remainder}s"
    }
}

private fun speedKmhText(speedKmh: Double?): String? {
    val speed = speedKmh ?: return null
    if (!speed.isFinite() || speed < 0.0) return null
    val normalized = if (speed == 0.0) 0.0 else speed
    return String.format(Locale.US, "%.1f km/h", normalized)
}

private fun averageSpeedChip(speedKmh: Double?): String? {
    val speed = speedKmh ?: return null
    if (!speed.isFinite() || speed < 0.0) return null
    val normalized = if (speed == 0.0) 0.0 else speed
    val rounded = String.format(Locale.US, "%.1f", normalized)
    val shown = if (rounded.endsWith(".0")) rounded.dropLast(2) else rounded
    return "$shown km/h avg"
}

private fun formatDuration(startMs: Long, endMs: Long): String {
    if (startMs <= 0L || endMs <= 0L || endMs <= startMs) return ""
    val totalSeconds = (endMs - startMs) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
