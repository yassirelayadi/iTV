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

    @Test
    fun `le comportement pondere les titres de depart`() {
        val fav = ForYou.seedWeight(favorite = true, watched = false, progress = null, ageDays = null)
        val finished = ForYou.seedWeight(favorite = false, watched = true, progress = null, ageDays = null)
        val abandoned = ForYou.seedWeight(favorite = false, watched = false, progress = 0.03f, ageDays = 1f)
        assertTrue(fav > finished)
        assertTrue(finished > 0f)
        assertTrue(abandoned < 0f)
        val recent = ForYou.seedWeight(true, false, null, 1f)
        val old = ForYou.seedWeight(true, false, null, 200f)
        assertTrue(recent > old)
    }

    @Test
    fun `un titre recommande par deux departs passe devant`() {
        fun t(name: String) = TmdbTitle(name, name, "2020", null, null, 7.0)
        val ranked = ForYou.rank(listOf(
            3f to listOf(t("A"), t("B")),
            2f to listOf(t("B"), t("C")),
            -2f to listOf(t("Z")),
        ))
        assertEquals("B", ranked.first().title)
        assertTrue(ranked.none { it.title == "Z" })
    }
}
