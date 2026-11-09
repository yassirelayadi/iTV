package fr.poptv.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File

/** Mise à jour depuis la dernière version publiée sur GitHub. */
object Updater {
    private const val BASE = "https://github.com/yassirelayadi/iTV/releases/download/derniere-version/"

    sealed interface Check {
        data object UpToDate : Check
        data object Available : Check
        data class Failed(val reason: String) : Check
    }

    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(Request.Builder().url(BASE + "version.json").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext Check.Failed("Aucune information de version publiée")
                val obj = Json.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject
                val latest = obj["code"]?.jsonPrimitive?.longOrNull ?: return@withContext Check.Failed("Information illisible")
                if (latest > installedCode(context)) Check.Available else Check.UpToDate
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Check.Failed("Connexion impossible")
        }
    }

    /** Télécharge l'APK dans le cache de l'appli ; renvoie le fichier, ou null en cas d'échec. */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "iTV.apk")
            Http.client.newCall(Request.Builder().url(BASE + "iTV.apk").build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return@withContext null
                val total = body.contentLength()
                var done = 0L
                var last = -1
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) { last = pct; onProgress(pct) }
                            }
                        }
                    }
                }
            }
            target
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Ouvre l'installateur Android. Renvoie false si l'autorisation « sources inconnues » manque (on ouvre alors son réglage). */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
