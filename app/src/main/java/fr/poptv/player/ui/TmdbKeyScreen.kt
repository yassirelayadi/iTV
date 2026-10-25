package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Saisie de la clé TMDB (champ masqué). La clé est vérifiée auprès de TMDB puis chiffrée sur l'appareil. */
@Composable
fun TmdbKeyScreen(
    configured: Boolean,
    usesDefault: Boolean,
    busy: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var key by rememberSaveable { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().appBackground().appear(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(horizontal = if (LocalCompact.current) 20.dp else 0.dp, vertical = 16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(GlassFill)
                .border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tendances et à l'affiche", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Text(
                "Crée un compte gratuit sur themoviedb.org, puis ouvre Paramètres → API et copie ta clé " +
                    "(ou le long jeton qui commence par « eyJ »). Colle-la ci-dessous : elle est vérifiée, " +
                    "puis chiffrée sur cet appareil et jamais partagée.",
                color = TextSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            if (usesDefault) {
                Text(
                    "L'app contient déjà une clé par défaut : ta propre clé est facultative.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            MinimalField(
                value = key,
                onChange = { key = it },
                placeholder = if (configured) "Nouvelle clé (laisser vide pour retirer)" else "Clé TMDB",
                modifier = Modifier.fillMaxWidth(),
                password = true,
                imeAction = ImeAction.Done,
            )
            if (busy) Text("Vérification…", color = TextSecondary)
            if (error != null) Text(error, color = ErrorColor, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = if (key.isBlank() && configured) "Retirer la clé" else "Enregistrer",
                    onClick = { if (!busy && (key.isNotBlank() || configured)) onSave(key) },
                )
                Pill("Annuler", onClick = onBack)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.",
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
