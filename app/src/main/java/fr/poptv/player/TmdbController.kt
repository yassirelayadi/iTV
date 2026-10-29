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

/**
 * TMDB : tendances (Top 10, « À l'affiche »), sélections et suggestions « Pour vous ».
 * Sorti d'AppViewModel : ne touche que l'état partagé et le stockage.
 */
class TmdbController(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val _state: MutableStateFlow<UiState>,
    private val store: Store,
    private val friendly: (Exception) -> String,
) {
    private var tmdbKeyValue: String? = null
    private var trends: TmdbTrends? = null
    private var picksJob: kotlinx.coroutines.Job? = null

    /** Charge les sélections en cache, lance les calculs « Pour vous » et suit la clé TMDB. */
    fun start() {
        // Dernières sélections TMDB : le Top 10 et les bandeaux s'affichent tout de suite, avant tout réseau.
        scope.launch {
            store.loadPicks()?.let { cached ->
                _state.update { if (it.picks.topMovies.isEmpty() && it.picks.topSeries.isEmpty()) it.copy(picks = cached) else it }
            }
        }
        scope.launch {
            store.loadForYou()?.let { cached ->
                _state.update {
                    if (it.forYouMovies.isEmpty() && it.forYouSeries.isEmpty()) it.copy(forYouMovies = cached.movies, forYouSeries = cached.series) else it
                }
            }
        }
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _state
                .map { ForYouInput(it.moviesLoading || it.seriesLoading || it.manageSection != null, it.movies, it.series, it.favVod, it.resume, it.watched) }
                .distinctUntilChanged { a, b -> a.busy == b.busy && a.movies === b.movies && a.series === b.series && a.fav == b.fav && a.resume == b.resume && a.watched == b.watched }
                .collectLatest { input ->
                    delay(400) // un calcul à la fois, après une courte pause (rafales de changements)
                    if (input.busy) return@collectLatest // pas pendant un téléchargement de catalogue
                    // Les suggestions sont un bonus : une erreur de calcul ne doit jamais fermer l'appli.
                    try {
                        computeForYou(input)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Pas de suggestions cette fois.
                    }
                }
        }
        scope.launch {
            store.tmdbKey.collect { own ->
                val default = BuildConfig.TMDB_DEFAULT_KEY.takeIf { it.isNotBlank() }
                tmdbKeyValue = own ?: default
                // Tendances déjà téléchargées (moins de 6 h) : disponibles tout de suite, sans réseau.
                trends = store.loadTrends()?.takeIf { System.currentTimeMillis() - it.fetchedAt < 6 * 3_600_000L }
                _state.update {
                    it.copy(tmdbConfigured = own != null, tmdbUsesDefault = own == null && default != null)
                }
                if (tmdbKeyValue == null) _state.update { it.copy(picks = TmdbPicks()) }
                else ensureTmdb()
            }
        }
    }

    fun openTmdb() = _state.update { it.copy(tmdbOpen = true, tmdbError = null) }

    fun closeTmdb() = _state.update { it.copy(tmdbOpen = false, tmdbError = null, tmdbBusy = false) }

    /** Enregistre (après vérification) ou efface la clé TMDB. Une clé vide supprime la configuration. */
    fun saveTmdbKey(raw: String) {
        val key = raw.trim().trim('"', '\'')
        scope.launch {
            if (key.isEmpty()) {
                store.saveTmdbKey(null)
                _state.update { it.copy(tmdbOpen = false, tmdbError = null) }
                return@launch
            }
            _state.update { it.copy(tmdbBusy = true, tmdbError = null) }
            try {
                val t = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TmdbClient.trends(key) }
                store.saveTmdbKey(key)
                trends = t
                _state.update { it.copy(tmdbBusy = false, tmdbOpen = false, tmdbError = null) }
                recomputePicks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(tmdbBusy = false, tmdbError = friendly(e)) }
            }
        }
    }

    /** Charge les tendances si besoin (au plus toutes les 6 heures), puis les rapproche du catalogue. */
    fun ensureTmdb() {
        val key = tmdbKeyValue ?: return
        val known = trends
        if (known != null && System.currentTimeMillis() - known.fetchedAt < 6 * 3_600_000L) {
            recomputePicks()
            return
        }
        scope.launch {
            try {
                val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TmdbClient.trends(key) }
                trends = fresh
                runCatching { store.saveTrends(fresh) }
                recomputePicks(immediate = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pas de tendances pour cette fois : les rangées habituelles restent affichées.
            }
        }
    }

    fun recomputePicks(immediate: Boolean = false) {
        val t = trends ?: return
        picksJob?.cancel()
        picksJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            // Pause : si le catalogue change encore, on ne recalcule qu'une fois à la fin.
            if (!immediate) delay(700)
            val s = _state.value
            // Pas de gros calcul pendant qu'un catalogue se télécharge : il est relancé à la fin du chargement.
            if (s.moviesLoading || s.seriesLoading) return@launch
            if (s.movies.size == 0 && s.series.size == 0) return@launch
            try {
                val movieLookup = TmdbMatcher.lookupIn(Section.MOVIES, t.moviesNow + t.moviesWeek, s.hiddenMovies)
                val seriesLookup = TmdbMatcher.lookupIn(Section.SERIES, t.seriesWeek, s.hiddenSeries)
                val picks = TmdbMatcher.match(t, movieLookup, seriesLookup)
                _state.update { it.copy(picks = picks) }
                runCatching { store.savePicks(picks) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Pas de Top 10 cette fois (mémoire ou donnée inattendue) : l'appli continue.
            }
        }
    }

    private class ForYouInput(
        val busy: Boolean,
        val movies: VodCatalog,
        val series: VodCatalog,
        val fav: Set<String>,
        val resume: List<ResumeEntry>,
        val watched: Set<String>,
    )

    private class Seed(val item: VodItem, val weight: Float)

    private var forYouJob: kotlinx.coroutines.Job? = null
    private val recCache = java.util.concurrent.ConcurrentHashMap<String, List<fr.poptv.player.data.TmdbTitle>>()
    private val tmdbIdCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var tmdbForYouMovies: List<VodItem> = emptyList()
    private var tmdbForYouSeries: List<VodItem> = emptyList()

    /**
     * « Pour vous » : titres de départ pondérés par le comportement (ma liste, terminés, repris, abandonnés),
     * puis recommandations de TMDB pour ces titres, retrouvées dans le catalogue. En attendant TMDB (ou sans clé),
     * on propose des titres des mêmes catégories.
     */
    private fun computeForYou(input: ForYouInput) {
        val now = System.currentTimeMillis()
        val hiddenMovies = _state.value.hiddenMovies
        val hiddenSeries = _state.value.hiddenSeries

        val movieW = LinkedHashMap<String, Float>()
        val seriesW = LinkedHashMap<String, Float>()
        fun add(map: MutableMap<String, Float>, id: String, w: Float) { map.merge(id, w, Float::plus) }
        for (key in input.fav) {
            if (key.startsWith("m:")) add(movieW, key.substring(2), ForYou.seedWeight(true, false, null, null))
            if (key.startsWith("s:")) add(seriesW, key.substring(2), ForYou.seedWeight(true, false, null, null))
        }
        for (key in input.watched) {
            if (key.startsWith("m:")) add(movieW, key.substring(2), ForYou.seedWeight(false, true, null, null))
        }
        for (e in input.resume) {
            val progress = if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else null
            val age = if (e.updatedAt > 0) (now - e.updatedAt) / 86_400_000f else null
            val w = ForYou.seedWeight(false, false, progress, age)
            if (e.kind == "movie") add(movieW, e.key.substringAfter(':'), w)
            else CatalogDb.byName(Section.SERIES, e.title.substringBeforeLast(" · ", e.title))?.let { add(seriesW, it.id, w) }
        }
        val moviesById = CatalogDb.byIds(Section.MOVIES, movieW.keys).associateBy { it.id }
        val seriesById = CatalogDb.byIds(Section.SERIES, seriesW.keys).associateBy { it.id }
        val movieSeeds = movieW.mapNotNull { (id, w) -> moviesById[id]?.takeIf { w > 0f }?.let { Seed(it, w) } }
        val seriesSeeds = seriesW.mapNotNull { (id, w) -> seriesById[id]?.takeIf { w > 0f }?.let { Seed(it, w) } }
        // Ce qui est déjà vu, abandonné ou dans la liste n'est jamais re-proposé.
        val movieSkip = movieW.keys
        val seriesSkip = seriesW.keys

        // Candidats : seulement les catégories des titres de départ (le reste ne marque aucun point).
        val fbMovies = ForYou.compute(
            CatalogDb.candidates(Section.MOVIES, hiddenMovies, movieSeeds.map { it.item.categoryId }.toSet(), 6000),
            movieSeeds.map { it.item }, movieSkip,
        )
        val fbSeries = ForYou.compute(
            CatalogDb.candidates(Section.SERIES, hiddenSeries, seriesSeeds.map { it.item.categoryId }.toSet(), 6000),
            seriesSeeds.map { it.item }, seriesSkip,
        )
        publishForYou(fbMovies, fbSeries)

        val key = tmdbKeyValue ?: return
        forYouJob?.cancel()
        forYouJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val m = if (movieSeeds.size >= 2) tmdbRecommended(key, movieSeeds, false, hiddenMovies, movieSkip) else emptyList()
                val s = if (seriesSeeds.size >= 2) tmdbRecommended(key, seriesSeeds, true, hiddenSeries, seriesSkip) else emptyList()
                tmdbForYouMovies = m
                tmdbForYouSeries = s
                publishForYou(fbMovies, fbSeries)
                val st = _state.value
                runCatching { store.saveForYou(ForYouCache(st.forYouMovies, st.forYouSeries)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pas de recommandations TMDB cette fois : les suggestions par catégorie restent affichées.
            }
        }
    }

    private fun publishForYou(fbMovies: List<VodItem>, fbSeries: List<VodItem>) {
        val movies = (tmdbForYouMovies.take(14) + fbMovies).distinctBy { it.id }.take(20)
        val series = (tmdbForYouSeries.take(14) + fbSeries).distinctBy { it.id }.take(20)
        _state.update { it.copy(forYouMovies = movies, forYouSeries = series) }
    }

    /** Recommandations TMDB des 6 titres de départ les plus pesants, retrouvées dans le catalogue. */
    private suspend fun tmdbRecommended(
        key: String,
        seeds: List<Seed>,
        tv: Boolean,
        hidden: Set<String>,
        skip: Set<String>,
    ): List<VodItem> = coroutineScope {
        val top = seeds.sortedByDescending { it.weight }.take(6)
        val gate = Semaphore(3)
        val lists = top.map { seed ->
            async { gate.withPermit { seed.weight to recsFor(key, seed.item, tv) } }
        }.awaitAll()
        val ranked = ForYou.rank(lists)
        val index = TmdbMatcher.lookupIn(if (tv) Section.SERIES else Section.MOVIES, ranked, hidden)
        val seen = HashSet<String>()
        val out = ArrayList<VodItem>()
        for (t in ranked) {
            val item = TmdbMatcher.find(index, t) ?: continue
            if (item.id in skip || !seen.add(item.id)) continue
            out.add(item)
            if (out.size >= 14) break
        }
        out
    }

    private fun recsFor(key: String, item: VodItem, tv: Boolean): List<fr.poptv.player.data.TmdbTitle> {
        val cacheKey = (if (tv) "s:" else "m:") + item.id
        recCache[cacheKey]?.let { return it }
        return try {
            val tmdbId = tmdbIdCache[cacheKey]
                ?: TmdbClient.findId(key, TmdbMatcher.norm(item.name), item.year, tv)?.also { tmdbIdCache[cacheKey] = it }
                ?: return emptyList()
            TmdbClient.recommendations(key, tmdbId, tv).also { recCache[cacheKey] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

}
