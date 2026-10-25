package fr.poptv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private fun parse(text: String) = M3uParser.parse(text.lines().asSequence())

    @Test
    fun parsesChannelsWithAttributes() {
        val p = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="tf1.fr" tvg-logo="http://x/l.png" group-title="TNT",TF1 HD
            http://srv/live/u/p/1.m3u8
            #EXTINF:-1 group-title="Sport, Foot",Canal, Plus
            http://srv/live/u/p/2.m3u8
            """.trimIndent(),
        )
        assertEquals(2, p.channels.size)
        assertEquals("TF1 HD", p.channels[0].name)
        assertEquals("tf1.fr", p.channels[0].epgId)
        assertEquals("Sport, Foot", p.channels[1].categoryId) // virgule dans un attribut entre guillemets
        assertEquals("Canal, Plus", p.channels[1].name)
    }

    @Test
    fun duplicateUrlsGetDistinctIds() {
        val p = parse("#EXTINF:-1,A\nhttp://x/1\n#EXTINF:-1,B\nhttp://x/1\n")
        assertEquals(2, p.channels.map { it.id }.toSet().size)
    }
}
