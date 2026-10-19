package fr.poptv.player.ui

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import fr.poptv.player.VodPlayback
import fr.poptv.player.data.Http
import kotlinx.coroutines.delay

/** Lecteur de films et d'épisodes : pause, saut de 10 s, barre de progression, reprise. */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    playback: VodPlayback,
    onProgress: (VodPlayback, Long, Long) -> Unit,
    onEnded: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnEnded by rememberUpdatedState(onEnded)

    val player = remember {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client).setUserAgent("iTV/0.1"))
            )
            .build()
    }

    var buffering by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(playback.startMs) }
    var duration by remember { mutableLongStateOf(0L) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) error = null
                if (playbackState == Player.STATE_ENDED) latestOnEnded()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlayerError(e: PlaybackException) {
                buffering = false
                error = "Lecture impossible (${e.errorCodeName}). OK pour réessayer."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Dernière position mémorisée (sert à « Reprendre »).
            latestOnProgress(playback, player.currentPosition, player.duration.coerceAtLeast(0L))
            player.release()
        }
    }

    LaunchedEffect(Unit) {
        player.setMediaItem(MediaItem.fromUri(playback.url), playback.startMs)
        player.prepare()
        player.playWhenReady = true
    }

    // Position affichée, et sauvegarde toutes les 10 secondes.
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            delay(500)
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            ticks++
            if (ticks >= 20) {
                ticks = 0
                latestOnProgress(playback, position, duration)
            }
        }
    }

    LaunchedEffect(overlayTick) {
        overlayVisible = true
        delay(4_000)
        overlayVisible = false
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        latestOnProgress(playback, player.currentPosition, player.duration.coerceAtLeast(0L))
        player.pause()
    }

    BackHandler(onBack = onBack)

    fun seekBy(deltaMs: Long) {
        val total = player.duration
        var target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        if (total > 0) target = target.coerceAtMost(total)
        player.seekTo(target)
        position = target
        overlayTick++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        seekBy(-10_000)
                        true
                    }
                    Key.DirectionRight -> {
                        seekBy(10_000)
                        true
                    }
                    Key.MediaRewind -> {
                        seekBy(-30_000)
                        true
                    }
                    Key.MediaFastForward -> {
                        seekBy(30_000)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (error != null) {
                            error = null
                            buffering = true
                            player.prepare()
                            player.playWhenReady = true
                        } else if (player.playWhenReady) {
                            player.pause()
                        } else {
                            player.play()
                        }
                        overlayTick++
                        true
                    }
                    Key.MediaPlay -> {
                        player.play()
                        overlayTick++
                        true
                    }
                    Key.MediaPause -> {
                        player.pause()
                        overlayTick++
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        overlayTick++
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setShutterBackgroundColor(AndroidColor.BLACK)
                }
            },
        )

        if (buffering && error == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        error?.let {
            Text(
                it,
                color = ErrorColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }

        if (paused && error == null && !buffering) {
            Text(
                "Pause",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(horizontal = 28.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible || paused,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 56.dp, vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    playback.title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x40FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(Color.White),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = TextSecondary, fontSize = 14.sp)
                    Text(if (duration > 0) formatTime(duration) else "", color = TextSecondary, fontSize = 14.sp)
                }
                Text(
                    "Gauche / Droite : 10 s  ·  OK : pause",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
