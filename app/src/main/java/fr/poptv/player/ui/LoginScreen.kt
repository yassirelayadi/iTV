package fr.poptv.player.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.poptv.player.data.Credentials

@Composable
fun LoginScreen(
    loading: Boolean,
    error: String?,
    canCancel: Boolean,
    onSubmit: (Credentials) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("xtream") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var m3u by rememberSaveable { mutableStateOf("") }

    val valid = if (mode == "xtream") {
        server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    } else {
        m3u.isNotBlank()
    }

    Box(
        modifier = Modifier.fillMaxSize().appBackground().appear(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (LocalCompact.current) 20.dp else 0.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoText(size = 52.sp)
            Text(
                "Connecte ton abonnement IPTV pour commencer.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Xtream Codes", onClick = { mode = "xtream" }, selected = mode == "xtream")
                Pill("Playlist M3U", onClick = { mode = "m3u" }, selected = mode == "m3u")
            }

            if (mode == "xtream") {
                MinimalField(server, { server = it }, "http://exemple.com:8080", Modifier.fillMaxWidth(), KeyboardType.Uri)
                MinimalField(user, { user = it }, "Nom d'utilisateur", Modifier.fillMaxWidth())
                MinimalField(
                    pass, { pass = it }, "Mot de passe", Modifier.fillMaxWidth(),
                    KeyboardType.Password, password = true, imeAction = ImeAction.Done,
                )
            } else {
                MinimalField(
                    m3u, { m3u = it }, "URL de la playlist M3U", Modifier.fillMaxWidth(),
                    KeyboardType.Uri, imeAction = ImeAction.Done,
                )
            }

            val address = (if (mode == "xtream") server else m3u).trim()
            if (address.isNotEmpty() && !address.startsWith("https://")) {
                Text(
                    "Adresse en http : tes identifiants circulent sans chiffrement. " +
                        "C'est courant en IPTV ; utilise https si ton fournisseur le propose.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (error != null) {
                Text(error, color = ErrorColor, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = if (loading) "Connexion…" else "Se connecter",
                    onClick = {
                        if (valid && !loading) {
                            onSubmit(
                                Credentials(
                                    type = mode,
                                    server = server.trim(),
                                    username = user.trim(),
                                    password = pass,
                                    m3uUrl = m3u.trim(),
                                )
                            )
                        }
                    },
                )
                if (canCancel) {
                    Pill("Annuler", onClick = onCancel)
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
