package com.n7folder.player.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n7folder.player.data.MusicSource
import com.n7folder.player.data.RemoteCoverProvider
import com.n7folder.player.data.UnavailableReason
import com.n7folder.player.ui.common.BackHeader
import com.n7folder.player.ui.common.formatCount
import com.n7folder.player.ui.common.formatDuration
import com.n7folder.player.ui.common.plural

/**
 * Dossiers sources : ajouter (SD, mémoire interne, clé USB, NAS…), retirer, relier un stockage
 * reconnecté, réanalyser, et suivre l'analyse en direct.
 */
@Composable
fun SourcesScreen(
    state: ScanUiState,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onRescan: () -> Unit,
    onRemove: (String) -> Unit,
    onRelink: (String) -> Unit,
    onToggleArtistRoot: (String, Boolean) -> Unit
) {
    val message = state.message

    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(
            title = "Dossiers sources",
            subtitle = null,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "h:actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddFolder, modifier = Modifier.weight(1f)) {
                        Text("+ Dossier", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onRescan,
                        enabled = state.sources.isNotEmpty() && !state.scanning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Réanalyser", maxLines = 1)
                    }
                }
            }

            item(key = "h:remote-cover") { RemoteCoverToggle() }

            if (message != null) {
                item(key = "h:message") {
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
            }

            if (state.loaded && state.sources.isEmpty()) {
                item(key = "h:empty") {
                    Text(
                        text = "Ajoutez un dossier de musique : mémoire interne, carte SD, clé USB ou " +
                            "dossier réseau exposé par une application de stockage. Plusieurs dossiers " +
                            "peuvent être fusionnés.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            items(state.sources, key = { source -> "s:" + source.id }) { source ->
                SourceCard(
                    source = source,
                    status = state.statuses[source.id] ?: SourceStatus.Idle,
                    onRemove = { onRemove(source.id) },
                    onRelink = { onRelink(source.id) },
                    onToggleArtistRoot = { value -> onToggleArtistRoot(source.id, value) }
                )
            }

            if (state.sources.isNotEmpty() && (state.scanning || state.totalTracks > 0)) {
                item(key = "h:progress") { ProgressCard(state) }
            }
        }
    }
}

/**
 * Recherche en ligne des pochettes manquantes (TheAudioDB, puis Last.fm si une clé est configurée),
 * en dernier recours quand ni un fichier local ni une image intégrée n'ont donné de résultat.
 */
@Composable
private fun RemoteCoverToggle() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(RemoteCoverProvider.isEnabled(context)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Pochettes manquantes en ligne", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Cherche une pochette (TheAudioDB) quand aucun fichier local ni tag intégré n'en a.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { value ->
                enabled = value
                RemoteCoverProvider.setEnabled(context, value)
            }
        )
    }
}

@Composable
private fun SourceCard(
    source: MusicSource,
    status: SourceStatus,
    onRemove: () -> Unit,
    onRelink: () -> Unit,
    onToggleArtistRoot: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.label.ifBlank { "Dossier" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = source.treeUri.lastPathSegment ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (status is SourceStatus.Unavailable) {
                TextButton(onClick = onRelink) { Text("Relier") }
            }
            TextButton(onClick = onRemove) { Text("Retirer") }
        }

        val statusColor: Color = if (status is SourceStatus.Unavailable) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = statusText(status),
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ce dossier est un artiste",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(checked = source.rootIsArtist, onCheckedChange = onToggleArtistRoot)
        }
    }
}

@Composable
private fun ProgressCard(state: ScanUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (state.scanning) "Analyse en cours…" else "Analyse terminée",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (state.scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Text(
            text = plural(state.totalTracks, "piste") + " · " + plural(state.artists.size, "artiste"),
            style = MaterialTheme.typography.bodyMedium
        )
        if (state.scanning) {
            if (state.currentDir.isNotEmpty()) {
                Text(
                    text = state.currentDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                text = plural(state.dirsVisited, "dossier") + " analysés en " + formatDuration(state.elapsedMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun statusText(status: SourceStatus): String = when (status) {
    SourceStatus.Idle -> "En attente"
    SourceStatus.Scanning -> "Analyse…"
    is SourceStatus.Done -> {
        if (status.files == 0) {
            "Aucune piste audio trouvée dans ce dossier"
        } else {
            val skipped = if (status.skipped > 0) " · " + formatCount(status.skipped) + " ignorés" else ""
            plural(status.files, "piste") + skipped
        }
    }
    is SourceStatus.Unavailable -> when (status.reason) {
        UnavailableReason.PERMISSION_LOST -> "Accès révoqué : reliez le dossier"
        UnavailableReason.NOT_FOUND -> "Dossier introuvable (stockage déconnecté ?) : reliez-le"
    }
}
