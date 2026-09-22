package com.n7folder.player.data

import java.text.Normalizer
import java.util.Locale

/**
 * Résultat de l'analyse du chemin d'un dossier. Une seule instance est partagée par toutes les
 * pistes du même dossier (économie mémoire sur 50 000 fichiers).
 */
data class ParsedFolder(
    val artist: String,
    val artistKey: String,
    val album: String,
    val albumKey: String,
    val year: Int?,
    val disc: Int?
) {
    /** Identifiant d'album unique : deux albums homonymes d'artistes différents ne fusionnent pas. */
    val albumId: String get() = "$artistKey|$albumKey"
}

/** Résultat de l'analyse d'un nom de fichier audio. */
data class ParsedTrackName(val track: Int?, val disc: Int?, val title: String)

/**
 * ParsePath v6+ : déduit artiste / album / année / CD / titre à partir des seuls noms de dossiers.
 *
 * Kotlin pur (aucune dépendance Android) : testable sur la JVM.
 *
 * @param knownArtistKeys clés (voir [keyify]) des dossiers artistes connus. Sert uniquement à décider
 * si "A & B" doit être regroupé sous "A" : on ne coupe que si un dossier "A" existe réellement,
 * ce qui protège "Simon & Garfunkel", "Hall & Oates", "Bigflo & Oli"…
 */
class PathNormalizer(private val knownArtistKeys: Set<String> = emptySet()) {

    /**
     * @param dirSegments noms des dossiers, de la racine jusqu'au dossier contenant le fichier
     * (le nom du fichier n'en fait pas partie).
     */
    fun parseFolder(dirSegments: List<String>): ParsedFolder {
        val sig = significantFolders(dirSegments)

        // CD1 / Disc 2 : rattachés à l'album parent, jamais un niveau d'album parasite.
        var disc: Int? = null
        while (sig.size >= 2) {
            val d = discNumberOfFolder(sig[sig.size - 1], allowBare = sig.size >= 3) ?: break
            if (disc == null) disc = d
            sig.removeAt(sig.size - 1)
        }

        // Artiste/2004/Album : un dossier "année" seul n'est pas un album.
        var year: Int? = null
        if (sig.size >= 3) {
            val y = yearOnly(sig[1])
            if (y != null) {
                year = y
                sig.removeAt(1)
            }
        }

        var artistRaw: String? = null
        var albumRaw: String? = null
        if (sig.size == 1) {
            val lone = sig[0]
            val lead = takeLeadingYear(lone)
            val split = splitArtistAlbum(lead?.second ?: lone)
            if (split != null) {
                // "SAEZ - Debbie" ou "1997 - Radiohead - OK Computer"
                artistRaw = split.first
                albumRaw = split.second
                if (lead != null) year = lead.first
            } else if (lead != null || takeTrailingYear(lone) != null) {
                // "1970 - Black Sabbath" : c'est un album (année), jamais un artiste nommé "1970"
                albumRaw = lone
            } else {
                // dossier artiste contenant des pistes en vrac
                artistRaw = lone
            }
        } else if (sig.size >= 2) {
            artistRaw = sig[0]
            albumRaw = sig[1]
        }

        val artist = if (artistRaw != null) cleanArtist(artistRaw) else UNKNOWN_ARTIST
        val artistKey = keyOrRaw(artist)

        var albumName = LOOSE_ALBUM
        if (albumRaw != null) {
            val parts = cleanAlbum(albumRaw, artistKey)
            albumName = parts.name
            if (parts.year != null) year = parts.year
            if (disc == null) disc = parts.disc
        }
        return ParsedFolder(artist, artistKey, albumName, keyOrRaw(albumName), year, disc)
    }

