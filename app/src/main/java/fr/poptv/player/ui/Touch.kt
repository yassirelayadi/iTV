package fr.poptv.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Sur téléphone, les lecteurs passent en paysage le temps de la lecture. */
@Composable
fun ForceLandscape() {
    if (!LocalCompact.current) return
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(Unit) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = previous }
    }
}

/** Lecteur de chaînes : un toucher affiche le bandeau, un balayage vertical change de chaîne. */
@Composable
fun Modifier.liveTouch(onTap: () -> Unit, onZap: (Int) -> Unit): Modifier {
    val tap by rememberUpdatedState(onTap)
    val zap by rememberUpdatedState(onZap)
    return this
        .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (total < -90f) zap(+1) else if (total > 90f) zap(-1)
                },
            ) { _, dy -> total += dy }
        }
}

/**
 * Lecteur de films : un toucher met en pause / affiche les commandes,
 * un double toucher à gauche ou à droite saute de 10 s, un appui long ouvre les options.
 */
@Composable
fun Modifier.vodTouch(
    onTap: () -> Unit,
    onSeek: (Int) -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val seek by rememberUpdatedState(onSeek)
    val long by rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        detectTapGestures(
            onTap = { tap() },
            onDoubleTap = { offset -> seek(if (offset.x < size.width / 2f) -1 else 1) },
            onLongPress = { long() },
        )
    }
}

/** Position (0..1) d'un toucher sur une barre de la largeur donnée. */
fun touchFraction(x: Float, size: IntSize): Float = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
