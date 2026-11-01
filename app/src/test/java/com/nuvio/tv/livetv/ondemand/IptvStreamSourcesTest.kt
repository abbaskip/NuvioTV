package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IptvStreamSourcesTest {
    private fun addon(name: String) = Addon(name, name, version = "1", description = null, logo = null,
        baseUrl = "https://$name.example.test", catalogs = emptyList(), types = emptyList(), resources = emptyList())
    private fun playlist(id: String, name: String = id) = PlaylistSource(id, name, "", xtreamServer = "https://portal.example.test",
        xtreamUsername = "synthetic-user", xtreamPassword = "synthetic-password", importVod = true)
    @Test fun `each playlist is independently interleaved with normal addons`() {
        val a = addon("one"); val b = addon("two")
        val sources = IptvStreamSources.mixedSources(listOf(a, b), listOf(playlist("p1"), playlist("p2")),
            listOf(a.baseUrl, "iptv-playlist:p1", b.baseUrl, "iptv-playlist:p2"), emptySet())
        assertEquals(listOf(a.baseUrl, "iptv-playlist:p1", b.baseUrl, "iptv-playlist:p2"), sources.map { it.baseUrl })
        assertTrue(sources.filter { IptvStreamSources.isPlaylist(it.baseUrl) }.all { it.catalogs.isEmpty() && it.resources.single().name == "stream" })
        assertFalse(sources.toString().contains("synthetic-password"))
    }
    @Test fun `duplicate playlist names remain distinct sources`() {
        val sources = IptvStreamSources.mixedSources(emptyList(), listOf(playlist("p1", "Same"), playlist("p2", "Same")), emptyList(), emptySet())
        assertEquals(2, sources.map { it.displayName }.distinct().size)
        assertEquals(2, sources.map { it.baseUrl }.distinct().size)
    }
    @Test fun `source disable does not remove a playlist or another provider`() {
        val sources = IptvStreamSources.mixedSources(emptyList(), listOf(playlist("p1"), playlist("p2")), emptyList(), setOf("iptv-playlist:p1"))
        assertFalse(sources.first().enabled); assertTrue(sources.last().enabled)
    }
    @Test fun `cloud addon reorder preserves playlist anchors and every source`() {
        val a = addon("one"); val b = addon("two"); val c = addon("new")
        val sources = IptvStreamSources.mixedSources(listOf(b, a, c), listOf(playlist("p1"), playlist("p2")),
            listOf(a.baseUrl, "iptv-playlist:p1", b.baseUrl, "iptv-playlist:p2"), emptySet())
        assertEquals(listOf(b.baseUrl, "iptv-playlist:p1", a.baseUrl, "iptv-playlist:p2", c.baseUrl), sources.map { it.baseUrl })
    }
    @Test fun `temporarily missing playlists do not duplicate or drop real addons`() {
        val a = addon("one"); val b = addon("two")
        val sources = IptvStreamSources.mixedSources(listOf(a, b), listOf(playlist("p2")), listOf("iptv-playlist:missing", a.baseUrl, b.baseUrl), emptySet())
        assertEquals(setOf(a.baseUrl, b.baseUrl, "iptv-playlist:p2"), sources.map { it.baseUrl }.toSet())
    }
    @Test fun `legacy placement is retained until the user sets an individual order`() {
        val a = addon("one"); val b = addon("two")
        assertEquals(listOf(a.baseUrl, "iptv-playlist:p1", b.baseUrl), IptvStreamSources.mixedSources(listOf(a, b), listOf(playlist("p1")), emptyList(), emptySet(), "AFTER_FIRST").map { it.baseUrl })
    }
    @Test fun `mixed ordering never sends a virtual playlist to addon sync`() = runBlocking {
        val addons = mockk<AddonRepository>(relaxed = true); val prefs = mockk<AddonPreferences>(relaxed = true)
        val facade = IptvStreamSources(addons, prefs, mockk(relaxed = true))
        val real = addon("one")
        val all = IptvStreamSources.mixedSources(listOf(real), listOf(playlist("p1")), emptyList(), emptySet())
        facade.setOrder(all)
        coVerify { addons.setAddonOrder(listOf(real.baseUrl)) }
        coVerify { prefs.setStreamSourceOrder(listOf(real.baseUrl, "iptv-playlist:p1")) }
        facade.setEnabled("iptv-playlist:p1", false)
        coVerify { prefs.setPlaylistStreamEnabled("iptv-playlist:p1", false) }
        coVerify(exactly = 0) { addons.setAddonEnabled("iptv-playlist:p1", any()) }
    }
}
