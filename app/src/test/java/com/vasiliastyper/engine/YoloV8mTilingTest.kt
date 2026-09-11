package com.vasiliastyper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grid tiling YOLOv8m harus menutup penuh gambar 720x16000 (bahkan lebih). */
class YoloV8mTilingTest {

    @Test
    fun tallStrip720x16000FullyCovered() {
        val tiles = YoloV8mBubbleDetector.splitGrid(720, 16000)
        // FIX #1: tile 960/overlap 320 → lebar 720 = 1 kolom, tinggi 16000 = 25 baris.
        // Hitung dinamis dari konstanta agar tidak rapuh bila tuning berubah.
        val expectedRows = YoloV8mBubbleDetector.splitTiles(16000, YoloV8mBubbleDetector.TILE_SIZE, YoloV8mBubbleDetector.TILE_OVERLAP).size
        val expectedCols = YoloV8mBubbleDetector.splitTiles(720, YoloV8mBubbleDetector.TILE_SIZE, YoloV8mBubbleDetector.TILE_OVERLAP).size
        assertEquals(expectedCols * expectedRows, tiles.size)
        for (t in tiles) {
            assertTrue(t.x0 >= 0 && t.x1 <= 720 && t.x1 > t.x0)
            assertTrue(t.y0 >= 0 && t.y1 <= 16000 && t.y1 > t.y0)
            assertTrue(t.x1 - t.x0 <= YoloV8mBubbleDetector.TILE_SIZE)
            assertTrue(t.y1 - t.y0 <= YoloV8mBubbleDetector.TILE_SIZE)
        }
        assertEquals(0, tiles.minOf { it.y0 })
        assertEquals(16000, tiles.maxOf { it.y1 })
        // Antar-baris bertetangga wajib overlap (tidak ada celah).
        val rows = tiles.sortedBy { it.y0 }
        for (i in 1 until rows.size) {
            assertTrue(rows[i].y0 < rows[i - 1].y1)
        }
    }

    @Test
    fun wideImageSplitsHorizontally() {
        val tiles = YoloV8mBubbleDetector.splitGrid(4000, 3000)
        assertTrue(tiles.size > 1)
        assertTrue(tiles.any { it.x0 == 0 })
        assertTrue(tiles.any { it.x1 == 4000 })
        assertTrue(tiles.any { it.y1 == 3000 })
        for (t in tiles) {
            assertTrue(t.x1 - t.x0 <= YoloV8mBubbleDetector.TILE_SIZE)
            assertTrue(t.y1 - t.y0 <= YoloV8mBubbleDetector.TILE_SIZE)
        }
    }

    @Test
    fun smallImageIsSingleTile() {
        val tiles = YoloV8mBubbleDetector.splitGrid(500, 400)
        assertEquals(1, tiles.size)
        assertEquals(0, tiles[0].x0)
        assertEquals(500, tiles[0].x1)
        assertEquals(400, tiles[0].y1)
    }
}
