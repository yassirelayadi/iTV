package fr.poptv.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.poptv.player.UiState
import fr.poptv.player.data.Section

/** Vrai juste après un clic dans le menu : le menu de l'écran suivant reprend alors le focus. */
internal object RailFocus {
    var pending = false
}

internal val ScreenPadding: androidx.compose.ui.unit.Dp
    @Composable get() = if (LocalCompact.current) 16.dp else 48.dp

/**
 * Cadre des écrans d'accueil : menu vertical à gauche (Chaînes, Films, Séries, recherche, réglages)
 * et, à droite, un titre avec le champ de recherche, puis le contenu.
 * Le titre se masque quand l'écran défile vers le bas (headerVisible = false).
 */
@Composable
fun AppShell(
    state: UiState,
    onSection: (Section) -> Unit,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    headerVisible: Boolean = true,
    backdrop: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var searchOpen by remember(state.section) { mutableStateOf(false) }
    val sectionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Le menu est recréé à chaque changement de section : on redonne le focus à la section choisie.
        if (RailFocus.pending) {
            RailFocus.pending = false
            androidx.compose.runtime.withFrameNanos { }
            runCatching { sectionFocus.requestFocus() }
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        SideRail(
            state = state,
            onSection = onSection,
            onSearch = { searchOpen = true },
            onSettings = onSettings,
            sectionFocus = sectionFocus,
        )
        // Le fond optionnel (image du bandeau) passe derrière l'en-tête et le contenu.
        androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f).fillMaxHeight().appear()) {
            backdrop?.invoke(this)
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = headerVisible) {
                    ScreenHeader(
                        state = state,
                        showSearch = searchOpen || state.query.isNotEmpty(),
                        focusSearch = searchOpen,
                        onQuery = onQuery,
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun ScreenHeader(
    state: UiState,
    showSearch: Boolean,
    focusSearch: Boolean,
    onQuery: (String) -> Unit,
) {
    val compact = LocalCompact.current
    val loading = when (state.section) {
        Section.LIVE -> state.loading
        Section.MOVIES -> state.moviesLoading
        Section.SERIES -> state.seriesLoading
    }
    val title = when (state.section) {
        Section.LIVE -> "Chaînes"
        Section.MOVIES -> "Films"
        Section.SERIES -> "Séries"
    }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) runCatching { searchFocus.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ScreenPadding,
                end = ScreenPadding,
                top = if (compact) 10.dp else 22.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        Text(title, fontSize = if (compact) 24.sp else 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        if (showSearch) {
            MinimalField(
                value = state.query,
                onChange = onQuery,
                placeholder = "Rechercher",
                modifier = Modifier.width(if (compact) 220.dp else 260.dp).focusRequester(searchFocus),
                imeAction = ImeAction.Search,
                shape = RoundedCornerShape(50),
                leadingIcon = Icons.Filled.Search,
            )
        }
    }
}

@Composable
private fun SideRail(
    state: UiState,
    onSection: (Section) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    sectionFocus: FocusRequester,
) {
    val compact = LocalCompact.current
    fun pick(section: Section) {
        if (section != state.section) RailFocus.pending = true
        onSection(section)
    }
    Column(
        modifier = Modifier
            .width(if (compact) 58.dp else 80.dp)
            .fillMaxHeight()
            .background(Color(0xC704080E))
            .drawBehind {
                // Filet fin à droite du menu
                drawRect(Color(0x2496CDFF), topLeft = androidx.compose.ui.geometry.Offset(size.width - 1f, 0f), size = androidx.compose.ui.geometry.Size(1f, size.height))
            }
            .padding(vertical = if (compact) 12.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        LogoText(size = if (compact) 16.sp else 22.sp)
        Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
        RailButton(AppIcons.Tv, "Chaînes", selected = state.section == Section.LIVE, focusRequester = sectionFocus.takeIf { state.section == Section.LIVE }) { pick(Section.LIVE) }
        if (state.vodSupported) {
            RailButton(AppIcons.Movie, "Films", selected = state.section == Section.MOVIES, focusRequester = sectionFocus.takeIf { state.section == Section.MOVIES }) { pick(Section.MOVIES) }
            RailButton(AppIcons.Library, "Séries", selected = state.section == Section.SERIES, focusRequester = sectionFocus.takeIf { state.section == Section.SERIES }) { pick(Section.SERIES) }
        }
        Spacer(Modifier.weight(1f))
        RailButton(Icons.Filled.Search, "Rechercher", selected = false, onClick = onSearch)
        RailButton(Icons.Filled.Settings, "Réglages", selected = false, onClick = onSettings)
    }
}

/** Icône du menu latéral : pastille bleue si c'est la section affichée, blanche au focus. */
@Composable
private fun RailButton(icon: ImageVector, label: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    FocusItem(
        onClick = onClick,
        modifier = Modifier.size(if (LocalCompact.current) 38.dp else 50.dp).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = RoundedCornerShape(if (LocalCompact.current) 13.dp else 18.dp),
        focusScale = 1.1f,
        idleColor = if (selected) Accent else Color.Transparent,
        focusedColor = if (selected) Accent else Color.White,
        idleBrush = if (selected) AccentBrush else null,
        glow = selected,
        focusRing = selected,
        ringColor = Color.White,
    ) { focused ->
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                selected -> Color.White
                focused -> Color.Black
                else -> TextSecondary
            },
            modifier = Modifier.align(Alignment.Center).size(if (LocalCompact.current) 21.dp else 26.dp),
        )
    }
}
