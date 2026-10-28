package fr.poptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouTest {
    private fun item(id: String, cat: String, year: String = "2020", rating: String = "7") =
        VodItem(id = id, name = "Film $id", poster = "p$id", categoryId = cat, year = year, rating = rating)

    @Test
    fun `pas assez de titres de depart donne une liste vide`() {
        val catalog = listOf(item("1", "a"), item("2", "a"))
        assertTrue(ForYou.compute(catalog, listOf(item("1", "a")), emptySet()).isEmpty())
    }

    @Test
    fun `propose la categorie la plus presente et exclut les titres de depart`() {
        val seeds = listOf(item("1", "action"), item("2", "action"), item("3", "drame"))
        val catalog = seeds + listOf(item("4", "action"), item("5", "drame"), item("6", "comedie"))
        val result = ForYou.compute(catalog, seeds, emptySet())
        assertEquals(listOf("4", "5"), result.map { it.id })
    }

    @Test
    fun `exclut les titres deja vus`() {
        val seeds = listOf(item("1", "a"), item("2", "a"), item("3", "a"))
        val catalog = seeds + listOf(item("4", "a"), item("5", "a"))
        assertEquals(listOf("5"), ForYou.compute(catalog, seeds, setOf("4")).map { it.id })
    }
}
