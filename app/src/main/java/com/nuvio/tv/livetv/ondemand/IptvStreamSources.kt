package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.streams.streamSourceKey
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** A presentation of existing sources, not synthetic HTTP addons or a second addon registry. */
@Singleton
class IptvStreamSources @Inject constructor(
    private val addons: AddonRepository,
    private val preferences: AddonPreferences,
    private val live: LiveTvPreferences
) {
    fun sources(): Flow<List<Addon>> = combine(addons.getInstalledAddons(), live.playlists,
        live.settings, preferences.mixedStreamSources) { installed, playlists, settings, saved ->
        val eligible = if (settings.onDemandInStreams) playlists.filter { it.enabled && it.isXtream && it.importVod } else emptyList()
        mixedSources(installed, eligible, saved.first, saved.second, settings.onDemandStreamsPosition)
    }

    suspend fun setOrder(sources: List<Addon>) {
        preferences.setStreamSourceOrder(sources.map { it.baseUrl })
        addons.setAddonOrder(sources.filterNot { isPlaylist(it.baseUrl) }.map { it.baseUrl })
    }

    suspend fun setEnabled(key: String, enabled: Boolean) {
        if (isPlaylist(key)) preferences.setPlaylistStreamEnabled(key, enabled) else addons.setAddonEnabled(key, enabled)
    }

    companion object {
        const val PREFIX = "iptv-playlist:"
        fun isPlaylist(key: String) = key.startsWith(PREFIX)

        internal fun mixedSources(installed: List<Addon>, playlists: List<PlaylistSource>,
            saved: List<String>, disabled: Set<String>, legacyPosition: String = "LAST"): List<Addon> {
            val occupied = installed.map { it.displayName }.toMutableSet()
            val counts = playlists.groupingBy { it.name }.eachCount()
            val virtual = playlists.map { playlist ->
                val key = PREFIX + playlist.id
                val baseName = "IPTV · ${playlist.name}"
                val name = if (counts[playlist.name] != 1 || baseName in occupied) "$baseName · ${playlist.id.take(8)}" else baseName
                occupied += name
                Addon(id = key, name = name, version = "IPTV", description = "Movies and series from this playlist. Manage its connection in Settings → IPTV.",
                    logo = null, baseUrl = key, catalogs = emptyList(), types = listOf(ContentType.MOVIE, ContentType.SERIES),
                    resources = listOf(AddonResource("stream", listOf("movie", "series", "tv"), null)), enabled = key !in disabled)
            }
            val all = installed + virtual
            if (saved.isEmpty()) return when (legacyPosition) {
                "FIRST" -> virtual + installed
                "AFTER_FIRST" -> installed.take(1) + virtual + installed.drop(1)
                else -> all
            }
            val byKey = all.associateBy { streamSourceKey(it.baseUrl) }
            // Keep IPTV anchors while accepting a changed real-addon order from Nuvio cloud.
            val real = installed.iterator()
            val seen = mutableSetOf<String>()
            val ordered = saved.map(::streamSourceKey).mapNotNull { key ->
                val source = if (isPlaylist(key)) byKey[key] else if (key in byKey && real.hasNext()) real.next() else null
                source?.takeIf { seen.add(it.baseUrl) }
            }
            return ordered + all.filter { seen.add(it.baseUrl) }
        }
    }
}
