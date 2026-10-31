package fr.poptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import fr.poptv.player.ui.FocusMemory
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import fr.poptv.player.ui.AppleEase
import fr.poptv.player.ui.Dur
import fr.poptv.player.ui.GlideScrolling
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.poptv.player.data.Section
import fr.poptv.player.ui.appBackground
import fr.poptv.player.ui.CategoriesScreen
import fr.poptv.player.ui.CrashScreen
import fr.poptv.player.ui.ChannelsScreen
import fr.poptv.player.ui.DetailScreen
import fr.poptv.player.ui.LoginScreen
import fr.poptv.player.ui.MediaScreen
import fr.poptv.player.ui.PlayerScreen
import fr.poptv.player.ui.PopTheme
import fr.poptv.player.ui.SettingsScreen
import fr.poptv.player.ui.SplashScreen
import fr.poptv.player.ui.VodPlayerScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        setContent {
            PopTheme {
                var crash by remember { mutableStateOf(CrashReport.read(this@MainActivity)) }
                val report = crash
                if (report != null) {
                    CrashScreen(report) {
                        CrashReport.clear(this@MainActivity)
                        crash = null
                    }
                } else {
                    // Écran de démarrage : logo en fondu, puis fondu vers l'accueil (environ 1,5 s).
                    var showSplash by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(1_300)
                        showSplash = false
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        App(viewModel)
                        AnimatedVisibility(visible = showSplash, exit = fadeOut(tween(Dur.Slow, easing = AppleEase))) {
                            SplashScreen()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun App(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playing = state.playing
    val vodPlaying = state.vodPlaying
    val detail = state.detail
    val manageSection = state.manageSection
    val epg by viewModel.epg.collectAsStateWithLifecycle()
    // Positions de défilement gardées ici : en revenant d'une fiche, on retrouve l'endroit où on était.
    val liveList = rememberLazyListState()
    val moviesList = rememberLazyListState()
    val seriesList = rememberLazyListState()

    GlideScrolling {
    when (val screen = state.screen) {
        is Screen.VodPlayer -> key(screen.key) {
            VodPlayerScreen(
                playback = vodPlaying!!,
                onProgress = viewModel::saveProgress,
                onEnded = viewModel::onVodEnded,
                onNext = viewModel::onVodEnded,
                onBack = viewModel::stopVod,
            )
        }

        Screen.LivePlayer -> PlayerScreen(
            channel = playing!!,
            epg = epg[playing.id],
            onNeedEpg = viewModel::requestEpg,
            onZap = viewModel::zap,
            onBack = viewModel::stop,
            channels = state.channels,
            categories = state.categories,
            favorites = state.favorites,
            epgAll = epg,
            onPlay = viewModel::play,
        )

        Screen.Login -> LoginScreen(
            loading = state.loading,
            error = state.error,
            canCancel = state.allChannels.isNotEmpty(),
            onSubmit = viewModel::login,
            onCancel = viewModel::cancelLogin,
        )

        Screen.Loading -> Box(
            modifier = Modifier.fillMaxSize().appBackground(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        Screen.TmdbKey -> fr.poptv.player.ui.TmdbKeyScreen(
            configured = state.tmdbConfigured,
            usesDefault = state.tmdbUsesDefault,
            busy = state.tmdbBusy,
            error = state.tmdbError,
            onSave = viewModel::saveTmdbKey,
            onBack = viewModel::closeTmdb,
        )

        is Screen.Categories -> CategoriesScreen(
            state = state,
            section = screen.section,
            onSetHidden = viewModel::setCategoriesHidden,
            onBack = viewModel::closeCategories,
        )

        Screen.Settings -> SettingsScreen(
            state = state,
            onOpenCategories = viewModel::openCategories,
            onRefresh = {
                viewModel.refresh()
                viewModel.closeSettings()
            },
            onAccount = viewModel::showLogin,
            onTmdb = viewModel::openTmdb,
            onLogout = viewModel::logout,
            onBack = viewModel::closeSettings,
        )

        is Screen.Browse -> {
            // La liste reste affichée sous la fiche : on retrouve exactement la même position et la même
            // vignette (focus) au retour. La fiche est une couche par-dessus, avec zoom doux.
            val screen = when {
                state.section == Section.LIVE -> 0
                state.section == Section.MOVIES -> 1
                else -> 2
            }
            // Retour : depuis Films ou Séries on revient aux chaînes ; depuis les chaînes, un second appui rapide quitte.
            // (Les écrans et la fiche, plus bas, ont leurs propres retours, prioritaires quand ils sont actifs.)
            val context = androidx.compose.ui.platform.LocalContext.current
            var lastBack by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
            val atRoot = detail == null && state.selectedCategory == CAT_ALL && state.query.isEmpty()
            BackHandler(enabled = atRoot && state.section != Section.LIVE) { viewModel.selectSection(Section.LIVE) }
            BackHandler(enabled = atRoot && state.section == Section.LIVE) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBack < 2_000) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    lastBack = now
                    android.widget.Toast.makeText(context, "Appuie encore sur Retour pour quitter", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            val lastDetail = remember { arrayOfNulls<fr.poptv.player.data.Detail>(1) }
            if (detail != null) lastDetail[0] = detail
            // Retour d'une fiche : la vignette ouverte reprend le focus.
            var detailWasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(detail != null) {
                if (detail == null && detailWasOpen) {
                    FocusMemory.requestRestore()
                    delay(700)
                    FocusMemory.restore = false
                }
                detailWasOpen = detail != null
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Un seul écran à la fois (pas de fondu enchaîné qui garderait l'ancien écran caché :
                    // la télécommande pourrait y perdre le curseur). Le nouvel écran apparaît en fondu.
                    androidx.compose.runtime.key(screen) {
                        val target = screen
                        when (target) {
                            0 -> ChannelsScreen(
                                state = state,
                                epg = epg,
                                onNeedEpg = viewModel::requestEpg,
                                onSection = viewModel::selectSection,
                                onSelectCategory = viewModel::selectCategory,
                                onQuery = viewModel::setQuery,
                                onPlay = viewModel::play,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onSettings = viewModel::openSettings,
                                listState = liveList,
                            )

                            else -> MediaScreen(
                                state = state,
                                onSection = viewModel::selectSection,
                                onSelectCategory = viewModel::selectCategory,
                                onQuery = viewModel::setQuery,
                                onSettings = viewModel::openSettings,
                                onOpen = { item ->
                                    if (state.section == Section.MOVIES) viewModel.openMovie(item) else viewModel.openSeries(item)
                                },
                                onResume = viewModel::playResume,
                                onToggleFavorite = viewModel::toggleFavoriteVod,
                                onRetry = viewModel::reloadMedia,
                                listState = if (target == 1) moviesList else seriesList,
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = detail != null,
                    enter = fadeIn(tween(Dur.Normal, easing = AppleEase)) + scaleIn(tween(Dur.Normal, easing = AppleEase), initialScale = 0.94f),
                    exit = fadeOut(tween(Dur.Normal, easing = AppleEase)) + scaleOut(tween(Dur.Normal, easing = AppleEase), targetScale = 1.06f),
                ) {
                    val shownDetail = lastDetail[0]
                    if (shownDetail != null) {
                        // La fiche garde le curseur à l'intérieur : la télécommande ne peut pas aller dans la liste dessous.
                        Box(modifier = Modifier.fillMaxSize().focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
                        DetailScreen(
                            detail = shownDetail,
                            resumeFor = { key -> state.resume.firstOrNull { it.key == key } },
                            watched = state.watched,
                            favorite = state.favVod.contains((if (shownDetail.isSeries) "s:" else "m:") + shownDetail.item.id),
                            onToggleFavorite = { viewModel.toggleFavoriteVod((if (shownDetail.isSeries) "s:" else "m:") + shownDetail.item.id) },
                            onPlayMovie = { fromStart -> viewModel.playMovie(fromStart) },
                            onPlayEpisode = { episode, siblings -> viewModel.playEpisode(episode, siblings) },
                            onBack = viewModel::closeDetail,
                            categoryName = (if (shownDetail.isSeries) state.allSeries else state.allMovies)
                                .categories.firstOrNull { it.id == shownDetail.item.categoryId }?.name,
                            backdrop = (state.picks.featured + state.picks.topMovies + state.picks.topSeries + state.picks.featuredSeries)
                                .firstOrNull { it.item.id == shownDetail.item.id }?.backdrop,
                        )
                        }
                    }
                }
            }
        }
    }
    }
}
