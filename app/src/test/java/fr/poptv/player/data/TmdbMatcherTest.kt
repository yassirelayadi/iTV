package fr.poptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbMatcherTest {
    @Test
    fun normalizeRemovesNoise() {
        assertEquals("avatar", TmdbMatcher.norm("FR| Avatar (2009) [MULTI] {4K} HD"))
        assertEquals("amelie poulain", TmdbMatcher.norm("Amélie Poulain"))
        assertEquals("le roi lion", TmdbMatcher.norm("Le Roi Lion VOSTFR"))
    }

    private fun item(id: String, name: String, year: String? = null) = VodItem(id = id, name = name, year = year)

    private fun title(t: String, orig: String = t, year: String? = null) =
        TmdbTitle(t, orig, year, "/b.jpg", "résumé", 7.0)

    @Test
    fun matchesByTitleOriginalTitleAndYear() {
        val movies = listOf(item("1", "Dune (2021)"), item("2", "Dune", "1984"), item("3", "Inconnu"))
        val trends = TmdbTrends(
            moviesWeek = listOf(title("Dune", year = "2021"), title("The Batman", "The Batman")),
            moviesNow = emptyList(),
            seriesWeek = emptyList(),
            fetchedAt = 0,
        )
        val picks = TmdbMatcher.match(trends, movies, emptyList())
        assertEquals(1, picks.topMovies.size)
        assertEquals("1", picks.topMovies[0].item.id) // choisit l'année 2021, pas 1984
    }

    @Test
    fun noDuplicateItemsInTop() {
        val movies = listOf(item("1", "Dune"))
        val trends = TmdbTrends(
            moviesWeek = listOf(title("Dune"), title("Dune")),
            moviesNow = emptyList(), seriesWeek = emptyList(), fetchedAt = 0,
        )
        assertEquals(1, TmdbMatcher.match(trends, movies, emptyList()).topMovies.size)
    }

    @Test
    fun emptyCatalogGivesEmptyPicks() {
        val trends = TmdbTrends(listOf(title("Dune")), emptyList(), emptyList(), 0)
        assertTrue(TmdbMatcher.match(trends, emptyList(), emptyList()).topMovies.isEmpty())
    }
}
