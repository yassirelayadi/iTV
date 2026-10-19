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
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import fr.poptv.player.R
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

/**
 * Police de toute l'app : Inter, la police libre la plus proche de la police d'Apple (SF Pro),
 * dont la licence n'autorise pas l'usage sur Android.
 */
val AppFont = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)

private fun TextStyle.inter() = copy(fontFamily = AppFont)

private fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(),
        titleMedium = base.titleMedium.inter(),
        titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter(),
        bodyMedium = base.bodyMedium.inter(),
        bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

@Composable
fun PopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = appTypography(),
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
