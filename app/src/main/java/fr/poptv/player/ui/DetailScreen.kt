package fr.poptv.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.focusProperties
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
    backdrop: String? = null,
    categoryName: String? = null,
    similar: List<fr.poptv.player.data.VodItem> = emptyList(),
    onOpenSimilar: (fr.poptv.player.data.VodItem) -> Unit = {},
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
    // Séries : la rangée du bas affiche les épisodes, ou les titres similaires quand on les demande.
    var showSimilar by remember(item.id) { mutableStateOf(false) }

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

    val metaParts = listOfNotNull(
        categoryName,
        info.year ?: item.year,
        prettyDuration(info.duration),
        info.genre,
        cleanRating(info.rating ?: item.rating)?.let { "★ $it" },
    )
    val plot = info.plot ?: item.plot

    // Fond : dégradé graphite teinté par la couleur de l'affiche, avec l'affiche très estompée par-dessus.
    val tint = rememberPosterTint(item.poster)
    Box(modifier = Modifier.fillMaxSize().appBackground(tint).appear()) {
        // Image de fond centrée, qui couvre tout l'écran : le décor TMDB (paysage) s'il existe,
        // sinon l'affiche agrandie.
        val bgImage = backdrop ?: item.poster
        if (bgImage != null) {
            AsyncImage(
                model = if (backdrop != null) rememberImage(backdrop, 1280, 720) else rememberImage(bgImage, 640, 960),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().alpha(if (backdrop != null) 0.28f else 0.14f),
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
                    .clip(RoundedCornerShape(Radius.L))
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
                    fontSize = if (compact) TypeSize.Title2 else TypeSize.Title1,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString("   ·   "),
                        color = TextSecondary,
                        fontSize = TypeSize.Body,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!detail.isSeries) {
                    if (plot != null) {
                        Text(
                            plot,
                            color = TextSecondary,
                            fontSize = TypeSize.Body,
                            lineHeight = LineHeight.Body,
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
                            icon = AppIcons.Play,
                            onClick = { onPlayMovie(false) },
                            modifier = Modifier.focusRequester(focus),
                        )
                        if (resume != null) {
                            Pill("Depuis le début", onClick = { onPlayMovie(true) }, icon = AppIcons.Refresh)
                        }
                        Pill(if (favorite) "Dans Ma liste" else "Ma liste", onClick = onToggleFavorite, icon = AppIcons.Heart, selected = favorite)
                        Pill("Retour", onClick = onBack, icon = AppIcons.Back)
                    }
                } else {
                    when {
                        detail.loading -> SkeletonLines(count = 3)
                        detail.error != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(AppIcons.Warning, contentDescription = null, tint = ErrorColor)
                            Text(detail.error, color = TextPrimary, fontWeight = FontWeight.Medium)
                        }
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
                                        icon = AppIcons.Play,
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
                                Pill(if (favorite) "Dans Ma liste" else "Ma liste", onClick = onToggleFavorite, icon = AppIcons.Heart, selected = favorite)
                                if (similar.isNotEmpty()) {
                                    Pill(if (showSimilar) "Épisodes" else "Similaires", onClick = { showSimilar = !showSimilar })
                                }
                                Pill("Retour", onClick = onBack, icon = AppIcons.Back)
                            }

                            AnimatedVisibility(
                                visible = seasonMenuOpen,
                                enter = fadeIn(tween(Dur.Normal, easing = AppleEase)) + expandVertically(tween(Dur.Normal, easing = AppleEase)),
                                exit = fadeOut(tween(Dur.Fast, easing = AppleEase)) + shrinkVertically(tween(Dur.Normal, easing = AppleEase)),
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
                                enter = fadeIn(tween(Dur.Normal, easing = AppleEase)),
                                exit = fadeOut(tween(Dur.Fast, easing = AppleEase)),
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
                            if (showSimilar && similar.isNotEmpty()) {
                                SimilarRow(similar, onOpenSimilar)
                            } else LazyRow(
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

        if (!compact) {
            DetailTv(
                detail = detail,
                metaParts = metaParts,
                category = categoryName,
                plot = plot,
                poster = item.poster,
                target = target,
                seasonIndex = seasonIndex,
                onSeason = { seasonIndex = it },
                resumeFor = resumeFor,
                watched = watched,
                favorite = favorite,
                onToggleFavorite = onToggleFavorite,
                onPlayMovie = onPlayMovie,
                onPlayEpisode = onPlayEpisode,
                similar = similar,
                onOpenSimilar = onOpenSimilar,
                primaryFocus = focus,
            )
            return@Box
        }
        val movieSimilar = !detail.isSeries && similar.isNotEmpty() && !compact
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 28.dp else 56.dp, vertical = if (compact) 18.dp else 36.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 22.dp else 40.dp),
                verticalAlignment = if (detail.isSeries || movieSimilar) Alignment.Top else Alignment.CenterVertically,
            ) {
                poster()
                // Pas de carte : le texte se pose directement sur le fond (série : colonne pleine hauteur).
                info(if (detail.isSeries) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f))
            }
            if (movieSimilar) SimilarRow(similar, onOpenSimilar)
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
        Text(line, color = TextSecondary, fontSize = TypeSize.Caption)
        Text(
            episode.title,
            color = TextPrimary,
            fontSize = TypeSize.Title2,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary != null) {
            Text(
                summary,
                color = TextSecondary,
                fontSize = TypeSize.Body,
                lineHeight = LineHeight.Body,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("Pas de résumé pour cet épisode.", color = TextSecondary, fontSize = TypeSize.Body)
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
                fontSize = if (episode.image != null) TypeSize.Body else TypeSize.Title1,
                fontWeight = if (episode.image != null) FontWeight.Bold else FontWeight.Bold,
                modifier = if (episode.image != null) {
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = 10.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
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
                    Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text("Reprendre", color = Color.White, fontSize = TypeSize.Caption, fontWeight = FontWeight.SemiBold)
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
                fontSize = TypeSize.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    color = if (resume != null && !watched) Accent else TextSecondary,
                    fontSize = TypeSize.Caption,
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
        shape = RoundedCornerShape(Radius.M),
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
            .background(Color(0xF2121214), RoundedCornerShape(Radius.L))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.number }) { index, s ->
            FocusItem(
                onClick = { onSelect(index) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == selectedIndex) Modifier.focusRequester(optionFocus) else Modifier),
                shape = RoundedCornerShape(Radius.L),
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
                    Text(seasonLabel(s), color = color, fontSize = TypeSize.Title3, maxLines = 1)
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

/** Rangée « Titres similaires » : petites affiches, le titre dessous. */
@Composable
private fun SimilarRow(items: List<fr.poptv.player.data.VodItem>, onOpen: (fr.poptv.player.data.VodItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Titres similaires", color = TextPrimary, fontSize = TypeSize.Title3, fontWeight = FontWeight.SemiBold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
        ) {
            items(items, key = { it.id }) { other ->
                Column(modifier = Modifier.width(92.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusItem(
                        onClick = { onOpen(other) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                        focusScale = 1.08f,
                        focusRing = true,
                    ) {
                        PosterPlaceholder(other.name, Modifier.fillMaxSize())
                        if (other.poster != null) {
                            AsyncImage(
                                model = rememberImage(other.poster, 240, 360),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(other.name, color = TextSecondary, fontSize = TypeSize.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Fiche sur TV : à gauche l'affiche et les infos, à droite les épisodes ou les titres similaires en lignes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailTv(
    detail: Detail,
    metaParts: List<String>,
    category: String?,
    plot: String?,
    poster: String?,
    target: PlayTarget?,
    seasonIndex: Int,
    onSeason: (Int) -> Unit,
    resumeFor: (String) -> ResumeEntry?,
    watched: Set<String>,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayMovie: (fromStart: Boolean) -> Unit,
    onPlayEpisode: (Episode, List<Episode>) -> Unit,
    similar: List<fr.poptv.player.data.VodItem>,
    onOpenSimilar: (fr.poptv.player.data.VodItem) -> Unit,
    primaryFocus: FocusRequester,
) {
    val item = detail.item
    val hasSimilar = similar.isNotEmpty()
    val hasEpisodes = detail.isSeries
    // 0 = épisodes, 1 = titres similaires (les films n'ont que les similaires).
    var tab by remember(item.id) { mutableIntStateOf(if (hasEpisodes) 0 else 1) }
    val showPanel = hasEpisodes || hasSimilar
    // Un requester par onglet : depuis les saisons, les épisodes ou les similaires, Haut remonte à l'onglet actif.
    val tabFocus = remember { List(2) { FocusRequester() } }
    val activeTab = tabFocus[tab]

    Row(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 56.dp, end = 24.dp, top = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(190.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(Radius.L))
                    .background(Surface1),
            ) {
                if (poster != null) {
                    AsyncImage(
                        model = rememberImage(poster, 400, 600),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!category.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(24.dp).height(3.dp).background(Accent, RoundedCornerShape(2.dp)))
                        Text(
                            category.uppercase(),
                            color = Color(0xCCFFFFFF),
                            fontSize = TypeSize.Caption,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    item.name,
                    fontSize = TypeSize.Title1,
                    lineHeight = LineHeight.Title1,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaShown = if (category != null && metaParts.firstOrNull() == category) metaParts.drop(1) else metaParts
                if (metaShown.isNotEmpty()) {
                    Text(
                        metaShown.joinToString("   ·   "),
                        color = TextSecondary,
                        fontSize = TypeSize.Body,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (plot != null) {
                    Text(
                        plot,
                        color = TextSecondary,
                        fontSize = TypeSize.Body,
                        lineHeight = LineHeight.Body,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val listLabel = if (favorite) "Dans ma liste" else "Ma liste"
                val listIcon = if (favorite) AppIcons.Check else AppIcons.Plus
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!detail.isSeries) {
                        val resume = resumeFor("m:${item.id}")
                        PrimaryButton(
                            text = if (resume != null) "Reprendre à ${formatTime(resume.positionMs)}" else "Lecture",
                            icon = AppIcons.Play,
                            onClick = { onPlayMovie(false) },
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                        if (resume != null) SecondaryButton("Depuis le début", onClick = { onPlayMovie(true) }, icon = AppIcons.Refresh)
                    } else if (target != null) {
                        PrimaryButton(
                            text = target.label,
                            icon = AppIcons.Play,
                            onClick = { onPlayEpisode(target.episode, target.season.episodes) },
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                    }
                    SecondaryButton(listLabel, onClick = onToggleFavorite, icon = listIcon)
                }
            }
        }

        if (showPanel) {
            Column(
                modifier = Modifier
                    .width(440.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xCC05080D)), startX = 0f, endX = 90f))
                    .padding(start = 36.dp, end = 40.dp, top = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (hasEpisodes) PanelTab("Épisodes", tab == 0, tabFocus[0]) { tab = 0 }
                    if (hasSimilar) PanelTab("Titres similaires", tab == 1, tabFocus[1]) { tab = 1 }
                }
                if (tab == 0 && hasEpisodes) {
                    when {
                        detail.loading -> SkeletonLines(count = 4)
                        detail.error != null -> Text(detail.error, color = TextPrimary, fontWeight = FontWeight.Medium)
                        else -> EpisodeList(detail, seasonIndex, onSeason, target, resumeFor, watched, onPlayEpisode, activeTab)
                    }
                } else {
                    SimilarList(similar, onOpenSimilar, activeTab)
                }
            }
        }
    }
}

/** Titre d'onglet du panneau : il s'active dès qu'il prend le focus, trait bleu sous l'onglet actif. */
@Composable
private fun PanelTab(text: String, active: Boolean, focusRequester: FocusRequester, onSelect: () -> Unit) {
    FocusItem(
        onClick = onSelect,
        modifier = Modifier.focusRequester(focusRequester),
        shape = RoundedCornerShape(Radius.S),
        focusScale = 1.03f,
        idleColor = Color.Transparent,
        focusedColor = Color.Transparent,
    ) { focused ->
        LaunchedEffect(focused) { if (focused) onSelect() }
        // IntrinsicSize.Max : le trait suit la largeur du texte au lieu de remplir tout le panneau.
        Column(modifier = Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                text,
                color = if (active) TextPrimary else TextSecondary,
                fontSize = TypeSize.Title3,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(if (active) Accent else Color.Transparent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun EpisodeList(
    detail: Detail,
    seasonIndex: Int,
    onSeason: (Int) -> Unit,
    target: PlayTarget?,
    resumeFor: (String) -> ResumeEntry?,
    watched: Set<String>,
    onPlayEpisode: (Episode, List<Episode>) -> Unit,
    activeTab: FocusRequester,
) {
    val season = detail.seasons.getOrNull(seasonIndex) ?: detail.seasons.first()
    val hasChips = detail.seasons.size > 1
    // Haut depuis les saisons remonte à l'onglet ; Haut depuis le premier épisode retombe sur la saison choisie.
    val chipFocus = remember { FocusRequester() }
    if (hasChips) {
        androidx.compose.foundation.lazy.LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = seasonIndex),
            modifier = Modifier.focusProperties { up = activeTab },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(detail.seasons, key = { _, s -> s.number }) { index, s ->
                Pill(
                    seasonLabel(s),
                    onClick = { onSeason(index) },
                    selected = index == seasonIndex,
                    modifier = if (index == seasonIndex) Modifier.focusRequester(chipFocus) else Modifier,
                )
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(season.number) {
        val index = season.episodes.indexOfFirst { it.id == target?.episode?.id }
        listState.scrollToItem(index.coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(season.episodes, key = { _, e -> e.id }) { position, episode ->
            val resume = resumeFor("e:${episode.id}")
            val seen = "e:${episode.id}" in watched
            FocusItem(
                onClick = { onPlayEpisode(episode, season.episodes) },
                modifier = Modifier.fillMaxWidth().then(
                    if (position == 0) Modifier.focusProperties { up = if (hasChips) chipFocus else activeTab } else Modifier,
                ),
                shape = RoundedCornerShape(Radius.M),
                focusScale = 1.02f,
                focusRing = true,
                idleColor = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Radius.S)).background(Surface1),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (episode.image != null) {
                            AsyncImage(
                                model = rememberImage(episode.image, 320, 180),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("${episode.number}", color = TextSecondary, fontSize = TypeSize.Title2, fontWeight = FontWeight.Bold)
                        }
                        if (resume != null && resume.durationMs > 0 && !seen) {
                            val fraction = (resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f)
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent))
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${episode.number}. ${episode.title}",
                            color = TextPrimary,
                            fontSize = TypeSize.Body,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val status = when {
                            seen -> "Vu"
                            resume != null -> "Reprendre à ${formatTime(resume.positionMs)}"
                            else -> null
                        }
                        val meta = listOfNotNull(status, prettyDuration(episode.duration)).joinToString("  ·  ")
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                color = if (resume != null && !seen) Accent else TextSecondary,
                                fontSize = TypeSize.Caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (seen) Icon(AppIcons.Check, contentDescription = "Vu", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SimilarList(items: List<fr.poptv.player.data.VodItem>, onOpen: (fr.poptv.player.data.VodItem) -> Unit, activeTab: FocusRequester) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, o -> o.id }) { position, other ->
            FocusItem(
                onClick = { onOpen(other) },
                modifier = Modifier.fillMaxWidth().then(
                    if (position == 0) Modifier.focusProperties { up = activeTab } else Modifier,
                ),
                shape = RoundedCornerShape(Radius.M),
                focusScale = 1.02f,
                focusRing = true,
                idleColor = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(modifier = Modifier.width(60.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(Radius.S))) {
                        PosterPlaceholder(other.name, Modifier.fillMaxSize())
                        if (other.poster != null) {
                            AsyncImage(
                                model = rememberImage(other.poster, 180, 270),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(other.name, color = TextPrimary, fontSize = TypeSize.Body, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        other.year?.let { Text(it, color = TextSecondary, fontSize = TypeSize.Caption, maxLines = 1) }
                        cleanRating(other.rating)?.let { Text("★ $it", color = Color(0xFFFFD166), fontSize = TypeSize.Caption, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    }
                }
            }
        }
    }
}
