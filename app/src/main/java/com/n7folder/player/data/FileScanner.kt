package com.n7folder.player.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import java.util.Locale

/**
 * Scanne des dossiers SAF (carte SD, stockage interne, clé USB OTG, NAS exposé par un fournisseur)
 * et émet la bibliothèque par lots, dès les premiers dossiers traités.
 *
 * Robustesse (jusqu'à 50 000 fichiers) :
 * - une seule requête ContentResolver par dossier, avec 4 colonnes (DocumentFile serait 10 à 50 fois
 *   plus lent : une requête par attribut et par fichier) ;
 * - parcours itératif avec pile explicite (pas de récursion : pas de StackOverflow) ;
 * - tout le travail sur Dispatchers.IO, [yield] après chaque dossier, lots de 100 à 500 pistes ;
 * - dossiers dans l'ordre alphabétique naturel : l'interface se remplit de A à Z ;
 * - protections : profondeur maximale, dossiers déjà visités, fichiers vides, fichiers cachés
 *   ("._Piste.mp3" de macOS), dossiers système (@eaDir de Synology, corbeilles…).
 *
 * Un dossier illisible n'arrête jamais le scan : il est compté dans `skipped`. Une racine illisible
 * (SD débranchée, permission révoquée) produit [ScanEvent.SourceUnavailable] pour proposer un relink.
 */
