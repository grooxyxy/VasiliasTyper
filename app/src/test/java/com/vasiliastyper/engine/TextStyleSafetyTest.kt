package com.vasiliastyper.engine

import com.vasiliastyper.model.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStyleSafetyTest {

    @Test
    fun safeCopyBoundsInvalidRendererValues() {
        val safe = TextStyle(
            fontSize = Float.POSITIVE_INFINITY,
            opacity = 140,
            outlineWidth = 10_000f,
            shadowDx = Float.NaN,
            shadowRadius = -5f,
            shadowSpread = Float.POSITIVE_INFINITY,
            gradientColors = mutableListOf(0xFF112233.toInt()),
            gradientAngle = Float.NaN,
            tracking = 9_999f,
            leading = -80f,
            effect = "unknown"
        ).safeCopyForApply()

        assertEquals(36f, safe.fontSize)
        assertEquals(100, safe.opacity)
        assertEquals(128f, safe.outlineWidth)
        assertEquals(0f, safe.shadowDx)
        assertEquals(0f, safe.shadowRadius)
        assertEquals(0f, safe.shadowSpread)
        assertEquals("NONE", safe.effect)
        assertEquals(2, safe.gradientColors.size)
        assertTrue(safe.tracking <= 512f)
        // Leading minimal -50% (rapat); di bawah itu dijepit ke -50.
        assertEquals(-50f, safe.leading)
    }

    @Test
    fun safeCopyDoesNotShareMutableGradientState() {
        val source = TextStyle(
            gradientStartColor = 0xFF010203.toInt(),
            gradientEndColor = 0xFF040506.toInt(),
            gradientColors = mutableListOf(0xFF111111.toInt(), 0xFF222222.toInt())
        )

        val first = source.safeCopyForApply()
        val second = source.safeCopyForApply()
        assertNotSame(first.gradientColors, second.gradientColors)

        first.gradientColors[0] = 0
        assertEquals(0xFF111111.toInt(), second.gradientColors[0])
        assertEquals(0xFF111111.toInt(), source.gradientColors[0])
    }
}
