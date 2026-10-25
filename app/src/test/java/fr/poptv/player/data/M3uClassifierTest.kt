package fr.poptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uClassifierTest {
    private fun ch(name: String, url: String, group: String = "Divers") =
        Channel(id = url.hashCode().toString(), name = name, streamUrl = url, categoryId = group)

    @Test
    fun splitsLiveMoviesAndSeries() {
        val playlist = Playlist(
            categories = emptyList(),
            channels = listOf(
                ch("TF1", "http://s/live/u/p/1.ts"),
                ch("Inception (2010)", "http://s/movie/u/p/2.mkv", "Films"),
                ch("Dark S01E02", "http://s/series/u/p/3.mkv", "Séries"),
                ch("Dark S01E03", "http://s/series/u/p/4.mkv", "Séries"),
            ),
        )
        val lib = M3uClassifier.split(playlist)
        assertEquals(1, lib.live.channels.size)
        assertEquals(1, lib.movies.items.size)
        assertEquals("Inception", lib.movies.items[0].name)
        assertEquals("2010", lib.movies.items[0].year)
        assertEquals(1, lib.series.items.size) // les deux épisodes forment une seule série
        assertEquals(2, lib.episodes.values.first().size)
    }

    @Test
    fun streamsWithoutExtensionStayLiveEvenInFilmsGroup() {
        val lib = M3uClassifier.split(Playlist(emptyList(), listOf(ch("Cinéma+", "http://s/123", "Films"))))
        assertEquals(1, lib.live.channels.size)
        assertTrue(lib.movies.items.isEmpty())
    }
}
