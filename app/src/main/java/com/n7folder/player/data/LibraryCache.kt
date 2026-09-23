package com.n7folder.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bibliothèque telle que reconstruite depuis le cache disque, prête à alimenter un [LibraryIndex]. */
class LibrarySnapshot(val tracks: List<TrackEntry>, val covers: List<CoverHint>)

/**
 * Fige la bibliothèque déjà analysée sur le disque (fichier JSON compressé, dans le stockage privé
 * de l'application — pas le cache, qui peut être vidé par le système). Au démarrage suivant, même
 * après un redémarrage du téléphone, [load] restitue la bibliothèque instantanément, sans repasser
 * par le sélecteur de stockage (SAF) : aucune nouvelle analyse tant que l'utilisateur ne force pas
 * "Réanalyser" ou n'ajoute pas un nouveau dossier.
 *
 * Le fichier est écrit intégralement puis renommé (jamais réécrit sur place) : une application tuée
 * en cours d'écriture ne laisse jamais un cache à moitié écrit derrière elle.
 */
class LibraryCache(context: Context) {

    private val appContext: Context = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    /** `null` si aucun cache n'existe encore, ou s'il est illisible/corrompu. */
    suspend fun load(sources: List<MusicSource>): LibrarySnapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val text = GZIPInputStream(FileInputStream(file)).use { gz ->
                gz.bufferedReader(Charsets.UTF_8).readText()
            }
            parse(JSONObject(text), sources)
        } catch (e: Exception) {
            // Cache corrompu ou format d'une version antérieure incompatible : on repart d'un scan.
            null
        }
    }

    /** Remplace entièrement le cache par [tracks]/[covers] (l'état complet de la bibliothèque). */
    suspend fun save(tracks: List<TrackEntry>, covers: List<CoverHint>) = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("tracks", tracksToJson(tracks))
            root.put("covers", coversToJson(covers))

            val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
            GZIPOutputStream(FileOutputStream(tmp)).use { gz ->
                gz.write(root.toString().toByteArray(Charsets.UTF_8))
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            // Cache best-effort : un échec d'écriture ne doit jamais faire échouer le scan lui-même.
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun tracksToJson(tracks: List<TrackEntry>): JSONArray {
        val array = JSONArray()
        for (t in tracks) {
            val o = JSONObject()
            o.put("sourceId", t.sourceId)
            o.put("documentId", t.documentId)
            o.put("fileName", t.fileName)
            o.put("title", t.title)
            putNullableInt(o, "trackNumber", t.trackNumber)
            putNullableInt(o, "discNumber", t.discNumber)
            o.put("sizeBytes", t.sizeBytes)
            val dirs = JSONArray()
            for (segment in t.dirPath) dirs.put(segment)
            o.put("dirPath", dirs)
            o.put("artist", t.folder.artist)
            o.put("artistKey", t.folder.artistKey)
            o.put("album", t.folder.album)
            o.put("albumKey", t.folder.albumKey)
            putNullableInt(o, "year", t.folder.year)
            putNullableInt(o, "disc", t.folder.disc)
            array.put(o)
        }
        return array
    }

    private fun coversToJson(covers: List<CoverHint>): JSONArray {
        val array = JSONArray()
        for (c in covers) {
            val o = JSONObject()
            o.put("artistKey", c.key.artistKey)
            o.put("albumKey", c.key.albumKey)
            o.put("uri", c.uri.toString())
            array.put(o)
        }
        return array
    }

    private fun parse(root: JSONObject, sources: List<MusicSource>): LibrarySnapshot {
        val treeUriById = HashMap<String, Uri>(sources.size * 2)
        for (s in sources) treeUriById[s.id] = s.treeUri

        // Un seul ParsedFolder par dossier réel, comme pendant un scan : mémoire proportionnée au
        // nombre de dossiers, pas au nombre de pistes.
        val folderInterning = HashMap<ParsedFolder, ParsedFolder>()

        val trackArray = root.optJSONArray("tracks") ?: JSONArray()
        val tracks = ArrayList<TrackEntry>(trackArray.length())
        for (i in 0 until trackArray.length()) {
            val o = trackArray.optJSONObject(i) ?: continue
            val sourceId = o.optString("sourceId", "")
            // Le dossier source a été retiré entre-temps (ou le cache date d'avant un "relier") :
            // cette piste est ignorée plutôt que de faire échouer tout le chargement.
            val treeUri = treeUriById[sourceId] ?: continue
            val documentId = o.optString("documentId", "")
            if (documentId.isEmpty()) continue

            val dirsArray = o.optJSONArray("dirPath")
            val dirPath = ArrayList<String>(dirsArray?.length() ?: 0)
            if (dirsArray != null) {
                for (j in 0 until dirsArray.length()) dirPath.add(dirsArray.optString(j))
            }

            val rawFolder = ParsedFolder(
                artist = o.optString("artist", ""),
                artistKey = o.optString("artistKey", ""),
                album = o.optString("album", ""),
                albumKey = o.optString("albumKey", ""),
                year = optNullableInt(o, "year"),
                disc = optNullableInt(o, "disc")
            )
            val folder = folderInterning.getOrPut(rawFolder) { rawFolder }

            tracks.add(
                TrackEntry(
                    sourceId = sourceId,
                    treeUri = treeUri,
                    documentId = documentId,
                    fileName = o.optString("fileName", ""),
                    title = o.optString("title", ""),
                    trackNumber = optNullableInt(o, "trackNumber"),
                    discNumber = optNullableInt(o, "discNumber"),
                    folder = folder,
                    dirPath = dirPath,
                    sizeBytes = o.optLong("sizeBytes", 0L)
                )
            )
        }

        val coverArray = root.optJSONArray("covers") ?: JSONArray()
        val covers = ArrayList<CoverHint>(coverArray.length())
        for (i in 0 until coverArray.length()) {
            val o = coverArray.optJSONObject(i) ?: continue
            val uriString = o.optString("uri", "")
            if (uriString.isEmpty()) continue
            val key = AlbumKey(o.optString("artistKey", ""), o.optString("albumKey", ""))
            covers.add(CoverHint(key, Uri.parse(uriString)))
        }

        return LibrarySnapshot(tracks, covers)
    }

    private fun putNullableInt(o: JSONObject, key: String, value: Int?) {
        if (value != null) o.put(key, value) else o.put(key, JSONObject.NULL)
    }

    private fun optNullableInt(o: JSONObject, key: String): Int? =
        if (o.isNull(key)) null else o.optInt(key)

    private companion object {
        const val FILE_NAME = "library_cache.json.gz"
    }
}
