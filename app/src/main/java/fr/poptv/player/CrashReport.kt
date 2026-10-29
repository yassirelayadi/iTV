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

    /**
     * Android tue parfois l'appli sans aucune erreur (manque de mémoire) : l'écran devient noir et rien n'est écrit.
     * Le système, lui, garde la raison de l'arrêt : on la relit au lancement suivant (Android 11 et plus).
     * Seuls les arrêts survenus appli à l'écran sont signalés, pas ceux d'une appli en arrière-plan.
     */
    private fun recordPreviousExit(app: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val seenFile = File(app.filesDir, "exit_seen.txt")
            val seen = seenFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
            val exit = manager.getHistoricalProcessExitReasons(app.packageName, 0, 5)
                .firstOrNull { it.timestamp > seen } ?: return
            seenFile.writeText(exit.timestamp.toString())
            val label = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Manque de mémoire (arrêt par le système)"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "Arrêt forcé par le système (signal ${exit.status})"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Plantage natif"
                android.app.ApplicationExitInfo.REASON_ANR -> "Appli ne répondant plus (ANR)"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Ressources excessives"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Échec au démarrage"
                else -> return
            }
            if (exit.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return
            if (file(app).exists()) return // une erreur détaillée existe déjà
            val text = buildString {
                appendLine("Arrêt de l'appli sans message d'erreur")
                appendLine(label)
                appendLine("Mémoire utilisée : ${exit.rss / 1024} Mo (pss ${exit.pss / 1024} Mo)")
                exit.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            }
            file(app).writeText(scrub(text))
        }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        recordPreviousExit(app)
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
