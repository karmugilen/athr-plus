package io.ather.pro.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.ather.pro.ui.theme.materialColorScheme
import androidx.compose.ui.graphics.toArgb
import android.content.res.Configuration
import java.util.Date
import android.text.format.DateFormat
import io.ather.pro.R
import java.util.Locale
import kotlin.math.roundToInt

/** Classic Material widget, plus a cutoff bar only while the charge limiter is enabled. */
object WidgetRenderer {
    fun render(context: Context, snapshot: DashboardWidgetSnapshot, widthDp: Float, heightDp: Float, now: Long): RemoteViews {
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val height = heightDp / scale
        val width = widthDp / scale
        val roomy = height >= 320
        val compact = height < 240
        val paddingDp = if (roomy) 16 else if (compact) 10 else 12
        val panelPadding = if (roomy) 12 else if (compact) 6 else 8
        val heroPadding = if (roomy) 10 else 3
        val headerVisible = height >= 155
        val headerHeight = if (Build.VERSION.SDK_INT >= 31 && compact) 32 else 40
        val socSize = if (roomy) 28f else if (width < 250) 22f else 24f
        val rangeSize = if (roomy) 14f else 12f
        val limitEnabled = snapshot.limitPercent != null
        val showLastRide = snapshot.lastRideText.isNotBlank() && height >= 150
        val lastRideSize = 12f
        val lastRideHeight = if (showLastRide) lastRideSize * 1.2f * scale + 2 else 0f
        val heroHeight = (socSize + rangeSize) * 1.2f * scale + 3 + heroPadding * 2 + lastRideHeight
        val chargeHeight = if (limitEnabled) 14 * 1.2f * scale + 20 else 0f
        val available = heightDp - paddingDp * 2 - (if (headerVisible) headerHeight else 0) - heroHeight - chargeHeight - panelPadding * 2
        val minRowsHeight = snapshot.modeRanges.size * 11 * 1.2f * scale
        val footerHeight = 11 * 1.2f * scale + 8
        // Keep every mode visible before allocating space to the last-sync footer.
        val showFooter = height >= 130 && (snapshot.modeRanges.isEmpty() || available - footerHeight >= minRowsHeight)
        val rowsSpace = available - (if (showFooter) footerHeight else 0f)
        val rows = snapshot.modeRanges.isNotEmpty() && rowsSpace >= minRowsHeight
        val details = height >= 180 || rows
        val rowPadding = if (roomy && rowsSpace >= snapshot.modeRanges.size * (14 * 1.2f * scale + 8)) 4 else 0
        val rowSize = if (snapshot.modeRanges.isEmpty()) 12f else
            ((rowsSpace / snapshot.modeRanges.size - rowPadding * 2) / (1.2f * scale))
                .coerceIn(11f, if (roomy) 14f else 12f)
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colors = materialColorScheme(context, dark)
        val accent = colors.primary.toArgb()
        val primary = colors.onSurface.toArgb()
        val secondary = colors.onSurfaceVariant.toArgb()
        val track = colors.surfaceContainerHighest.toArgb()
        val marker = colors.tertiary.toArgb()
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val status = when {
            snapshot.updatedAtMs == 0L -> "Open app"
            snapshot.connectionLabel == "CONNECTING" -> "Syncing"
            snapshot.connectionLabel == "LIVE" && now - snapshot.updatedAtMs !in 0L..60_000L -> "Saved"
            snapshot.connectionLabel == "LIVE" -> if (snapshot.charging) "Charging" else "Live"
            else -> "Offline"
        }
        return RemoteViews(context.packageName, R.layout.widget_scooter_status).apply {
            setTextColor(R.id.widget_soc, primary)
            setTextColor(R.id.widget_title, primary)
            setTextColor(R.id.widget_range, secondary)
            setTextColor(R.id.widget_last_ride, secondary)
            setTextColor(R.id.widget_sync, secondary)
            setTextColor(R.id.widget_modes, secondary)
            setTextColor(R.id.widget_charge_limit, accent)
            setTextColor(R.id.widget_charge_status, secondary)
            val outerPadding = dp(paddingDp)
            setViewPadding(R.id.widget_root, outerPadding, outerPadding, outerPadding, outerPadding)
            setTextViewText(R.id.widget_soc, "${snapshot.socText} battery")
            setTextViewText(R.id.widget_range, "${snapshot.rangeText} · ${snapshot.currentMode ?: "estimated range"}")
            setViewVisibility(R.id.widget_last_ride, if (showLastRide) View.VISIBLE else View.GONE)
            if (showLastRide) {
                setTextViewText(R.id.widget_last_ride, snapshot.lastRideText)
                setTextViewTextSize(R.id.widget_last_ride, TypedValue.COMPLEX_UNIT_SP, lastRideSize)
            }
            setTextViewTextSize(R.id.widget_soc, TypedValue.COMPLEX_UNIT_SP, socSize)
            setTextViewTextSize(R.id.widget_range, TypedValue.COMPLEX_UNIT_SP, rangeSize)
            setViewPadding(R.id.widget_hero, 0, dp(heroPadding), 0, dp(heroPadding))
            if (Build.VERSION.SDK_INT >= 31) {
                setViewLayoutHeight(R.id.widget_header, headerHeight.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutWidth(R.id.widget_refresh, headerHeight.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(R.id.widget_refresh, headerHeight.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }
            setViewVisibility(R.id.widget_charge_row, if (limitEnabled) View.VISIBLE else View.GONE)
            if (snapshot.limitPercent != null) {
                setTextViewText(R.id.widget_charge_limit, "Limit ${snapshot.limitPercent}%")
                setTextViewText(R.id.widget_charge_status, snapshot.estimatedStopAtMs?.let {
                    "Est. stop ${DateFormat.getTimeFormat(context).format(Date(it))}"
                } ?: snapshot.chargeLabel.substringAfter(" · ", ""))
                setImageViewBitmap(R.id.widget_limit_bar, limitBar(snapshot.socPercent, snapshot.limitPercent, accent, track, marker))
                setContentDescription(R.id.widget_limit_bar, "Battery ${snapshot.socText}, charge limit ${snapshot.limitPercent}%")
            }
            setTextViewText(R.id.widget_connection, status)
            setTextColor(R.id.widget_connection, if (status == "Live" || status == "Charging") accent else secondary)
            setTextViewText(R.id.widget_sync, if (snapshot.updatedAtMs > 0)
                "Synced ${DateFormat.getTimeFormat(context).format(Date(snapshot.updatedAtMs))}" else "Never synced")
            setViewVisibility(R.id.widget_header, if (headerVisible) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_details, if (details) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_footer, if (showFooter) View.VISIBLE else View.GONE)
            setViewPadding(R.id.widget_details, dp(panelPadding), dp(panelPadding), dp(panelPadding), dp(panelPadding))
            setViewVisibility(R.id.widget_modes_rows, if (rows) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_modes, if (rows) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_modes, if (snapshot.modeRanges.isEmpty()) snapshot.modesText else "Resize for mode ranges")
            removeAllViews(R.id.widget_modes_rows)
            if (rows) snapshot.modeRanges.forEach { mode ->
                val row = RemoteViews(context.packageName, R.layout.widget_mode_row).apply {
                    setViewPadding(R.id.widget_mode_row, 0, dp(rowPadding), 0, dp(rowPadding))
                    setTextViewTextSize(R.id.widget_mode_name, TypedValue.COMPLEX_UNIT_SP, rowSize)
                    setTextViewTextSize(R.id.widget_mode_range, TypedValue.COMPLEX_UNIT_SP, rowSize)
                    setTextViewText(R.id.widget_mode_name, mode.name)
                    setTextViewText(R.id.widget_mode_range, String.format(Locale.getDefault(), "%.0f km", mode.km))
                    setViewVisibility(R.id.widget_mode_current, if (mode.active) View.VISIBLE else View.GONE)
                    setTextColor(R.id.widget_mode_name, if (mode.active) accent else primary)
                    setTextColor(R.id.widget_mode_range, if (mode.active) accent else primary)
                    setContentDescription(R.id.widget_mode_row,
                        "${mode.name}${if (mode.active) ", current mode" else ""}, estimated ${mode.km.roundToInt()} kilometres at ${snapshot.socText} battery")
                }
                addView(R.id.widget_modes_rows, row)
            }
            setContentDescription(R.id.widget_root,
                "Athr+. Battery ${snapshot.socText}${if (snapshot.charging) ", charging" else ""}. " +
                    "${snapshot.rangeText}. ${if (showLastRide) "${snapshot.lastRideText}. " else ""}" +
                    "${snapshot.modesLabel}: ${snapshot.modesText}. ${snapshot.chargeLabel}. $status. ${snapshot.syncLabel}")
        }
    }

    private data class BarKey(val soc: Double?, val limit: Int, val accent: Int, val track: Int, val marker: Int)
    private var previousBar: Pair<BarKey, Bitmap>? = null

    /** A small native graphic: battery fill and a contrasting marker at the configured cutoff. */
    @Synchronized
    private fun limitBar(soc: Double?, limit: Int, accent: Int, track: Int, markerColor: Int): Bitmap {
        val key = BarKey(soc, limit, accent, track, markerColor)
        previousBar?.takeIf { it.first == key }?.let { return it.second }
        val bitmap = Bitmap.createBitmap(512, 24, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = track
        canvas.drawRoundRect(0f, 5f, 512f, 19f, 7f, 7f, paint)
        val fill = ((soc ?: 0.0).coerceIn(0.0, 100.0) / 100 * 512).toFloat()
        paint.color = accent
        canvas.drawRoundRect(0f, 5f, fill, 19f, 7f, 7f, paint)
        val marker = (limit.coerceIn(0, 100) / 100f * 512).coerceIn(3f, 509f)
        paint.color = markerColor
        canvas.drawRoundRect(marker - 3, 0f, marker + 3, 24f, 3f, 3f, paint)
        previousBar = key to bitmap
        return bitmap
    }
}
