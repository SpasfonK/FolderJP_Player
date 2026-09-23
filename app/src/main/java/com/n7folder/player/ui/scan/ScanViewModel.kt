package com.n7folder.player.ui.scan

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n7folder.player.data.AlbumKey
import com.n7folder.player.data.Alphabet
import com.n7folder.player.data.ArtistSummary
import com.n7folder.player.data.CoverHint
import com.n7folder.player.data.FileScanner
import com.n7folder.player.data.FolderTree
import com.n7folder.player.data.LibraryCache
import com.n7folder.player.data.LibraryIndex
import com.n7folder.player.data.MusicSource
import com.n7folder.player.data.ScanEvent
import com.n7folder.player.data.SourceStore
import com.n7folder.player.data.TrackEntry
import com.n7folder.player.data.UnavailableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** État d'analyse d'un dossier source. */
sealed interface SourceStatus {
    data object Idle : SourceStatus
    data object Scanning : SourceStatus
    data class Done(val files: Int, val skipped: Int) : SourceStatus
    data class Unavailable(val reason: UnavailableReason) : SourceStatus
}

data class ScanUiState(
    val sources: List<MusicSource> = emptyList(),
    val statuses: Map<String, SourceStatus> = emptyMap(),
    /** Vrai une fois la liste des sources lue sur le disque (évite un faux "aucun dossier"). */
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    val filesFound: Int = 0,
    val dirsVisited: Int = 0,
    val currentDir: String = "",
    val totalTracks: Int = 0,
    val artists: List<ArtistSummary> = emptyList(),
    /** Les mêmes artistes, indexés par clé (accès direct depuis les écrans artiste/album). */
    val artistIndex: Map<String, ArtistSummary> = emptyMap(),
    /** Nombre d'artistes par lettre, pour la barre A-Z. */
    val letterCounts: Map<Char, Int> = emptyMap(),
    /** Augmente à chaque changement de la bibliothèque (invalide le cache de l'explorateur). */
    val libraryVersion: Int = 0,
    val elapsedMs: Long = 0L,
    val message: String? = null,
    /** Identifiant du scan qui a produit cet état (sert à ignorer un scan périmé). */
    val scanId: Int = 0
)

