package fr.poptv.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icônes maison : grille de 24, trait de 1,75, bouts arrondis. Elles remplacent les icônes Material.
 * Chaque tracé est soit un trait (S), soit une forme pleine (F) ; la teinte de Icon s'applique aux deux.
 */
object AppIcons {
    private fun icon(name: String, vararg parts: Pair<Char, String>): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for ((kind, path) in parts) {
            if (kind == 'F') {
                b.addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            } else {
                b.addPath(
                    pathData = addPathNodes(path),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    // Navigation
    val Tv by lazy { icon("Tv", 'S' to "M5.5,5.5h13a2,2 0 0 1 2,2v7.5a2,2 0 0 1 -2,2h-13a2,2 0 0 1 -2,-2v-7.5a2,2 0 0 1 2,-2zM9,20.5h6") }
    val Movie by lazy { icon("Movie", 'S' to "M6,5h12a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2h-12a2,2 0 0 1 -2,-2v-10a2,2 0 0 1 2,-2zM4,9.5h16M8.5,5l1.5,4.5M13.5,5l1.5,4.5") }
    val Library by lazy {
        icon(
            "Library",
            'S' to "M6,7h9a2,2 0 0 1 2,2v8a2,2 0 0 1 -2,2h-9a2,2 0 0 1 -2,-2v-8a2,2 0 0 1 2,-2zM7.5,4h10.5a2,2 0 0 1 2,2v9.5",
            'F' to "M9.5,10.5v5l4,-2.5z",
        )
    }
    val Search by lazy { icon("Search", 'S' to "M5,11a6,6 0 1 0 12,0a6,6 0 1 0 -12,0zM15.5,15.5L20,20") }
    val Settings by lazy {
        icon("Settings", 'S' to "M4,7h9M17,7h3M4,17h3M11,17h9M13,7a2,2 0 1 0 4,0a2,2 0 1 0 -4,0zM7,17a2,2 0 1 0 4,0a2,2 0 1 0 -4,0z")
    }
    val Back by lazy { icon("Back", 'S' to "M19,12H5M11,6l-6,6 6,6") }

    // Actions
    val Play by lazy { icon("Play", 'F' to "M8,5.5v13l10.5,-6.5z") }
    val Plus by lazy { icon("Plus", 'S' to "M12,5v14M5,12h14") }
    val Check by lazy { icon("Check", 'S' to "M5,12.5l4.5,4.5L19,7.5") }
    val Heart by lazy { icon("Heart", 'S' to "M12,19.5s-7,-4.3 -7,-9.4A3.9,3.9 0 0 1 12,7.8a3.9,3.9 0 0 1 7,2.3c0,5.1 -7,9.4 -7,9.4z") }
    val Refresh by lazy { icon("Refresh", 'S' to "M19.5,12a7.5,7.5 0 1 1 -2.2,-5.3M19.5,4.5v4h-4") }
    val Warning by lazy { icon("Warning", 'S' to "M12,4.5l8.5,15h-17zM12,10.5v4M12,17.3v0.2") }
    val Close by lazy { icon("Close", 'S' to "M6.5,6.5l11,11M17.5,6.5l-11,11") }
    val Star by lazy { icon("Star", 'F' to "M12,4.5l2.3,4.8 5.2,0.7 -3.8,3.6 0.9,5.2L12,16.3l-4.6,2.5 0.9,-5.2 -3.8,-3.6 5.2,-0.7z") }
    val CaretDown by lazy { icon("CaretDown", 'S' to "M7,10l5,5 5,-5") }

    // Lecteur
    val SkipNext by lazy { icon("SkipNext", 'F' to "M6.5,6v12l8.5,-6z", 'S' to "M17.5,6v12") }
    val Audio by lazy { icon("Audio", 'S' to "M4,9.5h3.5l4.5,-4v13l-4.5,-4h-3.5zM15.5,9a4,4 0 0 1 0,6M18,6.5a7.5,7.5 0 0 1 0,11") }
    val Subtitles by lazy { icon("Subtitles", 'S' to "M5,5.5h14a2,2 0 0 1 2,2v9a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2v-9a2,2 0 0 1 2,-2zM7,12.5h3M13,12.5h4M7,15.5h7M16.5,15.5h0.5") }
    val Quality by lazy { icon("Quality", 'S' to "M5,5.5h14a2,2 0 0 1 2,2v9a2,2 0 0 1 -2,2h-14a2,2 0 0 1 -2,-2v-9a2,2 0 0 1 2,-2zM7.5,9v6M7.5,12h3M10.5,9v6M14,9h1.5a2,2 0 0 1 2,2v2a2,2 0 0 1 -2,2h-1.5z") }
    val Speed by lazy { icon("Speed", 'S' to "M4.5,17a7.5,7.5 0 1 1 15,0M12,17l3.5,-5") }
}
