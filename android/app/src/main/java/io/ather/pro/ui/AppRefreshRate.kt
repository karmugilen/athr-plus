package io.ather.pro.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Request the fastest supported rate up to 144 Hz while the app is resumed. */
@Composable
fun AppRefreshRate() {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view, owner) {
        val window = view.context.activity()?.window
        val previousRate = window?.attributes?.preferredRefreshRate ?: 0f
        val previousMode = window?.attributes?.preferredDisplayModeId ?: 0
        fun apply(active: Boolean) {
            if (window == null) return
            val attributes = window.attributes
            if (active) {
                val display = view.display ?: return
                val current = display.mode
                val modes = display.supportedModes.filter {
                    it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
                }
                val target = modes.filter { it.refreshRate <= 144.5f }.maxByOrNull { it.refreshRate }
                    ?: return
                attributes.preferredRefreshRate = target.refreshRate
                // Android 8–10 also use the explicit display mode for high refresh rates.
                attributes.preferredDisplayModeId = if (android.os.Build.VERSION.SDK_INT < 30) target.modeId else 0
            } else {
                attributes.preferredRefreshRate = previousRate
                attributes.preferredDisplayModeId = previousMode
            }
            window.attributes = attributes
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> apply(true)
                Lifecycle.Event.ON_PAUSE -> apply(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        apply(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            apply(false)
        }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
