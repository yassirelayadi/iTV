package fr.poptv.player

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Garde la trace du dernier plantage dans un petit fichier, affichée au lancement suivant :
 * on peut ainsi diagnostiquer un problème sur la télé sans outil. Les identifiants en sont retirés.
 */
object CrashReport {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                file(app).writeText(scrub(writer.toString()).take(6000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Retire tout ce qui ressemble à un identifiant ou à une adresse de lecture. */
    private fun scrub(text: String): String = text
        .replace(Regex("(?i)(username|password)=[^&\\s\"]*"), "$1=***")
        .replace(Regex("/(live|movie|series)/[^/\\s\"]+/[^/\\s\"]+/"), "/$1/***/***/")
        .replace(Regex("https?://[^\\s\"]+"), "<adresse>")
}
