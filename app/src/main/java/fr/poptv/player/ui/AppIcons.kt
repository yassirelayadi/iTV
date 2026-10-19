package fr.poptv.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icônes sans dépendance supplémentaire : tracés vectoriels simples (grille de 24). */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val Tv: ImageVector by lazy {
        icon(
            "Tv",
            "M21,3H3C1.9,3 1,3.9 1,5v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2z" +
                "M21,17H3V5h18v12z",
        )
    }

    val Movie: ImageVector by lazy {
        icon(
            "Movie",
            "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16" +
                "c1.1,0 2,-0.9 2,-2V4h-4z",
        )
    }

    val Library: ImageVector by lazy {
        icon(
            "Library",
            "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4" +
                "C22,2.9 21.1,2 20,2zM12,14.5v-9l6,4.5 -6,4.5z",
        )
    }

    val Check: ImageVector by lazy {
        icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    }

    val CaretDown: ImageVector by lazy {
        icon("CaretDown", "M7,10l5,5 5,-5z")
    }

    val SkipNext: ImageVector by lazy {
        icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    }

    val Audio: ImageVector by lazy {
        icon(
            "Audio",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    val Subtitles: ImageVector by lazy {
        icon(
            "Subtitles",
            "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2z" +
                "M4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z",
        )
    }

    val Quality: ImageVector by lazy {
        icon(
            "Quality",
            "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2z" +
                "M11,15H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zM18,14c0,0.55 -0.45,1 -1,1h-0.75v1.5h-1.5V15H14" +
                "c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v4zM14.5,10.5h2v3h-2z",
        )
    }

    val Speed: ImageVector by lazy {
        icon(
            "Speed",
            "M20.38,8.57l-1.23,1.85a8,8 0 0,1 -0.22,7.58H5.07A8,8 0 0,1 15.58,6.85l1.85,-1.23A10,10 0 0,0 3.35,19" +
                "a2,2 0 0,0 1.72,1h13.85a2,2 0 0,0 1.74,-1 10,10 0 0,0 -0.27,-10.44z" +
                "M10.59,15.41a2,2 0 0,0 2.83,0l5.66,-8.49 -8.49,5.66a2,2 0 0,0 0,2.83z",
        )
    }
}
