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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.focus.focusRequester
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
import fr.poptv.player.data.CatalogDb
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
private const val SHELF_FORYOU = "__foryou"

/** Ma liste : clés enregistrées (« m:id » / « s:id »), préfixe de la section affichée, et bascule. */
private class VodFavs(val keys: Set<String>, val prefix: String, val toggle: (String) -> Unit)

private val LocalVodFavs = androidx.compose.runtime.compositionLocalOf { VodFavs(emptySet(), "m:") { } }

private data class VodShelf(val id: String, val title: String, val items: List<VodItem>)

/** Contenu du bandeau du haut : l'élément sélectionné (titre, infos, résumé). */
private data class HeroInfo(val title: String, val meta: String, val plot: String?, val poster: String?)

private var moviesShelves: Pair<VodCatalog, List<VodShelf>>? = null
private var seriesShelves: Pair<VodCatalog, List<VodShelf>>? = null

/**
 * Les rangées sont lues dans la base (CatalogDb) une seule fois par catalogue et gardées en mémoire :
 * en revenant d'une fiche, l'écran se reconstruit sans refaire ces requêtes. Chaque rangée ne contient
 * que ses premiers titres, jamais le catalogue entier.
 */
private val shelvesLock = Any()

private fun cachedShelves(catalog: VodCatalog, isMovies: Boolean): List<VodShelf>? = synchronized(shelvesLock) {
    (if (isMovies) moviesShelves else seriesShelves)?.takeIf { it.first === catalog }?.second
}

private fun shelvesFor(catalog: VodCatalog, hidden: Set<String>, isMovies: Boolean): List<VodShelf> {
    synchronized(shelvesLock) {
        cachedShelves(catalog, isMovies)?.let { return it }
        val built = buildShelves(catalog, hidden, if (isMovies) Section.MOVIES else Section.SERIES)
        if (isMovies) moviesShelves = catalog to built else seriesShelves = catalog to built
        return built
    }
}

/**
 * Calcule les rangées d'un catalogue en arrière-plan, avant l'ouverture de l'écran :
 * à l'ouverture, elles sont déjà prêtes (le résultat est gardé pour ce catalogue précis).
 */
fun precomputeShelves(catalog: VodCatalog, hidden: Set<String>, isMovies: Boolean) {
    if (catalog.size > 0) shelvesFor(catalog, hidden, isMovies)
}

