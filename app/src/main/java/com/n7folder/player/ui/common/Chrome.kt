package com.n7folder.player.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n7folder.player.data.AlbumSummary
import com.n7folder.player.data.TrackEntry

/** En-tête des écrans secondaires : flèche de retour, titre, sous-titre. */
@Composable
fun BackHeader(title: String, subtitle: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTap(onClick = onBack) {
            Text(text = "←", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Boutons "Tout lire" et "Aléatoire", présents sur chaque bloc (artiste, album, dossier). */
@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay) { Text("▶  Tout lire", maxLines = 1) }
        OutlinedButton(onClick = onShuffle) { Text("🔀  Aléatoire", maxLines = 1) }
    }
}

/** Écran de repli (artiste ou dossier disparu après une nouvelle analyse, chargement en cours). */
@Composable
fun MessageScreen(message: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BackHeader(title = "", subtitle = null, onBack = onBack)
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "1984 · 10 pistes · 2 CD" */
fun albumDetails(album: AlbumSummary): String {
    val parts = ArrayList<String>()
    val year = album.year
    if (year != null) parts.add(year.toString())
    parts.add(plural(album.trackCount, "piste"))
    if (album.discCount >= 2) parts.add(album.discCount.toString() + " CD")
    return parts.joinToString(" · ")
}

/** Numéro affiché devant une piste : "3", ou "2-03" pour un album à plusieurs CD. */
fun trackLabel(track: TrackEntry, position: Int, multiDisc: Boolean): String {
    val number = track.trackNumber ?: (position + 1)
    val disc = track.discNumber
    return if (multiDisc && disc != null) disc.toString() + "-" + number.toString().padStart(2, '0') else number.toString()
}

/** Ligne de piste : appui = lire à partir d'ici ; menu "…" = lire ensuite / ajouter à la file. */
@Composable
fun TrackRow(
    number: String,
    title: String,
    playing: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number,
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box {
            IconTap(onClick = { menuOpen = true }) {
                Text(text = "⋯", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Lire ensuite") },
                    onClick = {
                        menuOpen = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ajouter à la file") },
                    onClick = {
                        menuOpen = false
                        onAddToQueue()
                    }
                )
            }
        }
    }
}
