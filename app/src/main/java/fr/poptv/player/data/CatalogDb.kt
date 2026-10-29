package fr.poptv.player.data

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Normalizer

/**
 * Catalogues films / séries dans une base SQLite : rien n'est gardé en entier en mémoire,
 * l'écran ne demande que ce qu'il affiche (rangées, une catégorie, une recherche).
 *
 * Un téléchargement écrit sous une nouvelle « génération » ; elle ne remplace l'ancienne qu'à la
 * fin (commit), donc un échec en cours de route laisse le catalogue précédent intact.
 */
object CatalogDb {
    private const val NAME = "catalog.db"
    private const val VERSION = 1
    private const val BATCH = 1000

    private lateinit var helper: Helper
    private val gens = IntArray(2)
    private val times = LongArray(2)

    fun init(context: Context) {
        if (::helper.isInitialized) return
        helper = Helper(context.applicationContext)
        val db = helper.writableDatabase
        for (s in 0..1) {
            gens[s] = metaLong(db, "gen_$s").toInt()
            times[s] = metaLong(db, "time_$s")
        }
    }

    private fun idx(section: Section) = if (section == Section.MOVIES) 0 else 1
    private fun sec(section: Section) = idx(section)
    private fun gen(section: Section) = gens[idx(section)]
    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun metaLong(db: SQLiteDatabase, key: String): Long =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun setMeta(db: SQLiteDatabase, key: String, value: Long) {
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", arrayOf<Any>(key, value))
    }

    // ---- État ----

