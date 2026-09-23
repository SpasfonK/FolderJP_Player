package com.n7folder.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n7folder.player.data.AlbumSummary
import com.n7folder.player.data.ArtistSummary
import com.n7folder.player.ui.common.BackHeader
import com.n7folder.player.ui.common.MessageScreen
import com.n7folder.player.ui.common.PlayButtons
import com.n7folder.player.ui.common.albumDetails
import com.n7folder.player.ui.common.pinchToGoBack
import com.n7folder.player.ui.common.plural

/**
 * Niveau 2 : la grille de pochettes d'un artiste. Chaque pochette a ses boutons ▶ / 🔀.
 * Pincer (zoom arrière) ou "retour" revient au nuage.
 */
@Composable
fun ArtistScreen(
    artist: ArtistSummary?,
    onBack: () -> Unit,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onPlayAlbum: (AlbumSummary, Boolean) -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onEnqueueAll: (Boolean) -> Unit
) {
    if (artist == null) {
        MessageScreen("Cet artiste n'est plus dans la bibliothèque.", onBack)
        return
    }

    Box(modifier = Modifier.fillMaxSize().pinchToGoBack(onBack)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    BackHeader(
                        title = artist.name,
                        subtitle = plural(artist.albums.size, "album") + " · " + plural(artist.trackCount, "piste"),
                        onBack = onBack
                    )
                    PlayButtons(
                        onPlay = { onPlayAll(false) },
                        onShuffle = { onPlayAll(true) },
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                    Row {
                        TextButton(onClick = { onEnqueueAll(true) }) { Text("Lire ensuite") }
                        TextButton(onClick = { onEnqueueAll(false) }) { Text("Ajouter à la file") }
                    }
                }
            }
            items(artist.albums, key = { album -> album.key.albumKey }) { album ->
                AlbumCard(
                    album = album,
                    artistName = artist.name,
                    onOpen = { onOpenAlbum(album) },
                    onPlay = { onPlayAlbum(album, false) },
                    onShuffle = { onPlayAlbum(album, true) }
                )
            }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumSummary, artistName: String, onOpen: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AlbumArt(
            album = album,
            artistName = artistName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpen)
        )
        // Toujours visible, même sans pochette : c'est souvent la seule façon de savoir de quel
        // dossier il s'agit. Hauteur réservée pour deux lignes afin que la grille reste alignée.
        Text(
            text = album.name,
            modifier = Modifier.padding(top = 6.dp).heightIn(min = 34.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = albumDetails(album),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row {
            TextButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("▶") }
            TextButton(onClick = onShuffle, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("🔀") }
        }
    }
}
