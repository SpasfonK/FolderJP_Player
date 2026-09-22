package com.n7folder.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetTest {

    @Test
    fun letterOf_usesTheFirstLetterOfTheKey() {
        assertEquals('S', Alphabet.letterOf("saez"))
        assertEquals('A', Alphabet.letterOf(PathNormalizer.keyify("Angèle")))
        assertEquals('E', Alphabet.letterOf(PathNormalizer.keyify("Édith Piaf")))
    }

    @Test
    fun letterOf_groupsDigitsSymbolsAndOtherAlphabetsUnderHash() {
        assertEquals(Alphabet.OTHER, Alphabet.letterOf("2pac"))
        assertEquals(Alphabet.OTHER, Alphabet.letterOf("!!!"))
        assertEquals(Alphabet.OTHER, Alphabet.letterOf(""))
        assertEquals(Alphabet.OTHER, Alphabet.letterOf(PathNormalizer.keyify("Мумий Тролль")))
    }

    @Test
    fun countByLetter_countsArtistsPerLetter() {
        val counts = Alphabet.countByLetter(listOf("saez", "sabbath", "abba", "2pac", "!!!"))
        assertEquals(2, counts['S'])
        assertEquals(1, counts['A'])
        assertEquals(2, counts[Alphabet.OTHER])
        assertEquals(null, counts['Z'])
    }

    @Test
    fun letters_startWithHashThenAToZ() {
        assertEquals(27, Alphabet.LETTERS.size)
        assertEquals('#', Alphabet.LETTERS.first())
        assertEquals('Z', Alphabet.LETTERS.last())
    }
}
