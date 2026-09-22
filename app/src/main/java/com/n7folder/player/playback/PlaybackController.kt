package com.n7folder.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.n7folder.player.data.AlbumKey
import com.n7folder.player.data.TrackEntry
import com.n7folder.player.service.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Ce que l'interface a besoin de savoir de la lecture en cours. */
data class PlaybackState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val trackUri: Uri? = null,
    /** documentId de la piste en cours : sert à la surligner dans les listes. */
    val currentDocumentId: String? = null,
    val currentIndex: Int = 0,
    val queueSize: Int = 0,
    /** Change à chaque modification de la file (ajout, retrait, déplacement, changement de piste). */
    val queueVersion: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val error: String? = null
)

/** Position de lecture, séparée du reste pour ne pas recomposer tout l'écran toutes les 400 ms. */
data class PositionState(val positionMs: Long = 0L, val durationMs: Long = 0L)

/** Une ligne de la file d'attente. */
class QueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val documentId: String
)

/**
 * Se connecte au [MusicService] via un MediaController et expose l'état de lecture.
 * Tout se passe sur le thread principal (exigence de MediaController) ; la fabrication des
 * MediaItem d'une grosse sélection se fait sur Dispatchers.Default, puis la file est transmise par
 * tranches : une transaction Binder ne peut pas transporter des dizaines de milliers d'éléments.
 */
