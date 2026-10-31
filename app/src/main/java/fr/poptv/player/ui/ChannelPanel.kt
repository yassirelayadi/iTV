package fr.poptv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Liste des chaînes par-dessus le direct : on parcourt les autres chaînes (par catégorie ou favoris)
 * sans quitter la lecture. OK sur une chaîne la lance ; Droite ou Retour referme le panneau.
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
    val list = remember(selected, channels, favorites) {
        when (selected) {
            CAT_ALL -> channels
            CAT_FAV -> channels.filter { it.id in favorites }
            else -> channels.filter { it.categoryId == selected }
        }
    }
    val chips = remember(categories, favorites) {
        buildList {
            add(CAT_ALL to "Toutes")
            if (favorites.isNotEmpty()) add(CAT_FAV to "Favoris")
            categories.forEach { add(it.id to it.name) }
        }
    }
    val listState = rememberLazyListState()
    val chipsState = rememberLazyListState(initialFirstVisibleItemIndex = chips.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    val firstChipFocus = remember { FocusRequester() }

    // À l'ouverture : la chaîne en cours est visible et sélectionnée.
    LaunchedEffect(Unit) {
        val i = list.indexOfFirst { it.id == current.id }
        if (i >= 0) {
            listState.scrollToItem((i - 2).coerceAtLeast(0))
            delay(80)
            runCatching { currentFocus.requestFocus() }
        } else {
            runCatching { firstChipFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .width(if (compact) 300.dp else 420.dp)
            .fillMaxHeight()
            .background(Color(0xE60A0C10))
            .padding(top = 20.dp, bottom = 12.dp)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Chaînes",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            state = chipsState,
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(chips, key = { it.first }) { (id, label) ->
                Pill(
                    text = label,
                    onClick = { selected = id },
                    selected = id == selected,
                    modifier = if (id == chips.first().first) Modifier.focusRequester(firstChipFocus) else Modifier,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(list, key = { it.id }) { channel ->
                val isCurrent = channel.id == current.id
                LaunchedEffect(channel.id) { onNeedEpg(channel.id) }
                FocusItem(
                    onClick = { onPick(channel, list) },
                    modifier = Modifier.fillMaxWidth().then(if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier),
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
