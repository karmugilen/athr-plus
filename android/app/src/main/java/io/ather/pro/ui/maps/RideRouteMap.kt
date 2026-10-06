package io.ather.pro.ui.maps

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ather.pro.BuildConfig
import io.ather.pro.domain.ride.RidePoint
import org.json.JSONObject

/** Bundled ride path. The caller sets the height. The script receives JSON numbers only. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RideRouteMap(points: List<RidePoint>, modifier: Modifier = Modifier) {
    val pathJson = remember(points) { numericPathJson(points) }
    var mapView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mapView) {
        val view = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.onResume() else view?.onPause()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pathJson, pageReady, mapView) {
        val view = mapView
        if (pageReady && view != null) view.evaluateJavascript("window.showRidePath($pathJson)", null)
    }
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.rgb(11, 13, 15))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = "Athr+/${BuildConfig.VERSION_NAME} $userAgentString"
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP -> { view.parent?.requestDisallowInterceptTouchEvent(false); view.performClick() }
                    MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    request?.url?.toString() != RIDE_MAP_URL

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url == RIDE_MAP_URL) pageReady = true
                }
            }
            mapView = this
            loadUrl(RIDE_MAP_URL)
        }
    }, onRelease = { view ->
        mapView = null
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.removeAllViews()
        view.destroy()
    })
}

/** Builds `[[lat,lng],...]` from finite coordinates. Tokens are JSON numbers, never text. */
private fun numericPathJson(points: List<RidePoint>): String {
    val builder = StringBuilder("[")
    var wrote = false
    for (point in points) {
        val latitude = jsonNumberOrNull(point.latitude) ?: continue
        val longitude = jsonNumberOrNull(point.longitude) ?: continue
        if (wrote) builder.append(',')
        wrote = true
        builder.append('[').append(latitude).append(',').append(longitude).append(']')
    }
    builder.append(']')
    return builder.toString()
}

private fun jsonNumberOrNull(value: Double): String? {
    if (!value.isFinite()) return null
    val token = runCatching { JSONObject.numberToString(value) }.getOrNull() ?: return null
    return token.takeIf { JSON_NUMBER.matches(it) }
}

private val JSON_NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")

private const val RIDE_MAP_URL = "file:///android_asset/ride-map.html"
