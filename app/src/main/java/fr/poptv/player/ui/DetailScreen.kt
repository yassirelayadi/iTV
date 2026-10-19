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
@Composable
fun DetailScreen(
    detail: Detail,
    resumeFor: (String) -> ResumeEntry?,
    onPlayMovie: (fromStart: Boolean) -> Unit,
    onPlayEpisode: (Episode, List<Episode>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val item = detail.item
    val info = detail.info
    val focus = remember { FocusRequester() }
    var seasonIndex by remember { mutableIntStateOf(0) }
    var focusedEpisodeId by remember { mutableStateOf<String?>(null) }

    // Focus initial : le bouton de lecture (film) ou le premier épisode (série, une fois chargé).
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
    Box(modifier = Modifier.fillMaxSize().appBackground(tint)) {
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

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(190.dp)
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

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    item.name,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
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
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val resume = resumeFor("m:${item.id}")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton(
                            text = if (resume != null) "Reprendre à ${formatTime(resume.positionMs)}" else "Lecture",
                            onClick = { onPlayMovie(false) },
                            modifier = Modifier.focusRequester(focus),
                        )
                        if (resume != null) {
                            Pill("Depuis le début", onClick = { onPlayMovie(true) })
                        }
                        Pill("Retour", onClick = onBack)
                    }
                } else {
                    when {
                        detail.loading -> Text("Chargement des épisodes…", color = TextSecondary)
                        detail.error != null -> Text(detail.error, color = ErrorColor)
                        else -> {
                            val season = detail.seasons.getOrNull(seasonIndex) ?: detail.seasons.first()
                            val shown = season.episodes.firstOrNull { it.id == focusedEpisodeId }
                                ?: season.episodes.first()

                            if (detail.seasons.size > 1) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 2.dp),
                                ) {
                                    itemsIndexed(detail.seasons, key = { _, s -> s.number }) { index, s ->
                                        Pill(
                                            text = if (s.number == 0) "Spéciaux" else "Saison ${s.number}",
                                            onClick = {
                                                seasonIndex = index
                                                focusedEpisodeId = null
                                            },
                                            selected = s.number == season.number,
                                        )
                                    }
                                }
                            }

                            // Épisode sélectionné : titre et résumé, mis à jour au fil du focus.
                            EpisodePanel(shown, fallbackPlot = plot)

                            Spacer(Modifier.weight(1f))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(vertical = 10.dp),
                            ) {
                                itemsIndexed(season.episodes, key = { _, ep -> ep.id }) { index, episode ->
                                    EpisodeTile(
                                        episode = episode,
                                        resume = resumeFor("e:${episode.id}"),
                                        onClick = { onPlayEpisode(episode, season.episodes) },
                                        onFocused = { focusedEpisodeId = episode.id },
                                        modifier = Modifier.width(220.dp),
                                        focusModifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
) {
    FocusItem(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .then(focusModifier)
            .onFocusChanged { if (it.hasFocus) onFocused() },
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
            fontWeight = if (episode.image != null) FontWeight.SemiBold else FontWeight.Light,
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
}
