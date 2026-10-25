package fr.poptv.player.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.foundation.layout.size
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

/** La carte « Épisode suivant » apparaît pendant les 45 dernières secondes et lance la suite après 8 s. */
private const val NEXT_COUNTDOWN = 8
private const val CREDITS_WINDOW_MS = 45_000L

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

    // Options : panneau latéral (touche Bas).
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var panelOpen by remember { mutableStateOf(false) }

    // Barre de progression : fine au repos, épaisse pendant un saut.
    var seekTick by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }

    // Carte « Épisode suivant » pendant le générique.
    var nextDismissed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(NEXT_COUNTDOWN) }
    val nextFocus = remember { FocusRequester() }
    val inCredits = playback.hasNext && !nextDismissed && error == null &&
        duration > 300_000L && duration - position <= CREDITS_WINDOW_MS

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

    // À la fermeture du panneau, le focus revient à l'écran de lecture.
    LaunchedEffect(panelOpen) {
        if (!panelOpen) runCatching { focusRequester.requestFocus() }
    }

    // La barre grossit pendant 2,5 s après chaque saut.
    LaunchedEffect(seekTick) {
        if (seekTick > 0) {
            seeking = true
            delay(2_500)
            seeking = false
        }
    }

    // Générique : la carte prend le focus, puis l'épisode suivant démarre après le compte à rebours.
    LaunchedEffect(inCredits) {
        if (inCredits) {
            delay(80)
            if (!panelOpen) runCatching { nextFocus.requestFocus() }
            var left = NEXT_COUNTDOWN
            while (left > 0) {
                countdown = left
                delay(1_000)
                if (!paused) left--
            }
            latestOnNext()
        } else if (!panelOpen) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        latestOnProgress(playback, player.currentPosition, player.duration.coerceAtLeast(0L))
        player.pause()
    }

    BackHandler {
        when {
            panelOpen -> panelOpen = false
            inCredits -> nextDismissed = true
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
        seekTick++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .vodTouch(
                onTap = {
                    if (panelOpen) {
                        panelOpen = false
                    } else if (error != null) {
                        error = null
                        buffering = true
                        player.prepare()
                        player.playWhenReady = true
                    } else if (overlayVisible && !paused) {
                        player.pause()
                        overlayTick++
                    } else if (paused) {
                        player.play()
                        overlayTick++
                    } else {
                        overlayTick++
                    }
                },
                onSeek = { dir -> seekBy(dir * 10_000L) },
                onLongPress = {
                    overlayTick++
                    panelOpen = true
                },
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Panneau ouvert ou carte « Épisode suivant » affichée : leurs boutons gèrent les touches.
                if (panelOpen || inCredits) return@onKeyEvent false
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
                        panelOpen = true
                        true
                    }
                    Key.DirectionUp, Key.Menu -> {
                        overlayTick++
                        if (event.key == Key.Menu) panelOpen = true
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
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(horizontal = 28.dp, vertical = 10.dp),
            )
        }

        val showOverlay = overlayVisible || paused || panelOpen

        // Au repos, seule une fine ligne de progression reste visible en bas de l'écran.
        if (!showOverlay && duration > 0) {
            val restFraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x40FFFFFF)),
            ) {
                Box(modifier = Modifier.fillMaxWidth(restFraction).fillMaxHeight().background(AccentBrush))
            }
        }

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val barHeight by animateDpAsState(if (seeking) 12.dp else 5.dp, tween(150), label = "progressHeight")
            val remaining = (duration - position).coerceAtLeast(0L)
            val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
            val endClock = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(System.currentTimeMillis() + (remaining / speed).toLong()))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = if (LocalCompact.current) 24.dp else 56.dp, vertical = if (LocalCompact.current) 16.dp else 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    playback.title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
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
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (LocalCompact.current) 30.dp else 14.dp)
                        .pointerInput(duration) {
                            if (duration <= 0L) return@pointerInput
                            detectTapGestures { offset ->
                                val target = (touchFraction(offset.x, size) * duration).toLong()
                                player.seekTo(target)
                                position = target
                                overlayTick++
                                seekTick++
                            }
                        }
                        .pointerInput(duration) {
                            if (duration <= 0L) return@pointerInput
                            detectHorizontalDragGestures { change, _ ->
                                val target = (touchFraction(change.position.x, size) * duration).toLong()
                                player.seekTo(target)
                                position = target
                                overlayTick++
                                seekTick++
                            }
                        },
                ) {
                    val trackWidth = maxWidth
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .height(barHeight)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x40FFFFFF)),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AccentBrush))
                    }
                    if (seeking) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = trackWidth * fraction - 7.dp)
                                .size(14.dp)
                                .background(Color.White, CircleShape),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = TextSecondary, fontSize = 14.sp)
                    Text(
                        if (duration > 0) "−${formatTime(remaining)}  ·  Fini à $endClock" else "",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                }
                if (LocalCompact.current) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Options", onClick = { panelOpen = true; overlayTick++ })
                        Text(
                            "Toucher : pause  ·  Double toucher : ±10 s",
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
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

        // Carte « Épisode suivant » pendant le générique.
        AnimatedVisibility(
            visible = inCredits && !panelOpen,
            enter = fadeIn(tween(350, easing = AppleEase)) + slideInVertically(tween(450, easing = AppleEase)) { it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(250)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (LocalCompact.current) 16.dp else 56.dp, bottom = if (LocalCompact.current) 110.dp else 150.dp),
        ) {
            NextEpisodeCard(
                label = playback.next,
                poster = playback.poster,
                seconds = countdown,
                total = NEXT_COUNTDOWN,
                onPlay = { latestOnNext() },
                onClose = { nextDismissed = true },
                playFocus = nextFocus,
            )
        }

        // Options : panneau latéral sur toute la hauteur.
        AnimatedVisibility(
            visible = panelOpen,
            enter = fadeIn(tween(300, easing = AppleEase)),
            exit = fadeOut(tween(220)),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x59000000)))
        }
        AnimatedVisibility(
            visible = panelOpen,
            enter = fadeIn(tween(300, easing = AppleEase)) + slideInHorizontally(tween(420, easing = AppleEase)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(260)) { it / 2 },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val audio = audioOptions(player, tracks)
            val subs = subtitleOptions(player, tracks)
            val quality = qualityOptions(player, tracks)
            val sections = buildList {
                if (playback.hasNext) {
                    add(PanelSection("Épisode suivant", AppIcons.SkipNext, listOf(TrackOption("Lire maintenant", false) { latestOnNext() })))
                }
                if (audio.size > 1) add(PanelSection("Audio", AppIcons.Audio, audio))
                if (subs.isNotEmpty()) add(PanelSection("Sous-titres", AppIcons.Subtitles, subs))
                if (quality.isNotEmpty()) add(PanelSection("Qualité", AppIcons.Quality, quality))
                add(PanelSection("Vitesse", AppIcons.Speed, speedOptions(player)))
            }
            PlayerSidePanel(
                sections = sections,
                onClose = { panelOpen = false },
            )
        }
    }
}
