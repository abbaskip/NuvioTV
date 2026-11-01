package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IptvVodStreamsTest {
    private val profileId = MutableStateFlow(1)
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns profileId }
    private val repository = mockk<OnDemandRepository>(relaxed = true)
    private val metadata = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
    private val tmdb = mockk<com.nuvio.tv.core.tmdb.TmdbService> { every { apiKey() } returns "synthetic-application-key" }
    private val loader = IptvVodStreams(repository, metadata, tmdb, mockk(relaxed = true), profiles)
    private val context = TvdbPlaybackContext(73244, "alternate", 300, 5, 3, listOf(200), "tt0386676:5:3")

    @Test fun `selected coordinates remain separate from verified catalogue episode ids`() = runBlocking {
        coEvery { tmdb.ensureTmdbId("tt0386676", "series") } returns "2316"
        val selection = StreamContentContext("tt0386676", "The Office", 2005, "Business Ethics", context)
        val target = loader.prepare("series", "tt0386676:5:3", 5, 3, selection)!!
        assertEquals(setOf(200L), target.tvdbEpisodeIds)
        assertEquals(300L, target.selectedTvdbEpisodeId)
        assertEquals(setOf("Business Ethics"), target.episodeTitles)
        assertEquals("tt0386676:5:3", selection.tvdb!!.requestVideoId)
        coVerify(exactly = 0) { metadata.getProviderMeta(any(), any()) }
    }
    @Test fun `Provider Default uses existing content metadata without a TVDB selection`() = runBlocking {
        coEvery { tmdb.ensureTmdbId("tt0303461", "series") } returns "1437"
        val target = loader.prepare("series", "tt0303461:1:1", 1, 1,
            StreamContentContext("tt0303461", "Firefly", 2002, "Serenity"))!!
        assertTrue(target.tvdbEpisodeIds.isEmpty())
        assertNull(target.selectedTvdbEpisodeId)
        assertEquals(setOf("Serenity"), target.episodeTitles)
        coVerify(exactly = 0) { metadata.getProviderMeta(any(), any()) }
    }
    @Test fun `combined targets do not inherit the titles of their individual parts`() = runBlocking {
        coEvery { tmdb.ensureTmdbId("tt0386676", "series") } returns "2316"
        val combined = context.copy(episodeIds = listOf(14, 15), providerSources = listOf(
            EpisodeSourceIdentity("tt0386676:5:14", EpisodeCoordinates(5, 14), listOf(14), "Lecture Circuit (1)"),
            EpisodeSourceIdentity("tt0386676:5:15", EpisodeCoordinates(5, 15), listOf(15), "Lecture Circuit (2)")))
        val target = loader.prepare("series", "tt0386676:5:16", 5, 16,
            StreamContentContext("tt0386676", "The Office", 2005, "Lecture Circuit", combined))!!
        assertEquals(setOf("Lecture Circuit"), target.episodeTitles)
    }
    @Test fun `a profile change prevents a pending catalogue result from reaching the picker`() = runBlocking {
        val target = VodStreamTarget(VodKind.MOVIE, "1", "Synthetic", 2020)
        coEvery { repository.integratedSources(target, "one") } coAnswers {
            profileId.value = 2
            listOf(OnDemandRepository.OnDemandSource("Synthetic", "https://media.example.test/movie.mp4", emptyMap(), playlistId = "one"))
        }
        assertTrue(loader.load("iptv-playlist:one", "IPTV · One", target, 1).isEmpty())
    }
    @Test fun `verified results use the normal Stream model with a playlist-specific binge identity`() = runBlocking {
        val target = VodStreamTarget(VodKind.MOVIE, "1", "Synthetic", 2020)
        coEvery { repository.integratedSources(target, "one") } returns listOf(OnDemandRepository.OnDemandSource(
            "Synthetic", "https://media.example.test/movie.mp4", mapOf("User-Agent" to "Test player"), playlistId = "one"))
        val stream = loader.load("iptv-playlist:one", "IPTV · One", target, 1).single()
        assertEquals("one", stream.iptvPlaylistId)
        assertEquals("iptv-playlist:one", stream.behaviorHints!!.bingeGroup)
        assertEquals("IPTV · One", stream.addonName)
        assertEquals("Test player", stream.behaviorHints!!.proxyHeaders!!.request!!["User-Agent"])
    }
    @Test fun `TVDB parent ids obtain existing provider metadata before catalogue matching`() = runBlocking {
        val meta = mockk<Meta>(relaxed = true) { every { imdbId } returns "tt0386676" }
        every { metadata.getProviderMeta("series", "tvdb:73244") } returns kotlinx.coroutines.flow.flowOf(com.nuvio.tv.core.network.NetworkResult.Success(meta))
        coEvery { tmdb.ensureTmdbId("tt0386676", "series") } returns "2316"
        val target = loader.prepare("series", "tvdb:73244:5:18", 5, 18,
            StreamContentContext("tvdb:73244", "The Office (US)", 2005, "Golden Ticket (Extended Cut)"))!!
        assertEquals("2316", target.tmdbId)
        assertEquals("tt0386676", target.imdbId)
        assertEquals(setOf("Golden Ticket (Extended Cut)"), target.episodeTitles)
    }

    @Test fun `a missing optional TMDB key retains strict title year matching without an unauthorized request`() = runBlocking {
        every { tmdb.apiKey() } returns ""
        val target = loader.prepare("series", "tt0386676:5:18", 5, 18,
            StreamContentContext("tt0386676", "The Office (US)", 2005, "Golden Ticket (Extended Cut)"))!!
        assertNull(target.tmdbId)
        assertEquals("tt0386676", target.imdbId)
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any(), any()) }
    }
    @Test fun `missing alternate episode titles cannot inherit a different native episode at the same coordinates`() = runBlocking {
        val video = mockk<Video>(relaxed = true) {
            every { id } returns "tt0386676:5:18"
            every { season } returns 5; every { episode } returns 18
            every { title } returns "New Boss"
        }
        val meta = mockk<Meta>(relaxed = true) {
            every { imdbId } returns "tt0386676"; every { videos } returns listOf(video)
        }
        every { metadata.getProviderMeta("series", "tt0386676") } returns kotlinx.coroutines.flow.flowOf(com.nuvio.tv.core.network.NetworkResult.Success(meta))
        coEvery { tmdb.ensureTmdbId("tt0386676", "series") } returns "2316"
        val target = loader.prepare("series", "tt0386676:5:18", 5, 18,
            StreamContentContext("tt0386676", "The Office (US)", 2005, null, context.copy(season = 5, episode = 18)))!!
        assertTrue(target.episodeTitles.isEmpty())
    }

}
