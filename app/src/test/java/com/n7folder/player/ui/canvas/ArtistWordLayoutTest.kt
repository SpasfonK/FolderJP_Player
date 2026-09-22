package com.n7folder.player.ui.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistWordLayoutTest {

    /** Largeur fictive : 0,55 x taille par caractère (aucun accès à Android). */
    private val measure: (String, Float) -> Float = { text, font -> text.length * font * 0.55f }

    private fun layout(words: List<CloudWord>, width: Float = 400f): CloudLayout =
        ArtistWordLayout.layout(words, width, 10f, 14f, 40f, 6f, 10f, measure)

    private fun sampleWords(count: Int): List<CloudWord> =
        (0 until count).map { i ->
            val key = "artist" + i.toString().padStart(4, '0')
            CloudWord(key, key, 1 + (i * 7) % 90)
        }

    @Test
    fun everyWordIsPlacedOnceInOrder() {
        val words = sampleWords(300)
        val result = layout(words)
        assertEquals(words.size, result.words.size)
        for (i in words.indices) assertEquals(words[i].key, result.words[i].key)
    }

    @Test
    fun wordsStayInsideTheMarginsAndNeverOverlap() {
        val result = layout(sampleWords(500))
        for (i in result.words.indices) {
            val w = result.words[i]
            assertTrue(w.x >= 10f - 0.01f)
            assertTrue(w.x + w.width <= 400f - 10f + 0.01f)
            if (i > 0 && result.words[i - 1].row == w.row) {
                val previous = result.words[i - 1]
                assertTrue(previous.x + previous.width + 10f <= w.x + 0.01f)
            }
        }
    }

    @Test
    fun rowsDoNotOverlapVertically() {
        val result = layout(sampleWords(500))
        for (r in 0 until result.rowCount - 1) {
            assertTrue(result.rowBottom[r] <= result.rowTop[r + 1] + 0.01f)
        }
    }

    @Test
    fun heavierArtistsGetLargerFonts() {
        val result = layout(listOf(CloudWord("a", "a", 1), CloudWord("b", "b", 10), CloudWord("c", "c", 1000)))
        assertTrue(result.words[0].fontPx < result.words[1].fontPx)
        assertTrue(result.words[1].fontPx < result.words[2].fontPx)
        assertEquals(40f, result.words[2].fontPx, 0.01f)
    }

    @Test
    fun aNameWiderThanTheScreenIsShrunkOnASingleRow() {
        val result = layout(listOf(CloudWord("x", "x".repeat(200), 50)), width = 300f)
        assertEquals(1, result.rowCount)
        assertTrue(result.words[0].width <= 280.01f)
    }

    @Test
    fun hitFindsTheWordUnderThePointAndNothingFarAway() {
        val result = layout(sampleWords(200))
        for (i in listOf(0, 17, 99, 199)) {
            val w = result.words[i]
            assertEquals(i, result.hit(w.x + w.width / 2f, (w.top + w.baseline) / 2f, 0f))
        }
        assertEquals(-1, result.hit(-500f, -500f, 10f))
    }

    @Test
    fun visibleRowSearchMatchesABruteForceScan() {
        val result = layout(sampleWords(400))
        var y = -20f
        while (y < result.height + 20f) {
            var first = result.rowCount
            for (r in 0 until result.rowCount) if (result.rowBottom[r] >= y) { first = r; break }
            var last = -1
            for (r in 0 until result.rowCount) if (result.rowTop[r] <= y) last = r
            assertEquals(first, result.firstRowAtOrAfter(y))
            assertEquals(last, result.lastRowAtOrBefore(y))
            y += 37f
        }
    }

    @Test
    fun firstIndexOfLetterPointsToTheFirstArtistOfThatLetter() {
        val words = listOf(
            CloudWord("2pac", "2Pac", 5),
            CloudWord("abba", "ABBA", 5),
            CloudWord("angele", "Angèle", 5),
            CloudWord("saez", "Saez", 5)
        )
        val result = layout(words)
        assertEquals(0, result.firstIndexOfLetter('#'))
        assertEquals(1, result.firstIndexOfLetter('A'))
        assertEquals(3, result.firstIndexOfLetter('S'))
        assertEquals(-1, result.firstIndexOfLetter('Z'))
    }

    @Test
    fun emptyInputGivesAnEmptyLayout() {
        val result = layout(emptyList())
        assertEquals(0, result.words.size)
        assertEquals(0, result.rowCount)
        assertEquals(-1, result.hit(1f, 1f, 5f))
    }
}
