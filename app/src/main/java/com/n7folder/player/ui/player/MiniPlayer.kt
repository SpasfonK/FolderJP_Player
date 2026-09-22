package com.n7folder.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n7folder.player.playback.PlaybackState
import com.n7folder.player.playback.PositionState
import com.n7folder.player.ui.common.IconTap
import com.n7folder.player.ui.common.PlayPauseIcon
import com.n7folder.player.ui.common.SkipIcon
import com.n7folder.player.ui.library.TrackArtwork

/** Mini-lecteur persistant, sous toutes les pages de la bibliothèque. Un appui l'agrandit. */
@Composable
fun MiniPlayer(
    state: PlaybackState,
    position: PositionState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = if (position.durationMs > 0L) {
        (position.positionMs.toFloat() / position.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val tint = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtwork(
                artworkUri = state.artworkUri,
                trackUri = state.trackUri,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = state.title.ifBlank { "Lecture" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconTap(onClick = onPrevious) { SkipIcon(next = false, tint = tint, modifier = Modifier.size(22.dp)) }
            IconTap(onClick = onToggle) {
                PlayPauseIcon(playing = state.isPlaying, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            IconTap(onClick = onNext) { SkipIcon(next = true, tint = tint, modifier = Modifier.size(22.dp)) }
        }
    }
}