class FileScanner(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Scanne les sources l'une après l'autre ; les pistes de toutes les sources sont fusionnées. */
    fun scan(sources: List<MusicSource>): Flow<ScanEvent> = flow<ScanEvent> {
        val startedAt = SystemClock.elapsedRealtime()
        val seen = HashSet<String>()
        var total = 0
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            emit(ScanEvent.SourceStarted(source))
            total += scanSource(source, seen, total)
        }
        emit(ScanEvent.Finished(total, SystemClock.elapsedRealtime() - startedAt))
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------------------------------
    // Parcours d'une source
    // ------------------------------------------------------------------------------------------

    /** @return le nombre de pistes ajoutées pour cette source. */
    private suspend fun FlowCollector<ScanEvent>.scanSource(
        source: MusicSource,
        seen: MutableSet<String>,
        filesBefore: Int
    ): Int {
        val rootDocId: String = try {
            DocumentsContract.getTreeDocumentId(source.treeUri)
        } catch (e: IllegalArgumentException) {
            emit(ScanEvent.SourceUnavailable(source, UnavailableReason.NOT_FOUND))
            return 0
        }

        val rootChildren: List<Child> = when (val root = listChildren(source.treeUri, rootDocId)) {
            is Listing.Ok -> root.children
            is Listing.Failed -> {
                emit(ScanEvent.SourceUnavailable(source, root.reason))
                return 0
            }
        }

        // Les dossiers de premier niveau servent à reconnaître "A & B" -> "A" sans casser
        // "Simon & Garfunkel" (voir PathNormalizer).
        val knownArtistKeys = HashSet<String>()
        collectArtistKeys(source, rootChildren, knownArtistKeys)
        val normalizer = PathNormalizer(knownArtistKeys)

        val visited = HashSet<String>()
        val stack = ArrayDeque<DirFrame>()
        val rootSegments: List<String> =
            if (source.rootIsArtist && source.rootName.isNotEmpty()) listOf(source.rootName) else emptyList()
        stack.addLast(DirFrame(rootDocId, source.rootName, rootSegments, emptyList(), null, 0))

        var files = 0
        var dirs = 0
        var skipped = 0
        var buffer = ArrayList<TrackEntry>()
        var covers = ArrayList<CoverHint>()
        var lastFlush = SystemClock.elapsedRealtime()
        var lastProgress = 0L
        var firstFlushDone = false

        // Émet le tampon en lots de MAX_CHUNK pistes maximum.
        suspend fun flush() {
            if (buffer.isEmpty() && covers.isEmpty()) return
            val tracks = buffer
            val hints = covers
            buffer = ArrayList()
            covers = ArrayList()
            var from = 0
            var first = true
            do {
                val to = minOf(from + MAX_CHUNK, tracks.size)
                val chunk: List<TrackEntry> =
                    if (from == 0 && to == tracks.size) tracks else tracks.subList(from, to).toList()
                emit(ScanEvent.Batch(source, chunk, if (first) hints else emptyList()))
                first = false
                from = to
                if (from < tracks.size) yield()
            } while (from < tracks.size)
            lastFlush = SystemClock.elapsedRealtime()
            firstFlushDone = true
        }

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val frame = stack.removeLast()
            if (!visited.add(frame.documentId)) continue

            val children: List<Child> = if (frame.depth == 0) {
                rootChildren
            } else {
                when (val listing = listChildren(source.treeUri, frame.documentId)) {
                    is Listing.Ok -> listing.children
                    is Listing.Failed -> {
                        skipped++
                        continue
                    }
                }
            }
            dirs++

            val subDirs = ArrayList<Child>()
            val audio = ArrayList<Child>()
            var bestImage: Child? = null
            var bestRank = Int.MAX_VALUE
            var imageCount = 0
            for (child in children) {
                val name = child.name
                if (name.startsWith(".")) continue
                if (child.isDir) {
                    if (!isIgnoredDir(name)) subDirs.add(child)
                    continue
                }
                if (isAudioFile(name, child.mime)) {
                    if (child.size == 0L) {
                        skipped++ // fichier vide : corrompu, inutile de le proposer à la lecture
                    } else {
                        audio.add(child)
                    }
                } else if (isImageFile(name)) {
                    imageCount++
                    val rank = coverRank(name)
                    if (rank < bestRank) {
                        bestRank = rank
                        bestImage = child
                    }
                }
            }

            // Pochette locale : cover/folder/front… ; une image quelconque n'est retenue que si elle
            // est la seule du dossier (sinon on ne sait pas si c'est la face avant).
            val image = bestImage
            val ownCover: Uri? =
                if (image != null && (bestRank < GENERIC_IMAGE_RANK || imageCount == 1)) {
                    DocumentsContract.buildDocumentUriUsingTree(source.treeUri, image.documentId)
                } else {
                    null
                }
            val effectiveCover: Uri? = ownCover ?: frame.inheritedCover

            if (audio.isNotEmpty()) {
                audio.sortWith(CHILD_ORDER)
                // Pistes posées directement dans le dossier racine choisi : son nom sert de segment.
                val segments: List<String> =
                    if (frame.segments.isEmpty() && source.rootName.isNotEmpty()) {
                        listOf(source.rootName)
                    } else {
                        frame.segments
                    }
                val parsed = normalizer.parseFolder(segments)
                val fileNames = ArrayList<String>(audio.size)
                for (file in audio) fileNames.add(file.name)
                val spaceNumbered = PathNormalizer.looksSpaceNumbered(fileNames)

                var added = 0
                for (file in audio) {
                    if (!seen.add(uniqueKey(source, file.documentId))) {
                        skipped++ // même fichier atteint par deux sources imbriquées
                        continue
                    }
                    val trackName = normalizer.parseTrackName(file.name, parsed.artistKey, spaceNumbered)
                    buffer.add(
                        TrackEntry(
                            sourceId = source.id,
                            treeUri = source.treeUri,
                            documentId = file.documentId,
                            fileName = file.name,
                            title = trackName.title,
                            trackNumber = trackName.track,
                            discNumber = trackName.disc ?: parsed.disc,
                            folder = parsed,
                            dirPath = frame.relPath,
                            sizeBytes = file.size
                        )
                    )
                    added++
                }
                if (added > 0 && effectiveCover != null) {
                    covers.add(CoverHint(AlbumKey(parsed.artistKey, parsed.albumKey), effectiveCover))
                }
                files += added
            }

            // Sous-dossiers empilés en ordre inverse pour être dépilés de A à Z.
            if (subDirs.isNotEmpty() && frame.depth < MAX_DEPTH) {
                subDirs.sortWith(CHILD_ORDER)
                for (i in subDirs.indices.reversed()) {
                    val dir = subDirs[i]
                    // CD1/, Disc 2/, FLAC/ héritent de la pochette de leur dossier parent.
                    val inherited: Uri? =
                        if (PathNormalizer.isDiscOrFormatFolder(dir.name)) effectiveCover else null
                    stack.addLast(
                        DirFrame(
                            dir.documentId,
                            dir.name,
                            frame.segments + dir.name,
                            frame.relPath + dir.name,
                            inherited,
                            frame.depth + 1
                        )
                    )
                }
            }

            val now = SystemClock.elapsedRealtime()
            val pending = buffer.size
            val flushDue = pending >= MAX_CHUNK ||
                (pending >= MIN_CHUNK && now - lastFlush >= MIN_FLUSH_INTERVAL_MS) ||
                (pending > 0 && !firstFlushDone) ||
                (pending > 0 && now - lastFlush >= MAX_FLUSH_INTERVAL_MS)
            if (flushDue) flush()

            if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                emit(ScanEvent.Progress(filesBefore + files, dirs, frame.name))
                lastProgress = now
            }
            yield() // rend la main : annulation immédiate, aucun monopole du thread
        }

        flush()
        emit(ScanEvent.SourceFinished(source, files, dirs, skipped))
        return files
    }

    /**
     * Premier niveau (jusqu'à 3 niveaux si des dossiers génériques "Musique/", "FLAC/" s'intercalent) :
     * clés des dossiers artistes, pour la fusion prudente "A & B" -> "A".
     */
    private fun collectArtistKeys(source: MusicSource, rootChildren: List<Child>, keys: MutableSet<String>) {
        if (source.rootIsArtist && source.rootName.isNotEmpty()) {
            keys.add(PathNormalizer.artistKeyHint(source.rootName))
            return
        }
        var level: List<Child> = rootChildren
        var depth = 0
        while (level.isNotEmpty() && depth < 3) {
            val next = ArrayList<Child>()
            for (child in level) {
                if (!child.isDir || child.name.startsWith(".") || isIgnoredDir(child.name)) continue
                if (PathNormalizer.isGenericContainer(child.name)) {
                    val listing = listChildren(source.treeUri, child.documentId)
                    if (listing is Listing.Ok) next.addAll(listing.children)
                } else {
                    keys.add(PathNormalizer.artistKeyHint(child.name))
                }
            }
            level = next
            depth++
        }
    }

    // ------------------------------------------------------------------------------------------
    // Accès SAF
    // ------------------------------------------------------------------------------------------

    private class Child(
        val documentId: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val mime: String
    )

    private class DirFrame(
        val documentId: String,
        val name: String,
        val segments: List<String>,
        /** Chemin relatif à la racine de la source (sans le nom de la racine), pour l'explorateur. */
        val relPath: List<String>,
        val inheritedCover: Uri?,
        val depth: Int
    )

    private sealed interface Listing {
        class Ok(val children: List<Child>) : Listing
        class Failed(val reason: UnavailableReason) : Listing
    }

    /** Liste les enfants directs d'un dossier en UNE requête. Ne lève jamais d'exception. */
    private fun listChildren(treeUri: Uri, parentDocumentId: String): Listing {
        val childrenUri: Uri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        } catch (e: IllegalArgumentException) {
            return Listing.Failed(UnavailableReason.NOT_FOUND)
        }
        return try {
            val cursor: Cursor? = resolver.query(childrenUri, PROJECTION, null, null, null)
            if (cursor == null) {
                Listing.Failed(UnavailableReason.NOT_FOUND)
            } else {
                cursor.use { readChildren(it) }
            }
        } catch (e: SecurityException) {
            Listing.Failed(UnavailableReason.PERMISSION_LOST)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Stockage retiré en cours de route, fournisseur planté, curseur invalide…
            Listing.Failed(UnavailableReason.NOT_FOUND)
        }
    }

    private fun readChildren(cursor: Cursor): Listing {
        val idCol = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
        val sizeCol = cursor.getColumnIndex(Document.COLUMN_SIZE)
        if (idCol < 0 || nameCol < 0 || mimeCol < 0) {
            return Listing.Failed(UnavailableReason.NOT_FOUND)
        }
        val out = ArrayList<Child>()
        while (cursor.moveToNext()) {
            val documentId = cursor.getString(idCol) ?: continue
            val name = cursor.getString(nameCol) ?: continue
            val mime = cursor.getString(mimeCol) ?: ""
            val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
            out.add(Child(documentId, name, mime == Document.MIME_TYPE_DIR, size, mime))
        }
        return Listing.Ok(out)
    }

    // ------------------------------------------------------------------------------------------
    // Classification des fichiers
    // ------------------------------------------------------------------------------------------

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot < 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    private fun isAudioFile(name: String, mime: String): Boolean {
        val ext = extensionOf(name)
        if (ext in AUDIO_EXTENSIONS) return true
        // Fournisseur qui annonce un vrai type audio pour un fichier sans extension.
        return ext.isEmpty() && mime.startsWith("audio/")
    }

    private fun isImageFile(name: String): Boolean = extensionOf(name) in IMAGE_EXTENSIONS

    private fun isIgnoredDir(name: String): Boolean = name.lowercase(Locale.ROOT) in IGNORED_DIRS

    /** Plus le rang est petit, plus le nom ressemble à une pochette d'album. */
    private fun coverRank(fileName: String): Int {
        val stem = PathNormalizer.stripExtension(fileName).lowercase(Locale.ROOT)
        return when {
            stem == "cover" -> 0
            stem == "folder" -> 1
            stem == "front" -> 2
            stem == "albumart" || stem == "album" -> 3
            stem == "artwork" || stem == "art" -> 4
            stem.startsWith("albumart") -> 5 // Windows Media Player : AlbumArt_{GUID}_Large.jpg
            stem.startsWith("cover") || stem.startsWith("folder") || stem.startsWith("front") -> 6
            else -> GENERIC_IMAGE_RANK
        }
    }

    /** Deux arbres imbriqués donnent le même documentId : on n'indexe le fichier qu'une fois. */
    private fun uniqueKey(source: MusicSource, documentId: String): String =
        "${source.treeUri.authority}/$documentId"

    private companion object {
        const val MIN_CHUNK = 100
        const val MAX_CHUNK = 500
        const val MIN_FLUSH_INTERVAL_MS = 150L
        const val MAX_FLUSH_INTERVAL_MS = 1000L
        const val PROGRESS_INTERVAL_MS = 200L
        const val MAX_DEPTH = 20
        const val GENERIC_IMAGE_RANK = 9

        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE
        )

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "ogg", "oga", "opus", "wav")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        // Comparaison en minuscules.
        val IGNORED_DIRS = setOf(
            "@eadir", "\$recycle.bin", "system volume information", "lost+found", "#recycle", "@recycle"
        )

        val CHILD_ORDER = Comparator<Child> { a, b -> PathNormalizer.naturalCompare(a.name, b.name) }
    }
}
