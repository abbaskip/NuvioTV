package com.nuvio.tv.livetv.ondemand

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the provider reports about the file itself (from its own media scan). */
data class VodTech(
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val fps: Double?,
    val hdr: String?,
    val audioCodec: String?,
    val channels: Int?,
    val language: String?,
    val bitrateKbps: Int?,
    val durationSecs: Int?
) {
    /** "4K", "1080p", "720p" or "SD". */
    val quality: String?
        get() {
            val h = height ?: return null
            val w = width ?: 0
            return when {
                h >= 1600 || w >= 3200 -> "4K"
                h >= 1000 || w >= 1800 -> "1080p"
                h >= 700 || w >= 1200 -> "720p"
                h > 0 -> "SD"
                else -> null
            }
        }

    /** Size worked out from bitrate × length (providers rarely give the file size). */
    val estimatedBytes: Long?
        get() {
            val br = bitrateKbps ?: return null
            val d = durationSecs ?: return null
            if (br <= 0 || d <= 0) return null
            return br.toLong() * 1000L / 8L * d
        }
}

/** One episode of a provider series, ready to play. */
data class VodEpisode(
    val season: Int,
    val episode: Int,
    val id: String,
    val ext: String,
    val title: String,
    val plot: String?,
    val image: String?,
    val url: String,
    val tech: VodTech? = null,
    val imdbId: String? = null,
    val tmdbId: Long? = null,
    val tvdbId: Long? = null
)

/** What the provider says about a movie or series (for the fallback detail screen). */
data class VodInfo(
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationText: String?,
    val episodes: List<VodEpisode> = emptyList(),
    val tech: VodTech? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null
)

data class OnDemandStatus(val loading: Boolean = false, val message: String? = null)

/**
 * Imports movies and series from Xtream logins that have "Import movies & series" on, keeps them
 * in [OnDemandDatabase], and turns them into playable links, including the "Watch On Demand"
 * streams that appear on Nuvio's own movie and episode screens.
 */
