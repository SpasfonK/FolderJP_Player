package com.n7folder.player.ui.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.n7folder.player.data.AlbumSummary
import com.n7folder.player.data.CoverArtManager
import java.io.File

/**
 * Pochette résolue en dehors des images locales : tags embarqués, puis recherche en ligne
 * (TheAudioDB/Last.fm) si la chaîne locale n'a rien donné. `null` tant que la résolution n'est pas
 * terminée, ou si rien n'a été trouvé nulle part.
 */
@Composable
fun rememberResolvedCover(key: String, trackUri: Uri?, artist: String, album: String): File? {
    val appContext = LocalContext.current.applicationContext
    val cover by produceState<File?>(initialValue = null, key, trackUri, artist, album) {
        value = CoverArtManager.from(appContext).coverFor(key, trackUri, artist, album)
    }
    return cover
}

@Composable
fun CoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "♪",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Pochette d'un album : image locale d'abord, sinon tags embarqués de la première piste, sinon
 * recherche en ligne par artiste/album (voir [CoverArtManager]).
 */
@Composable
fun AlbumArt(album: AlbumSummary, artistName: String, modifier: Modifier = Modifier) {
    val local = album.coverUri
    if (local != null) {
        AsyncImage(
            model = local,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        val resolved = rememberResolvedCover(
            key = "album:" + album.key.artistKey + "|" + album.key.albumKey,
            trackUri = album.tracks.firstOrNull()?.uri,
            artist = artistName,
            album = album.name
        )
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop
            )
        } else {
            CoverPlaceholder(modifier)
        }
    }
}

/** Pochette de la piste en cours de lecture (mini-lecteur, écran complet). */
@Composable
fun TrackArtwork(artworkUri: Uri?, trackUri: Uri?, artist: String, album: String, modifier: Modifier = Modifier) {
    if (artworkUri != null) {
        AsyncImage(
            model = artworkUri,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        val resolved = rememberResolvedCover(
            key = "np:" + artist + "|" + album,
            trackUri = trackUri,
            artist = artist,
            album = album
        )
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop
            )
        } else {
            CoverPlaceholder(modifier)
        }
    }
}
