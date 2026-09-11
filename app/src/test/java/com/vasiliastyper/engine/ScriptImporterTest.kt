package com.vasiliastyper.engine

import com.vasiliastyper.model.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptImporterTest {

    @Test
    fun standaloneNumericOcrArtifactDoesNotShiftTranslations() {
        val parsed = ScriptImporter.parseStructuredText(
            """
            OCR
            doing a rough calculation....
            240 billion won in korean currency.
            240,000,000,000
            we would need about that much!
            ah, the call would be possible for about 10 minutes.

            TRANSLATE
            - Kalau kuhitung secara kasar,
            - sekitar 240 miliar won.
            - Kurang lebih sebanyak itulah yang dibutuhkan!
            - Oh ya, durasi panggilannya hanya sekitar 10 menit.
            """.trimIndent()
        )

        assertTrue(parsed.hasOcrPairs)
        assertEquals(4, parsed.sourceLines.size)
        assertEquals(4, parsed.translationLines.size)
        assertEquals("240 billion won in korean currency.", parsed.lines[1].sourceText)
        assertEquals("- sekitar 240 miliar won.", parsed.lines[1].text)
        assertEquals("we would need about that much!", parsed.lines[2].sourceText)
        assertEquals("- Kurang lebih sebanyak itulah yang dibutuhkan!", parsed.lines[2].text)
        assertEquals("ah, the call would be possible for about 10 minutes.", parsed.lines[3].sourceText)
        assertEquals("- Oh ya, durasi panggilannya hanya sekitar 10 menit.", parsed.lines[3].text)
    }

    @Test
    fun numericDialogueIsPreservedWhenSectionCountsAlreadyMatch() {
        val parsed = ScriptImporter.parseStructuredText(
            """
            OCR
            911
            call me now
            TRANSLATE
            911
            hubungi aku sekarang
            """.trimIndent()
        )

        assertEquals(2, parsed.lines.size)
        assertEquals("911", parsed.lines.first().sourceText)
        assertEquals("911", parsed.lines.first().text)
    }

    @Test
    fun headersWithColonAreAccepted() {
        val parsed = ScriptImporter.parseStructuredText(
            """
            OCR / SOURCE:
            原文一
            原文二
            TRANSLATION:
            Terjemahan satu
            Terjemahan dua
            """.trimIndent()
        )

        assertTrue(parsed.hasOcrPairs)
        assertEquals(2, parsed.lines.size)
        assertEquals("原文二", parsed.lines[1].sourceText)
        assertEquals("Terjemahan dua", parsed.lines[1].text)
    }

    @Test
    fun translationStylePrefixesArePreservedExactly() {
        val parsed = ScriptImporter.parseStructuredText(
            """
            OCR
            dialogue
            narration
            system message
            custom
            TRANSLATE
            - Dialog
            / Narasi
            [ Pesan sistem
            (): Kode khusus
            """.trimIndent()
        )

        assertTrue(parsed.hasOcrPairs)
        assertEquals("- Dialog", parsed.lines[0].text)
        assertEquals("/ Narasi", parsed.lines[1].text)
        assertEquals("[ Pesan sistem", parsed.lines[2].text)
        assertEquals("(): Kode khusus", parsed.lines[3].text)
    }

    @Test
    fun tabSeparatedPairsAreAccepted() {
        val parsed = ScriptImporter.parseStructuredText(
            "source one\tterjemahan satu\nsource two\tterjemahan dua"
        )

        assertTrue(parsed.hasOcrPairs)
        assertEquals(2, parsed.lines.size)
        assertEquals("source one", parsed.lines.first().sourceText)
        assertEquals("terjemahan satu", parsed.lines.first().text)
    }

    @Test
    fun quotedCsvPairsKeepCommasInsideText() {
        val parsed = ScriptImporter.parseStructuredText(
            "\"Hello, world\",\"Halo, dunia\"\nGoodbye,Sampai jumpa"
        )

        assertTrue(parsed.hasOcrPairs)
        assertEquals(2, parsed.lines.size)
        assertEquals("Hello, world", parsed.lines.first().sourceText)
        assertEquals("Halo, dunia", parsed.lines.first().text)
    }

    @Test
    fun ordinaryPlacementScriptUsesEveryNonBlankLineInOrder() {
        val parsed = ScriptImporter.parsePlacementText(
            """
            - Dialog pertama

            / Narasi kedua
            (): Style khusus
            """.trimIndent()
        )

        assertTrue(!parsed.hasOcrPairs)
        assertEquals(3, parsed.lines.size)
        assertEquals("- Dialog pertama", parsed.lines[0].text)
        assertEquals("/ Narasi kedua", parsed.lines[1].text)
        assertEquals("(): Style khusus", parsed.lines[2].text)
    }

    @Test
    fun placementScriptKeepsTranslationsFromLegacyStructuredFormat() {
        val parsed = ScriptImporter.parsePlacementText(
            """
            OCR
            source one
            source two
            TRANSLATE
            target one
            target two
            """.trimIndent()
        )

        assertTrue(parsed.hasOcrPairs)
        assertEquals(listOf("target one", "target two"), parsed.lines.map { it.text })
    }

    @Test
    fun styleApplicationSnapshotRepairsInvalidLegacyValues() {
        val source = TextStyle(
            name = "  ",
            fontSize = Float.NaN,
            effect = "unknown",
            gradientStartColor = 0x112233,
            gradientEndColor = 0x445566,
            gradientColors = mutableListOf(),
            gradientAngle = Float.POSITIVE_INFINITY,
            outlineOpacity = 999,
            align = "diagonal"
        )

        val safe = source.safeCopyForApply()

        assertEquals("Style", safe.name)
        assertEquals(36f, safe.fontSize)
        assertEquals("NONE", safe.effect)
        assertEquals(listOf(0x112233, 0x445566), safe.gradientColors)
        assertEquals(90f, safe.gradientAngle)
        assertEquals(100, safe.outlineOpacity)
        assertEquals("CENTER", safe.align)
    }

    @Test
    fun styleApplicationSnapshotDoesNotShareMutableGradientList() {
        val source = TextStyle(gradientColors = mutableListOf(1, 2, 3))
        val safe = source.safeCopyForApply()

        assertNotSame(source.gradientColors, safe.gradientColors)
        safe.gradientColors[0] = 99
        assertEquals(listOf(1, 2, 3), source.gradientColors)
    }
}