/**
 * Orchestration : sources (SourceStore) -> scan (FileScanner) -> index (LibraryIndex) -> état UI.
 *
 * Threads : les fonctions publiques s'exécutent sur le thread principal ; les accès disque/binder
 * (SourceStore) passent par Dispatchers.IO ; le scan est collecté sur Dispatchers.Default, donc la
 * fusion des lots et la construction des instantanés n'occupent jamais le thread principal.
 *
 * Bibliothèque figée : la liste des pistes déjà analysées est mise en cache sur le disque
 * ([LibraryCache]). Au démarrage, si un cache existe, il est utilisé tel quel — aucune nouvelle
 * analyse des dossiers (SAF) n'est déclenchée, même après un redémarrage du téléphone. Seules trois
 * actions explicites relancent une vraie analyse : "Réanalyser", l'ajout d'un nouveau dossier, et le
 * changement d'un réglage qui change la façon dont les fichiers sont interprétés ("Ce dossier est un
 * artiste", ou relier un dossier après reconnexion du stockage). Retirer un dossier, à l'inverse, ne
 * relance rien : on filtre simplement la bibliothèque déjà en mémoire.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SourceStore(application)
    private val scanner = FileScanner(application)
    private val libraryCache = LibraryCache(application)

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    /** Numéro du scan courant : un scan périmé n'écrit plus dans l'état. */
    private val generation = AtomicInteger(0)
    private var scanJob: Job? = null
    private var relinkTargetId: String? = null

    init {
        viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(sources = sources, loaded = true) }
            if (sources.isEmpty()) return@launch
            val cached = withContext(Dispatchers.IO) { libraryCache.load(sources) }
            if (cached != null) {
                applyLibrary(cached.tracks, cached.covers, sources)
            } else {
                startScan() // premier lancement pour ces dossiers : pas de cache à restituer
            }
        }
    }

    /** (Re)lance l'analyse de toutes les sources ; annule celle en cours. */
    fun startScan() {
        val sources = _state.value.sources
        val gen = generation.incrementAndGet()
        scanJob?.cancel()
        _state.update {
            it.copy(
                statuses = sources.associate { source -> source.id to SourceStatus.Idle },
                scanning = sources.isNotEmpty(),
                filesFound = 0,
                dirsVisited = 0,
                currentDir = "",
                totalTracks = 0,
                artists = emptyList(),
                artistIndex = emptyMap(),
                letterCounts = emptyMap(),
                libraryVersion = it.libraryVersion + 1,
                elapsedMs = 0L,
                message = null,
                scanId = gen
            )
        }
        if (sources.isEmpty()) {
            scanJob = null
            return
        }
        scanJob = viewModelScope.launch(Dispatchers.Default) { runScan(gen, sources) }
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { store.add(uri) }
            if (added == null) {
                _state.update { it.copy(message = "Impossible de mémoriser l'accès à ce dossier.") }
                return@launch
            }
            val sources = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(sources = sources, message = null) }
            startScan()
        }
    }

    /** Retire une source. Ne relance jamais d'analyse : la bibliothèque déjà chargée est filtrée. */
    fun removeSource(sourceId: String) {
        viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) {
                store.remove(sourceId)
                store.load()
            }
            val previous = _state.value.artists
            val remainingTracks = tracksOf(previous).filter { it.sourceId != sourceId }
            val roughCovers = coversOf(previous) // peut contenir des pochettes d'albums disparus : sans effet
            _state.update { it.copy(sources = sources) }
            applyLibrary(remainingTracks, roughCovers, sources)

            // On persiste l'état réellement reconstruit : les pochettes orphelines en sont déjà écartées.
            val fresh = _state.value.artists
            withContext(Dispatchers.IO) { libraryCache.save(tracksOf(fresh), coversOf(fresh)) }
        }
    }

    /** À appeler juste avant d'ouvrir le sélecteur de dossier destiné au relink. */
    fun prepareRelink(sourceId: String) {
        relinkTargetId = sourceId
    }

    fun onRelinkPicked(uri: Uri?) {
        val target = relinkTargetId
        relinkTargetId = null
        if (uri == null || target == null) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) { store.relink(target, uri) }
            if (updated == null) {
                _state.update { it.copy(message = "Impossible de relier ce dossier.") }
                return@launch
            }
            val sources = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(sources = sources, message = null) }
            startScan()
        }
    }

    fun setRootIsArtist(sourceId: String, value: Boolean) {
        viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) {
                store.setRootIsArtist(sourceId, value)
                store.load()
            }
            _state.update { it.copy(sources = sources) }
            startScan()
        }
    }

    /** Pochettes locales par album (pour la notification de lecture). */
    fun coverMap(): Map<AlbumKey, Uri> {
        val out = HashMap<AlbumKey, Uri>()
        for (artist in _state.value.artists) {
            for (album in artist.albums) {
                val cover = album.coverUri
                if (cover != null) out[album.key] = cover
            }
        }
        return out
    }

    private var treeCache: Pair<Int, FolderTree>? = null

    /** Arborescence des dossiers pour l'explorateur ; construite hors du thread principal, mise en cache. */
    suspend fun folderTree(): FolderTree {
        val snapshot = _state.value
        val cached = treeCache
        if (cached != null && cached.first == snapshot.libraryVersion) return cached.second
        val tree = withContext(Dispatchers.Default) {
            val all = ArrayList<TrackEntry>(snapshot.totalTracks)
            for (artist in snapshot.artists) all.addAll(artist.allTracks)
            FolderTree.build(all, snapshot.sources)
        }
        treeCache = Pair(snapshot.libraryVersion, tree)
        return tree
    }

    // ------------------------------------------------------------------------------------------

    private fun tracksOf(artists: List<ArtistSummary>): List<TrackEntry> = artists.flatMap { it.allTracks }

    private fun coversOf(artists: List<ArtistSummary>): List<CoverHint> =
        artists.flatMap { artist ->
            artist.albums.mapNotNull { album -> album.coverUri?.let { CoverHint(album.key, it) } }
        }

    /**
     * Reconstruit l'état de l'interface directement depuis une liste de pistes déjà connues
     * (cache disque ou bibliothèque filtrée), sans passer par [FileScanner] : aucun accès SAF.
     */
    private suspend fun applyLibrary(tracks: List<TrackEntry>, covers: List<CoverHint>, sources: List<MusicSource>) {
        val gen = generation.incrementAndGet()
        scanJob?.cancel()
        scanJob = null

        val (artists, artistIndex, letters) = withContext(Dispatchers.Default) {
            val index = LibraryIndex()
            index.addBatch(tracks, covers)
            val snapshot = index.snapshot()
            val byKey = HashMap<String, ArtistSummary>(snapshot.size * 2)
            val keys = ArrayList<String>(snapshot.size)
            for (artist in snapshot) {
                byKey[artist.key] = artist
                keys.add(artist.key)
            }
            Triple(snapshot, byKey, Alphabet.countByLetter(keys))
        }

        val countBySource = HashMap<String, Int>()
        for (track in tracks) countBySource[track.sourceId] = (countBySource[track.sourceId] ?: 0) + 1
        val statuses: Map<String, SourceStatus> =
            sources.associate { source -> source.id to SourceStatus.Done(countBySource[source.id] ?: 0, 0) }

        // Écriture directe (pas via updateIfCurrent) : c'est ici que le nouveau scanId est défini.
        _state.update {
            it.copy(
                statuses = statuses,
                scanning = false,
                filesFound = tracks.size,
                dirsVisited = 0,
                currentDir = "",
                totalTracks = tracks.size,
                artists = artists,
                artistIndex = artistIndex,
                letterCounts = letters,
                libraryVersion = it.libraryVersion + 1,
                elapsedMs = 0L,
                message = null,
                scanId = gen
            )
        }
    }

    /** Écrit dans l'état seulement si le scan [gen] est toujours le scan courant (atomique). */
    private fun updateIfCurrent(gen: Int, transform: (ScanUiState) -> ScanUiState) {
        _state.update { current -> if (current.scanId == gen) transform(current) else current }
    }

    private suspend fun runScan(gen: Int, sources: List<MusicSource>) {
        val index = LibraryIndex() // confiné à cette coroutine
        var lastPublish = 0L

        fun publish(): List<ArtistSummary> {
            val artists = index.snapshot()
            val tracks = index.trackCount
            val byKey = HashMap<String, ArtistSummary>(artists.size * 2)
            val keys = ArrayList<String>(artists.size)
            for (artist in artists) {
                byKey[artist.key] = artist
                keys.add(artist.key)
            }
            val letters = Alphabet.countByLetter(keys)
            updateIfCurrent(gen) {
                it.copy(
                    artists = artists,
                    artistIndex = byKey,
                    letterCounts = letters,
                    totalTracks = tracks,
                    libraryVersion = it.libraryVersion + 1
                )
            }
            lastPublish = SystemClock.elapsedRealtime()
            return artists
        }

        fun setStatus(sourceId: String, status: SourceStatus) {
            updateIfCurrent(gen) { it.copy(statuses = it.statuses + (sourceId to status)) }
        }

        try {
            scanner.scan(sources).collect { event ->
                if (generation.get() != gen) return@collect
                when (event) {
                    is ScanEvent.SourceStarted -> setStatus(event.source.id, SourceStatus.Scanning)
                    is ScanEvent.Batch -> {
                        index.addBatch(event.tracks, event.covers)
                        if (SystemClock.elapsedRealtime() - lastPublish >= PUBLISH_INTERVAL_MS) publish()
                    }
                    is ScanEvent.Progress -> updateIfCurrent(gen) {
                        it.copy(
                            filesFound = event.filesFound,
                            dirsVisited = event.dirsVisited,
                            currentDir = event.currentDir
                        )
                    }
                    is ScanEvent.SourceUnavailable ->
                        setStatus(event.source.id, SourceStatus.Unavailable(event.reason))
                    is ScanEvent.SourceFinished -> {
                        publish()
                        setStatus(event.source.id, SourceStatus.Done(event.files, event.skipped))
                    }
                    is ScanEvent.Finished -> {
                        val finalArtists = publish()
                        updateIfCurrent(gen) {
                            it.copy(
                                scanning = false,
                                filesFound = event.totalFiles,
                                currentDir = "",
                                elapsedMs = event.elapsedMs
                            )
                        }
                        // Fige la bibliothèque : le prochain démarrage la restituera sans repasser par SAF.
                        if (generation.get() == gen) {
                            libraryCache.save(tracksOf(finalArtists), coversOf(finalArtists))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            updateIfCurrent(gen) { it.copy(scanning = false, message = "Analyse interrompue : $reason") }
        }
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 250L
    }
}
