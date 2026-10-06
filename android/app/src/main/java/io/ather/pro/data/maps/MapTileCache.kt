package io.ather.pro.data.maps

import java.io.File

/**
 * Disk cache for OpenStreetMap tiles the map has already shown.
 * Only tiles the WebView asks for are stored. Nothing is prefetched.
 */
class MapTileCache(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES,
    private val freshForMs: Long = FRESH_FOR_MS
) {
    data class TileKey(val zoom: Int, val x: Int, val y: Int)
    data class CachedTile(val bytes: ByteArray, val fresh: Boolean)

    fun read(key: TileKey): CachedTile? = synchronized(this) {
        val file = file(key)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!isPng(bytes)) {
            file.delete()
            return null
        }
        val age = now() - file.lastModified()
        CachedTile(bytes, fresh = age in 0 until freshForMs)
    }

    fun write(key: TileKey, bytes: ByteArray) {
        if (!isPng(bytes) || bytes.size > MAX_TILE_BYTES) return
        synchronized(this) {
            val file = file(key)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file.setLastModified(now())
            trim()
        }
    }

    private fun file(key: TileKey): File = File(root, "${key.zoom}/${key.x}/${key.y}.png")

    private fun trim() {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val keepUnder = maxBytes * 3 / 4
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= keepUnder) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    companion object {
        const val MAX_BYTES = 48L * 1024 * 1024
        const val MAX_TILE_BYTES = 512 * 1024
        const val FRESH_FOR_MS = 7L * 24 * 60 * 60 * 1000

        private val TILE = Regex("""^https://(?:[abc]\.)?tile\.openstreetmap\.org/(\d+)/(\d+)/(\d+)\.png$""")

        fun keyOf(url: String): TileKey? {
            val match = TILE.matchEntire(url.trim()) ?: return null
            val zoom = match.groupValues[1].toIntOrNull() ?: return null
            val x = match.groupValues[2].toIntOrNull() ?: return null
            val y = match.groupValues[3].toIntOrNull() ?: return null
            if (zoom !in 0..19 || x < 0 || y < 0) return null
            val span = 1 shl zoom
            if (x >= span || y >= span) return null
            return TileKey(zoom, x, y)
        }

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    }
}
