package com.n7folder.player.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.n7folder.player.playback.PlaybackState
import com.n7folder.player.playback.PositionState
import com.n7folder.player.ui.common.IconTap
import com.n7folder.player.ui.common.PlayPauseIcon
import com.n7folder.player.ui.common.SkipIcon
import com.n7folder.player.ui.common.formatTime
import com.n7folder.player.ui.library.TrackArtwork

/** Écran "Lecture en cours" : pochette, barre de progression, transport, aléatoire, répéter, file, égaliseur. */
@Composable
fun FullPlayerScreen(
    state: PlaybackState,
    position: PositionState,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = position.durationMs
    val fraction = if (duration > 0L) (position.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val shownFraction = if (dragging) dragValue else fraction
    val shownPosition = if (dragging) (dragValue * duration).toLong() else position.positionMs
    val tint = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconTap(onClick = onClose) {
                Text(text = "▾", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "Lecture en cours",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.size(44.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))
        TrackArtwork(
            artworkUri = state.artworkUri,
            trackUri = state.trackUri,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
        )
        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = state.title.ifBlank { "—" },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = state.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = state.album,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val error = state.error
        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Slider(
            value = shownFraction,
            onValueChange = { value ->
                dragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
            enabled = duration > 0L,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatTime(shownPosition), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = formatTime(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTap(onClick = onPrevious, touchSize = 64.dp) {
                SkipIcon(next = false, tint = tint, modifier = Modifier.size(34.dp))
            }
            IconTap(onClick = onToggle, touchSize = 84.dp) {
                PlayPauseIcon(playing = state.isPlaying, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(60.dp))
            }
            IconTap(onClick = onNext, touchSize = 64.dp) {
                SkipIcon(next = true, tint = tint, modifier = Modifier.size(34.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(
                label = "Aléatoire",
                selected = state.shuffle,
                onClick = onToggleShuffle,
                modifier = Modifier.weight(1f)
            )
            ToggleChip(
                label = repeatLabel(state.repeatMode),
                selected = state.repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onCycleRepeat,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToggleChip(
                label = "File (" + state.queueSize + ")",
                selected = false,
                onClick = onOpenQueue,
                modifier = Modifier.weight(1f)
            )
            ToggleChip(
                label = "Égaliseur",
                selected = false,
                onClick = onOpenEqualizer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun repeatLabel(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ALL -> "Répéter : tout"
    Player.REPEAT_MODE_ONE -> "Répéter : 1 piste"
    else -> "Répéter"
}

/** Bouton à deux états : plein quand actif, contour sinon. */
@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding) {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
