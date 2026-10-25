package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import fr.poptv.player.data.TmdbPick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.border
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
import fr.poptv.player.data.VodCatalog
import fr.poptv.player.data.VodItem
import androidx.compose.foundation.lazy.LazyListState

private const val SHELF_LIMIT = 30

private const val SHELF_RECENT = "__recent"
private const val SHELF_TOP = "__top"
private const val SHELF_FAV = "__fav"

/** Ma liste : clés enregistrées (« m:id » / « s:id »), préfixe de la section affichée, et bascule. */
private class VodFavs(val keys: Set<String>, val prefix: String, val toggle: (String) -> Unit)

private val LocalVodFavs = androidx.compose.runtime.compositionLocalOf { VodFavs(emptySet(), "m:") { } }

private data class VodShelf(val id: String, val title: String, val items: List<VodItem>)

/** Contenu du bandeau du haut : l'élément sélectionné (titre, infos, résumé). */
private data class HeroInfo(val title: String, val meta: String, val plot: String?, val poster: String?)

private var moviesShelves: Pair<VodCatalog, List<VodShelf>>? = null
private var seriesShelves: Pair<VodCatalog, List<VodShelf>>? = null

/**
 * Les rangées sont calculées une seule fois par catalogue et gardées en mémoire : en revenant d'une
 * fiche, l'écran se reconstruit sans refaire ces calculs (tris sur tout le catalogue).
 */
private val shelvesLock = Any()

private fun shelvesFor(catalog: VodCatalog, isMovies: Boolean): List<VodShelf> {
    synchronized(shelvesLock) {
        val cached = if (isMovies) moviesShelves else seriesShelves
        if (cached != null && cached.first === catalog) return cached.second
        val built = buildShelves(catalog)
        if (isMovies) moviesShelves = catalog to built else seriesShelves = catalog to built
        return built
    }
}

/**
 * Calcule les rangées d'un catalogue en arrière-plan, avant l'ouverture de l'écran :
 * à l'ouverture, elles sont déjà prêtes (le résultat est gardé pour ce catalogue précis).
 */
fun precomputeShelves(catalog: VodCatalog, isMovies: Boolean) {
    if (catalog.items.isNotEmpty()) shelvesFor(catalog, isMovies)
}

