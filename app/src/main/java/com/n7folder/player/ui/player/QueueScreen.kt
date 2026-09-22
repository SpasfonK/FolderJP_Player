package com.n7folder.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.n7folder.player.playback.PlaybackController
import com.n7folder.player.playback.PlaybackState
import com.n7folder.player.playback.QueueItem
import com.n7folder.player.ui.common.IconTap
import com.n7folder.player.ui.common.plural

private val ROW_HEIGHT = 60.dp

/**
 * File d'attente : appui = jouer cette piste, ✕ = retirer, poignée ≡ = glisser-déposer pour
 * réordonner. Seules les lignes visibles sont lues dans le lecteur (la file peut compter des
 * dizaines de milliers de pistes).
 */
@Composable
fun QueueScreen(playback: PlaybackController, state: PlaybackState, onClose: () -> Unit) {
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragIndex by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTap(onClick = onClose) {
                Text(text = "▾", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "File d'attente", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = plural(state.queueSize, "piste"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    playback.clearQueue()
                    onClose()
                },
                enabled = state.queueSize > 0
            ) { Text("Vider") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        if (state.queueSize == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "La file est vide.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(count = state.queueSize, key = { index -> playback.mediaIdAt(index) ?: ("absent:" + index) }) { index ->
                    val item = playback.queueItem(index)
                    if (item != null) {
                        val isDragging = draggingId == item.mediaId
                        QueueRow(
                            item = item,
                            index = index,
                            isCurrent = index == state.currentIndex,
                            isDragging = isDragging,
                            dragOffsetProvider = { dragOffset },
                            onClick = { playback.skipTo(index) },
                            onRemove = { playback.removeItem(index) },
                            onDragStart = { startIndex ->
                                draggingId = item.mediaId
                                dragIndex = startIndex
                                dragOffset = 0f
                            },
                            onDrag = { deltaY ->
                                dragOffset += deltaY
                                // Dès que la ligne dépasse la moitié de sa voisine, on échange les deux.
                                while (dragOffset > rowPx / 2f && dragIndex < state.queueSize - 1) {
                                    playback.moveItem(dragIndex, dragIndex + 1)
                                    dragIndex += 1
                                    dragOffset -= rowPx
                                }
                                while (dragOffset < -rowPx / 2f && dragIndex > 0) {
                                    playback.moveItem(dragIndex, dragIndex - 1)
                                    dragIndex -= 1
                                    dragOffset += rowPx
                                }
                            },
                            onDragEnd = {
                                draggingId = null
                                dragOffset = 0f
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    index: Int,
    isCurrent: Boolean,
    isDragging: Boolean,
    dragOffsetProvider: () -> Float,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: (Int) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    // Le geste de glisser vit plus longtemps qu'une composition : il lit toujours les dernières valeurs.
    val latestIndex by rememberUpdatedState(index)
    val latestStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onDragEnd)

    val background = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = if (isDragging) dragOffsetProvider() else 0f }
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { "Sans titre" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconTap(onClick = onRemove) {
            Text(text = "✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = ROW_HEIGHT)
                .pointerInput(item.mediaId) {
                    detectDragGestures(
                        onDragStart = { latestStart(latestIndex) },
                        onDragEnd = { latestEnd() },
                        onDragCancel = { latestEnd() },
                        onDrag = { change, amount ->
                            change.consume()
                            latestDrag(amount.y)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(text = "≡", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
