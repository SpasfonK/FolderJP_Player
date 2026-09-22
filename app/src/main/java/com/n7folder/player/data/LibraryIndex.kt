package com.n7folder.player.data

import android.net.Uri

/**
 * Fusionne au fil de l'eau les lots de pistes émis par [FileScanner] en artistes -> albums -> pistes.
 *
 * - Fusion multi-dossiers "sans écrasement" : deux sources qui contiennent le même artiste/album
 *   alimentent la même entrée ; seules les pistes strictement identiques sont dédoublonnées, en amont,
 *   par le scanner.
 * - [snapshot] ne reconstruit que les artistes modifiés depuis le dernier appel : coût proportionnel
 *   au lot reçu, pas à la taille de la bibliothèque.
 *
 * NON thread-safe : à utiliser depuis une seule coroutine (celle qui collecte le scan).
 */
class LibraryIndex {

    private class AlbumAcc(val key: AlbumKey) {
        var name: String = ""
        var nameScore: Int = -1
        var year: Int? = null
        val discs = HashSet<Int>()
        val tracks = ArrayList<TrackEntry>()
    }

    private class ArtistAcc(val key: String) {
        var name: String = ""
        var nameScore: Int = -1
        var trackCount: Int = 0
        val albums = HashMap<String, AlbumAcc>()
        var cached: ArtistSummary? = null
    }

    private val artists = HashMap<String, ArtistAcc>()
    private val covers = HashMap<AlbumKey, Uri>()
    private val dirty = HashSet<String>()

    /** Nombre total de pistes indexées. */
    var trackCount: Int = 0
        private set

    fun addBatch(tracks: List<TrackEntry>, coverHints: List<CoverHint>) {
        for (track in tracks) {
            val folder = track.folder

            val artist = artists.getOrPut(folder.artistKey) { ArtistAcc(folder.artistKey) }
            // "Angèle" l'emporte sur "Angele", "Saez" sur "SAEZ" pour l'affichage.
            val artistScore = PathNormalizer.displayScore(folder.artist)
            if (artistScore > artist.nameScore) {
                artist.name = folder.artist
                artist.nameScore = artistScore
            }

            val album = artist.albums.getOrPut(folder.albumKey) {
                AlbumAcc(AlbumKey(folder.artistKey, folder.albumKey))
            }
            val albumScore = PathNormalizer.displayScore(folder.album)
            if (albumScore > album.nameScore) {
                album.name = folder.album
                album.nameScore = albumScore
            }
            if (album.year == null) album.year = folder.year

            val disc = track.discNumber
            if (disc != null) album.discs.add(disc)

            album.tracks.add(track)
            artist.trackCount++
            trackCount++
            dirty.add(folder.artistKey)
        }

        for (hint in coverHints) {
            if (!covers.containsKey(hint.key)) {
                covers[hint.key] = hint.uri
                dirty.add(hint.key.artistKey)
            }
        }
    }

    /** Liste triée des artistes, prête pour l'interface (objets immuables). */
    fun snapshot(): List<ArtistSummary> {
        for (key in dirty) {
            val acc = artists[key] ?: continue
            acc.cached = buildSummary(acc)
        }
        dirty.clear()

        val out = ArrayList<ArtistSummary>(artists.size)
        for (acc in artists.values) {
            val summary = acc.cached
            if (summary != null) out.add(summary)
        }
        out.sortWith(ARTIST_ORDER)
        return out
    }

    private fun buildSummary(artist: ArtistAcc): ArtistSummary {
        val albums = ArrayList<AlbumSummary>(artist.albums.size)
        for (acc in artist.albums.values) {
            albums.add(
                AlbumSummary(
                    key = acc.key,
                    name = acc.name,
                    year = acc.year,
                    discCount = acc.discs.size,
                    coverUri = covers[acc.key],
                    tracks = acc.tracks.sortedWith(TRACK_ORDER)
                )
            )
        }
        albums.sortWith(ALBUM_ORDER)
        return ArtistSummary(artist.key, artist.name, albums)
    }

    private companion object {
        val ARTIST_ORDER = Comparator<ArtistSummary> { a, b ->
            PathNormalizer.naturalCompare(a.key, b.key)
        }

        val ALBUM_ORDER = Comparator<AlbumSummary> { a, b ->
            val ya = a.year ?: Int.MAX_VALUE
            val yb = b.year ?: Int.MAX_VALUE
            if (ya != yb) ya.compareTo(yb) else PathNormalizer.naturalCompare(a.name, b.name)
        }
    }
}
