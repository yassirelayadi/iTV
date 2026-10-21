package fr.poptv.player.ui

import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.poptv.player.UiState
import fr.poptv.player.data.AccountInfo
import fr.poptv.player.data.Section
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réglages : catégories à afficher, actualisation, compte. */
@Composable
fun SettingsScreen(
    state: UiState,
    onOpenCategories: (Section) -> Unit,
    onRefresh: () -> Unit,
    onAccount: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize().appBackground().appear(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (LocalCompact.current) 20.dp else 0.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Réglages", fontSize = 40.sp, fontWeight = FontWeight.Light, color = TextPrimary)
            Spacer(Modifier.height(8.dp))

            ProfileCard(state.account, state.accountLoading)

            SettingRow(
                title = "Catégories des chaînes",
                subtitle = summary(state.allCategories.size, state.hiddenLive.size),
                onClick = { onOpenCategories(Section.LIVE) },
                modifier = Modifier.focusRequester(focus),
            )
            if (state.vodSupported) {
                SettingRow(
                    title = "Catégories des films",
                    subtitle = if (state.allMovies.categories.isEmpty()) {
                        "Le catalogue se charge à l'ouverture"
                    } else {
                        summary(state.allMovies.categories.size, state.hiddenMovies.size)
                    },
                    onClick = { onOpenCategories(Section.MOVIES) },
                )
                SettingRow(
                    title = "Catégories des séries",
                    subtitle = if (state.allSeries.categories.isEmpty()) {
                        "Le catalogue se charge à l'ouverture"
                    } else {
                        summary(state.allSeries.categories.size, state.hiddenSeries.size)
                    },
                    onClick = { onOpenCategories(Section.SERIES) },
                )
            }

            Spacer(Modifier.height(8.dp))
            SettingRow("Actualiser les catalogues", "Recharge les chaînes, films et séries", onRefresh)
            SettingRow("Changer de compte", "Saisir d'autres identifiants", onAccount)
            SettingRow("Se déconnecter", "Efface les identifiants de cet appareil", onLogout)

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Pill("Terminé", onClick = onBack)
            }
        }
    }
}

/** Section Profil : identifiant, statut, date d'expiration et connexions du compte. */
@Composable
private fun ProfileCard(account: AccountInfo?, loading: Boolean) {
    if (account == null) return
    val lines = buildList {
        if (account.xtream) {
            add("Identifiant" to account.username.ifBlank { "—" })
            val status = account.status?.let {
                when (it.lowercase()) {
                    "active" -> "Actif"
                    "expired" -> "Expiré"
                    "banned" -> "Suspendu"
                    "disabled" -> "Désactivé"
                    else -> it
                }
            }
            if (status != null) add("Statut" to (status + if (account.trial) " (essai)" else ""))
            val exp = account.expiresSec
            add(
                "Expire le" to when {
                    exp != null -> expiryText(exp)
                    account.fromServer -> "Illimité"
                    loading -> "Chargement…"
                    else -> "Non communiqué par le fournisseur"
                },
            )
            if (account.maxConnections != null) {
                val used = account.activeConnections
                add("Connexions" to if (used != null) "$used en cours sur ${account.maxConnections}" else "${account.maxConnections} autorisées")
            }
        } else {
            add("Type" to "Liste M3U")
            if (account.username.isNotBlank()) add("Serveur" to account.username)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Profil", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        lines.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.width(110.dp))
                Text(value, color = TextPrimary, fontSize = 14.sp)
            }
        }
    }
}

private fun expiryText(expSec: Long): String {
    val date = SimpleDateFormat("d MMMM yyyy", Locale.FRENCH).format(Date(expSec * 1000))
    val days = (expSec - System.currentTimeMillis() / 1000).floorDiv(86_400L)
    val rest = when {
        days < 0 -> "expiré"
        days == 0L -> "aujourd'hui"
        days == 1L -> "demain"
        else -> "dans $days jours"
    }
    return "$date  ·  $rest"
}

private fun summary(total: Int, hidden: Int): String = when {
    total == 0 -> "Aucune catégorie"
    hidden == 0 -> "Toutes affichées ($total)"
    else -> "${total - hidden} affichées sur $total"
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        focusScale = 1.03f,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextSecondary, fontSize = 14.sp)
        }
    }
}
