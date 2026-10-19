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
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "poptv")

class Store(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val keyCreds = stringPreferencesKey("credentials_enc")
    private val keyFavorites = stringSetPreferencesKey("favorites")
    private val keyLast = stringPreferencesKey("last_channel")

    val credentials: Flow<Credentials?> = context.dataStore.data.map { prefs ->
        prefs[keyCreds]
            ?.let { SecretBox.decrypt(it) }
            ?.let { runCatching { json.decodeFromString(Credentials.serializer(), it) }.getOrNull() }
    }

    val favorites: Flow<Set<String>> = context.dataStore.data.map { it[keyFavorites].orEmpty() }

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
