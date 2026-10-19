package fr.poptv.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.poptv.player.UiState
import fr.poptv.player.data.Section

internal val ScreenPadding = 48.dp

/** Barre du haut commune : titre, sections (Chaînes / Films / Séries), recherche, actions. */
@Composable
fun AppTopBar(
    state: UiState,
    onSection: (Section) -> Unit,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val loading = when (state.section) {
        Section.LIVE -> state.loading
        Section.MOVIES -> state.moviesLoading
        Section.SERIES -> state.seriesLoading
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "iTV",
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        if (state.vodSupported) {
            Spacer(Modifier.width(12.dp))
            Pill("Chaînes", onClick = { onSection(Section.LIVE) }, selected = state.section == Section.LIVE)
            Pill("Films", onClick = { onSection(Section.MOVIES) }, selected = state.section == Section.MOVIES)
            Pill("Séries", onClick = { onSection(Section.SERIES) }, selected = state.section == Section.SERIES)
        }
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        MinimalField(
            value = state.query,
            onChange = onQuery,
            placeholder = "Rechercher",
            modifier = Modifier.width(240.dp),
            imeAction = ImeAction.Search,
            shape = RoundedCornerShape(50),
            leadingIcon = Icons.Filled.Search,
        )
        RoundIconButton(Icons.Filled.Settings, "Réglages", onClick = onSettings)
    }
}
