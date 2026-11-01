package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class SportsGuideCacheTest {
    @org.junit.Rule @JvmField val temp = org.junit.rules.TemporaryFolder()

    @Test fun `explicit MotoGP names outrank shared Grand Prix wording`() {
        val db = mockk<EpgDatabase>()
        every { db.fingerprint() } returns "motorsport"
        every { db.contentRevision() } returns "1"
        val start = 1_793_318_400_000L
        val titles = listOf("Live: MotoGP : Malaysian Grand Prix: MotoGP Free Practice 2 and Qualifying",
            "Live: Malaysian Grand Prix", "Live: Formula 1: Singapore Grand Prix", "Live: Grand Prix")
        every { db.visitPrograms(any(), any(), any()) } answers {
            titles.forEachIndexed { i, title -> thirdArg<(String, EpgProgram) -> Unit>().invoke("channel",
                EpgProgram(start + i * 3_600_000L, start + (i + 1) * 3_600_000L, title,
                    when (i) { 0 -> "Formula 1 news follows MotoGP coverage"; 1 -> "MotoGP qualifying coverage"; else -> null })) }
        }
        val channel = LiveChannel("channel", "playlist", "Test", "TNT Sports 2", null, null, null, "sports", "Sports", 1, "https://example.invalid")
        val found = SportsMatcher(db).guideEvents(start, start + 86_400_000, SportsCatalog.leagues,
            emptyList(), mapOf("channel" to channel), 0, start).sortedBy { it.startMs }
        assertEquals(listOf("guide/motogp", "guide/motogp", "racing/f1"), found.map { it.leagueId })
        val onlyF1 = SportsMatcher(db).guideEvents(start, start + 86_400_000,
            SportsCatalog.leagues.filter { it.id == "racing/f1" }, emptyList(), mapOf("channel" to channel), 0, start)
        assertEquals(listOf("Live: Formula 1: Singapore Grand Prix"),
            onlyF1.sortedBy { it.startMs }.map { "Live: " + it.title })
    }

    @Test fun `Grand Prix alone does not identify Formula One but explicit title or description does`() {
        val db = mockk<EpgDatabase>()
        every { db.fingerprint() } returns "grand-prix"
        every { db.contentRevision() } returns "1"
        val start = 1_793_318_400_000L
        val programmes = listOf(
            "Live: ISU Grand Prix of Figure Skating" to "Skate Canada International - Men's free skate",
            "Live: Grand Prix" to "World Judo Tour coverage",
            "Live: FIM Speedway Grand Prix" to "Coverage of speedway racing",
            "Live: MotoGP: Malaysian Grand Prix" to "Motorcycle racing",
            "Live: Grand Prix" to null,
            "Live: F1: British Grand Prix" to null,
            "Live: Formula One: British Grand Prix" to null,
            "Live: British Grand Prix" to "Formula 1 race coverage from Silverstone")
        every { db.visitPrograms(any(), any(), any()) } answers {
            programmes.forEachIndexed { i, (title, description) ->
                thirdArg<(String, EpgProgram) -> Unit>().invoke("channel",
                    EpgProgram(start + i * 3_600_000L, start + (i + 1) * 3_600_000L, title, description))
            }
        }
        val channel = LiveChannel("channel", "playlist", "Test", "Sports", null, null, null,
            "sports", "Sports", 1, "https://example.invalid")
        val cache = temp.newFolder()
        val legacy = SportsCatalog.leagues.map { league ->
            if (league.id == "racing/f1") league.copy(guideWords = league.guideWords + "Grand Prix") else league
        }
        SportsMatcher(db, cache).guideEvents(start, start + 86_400_000, legacy,
            emptyList(), mapOf("channel" to channel), 0, start)
        for (leagues in listOf(SportsCatalog.leagues, SportsCatalog.leagues.filter { it.id == "racing/f1" })) {
            val f1 = SportsMatcher(db, cache).guideEvents(start, start + 86_400_000, leagues,
                emptyList(), mapOf("channel" to channel), 0, start).filter { it.leagueId == "racing/f1" }
            assertEquals(programmes.takeLast(3).map { SportsMatcher.cleanTitle(it.first) },
                f1.sortedBy { it.startMs }.map { it.title })
        }
        verify(exactly = 3) { db.visitPrograms(any(), any(), any()) }
    }

    @Test fun `completed discovery survives process restart but guide channels and settings invalidate it`() {
        val db = mockk<EpgDatabase>()
        var revision = "committed"
        every { db.contentRevision() } answers { revision }
        every { db.fingerprint() } returns "same-guide"
        val start = 1_793_318_400_000L
        val programme = EpgProgram(start + 60_000, start + 3_600_000, "Live: NRL: Broncos vs Dolphins",
            "Rugby league coverage", "Sport", "round 1", "https://example.invalid/logo", 2026, listOf("Presenter"))
        every { db.visitPrograms(any(), any(), any()) } answers {
            thirdArg<(String, EpgProgram) -> Unit>().invoke("channel", programme)
        }
        val dir = temp.newFolder()
        val channel = LiveChannel("channel", "playlist", "Test", "AU: 7Mate", null, null, null, "local", "AU Local", 1, "https://example.invalid")
        fun scan(ch: LiveChannel = channel, shift: Long = 0, words: List<String> = emptyList(), now: Long = start) =
            SportsMatcher(db, dir).guideEvents(start, start + 86_400_000, SportsTaxonomy.leagues, words,
                mapOf("channel" to ch), shift, now)
        val original = scan()
        assertEquals(programme, original.single().guideHits.single().second)
        assertEquals(original, scan())
        assertEquals(GameState.POST, scan(now = start + 7_200_000).single().state)
        verify(exactly = 1) { db.visitPrograms(any(), any(), any()) }
        scan(ch = channel.copy(name = "AU: Seven HD"))
        scan(shift = 60_000)
        scan(words = listOf("Broncos"))
        revision = "new-guide"
        scan()
        verify(exactly = 5) { db.visitPrograms(any(), any(), any()) }
        dir.listFiles()!!.single { it.name.endsWith(".gz") }.writeText("incomplete")
        scan()
        verify(exactly = 6) { db.visitPrograms(any(), any(), any()) }
    }

    @Test fun `guide mutation during discovery and cancelled scans never persist partial results`() {
        val db = mockk<EpgDatabase>()
        every { db.fingerprint() } returns "guide"
        var revision = "before"
        every { db.contentRevision() } answers { revision }
        every { db.visitPrograms(any(), any(), any()) } answers { revision = "after" }
        val dir = temp.newFolder()
        SportsMatcher(db, dir).guideEvents(0, 86_400_000, emptyList(), emptyList(), emptyMap(), 0, 0)
        assertTrue(dir.listFiles()!!.isEmpty())
        every { db.visitPrograms(any(), any(), any()) } answers { }
        SportsMatcher(db, dir).guideEvents(0, 86_400_000, emptyList(), emptyList(), emptyMap(), 0, 0)
        assertEquals(1, dir.listFiles()!!.size)
        SportsMatcher(db, dir).guideEvents(0, 86_400_000, emptyList(), emptyList(), emptyMap(), 0, 0)
        verify(exactly = 2) { db.visitPrograms(any(), any(), any()) }
    }

    @Test fun `cache digest is stable and distinguishes field boundaries`() {
        assertEquals(SportsGuideCache.digest(listOf("a", "bc")), SportsGuideCache.digest(listOf("a", "bc")))
        assertNotEquals(SportsGuideCache.digest(listOf("a", "bc")), SportsGuideCache.digest(listOf("ab", "c")))
    }

    @Test fun `guide scans reuse unchanged data update on-air state and invalidate on guide or vocabulary change`() {
        val db = mockk<EpgDatabase>()
        every { db.contentRevision() } returns null
        var fingerprint = "one"
        every { db.fingerprint() } answers { fingerprint }
        val start = 1_793_318_400_000L
        val programme = EpgProgram(start + 60_000, start + 3_600_000, "Live: NRL: Broncos vs Dolphins")
        every { db.visitPrograms(any(), any(), any()) } answers {
            thirdArg<(String, EpgProgram) -> Unit>().invoke("channel", programme)
        }
        val channel = LiveChannel("channel", "playlist", "Test", "Fox Sports 501", null, null, null, "sports", "Sports", 1, "https://example.invalid")
        val matcher = SportsMatcher(db)
        fun scan(now: Long, words: List<String> = emptyList()) = matcher.guideEvents(start, start + 24 * 3_600_000,
            SportsTaxonomy.leagues, words, mapOf("channel" to channel), 0, now)
        val first = scan(start)
        assertTrue(first.isNotEmpty())
        assertEquals(GameState.PRE, first.single().state)
        assertEquals(GameState.LIVE, scan(start + 120_000).single().state)
        verify(exactly = 1) { db.visitPrograms(any(), any(), any()) }
        fingerprint = "two"
        scan(start + 130_000)
        verify(exactly = 2) { db.visitPrograms(any(), any(), any()) }
        scan(start + 140_000, listOf("Broncos"))
        verify(exactly = 3) { db.visitPrograms(any(), any(), any()) }
        scan(start + 9 * 60_000, listOf("Broncos"))
        verify(exactly = 3) { db.visitPrograms(any(), any(), any()) }
        assertEquals(GameState.POST, scan(start + 2 * 3_600_000, listOf("Broncos")).single().state)
        verify(exactly = 3) { db.visitPrograms(any(), any(), any()) }
        every { db.contentRevision() } returns "catch-up-update"
        scan(start + 2 * 3_600_000, listOf("Broncos"))
        verify(exactly = 4) { db.visitPrograms(any(), any(), any()) }
        every { db.contentRevision() } returns null
        fingerprint = ""
        scan(start + 2 * 3_600_000, listOf("Broncos"))
        verify(exactly = 5) { db.visitPrograms(any(), any(), any()) }
    }

    @Test fun `superseded guide scan cancels before finishing and never caches a partial day`() {
        val db = mockk<EpgDatabase>()
        every { db.contentRevision() } returns null
        every { db.fingerprint() } returns "guide"
        val start = 1_793_318_400_000L
        val programme = EpgProgram(start, start + 3_600_000, "Live: NRL: Broncos vs Dolphins")
        var visited = 0
        every { db.visitPrograms(any(), any(), any()) } answers {
            repeat(300) {
                visited++
                thirdArg<(String, EpgProgram) -> Unit>().invoke("channel", programme)
            }
        }
        val channel = LiveChannel("channel", "playlist", "Test", "Sports", null, null, null,
            "sports", "Sports", 1, "https://example.invalid")
        val matcher = SportsMatcher(db)
        val error = runCatching {
            matcher.guideEvents(start, start + 24 * 3_600_000, SportsTaxonomy.leagues, emptyList(),
                mapOf("channel" to channel), 0, start) { throw java.util.concurrent.CancellationException() }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(256, visited)
        assertTrue(matcher.guideEvents(start, start + 24 * 3_600_000, SportsTaxonomy.leagues, emptyList(),
            mapOf("channel" to channel), 0, start).isNotEmpty())
        verify(exactly = 2) { db.visitPrograms(any(), any(), any()) }
    }
    @Test fun `World Cup guide fixtures include women with participants in descriptions`() {
        val db = mockk<EpgDatabase>()
        every { db.contentRevision() } returns null
        every { db.fingerprint() } returns "world-cup"
        val start = 1_793_341_800_000L
        val programmes = listOf(
            EpgProgram(start, start + 7_500_000, "Rugby League World Cup 2026 : Women: England v Samoa", "Coverage of the Women's Rugby League World Cup."),
            EpgProgram(start + 8_100_000, start + 18_000_000, "Men's Rugby League World Cup", "Pool B: England v Papua New Guinea. Coverage of the Rugby League World Cup."))
        every { db.visitPrograms(any(), any(), any()) } answers {
            programmes.forEach { thirdArg<(String, EpgProgram) -> Unit>().invoke("channel", it) }
        }
        val channel = LiveChannel("channel", "playlist", "Test", "Premier Sports 1", null, null, null, "sports", "Sports", 1, "https://example.invalid")
        val events = SportsMatcher(db).guideEvents(start, start + 86_400_000, SportsTaxonomy.leagues,
            emptyList(), mapOf("channel" to channel), 0, start)
        assertEquals(2, events.size)
        assertTrue(events.all { SportsCatalog.league(it.leagueId)?.sport == Sport.RUGBY_LEAGUE })
        assertTrue(events.all { it.guideHits.single().first == "channel" })
    }
}
