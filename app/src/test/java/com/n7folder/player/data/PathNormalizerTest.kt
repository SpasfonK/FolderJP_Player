package com.n7folder.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM purs (aucun émulateur) : ./gradlew testDebugUnitTest
 * Chaque cas correspond à un défaut réel de l'application web Musico corrigé dans ParsePath v6+.
 */
class PathNormalizerTest {

    private val normalizer = PathNormalizer()

    private fun assertFolder(
        segments: List<String>,
        artist: String,
        album: String,
        year: Int? = null,
        disc: Int? = null,
        parser: PathNormalizer = normalizer
    ) {
        val parsed = parser.parseFolder(segments)
        assertEquals("artiste de $segments", artist, parsed.artist)
        assertEquals("album de $segments", album, parsed.album)
        assertEquals("année de $segments", year, parsed.year)
        assertEquals("disque de $segments", disc, parsed.disc)
    }

    private fun assertTrack(
        fileName: String,
        title: String,
        track: Int? = null,
        disc: Int? = null,
        artistKey: String = "",
        numberedBySpace: Boolean = false
    ) {
        val parsed = normalizer.parseTrackName(fileName, artistKey, numberedBySpace)
        assertEquals("titre de $fileName", title, parsed.title)
        assertEquals("piste de $fileName", track, parsed.track)
        assertEquals("disque de $fileName", disc, parsed.disc)
    }

    // --- keyify : Angèle = Angele = ANGELE -------------------------------------------------------

    @Test
    fun keyify_removesAccentsAndCase() {
        assertEquals("angele", PathNormalizer.keyify("Angèle"))
        assertEquals("angele", PathNormalizer.keyify("ANGELE"))
        assertEquals("angele", PathNormalizer.keyify("angele"))
        assertEquals("bjork", PathNormalizer.keyify("Björk"))
        assertEquals("motleycrue", PathNormalizer.keyify("Mötley Crüe"))
        assertEquals("oeuvres", PathNormalizer.keyify("Œuvres"))
    }

    @Test
    fun keyify_treatsAmpersandAsAnd() {
        assertEquals(PathNormalizer.keyify("Simon and Garfunkel"), PathNormalizer.keyify("Simon & Garfunkel"))
    }

    @Test
    fun keyOrRaw_keepsSymbolOnlyNames() {
        assertEquals("!!!", PathNormalizer.keyOrRaw("!!!"))
    }

    @Test
    fun accentedAndPlainFoldersShareTheSameArtistKey() {
        val a = normalizer.parseFolder(listOf("Angèle", "Brol"))
        val b = normalizer.parseFolder(listOf("ANGELE", "Brol"))
        assertEquals(a.artistKey, b.artistKey)
        assertEquals(a.albumId, b.albumId)
    }

    // --- "Artiste - Album" (SAEZ - Debbie) -------------------------------------------------------

    @Test
    fun artistDashAlbumFolderIsSplit() {
        assertFolder(listOf("Musique", "SAEZ - Debbie"), artist = "SAEZ", album = "Debbie")
        assertFolder(listOf("SAEZ_-_Debbie"), artist = "SAEZ", album = "Debbie")
    }

    @Test
    fun redundantArtistPrefixInAlbumFolderIsRemoved() {
        assertFolder(listOf("Musique", "Saez", "SAEZ - Debbie"), artist = "Saez", album = "Debbie")
        assertFolder(listOf("Saez", "Saez - Debbie"), artist = "Saez", album = "Debbie")
    }

    @Test
    fun splitAndNestedLayoutsGiveTheSameAlbumId() {
        val flat = normalizer.parseFolder(listOf("SAEZ - Debbie"))
        val nested = normalizer.parseFolder(listOf("Saez", "Debbie"))
        assertEquals(nested.albumId, flat.albumId)
    }

    // --- Années (1970 - Black Sabbath) -----------------------------------------------------------

    @Test
    fun leadingYearFolderNeverBecomesAnArtist() {
        assertFolder(
            listOf("Black Sabbath", "1970 - Black Sabbath"),
            artist = "Black Sabbath", album = "Black Sabbath", year = 1970
        )
        assertFolder(
            listOf("1970 - Black Sabbath"),
            artist = PathNormalizer.UNKNOWN_ARTIST, album = "Black Sabbath", year = 1970
        )
        assertFolder(
            listOf("Musique", "1970 - Black Sabbath"),
            artist = PathNormalizer.UNKNOWN_ARTIST, album = "Black Sabbath", year = 1970
        )
    }

    @Test
    fun yearIsExtractedFromAlbumNames() {
        assertFolder(listOf("Black Sabbath", "Paranoid (1970)"), artist = "Black Sabbath", album = "Paranoid", year = 1970)
        assertFolder(listOf("AC-DC", "Back in Black - 1980"), artist = "AC-DC", album = "Back in Black", year = 1980)
        assertFolder(
            listOf("Jean-Jacques Goldman", "1984 - Positif"),
            artist = "Jean-Jacques Goldman", album = "Positif", year = 1984
        )
    }

