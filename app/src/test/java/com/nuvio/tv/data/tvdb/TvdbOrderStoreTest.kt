package com.nuvio.tv.data.tvdb

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvdbOrderStoreTest {
    @Test fun `one TVDB series may contain independent native parents with the same numeric slot`() = runTest {
        val store = TvdbOrderStore(factory)
        val original = TvdbSourceReference(335862, "tt0983514:1:1", com.nuvio.tv.domain.model.EpisodeCoordinates(1, 1), "peters")
        val revival = TvdbSourceReference(9893344, "tt32436778:1:1", com.nuvio.tv.domain.model.EpisodeCoordinates(1, 1), "bel aire")
        store.rememberSources(1, 80552, listOf(original, revival))
        assertEquals(setOf(335862L, 9893344L), store.sources(1, 80552).keys)
        assertEquals(original, store.sources(1, 80552)[335862])
        assertEquals(revival, store.sources(1, 80552)[9893344])
    }
    @Test fun `verified split aliases survive recreation without allowing an unrelated episode to reuse the source`() = runTest {
        val store = TvdbOrderStore(factory)
        val first = TvdbSourceReference(7931974, "tt0386676:5:1", com.nuvio.tv.domain.model.EpisodeCoordinates(5, 1), "weight loss", 389875)
        val second = first.copy(episodeId = 7931977)
        store.rememberSources(1, 73244, listOf(first, second))
        val recreated = TvdbOrderStore(factory)
        assertEquals(setOf(7931974L, 7931977L), recreated.sources(1, 73244).keys)
        assertEquals(setOf(389875L), recreated.sources(1, 73244).values.map { it.trackingEpisodeId }.toSet())
        recreated.rememberSources(1, 73244, listOf(first.copy(episodeId = 390588, canonicalEpisodeId = null)))
        assertEquals(2, recreated.sources(1, 73244).size)
        assertTrue(recreated.sources(2, 73244).isEmpty())
    }

    @Test fun `backup retains stable order IDs profiles and aliases without playback caches`() = runTest {
        val store = TvdbOrderStore(factory)
        store.save(1, 78874, "dvd", listOf("tt0303461"))
        store.save(2, 78874, "alternate_2", listOf("tmdb:1437"))
        val one = store.exportSelections(1)
        val two = store.exportSelections(2)
        store.save(1, 78874, null, emptyList())
        store.importSelections(1, one)
        assertEquals("dvd", store.selected(1, listOf("tt0303461"))?.orderType)
        assertEquals("alternate_2", store.selected(2, listOf("tmdb:1437"))?.orderType)
        assertFalse(one.keys.any { it.startsWith("sources:") })
        assertNotEquals(one, two)
    }

    @Test fun `restore keeps unavailable orders but rejects malformed data before changing preferences`() = runTest {
        val store = TvdbOrderStore(factory)
        store.importSelections(1, mapOf("order:78874" to "unavailable_order", "alias:tt0303461" to "78874"))
        try {
            store.importSelections(1, mapOf("order:78874" to "bad/order"))
            fail("Malformed order accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("unavailable_order", store.selected(1, listOf("tt0303461"))?.orderType)
        store.importSelections(1, emptyMap())
        assertNull(store.selected(1, listOf("tt0303461")))
    }

    @Test fun `explicit default reset travels but a fresh install exports no deletion`() = runTest {
        val store = TvdbOrderStore(factory)
        assertTrue(store.exportSelections(1).isEmpty())
        store.save(1, 78874, "dvd", listOf("tt0303461"))
        store.save(1, 78874, null, listOf("tt0303461"))
        val reset = store.exportSelections(1)
        assertEquals("true", reset["reset:78874"])
        assertFalse(reset.containsKey("order:78874"))
        store.importSelections(2, reset)
        assertNull(store.selected(2, listOf("tt0303461")))
        store.save(1, 78874, "dvd", listOf("tt0303461"))
        assertFalse(store.exportSelections(1).containsKey("reset:78874"))
    }

    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private val stores = mutableMapOf<Pair<Int, String>, Memory>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::Memory) }
    }

    @Test fun `source aliases survive order removal and reject conflicting episode slots`() = runTest {
        val store = TvdbOrderStore(factory)
        val first = TvdbSourceReference(297989, "tt0303461:1:1", com.nuvio.tv.domain.model.EpisodeCoordinates(1, 1))
        store.rememberSources(1, 78874, listOf(first))
        val conflict = first.copy(episodeId = 297994)
        assertEquals(mapOf(297989L to first), store.rememberSources(1, 78874, listOf(conflict)))
        store.save(1, 78874, null, listOf("tt0303461"))
        assertEquals(mapOf(297989L to first), TvdbOrderStore(factory).sources(1, 78874))
        assertTrue(store.sources(2, 78874).isEmpty())
    }

    @Test fun `profile preferences survive recreation and aliases share one selection`() = runTest {
        TvdbOrderStore(factory).save(1, 78874, "dvd", listOf("tt0303461", "tmdb:1437"))
        val recreated = TvdbOrderStore(factory)
        assertEquals(TvdbOrderPreference(78874, "dvd"), recreated.selected(1, listOf("tmdb:1437")))
        assertNull(recreated.selected(2, listOf("tt0303461")))
        assertNull(recreated.selected(1, listOf("another-series")))
        recreated.save(1, 78874, "official", listOf("tt0303461"))
        assertEquals("official", recreated.selected(1, listOf("tmdb:1437"))?.orderType)
        recreated.save(1, 78874, null, listOf("tt0303461"))
        assertNull(recreated.selected(1, listOf("tmdb:1437")))
    }
}
