package com.vasiliastyper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glyph mask stripe harus menutup penuh 720x16000+ tanpa celah dan hemat memori. */
class TextMaskTilingTest {

    @Test
    fun tallStrip720x16000FullyCovered() {
        val ranges = TextMaskEngine.glyphStripeRanges(16000)
        assertTrue(ranges.isNotEmpty())
        assertEquals(0, ranges.first().first)
        assertEquals(15999, ranges.last().last)
        // Tidak ada celah antar stripe.
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i].first < ranges[i - 1].last + 1 + TextMaskEngine.GLYPH_STRIPE_OVERLAP)
            assertTrue(ranges[i].first <= ranges[i - 1].last + 1)
        }
        // Tiap stripe dibatasi tinggi + overlap agar satu Bitmap stripe kecil.
        for (r in ranges) {
            val h = r.last - r.first + 1
            assertTrue(h in 1..(TextMaskEngine.GLYPH_STRIPE_HEIGHT + TextMaskEngine.GLYPH_STRIPE_OVERLAP))
        }
        // 16000px dengan stripe 2000/overlap 200 → ~9 stripe, bukan 1 Bitmap raksasa.
        assertTrue(ranges.size in 5..15)
    }

    @Test
    fun superTall720x20000Covered() {
        val ranges = TextMaskEngine.glyphStripeRanges(20000)
        assertEquals(0, ranges.first().first)
        assertEquals(19999, ranges.last().last)
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i].first <= ranges[i - 1].last + 1)
        }
    }

    @Test
    fun shortImageSingleStripe() {
        val ranges = TextMaskEngine.glyphStripeRanges(800)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].first)
        assertEquals(799, ranges[0].last)
    }

    @Test
    fun stripeTilingHelperConsistent() {
        // Konsisten dengan StripeTiling pusat agar bottom-most tidak hilang.
        val viaEngine = TextMaskEngine.glyphStripeRanges(16000)
        val viaHelper = StripeTiling.ranges(16000, TextMaskEngine.GLYPH_STRIPE_HEIGHT, TextMaskEngine.GLYPH_STRIPE_OVERLAP)
        assertEquals(viaHelper.size, viaEngine.size)
        assertEquals(viaHelper.first().first, viaEngine.first().first)
        assertEquals(viaHelper.last().last, viaEngine.last().last)
    }
}