    /**
     * @param numberedBySpace true si tous les fichiers du dossier sont numérotés "01 Titre"
     * (sans ponctuation). Évite de manger "99 Luftballons" ou "7 Rings" dans un dossier isolé.
     */
    fun parseTrackName(
        fileName: String,
        artistKey: String = "",
        numberedBySpace: Boolean = false
    ): ParsedTrackName {
        var base = collapse(stripExtension(fileName))
        if (!base.contains(' ') && base.contains('_')) base = collapse(base.replace('_', ' '))

        var track: Int? = null
        var disc: Int? = null
        var title = base

        val discTrack = DISC_TRACK.matchEntire(base)
        val discValue = discTrack?.groupValues?.get(1)?.toInt() ?: 0
        if (discTrack != null && discValue in 1..20) {
            disc = discValue
            track = discTrack.groupValues[2].toInt()
            title = discTrack.groupValues[3]
        } else {
            val punct = TRACK_PUNCT.matchEntire(base)
            if (punct != null) {
                track = punct.groupValues[1].toInt()
                title = punct.groupValues[2]
            } else if (numberedBySpace) {
                val spaced = TRACK_SPACE.matchEntire(base)
                if (spaced != null) {
                    track = spaced.groupValues[1].toInt()
                    title = spaced.groupValues[2]
                }
            }
        }

        title = title.trim()
        val withoutArtist = stripArtistPrefix(title, artistKey)
        if (withoutArtist != null) title = withoutArtist
        if (title.isEmpty()) title = if (base.isNotEmpty()) base else fileName
        return ParsedTrackName(track, disc, title)
    }

    // ------------------------------------------------------------------------------------------
    // Étapes internes
    // ------------------------------------------------------------------------------------------

    /** Retire les dossiers génériques de tête (1/, Musique/, FLAC/…) et les dossiers de format. */
    private fun significantFolders(dirSegments: List<String>): MutableList<String> {
        val out = ArrayList<String>()
        var leading = true
        for (raw in dirSegments) {
            val seg = cleanSegment(raw)
            if (seg.isEmpty()) continue
            if (leading && isGenericContainer(seg)) continue
            leading = false
            if (isFormatFolder(seg)) continue
            out.add(seg)
        }
        return out
    }

    /** Nettoyage des invités : "Booba feat. Kaaris" -> "Booba" ; "A & B" -> "A" si le dossier "A" existe. */
    private fun cleanArtist(raw: String): String {
        var name = collapse(raw)
        val lead = takeLeadingYear(name)
        if (lead != null) name = lead.second
        name = cutAtFeatMarker(name)
        name = cutAtKnownCollab(name)
        return if (name.isBlank()) collapse(raw) else name
    }

    private fun cutAtKnownCollab(name: String): String {
        if (knownArtistKeys.isEmpty()) return name
        for (m in COLLAB_SEP.findAll(name)) {
            val left = name.substring(0, m.range.first).trim()
            if (left.isNotEmpty() && knownArtistKeys.contains(keyOrRaw(left))) return left
        }
        return name
    }

    private class AlbumParts(val name: String, val year: Int?, val disc: Int?)

    /** Élague artiste redondant, année, marqueur de CD et étiquettes de qualité du nom d'album. */
    private fun cleanAlbum(raw: String, artistKey: String): AlbumParts {
        var name = raw
        var year: Int? = null
        var disc: Int? = null

        val lead = takeLeadingYear(name)
        if (lead != null) {
            year = lead.first
            name = lead.second
        }
        val withoutArtist = stripArtistPrefix(name, artistKey)
        if (withoutArtist != null) name = withoutArtist
        if (year == null) {
            val lead2 = takeLeadingYear(name)
            if (lead2 != null) {
                year = lead2.first
                name = lead2.second
            }
        }

        var guard = 0
        var changed = true
        while (changed && guard < 4) {
            changed = false
            guard++
            val ty = takeTrailingYear(name)
            if (ty != null) {
                if (year == null) year = ty.first
                name = ty.second
                changed = true
            }
            val td = takeTrailingDisc(name)
            if (td != null) {
                if (disc == null) disc = td.first
                name = td.second
                changed = true
            }
            val tq = stripQualityTag(name)
            if (tq != null) {
                name = tq
                changed = true
            }
        }
        if (name.isBlank()) name = raw
        return AlbumParts(name.trim(), year, disc)
    }

