package com.n7folder.player.data

/** Index alphabétique A-Z (plus "#" pour chiffres, symboles et alphabets non latins). */
object Alphabet {
    const val OTHER = '#'

    /** Ordre d'affichage de la barre latérale : "#" d'abord, comme le tri naturel des clés. */
    val LETTERS: List<Char> = listOf(OTHER) + ('A'..'Z').toList()

    /** Lettre d'une clé d'artiste (voir [PathNormalizer.keyify] : minuscules, sans accents). */
    fun letterOf(artistKey: String): Char {
        val first = artistKey.firstOrNull() ?: return OTHER
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper else OTHER
    }

    /** Nombre d'artistes par lettre (les lettres sans artiste sont absentes). */
    fun countByLetter(artistKeys: Collection<String>): Map<Char, Int> {
        val counts = HashMap<Char, Int>()
        for (key in artistKeys) {
            val letter = letterOf(key)
            counts[letter] = (counts[letter] ?: 0) + 1
        }
        return counts
    }
}
