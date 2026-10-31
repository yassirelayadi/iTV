package fr.poptv.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

internal data class TrackOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

private fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH)
    if (name.isBlank()) return code
    return name.replaceFirstChar { it.uppercase() }
}

private fun channelsName(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "Mono"
    count == 2 -> "Stéréo"
    count == 6 -> "5.1"
    count == 8 -> "7.1"
    else -> "$count canaux"
}

@UnstableApi
internal fun audioOptions(player: Player, tracks: Tracks): List<TrackOption> {
    val out = mutableListOf<TrackOption>()
    var n = 0
    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            n++
            val format = group.getTrackFormat(i)
            val label = listOfNotNull(languageName(format.language) ?: format.label, channelsName(format.channelCount))
                .joinToString(" · ")
                .ifEmpty { "Piste $n" }
            out += TrackOption(label, group.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    return out
}

@UnstableApi
internal fun subtitleOptions(player: Player, tracks: Tracks): List<TrackOption> {
    val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    if (groups.isEmpty()) return emptyList()
    val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val anySelected = groups.any { it.isSelected }
    val out = mutableListOf<TrackOption>()
    out += TrackOption("Désactivés", disabled || !anySelected) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
    var n = 0
    groups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            n++
            val format = group.getTrackFormat(i)
            val label = languageName(format.language) ?: format.label ?: "Sous-titres $n"
            out += TrackOption(label, !disabled && group.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    return out
}

/** Qualités vidéo proposées par le flux (flux HLS à plusieurs débits). Vide pour un fichier simple. */
@UnstableApi
internal fun qualityOptions(player: Player, tracks: Tracks): List<TrackOption> {
    class Candidate(val group: Tracks.Group, val index: Int, val height: Int, val bitrate: Int)

    val candidates = mutableListOf<Candidate>()
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            candidates += Candidate(group, i, format.height.coerceAtLeast(0), format.bitrate.coerceAtLeast(0))
        }
    }
    if (candidates.size < 2) return emptyList()

    val manual = player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
    val out = mutableListOf<TrackOption>()
    out += TrackOption("Automatique", !manual) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
    }
    candidates.sortedWith(compareByDescending<Candidate> { it.height }.thenByDescending { it.bitrate })
        .forEach { c ->
            val label = buildString {
                append(if (c.height > 0) "${c.height}p" else "Qualité")
                if (c.bitrate > 0) append(" · ${"%.1f".format(Locale.FRANCE, c.bitrate / 1_000_000f)} Mb/s")
            }
            out += TrackOption(label, manual && c.group.isTrackSelected(c.index)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index))
                    .build()
            }
        }
    return out
}

internal fun speedOptions(player: Player): List<TrackOption> =
    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { speed ->
        val label = if (speed == 1f) {
            "Normale"
        } else {
            String.format(Locale.FRANCE, "%.2f", speed).trimEnd('0').trimEnd(',') + "×"
        }
        TrackOption(label, player.playbackParameters.speed == speed) { player.setPlaybackSpeed(speed) }
    }

/** Une section du panneau latéral du lecteur (Audio, Sous-titres, Qualité, Vitesse…). */
internal class PanelSection(val title: String, val icon: ImageVector, val options: List<TrackOption>)

private sealed interface PanelRow {
    class Header(val title: String, val icon: ImageVector) : PanelRow
    class Choice(val option: TrackOption) : PanelRow
}

/**
 * Panneau latéral sur toute la hauteur, à droite : toutes les options du lecteur d'un coup.
 * Le focus démarre sur le choix actuel de la première section ; une coche marque chaque choix actif.
 */
@Composable
internal fun PlayerSidePanel(
    sections: List<PanelSection>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = sections.flatMap { section ->
        listOf<PanelRow>(PanelRow.Header(section.title, section.icon)) + section.options.map { PanelRow.Choice(it) }
    }
    val initial = rows.indexOfFirst { it is PanelRow.Choice && it.option.selected }
        .takeIf { it >= 0 } ?: rows.indexOfFirst { it is PanelRow.Choice }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        if (runCatching { firstFocus.requestFocus() }.isFailure) {
            listState.scrollToItem(initial.coerceAtLeast(0))
            delay(60)
            runCatching { firstFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(Color(0xF505080D))
            .padding(horizontal = 22.dp, vertical = 30.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(rows) { index, row ->
                when (row) {
                    is PanelRow.Header -> Row(
                        modifier = Modifier.padding(start = 8.dp, top = if (index == 0) 0.dp else 18.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(row.icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                        Text(row.title, color = TextSecondary, fontSize = 15.sp)
                    }

                    is PanelRow.Choice -> FocusItem(
                        onClick = {
                            row.option.onSelect()
                            onClose()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == initial) Modifier.focusRequester(firstFocus) else Modifier),
                        shape = RoundedCornerShape(14.dp),
                        focusScale = 1.03f,
                        idleColor = Color.Transparent,
                        focusedColor = Color.White,
                    ) { focused ->
                        val color = if (focused) Color.Black else TextPrimary
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.option.label,
                                color = color,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (row.option.selected) {
                                Icon(AppIcons.Check, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Carte « Épisode suivant » affichée pendant le générique, avec un compte à rebours. */
@Composable
internal fun NextEpisodeCard(
    label: String?,
    poster: String?,
    seconds: Int,
    total: Int,
    onPlay: () -> Unit,
    onClose: () -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .width(460.dp)
            .background(Color(0xF0101A26), RoundedCornerShape(14.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2),
        ) {
            if (poster != null) {
                AsyncImage(
                    model = rememberImage(poster, 300, 170),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x59FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(((total - seconds + 1).toFloat() / total).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Épisode suivant · dans $seconds s", color = TextSecondary, fontSize = 13.sp)
            Text(
                label.orEmpty(),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton("Lire", primary = true, onClick = onPlay, modifier = Modifier.focusRequester(playFocus))
                CardButton("Fermer", primary = false, onClick = onClose)
            }
        }
    }
}

@Composable
private fun CardButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        focusScale = 1.08f,
        idleColor = if (primary) Color(0xFFE8EEF5) else Color(0x33FFFFFF),
        focusedColor = Color.White,
    ) { focused ->
        val color = if (focused || primary) Color.Black else Color.White
        Text(
            text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
        )
    }
}
