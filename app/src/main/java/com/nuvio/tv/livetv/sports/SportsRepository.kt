package com.nuvio.tv.livetv.sports

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything behind the Sports page: today's games from ESPN (refreshed every 30 seconds while
 * a game is on and you're looking, much less otherwise), sports found in your guide, and which
 * of your channels show each one. Kept on the device so the page opens straight away.
 */
@Singleton
class SportsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val liveRepo: LiveTvRepository,
    private val channelStore: LiveTvChannelStore,
    private val guideDb: EpgDatabase,
    private val playback: LiveTvPlaybackController,
    private val liveSession: com.nuvio.tv.livetv.ui.LiveTvSession,
    private val gemini: dagger.Lazy<SportsGemini>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feeds = SportsFeeds()
    private val freeDb = FreeSportsDb()
    private val freeLeagues = LinkedHashMap<String, League>()
    private val broadcastEnrichment = MutableStateFlow<Map<String, List<SportsBroadcast>>>(emptyMap())
    private val freeAttemptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val matcher = SportsMatcher(guideDb, File(context.cacheDir, "sports-guide"))
    private val dir = File(context.filesDir, "sports").apply { mkdirs() }

    val settings: StateFlow<SportsSettings> = prefs.sportsSettingsJson
        .map { SportsSettings.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsSettings())

    val follows: StateFlow<SportsFollows> = prefs.sportsMineJson
        .map { SportsFollows.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsFollows())

    private class LeagueData(val events: List<SportsEvent>, val fetchedAt: Long, val error: String?)

    private val leagueData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** ESPN games from the day after tomorrow to a week ahead (updated a few times a day). */
    private val weekData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val fixtureData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /**
     * Leagues you follow or show on My Teams: their games from a week ahead to five weeks ahead
     * (the week before that is in [weekData]), so a league's row runs well past this week.
     */
    private val aheadData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Followed teams' whole seasons from ESPN, by followed team key. */
    private val scheduleData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Partial schedules/results from the free TheSportsDB API, by league id. */
    private val tsdbData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val baseData = MutableStateFlow<List<SportsEvent>>(emptyList())
    private val baseFeed by lazy { SportsBaseFeed(dir, com.nuvio.tv.BuildConfig.SPORTS_BASE_FEED_URL, allowLocalhost = com.nuvio.tv.BuildConfig.DEBUG) }
    private var appliedBaseAt = 0L
    private val scoreAttempts = HashMap<String, Long>()
    private var lastSharedScoreRound = 0L
    private val focusedEvent = MutableStateFlow<String?>(null)
    private val geminiMatchGate = kotlinx.coroutines.sync.Mutex()
    private val tsdbTeams = java.util.concurrent.ConcurrentHashMap<String, List<TeamInfo>>()
    /** Games of teams looked up on a table (their card), by team key: so their game pages open. */
    private val lookedUp = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Shows found in the guide, by day (the day's start). */
    private val guideData = MutableStateFlow<Map<Long, List<SportsEvent>>>(emptyMap())

    /** Every game and event on the page's days (yesterday to a week ahead). */
    val events: StateFlow<List<SportsEvent>> = combine(leagueData, weekData, fixtureData, guideData, tsdbData) { l, w, f, g, t ->
        val guide = mergeWrestlingAirings(g.values.flatten())
        val from = dayStart(-DAYS_BACK - 1)
        val to = dayStart(DAYS_AHEAD + 2)
        val tsdb = t.values.flatMap { it.events }.filter { it.startMs in from until to }
        // A scheduled event (sporting-events.org, the wrestling schedule) gives way to the same
        // event found in the guide: one of its league within 3 hours of its start (or, for
        // events with a date only, that day). Anything else in the guide doesn't hide it.
        val guideByLeague = guide.groupBy { it.leagueId }
        val fixtures = f.values.flatMap { it.events }.filter { fx ->
            val same = guideByLeague[fx.leagueId].orEmpty()
            if (fx.hasTime) same.none { kotlin.math.abs(it.startMs - fx.startMs) <= 3 * HOUR || sameShowShifted(it, fx) }
            else same.none { dayOf(it.startMs) == dayOf(fx.startMs) }
        }
        val espn = l.values.flatMap { it.events } + w.values.flatMap { it.events }
        // Keep the scored fixture when the guide names both participants at the same time.
        // Women's and wheelchair listings must not disappear behind a men's fixture.
        val espnBySport = (espn + tsdb).filter { it.away != null && it.home != null }
            .groupBy { SportsCatalog.league(it.leagueId)?.sport }
        fun coveredByEspn(ev: SportsEvent): Boolean {
            val league = SportsCatalog.league(ev.leagueId) ?: return false
            if (league.espnPath != null) return false
            val same = espnBySport[league.sport] ?: return false
            return same.any { r -> SportsCatalog.league(r.leagueId)?.let {
                SportsEventIdentity.guideCoversFixture(ev, r, it)
            } == true }
        }
        (espn + (fixtures + guide).filterNot(::coveredByEspn) + tsdb.filterNot { event ->
            val league = SportsCatalog.league(event.leagueId)
            league != null && espn.any { other -> SportsCatalog.league(other.leagueId)?.let {
                SportsEventIdentity.same(event, other, league, it) } == true }
        }).distinctBy { it.id }
    }.combine(baseData) { current, base ->
        (current + base).groupBy { it.id }.values.map { versions -> versions.maxBy { it.updatedAt ?: 0L } }
    }.combine(broadcastEnrichment) { list, enriched ->
        list.map { e -> enriched[e.id]?.let { coverage -> e.copy(broadcastCoverage = coverage,
            broadcasts = (e.broadcasts + coverage.filter { it.country == null || (FreeSportsDb.region(it.country) ?: "OTHER") == SportsCatalog.regionOf(settings.value) }.map { it.name }).distinct()) } ?: e }
    }.combine(settings) { list, s ->
        list.filter { SportsTaxonomy.acceptsEvent(SportsCatalog.league(it.leagueId), it.title, it.subtitle, s.excludedSportsNodes) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Everything we know, for My Teams: the page's days plus your teams' whole seasons and the
     * non-league seasons. A game in both counts once (the fresher copy, from the page's days).
     */
    val known: StateFlow<List<SportsEvent>> = combine(events, scheduleData, tsdbData, aheadData, lookedUp) { ev, s, t, a, lu ->
        val seen = HashSet<String>(ev.size * 2)
        ev.forEach { seen += dedupeKey(it) }
        val extra = (a.values.flatMap { it.events } + s.values.flatMap { it.events } + t.values.flatMap { it.events } + lu.values.flatMap { it.events })
            .filter { seen.add(dedupeKey(it)) }
        ev + extra
    }.combine(settings) { list, s ->
        list.filter { SportsTaxonomy.acceptsEvent(SportsCatalog.league(it.leagueId), it.title, it.subtitle, s.excludedSportsNodes) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The same weekly wrestling show in the schedule file and in your guide, up to a day and a
     * bit apart: TheSportsDB sometimes files a US evening show under the wrong day (SmackDown,
     * Friday 8 pm Eastern, listed as Thursday 8 pm: midnight UTC put on the Friday date).
     * Your guide's airing is the real one, so the schedule's copy gives way to it.
     */
    private fun sameShowShifted(guide: SportsEvent, fx: SportsEvent): Boolean {
        if (SportsCatalog.league(fx.leagueId)?.sport != Sport.WRESTLING) return false
        if (kotlin.math.abs(guide.startMs - fx.startMs) > 30 * HOUR) return false
        val a = SportsCatalog.norm(wrestlingHeadline(guide.title)).trim()
        val b = SportsCatalog.norm(wrestlingHeadline(fx.title)).trim()
        return a.length >= 3 && b.length >= 3 && (a == b || a.contains(b) || b.contains(a))
    }

    /**
     * One wrestling show aired at different times (USA at 8 pm, a later or overseas feed at 1 am):
     * one entry, at the earliest airing, with every channel and its time. Airings of the same show
     * within 12 hours of the first count as one; next week's episode never does.
     */
    private fun mergeWrestlingAirings(guide: List<SportsEvent>): List<SportsEvent> {
        val (wrestling, rest) = guide.partition { SportsCatalog.league(it.leagueId)?.sport == Sport.WRESTLING }
        if (wrestling.size < 2) return guide
        val out = ArrayList<SportsEvent>(wrestling.size)
        val byShow = wrestling.groupBy { it.leagueId + "|" + SportsCatalog.norm(wrestlingHeadline(it.title)).trim() }
        for ((_, airings) in byShow) {
            var run = ArrayList<SportsEvent>()
            fun flush() {
                if (run.isEmpty()) return
                val first = run.first()
                out += if (run.size == 1) first else first.copy(
                    // On now if any airing is; otherwise the first airing's state.
                    state = if (run.any { it.state == GameState.LIVE }) GameState.LIVE else first.state,
                    guideHits = run.flatMap { it.guideHits }.distinctBy { (key, p) -> key + "|" + p.startMs },
                    endMs = run.mapNotNull { it.endMs }.maxOrNull() ?: first.endMs,
                    subtitle = first.subtitle ?: run.firstNotNullOfOrNull { it.subtitle },
                    word = first.word ?: run.firstNotNullOfOrNull { it.word }
                )
                run = ArrayList()
            }
            for (ev in airings.sortedBy { it.startMs }) {
                if (run.isNotEmpty() && ev.startMs - run.first().startMs > 12 * HOUR) flush()
                run += ev
            }
            flush()
        }
        return rest + out
    }

    private fun dedupeKey(ev: SportsEvent): String = ev.espnId?.let { "e:$it" } ?: ev.id

    private val _matches = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /** Event id -> the channels showing it, best first. */
    val matches: StateFlow<Map<String, List<ChannelMatch>>> = _matches.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _matching = MutableStateFlow(false)
    /** Looking through your channels and guide (the first time takes a few seconds). */
    val matching: StateFlow<Boolean> = _matching.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Something went wrong getting scores (shown quietly on the page). */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val viewers = MutableStateFlow(0)
    private val foreground = MutableStateFlow(true)
    private val kick = MutableStateFlow(0)
    @Volatile private var started = false

    /** Starts the background updates (once). Called by the app as it starts. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) { loadCache() }
            // The paced free API must not hold up ESPN's score refresh loop.
            launch {
                while (true) {
                    if (!baseFeed.enabled && foreground.value && viewers.value > 0) runCatching { updateTsdb(System.currentTimeMillis(), false) }
                        .onFailure { Log.w(TAG, "Free sports data update failed: ${describe(it)}") }
                    if (foreground.value) {
                        val channel = playback.state.value.channelKey
                        val now = System.currentTimeMillis()
                        val watching = known.value.filter { event ->
                            FreeSportsDb.refreshableWhileWatching(event, now) && _matches.value[event.id].orEmpty()
                                .any { it.channel.key == channel && it.sure }
                        }.maxByOrNull { it.startMs }
                        if (watching != null) runCatching { refreshFreeEvent(watching) }
                    }
                    delay(30_000)
                }
            }
            // Live scores must not wait for catalogue downloads or followed teams' full seasons.
            launch {
                while (true) {
                    if (baseFeed.enabled && foreground.value) {
                        runCatching { updateSharedScores(System.currentTimeMillis()) }
                            .onFailure {
                                currentCoroutineContext().ensureActive()
                                Log.w(TAG, "Live score update failed; will retry", it)
                            }
                    }
                    val lastKick = kick.value
                    withTimeoutOrNull(if (viewers.value > 0) 10_000L else 30_000L) {
                        kick.first { it != lastKick }
                    }
                }
            }
            runCatching { updateLoop() }.onFailure { Log.w(TAG, "Sports updates stopped", it) }
        }
        scope.launch { matchLoop() }
    }

    /** The Sports page (or a game) is on screen: keep scores fresh. */
    fun setViewing(on: Boolean) {
        viewers.update { (it + if (on) 1 else -1).coerceAtLeast(0) }
        if (on) {
            queueGeminiMatching()
            liveRepo.ensureLoaded(updateCheckDelayMs = 20_000)
            kick.value++
            // Nothing matched yet (or not for a while): do it now, don't wait for the next round.
            if (_matches.value.isEmpty() || System.currentTimeMillis() - lastMatchAt > 3 * MINUTE) matchKick.value++
        }
    }

    fun setForeground(on: Boolean) {
        foreground.value = on
        if (on) kick.value++
    }

    /** Update now (the page's refresh, a day picked). */
    fun refreshNow() {
        forceAll = true
        forceExtras = true
        kick.value++
    }

    @Volatile private var forceAll = false
    @Volatile private var forceExtras = false

    // ------------------------------------------------------------------ which leagues

    /** Leagues on the Sports page, plus any league you follow a team or the whole league in. */
    fun activeLeagues(s: SportsSettings = settings.value, f: SportsFollows = follows.value): List<League> {
        val mine = followedLeagueIds(f)
        return SportsCatalog.ordered(s).filter { (SportsCatalog.shown(it, s) || it.id in mine) && SportsTaxonomy.accepts(it, s.excludedSportsNodes) }
    }

    /**
     * Leagues you follow something in: your teams' leagues and the other competitions they play
     * in (cups, European games, a country's qualifiers), whole leagues, and top-bar leagues.
     */
    fun followedLeagueIds(f: SportsFollows = follows.value): Set<String> {
        val out = LinkedHashSet<String>()
        f.teams.forEach { t -> out += t.leagueId; out += SportsCatalog.relatedLeagues(t.leagueId) }
        out += f.leagues
        out += f.pins
        return out
    }

    private fun isMine(l: League, f: SportsFollows) = l.id in followedLeagueIds(f)

    // ------------------------------------------------------------------ updates

    private suspend fun updateLoop() {
        var lastKick = -1
        var lastFixtures = 0L
        while (true) {
            val active = foreground.value && (viewers.value > 0 || playback.state.value.channelKey != null || followedLeagueIds().isNotEmpty() || follows.value.reminders.isNotEmpty())
            if (active && baseFeed.enabled) {
                runCatching {
                    val snapshot = baseFeed.refresh()
                    if (snapshot != null) applyBaseFeed(snapshot)
                    else if (appliedBaseAt > 0) {
                        baseData.value = emptyList()
                        leagueData.value = emptyMap()
                        tsdbData.value = emptyMap()
                    }
                }
                // Personal full-season requests remain tied to followed teams, while Sports is open.
                if (viewers.value > 0) runCatching { updateSchedules(System.currentTimeMillis(), false) }
            } else if (active) {
                runCatching { updateOnce(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "Sports update failed", it) }
                val force = forceExtras.also { forceExtras = false }
                runCatching { updateWeek(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Sports week update failed", it) }
                runCatching { updateAhead(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Sports weeks-ahead update failed", it) }
                runCatching { updatePast(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "Sports past days update failed", it) }
                runCatching { updateSchedules(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Team schedules update failed", it) }
                if (System.currentTimeMillis() - lastFixtures > 12 * HOUR) {
                    lastFixtures = System.currentTimeMillis()
                    runCatching { updateFixtures() }
                }
            }
            val wait = if (viewers.value > 0) 10_000L else 30_000L
            lastKick = kick.value
            withTimeoutOrNull(wait) { kick.first { it != lastKick } }
        }
    }

    fun focusEvent(id: String?) {
        focusedEvent.value = id
        kick.value++
    }

    private fun applyBaseFeed(snapshot: SportsBaseFeed.Snapshot) {
        if (snapshot.generatedAt <= appliedBaseAt) return
        applyFreeLeagues(snapshot.metadata.toString())
        val parsed = snapshot.entries.flatMap { entry ->
            val league = SportsCatalog.league(entry.leagueId) ?: return@flatMap emptyList()
            runCatching {
                val events = when {
                    entry.provider == "espn" && league.espnPath != null -> SportsFeeds.parseScoreboard(league, entry.payload.toString())
                    entry.provider == "tsdb" && league.id.startsWith("tsdb/") -> FreeSportsDb.events(league, entry.payload.toString(), entry.fetchedAt)
                    entry.provider == "calendar" && league.fixtureSlug != null -> SportsFeeds.parseFixtures(league, entry.payload.toString())
                    else -> emptyList()
                }
                events.map { SportsBaseFeed.scheduled(it, entry.fetchedAt) }
            }.getOrDefault(emptyList())
        }
        baseData.value = parsed
        appliedBaseAt = snapshot.generatedAt
        _updatedAt.value = snapshot.generatedAt
    }

    private suspend fun updateSharedScores(now: Long) {
        if (now - lastSharedScoreRound < 30_000L) return
        lastSharedScoreRound = now
        val channel = playback.state.value.channelKey
        val playing = _matches.value.filterValues { list -> list.any { it.channel.key == channel && it.sure } }.keys
        val due = SportsRefreshPolicy.choose(known.value, activeLeagues(), focusedEvent.value, playing,
            followedLeagueIds(), viewers.value > 0, now, scoreAttempts)
        for (league in due) {
            // Record attempts before I/O: a failed service must not be retried on each loop.
            scoreAttempts[league.id] = now
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
            val targets = known.value.filter { it.leagueId == league.id && (it.id == focusedEvent.value || it.id in playing) }
            val at = targets.minByOrNull { kotlin.math.abs(it.startMs - now) }?.startMs ?: now
            runCatching {
                val parsed = SportsFeeds.parseScoreboard(league, feeds.scoreboardDayRaw(league, fmt.format(java.util.Date(at))))
                    .map { it.copy(updatedAt = now) }
                val prior = leagueData.value[league.id]?.events.orEmpty().filter { it.startMs >= now - DAY }
                leagueData.update { it + (league.id to LeagueData((parsed + prior).distinctBy { e -> e.id }, now, null)) }
                _problem.value = null
                Log.d(TAG, "Live scores: ${league.id}, ${parsed.size} games, ${parsed.count { it.state == GameState.LIVE }} live")
            }.onFailure {
                currentCoroutineContext().ensureActive()
                _problem.value = "Live scores unavailable; showing saved schedules and results."
                Log.w(TAG, "Live scoreboard failed for ${league.id}: ${describe(it)}")
            }
        }
        rebuildLiveScores()
    }

    private suspend fun updateOnce(now: Long) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val force = forceAll.also { forceAll = false }
        val data = leagueData.value
        val playingKey = playback.state.value.channelKey
        val playingLeagues = if (playingKey == null) emptySet() else _matches.value
            .filter { (_, list) -> list.any { it.channel.key == playingKey && it.sure } }
            .keys.mapNotNull { id -> events.value.firstOrNull { it.id == id && it.state == GameState.LIVE }?.leagueId }.toSet()
        val due = activeLeagues(s, f).filter { l ->
            if (l.espnPath == null) return@filter false
            val d = data[l.id]
            val age = now - (d?.fetchedAt ?: 0L)
            val live = d?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 15 * MINUTE)..(now + 10 * MINUTE)) } == true
            val mine = isMine(l, f)
            if (!viewing && !mine && l.id !in playingLeagues) return@filter false
            when {
                force -> age > 15_000
                viewing -> age > (if (live) 30_000 else 5 * MINUTE)
                l.id in playingLeagues && s.scoreOnInfoBar -> age > 60_000
                mine && live -> age > (if (s.scoringAlerts) 45_000 else 90_000)
                mine -> age > 30 * MINUTE
                // Not looking and not yours: only so the page isn't empty when it opens.
                d == null -> SportsCatalog.shown(l, s) && age > 6 * HOUR
                else -> false
            }
        }
        if (due.isEmpty()) return
        _loading.value = true
        try {
            val (from, to) = espnRange(now)
            val days = espnDays(now)
            val gate = Semaphore(4)
            val failed = ArrayList<String>()
            val reasons = LinkedHashSet<String>()
            coroutineScopeFetch(due) { l ->
                gate.withPermit {
                    val r = runCatching { fetchScoreboard(l, from, to, days) }
                    r.onSuccess { raws ->
                        val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrElse { emptyList() } }
                            .distinctBy { it.id }
                        val before = leagueData.value[l.id]?.events
                        leagueData.update { it + (l.id to LeagueData(parsed, System.currentTimeMillis(), null)) }
                        runCatching { File(dir, "sb_${safe(l.id)}.json").writeText("${System.currentTimeMillis()}\n" + raws.joinToString(RAW_SEP)) }
                        if (before != null) noticeScoring(l, before, parsed)
                    }.onFailure { e ->
                        synchronized(failed) { failed += l.name; reasons += describe(e) }
                        leagueData.update { m ->
                            val old = m[l.id]
                            m + (l.id to LeagueData(old?.events.orEmpty(), old?.fetchedAt ?: (System.currentTimeMillis() - 4 * MINUTE), e.message))
                        }
                    }
                }
            }
            val why = reasons.take(2).joinToString(", ")
            _problem.value = if (failed.size >= due.size && due.isNotEmpty()) "Couldn't reach the scores service ($why). Showing what was saved." else null
            if (failed.isNotEmpty()) {
                val line = "Sports: couldn't get scores for ${failed.take(6).joinToString(", ")}${if (failed.size > 6) " and ${failed.size - 6} more" else ""} ($why)"
                if (line != lastFailLine) { lastFailLine = line; LiveTvLoadReport.add(line) }
            } else lastFailLine = null
            _updatedAt.value = System.currentTimeMillis()
            rebuildLiveScores()
        } finally {
            _loading.value = false
        }
    }

    private var lastFailLine: String? = null

    // ------------------------------------------------------------------ the week ahead, seasons

    /** ESPN games from the day after tomorrow to a week ahead: a few times a day. */
    private suspend fun updateWeek(now: Long, force: Boolean) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val due = activeLeagues(s, f).filter { l ->
            if (l.espnPath == null) return@filter false
            val age = now - (weekData.value[l.id]?.fetchedAt ?: 0L)
            when {
                force -> age > 30 * MINUTE
                viewing -> age > 3 * HOUR
                isMine(l, f) -> age > 6 * HOUR
                else -> false
            }
        }
        if (due.isEmpty()) return
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val from = fmt.format(java.util.Date(now + 2 * DAY))
        val to = fmt.format(java.util.Date(now + DAYS_AHEAD * DAY))
        val singleDays = (2..DAYS_AHEAD).map { d -> fmt.format(java.util.Date(now + d * DAY)) }
        val gate = Semaphore(3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                // One request for the whole week; a feed that won't take a range of days: a day at a time.
                // A day at a time (ESPN turns down a range of days).
                val raws: List<String> = singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                val at = System.currentTimeMillis()
                if (raws.isEmpty()) {
                    // Try again in an hour, not straight away.
                    weekData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 2 * HOUR, "failed")) }
                    LiveTvLoadReport.add("Sports: couldn't get the week ahead for ${l.name}")
                    return@withPermit
                }
                val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                weekData.update { it + (l.id to LeagueData(parsed, at, null)) }
                runCatching { File(dir, "wk_${safe(l.id)}.json").writeText("$at\n" + raws.joinToString(RAW_SEP)) }
            }
        }
    }

    /**
     * Leagues you follow or show on My Teams: up to five weeks ahead (one request each, a few
     * times a day), so their rows aren't only this week. Not for feeds that need asking a day at a time.
     */
    private suspend fun updateAhead(now: Long, force: Boolean) {
        val f = follows.value
        // Plus your teams' competitions where ESPN gives no team schedule (rugby): their games
        // further ahead come from the league's months instead.
        val noSchedule = f.teams.flatMap { listOf(it.leagueId) + SportsCatalog.relatedLeagues(it.leagueId) }
            .filter { it.startsWith("rugby/") || it.startsWith("australian-football/") }
        val ids = (f.pins + f.leagues + noSchedule).toSet()
        if (aheadData.value.keys.any { it !in ids }) aheadData.update { m -> m.filterKeys { it in ids } }
        val due = ids.mapNotNull { SportsCatalog.league(it) }.filter { l ->
            l.espnPath != null && now - (aheadData.value[l.id]?.fetchedAt ?: 0L) > (if (force) HOUR else 6 * HOUR)
        }
        if (due.isEmpty()) return
        // ESPN takes a whole month ("202611") though not a range of days: the months the next
        // five weeks touch, kept from a week ahead on (the week before is in [weekData]).
        val months = SimpleDateFormat("yyyyMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val wanted = listOf(now + (DAYS_AHEAD + 1) * DAY, now + 35 * DAY).map { months.format(java.util.Date(it)) }.distinct()
        val keepFrom = dayStart(DAYS_AHEAD + 1)
        val keepTo = now + 36 * DAY
        for (l in due) {
            val raws = wanted.mapNotNull { m -> runCatching { feeds.scoreboardDayRaw(l, m) }.getOrNull() }
            val at = System.currentTimeMillis()
            if (raws.isEmpty()) {
                aheadData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val parsed = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
                raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }
                    .filter { it.startMs in keepFrom until keepTo }.distinctBy { it.id }
            }
            aheadData.update { it + (l.id to LeagueData(parsed, at, null)) }
        }
    }

    @Volatile private var pastWanted = 0L

    /** A day before yesterday is on screen: get the week back (once, then every couple of hours while looked at). */
    fun wantPast() {
        val now = System.currentTimeMillis()
        if (now - pastWanted > 60_000L) {
            pastWanted = now
            kick.value++
        }
    }

    /** The week before yesterday, for the page's past days: only when someone has looked at one. */
    private suspend fun updatePast(now: Long) {
        if (viewers.value == 0) return
        if (now - pastWanted > 30 * MINUTE) return
        val due = activeLeagues().filter { l ->
            l.espnPath != null && now - (weekData.value["p|" + l.id]?.fetchedAt ?: 0L) > 2 * HOUR
        }
        if (due.isEmpty()) return
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val from = fmt.format(java.util.Date(now - DAYS_BACK * DAY))
        val to = fmt.format(java.util.Date(now - 2 * DAY))
        val singleDays = (2..DAYS_BACK).map { d -> fmt.format(java.util.Date(now - d * DAY)) }
        val gate = Semaphore(3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                val raws: List<String> = singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                val at = System.currentTimeMillis()
                val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                weekData.update { it + ("p|" + l.id to LeagueData(parsed, if (raws.isEmpty()) at - HOUR else at, null)) }
            }
        }
    }

    /** Your teams' whole seasons (every competition), from ESPN: every 6 hours. */
    private suspend fun updateSchedules(now: Long, force: Boolean) {
        val f = follows.value
        val keys = f.teams.map { it.key }.toSet()
        if (scheduleData.value.keys.any { it !in keys }) scheduleData.update { m -> m.filterKeys { it in keys } }
        for (t in f.teams) {
            val l = SportsCatalog.league(t.leagueId) ?: continue
            val path = l.espnPath ?: continue
            val cur = scheduleData.value[t.key]
            if (cur != null && now - cur.fetchedAt < (if (force) HOUR else 6 * HOUR)) continue
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = false) }.getOrNull()?.let { raws += l to it }
            if (path.startsWith("soccer/") || l.national) {
                runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = true) }.getOrNull()?.let { raws += l to it }
                for (rid in SportsCatalog.relatedLeagues(l.id)) {
                    val rl = SportsCatalog.league(rid) ?: continue
                    val rp = rl.espnPath ?: continue
                    runCatching { feeds.teamScheduleRaw(rp, t.teamId, fixtures = true) }.getOrNull()?.let { raws += rl to it }
                }
            }
            val at = System.currentTimeMillis()
            // The league's team list (saved for a week): where the colours come from.
            if (raws.isNotEmpty()) runCatching { leagueTeams(l) }
            if (raws.isEmpty()) {
                scheduleData.update { m -> m + (t.key to LeagueData(m[t.key]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val list = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) { mergeSchedule(raws) }
            scheduleData.update { it + (t.key to LeagueData(list, at, null)) }
            runCatching {
                File(dir, "ts_${safe(t.key)}.json").writeText("$at\n" + raws.joinToString(RAW_SEP) { (rl, raw) -> rl.id + "\t" + raw })
            }
        }
    }

    /** One list from a team's schedule answers: each game once, under the first competition it came with. */
    private fun mergeSchedule(raws: List<Pair<League, String>>): List<SportsEvent> {
        val byId = LinkedHashMap<String, SportsEvent>()
        for ((rl, raw) in raws) {
            // First answer wins: the team's main league is asked first, and only has its own
            // games (or, when the feed says which competition a game is in, it's under that one).
            runCatching { SportsFeeds.parseSchedule(rl, raw) }.getOrDefault(emptyList()).forEach { ev ->
                byId.putIfAbsent(ev.espnId ?: ev.id, ev)
            }
        }
        // A team's schedule from ESPN leaves out the teams' colours (the scoreboard has them):
        // filled in from the league's team list and today's games, so every card has its colours.
        val known = HashMap<String, TeamInfo>()
        fun learn(t: TeamInfo) {
            if (t.color == null && t.altColor == null) return
            known.putIfAbsent(SportsCatalog.teamFamily(t.leagueId) + "|" + t.id, t)
        }
        raws.map { it.first }.distinctBy { it.id }.forEach { l -> cachedTeams(l).forEach(::learn) }
        (leagueData.value.values + weekData.value.values).forEach { d -> d.events.forEach { ev -> ev.sides.forEach { learn(it.team) } } }
        synchronized(teamCache) { teamCache.values.toList() }.forEach { list -> list.forEach(::learn) }
        fun fill(side: Side?): Side? {
            if (side == null || side.team.color != null) return side
            val k = known[SportsCatalog.teamFamily(side.team.leagueId) + "|" + side.team.id] ?: return side
            return side.copy(team = side.team.copy(color = k.color, altColor = k.altColor, logo = side.team.logo ?: k.logo))
        }
        return byId.values.map { ev -> if (ev.sides.all { it.team.color != null }) ev else ev.copy(away = fill(ev.away), home = fill(ev.home)) }
            .sortedBy { it.startMs }
    }

    /** A league's teams as already saved (no download): for colours. */
    private fun cachedTeams(league: League): List<TeamInfo> {
        synchronized(teamCache) { teamCache[league.id] }?.let { return it }
        val file = File(dir, "teams_${safe(league.id)}.json")
        if (!file.exists()) return emptyList()
        return runCatching { SportsFeeds.parseTeams(league, file.readText()) }.getOrDefault(emptyList())
    }

    private suspend fun freeCached(name: String, maxAge: Long, fetch: suspend () -> String): Pair<Long, String>? {
        val file = File(dir, "free_$name.json")
        val saved = runCatching { file.readText().let { it.substringBefore('\n').toLong() to it.substringAfter('\n') } }.getOrNull()
        val now = System.currentTimeMillis()
        if (saved != null && now - saved.first < maxAge) return saved
        var allowed = false
        freeAttemptAt.compute(name) { _, prior ->
            if (prior == null || now - prior >= 5 * MINUTE) { allowed = true; now } else prior
        }
        if (!allowed) return saved
        return runCatching {
            val raw = fetch()
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
            require(payload != null && payload.keys.any { it in setOf("leagues", "countries", "events", "event", "tvevent", "tv", "eventtv", "teams") })
            file.writeText("$now\n$raw")
            freeAttemptAt.remove(name)
            now to raw
        }.getOrElse { saved }
    }

    /** Independent public-key fallback. Every list is explicitly partial on this tier. */
    private suspend fun updateTsdb(now: Long, @Suppress("UNUSED_PARAMETER") force: Boolean) {
        for (id in FreeSportsDb.seedIds) {
            if (!foreground.value || viewers.value == 0) return
            freeCached("league_$id", 24 * HOUR) { freeDb.get("lookupleague.php", "id" to id) }
                ?.second?.let { applyFreeLeagues(it) }
        }
        val selected = SportsCatalog.all.filter { SportsTaxonomy.accepts(it, settings.value.excludedSportsNodes) }.map { it.sport }.toSet()
        for (sport in FreeSportsDb.discoverySports.filterKeys { it in selected }.values.distinct()) {
            if (!foreground.value || viewers.value == 0) return
            freeCached("discovery_${safe(sport)}", 24 * HOUR) { freeDb.get("search_all_leagues.php", "s" to sport) }
                ?.second?.let { applyFreeLeagues(it) }
        }
        val active = activeLeagues().filter { it.id.startsWith("tsdb/") }
        // Prioritise followed leagues, then the stalest; bounded batches leave room for event coverage lookups.
        val mine = followedLeagueIds()
        for (league in active.filter { tsdbData.value[it.id]?.let { d -> now - d.fetchedAt >= 30 * MINUTE } != false }
            .sortedWith(compareByDescending<League> { it.id in mine }.thenByDescending { it.id.removePrefix("tsdb/") in FreeSportsDb.seedIds }.thenBy { tsdbData.value[it.id]?.fetchedAt ?: 0L }).take(4)) {
            if (!foreground.value || viewers.value == 0) return
            val id = league.id.removePrefix("tsdb/")
            val saved = freeCached("events_$id", 30 * MINUTE) {
                val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                val raw = listOf("eventsnextleague.php", "eventspastleague.php").map {
                    freeDb.get(it, "id" to id)
                } + freeDb.get("eventsday.php", "d" to day, "l" to id)
                FreeSportsDb.joinEvents(raw)
            } ?: continue
            val prior = tsdbData.value[league.id]?.events.orEmpty().associateBy { it.id }
            val fetched = FreeSportsDb.events(league, saved.second, saved.first).map { e -> prior[e.id]?.let { old -> e.copy(broadcastCoverage = old.broadcastCoverage, broadcasts = old.broadcasts) } ?: e }
            val old = tsdbData.value[league.id]?.events.orEmpty().filter { it.startMs >= now - 7 * DAY }
            tsdbData.update { it + (league.id to LeagueData((fetched + old).distinctBy { e -> e.id }, saved.first, null)) }
        }
    }

    private fun applyFreeLeagues(raw: String) {
        FreeSportsDb.leagues(raw).forEach { freeLeagues[it.id] = it }
        SportsCatalog.setExtra(freeLeagues.values.toList())
    }

    /** Event-specific TV listings; only a verified cross-feed identity may enrich an ESPN event. */
    suspend fun loadBroadcasts(event: SportsEvent) = withContext(Dispatchers.IO) {
        var candidate = event
        if (event.source != EventSource.THESPORTSDB) {
            if (event.sides.size != 2 || !event.hasTime) return@withContext
            val league = SportsCatalog.league(event.leagueId) ?: return@withContext
            val query = listOfNotNull(event.home, event.away).joinToString("_vs_") { it.team.fullName }
            val day = java.time.Instant.ofEpochMilli(event.startMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
            val saved = freeCached("search_${safe(event.id)}", 6 * HOUR) {
                freeDb.get("searchevents.php", "e" to query, "d" to day)
            } ?: return@withContext
            val matched = ArrayList<SportsEvent>()
            for (leagueId in FreeSportsDb.eventLeagueIds(saved.second)) {
                if (!leagueId.all(Char::isDigit)) continue
                val metadata = freeCached("league_$leagueId", 24 * HOUR) { freeDb.get("lookupleague.php", "id" to leagueId) } ?: continue
                val otherLeague = FreeSportsDb.leagues(metadata.second).firstOrNull { it.id == "tsdb/$leagueId" } ?: continue
                matched += FreeSportsDb.events(otherLeague, saved.second, saved.first).filter {
                    SportsEventIdentity.same(event, it, league, otherLeague)
                }
            }
            candidate = matched.singleOrNull() ?: return@withContext
        }
        val id = candidate.id.substringAfterLast(':').takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@withContext
        val saved = freeCached("tv_$id", 6 * HOUR) { freeDb.get("lookuptv.php", "id" to id) } ?: return@withContext
        val coverage = FreeSportsDb.coverage(saved.second, id)
        broadcastEnrichment.update { it + (event.id to coverage) }
    }

    /** V1 may report a score/status for an individual event; never infer it from the clock. */
    suspend fun refreshFreeEvent(event: SportsEvent) = withContext(Dispatchers.IO) {
        if (!foreground.value || event.source != EventSource.THESPORTSDB) return@withContext
        val id = event.id.substringAfterLast(':').takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@withContext
        val league = SportsCatalog.league(event.leagueId) ?: return@withContext
        val saved = freeCached("event_$id", 90_000) { freeDb.get("lookupevent.php", "id" to id) } ?: return@withContext
        val updated = FreeSportsDb.events(league, saved.second, saved.first).singleOrNull { it.id == event.id } ?: return@withContext
        if (baseFeed.enabled) {
            tsdbData.update { map ->
                val prior = map[league.id]?.events.orEmpty()
                map + (league.id to LeagueData(listOf(updated) + prior.filter { it.id != updated.id }, saved.first, null))
            }
        } else tsdbData.update { map -> map.mapValues { (_, data) -> LeagueData(data.events.map { if (it.id == event.id) updated else it }, data.fetchedAt, data.error) } }
    }

    /** Leagues whose feed wouldn't take a range of days (asked one day at a time from then on). */
    private val dayByDay: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * A league's games for the three days around today: one request for the whole range; if the
     * feed won't take that, one request per day; failing that, whatever day the feed picks.
     */
    private fun fetchScoreboard(l: League, @Suppress("UNUSED_PARAMETER") from: String, @Suppress("UNUSED_PARAMETER") to: String, days: List<String>): List<String> {
        // ESPN turns down a range of days ("20261019-20261021": HTTP 400, in every league), so
        // a day at a time (the ESPN check in nuvio-data showed it).
        var firstError: Throwable? = null
        val singles = days.mapNotNull { d ->
            runCatching { feeds.scoreboardDayRaw(l, d) }.onFailure { if (firstError == null) firstError = it }.getOrNull()
        }
        if (singles.isNotEmpty()) return singles
        runCatching { feeds.scoreboardDefaultRaw(l) }.getOrNull()?.let { return listOf(it) }
        throw firstError ?: java.io.IOException("no answer")
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "no internet or the address can't be found"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "secure connection failed: ${e.message?.take(60)}"
        else -> e.message?.take(80)?.ifBlank { null } ?: e.javaClass.simpleName
    }

    private suspend fun coroutineScopeFetch(list: List<League>, block: suspend (League) -> Unit) {
        kotlinx.coroutines.coroutineScope { list.map { l -> async { block(l) } }.awaitAll() }
    }

    private suspend fun updateFixtures() {
        val s = settings.value
        val followed = followedLeagueIds()
        val leagues = activeLeagues(s).filter { it.fixtureSlug != null && (viewers.value > 0 || it.id in followed) }
        for (l in leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            val raw = runCatching { feeds.fixturesRaw(slug) }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
                ?: runCatching { file.readText() }.getOrNull() ?: continue
            val list = runCatching { SportsFeeds.parseFixtures(l, raw) }.getOrDefault(emptyList())
            fixtureData.update { it + (l.id to LeagueData(list, System.currentTimeMillis(), null)) }
        }
    }

    private fun loadCache() {
        if (baseFeed.enabled) {
            baseFeed.cached()?.let(::applyBaseFeed)
            return
        }
        val now = System.currentTimeMillis()
        val m = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val file = File(dir, "sb_${safe(l.id)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 2 * DAY) return@runCatching
                // Saved a while ago: counts as old, so it's checked again when wanted.
                m[l.id] = LeagueData(
                    text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null
                )
            }
        }
        val fx = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            if (!file.exists()) continue
            runCatching { fx[l.id] = LeagueData(SportsFeeds.parseFixtures(l, file.readText()), 0L, null) }
        }
        leagueData.value = m
        fixtureData.value = fx
        // The week ahead, team seasons and non-league football, as last saved.
        val wk = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val file = File(dir, "wk_${safe(l.id)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 3 * DAY) return@runCatching
                wk[l.id] = LeagueData(text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null)
            }
        }
        weekData.value = wk
        dir.listFiles().orEmpty().filter { it.name.startsWith("free_league_") || it.name.startsWith("free_discovery_") }.forEach { file ->
            runCatching { applyFreeLeagues(file.readText().substringAfter('\n')) }
        }
        val f = follows.value
        val sched = HashMap<String, LeagueData>()
        for (t in f.teams) {
            val file = File(dir, "ts_${safe(t.key)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                val raws = text.substring(nl + 1).split(RAW_SEP).mapNotNull { part ->
                    val tab = part.indexOf('\t')
                    if (tab <= 0) null else SportsCatalog.league(part.substring(0, tab))?.let { it to part.substring(tab + 1) }
                }
                if (raws.isNotEmpty()) sched[t.key] = LeagueData(mergeSchedule(raws), at, null)
            }
        }
        scheduleData.value = sched
        val ts = HashMap<String, LeagueData>()
        for (league in SportsCatalog.extra) {
            val file = File(dir, "free_events_${league.id.removePrefix("tsdb/")}.json")
            runCatching {
                val raw = file.readText(); val at = raw.substringBefore('\n').toLong()
                ts[league.id] = LeagueData(FreeSportsDb.events(league, raw.substringAfter('\n'), at), at, null)
            }
        }
        tsdbData.value = ts
    }

    // ------------------------------------------------------------------ matching channels

    @OptIn(FlowPreview::class)
    private suspend fun matchLoop() {
        val fixtures = events.map { list -> list.filter { it.source != EventSource.GUIDE }
            .map { "${it.id}@${it.startMs}:${it.broadcasts.hashCode()}:${it.broadcastCoverage.hashCode()}" }
        }.distinctUntilChanged()
        // User/channel/guide changes invalidate a pass; incoming fixtures only queue the next one.
        val trigger = combine(
            channelStore.displayChannels.map { it.size to it.firstOrNull()?.key }.distinctUntilChanged(),
            settings.map { listOf(it.networkChannels, it.eventChannels, it.teamChannels, it.leagueChannels, it.blocked, it.bestChannelFirst, it.hiddenLeagues, it.excludedSportsNodes, it.region, it.preferredGroups, it.backgroundGeminiMatching) }.distinctUntilChanged(),
            follows,
            combine(liveRepo.status.map { it.loading }, com.nuvio.tv.livetv.data.BackgroundWork.guideBuilding, liveRepo.guideGeneration) { loading, building, generation -> Triple(loading, building, generation) }.distinctUntilChanged(),
            liveSession.currentPlaylistId
        ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
        var lastRun = 0L
        scope.launch {
            // …and every 15 minutes (shows move on; new guide listings).
            while (true) {
                delay(15 * MINUTE)
                if (foreground.value && System.currentTimeMillis() - lastRun > 10 * MINUTE) matchKick.value++
            }
        }
        // Last time's matches straight away (the games, the channels), so the page isn't empty
        // while the new round runs.
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { File(dir, "matches.json").takeIf { it.exists() }?.readText() }.getOrNull() }
            if (saved != null) {
                val chans = channelStore.displayChannels.first { it.isNotEmpty() }.associateBy { it.key }
                if (_matches.value.isEmpty()) runCatching { restoreMatches(saved, chans) }
            }
        }
        trigger.collectLatest {
            // A busy scoreboard must not repeatedly cancel the guide discovery at the end of a pass.
            combine(fixtures, matchKick) { f, k -> f to k }
                .debounce { if (_matches.value.isEmpty()) 0L else 1_500L }.conflate().collect {
                if (!foreground.value && viewers.value == 0) return@collect
                if (viewers.value == 0 && playback.state.value.channelKey == null && followedLeagueIds().isEmpty() && follows.value.reminders.isEmpty()) return@collect
                if (!settings.value.showInSidebar && viewers.value == 0) return@collect
                val status = liveRepo.status.value
                if (!status.loadedOnce && channelStore.displayChannels.value.isEmpty()) return@collect
                pickPending = false
                lastRun = System.currentTimeMillis()
                val t0 = System.currentTimeMillis()
                runCatching { com.nuvio.tv.livetv.data.BackgroundWork.heavy { runMatch() } }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "Sports matching failed", it) }
                lastMatchAt = System.currentTimeMillis()
                val took = lastMatchAt - t0
                if (took > 2_000) LiveTvLoadReport.add("Sports matching took ${took / 1000.0} s (page open: ${viewers.value > 0})")
                runCatching { saveMatches() }
            }
        }
    }

    private val matchKick = MutableStateFlow(0)
    @Volatile private var lastMatchAt = 0L

    private val matchSaveGate = kotlinx.coroutines.sync.Mutex()

    /** Saves which channels were matched to which games, to show right away next time. */
    private suspend fun saveMatches() = withContext(Dispatchers.IO) { matchSaveGate.withLock {
        val arr = org.json.JSONArray()
        for ((id, list) in _matches.value) {
            val o = org.json.JSONObject().put("id", id)
            val items = org.json.JSONArray()
            list.forEach { m ->
                items.put(org.json.JSONObject().put("k", m.channel.key).put("s", m.score).put("h", m.how)
                    .put("q", m.quality ?: org.json.JSONObject.NULL).put("c", m.catchup))
            }
            arr.put(o.put("m", items))
        }
        File(dir, "matches.json").writeText(arr.toString())
    } }

    private fun restoreMatches(text: String, chans: Map<String, LiveChannel>) {
        val arr = org.json.JSONArray(text)
        val out = HashMap<String, List<ChannelMatch>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("m")
            val list = ArrayList<ChannelMatch>()
            for (j in 0 until items.length()) {
                val it = items.getJSONObject(j)
                val ch = chans[it.getString("k")] ?: continue
                list += ChannelMatch(ch, it.getInt("s"), it.getString("h"), null, if (it.isNull("q")) null else it.getString("q"), it.optBoolean("c"))
            }
            if (list.isNotEmpty()) out[o.getString("id")] = list
        }
        if (out.isNotEmpty() && _matches.value.isEmpty()) {
            _matches.value = out
            LiveTvLoadReport.add("Sports: showing last time's channels for ${out.size} games while matching again")
        }
    }
    @Volatile private var pickPending = false
    private var lastSummary: String? = null

    private fun visibleSportsChannels(live: com.nuvio.tv.livetv.model.LiveTvSettings,
        user: com.nuvio.tv.livetv.model.LiveUserState, sp: SportsSettings): List<LiveChannel> {
        val picked = HashSet<String>().apply {
            sp.eventChannels.values.forEach { add(it.substringBeforeLast('|')) }
            addAll(sp.networkChannels.values); addAll(sp.teamChannels.values); addAll(sp.leagueChannels.values)
        }
        return channelStore.displayChannels.value.filter { ch ->
            (liveSession.currentPlaylistId.value == null || ch.sourceId == liveSession.currentPlaylistId.value) &&
            (ch.key in picked || (ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups)) &&
                !ParentalControls.isLocked(ch.groupId, ch.group, live, user.lockedGroups) &&
                !(ParentalControls.enabled(live) && live.lockAdultContent && ParentalControls.isAdult(ch.name))
        }
    }

    /** Refine only uncertain, timed EPG candidates after publishing the local results. */
    private fun queueGeminiMatching() {
        if (!foreground.value || viewers.value == 0 || !settings.value.backgroundGeminiMatching) return
        scope.launch {
            if (!geminiMatchGate.tryLock()) return@launch
            try {
                val client = gemini.get()
                val session = client.session()
                if (session.second == null) return@launch
                val candidates = _matches.value.entries.sortedBy { if (it.key == focusedEvent.value) 0 else 1 }
                    .flatMap { (id, matches) ->
                        val event = known.value.firstOrNull { it.id == id } ?: return@flatMap emptyList()
                        matches.filter { it.score in 40..79 && it.program != null }.map { event to it }
                    }.distinctBy { (event, match) -> GeminiGuideInput.key(GeminiGuideInput.data(event, match.program!!)) }.take(3)
                for ((event, match) in candidates) {
                    if (!foreground.value || viewers.value == 0 || !settings.value.backgroundGeminiMatching || client.session() != session) break
                    val programme = match.program ?: continue
                    val key = GeminiGuideInput.key(GeminiGuideInput.data(event, programme))
                    val decision = runCatching { client.verifyProgramme(event, programme, session) }.getOrNull() ?: continue
                    if (!settings.value.backgroundGeminiMatching || client.session() != session) break
                    if (decision != GuideMatchDecision.UNCERTAIN) _matches.update { current ->
                        val list = current[event.id].orEmpty().map { candidate ->
                            val same = candidate.score in 40..79 && candidate.program?.let {
                                GeminiGuideInput.key(GeminiGuideInput.data(event, it)) == key
                            } == true
                            if (!same) candidate else when (decision) {
                                GuideMatchDecision.MATCH -> candidate.copy(score = 78, how = "Gemini suggests this programme; unconfirmed")
                                GuideMatchDecision.DIFFERENT -> candidate.copy(score = 35, how = "Gemini suggests a different programme")
                                else -> candidate
                            }
                        }
                        current + (event.id to SportsMatcher.sortMatches(list, settings.value.bestChannelFirst,
                            SportsMatcher.preferenceRank(settings.value)))
                    }
                    delay(15_000)
                }
            } finally { geminiMatchGate.unlock() }
        }
    }

    private fun guideReady(): Boolean = !com.nuvio.tv.livetv.data.BackgroundWork.guideBuilding.value

    /** Publish inexpensive local suggestions before accessing the guide or any remote service. */
    suspend fun loadEventChannels(event: SportsEvent) = withContext(Dispatchers.Default) {
        val live = prefs.settings.first()
        val user = prefs.userState.first()
        val sp = settings.value
        val coverage = broadcastEnrichment.value[event.id].orEmpty()
        val enriched = event.copy(broadcastCoverage = event.broadcastCoverage + coverage,
            broadcasts = (event.broadcasts + coverage.filter { it.country == null || FreeSportsDb.region(it.country) == SportsCatalog.regionOf(sp) }.map { it.name }).distinct())
        combine(channelStore.displayChannels, liveRepo.status) { channels, status ->
            channels.isNotEmpty() || status.loadedOnce
        }.first { it }
        val channels = visibleSportsChannels(live, user, sp)
        fun input(guide: Boolean) = SportsMatcher.Input(listOf(enriched), channels, user.channelQuality,
            live.epgOffsetMinutes * MINUTE, sp, System.currentTimeMillis(),
            replay = event.startMs < System.currentTimeMillis() - 5 * HOUR,
            guideAssignments = liveRepo.autoMatches.value + user.epgOverrides, includeGuide = guide)
        fun publish(result: SportsMatcher.Result, quick: Boolean = false) {
            _matches.update { previous ->
                val matches = if (quick) SportsMatcher.keepGuideMatches(previous, result.matches,
                    setOf(event.id), input(false)) else result.matches
                matches[event.id]?.let { previous + (event.id to it) } ?: (previous - event.id)
            }
        }
        val quick = matcher.match(input(false))
        publish(quick, quick = true)
        saveMatches()
        // The database retains its committed guide while a replacement is imported.
        val detailed = withContext(com.nuvio.tv.livetv.data.BackgroundWork.reads) { matcher.match(input(true)) }
        publish(detailed)
        saveMatches()
        queueGeminiMatching()
    }

    private suspend fun runMatch() = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
        val workContext = currentCoroutineContext()
        val checkpoint = {
            workContext.ensureActive()
            com.nuvio.tv.livetv.data.BackgroundWork.yieldToNavigation()
        }
        val live = prefs.settings.first()
        val user = prefs.userState.first()
        val channels = visibleSportsChannels(live, user, settings.value)
        if (channels.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val shift = live.epgOffsetMinutes * MINUTE
        val s = settings.value
        val f = follows.value
        val keys = channels.associateBy { it.key }
        fun input(list: List<SportsEvent>, guide: Boolean) = SportsMatcher.Input(list, channels,
            user.channelQuality, shift, s, now, includeGuide = guide)
        fun publish(result: SportsMatcher.Result, quick: Boolean = false) {
            val retained = if (quick) events.value.mapTo(HashSet()) { it.id } else HashSet<String>()
            focusedEvent.value?.let(retained::add)
            _matches.update { previous -> SportsMatcher.keepGuideMatches(previous, result.matches,
                retained, input(emptyList(), false)) }
            rebuildLiveScores()
        }
        val guideLeagues = activeLeagues(s, f)
        // Restore completed discoveries before the all-fixture pass, including after process death.
        for (offset in listOf(0, 1, -1)) {
            val saved = matcher.guideEvents(dayStart(offset), dayStart(offset + 1), guideLeagues,
                f.words, keys, shift, now, cachedOnly = true)
            if (saved.isNotEmpty()) guideData.update { previous ->
                val protectedIds = s.eventChannels.keys + s.blocked.map { it.substringBefore('|') } +
                    f.reminders.map { it.eventId } + listOfNotNull(focusedEvent.value)
                (previous + (dayStart(offset) to SportsGuideEvents.merge(saved, protectedIds.toSet())))
                    .filterKeys { it >= dayStart(-1) && it < dayStart(2) }
            }
        }
        val current = events.value
        _matching.value = true
        val quickStarted = System.currentTimeMillis()
        val quick = try { matcher.match(input(current, false), checkpoint) } finally { _matching.value = false }
        Log.d(TAG, "Quick matching ${current.size} events finished in ${System.currentTimeMillis() - quickStarted} ms")
        publish(quick, quick = true)
        saveMatches()
        // Importing a new guide cannot prevent already loaded channel names and user picks appearing.
        if (!guideReady()) return@withContext
        withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
            // EPG-only fixtures should not wait behind all of the API fixtures' channel searches.
            var guideMatches = emptyMap<String, List<ChannelMatch>>()
            val scanContext = currentCoroutineContext()
            for (offset in listOf(0, 1, -1)) {
                val scanStarted = System.currentTimeMillis()
                val discovered = runCatching {
                    val found = matcher.guideEvents(dayStart(offset), dayStart(offset + 1), guideLeagues, f.words, keys, shift, now) { scanContext.ensureActive(); checkpoint() }
                    val protectedIds = s.eventChannels.keys + s.blocked.map { it.substringBefore('|') } +
                        f.reminders.map { it.eventId } + listOfNotNull(focusedEvent.value)
                    SportsGuideEvents.merge(found, protectedIds.toSet())
                }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "Guide fixture discovery failed", it)
                    emptyList()
                }
                Log.d(TAG, "Guide day $offset: ${discovered.size} fixtures in ${System.currentTimeMillis() - scanStarted} ms")
                // Today's guide-only fixtures need not wait for tomorrow and yesterday's scans.
                guideData.update { previous -> (previous + (dayStart(offset) to discovered))
                    .filterKeys { it >= dayStart(-1) && it < dayStart(2) } }
                guideMatches = matcher.match(input(guideData.value.values.flatten(), false), checkpoint).matches
                publish(SportsMatcher.Result(quick.matches + guideMatches, quick.summary), quick = true)
            }
            val matchStarted = System.currentTimeMillis()
            Log.d(TAG, "Matching ${current.size} events against ${channels.size} channels")
            val result = matcher.match(input(current, true), checkpoint)
            Log.d(TAG, "Fixture matching finished in ${System.currentTimeMillis() - matchStarted} ms")
            publish(SportsMatcher.Result(result.matches + guideMatches, result.summary))
            saveMatches()
            queueGeminiMatching()
            if (result.summary != lastSummary) {
                lastSummary = result.summary
                LiveTvLoadReport.add(result.summary)
            }
        }
    }

    // ------------------------------------------------------------------ scores elsewhere in the app

    /** Channel key -> the live game it's showing (sure matches only), for the Live TV info bar. */
    private fun rebuildLiveScores() {
        val evs = events.value.associateBy { it.id }
        val out = HashMap<String, SportsEvent>()
        val cards = HashMap<String, SportsEvent>()
        for ((id, list) in _matches.value) {
            val ev = evs[id] ?: continue
            if (ev.state != GameState.LIVE) continue
            list.filter { it.sure }.forEach { m ->
                cards.putIfAbsent(m.channel.key, ev)
                if (ev.hasScore) out.putIfAbsent(m.channel.key, ev)
            }
        }
        SportsLive.byChannel.value = out
        SportsLive.cardByChannel.value = cards
    }

    /** A team you follow scored: tell the app (Scoring Alerts). */
    private fun noticeScoring(league: League, before: List<SportsEvent>, after: List<SportsEvent>) {
        if (!settings.value.scoringAlerts) return
        val f = follows.value
        val old = before.associateBy { it.id }
        for (ev in after) {
            if (ev.state != GameState.LIVE || !ev.hasScore) continue
            val prev = old[ev.id] ?: continue
            if (!prev.hasScore) continue
            for (side in ev.sides) {
                if (!f.followsTeam(league.id, side.team.id) && league.id !in f.leagues) continue
                val was = prev.sides.firstOrNull { it.team.id == side.team.id }?.score?.toDoubleOrNull() ?: continue
                val nowScore = side.score?.toDoubleOrNull() ?: continue
                if (nowScore > was) SportsLive.alert(SportsAlert(ev, side.team, league))
            }
        }
    }

    // ------------------------------------------------------------------ teams, details

    private val teamCache = HashMap<String, List<TeamInfo>>()

    /** A league's teams (for Pick Your Teams): saved for a week, then fetched again. */
    // ------------------------------------------------------------------ tables

    private val _standings = MutableStateFlow<Map<String, Standings>>(emptyMap())
    /** League tables looked at (or needed for My Sports' row headers), by league id. */
    val standings: StateFlow<Map<String, Standings>> = _standings.asStateFlow()
    /** Leagues without a table (a cup, golf…): when we found out, so they aren't asked again for a while. */
    private val noTable = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val tableLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Whether [league] might have a table (it's asked once; one without is left out after that). */
    fun mayHaveTable(league: League): Boolean {
        if (noTable.containsKey(league.id) && System.currentTimeMillis() - (noTable[league.id] ?: 0L) < 12 * HOUR) return false
        if (league.guideOnly) return false
        if (league.id.startsWith("tsdb/")) return false
        if (league.espnPath == null) return false
        if (league.kind == LeagueKind.EVENT) return league.sport == Sport.RACING && league.id in RACING_TABLES
        return league.sport !in setOf(Sport.GOLF, Sport.TENNIS, Sport.FIGHTING, Sport.WRESTLING, Sport.DARTS, Sport.SNOOKER)
    }

    /**
     * Leagues for the Tables page, in My Sports' order: your teams' leagues (and the European
     * club competitions they play in), the leagues you follow and the ones on your top bar.
     */
    fun tableLeagues(f: SportsFollows = follows.value): List<League> {
        val ids = LinkedHashSet<String>()
        for (key in SportsRows.rowKeys(f)) {
            when {
                key.startsWith("t:") -> f.teams.firstOrNull { "t:${it.key}" == key }?.let { t ->
                    ids += t.leagueId
                    SportsCatalog.relatedLeagues(t.leagueId).filter { it in EUROPEAN_TABLES }.forEach { ids += it }
                }
                key.startsWith("l:") -> ids += key.removePrefix("l:")
            }
        }
        return ids.mapNotNull { SportsCatalog.league(it) }.filter { mayHaveTable(it) }
    }

    /**
     * [leagueId]'s table: from memory when it's under an hour old ([force]: fetched again),
     * otherwise from ESPN or the nuvio-data file, with recent form and places moved since the
     * last round. Null when the league has no table (or it can't be had right now).
     */
    suspend fun standings(leagueId: String, force: Boolean = false): Standings? = withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(leagueId) ?: return@withContext null
        val lock = tableLocks.getOrPut(leagueId) { kotlinx.coroutines.sync.Mutex() }
        lock.lock()
        try {
            val now = System.currentTimeMillis()
            _standings.value[leagueId]?.let { if (!force && now - it.fetchedAt < HOUR) return@withContext it }
            if (!mayHaveTable(league)) return@withContext null
            val file = File(dir, "table_${safe(leagueId)}.json")
            val raw = runCatching {
                if (leagueId.startsWith("tsdb/")) return@withContext null
                else feeds.standingsRaw(Standings.espnUrl(league) ?: return@withContext null)
            }.getOrElse { e ->
                Log.w(TAG, "Table for $leagueId failed: ${describe(e)}")
                // No connection: the last copy (it says how old it is on the page).
                val old = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
                val at = old?.substringBefore('\n')?.toLongOrNull() ?: return@withContext _standings.value[leagueId]
                return@withContext (parseTable(league, old!!.substringAfter('\n'), at) ?: _standings.value[leagueId])
                    ?.also { st -> _standings.update { it + (leagueId to st) } }
            }
            var table = parseTable(league, raw, now)
            if (table == null) {
                noTable[leagueId] = now
                return@withContext null
            }
            runCatching { file.writeText("$now\n$raw") }
            // Form (the last five as squares): ESPN's tables don't have it, so it comes from the last
            // six weeks' results (one request).
            if (table.kind != StandingsKind.RACING && table.source == "ESPN") {
                val recent = known.value.filter { it.leagueId == leagueId }
                val past = runCatching {
                    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
                    SportsFeeds.parseScoreboard(league, feeds.scoreboardRaw(league, fmt.format(now - 42 * DAY), fmt.format(now)))
                }.getOrDefault(emptyList())
                table = Standings.withForm(table, Standings.formFrom((past + recent).distinctBy { it.espnId ?: it.id }, leagueId))
            }
            // Team colours for the rows: the league's team list (kept for a week), else games we know.
            if (table.kind != StandingsKind.RACING) {
                val listed = if (league.espnPath != null) runCatching { leagueTeams(league) }.getOrDefault(emptyList()).associateBy { it.id } else emptyMap()
                val fam = SportsCatalog.teamFamily(leagueId)
                val seen = HashMap<String, TeamInfo>()
                if (listed.size < table.groups.sumOf { it.rows.size }) known.value.forEach { ev ->
                    ev.sides.forEach { sd -> if (sd.team.color != null && SportsCatalog.teamFamily(sd.team.leagueId) == fam) seen.putIfAbsent(sd.team.id, sd.team) }
                }
                table = table.copy(groups = table.groups.map { g ->
                    g.copy(rows = g.rows.map { r ->
                        val t = listed[r.id]?.takeIf { it.color != null || it.altColor != null } ?: seen[r.id]
                        if (t == null) r else r.copy(color = t.color, altColor = t.altColor, logo = r.logo ?: t.logo)
                    })
                })
            }
            // Places moved since the last round (remembered on this TV).
            val mem = File(dir, "table_moves_${safe(leagueId)}.json")
            val (moved, save) = Standings.withChanges(table, runCatching { mem.takeIf { it.exists() }?.readText() }.getOrNull())
            runCatching { mem.writeText(save) }
            _standings.update { it + (leagueId to moved) }
            moved
        } finally {
            lock.unlock()
        }
    }

    private fun parseTable(league: League, raw: String, at: Long): Standings? = runCatching {
        if (league.id.startsWith("tsdb/")) Standings.parseTsdb(league, raw, at) else Standings.parseEspn(league, raw, at)
    }.getOrNull()?.takeIf { s -> s.groups.any { it.rows.isNotEmpty() } }

    /** The tables My Sports' row headers need ("2nd in Premier League"): your teams' own leagues. */
    suspend fun loadTeamTables() {
        val ids = follows.value.teams.map { it.leagueId }.distinct()
        for (id in ids) {
            val l = SportsCatalog.league(id) ?: continue
            if (!mayHaveTable(l)) continue
            runCatching { standings(id) }
        }
    }

    /**
     * A team as the app knows it (colours and logo from its league's team list), for a row of a
     * table: so following it from the table is the same as following it anywhere else.
     */
    fun teamFor(leagueId: String, row: StandingsRow): TeamInfo {
        val league = SportsCatalog.league(leagueId)
        val info = league?.let { l -> (synchronized(teamCache) { teamCache[l.id] } ?: cachedTeams(l)).firstOrNull { it.id == row.id } }
            ?: tsdbTeams[leagueId]?.firstOrNull { it.id == row.id }
            ?: known.value.asSequence().flatMap { it.sides.asSequence() }.map { it.team }
                .firstOrNull { it.id == row.id && SportsCatalog.teamFamily(it.leagueId) == SportsCatalog.teamFamily(leagueId) }
        return info?.copy(leagueId = leagueId, logo = info.logo ?: row.logo)
            ?: TeamInfo(leagueId, row.id, row.shortName, row.name, row.abbr, null, null, null, row.logo)
    }

    /**
     * A team's games for its card: results and games to come. A followed team's season is
     * already here; another team's is fetched (and kept for an hour, so its games open).
     */
    suspend fun teamGames(team: TeamInfo): List<SportsEvent> = withContext(Dispatchers.IO) {
        val fam = SportsCatalog.teamFamily(team.leagueId)
        fun mine(ev: SportsEvent) = ev.sides.any { it.team.id == team.id && SportsCatalog.teamFamily(it.team.leagueId) == fam }
        val now = System.currentTimeMillis()
        val league = SportsCatalog.league(team.leagueId)
        val followed = follows.value.teams.any { it.leagueId == team.leagueId && it.teamId == team.id }
        val cur = lookedUp.value[team.key]
        val path = league?.espnPath
        if (!followed && league != null && path != null && (cur == null || now - cur.fetchedAt > HOUR)) {
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = false) }.getOrNull()?.let { raws += league to it }
            if (path.startsWith("soccer/") || league.national) {
                runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = true) }.getOrNull()?.let { raws += league to it }
            }
            if (raws.isNotEmpty()) {
                val list = mergeSchedule(raws)
                // Only a few teams at a time (the most recent ones).
                lookedUp.update { m -> (m + (team.key to LeagueData(list, now, null))).entries.sortedByDescending { it.value.fetchedAt }.take(6).associate { it.key to it.value } }
            }
        }
        known.value.filter(::mine).distinctBy { dedupeKey(it) }.sortedBy { it.startMs }
    }

    suspend fun teams(league: League): List<TeamInfo> = withContext(Dispatchers.IO) {
        if (league.id.startsWith("tsdb/")) {
            if (tsdbTeams[league.id] == null) {
                freeCached("teams_${safe(league.id)}", 24 * HOUR) {
                    freeDb.get("search_all_teams.php", "l" to league.name)
                }?.second?.let { tsdbTeams[league.id] = FreeSportsDb.teams(league, it) }
            }
            return@withContext tsdbTeams[league.id].orEmpty()
        }
        // Countries: the same list whichever international competition you open (every country
        // ESPN lists in any of them), so England is there even between tournaments.
        if (league.national) {
            val all = LinkedHashMap<String, TeamInfo>()
            for (l in SportsCatalog.leagues.filter { it.national && it.nationalGroup == league.nationalGroup }) {
                leagueTeams(l).forEach { t -> all.putIfAbsent(t.id, if (l.id == league.id) t else t.copy(leagueId = league.id)) }
            }
            return@withContext all.values.sortedBy { it.fullName.lowercase(Locale.ROOT) }
        }
        leagueTeams(league)
    }

    private fun leagueTeams(league: League): List<TeamInfo> {
        synchronized(teamCache) { teamCache[league.id] }?.let { return it }
        if (league.espnPath == null) return emptyList()
        val file = File(dir, "teams_${safe(league.id)}.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * DAY
        val raw = (if (fresh) runCatching { file.readText() }.getOrNull() else null)
            ?: runCatching { feeds.teamsRaw(league).also { runCatching { file.writeText(it) } } }.getOrNull()
            ?: runCatching { file.readText() }.getOrNull()
            ?: return emptyList()
        val list = runCatching { SportsFeeds.parseTeams(league, raw) }.getOrDefault(emptyList())
            .sortedBy { (it.location ?: it.fullName).lowercase(Locale.ROOT) }
        if (list.isNotEmpty()) synchronized(teamCache) { teamCache[league.id] = list }
        return list
    }

    /** A game's summary as last asked: when, the score then, its scoring plays and the rest. */
    private class GameSummary(val at: Long, val sig: String, val plays: List<ScoringPlay>, val extras: GameExtras)
    private val summaryCache = HashMap<String, GameSummary>()

    /**
     * The game's ESPN summary, one request for the latest scoring and everything under it (team
     * stats, leaders, form, the table…). Asked again after 25 seconds while it's on (straight away
     * when the score changes), otherwise after half an hour.
     */
    private suspend fun summaryFor(ev: SportsEvent): GameSummary? = withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(ev.leagueId) ?: return@withContext null
        val id = ev.espnId ?: return@withContext null
        if (league.kind != LeagueKind.TEAMS || league.espnPath == null) return@withContext null
        val sig = "${ev.away?.score}-${ev.home?.score}-${ev.state}"
        val cached = synchronized(summaryCache) { summaryCache[ev.id] }
        if (cached != null && cached.sig == sig && System.currentTimeMillis() - cached.at < (if (ev.state == GameState.LIVE) 25_000 else 30 * MINUTE)) return@withContext cached
        val root = runCatching { feeds.summaryJson(league, id) }.getOrNull() ?: return@withContext cached
        val plays = if (ev.state == GameState.PRE) emptyList() else runCatching { SportsFeeds.parseSummary(root) }.getOrDefault(emptyList())
        val extras = runCatching { SportsExtras.parse(root, ev, league.sport) }.getOrDefault(GameExtras())
        GameSummary(System.currentTimeMillis(), sig, plays, extras).also { synchronized(summaryCache) { summaryCache[ev.id] = it } }
    }

    /** The game's scoring plays (touchdowns, goals…), when ESPN has them. */
    suspend fun scoringPlays(ev: SportsEvent): List<ScoringPlay> =
        if (ev.state == GameState.PRE) emptyList() else summaryFor(ev)?.plays.orEmpty()

    /** Everything else about the game ESPN has (see [GameExtras]); null when there's nothing to ask. */
    suspend fun gameExtras(ev: SportsEvent): GameExtras? = summaryFor(ev)?.extras

    // ------------------------------------------------------------------ your choices

    fun updateSettings(transform: (SportsSettings) -> SportsSettings) {
        scope.launch { prefs.editSportsSettings { raw -> transform(SportsSettings.fromJson(raw)).toJson() } }
    }

    fun updateFollows(transform: (SportsFollows) -> SportsFollows) {
        scope.launch {
            prefs.editSportsMine { raw -> transform(SportsFollows.fromJson(raw)).toJson() }
            kick.value++
        }
    }

    fun toggleTeam(team: TeamInfo) = updateFollows { f ->
        val had = f.followedTeam(team)
        if (had != null) f.copy(teams = f.teams - had)
        // Just the team (its league isn't followed with it: following the NFL is a separate choice).
        else f.copy(teams = f.teams + FollowedTeam(team.leagueId, team.id, team.name, team.abbr, team.color, team.logo), setUp = true)
    }

    /** Moves a row of My Sports up ([by] -1) or down (+1). */
    fun moveRow(key: String, by: Int) = updateFollows { f ->
        val keys = SportsRows.rowKeys(f).toMutableList()
        val i = keys.indexOf(key)
        val j = i + by
        if (i < 0 || j !in keys.indices) f
        else {
            keys.add(j, keys.removeAt(i))
            f.copy(order = keys)
        }
    }

    /** A league's own button on the Sports top bar (and its row under My Teams): on or off. */
    fun togglePin(leagueId: String) = updateFollows { f ->
        if (leagueId in f.pins) f.copy(pins = f.pins - leagueId, unpinned = f.unpinned + leagueId)
        else f.copy(pins = f.pins + leagueId, unpinned = f.unpinned - leagueId, setUp = true)
    }

    fun toggleLeague(leagueId: String) = updateFollows { f ->
        f.copy(leagues = if (leagueId in f.leagues) f.leagues - leagueId else f.leagues + leagueId, setUp = true)
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.length < 3) return
        updateFollows { f -> if (f.words.any { it.equals(w, true) }) f else f.copy(words = f.words + w, setUp = true) }
    }

    fun removeWord(word: String) = updateFollows { f -> f.copy(words = f.words.filterNot { it.equals(word, true) }) }

    fun markSetUp() = updateFollows { it.copy(setUp = true) }

    fun isReminded(ev: SportsEvent): Boolean = follows.value.reminders.any { it.eventId == ev.id }

    fun toggleReminder(ev: SportsEvent) = updateFollows { f ->
        if (f.reminders.any { it.eventId == ev.id }) f.copy(reminders = f.reminders.filterNot { it.eventId == ev.id })
        else f.copy(reminders = (f.reminders.filter { it.startMs > System.currentTimeMillis() - 3 * HOUR } + SportsReminder(ev.id, ev.title, ev.startMs)))
    }

    /** This game's reminder: switch to it by itself, or ask (sets a reminder if there wasn't one). */
    fun setAutoSwitch(ev: SportsEvent, on: Boolean) = updateFollows { f ->
        val has = f.reminders.any { it.eventId == ev.id }
        f.copy(reminders = if (has) f.reminders.map { if (it.eventId == ev.id) it.copy(autoSwitch = on) else it }
            else f.reminders + SportsReminder(ev.id, ev.title, ev.startMs, on))
    }

    /** Whether [ev]'s reminder switches by itself: its own choice, else the setting. */
    fun switchesFor(ev: SportsEvent): Boolean =
        follows.value.reminders.firstOrNull { it.eventId == ev.id }?.autoSwitch ?: settings.value.autoSwitch

    fun dropReminder(eventId: String) = updateFollows { f -> f.copy(reminders = f.reminders.filterNot { it.eventId == eventId }) }

    /**
     * "This is the right channel" for [ev]. With [forNetworks], also for every game the same
     * network has from now on (FOX on your FOX 4K channel, say).
     */
    fun pickChannel(ev: SportsEvent, channelKey: String, forNetworks: Boolean, forTeam: TeamInfo? = null, forLeague: Boolean = false) = updateSettings { s ->
        val keep = s.eventChannels.filterValues { v -> (v.substringAfterLast('|').toLongOrNull() ?: 0L) > System.currentTimeMillis() - 3 * DAY }
        s.copy(
            eventChannels = keep + (ev.id to "$channelKey|${ev.startMs}"),
            networkChannels = if (forNetworks) s.networkChannels + ev.broadcasts.associate { SportsMatcher.networkKey(it) to channelKey } else s.networkChannels,
            teamChannels = if (forTeam != null) s.teamChannels + (teamChannelKey(ev.leagueId, forTeam.id) to channelKey) else s.teamChannels,
            leagueChannels = if (forLeague) s.leagueChannels + (ev.leagueId to channelKey) else s.leagueChannels,
            blocked = s.blocked - "${ev.id}|$channelKey"
        )
    }.also {
        // Show it on the game straight away (the next channel search keeps it there), and
        // search again now rather than waiting for the guide to finish loading.
        channelStore.displayChannels.value.firstOrNull { it.key == channelKey }?.let { ch ->
            val mine = ChannelMatch(ch, 200, "Your pick", null, SportsMatcher.nameQuality(ch.name), false)
            _matches.value = _matches.value + (ev.id to (listOf(mine) + _matches.value[ev.id].orEmpty().filter { it.channel.key != channelKey }))
        }
        pickPending = true
        matchKick.value++
    }

    /** "Not this channel" for [ev] (and it stops being used for its network). */
    fun blockChannel(ev: SportsEvent, channelKey: String) = updateSettings { s ->
        s.copy(
            blocked = (s.blocked + "${ev.id}|$channelKey").toList().takeLast(400).toSet(),
            eventChannels = s.eventChannels.filterNot { (id, v) -> id == ev.id && v.substringBeforeLast('|') == channelKey },
            networkChannels = s.networkChannels.filterNot { (n, key) -> key == channelKey && ev.broadcasts.any { SportsMatcher.networkKey(it) == n } },
            teamChannels = s.teamChannels.filterNot { (t, key) -> key == channelKey && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) == t } },
            leagueChannels = s.leagueChannels.filterNot { (l, key) -> key == channelKey && l == ev.leagueId }
        )
    }.also {
        _matches.value[ev.id]?.let { l -> _matches.value = _matches.value + (ev.id to l.filter { it.channel.key != channelKey }) }
        pickPending = true
        matchKick.value++
    }

    fun clearFixes() = updateSettings { it.copy(networkChannels = emptyMap(), eventChannels = emptyMap(), blocked = emptySet(), teamChannels = emptyMap(), leagueChannels = emptyMap()) }

    /** The channel to tune for a reminder or "watch now": the best match, if any. */
    fun bestChannel(eventId: String): ChannelMatch? = _matches.value[eventId]?.firstOrNull()

    fun channelsForPicker(): List<LiveChannel> = channelStore.displayChannels.value.filter { liveSession.currentPlaylistId.value == null || it.sourceId == liveSession.currentPlaylistId.value }

    // ------------------------------------------------------------------ days

    fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    private fun dayOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** ESPN groups games by US Eastern days: yesterday to tomorrow there covers the page's three days. */
    private fun espnRange(now: Long): Pair<String, String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return f.format(java.util.Date(now - DAY)) to f.format(java.util.Date(now + DAY))
    }

    /** The ESPN (US Eastern) dates for yesterday, today and tomorrow. */
    private fun espnDays(now: Long): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return listOf(now - DAY, now, now + DAY).map { f.format(java.util.Date(it)) }
    }

    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")

    companion object {
        private const val TAG = "Sports"
        /** Racing series with a drivers' table on ESPN. */
        private val RACING_TABLES = setOf("racing/f1", "racing/nascar-premier", "racing/nascar-secondary", "racing/nascar-truck", "racing/irl")
        /** Club competitions with a table of their own (the league phase), shown with your team's league. */
        private val EUROPEAN_TABLES = setOf("soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf")
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        /** The page's days run from this many days back to [DAYS_AHEAD] ahead. */
        const val DAYS_AHEAD = 7
        const val DAYS_BACK = 7
        /** Between saved answers when a league was asked one day at a time. */
        private const val RAW_SEP = "\n\u0001\n"
    }
}

/** A team you follow just scored (shown for a few seconds wherever you are). */
data class SportsAlert(val event: SportsEvent, val team: TeamInfo, val league: League, val id: Long = System.nanoTime())

/** Sports data the rest of the app shows: scores on Live TV's info bar, scoring alerts. */
object SportsLive {
    val byChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    /** Channel key -> the live game on it (with or without a score), for the card on the info panel. */
    val cardByChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    val alerts = MutableStateFlow<SportsAlert?>(null)
    fun alert(a: SportsAlert) { alerts.value = a }
}
