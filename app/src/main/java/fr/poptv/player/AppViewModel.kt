package fr.poptv.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.poptv.player.data.Category
import fr.poptv.player.data.Channel
import fr.poptv.player.ui.precomputeShelves
import fr.poptv.player.data.AccountInfo
import fr.poptv.player.data.Credentials
import fr.poptv.player.data.DetailInfo
import fr.poptv.player.data.M3uClassifier
import fr.poptv.player.data.Detail
import fr.poptv.player.data.Episode
import fr.poptv.player.data.EpgInfo
import fr.poptv.player.data.Repository
import fr.poptv.player.data.ResumeEntry
import kotlinx.coroutines.flow.collectLatest
import fr.poptv.player.data.ForYou
import fr.poptv.player.data.ForYouCache
import fr.poptv.player.data.Season
import fr.poptv.player.data.Section
import fr.poptv.player.data.TmdbClient
import fr.poptv.player.data.TmdbMatcher
import fr.poptv.player.data.TmdbPicks
import fr.poptv.player.data.TmdbTrends
import fr.poptv.player.data.Store
import fr.poptv.player.data.VodCatalog
import fr.poptv.player.data.CatalogDb
import fr.poptv.player.data.VodItem
import fr.poptv.player.data.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

const val CAT_ALL = "__all"

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
    val next: String? = null, // libellé de l'épisode suivant (« S1 E4  Titre »), pour la carte de fin
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
    val favVod: Set<String> = emptySet(),
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
    val recent: List<String> = emptyList(),
    val watched: Set<String> = emptySet(),
    val vodPlaying: VodPlayback? = null,
    // Réglages et catégories masquées
    val hiddenLive: Set<String> = emptySet(),
    val hiddenMovies: Set<String> = emptySet(),
    val hiddenSeries: Set<String> = emptySet(),
    val settingsOpen: Boolean = false,
    val account: AccountInfo? = null,
    val accountLoading: Boolean = false,
    val manageSection: Section? = null,
    // TMDB : tendances et « à l'affiche »
    val tmdbConfigured: Boolean = false,
    val tmdbUsesDefault: Boolean = false,
    val tmdbOpen: Boolean = false,
    val tmdbBusy: Boolean = false,
    val tmdbError: String? = null,
    val picks: TmdbPicks = TmdbPicks(),
    // « Pour vous » : suggestions selon la liste et l'historique
    val forYouMovies: List<VodItem> = emptyList(),
    val forYouSeries: List<VodItem> = emptyList(),
)

private fun VodCatalog.without(hidden: Set<String>): VodCatalog =
    if (hidden.isEmpty()) {
        this
    } else {
        VodCatalog(
            categories = categories.filter { it.id !in hidden },
            items = items.filter { it.categoryId !in hidden },
            excluded = excluded,
            counts = counts.filterKeys { it !in hidden },
        )
    }

/** Recalcule les listes visibles à partir des listes complètes et des catégories masquées. */
internal fun UiState.refiltered(): UiState = copy(
    categories = if (hiddenLive.isEmpty()) allCategories else allCategories.filter { it.id !in hiddenLive },
    channels = if (hiddenLive.isEmpty()) allChannels else allChannels.filter { it.categoryId !in hiddenLive },
    movies = allMovies.without(hiddenMovies),
    series = allSeries.without(hiddenSeries),
)

/** Refiltre uniquement la section modifiée : un appui sur une catégorie ne doit pas tout recalculer. */
internal fun UiState.refilteredFor(section: Section): UiState = when (section) {
    Section.LIVE -> copy(
        categories = if (hiddenLive.isEmpty()) allCategories else allCategories.filter { it.id !in hiddenLive },
        channels = if (hiddenLive.isEmpty()) allChannels else allChannels.filter { it.categoryId !in hiddenLive },
    )
    Section.MOVIES -> copy(movies = allMovies.without(hiddenMovies))
    Section.SERIES -> copy(series = allSeries.without(hiddenSeries))
}

