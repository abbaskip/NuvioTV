package com.nuvio.tv.livetv.ondemand

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class VodKind(val key: String) { MOVIE("movie"), SERIES("series") }

/**
 * What makes two catalog entries the same title: the TMDB id when there is one, otherwise the
 * name and year. Used to show each title once ("Merge duplicates").
 */
private const val DUP_KEY = "CASE WHEN tmdb IS NOT NULL AND tmdb != '' THEN 't' || tmdb ELSE 'n' || norm || '|' || IFNULL(year, '') END"

/** How On Demand lists are sorted (the bar above the posters). */
enum class VodSort(val label: String) {
    DEFAULT("Provider's Order"),
    NAME_AZ("A–Z"),
    NAME_ZA("Z–A"),
    NEWEST("Newest Release"),
    OLDEST("Oldest Release"),
    RECENTLY_ADDED("Recently Added"),
    RATING("Highest Rated"),
    SHUFFLE("Shuffle");

    /**
     * The SQL order. Titles with no year (or no rating) go at the end, so they don't fill the
     * top. [seed] picks the Shuffle order, which stays the same while you scroll.
     */
    internal fun orderBy(fallback: String, seed: Long = 0): String = when (this) {
        DEFAULT -> fallback
        // Numbers and symbols sort before letters, so A–Z is #–Z and Z–A ends with them.
        NAME_AZ -> "norm ASC"
        NAME_ZA -> "norm DESC"
        NEWEST -> "year IS NULL, year DESC, added DESC"
        OLDEST -> "year IS NULL, year ASC, added ASC"
        RECENTLY_ADDED -> "added DESC"
        RATING -> "(rating IS NULL OR rating = '' OR CAST(rating AS REAL) <= 0), CAST(rating AS REAL) DESC, norm ASC"
        SHUFFLE -> "((rowid * 1103515245 + ${seed.coerceIn(1, 2_000_000_000)}) % 2147483647)"
    }

    companion object {
        /** A saved choice: "NAME_AZ", or "SHUFFLE:<seed>" (each pick of Shuffle is a new order). */
        fun parse(saved: String?): Pair<VodSort, Long>? {
            if (saved.isNullOrBlank()) return null
            val name = saved.substringBefore(':')
            val sort = entries.firstOrNull { it.name == name } ?: return null
            return sort to (saved.substringAfter(':', "").toLongOrNull() ?: 1L)
        }

        fun encode(sort: VodSort): String =
            if (sort == SHUFFLE) "SHUFFLE:" + (1L + kotlin.random.Random.nextLong(1_999_999_999L)) else sort.name
    }
}

/** One movie or series from a provider's On Demand catalog. */
data class VodItem(
    val playlistId: String,
    val kind: VodKind,
    /** stream_id for movies, series_id for series. */
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    /** Movies: the file type the provider serves (mp4, mkv…). */
    val ext: String?,
    val tmdbId: String?,
    val year: Int?,
    val rating: String?,
    val addedSec: Long,
    /** The provider's genres, as sent ("Action, Comedy"). Not every provider includes them. */
    val genre: String? = null,
    /** With duplicates merged: how many copies of this title there are (qualities, providers). */
    val copies: Int = 1
) {
    val uid: String get() = "${kind.key}:$playlistId:$id"
}

data class VodCategory(
    val playlistId: String,
    val kind: VodKind,
    val id: String,
    val name: String,
    val count: Int
) {
    /** Also the key used for hiding and locking ("movie:<playlist>:<category>"). */
    val uid: String get() = "${kind.key}:$playlistId:$id"
}

/**
 * On-device index of every provider's movies and series. Providers can list 100,000+ titles,
 * which would be far too much to keep in memory, so they live in a small SQLite database and
 * are read a page at a time.
 */