    companion object {
        const val UNKNOWN_ARTIST = "Artiste inconnu"
        const val LOOSE_ALBUM = "Sans album"

        private val LATIN_DIACRITICS = Regex("[\\u0300-\\u036f]+")
        private val NOT_KEY_CHAR = Regex("[^\\p{L}\\p{N}\\p{M}]+")
        private val SPACES = Regex("\\s+")

        private val LEADING_YEAR_DASH =
            Regex("^((?:19|20)\\d{2})\\s*[-–—_.]+\\s*(?!\\d{1,2}[-._]\\d{1,2}\\b)(\\S.*)$")
        private val LEADING_YEAR_BRACKET =
            Regex("^[\\[(]((?:19|20)\\d{2})[\\])]\\s*[-–—_.]*\\s*(\\S.*)$")
        private val TRAILING_YEAR_BRACKET = Regex("\\s*[\\[(]((?:19|20)\\d{2})[\\])]\\s*$")
        private val TRAILING_YEAR_DASH = Regex("\\s+[-–—]\\s+((?:19|20)\\d{2})\\s*$")
        private val YEAR_ONLY = Regex("^((?:19|20)\\d{2})$")

        private val DISC_FOLDER = Regex(
            "^(?:cd|disc|disk|disque|disco|dvd)[\\s._-]*0*(\\d{1,2})(?:[\\s._:(\\[-].*)?$",
            RegexOption.IGNORE_CASE
        )
        private val TRAILING_DISC = Regex(
            "\\s*[(\\[\\-_ ]\\s*(?:cd|disc|disk|disque|disco)[\\s._-]*0*(\\d{1,2})\\s*[)\\]]?\\s*$",
            RegexOption.IGNORE_CASE
        )
        private val QUALITY_TAG = Regex(
            "\\s*[\\[(](?:flac|mp3|aac|ogg|opus|wav|alac|m4a|lossless|web|vinyl|24[\\s-]?bit|\\d{2,3}\\s?kbps?)\\b[^\\])]*[\\])]\\s*$",
            RegexOption.IGNORE_CASE
        )

        private val SPLIT_ARTIST_ALBUM = Regex("\\s+[-–—]\\s+")
        private val SEP_ANY = Regex("\\s*[-–—]+\\s*")
        private val FEAT_MARKER = Regex(
            "(?<![\\p{L}\\p{N}])(?:feat(?:uring)?\\.?|ft\\.?|avec|w/)(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE
        )
        private val COLLAB_SEP = Regex(
            "\\s+(?:&|et|and|x|×|\\+|/)\\s+|\\s*,\\s+",
            RegexOption.IGNORE_CASE
        )

        private val DISC_TRACK = Regex("^(\\d{1,2})-(\\d{2})(?!\\d)[\\s._)-]+(\\S.*)$")
        private val TRACK_PUNCT = Regex("^(\\d{1,3})(?!\\d)\\s*[-–—._):]+\\s*(\\S.*)$")
        private val TRACK_SPACE = Regex("^(\\d{1,3})\\s+(\\S.*)$")
        private val LEADING_NUMBER_SPACE = Regex("^(\\d{1,3})\\s+\\S")

        private val FORMAT_KEYS = setOf(
            "mp3", "mp3s", "flac", "flacs", "aac", "ogg", "opus", "wav", "m4a", "lossless", "lossy",
            "320", "320kbps", "256", "256kbps", "192", "128", "v0", "v2"
        )
        private val CONTAINER_KEYS = setOf(
            "musique", "musiques", "music", "musics", "audio", "audios", "son", "sons", "sound",
            "sounds", "song", "songs", "chanson", "chansons", "media", "medias", "library",
            "librairie", "bibliotheque", "collection", "collections", "download", "downloads",
            "telechargement", "telechargements", "sdcard", "storage", "emulated", "internal", "usb",
            "artists", "artistes", "interpretes", "albums"
        )

        /**
         * Clé d'identité : minuscules, sans accents latins, sans ponctuation, "&" = "and".
         * Angèle = Angele = ANGELE = "angele". Les autres écritures (kana, cyrillique, hangul…)
         * sont conservées telles quelles pour ne pas fusionner des noms différents.
         */
        fun keyify(input: String): String {
            if (input.isEmpty()) return ""
            val sb = StringBuilder(input.length + 8)
            for (ch in input.lowercase(Locale.ROOT)) {
                when (ch) {
                    'ß' -> sb.append("ss")
                    'æ' -> sb.append("ae")
                    'œ' -> sb.append("oe")
                    'ø' -> sb.append('o')
                    'đ', 'ð' -> sb.append('d')
                    'ł' -> sb.append('l')
                    'þ' -> sb.append("th")
                    'ı' -> sb.append('i')
                    '&' -> sb.append(" and ")
                    else -> sb.append(ch)
                }
            }
            val decomposed = Normalizer.normalize(sb, Normalizer.Form.NFKD)
            return NOT_KEY_CHAR.replace(LATIN_DIACRITICS.replace(decomposed, ""), "")
        }

        /** Comme [keyify], mais un nom fait uniquement de symboles ("!!!") garde une clé non vide. */
        fun keyOrRaw(name: String): String {
            val k = keyify(name)
            return if (k.isNotEmpty()) k else name.trim().lowercase(Locale.ROOT)
        }

        /** Dossier "conteneur" sans valeur d'artiste : Musique, FLAC, 1, 2, Downloads… */
        fun isGenericContainer(name: String): Boolean {
            val key = keyify(name)
            if (key.isEmpty()) return false
            if (key in CONTAINER_KEYS || key in FORMAT_KEYS) return true
            return key.length <= 2 && key.all { it.isDigit() }
        }

        fun isFormatFolder(name: String): Boolean = keyify(name) in FORMAT_KEYS

        /**
         * "CD1", "Disc 2", "Disque 1 - Bonus" -> numéro de disque.
         * @param allowBare accepte aussi un simple "1" / "2" (à n'utiliser que sous un dossier d'album).
         */
        fun discNumberOfFolder(name: String, allowBare: Boolean = false): Int? {
            val trimmed = name.trim()
            val m = DISC_FOLDER.matchEntire(trimmed)
            if (m != null) return m.groupValues[1].toInt()
            if (allowBare && trimmed.isNotEmpty() && trimmed.length <= 2 && trimmed.all { it.isDigit() }) {
                val n = trimmed.toInt()
                if (n in 1..30) return n
            }
            return null
        }

        /** Dossier qui hérite de la pochette de son parent (CD1, FLAC…). */
        fun isDiscOrFormatFolder(name: String): Boolean =
            discNumberOfFolder(name, allowBare = true) != null || isFormatFolder(name)

        /** Clé d'artiste que produirait ce nom de dossier situé au premier niveau significatif. */
        fun artistKeyHint(folderName: String): String {
            var name = cleanSegment(folderName)
            val lead = takeLeadingYear(name)
            if (lead != null) name = lead.second
            val split = splitArtistAlbum(name)
            val artistPart = split?.first ?: name
            return keyOrRaw(cutAtFeatMarker(artistPart))
        }

        /** Vrai si tous les fichiers sont numérotés "NN Titre" avec des numéros distincts. */
        fun looksSpaceNumbered(fileNames: List<String>): Boolean {
            if (fileNames.size < 2) return false
            val seen = HashSet<Int>()
            for (name in fileNames) {
                val m = LEADING_NUMBER_SPACE.find(name) ?: return false
                if (!seen.add(m.groupValues[1].toInt())) return false
            }
            return true
        }

        /** Meilleur nom d'affichage entre deux variantes : accents d'abord, puis casse mixte. */
        fun displayScore(name: String): Int {
            var score = 0
            for (c in Normalizer.normalize(name, Normalizer.Form.NFD)) {
                if (c in '\u0300'..'\u036f') score += 2
            }
            if (name.any { it.isLowerCase() }) score += 1
            return score
        }

        /** Tri naturel insensible à la casse : "Piste 2" < "Piste 10". */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ie = i
                    while (ie < a.length && a[ie].isDigit()) ie++
                    var je = j
                    while (je < b.length && b[je].isDigit()) je++
                    val na = a.substring(i, ie).trimStart('0')
                    val nb = b.substring(j, je).trimStart('0')
                    if (na.length != nb.length) return na.length.compareTo(nb.length)
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ie
                    j = je
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j)
        }

        fun stripExtension(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return fileName
            val ext = fileName.substring(dot + 1)
            return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) fileName.substring(0, dot) else fileName
        }

        // -------------------------------------------------------------------------------------
        // Helpers de texte
        // -------------------------------------------------------------------------------------

        private fun collapse(s: String): String = SPACES.replace(s.trim(), " ")

        /** "SAEZ_-_Debbie" (style scène, sans espaces) -> "SAEZ - Debbie". */
        private fun cleanSegment(raw: String): String {
            var s = collapse(raw)
            if (!s.contains(' ') && s.contains('_')) s = s.replace('_', ' ')
            return collapse(s)
        }

        private fun yearOnly(name: String): Int? {
            val m = YEAR_ONLY.matchEntire(name.trim()) ?: return null
            return m.groupValues[1].toInt()
        }

        /** "1970 - Nom", "1970_Nom", "[1970] Nom" -> (1970, "Nom"). "1989" ou "1989 (Deluxe)" : aucun changement. */
        private fun takeLeadingYear(name: String): Pair<Int, String>? {
            val m = LEADING_YEAR_DASH.find(name) ?: LEADING_YEAR_BRACKET.find(name) ?: return null
            return Pair(m.groupValues[1].toInt(), m.groupValues[2].trim())
        }

        /** "Nom (2008)", "Nom [2008]", "Nom - 2008". */
        private fun takeTrailingYear(name: String): Pair<Int, String>? {
            val m = TRAILING_YEAR_BRACKET.find(name) ?: TRAILING_YEAR_DASH.find(name) ?: return null
            val rest = name.substring(0, m.range.first).trim()
            if (rest.isEmpty()) return null
            return Pair(m.groupValues[1].toInt(), rest)
        }

        /** "Nom (CD2)", "Nom - Disc 1", "Nom [Disque 2]". */
        private fun takeTrailingDisc(name: String): Pair<Int, String>? {
            val m = TRAILING_DISC.find(name) ?: return null
            val rest = name.substring(0, m.range.first).trim()
            if (rest.isEmpty()) return null
            return Pair(m.groupValues[1].toInt(), rest)
        }

        /** "Nom [FLAC]", "Nom (320kbps)" -> "Nom". */
        private fun stripQualityTag(name: String): String? {
            val m = QUALITY_TAG.find(name) ?: return null
            val rest = name.substring(0, m.range.first).trim()
            return if (rest.isEmpty()) null else rest
        }

        /** "SAEZ - Debbie" -> ("SAEZ", "Debbie"). Refuse "1970 - X", "01 - X", "CD1 - X". */
        private fun splitArtistAlbum(name: String): Pair<String, String>? {
            val m = SPLIT_ARTIST_ALBUM.find(name) ?: return null
            val left = name.substring(0, m.range.first).trim()
            val right = name.substring(m.range.last + 1).trim()
            if (left.isEmpty() || right.isEmpty()) return null
            if (left.all { it.isDigit() }) return null
            if (discNumberOfFolder(left) != null) return null
            return Pair(left, right)
        }

        /**
         * Si [name] commence par l'artiste suivi d'un tiret ("SAEZ - Debbie" sous le dossier "Saez"),
         * renvoie le reste ("Debbie"). Comparaison par [keyify] : accents et casse ignorés.
         */
        private fun stripArtistPrefix(name: String, artistKey: String): String? {
            if (artistKey.isEmpty()) return null
            for (m in SEP_ANY.findAll(name)) {
                val left = name.substring(0, m.range.first)
                if (left.isBlank()) continue
                val right = name.substring(m.range.last + 1).trim()
                if (right.isEmpty()) break
                if (keyOrRaw(left) == artistKey) return right
            }
            return null
        }

        /** "Booba feat. Kaaris", "Angèle (ft. Damso)", "Renaud avec Axelle Red" -> artiste principal. */
        private fun cutAtFeatMarker(name: String): String {
            val m = FEAT_MARKER.find(name) ?: return name
            val head = name.substring(0, m.range.first).trimEnd(' ', '(', '[', ',', '-', '–', '—', '&', '+', '/')
            return if (head.isBlank()) name else head
        }
    }
}
