package fr.poptv.player.data

import kotlinx.serialization.Serializable

enum class Section { LIVE, MOVIES, SERIES }

@Serializable
data class Category(
    val id: String,
    val name: String,
)

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logo: String? = null,
    val categoryId: String = "",
    val epgId: String? = null,
)

@Serializable
data class Playlist(
    val categories: List<Category>,
    val channels: List<Channel>,
)

/** type = "xtream" ou "m3u". */
@Serializable
data class Credentials(
    val type: String = "xtream",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
)

// ---- Films et séries (Xtream uniquement) ----

/** Un film ou une série du catalogue. */
@Serializable
data class VodItem(
    val id: String,
    val name: String,
    val poster: String? = null,
    val categoryId: String = "",
    val ext: String = "mp4",
    val year: String? = null,
    val rating: String? = null,
    val plot: String? = null,
    /** Date d'ajout au catalogue (secondes depuis 1970), 0 si inconnue. */
    val added: Long = 0,
)

@Serializable
data class VodCatalog(
    val categories: List<Category> = emptyList(),
    val items: List<VodItem> = emptyList(),
    /** Catégories masquées au moment du téléchargement : leurs éléments n'ont pas été chargés. */
    val excluded: List<String> = emptyList(),
)

/** Position de lecture mémorisée, pour la rangée « Reprendre ». kind = "movie" ou "episode". */
@Serializable
data class ResumeEntry(
    val key: String,
    val kind: String,
    val title: String,
    val ext: String = "mp4",
    val poster: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val ext: String,
    val plot: String? = null,
    val image: String? = null,
    val duration: String? = null,
)

data class Season(
    val number: Int,
    val episodes: List<Episode>,
)

data class DetailInfo(
    val plot: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val duration: String? = null,
    val rating: String? = null,
)

/** Page de détail ouverte (film ou série). */
data class Detail(
    val item: VodItem,
    val isSeries: Boolean,
    val loading: Boolean = true,
    val error: String? = null,
    val info: DetailInfo = DetailInfo(),
    val seasons: List<Season> = emptyList(),
)
