package fr.poptv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Affiché au lancement qui suit un plantage : permet de photographier l'erreur et de me l'envoyer. */
@Composable
fun CrashScreen(report: String, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize().appBackground().padding(horizontal = 56.dp, vertical = 32.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("iTV s'est arrêtée la dernière fois", color = TextPrimary, fontSize = 26.sp)
            Text(
                "Envoie-moi une photo de cet écran pour que je corrige le problème.",
                color = TextSecondary,
                fontSize = 15.sp,
            )
            PrimaryButton("Continuer", onClick = onClose, modifier = Modifier.focusRequester(focus))
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    report.lines().take(22).joinToString("\n"),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
