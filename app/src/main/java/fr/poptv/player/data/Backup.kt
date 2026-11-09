package fr.poptv.player.data

import kotlinx.serialization.Serializable

const val DEFAULT_PROFILE = "default"

/** Un profil : ses favoris, son historique et ses reprises sont séparés de ceux des autres. */
@Serializable
data class Profile(val id: String, val name: String)

/** Données d'un profil dans une sauvegarde. */
@Serializable
data class ProfileData(
    val id: String,
    val name: String,
    val favorites: Set<String> = emptySet(),
    val favoritesVod: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val watched: Set<String> = emptySet(),
    val resume: List<ResumeEntry> = emptyList(),
    val lastChannel: String? = null,
)

/** Contenu d'un fichier de sauvegarde iTV. Les identifiants du compte n'y figurent que si l'utilisateur l'a demandé. */
@Serializable
data class Backup(
    val app: String = "iTV",
    val version: Int = 1,
    val exportedAt: Long = 0,
    val activeProfile: String = DEFAULT_PROFILE,
    val profiles: List<ProfileData> = emptyList(),
    val hidden: Map<String, List<String>> = emptyMap(),
    val channelPreview: Boolean = true,
    val credentials: Credentials? = null,
)
