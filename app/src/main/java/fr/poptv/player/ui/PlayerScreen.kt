package fr.poptv.player.ui

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import fr.poptv.player.data.Channel
import fr.poptv.player.data.EpgInfo
import fr.poptv.player.data.Http
import kotlinx.coroutines.delay

private const val MAX_RETRIES = 3

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channel: Channel,
    epg: EpgInfo?,
    onNeedEpg: (String) -> Unit,
    onZap: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val player = remember {
        // Tampons réduits pour un zapping rapide.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client).setUserAgent("iTV/0.1"))
            )
            .setLoadControl(loadControl)
            .build()
            .apply { playWhenReady = true }
    }

    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var retryTick by remember { mutableIntStateOf(0) }
    var reconnecting by remember { mutableStateOf(false) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    error = null
                    retries = 0
                    reconnecting = false
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // Coupure du flux : jusqu'à 3 nouvelles tentatives automatiques (2 s, 4 s, 6 s), puis message.
                if (retries < MAX_RETRIES) {
                    retries++
                    reconnecting = true
                    buffering = true
                    retryTick++
                } else {
                    reconnecting = false
                    buffering = false
                    error = "Lecture impossible (${e.errorCodeName}). OK pour réessayer."
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(retryTick) {
        if (retryTick == 0) return@LaunchedEffect
        delay(2_000L * retries)
        if (!reconnecting) return@LaunchedEffect // chaîne changée ou lecture revenue entre-temps
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(channel.id) {
        error = null
        retries = 0
        reconnecting = false
        buffering = true
        val item = MediaItem.Builder()
            .setUri(channel.streamUrl)
            .apply { if (channel.streamUrl.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    // Le programme est redemandé à chaque affichage du bandeau (le serveur n'est interrogé qu'au besoin).
    LaunchedEffect(channel.id, overlayTick) { onNeedEpg(channel.id) }

    LaunchedEffect(channel.id, overlayTick) {
        overlayVisible = true
        delay(4_000)
        overlayVisible = false
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { player.play() }

    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .liveTouch(
                onTap = {
                    if (error != null) {
                        error = null
                        retries = 0
                        buffering = true
                        player.prepare()
                        player.playWhenReady = true
                    } else {
                        overlayTick++
                    }
                },
                onZap = onZap,
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> {
                        onZap(+1)
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> {
                        onZap(-1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (error != null) {
                            error = null
                            retries = 0
                            buffering = true
                            player.prepare()
                            player.playWhenReady = true
                        } else {
                            overlayTick++
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
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                if (reconnecting) {
                    Text(
                        "Reconnexion… ($retries/$MAX_RETRIES)",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
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

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = if (LocalCompact.current) 24.dp else 56.dp, vertical = if (LocalCompact.current) 20.dp else 40.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (channel.logo != null) {
                        AsyncImage(
                            model = rememberImage(channel.logo, 256, 256),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Surface1),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                        val nowSec = System.currentTimeMillis() / 1000
                        val current = epg?.now(nowSec)
                        val upcoming = epg?.next(nowSec)
                        if (current != null) {
                            Text(
                                "${current.title}  ·  ${formatClock(current.startSec)} – ${formatClock(current.endSec)}",
                                color = Color.White,
                                fontSize = 18.sp,
                                maxLines = 1,
                            )
                            Box(
                                modifier = Modifier
                                    .width(360.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0x40FFFFFF)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(current.progress(nowSec))
                                        .fillMaxHeight()
                                        .background(AccentBrush),
                                )
                            }
                        }
                        if (upcoming != null) {
                            Text(
                                "Ensuite : ${upcoming.title}  ·  ${formatClock(upcoming.startSec)}",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                maxLines = 1,
                            )
                        }
                        Text(
                            if (LocalCompact.current) "Balayer vers le haut / bas pour changer de chaîne" else "Haut / Bas pour changer de chaîne",
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