private fun buildShelves(catalog: VodCatalog, hidden: Set<String>, section: Section): List<VodShelf> = buildList {
    val recent = CatalogDb.recent(section, hidden, SHELF_LIMIT)
    if (recent.size >= 5) add(VodShelf(SHELF_RECENT, "Ajouts récents", recent))
    val top = CatalogDb.top(section, hidden, SHELF_LIMIT)
    if (top.size >= 5) add(VodShelf(SHELF_TOP, "Les mieux notés", top))
    catalog.categories.forEach { category ->
        // Un titre de plus que la limite : sert à savoir s'il faut proposer « Tout voir ».
        val items = CatalogDb.inCategory(section, category.id, SHELF_LIMIT + 1)
        if (items.isNotEmpty()) add(VodShelf(category.id, category.name, items))
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
    val hidden = if (isMovies) state.hiddenMovies else state.hiddenSeries
    val section = if (isMovies) Section.MOVIES else Section.SERIES
    val shelvesState = androidx.compose.runtime.produceState<List<VodShelf>?>(cachedShelves(catalog, isMovies), catalog) {
        if (value == null && catalog.size > 0) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { shelvesFor(catalog, hidden, isMovies) }
        }
    }
    val shelvesReady = shelvesState.value != null || catalog.size == 0
    val shelves = shelvesState.value ?: emptyList()
    // Préchargement des affiches des premières rangées : elles sont déjà en cache quand on défile.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(shelves) {
        val loader = coil.Coil.imageLoader(context)
        shelves.take(5).flatMap { it.items.take(12) }.mapNotNull { it.poster }.distinct().forEach { url ->
            loader.enqueue(
                coil.request.ImageRequest.Builder(context).data(url).size(300, 450).build(),
            )
        }
    }

    // Tendances TMDB retrouvées dans le catalogue : bandeau « À l'affiche » (films) et Top 10.
    val featured = if (isMovies) state.picks.featured else state.picks.featuredSeries
    val top = if (isMovies) state.picks.topMovies else state.picks.topSeries

    val favPrefix = if (isMovies) "m:" else "s:"
    val favItemsState = androidx.compose.runtime.produceState<List<VodItem>>(emptyList(), catalog, state.favVod, favPrefix) {
        val ids = state.favVod.filter { it.startsWith(favPrefix) }.map { it.removePrefix(favPrefix) }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CatalogDb.byIds(section, ids).filter { it.categoryId !in hidden }
        }
    }
    val favItems = favItemsState.value
    val vodFavs = remember(state.favVod, favPrefix, onToggleFavorite) { VodFavs(state.favVod, favPrefix, onToggleFavorite) }

    val query = state.query.trim()
    val gridMode = query.isNotEmpty() || state.selectedCategory != CAT_ALL || (shelvesReady && shelves.isEmpty())

    // Grille : requête dans la base, après une courte pause pendant la frappe (pas une requête par lettre).
    val gridState = androidx.compose.runtime.produceState<List<VodItem>?>(null, catalog, state.selectedCategory, query, gridMode) {
        if (!gridMode) return@produceState
        if (query.isNotEmpty()) delay(250)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CatalogDb.grid(section, hidden, state.selectedCategory.takeIf { it != CAT_ALL }, query, 5000)
        }
    }
    val gridItems = gridState.value

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
        headerVisible = gridMode || !scrolled || catalog.size == 0,
        onSearchRequest = { scope.launch { listState.scrollToItem(0) } },
        backdrop = if (featured.isNotEmpty() && !gridMode && catalog.size > 0) {
            { FeaturedBackdrop(featured[featIndex.coerceIn(0, featured.lastIndex)], listState) }
        } else {
            null
        },
    ) {

        when {
            catalog.size == 0 -> when {
                state.mediaError != null -> EmptyState(
                    icon = Icons.Filled.Warning,
                    title = "Impossible de charger le catalogue",
                    message = state.mediaError,
                    error = true,
                    actionLabel = if (loading) "Chargement…" else "Réessayer",
                    onAction = { if (!loading) onRetry() },
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

            !shelvesReady && !gridMode -> CatalogSkeleton()

            gridMode -> if (gridItems == null) {
                Box(Modifier.fillMaxSize())
            } else if (gridItems.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "Aucun résultat",
                    message = if (query.isNotEmpty()) "Rien ne correspond à « $query »." else "Cette catégorie ne contient aucun titre.",
                    actionLabel = if (query.isNotEmpty()) "Effacer la recherche" else "Retour à l'accueil",
                    onAction = { onQuery(""); onSelectCategory(CAT_ALL) },
                )
            } else {
                androidx.compose.runtime.CompositionLocalProvider(LocalRowId provides "__grid") {
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
                val forYou = if (isMovies) state.forYouMovies else state.forYouSeries
                if (forYou.size >= 5) {
                    item(key = "foryou") {
                        VodShelfRow(
                            shelf = VodShelf(SHELF_FORYOU, "Pour vous", forYou),
                            categoryNames = categoryNames,
                            onOpen = onOpen,
                            onSeeAll = {},
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
                model = rememberImage(image, 1280, 720),
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

    // Hauteur fixe, dimensionnée pour le pire cas (titre sur 2 lignes + 2 lignes de résumé + bouton) :
    // les rangées en dessous ne bougent pas quand le bandeau change de film.
    Box(modifier = Modifier.fillMaxWidth().height(if (compact) 170.dp else 320.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = ScreenPadding, end = ScreenPadding, bottom = 6.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
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
            // Titre plus petit quand il est long, pour tenir sur deux lignes sans écraser le reste.
            val longTitle = pick.item.name.length > 24
            Text(
                pick.item.name,
                color = TextPrimary,
                fontSize = if (compact) (if (longTitle) 17.sp else 20.sp) else (if (longTitle) 30.sp else 38.sp),
                lineHeight = if (compact) (if (longTitle) 19.sp else 22.sp) else (if (longTitle) 32.sp else 40.sp),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.5).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            if (meta.isNotEmpty()) Text(meta, color = TextSecondary, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Medium)
            if (!compact && pick.overview != null) {
                Text(
                    pick.overview,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val heroFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val restoreTick = FocusMemory.tick.intValue
            LaunchedEffect(restoreTick) {
                if (FocusMemory.restore && FocusMemory.key == "__hero") {
                    FocusMemory.restore = false
                    runCatching { heroFocus.requestFocus() }
                }
            }
            PrimaryButton(
                text = "Voir la fiche",
                onClick = {
                    FocusMemory.key = "__hero"
                    onOpen(pick.item)
                },
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth(if (compact) 0.6f else 0.34f).focusRequester(heroFocus).onFocusChanged {
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
    androidx.compose.runtime.CompositionLocalProvider(LocalRowId provides "__top") {
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
    // Une seule vignette par série (l'épisode le plus récent), avec l'affiche de la série.
    val shown = remember(entries) {
        val seen = HashSet<String>()
        entries.filter { e -> seen.add(resumeGroup(e)) }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalRowId provides "__resume") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShelfTitle("Reprendre")
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(shown, key = { resumeGroup(it) }) { entry ->
                val isEpisode = entry.kind == "episode"
                val name = if (isEpisode) entry.title.substringBeforeLast(" · ", entry.title) else entry.title
                val episodeLabel = if (isEpisode) entry.title.substringAfterLast(" · ", "") else ""
                PosterTile(
                    title = name,
                    poster = entry.poster,
                    progress = if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else null,
                    onClick = { onResume(entry) },
                    onFocus = {
                        val at = "Reprendre à ${formatTime(entry.positionMs)}"
                        onFocusInfo(HeroInfo(name, if (episodeLabel.isNotEmpty()) "$episodeLabel · $at" else at, null, entry.poster))
                    },
                    modifier = Modifier.width(posterWidth()),
                )
            }
        }
    }
    }
}

/** Clé de regroupement de « Reprendre » : une série = une entrée, un film = lui-même. */
private fun resumeGroup(e: ResumeEntry): String =
    if (e.kind == "episode") "s:" + e.title.substringBeforeLast(" · ", e.title) else e.key

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

    androidx.compose.runtime.CompositionLocalProvider(LocalRowId provides shelf.id) {
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
            if (shelf.items.size > SHELF_LIMIT && shelf.id != SHELF_RECENT && shelf.id != SHELF_TOP && shelf.id != SHELF_FAV && shelf.id != SHELF_FORYOU) {
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
    val memoryKey = LocalRowId.current + "|" + title + "|" + (poster ?: "")
    val tileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val restoreTick = FocusMemory.tick.intValue
    LaunchedEffect(restoreTick) {
        if (FocusMemory.restore && FocusMemory.key == memoryKey) {
            FocusMemory.restore = false
            runCatching { tileFocus.requestFocus() }
        }
    }
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
            onClick = {
                FocusMemory.key = memoryKey
                onClick()
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).focusRequester(tileFocus),
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
