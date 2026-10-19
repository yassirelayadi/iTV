package fr.poptv.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.poptv.player.data.Category
import fr.poptv.player.data.Channel
import fr.poptv.player.data.Credentials
import fr.poptv.player.data.Detail
import fr.poptv.player.data.Episode
import fr.poptv.player.data.Repository
import fr.poptv.player.data.ResumeEntry
import fr.poptv.player.data.Section
import fr.poptv.player.data.Store
import fr.poptv.player.data.VodCatalog
import fr.poptv.player.data.VodItem
import fr.poptv.player.data.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

const val CAT_ALL = "__all"

/** Un catalogue en cache de moins de 6 heures n'est pas retéléchargé. */
private const val MEDIA_CACHE_TTL_MS = 6L * 60 * 60 * 1000
const val CAT_FAV = "__fav"

/** Lecture d'un film ou d'un épisode. key = "m:ID" (film) ou "e:ID" (épisode). */
data class VodPlayback(
    val key: String,
    val kind: String,
    val title: String,
    val url: String, // uniquement en mémoire : contient les identifiants, jamais enregistrée
    val ext: String,
    val poster: String?,
    val startMs: Long,
    val hasNext: Boolean,
    val subtitle: String? = null, // titre de l'épisode, affiché sous le titre dans le lecteur
)

/**
 * Les listes « all* » contiennent tout le catalogue ; les listes sans préfixe
 * (categories, channels, movies, series) sont filtrées : sans les catégories masquées.
 * Les écrans de liste ne lisent que les listes filtrées.
 */
data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val needsLogin: Boolean = false,
    val allCategories: List<Category> = emptyList(),
    val allChannels: List<Channel> = emptyList(),
    val categories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val selectedCategory: String = CAT_ALL,
    val query: String = "",
    val playing: Channel? = null,
    val zapList: List<Channel> = emptyList(),
    // Films et séries
    val vodSupported: Boolean = false,
    val section: Section = Section.LIVE,
    val allMovies: VodCatalog = VodCatalog(),
    val allSeries: VodCatalog = VodCatalog(),
    val movies: VodCatalog = VodCatalog(),
    val series: VodCatalog = VodCatalog(),
    val moviesLoading: Boolean = false,
    val seriesLoading: Boolean = false,
    val mediaError: String? = null,
    val detail: Detail? = null,
    val resume: List<ResumeEntry> = emptyList(),
    val vodPlaying: VodPlayback? = null,
    // Réglages et catégories masquées
    val hiddenLive: Set<String> = emptySet(),
    val hiddenMovies: Set<String> = emptySet(),
    val hiddenSeries: Set<String> = emptySet(),
    val settingsOpen: Boolean = false,
    val manageSection: Section? = null,
)

private fun VodCatalog.without(hidden: Set<String>): VodCatalog =
    if (hidden.isEmpty()) {
        this
    } else {
        VodCatalog(
            categories = categories.filter { it.id !in hidden },
            items = items.filter { it.categoryId !in hidden },
        )
    }

/** Recalcule les listes visibles à partir des listes complètes et des catégories masquées. */
private fun UiState.refiltered(): UiState = copy(
    categories = if (hiddenLive.isEmpty()) allCategories else allCategories.filter { it.id !in hiddenLive },
    channels = if (hiddenLive.isEmpty()) allChannels else allChannels.filter { it.categoryId !in hiddenLive },
    movies = allMovies.without(hiddenMovies),
    series = allSeries.without(hiddenSeries),
)

private fun UiState.hiddenOf(section: Section): Set<String> = when (section) {
    Section.LIVE -> hiddenLive
    Section.MOVIES -> hiddenMovies
    Section.SERIES -> hiddenSeries
}

