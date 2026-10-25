package fr.poptv.player.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Un titre renvoyé par TMDB (tendances, à l'affiche). */
data class TmdbTitle(
    val title: String,
    val originalTitle: String,
    val year: String?,
    val backdrop: String?,
    val overview: String?,
    val rating: Double?,
)

class TmdbTrends(
    val moviesWeek: List<TmdbTitle>,
    val moviesNow: List<TmdbTitle>,
    val seriesWeek: List<TmdbTitle>,
    val fetchedAt: Long,
)

/** Un titre de TMDB retrouvé dans le catalogue du fournisseur. */
class TmdbPick(val item: VodItem, val backdrop: String?, val overview: String?)

class TmdbPicks(
    val topMovies: List<TmdbPick> = emptyList(),
    val topSeries: List<TmdbPick> = emptyList(),
    /** Films « à l'affiche » avec une grande image, pour le bandeau. */
    val featured: List<TmdbPick> = emptyList(),
)

/**
 * Client TMDB (https uniquement). La clé est envoyée dans l'en-tête Authorization (jeton « eyJ… »)
 * ou en paramètre api_key (clé courte) ; elle n'est jamais écrite dans les journaux ni dans les messages d'erreur.
 */
object TmdbClient {
    private const val BASE = "https://api.themoviedb.org/3/"
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w1280"
    private val json = Json { ignoreUnknownKeys = true }

    private fun get(key: String, path: String, params: Map<String, String>): JsonArray {
        val url = (BASE + path).toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .apply { if (!key.startsWith("eyJ")) addQueryParameter("api_key", key) }
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply { if (key.startsWith("eyJ")) header("Authorization", "Bearer $key") }
            .build()
        return Http.client.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> error("Clé TMDB refusée. Vérifie qu'elle est correcte.")
                !response.isSuccessful -> error("TMDB a répondu HTTP ${response.code}")
            }
            val body = response.body?.string() ?: error("Réponse vide de TMDB")
            val root = json.parseToJsonElement(body) as? JsonObject ?: error("Réponse TMDB illisible")
            root["results"] as? JsonArray ?: JsonArray(emptyList())
        }
    }

    private fun titles(array: JsonArray, tv: Boolean): List<TmdbTitle> = array.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val title = o.text(if (tv) "name" else "title") ?: return@mapNotNull null
        TmdbTitle(
            title = title,
            originalTitle = o.text(if (tv) "original_name" else "original_title") ?: title,
            year = o.text(if (tv) "first_air_date" else "release_date")?.take(4),
            backdrop = o.text("backdrop_path"),
            overview = o.text("overview"),
            rating = o.str("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 },
        )
    }

    /** Plusieurs pages d'une même liste : TMDB n'en donne que 20 par page, et le catalogue n'en contient qu'une partie. */
    private fun pages(key: String, path: String, params: Map<String, String>, tv: Boolean, from: Int, to: Int): List<TmdbTitle> =
        (from..to).flatMap { page ->
            runCatching { titles(get(key, path, params + ("page" to page.toString())), tv) }.getOrDefault(emptyList())
        }

    fun trends(key: String): TmdbTrends {
        val lang = mapOf("language" to "fr-FR")
        // La première requête valide la clé : si elle échoue, l'erreur remonte telle quelle.
        val firstMovies = titles(get(key, "trending/movie/week", lang), tv = false)
        val firstSeries = titles(get(key, "trending/tv/week", lang), tv = true)
        val moviesWeek = (firstMovies + pages(key, "trending/movie/week", lang, false, 2, 3) +
            pages(key, "movie/popular", lang, false, 1, 2)).distinctBy { it.title + it.year }
        val seriesWeek = (firstSeries + pages(key, "trending/tv/week", lang, true, 2, 3) +
            pages(key, "tv/popular", lang, true, 1, 3)).distinctBy { it.title + it.year }
        return TmdbTrends(
            moviesWeek = moviesWeek,
            moviesNow = titles(get(key, "movie/now_playing", lang + ("region" to "FR")), tv = false),
            seriesWeek = seriesWeek,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

/** Rapproche les titres TMDB des films et séries du catalogue (par titre normalisé et année). */
object TmdbMatcher {
    private val tagRegex = Regex("""\[[^\]]*\]|\{[^\}]*\}""")
    private val yearRegex = Regex("""\(?\b((?:19|20)\d{2})\b\)?""")
    private val prefixRegex = Regex("""^\s*[A-Za-z]{2,3}\s*[|:\-]\s+""")
    private val qualityRegex = Regex("""\b(multi|vf|vff|vo|vost|vostfr|truefrench|french|4k|uhd|fhd|hd|sd|hdr|bluray|webrip|web-dl|x264|x265|hevc)\b""")

    fun norm(raw: String): String {
        var s = raw.replace(tagRegex, " ")
        s = prefixRegex.replace(s, "")
        s = yearRegex.replace(s, " ")
        s = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        s = qualityRegex.replace(s, " ")
        s = s.replace(Regex("[^a-z0-9]+"), " ").trim()
        return s
    }

    private fun yearOf(raw: String, fallback: String?): String? =
        fallback?.takeIf { it.length == 4 } ?: yearRegex.find(raw)?.groupValues?.get(1)

    private fun index(items: List<VodItem>): Map<String, List<VodItem>> {
        val map = HashMap<String, MutableList<VodItem>>(items.size * 2)
        for (item in items) {
            val k = norm(item.name)
            if (k.isNotEmpty()) map.getOrPut(k) { ArrayList(1) }.add(item)
        }
        return map
    }

    private fun find(index: Map<String, List<VodItem>>, t: TmdbTitle): VodItem? {
        val candidates = listOf(t.title, t.originalTitle).map { norm(it) }.filter { it.isNotEmpty() }.distinct()
        for (c in candidates) {
            val found = index[c] ?: continue
            val withYear = t.year?.toIntOrNull()?.let { y ->
                found.firstOrNull { f -> yearOf(f.name, f.year)?.toIntOrNull()?.let { kotlin.math.abs(it - y) <= 1 } == true }
            }
            return withYear ?: found.firstOrNull { it.poster != null } ?: found.first()
        }
        return null
    }

    private fun picks(list: List<TmdbTitle>, index: Map<String, List<VodItem>>, limit: Int): List<TmdbPick> {
        val used = HashSet<String>()
        val out = ArrayList<TmdbPick>()
        for (t in list) {
            val item = find(index, t) ?: continue
            if (!used.add(item.id)) continue
            out.add(TmdbPick(item, t.backdrop?.let { TmdbClient.IMAGE_BASE + it }, t.overview))
            if (out.size >= limit) break
        }
        return out
    }

    fun match(trends: TmdbTrends, movies: List<VodItem>, series: List<VodItem>): TmdbPicks {
        val movieIndex = index(movies)
        val seriesIndex = index(series)
        val featured = picks(trends.moviesNow + trends.moviesWeek, movieIndex, 12)
            .filter { it.backdrop != null }
            .take(5)
        return TmdbPicks(
            topMovies = picks(trends.moviesWeek, movieIndex, 10),
            topSeries = picks(trends.seriesWeek, seriesIndex, 10),
            featured = featured,
        )
    }
}
