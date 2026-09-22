package com.n7folder.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.n7folder.player.data.TrackEntry
import java.util.concurrent.atomic.AtomicLong

/**
 * Transforme une piste indexée en MediaItem. Titre, artiste et album viennent des DOSSIERS
 * (pas des tags ID3), comme dans le reste de l'application.
 *
 * Chaque entrée de la file reçoit un identifiant unique ("documentId#n") : la même piste peut être
 * ajoutée plusieurs fois, et l'identifiant sert de clé stable pour le glisser-déposer.
 */
object MediaItemFactory {
    private const val ID_SEPARATOR = '#'
    private val counter = AtomicLong(0L)

    fun create(track: TrackEntry, artwork: Uri?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.folder.artist)
            .setAlbumArtist(track.folder.artist)
            .setAlbumTitle(track.folder.album)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .setArtworkUri(artwork)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId("${track.documentId}$ID_SEPARATOR${counter.incrementAndGet()}")
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Retrouve le documentId d'origine à partir de l'identifiant d'une entrée de la file. */
    fun documentIdOf(mediaId: String): String = mediaId.substringBeforeLast(ID_SEPARATOR)
}
