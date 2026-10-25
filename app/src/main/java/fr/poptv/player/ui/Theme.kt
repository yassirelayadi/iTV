package fr.poptv.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import fr.poptv.player.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fond : dégradé graphite, plus clair en haut. Il prend une teinte de l'affiche sélectionnée.
val BgTop = Color(0xFF16222F)
val BgBottom = Color(0xFF000000)
val Bg = BgBottom
val Surface1 = Color(0xFF17212D)
val Surface2 = Color(0xFF263444)
val Surface3 = Color(0xFF38485B)
val TextPrimary = Color(0xFFF5F7FA)
val TextSecondary = Color(0xFFA7B4C4)
val ErrorColor = Color(0xFFFF453A)

/** Bleu d'accent (bleu « Prime Video ») : sélection (onglets, menu latéral) et « i » du logo. */
val Accent = Color(0xFF1A98FF)

/** Dégradé de l'accent : bleu clair, bleu Prime, bleu royal. */
val AccentBrush = Brush.linearGradient(listOf(Color(0xFF7FDCFF), Color(0xFF1A98FF), Color(0xFF3D6BFF)))

/**
 * Police de toute l'app : Inter, la police libre la plus proche de la police d'Apple (SF Pro),
 * dont la licence n'autorise pas l'usage sur Android.
 */
val AppFont = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/** Police du logo : Sora Bold. */
val LogoFont = FontFamily(Font(R.font.sora_bold, FontWeight.Bold))

/** Logo iTV : le « i » en bleu, le reste en blanc. */
@Composable
fun LogoText(size: TextUnit, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Accent)) { append("i") }
            append("TV")
        },
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = size,
        color = Color.White,
        modifier = modifier,
    )
}

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

/** Vrai sur un téléphone (écran étroit) : menu en bas, marges réduites, lecture tactile. */
val LocalCompact = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun PopTheme(content: @Composable () -> Unit) {
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp < 500
    androidx.compose.runtime.CompositionLocalProvider(LocalCompact provides compact) {
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
    val noise = remember { ShaderBrush(ImageShader(noiseBitmap, TileMode.Repeated, TileMode.Repeated)) }
    // Halos lumineux : bleu Prime par défaut, ou couleur de l'affiche sélectionnée.
    val halo by animateColorAsState(if (tint == null) Accent else lerp(tint, Accent, 0.25f), tween(700), label = "bgHalo")
    return background(
        Brush.verticalGradient(
            0f to top,
            0.5f to lerp(top, BgBottom, 0.82f),
            1f to BgBottom,
        )
    ).drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(halo.copy(alpha = 0.34f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.04f),
                radius = size.width * 0.62f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(halo.copy(alpha = 0.14f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.92f),
                radius = size.width * 0.5f,
            ),
        )
        // Grain très léger (mode « Incrustation ») : casse les bandes visibles des dégradés sur les téléviseurs.
        // Il suit la luminosité du fond, donc le noir pur reste parfaitement noir.
        drawRect(noise, blendMode = BlendMode.Overlay)
    }
}

/** Petite texture de grain gris moyen, répétée sur tout l'écran. */
private val noiseBitmap: ImageBitmap by lazy {
    val size = 128
    val random = kotlin.random.Random(7)
    val pixels = IntArray(size * size) {
        val g = 122 + random.nextInt(13)
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Courbe douce des animations : démarrage vif, arrivée très lente (proche de celle d'Apple). */
val AppleEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Apparition d'un écran : fondu et léger zoom depuis 97 %. Le fond placé avant ce modificateur reste fixe. */
@Composable
fun Modifier.appear(): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(300, easing = AppleEase)) }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        val s = 0.97f + 0.03f * p
        scaleX = s
        scaleY = s
    }
}