class PlaybackController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(PositionState())
    val position: StateFlow<PositionState> = _position.asStateFlow()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queueVersion = 0
    private var ticker: Job? = null
    private var loadJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            refresh(player, timelineChanged)
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) startTicker(player.isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            val reason = error.message ?: "erreur inconnue"
            _state.update { it.copy(error = "Lecture impossible : $reason") }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Connexion
    // ------------------------------------------------------------------------------------------

    fun connect() {
        if (future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
        val pending = MediaController.Builder(appContext, token).buildAsync()
        future = pending
        pending.addListener({
            try {
                val ready = pending.get()
                controller = ready
                ready.addListener(listener)
                refresh(ready, true)
                if (ready.isPlaying) startTicker(true)
            } catch (e: Exception) {
                future = null
                _state.update { it.copy(connected = false, error = "Service de lecture indisponible") }
            }
        }, mainExecutor)
    }

    fun release() {
        loadJob?.cancel()
        ticker?.cancel()
        controller?.removeListener(listener)
        val pending = future
        if (pending != null) MediaController.releaseFuture(pending)
        controller = null
        future = null
        scope.cancel()
    }

    private suspend fun awaitController(): MediaController? {
        var attempts = 0
        while (attempts < MAX_CONNECT_ATTEMPTS) {
            val ready = controller
            if (ready != null) return ready
            connect()
            delay(50)
            attempts++
        }
        return null
    }

    private fun withController(action: (MediaController) -> Unit) {
        val ready = controller
        if (ready != null) {
            action(ready)
            return
        }
        scope.launch {
            val connected = awaitController()
            if (connected != null) action(connected)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lancer une lecture
    // ------------------------------------------------------------------------------------------

    /**
     * Remplace la file par [tracks] et lance la lecture.
     * @param startIndex piste de départ (ignoré en mode [shuffle])
     * @param shuffle ordre aléatoire (la file affichée est celle de l'ordre tiré)
     * @param covers pochettes locales par album, pour la notification
     */
    fun play(
        tracks: List<TrackEntry>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        covers: Map<AlbumKey, Uri> = emptyMap()
    ) {
        if (tracks.isEmpty()) return
        loadJob?.cancel()
        _state.update { it.copy(error = null) }
        loadJob = scope.launch {
            val ordered: List<TrackEntry> =
                if (shuffle) withContext(Dispatchers.Default) { tracks.shuffled() } else tracks
            val start = if (shuffle) 0 else startIndex.coerceIn(0, ordered.size - 1)
            val from = maxOf(0, start - HEAD_WINDOW)
            val to = minOf(ordered.size, start + TAIL_WINDOW)

            // 1) Une fenêtre autour de la piste demandée : la musique démarre tout de suite.
            val first = withContext(Dispatchers.Default) { build(ordered, from, to, covers) }
            val ready = awaitController() ?: return@launch
            ready.setMediaItems(first, start - from, 0L)
            ready.prepare()
            ready.play()

            // 2) La suite, par tranches, à la fin de la file.
            var next = to
            while (next < ordered.size) {
                val end = minOf(ordered.size, next + CHUNK)
                val items = withContext(Dispatchers.Default) { build(ordered, next, end, covers) }
                ready.addMediaItems(items)
                next = end
                yield()
            }

            // 3) Ce qui précède la piste demandée, par tranches, au début de la file.
            var inserted = 0
            while (inserted < from) {
                val end = minOf(from, inserted + CHUNK)
                val items = withContext(Dispatchers.Default) { build(ordered, inserted, end, covers) }
                ready.addMediaItems(inserted, items)
                inserted = end
                yield()
            }
        }
    }

    /** Ajoute des pistes à la file : juste après la piste en cours ([playNext]) ou à la fin. */
    fun enqueue(tracks: List<TrackEntry>, playNext: Boolean, covers: Map<AlbumKey, Uri> = emptyMap()) {
        if (tracks.isEmpty()) return
        val limited = if (tracks.size > MAX_ENQUEUE) tracks.subList(0, MAX_ENQUEUE) else tracks
        scope.launch {
            val items = withContext(Dispatchers.Default) { build(limited, 0, limited.size, covers) }
            val ready = awaitController() ?: return@launch
            if (ready.mediaItemCount == 0) {
                ready.setMediaItems(items)
                ready.prepare()
                ready.play()
            } else {
                val index = if (playNext) ready.currentMediaItemIndex + 1 else ready.mediaItemCount
                ready.addMediaItems(index, items)
            }
        }
    }

    private fun build(
        list: List<TrackEntry>,
        from: Int,
        to: Int,
        covers: Map<AlbumKey, Uri>
    ): List<MediaItem> {
        val out = ArrayList<MediaItem>(to - from)
        for (i in from until to) {
            val track = list[i]
            out.add(MediaItemFactory.create(track, covers[AlbumKey(track.folder.artistKey, track.folder.albumKey)]))
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // Commandes de transport
    // ------------------------------------------------------------------------------------------

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = withController { c -> c.seekToNext() }

    /** Comme partout : après 3 secondes de lecture, "précédent" revient au début de la piste. */
    fun previous() = withController { c -> c.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { c ->
        c.seekTo(positionMs)
        publishPosition(c)
    }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() = withController { c -> c.shuffleModeEnabled = !c.shuffleModeEnabled }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------------------------------
    // File d'attente
    // ------------------------------------------------------------------------------------------

    /** Ligne de la file (lue à la demande : seules les lignes visibles sont construites). */
    fun queueItem(index: Int): QueueItem? {
        val c = controller ?: return null
        if (index < 0 || index >= c.mediaItemCount) return null
        val item = c.getMediaItemAt(index)
        val metadata = item.mediaMetadata
        return QueueItem(
            mediaId = item.mediaId,
            title = metadata.title?.toString() ?: "",
            artist = metadata.artist?.toString() ?: "",
            documentId = MediaItemFactory.documentIdOf(item.mediaId)
        )
    }

    fun mediaIdAt(index: Int): String? {
        val c = controller ?: return null
        if (index < 0 || index >= c.mediaItemCount) return null
        return c.getMediaItemAt(index).mediaId
    }

    fun skipTo(index: Int) = withController { c ->
        if (index >= 0 && index < c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    fun moveItem(from: Int, to: Int) = withController { c ->
        val count = c.mediaItemCount
        if (from != to && from >= 0 && to >= 0 && from < count && to < count) c.moveMediaItem(from, to)
    }

    fun removeItem(index: Int) = withController { c ->
        if (index >= 0 && index < c.mediaItemCount) c.removeMediaItem(index)
    }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    // ------------------------------------------------------------------------------------------
    // Publication de l'état
    // ------------------------------------------------------------------------------------------

    private fun refresh(player: Player, timelineChanged: Boolean) {
        if (timelineChanged) queueVersion++
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val previousError = _state.value.error
        _state.value = PlaybackState(
            connected = true,
            hasMedia = item != null,
            isPlaying = player.isPlaying,
            title = metadata?.title?.toString() ?: "",
            artist = metadata?.artist?.toString() ?: "",
            album = metadata?.albumTitle?.toString() ?: "",
            artworkUri = metadata?.artworkUri,
            trackUri = item?.localConfiguration?.uri,
            currentDocumentId = if (item != null) MediaItemFactory.documentIdOf(item.mediaId) else null,
            currentIndex = player.currentMediaItemIndex,
            queueSize = player.mediaItemCount,
            queueVersion = queueVersion,
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
            error = previousError
        )
        publishPosition(player)
    }

    private fun publishPosition(player: Player) {
        val duration = player.duration
        _position.value = PositionState(
            positionMs = player.currentPosition,
            durationMs = if (duration == C.TIME_UNSET) 0L else duration
        )
    }

    private fun startTicker(playing: Boolean) {
        ticker?.cancel()
        if (!playing) return
        ticker = scope.launch {
            while (isActive) {
                val ready = controller
                if (ready != null) publishPosition(ready)
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val HEAD_WINDOW = 100
        const val TAIL_WINDOW = 300
        const val CHUNK = 250
        const val MAX_ENQUEUE = 2000
        const val MAX_CONNECT_ATTEMPTS = 100
        const val TICK_MS = 400L
    }
}
