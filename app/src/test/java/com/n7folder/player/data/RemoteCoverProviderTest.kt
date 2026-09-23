package com.n7folder.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests JVM purs (aucun réseau) : seules les fonctions de construction d'URL et d'extraction JSON
 * sont testées ici — le reste (RemoteCoverProvider.fetchCoverBytes) fait de vrais appels réseau et
 * n'est jamais exécuté en test unitaire.
 */
class RemoteCoverProviderTest {

    @Test
    fun theAudioDbUrl_encodesSpacesAccentsAndAmpersand() {
        assertEquals(
            "https://www.theaudiodb.com/api/v1/json/2/searchalbum.php?s=Assassin&a=Le+futur+que+nous+r%C3%A9serve+le+pass%C3%A9",
            RemoteCoverProvider.buildTheAudioDbUrl("Assassin", "Le futur que nous réserve le passé")
        )
        assertEquals(
            "https://www.theaudiodb.com/api/v1/json/2/searchalbum.php?s=Simon+%26+Garfunkel&a=Bridge+Over+Troubled+Water",
            RemoteCoverProvider.buildTheAudioDbUrl("Simon & Garfunkel", "Bridge Over Troubled Water")
        )
    }

    @Test
    fun lastFmUrl_includesKeyArtistAndAlbum() {
        assertEquals(
            "https://ws.audioscrobbler.com/2.0/?method=album.getinfo&api_key=KEY123&artist=Saez&album=Debbie&format=json",
            RemoteCoverProvider.buildLastFmUrl("KEY123", "Saez", "Debbie")
        )
    }

    @Test
    fun audioDb_prefersHighQualityThumb() {
        val json = """
            {"album":[{"idAlbum":"112267","strAlbum":"Paranoid",
            "strAlbumThumb":"https:\/\/r2.theaudiodb.com\/images\/media\/album\/thumb\/paranoid.jpg",
            "strAlbumThumbHQ":"https:\/\/r2.theaudiodb.com\/images\/media\/album\/thumbhq\/paranoid_hq.jpg"}]}
        """.trimIndent()
        assertEquals(
            "https://r2.theaudiodb.com/images/media/album/thumbhq/paranoid_hq.jpg",
            RemoteCoverProvider.extractAlbumThumbUrl(json)
        )
    }

    @Test
    fun audioDb_fallsBackToNormalThumbWhenHqIsEmpty() {
        val json = """{"album":[{"strAlbum":"Debbie","strAlbumThumb":"https://example.com/debbie.jpg","strAlbumThumbHQ":""}]}"""
        assertEquals("https://example.com/debbie.jpg", RemoteCoverProvider.extractAlbumThumbUrl(json))
    }

    @Test
    fun audioDb_noResultGivesNull() {
        assertNull(RemoteCoverProvider.extractAlbumThumbUrl("""{"album":null}"""))
        assertNull(RemoteCoverProvider.extractAlbumThumbUrl("""{"album":[{"strAlbum":"Sans pochette"}]}"""))
        assertNull(RemoteCoverProvider.extractAlbumThumbUrl("""{"album":[{"strAlbumThumb":"","strAlbumThumbHQ":""}]}"""))
    }

    @Test
    fun lastFm_picksTheLargestNonEmptyImage() {
        val json = """
            {"album":{"image":[
                {"#text":"https://a.com/s.jpg","size":"small"},
                {"#text":"https://a.com/m.jpg","size":"medium"},
                {"#text":"https://a.com/xl.jpg","size":"extralarge"}
            ]}}
        """.trimIndent()
        assertEquals("https://a.com/xl.jpg", RemoteCoverProvider.extractLastFmImageUrl(json))
    }

    @Test
    fun lastFm_skipsAnEmptyLargestSize() {
        val json = """
            {"album":{"image":[
                {"#text":"https://a.com/s.jpg","size":"small"},
                {"#text":"","size":"extralarge"}
            ]}}
        """.trimIndent()
        assertEquals("https://a.com/s.jpg", RemoteCoverProvider.extractLastFmImageUrl(json))
    }

    @Test
    fun lastFm_errorOrEmptyGivesNull() {
        assertNull(RemoteCoverProvider.extractLastFmImageUrl("""{"error":6,"message":"Album not found"}"""))
        assertNull(RemoteCoverProvider.extractLastFmImageUrl("""{"album":{"image":[]}}"""))
    }
}
