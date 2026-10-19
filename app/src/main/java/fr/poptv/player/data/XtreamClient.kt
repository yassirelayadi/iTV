package fr.poptv.player.data

import android.net.Uri
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false) // pas de retour de https vers http
        .build()
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** Texte nettoyé : null si absent ou vide. */
internal fun JsonObject.text(key: String): String? =
    str(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

object XtreamClient {
    private val json = Json { ignoreUnknownKeys = true }

    fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    private fun api(creds: Credentials, action: String, params: Map<String, String> = emptyMap()): String {
        val base = normalizeServer(creds.server)
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", creds.username)
            .addQueryParameter("password", creds.password)
            .addQueryParameter("action", action)
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "iTV/0.1").build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Le serveur a répondu HTTP ${response.code}")
            response.body?.string() ?: error("Réponse vide du serveur")
        }
    }

    /**
     * Lit une grande liste JSON élément par élément, sans jamais la garder en entier en mémoire
     * (un catalogue de films peut peser plusieurs dizaines de Mo).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T : Any> streamArray(
        creds: Credentials,
        action: String,
        params: Map<String, String> = emptyMap(),
        transform: (JsonObject) -> T?,
    ): List<T> {
        val base = normalizeServer(creds.server)
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", creds.username)
            .addQueryParameter("password", creds.password)
            .addQueryParameter("action", action)
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "iTV/0.1").build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Le serveur a répondu HTTP ${response.code}")
            val body = response.body ?: error("Réponse vide du serveur")
            val out = ArrayList<T>()
            try {
                body.byteStream().buffered().use { stream ->
                    for (element in json.decodeToSequence(stream, JsonObject.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
                        transform(element)?.let { out.add(it) }
                    }
                }
            } catch (e: SerializationException) {
                error("Identifiants refusés, réponse incomplète ou serveur incompatible")
            }
            out
        }
    }

    private fun parse(body: String) = try {
        json.parseToJsonElement(body)
    } catch (e: Exception) {
        error("Réponse inattendue du serveur (pas du JSON)")
    }

    private fun parseArray(body: String): JsonArray =
        parse(body) as? JsonArray ?: error("Identifiants refusés ou serveur incompatible")

    private fun parseObject(body: String): JsonObject =
        parse(body) as? JsonObject ?: error("Réponse inattendue du serveur")

    private fun categories(creds: Credentials, action: String): List<Category> =
        parseArray(api(creds, action)).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(id = id, name = o.text("category_name") ?: id)
        }

    // ---- Chaînes en direct ----

    fun fetch(creds: Credentials): Playlist {
        val categories = categories(creds, "get_live_categories")

        val channels = streamArray(creds, "get_live_streams") { o ->
            val id = o.str("stream_id") ?: return@streamArray null
            Channel(
                id = id,
                name = o.str("name") ?: id,
                // Pas d'identifiants dans les données mises en cache : l'URL est complétée à la lecture.
                streamUrl = LIVE_PREFIX + id,
                logo = o.text("stream_icon"),
                categoryId = o.str("category_id") ?: "",
                epgId = o.text("epg_channel_id"),
            )
        }.distinctBy { it.id }

        if (channels.isEmpty()) error("Aucune chaîne trouvée pour ce compte")
        return Playlist(categories, channels)
    }

    // ---- Films ----

    fun movieCategories(creds: Credentials): List<Category> = categories(creds, "get_vod_categories")

    private fun categoryParams(categoryId: String?): Map<String, String> =
        if (categoryId == null) emptyMap() else mapOf("category_id" to categoryId)

    /**
     * Films d'une seule catégorie (categoryId) ou de tout le catalogue (null). Les catégories de
     * `skip` sont ignorées dès la lecture : elles ne sont jamais gardées en mémoire.
     */
    fun fetchMovies(creds: Credentials, categoryId: String? = null, skip: Set<String> = emptySet()): List<VodItem> =
        streamArray(creds, "get_vod_streams", categoryParams(categoryId)) { o ->
            val category = o.str("category_id") ?: categoryId ?: ""
            if (category in skip) return@streamArray null
            val id = o.str("stream_id") ?: return@streamArray null
            VodItem(
                id = id,
                name = o.text("name") ?: id,
                poster = o.text("stream_icon"),
                categoryId = category,
                ext = o.text("container_extension") ?: "mp4",
                rating = o.text("rating"),
                added = o.str("added")?.toLongOrNull() ?: 0L,
            )
        }

    fun vodInfo(creds: Credentials, id: String): DetailInfo {
        val root = parseObject(api(creds, "get_vod_info", mapOf("vod_id" to id)))
        val info = root["info"] as? JsonObject ?: return DetailInfo()
        return DetailInfo(
            plot = info.text("plot") ?: info.text("description"),
            year = (info.text("releasedate") ?: info.text("release_date") ?: info.text("releaseDate"))?.take(4),
            genre = info.text("genre"),
            duration = info.text("duration"),
            rating = info.text("rating"),
        )
    }

    fun movieUrl(creds: Credentials, item: VodItem): String =
        mediaUrl(creds, "movie", item.id, item.ext)

    /** URL de lecture d'un film (kind = "movie") ou d'un épisode (autre valeur). Jamais mise en cache. */
    fun mediaUrl(creds: Credentials, kind: String, id: String, ext: String): String {
        val folder = if (kind == "movie") "movie" else "series"
        return "${normalizeServer(creds.server)}/$folder/${Uri.encode(creds.username)}/${Uri.encode(creds.password)}/$id.$ext"
    }

    /** Les chaînes Xtream sont mémorisées sous la forme « xtream-live:ID », sans identifiants. */
    const val LIVE_PREFIX = "xtream-live:"

    fun liveUrl(creds: Credentials, id: String): String =
        "${normalizeServer(creds.server)}/live/${Uri.encode(creds.username)}/${Uri.encode(creds.password)}/$id.m3u8"

    // ---- Séries ----

    fun seriesCategories(creds: Credentials): List<Category> = categories(creds, "get_series_categories")

    fun fetchSeries(creds: Credentials, categoryId: String? = null, skip: Set<String> = emptySet()): List<VodItem> =
        streamArray(creds, "get_series", categoryParams(categoryId)) { o ->
            val category = o.str("category_id") ?: categoryId ?: ""
            if (category in skip) return@streamArray null
            val id = o.str("series_id") ?: return@streamArray null
            VodItem(
                id = id,
                name = o.text("name") ?: id,
                poster = o.text("cover"),
                categoryId = category,
                year = (o.text("year") ?: o.text("releaseDate") ?: o.text("release_date"))?.take(4),
                rating = o.text("rating"),
                plot = o.text("plot"),
                added = o.str("last_modified")?.toLongOrNull() ?: 0L,
            )
        }

    fun seriesInfo(creds: Credentials, id: String): Pair<DetailInfo, List<Season>> {
        val root = parseObject(api(creds, "get_series_info", mapOf("series_id" to id)))
        val info = root["info"] as? JsonObject
        val detailInfo = DetailInfo(
            plot = info?.text("plot"),
            year = (info?.text("releaseDate") ?: info?.text("releasedate"))?.take(4),
            genre = info?.text("genre"),
            rating = info?.text("rating"),
        )

        // « episodes » est normalement un objet {"1": [...], "2": [...]}, parfois une liste de listes.
        val episodes = root["episodes"]
        val groups: List<Pair<Int, JsonArray>> = when (episodes) {
            is JsonObject -> episodes.entries.mapNotNull { (key, value) ->
                (value as? JsonArray)?.let { (key.toIntOrNull() ?: 0) to it }
            }
            is JsonArray -> episodes.mapIndexedNotNull { index, value ->
                (value as? JsonArray)?.let { (index + 1) to it }
            }
            else -> emptyList()
        }

        val seasons = groups.mapNotNull { (seasonKey, array) ->
            val list = array.mapIndexedNotNull { index, el ->
                val o = el as? JsonObject ?: return@mapIndexedNotNull null
                val episodeId = o.str("id") ?: return@mapIndexedNotNull null
                val epInfo = o["info"] as? JsonObject
                val number = o.str("episode_num")?.toIntOrNull() ?: (index + 1)
                Episode(
                    id = episodeId,
                    title = o.text("title") ?: "Épisode $number",
                    season = o.str("season")?.toIntOrNull() ?: seasonKey,
                    number = number,
                    ext = o.text("container_extension") ?: "mp4",
                    plot = epInfo?.text("plot"),
                    image = epInfo?.text("movie_image"),
                    duration = epInfo?.text("duration"),
                )
            }.sortedBy { it.number }
            if (list.isEmpty()) null else Season(number = seasonKey, episodes = list)
        }.sortedBy { it.number }

        return detailInfo to seasons
    }

    fun episodeUrl(creds: Credentials, episode: Episode): String =
        mediaUrl(creds, "episode", episode.id, episode.ext)
}
