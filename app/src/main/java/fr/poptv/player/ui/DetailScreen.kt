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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** Page d'un film ou d'une série : affiche, résumé, bouton de lecture ou épisodes par saison. */
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

    // Focus initial : le bouton de lecture (film) ou le premier épisode (série, une fois chargé).
    val focusReady = if (detail.isSeries) !detail.loading else true
    LaunchedEffect(focusReady) {
        if (focusReady) runCatching { focus.requestFocus() }
    }

    val meta = listOfNotNull(
        info.year ?: item.year,
        info.duration,
        info.genre,
        cleanRating(info.rating ?: item.rating)?.let { "★ $it" },
    ).joinToString("  ·  ")
    val plot = info.plot ?: item.plot

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Box(
            modifier = Modifier
                .width(200.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1),
        ) {
            if (item.poster != null) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                item.name,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(meta, color = TextSecondary, fontSize = 15.sp)
            }
            if (plot != null) {
                Text(
                    plot,
                    color = TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))

            if (!detail.isSeries) {
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

                        if (detail.seasons.size > 1) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                itemsIndexed(detail.seasons, key = { _, s -> s.number }) { index, s ->
                                    Pill(
                                        text = if (s.number == 0) "Spéciaux" else "Saison ${s.number}",
                                        onClick = { seasonIndex = index },
                                        selected = s.number == season.number,
                                    )
                                }
                            }
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) {
                            itemsIndexed(season.episodes, key = { _, ep -> ep.id }) { index, episode ->
                                EpisodeTile(
                                    episode = episode,
                                    resume = resumeFor("e:${episode.id}"),
                                    onClick = { onPlayEpisode(episode, season.episodes) },
                                    modifier = Modifier.width(260.dp),
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

@Composable
private fun EpisodeTile(
    episode: Episode,
    resume: ResumeEntry?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusItem(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(focusModifier),
            focusScale = 1.06f,
            focusRing = true,
        ) {
            if (episode.image != null) {
                AsyncImage(
                    model = episode.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "${episode.number}",
                    color = TextSecondary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
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
        Text(
            "${episode.number}. ${episode.title}",
            color = TextSecondary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
