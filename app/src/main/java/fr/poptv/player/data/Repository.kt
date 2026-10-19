package fr.poptv.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

class Repository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val cacheFile: File get() = File(context.cacheDir, "playlist.json")

    suspend fun fetch(creds: Credentials): Playlist = withContext(Dispatchers.IO) {
        if (creds.type == "m3u") fetchM3u(creds.m3uUrl) else XtreamClient.fetch(creds)
    }

    private fun fetchM3u(rawUrl: String): Playlist {
        var url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        val request = Request.Builder().url(url).header("User-Agent", "iTV/0.1").build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Le serveur a répondu HTTP ${response.code}")
            val source = response.body?.source() ?: error("Réponse vide du serveur")
            val playlist = source.inputStream().bufferedReader().use { reader ->
                M3uParser.parse(reader.lineSequence())
            }
            if (playlist.channels.isEmpty()) error("Aucune chaîne trouvée dans la playlist")
            playlist
        }
    }

    suspend fun loadCached(): Playlist? = withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString(Playlist.serializer(), cacheFile.readText())
        }.getOrNull()
    }

    suspend fun saveCache(playlist: Playlist) = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.writeText(json.encodeToString(Playlist.serializer(), playlist))
        }
        Unit
    }

    // ---- Films et séries ----

    private fun mediaFile(section: Section): File =
        File(context.cacheDir, if (section == Section.MOVIES) "movies.json" else "series.json")

    suspend fun fetchMedia(creds: Credentials, section: Section): VodCatalog = withContext(Dispatchers.IO) {
        if (section == Section.MOVIES) XtreamClient.fetchMovies(creds) else XtreamClient.fetchSeries(creds)
    }

    suspend fun loadCachedMedia(section: Section): VodCatalog? = withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString(VodCatalog.serializer(), mediaFile(section).readText())
        }.getOrNull()
    }

    suspend fun saveMediaCache(section: Section, catalog: VodCatalog) = withContext(Dispatchers.IO) {
        runCatching {
            mediaFile(section).writeText(json.encodeToString(VodCatalog.serializer(), catalog))
        }
        Unit
    }

    suspend fun vodInfo(creds: Credentials, id: String): DetailInfo = withContext(Dispatchers.IO) {
        XtreamClient.vodInfo(creds, id)
    }

    suspend fun seriesInfo(creds: Credentials, id: String): Pair<DetailInfo, List<Season>> =
        withContext(Dispatchers.IO) { XtreamClient.seriesInfo(creds, id) }

    fun clearCache() {
        cacheFile.delete()
        mediaFile(Section.MOVIES).delete()
        mediaFile(Section.SERIES).delete()
    }
}