    fun count(section: Section): Int = db.rawQuery(
        "SELECT COUNT(*) FROM vod WHERE section=? AND gen=?", arrayOf(sec(section).toString(), gen(section).toString()),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun hasData(section: Section): Boolean = gen(section) > 0 && count(section) > 0

    /** Ancienneté du catalogue, ou null s'il n'a jamais été téléchargé. */
    fun ageMs(section: Section): Long? =
        times[idx(section)].takeIf { it > 0 }?.let { System.currentTimeMillis() - it }

    /** Catégories, catégories exclues du téléchargement et nombre de titres par catégorie (sans les titres). */
    fun meta(section: Section): VodCatalog {
        val s = sec(section).toString()
        val g = gen(section).toString()
        val categories = ArrayList<Category>()
        val excluded = ArrayList<String>()
        db.rawQuery("SELECT id,name,excluded FROM cats WHERE section=? AND gen=? ORDER BY pos", arrayOf(s, g)).use {
            while (it.moveToNext()) {
                categories.add(Category(id = it.getString(0), name = it.getString(1)))
                if (it.getInt(2) == 1) excluded.add(it.getString(0))
            }
        }
        val counts = HashMap<String, Int>()
        db.rawQuery("SELECT cat,COUNT(*) FROM vod WHERE section=? AND gen=? GROUP BY cat", arrayOf(s, g)).use {
            while (it.moveToNext()) counts[it.getString(0)] = it.getInt(1)
        }
        return VodCatalog(categories = categories, excluded = excluded, counts = counts)
    }

    // ---- Écriture ----

    class Writer internal constructor(private val section: Section, private val gen: Int) {
        private val buffer = ArrayList<VodItem>(BATCH)

        /** Les titres sont groupés puis écrits d'un coup : la transaction reste sur un seul fil. */
        fun add(item: VodItem) {
            val batch: List<VodItem>? = synchronized(buffer) {
                buffer.add(item)
                if (buffer.size >= BATCH) ArrayList(buffer).also { buffer.clear() } else null
            }
            if (batch != null) write(batch)
        }

        fun close() {
            val batch = synchronized(buffer) { ArrayList(buffer).also { buffer.clear() } }
            if (batch.isNotEmpty()) write(batch)
        }

        private fun write(batch: List<VodItem>) {
            val db = CatalogDb.db
            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT OR IGNORE INTO vod(section,gen,id,name,norm,srch,poster,cat,ext,year,rating,ratingNum,plot,added,url) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                )
                for (item in batch) {
                    stmt.clearBindings()
                    stmt.bindLong(1, CatalogDb.sec(section).toLong())
                    stmt.bindLong(2, gen.toLong())
                    stmt.bindString(3, item.id)
                    stmt.bindString(4, item.name)
                    stmt.bindString(5, TmdbMatcher.norm(item.name))
                    stmt.bindString(6, fold(item.name))
                    item.poster?.let { stmt.bindString(7, it) }
                    stmt.bindString(8, item.categoryId)
                    stmt.bindString(9, item.ext)
                    item.year?.let { stmt.bindString(10, it) }
                    item.rating?.let { stmt.bindString(11, it) }
                    stmt.bindDouble(12, ratingValue(item.rating))
                    item.plot?.let { stmt.bindString(13, it) }
                    stmt.bindLong(14, item.added)
                    item.url?.let { stmt.bindString(15, it) }
                    stmt.executeInsert()
                }
                stmt.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Démarre un téléchargement : renvoie la génération à écrire. */
    fun beginLoad(section: Section): Int {
        val s = sec(section)
        // Reste éventuel d'un téléchargement interrompu.
        db.execSQL("DELETE FROM vod WHERE section=? AND gen<>?", arrayOf<Any>(s, gen(section)))
        db.execSQL("DELETE FROM cats WHERE section=? AND gen<>?", arrayOf<Any>(s, gen(section)))
        return gen(section) + 1
    }

    fun writer(section: Section, gen: Int) = Writer(section, gen)

    /** Termine un téléchargement : la nouvelle génération remplace l'ancienne d'un seul coup. */
    fun commit(section: Section, gen: Int, categories: List<Category>, excluded: Collection<String>) {
        val s = sec(section)
        val db = db
        db.beginTransaction()
        try {
            categories.forEachIndexed { i, c ->
                db.execSQL(
                    "INSERT OR REPLACE INTO cats(section,gen,id,name,pos,excluded) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any>(s, gen, c.id, c.name, i, if (c.id in excluded) 1 else 0),
                )
            }
            val now = System.currentTimeMillis()
            setMeta(db, "gen_$s", gen.toLong())
            setMeta(db, "time_$s", now)
            db.execSQL("DELETE FROM vod WHERE section=? AND gen<>?", arrayOf<Any>(s, gen))
            db.execSQL("DELETE FROM cats WHERE section=? AND gen<>?", arrayOf<Any>(s, gen))
            db.setTransactionSuccessful()
            gens[s] = gen
            times[s] = now
        } finally {
            db.endTransaction()
        }
    }

    fun abort(section: Section, gen: Int) {
        db.execSQL("DELETE FROM vod WHERE section=? AND gen=?", arrayOf<Any>(sec(section), gen))
    }

    /** Catalogue déjà trié en mémoire (liste M3U) : on l'écrit d'un bloc. */
    fun replace(section: Section, catalog: VodCatalog) {
        val g = beginLoad(section)
        val w = writer(section, g)
        try {
            catalog.items.forEach { w.add(it) }
        } finally {
            w.close()
        }
        commit(section, g, catalog.categories, catalog.excluded)
    }

    fun clear() {
        val db = db
        db.execSQL("DELETE FROM vod")
        db.execSQL("DELETE FROM cats")
        db.execSQL("DELETE FROM meta")
        gens.fill(0)
        times.fill(0)
    }

    // ---- Lecture ----

    private const val COLS = "id,name,poster,cat,ext,year,rating,plot,added,url"

    private fun Cursor.item() = VodItem(
        id = getString(0),
        name = getString(1),
        poster = getString(2),
        categoryId = getString(3),
        ext = getString(4) ?: "mp4",
        year = getString(5),
        rating = getString(6),
        plot = getString(7),
        added = getLong(8),
        url = getString(9),
    )

    private fun hiddenClause(hidden: Set<String>): String =
        if (hidden.isEmpty()) "" else " AND cat NOT IN (" + hidden.joinToString(",") { DatabaseUtils.sqlEscapeString(it) } + ")"

    private fun base(section: Section) = "SELECT $COLS FROM vod WHERE section=${sec(section)} AND gen=${gen(section)}"

    private fun run(sql: String, args: Array<String> = emptyArray()): List<VodItem> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<VodItem>(c.count)
            while (c.moveToNext()) out.add(c.item())
            out
        }

    /** Ajouts récents (titres datés). */
    fun recent(section: Section, hidden: Set<String>, limit: Int) =
        run(base(section) + hiddenClause(hidden) + " AND added>0 ORDER BY added DESC LIMIT $limit")

    /** Les mieux notés. */
    fun top(section: Section, hidden: Set<String>, limit: Int) =
        run(base(section) + hiddenClause(hidden) + " AND ratingNum>0 ORDER BY ratingNum DESC LIMIT $limit")

    /** Premiers titres d'une catégorie (ordre du fournisseur). */
    fun inCategory(section: Section, category: String, limit: Int) =
        run(base(section) + " AND cat=? ORDER BY rid LIMIT $limit", arrayOf(category))

    /** Nombre de titres d'une catégorie. */
    fun categorySize(section: Section, category: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM vod WHERE section=${sec(section)} AND gen=${gen(section)} AND cat=?", arrayOf(category),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Grille : tout le catalogue ou une catégorie, avec recherche facultative (sans accents ni casse). */
    fun grid(section: Section, hidden: Set<String>, category: String?, query: String, limit: Int = 3000): List<VodItem> {
        val q = fold(query)
        val sql = StringBuilder(base(section))
        val args = ArrayList<String>()
        if (category != null) { sql.append(" AND cat=?"); args.add(category) } else sql.append(hiddenClause(hidden))
        if (q.isNotEmpty()) {
            for (word in q.split(' ').filter { it.isNotEmpty() }) {
                sql.append(" AND srch LIKE ? ESCAPE '\\'")
                args.add("%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            }
        }
        sql.append(" ORDER BY rid LIMIT $limit")
        return run(sql.toString(), args.toTypedArray())
    }

    fun byIds(section: Section, ids: Collection<String>): List<VodItem> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<VodItem>()
        for (chunk in ids.chunked(400)) {
            out += run(base(section) + " AND id IN (" + chunk.joinToString(",") { DatabaseUtils.sqlEscapeString(it) } + ")")
        }
        return out
    }

    fun byName(section: Section, name: String): VodItem? =
        run(base(section) + " AND name=? LIMIT 1", arrayOf(name)).firstOrNull()

    /** Titres dont le nom normalisé (TmdbMatcher.norm) est l'un de ceux demandés. */
    fun byNorms(section: Section, norms: Collection<String>): Map<String, List<VodItem>> {
        if (norms.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<VodItem>>()
        for (chunk in norms.distinct().chunked(300)) {
            val sql = "SELECT norm,$COLS FROM vod WHERE section=${sec(section)} AND gen=${gen(section)} AND norm IN (" +
                chunk.joinToString(",") { DatabaseUtils.sqlEscapeString(it) } + ")"
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val item = VodItem(
                        id = c.getString(1), name = c.getString(2), poster = c.getString(3), categoryId = c.getString(4),
                        ext = c.getString(5) ?: "mp4", year = c.getString(6), rating = c.getString(7),
                        plot = c.getString(8), added = c.getLong(9), url = c.getString(10),
                    )
                    out.getOrPut(c.getString(0)) { ArrayList(1) }.add(item)
                }
            }
        }
        return out
    }

    /** Titres des catégories données (pour « Pour vous »), les mieux notés d'abord. */
    fun candidates(section: Section, hidden: Set<String>, categories: Collection<String>, limit: Int): List<VodItem> {
        if (categories.isEmpty()) return emptyList()
        val inList = categories.joinToString(",") { DatabaseUtils.sqlEscapeString(it) }
        return run(base(section) + hiddenClause(hidden) + " AND cat IN ($inList) ORDER BY ratingNum DESC LIMIT $limit")
    }

    // ---- Utilitaires ----

    private val marks = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]+")

    /** Minuscules, sans accents ni ponctuation : sert à la recherche. */
    fun fold(raw: String): String {
        val s = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(marks, "")
        return s.replace(nonAlnum, " ").trim()
    }

    fun ratingValue(raw: String?): Double =
        raw?.replace(',', '.')?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE vod(rid INTEGER PRIMARY KEY AUTOINCREMENT, section INTEGER NOT NULL, gen INTEGER NOT NULL, " +
                    "id TEXT NOT NULL, name TEXT NOT NULL, norm TEXT, srch TEXT, poster TEXT, cat TEXT, ext TEXT, year TEXT, " +
                    "rating TEXT, ratingNum REAL, plot TEXT, added INTEGER, url TEXT)",
            )
            db.execSQL("CREATE UNIQUE INDEX vod_id ON vod(section,gen,id)")
            db.execSQL("CREATE INDEX vod_cat ON vod(section,gen,cat)")
            db.execSQL("CREATE INDEX vod_norm ON vod(section,gen,norm)")
            db.execSQL("CREATE INDEX vod_name ON vod(section,gen,name)")
            db.execSQL("CREATE INDEX vod_added ON vod(section,gen,added)")
            db.execSQL("CREATE INDEX vod_rating ON vod(section,gen,ratingNum)")
            db.execSQL(
                "CREATE TABLE cats(section INTEGER NOT NULL, gen INTEGER NOT NULL, id TEXT NOT NULL, name TEXT, " +
                    "pos INTEGER, excluded INTEGER, PRIMARY KEY(section,gen,id))",
            )
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