@Singleton
class OnDemandDatabase @Inject constructor(@ApplicationContext private val appContext: Context) :
    SQLiteOpenHelper(appContext, "livetv_on_demand.db", null, 9) {

    init {
        // Browsing On Demand doesn't wait while an import or genre lookup is writing.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items (
                pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL,
                name TEXT NOT NULL, norm TEXT NOT NULL, icon TEXT, cat TEXT NOT NULL,
                ext TEXT, tmdb TEXT, year INTEGER, rating TEXT, added INTEGER NOT NULL DEFAULT 0,
                genre TEXT, h INTEGER,
                PRIMARY KEY (pl, kind, id))"""
        )
        db.execSQL("CREATE INDEX items_cat ON items(kind, pl, cat)")
        db.execSQL("CREATE INDEX items_norm ON items(kind, norm)")
        db.execSQL("CREATE INDEX items_tmdb ON items(kind, tmdb)")
        db.execSQL("CREATE INDEX items_added ON items(kind, added)")
        createPosterTable(db)
        createPrefixTable(db)
        createPosterFixTable(db)
        db.execSQL(
            """CREATE TABLE categories (
                pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
                position INTEGER NOT NULL, PRIMARY KEY (pl, kind, id))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 only adds the poster cache; the imported catalogs are kept.
        if (oldVersion < 2) createPosterTable(db)
        // Version 3: titles are cleaned and matched by IMDb id now, so forget the old
        // "no poster found" answers (the catalogs themselves are kept).
        if (oldVersion < 3) db.execSQL("DELETE FROM posters")
        // Version 4: each provider's own title tags ("4K-D+ - ", "4k-NF - ") are learned and
        // removed, so look posters up again with the cleaner titles.
        if (oldVersion < 4) {
            createPrefixTable(db)
            db.execSQL("DELETE FROM posters")
        }
        // Version 5: genres (filled in by the next import).
        if (oldVersion < 5) runCatching { db.execSQL("ALTER TABLE items ADD COLUMN genre TEXT") }
        // Version 6: the matched title's proper name; titles like "007 - A View to a Kill" are
        // looked up again with the improved matching.
        if (oldVersion < 6) {
            runCatching { db.execSQL("ALTER TABLE posters ADD COLUMN title TEXT") }
            db.execSQL("DELETE FROM posters WHERE poster = ''")
        }
        // Version 7: release years taken from a number in the title by mistake ("2038 Origin
        // Unknown" came out as a 2038 release, "1917" as 1917) are worked out again.
        if (oldVersion < 7) runCatching { fixYearsFromNames(db) }
        // Version 8: titles are only matched to a same-named title from (about) the same year,
        // so look posters up again (some showed another film's poster and details).
        if (oldVersion < 8) db.execSQL("DELETE FROM posters")
        // Version 9: each title's fingerprint (imports only write what changed), titles you've
        // fixed by hand ("Fix poster"), and posters looked up again: matched by the provider's
        // TMDB id where it has one, and with cleaner titles.
        if (oldVersion < 9) {
            runCatching { db.execSQL("ALTER TABLE items ADD COLUMN h INTEGER") }
            createPosterFixTable(db)
            db.execSQL("DELETE FROM posters")
        }
    }

    private fun fixYearsFromNames(db: SQLiteDatabase) {
        val firstAny = Regex("(19|20)\\d{2}")
        val fixes = ArrayList<Pair<Long, Int?>>()
        db.rawQuery("SELECT rowid, name, year FROM items WHERE year IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val year = c.getInt(2)
                val fromNameBefore = setOfNotNull(
                    firstAny.find(name)?.value?.toIntOrNull(),
                    yearInTitle(name)
                )
                val proper = releaseYearInName(name)
                // Only years that came from the title: a year the provider sent is left alone.
                if (year in fromNameBefore && proper != year) fixes += c.getLong(0) to proper
                else if (year > latestYear()) fixes += c.getLong(0) to proper
            }
        }
        if (fixes.isEmpty()) return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("UPDATE items SET year = ? WHERE rowid = ?")
            fixes.forEach { (rowid, y) ->
                stmt.clearBindings()
                if (y == null) stmt.bindNull(1) else stmt.bindLong(1, y.toLong())
                stmt.bindLong(2, rowid)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Titles with no release year, given the year in their name ("Movie (2019)", "Movie 2019").
     * Many providers send an empty year field, and the year in the name was never tried after
     * an empty one, so Newest and Oldest Release had nothing to sort by. Returns how many.
     */
    /** Gives back space left empty by removed titles (see SqliteCompact). */
    fun compact(): Long = com.nuvio.tv.livetv.data.SqliteCompact.compactIfWasteful(writableDatabase, "On Demand")

    /**
     * Release years for titles imported before years were read from their names (imports read
     * them now). A one-time fix: it used to run on every start, scanning every title without a
     * year (often tens of thousands) and finding nothing new. Done once, then remembered.
     */
    private val yearsFilledMark get() = java.io.File(appContext.noBackupFilesDir, "ondemand_years_filled")

    fun fillMissingYears(): Int {
        if (yearsFilledMark.exists()) return 0
        val n = fillMissingYearsOnce()
        runCatching { yearsFilledMark.createNewFile() }
        return n
    }

    private fun fillMissingYearsOnce(): Int {
        val db = writableDatabase
        val found = ArrayList<Pair<Long, Int>>()
        db.rawQuery("SELECT rowid, name FROM items WHERE year IS NULL", null).use { c ->
            while (c.moveToNext()) {
                val y = releaseYearInName(c.getString(1) ?: continue) ?: continue
                found += c.getLong(0) to y
            }
        }
        if (found.isEmpty()) return 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("UPDATE items SET year = ? WHERE rowid = ?")
            found.forEach { (rowid, y) ->
                stmt.clearBindings(); stmt.bindLong(1, y.toLong()); stmt.bindLong(2, rowid); stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return found.size
    }

    private fun createPrefixTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS prefixes (pl TEXT NOT NULL, prefix TEXT NOT NULL, PRIMARY KEY (pl, prefix))")
    }

    // ------------------------------------------------------------------ provider title tags

    /**
     * Works out a provider's own title tags from its catalog: a short bit before " - ", " | "
     * or ": " that starts many titles ("4K-D+ - Toy Story 5", "4k-NF - Roommates", "EN - …")
     * is a tag, not part of the name. Every provider labels things its own way, so this is
     * learned rather than listed.
     */
    fun learnPrefixes(playlistId: String) {
        val counts = HashMap<String, Int>()
        var total = 0
        readableDatabase.rawQuery("SELECT name FROM items WHERE pl = ?", arrayOf(playlistId)).use { c ->
            while (c.moveToNext()) {
                total++
                leadingSegment(c.getString(0))?.let { counts[it] = (counts[it] ?: 0) + 1 }
            }
        }
        val threshold = maxOf(15, total / 500)
        val learned = counts.filter { (seg, n) -> n >= threshold && seg.split(' ').size <= 4 }.keys
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("prefixes", "pl = ?", arrayOf(playlistId))
            learned.forEach { p ->
                db.insertWithOnConflict("prefixes", null, ContentValues().apply { put("pl", playlistId); put("prefix", p) },
                    SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loadPrefixes()
    }

    fun hasLearnedPrefixes(playlistId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM prefixes WHERE pl = ? LIMIT 1", arrayOf(playlistId)).use { it.moveToFirst() }

    /** Loads every provider's learned tags into memory (used by [displayTitle]). */
    fun loadPrefixes() {
        val set = HashSet<String>()
        runCatching {
            readableDatabase.rawQuery("SELECT prefix FROM prefixes", null).use { c -> while (c.moveToNext()) set += c.getString(0) }
        }
        knownPrefixes = set
    }

    private fun createPosterFixTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS poster_fix (uid TEXT PRIMARY KEY, title TEXT NOT NULL, year INTEGER)")
    }

    /** The name (and year) you typed for a title with "Fix poster", if any. */
    fun posterFix(uid: String): Pair<String, Int?>? =
        readableDatabase.rawQuery("SELECT title, year FROM poster_fix WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) c.getString(0) to (if (c.isNull(1)) null else c.getInt(1)) else null
        }

    /** Sets (or with a blank [title], clears) a title's "Fix poster" name, and forgets its old poster. */
    fun setPosterFix(uid: String, title: String, year: Int?) {
        val db = writableDatabase
        if (title.isBlank()) db.delete("poster_fix", "uid = ?", arrayOf(uid))
        else db.insertWithOnConflict("poster_fix", null, ContentValues().apply {
            put("uid", uid); put("title", title.trim()); if (year != null) put("year", year) else putNull("year")
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("posters", "uid = ?", arrayOf(uid))
    }

    private fun createPosterTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS posters (uid TEXT PRIMARY KEY, poster TEXT, checked INTEGER NOT NULL, title TEXT)")
    }

    // ------------------------------------------------------------------ poster cache

    /**
     * Posters found in your addons, remembered between app starts so On Demand doesn't look
     * them up again every time. An empty value means "none of your addons had it" (retried
     * after a week).
     */
    fun cachedPoster(uid: String): Pair<String, Long>? =
        readableDatabase.rawQuery("SELECT poster, checked FROM posters WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "") to c.getLong(1) else null
        }

    fun cachePoster(uid: String, poster: String?, title: String? = null) {
        writableDatabase.insertWithOnConflict("posters", null, ContentValues().apply {
            put("uid", uid); put("poster", poster ?: ""); put("checked", System.currentTimeMillis())
            if (title != null) put("title", title)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The proper name from your addons' match ("A View to a Kill"), if one was found. */
    fun cachedTitle(uid: String): String? =
        readableDatabase.rawQuery("SELECT title FROM posters WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /** Category names, for parental locks on "Watch On Demand" results. */
    fun categoryName(playlistId: String, kind: VodKind, id: String): String? =
        readableDatabase.rawQuery(
            "SELECT name FROM categories WHERE pl = ? AND kind = ? AND id = ?", arrayOf(playlistId, kind.key, id)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    // ------------------------------------------------------------------ writing

    /** What the last [replace] actually changed: titles written, and titles removed. */
    @Volatile var lastChanges: Pair<Int, Int> = 0 to 0
        private set

    /** A title's details as one number: the same number means nothing to write. */
    private fun fingerprint(it: VodItem): Long {
        var h = 1125899906842597L
        for (s in arrayOf(it.name, it.icon, it.categoryId, it.ext, it.tmdbId, it.year?.toString(), it.rating, it.addedSec.toString(), it.genre)) {
            h = 31 * h + (s?.hashCode() ?: 7)
        }
        return if (h == 0L) 1L else h
    }

    /**
     * Brings one provider's catalog of [kind] up to date in a single transaction. Only titles that
     * are new or changed are written, and titles the provider no longer lists are removed: every
     * import used to delete and write back the whole catalog (100,000+ titles), even when only a
     * few had changed. The first import of a catalog writes with the search indexes off and builds
     * them once at the end, which is much quicker than keeping them up to date title by title.
     */
    fun replace(playlistId: String, kind: VodKind, categories: List<Pair<String, String>>, items: Sequence<VodItem>): Int {
        val db = writableDatabase
        val existing = HashMap<String, Long>()
        db.rawQuery("SELECT id, h FROM items WHERE pl = ? AND kind = ?", arrayOf(playlistId, kind.key)).use { c ->
            while (c.moveToNext()) existing[c.getString(0)] = if (c.isNull(1)) 0L else c.getLong(1)
        }
        val bulk = existing.isEmpty()
        var count = 0
        var written = 0
        var removed = 0
        db.beginTransaction()
        try {
            if (bulk) {
                db.execSQL("DROP INDEX IF EXISTS items_cat")
                db.execSQL("DROP INDEX IF EXISTS items_norm")
                db.execSQL("DROP INDEX IF EXISTS items_tmdb")
                db.execSQL("DROP INDEX IF EXISTS items_added")
            }
            db.delete("categories", "pl = ? AND kind = ?", arrayOf(playlistId, kind.key))
            categories.forEachIndexed { i, (id, name) ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("pl", playlistId); put("kind", kind.key); put("id", id); put("name", name); put("position", i)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO items (pl, kind, id, name, norm, icon, cat, ext, tmdb, year, rating, added, genre, h) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            val seen = HashSet<String>(maxOf(16, existing.size))
            items.forEach { it ->
                count++
                seen.add(it.id)
                val h = fingerprint(it)
                if (existing[it.id] == h) return@forEach // unchanged
                stmt.clearBindings()
                stmt.bindString(1, playlistId)
                stmt.bindString(2, kind.key)
                stmt.bindString(3, it.id)
                stmt.bindString(4, it.name)
                stmt.bindString(5, normalize(it.name))
                it.icon?.let { v -> stmt.bindString(6, v) } ?: stmt.bindNull(6)
                stmt.bindString(7, it.categoryId)
                it.ext?.let { v -> stmt.bindString(8, v) } ?: stmt.bindNull(8)
                it.tmdbId?.let { v -> stmt.bindString(9, v) } ?: stmt.bindNull(9)
                it.year?.let { v -> stmt.bindLong(10, v.toLong()) } ?: stmt.bindNull(10)
                it.rating?.let { v -> stmt.bindString(11, v) } ?: stmt.bindNull(11)
                stmt.bindLong(12, it.addedSec)
                it.genre?.let { v -> stmt.bindString(13, v) } ?: stmt.bindNull(13)
                stmt.bindLong(14, h)
                stmt.executeInsert()
                existing[it.id] = h // a title listed twice is written once
                written++
            }
            // Titles the provider no longer lists.
            if (existing.size > seen.size) {
                val del = db.compileStatement("DELETE FROM items WHERE pl = ? AND kind = ? AND id = ?")
                existing.keys.filter { it !in seen }.forEach { id ->
                    del.clearBindings(); del.bindString(1, playlistId); del.bindString(2, kind.key); del.bindString(3, id)
                    removed += del.executeUpdateDelete()
                }
            }
            if (bulk) {
                db.execSQL("CREATE INDEX IF NOT EXISTS items_cat ON items(kind, pl, cat)")
                db.execSQL("CREATE INDEX IF NOT EXISTS items_norm ON items(kind, norm)")
                db.execSQL("CREATE INDEX IF NOT EXISTS items_tmdb ON items(kind, tmdb)")
                db.execSQL("CREATE INDEX IF NOT EXISTS items_added ON items(kind, added)")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        lastChanges = written to removed
        return count
    }

    /** Drops every provider not in [keepPlaylistIds]; returns the ones it dropped. */
    fun removeExcept(keepPlaylistIds: Set<String>): Set<String> {
        val db = writableDatabase
        val existing = HashSet<String>()
        db.rawQuery("SELECT DISTINCT pl FROM items UNION SELECT DISTINCT pl FROM categories", null).use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }
        val dropped = existing - keepPlaylistIds
        dropped.forEach { pl ->
            db.delete("items", "pl = ?", arrayOf(pl))
            db.delete("categories", "pl = ?", arrayOf(pl))
        }
        return dropped
    }

    // ------------------------------------------------------------------ reading

    /** Titles still waiting for genres that have a TMDB id, newest first. */
    fun needingGenres(limit: Int): List<VodItem> =
        query("SELECT * FROM items WHERE genre IS NULL AND tmdb IS NOT NULL ORDER BY added DESC LIMIT $limit", arrayListOf())

    fun countNeedingGenres(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE genre IS NULL AND tmdb IS NOT NULL", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Marks a title as looked up even when no genres were found (so it isn't retried). */
    fun setGenre(item: VodItem, genres: String) {
        writableDatabase.execSQL(
            "UPDATE items SET genre = ? WHERE pl = ? AND kind = ? AND id = ? AND genre IS NULL",
            arrayOf(genres, item.playlistId, item.kind.key, item.id)
        )
    }

    /** Fills in genres from your addons' match, when the provider didn't send any. */
    fun fillGenre(item: VodItem, genres: String) {
        writableDatabase.execSQL(
            "UPDATE items SET genre = ? WHERE pl = ? AND kind = ? AND id = ? AND (genre IS NULL OR genre = '')",
            arrayOf(genres, item.playlistId, item.kind.key, item.id)
        )
    }

    /** Genres across a kind's catalog, most common first ("Action" 1,204…). */
    fun genres(kind: VodKind): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        val display = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT genre FROM items WHERE kind = ? AND genre IS NOT NULL AND genre != ''", arrayOf(kind.key)).use { c ->
            while (c.moveToNext()) {
                c.getString(0).split(',', '/', '|', '&', ';').map { it.trim() }.filter { it.length in 2..30 }.distinct().forEach { g ->
                    val k = g.lowercase()
                    counts[k] = (counts[k] ?: 0) + 1
                    display.putIfAbsent(k, g.replaceFirstChar { ch -> ch.uppercase() })
                }
            }
        }
        return counts.entries.filter { it.value >= 3 }.sortedByDescending { it.value }.map { display[it.key]!! to it.value }
    }

    fun hasAny(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM items LIMIT 1", null).use { it.moveToFirst() }

    fun count(kind: VodKind): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE kind = ?", arrayOf(kind.key)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun categories(kind: VodKind): List<VodCategory> {
        val out = ArrayList<VodCategory>()
        readableDatabase.rawQuery(
            """SELECT c.pl, c.id, c.name, (SELECT COUNT(*) FROM items i WHERE i.pl = c.pl AND i.kind = c.kind AND i.cat = c.id)
               FROM categories c WHERE c.kind = ? ORDER BY c.pl, c.position""",
            arrayOf(kind.key)
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(3)
                if (n > 0) out += VodCategory(c.getString(0), kind, c.getString(1), c.getString(2), n)
            }
        }
        return out
    }

    /** A page of items: in one category, or across all (optionally newest first). */
    fun items(
        kind: VodKind,
        category: VodCategory?,
        excludeCategories: Set<String>,
        newestFirst: Boolean,
        limit: Int,
        offset: Int,
        sort: VodSort = VodSort.DEFAULT,
        genre: String? = null,
        merge: Boolean = true,
        seed: Long = 0
    ): List<VodItem> {
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        if (genre != null) {
            where.append(" AND genre LIKE ?")
            args += "%$genre%"
        }
        if (category != null) {
            where.append(" AND pl = ? AND cat = ?")
            args += category.playlistId; args += category.id
        }
        excludeCategories.forEach { uid ->
            val parts = uid.split(':', limit = 3)
            if (parts.size == 3 && parts[0] == kind.key) {
                where.append(" AND NOT (pl = ? AND cat = ?)")
                args += parts[1]; args += parts[2]
            }
        }
        val order = sort.orderBy(if (newestFirst) "added DESC" else "rowid", seed)
        val group = if (merge) " GROUP BY $DUP_KEY" else ""
        val copies = if (merge) ", COUNT(*) AS copies" else ""
        // A fixed last tie-break: titles that sort the same (same year, same date added) must
        // come back in the same order on every page, or a title shows on two pages and another
        // on none (that's how sorting seemed to undo "Merge Duplicates").
        val tieBreak = if (merge) "MIN(rowid)" else "rowid"
        return query("SELECT *$copies FROM items WHERE $where$group ORDER BY $order, $tieBreak LIMIT $limit OFFSET $offset", args)
    }

    fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int = 300, sort: VodSort = VodSort.DEFAULT, merge: Boolean = true): List<VodItem> {
        val words = normalizeWords(text)
        if (words.isEmpty()) return emptyList()
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        words.forEach { w -> where.append(" AND norm LIKE ?"); args += "%$w%" }
        val group = if (merge) " GROUP BY $DUP_KEY" else ""
        val copies = if (merge) ", COUNT(*) AS copies" else ""
        return query("SELECT *$copies FROM items WHERE $where$group ORDER BY ${sort.orderBy("length(name)")} LIMIT $limit", args)
            .filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
    }

    /** Every copy of a title: same TMDB id, or same name and year (other quality, other provider). */
    fun versions(item: VodItem): List<VodItem> =
        if (!item.tmdbId.isNullOrBlank()) query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(item.kind.key, item.tmdbId))
        else query(
            "SELECT * FROM items WHERE kind = ? AND (tmdb IS NULL OR tmdb = '') AND norm = ? AND IFNULL(year, 0) = ?",
            arrayListOf(item.kind.key, normalize(item.name), (item.year ?: 0).toString())
        )

    /** Category names by id, for describing versions. */
    fun categoryNames(playlistId: String, kind: VodKind): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT id, name FROM categories WHERE pl = ? AND kind = ?", arrayOf(playlistId, kind.key)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    fun byTmdb(kind: VodKind, tmdbId: String): List<VodItem> =
        query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(kind.key, tmdbId))

    fun byTitle(kind: VodKind, title: String): List<VodItem> {
        val keys = titleLookupKeys(title)
        if (keys.isEmpty()) return emptyList()
        return query("SELECT * FROM items WHERE kind = ? AND norm IN (${keys.joinToString { "?" }})",
            arrayListOf(kind.key).apply { addAll(keys) })
    }

    fun item(playlistId: String, kind: VodKind, id: String): VodItem? =
        query("SELECT * FROM items WHERE pl = ? AND kind = ? AND id = ?", arrayListOf(playlistId, kind.key, id)).firstOrNull()

    private fun query(sql: String, args: List<String>): List<VodItem> {
        val out = ArrayList<VodItem>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c -> while (c.moveToNext()) out += c.toItem() }
        return out
    }

    private fun Cursor.toItem(): VodItem = VodItem(
        playlistId = getString(getColumnIndexOrThrow("pl")),
        kind = if (getString(getColumnIndexOrThrow("kind")) == VodKind.SERIES.key) VodKind.SERIES else VodKind.MOVIE,
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        icon = getStringOrNull("icon"),
        categoryId = getString(getColumnIndexOrThrow("cat")),
        ext = getStringOrNull("ext"),
        tmdbId = getStringOrNull("tmdb"),
        year = getColumnIndexOrThrow("year").let { i -> if (isNull(i)) null else getInt(i) },
        rating = getStringOrNull("rating"),
        addedSec = getLong(getColumnIndexOrThrow("added")),
        genre = getColumnIndex("genre").takeIf { it >= 0 }?.let { i -> if (isNull(i)) null else getString(i) },
        copies = getColumnIndex("copies").takeIf { it >= 0 }?.let { i -> getInt(i) } ?: 1
    )

    private fun Cursor.getStringOrNull(col: String): String? =
        getColumnIndexOrThrow(col).let { i -> if (isNull(i)) null else getString(i) }

    companion object {
        /** Lower case, letters and digits only, with years and quality tags taken out. */
        fun normalize(s: String): String = normalizeWords(s).joinToString("")

        // Match the same decoration cleanup used by the final title/year identity check.
        internal fun titleLookupKeys(title: String): List<String> =
            listOf(normalize(title), normalize(displayTitle(title))).filter { it.isNotBlank() }.distinct()

        private val PREFIX = Regex("""^(\[[^\]]*\]|\|[^|]*\||[A-Z]{2,4}\s*[-|:]\s+)+""")
        // Built once: building a pattern is slow, and these run for every title on screen.
        private val YEAR_IN_BRACKETS_TRAIL = Regex("""\s*\((19|20)\d{2}\)""")
        private val YEAR_AT_END = Regex("""\s*[-|]\s*(19|20)\d{2}\s*$""")
        private val QUALITY_AT_END = Regex("""(?i)\s*[\[(]?\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hdr|multi|multi-?sub|vostfr|dub|sub)\b[\])]?\s*$""")
        private val YEAR_WORD = Regex("""(19|20)\d{2}""")
        private val YEAR_ANYWHERE = Regex("""\b(19|20)\d{2}\b""")
        private val YEAR_IN_BRACKETS = Regex("""\((19|20)\d{2}\)""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        /** Cleaned titles already worked out, by provider title (the same names come up again and again). */
        private val displayTitles = com.nuvio.tv.livetv.data.boundedCache<String, String>(5_000)
        private val NOISE = Regex("""\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|multi|sub|dub|vostfr)\b""")

        /**
         * The title without the provider's decorations: language / country / service tags in
         * front ("EN - ", "|EN| ", "[US] ", "NF - "), a year in brackets, and quality tags.
         * "EN  - Coyote vs. Acme (2026) 4K" becomes "Coyote vs. Acme".
         */
        /** Every provider's learned title tags, lower case. */
        @Volatile var knownPrefixes: Set<String> = emptySet()
            set(value) { field = value; displayTitles.clear() }

        private val SEGMENT = Regex("""^\s*(.{1,30}?)\s*(?:\s[-–—|]\s|\s?\|\s?|:\s)""")

        /** The bit before the first separator, lower case, or null. */
        internal fun leadingSegment(name: String): String? =
            SEGMENT.find(name)?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        /**
         * Looks like a provider tag even without learning it: one short "word" (no spaces),
         * mostly capitals, digits or symbols, like "4K-D+", "4k-NF", "EN", "NF", "UHD-DV".
         * Real titles ("Spider-Man", "Mission: Impossible") don't fit.
         */
        private fun looksLikeTag(segment: String): Boolean {
            val t = segment.trim()
            if (t.length !in 2..12 || t.any { it.isWhitespace() }) return false
            if (t.all { it.isDigit() }) return false // "2001 - A Space Odyssey"
            val upper = t.count { it.isUpperCase() }
            val lower = t.count { it.isLowerCase() }
            return upper >= lower && t.any { it.isLetterOrDigit() }
        }

        private fun stripTags(s: String): String {
            var out = s.trim()
            repeat(3) {
                val m = SEGMENT.find(out) ?: return out
                val seg = m.groupValues[1].trim()
                if (seg.lowercase() in knownPrefixes || looksLikeTag(seg)) {
                    val rest = out.substring(m.range.last + 1).trim()
                    if (rest.isBlank()) return out
                    out = rest
                } else return out
            }
            return out
        }

        fun displayTitle(s: String): String {
            displayTitles[s]?.let { return it }
            return cleanTitle(s).also { displayTitles[s] = it }
        }

        /**
         * Names written with dots instead of spaces, the way file names often are
         * ("EN-TOP -181.Mad.Max.Fury.Road.2015"): spaces back, a "TOP -181" list number in
         * front and the year at the end taken off, so it reads "Mad Max Fury Road" (the year is
         * still used for sorting). Other names are left as they are.
         */
        private fun undotted(s: String): String {
            if (DOTTED_WORDS.findAll(s).count() < 2 || s.count { it == ' ' } > s.count { it == '.' }) return s
            var t = s.replace(DOT_BETWEEN, " ").trim()
            t = t.replace(TOP_LIST_NUMBER, "").trim()
            val y = TRAILING_BARE_YEAR.find(t)
            if (y != null && t.substring(0, y.range.first).any { it.isLetter() }) t = t.substring(0, y.range.first).trim()
            return t.ifBlank { s }
        }
        private val DOTTED_WORDS = Regex("""[\p{L}\p{N}]\.[\p{L}]""")
        private val DOT_BETWEEN = Regex("""(?<=[\p{L}\p{N}])\.(?=[\p{L}\p{N}])""")
        private val TOP_LIST_NUMBER = Regex("""(?i)^\s*(?:[a-z]{2,4}\s*-\s*)?top\s*[-#]?\s*\d{0,4}\s*[-.:)]?\s*""")
        private val TRAILING_BARE_YEAR = Regex("""\s(?:19|20)\d{2}\s*$""")

        private fun cleanTitle(raw: String): String {
            val s = undotted(raw)
            var t = stripTags(s).replace(PREFIX, "")
                .replace(YEAR_IN_BRACKETS_TRAIL, "")
                .replace(YEAR_AT_END, "")
                .replace(QUALITY_AT_END, "")
                .trim()
            // More of the decorations other IPTV apps take off (OwnTV's title cleaner): flags,
            // tags in brackets ("[MULTI-SUB]", "(VOSTFR)"), release words anywhere ("WEB-DL",
            // "x265", "5.1"), and a capital language tag at the very end ("Movie FR").
            // Never down to nothing.
            val before = t
            t = t.replace(EMOJI_FLAG, " ")
                .replace(BRACKET_TAG) { m -> if (RELEASE_WORDS.containsMatchIn(m.value) || SHORT_CAPS.matches(m.value)) " " else m.value }
                .replace(RELEASE_WORDS, " ")
                .replace(TRAILING_LANG_TAG, "")
                .replace(MULTI_SPACE, " ")
                .trim()
                .trim('-', '–', '—', '|', ':', '.', '_', ' ')
            if (t.isBlank() || t.none { it.isLetterOrDigit() }) t = before
            return t.ifBlank { s.trim() }
        }

        private val EMOJI_FLAG = Regex("[\\x{1F1E6}-\\x{1F1FF}]")
        private val BRACKET_TAG = Regex("""[\[{(][^\[\]{}()]*[\]})]""")
        private val SHORT_CAPS = Regex("""[\[{(]\s*[A-Z]{2,4}\s*[\]})]""")
        private val RELEASE_WORDS = Regex(
            "(?i)\\b(uhd|fhd|hevc|h\\.?265|h\\.?264|x265|x264|hdr10\\+?|hdr|dolby\\s+vision|atmos|" +
                "multi[- ]?subs?|multisub|dual[- ]?audio|remux|web[- ]?dl|webrip|bluray|blu-ray|bdrip|dvdrip|hdrip|" +
                "vostfr|vost|subbed|dubbed|dublado|legendado|truefrench|hdlight|" +
                "10bit|8bit|60fps|50fps|aac|e?ac3|dts|ddp|[257]\\.1|hdtc|hdcam|camrip|1080p|720p|2160p|480p)\\b"
        )
        private val TRAILING_LANG_TAG = Regex("""\s+(?:FR|EN|DE|ES|PT|NL|PL|TR|AR|RU|LAT|SUB|DUB|MULTI)$""")
        private val MULTI_SPACE = Regex("""\s{2,}""")

        private val TRAILING_EXTRA = setOf("uk", "us", "usa", "au", "aus", "ca", "nz", "ie", "gb", "de", "fr", "es", "it", "nl")

        /**
         * The core of a title for loose matching: cleaned of provider tags, with a trailing year
         * or country tag dropped. "EN - Big Brother UK 2023" and "Big Brother (2023)" both
         * become "bigbrother" (the year is then compared separately).
         */
        fun coreKey(name: String): String {
            val words = normalizeWords(displayTitle(name)).toMutableList()
            while (words.size > 1) {
                val last = words.last()
                if (last in TRAILING_EXTRA || YEAR_WORD.matches(last)) words.removeAt(words.lastIndex) else break
            }
            return words.joinToString("")
        }

        /** A year in a provider title ("Big Brother UK 2023"), if any. */
        fun yearInTitle(name: String): Int? = YEAR_ANYWHERE.findAll(name).lastOrNull()?.value?.toIntOrNull()

        /** The latest believable release year (next year, for titles listed early). */
        fun latestYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1

        /**
         * A title's release year from its name, for sorting: only a year the provider added to
         * the name, in brackets ("Movie (2018)") or at the end ("Movie - 2018", "Movie 2018").
         * A number that's part of the title ("2038 Origin Unknown", "1917", "2001: A Space
         * Odyssey") isn't one, and neither is a year still to come.
         */
        fun releaseYearInName(name: String): Int? {
            val latest = latestYear()
            YEAR_IN_BRACKETS.findAll(name).lastOrNull()?.value?.trim('(', ')')?.toIntOrNull()
                ?.takeIf { it <= latest }?.let { return it }
            // At the end, after the title (quality tags like "4K" or "1080p" set aside).
            var rest = name.trim()
            repeat(3) { rest = rest.replace(QUALITY_AT_END, "").trim() }
            val m = TRAILING_YEAR.find(rest) ?: return null
            val before = rest.substring(0, m.range.first).trim()
            // Something has to come before it (a title that's only a year is a title).
            if (before.none { it.isLetter() }) return null
            return m.groupValues[1].toIntOrNull()?.takeIf { it <= latest }
        }

        private val TRAILING_YEAR = Regex("""(?:^|[\s\-|:,.])((?:19|20)\d{2})\s*$""")

        fun normalizeWords(s: String): List<String> =
            s.replace(PREFIX, "")
                .replace(YEAR_IN_BRACKETS, " ")
                .lowercase()
                .replace(NOISE, " ")
                .split(NON_WORD)
                .filter { it.isNotBlank() }
    }
}
