package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class SportsReplayMatcherTest {
    private val now = 1_793_318_400_000L
    private val start = now - 2 * 24 * 3_600_000L
    private val league = "football/nfl"
    private fun side(id: String, name: String, home: Boolean) = Side(TeamInfo(league, id, name, name, id, null, null, null, null), "7", null, false, home)
    private val event = SportsEvent("espn:football/nfl:1", league, start, true, GameState.POST,
        "Seattle Seahawks at Arizona Cardinals", null, "Final", side("SEA", "Seattle Seahawks", false),
        side("ARI", "Arizona Cardinals", true), broadcasts = listOf("ESPN2"), source = EventSource.ESPN)
    private fun channel(key: String, name: String, days: Int? = 7) = LiveChannel(key, "playlist", "Test", name, null, null, null,
        "sports", "Sports", 1, "https://example.invalid/live/1", catchup = days?.let { CatchupInfo("xc", null, it) })
    private fun match(channels: List<LiveChannel>, programmes: List<Pair<String, EpgProgram>> = emptyList(),
                      settings: SportsSettings = SportsSettings(), replay: Boolean = true, fixture: SportsEvent = event, assignments: Map<String, EpgAssignment> = emptyMap()): List<ChannelMatch> {
        val db = mockk<EpgDatabase>()
        every { db.findByWords(any(), any(), any(), any(), any(), any()) } returns programmes
        every { db.range(any(), any(), any(), any(), any()) } returns emptyMap()
        return SportsMatcher(db).match(SportsMatcher.Input(listOf(fixture), channels, emptyMap(), 0, settings, now, replay, guideAssignments = assignments))
            .matches[fixture.id].orEmpty()
    }
    @Test fun `event locations cannot be assembled from unrelated opponents but coherent names retain all feeds`() {
        val cases = listOf(
            Triple("racing/f1", "Mexico City Grand Prix", "Rapid City Rush vs New Mexico Goatheads"),
            Triple("golf/pga", "South Carolina Championship", "South Sydney vs Carolina"))
        for ((id, title, unrelated) in cases) for (timed in listOf(true, false)) {
            val fixture = SportsEvent("fixture", id, now, timed, GameState.LIVE, title, null, null,
                null, null, endMs = now + 7_200_000)
            val channels = listOf(channel("bad", "Flo Hockey: Rapid City Rush vs New Mexico Goatheads"), channel("valid-au", "AU Sports HD"),
                channel("valid-uk", "UK Sports UHD"), channel("qualified", "Other provider"))
            val programme = { text: String -> EpgProgram(now, now + 7_200_000, text) }
            val leagueName = SportsCatalog.league(id)!!.name
            val rows = listOf("bad" to programme(unrelated), "valid-au" to programme(title),
                "valid-uk" to programme(title), "qualified" to programme("$leagueName: $title"))
            val result = match(channels, rows, replay = false, fixture = fixture)
            assertEquals("$title / timed=$timed", setOf("valid-au", "valid-uk", "qualified"),
                result.map { it.channel.key }.toSet())
        }
    }

    @Test fun `quick event channel matching also requires coherent location identity without a competition label`() {
        val fixture = SportsEvent("fixture", "racing/f1", now, false, GameState.LIVE,
            "Mexico City Grand Prix", null, null, null, null, endMs = now + 7_200_000)
        val channels = listOf(channel("bad", "FloHockey: Rapid City Rush vs New Mexico Goatheads"),
            channel("au", "AU: Mexico City GP HD"), channel("uk", "UK: Mexico City Grand Prix UHD"),
            channel("dedicated", "UK: Sky Sports F1 HD"))
        val result = SportsMatcher(mockk()).match(SportsMatcher.Input(listOf(fixture), channels,
            emptyMap(), 0, SportsSettings(), now, includeGuide = false)).matches[fixture.id].orEmpty()
        assertEquals(setOf("au", "uk", "dedicated"), result.map { it.channel.key }.toSet())
    }

    @Test fun `older event can offer an archive broadcaster without retained guide rows`() {
        val channels = match(listOf(channel("archive", "AU: ESPN2 HD")))
        assertEquals(listOf("archive"), channels.map { it.channel.key })
        assertTrue(channels.single().catchup)
        assertFalse(channels.single().sure)
        assertTrue(match(listOf(channel("archive", "AU: ESPN2 HD")), replay = false).isEmpty())
    }
    @Test fun `both participant names identify an archive event channel`() {
        val match = match(listOf(channel("event", "Seattle Seahawks vs Arizona Cardinals HD"))).single()
        assertTrue(match.catchup)
        assertTrue(match.sure)
    }
    @Test fun `retained guide names both teams and confirms the replay channel`() {
        val p = EpgProgram(start, start + 3 * 3_600_000, "NFL Football", description = "Seattle Seahawks at Arizona Cardinals")
        val match = match(listOf(channel("epg", "Sports Extra HD")), listOf("epg" to p)).single()
        assertTrue(match.catchup)
        assertTrue(match.sure)
        assertEquals(start, match.program?.startMs)
    }
    @Test fun `expired and absent archives remain labelled candidates but blocked channels are excluded`() {
        val channels = listOf(channel("expired", "AU: ESPN2 HD", 1), channel("none", "AU: ESPN2 HD", null), channel("blocked", "AU: ESPN2 HD"))
        val matches = match(channels, settings = SportsSettings(blocked = setOf("${event.id}|blocked")))
        assertEquals(setOf("expired", "none"), matches.map { it.channel.key }.toSet())
        assertTrue(matches.none { it.catchup })
    }
    @Test fun `manual replay choice is preserved but cannot bypass archive eligibility`() {
        val selected = channel("manual", "My Sports Channel")
        val settings = SportsSettings(eventChannels = mapOf(event.id to "manual|$start"))
        assertEquals("manual", match(listOf(selected), settings = settings).single().channel.key)
        assertFalse(match(listOf(selected.copy(catchup = null)), settings = settings).single().catchup)
    }
    private fun lakersClippers(): SportsEvent {
        val id = "basketball/nba"
        fun team(key: String, nick: String, full: String, home: Boolean) = Side(
            TeamInfo(id, key, nick, full, key, "Los Angeles", null, null, null), null, null, false, home)
        return SportsEvent("espn:basketball/nba:401909892", id, now - 20 * 3_600_000L, true, GameState.POST,
            "Los Angeles Lakers at LA Clippers", null, "Final", team("LAL", "Lakers", "Los Angeles Lakers", false),
            team("LAC", "Clippers", "LA Clippers", true), broadcasts = listOf("KTLA"))
    }
    @Test fun `Lakers and Clippers dedicated channels remain suggestions without inventing archive support`() {
        val candidates = listOf(channel("lakers", "US: NBA LOS ANGELES LAKERS FHD", null),
            channel("clippers", "US: NBA LOS ANGELES CLIPPERS FHD", null),
            channel("city", "US: NBA LOS ANGELES FHD", null), channel("other", "US: NBA BOSTON CELTICS FHD", null),
            channel("radio", "Radio: Los Angeles Lakers", null))
        val matches = match(candidates, fixture = lakersClippers())
        assertEquals(setOf("lakers", "clippers", "radio"), matches.map { it.channel.key }.toSet())
        assertTrue(matches.none { it.sure || it.catchup })
    }
    @Test fun `historical CW guide row can confirm the game and independently enable replay`() {
        val fixture = lakersClippers()
        val p = EpgProgram(fixture.startMs, fixture.startMs + 3 * 3_600_000L, "NBA Basketball",
            description = "Los Angeles Lakers at LA Clippers")
        val matches = match(listOf(channel("cw", "US: CW West"), channel("lakers", "US: NBA LOS ANGELES LAKERS FHD", null)),
            programmes = listOf("cw" to p), fixture = fixture)
        assertTrue(matches.first { it.channel.key == "cw" }.let { it.sure && it.catchup })
        assertFalse(matches.first { it.channel.key == "lakers" }.catchup)
    }


    @Test fun `station guide aliases offer replay without claiming a confirmed broadcast`() {
        val fixture = lakersClippers()
        val assignment = EpgAssignment("guide", "station")
        val matches = match(listOf(channel("station", "US: Los Angeles CW KTLA", null), channel("cw", "US: CW West"),
            channel("other", "US: CW East")), fixture = fixture,
            assignments = mapOf("station" to assignment, "cw" to assignment, "other" to EpgAssignment("another-guide", "station")))
        assertEquals(setOf("station", "cw"), matches.map { it.channel.key }.toSet())
        assertTrue(matches.first { it.channel.key == "cw" }.catchup)
        assertTrue(matches.none { it.sure })
    }
    @Test fun `verified paired team abbreviations with league context match the guide`() {
        val fixture = lakersClippers()
        val programme = EpgProgram(fixture.startMs, fixture.startMs + 3 * 3_600_000L, "NBA Basketball: LAL vs LAC")
        val match = match(listOf(channel("cw", "US: CW West")), listOf("cw" to programme), fixture = fixture).single()
        assertTrue(match.sure && match.catchup)
    }

    @Test fun `manual guide reassignment and explicit unassignment do not inherit old station matches`() {
        val fixture = lakersClippers()
        val station = EpgAssignment("guide", "ktla")
        val automatic = mapOf("station" to station, "cw" to station)
        val channels = listOf(channel("station", "US: Los Angeles CW KTLA", null), channel("cw", "US: CW West"))
        for (override in listOf(EpgAssignment.None, EpgAssignment("guide", "another-station"))) {
            val effective = automatic + ("cw" to override)
            assertEquals(listOf("station"), match(channels, fixture = fixture, assignments = effective).map { it.channel.key })
        }
    }
}
