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
 * Pochette embarquée dans les fichiers audio, extraite à la demande (2e temps du chargement des
 * pochettes) : null tant que l'extraction n'est pas terminée, ou si le fichier n'en contient pas.
 */
@Composable
fun rememberEmbeddedCover(key: String, trackUri: Uri?): File? {
    val appContext = LocalContext.current.applicationContext
    val cover by produceState<File?>(initialValue = null, key, trackUri) {
        value = if (trackUri == null) null else CoverArtManager.from(appContext).embeddedCoverFor(key, trackUri)
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

/** Pochette d'un album : image locale d'abord, sinon pochette intégrée à la première piste. */
@Composable
fun AlbumArt(album: AlbumSummary, modifier: Modifier = Modifier) {
    val local = album.coverUri
    if (local != null) {
        AsyncImage(
            model = local,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        val embedded = rememberEmbeddedCover(
            key = "album:" + album.key.artistKey + "|" + album.key.albumKey,
            trackUri = album.tracks.firstOrNull()?.uri
        )
        if (embedded != null) {
            AsyncImage(
                model = embedded,
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop
            )
        } else {
            CoverPlaceholder(modifier)
        }
    }
}

/** Pochette de la piste en cours de lecture (lecteur, mini-lecteur). */
@Composable
fun TrackArtwork(artworkUri: Uri?, trackUri: Uri?, modifier: Modifier = Modifier) {
    if (artworkUri != null) {
        AsyncImage(
            model = artworkUri,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        val embedded = rememberEmbeddedCover(key = "track:" + trackUri.toString(), trackUri = trackUri)
        if (embedded != null) {
            AsyncImage(
                model = embedded,
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop
            )
        } else {
            CoverPlaceholder(modifier)
        }
    }
}
