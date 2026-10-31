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
 * Fiches films / séries et lecture (reprise, file d'épisodes, progression). Sorti d'AppViewModel :
 * partage l'état et le stockage, et reçoit les identifiants par une fonction.
 */
class VodController(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val _state: MutableStateFlow<UiState>,
    private val store: Store,
    private val repo: Repository,
    private val credentials: () -> Credentials?,
    private val friendly: (Exception) -> String,
) {
    /** Épisodes et adresses des listes M3U, remplis au chargement de la playlist. */
    var m3uEpisodes: Map<String, List<Episode>> = emptyMap()
    var m3uUrls: Map<String, String> = emptyMap()
    private var queue: List<VodPlayback> = emptyList()
    private var queueIndex = -1

    // ---- Pages de détail ----

    fun openMovie(item: VodItem) {
        _state.update { it.copy(detail = Detail(item = item, isSeries = false)) }
        val creds = credentials() ?: return
        if (creds.type != "xtream") {
            updateDetail(item.id) { it.copy(info = DetailInfo(year = item.year), loading = false) }
            return
        }
        scope.launch {
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
        val creds = credentials() ?: return
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
        scope.launch {
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
        val creds = credentials() ?: return
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
        val creds = credentials() ?: return
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
        scope.launch {
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
        val creds = credentials() ?: return
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
        // L'épisode suivant est repéré tout de suite : la file est vidée quand le lecteur se ferme.
        val finished = durationMs > 0 && positionMs >= durationMs * 95 / 100
        val nextEpisode = if (finished && playback.kind == "episode") {
            val i = queue.indexOfFirst { it.key == playback.key }
            if (i >= 0 && i < queue.lastIndex) queue[i + 1] else null
        } else {
            null
        }
        scope.launch {
            if (finished) {
                store.removeResume(playback.key)
                store.markWatched(playback.key)
                // Une série reste dans « Reprendre » tant qu'elle n'est pas finie : on y met l'épisode suivant.
                if (nextEpisode != null) {
                    store.saveResume(
                        ResumeEntry(
                            key = nextEpisode.key,
                            kind = nextEpisode.kind,
                            title = nextEpisode.title,
                            ext = nextEpisode.ext,
                            poster = nextEpisode.poster,
                            positionMs = 0,
                            durationMs = 0,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
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

}
