package com.n7folder.player.data

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable

/**
 * Dossier racine choisi par l'utilisateur dans le sélecteur de dossiers Android (SAF).
 *
 * @param id identifiant stable : il survit à un "relink" (nouvelle Uri après reconnexion du stockage).
 * @param label nom affiché ("Musique", "Stockage interne"…).
 * @param rootName nom réel du dossier racine ("Saez"), vide pour la racine d'un volume entier.
 * @param rootIsArtist vrai si le dossier choisi EST un dossier artiste : ses sous-dossiers sont alors
 * des albums (sans cela, "Saez/Debbie" choisi directement donnerait un artiste "Debbie").
 */
data class MusicSource(
    val id: String,
    val treeUri: Uri,
    val label: String,
    val rootName: String,
    val rootIsArtist: Boolean = false
)

/**
 * Une piste audio indexée. L'Uri de lecture est reconstruite à la demande à partir de [treeUri] et
 * [documentId] pour ne pas conserver 50 000 objets Uri en mémoire.
 * [folder] est partagé par toutes les pistes du même dossier.
 * [dirPath] : chemin du dossier relatif à la racine de la source (partagé lui aussi), pour l'explorateur.
 */
data class TrackEntry(
    val sourceId: String,
    val treeUri: Uri,
    val documentId: String,
    val fileName: String,
    val title: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val folder: ParsedFolder,
    val dirPath: List<String>,
    val sizeBytes: Long
) {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}

/** Identité d'un album : l'artiste fait partie de la clé (deux "Greatest Hits" ne fusionnent pas). */
data class AlbumKey(val artistKey: String, val albumKey: String)

/** Pochette locale (cover.jpg, folder.jpg…) trouvée à côté des pistes d'un album. */
data class CoverHint(val key: AlbumKey, val uri: Uri)

/** Pourquoi un dossier source n'a pas pu être lu. */
enum class UnavailableReason {
    /** L'autorisation d'accès persistante a été révoquée (ou jamais accordée). */
    PERMISSION_LOST,

    /** Stockage débranché, dossier supprimé ou fournisseur injoignable. */
    NOT_FOUND
}

/** Événements émis par [FileScanner.scan], dans l'ordre. */
sealed interface ScanEvent {
    data class SourceStarted(val source: MusicSource) : ScanEvent

    /** Lot de 100 à 500 pistes (sauf le premier lot, volontairement petit pour afficher vite). */
    data class Batch(
        val source: MusicSource,
        val tracks: List<TrackEntry>,
        val covers: List<CoverHint>
    ) : ScanEvent

    data class Progress(val filesFound: Int, val dirsVisited: Int, val currentDir: String) : ScanEvent

    data class SourceUnavailable(val source: MusicSource, val reason: UnavailableReason) : ScanEvent

    data class SourceFinished(
        val source: MusicSource,
        val files: Int,
        val dirs: Int,
        val skipped: Int
    ) : ScanEvent

    data class Finished(val totalFiles: Int, val elapsedMs: Long) : ScanEvent
}

/** Ordre de lecture d'un album : disque, numéro de piste, puis nom de fichier en tri naturel. */
val TRACK_ORDER: Comparator<TrackEntry> = Comparator { a, b ->
    val byDisc = (a.discNumber ?: 0).compareTo(b.discNumber ?: 0)
    if (byDisc != 0) {
        byDisc
    } else {
        val byTrack = (a.trackNumber ?: Int.MAX_VALUE).compareTo(b.trackNumber ?: Int.MAX_VALUE)
        if (byTrack != 0) byTrack else PathNormalizer.naturalCompare(a.fileName, b.fileName)
    }
}

/** Vue immuable d'un album pour l'interface ; [tracks] est déjà dans l'ordre de lecture. */
@Immutable
class AlbumSummary(
    val key: AlbumKey,
    val name: String,
    val year: Int?,
    val discCount: Int,
    val coverUri: Uri?,
    val tracks: List<TrackEntry>
) {
    val trackCount: Int get() = tracks.size
}

/** Vue immuable d'un artiste ; [albums] est trié par année puis par nom. */
@Immutable
class ArtistSummary(
    val key: String,
    val name: String,
    val albums: List<AlbumSummary>
) {
    val trackCount: Int = albums.sumOf { it.tracks.size }

    /** Toutes les pistes de l'artiste, album après album (calculé à la demande). */
    val allTracks: List<TrackEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = ArrayList<TrackEntry>(trackCount)
        for (album in albums) out.addAll(album.tracks)
        out
    }
}
