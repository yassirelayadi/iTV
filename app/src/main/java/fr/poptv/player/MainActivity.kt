package fr.poptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                        AnimatedVisibility(visible = showSplash, exit = fadeOut(tween(450))) {
                            SplashScreen()
                        }
                    }
                }
            }
        }
    }
}

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

    when {
        vodPlaying != null -> key(vodPlaying.key) {
            VodPlayerScreen(
                playback = vodPlaying,
                onProgress = viewModel::saveProgress,
                onEnded = viewModel::onVodEnded,
                onNext = viewModel::onVodEnded,
                onBack = viewModel::stopVod,
            )
        }

        playing != null -> PlayerScreen(
            channel = playing,
            epg = epg[playing.id],
            onNeedEpg = viewModel::requestEpg,
            onZap = viewModel::zap,
            onBack = viewModel::stop,
        )

        state.needsLogin -> LoginScreen(
            loading = state.loading,
            error = state.error,
            canCancel = state.allChannels.isNotEmpty(),
            onSubmit = viewModel::login,
            onCancel = viewModel::cancelLogin,
        )

        state.loading && state.allChannels.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize().appBackground(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        state.tmdbOpen -> fr.poptv.player.ui.TmdbKeyScreen(
            configured = state.tmdbConfigured,
            busy = state.tmdbBusy,
            error = state.tmdbError,
            onSave = viewModel::saveTmdbKey,
            onBack = viewModel::closeTmdb,
        )

        manageSection != null -> CategoriesScreen(
            state = state,
            section = manageSection,
            onSetHidden = viewModel::setCategoriesHidden,
            onBack = viewModel::closeCategories,
        )

        state.settingsOpen -> SettingsScreen(
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

        detail != null -> DetailScreen(
            detail = detail,
            resumeFor = { key -> state.resume.firstOrNull { it.key == key } },
            watched = state.watched,
            onPlayMovie = { fromStart -> viewModel.playMovie(fromStart) },
            onPlayEpisode = { episode, siblings -> viewModel.playEpisode(episode, siblings) },
            onBack = viewModel::closeDetail,
        )

        state.section == Section.LIVE -> ChannelsScreen(
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
            listState = if (state.section == Section.MOVIES) moviesList else seriesList,
        )
    }
}
