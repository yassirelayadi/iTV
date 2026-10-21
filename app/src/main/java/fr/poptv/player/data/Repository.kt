package fr.poptv.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.Request
import java.io.File

/** Au-delà de ce nombre de catégories affichées, on télécharge tout d'un bloc. */
private const val PER_CATEGORY_LIMIT = 30

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

    /**
     * Télécharge un catalogue en ignorant les catégories masquées. Peu de catégories affichées :
     * une requête par catégorie (4 en parallèle) ; sinon un seul téléchargement dont on écarte
     * les catégories masquées au fil de la lecture.
     */
    suspend fun fetchMedia(creds: Credentials, section: Section, hidden: Set<String>): VodCatalog =
        withContext(Dispatchers.IO) {
            val movies = section == Section.MOVIES
            val categories = if (movies) XtreamClient.movieCategories(creds) else XtreamClient.seriesCategories(creds)
            val visible = categories.filter { it.id !in hidden }

            fun load(categoryId: String?, skip: Set<String>) =
                if (movies) XtreamClient.fetchMovies(creds, categoryId, skip)
                else XtreamClient.fetchSeries(creds, categoryId, skip)

            val items = if (hidden.isEmpty() || visible.size > PER_CATEGORY_LIMIT) {
                load(null, hidden)
            } else {
                val gate = Semaphore(4)
                coroutineScope {
                    visible.map { category ->
                        async { gate.withPermit { load(category.id, emptySet()) } }
                    }.awaitAll().flatten()
                }
            }
            VodCatalog(
                categories = categories,
                items = items.distinctBy { it.id },
                excluded = categories.map { it.id }.filter { it in hidden },
            )
        }

    suspend fun loadCachedMedia(section: Section): VodCatalog? = withContext(Dispatchers.IO) {
        readCache(mediaFile(section)) { json.decodeFromStream(VodCatalog.serializer(), it) }
    }

    /** Ancienneté du cache d'un catalogue, ou null s'il n'existe pas. */
    fun mediaCacheAgeMs(section: Section): Long? {
        val file = mediaFile(section)
        return if (file.exists()) System.currentTimeMillis() - file.lastModified() else null
    }

    suspend fun saveMediaCache(section: Section, catalog: VodCatalog) = withContext(Dispatchers.IO) {
        writeCache(mediaFile(section)) { json.encodeToStream(VodCatalog.serializer(), catalog, it) }
    }

    suspend fun vodInfo(creds: Credentials, id: String): DetailInfo = withContext(Dispatchers.IO) {
        XtreamClient.vodInfo(creds, id)
    }

    suspend fun seriesInfo(creds: Credentials, id: String): Pair<DetailInfo, List<Season>> =
        withContext(Dispatchers.IO) { XtreamClient.seriesInfo(creds, id) }

    suspend fun account(creds: Credentials): AccountInfo = withContext(Dispatchers.IO) {
        XtreamClient.accountInfo(creds)
    }

    suspend fun epg(creds: Credentials, channelId: String): List<Program> = withContext(Dispatchers.IO) {
        XtreamClient.shortEpg(creds, channelId)
    }

    fun clearCache() {
        cacheFile.delete()
        mediaFile(Section.MOVIES).delete()
        mediaFile(Section.SERIES).delete()
    }
}
