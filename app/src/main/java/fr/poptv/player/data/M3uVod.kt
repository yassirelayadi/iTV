package fr.poptv.player.data

import java.text.Normalizer

/** Résultat du tri d'une playlist M3U : chaînes en direct, films et séries. */
class M3uLibrary(
    val live: Playlist,
    val movies: VodCatalog,
    val series: VodCatalog,
    /** Épisodes de chaque série, par identifiant de série. */
    val episodes: Map<String, List<Episode>>,
    /** Adresse de lecture de chaque film / épisode, par identifiant. */
    val urls: Map<String, String>,
)

/**
 * Sépare une playlist M3U en chaînes, films et séries, d'après l'adresse (/movie/, /series/),
 * le nom du groupe (« Films », « Séries »…), l'extension du fichier et le motif S01E02 du titre.
 */
object M3uClassifier {
    private enum class Kind { LIVE, MOVIE, SERIES }

    private val episodeRegexes = listOf(
        Regex("""^(.*?)[\s._\-]*[Ss](\d{1,2})\s*[EeÉé](?:p(?:isode)?\s*)?(\d{1,3})\b(.*)$"""),
        Regex("""^(.*?)[\s._\-]*(\d{1,2})x(\d{1,3})\b(.*)$"""),
    )
    private val videoExt = setOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "flv")
    private val yearRegex = Regex("""[\s(\[]((?:19|20)\d{2})[)\]]?\s*$""")

    private fun plain(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun extOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/', "").substringAfterLast('.', "").lowercase()

    private fun kindOf(c: Channel): Kind {
        val url = c.streamUrl.lowercase()
        if ("/series/" in url) return Kind.SERIES
        if ("/movie/" in url) return Kind.MOVIE
        val isEpisode = episodeRegexes[0].matches(c.name)
        // Un fichier vidéo (mkv, mp4…) est un film ou un épisode ; les flux sans extension restent des chaînes,
        // même dans un groupe nommé « Films », pour ne pas ranger de vraies chaînes parmi les films.
        if (extOf(url) in videoExt) {
            val group = plain(c.categoryId)
            val seriesGroup = "serie" in group || "saison" in group || "season" in group || "tv show" in group
            return if (isEpisode || seriesGroup) Kind.SERIES else Kind.MOVIE
        }
        if (isEpisode) return Kind.SERIES
        return Kind.LIVE
    }

    private fun categories(groups: Collection<String>) = groups.distinct().map { Category(id = it, name = it) }

    fun split(playlist: Playlist): M3uLibrary {
        val live = ArrayList<Channel>()
        val movies = ArrayList<VodItem>()
        val urls = HashMap<String, String>()
        // Séries : titre -> (série, épisodes)
        val seriesByTitle = LinkedHashMap<String, Pair<VodItem, MutableList<Episode>>>()
        val episodes = HashMap<String, List<Episode>>()

        for (c in playlist.channels) {
            when (kindOf(c)) {
                Kind.LIVE -> live.add(c)
                Kind.MOVIE -> {
                    val year = yearRegex.find(c.name)?.groupValues?.get(1)
                    val name = c.name.replace(yearRegex, "").trim().ifEmpty { c.name }
                    movies.add(
                        VodItem(
                            id = c.id,
                            name = name,
                            poster = c.logo,
                            categoryId = c.categoryId,
                            ext = extOf(c.streamUrl).ifEmpty { "mp4" },
                            year = year,
                            url = c.streamUrl,
                        )
                    )
                    urls[c.id] = c.streamUrl
                }
                Kind.SERIES -> {
                    var title = c.name
                    var season = 1
                    var number = 0
                    for (rx in episodeRegexes) {
                        val m = rx.matchEntire(c.name) ?: continue
                        title = m.groupValues[1].trim().trim('-', '.', '_', ' ').ifEmpty { c.categoryId }
                        season = m.groupValues[2].toInt()
                        number = m.groupValues[3].toInt()
                        break
                    }
                    val entry = seriesByTitle.getOrPut(title.lowercase()) {
                        val id = "s" + title.lowercase().hashCode().toString()
                        VodItem(id = id, name = title, poster = c.logo, categoryId = c.categoryId, ext = "mp4") to
                            ArrayList<Episode>()
                    }
                    val list = entry.second
                    if (number == 0) number = list.count { it.season == season } + 1
                    list.add(
                        Episode(
                            id = c.id,
                            title = c.name,
                            season = season,
                            number = number,
                            ext = extOf(c.streamUrl).ifEmpty { "mp4" },
                            image = c.logo,
                            url = c.streamUrl,
                        )
                    )
                    urls[c.id] = c.streamUrl
                }
            }
        }

        val seriesItems = seriesByTitle.values.map { (item, eps) ->
            episodes[item.id] = eps.sortedWith(compareBy({ it.season }, { it.number }))
            item
        }

        return M3uLibrary(
            live = Playlist(categories(live.map { it.categoryId }), live),
            movies = VodCatalog(categories(movies.map { it.categoryId }), movies),
            series = VodCatalog(categories(seriesItems.map { it.categoryId }), seriesItems),
            episodes = episodes,
            urls = urls,
        )
    }

    /** Regroupe les épisodes d'une série par saison. */
    fun seasons(episodes: List<Episode>): List<Season> =
        episodes.groupBy { it.season }.toSortedMap().map { (n, eps) -> Season(n, eps) }
}
