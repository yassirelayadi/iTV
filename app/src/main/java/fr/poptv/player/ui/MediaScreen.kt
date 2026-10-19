package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import java.util.Locale
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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

private const val SHELF_RECENT = "__recent"
private const val SHELF_TOP = "__top"

private data class VodShelf(val id: String, val title: String, val items: List<VodItem>)

/** Contenu du bandeau du haut : l'élément sélectionné (titre, infos, résumé). */
private data class HeroInfo(val title: String, val meta: String, val plot: String?, val poster: String?)

/** « 7.80000 » -> « 7,8 » ; null si la note est absente ou nulle. */
private fun ratingLabel(raw: String?): String? {
    val number = cleanRating(raw)?.replace(',', '.')?.toDoubleOrNull() ?: return null
    return if (number <= 0.0) null else String.format(Locale.FRANCE, "%.1f", number)
}

private fun VodItem.hero(categoryName: String?): HeroInfo = HeroInfo(
    title = name,
    meta = listOfNotNull(categoryName, year, ratingLabel(rating)?.let { "★ $it" }).joinToString("  ·  "),
    plot = plot,
    poster = poster,
)

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

    val categoryNames = remember(catalog) { catalog.categories.associate { it.id to it.name } }

    // Accueil : « Ajouts récents » et « Les mieux notés », puis une rangée par catégorie.
    val shelves = remember(catalog) {
        val byCategory = catalog.items.groupBy { it.categoryId }
        buildList {
            val recent = catalog.items.filter { it.added > 0 }.sortedByDescending { it.added }.take(SHELF_LIMIT)
            if (recent.size >= 5) add(VodShelf(SHELF_RECENT, "Ajouts récents", recent))
            val top = catalog.items
                .mapNotNull { item -> ratingLabel(item.rating)?.replace(',', '.')?.toDoubleOrNull()?.let { item to it } }
                .sortedByDescending { it.second }
                .take(SHELF_LIMIT)
                .map { it.first }
            if (top.size >= 5) add(VodShelf(SHELF_TOP, "Les mieux notés", top))
            catalog.categories.forEach { category ->
                byCategory[category.id]?.let { add(VodShelf(category.id, category.name, it)) }
            }
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

    // Le bandeau du haut et le fond suivent l'élément sélectionné.
    var focusedInfo by remember { mutableStateOf<HeroInfo?>(null) }
    val onFocusInfo: (HeroInfo) -> Unit = { focusedInfo = it }
    val initialInfo = remember(resume, shelves) {
        resume.firstOrNull()?.let { HeroInfo(it.title, "Reprendre à ${formatTime(it.positionMs)}", null, it.poster) }
            ?: shelves.firstOrNull()?.items?.firstOrNull()?.let { it.hero(categoryNames[it.categoryId]) }
    }
    val hero = focusedInfo ?: initialInfo
    val tint = rememberPosterTint(hero?.poster)

    // Le titre se masque quand on descend dans la liste.
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    AppShell(
        state = state,
        onSection = onSection,
        onQuery = onQuery,
        onSettings = onSettings,
        modifier = Modifier.appBackground(tint),
        headerVisible = gridMode || !scrolled || catalog.items.isEmpty(),
    ) {
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
                            rating = ratingLabel(item.rating),
                            onFocus = { onFocusInfo(item.hero(categoryNames[item.categoryId])) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "hero") { MediaHero(hero) }
                if (resume.isNotEmpty()) {
                    item(key = "resume") {
                        ResumeRow(resume, onResume, onFocusInfo)
                    }
                }
                items(shelves, key = { it.id }) { shelf ->
                    VodShelfRow(
                        shelf = shelf,
                        categoryNames = categoryNames,
                        onOpen = onOpen,
                        onSeeAll = { onSelectCategory(shelf.id) },
                        onFocusInfo = onFocusInfo,
                    )
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
private fun ResumeRow(
    entries: List<ResumeEntry>,
    onResume: (ResumeEntry) -> Unit,
    onFocusInfo: (HeroInfo) -> Unit,
) {
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
                    onFocus = {
                        onFocusInfo(HeroInfo(entry.title, "Reprendre à ${formatTime(entry.positionMs)}", null, entry.poster))
                    },
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}

@Composable
private fun VodShelfRow(
    shelf: VodShelf,
    categoryNames: Map<String, String>,
    onOpen: (VodItem) -> Unit,
    onSeeAll: () -> Unit,
    onFocusInfo: (HeroInfo) -> Unit,
) {
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
                    rating = ratingLabel(item.rating),
                    onFocus = { onFocusInfo(item.hero(categoryNames[item.categoryId])) },
                    modifier = Modifier.width(150.dp),
                )
            }
            if (shelf.items.size > SHELF_LIMIT && shelf.id != SHELF_RECENT && shelf.id != SHELF_TOP) {
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
    rating: String? = null,
    onFocus: () -> Unit = {},
) {
    var tileFocused by remember { mutableStateOf(false) }
    val titleColor by animateColorAsState(if (tileFocused) TextPrimary else TextSecondary, label = "titleColor")

    Column(
        modifier = modifier.onFocusChanged {
            tileFocused = it.hasFocus
            if (it.hasFocus) onFocus()
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FocusItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            focusScale = 1.08f,
            focusRing = true,
        ) {
            if (poster != null) {
                AsyncImage(
                    model = rememberImage(poster, 300, 450),
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
            if (rating != null) {
                Text(
                    "★ $rating",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
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
            color = titleColor,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Bandeau du haut : titre, infos et résumé de l'élément sélectionné. */
@Composable
private fun MediaHero(info: HeroInfo?) {
    Crossfade(
        targetState = info,
        animationSpec = tween(260, easing = AppleEase),
        modifier = Modifier.fillMaxWidth().height(124.dp),
        label = "hero",
    ) { shown ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        val info = shown
        if (info != null) {
            Text(
                info.title,
                color = TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (info.meta.isNotEmpty()) {
                Text(info.meta, color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (info.plot != null) {
                Text(
                    info.plot,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}
