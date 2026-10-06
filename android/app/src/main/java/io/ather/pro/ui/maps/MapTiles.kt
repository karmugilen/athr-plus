package io.ather.pro.ui.maps

import android.content.Context
import android.webkit.WebResourceResponse
import io.ather.pro.BuildConfig
import io.ather.pro.data.maps.MapTileCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/** Serves OpenStreetMap tiles from disk, then the network. A failed download still shows a saved tile. */
object MapTiles {
    private const val USER_AGENT = "Athr+/${BuildConfig.VERSION_NAME} (Android; map tiles)"
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    @Volatile private var cache: MapTileCache? = null

    fun intercept(context: Context, url: String?): WebResourceResponse? {
        val key = url?.let(MapTileCache::keyOf) ?: return null
        return runCatching {
            val store = store(context)
            val saved = store.read(key)
            if (saved != null && saved.fresh) return png(saved.bytes)
            val downloaded = download(url)
            if (downloaded != null) {
                store.write(key, downloaded)
                return png(downloaded)
            }
            saved?.let { png(it.bytes) }
        }.getOrNull()
    }

    private fun store(context: Context): MapTileCache {
        cache?.let { return it }
        return MapTileCache(context.applicationContext.cacheDir.resolve("map-tiles")).also { cache = it }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes()?.takeIf { MapTileCache.isPng(it) && it.size <= MapTileCache.MAX_TILE_BYTES }
        }
    }.getOrNull()

    private fun png(bytes: ByteArray) = WebResourceResponse(
        "image/png",
        null,
        200,
        "OK",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(bytes)
    )
}
