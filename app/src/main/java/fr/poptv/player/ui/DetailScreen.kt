package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import fr.poptv.player.data.Season
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.poptv.player.data.Detail
import fr.poptv.player.data.Episode
import fr.poptv.player.data.ResumeEntry

/** Page d'un film ou d'une série : fond flouté, affiche, résumé ; pour une série, épisodes par saison. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    detail: Detail,
    resumeFor: (String) -> ResumeEntry?,
    watched: Set<String>,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayMovie: (fromStart: Boolean) -> Unit,
    onPlayEpisode: (Episode, List<Episode>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val item = detail.item
    val info = detail.info
    val focus = remember { FocusRequester() }
    // Prochain épisode à lire : celui en cours, sinon celui qui suit le dernier épisode vu.
    val target = if (detail.isSeries && detail.seasons.isNotEmpty()) nextTarget(detail.seasons, resumeFor, watched) else null
    var seasonIndex by remember(detail.seasons) {
        mutableIntStateOf(detail.seasons.indexOfFirst { it.number == target?.season?.number }.coerceAtLeast(0))
    }
    var focusedEpisodeId by remember { mutableStateOf<String?>(null) }

    // Menu déroulant des saisons.
    var seasonMenuOpen by remember { mutableStateOf(false) }
    var menuTick by remember { mutableIntStateOf(0) }
    val optionFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    val closeSeasonMenu: () -> Unit = {
        seasonMenuOpen = false
        menuTick++
    }
    BackHandler(enabled = seasonMenuOpen) { closeSeasonMenu() }
    LaunchedEffect(seasonMenuOpen) {
        if (seasonMenuOpen) {
            delay(50)
            runCatching { optionFocus.requestFocus() }
        }
    }
    LaunchedEffect(menuTick) {
        if (menuTick > 0) {
            delay(50)
            runCatching { seasonFocus.requestFocus() }
        }
    }

    // Focus initial : le bouton de lecture (film ou série, une fois chargée).
    val focusReady = if (detail.isSeries) !detail.loading else true
    LaunchedEffect(focusReady) {
        if (focusReady) runCatching { focus.requestFocus() }
    }

    val meta = listOfNotNull(
        info.year ?: item.year,
        prettyDuration(info.duration),
        info.genre,
        cleanRating(info.rating ?: item.rating)?.let { "★ $it" },
    ).joinToString("  ·  ")
    val plot = info.plot ?: item.plot

    // Fond : dégradé graphite teinté par la couleur de l'affiche, avec l'affiche très estompée par-dessus.
    val tint = rememberPosterTint(item.poster)
    Box(modifier = Modifier.fillMaxSize().appBackground(tint).appear()) {
        if (item.poster != null) {
            AsyncImage(
                model = rememberImage(item.poster, 320, 480),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.10f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0x99000000), Color(0x40000000), Color(0x00000000)),
                    )
                ),
        )

        val compact = LocalCompact.current
        val poster: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .width(if (compact) 108.dp else 190.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface1),
            ) {
                if (item.poster != null) {
                    AsyncImage(
                        model = rememberImage(item.poster, 400, 600),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val info: @Composable (Modifier) -> Unit = { m ->
            Column(
                modifier = m,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    item.name,
                    fontSize = if (compact) 24.sp else 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(meta, color = TextSecondary, fontSize = 15.sp)
                }

                if (!detail.isSeries) {
                    if (plot != null) {
                        Text(
                            plot,
                            color = TextSecondary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            maxLines = if (compact) 3 else 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val resume = resumeFor("m:${item.id}")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimaryButton(
                            text = if (resume != null) "Reprendre à ${formatTime(resume.positionMs)}" else "Lecture",
                            icon = Icons.Filled.PlayArrow,
                            onClick = { onPlayMovie(false) },
                            modifier = Modifier.focusRequester(focus),
                        )
                        if (resume != null) {
                            Pill("Depuis le début", onClick = { onPlayMovie(true) }, icon = Icons.Filled.Refresh)
                        }
                        Pill(if (favorite) "Dans Ma liste" else "Ma liste", onClick = onToggleFavorite, icon = Icons.Filled.Favorite, selected = favorite)
                        Pill("Retour", onClick = onBack, icon = Icons.Filled.ArrowBack)
                    }
                } else {
                    when {
                        detail.loading -> Text("Chargement des épisodes…", color = TextSecondary)
                        detail.error != null -> Text(detail.error, color = ErrorColor)
                        else -> {
                            val season = detail.seasons.getOrNull(seasonIndex) ?: detail.seasons.first()
                            val shown = season.episodes.firstOrNull { it.id == focusedEpisodeId }
                                ?: season.episodes.first()

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (target != null) {
                                    PrimaryButton(
                                        text = target.label,
                                        icon = Icons.Filled.PlayArrow,
                                        onClick = { onPlayEpisode(target.episode, target.season.episodes) },
                                        modifier = Modifier.focusRequester(focus),
                                    )
                                }
                                if (detail.seasons.size > 1) {
                                    SeasonButton(
                                        label = seasonLabel(season),
                                        onClick = {
                                            if (seasonMenuOpen) {
                                                closeSeasonMenu()
                                            } else {
                                                seasonMenuOpen = true
                                            }
                                        },
                                        modifier = Modifier.focusRequester(seasonFocus),
                                    )
                                }
                                Pill(if (favorite) "Dans Ma liste" else "Ma liste", onClick = onToggleFavorite, icon = Icons.Filled.Favorite, selected = favorite)
                                Pill("Retour", onClick = onBack, icon = Icons.Filled.ArrowBack)
                            }

                            AnimatedVisibility(
                                visible = seasonMenuOpen,
                                enter = fadeIn(tween(240, easing = AppleEase)) + expandVertically(tween(300, easing = AppleEase)),
                                exit = fadeOut(tween(160)) + shrinkVertically(tween(220, easing = AppleEase)),
                            ) {
                                SeasonMenu(
                                    seasons = detail.seasons,
                                    selectedIndex = seasonIndex,
                                    optionFocus = optionFocus,
                                    onSelect = { index ->
                                        seasonIndex = index
                                        focusedEpisodeId = null
                                        closeSeasonMenu()
                                    },
                                )
                            }
                            // Épisode sélectionné : titre et résumé, mis à jour au fil du focus.
                            AnimatedVisibility(
                                visible = !seasonMenuOpen,
                                enter = fadeIn(tween(240, easing = AppleEase)),
                                exit = fadeOut(tween(120)),
                            ) {
                                EpisodePanel(shown, fallbackPlot = plot)
                            }

                            Spacer(Modifier.weight(1f))

                            // La liste s'ouvre sur l'épisode à reprendre (ou le prochain à voir).
                            val episodesState = rememberLazyListState()
                            LaunchedEffect(season.number) {
                                val index = season.episodes.indexOfFirst { it.id == target?.episode?.id }
                                episodesState.scrollToItem(index.coerceAtLeast(0))
                            }
                            LazyRow(
                                state = episodesState,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(vertical = 10.dp),
                            ) {
                                itemsIndexed(season.episodes, key = { _, ep -> ep.id }) { _, episode ->
                                    EpisodeTile(
                                        episode = episode,
                                        resume = resumeFor("e:${episode.id}"),
                                        watched = "e:${episode.id}" in watched,
                                        onClick = { onPlayEpisode(episode, season.episodes) },
                                        onFocused = { focusedEpisodeId = episode.id },
                                        modifier = Modifier.width(if (compact) 150.dp else 220.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 28.dp else 56.dp, vertical = if (compact) 18.dp else 36.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 22.dp else 40.dp),
        ) {
            poster()
            info(Modifier.weight(1f))
        }
    }
}

@Composable
private fun EpisodePanel(episode: Episode, fallbackPlot: String?) {
    val line = listOfNotNull(
        "Saison ${episode.season} · Épisode ${episode.number}",
        prettyDuration(episode.duration),
    ).joinToString("  ·  ")
    val summary = episode.plot ?: fallbackPlot
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(line, color = TextSecondary, fontSize = 13.sp)
        Text(
            episode.title,
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary != null) {
            Text(
                summary,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("Pas de résumé pour cet épisode.", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EpisodeTile(
    episode: Episode,
    resume: ResumeEntry?,
    watched: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tileFocused by remember { mutableStateOf(false) }
    val titleColor by animateColorAsState(if (tileFocused) TextPrimary else TextSecondary, label = "episodeTitle")
    val status = when {
        watched -> "Vu"
        resume != null -> "Reprendre à ${formatTime(resume.positionMs)}"
        else -> null
    }
    val meta = listOfNotNull(status, prettyDuration(episode.duration)).joinToString("  ·  ")

    Column(
        modifier = modifier.onFocusChanged {
            tileFocused = it.hasFocus
            if (it.hasFocus) onFocused()
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FocusItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            focusScale = 1.08f,
            focusRing = true,
        ) {
            if (episode.image != null) {
                AsyncImage(
                    model = rememberImage(episode.image, 480, 270),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Numéro de l'épisode, en bas à gauche de la vignette.
            Text(
                "${episode.number}",
                color = if (episode.image != null) Color.White else TextSecondary,
                fontSize = if (episode.image != null) 15.sp else 34.sp,
                fontWeight = if (episode.image != null) FontWeight.SemiBold else FontWeight.Bold,
                modifier = if (episode.image != null) {
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = 10.dp)
                        .background(Color(0x99000000), RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 2.dp)
                } else {
                    Modifier.align(Alignment.Center)
                },
            )
            // Pastille « Reprendre », en haut à gauche, pour l'épisode commencé et pas fini.
            if (resume != null && !watched) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Accent, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text("Reprendre", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            // Coche « vu », en haut à droite.
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Check, contentDescription = "Vu", tint = Color.Black, modifier = Modifier.size(14.dp))
                }
            }
            // Barre de progression si l'épisode est commencé.
            if (resume != null && resume.durationMs > 0) {
                val fraction = (resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x66FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(Color.White),
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                episode.title,
                color = titleColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    color = if (resume != null && !watched) Accent else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (resume != null && !watched) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun seasonLabel(season: Season): String =
    if (season.number == 0) "Spéciaux" else "Saison ${season.number}"

/** Bouton « Saison N ▾ » qui ouvre le menu des saisons. */
@Composable
private fun SeasonButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusItem(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.08f,
        idleColor = Surface2,
        focusedColor = Color.White,
    ) { focused ->
        val color = if (focused) Color.Black else TextPrimary
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
            Icon(AppIcons.CaretDown, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }
    }
}

