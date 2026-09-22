package com.n7folder.player.ui.canvas

import com.n7folder.player.data.Alphabet
import kotlin.math.ln
import kotlin.math.max

/** Un mot du nuage ; [weight] (nombre de pistes) détermine sa taille. */
class CloudWord(val key: String, val text: String, val weight: Int)

/** Mot positionné, en coordonnées "monde" (pixels à l'échelle 1). */
class PlacedWord(
    val key: String,
    val text: String,
    val x: Float,
    val top: Float,
    val baseline: Float,
    val fontPx: Float,
    val width: Float,
    val height: Float,
    val row: Int
)

/**
 * Résultat de la mise en page : mots rangés par lignes, du haut vers le bas.
 * Les tableaux de lignes permettent de ne parcourir que ce qui est visible (recherche dichotomique).
 */
class CloudLayout(
    val words: List<PlacedWord>,
    val rowTop: FloatArray,
    val rowBottom: FloatArray,
    val rowFirst: IntArray,
    val rowEnd: IntArray,
    val width: Float,
    val height: Float,
    private val letterFirst: IntArray
) {
    val rowCount: Int get() = rowTop.size

    /** Premier rang dont le bas est >= [y] ([rowCount] si aucun). */
    fun firstRowAtOrAfter(y: Float): Int {
        var lo = 0
        var hi = rowCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rowBottom[mid] >= y) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Dernier rang dont le haut est <= [y] (-1 si aucun). */
    fun lastRowAtOrBefore(y: Float): Int {
        var lo = 0
        var hi = rowCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rowTop[mid] <= y) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    /**
     * Mot situé sous le point ([x], [y]) du monde, avec une tolérance [slop] (doigt plus large qu'un
     * mot). Renvoie l'indice dans [words], ou -1.
     */
    fun hit(x: Float, y: Float, slop: Float): Int {
        if (rowCount == 0) return -1
        var row = firstRowAtOrAfter(y)
        if (row >= rowCount) {
            row = rowCount - 1
        } else if (y < rowTop[row] && row > 0 && (y - rowBottom[row - 1]) < (rowTop[row] - y)) {
            row -= 1 // dans l'interstice : on prend la ligne la plus proche
        }
        val dy = when {
            y < rowTop[row] -> rowTop[row] - y
            y > rowBottom[row] -> y - rowBottom[row]
            else -> 0f
        }
        if (dy > slop) return -1

        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in rowFirst[row] until rowEnd[row]) {
            val w = words[i]
            val dx = when {
                x < w.x -> w.x - x
                x > w.x + w.width -> x - (w.x + w.width)
                else -> 0f
            }
            if (dx <= slop && dx < bestDistance) {
                best = i
                bestDistance = dx
            }
        }
        return best
    }

    /** Indice du premier mot dont l'initiale est [letter] (voir [Alphabet]), ou -1. */
    fun firstIndexOfLetter(letter: Char): Int {
        val position = Alphabet.LETTERS.indexOf(letter)
        return if (position < 0) -1 else letterFirst[position]
    }
}

/**
 * Nuage de mots façon "tag cloud" : les artistes, dans l'ordre alphabétique reçu, s'écoulent de
 * gauche à droite et vont à la ligne ; la taille suit le nombre de pistes (échelle logarithmique) ;
 * chaque ligne est centrée, avec une ligne de base commune. Algorithme linéaire, sans dépendance
 * Android : la mesure du texte est injectée ([measure] : texte, taille -> largeur en pixels).
 */
object ArtistWordLayout {
    private const val LINE_FACTOR = 1.28f
    private const val BASELINE_FACTOR = 0.82f
    private const val ASCENT_FACTOR = 0.95f

    private class Pending(val word: CloudWord, val font: Float, val width: Float)

    fun layout(
        words: List<CloudWord>,
        width: Float,
        padX: Float,
        minFont: Float,
        maxFont: Float,
        lineGap: Float,
        wordGap: Float,
        measure: (String, Float) -> Float
    ): CloudLayout {
        val usable = max(1f, width - 2f * padX)

        var maxWeight = 1
        for (w in words) if (w.weight > maxWeight) maxWeight = w.weight
        val denominator = ln(1f + maxWeight)

        val placed = ArrayList<PlacedWord>(words.size)
        val rowTops = ArrayList<Float>()
        val rowBottoms = ArrayList<Float>()
        val rowFirsts = ArrayList<Int>()
        val rowEnds = ArrayList<Int>()
        val letterFirst = IntArray(Alphabet.LETTERS.size) { -1 }

        val row = ArrayList<Pending>()
        var rowWidth = 0f
        var rowMaxFont = 0f
        var y = lineGap

        fun flushRow() {
            if (row.isEmpty()) return
            val rowHeight = rowMaxFont * LINE_FACTOR
            val baseline = y + rowHeight * BASELINE_FACTOR
            var x = padX + max(0f, (usable - rowWidth) / 2f)
            val first = placed.size
            for (p in row) {
                val index = placed.size
                placed.add(
                    PlacedWord(
                        key = p.word.key,
                        text = p.word.text,
                        x = x,
                        top = baseline - p.font * ASCENT_FACTOR,
                        baseline = baseline,
                        fontPx = p.font,
                        width = p.width,
                        height = p.font * LINE_FACTOR,
                        row = rowTops.size
                    )
                )
                val letterIndex = Alphabet.LETTERS.indexOf(Alphabet.letterOf(p.word.key))
                if (letterIndex >= 0 && letterFirst[letterIndex] < 0) letterFirst[letterIndex] = index
                x += p.width + wordGap
            }
            rowTops.add(y)
            rowBottoms.add(y + rowHeight)
            rowFirsts.add(first)
            rowEnds.add(placed.size)
            y += rowHeight + lineGap
            row.clear()
            rowWidth = 0f
            rowMaxFont = 0f
        }

        for (word in words) {
            val t = if (denominator > 0f) ln(1f + word.weight.coerceAtLeast(0)) / denominator else 0f
            var font = minFont + (maxFont - minFont) * t
            var w = measure(word.text, font)
            if (w > usable) {
                // Nom plus large que l'écran : on le réduit pour qu'il tienne sur une ligne.
                font = font * usable / w
                w = usable
            }
            if (row.isNotEmpty() && rowWidth + wordGap + w > usable) flushRow()
            rowWidth = if (row.isEmpty()) w else rowWidth + wordGap + w
            row.add(Pending(word, font, w))
            if (font > rowMaxFont) rowMaxFont = font
        }
        flushRow()

        return CloudLayout(
            words = placed,
            rowTop = rowTops.toFloatArray(),
            rowBottom = rowBottoms.toFloatArray(),
            rowFirst = rowFirsts.toIntArray(),
            rowEnd = rowEnds.toIntArray(),
            width = width,
            height = y,
            letterFirst = letterFirst
        )
    }
}