internal fun UiState.hiddenOf(section: Section): Set<String> = when (section) {
    Section.LIVE -> hiddenLive
    Section.MOVIES -> hiddenMovies
    Section.SERIES -> hiddenSeries
}

internal fun UiState.withHidden(section: Section, ids: Set<String>): UiState = when (section) {
    Section.LIVE -> copy(hiddenLive = ids)
    Section.MOVIES -> copy(hiddenMovies = ids)
    Section.SERIES -> copy(hiddenSeries = ids)
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val repo = Repository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Guide des programmes, chargé à la demande pour les chaînes affichées.
    private val _epg = MutableStateFlow<Map<String, EpgInfo>>(emptyMap())
    val epg: StateFlow<Map<String, EpgInfo>> = _epg.asStateFlow()
    private val epgInFlight = HashSet<String>()
    private val epgGate = Semaphore(3)

    private var credentials: Credentials? = null

    // Listes M3U : épisodes par série et adresses de lecture, reconstruits à chaque chargement.

    // TMDB : la clé reste ici, en mémoire, et chiffrée sur disque ; elle n'entre jamais dans l'état affiché.
    private val tmdb = TmdbController(viewModelScope, _state, store, ::friendly)
    private val vod = VodController(viewModelScope, _state, store, repo, { credentials }, ::friendly)
    private val media = MediaLoader(viewModelScope, _state, repo, tmdb, { credentials }, ::friendly)
    private var preloadJob: kotlinx.coroutines.Job? = null

    init {
        tmdb.start()
        // Les rangées des écrans Films et Séries sont calculées dès que le catalogue change,
        // hors du fil principal : à l'ouverture de l'écran, elles sont déjà prêtes.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            // Pas de calcul pendant la gestion des catégories : il se fait une fois, à la fermeture de l'écran.
            _state.map { if (it.manageSection != null) null else it.movies to it.hiddenMovies }.distinctUntilChanged { a, b -> a?.first === b?.first }.collect { it?.let { (c, h) -> runCatching { precomputeShelves(c, h, true) } } }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _state.map { if (it.manageSection != null) null else it.series to it.hiddenSeries }.distinctUntilChanged { a, b -> a?.first === b?.first }.collect { it?.let { (c, h) -> runCatching { precomputeShelves(c, h, false) } } }
        }
        viewModelScope.launch {
            store.favorites.collect { favs -> _state.update { it.copy(favorites = favs) } }
        }
        viewModelScope.launch {
            store.favoritesVod.collect { favs -> _state.update { it.copy(favVod = favs) } }
        }
        viewModelScope.launch {
            store.resume.collect { list -> _state.update { it.copy(resume = list) } }
        }
        viewModelScope.launch {
            store.recentChannels.collect { ids -> _state.update { it.copy(recent = ids) } }
        }
        viewModelScope.launch {
            store.watched.collect { keys -> _state.update { it.copy(watched = keys) } }
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
            _epg.value = emptyMap()
            _state.update {
                UiState(
                    loading = false,
                    needsLogin = true,
                    favorites = it.favorites,
                    favVod = it.favVod,
                    resume = it.resume,
                    recent = it.recent,
                    watched = it.watched,
                    tmdbConfigured = it.tmdbConfigured,
                    tmdbUsesDefault = it.tmdbUsesDefault,
                )
            }
        }
    }

    /** Recharge les chaînes, ainsi que les films et séries déjà chargés. */
    fun refresh() {
        viewModelScope.launch {
            store.credentials.first()?.let { load(it, firstLogin = false) }
        }
        val s = _state.value
        if (s.allMovies.size > 0) media.load(Section.MOVIES, force = true)
        if (s.allSeries.size > 0) media.load(Section.SERIES, force = true)
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
                // M3U : on sépare chaînes, films et séries d'après les adresses et les groupes.
                val library = if (creds.type == "m3u") M3uClassifier.split(playlist) else null
                // Films et séries M3U : écrits dans la base, l'écran ne garde que les catégories et les effectifs.
                var movieMeta: VodCatalog? = null
                var seriesMeta: VodCatalog? = null
                if (library != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        CatalogDb.replace(Section.MOVIES, library.movies)
                        CatalogDb.replace(Section.SERIES, library.series)
                        movieMeta = CatalogDb.meta(Section.MOVIES)
                        seriesMeta = CatalogDb.meta(Section.SERIES)
                    }
                }
                if (library != null) {
                    vod.m3uEpisodes = library.episodes
                    vod.m3uUrls = library.urls
                }
                val live = library?.live ?: playlist
                _state.update {
                    it.copy(
                        allCategories = live.categories,
                        allChannels = live.channels,
                        vodSupported = if (library != null) {
                            library.movies.items.isNotEmpty() || library.series.items.isNotEmpty()
                        } else {
                            creds.type == "xtream"
                        },
                        section = if (firstLogin) Section.LIVE else it.section,
                        allMovies = movieMeta ?: if (firstLogin) VodCatalog() else it.allMovies,
                        allSeries = seriesMeta ?: if (firstLogin) VodCatalog() else it.allSeries,
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
                preloadCatalogs(creds)
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

    /**
     * Charge films, séries et tendances TMDB en arrière-plan, une fois les chaînes affichées :
     * l'un après l'autre (mémoire et débit ménagés), pour que les sections soient prêtes à l'ouverture.
     */
    private fun preloadCatalogs(creds: Credentials) {
        if (creds.type != "xtream") return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            delay(500)
            tmdb.ensureTmdb()
            media.load(Section.MOVIES, force = false)?.join()
            media.load(Section.SERIES, force = false)?.join()
        }
    }

    // ---- Sections et navigation dans les listes ----

    fun selectSection(section: Section) {
        _state.update { it.copy(section = section, selectedCategory = CAT_ALL, query = "", mediaError = null) }
        if (section != Section.LIVE) {
            media.load(section, force = false)
            tmdb.ensureTmdb()
        }
    }

    fun selectCategory(id: String) = _state.update { it.copy(selectedCategory = id) }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** Recharge le catalogue de la section affichée (bouton « Réessayer » / « Actualiser » des états vides). */
    fun reloadMedia() {
        // L'erreur reste affichée pendant le rechargement : le bouton ne disparaît pas sous le doigt.
        if (credentials?.type == "xtream") media.load(_state.value.section, force = true) else refresh()
    }

    /** Ajoute ou retire un film (« m:id ») ou une série (« s:id ») de Ma liste. */
    fun toggleFavoriteVod(key: String) {
        viewModelScope.launch { store.toggleFavoriteVod(key) }
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch { store.toggleFavorite(channelId) }
    }

    // ---- Réglages et catégories masquées ----

    fun openSettings() {
        _state.update { it.copy(settingsOpen = true) }
        loadAccount()
    }

    /** Charge le profil du compte (identifiant, expiration) à l'ouverture des réglages. */
    private fun loadAccount() {
        val creds = credentials ?: return
        if (creds.type != "xtream") {
            val host = runCatching { android.net.Uri.parse(creds.m3uUrl).host }.getOrNull().orEmpty()
            _state.update { it.copy(account = AccountInfo(xtream = false, username = host), accountLoading = false) }
            return
        }
        _state.update {
            it.copy(
                account = it.account ?: AccountInfo(username = creds.username),
                accountLoading = true,
            )
        }
        viewModelScope.launch {
            val info = try {
                repo.account(creds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AccountInfo(username = creds.username)
            }
            _state.update { it.copy(account = info, accountLoading = false) }
        }
    }

    fun closeSettings() {
        val wasManaging = _state.value.manageSection != null
        _state.update { it.copy(settingsOpen = false, manageSection = null, selectedCategory = CAT_ALL, query = "") }
        if (wasManaging) reloadRestoredCategories()
    }

    fun openCategories(section: Section) {
        _state.update { it.copy(manageSection = section) }
        if (section != Section.LIVE) media.load(section, force = false)
    }

    fun closeCategories() {
        _state.update { it.copy(manageSection = null) }
        reloadRestoredCategories()
        tmdb.recomputePicks() // le Top 10 tient compte des catégories masquées ou réaffichées
    }

    /** Catégories réaffichées pendant la gestion : leurs titres n'ont pas été téléchargés, on recharge une fois. */
    private fun reloadRestoredCategories() {
        val s = _state.value
        for (section in listOf(Section.MOVIES, Section.SERIES)) {
            val catalog = if (section == Section.MOVIES) s.allMovies else s.allSeries
            if (catalog.excluded.any { it !in s.hiddenOf(section) }) media.load(section, force = true)
        }
    }

    /** Masque (hidden = true) ou affiche (false) les catégories données pour une section. */
    fun setCategoriesHidden(section: Section, ids: Collection<String>, hidden: Boolean) {
        var updated: Set<String> = emptySet()
        _state.update { s ->
            val current = s.hiddenOf(section)
            updated = if (hidden) current + ids else current - ids.toSet()
            s.withHidden(section, updated).refilteredFor(section)
        }
        viewModelScope.launch { store.setHidden(section, updated) }
        // Les catégories masquées ne sont pas téléchargées : en réafficher une demande de la charger.
        if (!hidden && section != Section.LIVE) {
            // Le retéléchargement se fera une seule fois, à la fermeture de l'écran (reloadRestoredCategories).
        }
    }

    // ---- Catalogues films / séries ----

    // ---- TMDB : Top 10 et « À l'affiche » ----

    // ---- TMDB : Top 10 et « À l'affiche » ----

    fun openTmdb() = tmdb.openTmdb()

    fun closeTmdb() = tmdb.closeTmdb()

    fun saveTmdbKey(raw: String) = tmdb.saveTmdbKey(raw)


    // ---- Fiches et lecture des films et séries (VodController) ----

    fun openMovie(item: VodItem) = vod.openMovie(item)
    fun openSeries(item: VodItem) = vod.openSeries(item)
    fun closeDetail() = vod.closeDetail()
    fun playMovie(fromStart: Boolean) = vod.playMovie(fromStart)
    fun playEpisode(episode: Episode, siblings: List<Episode>, fromStart: Boolean = false) = vod.playEpisode(episode, siblings, fromStart)
    fun playResume(entry: ResumeEntry) = vod.playResume(entry)
    fun onVodEnded() = vod.onVodEnded()
    fun stopVod() = vod.stopVod()
    fun saveProgress(playback: VodPlayback, positionMs: Long, durationMs: Long) = vod.saveProgress(playback, positionMs, durationMs)

    // ---- Chaînes en direct ----

    /** Demande le programme d'une chaîne (comptes Xtream). Rafraîchi au plus toutes les 10 minutes. */
    fun requestEpg(channelId: String) {
        val creds = credentials ?: return
        if (creds.type != "xtream") return
        val nowSec = System.currentTimeMillis() / 1000
        _epg.value[channelId]?.let { known ->
            val fresh = nowSec - known.fetchedAt < 600 &&
                (known.programs.isEmpty() || known.programs.last().endSec > nowSec)
            if (fresh) return
        }
        if (!epgInFlight.add(channelId)) return
        viewModelScope.launch {
            try {
                val programs = epgGate.withPermit { repo.epg(creds, channelId) }
                _epg.update { it + (channelId to EpgInfo(programs, nowSec)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pas de guide pour cette chaîne : on réessaiera plus tard.
                _epg.update { it + (channelId to EpgInfo(emptyList(), nowSec)) }
            } finally {
                epgInFlight.remove(channelId)
            }
        }
    }

    fun play(channel: Channel, among: List<Channel>) {
        _state.update { it.copy(playing = resolve(channel), zapList = among) }
        viewModelScope.launch {
            store.setLastChannel(channel.id)
            store.addRecent(channel.id)
        }
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
        is IllegalStateException -> when {
            e.message?.contains("HTTP 5") == true -> "Le serveur du fournisseur est indisponible (${e.message?.substringAfter("HTTP ")}). Réessaie dans quelques minutes."
            else -> e.message ?: "Erreur inconnue"
        }
        is IllegalArgumentException -> "Adresse invalide."
        else -> e.message ?: "Erreur inconnue"
    }
}
