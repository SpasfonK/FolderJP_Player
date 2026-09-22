package com.n7folder.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n7folder.player.playback.EqState
import com.n7folder.player.playback.EqualizerEngine
import com.n7folder.player.ui.common.IconTap
import java.util.Locale
import kotlin.math.roundToInt

private const val PAD_Y = 14f

/** Égaliseur graphique : une glissière verticale par bande (±12 dB), préréglages, activation. */
@Composable
fun EqualizerScreen(state: EqState, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconTap(onClick = onClose) {
                Text(text = "▾", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "Égaliseur",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            Switch(
                checked = state.enabled,
                onCheckedChange = { on -> EqualizerEngine.setEnabled(on) },
                enabled = state.supported,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        if (!state.supported) {
            Text(
                text = "L'égaliseur s'active dès qu'une piste est en lecture (si l'appareil le permet).",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (band in state.gains.indices) {
                GainSlider(
                    label = state.labels.getOrElse(band) { "" },
                    value = state.gains[band],
                    min = state.minDb,
                    max = state.maxDb,
                    enabled = state.supported && state.enabled,
                    onChange = { db -> EqualizerEngine.setGain(band, db) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (name in EqualizerEngine.PRESETS.keys) {
                OutlinedButton(onClick = { EqualizerEngine.applyPreset(name) }, enabled = state.supported) {
                    Text(text = name, maxLines = 1)
                }
            }
        }
        TextButton(onClick = { EqualizerEngine.reset() }, enabled = state.supported) { Text("Tout remettre à zéro") }
    }
}

@Composable
private fun GainSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant
    val latestChange by rememberUpdatedState(onChange)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = String.format(Locale.getDefault(), "%+.1f", value),
            fontSize = 9.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else markColor
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(min, max, enabled) {
                    if (enabled) {
                        detectTapGestures(onTap = { position ->
                            latestChange(valueAt(position.y, size.height.toFloat(), min, max))
                        })
                    }
                }
                .pointerInput(min, max, enabled) {
                    if (enabled) {
                        detectVerticalDragGestures(
                            onDragStart = { position ->
                                latestChange(valueAt(position.y, size.height.toFloat(), min, max))
                            },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                latestChange(valueAt(change.position.y, size.height.toFloat(), min, max))
                            }
                        )
                    }
                }
        ) {
            val top = PAD_Y
            val bottom = size.height - PAD_Y
            val centerX = size.width / 2f
            val span = if (max > min) max - min else 1f

            fun yFor(db: Float): Float = bottom - ((db - min) / span) * (bottom - top)

            val zeroY = yFor(0f)
            val valueY = yFor(value)
            val alpha = if (enabled) 1f else 0.4f

            drawLine(trackColor, Offset(centerX, top), Offset(centerX, bottom), strokeWidth = 6f, cap = StrokeCap.Round)
            drawLine(markColor, Offset(centerX - 14f, zeroY), Offset(centerX + 14f, zeroY), strokeWidth = 2f)
            drawLine(accent.copy(alpha = alpha), Offset(centerX, zeroY), Offset(centerX, valueY), strokeWidth = 6f, cap = StrokeCap.Round)
            drawCircle(accent.copy(alpha = alpha), radius = 11f, center = Offset(centerX, valueY))
        }
        Text(text = label, fontSize = 10.sp, color = markColor)
    }
}

/** Convertit une position verticale (0 = haut) en gain, arrondi au demi-décibel. */
private fun valueAt(y: Float, height: Float, min: Float, max: Float): Float {
    val usable = if (height - 2f * PAD_Y > 1f) height - 2f * PAD_Y else 1f
    val t = 1f - ((y - PAD_Y) / usable).coerceIn(0f, 1f)
    val raw = min + t * (max - min)
    return (raw * 2f).roundToInt() / 2f
}
