package fr.poptv.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.Request
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
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

    /** Lit un cache en flux ; un cache illisible (ou trop gros) est supprimé. */
    private fun <T> readCache(file: File, read: (java.io.InputStream) -> T): T? {
        if (!file.exists()) return null
        return try {
            file.inputStream().buffered().use(read)
        } catch (e: Throwable) {
            file.delete()
            null
        }
    }

    /** Écrit en flux dans un fichier temporaire, puis le renomme : jamais de cache à moitié écrit. */
    private fun writeCache(file: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().buffered().use(write)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Throwable) {
            tmp.delete()
        }
    }

    suspend fun loadCached(): Playlist? = withContext(Dispatchers.IO) {
        readCache(cacheFile) { json.decodeFromStream(Playlist.serializer(), it) }
    }

    suspend fun saveCache(playlist: Playlist) = withContext(Dispatchers.IO) {
        writeCache(cacheFile) { json.encodeToStream(Playlist.serializer(), playlist, it) }
    }

    // ---- Films et séries ----

    private fun mediaFile(section: Section): File =
        File(context.cacheDir, if (section == Section.MOVIES) "movies.json" else "series.json")

    suspend fun fetchMedia(creds: Credentials, section: Section): VodCatalog = withContext(Dispatchers.IO) {
        if (section == Section.MOVIES) XtreamClient.fetchMovies(creds) else XtreamClient.fetchSeries(creds)
    }

    suspend fun loadCachedMedia(section: Section): VodCatalog? = withContext(Dispatchers.IO) {
        readCache(mediaFile(section)) { json.decodeFromStream(VodCatalog.serializer(), it) }
    }

    suspend fun saveMediaCache(section: Section, catalog: VodCatalog) = withContext(Dispatchers.IO) {
        writeCache(mediaFile(section)) { json.encodeToStream(VodCatalog.serializer(), catalog, it) }
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
