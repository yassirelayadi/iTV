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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import fr.poptv.player.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fond : dégradé graphite, plus clair en haut. Il prend une teinte de l'affiche sélectionnée.
val BgTop = Color(0xFF1A2029)
val BgBottom = Color(0xFF05070A)
val Bg = BgBottom
val Surface1 = Color(0xFF17212D)
val Surface2 = Color(0xFF263444)
val Surface3 = Color(0xFF38485B)
val TextPrimary = Color(0xFFF5F7FA)
val TextSecondary = Color(0xFFA7B4C4)
val ErrorColor = Color(0xFFFF453A)

/** Bleu d'accent (bleu « Prime Video ») : sélection (onglets, menu latéral) et « i » du logo. */
val Accent = Color(0xFF1A98FF)

/** Verre : remplissage translucide bleuté, filet clair et remplissage au focus (cartes, lignes, champs). */
val GlassFill = Color(0x14FFFFFF)
val GlassBorder = Color(0x1FFFFFFF)
val GlassFocus = Color(0x33FFFFFF)

/** Dégradé de l'accent : bleu clair, bleu Prime, bleu royal. */
val AccentBrush = Brush.linearGradient(listOf(Color(0xFF7FDCFF), Color(0xFF1A98FF), Color(0xFF3D6BFF)))

/**
 * Police de toute l'app : Geist (licence OFL). Graisse maximale : Bold, réservée au Titre 1.
 * Light et ExtraBold renvoient vers Regular et Bold pour ne jamais sortir de l'échelle.
 */
val AppFont = FontFamily(
    Font(R.font.geist_regular, FontWeight.Light),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
    Font(R.font.geist_bold, FontWeight.ExtraBold),
)

/**
 * Échelle typographique : les seules tailles autorisées dans l'app.
 * Titre 1 (bandeau, fiches) · Titre 2 (titres d'écran) · Titre 3 (rangées, onglets) · Corps · Légende · Étiquette.
 */
object TypeSize {
    val Title1 = 30.sp
    val Title2 = 22.sp
    val Title3 = 17.sp
    val Body = 14.sp
    val Caption = 12.sp
    val Label = 11.sp
}

/** Interlignes associés à l'échelle. */
object LineHeight {
    val Title1 = 36.sp
    val Title2 = 28.sp
    val Title3 = 22.sp
    val Body = 20.sp
    val Caption = 16.sp
}

/** Rayons d'angle : S (badges, vignettes), M (boutons, cartes), L (panneaux, grandes cartes). Pilule : RoundedCornerShape(50). */
object Radius {
    val S = 6.dp
    val M = 10.dp
    val L = 16.dp
}

/** Espacements : grille de 4. */
object Space {
    val X1 = 4.dp
    val X2 = 8.dp
    val X3 = 12.dp
    val X4 = 16.dp
    val X6 = 24.dp
    val X8 = 32.dp
    val X12 = 48.dp
}

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
        fontWeight = FontWeight.Bold,
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
        bodyLarge = base.bodyLarge.inter().copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = base.bodyMedium.inter().copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

/** Vrai sur un téléphone (écran étroit) : menu en bas, marges réduites, lecture tactile. */
val LocalCompact = androidx.compose.runtime.compositionLocalOf { false }

/** Taille de l'interface sur TV (réglage « Taille de l'interface ») : 0,8 compacte, 0,9 normale, 1,0 grande. */
object UiScale {
    private const val FILE = "itv_ui"
    private const val KEY = "ui_scale"
    val value = androidx.compose.runtime.mutableFloatStateOf(1.0f)
    private var loaded = false

    fun load(context: android.content.Context) {
        if (loaded) return
        loaded = true
        value.floatValue = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getFloat(KEY, 1.0f)
    }

    fun set(context: android.content.Context, scale: Float) {
        value.floatValue = scale
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putFloat(KEY, scale).apply()
    }

    fun label(scale: Float) = when {
        scale < 0.85f -> "Compacte"
        scale < 0.95f -> "Normale"
        else -> "Grande"
    }

    fun next(scale: Float) = when {
        scale < 0.85f -> 0.9f
        scale < 0.95f -> 1.0f
        else -> 0.8f
    }
}

@Composable
fun PopTheme(content: @Composable () -> Unit) {
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp < 500
    val context = androidx.compose.ui.platform.LocalContext.current
    UiScale.load(context)
    val base = androidx.compose.ui.platform.LocalDensity.current
    // Sur TV, tout (textes, boutons, images) suit ce facteur ; les téléphones gardent leur taille.
    val scale = if (compact) 1f else UiScale.value.floatValue
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCompact provides compact,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * scale, base.fontScale),
    ) {
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
                listOf(halo.copy(alpha = 0.22f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.04f),
                radius = size.width * 0.62f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(halo.copy(alpha = 0.08f), Color.Transparent),
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
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(Dur.Normal, easing = AppleEase)) }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        val s = 0.97f + 0.03f * p
        scaleX = s
        scaleY = s
    }
}

