package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreamSourcePreferencesTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    private val stores = mutableMapOf<Pair<Int, String>, MemoryStore>()
    private val active = MutableStateFlow(1)
    private val profiles = MutableStateFlow(listOf(UserProfile(1, "Primary", "#000000"), UserProfile(2, "Other", "#000000")))
    private val manager = mockk<ProfileManager>()
    init {
        every { manager.activeProfileId } returns active
        every { manager.profiles } returns profiles
        every { manager.activeProfile } answers { profiles.value.firstOrNull { it.id == active.value } }
    }
    private val factory = mockk<ProfileDataStoreFactory> { every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::MemoryStore) } }
    private fun store() = AddonPreferences(factory, manager)

    @Test fun `source backup contains opaque addon identities rather than configured URLs`() = runTest {
        val prefs = store()
        prefs.setStreamSourceOrder(listOf("https://addons.example.test/config?key=synthetic-private-setting", "iptv-playlist:p1"))
        val backup = prefs.exportStreamSourceSelections(1).getValue("order")
        assertFalse(backup.contains("https://")); assertFalse(backup.contains("synthetic-private-setting"))
        assertTrue(backup.contains("addon:")); assertTrue(backup.contains("iptv-playlist:p1"))
    }
    @Test fun `mixed order and disabled source survive recreation without changing real addon URLs`() = runTest {
        val preferences = store()
        preferences.setAddonOrder(listOf("https://one.example.test", "https://two.example.test"))
        preferences.setStreamSourceOrder(listOf("https://one.example.test", "iptv-playlist:p1", "https://two.example.test", "iptv-playlist:p2"))
        preferences.setPlaylistStreamEnabled("iptv-playlist:p1", false)
        assertEquals(listOf("https://one.example.test", "https://two.example.test"), store().installedAddonUrls.first())
        assertEquals("iptv-playlist:p1", store().mixedStreamSources.first().first[1])
        assertEquals(setOf("iptv-playlist:p1"), store().mixedStreamSources.first().second)
    }
    @Test fun `Drive round trip keeps stable unavailable playlist ids and per profile choices`() = runTest {
        val preferences = store()
        preferences.setStreamSourceOrder(listOf("iptv-playlist:temporarily-unavailable", "https://one.example.test"))
        preferences.setPlaylistStreamEnabled("iptv-playlist:temporarily-unavailable", false)
        val saved = preferences.exportStreamSourceSelections(1)
        preferences.importStreamSourceSelections(2, saved)
        assertEquals(saved, store().exportStreamSourceSelections(2))
        preferences.importStreamSourceSelections(1, emptyMap())
        assertTrue(preferences.exportStreamSourceSelections(1).isEmpty())
        assertEquals(saved, preferences.exportStreamSourceSelections(2))
    }
    @Test fun `invalid source backup is rejected before any preferences change`() = runTest {
        val preferences = store()
        preferences.setStreamSourceOrder(listOf("iptv-playlist:p1"))
        val before = preferences.exportStreamSourceSelections(1)
        assertTrue(runCatching { preferences.importStreamSourceSelections(1, mapOf("order" to "[\"iptv-playlist:p2\"]", "disabled" to "[\"https://not-a-playlist.example.test\"]")) }.isFailure)
        assertEquals(before, preferences.exportStreamSourceSelections(1))
    }
    @Test fun `fresh profile does not export implicit removals`() = runTest {
        assertTrue(store().exportStreamSourceSelections(2).isEmpty())
    }
}
