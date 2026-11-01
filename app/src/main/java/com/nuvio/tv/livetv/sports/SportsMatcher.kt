package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.SportsCatalog.hasWord
import com.nuvio.tv.livetv.sports.SportsCatalog.norm

/**
 * Finds which of your channels show each game, in four ways (best first):
 *  1. your TV guide: a show around kick-off naming both teams (or the event),
 *  2. the channel's own name ("NFL 03: Bears @ Lions", common for event channels),
 *  3. the network ESPN lists (FOX, ESPN, TNT…) with a sports show on at the time,
 *  4. channels you picked yourself with Fix Match (and never the ones you said were wrong).
 * Works for any country: UK users get Sky Sports / TNT Sports from their own guide.
 */
internal class SportsMatcher(private val db: EpgDatabase, private val guideCacheDir: java.io.File? = null) {

    class Input(
        val events: List<SportsEvent>,
        /** Channels you can see (not hidden, not locked). */
        val channels: List<LiveChannel>,
        /** Channel key -> picture quality seen ("4K", "FHD", "HD", "SD"). */
        val quality: Map<String, String>,
        /** Your guide time shift (Live TV settings), in ms: the database keeps the guide's own times. */
        val shiftMs: Long,
        val settings: SportsSettings,
        val now: Long,
        val replay: Boolean = false,
        val guideAssignments: Map<String, EpgAssignment> = emptyMap(),
        val includeGuide: Boolean = true
    )

    class Result(val matches: Map<String, List<ChannelMatch>>, val summary: String)

    private class Found(val key: String, var score: Int, var how: String, var program: EpgProgram?)

    /** Disc golf is its own sport: its events keep their matches, and no other event takes its shows. */
    private fun isDiscGolf(ev: SportsEvent): Boolean =
        hasWord(norm(ev.title), "disc golf") || ev.leagueId.contains("disc", ignoreCase = true)

    /** Runs [block] over the channels (in list order) whose name has one of [tokens] as a word. */
    private inline fun forNamed(
        index: java.util.SortedMap<String, List<Int>>,
        tokens: Set<String>,
        names: List<Pair<LiveChannel, String>>,
        block: (LiveChannel, String) -> Unit
    ) {
        val hits = java.util.BitSet(names.size)
        for (tok in tokens) for ((word, positions) in index.tailMap(tok)) {
            if (!word.startsWith(tok)) break
            positions.forEach { hits.set(it) }
        }
        var i = hits.nextSetBit(0)
        while (i >= 0) {
            val (ch, nn) = names[i]
            block(ch, nn)
            i = hits.nextSetBit(i + 1)
        }
    }

    /** Channel names worked out once per channel list and kept between searches (they rarely change). */
    private class NameData(
        val fingerprint: Long,
        val names: List<Pair<LiveChannel, String>>,
        val tokenIndex: java.util.SortedMap<String, List<Int>>,
        val discNames: Set<String>
    )
    @Volatile private var nameData: NameData? = null

    @Synchronized private fun nameData(channels: List<LiveChannel>): NameData {
        // The channel list is rebuilt for every search, so it's recognised by its contents.
        var fp = channels.size.toLong()
        for (ch in channels) fp = fp * 31 + ch.key.hashCode() * 17L + ch.name.hashCode()
        nameData?.let { if (it.fingerprint == fp) return it }
        // Separator "channels" ("##### USA SPORTS #####", "==== UK ====") are never a game's channel.
        val names = channels.mapNotNull { ch ->
            if (ch.name.length < 6 || isBanner(ch.name)) null
            else ch to norm(ch.name.replace("+", " plus ").replace("@", " at "))
        }
        // Channel names by word, so a game's channels are found without going through every channel.
        val tokenIndex = HashMap<String, MutableList<Int>>()
        names.forEachIndexed { i, (_, nn) ->
            val tokens = nn.split(' ').filter { it.isNotEmpty() }.toMutableSet()
            // Lookup aliases only: final broadcaster matching still checks the full name.
            for (word in tokens.toList()) {
                if (word == "bt") tokens += "tnt"
                val number = word.removeSuffix("uhd").removeSuffix("fhd").removeSuffix("hd").removeSuffix("sd")
                    .removePrefix("channel").removePrefix("network")
                when (number) { "7" -> tokens += "seven"; "9" -> tokens += "nine"; "10" -> tokens += "ten" }
            }
            tokens.forEach { tok -> tokenIndex.getOrPut(tok) { ArrayList() }.add(i) }
        }
        val discNames = names.filter { (_, nn) -> hasWord(nn, "disc golf") }.mapTo(HashSet()) { it.first.key }
        return NameData(fp, names, java.util.TreeMap<String, List<Int>>(tokenIndex), discNames).also { nameData = it }
    }

