package fr.poptv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.poptv.player.CAT_ALL
import fr.poptv.player.CAT_FAV
import fr.poptv.player.data.Category
import fr.poptv.player.data.Channel
import fr.poptv.player.data.EpgInfo
import kotlinx.coroutines.delay

/**
 * Liste des chaînes par-dessus le direct, sur deux niveaux dans le même panneau : les chaînes de la catégorie
 * en cours, et (Gauche) la liste verticale des catégories. OK lance une chaîne ; Droite ou Retour referme.
 */
@Composable
fun ChannelPanel(
    current: Channel,
    channels: List<Channel>,
    categories: List<Category>,
    favorites: Set<String>,
    epg: Map<String, EpgInfo>,
    onNeedEpg: (String) -> Unit,
    onPick: (Channel, List<Channel>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompact.current
    var selected by remember { mutableStateOf(if (categories.any { it.id == current.categoryId }) current.categoryId else CAT_ALL) }
    var showCategories by remember { mutableStateOf(false) }
    val list = remember(selected, channels, favorites) {
        when (selected) {
            CAT_ALL -> channels
            CAT_FAV -> channels.filter { it.id in favorites }
            else -> channels.filter { it.categoryId == selected }
        }
    }
    // Catégories avec leur nombre de chaînes : « Toutes », « Favoris », puis les catégories du fournisseur.
    val entries = remember(categories, channels, favorites) {
        val counts = channels.groupingBy { it.categoryId }.eachCount()
        buildList {
            add(Triple(CAT_ALL, "Toutes les chaînes", channels.size))
            val fav = channels.count { it.id in favorites }
            if (fav > 0) add(Triple(CAT_FAV, "Favoris", fav))
            categories.forEach { c -> counts[c.id]?.let { add(Triple(c.id, c.name, it)) } }
        }
    }
    val selectedLabel = entries.firstOrNull { it.first == selected }?.second ?: "Toutes les chaînes"
    val listState = rememberLazyListState()
    val catState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val catFocus = remember { FocusRequester() }
    val currentInList = list.any { it.id == current.id }

    // Retour : depuis les catégories, on revient aux chaînes (le panneau reste ouvert).
    BackHandler(enabled = showCategories) { showCategories = false }

    // À chaque changement de niveau : l'élément utile est visible et a le focus.
    LaunchedEffect(showCategories) {
        if (showCategories) {
            val i = entries.indexOfFirst { it.first == selected }.coerceAtLeast(0)
            catState.scrollToItem((i - 2).coerceAtLeast(0))
            delay(80)
            runCatching { catFocus.requestFocus() }
        } else {
            val i = list.indexOfFirst { it.id == current.id }
            listState.scrollToItem(if (i >= 0) (i - 2).coerceAtLeast(0) else 0)
            delay(80)
            runCatching { if (i >= 0) currentFocus.requestFocus() else firstFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .width(if (compact) 300.dp else 420.dp)
            .fillMaxHeight()
            .background(Color(0xE60A0C10))
            .padding(top = 20.dp, bottom = 12.dp)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> if (!showCategories) { showCategories = true; true } else false
                    Key.DirectionRight -> { if (showCategories) showCategories = false else onClose(); true }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .clickable { showCategories = !showCategories },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                if (showCategories) "Catégories" else selectedLabel,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (showCategories) "OK : choisir   ·   Droite : retour" else "Gauche : changer de catégorie",
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }
        if (showCategories) {
            LazyColumn(
                state = catState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = { it.first }) { (id, label, count) ->
                    val isSelected = id == selected
                    FocusItem(
                        onClick = { selected = id; showCategories = false },
                        modifier = Modifier.fillMaxWidth().then(if (isSelected) Modifier.focusRequester(catFocus) else Modifier),
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1f,
                        idleColor = if (isSelected) GlassFill else Color.Transparent,
                        focusedColor = Color.White,
                    ) { focused ->
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                label,
                                color = if (focused) Color.Black else TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                count.toString(),
                                color = if (focused) Color(0xFF444B55) else TextSecondary,
                                fontSize = 13.sp,
                            )
                            if (isSelected) {
                                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(list, key = { _, c -> c.id }) { index, channel ->
                    val isCurrent = channel.id == current.id
                    LaunchedEffect(channel.id) { onNeedEpg(channel.id) }
                    val requester = when {
                        isCurrent -> Modifier.focusRequester(currentFocus)
                        index == 0 && !currentInList -> Modifier.focusRequester(firstFocus)
                        else -> Modifier
                    }
                    FocusItem(
                        onClick = { onPick(channel, list) },
                        modifier = Modifier.fillMaxWidth().then(requester),
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1f,
                        idleColor = if (isCurrent) GlassFill else Color.Transparent,
                        focusedColor = Color.White,
                    ) { focused ->
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Surface1),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (channel.logo != null) {
                                    AsyncImage(
                                        model = rememberImage(channel.logo, 128, 128),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    channel.name,
                                    color = if (focused) Color.Black else TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val program = epg[channel.id]?.now(System.currentTimeMillis() / 1000)?.title
                                if (program != null) {
                                    Text(
                                        program,
                                        color = if (focused) Color(0xFF444B55) else TextSecondary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (isCurrent) {
                                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
                            }
                        }
                    }
                }
            }
        }
    }
}
