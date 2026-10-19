package fr.poptv.player.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Couleur dominante de l'affiche donnée. Garde la dernière couleur trouvée pendant le calcul
 * de la suivante (pas de clignotement) ; null quand aucune affiche n'est sélectionnée.
 */
@Composable
fun rememberPosterTint(url: String?): Color? {
    val context = LocalContext.current
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) {
            tint = null
            return@LaunchedEffect
        }
        delay(250) // on n'analyse pas chaque affiche pendant un défilement rapide
        val found = withContext(Dispatchers.Default) { runCatching { extractTint(context, url) }.getOrNull() }
        if (found != null) tint = found
    }
    return tint
}

private suspend fun extractTint(context: Context, url: String): Color? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(120, 180)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    val palette = Palette.from(bitmap).maximumColorCount(12).generate()
    val swatch = palette.darkMutedSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch ?: return null
    return Color(swatch.rgb)
}
