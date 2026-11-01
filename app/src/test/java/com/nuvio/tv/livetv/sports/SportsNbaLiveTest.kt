package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsNbaLiveTest {
    private val nba = SportsCatalog.league("basketball/nba")!!
    private fun fixture(name: String) = javaClass.getResource("/sports/$name.json")!!.readText()

    // Reduced public ESPN scoreboard/summary responses captured on 1 November 2026.
    @Test fun `NBA live scoreboard retains scores clock and underlying event identity`() {
        val event = SportsFeeds.parseScoreboard(nba, fixture("nba-live-scoreboard")).single()
        assertEquals(GameState.LIVE, event.state)
        assertEquals("401909898", event.espnId)
        assertEquals("espn:basketball/nba:401909898", event.id)
        assertFalse(event.away!!.score.isNullOrBlank())
        assertFalse(event.home!!.score.isNullOrBlank())
        assertFalse(event.status.isNullOrBlank())
        val now = event.startMs + 60_000
        assertEquals(listOf(nba), SportsRefreshPolicy.choose(listOf(event), listOf(nba), null,
            emptySet(), emptySet(), true, now, emptyMap()))
    }

    @Test fun `NBA summary exposes player leaders team stats and scoring plays from the same response`() {
        val event = SportsFeeds.parseScoreboard(nba, fixture("nba-live-scoreboard")).single()
        val summary = JSONObject(fixture("nba-live-summary"))
        val extras = SportsExtras.parse(summary, event, Sport.BASKETBALL)
        assertTrue(extras.stats.any { it.label.contains("ASSIST") })
        assertTrue(extras.performers.any { it.category == "POINTS" && it.name.isNotBlank() && it.line.isNotBlank() })
        val plays = SportsFeeds.parseSummary(summary)
        assertEquals(2, plays.size)
        assertTrue(plays.all { !it.teamAbbr.isNullOrBlank() && !it.awayScore.isNullOrBlank() && !it.homeScore.isNullOrBlank() })
    }
}
