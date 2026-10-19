package fr.poptv.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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

/** Menus du lecteur de films et d'épisodes. */
internal enum class OptionsMenu(val title: String) {
    AUDIO("Audio"),
    SUBTITLES("Sous-titres"),
    QUALITY("Qualité vidéo"),
    SPEED("Vitesse"),
}

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

/** Bouton de la rangée d'options du lecteur : translucide, blanc au focus. */
@Composable
internal fun ControlButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.08f,
        idleColor = Color(0x40FFFFFF),
        focusedColor = Color.White,
    ) { focused ->
        val color = if (focused) Color.Black else Color.White
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** Panneau de choix (audio, sous-titres, qualité, vitesse) : liste verticale, coche sur le choix actuel. */
@Composable
internal fun OptionsPanel(
    title: String,
    options: List<TrackOption>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(
        modifier = modifier
            .width(340.dp)
            .background(Color(0xF2121214), RoundedCornerShape(22.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (options.isEmpty()) {
            Text("Aucun choix disponible.", color = TextSecondary, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(options) { index, option ->
                    FocusItem(
                        onClick = {
                            option.onSelect()
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
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.label,
                                color = if (focused) Color.Black else TextPrimary,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (option.selected) {
                                Text("✓", color = if (focused) Color.Black else TextPrimary, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