private fun buildShelves(catalog: VodCatalog): List<VodShelf> {
    val byCategory = catalog.items.groupBy { it.categoryId }
    return buildList {
        val recent = catalog.items.filter { it.added > 0 }.sortedByDescending { it.added }.take(SHELF_LIMIT)
        if (recent.size >= 5) add(VodShelf(SHELF_RECENT, "Ajouts récents", recent))
        val top = catalog.items
            .mapNotNull { item -> cleanRating(item.rating)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }?.let { item to it } }
            .sortedByDescending { it.second }
            .take(SHELF_LIMIT)
            .map { it.first }
        if (top.size >= 5) add(VodShelf(SHELF_TOP, "Les mieux notés", top))
        catalog.categories.forEach { category ->
            byCategory[category.id]?.let { add(VodShelf(category.id, category.name, it)) }
        }
    }
}

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
    onToggleFavorite: (String) -> Unit,
    onRetry: () -> Unit,
    listState: LazyListState,
) {
    val isMovies = state.section == Section.MOVIES
    val catalog = if (isMovies) state.movies else state.series
    val loading = if (isMovies) state.moviesLoading else state.seriesLoading
    val kind = if (isMovies) "movie" else "episode"

    val resume = remember(state.resume, kind) { state.resume.filter { it.kind == kind } }

    val categoryNames = remember(catalog) { catalog.categories.associate { it.id to it.name } }

    // Accueil : « Ajouts récents » et « Les mieux notés », puis une rangée par catégorie.
    val shelves = remember(catalog) { shelvesFor(catalog, isMovies) }

    // Tendances TMDB retrouvées dans le catalogue : bandeau « À l'affiche » (films) et Top 10.
    val featured = if (isMovies) state.picks.featured else emptyList()
    val top = if (isMovies) state.picks.topMovies else state.picks.topSeries

    val favPrefix = if (isMovies) "m:" else "s:"
    val favItems = remember(catalog, state.favVod, favPrefix) {
        catalog.items.filter { (favPrefix + it.id) in state.favVod }
    }
    val vodFavs = remember(state.favVod, favPrefix, onToggleFavorite) { VodFavs(state.favVod, favPrefix, onToggleFavorite) }

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

    // Le fond prend la couleur de l'affiche sélectionnée.
    var focusedInfo by remember { mutableStateOf<HeroInfo?>(null) }
    val onFocusInfo: (HeroInfo) -> Unit = { focusedInfo = it }
    val initialInfo = remember(resume, shelves) {
        resume.firstOrNull()?.let { HeroInfo(it.title, "Reprendre à ${formatTime(it.positionMs)}", null, it.poster) }
            ?: shelves.firstOrNull()?.items?.firstOrNull()?.let { it.hero(categoryNames[it.categoryId]) }
    }
    val hero = focusedInfo ?: initialInfo
    val tint = rememberPosterTint(hero?.poster)

    // Le titre se masque quand on descend dans la liste.
    val scope = rememberCoroutineScope()

    // Bandeau « À l'affiche » : image affichée (change toutes les 8 s, sauf quand le bouton a le focus).
    var featIndex by remember { mutableIntStateOf(0) }
    var featPaused by remember { mutableStateOf(false) }
    LaunchedEffect(featured.size, featPaused) {
        while (featured.size > 1 && !featPaused) {
            delay(8_000)
            featIndex = (featIndex + 1) % featured.size
        }
    }
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    androidx.compose.runtime.CompositionLocalProvider(LocalVodFavs provides vodFavs) {
    AppShell(
        state = state,
        onSection = onSection,
        onQuery = onQuery,
        onSettings = onSettings,
        modifier = Modifier.appBackground(tint),
        headerVisible = gridMode || !scrolled || catalog.items.isEmpty(),
        backdrop = if (featured.isNotEmpty() && !gridMode && catalog.items.isNotEmpty()) {
            { FeaturedBackdrop(featured[featIndex.coerceIn(0, featured.lastIndex)], listState) }
        } else {
            null
        },
    ) {
        MediaTabs(catalog.categories, state.selectedCategory, onSelectCategory)

        when {
            catalog.items.isEmpty() -> when {
                state.mediaError != null -> EmptyState(
                    icon = Icons.Filled.Warning,
                    title = "Impossible de charger le catalogue",
                    message = state.mediaError,
                    error = true,
                    actionLabel = "Réessayer",
                    onAction = onRetry,
                )
                loading -> CatalogSkeleton()
                else -> EmptyState(
                    icon = if (isMovies) AppIcons.Movie else AppIcons.Library,
                    title = if (isMovies) "Aucun film pour l'instant" else "Aucune série pour l'instant",
                    message = "Le fournisseur n'en propose pas, ou toutes les catégories sont masquées " +
                        "(Réglages → Catégories). Actualise pour recharger le catalogue.",
                    actionLabel = "Actualiser",
                    onAction = onRetry,
                )
            }

            gridMode -> if (gridItems.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "Aucun résultat",
                    message = if (query.isNotEmpty()) "Rien ne correspond à « $query »." else "Cette catégorie ne contient aucun titre.",
                    actionLabel = if (query.isNotEmpty()) "Effacer la recherche" else "Retour à l'accueil",
                    onAction = { onQuery(""); onSelectCategory(CAT_ALL) },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = posterWidth()),
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
                            favKey = item.id,
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
                if (featured.isNotEmpty()) {
                    item(key = "featured") {
                        FeaturedHero(
                            pick = featured[featIndex.coerceIn(0, featured.lastIndex)],
                            count = featured.size,
                            index = featIndex,
                            onOpen = onOpen,
                            onFocusInfo = onFocusInfo,
                            onFocused = { scope.launch { listState.animateScrollToItem(0) } },
                            onPausedChange = { featPaused = it },
                        )
                    }
                }
                if (resume.isNotEmpty()) {
                    item(key = "resume") {
                        ResumeRow(resume, onResume, onFocusInfo)
                    }
                }
                if (favItems.isNotEmpty()) {
                    item(key = "favs") {
                        VodShelfRow(
                            shelf = VodShelf(SHELF_FAV, "Ma liste", favItems),
                            categoryNames = categoryNames,
                            onOpen = onOpen,
                            onSeeAll = {},
                            onFocusInfo = onFocusInfo,
                        )
                    }
                }
                if (top.size >= 3) {
                    item(key = "top10") {
                        TopRow(
                            title = if (isMovies) "Top 10 des films cette semaine" else "Top 10 des séries cette semaine",
                            picks = top,
                            categoryNames = categoryNames,
                            onOpen = onOpen,
                            onFocusInfo = onFocusInfo,
                        )
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
}

/** Image du bandeau « À l'affiche » : plein écran derrière l'en-tête, fondue vers le bas, qui défile avec la liste. */
@Composable
private fun FeaturedBackdrop(pick: TmdbPick, listState: LazyListState) {
    val compact = LocalCompact.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 300.dp else 470.dp)
            .graphicsLayer {
                // Quitte l'écran avec le défilement de la liste.
                translationY = -(if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 100_000f)
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Fondu vers le fond de l'écran sur le bas de l'image.
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        Crossfade(targetState = pick.backdrop, animationSpec = tween(700), label = "featuredImage") { image ->
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Voile sombre à gauche pour la lisibilité du titre.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xE605080D), Color(0x7305080D), Color(0x1A05080D))),
            ),
        )
    }
}

