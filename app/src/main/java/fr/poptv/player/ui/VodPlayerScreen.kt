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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Tracks
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
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnNext by rememberUpdatedState(onNext)

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

    // Options : rangée de boutons (touche Bas) et panneau de choix.
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var optionsActive by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<OptionsMenu?>(null) }
    var lastButton by remember { mutableIntStateOf(0) }
    val buttonFocus = remember { List(6) { FocusRequester() } }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) error = null
                if (playbackState == Player.STATE_ENDED) latestOnEnded()
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
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

    // Le focus suit l'ouverture et la fermeture de la rangée d'options et du panneau.
    LaunchedEffect(optionsActive) {
        if (optionsActive) {
            delay(60)
            runCatching { buttonFocus[0].requestFocus() }
        } else {
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(menu) {
        if (menu == null && optionsActive) {
            delay(60)
            runCatching { buttonFocus[lastButton.coerceIn(0, buttonFocus.lastIndex)].requestFocus() }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        latestOnProgress(playback, player.currentPosition, player.duration.coerceAtLeast(0L))
        player.pause()
    }

    BackHandler {
        when {
            menu != null -> menu = null
            optionsActive -> optionsActive = false
            else -> onBack()
        }
    }

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
                // Panneau ouvert : ses boutons gèrent les touches.
                if (menu != null) return@onKeyEvent false
                // Rangée d'options affichée : Haut la referme, le reste déplace le focus.
                if (optionsActive) {
                    if (event.key == Key.DirectionUp) {
                        optionsActive = false
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }
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
                    Key.DirectionDown -> {
                        overlayTick++
                        optionsActive = true
                        true
                    }
                    Key.DirectionUp, Key.Menu -> {
                        overlayTick++
                        if (event.key == Key.Menu) optionsActive = true
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
            visible = overlayVisible || paused || optionsActive || menu != null,
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
                if (playback.subtitle != null) {
                    Text(
                        playback.subtitle,
                        color = TextSecondary,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                if (optionsActive) {
                    val audio = audioOptions(player, tracks)
                    val subs = subtitleOptions(player, tracks)
                    val quality = qualityOptions(player, tracks)
                    val buttons = buildList<Triple<String, ImageVector, () -> Unit>> {
                        if (playback.hasNext) add(Triple("Épisode suivant", AppIcons.SkipNext, { latestOnNext() }))
                        if (audio.size > 1) add(Triple("Audio", AppIcons.Audio, { menu = OptionsMenu.AUDIO }))
                        if (subs.isNotEmpty()) add(Triple("Sous-titres", AppIcons.Subtitles, { menu = OptionsMenu.SUBTITLES }))
                        if (quality.isNotEmpty()) add(Triple("Qualité", AppIcons.Quality, { menu = OptionsMenu.QUALITY }))
                        add(Triple("Vitesse", AppIcons.Speed, { menu = OptionsMenu.SPEED }))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        buttons.forEachIndexed { index, (label, icon, action) ->
                            ControlButton(
                                text = label,
                                icon = icon,
                                onClick = {
                                    lastButton = index
                                    action()
                                },
                                modifier = Modifier.focusRequester(buttonFocus[index]),
                            )
                        }
                    }
                } else {
                    Text(
                        "Gauche / Droite : 10 s  ·  OK : pause  ·  Bas : options",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        menu?.let { current ->
            val options = when (current) {
                OptionsMenu.AUDIO -> audioOptions(player, tracks)
                OptionsMenu.SUBTITLES -> subtitleOptions(player, tracks)
                OptionsMenu.QUALITY -> qualityOptions(player, tracks)
                OptionsMenu.SPEED -> speedOptions(player)
            }
            OptionsPanel(
                title = current.title,
                options = options,
                onClose = { menu = null },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 56.dp, bottom = 150.dp),
            )
        }
    }
}
