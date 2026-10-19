package fr.poptv.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fond : dégradé graphite, plus clair en haut. Il prend une teinte de l'affiche sélectionnée.
val BgTop = Color(0xFF3A3A3C)
val BgBottom = Color(0xFF161618)
val Bg = BgBottom
val Surface1 = Color(0xFF2A2A2D)
val Surface2 = Color(0xFF4A4A4E)
val Surface3 = Color(0xFF636368)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA1A1A6)
val ErrorColor = Color(0xFFFF453A)

@Composable
fun PopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            background = Bg,
            onBackground = TextPrimary,
            surface = Surface1,
            onSurface = TextPrimary,
            error = ErrorColor,
        ),
        content = content,
    )
}

/** Haut du dégradé : graphite, ou graphite teinté par la couleur de l'affiche (assombri pour rester lisible). */
private fun tintedTop(tint: Color?): Color {
    if (tint == null) return BgTop
    var top = lerp(BgTop, lerp(tint, Color.Black, 0.35f), 0.75f)
    val lum = top.luminance()
    if (lum > 0.055f) top = lerp(top, Color.Black, 1f - 0.055f / lum)
    return top
}

/** Fond de tous les écrans : dégradé graphite, qui glisse en douceur vers la teinte de l'affiche. */
@Composable
fun Modifier.appBackground(tint: Color? = null): Modifier {
    val top by animateColorAsState(tintedTop(tint), tween(700), label = "bgTop")
    return background(Brush.verticalGradient(listOf(top, BgBottom)))
}