    @Test
    fun yearOnlyFolderBetweenArtistAndAlbumIsNotAnAlbum() {
        assertFolder(listOf("Radiohead", "2004", "Hail to the Thief"), artist = "Radiohead", album = "Hail to the Thief", year = 2004)
    }

    @Test
    fun yearThatIsTheTitleIsKept() {
        assertFolder(listOf("Artist", "1989"), artist = "Artist", album = "1989")
        assertFolder(listOf("Taylor Swift", "1989 (Deluxe)"), artist = "Taylor Swift", album = "1989 (Deluxe)")
    }

    @Test
    fun yearArtistAlbumInOneFolder() {
        assertFolder(listOf("1997 - Radiohead - OK Computer"), artist = "Radiohead", album = "OK Computer", year = 1997)
    }

    // --- CD1 / Disc 2 ----------------------------------------------------------------------------

    @Test
    fun discFoldersAreAttachedToTheParentAlbum() {
        assertFolder(listOf("Pink Floyd", "The Wall", "CD1"), artist = "Pink Floyd", album = "The Wall", disc = 1)
        assertFolder(listOf("Pink Floyd", "The Wall", "CD2"), artist = "Pink Floyd", album = "The Wall", disc = 2)
        assertFolder(listOf("Pink Floyd", "The Wall", "Disque 1"), artist = "Pink Floyd", album = "The Wall", disc = 1)
        assertFolder(listOf("Pink Floyd", "The Wall", "CD 1 - Another Brick"), artist = "Pink Floyd", album = "The Wall", disc = 1)
        assertFolder(listOf("Pink Floyd", "The Wall", "1"), artist = "Pink Floyd", album = "The Wall", disc = 1)
        assertFolder(listOf("Various Artists", "Now 50", "CD1"), artist = "Various Artists", album = "Now 50", disc = 1)
    }

    @Test
    fun discMarkerInsideAlbumNameIsRemoved() {
        assertFolder(listOf("Pink Floyd", "The Wall (CD2)"), artist = "Pink Floyd", album = "The Wall", disc = 2)
        assertFolder(
            listOf("Pink Floyd", "1979 - The Wall", "Disc 2"),
            artist = "Pink Floyd", album = "The Wall", year = 1979, disc = 2
        )
    }

    // --- feat / avec / & -------------------------------------------------------------------------

    @Test
    fun featuredGuestsAreCutFromTheArtist() {
        assertFolder(listOf("Booba feat. Kaaris", "Futur"), artist = "Booba", album = "Futur")
        assertFolder(listOf("Angèle (ft. Damso)", "Brol"), artist = "Angèle", album = "Brol")
        assertFolder(listOf("Renaud avec Axelle Red", "Best of"), artist = "Renaud", album = "Best of")
    }

    @Test
    fun ampersandDuosAreKeptUnlessTheFirstArtistExists() {
        assertFolder(listOf("Simon & Garfunkel", "Bridge Over Troubled Water"), artist = "Simon & Garfunkel", album = "Bridge Over Troubled Water")
        assertFolder(listOf("Hall & Oates", "Private Eyes"), artist = "Hall & Oates", album = "Private Eyes")

        val withKnownArtists = PathNormalizer(setOf("bigflo", "booba"))
        assertFolder(listOf("Bigflo & Oli", "La vraie vie"), artist = "Bigflo", album = "La vraie vie", parser = withKnownArtists)
        assertFolder(listOf("Booba x Kaaris", "Double Poney"), artist = "Booba", album = "Double Poney", parser = withKnownArtists)
        assertFolder(listOf("Bigflo & Oli", "La vraie vie"), artist = "Bigflo & Oli", album = "La vraie vie")
    }

    // --- Dossiers génériques et de format --------------------------------------------------------

    @Test
    fun genericLeadingFoldersAreSkipped() {
        assertFolder(listOf("1", "Booba feat. Kaaris"), artist = "Booba", album = PathNormalizer.LOOSE_ALBUM)
        assertFolder(listOf("Musique", "1", "Saez", "Debbie"), artist = "Saez", album = "Debbie")
        assertFolder(listOf("Musique", "FLAC", "Radiohead", "OK Computer"), artist = "Radiohead", album = "OK Computer")
        assertFolder(listOf("Music", "Daft Punk", "Discovery"), artist = "Daft Punk", album = "Discovery")
    }

    @Test
    fun formatFoldersAndQualityTagsAreRemoved() {
        assertFolder(listOf("FLAC", "Radiohead", "OK Computer [FLAC]"), artist = "Radiohead", album = "OK Computer")
        assertFolder(listOf("Radiohead", "OK Computer", "FLAC"), artist = "Radiohead", album = "OK Computer")
        assertFolder(listOf("Black Sabbath", "1970 - Paranoid [FLAC]"), artist = "Black Sabbath", album = "Paranoid", year = 1970)
    }

