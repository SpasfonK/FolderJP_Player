package com.n7folder.player.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Liste persistante des dossiers de musique choisis via ACTION_OPEN_DOCUMENT_TREE.
 *
 * - takePersistableUriPermission : l'accès survit au redémarrage de l'application.
 * - Multi-dossiers : chaque source garde un [MusicSource.id] stable.
 * - Relink : si un stockage externe est remplacé (nouvelle carte SD, autre clé USB), [relink] rattache
 *   la source existante à la nouvelle Uri sans perdre son identité ni ses réglages.
 *
 * Toutes les méthodes font des appels binder / disque : à appeler depuis Dispatchers.IO.
 */
class SourceStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): List<MusicSource> = synchronized(lock) { readLocked() }

    /**
     * Ajoute un dossier (ou renvoie la source existante si cette Uri est déjà connue).
     * @return null si l'autorisation persistante n'a pas pu être obtenue.
     */
    fun add(treeUri: Uri): MusicSource? = synchronized(lock) { addLocked(treeUri) }

    fun remove(sourceId: String) = synchronized(lock) { removeLocked(sourceId) }

    /**
     * Rattache une source existante à une nouvelle Uri (stockage reconnecté ailleurs).
     * @return la source mise à jour, ou null (source inconnue / autorisation impossible).
     */
    fun relink(sourceId: String, newTreeUri: Uri): MusicSource? =
        synchronized(lock) { relinkLocked(sourceId, newTreeUri) }

    fun setRootIsArtist(sourceId: String, value: Boolean) = synchronized(lock) {
        val updated = readLocked().map { if (it.id == sourceId) it.copy(rootIsArtist = value) else it }
        writeLocked(updated)
    }

    // ------------------------------------------------------------------------------------------

    private fun addLocked(treeUri: Uri): MusicSource? {
        val current = readLocked()
        val existing = current.firstOrNull { it.treeUri == treeUri }
        if (existing != null) return existing
        if (!persist(treeUri)) return null

        val display = queryDisplayName(treeUri)
        val source = MusicSource(
            id = UUID.randomUUID().toString(),
            treeUri = treeUri,
            label = display ?: fallbackLabel(treeUri),
            rootName = rootNameOf(treeUri, display)
        )
        writeLocked(current + source)
        return source
    }

    private fun removeLocked(sourceId: String) {
        val current = readLocked()
        val target = current.firstOrNull { it.id == sourceId } ?: return
        val remaining = current.filter { it.id != sourceId }
        writeLocked(remaining)
        if (remaining.none { it.treeUri == target.treeUri }) release(target.treeUri)
    }

    private fun relinkLocked(sourceId: String, newTreeUri: Uri): MusicSource? {
        val current = readLocked()
        val old = current.firstOrNull { it.id == sourceId } ?: return null
        if (!persist(newTreeUri)) return null

        val display = queryDisplayName(newTreeUri)
        val updated = old.copy(
            treeUri = newTreeUri,
            label = display ?: fallbackLabel(newTreeUri),
            rootName = rootNameOf(newTreeUri, display)
        )
        val remaining = current.map { if (it.id == sourceId) updated else it }
        writeLocked(remaining)
        if (old.treeUri != newTreeUri && remaining.none { it.treeUri == old.treeUri }) {
            release(old.treeUri)
        }
        return updated
    }

    private fun readLocked(): List<MusicSource> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<MusicSource>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                out.add(
                    MusicSource(
                        id = o.getString("id"),
                        treeUri = Uri.parse(o.getString("uri")),
                        label = o.optString("label", ""),
                        rootName = o.optString("rootName", ""),
                        rootIsArtist = o.optBoolean("rootIsArtist", false)
                    )
                )
            }
            out
        } catch (e: JSONException) {
            // Données corrompues : on repart d'une liste vide plutôt que de planter au démarrage.
            emptyList()
        }
    }

    private fun writeLocked(sources: List<MusicSource>) {
        val array = JSONArray()
        for (s in sources) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("uri", s.treeUri.toString())
            o.put("label", s.label)
            o.put("rootName", s.rootName)
            o.put("rootIsArtist", s.rootIsArtist)
            array.put(o)
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    private fun persist(uri: Uri): Boolean = try {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun release(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission déjà absente : rien à libérer.
        } catch (e: IllegalArgumentException) {
            // Uri invalide : rien à libérer.
        }
    }

    /** Nom d'affichage du dossier racine tel que le fournisseur le connaît (null si injoignable). */
    private fun queryDisplayName(treeUri: Uri): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            appContext.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fallbackLabel(treeUri: Uri): String {
        val docId = treeDocumentIdOrNull(treeUri) ?: return "Dossier"
        val leaf = pathPartOf(docId).trimEnd('/').substringAfterLast('/')
        if (leaf.isNotEmpty()) return leaf
        return if (docId.startsWith("primary", ignoreCase = true)) "Stockage interne" else "Stockage externe"
    }

    /**
     * Nom du dossier racine, ou "" pour la racine d'un volume entier (le nom du volume n'est pas un
     * nom d'artiste).
     */
    private fun rootNameOf(treeUri: Uri, displayName: String?): String {
        val docId = treeDocumentIdOrNull(treeUri) ?: return ""
        val path = pathPartOf(docId).trimEnd('/')
        if (docId.contains(':') && path.isEmpty()) return ""
        val leaf = path.substringAfterLast('/')
        return if (!displayName.isNullOrBlank()) displayName else leaf
    }

    private fun treeDocumentIdOrNull(treeUri: Uri): String? = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** "primary:Musique/Rock" -> "Musique/Rock" ; "1234" (identifiant opaque) -> "1234". */
    private fun pathPartOf(docId: String): String {
        val colon = docId.indexOf(':')
        return if (colon >= 0) docId.substring(colon + 1) else docId
    }

    private companion object {
        const val PREFS_NAME = "n7_sources"
        const val KEY_SOURCES = "sources_json"
    }
}
