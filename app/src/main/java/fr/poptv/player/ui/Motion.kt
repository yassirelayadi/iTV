package fr.poptv.player.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Glissement du focus : quand la télécommande amène une rangée ou une tuile près du bord, la liste
 * défile en douceur (260 ms, démarrage vif, arrivée lente) en gardant une marge de 10 % autour de
 * l'élément, au lieu de le coller au bord de l'écran.
 */
@OptIn(ExperimentalFoundationApi::class)
private object GlideSpec : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(260, easing = AppleEase)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.10f
        val end = offset + size
        return when {
            size > containerSize - 2 * margin -> offset - margin
            offset < margin -> offset - margin
            end > containerSize - margin -> end - (containerSize - margin)
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlideScrolling(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides GlideSpec, content = content)
}
