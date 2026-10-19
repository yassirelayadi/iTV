package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.poptv.player.UiState
import fr.poptv.player.data.Section

/**
 * Choix des catégories affichées pour une section. Chaque ligne bascule Affichée / Masquée ;
 * les boutons du haut s'appliquent à toute la liste, ou aux résultats si un filtre est saisi.
 */
@Composable
fun CategoriesScreen(
    state: UiState,
    section: Section,
    onSetHidden: (Section, Collection<String>, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val categories = when (section) {
        Section.LIVE -> state.allCategories
        Section.MOVIES -> state.allMovies.categories
        Section.SERIES -> state.allSeries.categories
    }
    val hidden = when (section) {
        Section.LIVE -> state.hiddenLive
        Section.MOVIES -> state.hiddenMovies
        Section.SERIES -> state.hiddenSeries
    }
    val loading = when (section) {
        Section.LIVE -> state.loading
        Section.MOVIES -> state.moviesLoading
        Section.SERIES -> state.seriesLoading
    }
    val title = when (section) {
        Section.LIVE -> "Catégories des chaînes"
        Section.MOVIES -> "Catégories des films"
        Section.SERIES -> "Catégories des séries"
    }

    // Nombre d'éléments par catégorie.
    val counts = remember(section, state.allChannels, state.allMovies, state.allSeries) {
        when (section) {
            Section.LIVE -> state.allChannels.groupingBy { it.categoryId }.eachCount()
            Section.MOVIES -> state.allMovies.items.groupingBy { it.categoryId }.eachCount()
            Section.SERIES -> state.allSeries.items.groupingBy { it.categoryId }.eachCount()
        }
    }

    var query by remember { mutableStateOf("") }
    val shown = remember(categories, query) {
        val q = query.trim()
        if (q.isEmpty()) categories else categories.filter { it.name.contains(q, ignoreCase = true) }
    }
    val filtering = query.isNotBlank()
    val visibleCount = categories.count { it.id !in hidden }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Light, color = TextPrimary)
        Text("$visibleCount affichées sur ${categories.size}", color = TextSecondary)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MinimalField(
                value = query,
                onChange = { query = it },
                placeholder = "Filtrer les catégories",
                modifier = Modifier.width(300.dp),
                imeAction = ImeAction.Search,
                shape = RoundedCornerShape(50),
            )
            Pill(
                if (filtering) "Afficher les résultats" else "Tout afficher",
                onClick = { onSetHidden(section, shown.map { it.id }, false) },
            )
            Pill(
                if (filtering) "Masquer les résultats" else "Tout masquer",
                onClick = { onSetHidden(section, shown.map { it.id }, true) },
            )
            Pill("Terminé", onClick = onBack)
        }

        if (categories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Chargement du catalogue… cela peut prendre un moment." else "Aucune catégorie.",
                    color = TextSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { category ->
                    val isHidden = category.id in hidden
                    FocusItem(
                        onClick = { onSetHidden(section, listOf(category.id), !isHidden) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        focusScale = 1.02f,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                category.name,
                                color = if (isHidden) TextSecondary else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${counts[category.id] ?: 0}", color = TextSecondary, fontSize = 14.sp)
                            Text(
                                if (isHidden) "Masquée" else "Affichée",
                                color = if (isHidden) TextSecondary else TextPrimary,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(90.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
