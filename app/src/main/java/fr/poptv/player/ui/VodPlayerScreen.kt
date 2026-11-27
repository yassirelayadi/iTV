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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    // Mesure du débit réseau, affichée pendant les chargements pour savoir si le flux arrive assez vite.
    val diag = remember { LoadDiag(basePositionMs = playback.startMs) }
    val bandwidthMeter = remember { androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context).build() }
    // Préchargement sur le stockage : jusqu'à 1,5 Go d'avance (moitié de l'espace libre au plus), une seule connexion.
    val ahead = remember {
        val dir = java.io.File(context.cacheDir, "precharge").apply { listFiles()?.forEach { it.delete() } }
        val maxAhead = (dir.parentFile?.usableSpace ?: 0L).div(2).coerceIn(200L * 1024 * 1024, 1536L * 1024 * 1024)
        fr.poptv.player.data.AheadDataSourceFactory(dir, maxAhead) { n ->
            diag.total.addAndGet(n.toLong())
            diag.sinceBase.addAndGet(n.toLong())
        }
    }
    val player = remember {
        // Mémoire tampon : 2 min d'avance, plafonnée à 160 Mo. Minimum = maximum : le lecteur lit le flux en continu
        // au lieu de s'arrêter une minute puis de reprendre. Sinon la connexion reste inactive pendant ces pauses,
        // certains serveurs IPTV la ferment, et il faut se reconnecter (chargement) toutes les minutes environ.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            // Reprise rapide après un chargement (3 s d'avance au démarrage, 4 s après un arrêt) : des arrêts courts
            // plutôt que de longues attentes, comme les autres lecteurs IPTV.
            .setBufferDurationsMs(120_000, 120_000, 3_000, 4_000)
            .setTargetBufferBytes(160 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                // Le film est lu depuis le préchargement (stockage), qui le télécharge au rythme du serveur.
                DefaultMediaSourceFactory(ahead)
            )
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build()
    }

    var buffering by remember { mutableStateOf(true) }
    // Journal de diagnostic : une session par lecture.
    val session = remember { java.util.UUID.randomUUID().toString().take(8) }
    fun rlog(event: String, vararg data: Pair<String, Any?>) = fr.poptv.player.data.RemoteLog.log(session, event, *data)
    var paused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(playback.startMs) }
    var duration by remember { mutableLongStateOf(0L) }
    // Fin de la partie déjà chargée (mémoire + stockage), affichée en gris clair sur la barre de progression.
    var bufferedEnd by remember { mutableLongStateOf(0L) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    // Options : panneau latéral (touche Bas).
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var panelOpen by remember { mutableStateOf(false) }
    // Section ouverte depuis un bouton du bas (null = toutes, via la touche Menu).
    var panelFilter by remember { mutableStateOf<String?>(null) }
    // Rangée de boutons (audio, sous-titres, vitesse, qualité) : prend le focus avec la touche Bas.
    var controlsFocused by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableIntStateOf(0) }
    val controlsFocus = remember { FocusRequester() }
    // Saut en cours (« + 30 s » au centre), cumulé tant que les sauts s'enchaînent.
    var seekDelta by remember { mutableLongStateOf(0L) }
    var seekLabelTick by remember { mutableIntStateOf(0) }

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
        var autoRetried = false
        var audioRetries = 0
        val errorScope = kotlinx.coroutines.MainScope()
        var wasReady = false
        // Diagnostic de chargement : ce que le réseau a vraiment livré, et combien de fois il a fallu se reconnecter.
        val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
            }

            override fun onLoadCompleted(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                if (mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA) {
                    rlog("load_end", "bytes" to loadEventInfo.bytesLoaded, "ms" to loadEventInfo.loadDurationMs, "pos" to player.currentPosition)
                }
            }

            override fun onLoadCanceled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                if (mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA) {
                    rlog("load_cancel", "bytes" to loadEventInfo.bytesLoaded, "ms" to loadEventInfo.loadDurationMs, "pos" to player.currentPosition)
                }
            }

            override fun onLoadStarted(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                if (mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA) {
                    diag.loads++
                    rlog("load_start", "n" to diag.loads, "offset" to loadEventInfo.dataSpec.position, "pos" to player.currentPosition)
                }
            }

            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                diag.errors++
                diag.lastError = (error.cause ?: error).javaClass.simpleName
                val http = (error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                rlog(
                    "load_error",
                    "type" to diag.lastError,
                    "msg" to (error.cause?.message ?: error.message),
                    "http" to http,
                    "bytes" to loadEventInfo.bytesLoaded,
                    "ms" to loadEventInfo.loadDurationMs,
                    "canceled" to wasCanceled,
                    "pos" to player.currentPosition,
                )
            }
        }
        player.addAnalyticsListener(analytics)
        rlog("start", "kind" to playback.kind, "ext" to playback.ext, "title" to playback.title, "start_ms" to playback.startMs)
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    diag.sinceBase.set(0)
                    diag.basePositionMs = newPosition.positionMs
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING && wasReady) {
                    diag.stalls++
                    diag.stallAt = android.os.SystemClock.elapsedRealtime()
                    rlog(
                        "stall",
                        "n" to diag.stalls,
                        "pos" to player.currentPosition,
                        "ahead_ms" to (player.bufferedPosition - player.currentPosition),
                        "rate_mbps" to diag.lastRateMbps,
                        "loads" to diag.loads,
                        "errors" to diag.errors,
                    )
                }
                if (playbackState == Player.STATE_READY) {
                    if (diag.stallAt > 0) {
                        rlog("resume", "stall_ms" to (android.os.SystemClock.elapsedRealtime() - diag.stallAt), "pos" to player.currentPosition)
                        diag.stallAt = 0
                    } else if (!wasReady) {
                        rlog(
                            "ready",
                            "video" to player.videoFormat?.let { "${it.sampleMimeType} ${it.width}x${it.height} ${it.bitrate}" },
                            "audio" to player.audioFormat?.let { "${it.sampleMimeType} ${it.channelCount}ch ${it.bitrate}" },
                            "duration_ms" to player.duration,
                        )
                    }
                    wasReady = true
                }
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
                rlog("player_error", "code" to e.errorCodeName, "http" to e.httpCode(), "msg" to (e.cause?.message ?: e.message), "pos" to player.currentPosition)
                // Une seule connexion autorisée : si le serveur refuse parce que le flux précédent (aperçu, autre
                // titre) n'est pas encore fermé de son côté, on réessaie une fois tout seul après 3 s.
                // Sortie audio pas encore libérée par la lecture précédente (fréquent en relançant un film,
                // surtout avec un son Dolby/DTS envoyé tel quel à la TV ou à la barre de son) : on réessaie seul.
                if (e.isAudioOutputBusy() && audioRetries < 3) {
                    audioRetries++
                    buffering = true
                    errorScope.launch {
                        kotlinx.coroutines.delay(1_000L * audioRetries)
                        player.prepare()
                    }
                    return
                }
                if (e.isConnectionLimit() && !autoRetried) {
                    autoRetried = true
                    buffering = true
                    errorScope.launch {
                        kotlinx.coroutines.delay(3_000)
                        player.prepare()
                    }
                    return
                }
                buffering = false
                error = e.friendlyMessage() + " OK pour réessayer."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeAnalyticsListener(analytics)
            rlog("stop", "pos" to player.currentPosition, "stalls" to diag.stalls, "loads" to diag.loads, "errors" to diag.errors, "bytes" to diag.total.get())
            fr.poptv.player.data.RemoteLog.flushSoon()
            player.removeListener(listener)
            errorScope.cancel()
            // Dernière position mémorisée (sert à « Reprendre »).
            latestOnProgress(playback, player.currentPosition, player.duration.coerceAtLeast(0L))
            // Coupe le téléchargement et efface le fichier préchargé avant de libérer le lecteur.
            ahead.release()
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
            if (duration > 0) {
                val onDisk = ahead.loadedEndFraction()?.let { (it * duration).toLong() } ?: 0L
                bufferedEnd = maxOf(player.bufferedPosition, onDisk).coerceAtMost(duration)
            }
            duration = player.duration.coerceAtLeast(0L)
            ticks++
            if (ticks >= 20) {
                ticks = 0
                val nowNs = System.nanoTime()
                val totalNow = diag.total.get()
                val tickBytes = totalNow - diag.tickTotal
                val seconds = (nowNs - diag.tickNs) / 1e9
                fun tickRate() = if (diag.tickNs > 0 && seconds > 0) tickBytes * 8.0 / seconds / 1_000_000 else null
                tickRate()?.let { diag.lastRateMbps = it }
                latestOnProgress(playback, position, duration)
                rlog(
                    "tick",
                    "pos" to position,
                    "ahead_ms" to (player.bufferedPosition - player.currentPosition),
                    "disk_ahead_mb" to ahead.aheadBytes() / 1_000_000.0,
                    "net_mbps" to tickRate(),
                    "recv_mb_10s" to tickBytes / 1_000_000.0,
                    "state" to player.playbackState,
                    "playing" to player.isPlaying,
                )
                diag.tickNs = nowNs
                diag.tickTotal = totalNow

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
        if (!panelOpen) {
            panelFilter = null
            runCatching { focusRequester.requestFocus() }
        }
    }

    LaunchedEffect(controlsTick) {
        if (controlsTick > 0) {
            delay(90)
            runCatching { controlsFocus.requestFocus() }
        }
    }

    // Le libellé du saut disparaît 1 s après le dernier saut.
    LaunchedEffect(seekLabelTick) {
        if (seekLabelTick > 0) {
            delay(1_000)
            seekDelta = 0L
        }
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
            controlsFocused -> runCatching { focusRequester.requestFocus() }
            inCredits -> nextDismissed = true
            else -> onBack()
        }
    }

    fun seekBy(deltaMs: Long) {
        val total = player.duration
        var target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        if (total > 0) target = target.coerceAtMost(total)
        seekDelta += target - player.currentPosition
        seekLabelTick++
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
                    panelFilter = null
                    panelOpen = true
                },
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Panneau ouvert ou carte « Épisode suivant » affichée : leurs boutons gèrent les touches.
                if (panelOpen || inCredits || controlsFocused) return@onKeyEvent false
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
                        // Affiche les commandes et donne le focus à la rangée de boutons.
                        overlayTick++
                        controlsTick++
                        true
                    }
                    Key.DirectionUp, Key.Menu -> {
                        overlayTick++
                        if (event.key == Key.Menu) {
                            panelFilter = null
                            panelOpen = true
                        }
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
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
            }
        }

        error?.let {
            Text(
                it,
                color = ErrorColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(Radius.M))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }

        if (paused && error == null && !buffering) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x59000000)))
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (LocalCompact.current) 64.dp else 96.dp)
                    .background(Color(0x40FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 6.dp else 9.dp)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .size(width = if (LocalCompact.current) 8.dp else 11.dp, height = if (LocalCompact.current) 26.dp else 38.dp)
                                .background(Color.White, RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }

        // Saut en cours : « + 30 s » au centre.
        if (seekDelta != 0L && !paused) {
            val seconds = kotlin.math.abs(seekDelta) / 1000
            Text(
                (if (seekDelta > 0) "+ " else "− ") + seconds + " s",
                color = Color.White,
                fontSize = if (LocalCompact.current) TypeSize.Title2 else TypeSize.Title1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Rien ne reste affiché au repos : l'interface n'apparaît qu'après une action, puis disparaît seule.
        val showOverlay = overlayVisible || paused || panelOpen || controlsFocused

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val barHeight by animateDpAsState(if (seeking || controlsFocused) 8.dp else 5.dp, tween(Dur.Fast, easing = AppleEase), label = "progressHeight")
            val remaining = (duration - position).coerceAtLeast(0L)
            val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
            val endClock = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(System.currentTimeMillis() + (remaining / speed).toLong()))
            val sidePadding = if (LocalCompact.current) 24.dp else 56.dp

            Box(modifier = Modifier.fillMaxSize()) {
                // En haut : titre et infos, sur un dégradé qui s'estompe.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(start = sidePadding, end = sidePadding, top = if (LocalCompact.current) 16.dp else 36.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        playback.title,
                        color = Color.White,
                        fontSize = if (LocalCompact.current) TypeSize.Title3 else TypeSize.Title2,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.6).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = if (paused) "En pause" else playback.subtitle
                    if (sub != null) {
                        Text(sub, color = Color(0xB3FFFFFF), fontSize = TypeSize.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // En bas : progression, temps, puis les boutons.
                if (!panelOpen) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE0000000))))
                            .padding(start = sidePadding, end = sidePadding, top = 60.dp, bottom = if (LocalCompact.current) 16.dp else 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (LocalCompact.current) 30.dp else 22.dp)
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
                            val knob = if (seeking || controlsFocused) 20.dp else 14.dp
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth()
                                    .height(barHeight)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0x40FFFFFF)),
                            ) {
                                val loadedFraction = if (duration > 0) (bufferedEnd.toFloat() / duration).coerceIn(fraction, 1f) else 0f
                                Box(modifier = Modifier.fillMaxWidth(loadedFraction).fillMaxHeight().background(Color(0x66FFFFFF)))
                                Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = trackWidth * fraction - knob / 2)
                                    .size(knob)
                                    .background(Color.White, CircleShape),
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(position), color = Color(0xCCFFFFFF), fontSize = TypeSize.Body, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (duration > 0) "−${formatTime(remaining)}  ·  Fini à $endClock" else "",
                                color = Color(0xCCFFFFFF),
                                fontSize = TypeSize.Body,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        val audio = audioOptions(player, tracks)
                        val subs = subtitleOptions(player, tracks)
                        val quality = qualityOptions(player, tracks)
                        val speedOpts = speedOptions(player)
                        fun open(section: String) {
                            panelFilter = section
                            panelOpen = true
                            overlayTick++
                        }
                        Row(
                            modifier = Modifier
                                .onFocusChanged { controlsFocused = it.hasFocus }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        overlayTick++
                                        if (event.key == Key.DirectionUp) {
                                            runCatching { focusRequester.requestFocus() }
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    false
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            var first = true
                            fun mod(): Modifier = if (first) { first = false; Modifier.focusRequester(controlsFocus) } else Modifier
                            if (audio.size > 1) {
                                PlayerChip(AppIcons.Audio, audio.firstOrNull { it.selected }?.label ?: "Audio", mod()) { open("Audio") }
                            }
                            if (subs.isNotEmpty()) {
                                PlayerChip(AppIcons.Subtitles, subs.firstOrNull { it.selected }?.label ?: "Sous-titres", mod()) { open("Sous-titres") }
                            }
                            PlayerChip(AppIcons.Speed, speedOpts.firstOrNull { it.selected }?.label ?: "1×", mod()) { open("Vitesse") }
                            if (quality.isNotEmpty()) {
                                PlayerChip(AppIcons.Quality, quality.firstOrNull { it.selected }?.label ?: "Qualité", mod()) { open("Qualité") }
                            }
                            if (LocalCompact.current) {
                                Text(
                                    "Toucher : pause  ·  Double toucher : ±10 s",
                                    color = Color(0xB3FFFFFF),
                                    fontSize = TypeSize.Caption,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Carte « Épisode suivant » pendant le générique.
        AnimatedVisibility(
            visible = inCredits && !panelOpen,
            enter = fadeIn(tween(Dur.Slow, easing = AppleEase)) + slideInVertically(tween(Dur.Slow, easing = AppleEase)) { it / 3 },
            exit = fadeOut(tween(Dur.Normal, easing = AppleEase)) + slideOutVertically(tween(Dur.Normal, easing = AppleEase)) { it / 3 },
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
            enter = fadeIn(tween(Dur.Normal, easing = AppleEase)),
            exit = fadeOut(tween(Dur.Normal, easing = AppleEase)),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x59000000)))
        }
        AnimatedVisibility(
            visible = panelOpen,
            enter = fadeIn(tween(Dur.Normal, easing = AppleEase)) + slideInHorizontally(tween(Dur.Slow, easing = AppleEase)) { it / 2 },
            exit = fadeOut(tween(Dur.Normal, easing = AppleEase)) + slideOutHorizontally(tween(Dur.Normal, easing = AppleEase)) { it / 2 },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val audio = audioOptions(player, tracks)
            val subs = subtitleOptions(player, tracks)
            val quality = qualityOptions(player, tracks)
            val allSections = buildList {
                if (playback.hasNext) {
                    add(PanelSection("Épisode suivant", AppIcons.SkipNext, listOf(TrackOption("Lire maintenant", false) { latestOnNext() })))
                }
                if (audio.size > 1) add(PanelSection("Audio", AppIcons.Audio, audio))
                if (subs.isNotEmpty()) add(PanelSection("Sous-titres", AppIcons.Subtitles, subs))
                if (quality.isNotEmpty()) add(PanelSection("Qualité", AppIcons.Quality, quality))
                add(PanelSection("Vitesse", AppIcons.Speed, speedOptions(player)))
            }
            val sections = allSections.filter { panelFilter == null || it.title == panelFilter }.ifEmpty { allSections }
            PlayerSidePanel(
                sections = sections,
                onClose = { panelOpen = false },
            )
        }
    }
}

/** Bouton arrondi translucide sous la barre de progression (audio, sous-titres, vitesse, qualité). */
@Composable
private fun PlayerChip(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.05f,
        idleColor = Color(0x38FFFFFF),
        focusedColor = Color.White,
    ) { focused ->
        val tint = if (focused) Color.Black else Color.White
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, color = tint, fontSize = TypeSize.Body, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Compteurs du diagnostic de chargement (mis à jour par le lecteur, lus par l'écran de chargement). */
private class LoadDiag(var basePositionMs: Long) {
    /** Octets reçus depuis le début de la lecture, et depuis le dernier saut (pour le débit moyen du film). */
    val total = java.util.concurrent.atomic.AtomicLong()
    val sinceBase = java.util.concurrent.atomic.AtomicLong()
    var tickNs = 0L
    var tickTotal = 0L
    var lastRateMbps = 0.0
    var loads = 0
    var errors = 0
    var stalls = 0
    var lastError = ""
    var stallAt = 0L

    val counter = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            total.addAndGet(bytesTransferred.toLong())
            sinceBase.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
    }
}
