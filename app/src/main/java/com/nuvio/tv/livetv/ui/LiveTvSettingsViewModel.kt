package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.LiveTvStatus
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveTvSettingsViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    val driveSync: com.nuvio.tv.livetv.sync.LiveTvDriveSync,
    val onDemand: com.nuvio.tv.livetv.ondemand.OnDemandRepository,
    private val posterResolver: com.nuvio.tv.livetv.data.LiveTvPosterResolver,
    private val matcher247: com.nuvio.tv.livetv.data.LiveTv247Matcher
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistSource>> =
        prefs.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val epgSources: StateFlow<List<EpgSource>> =
        prefs.epgSources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<LiveTvSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())
    val userState: StateFlow<LiveUserState> =
        prefs.userState.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())
    val status: StateFlow<LiveTvStatus> = repository.status
    /** Expiry and connections per playlist, when the provider gives them. */
    val accounts: StateFlow<Map<String, com.nuvio.tv.livetv.model.PlaylistAccount>> =
        prefs.playlistAccounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        repository.ensureLoaded()
        driveSync.start()
    }

    fun update(transform: (LiveTvSettings) -> LiveTvSettings) {
        viewModelScope.launch { prefs.updateSettings(transform) }
    }

    /** Settings that change which programs are kept need the guide re-read. */
    fun updateAndReloadEpg(transform: (LiveTvSettings) -> LiveTvSettings) {
        viewModelScope.launch {
            prefs.updateSettings(transform)
            repository.refreshEpgOnly()
        }
    }

    fun addPlaylist(name: String, url: String, userAgent: String) =
        viewModelScope.launch { repository.addPlaylist(name, url, userAgent) }

    fun addStalker(name: String, portal: String, mac: String, user: String, pass: String, serial: String) =
        viewModelScope.launch { repository.addStalker(name, portal, mac, user, pass, serial) }

    fun addXtream(name: String, server: String, user: String, pass: String, importVod: Boolean = true, importLive: Boolean = true) =
        viewModelScope.launch {
            repository.addXtream(name, server, user, pass, importVod, importLive)
            onDemand.refreshSoon()
        }

    fun savePlaylist(source: PlaylistSource) = viewModelScope.launch {
        val before = playlists.value.firstOrNull { it.id == source.id }
        repository.updatePlaylist(source)
        // Movies & series just switched on: import this provider now, whenever it was last
        // imported (switched off earlier, it had its titles removed but still counted as
        // "imported recently", so nothing came back for up to a day).
        if (source.isXtream && source.importVod && source.enabled && before?.importVod == false) onDemand.refreshNow(source.id)
        else onDemand.refreshSoon()
    }
    /**
     * "Update now" on a playlist. A login with TV channels off only has On Demand to update, so
     * its movies and series are re-imported straight away (and Live TV isn't touched).
     */
    fun updatePlaylistNow(source: PlaylistSource) = viewModelScope.launch {
        repository.updatePlaylist(source)
        if (source.isXtream && source.importVod && !source.liveEnabled) onDemand.refreshNow(source.id)
        else onDemand.refreshSoon()
    }
    fun removePlaylist(id: String) = viewModelScope.launch {
        repository.removePlaylist(id)
        onDemand.refreshSoon() // its movies and series go too
    }
    fun setPlaylistLogoSource(id: String, logoSource: String) =
        viewModelScope.launch { repository.setPlaylistLogoSource(id, logoSource) }
    fun setPlaylistEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        repository.setPlaylistEnabled(id, enabled)
        // Its movies and series go away with it, and come back (imported again) when re-enabled.
        val pl = playlists.value.firstOrNull { it.id == id }
        if (enabled && pl != null && pl.isXtream && pl.importVod) onDemand.refreshNow(id) else onDemand.refreshSoon()
    }
    fun movePlaylist(id: String, delta: Int) = viewModelScope.launch { repository.movePlaylist(id, delta) }

    fun addEpg(name: String, url: String) = viewModelScope.launch { repository.addEpg(name, url) }
    fun saveEpg(source: EpgSource) = viewModelScope.launch { repository.updateEpg(source) }
    fun removeEpg(id: String) = viewModelScope.launch { repository.removeEpg(id) }
    fun setEpgEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repository.setEpgEnabled(id, enabled) }

    fun refreshAll() = repository.refreshAll(force = true)

    fun unhideChannel(key: String) = viewModelScope.launch { prefs.setChannelHidden(key, false) }
    fun unhideAllChannels() = viewModelScope.launch { prefs.clearHiddenChannels() }
    fun unhideGroup(id: String) = viewModelScope.launch { prefs.setGroupHidden(id, false) }
    fun unhideAllGroups() = viewModelScope.launch { prefs.clearHiddenGroups() }
    fun clearFavorites() = viewModelScope.launch { prefs.clearFavorites() }
    fun clearRecent() = viewModelScope.launch { prefs.clearRecent() }
    fun resetChannelEdits() = viewModelScope.launch { prefs.resetChannelEdits() }
    fun resetGroupEdits() = viewModelScope.launch { prefs.resetGroupEdits() }
    fun resetChannelOrder() = viewModelScope.launch { prefs.resetChannelOrder() }
    fun clearVodHidden() = viewModelScope.launch { prefs.clearVodHiddenCategories() }

    val posterTest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Settings → "Test poster lookup". */
    /** Forgets every Live TV poster and show info, so they're fetched again from your addons. */
    fun clearPosterData() {
        posterResolver.clearAll() // also empties the saved posters, 24/7 matches included
        matcher247.clear()
    }

    fun testPoster(title: String) = viewModelScope.launch {
        posterTest.value = "Searching…"
        posterTest.value = runCatching { posterResolver.diagnose(title) }.getOrElse { "Test failed: ${it.message}" }
    }
    fun removeReminder(channelKey: String, startMs: Long) = viewModelScope.launch { prefs.removeReminder(channelKey, startMs) }
    fun clearChannelCopies() = viewModelScope.launch { prefs.clearChannelCopies() }
    fun resetEpgAssignments() = viewModelScope.launch {
        prefs.clearEpgOverrides()
        repository.rematchEpg()
    }
    fun deleteCustomGroup(id: String) = viewModelScope.launch { prefs.deleteCustomGroup(id) }

    /** Readable name for a hidden channel key, even if its playlist is currently disabled. */
    fun channelLabel(key: String): String {
        repository.channels.value.firstOrNull { it.key == key }?.let { return "${it.number}  ${it.name}" }
        return key.substringAfterLast('|').substringBefore('#')
    }

    fun groupLabel(id: String): String {
        userState.value.customGroups.firstOrNull { it.id == id }?.let { return it.name }
        userState.value.groupNames[id]?.let { return it }
        repository.channels.value.firstOrNull { it.groupId == id }?.let { return it.group }
        return id.substringAfter("::")
    }
}
