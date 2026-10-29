package fr.poptv.player

import fr.poptv.player.data.Section

/**
 * Écrans de l'appli, du plus prioritaire au moins prioritaire. Un seul endroit décide de ce qui est
 * affiché : l'interface ne teste plus des indicateurs dispersés dans l'état.
 */
sealed interface Screen {
    data class VodPlayer(val key: String) : Screen
    data object LivePlayer : Screen
    data object Login : Screen
    data object Loading : Screen
    data object TmdbKey : Screen
    data class Categories(val section: Section) : Screen
    data object Settings : Screen

    /** Liste des chaînes, des films ou des séries (la fiche est une couche par-dessus). */
    data class Browse(val section: Section) : Screen
}

/** Écran à afficher d'après l'état : la priorité est l'ordre des lignes. */
val UiState.screen: Screen
    get() = when {
        vodPlaying != null -> Screen.VodPlayer(vodPlaying.key)
        playing != null -> Screen.LivePlayer
        needsLogin -> Screen.Login
        loading && allChannels.isEmpty() -> Screen.Loading
        tmdbOpen -> Screen.TmdbKey
        manageSection != null -> Screen.Categories(manageSection)
        settingsOpen -> Screen.Settings
        else -> Screen.Browse(section)
    }