    @Test
    fun emptyOrOnlyGenericPathsGiveUnknownArtist() {
        assertFolder(emptyList(), artist = PathNormalizer.UNKNOWN_ARTIST, album = PathNormalizer.LOOSE_ALBUM)
        assertFolder(listOf("Musique"), artist = PathNormalizer.UNKNOWN_ARTIST, album = PathNormalizer.LOOSE_ALBUM)
    }

    @Test
    fun looseTracksInAnArtistFolder() {
        assertFolder(listOf("Daft Punk"), artist = "Daft Punk", album = PathNormalizer.LOOSE_ALBUM)
    }

    @Test
    fun namesWithSymbolsSurvive() {
        assertFolder(listOf("!!!", "Louden Up Now"), artist = "!!!", album = "Louden Up Now")
        assertFolder(listOf("Blink-182", "Enema of the State"), artist = "Blink-182", album = "Enema of the State")
    }

    // --- Noms de fichiers ------------------------------------------------------------------------

    @Test
    fun trackNumbersWithPunctuation() {
        assertTrack("01 - Titre.mp3", title = "Titre", track = 1)
        assertTrack("01. Titre.flac", title = "Titre", track = 1)
        assertTrack("07-Hallelujah.mp3", title = "Hallelujah", track = 7)
    }

    @Test
    fun discAndTrackPrefix() {
        assertTrack("1-05 Titre.mp3", title = "Titre", track = 5, disc = 1)
        assertTrack("2-11 - Titre.flac", title = "Titre", track = 11, disc = 2)
    }

    @Test
    fun spaceSeparatedNumbersOnlyWhenTheWholeFolderIsNumbered() {
        assertTrack("01 Titre.mp3", title = "01 Titre", numberedBySpace = false)
        assertTrack("01 Titre.mp3", title = "Titre", track = 1, numberedBySpace = true)
        assertTrack("99 Luftballons.mp3", title = "99 Luftballons")
        assertTrack("10 Years Gone.mp3", title = "10 Years Gone")
    }

    @Test
    fun numbersThatAreTheTitleAreKept() {
        assertTrack("1999.mp3", title = "1999")
        assertTrack("01.mp3", title = "01")
    }

    @Test
    fun redundantArtistPrefixIsRemovedFromTitles() {
        assertTrack("SAEZ - Debbie.mp3", title = "Debbie", artistKey = "saez")
        assertTrack("Saez_-_Titre.mp3", title = "Titre", artistKey = "saez")
        assertTrack("03 - Saez - Titre.mp3", title = "Titre", track = 3, artistKey = "saez")
        assertTrack("Bohemian Rhapsody - Queen.mp3", title = "Bohemian Rhapsody - Queen", artistKey = "queen")
    }

    @Test
    fun plainTitlesAreUntouched() {
        assertTrack("Titre sans numéro.opus", title = "Titre sans numéro")
        assertTrack("Mon.Titre.Avec.Points.mp3", title = "Mon.Titre.Avec.Points")
        assertTrack("12_Titre_Underscore.mp3", title = "12 Titre Underscore")
    }

    @Test
    fun looksSpaceNumbered_requiresDistinctNumbersOnEveryFile() {
        assertTrue(PathNormalizer.looksSpaceNumbered(listOf("01 A.mp3", "02 B.mp3", "03 C.mp3")))
        assertFalse(PathNormalizer.looksSpaceNumbered(listOf("01 A.mp3", "B.mp3")))
        assertFalse(PathNormalizer.looksSpaceNumbered(listOf("01 A.mp3", "01 B.mp3")))
        assertFalse(PathNormalizer.looksSpaceNumbered(listOf("99 Luftballons.mp3")))
    }

    // --- Tri et affichage ------------------------------------------------------------------------

    @Test
    fun naturalCompare_sortsNumbersByValue() {
        assertTrue(PathNormalizer.naturalCompare("Piste 2", "Piste 10") < 0)
        assertTrue(PathNormalizer.naturalCompare("a10", "a9") > 0)
        assertEquals(0, PathNormalizer.naturalCompare("ABC", "abc"))
    }

    @Test
    fun displayScore_prefersAccentedAndMixedCaseVariants() {
        assertTrue(PathNormalizer.displayScore("Angèle") > PathNormalizer.displayScore("Angele"))
        assertTrue(PathNormalizer.displayScore("Saez") > PathNormalizer.displayScore("SAEZ"))
    }

    @Test
    fun stripExtension_onlyRemovesRealExtensions() {
        assertEquals("a", PathNormalizer.stripExtension("a.mp3"))
        assertEquals("Mr. Brightside", PathNormalizer.stripExtension("Mr. Brightside"))
        assertEquals(".hidden", PathNormalizer.stripExtension(".hidden"))
    }
}
