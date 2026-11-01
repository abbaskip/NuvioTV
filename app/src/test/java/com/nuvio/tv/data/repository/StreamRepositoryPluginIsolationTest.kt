package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class StreamRepositoryPluginIsolationTest {
    @Test
    fun `addon results arrive while plugin TMDB lookup is pending`() = runBlocking {
        val harness = newHarness(listOf(compatibleScraper()))
        val tmdbResult = CompletableDeferred<String?>()
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } coAnswers {
            tmdbResult.await()
        }

        val result = withTimeout(1_000) {
            harness.repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1341338",
                season = null,
                episode = null
            ).first { it is NetworkResult.Success }
        }

        val groups = (result as NetworkResult.Success).data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(1, groups.single().streams.size)
        assertTrue(tmdbResult.isActive)
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
        tmdbResult.complete(null)
        Unit
    }

    @Test
    fun `TMDB lookup is skipped when no compatible plugin is enabled`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        assertTrue(results.last() is NetworkResult.Success)
        coVerify(exactly = 0) { harness.tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
    }

    /**
     * The stream path must read addon display info from the installed [Addon] it was given, not
     * refetch the manifest. fetchAddon is deliberately left stubbed on the harness so this asserts
     * the call is available and still not made, rather than merely un-stubbed.
     */
    @Test
    fun `stream lookup issues no manifest request`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        assertEquals(listOf("Fast Addon"), success.data.map { it.addonName })
        // Pins the URL too: the regression is not merely "one call happened", it is that the
        // stream request is the only request, built from the addon's own baseUrl.
        coVerify(exactly = 1) { harness.api.getStreams("https://addon.example/stream/movie/tt1341338.json") }
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    @Test
    fun `streams carry the installed addon name and logo`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        val groups = success.data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(listOf(ADDON_LOGO), groups.map { it.addonLogo })
        assertTrue(groups.single().streams.all { it.addonName == "Fast Addon" })
        assertTrue(groups.single().streams.all { it.addonLogo == ADDON_LOGO })
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    @Test
    fun `repository grouping preserves each plugin provider name`() = runTest {
        assertPluginProviderNames(groupByRepository = true)
    }

    @Test
    fun `ungrouped plugins retain their existing group names`() = runTest {
        assertPluginProviderNames(groupByRepository = false)
    }

    private suspend fun assertPluginProviderNames(groupByRepository: Boolean) {
        val scrapers = listOf(
            compatibleScraper().copy(id = "first", name = "First provider"),
            compatibleScraper().copy(id = "second", name = "Second provider")
        )
        val harness = newHarness(scrapers)
        every { harness.pluginManager.groupStreamsByRepository } returns flowOf(groupByRepository)
        every { harness.pluginManager.repositories } returns flowOf(
            listOf(PluginRepository(id = "repo", name = "Repository", url = "https://repo.example"))
        )
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } returns "123"
        every { harness.pluginManager.executeScrapersStreaming("123", "movie", null, null) } returns
            flowOf(*scrapers.map { scraper ->
                scraper to listOf(LocalScraperResult(
                    title = "Shared title",
                    url = "https://stream.example/${scraper.id}.m3u8"
                ))
            }.toTypedArray())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie", videoId = "tt1341338", season = null, episode = null
        ).toList()
        val groups = (results.last() as NetworkResult.Success).data
        val pluginGroups = groups.filter { it.addonName != "Fast Addon" }
        assertEquals(
            if (groupByRepository) listOf("Repository") else scrapers.map { it.name },
            pluginGroups.map { it.addonName }
        )
        val streams = pluginGroups.flatMap { it.streams }
        assertEquals(scrapers.map { it.name }, streams.map { it.pluginSourceName })
        assertEquals(listOf("Shared title", "Shared title"), streams.map { it.title })
        assertTrue(streams.all { stream ->
            stream.addonName == if (groupByRepository) "Repository" else stream.pluginSourceName
        })
        assertTrue(groups.single { it.addonName == "Fast Addon" }.streams.all { it.pluginSourceName == null })
    }

    private fun playlistAddon() = compatibleAddon().copy(id = "iptv-playlist:one", name = "IPTV · One",
        displayName = "IPTV · One", baseUrl = "iptv-playlist:one", resources = listOf(AddonResource("stream", listOf("movie", "series"), null)))
    private fun playlistStream() = com.nuvio.tv.domain.model.Stream(name = "IPTV", title = "Verified copy", description = null,
        url = "https://media.example.test/verified", ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = null, addonName = "IPTV · One", addonLogo = null, iptvPlaylistId = "one")

    @Test fun `normal addon results arrive while IPTV catalogue matching is pending`() = runBlocking {
        val sources = mockk<com.nuvio.tv.livetv.ondemand.IptvStreamSources>()
        val loader = mockk<com.nuvio.tv.livetv.ondemand.IptvVodStreams>()
        every { sources.sources() } returns flowOf(listOf(compatibleAddon(), playlistAddon()))
        coEvery { loader.revision() } returns "catalogue-1"
        val pending = CompletableDeferred<com.nuvio.tv.livetv.ondemand.VodStreamTarget?>()
        coEvery { loader.prepare(any(), any(), any(), any(), any()) } coAnswers { pending.await() }
        coEvery { loader.load(any(), any(), any(), any()) } returns listOf(playlistStream())
        val h = newHarness(emptyList(), sources, loader)
        val first = withTimeout(1_000) { h.repository.getStreamsFromAllAddons("movie", "tt1341338").first { it is NetworkResult.Success } }
        assertEquals(listOf("Fast Addon"), (first as NetworkResult.Success).data.map { it.addonName })
        assertTrue(pending.isActive)
        pending.complete(com.nuvio.tv.livetv.ondemand.VodStreamTarget(com.nuvio.tv.livetv.ondemand.VodKind.MOVIE, null, "Synthetic", 2020))
        coVerify(exactly = 1) { h.api.getStreams(any()) }
    }

    @Test fun `IPTV failure cannot discard normal addon results or fetch a virtual manifest`() = runBlocking {
        val sources = mockk<com.nuvio.tv.livetv.ondemand.IptvStreamSources>()
        val loader = mockk<com.nuvio.tv.livetv.ondemand.IptvVodStreams>()
        every { sources.sources() } returns flowOf(listOf(compatibleAddon(), playlistAddon()))
        coEvery { loader.revision() } returns "catalogue-1"
        coEvery { loader.prepare(any(), any(), any(), any(), any()) } throws IllegalStateException("Synthetic unavailable catalogue")
        val h = newHarness(emptyList(), sources, loader)
        val results = h.repository.getStreamsFromAllAddons("movie", "tt1341338").toList()
        assertEquals(listOf("Fast Addon"), (results.last() as NetworkResult.Success).data.map { it.addonName })
        coVerify(exactly = 1) { h.api.getStreams(any()) }
        assertTrue(h.repository.getStreamsFromAddon(playlistAddon(), "movie", "tt1341338") is NetworkResult.Error)
        coVerify(exactly = 1) { h.api.getStreams(any()) }
    }

    @Test fun `same numeric request with different selected episode identities uses separate cached searches`() = runBlocking {
        val sources = mockk<com.nuvio.tv.livetv.ondemand.IptvStreamSources>()
        val loader = mockk<com.nuvio.tv.livetv.ondemand.IptvVodStreams>()
        every { sources.sources() } returns flowOf(listOf(compatibleAddon().copy(resources = listOf(AddonResource("stream", listOf("series"), null))), playlistAddon()))
        coEvery { loader.revision() } returns "catalogue-1"
        coEvery { loader.prepare(any(), any(), any(), any(), any()) } returns com.nuvio.tv.livetv.ondemand.VodStreamTarget(
            com.nuvio.tv.livetv.ondemand.VodKind.SERIES, "2316", "The Office", 2005, setOf("Business Ethics"))
        coEvery { loader.load(any(), any(), any(), any()) } returns listOf(playlistStream())
        val h = newHarness(emptyList(), sources, loader)
        val first = com.nuvio.tv.domain.model.StreamContentContext("tt0386676", "The Office", 2005, "Business Ethics",
            com.nuvio.tv.domain.model.TvdbPlaybackContext(73244, "alternate", 100, 5, 3, listOf(200), "tt0386676:5:3"))
        h.repository.getStreamsFromAllAddons("series", "tt0386676:5:3", 5, 3, contentContext = first).toList()
        h.repository.getStreamsFromAllAddons("series", "tt0386676:5:3", 5, 3, contentContext = first).toList()
        val second = first.copy(episodeTitle = "Baby Shower", tvdb = first.tvdb!!.copy(orderType = "official", episodeId = 201, episodeIds = listOf(201)))
        h.repository.getStreamsFromAllAddons("series", "tt0386676:5:3", 5, 3, contentContext = second).toList()
        coVerify(exactly = 2) { loader.prepare("series", "tt0386676:5:3", 5, 3, any()) }
        coVerify { loader.prepare("series", "tt0386676:5:3", 5, 3, first) }
        coVerify { loader.prepare("series", "tt0386676:5:3", 5, 3, second) }
        coVerify(exactly = 2) { h.api.getStreams(match { java.net.URLDecoder.decode(it, "UTF-8").endsWith("/stream/series/tt0386676:5:3.json") }) }
        // Neither IPTV catalogue matching nor its identity context changes Stremio coordinates.
        coVerify(exactly = 0) { h.api.getStreams(match { it.contains("iptv-playlist:") }) }
    }

    private fun newHarness(enabledScrapers: List<ScraperInfo>,
        iptvSources: com.nuvio.tv.livetv.ondemand.IptvStreamSources? = null,
        iptvStreams: com.nuvio.tv.livetv.ondemand.IptvVodStreams? = null): Harness {
        val addon = compatibleAddon()
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any()) } returns Response.success(
            StreamResponseDto(
                streams = listOf(
                    StreamDto(
                        name = "Fast Stream",
                        url = "https://stream.example/video.m3u8"
                    )
                )
            )
        )

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonRepository.fetchAddon(addon.baseUrl) } returns NetworkResult.Success(addon)

        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(enabledScrapers)
        every { pluginManager.pluginsEnabled } returns flowOf(enabledScrapers.isNotEmpty())
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val tmdbService = mockk<TmdbService>(relaxed = true)
        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        every { debridSettingsDataStore.settings } returns flowOf(DebridSettings())
        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>()
        }
        val availability = mockk<LocalDebridAvailabilityService>()
        coEvery { availability.markChecking(any()) } coAnswers {
            firstArg<List<AddonStreams>>()
        }
        coEvery { availability.annotateCachedAvailability(any()) } coAnswers {
            firstArg<List<AddonStreams>>()
        }

        return Harness(
            repository = StreamRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                api = api,
                addonRepository = addonRepository,
                pluginManager = pluginManager,
                profileManager = profileManager,
                debridSettingsDataStore = debridSettingsDataStore,
                tmdbService = tmdbService,
                debridStreamPresentation = presentation,
                localDebridAvailabilityService = availability,
                iptvSources = iptvSources,
                iptvStreams = iptvStreams,
                serverStreams = mockk {
                    every { isNativeRequest(any()) } returns false
                    every { revision } returns 0
                    every { sources(any(), any(), any(), any(), any()) } returns emptyList()
                }
            ),
            api = api,
            tmdbService = tmdbService,
            addonRepository = addonRepository,
            pluginManager = pluginManager
        )
    }

    private fun compatibleAddon(): Addon = Addon(
        id = "fast-addon",
        name = "Fast Addon",
        version = "1.0.0",
        description = null,
        logo = ADDON_LOGO,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(
                name = "stream",
                types = listOf("movie"),
                idPrefixes = listOf("tt")
            )
        )
    )

    private fun compatibleScraper(): ScraperInfo = ScraperInfo(
        id = "plugin",
        name = "Plugin",
        description = "",
        version = "1.0.0",
        filename = "plugin.js",
        supportedTypes = listOf("movie"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo",
        formats = null,
        type = RepositoryType.NUVIO_JS
    )

    private companion object {
        const val ADDON_LOGO = "https://addon.example/logo.png"
    }

    private data class Harness(
        val repository: StreamRepositoryImpl,
        val api: AddonApi,
        val tmdbService: TmdbService,
        val addonRepository: AddonRepository,
        val pluginManager: PluginManager
    )
}
