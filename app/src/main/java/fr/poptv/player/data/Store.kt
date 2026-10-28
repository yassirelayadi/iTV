package fr.poptv.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "poptv")

class Store(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val keyCreds = stringPreferencesKey("credentials_enc")
    private val keyFavorites = stringSetPreferencesKey("favorites")
    private val keyFavVod = stringSetPreferencesKey("favorites_vod")
    private val keyLast = stringPreferencesKey("last_channel")

    val credentials: Flow<Credentials?> = context.dataStore.data.map { prefs ->
        prefs[keyCreds]
            ?.let { SecretBox.decrypt(it) }
            ?.let { runCatching { json.decodeFromString(Credentials.serializer(), it) }.getOrNull() }
    }

    // ---- Clé TMDB (chiffrée comme les identifiants) ----

    private val keyTmdb = stringPreferencesKey("tmdb_key_enc")

    val tmdbKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[keyTmdb]?.let { SecretBox.decrypt(it) }?.takeIf { it.isNotBlank() }
    }

    suspend fun saveTmdbKey(key: String?) {
        context.dataStore.edit { prefs ->
            val encrypted = key?.trim()?.takeIf { it.isNotEmpty() }?.let { SecretBox.encrypt(it) }
            if (encrypted == null) prefs.remove(keyTmdb) else prefs[keyTmdb] = encrypted
        }
    }

    val favorites: Flow<Set<String>> = context.dataStore.data.map { it[keyFavorites].orEmpty() }

    /** Ma liste : films (« m:id ») et séries (« s:id »). */
    val favoritesVod: Flow<Set<String>> = context.dataStore.data.map { it[keyFavVod].orEmpty() }

    suspend fun toggleFavoriteVod(key: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[keyFavVod].orEmpty()
            prefs[keyFavVod] = if (key in current) current - key else current + key
        }
    }

    val lastChannel: Flow<String?> = context.dataStore.data.map { it[keyLast] }

    suspend fun saveCredentials(creds: Credentials?) {
        context.dataStore.edit { prefs ->
            // Jamais de stockage en clair : si le chiffrement échoue, rien n'est enregistré.
            val encrypted = creds?.let { SecretBox.encrypt(json.encodeToString(Credentials.serializer(), it)) }
            if (encrypted == null) prefs.remove(keyCreds) else prefs[keyCreds] = encrypted
        }
    }

    suspend fun toggleFavorite(channelId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[keyFavorites].orEmpty()
            prefs[keyFavorites] = if (channelId in current) current - channelId else current + channelId
        }
    }

    suspend fun setLastChannel(channelId: String) {
        context.dataStore.edit { it[keyLast] = channelId }
    }

    // ---- Chaînes récentes (la plus récente en premier) ----

    private val keyRecent = stringPreferencesKey("recent_channels")
    private val idListSerializer = ListSerializer(String.serializer())

    private fun decodeIds(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString(idListSerializer, it) }.getOrNull() }.orEmpty()

    val recentChannels: Flow<List<String>> = context.dataStore.data.map { decodeIds(it[keyRecent]) }

    suspend fun addRecent(channelId: String) {
        context.dataStore.edit { prefs ->
            val updated = (listOf(channelId) + decodeIds(prefs[keyRecent]).filterNot { it == channelId }).take(15)
            prefs[keyRecent] = json.encodeToString(idListSerializer, updated)
        }
    }

    // ---- Films et épisodes terminés (coche « vu ») ----

    private val keyWatched = stringSetPreferencesKey("watched")

    val watched: Flow<Set<String>> = context.dataStore.data.map { it[keyWatched].orEmpty() }

    suspend fun markWatched(key: String) {
        context.dataStore.edit { prefs -> prefs[keyWatched] = prefs[keyWatched].orEmpty() + key }
    }

    // ---- Catégories masquées (une liste par section) ----

    private fun hiddenKey(section: Section) =
        stringSetPreferencesKey("hidden_${section.name.lowercase()}")

    suspend fun loadHidden(): Map<Section, Set<String>> {
        val prefs = context.dataStore.data.first()
        return Section.entries.associateWith { prefs[hiddenKey(it)].orEmpty() }
    }

    suspend fun setHidden(section: Section, ids: Set<String>) {
        context.dataStore.edit { it[hiddenKey(section)] = ids }
    }

    suspend fun clearHidden() {
        context.dataStore.edit { prefs -> Section.entries.forEach { prefs.remove(hiddenKey(it)) } }
    }

    // ---- Dernières sélections TMDB (Top 10 et bandeaux) : affichées dès l'ouverture ----

    private val keyPicks = stringPreferencesKey("picks_cache")

    suspend fun loadPicks(): TmdbPicks? = context.dataStore.data.first()[keyPicks]
        ?.let { runCatching { json.decodeFromString(TmdbPicks.serializer(), it) }.getOrNull() }

    suspend fun savePicks(picks: TmdbPicks) {
        context.dataStore.edit { it[keyPicks] = json.encodeToString(TmdbPicks.serializer(), picks) }
    }

    // ---- Dernières suggestions « Pour vous » : affichées dès l'ouverture ----

    private val keyForYou = stringPreferencesKey("foryou_cache")

    suspend fun loadForYou(): ForYouCache? = context.dataStore.data.first()[keyForYou]
        ?.let { runCatching { json.decodeFromString(ForYouCache.serializer(), it) }.getOrNull() }

    suspend fun saveForYou(cache: ForYouCache) {
        context.dataStore.edit { it[keyForYou] = json.encodeToString(ForYouCache.serializer(), cache) }
    }

    // ---- Reprise de lecture (films et épisodes) ----

    private val keyResume = stringPreferencesKey("resume")
    private val resumeSerializer = ListSerializer(ResumeEntry.serializer())

    private fun decodeResume(raw: String?): List<ResumeEntry> =
        raw?.let { runCatching { json.decodeFromString(resumeSerializer, it) }.getOrNull() }.orEmpty()

    val resume: Flow<List<ResumeEntry>> = context.dataStore.data.map { decodeResume(it[keyResume]) }

    suspend fun saveResume(entry: ResumeEntry) = editResume { list ->
        (listOf(entry) + list.filterNot { it.key == entry.key }).take(20)
    }

    suspend fun removeResume(key: String) = editResume { list -> list.filterNot { it.key == key } }

    private suspend fun editResume(transform: (List<ResumeEntry>) -> List<ResumeEntry>) {
        context.dataStore.edit { prefs ->
            val updated = transform(decodeResume(prefs[keyResume]))
            prefs[keyResume] = json.encodeToString(resumeSerializer, updated)
        }
    }
}
