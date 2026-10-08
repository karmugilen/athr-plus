package io.ather.pro.ui.maps

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ather.pro.BuildConfig

/** The WebView only hosts bundled map code. Its lifecycle follows the visible map screen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StreetMapView(modifier: Modifier = Modifier, onReady: (WebView?) -> Unit) {
    val currentOnReady by rememberUpdatedState(onReady)
    var mapView by remember { mutableStateOf<WebView?>(null) }
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
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.rgb(11, 13, 15))
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
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    MapTiles.intercept(view.context, request.url?.toString())

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    request?.url?.toString() != MAP_URL

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url == MAP_URL) currentOnReady(view)
                }
            }
            mapView = this
            loadUrl(MAP_URL)
        }
    }, onRelease = { view ->
        currentOnReady(null)
        mapView = null
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.removeAllViews()
        view.destroy()
    })
}

private const val MAP_URL = "file:///android_asset/map.html"
