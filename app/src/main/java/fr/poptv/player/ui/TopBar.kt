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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Mémorise la vignette ouverte (fiche, lecteur…) : au retour, elle reprend le focus de la télécommande
 * au lieu de laisser celui-ci retomber sur le menu de gauche.
 */
internal object FocusMemory {
    var key: String? = null
    var restore = false
    val tick = androidx.compose.runtime.mutableIntStateOf(0)

    fun requestRestore() {
        restore = true
        tick.intValue++
    }
}

/** Identifiant de la rangée en cours (distingue le même titre dans « Ma liste » et dans le Top 10). */
internal val LocalRowId = androidx.compose.runtime.compositionLocalOf { "" }

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
    alwaysHeader: Boolean = true,
    onSearchRequest: (() -> Unit)? = null,
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

    // Le fond (image du bandeau) occupe tout l'écran, derrière le menu, l'en-tête et le contenu.
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        backdrop?.invoke(this)
        Row(modifier = Modifier.fillMaxSize()) {
            SideRail(
                state = state,
                onSection = onSection,
                onSearch = {
                    searchOpen = true
                    // En bas de la liste, l'en-tête est masqué : on remonte pour afficher le champ.
                    onSearchRequest?.invoke()
                },
                onSettings = onSettings,
                sectionFocus = sectionFocus,
            )
            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f).fillMaxHeight().appear()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = headerVisible && (alwaysHeader || searchOpen || state.query.isNotEmpty())) {
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
        Text(title, fontSize = TypeSize.Title2, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = TextPrimary)
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
                shape = RoundedCornerShape(Radius.M),
                leadingIcon = AppIcons.Search,
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
    val collapsed = if (compact) 58.dp else 88.dp
    var expanded by remember { mutableStateOf(false) }
    val railWidth by androidx.compose.animation.core.animateDpAsState(
        if (expanded) 240.dp else collapsed,
        androidx.compose.animation.core.tween(Dur.Normal, easing = AppleEase),
        label = "railWidth",
    )
    fun pick(section: Section) {
        if (section != state.section) RailFocus.pending = true
        onSection(section)
    }
    // Le menu se déplie par-dessus le contenu (sans le pousser) dès qu'il a le focus.
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .width(collapsed)
            .fillMaxHeight()
            .zIndex(2f)
            .wrapContentWidth(Alignment.Start, unbounded = true),
    ) {
        Column(
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color(0xF205080D), Color(0xCC05080D), Color(0x0005080D))))
                .onFocusChanged { expanded = it.hasFocus }
                .padding(vertical = if (compact) 12.dp else 22.dp, horizontal = (collapsed - if (compact) 38.dp else 50.dp) / 2),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.width(if (compact) 38.dp else 50.dp), contentAlignment = Alignment.Center) {
                LogoText(size = if (compact) 16.sp else 22.sp)
            }
            Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
            RailButton(AppIcons.Tv, "Chaînes", expanded, selected = state.section == Section.LIVE, focusRequester = sectionFocus.takeIf { state.section == Section.LIVE }) { pick(Section.LIVE) }
            if (state.vodSupported) {
                RailButton(AppIcons.Movie, "Films", expanded, selected = state.section == Section.MOVIES, focusRequester = sectionFocus.takeIf { state.section == Section.MOVIES }) { pick(Section.MOVIES) }
                RailButton(AppIcons.Library, "Séries", expanded, selected = state.section == Section.SERIES, focusRequester = sectionFocus.takeIf { state.section == Section.SERIES }) { pick(Section.SERIES) }
            }
            Spacer(Modifier.weight(1f))
            RailButton(AppIcons.Search, "Recherche", expanded, selected = false, onClick = onSearch)
            RailButton(AppIcons.Settings, "Réglages", expanded, selected = false, onClick = onSettings)
        }
    }
}

/** Entrée du menu latéral : icône seule au repos, icône + libellé quand le menu est déplié. */
@Composable
private fun RailButton(icon: ImageVector, label: String, expanded: Boolean, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val compact = LocalCompact.current
    val size = if (compact) 38.dp else 50.dp
    FocusItem(
        onClick = onClick,
        modifier = (if (expanded) Modifier.fillMaxWidth().height(size) else Modifier.size(size))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = RoundedCornerShape(if (compact) 13.dp else 16.dp),
        focusScale = 1.04f,
        idleColor = if (selected) Color(0x2EFFFFFF) else Color.Transparent,
        focusedColor = Color.White,
    ) { focused ->
        val tint = when {
            focused -> Color.Black
            selected -> Color.White
            else -> TextSecondary
        }
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = (size - if (compact) 21.dp else 26.dp) / 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (compact) 21.dp else 26.dp))
            if (expanded) {
                Text(label, color = tint, fontSize = TypeSize.Title3, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
    }
}
