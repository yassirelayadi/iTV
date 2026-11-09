package fr.poptv.player.ui

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import fr.poptv.player.R
import fr.poptv.player.data.Http

/** Réglage « Aperçu des chaînes » (activé par défaut), lu par la grille et modifié dans les Réglages. */
object PreviewPref {
    private const val FILE = "itv_ui"
    private const val KEY = "channel_preview"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * Aperçu muet d'une chaîne en direct, affiché dans une carte. Un seul aperçu existe à la fois
 * (celui de la carte qui a le focus) : le lecteur est libéré dès que la carte perd le focus ou quitte l'écran,
 * ce qui garde une seule connexion ouverte avec le fournisseur.
 */
@OptIn(UnstableApi::class)
@Composable
fun ChannelPreview(streamUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ready by remember(streamUrl) { mutableStateOf(false) }
    val player = remember(streamUrl) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 10_000, 500, 1_500)
            .build()
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client).setUserAgent("iTV/0.1")))
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(streamUrl)
                        .apply { if (streamUrl.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
                        .build(),
                )
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                ready = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready = true
            }
        }
        player.addListener(listener)
        if (player.playbackState == Player.STATE_READY) ready = true
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().alpha(if (ready) 1f else 0f),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.preview_player_view, null) as PlayerView).apply {
                    this.player = player
                }
            },
        )
    }
}