/** Texte et boutons du bandeau « À l'affiche », posés sur l'image plein écran. */
@Composable
private fun FeaturedHero(
    pick: TmdbPick,
    count: Int,
    index: Int,
    onOpen: (VodItem) -> Unit,
    onFocusInfo: (HeroInfo) -> Unit,
    onFocused: () -> Unit,
    onPausedChange: (Boolean) -> Unit,
) {
    val compact = LocalCompact.current
    val meta = listOfNotNull(pick.item.year, ratingLabel(pick.item.rating)?.let { "★ $it" }).joinToString("  ·  ")

    Box(modifier = Modifier.fillMaxWidth().height(if (compact) 150.dp else 280.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = ScreenPadding, bottom = 6.dp)
                .fillMaxWidth(0.58f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
        ) {
            Text(
                "À L'AFFICHE",
                color = Color.White,
                fontSize = if (compact) 9.sp else 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GlassFill)
                    .border(1.dp, GlassBorder, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text(
                pick.item.name,
                color = TextPrimary,
                fontSize = if (compact) 22.sp else 44.sp,
                lineHeight = if (compact) 24.sp else 46.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.5).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) Text(meta, color = TextSecondary, fontSize = if (compact) 11.sp else 16.sp, fontWeight = FontWeight.Medium)
            if (!compact && pick.overview != null) {
                Text(
                    pick.overview,
                    color = TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PrimaryButton(
                text = "Voir la fiche",
                onClick = { onOpen(pick.item) },
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.onFocusChanged {
                    onPausedChange(it.hasFocus)
                    if (it.hasFocus) {
                        onFocused()
                        onFocusInfo(HeroInfo(pick.item.name, meta, pick.overview, pick.item.poster))
                    }
                },
            )
        }
        if (count > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = ScreenPadding, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (i in 0 until count) {
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (i == index) 24.dp else 6.dp)
                            .background(if (i == index) AccentBrush else Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x66FFFFFF))), RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

/** Rangée « Top 10 » façon Netflix : gros numéro contourné devant chaque affiche. */
@Composable
private fun TopRow(
    title: String,
    picks: List<TmdbPick>,
    categoryNames: Map<String, String>,
    onOpen: (VodItem) -> Unit,
    onFocusInfo: (HeroInfo) -> Unit,
) {
    val compact = LocalCompact.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShelfTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            itemsIndexed(picks, key = { _, p -> p.item.id }) { i, pick ->
                Box {
                    Text(
                        "${i + 1}",
                        style = TextStyle(
                            fontSize = if (compact) 64.sp else 120.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xB3FFFFFF),
                            drawStyle = Stroke(width = 5f),
                            letterSpacing = (-4).sp,
                        ),
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    PosterTile(
                        title = pick.item.name,
                        poster = pick.item.poster,
                        onClick = { onOpen(pick.item) },
                        rating = ratingLabel(pick.item.rating),
                        onFocus = { onFocusInfo(pick.item.hero(categoryNames[pick.item.categoryId])) },
                        favKey = pick.item.id,
                        modifier = Modifier
                            .padding(start = if (i == 9) (if (compact) 56.dp else 104.dp) else (if (compact) 34.dp else 64.dp))
                            .width(posterWidth()),
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
        fontWeight = FontWeight.Bold,
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
                    aspect = 16f / 9f,
                    modifier = Modifier.width(if (LocalCompact.current) 158.dp else 236.dp),
                )
            }
        }
    }
}

/** Largeur d'une affiche : réduite sur téléphone. */
@Composable
private fun posterWidth() = if (LocalCompact.current) 88.dp else 150.dp

@Composable
private fun VodShelfRow(
    shelf: VodShelf,
    categoryNames: Map<String, String>,
    onOpen: (VodItem) -> Unit,
    onSeeAll: () -> Unit,
    onFocusInfo: (HeroInfo) -> Unit,
) {
    val visible = remember(shelf.items) { if (shelf.id == SHELF_FAV) shelf.items else shelf.items.take(SHELF_LIMIT) }

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
                    favKey = item.id,
                    modifier = Modifier.width(posterWidth()),
                )
            }
            if (shelf.items.size > SHELF_LIMIT && shelf.id != SHELF_RECENT && shelf.id != SHELF_TOP && shelf.id != SHELF_FAV) {
                item(key = "see-all") {
                    FocusItem(
                        onClick = onSeeAll,
                        modifier = Modifier.width(posterWidth()).aspectRatio(2f / 3f),
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

/** Affiche (2:3 par défaut, 16:9 pour « Reprendre ») avec le titre dessous et, si fourni, une barre de progression. */
@Composable
private fun PosterTile(
    title: String,
    poster: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    rating: String? = null,
    aspect: Float = 2f / 3f,
    onFocus: () -> Unit = {},
    favKey: String? = null,
) {
    val favs = LocalVodFavs.current
    val isFav = favKey != null && (favs.prefix + favKey) in favs.keys
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
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
            focusScale = 1.08f,
            focusRing = true,
            onLongClick = favKey?.let { k -> { favs.toggle(favs.prefix + k) } },
        ) {
            if (isFav) {
                Text(
                    "♥",
                    color = Color(0xFFFF3B30),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
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
                    fontWeight = FontWeight.ExtraBold,
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
            fontSize = if (LocalCompact.current) 11.sp else 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
