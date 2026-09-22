package com.n7folder.player.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

private const val PINCH_IN_RATIO = 0.7f

/**
 * "Zoom arrière" : quand deux doigts se rapprochent d'au moins 30 %, appelle [onPinchIn].
 * Les événements sont seulement observés (jamais consommés) : le défilement de la liste ou de la
 * grille qui se trouve dessous continue de fonctionner normalement.
 */
@Composable
fun Modifier.pinchToGoBack(onPinchIn: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onPinchIn)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var startDistance = 0f
            var fired = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                    if (startDistance == 0f) {
                        startDistance = distance
                    } else if (!fired && distance < startDistance * PINCH_IN_RATIO) {
                        fired = true
                        latest()
                    }
                } else {
                    startDistance = 0f
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
