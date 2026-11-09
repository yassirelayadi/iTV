package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.poptv.player.CAT_ALL
import fr.poptv.player.CAT_FAV
import fr.poptv.player.UiState
import fr.poptv.player.data.Channel
import fr.poptv.player.data.EpgInfo
import fr.poptv.player.data.Section
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

private const val SHELF_LIMIT = 40
private const val CAT_EVERY = "__every"

@Composable
fun ChannelsScreen(
    state: UiState,
    epg: Map<String, EpgInfo>,
    onNeedEpg: (String) -> Unit,
    onSection: (Section) -> Unit,
    onSelectCategory: (String) -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (Channel, List<Channel>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSettings: () -> Unit,
    listState: LazyListState,
) {
    // Chaînes regardées récemment, la plus récente en premier.
    val recentChannels = remember(state.channels, state.recent) {
        val byId = state.channels.associateBy { it.id }
        state.recent.mapNotNull { byId[it] }
    }

    val query = state.query.trim()

    // Grille de cartes : les chaînes récentes par défaut (toutes s'il n'y en a pas encore), ou la catégorie choisie.
    val gridChannels = remember(state.channels, state.favorites, state.selectedCategory, query, recentChannels) {
        val base = when {
            state.selectedCategory == CAT_FAV -> state.channels.filter { it.id in state.favorites }
            state.selectedCategory == CAT_EVERY -> state.channels
            state.selectedCategory == CAT_ALL ->
                if (recentChannels.isNotEmpty() && query.isEmpty()) recentChannels else state.channels
            else -> state.channels.filter { it.categoryId == state.selectedCategory }
        }
        if (query.isEmpty()) base else base.filter { it.name.contains(query, ignoreCase = true) }
    }

    // Retour : quitte d'abord la catégorie ou la recherche, puis l'application.
    BackHandler(enabled = state.selectedCategory != CAT_ALL || state.query.isNotEmpty()) {
        onSelectCategory(CAT_ALL)
        onQuery("")
    }

    // Le titre se masque quand on descend dans la liste.
    val searchScope = androidx.compose.runtime.rememberCoroutineScope()

    AppShell(
        state = state,
        onSection = onSection,
        onQuery = onQuery,
        onSettings = onSettings,
        modifier = Modifier.appBackground(),
        headerVisible = true,
        alwaysHeader = false,
        onSearchRequest = { searchScope.launch { listState.scrollToItem(0) } },
    ) {
        CategoryTabs(state, hasRecent = recentChannels.isNotEmpty(), onSelect = onSelectCategory)

        if (gridChannels.isEmpty()) {
            when {
                state.loading -> CatalogSkeleton()
                state.selectedCategory == CAT_FAV && query.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Favorite,
                    title = "Aucun favori",
                    message = "Appuie longuement sur OK sur une chaîne (ou appui long au doigt) pour l'ajouter ici.",
                    actionLabel = "Voir toutes les chaînes",
                    onAction = { onSelectCategory(CAT_ALL) },
                )
                else -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = "Aucune chaîne trouvée",
                    message = if (query.isNotEmpty()) "Rien ne correspond à « $query »." else "Cette catégorie est vide.",
                    actionLabel = if (query.isNotEmpty()) "Effacer la recherche" else "Voir toutes les chaînes",
                    onAction = { onQuery(""); onSelectCategory(CAT_ALL) },
                )
            }
        } else if (!LocalCompact.current) {
            ChannelCardGrid(
                channels = gridChannels,
                epg = epg,
                onNeedEpg = onNeedEpg,
                favorites = state.favorites,
                onPlay = { onPlay(it, gridChannels) },
                onToggleFavorite = onToggleFavorite,
                listState = listState,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = tileWidth()),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(gridChannels, key = { it.id }) { channel ->
                    ChannelTile(
                        channel = channel,
                        info = epg[channel.id],
                        onNeedEpg = onNeedEpg,
                        favorite = channel.id in state.favorites,
                        onClick = { onPlay(channel, gridChannels) },
                        onLongClick = { onToggleFavorite(channel.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTabs(state: UiState, hasRecent: Boolean, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = CAT_ALL) {
            Pill(if (hasRecent) "Récents" else "Toutes", onClick = { onSelect(CAT_ALL) }, selected = state.selectedCategory == CAT_ALL)
        }
        if (hasRecent) {
            item(key = CAT_EVERY) {
                Pill("Toutes", onClick = { onSelect(CAT_EVERY) }, selected = state.selectedCategory == CAT_EVERY)
            }
        }
        item(key = CAT_FAV) {
            Pill("Favoris", onClick = { onSelect(CAT_FAV) }, selected = state.selectedCategory == CAT_FAV)
        }
        items(state.categories, key = { it.id }) { category ->
            Pill(
                category.name,
                onClick = { onSelect(category.id) },
                selected = state.selectedCategory == category.id,
            )
        }
    }
}

/** Largeur d'une carte de chaîne : réduite sur téléphone. */
@Composable
private fun tileWidth() = if (LocalCompact.current) 120.dp else 180.dp

/** Carte d'une chaîne : logo au centre, nom dessous, pastille « EN DIRECT ». */
@Composable
private fun ChannelTile(
    channel: Channel,
    info: EpgInfo?,
    onNeedEpg: (String) -> Unit,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(channel.id) { onNeedEpg(channel.id) }
    val nowSec = System.currentTimeMillis() / 1000
    val program = info?.now(nowSec)

    FocusItem(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.aspectRatio(4f / 3f),
        focusScale = 1.08f,
        focusRing = true,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = if (LocalCompact.current) 8.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(modifier = Modifier.size(if (LocalCompact.current) (if (program != null) 34.dp else 40.dp) else if (program != null) 50.dp else 58.dp), contentAlignment = Alignment.Center) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = rememberImage(channel.logo, 256, 256),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        channel.name.take(2).uppercase(),
                        color = TextSecondary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                channel.name,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (program != null) {
                Text(
                    program.title,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (program != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(program.progress(nowSec))
                        .fillMaxHeight()
                        .background(Color.White),
                )
            }
        }
        LiveBadge(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        if (favorite) {
            Text(
                "★",
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
    }
}

private const val CARD_COLUMNS = 4

/** Grille de cartes : lignes de 4, la carte qui a le focus s'élargit et lance l'aperçu en direct. */
@Composable
private fun ChannelCardGrid(
    channels: List<Channel>,
    epg: Map<String, EpgInfo>,
    onNeedEpg: (String) -> Unit,
    favorites: Set<String>,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    listState: LazyListState,
) {
    val rows = remember(channels) { channels.chunked(CARD_COLUMNS) }
    val context = LocalContext.current
    val preview = remember { PreviewPref.enabled(context) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(rows, key = { it.first().id }) { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { channel ->
                    ChannelCardSlot(
                        channel = channel,
                        info = epg[channel.id],
                        onNeedEpg = onNeedEpg,
                        favorite = channel.id in favorites,
                        preview = preview,
                        onClick = { onPlay(channel) },
                        onLongClick = { onToggleFavorite(channel.id) },
                    )
                }
                repeat(CARD_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Couleur d'une chaîne : stable, tirée de son nom (les logos n'ont pas de couleur de marque). */
private fun channelColor(name: String): Color {
    val hue = (kotlin.math.abs(name.lowercase().hashCode()) % 360).toFloat()
    return Color.hsv(hue, 0.55f, 0.42f)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ChannelCardSlot(
    channel: Channel,
    info: EpgInfo?,
    onNeedEpg: (String) -> Unit,
    favorite: Boolean,
    preview: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val grow by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1.55f else 1f,
        androidx.compose.animation.core.tween(Dur.Normal, easing = AppleEase),
        label = "cardGrow",
    )
    Box(modifier = Modifier.weight(grow).onFocusChanged { focused = it.hasFocus }) {
        ChannelCard(channel, info, onNeedEpg, favorite, preview, onClick, onLongClick)
    }
}

/** Carte d'une chaîne : logo, programme en cours avec progression ; l'aperçu vidéo démarre après 1 s de focus. */
@Composable
private fun ChannelCard(
    channel: Channel,
    info: EpgInfo?,
    onNeedEpg: (String) -> Unit,
    favorite: Boolean,
    preview: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    LaunchedEffect(channel.id) { onNeedEpg(channel.id) }
    val nowSec = System.currentTimeMillis() / 1000
    val program = info?.now(nowSec)
    val shape = RoundedCornerShape(14.dp)
    val color = remember(channel.name) { channelColor(channel.name) }

    FocusItem(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(118.dp),
        shape = shape,
        focusScale = 1.03f,
        focusRing = true,
        idleColor = Color.Transparent,
        focusedColor = Color.Transparent,
    ) { focused ->
        // Aperçu : un seul à la fois (celui de la carte au focus), démarré après une courte pause.
        var previewOn by androidx.compose.runtime.remember { mutableStateOf(false) }
        LaunchedEffect(focused, preview) {
            if (focused && preview) {
                delay(1_000)
                previewOn = true
            } else {
                previewOn = false
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(color, Color(0xFF0B0E14)))),
        )
        if (previewOn) {
            ChannelPreview(channel.streamUrl, Modifier.fillMaxSize())
        }
        // Voile en bas pour la lisibilité du texte, y compris sur la vidéo.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xCC000000)),
            ),
        )
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x59000000)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = rememberImage(channel.logo, 128, 128),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Text(channel.name.take(2).uppercase(), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (favorite) Text("★", color = Color.White, fontSize = 16.sp)
        }
        if (focused) {
            LiveBadge(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = if (program != null) 12.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                program?.title ?: channel.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (program != null) "${channel.name}  ·  ${formatClock(program.startSec)} – ${formatClock(program.endSec)}" else "En direct",
                color = Color(0xCCFFFFFF),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (program != null) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                    Box(Modifier.fillMaxWidth(program.progress(nowSec)).fillMaxHeight().background(Color.White))
                }
            }
        }
    }
}
