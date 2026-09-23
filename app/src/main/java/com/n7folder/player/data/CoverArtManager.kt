package com.n7folder.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Pochettes en trois temps, dans l'ordre (comme Musico) :
 * 1. immédiat : image locale dédiée (cover.jpg, folder.jpg…) repérée par [FileScanner] — gérée en
 *    amont, avant même d'appeler cette classe ;
 * 2. tags embarqués : image ID3 / Vorbis Comment / MP4 lue via MediaMetadataRetriever ;
 * 3. en ligne (repli) : [RemoteCoverProvider] (TheAudioDB, puis Last.fm si une clé est fournie).
 *
 * Chaque pochette obtenue est réduite (512 px max) et mise en cache disque sous une seule clé
 * ([coverFor]'s `key`), qu'elle vienne des tags ou du réseau : la source qui répond la première
 * "gagne" et les suivantes ne sont jamais tentées à nouveau pour cette clé (marqueur `.none` si la
 * chaîne entière échoue). Aucun Bitmap n'est conservé en mémoire : chaque image décodée est recyclée
 * juste après avoir été écrite ; l'affichage passe ensuite par Coil, qui gère ses propres caches.
 */
class CoverArtManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dir: File = File(appContext.cacheDir, "covers").apply { mkdirs() }

    /** Bornes de concurrence séparées : le disque (tags embarqués) et le réseau ont des coûts différents. */
    private val diskGate = Semaphore(2)
    private val networkGate = Semaphore(2)
    private var writes = 0

    /**
     * Pochette pour [key] (identifiant stable d'un album ou d'une piste), en tentant dans l'ordre les
     * tags embarqués de [trackUri] puis, si [artist]/[album] sont fournis, la recherche en ligne.
     * @return le fichier JPEG en cache, ou null si aucune source n'a rien donné.
     */
    suspend fun coverFor(key: String, trackUri: Uri?, artist: String?, album: String?): File? =
        withContext(Dispatchers.IO) {
            val name = sha1(key)
            val jpg = File(dir, "$name.jpg")
            if (jpg.exists()) return@withContext jpg
            val none = File(dir, "$name.none")
            if (none.exists()) return@withContext null

            if (trackUri != null) {
                val fromTags = diskGate.withPermit {
                    saveIfAbsent(name) { target -> extractEmbedded(trackUri, target) }
                }
                if (fromTags != null) return@withContext fromTags
            }

            if (!artist.isNullOrBlank() && !album.isNullOrBlank()) {
                val bytes = networkGate.withPermit {
                    RemoteCoverProvider.fetchCoverBytes(appContext, artist, album)
                }
                if (bytes != null) {
                    val fromRemote = saveIfAbsent(name) { target -> downscaleAndSave(bytes, target) }
                    if (fromRemote != null) return@withContext fromRemote
                }
            }

            // Chaîne entière épuisée : on ne retentera pas à chaque affichage.
            try {
                none.createNewFile()
            } catch (e: Exception) {
                // Cache en lecture seule ou plein : on réessaiera au prochain affichage.
            }
            null
        }

    // ------------------------------------------------------------------------------------------

    private fun saveIfAbsent(name: String, produce: (File) -> Boolean): File? {
        val jpg = File(dir, "$name.jpg")
        if (jpg.exists()) return jpg
        val tmp = File(dir, "$name.tmp")
        val ok = try {
            produce(tmp)
        } catch (e: Exception) {
            false
        }
        return if (ok && tmp.renameTo(jpg)) {
            noteWrite()
            jpg
        } else {
            tmp.delete()
            null
        }
    }

    private fun extractEmbedded(uri: Uri, target: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val bytes = retriever.embeddedPicture ?: return false
            downscaleAndSave(bytes, target)
        } catch (e: Exception) {
            // Fichier illisible, fournisseur injoignable, format sans pochette… : pas de pochette.
            false
        } finally {
            retriever.release()
        }
    }

    /** Décode [bytes], les réduit sous MAX_SIDE px, et écrit un JPEG dans [target]. */
    private fun downscaleAndSave(bytes: ByteArray, target: File): Boolean {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE && bounds.outHeight / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        try {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        } finally {
            bitmap.recycle()
        }
        return target.length() > 0L
    }

    /** Garde le cache sous MAX_CACHE_BYTES en supprimant les pochettes les plus anciennes. */
    private fun noteWrite() {
        writes++
        if (writes % TRIM_EVERY != 0) return
        val files = dir.listFiles() ?: return
        var total = 0L
        for (f in files) total += f.length()
        if (total <= MAX_CACHE_BYTES) return
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (f in oldestFirst) {
            if (total <= TRIM_TARGET_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    companion object {
        private const val MAX_SIDE = 512
        private const val TRIM_EVERY = 25
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val TRIM_TARGET_BYTES = 48L * 1024 * 1024

        @Volatile
        private var instance: CoverArtManager? = null

        fun from(context: Context): CoverArtManager {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: CoverArtManager(context).also { instance = it }
            }
        }
    }
}