/** Liste des saisons, sous le bouton : coche sur la saison affichée. */
@Composable
private fun SeasonMenu(
    seasons: List<Season>,
    selectedIndex: Int,
    optionFocus: FocusRequester,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(230.dp)
            .heightIn(max = 150.dp)
            .background(Color(0xF2121214), RoundedCornerShape(20.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.number }) { index, s ->
            FocusItem(
                onClick = { onSelect(index) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == selectedIndex) Modifier.focusRequester(optionFocus) else Modifier),
                shape = RoundedCornerShape(14.dp),
                focusScale = 1.03f,
                idleColor = Color.Transparent,
                focusedColor = Color.White,
            ) { focused ->
                val color = if (focused) Color.Black else TextPrimary
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(seasonLabel(s), color = color, fontSize = 16.sp, maxLines = 1)
                    if (index == selectedIndex) {
                        Icon(AppIcons.Check, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private class PlayTarget(val episode: Episode, val season: Season, val label: String)

/**
 * Épisode que propose le gros bouton : celui en cours (le plus récent), sinon celui qui suit le dernier
 * épisode vu, sinon le premier. Les spéciaux (saison 0) ne comptent que s'il n'y a rien d'autre.
 */
private fun nextTarget(
    seasons: List<Season>,
    resumeFor: (String) -> ResumeEntry?,
    watched: Set<String>,
): PlayTarget? {
    val all = seasons.flatMap { s -> s.episodes.map { e -> s to e } }
    if (all.isEmpty()) return null

    var inProgress: Triple<Season, Episode, ResumeEntry>? = null
    for ((s, e) in all) {
        val entry = resumeFor("e:${e.id}") ?: continue
        if (inProgress == null || entry.updatedAt > inProgress.third.updatedAt) inProgress = Triple(s, e, entry)
    }
    if (inProgress != null) {
        val (s, e, _) = inProgress
        return PlayTarget(e, s, "Reprendre S${e.season} · E${e.number}")
    }

    val ordered = all.filter { it.first.number != 0 }.ifEmpty { all }
    val lastWatched = ordered.indexOfLast { (_, e) -> "e:${e.id}" in watched }
    val pick = if (lastWatched >= 0) ordered.getOrNull(lastWatched + 1) else ordered.first()
    if (pick != null) {
        return PlayTarget(pick.second, pick.first, "Lecture S${pick.second.season} · E${pick.second.number}")
    }
    val first = ordered.first()
    return PlayTarget(first.second, first.first, "Revoir S${first.second.season} · E${first.second.number}")
}
