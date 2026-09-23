package com.n7folder.player.data

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Dernier maillon de la chaîne de pochettes, quand ni un fichier local (cover.jpg…) ni une image
 * intégrée au fichier audio n'ont donné de résultat : recherche en ligne, comme le fait Musico.
 *
 * Ordre : TheAudioDB (clé de test publique, gratuite, sans inscription) puis Last.fm si une clé a
 * été renseignée dans [LASTFM_API_KEY] (vide par défaut : Last.fm est alors ignoré).
 *
 * Peut être désactivé par la personne (réglage persistant) — utile en itinérance ou pour rester
 * hors ligne par principe.
 */
object RemoteCoverProvider {

    /**
     * Clé de test TheAudioDB, publique et documentée par l'éditeur pour un usage personnel à faible
     * volume. Suffisante pour une bibliothèque personnelle ; à remplacer par une clé propre en cas
     * d'usage intensif.
     */
    private const val AUDIODB_TEST_KEY = "2"

    /** Vide par défaut : Last.fm exige une inscription. Renseigner ici pour l'activer en second recours. */
    const val LASTFM_API_KEY = ""

    private const val PREFS_NAME = "n7_remote_cover"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).apply()
    }

    /**
     * Cherche une pochette pour cet artiste/album en ligne. Ne lève jamais d'exception : tout échec
     * (pas de réseau, aucun résultat, appareil hors ligne) donne simplement `null`.
     * À appeler depuis Dispatchers.IO.
     */
    fun fetchCoverBytes(context: Context, artist: String, album: String): ByteArray? {
        if (!isEnabled(context)) return null
        if (artist.isBlank() || album.isBlank()) return null

        val fromAudioDb = httpGetText(buildTheAudioDbUrl(artist, album))
        if (fromAudioDb != null) {
            val thumbUrl = extractAlbumThumbUrl(fromAudioDb)
            if (thumbUrl != null) {
                val bytes = httpGetBytes(thumbUrl)
                if (bytes != null) return bytes
            }
        }

        if (LASTFM_API_KEY.isNotBlank()) {
            val fromLastFm = httpGetText(buildLastFmUrl(LASTFM_API_KEY, artist, album))
            if (fromLastFm != null) {
                val imageUrl = extractLastFmImageUrl(fromLastFm)
                if (imageUrl != null) {
                    val bytes = httpGetBytes(imageUrl)
                    if (bytes != null) return bytes
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------------------------------
    // Fonctions pures (construction d'URL, extraction JSON) — testées indépendamment du réseau.
    // ------------------------------------------------------------------------------------------

    fun buildTheAudioDbUrl(artist: String, album: String): String =
        "https://www.theaudiodb.com/api/v1/json/$AUDIODB_TEST_KEY/searchalbum.php?s=" +
            encode(artist) + "&a=" + encode(album)

    fun buildLastFmUrl(apiKey: String, artist: String, album: String): String =
        "https://ws.audioscrobbler.com/2.0/?method=album.getinfo&api_key=" + encode(apiKey) +
            "&artist=" + encode(artist) + "&album=" + encode(album) + "&format=json"

    /** `null` si TheAudioDB n'a rien trouvé ; sinon l'URL de la meilleure image (HQ de préférence). */
    fun extractAlbumThumbUrl(json: String): String? {
        if (ALBUM_NULL.matcher(json).find()) return null
        val hq = firstNonEmptyGroup(THUMB_HQ, json)
        if (hq != null) return unescape(hq)
        return firstNonEmptyGroup(THUMB, json)?.let { unescape(it) }
    }

    /** Last.fm liste les tailles du plus petit au plus grand : on garde la dernière URL non vide. */
    fun extractLastFmImageUrl(json: String): String? {
        val matcher = LASTFM_IMAGE.matcher(json)
        var best: String? = null
        while (matcher.find()) {
            val url = matcher.group(1)
            if (!url.isNullOrEmpty()) best = unescape(url)
        }
        return best
    }

    private fun firstNonEmptyGroup(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        if (matcher.find() && matcher.group(1).isNotEmpty()) return matcher.group(1)
        return null
    }

    private fun unescape(url: String): String = url.replace("\\/", "/")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val ALBUM_NULL: Pattern = Pattern.compile("\"album\"\\s*:\\s*null")
    private val THUMB_HQ: Pattern = Pattern.compile("\"strAlbumThumbHQ\"\\s*:\\s*\"([^\"]*)\"")
    private val THUMB: Pattern = Pattern.compile("\"strAlbumThumb\"\\s*:\\s*\"([^\"]*)\"")
    private val LASTFM_IMAGE: Pattern = Pattern.compile("\"#text\"\\s*:\\s*\"([^\"]*)\"")

    // ------------------------------------------------------------------------------------------
    // Réseau (impur, jamais testé unitairement — voir les fonctions pures ci-dessus)
    // ------------------------------------------------------------------------------------------

    private fun httpGetText(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(urlString)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun httpGetBytes(urlString: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(urlString)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) null else bytes
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        return connection
    }
}
