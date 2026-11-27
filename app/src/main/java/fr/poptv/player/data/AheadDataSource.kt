package fr.poptv.player.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Préchargement d'un film sur le stockage de la TV, avec UNE seule connexion au serveur.
 *
 * Un téléchargeur lit le film en continu, au rythme du serveur, et l'écrit dans un fichier temporaire ; le lecteur
 * lit ce fichier. L'avance n'est plus limitée par la mémoire vive : une pause laisse le téléchargement prendre
 * des minutes d'avance. Un saut en dehors de ce qui est téléchargé relance le téléchargement à la nouvelle position.
 */
@UnstableApi
class AheadFetcher(
    private val url: String,
    private val file: File,
    private val maxAheadBytes: Long,
    private val onBytes: (Int) -> Unit,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Plage téléchargée en continu : [start, end).
    private var start = 0L
    private var end = 0L
    private var readPos = 0L
    var totalLength = C.LENGTH_UNSET.toLong()
        private set
    private var finished = false
    private var error: IOException? = null
    private var generation = 0
    private var thread: Thread? = null
    private var call: okhttp3.Call? = null
    private var released = false
    private val raf = RandomAccessFile(file, "rw")

    /** Octets téléchargés d'avance par rapport à la lecture. */
    fun aheadBytes(): Long = lock.withLock { (end - readPos).coerceAtLeast(0) }

    /** Prépare une lecture à partir de [position] : relance le téléchargement si la position n'est pas couverte. */
    fun prepare(position: Long) = lock.withLock {
        if (released) throw IOException("Lecture fermée")
        readPos = position
        // Un petit saut en avant est couvert : il suffit d'attendre que le téléchargement l'atteigne.
        val covered = position >= start && position <= end + GAP
        if (!covered) {
            restartLocked(position)
        } else if (thread == null && !finished) {
            // Téléchargement arrêté (erreur réseau) : on reprend là où il s'était arrêté.
            restartLocked(end, keepRange = true)
        }
    }

    /** Lit jusqu'à [length] octets à [position] ; attend que le téléchargement les ait reçus. -1 en fin de film. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        lock.withLock {
            readPos = position
            changed.signalAll()
            while (true) {
                if (released) throw IOException("Lecture fermée")
                if (position < start || position > end + GAP) restartLocked(position)
                if (position < end) break
                if (finished) return C.RESULT_END_OF_INPUT
                error?.let { e ->
                    error = null
                    throw e
                }
                if (thread == null) restartLocked(end, keepRange = true)
                try {
                    changed.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException()
                }
            }
            val n = minOf(length.toLong(), end - position).toInt()
            raf.seek(position - start)
            raf.readFully(buffer, offset, n)
            return n
        }
    }

    private companion object {
        const val GAP = 4L * 1024 * 1024
    }

    fun release() {
        lock.withLock {
            released = true
            generation++
            call?.cancel()
            changed.signalAll()
        }
        thread?.interrupt()
        runCatching { raf.close() }
        file.delete()
    }

    private fun restartLocked(position: Long, keepRange: Boolean = false) {
        generation++
        val gen = generation
        if (!keepRange) {
            start = position
            end = position
            runCatching { raf.setLength(0) }
        }
        finished = false
        error = null
        // Une seule connexion : l'ancienne est coupée net avant d'ouvrir la nouvelle.
        call?.cancel()
        call = null
        thread?.interrupt()
        thread = Thread({ download(gen, position) }, "iTV-prechargement").apply {
            isDaemon = true
            start()
        }
    }

    private fun download(gen: Int, from: Long) {
        try {
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            if (from > 0) builder.header("Range", "bytes=$from-")
            val c = Http.client.newCall(builder.build())
            lock.withLock {
                if (gen != generation) return
                call = c
            }
            c.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Réponse vide")
                val input = body.byteStream()
                // Taille totale : « Content-Range: bytes a-b/total », sinon longueur + position.
                val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: body.contentLength().takeIf { it > 0 }?.let { if (response.code == 206) it + from else it }
                lock.withLock { if (gen == generation && total != null) totalLength = total }
                // Serveur qui ignore la demande de plage : on saute le début.
                if (from > 0 && response.code == 200) {
                    var skip = from
                    while (skip > 0) {
                        val s = input.skip(skip)
                        if (s <= 0) throw IOException("Plage non prise en charge")
                        skip -= s
                    }
                }
                val buf = ByteArray(64 * 1024)
                while (true) {
                    // Assez d'avance : on attend que la lecture progresse (la connexion reste ouverte et se remplit).
                    lock.withLock {
                        while (gen == generation && end - readPos >= maxAheadBytes) {
                            changed.await(1, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        if (gen != generation) return
                    }
                    val n = input.read(buf)
                    if (n < 0) {
                        lock.withLock {
                            if (gen == generation) {
                                finished = true
                                thread = null
                                changed.signalAll()
                            }
                        }
                        return
                    }
                    onBytes(n)
                    lock.withLock {
                        if (gen != generation) return
                        raf.seek(end - start)
                        raf.write(buf, 0, n)
                        end += n
                        changed.signalAll()
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Remplacé par un autre téléchargement.
        } catch (e: IOException) {
            lock.withLock {
                if (gen == generation) {
                    error = e
                    thread = null
                    changed.signalAll()
                }
            }
        }
    }
}

/** Source de lecture qui lit le film préchargé par [AheadFetcher] (un téléchargeur par adresse). */
@UnstableApi
class AheadDataSource(private val fetcherFor: (String) -> AheadFetcher) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var fetcher: AheadFetcher? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val f = fetcherFor(dataSpec.uri.toString())
        fetcher = f
        position = dataSpec.position
        f.prepare(position)
        val total = f.totalLength
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total != C.LENGTH_UNSET.toLong() -> (total - position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = fetcher!!.read(position, buffer, offset, want)
        if (n == C.RESULT_END_OF_INPUT) return n
        position += n
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (fetcher != null) {
            fetcher = null
            transferEnded()
        }
    }
}

/** Fabrique : un seul téléchargeur par film, supprimé avec la lecture. */
@UnstableApi
class AheadDataSourceFactory(
    private val dir: File,
    private val maxAheadBytes: Long,
    private val onBytes: (Int) -> Unit,
) : DataSource.Factory {
    private val fetchers = HashMap<String, AheadFetcher>()

    @Synchronized
    fun fetcher(url: String): AheadFetcher = fetchers.getOrPut(url) {
        dir.mkdirs()
        AheadFetcher(url, File(dir, "film-${fetchers.size}-${System.nanoTime()}.part"), maxAheadBytes, onBytes)
    }

    /** Avance téléchargée (octets) du film en cours. */
    @Synchronized
    fun aheadBytes(): Long = fetchers.values.maxOfOrNull { it.aheadBytes() } ?: 0L

    override fun createDataSource(): DataSource = AheadDataSource(::fetcher)

    @Synchronized
    fun release() {
        fetchers.values.forEach { it.release() }
        fetchers.clear()
        dir.listFiles()?.forEach { it.delete() }
    }
}
