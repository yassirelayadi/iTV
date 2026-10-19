package fr.poptv.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF000000)
val Surface1 = Color(0xFF1C1C1E)
val Surface2 = Color(0xFF2C2C2E)
val Surface3 = Color(0xFF3A3A3C)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFF8E8E93)
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