private fun UiState.withHidden(section: Section, ids: Set<String>): UiState = when (section) {
    Section.LIVE -> copy(hiddenLive = ids)
    Section.MOVIES -> copy(hiddenMovies = ids)
    Section.SERIES -> copy(hiddenSeries = ids)
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val repo = Repository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var credentials: Credentials? = null
    private var queue: List<VodPlayback> = emptyList()
    private var queueIndex = -1

    init {
        viewModelScope.launch {
            store.favorites.collect { favs -> _state.update { it.copy(favorites = favs) } }
        }
        viewModelScope.launch {
            store.resume.collect { list -> _state.update { it.copy(resume = list) } }
        }
        viewModelScope.launch {
            // Les catégories masquées sont lues avant la première liste, pour éviter un flash.
            val hidden = store.loadHidden()
            _state.update {
                it.copy(
                    hiddenLive = hidden[Section.LIVE].orEmpty(),
                    hiddenMovies = hidden[Section.MOVIES].orEmpty(),
                    hiddenSeries = hidden[Section.SERIES].orEmpty(),
                )
            }
            val creds = store.credentials.first()
            if (creds == null) {
                _state.update { it.copy(loading = false, needsLogin = true) }
            } else {
                load(creds, firstLogin = false)
            }
        }
    }

    // ---- Connexion / chargement des chaînes ----

    fun login(creds: Credentials) = load(creds, firstLogin = true)

    fun showLogin() = _state.update { it.copy(needsLogin = true, error = null) }

    fun cancelLogin() = _state.update {
        if (it.allChannels.isNotEmpty()) it.copy(needsLogin = false, error = null) else it
    }

    fun logout() {
        viewModelScope.launch {
            store.saveCredentials(null)
            store.clearHidden()
            repo.clearCache()
            credentials = null
            _state.update {
                UiState(loading = false, needsLogin = true, favorites = it.favorites, resume = it.resume)
            }
        }
    }

    /** Recharge les chaînes, ainsi que les films et séries déjà chargés. */
    fun refresh() {
        viewModelScope.launch {
            store.credentials.first()?.let { load(it, firstLogin = false) }
        }
        val s = _state.value
        if (s.allMovies.items.isNotEmpty()) loadMedia(Section.MOVIES, force = true)
        if (s.allSeries.items.isNotEmpty()) loadMedia(Section.SERIES, force = true)
    }

    private fun load(creds: Credentials, firstLogin: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            if (!firstLogin) {
                credentials = creds
                repo.loadCached()?.let { cached ->
                    _state.update {
                        it.copy(
                            allCategories = cached.categories,
                            allChannels = cached.channels,
                            vodSupported = creds.type == "xtream",
                            loading = false,
                            needsLogin = false,
                        ).refiltered()
                    }
                }
            }

            try {
                val playlist = repo.fetch(creds)
                credentials = creds
                if (firstLogin) {
                    store.saveCredentials(creds)
                    store.clearHidden() // les identifiants de catégories changent avec le fournisseur
                    repo.clearCache()
                }
                // Une playlist M3U contient souvent des identifiants dans ses liens : on ne la met pas en cache.
                if (creds.type == "xtream") repo.saveCache(playlist)
                _state.update {
                    it.copy(
                        allCategories = playlist.categories,
                        allChannels = playlist.channels,
                        vodSupported = creds.type == "xtream",
                        section = if (firstLogin) Section.LIVE else it.section,
                        allMovies = if (firstLogin) VodCatalog() else it.allMovies,
                        allSeries = if (firstLogin) VodCatalog() else it.allSeries,
                        hiddenLive = if (firstLogin) emptySet() else it.hiddenLive,
                        hiddenMovies = if (firstLogin) emptySet() else it.hiddenMovies,
                        hiddenSeries = if (firstLogin) emptySet() else it.hiddenSeries,
                        selectedCategory = if (firstLogin) CAT_ALL else it.selectedCategory,
                        settingsOpen = if (firstLogin) false else it.settingsOpen,
                        manageSection = if (firstLogin) null else it.manageSection,
                        query = "",
                        loading = false,
                        needsLogin = false,
                        error = null,
                    ).refiltered()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (!firstLogin && it.allChannels.isNotEmpty()) {
                        it.copy(loading = false) // on garde la liste en cache
                    } else {
                        it.copy(loading = false, needsLogin = true, error = friendly(e))
                    }
                }
            }
        }
    }

    // ---- Sections et navigation dans les listes ----

    fun selectSection(section: Section) {
        _state.update { it.copy(section = section, selectedCategory = CAT_ALL, query = "", mediaError = null) }
        if (section != Section.LIVE) loadMedia(section, force = false)
    }

    fun selectCategory(id: String) = _state.update { it.copy(selectedCategory = id) }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { store.toggleFavorite(channelId) }
    }

    // ---- Réglages et catégories masquées ----

    fun openSettings() = _state.update { it.copy(settingsOpen = true) }

    fun closeSettings() = _state.update {
        it.copy(settingsOpen = false, manageSection = null, selectedCategory = CAT_ALL, query = "")
    }

    fun openCategories(section: Section) {
        _state.update { it.copy(manageSection = section) }
        if (section != Section.LIVE) loadMedia(section, force = false)
    }

    fun closeCategories() = _state.update { it.copy(manageSection = null) }

    /** Masque (hidden = true) ou affiche (false) les catégories données pour une section. */
    fun setCategoriesHidden(section: Section, ids: Collection<String>, hidden: Boolean) {
        var updated: Set<String> = emptySet()
        _state.update { s ->
            val current = s.hiddenOf(section)
            updated = if (hidden) current + ids else current - ids.toSet()
            s.withHidden(section, updated).refiltered()
        }
        viewModelScope.launch { store.setHidden(section, updated) }
        // Les catégories masquées ne sont pas téléchargées : en réafficher une demande de la charger.
        if (!hidden && section != Section.LIVE) {
            val catalog = _state.value.let { if (section == Section.MOVIES) it.allMovies else it.allSeries }
            if (catalog.excluded.any { it in ids }) loadMedia(section, force = true)
        }
    }

    // ---- Catalogues films / séries ----

    private fun loadMedia(section: Section, force: Boolean) {
        val creds = credentials ?: return
        val s = _state.value
        val isMovies = section == Section.MOVIES
        val loaded = (if (isMovies) s.allMovies else s.allSeries).items.isNotEmpty()
        val busy = if (isMovies) s.moviesLoading else s.seriesLoading
        if (busy || (loaded && !force)) return

        viewModelScope.launch {
            setMediaLoading(section, true)
            var fromCache = loaded
            if (!loaded) {
                repo.loadCachedMedia(section)?.let {
                    setCatalog(section, it)
                    fromCache = true
                }
            }
            try {
                // Cache récent : affichage immédiat, sans retélécharger tout le catalogue.
                // « Actualiser » dans les réglages force le téléchargement. Si une catégorie
                // a été réaffichée depuis, le cache ne la contient pas : on recharge.
                val age = repo.mediaCacheAgeMs(section)
                val cachedNow = _state.value.let { if (isMovies) it.allMovies else it.allSeries }
                val missing = cachedNow.excluded.any { it !in _state.value.hiddenOf(section) }
                if (!force && fromCache && !missing && age != null && age < MEDIA_CACHE_TTL_MS) return@launch

                // Boucle : si des catégories sont réaffichées pendant le téléchargement, on recharge.
                while (true) {
                    val hidden = _state.value.hiddenOf(section)
                    val catalog = repo.fetchMedia(creds, section, hidden)
                    repo.saveMediaCache(section, catalog)
                    // Rien n'a changé : on évite de reconstruire tout l'écran.
                    val current = _state.value.let { if (isMovies) it.allMovies else it.allSeries }
                    if (catalog != current) setCatalog(section, catalog)
                    val unhiddenMeanwhile = hidden.any { it !in _state.value.hiddenOf(section) }
                    if (!unhiddenMeanwhile) break
                }
                _state.update { it.copy(mediaError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(mediaError = "Catalogue trop volumineux pour la mémoire de cet appareil.") }
            } catch (e: Exception) {
                _state.update { st ->
                    val empty = (if (isMovies) st.allMovies else st.allSeries).items.isEmpty()
                    st.copy(mediaError = if (empty) friendly(e) else null)
                }
            } finally {
                setMediaLoading(section, false)
            }
        }
    }

    private fun setCatalog(section: Section, catalog: VodCatalog) = _state.update {
        if (section == Section.MOVIES) {
            it.copy(allMovies = catalog).refiltered()
        } else {
            it.copy(allSeries = catalog).refiltered()
        }
    }

    private fun setMediaLoading(section: Section, loading: Boolean) = _state.update {
        if (section == Section.MOVIES) it.copy(moviesLoading = loading) else it.copy(seriesLoading = loading)
    }

    // ---- Pages de détail ----

    fun openMovie(item: VodItem) {
        _state.update { it.copy(detail = Detail(item = item, isSeries = false)) }
        val creds = credentials ?: return
        viewModelScope.launch {
            try {
                val info = repo.vodInfo(creds, item.id)
                updateDetail(item.id) { it.copy(info = info, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Les détails sont facultatifs : la lecture reste possible.
                updateDetail(item.id) { it.copy(loading = false) }
            }
        }
    }

    fun openSeries(item: VodItem) {
        _state.update { it.copy(detail = Detail(item = item, isSeries = true)) }
        val creds = credentials ?: return
        viewModelScope.launch {
            try {
                val (info, seasons) = repo.seriesInfo(creds, item.id)
                updateDetail(item.id) {
                    it.copy(
                        info = info,
                        seasons = seasons,
                        loading = false,
                        error = if (seasons.isEmpty()) "Aucun épisode disponible pour cette série." else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetail(item.id) { it.copy(loading = false, error = friendly(e)) }
            }
        }
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    private fun updateDetail(itemId: String, transform: (Detail) -> Detail) = _state.update { s ->
        val d = s.detail
        if (d != null && d.item.id == itemId) s.copy(detail = transform(d)) else s
    }

    // ---- Lecture des films et épisodes ----

    private fun resumePosition(key: String): Long =
        _state.value.resume.firstOrNull { it.key == key }?.positionMs ?: 0L

    fun playMovie(fromStart: Boolean) {
        val detail = _state.value.detail ?: return
        val creds = credentials ?: return
        val key = "m:${detail.item.id}"
        queue = emptyList()
        queueIndex = -1
        _state.update {
            it.copy(
                vodPlaying = VodPlayback(
                    key = key,
                    kind = "movie",
                    title = detail.item.name,
                    url = XtreamClient.movieUrl(creds, detail.item),
                    ext = detail.item.ext,
                    poster = detail.item.poster,
                    startMs = if (fromStart) 0L else resumePosition(key),
                    hasNext = false,
                )
            )
        }
    }

    fun playEpisode(episode: Episode, siblings: List<Episode>, fromStart: Boolean = false) {
        val detail = _state.value.detail ?: return
        val creds = credentials ?: return
        queue = siblings.map { ep ->
            VodPlayback(
                key = "e:${ep.id}",
                kind = "episode",
                title = "${detail.item.name} · S${ep.season} E${ep.number}",
                url = XtreamClient.episodeUrl(creds, ep),
                ext = ep.ext,
                poster = detail.item.poster,
                startMs = 0L,
                hasNext = false,
                subtitle = ep.title,
            )
        }
        queueIndex = siblings.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val current = queue[queueIndex]
        _state.update {
            it.copy(
                vodPlaying = current.copy(
                    startMs = if (fromStart) 0L else resumePosition(current.key),
                    hasNext = queueIndex < queue.lastIndex,
                )
            )
        }
    }

    fun playResume(entry: ResumeEntry) {
        val creds = credentials ?: return
        queue = emptyList()
        queueIndex = -1
        _state.update {
            it.copy(
                vodPlaying = VodPlayback(
                    key = entry.key,
                    kind = entry.kind,
                    title = entry.title,
                    // L'URL est reconstruite avec les identifiants actuels : l'historique n'en contient pas.
                    url = XtreamClient.mediaUrl(creds, entry.kind, entry.key.substringAfter(':'), entry.ext),
                    ext = entry.ext,
                    poster = entry.poster,
                    startMs = entry.positionMs,
                    hasNext = false,
                )
            )
        }
    }

    /** Fin de lecture : épisode suivant s'il y en a un, sinon retour à l'écran précédent. */
    fun onVodEnded() {
        if (queueIndex in 0 until queue.lastIndex) {
            queueIndex++
            val next = queue[queueIndex]
            _state.update { it.copy(vodPlaying = next.copy(hasNext = queueIndex < queue.lastIndex)) }
        } else {
            stopVod()
        }
    }

    fun stopVod() {
        queue = emptyList()
        queueIndex = -1
        _state.update { it.copy(vodPlaying = null) }
    }

    fun saveProgress(playback: VodPlayback, positionMs: Long, durationMs: Long) {
        viewModelScope.launch {
            if (durationMs > 0 && positionMs >= durationMs * 95 / 100) {
                store.removeResume(playback.key)
            } else if (positionMs > 30_000) {
                store.saveResume(
                    ResumeEntry(
                        key = playback.key,
                        kind = playback.kind,
                        title = playback.title,
                        ext = playback.ext,
                        poster = playback.poster,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    // ---- Chaînes en direct ----

    fun play(channel: Channel, among: List<Channel>) {
        _state.update { it.copy(playing = resolve(channel), zapList = among) }
        viewModelScope.launch { store.setLastChannel(channel.id) }
    }

    fun stop() = _state.update { it.copy(playing = null) }

    /** Complète l'URL d'une chaîne Xtream avec les identifiants, en mémoire seulement. */
    private fun resolve(channel: Channel): Channel {
        val creds = credentials ?: return channel
        if (!channel.streamUrl.startsWith(XtreamClient.LIVE_PREFIX)) return channel
        return channel.copy(
            streamUrl = XtreamClient.liveUrl(creds, channel.streamUrl.removePrefix(XtreamClient.LIVE_PREFIX))
        )
    }

    fun zap(delta: Int) {
        val s = _state.value
        val current = s.playing ?: return
        if (s.zapList.isEmpty()) return
        val index = s.zapList.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        play(s.zapList[(index + delta).mod(s.zapList.size)], s.zapList)
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "Serveur introuvable. Vérifie l'adresse et ta connexion."
        is SocketTimeoutException -> "Le serveur met trop de temps à répondre."
        is IOException -> "Erreur réseau : ${e.message ?: "connexion impossible"}"
        is IllegalStateException -> e.message ?: "Erreur inconnue"
        is IllegalArgumentException -> "Adresse invalide."
        else -> e.message ?: "Erreur inconnue"
    }
}
