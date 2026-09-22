package com.n7folder.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n7folder.player.data.FolderTree
import com.n7folder.player.data.TrackEntry
import com.n7folder.player.playback.EqualizerEngine
import com.n7folder.player.playback.PlayerViewModel
import com.n7folder.player.ui.library.AlbumScreen
import com.n7folder.player.ui.library.ArtistScreen
import com.n7folder.player.ui.library.CloudScreen
import com.n7folder.player.ui.library.ExplorerScreen
import com.n7folder.player.ui.nav.NavViewModel
import com.n7folder.player.ui.nav.PlayerPanel
import com.n7folder.player.ui.nav.Route
import com.n7folder.player.ui.player.EqualizerScreen
import com.n7folder.player.ui.player.FullPlayerScreen
import com.n7folder.player.ui.player.MiniPlayer
import com.n7folder.player.ui.player.QueueScreen
import com.n7folder.player.ui.scan.ScanViewModel
import com.n7folder.player.ui.scan.SourcesScreen
import kotlinx.coroutines.delay

/**
 * Racine de l'interface : la bibliothèque (nuage -> artiste -> album, explorateur, sources) au
 * centre, le mini-lecteur persistant en bas, et les panneaux plein écran du lecteur par-dessus.
 */
@Composable
fun N7App(
    scanVm: ScanViewModel,
    playerVm: PlayerViewModel,
    navVm: NavViewModel,
    onAddFolder: () -> Unit,
    onRelink: (String) -> Unit
) {
    val scan by scanVm.state.collectAsStateWithLifecycle()
    val nav by navVm.state.collectAsStateWithLifecycle()
    val playback = playerVm.playback
    val playState by playback.state.collectAsStateWithLifecycle()
    val position by playback.position.collectAsStateWithLifecycle()
    val eq by EqualizerEngine.state.collectAsStateWithLifecycle()

    BackHandler(enabled = nav.canGoBack) { navVm.back() }

    // Sans dossier, on ouvre directement la gestion des sources ; dès qu'un dossier est ajouté,
    // retour à l'accueil pour voir le nuage se remplir pendant l'analyse.
    val hasSources = scan.sources.isNotEmpty()
    LaunchedEffect(scan.loaded, hasSources) {
        if (scan.loaded) {
            if (hasSources) navVm.goHome() else navVm.push(Route.Sources)
        }
    }

    // Plus rien à lire : on quitte l'écran "Lecture en cours".
    LaunchedEffect(playState.hasMedia, nav.panel) {
        if (!playState.hasMedia && nav.panel != PlayerPanel.NONE) navVm.closePanels()
    }

    // Arborescence des dossiers, construite hors du thread principal seulement quand on l'explore.
    val route = nav.route
    val exploring = route is Route.Explorer
    var tree by remember { mutableStateOf<FolderTree?>(null) }
    LaunchedEffect(exploring, scan.libraryVersion) {
        if (exploring) {
            if (scan.scanning) delay(800)
            tree = scanVm.folderTree()
        }
    }

    val playTracks: (List<TrackEntry>, Int, Boolean) -> Unit = { tracks, start, shuffle ->
        playback.play(tracks, start, shuffle, scanVm.coverMap())
    }
    val enqueueTracks: (List<TrackEntry>, Boolean) -> Unit = { tracks, playNext ->
        playback.enqueue(tracks, playNext, scanVm.coverMap())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(targetState = route, label = "route") { current ->
                    when (current) {
                        Route.Cloud -> CloudScreen(
                            state = scan,
                            onOpenArtist = { key -> navVm.push(Route.Artist(key)) },
                            onOpenExplorer = { navVm.push(Route.Explorer(emptyList())) },
                            onOpenSources = { navVm.push(Route.Sources) },
                            onPlayArtist = { artist, shuffle -> playTracks(artist.allTracks, 0, shuffle) },
                            onEnqueueArtist = { artist, playNext -> enqueueTracks(artist.allTracks, playNext) }
                        )

                        is Route.Artist -> {
                            val artist = scan.artistIndex[current.key]
                            ArtistScreen(
                                artist = artist,
                                onBack = { navVm.back() },
                                onOpenAlbum = { album -> navVm.push(Route.Album(current.key, album.key.albumKey)) },
                                onPlayAlbum = { album, shuffle -> playTracks(album.tracks, 0, shuffle) },
                                onPlayAll = { shuffle -> if (artist != null) playTracks(artist.allTracks, 0, shuffle) },
                                onEnqueueAll = { playNext -> if (artist != null) enqueueTracks(artist.allTracks, playNext) }
                            )
                        }

                        is Route.Album -> {
                            val artist = scan.artistIndex[current.artistKey]
                            val album = artist?.albums?.firstOrNull { it.key.albumKey == current.albumKey }
                            AlbumScreen(
                                artist = artist,
                                album = album,
                                currentDocumentId = playState.currentDocumentId,
                                onBack = { navVm.back() },
                                onPlayFrom = { start -> if (album != null) playTracks(album.tracks, start, false) },
                                onShuffle = { if (album != null) playTracks(album.tracks, 0, true) },
                                onEnqueueTrack = { track, playNext -> enqueueTracks(listOf(track), playNext) }
                            )
                        }

                        is Route.Explorer -> ExplorerScreen(
                            tree = tree,
                            path = current.path,
                            currentDocumentId = playState.currentDocumentId,
                            onOpenFolder = { path -> navVm.push(Route.Explorer(path)) },
                            onCrumb = { path -> navVm.popTo(Route.Explorer(path)) },
                            onBack = { navVm.back() },
                            onPlayTracks = playTracks,
                            onEnqueueTracks = enqueueTracks
                        )

                        Route.Sources -> SourcesScreen(
                            state = scan,
                            onBack = { navVm.back() },
                            onAddFolder = onAddFolder,
                            onRescan = { scanVm.startScan() },
                            onRemove = { id -> scanVm.removeSource(id) },
                            onRelink = onRelink,
                            onToggleArtistRoot = { id, value -> scanVm.setRootIsArtist(id, value) }
                        )
                    }
                }
            }

            if (playState.hasMedia && nav.panel == PlayerPanel.NONE) {
                MiniPlayer(
                    state = playState,
                    position = position,
                    onToggle = { playback.togglePlayPause() },
                    onPrevious = { playback.previous() },
                    onNext = { playback.next() },
                    onOpen = { navVm.openPanel(PlayerPanel.NOW_PLAYING) }
                )
            }
        }

        if (nav.panel != PlayerPanel.NONE) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (nav.panel) {
                    PlayerPanel.NOW_PLAYING -> FullPlayerScreen(
                        state = playState,
                        position = position,
                        onClose = { navVm.back() },
                        onToggle = { playback.togglePlayPause() },
                        onPrevious = { playback.previous() },
                        onNext = { playback.next() },
                        onSeek = { positionMs -> playback.seekTo(positionMs) },
                        onCycleRepeat = { playback.cycleRepeat() },
                        onToggleShuffle = { playback.toggleShuffle() },
                        onOpenQueue = { navVm.openPanel(PlayerPanel.QUEUE) },
                        onOpenEqualizer = { navVm.openPanel(PlayerPanel.EQUALIZER) }
                    )

                    PlayerPanel.QUEUE -> QueueScreen(
                        playback = playback,
                        state = playState,
                        onClose = { navVm.back() }
                    )

                    PlayerPanel.EQUALIZER -> EqualizerScreen(
                        state = eq,
                        onClose = { navVm.back() }
                    )

                    PlayerPanel.NONE -> Unit
                }
            }
        }
    }
}
