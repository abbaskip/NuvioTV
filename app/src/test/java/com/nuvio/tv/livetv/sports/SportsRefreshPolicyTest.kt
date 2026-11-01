package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsRefreshPolicyTest {
    private val now = 1_793_318_400_000L
    private val leagues = (1..5).map { League("soccer/test.$it", Sport.FOOTBALL, "League$it", "L$it", "soccer/test.$it") }
    private val events = leagues.mapIndexed { index, l -> SportsEvent("espn:${l.id}:$index", l.id, now - 60_000,
        true, GameState.PRE, "A v B", null, null, null, null) }
    private fun choose(focused: String? = null, playing: Set<String> = emptySet(), followed: Set<String> = emptySet(),
                       viewing: Boolean = true, attempts: Map<String, Long> = emptyMap(), list: List<SportsEvent> = events) =
        SportsRefreshPolicy.choose(list, leagues, focused, playing, followed, viewing, now, attempts)

    @Test fun `only two relevant leagues qualify with focused watched and followed events first`() {
        val selected = choose(focused = events[3].id, followed = setOf(leagues[4].id))
        assertEquals(listOf(leagues[3], leagues[4]), selected)
        assertEquals(listOf(leagues[2]), choose(playing = setOf(events[2].id), viewing = false))
        assertTrue(choose(viewing = false).isEmpty())
    }
    @Test fun `failed or successful attempts enforce cooldown and other leagues get their turn`() {
        val attempts = mapOf(leagues[0].id to now, leagues[1].id to now)
        assertEquals(listOf(leagues[2], leagues[3]), choose(attempts = attempts))
        assertTrue(choose(attempts = leagues.associate { it.id to now }).isEmpty())
    }
    @Test fun `future stale finished postponed and unknown kickoff events never trigger polling`() {
        val variants = listOf(events[0].copy(startMs = now + 3_600_000), events[0].copy(startMs = now - 24 * 3_600_000),
            events[0].copy(state = GameState.POST), events[0].copy(state = GameState.OFF), events[0].copy(hasTime = false))
        for (event in variants) assertTrue(choose(list = listOf(event)).isEmpty())
        assertTrue(choose(list = emptyList()).isEmpty())
    }
    @Test fun `known multiday event end permits refresh without inventing a kickoff`() {
        val event = events[0].copy(hasTime = false, startMs = now - 2 * 24 * 3_600_000L, endMs = now + 24 * 3_600_000L)
        assertEquals(listOf(leagues[0]), choose(list = listOf(event)))
    }

    @Test fun `followed leagues cannot starve other live scores over repeated rounds`() {
        val attempts = mutableMapOf<String, Long>()
        val visited = mutableSetOf<String>()
        repeat(4) { round ->
            val at = now + round * 30_000L
            val due = SportsRefreshPolicy.choose(events, leagues, null, emptySet(),
                leagues.take(4).map { it.id }.toSet(), true, at, attempts)
            due.forEach { attempts[it.id] = at; visited += it.id }
        }
        assertEquals(leagues.map { it.id }.toSet(), visited)
    }

    @Test fun `focused game remains first even when other leagues have never refreshed`() {
        assertEquals(leagues[3], choose(focused = events[3].id,
            attempts = mapOf(leagues[3].id to now - 60_000)).first())
    }

    @Test fun `expired timestamps and never attempted leagues sort together without mixed numeric types`() {
        val due = choose(attempts = mapOf(leagues[0].id to now - 60_000))
        assertEquals(listOf(leagues[1], leagues[2]), due)
    }

}
