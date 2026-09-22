package com.n7folder.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n7folder.player.data.FolderNode
import com.n7folder.player.data.FolderTree
import com.n7folder.player.data.TrackEntry
import com.n7folder.player.ui.common.BackHeader
import com.n7folder.player.ui.common.MessageScreen
import com.n7folder.player.ui.common.PlayButtons
import com.n7folder.player.ui.common.TrackRow
import com.n7folder.player.ui.common.pinchToGoBack
import com.n7folder.player.ui.common.plural

/**
 * Explorateur arborescent : l'organisation réelle des dossiers, avec fil d'Ariane cliquable.
 * Chaque dossier a ses boutons ▶ (tout lire, sous-dossiers compris) et 🔀.
 */
@Composable
fun ExplorerScreen(
    tree: FolderTree?,
    path: List<String>,
    currentDocumentId: String?,
    onOpenFolder: (List<String>) -> Unit,
    onCrumb: (List<String>) -> Unit,
    onBack: () -> Unit,
    onPlayTracks: (List<TrackEntry>, Int, Boolean) -> Unit,
    onEnqueueTracks: (List<TrackEntry>, Boolean) -> Unit
) {
    if (tree == null) {
        MessageScreen("Lecture de l'arborescence…", onBack)
        return
    }
    val node = tree.find(path)
    if (node == null) {
        MessageScreen("Ce dossier n'existe plus dans la bibliothèque.", onBack)
        return
    }

    Column(modifier = Modifier.fillMaxSize().pinchToGoBack(onBack)) {
        Breadcrumb(tree = tree, path = path, onCrumb = onCrumb)

        BackHeader(
            title = if (path.isEmpty()) "Dossiers" else node.name,
            subtitle = plural(node.totalTracks, "piste"),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (node.totalTracks > 0) {
            PlayButtons(
                onPlay = { onPlayTracks(node.allTracks(), 0, false) },
                onShuffle = { onPlayTracks(node.allTracks(), 0, true) },
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
            Row(modifier = Modifier.padding(start = 8.dp)) {
                TextButton(onClick = { onEnqueueTracks(node.allTracks(), true) }) { Text("Lire ensuite") }
                TextButton(onClick = { onEnqueueTracks(node.allTracks(), false) }) { Text("Ajouter à la file") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(count = node.children.size, key = { index -> node.children[index].path.joinToString("/") }) { index ->
                val child = node.children[index]
                FolderRow(
                    folder = child,
                    onOpen = { onOpenFolder(child.path) },
                    onPlay = { onPlayTracks(child.allTracks(), 0, false) },
                    onShuffle = { onPlayTracks(child.allTracks(), 0, true) }
                )
            }
            items(count = node.tracks.size, key = { index -> "t:" + node.tracks[index].documentId }) { index ->
                val track = node.tracks[index]
                TrackRow(
                    number = (index + 1).toString(),
                    title = track.title,
                    playing = track.documentId == currentDocumentId,
                    onClick = { onPlayTracks(node.tracks, index, false) },
                    onPlayNext = { onEnqueueTracks(listOf(track), true) },
                    onAddToQueue = { onEnqueueTracks(listOf(track), false) }
                )
            }
        }
    }
}

/** Dossiers › Source › Dossier › Sous-dossier : chaque niveau (sauf le dernier) est cliquable. */
@Composable
private fun Breadcrumb(tree: FolderTree, path: List<String>, onCrumb: (List<String>) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crumb(text = "Dossiers", isLink = path.isNotEmpty(), onClick = { onCrumb(emptyList()) })
        for (i in path.indices) {
            val prefix = path.subList(0, i + 1)
            val name = tree.find(prefix)?.name ?: path[i]
            Text(text = "  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Crumb(text = name, isLink = i < path.size - 1, onClick = { onCrumb(prefix) })
        }
    }
}

@Composable
private fun Crumb(text: String, isLink: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = if (isLink) Modifier.clickable(onClick = onClick) else Modifier
    )
}

@Composable
private fun FolderRow(folder: FolderNode, onOpen: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "📁", style = MaterialTheme.typography.titleLarge)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = plural(folder.totalTracks, "piste"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("▶") }
        TextButton(onClick = onShuffle, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("🔀") }
    }
}
