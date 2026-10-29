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
private fun UiState.refiltered(): UiState = copy(
    categories = if (hiddenLive.isEmpty()) allCategories else allCategories.filter { it.id !in hiddenLive },
    channels = if (hiddenLive.isEmpty()) allChannels else allChannels.filter { it.categoryId !in hiddenLive },
    movies = allMovies.without(hiddenMovies),
    series = allSeries.without(hiddenSeries),
)

/** Refiltre uniquement la section modifiée : un appui sur une catégorie ne doit pas tout recalculer. */
private fun UiState.refilteredFor(section: Section): UiState = when (section) {
    Section.LIVE -> copy(
        categories = if (hiddenLive.isEmpty()) allCategories else allCategories.filter { it.id !in hiddenLive },
        channels = if (hiddenLive.isEmpty()) allChannels else allChannels.filter { it.categoryId !in hiddenLive },
    )
    Section.MOVIES -> copy(movies = allMovies.without(hiddenMovies))
    Section.SERIES -> copy(series = allSeries.without(hiddenSeries))
}

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

    // Guide des programmes, chargé à la demande pour les chaînes affichées.
    private val _epg = MutableStateFlow<Map<String, EpgInfo>>(emptyMap())
    val epg: StateFlow<Map<String, EpgInfo>> = _epg.asStateFlow()
    private val epgInFlight = HashSet<String>()
    private val epgGate = Semaphore(3)

    private var credentials: Credentials? = null

    // Listes M3U : épisodes par série et adresses de lecture, reconstruits à chaque chargement.
    private var m3uEpisodes: Map<String, List<Episode>> = emptyMap()
    private var m3uUrls: Map<String, String> = emptyMap()
    private var queue: List<VodPlayback> = emptyList()

    // TMDB : la clé reste ici, en mémoire, et chiffrée sur disque ; elle n'entre jamais dans l'état affiché.
    private val tmdb = TmdbController(viewModelScope, _state, store, ::friendly)
    private var preloadJob: kotlinx.coroutines.Job? = null
    private var queueIndex = -1

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
        if (s.allMovies.size > 0) loadMedia(Section.MOVIES, force = true)
        if (s.allSeries.size > 0) loadMedia(Section.SERIES, force = true)
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
                    m3uEpisodes = library.episodes
                    m3uUrls = library.urls
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
            loadMedia(Section.MOVIES, force = false)?.join()
            loadMedia(Section.SERIES, force = false)?.join()
        }
    }

    // ---- Sections et navigation dans les listes ----

    fun selectSection(section: Section) {
        _state.update { it.copy(section = section, selectedCategory = CAT_ALL, query = "", mediaError = null) }
        if (section != Section.LIVE) {
            loadMedia(section, force = false)
            tmdb.ensureTmdb()
        }
    }

    fun selectCategory(id: String) = _state.update { it.copy(selectedCategory = id) }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** Recharge le catalogue de la section affichée (bouton « Réessayer » / « Actualiser » des états vides). */
    fun reloadMedia() {
        // L'erreur reste affichée pendant le rechargement : le bouton ne disparaît pas sous le doigt.
        if (credentials?.type == "xtream") loadMedia(_state.value.section, force = true) else refresh()
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
        if (section != Section.LIVE) loadMedia(section, force = false)
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
            if (catalog.excluded.any { it !in s.hiddenOf(section) }) loadMedia(section, force = true)
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

    private fun loadMedia(section: Section, force: Boolean): kotlinx.coroutines.Job? {
        val creds = credentials ?: return null
        if (creds.type != "xtream") return null // M3U : le catalogue est déjà trié au chargement
        val s = _state.value
        val isMovies = section == Section.MOVIES
        val loaded = (if (isMovies) s.allMovies else s.allSeries).size > 0
        val busy = if (isMovies) s.moviesLoading else s.seriesLoading
        // Un seul téléchargement à la fois par section : des appuis rapides (afficher / masquer des
        // catégories) ne doivent pas lancer plusieurs téléchargements du catalogue en parallèle.
        if (busy || mediaJobs[section]?.isActive == true || (loaded && !force)) return null

        val job = viewModelScope.launch {
            setMediaLoading(section, true)
            // Un seul catalogue est téléchargé et analysé à la fois : changer de section pendant un
            // chargement ne doit pas lancer un deuxième gros traitement en parallèle (mémoire).
            mediaGate.withLock {
            var fromCache = loaded
            if (!loaded) {
                val stored = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (CatalogDb.hasData(section)) CatalogDb.meta(section) else null
                }
                if (stored != null) {
                    setCatalog(section, stored)
                    fromCache = true
                }
            }
            try {
                // Cache récent : affichage immédiat, sans retélécharger tout le catalogue.
                // « Actualiser » dans les réglages force le téléchargement. Si une catégorie
                // a été réaffichée depuis, le cache ne la contient pas : on recharge.
                val age = CatalogDb.ageMs(section)
                val cachedNow = _state.value.let { if (isMovies) it.allMovies else it.allSeries }
                val missing = cachedNow.excluded.any { it !in _state.value.hiddenOf(section) }
                if (!force && fromCache && !missing && age != null && age < MEDIA_CACHE_TTL_MS) return@launch

                // Boucle : si des catégories sont réaffichées pendant le téléchargement, on recharge.
                while (true) {
                    val hidden = _state.value.hiddenOf(section)
                    val catalog = repo.fetchMedia(creds, section, hidden)
                    setCatalog(section, catalog)
                    val unhiddenMeanwhile = hidden.any { it !in _state.value.hiddenOf(section) }
                    if (!unhiddenMeanwhile) break
                }
                _state.update { it.copy(mediaError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(mediaError = "Catalogue trop volumineux pour la mémoire de cet appareil.") }
            } catch (e: Throwable) {
                _state.update { st ->
                    val empty = (if (isMovies) st.allMovies else st.allSeries).size == 0
                    st.copy(mediaError = if (empty) friendly(e as? Exception ?: Exception(e.message)) else null)
                }
            } finally {
                setMediaLoading(section, false)
                // Le Top 10 et « Pour vous » se calculent une fois le chargement terminé.
                tmdb.recomputePicks(immediate = true)
            }
            }
        }
        mediaJobs[section] = job
        job.invokeOnCompletion { if (mediaJobs[section] === job) mediaJobs.remove(section) }
        return job
    }

    private val mediaJobs = mutableMapOf<Section, kotlinx.coroutines.Job>()
    private val mediaGate = kotlinx.coroutines.sync.Mutex()

    private fun setCatalog(section: Section, catalog: VodCatalog) {
        _state.update {
            if (section == Section.MOVIES) {
                it.copy(allMovies = catalog).refiltered()
            } else {
                it.copy(allSeries = catalog).refiltered()
            }
        }
        tmdb.recomputePicks()
    }

    // ---- TMDB : Top 10 et « À l'affiche » ----

    // ---- TMDB : Top 10 et « À l'affiche » ----

    fun openTmdb() = tmdb.openTmdb()

    fun closeTmdb() = tmdb.closeTmdb()

    fun saveTmdbKey(raw: String) = tmdb.saveTmdbKey(raw)

    private fun setMediaLoading(section: Section, loading: Boolean) = _state.update {
        if (section == Section.MOVIES) it.copy(moviesLoading = loading) else it.copy(seriesLoading = loading)
    }

    // ---- Pages de détail ----

    fun openMovie(item: VodItem) {
        _state.update { it.copy(detail = Detail(item = item, isSeries = false)) }
        val creds = credentials ?: return
        if (creds.type != "xtream") {
            updateDetail(item.id) { it.copy(info = DetailInfo(year = item.year), loading = false) }
            return
        }
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
        if (creds.type != "xtream") {
            val seasons = M3uClassifier.seasons(m3uEpisodes[item.id].orEmpty())
            updateDetail(item.id) {
                it.copy(
                    seasons = seasons,
                    loading = false,
                    error = if (seasons.isEmpty()) "Aucun épisode disponible pour cette série." else null,
                )
            }
            return
        }
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
                    url = detail.item.url ?: XtreamClient.movieUrl(creds, detail.item),
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
        // La file de lecture couvre toutes les saisons : l'épisode suivant est proposé même en fin de saison.
        val all = orderedEpisodes(detail.seasons, episode)
        val list = if (all.any { it.id == episode.id }) all else siblings
        startEpisodeQueue(creds, detail.item.name, detail.item.poster, list, episode, fromStart, null)
    }

    /** Épisodes de toutes les saisons, dans l'ordre (les spéciaux seulement si on regarde un spécial). */
    private fun orderedEpisodes(seasons: List<Season>, current: Episode): List<Episode> =
        seasons.filter { it.number != 0 || current.season == 0 }
            .sortedBy { it.number }
            .flatMap { it.episodes.sortedBy { e -> e.number } }

    private fun startEpisodeQueue(
        creds: Credentials,
        seriesName: String,
        poster: String?,
        list: List<Episode>,
        episode: Episode,
        fromStart: Boolean,
        startMs: Long?,
    ) {
        queue = list.map { ep ->
            VodPlayback(
                key = "e:${ep.id}",
                kind = "episode",
                title = "$seriesName · S${ep.season} E${ep.number}",
                url = ep.url ?: XtreamClient.episodeUrl(creds, ep),
                ext = ep.ext,
                poster = poster,
                startMs = 0L,
                hasNext = false,
                subtitle = ep.title,
            )
        }
        queueIndex = list.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val current = queue[queueIndex]
        _state.update {
            it.copy(
                vodPlaying = current.copy(
                    startMs = if (fromStart) 0L else startMs ?: resumePosition(current.key),
                    hasNext = queueIndex < queue.lastIndex,
                    next = nextLabel(),
                )
            )
        }
    }

    /** Reprise d'un épisode depuis la rangée « Reprendre » : on reconstruit la série pour proposer la suite. */
    private fun resumeEpisode(entry: ResumeEntry, creds: Credentials): Boolean {
        val seriesName = entry.title.substringBeforeLast(" · ", "")
        val item = CatalogDb.byName(Section.SERIES, seriesName) ?: return false
        viewModelScope.launch {
            val seasons = try {
                if (creds.type == "xtream") repo.seriesInfo(creds, item.id).second
                else M3uClassifier.seasons(m3uEpisodes[item.id].orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val key = entry.key.substringAfter(':')
            val episode = seasons.flatMap { it.episodes }.firstOrNull { it.id == key }
            if (episode == null) {
                playResumeSimple(entry, creds)
            } else {
                startEpisodeQueue(creds, seriesName, entry.poster ?: item.poster, orderedEpisodes(seasons, episode), episode, false, entry.positionMs)
            }
        }
        return true
    }

    fun playResume(entry: ResumeEntry) {
        val creds = credentials ?: return
        if (entry.kind == "episode" && resumeEpisode(entry, creds)) return
        playResumeSimple(entry, creds)
    }

    private fun playResumeSimple(entry: ResumeEntry, creds: Credentials) {
        queue = emptyList()
        queueIndex = -1
        _state.update {
            it.copy(
                vodPlaying = VodPlayback(
                    key = entry.key,
                    kind = entry.kind,
                    title = entry.title,
                    // L'URL est reconstruite avec les identifiants actuels : l'historique n'en contient pas.
                    url = m3uUrls[entry.key.substringAfter(':')]
                        ?: if (creds.type == "xtream") {
                            XtreamClient.mediaUrl(creds, entry.kind, entry.key.substringAfter(':'), entry.ext)
                        } else {
                            return
                        },
                    ext = entry.ext,
                    poster = entry.poster,
                    startMs = entry.positionMs,
                    hasNext = false,
                )
            )
        }
    }

    /** « S1 E4  Titre de l'épisode » pour l'épisode qui suit celui en cours, ou null. */
    private fun nextLabel(): String? {
        val next = queue.getOrNull(queueIndex + 1) ?: return null
        val number = next.title.substringAfterLast(" · ")
        return listOfNotNull(number, next.subtitle).joinToString("  ")
    }

    /** Fin de lecture : épisode suivant s'il y en a un, sinon retour à l'écran précédent. */
    fun onVodEnded() {
        if (queueIndex in 0 until queue.lastIndex) {
            queueIndex++
            val next = queue[queueIndex]
            _state.update {
                it.copy(vodPlaying = next.copy(hasNext = queueIndex < queue.lastIndex, next = nextLabel()))
            }
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
                store.markWatched(playback.key)
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
