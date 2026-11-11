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
                    icon = AppIcons.Heart,
                    title = "Aucun favori",
                    message = "Appuie longuement sur OK sur une chaîne (ou appui long au doigt) pour l'ajouter ici.",
                    actionLabel = "Voir toutes les chaînes",
                    onAction = { onSelectCategory(CAT_ALL) },
                )
                else -> EmptyState(
                    icon = AppIcons.Search,
                    title = "Aucune chaîne trouvée",
                    message = if (query.isNotEmpty()) "Rien ne correspond à « $query »." else "Cette catégorie est vide.",
                    actionLabel = if (query.isNotEmpty()) "Effacer la recherche" else "Voir toutes les chaînes",
                    onAction = { onQuery(""); onSelectCategory(CAT_ALL) },
                )
            }
        } else if (!LocalCompact.current) {
            ChannelListWithPreview(
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
                        fontSize = TypeSize.Title2,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                channel.name,
                color = TextPrimary,
                fontSize = TypeSize.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (program != null) {
                Text(
                    program.title,
                    color = TextSecondary,
                    fontSize = TypeSize.Caption,
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
                fontSize = TypeSize.Title3,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
    }
}

/** Couleur d'une chaîne : stable, tirée de son nom (les logos n'ont pas de couleur de marque). */
private fun channelColor(name: String): Color {
    val hue = (kotlin.math.abs(name.lowercase().hashCode()) % 360).toFloat()
    return Color.hsv(hue, 0.55f, 0.42f)
}

/** Liste des chaînes à gauche ; à droite, l'aperçu en direct et le programme de la chaîne sélectionnée. */
@Composable
private fun ChannelListWithPreview(
    channels: List<Channel>,
    epg: Map<String, EpgInfo>,
    onNeedEpg: (String) -> Unit,
    favorites: Set<String>,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val previewEnabled = remember { PreviewPref.enabled(context) }
    // focusedId : chaîne actuellement sélectionnée (null si le focus est hors de la liste) ; shownId : la dernière, gardée à l'écran.
    var focusedId by remember { mutableStateOf<String?>(null) }
    var shownId by remember { mutableStateOf<String?>(null) }
    val shown = channels.firstOrNull { it.id == shownId } ?: channels.first()

    Row(modifier = Modifier.fillMaxSize().padding(end = ScreenPadding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(0.56f).fillMaxHeight(),
            contentPadding = PaddingValues(start = ScreenPadding, top = 6.dp, bottom = 14.dp, end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                ChannelRow(
                    channel = channel,
                    info = epg[channel.id],
                    onNeedEpg = onNeedEpg,
                    favorite = channel.id in favorites,
                    onFocusChange = { focused ->
                        if (focused) {
                            focusedId = channel.id
                            shownId = channel.id
                        } else if (focusedId == channel.id) {
                            focusedId = null
                        }
                    },
                    onClick = { onPlay(channel) },
                    onLongClick = { onToggleFavorite(channel.id) },
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        ChannelPreviewPanel(
            channel = shown,
            info = epg[shown.id],
            previewOn = previewEnabled && focusedId != null,
            modifier = Modifier.weight(0.44f).fillMaxHeight().padding(top = 8.dp),
        )
    }
}

/** Une ligne : logo, programme en cours, chaîne et horaires, progression. */
@Composable
private fun ChannelRow(
    channel: Channel,
    info: EpgInfo?,
    onNeedEpg: (String) -> Unit,
    favorite: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    LaunchedEffect(channel.id) { onNeedEpg(channel.id) }
    val nowSec = System.currentTimeMillis() / 1000
    val program = info?.now(nowSec)
    val color = remember(channel.name) { channelColor(channel.name) }

    FocusItem(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(74.dp),
        shape = RoundedCornerShape(Radius.L),
        focusScale = 1.02f,
        focusRing = true,
        idleColor = Color.Transparent,
    ) { focused ->
        LaunchedEffect(focused) { onFocusChange(focused) }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(Radius.M)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = rememberImage(channel.logo, 128, 128),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    Text(channel.name.take(2).uppercase(), color = Color.White, fontSize = TypeSize.Body, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    program?.title ?: channel.name,
                    color = TextPrimary,
                    fontSize = TypeSize.Title3,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (program != null) "${channel.name}  ·  ${formatClock(program.startSec)} – ${formatClock(program.endSec)}" else "En direct",
                    color = TextSecondary,
                    fontSize = TypeSize.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (program != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(program.progress(nowSec)).fillMaxHeight().background(Accent))
                    }
                }
            }
            if (favorite) Text("★", color = Color.White, fontSize = TypeSize.Body)
        }
    }
}

/** Panneau de droite : aperçu vidéo (après 1 s de focus), programme en cours et suivant. */
@Composable
private fun ChannelPreviewPanel(channel: Channel, info: EpgInfo?, previewOn: Boolean, modifier: Modifier) {
    val nowSec = System.currentTimeMillis() / 1000
    val program = info?.now(nowSec)
    val next = info?.next(nowSec)
    val color = remember(channel.name) { channelColor(channel.name) }

    // Un seul flux à la fois : l'aperçu démarre après une courte pause sur la chaîne et s'arrête dès qu'on la quitte.
    var videoOn by remember(channel.id) { mutableStateOf(false) }
    LaunchedEffect(channel.id, previewOn) {
        if (previewOn) {
            delay(1_000)
            videoOn = true
        } else {
            videoOn = false
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Radius.L))
                .background(Brush.linearGradient(listOf(color, Color(0xFF0B0E14)))),
        ) {
            if (videoOn) ChannelPreview(channel.streamUrl, Modifier.fillMaxSize())
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).size(40.dp)
                    .clip(RoundedCornerShape(Radius.M)).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = rememberImage(channel.logo, 128, 128),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(5.dp),
                    )
                } else {
                    Text(channel.name.take(2).uppercase(), color = Color.White, fontSize = TypeSize.Body, fontWeight = FontWeight.SemiBold)
                }
            }
            LiveBadge(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Text(
            if (program != null) "EN CE MOMENT  ·  ${formatClock(program.startSec)} – ${formatClock(program.endSec)}" else "EN DIRECT",
            color = Accent,
            fontSize = TypeSize.Caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        )
        Text(
            program?.title ?: channel.name,
            color = TextPrimary,
            fontSize = TypeSize.Title2,
            lineHeight = LineHeight.Title2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (program != null) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(program.progress(nowSec)).fillMaxHeight().background(Accent))
            }
        }
        if (next != null) {
            Text(
                "Ensuite  ·  ${next.title}  ·  ${formatClock(next.startSec)}",
                color = TextSecondary,
                fontSize = TypeSize.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
