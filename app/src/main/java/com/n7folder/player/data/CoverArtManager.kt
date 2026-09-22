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
 * Pochettes en deux temps :
 * 1. immédiat : images locales (cover.jpg, folder.jpg…) repérées par [FileScanner] ;
 * 2. à la demande : pochette intégrée aux fichiers audio, extraite seulement pour ce qui est affiché,
 *    réduite (512 px max) et mise en cache disque.
 *
 * Aucun Bitmap n'est conservé : chaque image décodée est recyclée juste après avoir été écrite ;
 * l'affichage passe ensuite par Coil, qui gère ses propres caches mémoire/disque.
 */
class CoverArtManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dir: File = File(appContext.cacheDir, "covers").apply { mkdirs() }

    /** Au plus 2 extractions simultanées : chacune ouvre un fichier audio via le fournisseur SAF. */
    private val gate = Semaphore(2)
    private var writes = 0

    /**
     * @param key identifiant stable de la pochette (un album, une piste…)
     * @param trackUri fichier audio dont on lit la pochette intégrée
     * @return le fichier JPEG en cache, ou null si le fichier ne contient pas de pochette
     */
    suspend fun embeddedCoverFor(key: String, trackUri: Uri): File? = withContext(Dispatchers.IO) {
        val name = sha1(key)
        val jpg = File(dir, "$name.jpg")
        if (jpg.exists()) return@withContext jpg
        val none = File(dir, "$name.none")
        if (none.exists()) return@withContext null

        gate.withPermit {
            if (jpg.exists()) return@withPermit jpg
            val tmp = File(dir, "$name.tmp")
            val ok = extract(trackUri, tmp)
            if (ok && tmp.renameTo(jpg)) {
                noteWrite()
                jpg
            } else {
                tmp.delete()
                try {
                    none.createNewFile()
                } catch (e: Exception) {
                    // Cache en lecture seule ou plein : on réessaiera au prochain affichage.
                }
                null
            }
        }
    }

    private fun extract(uri: Uri, target: File): Boolean {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val bytes = retriever.embeddedPicture ?: return false

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
        } catch (e: Exception) {
            // Fichier illisible, fournisseur injoignable, format sans pochette… : pas de pochette.
            return false
        } finally {
            retriever.release()
        }
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
