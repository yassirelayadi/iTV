package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import fr.poptv.player.data.Section

private val SidePadding = 48.dp
private const val SHELF_LIMIT = 40
private const val CAT_RECENT = "__recent"

private data class Shelf(val id: String, val title: String, val channels: List<Channel>)

@Composable
fun ChannelsScreen(
    state: UiState,
    onSection: (Section) -> Unit,
    onSelectCategory: (String) -> Unit,
    onQuery: (String) -> Unit,
    onPlay: (Channel, List<Channel>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSettings: () -> Unit,
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
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    AppShell(
        state = state,
        onSection = onSection,
        onQuery = onQuery,
        onSettings = onSettings,
        modifier = Modifier.appBackground(),
        headerVisible = gridMode || !scrolled,
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
                        ResumeHero(channel = last, onPlay = { onPlay(last, among) })
                    }
                }
                items(shelves, key = { it.id }) { shelf ->
                    ShelfRow(
                        shelf = shelf,
                        favorites = state.favorites,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        onSeeAll = { onSelectCategory(shelf.id) },
                    )
                }
            }
        } else if (gridChannels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        state.loading -> "Chargement…"
                        state.selectedCategory == CAT_FAV && query.isEmpty() ->
                            "Aucun favori. Appuie longuement sur OK sur une chaîne pour l'ajouter."
                        else -> "Aucune chaîne trouvée."
                    },
                    color = TextSecondary,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = SidePadding, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(gridChannels, key = { it.id }) { channel ->
                    ChannelTile(
                        channel = channel,
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
        contentPadding = PaddingValues(horizontal = SidePadding, vertical = 8.dp),
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

/** Bandeau du haut : la dernière chaîne regardée, avec un bouton pour la reprendre. */
@Composable
private fun ResumeHero(channel: Channel, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = SidePadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF2A3F6B), Color(0xFF1C1C1F))))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(22.dp))
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
                    fontWeight = FontWeight.Light,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "DERNIÈRE CHAÎNE REGARDÉE",
                color = TextSecondary,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    channel.name,
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LiveBadge(fontSize = 11.sp)
            }
        }
        PrimaryButton("Reprendre", onClick = onPlay, icon = Icons.Filled.PlayArrow)
    }
}

@Composable
private fun ShelfRow(
    shelf: Shelf,
    favorites: Set<String>,
    onPlay: (Channel, List<Channel>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val visible = remember(shelf.channels) { shelf.channels.take(SHELF_LIMIT) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            shelf.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = SidePadding),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = SidePadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(visible, key = { it.id }) { channel ->
                ChannelTile(
                    channel = channel,
                    favorite = channel.id in favorites,
                    onClick = { onPlay(channel, shelf.channels) },
                    onLongClick = { onToggleFavorite(channel.id) },
                    modifier = Modifier.width(180.dp),
                )
            }
            if (shelf.channels.size > SHELF_LIMIT && shelf.id != CAT_RECENT) {
                item(key = "see-all") {
                    FocusItem(
                        onClick = onSeeAll,
                        modifier = Modifier.width(180.dp).aspectRatio(4f / 3f),
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
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusItem(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.aspectRatio(4f / 3f),
        focusScale = 1.08f,
        focusRing = true,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
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
                        fontWeight = FontWeight.Light,
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
        }
        LiveBadge(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        if (favorite) {
            Text(
                "★",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
    }
}
