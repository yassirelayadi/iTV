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

/** Un catalogue en cache de moins de 6 heures n'est pas retéléchargé. */
private const val MEDIA_CACHE_TTL_MS = 6L * 60 * 60 * 1000

/**
 * Téléchargement des catalogues films et séries dans la base (CatalogDb) : un seul à la fois,
 * sans doublon quand on change de section ou de catégories pendant un chargement.
 */
class MediaLoader(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val _state: MutableStateFlow<UiState>,
    private val repo: Repository,
    private val tmdb: TmdbController,
    private val credentials: () -> Credentials?,
    private val friendly: (Exception) -> String,
) {
    fun load(section: Section, force: Boolean): kotlinx.coroutines.Job? {
        val creds = credentials() ?: return null
        if (creds.type != "xtream") return null // M3U : le catalogue est déjà trié au chargement
        val s = _state.value
        val isMovies = section == Section.MOVIES
        val loaded = (if (isMovies) s.allMovies else s.allSeries).size > 0
        val busy = if (isMovies) s.moviesLoading else s.seriesLoading
        // Un seul téléchargement à la fois par section : des appuis rapides (afficher / masquer des
        // catégories) ne doivent pas lancer plusieurs téléchargements du catalogue en parallèle.
        if (busy || mediaJobs[section]?.isActive == true || (loaded && !force)) return null

        val job = scope.launch {
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


    private fun setMediaLoading(section: Section, loading: Boolean) = _state.update {
        if (section == Section.MOVIES) it.copy(moviesLoading = loading) else it.copy(seriesLoading = loading)
    }
}
