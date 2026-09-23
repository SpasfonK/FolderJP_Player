package com.n7folder.player.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n7folder.player.data.ArtistSummary
import com.n7folder.player.ui.canvas.CloudWord
import com.n7folder.player.ui.canvas.N7ZoomableSurface
import com.n7folder.player.ui.common.plural
import com.n7folder.player.ui.scan.ScanUiState

/**
 * Accueil : le nuage d'artistes zoomable, la barre A-Z, et l'accès aux dossiers / sources.
 * Appui = albums de l'artiste ; appui long = menu (Tout lire, Aléatoire, file d'attente).
 */
@Composable
fun CloudScreen(
    state: ScanUiState,
    onOpenArtist: (String) -> Unit,
    onOpenExplorer: () -> Unit,
    onOpenSources: () -> Unit,
    onPlayArtist: (ArtistSummary, Boolean) -> Unit,
    onEnqueueArtist: (ArtistSummary, Boolean) -> Unit
) {
    var actionKey by remember { mutableStateOf<String?>(null) }
    var focusLetter by remember { mutableStateOf<Char?>(null) }
    var focusNonce by remember { mutableIntStateOf(0) }

    val words = remember(state.artists) {
        state.artists.map { artist ->
            // Pochette locale connue (jamais de réseau ici : cette liste est calculée pour TOUS les
            // artistes à chaque changement, et interroger une API pour chacun serait disproportionné).
            val cover = artist.albums.firstOrNull { it.coverUri != null }?.coverUri?.toString()
            CloudWord(artist.key, artist.name, artist.trackCount, cover)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "n7Folder",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = plural(state.totalTracks, "piste") + " · " + plural(state.artists.size, "artiste"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpenExplorer) { Text("Dossiers") }
            TextButton(onClick = onOpenSources) { Text("Sources") }
        }

        if (state.scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            if (state.currentDir.isNotEmpty()) {
                Text(
                    text = state.currentDir,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            N7ZoomableSurface(
                words = words,
                onWordTap = onOpenArtist,
                onWordLongPress = { key -> actionKey = key },
                onZoomIntoWord = onOpenArtist,
                focusLetter = focusLetter,
                focusNonce = focusNonce,
                layoutDelayMs = if (state.scanning) 350L else 0L
            )
            AlphabetBar(
                counts = state.letterCounts,
                onLetter = { letter ->
                    focusLetter = letter
                    focusNonce += 1
                },
                modifier = Modifier.fillMaxSize()
            )
            if (words.isEmpty() && !state.scanning) {
                Text(
                    text = "Aucun artiste pour l'instant.\nAjoutez un dossier depuis « Sources ».",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    val key = actionKey
    val actionArtist = if (key != null) state.artistIndex[key] else null
    if (actionArtist != null) {
        ArtistActionsDialog(
            artist = actionArtist,
            onDismiss = { actionKey = null },
            onOpen = {
                actionKey = null
                onOpenArtist(actionArtist.key)
            },
            onPlay = { shuffle ->
                actionKey = null
                onPlayArtist(actionArtist, shuffle)
            },
            onEnqueue = { playNext ->
                actionKey = null
                onEnqueueArtist(actionArtist, playNext)
            }
        )
    }
}

@Composable
private fun ArtistActionsDialog(
    artist: ArtistSummary,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPlay: (Boolean) -> Unit,
    onEnqueue: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text(artist.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    text = plural(artist.albums.size, "album") + " · " + plural(artist.trackCount, "piste"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onPlay(false) }) { Text("▶  Tout lire") }
                TextButton(onClick = { onPlay(true) }) { Text("🔀  Aléatoire") }
                TextButton(onClick = { onEnqueue(true) }) { Text("Lire ensuite") }
                TextButton(onClick = { onEnqueue(false) }) { Text("Ajouter à la file") }
                TextButton(onClick = onOpen) { Text("Ouvrir les albums") }
            }
        }
    )
}
