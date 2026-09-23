package com.n7folder.player.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.n7folder.player.data.AlbumSummary
import com.n7folder.player.data.ArtistSummary
import com.n7folder.player.data.TrackEntry
import com.n7folder.player.ui.common.BackHeader
import com.n7folder.player.ui.common.MessageScreen
import com.n7folder.player.ui.common.PlayButtons
import com.n7folder.player.ui.common.TrackRow
import com.n7folder.player.ui.common.albumDetails
import com.n7folder.player.ui.common.pinchToGoBack
import com.n7folder.player.ui.common.trackLabel

/** Niveau 3 : les pistes d'un album. Appui sur une piste = lire l'album à partir d'elle. */
@Composable
fun AlbumScreen(
    artist: ArtistSummary?,
    album: AlbumSummary?,
    currentDocumentId: String?,
    onBack: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onShuffle: () -> Unit,
    onEnqueueTrack: (TrackEntry, Boolean) -> Unit
) {
    if (artist == null || album == null) {
        MessageScreen("Cet album n'est plus dans la bibliothèque.", onBack)
        return
    }
    val multiDisc = album.discCount >= 2

    LazyColumn(
        modifier = Modifier.fillMaxSize().pinchToGoBack(onBack),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "header") {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                BackHeader(title = album.name, subtitle = artist.name, onBack = onBack)
                Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 8.dp)) {
                    AlbumArt(
                        album = album,
                        artistName = artist.name,
                        modifier = Modifier.size(132.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = albumDetails(album),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        PlayButtons(onPlay = { onPlayFrom(0) }, onShuffle = onShuffle)
                    }
                }
            }
        }
        items(count = album.tracks.size, key = { index -> album.tracks[index].documentId }) { index ->
            val track = album.tracks[index]
            TrackRow(
                number = trackLabel(track, index, multiDisc),
                title = track.title,
                playing = track.documentId == currentDocumentId,
                onClick = { onPlayFrom(index) },
                onPlayNext = { onEnqueueTrack(track, true) },
                onAddToQueue = { onEnqueueTrack(track, false) }
            )
        }
    }
}