    fun match(input: Input, checkpoint: () -> Unit = {}): Result {
        checkpoint()
        val byKey = input.channels.associateBy { it.key }
        val rank = preferenceRank(input.settings)
        val tStart = System.currentTimeMillis()
        val nd0 = nameData(input.channels)
        val names = nd0.names
        val tokenIndex = nd0.tokenIndex
        val groups = input.channels.map { it.group }.distinct().associateWith(::norm)
        val discNames = nd0.discNames
        val discIds = input.events.filter { isDiscGolf(it) }.mapTo(HashSet()) { it.id }
        val found = HashMap<String, HashMap<String, Found>>()
        fun add(eventId: String, key: String, score: Int, how: String, program: EpgProgram?) {
            if (key !in byKey || score <= 0) return
            val m = found.getOrPut(eventId) { HashMap() }
            val cur = m[key]
            if (cur == null || score > cur.score) m[key] = Found(key, score, how, program ?: cur?.program)
            else if (cur.program == null && program != null) cur.program = program
        }

        // Events that run for days (a golf tournament, an F1 weekend from practice to the race) have
        // no single start time: while they're on, treat them as starting now.
        fun multiDay(ev: SportsEvent) = !ev.hasTime && ev.endMs != null
        // Their ids: with no start time of their own, only a show that names the competition ("Formula 1",
        // "Grand Prix"…) counts, not one that merely shares a word or two with the title ("United States").
        val noStart = HashSet<String>()
        val events = input.events.mapNotNull { ev ->
            when {
                ev.hasTime -> ev
                !multiDay(ev) -> null
                // The feed's end date is the start of the last day (Sunday 00:00): the event runs through that day.
                ev.endMs!! + 24 * HOUR < input.now -> null
                ev.startMs >= input.now -> ev
                else -> ev.copy(startMs = input.now)
            }?.also { if (!ev.hasTime) noStart.add(ev.id) }
        }
        val wanted = events.filter { ev ->
            ev.state != GameState.OFF &&
                ((input.replay && ev.startMs < input.now && byKey.isNotEmpty()) || ev.startMs in (input.now - 8 * HOUR)..(input.now + AHEAD_MS))
        }
        // A channel you picked yourself shows straight away, however far off the game is (the
        // search above only looks a couple of days ahead).
        val picked = events.filter { ev ->
            ev.state != GameState.OFF && (input.replay || ev.startMs >= input.now - 8 * HOUR) &&
                (ev.id in input.settings.eventChannels || ev.leagueId in input.settings.leagueChannels ||
                    (input.settings.teamChannels.isNotEmpty() && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) in input.settings.teamChannels }))
        }
        val byId = (wanted + picked).associateBy { it.id }
        var byGuide = 0; var byName = 0; var byNetwork = 0

        // Shows found in the guide already know their channels.
        wanted.filter { it.source == EventSource.GUIDE }.forEach { ev ->
            ev.guideHits.forEach { (key, p) -> add(ev.id, key, 100, "Guide: ${p.title}", p) }
        }

        val teamEvents = wanted.filter { it.source != EventSource.GUIDE }
        val terms = teamEvents.associate { ev -> ev.id to termsFor(ev) }

        // Tournaments and race weekends running at the same time (several tennis events, two golf
        // tours): a channel or show that just says "Tennis" or "Golf" can't tell them apart.
        // Each one's own words ("basel", "bermuda") pick it out; the competition's words only
        // count when no other event running now uses them.
        val multiEvents = teamEvents.filter { it.id in noStart }
        val specific: Map<String, Set<String>> = multiEvents.associate { ev ->
            val league = terms[ev.id]?.leagueWords.orEmpty().flatMap { it.split(' ') }.toSet()
            ev.id to norm(ev.title).trim().split(' ')
                .filter { it.length >= 4 && it.any { c -> c.isLetter() } && it !in GENERIC_TOURNAMENT_WORDS && it !in league }
                .toSet()
        }
        val leagueWordUse = HashMap<String, Int>()
        multiEvents.forEach { ev -> terms[ev.id]?.leagueWords.orEmpty().toSet().forEach { leagueWordUse[it] = (leagueWordUse[it] ?: 0) + 1 } }
        fun specificHits(evId: String, text: String) = specific[evId].orEmpty().count { hasWord(text, it) }
        /** The competition named by a word only this event uses ("pga tour" while the DP World Tour is on too). */
        fun distinctLeague(evId: String, text: String) = terms[evId]?.leagueWords.orEmpty()
            .any { it != "grand prix" && (leagueWordUse[it] ?: 0) <= 1 && hasWord(text, it) }
        /** The text names another event of the same kind running now ("Vienna" for the Basel tournament). */
        fun namesOther(evId: String, text: String): Boolean {
            val mine = terms[evId]?.leagueWords.orEmpty().toSet()
            return multiEvents.any { o ->
                o.id != evId && terms[o.id]?.leagueWords.orEmpty().any { it in mine } && specificHits(o.id, text) > 0
            }
        }

        // ---- 1. The guide, a half-hour slot at a time.
        if (input.includeGuide) teamEvents.groupBy { it.startMs / (30 * MINUTE) }.forEach { (_, slot) ->
            checkpoint()
            val slotStart = slot.minOf { it.startMs }
            val slotEnd = slot.maxOf { it.startMs }
            val words = slot.flatMap { ev -> terms[ev.id]?.sqlWords.orEmpty() }.distinct()
            if (words.isEmpty()) return@forEach
            val hits = runCatching {
                db.findByWords(
                    fromMs = slotStart - 100 * MINUTE - input.shiftMs,
                    toMs = slotEnd + 70 * MINUTE - input.shiftMs,
                    minStopMs = slotStart + 10 * MINUTE - input.shiftMs,
                    words = words
                )
            }.getOrDefault(emptyList())
            if (hits.isEmpty()) return@forEach
            val prepared = hits.mapNotNull { (key, p0) ->
                val ch = byKey[key] ?: return@mapNotNull null
                val p = if (input.shiftMs == 0L) p0 else p0.copy(startMs = p0.startMs + input.shiftMs, stopMs = p0.stopMs + input.shiftMs)
                if (fillerOrLoop(ch, p)) return@mapNotNull null
                // The guide's own label for studio shows and replays, when it has one.
                val cat = norm(p.category.orEmpty())
                if (hasWord(cat, "sports non event") || hasWord(cat, "classic sport event")) return@mapNotNull null
                // Many guides put the match-up in the episode title ("MLB Baseball" / "Cubs at
                // Cardinals"); it's the first line of the description here: read it with the title.
                val desc = p.description.orEmpty()
                val sub = if ('\n' in desc) desc.substringBefore('\n').takeIf { it.length <= 90 } else null
                val nt = norm(p.title + (sub?.let { " $it" } ?: ""))
                if (SportsCatalog.NOT_LIVE_WORDS.any { hasWord(nt, it) }) return@mapNotNull null
                Triple(key, p, nt to norm(desc))
            }
            val programmeSports = prepared.associate { (key, p, _) ->
                (key to p.startMs) to SportsTaxonomy.classifyProgramme(p.title, null)?.let { SportsTaxonomy.sport(it.sportId) }
            }
            for (ev in slot) {
                val eventSport = SportsCatalog.league(ev.leagueId)?.sport
                val t = terms[ev.id] ?: continue
                for ((key, p, texts) in prepared) {
                    val (nt, nd) = texts
                    val programmeSport = programmeSports[key to p.startMs]
                    if (conflictingSports(eventSport, programmeSport) && !t.namesLeague(nt, "")) continue
                    // Disc golf shows never count for regular golf.
                    if (ev.id !in discIds && (hasWord(nt, "disc golf") || hasWord(nd, "disc golf"))) continue
                    if (p.startMs > ev.startMs + 60 * MINUTE || p.stopMs < ev.startMs + 15 * MINUTE) continue
                    var score = t.scoreText(nt, nd)
                    // No start time (a race weekend, a tournament): a show on now that names this
                    // event, or the competition when nothing else of its kind is on.
                    if (ev.id in noStart) {
                        val hits = specificHits(ev.id, nt) + specificHits(ev.id, nd)
                        if (!t.namesLeague(nt, nd) && !t.namesEvent(nt, nd)) continue
                        when {
                            score > 0 -> Unit
                            hits >= 1 -> score = 88
                            namesOther(ev.id, nt) || namesOther(ev.id, nd) -> continue
                            distinctLeague(ev.id, nt) || distinctLeague(ev.id, nd) -> score = 70
                            else -> continue
                        }
                    }
                    if (score <= 0) continue
                    // Pre-game shows often start an hour early on the same channel; much earlier or later is less likely the game.
                    if (p.startMs < ev.startMs - 80 * MINUTE || p.startMs > ev.startMs + 25 * MINUTE) score -= 10
                    add(ev.id, key, score, "Guide: ${p.title}", p)
                }
            }
        }
        byGuide = found.size
        val tGuide = System.currentTimeMillis()

        // ---- 2. The channel's own name.
        for (ev in teamEvents) {
            checkpoint()
            if (!input.replay && ev.state == GameState.POST && ev.startMs < input.now - 5 * HOUR) continue
            val t = terms[ev.id] ?: continue
            val discEvent = ev.id in discIds
            // Only channels whose name has one of the game's words can match: look at those, not
            // all of them (with tens of thousands of channels, every game against every channel took minutes).
            forNamed(tokenIndex, t.anchorTokens(ev.id in noStart), names) { ch, nn ->
                val channelSport = SportsTaxonomy.classifyProgramme(ch.name, null)?.let { SportsTaxonomy.sport(it.sportId) }
                if (conflictingSports(SportsCatalog.league(ev.leagueId)?.sport, channelSport) && !t.leagueInName(nn)) return@forNamed
                if (!discEvent && ch.key in discNames) return@forNamed
                if (t.inName(nn)) { add(ev.id, ch.key, 95, "Channel name", null); byName++ }
                else if (t.teamChannel(nn, groups[ch.group].orEmpty())) { add(ev.id, ch.key, 65, "Team channel; specific game unconfirmed", null); byName++ }
                // A channel named for the event ("Tennis: Swiss Indoors Basel") or for the competition
                // ("Sky Sports F1") when nothing else of its kind is on.
                else if (ev.id in noStart) {
                    val hits = specificHits(ev.id, nn)
                    when {
                        (hits >= 1 && t.leagueInName(nn)) || (hits >= 2 && t.namesEvent(nn, "")) -> { add(ev.id, ch.key, 90, "Channel name", null); byName++ }
                        !namesOther(ev.id, nn) && distinctLeague(ev.id, nn) -> { add(ev.id, ch.key, 78, "Channel name", null); byName++ }
                    }
                }
            }
        }

        val tNames = System.currentTimeMillis()

        // ---- 3. The network ESPN lists, with a sports show on at the time.
        val networkEvents = teamEvents.filter { it.broadcasts.isNotEmpty() && (input.replay || it.startMs > input.now - 5 * HOUR) }
        val candidates = HashMap<String, List<LiveChannel>>()
        // Existing guide assignments can identify the same station under a different playlist label.
        // They broaden suggestions only; they do not prove that a particular game was broadcast.
        val guideAliases = if (input.replay) input.guideAssignments
            .filter { (key, assignment) -> key in byKey && !assignment.isNone }
            .entries.groupBy({ it.value }, { it.key }) else emptyMap()
        networkEvents.flatMap { it.broadcasts }.map { networkKey(it) }.distinct().forEach { n ->
            val pats = (NETWORKS[n] ?: listOf(n)).map(BroadcastNames::canonical).filter { it.length >= 3 }.distinct()
            if (pats.isEmpty()) return@forEach
            val not = NETWORK_NOT[n].orEmpty()
            val participants = networkEvents.filter { ev -> ev.broadcasts.any { networkKey(it) == n } }
                .flatMap { ev -> terms[ev.id]?.let { it.a + it.b }.orEmpty() }.distinct()
            val list = ArrayList<LiveChannel>()
            forNamed(tokenIndex, pats.mapNotNull { it.trim().substringBefore(' ').takeIf { w -> w.isNotEmpty() } }.toSet(), names) { ch, nn ->
                if ((pats.any { BroadcastNames.matches(it, ch.name, participants) } || BroadcastNames.matches(n, ch.name, participants)) && not.none { hasWord(nn, it) }) list += ch
            }
            val aliases = guideAliases.values.filter { group -> list.any { it.key in group } }.flatten()
                .mapNotNull(byKey::get)
            candidates[n] = (list + aliases).distinctBy { it.key }
        }
        networkEvents.groupBy { it.startMs / (30 * MINUTE) }.forEach { (_, slot) ->
            checkpoint()
            val at = slot.minOf { it.startMs } + 20 * MINUTE
            // Your own channel for a network counts even when no channel is named like it (Sky
            // Sports F1 for "Apple TV"): nothing to look through, but the pick still applies.
            for (ev in slot) for (b in ev.broadcasts) {
                input.settings.networkChannels[networkKey(b)]?.let { key -> add(ev.id, key, 75, "Your channel for $b", null) }
            }
            val keys = slot.flatMap { ev -> ev.broadcasts.flatMap { candidates[networkKey(it)].orEmpty() } }.map { it.key }.distinct()
            if (keys.isEmpty()) return@forEach
            val onNow: Map<String, EpgProgram?> = if (!input.includeGuide) emptyMap() else runCatching {
                db.range(at - input.shiftMs, at - input.shiftMs + 1, light = true, channels = keys)
                    .mapValues { (_, l) -> l.firstOrNull()?.let { p -> p.copy(startMs = p.startMs + input.shiftMs, stopMs = p.stopMs + input.shiftMs) } }
            }.getOrDefault(emptyMap())
            for (ev in slot) {
                val league = SportsCatalog.league(ev.leagueId) ?: continue
                val sporty = (league.guideWords + SPORT_WORDS).map { norm(it).trim() }
                for (b in ev.broadcasts) {
                    val n = networkKey(b)
                    input.settings.networkChannels[n]?.let { key -> add(ev.id, key, 75, "Your channel for $b", onNow[key]) }
                    // No start time (a race weekend, a tournament): the network's listing says nothing about what's on now.
                    if (ev.id in noStart) continue
                    val national = n in NATIONAL
                    candidates[n].orEmpty()
                        .map { ch ->
                            val p = onNow[ch.key]
                            val reported = ev.broadcastCoverage.filter { networkKey(it.name) == n }
                            val countryAllowed = reported.isEmpty() || reported.any { BroadcastNames.compatible(it, ch.name) }
                            val showText = p?.let { norm(it.title + " " + (it.episode ?: "")) }
                            val score = when {
                                !countryAllowed -> 0
                                ev.id !in discIds && showText != null && hasWord(showText, "disc golf") -> 0
                                p == null -> if (!input.includeGuide || input.replay || reported.isNotEmpty()) 55 else if (keys.size < 400 && national) 45 else 0
                                // The guide says it's a different game: not this one.
                                terms[ev.id]?.otherGame(showText!!) == true -> 0
                                sporty.any { hasWord(norm(p.title), it) } && SportsCatalog.NOT_LIVE_WORDS.none { hasWord(norm(p.title), it) } ->
                                    if (national) 62 else 52
                                else -> 0
                            }
                            Triple(ch, score, p)
                        }
                        .filter { it.second > 0 }
                        .sortedWith(
                            compareByDescending<Triple<LiveChannel, Int, EpgProgram?>> { rank(it.first) }
                                .thenByDescending { qualityRank(input.quality[it.first.key] ?: nameQuality(it.first.name)) }
                        )
                        .take(if (national) 4 else 6)
                        .forEach { (ch, score, p) -> add(ev.id, ch.key, score, "$b listed broadcaster", p); byNetwork++ }
                }
            }
        }

        // ---- 4. Your own fixes: this game, then every game of a team.
        for (ev in byId.values) {
            input.settings.eventChannels[ev.id]?.substringBeforeLast('|')?.let { add(ev.id, it, 200, "Your pick", null) }
            // Your channel for the whole competition (every Formula 1 round, every PGA Tour event).
            input.settings.leagueChannels[ev.leagueId]?.let { add(ev.id, it, 140, "Your channel for ${SportsCatalog.league(ev.leagueId)?.name ?: "this competition"}", null) }
            if (input.settings.teamChannels.isNotEmpty()) for (side in ev.sides) {
                input.settings.teamChannels[teamChannelKey(ev.leagueId, side.team.id)]?.let { add(ev.id, it, 150, "Your channel for ${side.team.name}", null) }
            }
        }

        val out = HashMap<String, List<ChannelMatch>>()
        for ((eventId, m) in found) {
            val ev = byId[eventId] ?: continue
            val list = m.values
                .filter { "$eventId|${it.key}" !in input.settings.blocked }
                .mapNotNull { f ->
                    val ch = byKey[f.key] ?: return@mapNotNull null
                    ChannelMatch(
                        channel = ch,
                        score = f.score,
                        how = f.how,
                        program = f.program,
                        quality = input.quality[ch.key] ?: nameQuality(ch.name),
                        catchup = ch.catchup != null && CatchupUrlBuilder.isAvailable(ch.catchup, f.program?.startMs ?: ev.startMs, input.now)
                    )
                }
            if (list.isEmpty()) continue
            out[eventId] = sortMatches(list, input.settings.bestChannelFirst, rank).limitSuggestions()
        }
        // Events that run for days (golf, race weekends): what was looked for and found, for the report.
        val multi = teamEvents.filter { it.id in noStart }.joinToString(" | ") { ev ->
            val t = terms[ev.id]
            "${ev.title.take(32)}: looked for ${t?.leagueWords?.joinToString("/")}, " +
                "channels named like that ${names.count { (_, nn) -> t?.leagueInName(nn) == true }}, matched ${out[ev.id]?.size ?: 0}"
        }
        val tEnd = System.currentTimeMillis()
        val summary = "Sports: ${wanted.size} games and events in the next day or so · channels found for ${out.size} " +
            "(guide $byGuide, channel names $byName, networks $byNetwork) · ${input.channels.size} channels searched in " +
            "${(tEnd - tStart) / 1000} s (guide ${(tGuide - tStart) / 1000}, names ${(tNames - tGuide) / 1000}, networks ${(tEnd - tNames) / 1000})" +
            (if (multi.isNotEmpty()) " · multi-day: $multi" else "")
        return Result(out, summary)
    }

    /**
     * Shows in the guide for sports ESPN doesn't cover (darts, snooker, rugby…) and names you
     * follow: live shows on [dayStart, dayEnd) whose title says so.
     */
    private val classifiedText = object : LinkedHashMap<Pair<String, String?>, SportsTaxonomy.Node?>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String?>, SportsTaxonomy.Node?>) = size > 4096
        }

    private var guideChannels: Map<String, LiveChannel>? = null
    private var guideChannelsDigest = ""

    private fun channelDigest(channels: Map<String, LiveChannel>): String {
        if (guideChannels !== channels) {
            guideChannelsDigest = SportsGuideCache.digest(channels.values.sortedBy { it.key }
                .flatMap { listOf(it.key, it.name, it.group, it.groupId, it.tvgName.orEmpty()) })
            guideChannels = channels
        }
        return guideChannelsDigest
    }

    private data class GuideScanKey(val start: Long, val end: Long, val fingerprint: String?, val revision: String?,
        val channels: String, val leagues: String, val words: List<String>, val shift: Long)
    private data class GuideScan(val key: GuideScanKey, val events: List<SportsEvent>)
    private val guideScans = LinkedHashMap<Long, GuideScan>()
    private fun guideState(event: SportsEvent, now: Long): SportsEvent {
        val state = when {
            event.endMs != null && now >= event.endMs -> GameState.POST
            now >= event.startMs -> GameState.LIVE
            else -> GameState.PRE
        }
        return if (state == event.state) event else event.copy(state = state)
    }

    fun guideEvents(
        dayStart: Long,
        dayEnd: Long,
        leagues: List<League>,
        followedWords: List<String>,
        channels: Map<String, LiveChannel>,
        shiftMs: Long,
        now: Long,
        cachedOnly: Boolean = false,
        checkCancelled: () -> Unit = {}
    ): List<SportsEvent> {
        val scanKey = GuideScanKey(dayStart, dayEnd, db.fingerprint(), db.contentRevision(),
            channelDigest(channels),
            SportsGuideCache.digest(leagues.map { listOf(it.id, it.sport.name, it.liveByDefault.toString()) + it.guideWords }.flatten()),
            followedWords.toList(), shiftMs)
        guideScans[dayStart]?.takeIf { it.key == scanKey }
            ?.let { return it.events.map { event -> guideState(event, now) } }
        val diskKey = SportsGuideCache.digest(listOf("2", scanKey.start.toString(), scanKey.end.toString(),
            scanKey.fingerprint.orEmpty(), scanKey.revision.orEmpty(), scanKey.channels, scanKey.leagues,
            scanKey.shift.toString()) + scanKey.words)
        guideCacheDir?.let { dir -> SportsGuideCache.read(dir, dayStart, diskKey) }?.let { saved ->
            guideScans[dayStart] = GuideScan(scanKey, saved)
            return saved.map { guideState(it, now) }
        }
        if (cachedOnly) return emptyList()
        val leagueWords = leagues.flatMap { l -> l.guideWords.map { it to l } }
        // Identify legacy competitions independently of visibility, as the taxonomy does.
        val fallbackWords = (SportsCatalog.leagues + leagues).distinctBy { it.id }
            .filter { l -> !l.id.startsWith("taxonomy/") && SportsTaxonomy.roots.none { SportsTaxonomy.sport(it.id) == l.sport } }
            .flatMap { l -> l.guideWords.map { it to l } }
        val hits = ArrayList<Pair<String, EpgProgram>>()
        fun classify(p: EpgProgram): SportsTaxonomy.Node? {
            val key = p.title to p.description
            if (!classifiedText.containsKey(key)) classifiedText[key] = SportsTaxonomy.classifyProgramme(p.title, p.description)
            return classifiedText[key]
        }
        val allowedIds = leagues.mapTo(HashSet()) { it.id }
        val otherWords = (leagueWords.filterNot { it.second.id.startsWith("taxonomy/") }.map { it.first } + followedWords)
            .map { norm(it).trim() }.distinct()
        val otherIndex = otherWords.groupBy { it.substringBefore(' ') }
        val includedText = com.nuvio.tv.livetv.data.boundedCache<Pair<String, String?>, Boolean>(8192)
        var visited = 0
        db.visitPrograms(dayStart - shiftMs, dayEnd - shiftMs) { key, p ->
            if (++visited % 256 == 0) checkCancelled()
            if (key in channels) {
                // Regional/quality feeds repeat the same programme text; classify it once per scan.
                val included = includedText.getOrPut(p.title to p.description) {
                    classify(p)?.let { "taxonomy/" + it.id in allowedIds } == true ||
                        (otherIndex.isNotEmpty() && norm(p.title + " " + p.description.orEmpty()).let { text ->
                            text.trim().split(' ').distinct().asSequence()
                                .flatMap { otherIndex[it].orEmpty().asSequence() }.any { hasWord(text, it) }
                        })
                }
                if (included) hits += key to p
            }
        }
        val groups = LinkedHashMap<String, MutableList<Pair<String, EpgProgram>>>()
        val meta = HashMap<String, Pair<League?, String?>>()
        val seen = HashSet<String>()
        for ((index, hit) in hits.withIndex()) {
            if (index % 256 == 0) checkCancelled()
            val (key, p0) = hit
            val ch = channels[key] ?: continue
            if (!seen.add("$key|${p0.startMs}")) continue
            val p = if (shiftMs == 0L) p0 else p0.copy(startMs = p0.startMs + shiftMs, stopMs = p0.stopMs + shiftMs)
            val nt = norm(p.title)
            val nd = norm(p.description.orEmpty())
            if (SportsCatalog.NOT_LIVE_WORDS.any { hasWord(nt, it) }) continue
            val classified = classify(p)
            val league = if (classified != null) leagues.firstOrNull { it.id == "taxonomy/" + classified.id }
                else fallbackWords.filter { (w, _) ->
                    hasWord(nt, norm(w).trim()) || hasWord(nd, norm(w).trim()) }
                    .maxByOrNull { (_, l) ->
                        // Explicit names beat generic aliases such as "Grand Prix".
                        val names = listOf(l.name, l.short).map { norm(it).trim() }.filter { it.isNotEmpty() }
                        when {
                            names.any { hasWord(nt, it) } -> 2
                            names.any { hasWord(nd, it) } -> 1
                            else -> 0
                        }
                    }?.second?.takeIf { it.id in allowedIds }
            // 24/7 and loop channels ("NETFLIX WWE", a movie channel called "… NXT"): not live sport.
            if (fillerOrLoop(ch, p)) continue
            // Wrestling words turn up in other shows' titles ("RuPaul's Drag Race - Lip Sync
            // Smackdown", "The Goldbergs - WrestleMania"): the title must start with the show's
            // name, or name the promotion (WWE, AEW, TNA).
            if (league?.sport == Sport.WRESTLING && !wrestlingTitle(cleanTitle(p.title), league)) continue
            val saysLive = isLive(nt, nd)
            // Wrestling's weekly shows are live without the guide saying so, but not on a channel
            // named after the show or league (those run old shows all day).
            if (!saysLive && (league?.liveByDefault != true || league.guideWords.any { hasWord(nameNorm(ch.name), norm(it).trim()) }) &&
                !MATCHUP.containsMatchIn(" $nt $nd ")) continue
            val word = followedWords.firstOrNull { w -> hasWord(nt, norm(w).trim()) || hasWord(nd, norm(w).trim()) }
            if (league == null && word == null) continue
            val clean = cleanTitle(p.title)
            val g = (league?.id ?: OTHER_ID) + "|" + norm(clean).trim() + "|" + (p.startMs / (15 * MINUTE))
            groups.getOrPut(g) { ArrayList() } += key to p
            val prev = meta[g]
            meta[g] = (prev?.first ?: league) to (prev?.second ?: word)
        }
        val result = groups.map { (g, list) ->
            val p = list.first().second
            val (league, word) = meta[g] ?: (null to null)
            SportsEvent(
                id = "guide:" + g.hashCode().toUInt().toString(16) + ":" + (p.startMs / 60_000),
                leagueId = league?.id ?: OTHER_ID,
                startMs = p.startMs,
                hasTime = true,
                state = when {
                    now >= p.stopMs -> GameState.POST
                    now >= p.startMs -> GameState.LIVE
                    else -> GameState.PRE
                },
                title = cleanTitle(p.title),
                subtitle = p.description?.takeIf { it.isNotBlank() }?.let { d -> d.take(140) + if (d.length > 140) "…" else "" },
                status = null,
                away = null, home = null,
                source = EventSource.GUIDE,
                guideHits = list,
                endMs = p.stopMs,
                word = word
            )
        }
        checkCancelled()
        // Never persist a scan that overlapped a committed guide change.
        if (db.fingerprint() == scanKey.fingerprint && db.contentRevision() == scanKey.revision) {
            guideScans[dayStart] = GuideScan(scanKey, result)
            guideCacheDir?.let { SportsGuideCache.write(it, dayStart, diskKey, result) }
        }
        while (guideScans.size > 3) guideScans.remove(guideScans.keys.first())
        return result
    }

    // ------------------------------------------------------------------ how a game is named

    private class Terms(
        val a: List<String>, val aWeak: List<String>,
        val b: List<String>, val bWeak: List<String>,
        val abbrA: String?, val abbrB: String?,
        val leagueWords: List<String>,
        val eventWords: List<String>,
        val sqlWords: List<String>
    ) {
        private fun any(text: String, list: List<String>) = list.any { hasWord(text, it) }

        // A location's words scattered across two opponents are not an event identity.
        private val eventPhrases = eventWords.flatMap { phrase ->
            val words = phrase.split(' ')
            val specific = words.filter { it !in GENERIC_TOURNAMENT_WORDS && it !in GENERIC_EVENT_WORDS }
            listOfNotNull(phrase.takeIf { words.size >= 2 && specific.isNotEmpty() },
                specific.joinToString(" ").takeIf { specific.size >= 2 })
        }.distinct()

        fun namesEvent(nt: String, nd: String): Boolean = any(nt, eventPhrases) || any(nd, eventPhrases)

        fun scoreText(nt: String, nd: String): Int {
            if (a.isNotEmpty() && b.isNotEmpty()) {
                val aT = any(nt, a); val bT = any(nt, b)
                val aW = any(nt, aWeak); val bW = any(nt, bWeak)
                val aD = any(nd, a); val bD = any(nd, b)
                return when {
                    aT && bT -> 100
                    abbrA != null && abbrB != null && abbrA != abbrB &&
                        hasWord(nt + nd, abbrA) && hasWord(nt + nd, abbrB) &&
                        (namesLeague(nt, nd) || MATCHUP.containsMatchIn(nt)) -> 92
                    (aT && bW) || (bT && aW) -> 90
                    (aT || aD) && (bT || bD) -> 82
                    (aT || bT) && any(nt, leagueWords) -> 58
                    else -> 0
                }
            }
            if (!namesLeague(nt, nd) && !namesEvent(nt, nd)) return 0
            val ew = eventWords.count { hasWord(nt, it) || hasWord(nd, it) }
            val lw = any(nt, leagueWords)
            return when {
                lw && ew > 0 -> 92
                ew >= 2 -> 75
                ew > 0 && any(nd, leagueWords) -> 70
                else -> 0
            }
        }

        /** The show names a game ("Bills at Raiders", "Juventus vs. Lazio") that isn't this one. */
        fun otherGame(text: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val us = any(text, a) || any(text, aWeak) || any(text, b) || any(text, bWeak) ||
                listOfNotNull(abbrA, abbrB).any { it.length >= 2 && hasWord(text, it) }
            if (us) return false
            return MATCHUP.containsMatchIn(text)
        }

        /** The show's title or description names the competition itself ("Formula 1", "Grand Prix"). */
        fun namesLeague(nt: String, nd: String): Boolean = leagueWords.any { hasWord(nt, it) || hasWord(nd, it) }

        /** The first word of every name this game looks for in a channel's name (to find those channels quickly). */
        fun anchorTokens(includeLeague: Boolean): Set<String> {
            val out = HashSet<String>()
            // Team fixtures require a participant; a league-only feed cannot pass those checks.
            val lists = listOf(a, b, eventWords) + if (includeLeague || a.isEmpty() || b.isEmpty()) listOf(leagueWords) else emptyList()
            for (l in lists) for (term in l) {
                term.trim().substringBefore(' ').takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
            abbrA?.let { out.add(it) }
            abbrB?.let { out.add(it) }
            return out
        }

        /** The channel's own name carries the competition's name ("Sky Sports F1", "Formula 1 HD"). */
        fun leagueInName(nn: String): Boolean = leagueWords.any { it.length >= 2 && it != "grand prix" && hasWord(nn, it) }

        /** A dedicated team feed is a candidate, not proof of this particular fixture. */
        fun teamChannel(nn: String, group: String): Boolean =
            a.isNotEmpty() && b.isNotEmpty() && (any(nn, a) || any(nn, b)) &&
                (leagueInName(nn + " " + group) || (a + b).any { nn.trim() == "radio $it" })

        fun inName(nn: String): Boolean {
            if (a.isNotEmpty() && b.isNotEmpty()) {
                if (any(nn, a) && any(nn, b)) return true
                val x = abbrA ?: return false
                val y = abbrB ?: return false
                return listOf("$x $y", "$x at $y", "$x vs $y", "$x v $y", "$y $x", "$y vs $x", "$y v $x", "$y at $x").any { hasWord(nn, it) }
            }
            return eventWords.isNotEmpty() && any(nn, leagueWords) && eventWords.count { hasWord(nn, it) } >= 1
        }
    }

    /** A wrestling show's own title: starts with one of the league's names, or names the promotion. */
    private fun wrestlingTitle(title: String, league: League): Boolean {
        val t = norm(title)
        if (listOf("wwe", "aew", "tna", "njpw", "all elite wrestling", "impact wrestling").any { hasWord(t, it) }) return true
        val start = t.trim()
        return league.guideWords.any { w ->
            val n = norm(w).trim()
            start == n || start.startsWith("$n ") || start.startsWith("friday night $n") || start.startsWith("monday night $n")
        }
    }

    /** A channel name without "US:" and quality words, for comparing with show titles. */
    private fun nameNorm(name: String): String =
        norm(name.replace(NAME_PREFIX, "")).replace(NAME_NOISE, " ").replace(SPACES, " ")

    /**
     * Not a real broadcast of a game or show: a 24/7 channel, a block of 6 hours or more, or a
     * listing that's just the channel's own name ("CINE PRO NXT" on CINE PRO NXT).
     */
    private val fillerChannels = com.nuvio.tv.livetv.data.boundedCache<List<String?>, Pair<Boolean, String>>(8192)
    private val fillerTitles = com.nuvio.tv.livetv.data.boundedCache<String, Pair<String, Boolean>>(8192)

    private fun fillerOrLoop(ch: LiveChannel, p: EpgProgram): Boolean {
        val channel = fillerChannels.getOrPut(listOf(ch.key, ch.name, ch.group, ch.groupId, ch.tvgName)) {
            com.nuvio.tv.livetv.data.LiveTvPosterResolver.is247(ch) to nameNorm(ch.name).trim()
        }
        if (channel.first || p.stopMs - p.startMs >= 6 * HOUR) return true
        val (t, matchup) = fillerTitles.getOrPut(p.title) {
            nameNorm(cleanTitle(p.title)).trim() to MATCHUP.containsMatchIn(norm(p.title.replace("@", " at ")))
        }
        val c = channel.second
        if (t.isEmpty() || c.isEmpty()) return t.isEmpty()
        if (t == c) return true
        if (matchup) return false
        // The listing repeats the whole (multi-word) channel name: filler ("CINE PRO NXT").
        return c.contains(' ') && c.length >= 8 && " $t ".contains(" $c ")
    }

    private fun conflictingSports(event: Sport?, listing: Sport?): Boolean =
        event != null && listing != null && event != Sport.OTHER && listing != Sport.OTHER && event != listing

    private fun termsFor(ev: SportsEvent): Terms {
        val league = SportsCatalog.league(ev.leagueId)
        val leagueWords = league?.guideWords.orEmpty().map { norm(it).trim() }.filter { it.length >= 2 }
        val away = ev.away
        val home = ev.home
        if (league != null && away != null && home != null) {
            val (a, aw) = SportsCatalog.teamNames(away.team, league)
            val (b, bw) = SportsCatalog.teamNames(home.team, league)
            fun ab(t: TeamInfo) = norm(t.abbr).trim().takeIf { it.length in 2..4 }
            return Terms(a, aw, b, bw, ab(away.team), ab(home.team), leagueWords, emptyList(), (a + b + listOfNotNull(ab(away.team), ab(home.team))).distinct())
        }
        // A wrestling show: its own name ("RAW #1743" -> "raw", "SmackDown", "NXT"), so a channel
        // called "LIVE EVENT 01 - WWE Monday Night RAW" is found.
        if (league?.sport == Sport.WRESTLING) {
            val show = norm(wrestlingHeadline(ev.title)).trim()
            val words = listOf(show).filter { it.length >= 3 }
            if (words.isNotEmpty()) return Terms(emptyList(), emptyList(), emptyList(), emptyList(), null, null, leagueWords, words, (words + leagueWords).distinct())
        }
        // One event (a race, a fight card, a tournament): its own words ("mexico city", "pantoja").
        val core = norm(ev.title).trim()
            .split(" vs ", " v ", " at ")
            .flatMap { part -> listOf(part.trim()) + part.trim().split(' ').filter { it.length >= 5 } }
            .map { it.trim() }
            .filter { it.length >= 4 && it !in GENERIC_EVENT_WORDS && leagueWords.none { lw -> lw == it } }
            .distinct()
            .take(8)
        return Terms(emptyList(), emptyList(), emptyList(), emptyList(), null, null, leagueWords, core, (core + leagueWords).filter { it.length >= 3 }.distinct())
    }

    companion object {
        /** A quick pass must not erase verified guide results while the full refresh is pending. */
        internal fun keepGuideMatches(previous: Map<String, List<ChannelMatch>>, fresh: Map<String, List<ChannelMatch>>,
            retainedEvents: Set<String>, input: Input): Map<String, List<ChannelMatch>> {
            val channels = input.channels.associateBy { it.key }
            val retained = previous.filterKeys { it in retainedEvents }.mapValues { (eventId, matches) ->
                val guide = matches.mapNotNull { match ->
                    if (!match.sure) return@mapNotNull null
                    val channel = channels[match.channel.key] ?: return@mapNotNull null
                    val programme = match.program ?: return@mapNotNull null
                    if ("$eventId|${channel.key}" in input.settings.blocked) return@mapNotNull null
                    match.copy(channel = channel, catchup = CatchupUrlBuilder.isAvailable(channel.catchup, programme.startMs, input.now))
                }
                sortMatches((fresh[eventId].orEmpty() + guide).sortedByDescending { it.score }
                    .distinctBy { it.channel.key }, input.settings.bestChannelFirst, preferenceRank(input.settings)).limitSuggestions()
            }.filterValues { it.isNotEmpty() }
            return fresh + retained
        }
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        /** How far ahead games are looked for in your channels (most guides cover 2–3 days). */
        const val AHEAD_MS = 56 * HOUR

        private val SPACES = Regex(" +")
        private val cleanedTitles = com.nuvio.tv.livetv.data.boundedCache<String, String>(8192)
        private val NAME_PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*[:|\-]\s*""")
        private val NAME_NOISE = Regex(""" (fhd|uhd|hd|sd|4k|hevc|raw|backup|vip) """)

        /** Shows found in the guide for a followed name that isn't one of the listed sports. */
        const val OTHER_ID = "guide/other"

        /** Words too common to tell one tournament or race from another ("Hall of Fame Open" is hall/fame, never open). */
        private val GENERIC_TOURNAMENT_WORDS = setOf(
            "atp", "wta", "tour", "tennis", "golf", "open", "cup", "championship", "championships", "masters",
            "classic", "international", "invitational", "presented", "the", "for", "and", "ladies", "mens", "womens",
            "grand", "prix", "race", "series", "world", "trophy", "tournament", "challenger", "final", "finals",
            "round", "qualifying", "practice", "sprint", "week", "weekend", "live", "season", "formula", "edition"
        )

        private val BANNER_RUN = Regex("([#=*~_▬━─•■◆★|\\-])\\1{2,}")

        /** A separator in the channel list ("##### USA SPORTS #####", "▬▬ UK ▬▬"), not a channel. */
        fun isBanner(name: String): Boolean {
            if (BANNER_RUN.containsMatchIn(name)) return true
            val letters = name.count { it.isLetterOrDigit() }
            return name.length >= 6 && letters * 10 < name.length * 4
        }

        private val GENERIC_EVENT_WORDS = setOf(
            "grand prix", "grand", "prix", "championship", "championships", "open", "classic", "tour", "the", "final",
            "finals", "race", "series", "invitational", "masters", "trophy", "fight night", "night", "main card", "prelims"
        )

        /** Words that make a show on a sports network look like a game (not news or a talk show). */
        private val SPORT_WORDS = listOf(
            "football", "basketball", "hockey", "baseball", "soccer", "game", "live", "ncaa", "college", "sports",
            "nfl", "nba", "nhl", "mlb", "mls", "playoff", "playoffs", "bowl", "series"
        )

        private fun isLive(nt: String, nd: String): Boolean =
            nt.startsWith(" live ") || hasWord(nt, "live") || nd.startsWith(" live ") ||
                hasWord(nd, "live coverage") || hasWord(nd, "live action") || hasWord(nd, "coverage of")

        /** "x at y", "x vs y", "x v y" in a show title (text already through norm). */
        private val MATCHUP = Regex("""\s(?:at|vs|v|versus)\s""")

        private val LIVE_PREFIX = Regex("""^\s*(?:live\s*[:\-–|]?\s*)""", RegexOption.IGNORE_CASE)
        // Guides mark first showings: "New: AEW Collision", "AEW Collision (New)", "… - NEW". Only
        // with the colon, dash or brackets ("New England Patriots" and "New York" stay as they are).
        private val NEW_PREFIX = Regex("""^\s*\(?new\)?\s*[:\-–|]\s*""", RegexOption.IGNORE_CASE)
        private val NEW_SUFFIX = Regex("""\s*(?:\(new\)|\[new]|[\-–|]\s*new)\s*$""", RegexOption.IGNORE_CASE)
        fun cleanTitle(t: String): String = cleanedTitles[t] ?: cleanTitleUncached(t).also { cleanedTitles[t] = it }

        private fun cleanTitleUncached(t: String): String {
            var s = SportsCatalog.withoutSmallMarks(t)
            repeat(2) { s = s.replace(LIVE_PREFIX, "").replace(NEW_PREFIX, "").replace(NEW_SUFFIX, "").trim() }
            return s.ifBlank { t }
        }

        /** A network as we key it ("ESPN+" -> "espn plus", "USA Net" -> "usa net"). */
        fun networkKey(name: String): String = norm(name.replace("+", " plus ")).trim()

        /** Networks that show the same thing everywhere (no local stations). */
        private val NATIONAL = setOf(
            "espn", "espn2", "espnu", "espnews", "espn plus", "espn deportes", "fs1", "fs2", "nfl net", "nfl network", "nfln",
            "nba tv", "nhl net", "nhl network", "mlb net", "mlb network", "tnt", "tbs", "trutv", "usa net", "usa network",
            "cbssn", "cbs sports network", "btn", "big ten network", "secn", "sec network", "accn", "acc network",
            "golf", "golf channel", "tennis channel", "fox deportes", "tudn", "univision", "telemundo", "universo", "peacock",
            "prime video", "paramount plus", "apple tv", "netflix", "bein sports", "nbcsn", "mlb tv", "max", "truetv"
        )

        /** How each network appears in channel names. */
        private val NETWORKS: Map<String, List<String>> = mapOf(
            "fox" to listOf("fox"),
            "cbs" to listOf("cbs"),
            "nbc" to listOf("nbc"),
            "abc" to listOf("abc"),
            "espn" to listOf("espn"),
            "espn2" to listOf("espn2", "espn 2"),
            "espnu" to listOf("espnu", "espn u"),
            "espnews" to listOf("espnews", "espn news"),
            "espn plus" to listOf("espn plus"),
            "espn deportes" to listOf("espn deportes"),
            "fs1" to listOf("fs1", "fox sports 1"),
            "fs2" to listOf("fs2", "fox sports 2"),
            "nfl net" to listOf("nfl network", "nfl net", "nfln"),
            "nfl network" to listOf("nfl network", "nfl net", "nfln"),
            "nfln" to listOf("nfl network", "nfl net", "nfln"),
            "nba tv" to listOf("nba tv", "nbatv"),
            "nhl net" to listOf("nhl network", "nhl net"),
            "nhl network" to listOf("nhl network", "nhl net"),
            "mlb net" to listOf("mlb network", "mlb net"),
            "mlb network" to listOf("mlb network", "mlb net"),
            "tnt" to listOf("tnt"),
            "tbs" to listOf("tbs"),
            "trutv" to listOf("trutv", "tru tv"),
            "usa net" to listOf("usa network", "usa net"),
            "usa network" to listOf("usa network", "usa net"),
            "cbssn" to listOf("cbs sports network", "cbssn", "cbs sports net"),
            "cbs sports network" to listOf("cbs sports network", "cbssn", "cbs sports net"),
            "btn" to listOf("big ten network", "btn", "big ten"),
            "big ten network" to listOf("big ten network", "btn", "big ten"),
            "secn" to listOf("sec network", "secn"),
            "sec network" to listOf("sec network", "secn"),
            "accn" to listOf("acc network", "accn"),
            "acc network" to listOf("acc network", "accn"),
            "golf" to listOf("golf channel", "golf"),
            "golf channel" to listOf("golf channel", "golf"),
            "tennis channel" to listOf("tennis channel"),
            "fox deportes" to listOf("fox deportes"),
            "tudn" to listOf("tudn"),
            "univision" to listOf("univision"),
            "telemundo" to listOf("telemundo"),
            "universo" to listOf("universo"),
            "peacock" to listOf("peacock"),
            "prime video" to listOf("prime video", "amazon prime"),
            "paramount plus" to listOf("paramount plus", "paramount"),
            "apple tv" to listOf("apple tv", "mls season pass"),
            "netflix" to listOf("netflix"),
            "bein sports" to listOf("bein sports", "bein"),
            "max" to listOf("max", "hbo max")
        )

        /** Channel-name words that rule a channel out for that network (FOX News isn't FOX). */
        private val NETWORK_NOT: Map<String, List<String>> = mapOf(
            "fox" to listOf("news", "business", "deportes", "sports 1", "sports 2", "fs1", "fs2", "soccer", "weather", "life", "kids", "movies", "classics", "comedy", "nation", "family", "crime", "reality"),
            "cbs" to listOf("news", "sports", "cbssn", "golazo", "reality", "drama", "action"),
            "nbc" to listOf("news", "nbcsn", "sports", "universo", "golf"),
            "abc" to listOf("news", "family", "kids", "me", "australia"),
            "espn" to listOf("2", "u", "news", "deportes", "classic", "plus", "espn2", "espnu", "espnews"),
            "tnt" to listOf("sports 1", "sports 2", "sports 3", "sports 4", "sports 5", "drama", "series", "film", "comedy"),
            "tbs" to listOf("news"),
            "max" to listOf("cinemax", "maximum", "maxx", "movies"),
            "golf" to listOf("tv"),
            "paramount plus" to listOf("network", "comedy")
        )

        fun nameQuality(name: String): String? {
            val n = norm(name)
            return when {
                listOf("4k", "uhd", "2160", "2160p").any { hasWord(n, it) } -> "4K"
                listOf("fhd", "1080", "1080p", "1080i").any { hasWord(n, it) } -> "FHD"
                listOf("hd", "720", "720p").any { hasWord(n, it) } -> "HD"
                listOf("sd", "480", "576").any { hasWord(n, it) } -> "SD"
                else -> null
            }
        }

        fun qualityRank(q: String?): Int = when (q) {
            "4K" -> 4; "FHD" -> 3; "HD" -> 2; "SD" -> 1; else -> 2
        }

        /** Your own region's words, as channel names and groups use them ("UK: TNT Sports 1", group "UK | Sports"). */
        private val REGION_WORDS = mapOf(
            "UK" to listOf("uk", "gb", "united kingdom", "britain", "england", "scotland"),
            "IE" to listOf("ie", "ireland", "irish", "irl", "eir"),
            "US" to listOf("us", "usa", "united states"),
            "CA" to listOf("ca", "canada", "can"),
            "AU" to listOf("au", "aus", "australia", "nz", "new zealand")
        )

        /**
         * How much a channel is preferred for any game: 2 in a group you chose (Sports settings),
         * 1 from your own region (its group or the start of its name says so), else 0.
         */
        /** Words that mark a channel's language, at the start of its name or in its group ("ES: ESPN", "Deportes | Spanish"). */
        private val LANGUAGE_WORDS = mapOf(
            "es" to listOf("es", "esp", "spain", "spanish", "espanol", "latino", "latin", "latam", "mx", "mexico", "argentina", "deportes"),
            "fr" to listOf("fr", "fra", "france", "french", "francais"),
            "de" to listOf("de", "ger", "germany", "german", "deutsch", "deutschland"),
            "it" to listOf("it", "ita", "italy", "italia", "italian", "italiano"),
            "pt" to listOf("pt", "por", "portugal", "portuguese", "br", "brasil", "brazil"),
            "nl" to listOf("nl", "netherlands", "dutch", "nederland"),
            "pl" to listOf("pl", "poland", "polish", "polska"),
            "tr" to listOf("tr", "turkey", "turkish", "turkiye"),
            "ar" to listOf("arabic", "arab", "mena"),
            "ru" to listOf("ru", "russia", "russian")
        )
        private val LANGUAGE_TAG = Regex("[\\[(]([A-Za-z]{2,3})[\\])]")

        /** The language a channel's name or group says it's in, if any. */
        private fun channelLanguage(ch: LiveChannel): String? {
            val prefix = if (ch.name.contains(':') || ch.name.contains('|'))
                norm(ch.name.substringBefore(':').substringBefore('|').take(16)) else " "
            val tags = norm(LANGUAGE_TAG.findAll(ch.name).joinToString(" ") { it.groupValues[1] })
            val group = norm(ch.group.substringBefore('|').substringBefore(':'))
            return LANGUAGE_WORDS.entries.firstOrNull { (_, w) ->
                w.any { hasWord(prefix, it) || hasWord(tags, it) || (it.length > 3 && hasWord(group, it)) }
            }?.key
        }

        fun preferenceRank(settings: SportsSettings): (LiveChannel) -> Int {
            val groups = settings.preferredGroups.map { it.lowercase().trim() }.toSet()
            val words = REGION_WORDS[SportsCatalog.regionOf(settings)].orEmpty()
            // Channels in another language than the TV's go below the rest (a Spanish feed of the
            // game isn't what most English speakers want first). Your own groups still come first.
            val myLanguage = java.util.Locale.getDefault().language.lowercase()
            // Sorting compares a channel repeatedly, often across many fixtures in one pass.
            val ranks = HashMap<Pair<String, String>, Int>()
            return { ch -> ranks.getOrPut(ch.name to ch.group) {
                when {
                    groups.isNotEmpty() && ch.group.lowercase().trim() in groups -> 2
                    channelLanguage(ch)?.let { it != myLanguage } == true -> -1
                    words.isNotEmpty() && (
                        norm(ch.group).let { g -> words.any { hasWord(g, it) } } ||
                            norm(ch.name.substringBefore(':').substringBefore('|').take(16)).let { n ->
                                (ch.name.contains(':') || ch.name.contains('|')) && words.any { hasWord(n, it) }
                            }
                        ) -> 1
                    else -> 0
                }
            } }
        }

        // Keep strong matches across broadcaster variants; the event page scrolls.
        // Only weaker suggestions need a display limit.
        private fun List<ChannelMatch>.limitSuggestions(): List<ChannelMatch> =
            filterIndexed { index, match -> index < 8 || match.sure }

        fun sortMatches(list: List<ChannelMatch>, bestFirst: Boolean, rank: (LiveChannel) -> Int = { 0 }): List<ChannelMatch> {
            fun band(score: Int) = when {
                score >= 150 -> 3
                score >= 80 -> 2
                score >= 55 -> 1
                else -> 0
            }
            // Your own picks first; then your groups and region; then how sure and how good.
            return if (bestFirst) list.sortedWith(
                compareByDescending<ChannelMatch> { if (band(it.score) == 3) 1 else 0 }
                    .thenByDescending { rank(it.channel) }
                    .thenByDescending { band(it.score) }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenByDescending { it.score }
                    .thenBy { it.channel.name.length }
            ) else list.sortedWith(
                compareByDescending<ChannelMatch> { if (band(it.score) == 3) 1 else 0 }
                    .thenByDescending { rank(it.channel) }
                    .thenByDescending { it.score }
                    .thenBy { it.channel.number }
            )
        }
    }
}

/** The key a team's own channel is saved under: the same club or country in every competition. */
internal fun teamChannelKey(leagueId: String, teamId: String): String = SportsCatalog.teamFamily(leagueId) + "|" + teamId
