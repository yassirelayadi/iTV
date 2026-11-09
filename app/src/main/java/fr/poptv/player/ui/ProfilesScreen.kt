package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.poptv.player.data.Profile

private val AvatarColors = listOf(
    Color(0xFF1A98FF), Color(0xFFE5484D), Color(0xFF30A46C), Color(0xFFF5A524), Color(0xFF8E4EC6), Color(0xFF12A594),
)

private fun avatarColor(p: Profile) = AvatarColors[(p.id.hashCode() and 0x7FFFFFFF) % AvatarColors.size]

/** « Qui regarde ? » : choisir, créer ou supprimer un profil. */
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    activeId: String,
    canClose: Boolean,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }

    BackHandler {
        when {
            creating -> creating = false
            managing -> managing = false
            canClose -> onClose()
        }
    }
    LaunchedEffect(creating) { if (!creating) runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().appBackground().appear(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text(
                if (creating) "Nouveau profil" else if (managing) "Gérer les profils" else "Qui regarde ?",
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(32.dp))

            if (creating) {
                MinimalField(
                    value = name,
                    onChange = { name = it.take(20) },
                    placeholder = "Prénom",
                    modifier = Modifier.width(320.dp),
                    imeAction = ImeAction.Done,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Annuler", onClick = { creating = false })
                    PrimaryButton("Créer", onClick = {
                        if (name.isNotBlank()) {
                            onAdd(name)
                            name = ""
                            creating = false
                        }
                    })
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp, horizontal = 12.dp),
                ) {
                    profiles.forEachIndexed { i, p ->
                        Avatar(
                            label = p.name,
                            initial = p.name.firstOrNull()?.uppercase() ?: "?",
                            color = avatarColor(p),
                            badge = if (managing && profiles.size > 1) "✕" else null,
                            selected = p.id == activeId,
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = { if (managing) { if (profiles.size > 1) onDelete(p.id) } else onSelect(p.id) },
                        )
                    }
                    if (!managing && profiles.size < 6) {
                        Avatar(label = "Ajouter", initial = "+", color = Color(0x33FFFFFF), onClick = { creating = true })
                    }
                }
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(if (managing) "Terminé" else "Gérer", onClick = { managing = !managing })
                    if (canClose && !managing) SecondaryButton("Fermer", onClick = onClose)
                }
                if (managing) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Supprimer un profil efface ses favoris, son historique et ses reprises.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(
    label: String,
    initial: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    badge: String? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.width(112.dp)) {
        FocusItem(
            onClick = onClick,
            shape = CircleShape,
            focusScale = 1.1f,
            idleColor = color,
            focusedColor = color,
            focusRing = true,
        ) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Text(initial, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                if (badge != null) {
                    Box(Modifier.fillMaxSize().background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                        Text(badge, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            color = if (selected) Accent else TextPrimary,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
