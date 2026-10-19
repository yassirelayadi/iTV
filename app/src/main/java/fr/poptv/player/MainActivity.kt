package fr.poptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.poptv.player.data.Section
import fr.poptv.player.ui.Bg
import fr.poptv.player.ui.CategoriesScreen
import fr.poptv.player.ui.CrashScreen
import fr.poptv.player.ui.ChannelsScreen
import fr.poptv.player.ui.DetailScreen
import fr.poptv.player.ui.LoginScreen
import fr.poptv.player.ui.MediaScreen
import fr.poptv.player.ui.PlayerScreen
import fr.poptv.player.ui.PopTheme
import fr.poptv.player.ui.SettingsScreen
import fr.poptv.player.ui.VodPlayerScreen

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
                    App(viewModel)
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
            modifier = Modifier.fillMaxSize().background(Bg),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

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
            onLogout = viewModel::logout,
            onBack = viewModel::closeSettings,
        )

        detail != null -> DetailScreen(
            detail = detail,
            resumeFor = { key -> state.resume.firstOrNull { it.key == key } },
            onPlayMovie = { fromStart -> viewModel.playMovie(fromStart) },
            onPlayEpisode = { episode, siblings -> viewModel.playEpisode(episode, siblings) },
            onBack = viewModel::closeDetail,
        )

        state.section == Section.LIVE -> ChannelsScreen(
            state = state,
            onSection = viewModel::selectSection,
            onSelectCategory = viewModel::selectCategory,
            onQuery = viewModel::setQuery,
            onPlay = viewModel::play,
            onToggleFavorite = viewModel::toggleFavorite,
            onSettings = viewModel::openSettings,
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
        )
    }
}
