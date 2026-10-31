package fr.poptv.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * État vide : une icône, un titre clair, une phrase qui dit quoi faire, et si possible un bouton d'action.
 * [error] colore l'icône et le titre en rouge pour les erreurs de chargement.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val compact = LocalCompact.current
    Box(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Box(
                modifier = Modifier.size(if (compact) 52.dp else 76.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (error) ErrorColor else TextSecondary,
                    modifier = Modifier.size(if (compact) 24.dp else 34.dp),
                )
            }
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            Text(
                title,
                color = if (error) ErrorColor else TextPrimary,
                fontSize = if (compact) 17.sp else 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
            )
            Text(
                message,
                color = TextSecondary,
                fontSize = if (compact) 13.sp else 17.sp,
                lineHeight = if (compact) 18.sp else 23.sp,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                PrimaryButton(actionLabel, onClick = onAction)
            }
        }
    }
}

/** Chargement d'un catalogue : silhouettes d'affiches qui pulsent doucement, à la place d'un texte. */
@Composable
fun CatalogSkeleton(modifier: Modifier = Modifier) {
    val compact = LocalCompact.current
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeletonPulse",
    )
    val width = if (compact) 88.dp else 150.dp
    Column(
        modifier = modifier.fillMaxSize().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 26.dp),
    ) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .padding(start = ScreenPadding)
                        .width(if (compact) 120.dp else 200.dp)
                        .height(if (compact) 14.dp else 20.dp)
                        .alpha(pulse)
                        .background(GlassBorder, RoundedCornerShape(50)),
                )
                LazyRow(
                    userScrollEnabled = false,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(List(10) { it }) {
                        Box(
                            modifier = Modifier
                                .width(width)
                                .height(width * 1.5f)
                                .alpha(pulse)
                                .background(GlassFill, RoundedCornerShape(14.dp))
                                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** Chargement d'une liste de lignes (épisodes) : barres qui pulsent doucement. */
@Composable
fun SkeletonLines(count: Int, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "lines").animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "linesPulse",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (i == count - 1) 0.5f else 0.8f)
                    .height(18.dp)
                    .alpha(pulse)
                    .background(GlassBorder, RoundedCornerShape(6.dp)),
            )
        }
    }
}
