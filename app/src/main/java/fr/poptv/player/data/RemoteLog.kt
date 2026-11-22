package fr.poptv.player.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Journal de diagnostic envoyé à une base Supabase (table « logs », ajout seulement : l'appli ne peut rien y lire).
 * Il ne contient jamais l'identifiant ni le mot de passe IPTV : les adresses sont retirées des messages.
 * Désactivé par défaut : chaque personne l'active elle-même dans les réglages (l'appli est installée sur plusieurs TV).
 */
object RemoteLog {
    private const val ENDPOINT = "https://xosqsyufujwjduxhgnpk.supabase.co/rest/v1/logs"
    private const val KEY = "sb_publishable_BATafSeEk7b8PaqtA4lV8Q_JmNdEHX0"
    private const val PREFS = "itv_ui"
    private const val PREF_KEY = "remote_log"

    private val queue = ConcurrentLinkedQueue<JsonObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var device = ""
    private var version = 0
    @Volatile var enabled = false
        private set

    /** Identifiant anonyme de cette installation (6 caractères), pour distinguer les TV dans le journal. */
    var installId = ""
        private set

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(PREF_KEY, false)
        installId = prefs.getString("install_id", null) ?: java.util.UUID.randomUUID().toString().take(6).also {
            prefs.edit().putString("install_id", it).apply()
        }
        device = "$installId · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}".take(120)
        version = runCatching { Updater.installedCode(app).toInt() }.getOrDefault(0)
        scope.launch {
            while (true) {
                delay(10_000)
                flush()
            }
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_KEY, value).apply()
        if (!value) queue.clear()
    }

    /** Retire toute adresse (elles contiennent l'identifiant et le mot de passe IPTV). */
    fun clean(text: String?): String = (text ?: "")
        .replace(Regex("""\w+://\S+"""), "<adresse>")
        // Noms de serveur (ex. « Unable to resolve host "serveur.com" ») et adresses IP : le fournisseur n'apparaît pas.
        .replace(Regex("""\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b"""), "<serveur>")
        .replace(Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b"""), "<ip>")
        .take(300)

    fun log(session: String, event: String, vararg data: Pair<String, Any?>) {
        if (!enabled || !started) return
        val payload = JsonObject(
            data.associate { (k, v) ->
                k to when (v) {
                    null -> JsonPrimitive(null as String?)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(clean(v.toString()))
                }
            },
        )
        queue.add(
            JsonObject(
                mapOf(
                    "device" to JsonPrimitive(device),
                    "app_version" to JsonPrimitive(version),
                    "session" to JsonPrimitive(session.take(64)),
                    "event" to JsonPrimitive(event.take(64)),
                    "data" to payload,
                ),
            ),
        )
        while (queue.size > 500) queue.poll()
    }

    /** Envoie ce qui est en attente (appelé toutes les 10 s, et à la fin d'une lecture). */
    fun flushSoon() {
        scope.launch { flush() }
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val batch = ArrayList<JsonObject>()
        while (batch.size < 100) batch.add(queue.poll() ?: break)
        if (batch.isEmpty()) return
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("apikey", KEY)
            .header("Prefer", "return=minimal")
            .post(JsonArray(batch).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val ok = runCatching { Http.client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        // En cas d'échec réseau, on remet le lot en file (dans la limite de 500 entrées).
        if (!ok) batch.asReversed().forEach { if (queue.size < 500) queue.add(it) }
    }
}