@Singleton
class OnDemandRepository @Inject constructor(
    private val db: OnDemandDatabase,
    private val prefs: LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // Big catalogs (100,000+ titles) on slow panels can take a long time to send.
        .callTimeout(40, TimeUnit.MINUTES)
        .build()

    private val _hasContent = MutableStateFlow(false)
    /** True when at least one provider's movies or series are imported (shows the menu item). */
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    private val _version = MutableStateFlow(0)
    /** Goes up after every import, so screens reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _genreUpdates = MutableStateFlow(0)
    /** Goes up as background genre lookups add genres (the genre list refreshes, nothing else). */
    val genreUpdates: StateFlow<Int> = _genreUpdates.asStateFlow()

    private val _genreProgress = MutableStateFlow<String?>(null)
    /** "Adding genres… 12,400 to go", shown in settings while it runs. */
    val genreProgress: StateFlow<String?> = _genreProgress.asStateFlow()

    private var genreJob: kotlinx.coroutines.Job? = null

    private val _status = MutableStateFlow(OnDemandStatus())
    val status: StateFlow<OnDemandStatus> = _status.asStateFlow()

    private val detailHttp = http.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val prefixMutex = Mutex()
    private var prefixesReady = false
    private val infoLocks = ConcurrentHashMap<String, Mutex>()
    private val detailRequests = kotlinx.coroutines.sync.Semaphore(4)
    private val infoCache = com.nuvio.tv.livetv.data.boundedCache<String, VodInfo>(300)
    /** The last import problem, shown in settings (cleared by a clean import). */
    @Volatile private var lastProblem: String? = null

    /** Each login's own last problem (by playlist id), so one login's success or failure doesn't hide another's. */
    private val problemByPlaylist = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        // Start-up upkeep and the automatic catalog check: low priority, out of the screen's way.
        scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
            // A failed check (database busy) keeps the current answer instead of hiding On Demand.
            _hasContent.value = runCatching { db.hasAny() }.getOrDefault(_hasContent.value)
            // The heavier work below waits a little, so it never competes with Nuvio's own
            // start-up (Continue watching looks up each title's details with a short time limit;
            // a busy box made those lookups miss, leaving the home hero without year, genres
            // and description).
            kotlinx.coroutines.delay(START_DELAY_MS)
            // Release years for titles imported before years were read from their names.
            runCatching { db.fillMissingYears() }
            // Space freed since last time (a playlist removed, smaller catalogs) back to the box.
            com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
            runCatching { db.compact() }
            // Learn each provider's title tags (for catalogs imported before this existed).
            runCatching {
                db.loadPrefixes()
                prefs.playlists.first().filter { it.isXtream && it.importVod }.forEach { pl ->
                    if (!db.hasLearnedPrefixes(pl.id)) db.learnPrefixes(pl.id)
                }
                _version.value++
            }
            refresh(force = false)
        }
        // A login with movies and series that arrives or is switched on later (from Google Drive
        // sync or a restore, which used to leave On Demand empty until "Update now" or a restart):
        // import it straight away. One that's gone is dropped the same way.
        scope.launch {
            prefs.playlists
                .map { list -> list.filter { it.enabled && it.isXtream && it.importVod }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshSoon() }
        }
    }

    /** Imports new providers and drops ones that were turned off (no re-download of the rest). */
    fun refreshSoon() {
        scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) { kotlinx.coroutines.delay(1_500); refresh(force = false) }
    }

    fun refreshNow() {
        scope.launch { refresh(force = true) }
    }

    /** "Update now" on one login: re-imports just that provider's movies and series. */
    fun refreshNow(playlistId: String) {
        scope.launch { kotlinx.coroutines.delay(500); refresh(force = true, onlyId = playlistId) }
    }

    /**
     * Imports providers that are due (or all, when [force]); drops ones that were turned off.
     * With [onlyId], only that provider is imported (the others are kept as they are).
     */
    suspend fun refresh(force: Boolean, onlyId: String? = null) = mutex.withLock {
        val playlists = prefs.playlists.first().filter { it.enabled && it.isXtream && it.importVod }
        val keep = playlists.map { it.id }.toSet()
        val dropped = runCatching { db.removeExcept(keep) }.getOrDefault(emptySet())
        // A provider switched off forgets when it was last imported, so switching it back on
        // imports it again straight away (it used to wait up to a day, with On Demand gone from
        // the menu in the meantime).
        dropped.forEach { prefs.setOnDemandImportTime(it, 0L) }
        val stamps = prefs.onDemandImportTimes()
        val now = System.currentTimeMillis()
        for (pl in playlists) {
            if (onlyId != null && pl.id != onlyId) continue
            val last = stamps[pl.id] ?: 0L
            if (!force && now - last < REFRESH_MS) continue
            // Automatic imports wait while a movie or episode is playing in Nuvio's player, and
            // while the guide is being built or Sports is finding channels.
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitNoHeavy()
            _status.value = OnDemandStatus(true, "Importing movies and series from ${pl.name}…")
            lastProblem = null
            // Movies and series import separately, so a problem with one keeps the other.
            val movies = runCatching { importKind(pl, VodKind.MOVIE) }
                .onFailure { Log.w(TAG, "Movies for ${pl.name} failed", it); lastProblem = "Couldn't import movies from ${pl.name}: ${it.message ?: it.javaClass.simpleName}" }
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitNoHeavy()
            val series = runCatching { importKind(pl, VodKind.SERIES) }
                .onFailure { Log.w(TAG, "Series for ${pl.name} failed", it); lastProblem = "Couldn't import series from ${pl.name}: ${it.message ?: it.javaClass.simpleName}" }
            val ok = movies.isSuccess && series.isSuccess && lastProblem == null
            lastProblem?.let { problemByPlaylist[pl.id] = it } ?: problemByPlaylist.remove(pl.id)
            // Success: next import in a day. Failure: try again in an hour, not on every start.
            prefs.setOnDemandImportTime(
                pl.id,
                if (ok) System.currentTimeMillis() else System.currentTimeMillis() - REFRESH_MS + 60 * 60 * 1000L
            )
            runCatching { db.learnPrefixes(pl.id) }
            _version.value++
        }
        _hasContent.value = runCatching { db.hasAny() }.getOrDefault(_hasContent.value)
        // A problem stays visible in Settings → Live TV → On Demand (no more silent failures).
        // Every login's problem, not only the last one imported.
        problemByPlaylist.keys.retainAll(keep)
        _status.value = OnDemandStatus(false, problemByPlaylist.values.joinToString("\n").ifBlank { null })
    }

    /**
     * No longer used: nothing in On Demand shows genres any more, so they're not looked up
     * (this used to run after every import, for every title). Kept for reference only.
     *
     * Fills in genres in the background from TMDB, using the TMDB
     * id most providers include for each title. Newest titles first, about 20 a second (well
     * within TMDB's limits), and each title only once. Runs while the app is open; picks up
     * where it left off next time.
     */
    fun startGenreFill() {
        if (genreJob?.isActive == true) return
        val key = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return
        // Low priority: it runs for a long time in the background, and mustn't slow the screen.
        genreJob = scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
            var added = 0
            var shownOnce = false
            while (true) {
                val batch = runCatching { db.needingGenres(40) }.getOrDefault(emptyList())
                if (batch.isEmpty()) break
                kotlinx.coroutines.coroutineScope {
                    batch.chunked(10).map { part ->
                        async {
                            for (item in part) {
                                val genres = tmdbGenres(item, key)
                                runCatching { db.setGenre(item, genres.orEmpty()) }
                                if (!genres.isNullOrBlank()) added++
                                kotlinx.coroutines.delay(200) // 4 at a time × 5/s ≈ 20 a second
                            }
                        }
                    }.forEach { it.await() }
                }
                val left = runCatching { db.countNeedingGenres() }.getOrDefault(0)
                _genreProgress.value = if (left > 0) "Adding genres… ${"%,d".format(left)} to go" else null
                // Show the first genres soon after you switch to By genre, then every few hundred.
                if (added >= if (shownOnce) 400 else 40) { _genreUpdates.value++; added = 0; shownOnce = true }
            }
            _genreProgress.value = null
            _genreUpdates.value++
        }
    }

    /** "Action, Comedy" from TMDB for one title, "" if it has none, null if TMDB didn't answer. */
    private fun tmdbGenres(item: VodItem, key: String): String? {
        val id = item.tmdbId?.toIntOrNull() ?: return ""
        val path = if (item.kind == VodKind.SERIES) "tv" else "movie"
        return runCatching {
            val text = getText("https://api.themoviedb.org/3/$path/$id?api_key=$key&language=en-US", "")
            val arr = JSONObject(text).optJSONArray("genres") ?: return@runCatching ""
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                .joinToString(", ")
        }.getOrElse { e ->
            // 404: TMDB doesn't know this id, so don't try again. Anything else: try next time.
            if (e.message?.contains("404") == true) "" else null
        }
    }

    private fun importKind(pl: PlaylistSource, kind: VodKind) {
        val t0 = System.currentTimeMillis()
        try { importKindInner(pl, kind) } finally {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "On Demand \"${pl.name}\": ${if (kind == VodKind.MOVIE) "movies" else "series"} imported in ${(System.currentTimeMillis() - t0) / 1000} s"
            )
        }
    }

    private fun importKindInner(pl: PlaylistSource, kind: VodKind) {
        val api = pl.xtreamApiUrl()
        val catAction = if (kind == VodKind.MOVIE) "get_vod_categories" else "get_series_categories"
        val listAction = if (kind == VodKind.MOVIE) "get_vod_streams" else "get_series"
        val what = if (kind == VodKind.MOVIE) "movies" else "series"
        val categories = ArrayList<Pair<String, String>>()
        runCatching {
            val arr = org.json.JSONArray(getText("$api&action=$catAction", pl.userAgent))
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                categories += c.optString("category_id") to c.optString("category_name").ifBlank { "Other" }
            }
        }
        _status.value = OnDemandStatus(true, "Importing $what from ${pl.name}…")
        var n = 0
        fun progress() {
            if (++n % 500 == 0) _status.value = OnDemandStatus(true, "Importing $what from ${pl.name}… ${"%,d".format(n)}")
        }

        // 1. The whole list in one go (fastest).
        var stoppedEarly: Throwable? = null
        val saved = db.replace(
            pl.id, kind, categories,
            streamItems(pl, kind, "$api&action=$listAction", onStop = { stoppedEarly = it }, onItem = ::progress)
        )
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "On Demand \"${pl.name}\": ${"%,d".format(saved)} $what, ${categories.size} categories; " +
                "${"%,d".format(db.lastChanges.first)} new or changed, ${"%,d".format(db.lastChanges.second)} removed"
        )
        val firstProblem = stoppedEarly ?: return

        // 2. Some panels cut off huge lists part-way (an "EOFException" partway through).
        //    Ask again one category at a time: many small lists instead of one huge one.
        if (categories.isEmpty()) {
            reportStop(pl, what, saved, firstProblem)
            if (saved == 0) throw firstProblem
            return
        }
        Log.w(TAG, "${pl.name}: $what list stopped after $saved; importing by category", firstProblem)
        n = 0
        _status.value = OnDemandStatus(true, "Importing $what from ${pl.name} by category…")
        var failedCategories = 0
        val byCategory = sequence {
            for ((catId, _) in categories) {
                var problem: Throwable? = null
                yieldAll(
                    streamItems(
                        pl, kind, "$api&action=$listAction&category_id=${enc(catId)}",
                        onStop = { problem = it }, onItem = ::progress
                    )
                )
                if (problem != null) failedCategories++
                // "Too many requests": stop here instead of asking for every remaining category
                // (that kept the provider refusing, including when you then pressed play).
                if (problem?.message?.contains("429") == true) break
            }
        }
        val total = db.replace(pl.id, kind, categories, byCategory)
        if (total < saved) {
            // Category by category did worse: go back to what the full list gave.
            db.replace(pl.id, kind, categories, streamItems(pl, kind, "$api&action=$listAction", onStop = {}, onItem = {}))
            reportStop(pl, what, saved, firstProblem)
        } else if (failedCategories > 0) {
            lastProblem = "${pl.name}: imported ${"%,d".format(total)} $what, but $failedCategories categories " +
                "didn't finish. It will try again in an hour."
        }
    }

    private fun reportStop(pl: PlaylistSource, what: String, saved: Int, e: Throwable) {
        lastProblem = "${pl.name} stopped sending $what after ${"%,d".format(saved)} " +
            "(${e.message ?: e.javaClass.simpleName}). Those were saved; it will try again in an hour."
    }

    /**
     * Streams one provider list straight into the database, a title at a time. If the provider
     * stops part-way, [onStop] is told and everything received so far is still yielded.
     */
    private fun streamItems(
        pl: PlaylistSource,
        kind: VodKind,
        url: String,
        onStop: (Throwable) -> Unit,
        onItem: () -> Unit
    ): Sequence<VodItem> = sequence {
        val request = Request.Builder().url(url)
            .header("User-Agent", pl.userAgent.ifBlank { LiveTvRepository.DEFAULT_UA }).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            onStop(e); return@sequence
        }
        response.use { r ->
            if (!r.isSuccessful) { onStop(IllegalStateException("HTTP ${r.code}")); return@sequence }
            val body = r.body ?: run { onStop(IllegalStateException("Empty response")); return@sequence }
            JsonReader(body.charStream()).use { reader ->
                // Panels don't always send perfectly valid JSON; be forgiving.
                reader.isLenient = true
                try {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return@sequence }
                    reader.beginArray()
                } catch (e: Exception) {
                    onStop(e); return@sequence
                }
                while (true) {
                    val f = try {
                        if (!reader.hasNext()) break
                        readFlat(reader)
                    } catch (e: Exception) {
                        onStop(e)
                        break
                    }
                    val id = (if (kind == VodKind.MOVIE) f["stream_id"] else f["series_id"]) ?: continue
                    val name = f["name"]?.trim().orEmpty().ifBlank { f["title"].orEmpty() }
                    if (name.isBlank()) continue
                    yield(
                        VodItem(
                            playlistId = pl.id,
                            kind = kind,
                            id = id,
                            name = name,
                            icon = (if (kind == VodKind.MOVIE) f["stream_icon"] else f["cover"])?.takeIf { it.startsWith("http") },
                            categoryId = f["category_id"].orEmpty(),
                            ext = f["container_extension"]?.takeIf { it.isNotBlank() },
                            tmdbId = (f["tmdb"] ?: f["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
                            // The first of these that has a year: providers often send an empty
                            // year field, which used to stop it trying the others (and the name).
                            year = listOf(f["year"], f["releaseDate"], f["release_date"]).firstNotNullOfOrNull { yearOf(it) }
                                ?.takeIf { it <= OnDemandDatabase.latestYear() }
                                ?: OnDemandDatabase.releaseYearInName(name),
                            rating = f["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                            addedSec = (f["added"] ?: f["last_modified"])?.toLongOrNull() ?: 0L,
                            genre = f["genre"]?.trim()?.takeIf { it.isNotBlank() }
                        )
                    )
                    onItem()
                }
            }
        }
    }

    /** One JSON object's simple fields as strings (nested values are skipped). */
    private fun readFlat(reader: JsonReader): Map<String, String> {
        val f = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> f[name] = reader.nextString()
                JsonToken.BOOLEAN -> f[name] = reader.nextBoolean().toString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return f
    }

    // ------------------------------------------------------------------ reading

    suspend fun categories(kind: VodKind): List<VodCategory> = withContext(Dispatchers.IO) {
        runCatching { db.categories(kind) }.getOrDefault(emptyList())
    }

    suspend fun items(kind: VodKind, category: VodCategory?, hidden: Set<String>, newestFirst: Boolean, limit: Int, offset: Int, sort: VodSort = VodSort.DEFAULT, genre: String? = null, merge: Boolean = true, seed: Long = 0) =
        withContext(Dispatchers.IO) {
            runCatching { db.items(kind, category, hidden, newestFirst, limit, offset, sort, genre, merge, seed) }.getOrDefault(emptyList())
        }

    /** One version of a title you can pick: where it's from and what it is. */
    data class Version(val item: VodItem, val label: String, val detail: String)

    /** Every copy of [item] (other qualities, other categories, other providers). */
    suspend fun versions(item: VodItem): List<Version> = withContext(Dispatchers.IO) {
        val all = runCatching { db.versions(item) }.getOrDefault(listOf(item)).ifEmpty { listOf(item) }
        val playlists = prefs.playlists.first().associateBy { it.id }
        val catNames = HashMap<String, Map<String, String>>()
        all.map { v ->
            val cats = catNames.getOrPut(v.playlistId) { runCatching { db.categoryNames(v.playlistId, v.kind) }.getOrDefault(emptyMap()) }
            val quality = qualityOf(v.name)
            val label = listOfNotNull(quality, v.ext?.uppercase()).joinToString(" · ").ifBlank { "Standard" }
            val detail = listOfNotNull(
                playlists[v.playlistId]?.name.takeIf { playlists.size > 1 },
                cats[v.categoryId],
                v.year?.toString()
            ).joinToString(" · ")
            Version(v, label, detail)
        }.sortedByDescending { qualityRank(it.label) }
    }

    private fun qualityOf(name: String): String? {
        val n = name.lowercase()
        return when {
            Regex("""\b(4k|uhd|2160p)\b""").containsMatchIn(n) -> "4K"
            Regex("""\b(fhd|1080p)\b""").containsMatchIn(n) -> "Full HD"
            Regex("""\b(hd|720p)\b""").containsMatchIn(n) -> "HD"
            Regex("""\b(sd|480p)\b""").containsMatchIn(n) -> "SD"
            else -> null
        }?.let { q -> if (Regex("""\b(hevc|x265|h265)\b""").containsMatchIn(n)) "$q HEVC" else q }
    }

    private fun qualityRank(label: String) = when {
        label.startsWith("4K") -> 4
        label.startsWith("Full HD") -> 3
        label.startsWith("HD") -> 2
        label.startsWith("SD") -> 1
        else -> 0
    }

    /** Genres from your addons' metadata, saved for titles the provider sent none for. */
    suspend fun fillGenres(item: VodItem, genres: List<String>) = withContext(Dispatchers.IO) {
        val text = genres.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        if (text.isNotBlank() && item.genre.isNullOrBlank()) runCatching { db.fillGenre(item, text) }
        Unit
    }

    suspend fun genres(kind: VodKind): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        runCatching { db.genres(kind) }.getOrDefault(emptyList())
    }

    suspend fun search(kind: VodKind, text: String, hidden: Set<String>, sort: VodSort = VodSort.DEFAULT, merge: Boolean = true) = withContext(Dispatchers.IO) {
        runCatching { db.search(kind, text, hidden, sort = sort, merge = merge) }.getOrDefault(emptyList())
    }

    private suspend fun playlist(id: String): PlaylistSource? = prefs.playlists.first().firstOrNull { it.id == id }

    /** A title by its uid ("movie:<playlist>:<id>"). */
    suspend fun itemByUid(uid: String): VodItem? = withContext(Dispatchers.IO) {
        val parts = uid.split(':', limit = 3)
        if (parts.size != 3) return@withContext null
        val kind = if (parts[0] == VodKind.SERIES.key) VodKind.SERIES else VodKind.MOVIE
        runCatching { db.item(parts[1], kind, parts[2]) }.getOrNull()
    }

    /** Movie link: /movie/user/pass/<id>.<ext> */
    suspend fun movieUrl(item: VodItem): String? {
        val pl = playlist(item.playlistId) ?: return null
        return "${pl.xtreamBase()}/movie/${enc(pl.xtreamUsername)}/${enc(pl.xtreamPassword)}/${item.id}.${item.ext ?: "mp4"}"
    }

    /** Provider details for a movie or series (series include every episode). Cached. */
    suspend fun info(item: VodItem): VodInfo? = withContext(Dispatchers.IO) {
        val pl = playlist(item.playlistId) ?: return@withContext null
        val cacheKey = item.uid + ":" + _version.value + ":" + java.security.MessageDigest.getInstance("SHA-256").digest(
            listOf(pl.xtreamServer, pl.xtreamUsername, pl.xtreamPassword, pl.userAgent).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
        infoCache[cacheKey]?.let { return@withContext it }
        infoLocks.getOrPut(cacheKey) { Mutex() }.withLock {
        infoCache[cacheKey]?.let { return@withLock it }
        val api = pl.xtreamApiUrl()
        val action = if (item.kind == VodKind.MOVIE) "get_vod_info&vod_id=${item.id}" else "get_series_info&series_id=${item.id}"
        val json = runCatching { JSONObject(getText("$api&action=$action", pl.userAgent, detailHttp)) }.getOrNull() ?: return@withLock null
        val info = json.optJSONObject("info") ?: JSONObject()
        val episodes = ArrayList<VodEpisode>()
        json.optJSONObject("episodes")?.let { byseason ->
            byseason.keys().forEach { seasonKey ->
                val arr = byseason.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id").ifBlank { continue }
                    val ext = e.optString("container_extension").ifBlank { "mp4" }
                    val season = e.optString("season").toIntOrNull() ?: seasonKey.toIntOrNull() ?: 1
                    val num = e.optString("episode_num").toIntOrNull() ?: (i + 1)
                    val einfo = e.optJSONObject("info")
                    episodes += VodEpisode(
                        season = season,
                        episode = num,
                        id = id,
                        ext = ext,
                        title = e.optString("title").ifBlank { "Episode $num" },
                        plot = einfo?.optString("plot")?.takeIf { it.isNotBlank() },
                        image = einfo?.optString("movie_image")?.takeIf { it.startsWith("http") },
                        url = "${pl.xtreamBase()}/series/${enc(pl.xtreamUsername)}/${enc(pl.xtreamPassword)}/$id.$ext",
                        tech = einfo?.let { parseTech(it) },
                        imdbId = (einfo?.optString("imdb_id") ?: e.optString("imdb_id")).takeIf { it.matches(Regex("tt[0-9]+")) },
                        tmdbId = (einfo?.optString("tmdb_id") ?: e.optString("tmdb_id")).toLongOrNull()?.takeIf { it > 0 },
                        tvdbId = (einfo?.optString("tvdb_id") ?: e.optString("tvdb_id")).toLongOrNull()?.takeIf { it > 0 }
                    )
                }
            }
        }
        val result = VodInfo(
            plot = info.optString("plot").ifBlank { info.optString("description") }.takeIf { it.isNotBlank() },
            cover = (info.optString("movie_image").ifBlank { info.optString("cover") }).takeIf { it.startsWith("http") },
            backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") },
            genre = info.optString("genre").takeIf { it.isNotBlank() },
            releaseDate = (info.optString("releasedate").ifBlank { info.optString("releaseDate") }).takeIf { it.isNotBlank() },
            durationText = info.optString("duration").takeIf { it.isNotBlank() },
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.episode })),
            tech = parseTech(info),
            tmdbId = info.optString("tmdb_id").ifBlank { info.optString("tmdb") }.takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true },
            imdbId = info.optString("imdb_id").takeIf { it.matches(Regex("tt[0-9]+")) }
        )
        infoCache[cacheKey] = result
        result
        }
    }

    // ------------------------------------------------------------------ poster cache

    suspend fun cachedPoster(item: VodItem): Pair<String, Long>? = withContext(Dispatchers.IO) {
        runCatching { db.cachedPoster(item.uid) }.getOrNull()
    }

    suspend fun cachePoster(item: VodItem, poster: String?, title: String? = null) = withContext(Dispatchers.IO) {
        runCatching { db.cachePoster(item.uid, poster, title) }
        Unit
    }

    suspend fun cachedTitle(item: VodItem): String? = withContext(Dispatchers.IO) {
        runCatching { db.cachedTitle(item.uid) }.getOrNull()
    }

    /** The name (and year) typed for this title with "Fix poster", if any. */
    suspend fun posterFix(item: VodItem): Pair<String, Int?>? = withContext(Dispatchers.IO) {
        runCatching { db.posterFix(item.uid) }.getOrNull()
    }

    /** "Fix poster": the name (and year) to look this title up by; a blank name goes back to automatic. */
    suspend fun setPosterFix(item: VodItem, title: String, year: Int?) = withContext(Dispatchers.IO) {
        runCatching { db.setPosterFix(item.uid, title, year) }
        Unit
    }

    // ------------------------------------------------------------------ "Watch On Demand"

    /** A playable On Demand copy of something you opened in Nuvio. */
    data class OnDemandSource(
        val label: String,
        val url: String,
        val headers: Map<String, String>,
        val title: String = "",
        val year: Int? = null,
        val providerName: String = "",
        val ext: String? = null,
        val tech: VodTech? = null,
        val episodeTag: String? = null,
        val playlistId: String = ""
    )

    /** Indexed title lookup; no catalogue import or broad scan is required to open an episode. */
    internal suspend fun integratedSources(target: VodStreamTarget, playlistId: String): List<OnDemandSource> = withContext(Dispatchers.IO) {
        val pl = playlist(playlistId)?.takeIf { it.enabled && it.isXtream && it.importVod } ?: return@withContext emptyList()
        prefixMutex.withLock {
            if (!prefixesReady) { db.loadPrefixes(); prefixesReady = true }
        }
        val candidates = linkedMapOf<String, VodItem>()
        target.tmdbId?.let { db.byTmdb(target.kind, it).forEach { item -> candidates[item.uid] = item } }
        if (target.title.isNotBlank()) db.byTitle(target.kind, target.title).forEach { candidates.putIfAbsent(it.uid, it) }
        val user = prefs.userState.first()
        val settings = prefs.settings.first()
        val headers = if (pl.userAgent.isBlank()) emptyMap() else mapOf("User-Agent" to pl.userAgent)
        val allowed = candidates.values.filter { item ->
            item.playlistId == playlistId && "${item.kind.key}:${item.playlistId}:${item.categoryId}" !in user.vodHiddenCategories &&
                !com.nuvio.tv.livetv.parental.ParentalControls.isLocked("vod:${item.kind.key}:${item.playlistId}:${item.categoryId}",
                    db.categoryName(item.playlistId, item.kind, item.categoryId).orEmpty(), settings, user.lockedGroups)
        }
        Log.d("IptvVod", "Catalogue lookup: candidates=${candidates.size} allowed=${allowed.size}")
        kotlinx.coroutines.coroutineScope {
            allowed.map { item -> async {
                detailRequests.withPermit {
                    // A catalog's matching TMDB id is sufficient for a movie link. Other cases
                    // verify the detail response before trusting a title/year fallback.
                    val detail = if (target.kind == VodKind.SERIES || !VodMatchPolicy.titleMatches(target, item)) info(item) else null
                    if (!VodMatchPolicy.parentMatches(target, item, detail)) return@withPermit emptyList()
                    val episodes = if (target.kind == VodKind.SERIES) VodMatchPolicy.episodes(target, detail?.episodes.orEmpty(), item.name) else emptyList()
                    Log.d("IptvVod", "Catalogue detail: episodes=${detail?.episodes?.size ?: 0} matched=${episodes.size}")
                    if (target.kind == VodKind.SERIES) episodes.map { episode ->
                        OnDemandSource("${pl.name} · S${episode.season}E${episode.episode} · ${episode.ext.uppercase()}", episode.url, headers,
                            item.name, item.year, pl.name, episode.ext, episode.tech,
                            "S%02dE%02d".format(episode.season, episode.episode), pl.id)
                    } else movieUrl(item)?.let { url -> listOf(OnDemandSource(
                        "${pl.name} · ${(item.ext ?: "mp4").uppercase()}", url, headers,
                        item.name, item.year, pl.name, item.ext ?: "mp4", detail?.tech, playlistId = pl.id)) }.orEmpty()
                }
            } }.let { kotlinx.coroutines.awaitAll(*it.toTypedArray()).flatten() }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun getText(url: String, userAgent: String, client: OkHttpClient = http): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { LiveTvRepository.DEFAULT_UA }).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string() ?: error("Empty response")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    /** Reads the provider's media scan ("video" / "audio" / "bitrate" / "duration_secs"). */
    private fun parseTech(info: JSONObject): VodTech? {
        val v = info.optJSONObject("video")
        val a = info.optJSONObject("audio")
        val bitrate = info.optString("bitrate").toIntOrNull()?.takeIf { it > 0 }
        val duration = info.optString("duration_secs").toIntOrNull()?.takeIf { it > 0 }
            ?: info.optString("duration").split(':').takeIf { it.size == 3 }?.let { p ->
                (p[0].toIntOrNull() ?: 0) * 3600 + (p[1].toIntOrNull() ?: 0) * 60 + (p[2].toIntOrNull() ?: 0)
            }?.takeIf { it > 0 }
        if (v == null && a == null && bitrate == null && duration == null) return null
        val fps = v?.optString("r_frame_rate")?.let { r ->
            val parts = r.split('/')
            val n = parts.getOrNull(0)?.toDoubleOrNull()
            val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (n != null && d > 0) n / d else null
        }?.takeIf { it in 1.0..240.0 }
        val transfer = v?.optString("color_transfer").orEmpty()
        val hdr = when {
            v?.optJSONArray("side_data_list")?.toString()?.contains("DOVI", ignoreCase = true) == true -> "Dolby Vision"
            transfer.contains("smpte2084") -> "HDR10"
            transfer.contains("arib-std-b67") -> "HLG"
            else -> null
        }
        val lang = a?.optJSONObject("tags")?.optString("language")?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            runCatching { java.util.Locale(code).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() && it != code } ?: code.uppercase()
        }
        return VodTech(
            width = v?.optInt("width")?.takeIf { it > 0 },
            height = v?.optInt("height")?.takeIf { it > 0 },
            videoCodec = v?.optString("codec_name")?.takeIf { it.isNotBlank() },
            fps = fps,
            hdr = hdr,
            audioCodec = a?.optString("codec_name")?.takeIf { it.isNotBlank() },
            channels = a?.optInt("channels")?.takeIf { it > 0 },
            language = lang,
            bitrateKbps = bitrate,
            durationSecs = duration
        )
    }

    private fun yearOf(s: String?): Int? =
        s?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }

    companion object {
        private const val TAG = "OnDemand"
        /** Re-import each provider once a day. */
        private const val REFRESH_MS = 24L * 60 * 60 * 1000
    }
}

/** How long On Demand waits after the app starts before its heavier checks. */
private const val START_DELAY_MS = 20_000L
