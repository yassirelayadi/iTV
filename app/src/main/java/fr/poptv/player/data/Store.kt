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
    // ---- Profils : favoris, historique et reprises sont propres à chaque profil ----
    // Le profil « default » garde les clés d'origine (aucune migration) ; les autres ajoutent un suffixe.

    private val keyProfiles = stringPreferencesKey("profiles")
    private val keyActive = stringPreferencesKey("active_profile")
    private val profileListSerializer = ListSerializer(Profile.serializer())

    private fun scoped(base: String, profile: String) = if (profile == DEFAULT_PROFILE) base else "${base}__$profile"
    private fun Preferences.activeId(): String = this[keyActive] ?: DEFAULT_PROFILE
    private fun setK(base: String, profile: String) = stringSetPreferencesKey(scoped(base, profile))
    private fun strK(base: String, profile: String) = stringPreferencesKey(scoped(base, profile))

    private fun decodeProfiles(raw: String?): List<Profile> =
        raw?.let { runCatching { json.decodeFromString(profileListSerializer, it) }.getOrNull() }.orEmpty()
            .ifEmpty { listOf(Profile(DEFAULT_PROFILE, "Principal")) }

    val profiles: Flow<List<Profile>> = context.dataStore.data.map { decodeProfiles(it[keyProfiles]) }
    val activeProfile: Flow<String> = context.dataStore.data.map { it.activeId() }

    suspend fun setActiveProfile(id: String) {
        context.dataStore.edit { it[keyActive] = id }
    }

    suspend fun addProfile(name: String): Profile {
        val profile = Profile("p" + System.currentTimeMillis().toString(36), name.trim().take(20).ifBlank { "Profil" })
        context.dataStore.edit { prefs ->
            prefs[keyProfiles] = json.encodeToString(profileListSerializer, decodeProfiles(prefs[keyProfiles]) + profile)
        }
        return profile
    }

    /** Supprime un profil et ses données ; le dernier profil ne peut pas être supprimé. */
    suspend fun deleteProfile(id: String) {
        context.dataStore.edit { prefs ->
            val list = decodeProfiles(prefs[keyProfiles])
            if (list.size <= 1) return@edit
            val rest = list.filterNot { it.id == id }
            prefs[keyProfiles] = json.encodeToString(profileListSerializer, rest)
            PROFILE_SET_KEYS.forEach { prefs.remove(setK(it, id)) }
            PROFILE_STRING_KEYS.forEach { prefs.remove(strK(it, id)) }
            if (prefs.activeId() == id) prefs[keyActive] = rest.first().id
        }
    }

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

    val favorites: Flow<Set<String>> = context.dataStore.data.map { it[setK("favorites", it.activeId())].orEmpty() }

    /** Ma liste : films (« m:id ») et séries (« s:id »). */
    val favoritesVod: Flow<Set<String>> = context.dataStore.data.map { it[setK("favorites_vod", it.activeId())].orEmpty() }

    suspend fun toggleFavoriteVod(key: String) {
        context.dataStore.edit { prefs ->
            val k = setK("favorites_vod", prefs.activeId())
            val current = prefs[k].orEmpty()
            prefs[k] = if (key in current) current - key else current + key
        }
    }

    val lastChannel: Flow<String?> = context.dataStore.data.map { it[strK("last_channel", it.activeId())] }

    suspend fun saveCredentials(creds: Credentials?) {
        context.dataStore.edit { prefs ->
            // Jamais de stockage en clair : si le chiffrement échoue, rien n'est enregistré.
            val encrypted = creds?.let { SecretBox.encrypt(json.encodeToString(Credentials.serializer(), it)) }
            if (encrypted == null) prefs.remove(keyCreds) else prefs[keyCreds] = encrypted
        }
    }

    suspend fun toggleFavorite(channelId: String) {
        context.dataStore.edit { prefs ->
            val k = setK("favorites", prefs.activeId())
            val current = prefs[k].orEmpty()
            prefs[k] = if (channelId in current) current - channelId else current + channelId
        }
    }

    suspend fun setLastChannel(channelId: String) {
        context.dataStore.edit { it[strK("last_channel", it.activeId())] = channelId }
    }

    // ---- Chaînes récentes (la plus récente en premier) ----

    private val idListSerializer = ListSerializer(String.serializer())

    private fun decodeIds(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString(idListSerializer, it) }.getOrNull() }.orEmpty()

    val recentChannels: Flow<List<String>> = context.dataStore.data.map { decodeIds(it[strK("recent_channels", it.activeId())]) }

    suspend fun addRecent(channelId: String) {
        context.dataStore.edit { prefs ->
            val k = strK("recent_channels", prefs.activeId())
            val updated = (listOf(channelId) + decodeIds(prefs[k]).filterNot { it == channelId }).take(15)
            prefs[k] = json.encodeToString(idListSerializer, updated)
        }
    }

    // ---- Films et épisodes terminés (coche « vu ») ----

    val watched: Flow<Set<String>> = context.dataStore.data.map { it[setK("watched", it.activeId())].orEmpty() }

    suspend fun markWatched(key: String) {
        context.dataStore.edit { prefs ->
            val k = setK("watched", prefs.activeId())
            prefs[k] = prefs[k].orEmpty() + key
        }
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

    // ---- Tendances TMDB : gardées quelques heures, pour ne pas les redemander à chaque lancement ----

    private val keyTrends = stringPreferencesKey("trends_cache")

    suspend fun loadTrends(): TmdbTrends? = context.dataStore.data.first()[keyTrends]
        ?.let { runCatching { json.decodeFromString(TmdbTrends.serializer(), it) }.getOrNull() }

    suspend fun saveTrends(trends: TmdbTrends) {
        context.dataStore.edit { it[keyTrends] = json.encodeToString(TmdbTrends.serializer(), trends) }
    }

    // ---- Dernières sélections TMDB (Top 10 et bandeaux) : affichées dès l'ouverture ----

    private val keyPicks = stringPreferencesKey("picks_cache")

    suspend fun loadPicks(): TmdbPicks? = context.dataStore.data.first()[keyPicks]
        ?.let { runCatching { json.decodeFromString(TmdbPicks.serializer(), it) }.getOrNull() }

    suspend fun savePicks(picks: TmdbPicks) {
        context.dataStore.edit { it[keyPicks] = json.encodeToString(TmdbPicks.serializer(), picks) }
    }

    // ---- Dernières suggestions « Pour vous » : affichées dès l'ouverture ----

    suspend fun loadForYou(): ForYouCache? = context.dataStore.data.first().let { it[strK("foryou_cache", it.activeId())] }
        ?.let { runCatching { json.decodeFromString(ForYouCache.serializer(), it) }.getOrNull() }

    suspend fun saveForYou(cache: ForYouCache) {
        context.dataStore.edit { it[strK("foryou_cache", it.activeId())] = json.encodeToString(ForYouCache.serializer(), cache) }
    }

    // ---- Reprise de lecture (films et épisodes) ----

    private val resumeSerializer = ListSerializer(ResumeEntry.serializer())

    private fun decodeResume(raw: String?): List<ResumeEntry> =
        raw?.let { runCatching { json.decodeFromString(resumeSerializer, it) }.getOrNull() }.orEmpty()

    val resume: Flow<List<ResumeEntry>> = context.dataStore.data.map { decodeResume(it[strK("resume", it.activeId())]) }

    suspend fun saveResume(entry: ResumeEntry) = editResume { list ->
        (listOf(entry) + list.filterNot { it.key == entry.key }).take(20)
    }

    suspend fun removeResume(key: String) = editResume { list -> list.filterNot { it.key == key } }

    private suspend fun editResume(transform: (List<ResumeEntry>) -> List<ResumeEntry>) {
        context.dataStore.edit { prefs ->
            val k = strK("resume", prefs.activeId())
            val updated = transform(decodeResume(prefs[k]))
            prefs[k] = json.encodeToString(resumeSerializer, updated)
        }
    }

    // ---- Sauvegarde et restauration ----

    /** Tout ce qui appartient à l'utilisateur (profils, favoris, historique, catégories masquées), en JSON. */
    suspend fun exportBackup(includeAccount: Boolean, channelPreview: Boolean): String {
        val prefs = context.dataStore.data.first()
        val profiles = decodeProfiles(prefs[keyProfiles]).map { p ->
            ProfileData(
                id = p.id,
                name = p.name,
                favorites = prefs[setK("favorites", p.id)].orEmpty(),
                favoritesVod = prefs[setK("favorites_vod", p.id)].orEmpty(),
                recent = decodeIds(prefs[strK("recent_channels", p.id)]),
                watched = prefs[setK("watched", p.id)].orEmpty(),
                resume = decodeResume(prefs[strK("resume", p.id)]),
                lastChannel = prefs[strK("last_channel", p.id)],
            )
        }
        val credentials = if (includeAccount) {
            prefs[keyCreds]?.let { SecretBox.decrypt(it) }
                ?.let { runCatching { json.decodeFromString(Credentials.serializer(), it) }.getOrNull() }
        } else {
            null
        }
        val backup = Backup(
            exportedAt = System.currentTimeMillis(),
            activeProfile = prefs.activeId(),
            profiles = profiles,
            hidden = Section.entries.associate { it.name to prefs[hiddenKey(it)].orEmpty().toList() },
            channelPreview = channelPreview,
            credentials = credentials,
        )
        return json.encodeToString(Backup.serializer(), backup)
    }

    /** Remplace les données de l'utilisateur par celles du fichier ; null si le fichier n'est pas une sauvegarde iTV. */
    suspend fun importBackup(raw: String): Backup? {
        val backup = runCatching { json.decodeFromString(Backup.serializer(), raw) }.getOrNull() ?: return null
        if (backup.profiles.isEmpty()) return null
        context.dataStore.edit { prefs ->
            // Efface les profils actuels, puis écrit ceux du fichier.
            decodeProfiles(prefs[keyProfiles]).forEach { p ->
                PROFILE_SET_KEYS.forEach { prefs.remove(setK(it, p.id)) }
                PROFILE_STRING_KEYS.forEach { prefs.remove(strK(it, p.id)) }
            }
            prefs[keyProfiles] = json.encodeToString(profileListSerializer, backup.profiles.map { Profile(it.id, it.name) })
            backup.profiles.forEach { p ->
                prefs[setK("favorites", p.id)] = p.favorites
                prefs[setK("favorites_vod", p.id)] = p.favoritesVod
                prefs[setK("watched", p.id)] = p.watched
                prefs[strK("recent_channels", p.id)] = json.encodeToString(idListSerializer, p.recent)
                prefs[strK("resume", p.id)] = json.encodeToString(resumeSerializer, p.resume)
                p.lastChannel?.let { prefs[strK("last_channel", p.id)] = it }
            }
            prefs[keyActive] = backup.activeProfile.takeIf { id -> backup.profiles.any { it.id == id } } ?: backup.profiles.first().id
            Section.entries.forEach { prefs[hiddenKey(it)] = backup.hidden[it.name].orEmpty().toSet() }
            backup.credentials?.let { c ->
                SecretBox.encrypt(json.encodeToString(Credentials.serializer(), c))?.let { prefs[keyCreds] = it }
            }
        }
        return backup
    }

    private companion object {
        val PROFILE_SET_KEYS = listOf("favorites", "favorites_vod", "watched")
        val PROFILE_STRING_KEYS = listOf("last_channel", "recent_channels", "resume", "foryou_cache")
    }
}
