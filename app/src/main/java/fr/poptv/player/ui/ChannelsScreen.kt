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

private const val SHELF_LIMIT = 40
private const val CAT_RECENT = "__recent"

private data class Shelf(val id: String, val title: String, val channels: List<Channel>)

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

    // Accueil : « Favoris », « Récents », puis une rangée horizontale par catégorie.
    val shelves = remember(state.channels, state.categories, state.favorites, recentChannels) {
        val byCategory = state.channels.groupBy { it.categoryId }
        buildList {
            val favorites = state.channels.filter { it.id in state.favorites }
            if (favorites.isNotEmpty()) add(Shelf(CAT_FAV, "Favoris", favorites))
            if (recentChannels.isNotEmpty()) add(Shelf(CAT_RECENT, "Récents", recentChannels))
            state.categories.forEach { category ->
                byCategory[category.id]?.let { add(Shelf(category.id, category.name, it)) }
            }
        }
    }

    val query = state.query.trim()
    val gridMode = query.isNotEmpty() || state.selectedCategory != CAT_ALL || shelves.isEmpty()

    // Catégorie choisie ou recherche : grille de chaînes.
    val gridChannels = remember(state.channels, state.favorites, state.selectedCategory, query) {
        val base = when (state.selectedCategory) {
            CAT_ALL -> state.channels
            CAT_FAV -> state.channels.filter { it.id in state.favorites }
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
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val searchScope = androidx.compose.runtime.rememberCoroutineScope()

    AppShell(
        state = state,
        onSection = onSection,
        onQuery = onQuery,
        onSettings = onSettings,
        modifier = Modifier.appBackground(),
        headerVisible = gridMode || !scrolled,
        onSearchRequest = { searchScope.launch { listState.scrollToItem(0) } },
    ) {
        CategoryTabs(state, onSelectCategory)

        if (!gridMode) {
            val last = recentChannels.firstOrNull()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (last != null) {
                    item(key = "hero") {
                        val among = remember(state.channels, last) {
                            state.channels.filter { it.categoryId == last.categoryId }.ifEmpty { listOf(last) }
                        }
                        ResumeHero(
                            channel = last,
                            info = epg[last.id],
                            onNeedEpg = onNeedEpg,
                            onPlay = { onPlay(last, among) },
                        )
                    }
                }
                items(shelves, key = { it.id }) { shelf ->
                    ShelfRow(
                        shelf = shelf,
                        epg = epg,
                        onNeedEpg = onNeedEpg,
                        favorites = state.favorites,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        onSeeAll = { onSelectCategory(shelf.id) },
                    )
                }
            }
        } else if (gridChannels.isEmpty()) {
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
private fun CategoryTabs(state: UiState, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = CAT_ALL) {
            Pill("Accueil", onClick = { onSelect(CAT_ALL) }, selected = state.selectedCategory == CAT_ALL)
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

/** Bandeau du haut : la dernière chaîne regardée, avec un bouton pour la reprendre. */
@Composable
private fun ResumeHero(channel: Channel, info: EpgInfo?, onNeedEpg: (String) -> Unit, onPlay: () -> Unit) {
    LaunchedEffect(channel.id) { onNeedEpg(channel.id) }
    val nowSec = System.currentTimeMillis() / 1000
    val program = info?.now(nowSec)
    Row(
        modifier = Modifier
            .padding(horizontal = ScreenPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (LocalCompact.current) 18.dp else 26.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF1A5FB4), Color(0xFF0B1F3A))))
            .padding(horizontal = if (LocalCompact.current) 14.dp else 24.dp, vertical = if (LocalCompact.current) 10.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 14.dp else 22.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (LocalCompact.current) 52.dp else 84.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logo != null) {
                AsyncImage(
                    model = rememberImage(channel.logo, 256, 256),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            } else {
                Text(
                    channel.name.take(2).uppercase(),
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "DERNIÈRE CHAÎNE REGARDÉE",
                color = TextSecondary,
                fontSize = 13.sp,
                letterSpacing = 0.8.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    channel.name,
                    color = TextPrimary,
                    fontSize = if (LocalCompact.current) 18.sp else 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LiveBadge(fontSize = 13.sp)
            }
            if (program != null) {
                Text(
                    "${program.title}  ·  ${formatClock(program.startSec)} – ${formatClock(program.endSec)}",
                    color = TextPrimary,
                    fontSize = if (LocalCompact.current) 12.sp else 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x40FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(program.progress(nowSec))
                            .fillMaxHeight()
                            .background(Color.White),
                    )
                }
            }
        }
        PrimaryButton("Reprendre", onClick = onPlay, icon = Icons.Filled.PlayArrow)
    }
}

@Composable
private fun ShelfRow(
    shelf: Shelf,
    epg: Map<String, EpgInfo>,
    onNeedEpg: (String) -> Unit,
    favorites: Set<String>,
    onPlay: (Channel, List<Channel>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val visible = remember(shelf.channels) { shelf.channels.take(SHELF_LIMIT) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            shelf.title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(visible, key = { it.id }) { channel ->
                ChannelTile(
                    channel = channel,
                    info = epg[channel.id],
                    onNeedEpg = onNeedEpg,
                    favorite = channel.id in favorites,
                    onClick = { onPlay(channel, shelf.channels) },
                    onLongClick = { onToggleFavorite(channel.id) },
                    modifier = Modifier.width(tileWidth()),
                )
            }
            if (shelf.channels.size > SHELF_LIMIT && shelf.id != CAT_RECENT) {
                item(key = "see-all") {
                    FocusItem(
                        onClick = onSeeAll,
                        modifier = Modifier.width(tileWidth()).aspectRatio(4f / 3f),
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
