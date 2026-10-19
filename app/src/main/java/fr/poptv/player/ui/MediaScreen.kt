package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.poptv.player.CAT_ALL
import fr.poptv.player.UiState
import fr.poptv.player.data.Category
import fr.poptv.player.data.ResumeEntry
import fr.poptv.player.data.Section
import fr.poptv.player.data.VodItem

private const val SHELF_LIMIT = 30

private data class VodShelf(val id: String, val title: String, val items: List<VodItem>)

/** Écran des films ou des séries : rangées d'affiches, ou grille si on choisit une catégorie. */
@Composable
fun MediaScreen(
    state: UiState,
    onSection: (Section) -> Unit,
    onSelectCategory: (String) -> Unit,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit,
    onOpen: (VodItem) -> Unit,
    onResume: (ResumeEntry) -> Unit,
) {
    val isMovies = state.section == Section.MOVIES
    val catalog = if (isMovies) state.movies else state.series
    val loading = if (isMovies) state.moviesLoading else state.seriesLoading
    val kind = if (isMovies) "movie" else "episode"

    val resume = remember(state.resume, kind) { state.resume.filter { it.kind == kind } }

    val shelves = remember(catalog) {
        val byCategory = catalog.items.groupBy { it.categoryId }
        catalog.categories.mapNotNull { category ->
            byCategory[category.id]?.let { VodShelf(category.id, category.name, it) }
        }
    }

    val query = state.query.trim()
    val gridMode = query.isNotEmpty() || state.selectedCategory != CAT_ALL || shelves.isEmpty()

    val gridItems = remember(catalog, state.selectedCategory, query) {
        val base = if (state.selectedCategory == CAT_ALL) {
            catalog.items
        } else {
            catalog.items.filter { it.categoryId == state.selectedCategory }
        }
        if (query.isEmpty()) base else base.filter { it.name.contains(query, ignoreCase = true) }
    }

    // Retour : quitte d'abord la catégorie ou la recherche, puis l'application.
    BackHandler(enabled = state.selectedCategory != CAT_ALL || state.query.isNotEmpty()) {
        onSelectCategory(CAT_ALL)
        onQuery("")
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        AppTopBar(state, onSection, onQuery, onSettings)
        MediaTabs(catalog.categories, state.selectedCategory, onSelectCategory)

        when {
            catalog.items.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        state.mediaError != null -> state.mediaError
                        loading -> "Chargement du catalogue… cela peut prendre un moment."
                        else -> "Aucun contenu disponible."
                    },
                    color = if (state.mediaError != null) ErrorColor else TextSecondary,
                )
            }

            gridMode -> if (gridItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aucun résultat.", color = TextSecondary)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(gridItems, key = { it.id }) { item ->
                        PosterTile(
                            title = item.name,
                            poster = item.poster,
                            onClick = { onOpen(item) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (resume.isNotEmpty()) {
                    item(key = "resume") {
                        ResumeRow(resume, onResume)
                    }
                }
                items(shelves, key = { it.id }) { shelf ->
                    VodShelfRow(shelf, onOpen, onSeeAll = { onSelectCategory(shelf.id) })
                }
            }
        }
    }
}

@Composable
private fun MediaTabs(categories: List<Category>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = CAT_ALL) {
            Pill("Accueil", onClick = { onSelect(CAT_ALL) }, selected = selected == CAT_ALL)
        }
        items(categories, key = { it.id }) { category ->
            Pill(category.name, onClick = { onSelect(category.id) }, selected = selected == category.id)
        }
    }
}

@Composable
private fun ShelfTitle(text: String) {
    Text(
        text,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        color = TextPrimary,
        modifier = Modifier.padding(horizontal = ScreenPadding),
    )
}

@Composable
private fun ResumeRow(entries: List<ResumeEntry>, onResume: (ResumeEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShelfTitle("Reprendre")
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                PosterTile(
                    title = entry.title,
                    poster = entry.poster,
                    progress = if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else null,
                    onClick = { onResume(entry) },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}

@Composable
private fun VodShelfRow(shelf: VodShelf, onOpen: (VodItem) -> Unit, onSeeAll: () -> Unit) {
    val visible = remember(shelf.items) { shelf.items.take(SHELF_LIMIT) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShelfTitle(shelf.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(visible, key = { it.id }) { item ->
                PosterTile(
                    title = item.name,
                    poster = item.poster,
                    onClick = { onOpen(item) },
                    modifier = Modifier.width(150.dp),
                )
            }
            if (shelf.items.size > SHELF_LIMIT) {
                item(key = "see-all") {
                    FocusItem(
                        onClick = onSeeAll,
                        modifier = Modifier.width(150.dp).aspectRatio(2f / 3f),
                        focusScale = 1.08f,
                        focusRing = true,
                    ) {
                        Text(
                            "Tout voir",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

/** Affiche verticale (2:3) avec le titre dessous et, si fourni, une barre de progression. */
@Composable
private fun PosterTile(
    title: String,
    poster: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            focusScale = 1.08f,
            focusRing = true,
        ) {
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    title.take(2).uppercase(),
                    color = TextSecondary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x66FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.White),
                    )
                }
            }
        }
        Text(
            title,
            color = TextSecondary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
