package com.n7folder.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n7folder.player.data.Alphabet
import com.n7folder.player.ui.common.plural
import kotlinx.coroutines.delay

/**
 * Sélecteur alphabétique (# A-Z) avec, à côté de chaque lettre, le nombre d'artistes.
 * Un appui ou un glissement de doigt le long de la barre saute à la lettre ; une bulle rappelle
 * la lettre et son nombre d'artistes. Le composant remplit son parent mais ne capte les gestes que
 * sur la barre : le reste de l'écran reste utilisable.
 */
@Composable
fun AlphabetBar(counts: Map<Char, Int>, onLetter: (Char) -> Unit, modifier: Modifier = Modifier) {
    val letters = Alphabet.LETTERS
    val density = LocalDensity.current
    var active by remember { mutableStateOf<Char?>(null) }

    LaunchedEffect(active) {
        if (active != null) {
            delay(700)
            active = null
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val rowHeight = minOf(maxHeight / letters.size, 24.dp)
        val rowPx = with(density) { rowHeight.toPx() }

        fun select(y: Float) {
            val index = (y / rowPx).toInt().coerceIn(0, letters.size - 1)
            val letter = letters[index]
            active = letter
            if ((counts[letter] ?: 0) > 0) onLetter(letter)
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(40.dp)
                .height(rowHeight * letters.size)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .pointerInput(rowPx) {
                    detectTapGestures(onTap = { position -> select(position.y) })
                }
                .pointerInput(rowPx) {
                    detectVerticalDragGestures(
                        onDragStart = { position -> select(position.y) },
                        onVerticalDrag = { change, _ -> select(change.position.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (letter in letters) {
                val count = counts[letter] ?: 0
                val dim = if (count > 0) 1f else 0.28f
                Row(
                    modifier = Modifier.fillMaxWidth().height(rowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Text(
                        text = letter.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = dim)
                    )
                    if (count > 0) {
                        Text(
                            text = compactCount(count),
                            fontSize = 7.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }
        }

        val shown = active
        if (shown != null) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = shown.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = plural(counts[shown] ?: 0, "artiste"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

private fun compactCount(n: Int): String = if (n >= 1000) (n / 1000).toString() + "k" else n.toString()
