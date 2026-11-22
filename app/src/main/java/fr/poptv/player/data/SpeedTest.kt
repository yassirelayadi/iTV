package fr.poptv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Test de débit : télécharge pendant quelques secondes et mesure le débit réel (en Mb/s). */
object SpeedTest {
    /** Fichiers de test de serveurs rapides (essayés dans l'ordre) : mesurent la connexion internet de l'appareil. */
    val INTERNET_URLS = listOf(
        "https://proof.ovh.net/files/100Mb.dat",
        "https://speed.cloudflare.com/__down?bytes=50000000",
        "https://ash-speed.hetzner.com/100MB.bin",
    )

    /** Débit internet : premier serveur de test qui répond. */
    suspend fun internet(seconds: Int = 8): Double? {
        for (url in INTERNET_URLS) {
            measure(url, seconds, userAgent = "Mozilla/5.0 (Linux; Android) iTV")?.let { return it }
        }
        return null
    }

    /**
     * Débit en Mb/s mesuré sur [seconds] secondes, à partir du premier octet reçu (le temps de connexion n'est pas compté).
     * null si le téléchargement échoue.
     */
    suspend fun measure(url: String, seconds: Int = 8, userAgent: String = UA): Double? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            Http.client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@withContext null
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                var start = 0L
                val limitNs = seconds * 1_000_000_000L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (start == 0L) start = System.nanoTime() else bytes += n
                    if (System.nanoTime() - start >= limitNs) break
                }
                val elapsed = (System.nanoTime() - start) / 1e9
                if (start == 0L || elapsed < 0.5) null else bytes * 8 / elapsed / 1_000_000
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
