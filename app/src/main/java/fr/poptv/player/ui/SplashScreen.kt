package fr.poptv.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp

/** Écran de démarrage : le logo apparaît en fondu sur le fond graphite. */
@Composable
fun SplashScreen() {
    var shown by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(700, easing = AppleEase), label = "splashAlpha")
    LaunchedEffect(Unit) { shown = true }

    Box(modifier = Modifier.fillMaxSize().appBackground(), contentAlignment = Alignment.Center) {
        LogoText(
            size = 72.sp,
            modifier = Modifier.graphicsLayer {
                alpha = progress
                val s = 0.92f + 0.08f * progress
                scaleX = s
                scaleY = s
            },
        )
    }
}
