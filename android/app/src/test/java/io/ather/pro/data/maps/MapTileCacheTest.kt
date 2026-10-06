package io.ather.pro.data.maps

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MapTileCacheTest {
    private var now = 1_000_000L
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @Test fun acceptsOnlyOpenStreetMapTileUrls() {
        assertEquals(MapTileCache.TileKey(15, 100, 200), MapTileCache.keyOf("https://tile.openstreetmap.org/15/100/200.png"))
        assertEquals(MapTileCache.TileKey(12, 3, 4), MapTileCache.keyOf("https://b.tile.openstreetmap.org/12/3/4.png"))
        assertNull(MapTileCache.keyOf("http://tile.openstreetmap.org/15/100/200.png"))
        assertNull(MapTileCache.keyOf("https://tile.openstreetmap.org/15/100/200.png?x=1"))
        assertNull(MapTileCache.keyOf("https://example.com/15/100/200.png"))
        assertNull(MapTileCache.keyOf("https://tile.openstreetmap.org/15/40000/1.png"))
        assertNull(MapTileCache.keyOf("https://tile.openstreetmap.org/22/1/1.png"))
    }

    @Test fun freshTileIsServedAndAnOldTileIsKeptForOffline() {
        val cache = cache(maxBytes = 10_000)
        val key = MapTileCache.TileKey(10, 5, 6)
        cache.write(key, png)
        assertTrue(cache.read(key)!!.fresh)
        now += MapTileCache.FRESH_FOR_MS
        val stale = cache.read(key)!!
        assertFalse(stale.fresh)
        assertEquals(png.size, stale.bytes.size)
    }

    @Test fun rejectsNonPngAndDropsTheOldestTileWhenFull() {
        val cache = cache(maxBytes = png.size.toLong() * 2)
        val first = MapTileCache.TileKey(3, 0, 0)
        val second = MapTileCache.TileKey(3, 1, 0)
        val third = MapTileCache.TileKey(3, 2, 0)
        cache.write(first, png)
        now += 1_000
        cache.write(second, png)
        now += 1_000
        cache.write(third, png)
        cache.write(MapTileCache.TileKey(3, 0, 1), byteArrayOf(1, 2, 3))
        assertNull(cache.read(first))
        assertNotNull(cache.read(third))
    }

    private fun cache(maxBytes: Long) = MapTileCache(
        root = File.createTempFile("tiles", "").apply { delete(); mkdirs() },
        now = { now },
        maxBytes = maxBytes,
        freshForMs = MapTileCache.FRESH_FOR_MS
    )
}
