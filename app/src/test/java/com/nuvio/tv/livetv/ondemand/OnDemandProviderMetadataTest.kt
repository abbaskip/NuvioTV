package com.nuvio.tv.livetv.ondemand

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnDemandProviderMetadataTest {
    private val repository = mockk<OnDemandRepository>(relaxed = true)
    private val preferences = mockk<LiveTvPreferences>()
    private val history = mockk<WatchProgressRepository>()
    private val watched = mockk<WatchedSeriesStateHolder>()
    private val store = ViewModelStore()
    private val category = VodCategory("provider", VodKind.SERIES, "shows", "Shows", 45)
    private val items = (1..45).map { n -> VodItem("provider", VodKind.SERIES, "$n", "Show $n (2005)",
        "https://provider.example/poster/$n.jpg", "shows", null, "$n", 2005, "8.1", 0, "Comedy") }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { preferences.settings } returns MutableStateFlow(LiveTvSettings(onDemandAddonPosters = true))
        every { preferences.userState } returns MutableStateFlow(LiveUserState())
        every { preferences.playlists } returns MutableStateFlow(emptyList())
        every { repository.version } returns MutableStateFlow(0)
        every { repository.genreUpdates } returns MutableStateFlow(0)
        every { repository.status } returns MutableStateFlow(OnDemandStatus())
        every { history.observeWatchedMovieIds() } returns MutableStateFlow(emptySet())
        every { watched.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        coEvery { repository.categories(any()) } answers { if (firstArg<VodKind>() == VodKind.SERIES) listOf(category) else emptyList() }
        coEvery { repository.genres(any()) } returns emptyList()
        coEvery { repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (firstArg<VodKind>() == VodKind.SERIES) items.drop(arg(5)).take(arg(4)) else emptyList()
        }
    }

    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun viewModel() = OnDemandViewModel(repository, preferences, history, watched).also { store.put("vod", it) }

    @Test fun `browsing focusing and paging keep provider artwork without enrichment or detail prefetch`() = runTest {
        val vm = viewModel()
        vm.loadHome()
        val initial = vm.home.first { !it.loading }
        assertEquals(30, initial.rows.single().items.size)
        initial.rows.single().items.forEach { card -> vm.focusHome(card.id) }
        vm.loadMoreHome(category.uid)
        val loaded = vm.home.first { it.rows.singleOrNull()?.items?.size == 45 }
        assertEquals(items.map { it.icon }, loaded.rows.single().items.map { it.poster })
        coVerify(exactly = 0) { repository.info(any()) }
        coVerify(exactly = 0) { repository.cachedPoster(any()) }
        coVerify(exactly = 0) { repository.cachedTitle(any()) }
        coVerify(exactly = 0) { repository.cachePoster(any(), any(), any()) }
        coVerify(exactly = 0) { repository.fillGenres(any(), any()) }
    }

    @Test fun `opening a standalone title uses provider details even with the old addon poster option enabled`() = runTest {
        val vm = viewModel()
        assertEquals(OnDemandTarget.Provider(items.first()), vm.targetFor(items.first()))
        assertEquals(items.first().icon, vm.posterFor(items.first()))
        coVerify(exactly = 0) { repository.cachedPoster(any()) }
        vm.info(items.first())
        coVerify(exactly = 1) { repository.info(items.first()) }
    }

    @Test fun `provider presentation retains title year rating genres and missing artwork without guessing`() {
        val card = VodHomeIds.toMeta(items.first())
        assertEquals("Show 1", card.name)
        assertEquals("2005", card.releaseInfo)
        assertEquals(8.1f, card.imdbRating)
        assertEquals(listOf("Comedy"), card.genres)
        assertNull(VodHomeIds.toMeta(items.first().copy(icon = null)).poster)
    }

    @Test fun `explicit context actions use the provider identity without matching by title`() = runTest {
        val vm = viewModel(); vm.loadHome(); vm.home.first { !it.loading }
        val card = vm.home.value.rows.single().items.first()
        assertEquals("tmdb:1", vm.optionsTarget(card.id)?.first?.id)
        assertTrue(vm.isWatched(card, emptySet<String>() to setOf("tmdb:1")))
        assertFalse(vm.isWatched(card, emptySet<String>() to setOf("tmdb:2")))
        coVerify(exactly = 0) { repository.info(any()) }
    }
}
