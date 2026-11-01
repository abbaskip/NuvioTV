package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.LiveTvStatus
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.ZapMode
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.model.forPlaylist
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Matches Nuvio's side-menu setting: classic drawer, modern floating panel, or modern with blur. */
enum class LiveMenuStyle { CLASSIC, MODERN, MODERN_BLUR }

/** In-memory session shared by the guide and the full-screen player (current group, etc). */
@Singleton
class LiveTvSession @Inject constructor() {
    val currentGroupId = MutableStateFlow<String?>(null)
    val currentPlaylistId = MutableStateFlow<String?>(null)
    var autoPlayedThisLaunch = false
    /** Set when full screen opens, so Back to the guide returns to the channel, not the groups. */
    var returningFromFullscreen = false
    /** Overlay mode off: Left in full screen returns to the guide with the groups open. */
    var openGroupsOnReturn = false
    val collapsedPlaylists = MutableStateFlow<Set<String>>(emptySet())
    /** Sleep timer: when playback stops (null = off). Kept while the app is open. */
    val sleepAtMs = MutableStateFlow<Long?>(null)
    /** The "closed while starting" note has been shown (once per app launch). */
    var crashNoticeShown = false
}

data class LiveTvUiState(
    val groups: List<ChannelGroup> = emptyList(),
    val selectedGroupId: String = ChannelGroup.ALL,
    val channels: List<LiveChannel> = emptyList(),
    val allVisibleChannels: List<LiveChannel> = emptyList(),
    val hasSources: Boolean = true,
    val searchQuery: String = "",
    /** Non-special groups in the order shown, used when the user moves a group. */
    val orderableGroupIds: List<String> = emptyList(),
    val customGroups: List<ChannelGroup> = emptyList(),
    /** Where "back" and fallbacks go: All channels, or the first group when that's hidden. */
    val defaultGroupId: String = ChannelGroup.ALL
) {
    /** The group to switch to so [channel] is in the list. */
    fun groupFor(channel: LiveChannel): String =
        if (groups.any { it.id == ChannelGroup.ALL }) ChannelGroup.ALL else channel.groupId
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    val playback: LiveTvPlaybackController,
    private val session: LiveTvSession,
    private val channelStore: com.nuvio.tv.livetv.data.LiveTvChannelStore,
    layoutPrefs: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    driveSync: com.nuvio.tv.livetv.sync.LiveTvDriveSync,
    val recorder: com.nuvio.tv.livetv.record.LiveTvRecorder,
    val scheduler: com.nuvio.tv.livetv.record.LiveTvRecordingScheduler
) : ViewModel() {

    init {
        // Google Drive sync (if connected): pick up a newer setup from another TV.
        driveSync.start()
    }


    /** Nuvio's side-menu appearance (Layout settings), so Live TV panels can match it. */
    val menuStyle: StateFlow<LiveMenuStyle> = combine(
        layoutPrefs.modernSidebarEnabled,
        layoutPrefs.modernSidebarBlurEnabled
    ) { modern, blur ->
        when {
            !modern -> LiveMenuStyle.CLASSIC
            blur && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> LiveMenuStyle.MODERN_BLUR
            else -> LiveMenuStyle.MODERN
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LiveMenuStyle.CLASSIC)


    val settings: StateFlow<LiveTvSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())

    val userState: StateFlow<LiveUserState> = prefs.userState
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())

    /** Picture shape per channel (see [com.nuvio.tv.livetv.player.PictureShape]). */
    val pictureShapes: StateFlow<Map<String, String>> = prefs.pictureShapes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun pictureShapeOf(channelKey: String?): com.nuvio.tv.livetv.player.PictureShape =
        com.nuvio.tv.livetv.player.PictureShape.of(channelKey?.let { pictureShapes.value[it] })

    fun setPictureShape(channelKey: String, shape: com.nuvio.tv.livetv.player.PictureShape) {
        viewModelScope.launch { prefs.setPictureShape(channelKey, shape) }
    }

    val status: StateFlow<LiveTvStatus> = repository.status
    val playbackState: StateFlow<LivePlaybackState> = playback.state

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    private val searchQuery = MutableStateFlow("")

    /**
     * Guide Time Offset: each channel's listings as last moved, by the original list. Declared
     * before [programs], whose first run can start while the rest is still being set up.
     */
    private val shiftedLists = java.util.IdentityHashMap<List<EpgProgram>, List<EpgProgram>>()
    private var shiftedBy = 0L

    /** Programs with the user's EPG time offset applied. */
    val programs: StateFlow<Map<String, List<EpgProgram>>> = combine(
        repository.programs,
        settings.map { it.epgOffsetMinutes }.distinctUntilChanged()
    ) { map, offset ->
        if (offset == 0) { shiftedLists.clear(); map } else {
            val shift = offset * 60_000L
            if (shift != shiftedBy) { shiftedLists.clear(); shiftedBy = shift }
            // Only channels whose listings changed are moved again: each update of the guide
            // (as the hours pass, scrolling ahead) used to copy every show of every channel, and
            // made every guide row redraw. Keyed by the list itself (same list = same listings).
            val next = java.util.IdentityHashMap<List<EpgProgram>, List<EpgProgram>>(map.size)
            val out = map.mapValues { (_, list) ->
                val moved = shiftedLists[list] ?: list.map { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) }
                next[list] = moved
                moved
            }
            shiftedLists.clear()
            shiftedLists.putAll(next)
            out
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val hasSources: StateFlow<Boolean> = prefs.playlists.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Channels with the user's renames and renumbering applied. Worked out once for the whole
     * app (guide, full screen and search share it) instead of once per screen.
     */
    val selectedPlaylistId: StateFlow<String?> = session.currentPlaylistId
    val displayChannels: StateFlow<List<LiveChannel>> = combine(
        channelStore.displayChannels, selectedPlaylistId
    ) { channels, sourceId -> channels.forPlaylist(sourceId) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectPlaylist(sourceId: String?) {
        session.currentPlaylistId.value = sourceId
        selectGroup(ChannelGroup.ALL)
    }

    val uiState: StateFlow<LiveTvUiState> = combine(
        // Only what the lists use: saving the last channel, its picture quality or audio track
        // (on every channel change) used to rebuild every list over every channel, right when a
        // slower box needed its processor to start the new channel.
        combine(displayChannels, userState.distinctUntilChanged { a, b -> sameForLists(a, b) }, settings) { a, b, c -> Triple(a, b, c) },
        session.currentGroupId,
        searchQuery,
        combine(hasSources, com.nuvio.tv.livetv.parental.ParentalControls.unlocked) { h, _ -> h }
    ) { (channels, user, s), groupIdRaw, query, hasSrc ->
        buildUi(channels, user, s, groupIdRaw, query, hasSrc)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvUiState())

    /** Channels by backup name (see [backupName]); null until first worked out. Declared before
     *  init, which fills it in the background. */
    @Volatile private var backupIndex: Map<String, List<LiveChannel>>? = null

    init {
        // A provider whose catch-up only works as TS: ask for TS for a week (saves a failed
        // HLS try, about a second, on every replay), then try HLS again; if that fails too,
        // another week of TS, and so on.
        // A channel that won't play: another channel by the same name, if there is one.
        playback.onLiveFailure = { key -> switchToBackup(key) }
        // Channels by their "backup name", worked out away from the screen whenever the list
        // changes: looking through every channel when one failed ran on the main thread and,
        // with a big playlist, froze the app until Android closed it.
        viewModelScope.launch(Dispatchers.Default) {
            repository.channels.collectLatest { list ->
                backupIndex = list.groupBy { backupName(it.name) }
            }
        }
        // The audio track picked on each channel, remembered and used every time it plays.
        playback.onAudioChosen = { key, audio -> viewModelScope.launch { prefs.setChannelAudio(key, audio) } }
        viewModelScope.launch { userState.collect { playback.channelAudio = it.channelAudio } }
        playback.onCatchupHlsUnsupported = { sourceId ->
            viewModelScope.launch {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: this provider doesn't do HLS replays; using TS for the next week")
                val now = System.currentTimeMillis()
                prefs.updatePlaylists { l -> l.map { if (it.id == sourceId) it.copy(catchupNoHlsSince = now) else it } }
            }
        }
        playback.onCatchupHlsWorks = { sourceId ->
            if ((playlistNoHls.value[sourceId] ?: 0L) > 0L) viewModelScope.launch {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: HLS replays work on this provider again")
                prefs.updatePlaylists { l -> l.map { if (it.id == sourceId) it.copy(catchupNoHlsSince = 0L) else it } }
            }
        }
        repository.ensureLoaded()
        // Coming back to Live TV after hours in the background: anything due is updated now.
        repository.refreshIfStale()
        viewModelScope.launch {
            while (true) {
                // Nothing to keep up to date while a movie or episode plays in Nuvio's player.
                com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
                _now.value = System.currentTimeMillis()
                // Keep the hours around now loaded from the guide database as time passes.
                repository.ensureRange(_now.value - 3 * 3_600_000L, _now.value + 12 * 3_600_000L)
                delay(30_000)
            }
        }
        viewModelScope.launch {
            settings.collect {
                playback.autoReconnect = it.autoReconnect
                playback.catchupCache = it.catchupCache
                playback.audioPassthrough = it.audioPassthrough
                playback.bufferSize = it.bufferSize
                playback.timeshiftEnabled = it.timeshiftEnabled
                playback.timeshiftMinutes = it.timeshiftMinutes
                playback.rewindAlways = it.timeshiftEnabled && it.rewindAlways
                playback.setBufferStorage(it.recordingStorage)
                playback.captionsByDefault = it.captionsByDefault
            }
        }
        viewModelScope.launch {
            // Restore the remembered group once the user state is known.
            val remembered = prefs.userState.first()
            if (session.currentGroupId.value == null) {
                val s = prefs.currentSettings()
                session.currentGroupId.value = if (s.rememberLastGroup) remembered.lastGroupId else null
            }
        }
    }

    /** The parts of the saved user state that [buildUi] reads; keep in step with it. */
    private fun sameForLists(a: LiveUserState, b: LiveUserState): Boolean =
        a.favorites == b.favorites && a.recent == b.recent &&
            a.hiddenChannels == b.hiddenChannels && a.hiddenGroups == b.hiddenGroups &&
            a.lockedGroups == b.lockedGroups && a.customGroups == b.customGroups &&
            a.channelOrder == b.channelOrder && a.groupOrder == b.groupOrder &&
            a.channelCopies == b.channelCopies

    private fun buildUi(
        channels: List<LiveChannel>,
        user: LiveUserState,
        s: LiveTvSettings,
        groupIdRaw: String?,
        query: String,
        hasSrc: Boolean,
        /**
         * Home row: the asked-for group or nothing. Favorites / Recently watched work even when
         * hidden from the guide's group list, and a group that's gone shows no channels instead
         * of falling back to All channels (thousands of cards on the home screen).
         */
        exactGroup: Boolean = false
    ): LiveTvUiState {
        val shown = channels.filter { it.key !in user.hiddenChannels && it.groupId !in user.hiddenGroups }
        // Parental controls: channels in locked groups stay out of every list until unlocked.
        val lockedIds = HashSet<String>()
        shown.distinctBy { it.groupId }.forEach { c ->
            if (com.nuvio.tv.livetv.parental.ParentalControls.isLocked(c.groupId, c.group, s, user.lockedGroups)) lockedIds += c.groupId
        }
        user.customGroups.forEach { g ->
            if (com.nuvio.tv.livetv.parental.ParentalControls.isLocked(g.id, g.name, s, user.lockedGroups)) lockedIds += g.id
        }
        val visible = shown.filter { it.groupId !in lockedIds }
        val byKey = visible.associateBy { it.key }
        val favorites = user.favorites.mapNotNull { byKey[it] }
        val recent = user.recent.mapNotNull { byKey[it] }

        fun sorted(list: List<LiveChannel>): List<LiveChannel> = when (s.channelSort) {
            ChannelSort.PLAYLIST -> list
            ChannelSort.NUMBER -> list.sortedBy { it.number }
            ChannelSort.NAME -> list.sortedBy { it.name.lowercase() }
        }

        /** Your order from "Reorder channels" wins; channels you haven't placed follow as sorted. */
        fun ordered(groupId: String, list: List<LiveChannel>): List<LiveChannel> {
            val order = user.channelOrder[groupId] ?: return sorted(list)
            val byKeyHere = list.associateBy { it.key }
            val placed = order.mapNotNull { byKeyHere[it] }
            val placedKeys = placed.mapTo(HashSet()) { it.key }
            return placed + sorted(list.filter { it.key !in placedKeys })
        }

        /** A playlist group's own channels plus channels copied into it. */
        fun groupChannels(groupId: String): List<LiveChannel> {
            val own = visible.filter { it.groupId == groupId }
            val ownKeys = own.mapTo(HashSet()) { it.key }
            val copies = user.channelCopies[groupId].orEmpty().mapNotNull { byKey[it] }.filter { it.key !in ownKeys }
            return own + copies
        }

        val groups = mutableListOf<ChannelGroup>()
        groups += ChannelGroup(ChannelGroup.SEARCH, "Search", 0, special = true)
        if (s.showFavoritesGroup) groups += ChannelGroup(ChannelGroup.FAVORITES, "Favorites", favorites.size, special = true)
        if (s.showRecentGroup) groups += ChannelGroup(ChannelGroup.RECENT, "Recently watched", recent.size, special = true)
        if (s.showAllChannelsGroup) groups += ChannelGroup(ChannelGroup.ALL, "All channels", visible.size, special = true)

        // Your own groups first, then the playlist's groups, then the saved order on top.
        val regular = mutableListOf<ChannelGroup>()
        val custom = user.customGroups
            .filter { it.id !in user.hiddenGroups }
            .map { g -> ChannelGroup(g.id, g.name, g.channelKeys.count { it in byKey }, locked = g.id in lockedIds) }
        regular += custom
        val playlistGroups = LinkedHashMap<String, Pair<String, Int>>()
        shown.forEach { c ->
            val cur = playlistGroups[c.groupId]
            playlistGroups[c.groupId] = (cur?.first ?: c.group) to ((cur?.second ?: 0) + 1)
        }
        val groupSource = HashMap<String, String>()
        shown.forEach { c -> groupSource.putIfAbsent(c.groupId, c.sourceId) }
        playlistGroups.forEach { (id, v) ->
            val copied = user.channelCopies[id].orEmpty().count { k -> byKey[k]?.let { it.groupId != id } == true }
            regular += ChannelGroup(id, v.first, v.second + copied, sourceId = groupSource[id], locked = id in lockedIds)
        }
        val orderIndex = user.groupOrder.withIndex().associate { (i, id) -> id to i }
        // Playlists in their own order (your own groups first), for keeping each playlist's
        // groups together when sorting by name.
        val playlistIndex = HashMap<String, Int>().also { m -> shown.forEach { c -> m.putIfAbsent(c.sourceId, m.size) } }
        val orderedRegular = when (s.groupSort) {
            // As the playlist lists them (your own groups first).
            "playlist" -> regular
            // A–Z, kept per playlist when playlist headings are on.
            "name" -> regular.sortedWith(
                compareBy<ChannelGroup>(
                    { g -> if (g.sourceId == null) -1 else if (s.groupPlaylistHeadings) playlistIndex[g.sourceId] ?: Int.MAX_VALUE else 0 },
                    { g -> g.title.lowercase() }
                )
            )
            // Your order (Reorder groups), playlist order for anything not moved.
            else -> regular.withIndex()
                .sortedWith(compareBy<IndexedValue<ChannelGroup>>({ orderIndex[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
        }
        groups += orderedRegular

        val defaultGroupId = when {
            s.showAllChannelsGroup -> ChannelGroup.ALL
            orderedRegular.isNotEmpty() -> orderedRegular.first().id
            s.showFavoritesGroup -> ChannelGroup.FAVORITES
            else -> ChannelGroup.ALL
        }
        val groupId = if (exactGroup) groupIdRaw ?: "" else groupIdRaw?.takeIf { id -> groups.any { it.id == id } } ?: defaultGroupId
        val rawList = when {
            groupId == ChannelGroup.ALL && s.showAllChannelsGroup -> ordered(ChannelGroup.ALL, visible)
            // "Playlist order" keeps your own order here; Number / Name sort these too.
            groupId == ChannelGroup.FAVORITES -> sorted(favorites)
            groupId == ChannelGroup.RECENT -> recent
            groupId == ChannelGroup.SEARCH -> {
                val q = query.trim()
                if (q.isEmpty()) emptyList() else sorted(visible.filter {
                    it.name.contains(q, ignoreCase = true) || it.number.toString() == q
                })
            }
            groupId in lockedIds -> emptyList()
            groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) ->
                sorted(user.customGroups.firstOrNull { it.id == groupId }?.channelKeys?.mapNotNull { byKey[it] }.orEmpty())
            else -> ordered(groupId, groupChannels(groupId)).distinctBy { it.key }
        }
        // TiviMate's number override: 1, 2, 3… in the order the group shows them.
        val list = if (s.sequentialNumbers && groupId != ChannelGroup.SEARCH) {
            rawList.mapIndexed { i, c -> c.copy(number = i + 1) }
        } else rawList
        return LiveTvUiState(
            groups = groups,
            selectedGroupId = groupId,
            channels = list,
            allVisibleChannels = sorted(visible),
            hasSources = hasSrc,
            searchQuery = query,
            orderableGroupIds = orderedRegular.map { it.id },
            customGroups = custom,
            defaultGroupId = defaultGroupId
        )
    }

    // ------------------------------------------------------------ actions

    fun selectGroup(id: String) {
        session.currentGroupId.value = id
    }

    // ------------------------------------------------------------ sleep timer, quality

    /** Sleep timer: lives in the playback controller, so it works on every screen. */
    val sleepAtMs: StateFlow<Long?> = playback.sleepAtMs

    fun setSleepTimer(minutes: Int?) = playback.setSleepTimer(minutes)

    /** Remembers the picture quality a channel played at, for the guide's badges. */
    fun recordQuality(channelKey: String, height: Int) {
        val q = when {
            height >= 1800 -> "4K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            height > 0 -> "SD"
            else -> return
        }
        if (userState.value.channelQuality[channelKey] == q) return
        viewModelScope.launch { prefs.setChannelQuality(channelKey, q) }
    }

    // ------------------------------------------------------------ reminders

    /** Sets or cancels "Remind me" for an upcoming show. */
    fun toggleReminder(channel: LiveChannel, program: EpgProgram) = viewModelScope.launch {
        val exists = userState.value.reminders.any { it.channelKey == channel.key && it.startMs == program.startMs }
        if (exists) prefs.removeReminder(channel.key, program.startMs)
        else prefs.addReminder(com.nuvio.tv.livetv.model.Reminder(channel.key, channel.name, program.title, program.startMs))
    }

    // ------------------------------------------------------------ parental controls

    fun parentalEnabled(): Boolean = com.nuvio.tv.livetv.parental.ParentalControls.enabled(settings.value)

    /** Opens a locked group with the PIN; false if the PIN is wrong. */
    fun unlockGroup(group: ChannelGroup, pin: String): Boolean =
        com.nuvio.tv.livetv.parental.ParentalControls.unlock(group.id, pin, settings.value)

    fun isGroupLockSet(groupId: String) = groupId in userState.value.lockedGroups

    fun setGroupLocked(groupId: String, locked: Boolean) = viewModelScope.launch { prefs.setGroupLocked(groupId, locked) }

    fun checkPin(pin: String) = com.nuvio.tv.livetv.parental.ParentalControls.checkPin(pin, settings.value)

    fun setSearchQuery(q: String) {
        searchQuery.value = q
        session.currentGroupId.value = ChannelGroup.SEARCH
    }

    /** Channels already tried in a row of backup switches (cleared when you change channel). */
    private val backupTried = HashSet<String>()
    private var switchingToBackup = false

    /**
     * Switches from a channel that won't play to another one with the same name (a different
     * group or playlist: "US: ESPN HD" / "ESPN Backup"), other playlists first. Each one is tried
     * once; when none are left it stops and the channel shows its error as before.
     */
    private fun switchToBackup(failedKey: String): Boolean {
        if (!settings.value.autoBackupChannel) return false
        // Not worked out yet (just started): no backup this time, rather than a long search here.
        val index = backupIndex ?: return false
        val failed = playback.playingChannel?.takeIf { it.key == failedKey }
            ?: repository.channels.value.firstOrNull { it.key == failedKey } ?: return false
        backupTried += failedKey
        val name = backupName(failed.name)
        if (name.length < 2) return false
        val s = settings.value
        val user = userState.value
        val pick = index[name].orEmpty().asSequence()
            .filter { it.key !in backupTried }
            // Local stations: "FOX (WNYW)" isn't a backup for "FOX (KTTV)".
            .filter { c -> val a = stationNote(failed.name); val b = stationNote(c.name); a == null || b == null || a == b }
            .filter { !com.nuvio.tv.livetv.parental.ParentalControls.isLocked(it.groupId, it.group, s, user.lockedGroups) }
            .sortedBy { if (it.sourceId != failed.sourceId) 0 else 1 }
            .firstOrNull() ?: return false
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Channel ${failed.name} won't play: switched to ${pick.name} (${pick.group})")
        com.nuvio.tv.livetv.record.LiveTvNotice.show("${failed.name} isn't working right now. Switched to ${pick.name} from ${pick.group}.")
        switchingToBackup = true
        try { preview(pick) } finally { switchingToBackup = false }
        return true
    }

    fun preview(channel: LiveChannel) {
        if (!switchingToBackup) backupTried.clear()
        _catchup.value = null
        // Another channel from a provider that's recording: the recording holds a connection.
        recorder.recordings.value.firstOrNull {
            it.isRecording && it.channelKey != channel.key &&
                repository.channels.value.firstOrNull { c -> c.key == it.channelKey }?.sourceId == channel.sourceId
        }?.let { rec ->
            if (playback.playingChannel?.key != channel.key) {
                com.nuvio.tv.livetv.record.LiveTvNotice.show(
                    "Recording \"${rec.title}\" is using a connection to this provider. If this channel doesn't play, your account may allow only one at a time."
                )
            }
        }
        playback.play(channel)
        // Remember the group you watched in, so Live TV reopens there.
        val group = session.currentGroupId.value?.takeIf { it != ChannelGroup.SEARCH }
            ?: uiState.value.selectedGroupId
        viewModelScope.launch {
            prefs.recordWatched(channel.key, addToRecent = !isAdultChannel(channel))
            prefs.setLastGroup(group)
        }
    }

    /** Adult channels never show in Recently watched (by their name or their group's name). */
    fun isAdultChannel(ch: LiveChannel): Boolean =
        com.nuvio.tv.livetv.parental.ParentalControls.isAdult(ch.group) ||
            com.nuvio.tv.livetv.parental.ParentalControls.isAdult(ch.name)

    /**
     * The info panel's Recent row: channels watched before [currentKey], most recent first.
     * Never adult channels or ones in a group that's locked right now.
     */
    fun recentForPanel(currentKey: String?, limit: Int = 20): List<LiveChannel> {
        val user = userState.value
        val s = settings.value
        return user.recent.asSequence()
            .filter { it != currentKey }
            .mapNotNull { channelByKey(it) }
            .filter { ch ->
                !isAdultChannel(ch) &&
                    !com.nuvio.tv.livetv.parental.ParentalControls.isLocked(ch.groupId, ch.group, s, user.lockedGroups)
            }
            .take(limit)
            .toList()
    }

    fun clearRecent() { viewModelScope.launch { prefs.clearRecent() } }

    /**
     * The channels in the info panel's row, as set in Settings: recently watched, or your
     * favorites (in your favorites order). Never the channel playing, or a locked group's.
     */
    fun panelRowChannels(currentKey: String?): List<LiveChannel> = when (settings.value.panelRow) {
        "FAVORITES" -> {
            val user = userState.value
            val s = settings.value
            user.favorites.asSequence()
                .filter { it != currentKey }
                .mapNotNull { channelByKey(it) }
                .filter { ch -> !com.nuvio.tv.livetv.parental.ParentalControls.isLocked(ch.groupId, ch.group, s, user.lockedGroups) }
                .take(60)
                .toList()
        }
        "OFF" -> emptyList()
        else -> recentForPanel(currentKey)
    }

    /** Every channel in the current group, hidden ones included, for Manage visibility. */
    fun channelsWithHidden(): List<LiveChannel> {
        val user = userState.value.copy(hiddenChannels = emptySet())
        return buildUi(
            displayChannels.value, user, settings.value,
            session.currentGroupId.value ?: uiState.value.selectedGroupId, "", true
        ).channels
    }

    /** Every group you can hide (not Favorites, Recently watched or All), hidden ones included. */
    fun groupsWithHidden(): List<ChannelGroup> {
        val user = userState.value.copy(hiddenGroups = emptySet())
        return buildUi(displayChannels.value, user, settings.value, uiState.value.selectedGroupId, "", true)
            .groups.filter { !it.special && !it.id.startsWith(ChannelGroup.CUSTOM_PREFIX) }
    }

    fun saveGroupVisibility(inList: List<ChannelGroup>, hidden: Set<String>) = viewModelScope.launch {
        val ids = inList.map { it.id }.toSet()
        prefs.updateHiddenGroups(show = ids - hidden, hide = hidden intersect ids)
    }

    /** Saves Manage visibility: [hidden] is the full set of hidden keys among [inList]. */
    fun saveVisibility(inList: List<LiveChannel>, hidden: Set<String>) = viewModelScope.launch {
        val keys = inList.map { it.key }.toSet()
        prefs.updateHiddenChannels(show = keys - hidden, hide = hidden intersect keys)
    }

    fun updateSettings(transform: (LiveTvSettings) -> LiveTvSettings) {
        viewModelScope.launch { prefs.updateSettings(transform) }
    }

    fun requestGroupsOnReturn() {
        session.openGroupsOnReturn = true
    }

    fun consumeGroupsOnReturn(): Boolean =
        session.openGroupsOnReturn.also { session.openGroupsOnReturn = false }

    /** Whether the guide is being shown again after full screen (without using it up). */
    fun isReturningFromFullscreen(): Boolean = session.returningFromFullscreen

    /**
     * The group to show [channel] in when Live TV opens or you come back to it: the group you
     * watched it in (a custom group, Favorites…) if it's still there, otherwise the group on
     * screen, otherwise its own playlist group.
     */
    suspend fun groupToShow(channel: LiveChannel): String? {
        val ui = awaitGroups() ?: uiState.value
        val user = prefs.userState.first()
        val last = user.lastGroupId
        if (last != null && ui.groups.any { it.id == last } && channelInGroup(channel, last, user)) return last
        if (ui.channels.any { it.key == channel.key }) return null // already showing
        return ui.groupFor(channel)
    }

    private fun channelInGroup(channel: LiveChannel, groupId: String, user: LiveUserState): Boolean = when {
        groupId == ChannelGroup.ALL -> true
        groupId == ChannelGroup.FAVORITES -> channel.key in user.favorites
        groupId == ChannelGroup.RECENT -> channel.key in user.recent
        groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) ->
            user.customGroups.firstOrNull { it.id == groupId }?.channelKeys?.contains(channel.key) == true
        else -> channel.groupId == groupId || channel.key in user.channelCopies[groupId].orEmpty()
    }

    fun markFullscreenOpened() {
        session.returningFromFullscreen = true
    }

    /** True once, right after coming back from full screen. */
    fun consumeReturningFromFullscreen(): Boolean =
        session.returningFromFullscreen.also { session.returningFromFullscreen = false }

    /** The group list, once it has loaded (for opening Live TV on the groups). */
    suspend fun awaitGroups(): LiveTvUiState? =
        withTimeoutOrNull(10_000) { uiState.first { it.groups.size > 1 } }

    /** The catch-up program playing, and where in it the current stream starts. */
    data class CatchupSession(val channel: LiveChannel, val program: EpgProgram, val baseOffsetMs: Long)

    private val _catchup = MutableStateFlow<CatchupSession?>(null)
    val catchup: StateFlow<CatchupSession?> = _catchup

    /** Plays [program] from the archive, starting [offsetMs] into it. */
    fun playCatchup(channel: LiveChannel, program: EpgProgram, offsetMs: Long = 0L): Boolean {
        val catchup = channel.catchup ?: return false
        val now = System.currentTimeMillis()
        if (!CatchupUrlBuilder.isAvailable(catchup, program.startMs, now)) return false
        // Server time zone not known yet (M3U from an Xtream server): learn it first (a second
        // at most, once), then play, so the replay starts at the right time.
        if (playlistZones.value[channel.sourceId].isNullOrBlank() && channel.sourceId !in zoneAsked &&
            Regex("""/(?:live/)?[^/]+/[^/]+/\d+(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(channel.url)
        ) {
            zoneAsked += channel.sourceId
            viewModelScope.launch {
                val tz = kotlinx.coroutines.withTimeoutOrNull(4_000) { repository.learnServerZone(channel) }
                startCatchup(channel, catchup, program, offsetMs, tz)
            }
            return true
        }
        return startCatchup(channel, catchup, program, offsetMs, playlistZones.value[channel.sourceId])
    }

    /** Playlists whose server time zone has been asked for (once per session). */
    private val zoneAsked = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun startCatchup(
        channel: LiveChannel,
        catchup: com.nuvio.tv.livetv.model.CatchupInfo,
        program: EpgProgram,
        offsetMs: Long,
        zone: String?
    ): Boolean {
        val now = System.currentTimeMillis()
        // The guide's time shift is taken back off (the archive goes by the guide's own times),
        // then the catch-up time shift added, for providers whose archive is on another clock.
        val shift = settings.value.epgOffsetMinutes * 60_000L - settings.value.catchupShiftMinutes * 60_000L
        val start = program.startMs + offsetMs.coerceAtLeast(0)
        // Ask for more than the show itself: up to 3 hours past its end (but not past what has
        // aired). Shows that run over keep playing, and the next show follows on seamlessly.
        val windowStop = maxOf(program.stopMs, minOf(now - 30_000, program.stopMs + CONTINUE_MS))
        val url = CatchupUrlBuilder.build(
            channel.url, catchup, start - shift, windowStop - shift, now,
            preferHls = settings.value.catchupPreferHls &&
                now - (playlistNoHls.value[channel.sourceId] ?: 0L) > HLS_RETRY_MS,
            serverTimezone = zone
        ) ?: return false
        playback.play(
            channel, overrideUrl = url, catchupTitle = program.title,
            fallbacks = CatchupUrlBuilder.alternatives(
                url, zone,
                showMinutes = (program.stopMs - start) / 60_000,
                airedMinutes = if (now < program.stopMs) (now - start) / 60_000 else null
            ),
            catchupStartMs = start
        )
        _catchup.value = CatchupSession(channel, program, offsetMs.coerceAtLeast(0))
        // Load this channel's full schedule, so catch-up can follow on into later shows.
        repository.ensureChannelSchedule(channel.key)
        return true
    }

    /** Playlist id -> when HLS catch-up last failed there (see [PlaylistSource.catchupNoHlsSince]). */
    private val playlistNoHls: StateFlow<Map<String, Long>> = prefs.playlists
        .map { l -> l.associate { it.id to it.catchupNoHlsSince } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Playlist id -> Xtream server time zone (catch-up links are in server time). */
    private val playlistZones: StateFlow<Map<String, String>> = prefs.playlists
        .map { l -> l.associate { it.id to it.serverTimezone } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Catch-up carries on into the next show: when playback passes the end of the show it
     * started on, the title, info and seek bar move to the next show; if the replay window
     * ends, a new one starts from that point.
     */
    fun followCatchup() {
        val s = _catchup.value ?: return
        val pos = catchupPositionMs() ?: return
        val absolute = s.program.startMs + pos
        val list = programs.value[s.channel.key].orEmpty()
        val p = playback.player
        if (p?.playbackState == androidx.media3.common.Player.STATE_ENDED) {
            // The window we asked for ran out: start a new one right where we are.
            val at = list.firstOrNull { absolute >= it.startMs && absolute < it.stopMs } ?: return
            if (absolute < System.currentTimeMillis() - 60_000) playCatchup(s.channel, at, absolute - at.startMs)
            return
        }
        if (absolute < s.program.stopMs) return
        val next = list.firstOrNull { absolute >= it.startMs && absolute < it.stopMs }
            ?: list.firstOrNull { it.startMs >= s.program.stopMs }
            ?: return
        if (next.startMs == s.program.startMs) return
        _catchup.value = CatchupSession(s.channel, next, s.program.startMs + s.baseOffsetMs - next.startMs)
        playback.setCatchupTitle(next.title)
    }

    private fun catchupStartPossible(channel: LiveChannel): Boolean {
        val p = currentProgram(channel.key) ?: return false
        return CatchupUrlBuilder.isAvailable(channel.catchup, p.startMs, System.currentTimeMillis())
    }

    /** True if the show on now can be watched from its beginning: catch-up, or a recording running on the channel. */
    fun canWatchFromStart(channel: LiveChannel): Boolean =
        catchupStartPossible(channel) || recorder.activeFor(channel.key) != null

    /** What "from the beginning" means here: the whole show (catch-up), or from where the recording began. */
    fun restartLabel(channel: LiveChannel): String =
        if (catchupStartPossible(channel)) "Watch from the beginning" else "Watch from the start of the recording"

    /** Restarts the show that's on now, from its beginning (catch-up), or from the start of the running recording. */
    fun watchFromStart(channel: LiveChannel): Boolean {
        if (catchupStartPossible(channel)) {
            return currentProgram(channel.key)?.let { playCatchup(channel, it, 0L) } ?: false
        }
        val rec = recorder.activeFor(channel.key) ?: return false
        if (playback.playingChannel?.key != channel.key) preview(channel)
        playback.playFromRecording(recorder.playlistFile(rec), fromStart = true)
        return true
    }

    /** Where playback is, as time into the catch-up program. */
    fun catchupPositionMs(): Long? {
        val s = _catchup.value ?: return null
        // A replay that came as the live stream's rewind window: go by the stream's clock.
        playback.archiveOffsetMs(s.program.startMs)?.let { return it.coerceAtLeast(0) }
        val pos = playback.player?.currentPosition ?: 0L
        return s.baseOffsetMs + pos.coerceAtLeast(0)
    }

    /**
     * Seeks the catch-up program to [offsetMs]. Seeks inside the stream when the provider allows
     * it; otherwise asks the provider for the archive again, starting at that point.
     */
    fun seekCatchupTo(offsetMs: Long) {
        val s = _catchup.value ?: return
        val now = System.currentTimeMillis()
        val latest = (minOf(s.program.stopMs, now - 15_000) - s.program.startMs).coerceAtLeast(0)
        val target = offsetMs.coerceIn(0, latest)
        if (playback.seekArchiveTo(s.program.startMs, target)) return
        val p = playback.player
        val inStream = target - s.baseOffsetMs
        if (p != null && p.isCurrentMediaItemSeekable && p.duration != androidx.media3.common.C.TIME_UNSET &&
            inStream >= 0 && inStream <= p.duration
        ) {
            p.seekTo(inStream)
        } else {
            playCatchup(s.channel, s.program, target)
        }
    }

    fun backToLive(channel: LiveChannel) {
        _catchup.value = null
        playback.play(channel)
    }

    fun toggleFavorite(channel: LiveChannel) = viewModelScope.launch { prefs.toggleFavorite(channel.key) }
    fun moveFavorite(channel: LiveChannel, delta: Int) = viewModelScope.launch { prefs.moveFavorite(channel.key, delta) }
    fun hideChannel(channel: LiveChannel) = viewModelScope.launch { prefs.setChannelHidden(channel.key, true) }
    fun hideGroup(groupId: String) = viewModelScope.launch {
        // Favorites, Recently watched and All channels are switched off in the settings rather
        // than hidden like a playlist group (hiding them that way did nothing).
        when (groupId) {
            ChannelGroup.FAVORITES -> prefs.updateSettings { it.copy(showFavoritesGroup = false) }
            ChannelGroup.RECENT -> prefs.updateSettings { it.copy(showRecentGroup = false) }
            ChannelGroup.ALL -> prefs.updateSettings { it.copy(showAllChannelsGroup = false) }
            else -> prefs.setGroupHidden(groupId, true)
        }
        if (session.currentGroupId.value == groupId) selectGroup(uiState.value.defaultGroupId)
    }
    fun refresh() = repository.refreshAll(force = true)

    // ------------------------------------------------------------ per-channel EPG

    val epgSources: StateFlow<List<com.nuvio.tv.livetv.model.EpgSourceChannels>> = repository.epgSources
    /** Assign EPG's channel lists are being read (only when they weren't saved). */
    val epgListsLoading: StateFlow<Boolean> = repository.epgListsLoading
    /** Assign EPG opened: makes sure it has the guides' channel lists. */
    fun ensureEpgLists() = repository.ensureSourceLists()

    // ------------------------------------------------------------ home screen row

    /** One card in the home screen row: the channel, what's on now and what's next. */
    data class HomeRowEntry(val channel: LiveChannel, val now: EpgProgram?, val next: EpgProgram?)

    /**
     * The home row's channels (which group, in what order). Worked out only when channels, your
     * favorites or settings change, not every time the clock or the guide moves on (it used to
     * sort through every channel twice a minute while Home was open).
     */
    private val homeRowChannels = combine(displayChannels, userState, settings) { channels, user, s ->
        if (!s.homeRowEnabled || channels.isEmpty()) return@combine emptyList<LiveChannel>() to s
        val groupId = when (s.homeRowSource) {
            "favorites", "" -> ChannelGroup.FAVORITES
            "recent" -> ChannelGroup.RECENT
            else -> s.homeRowSource
        }
        buildUi(channels, user, s, groupId, "", true, exactGroup = true).channels to s
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

    val homeRow: StateFlow<List<HomeRowEntry>> = combine(
        homeRowChannels,
        programs,
        now
    ) { (list, s), progs, nowMs ->
        if (list.isEmpty()) return@combine emptyList()
        var entries = list.map { ch ->
            val l = progs[ch.key].orEmpty()
            val cur = l.firstOrNull { nowMs >= it.startMs && nowMs < it.stopMs }
            val nxt = l.firstOrNull { it.startMs >= (cur?.stopMs ?: nowMs) }
            HomeRowEntry(ch, cur, nxt)
        }
        if (s.homeRowHidePlaceholders) {
            entries = entries.filter { e ->
                e.now != null && !com.nuvio.tv.livetv.data.LiveTvRepository.isPlaceholderTitle(e.now.title)
            }
        }
        entries = when (s.homeRowSort) {
            "number" -> entries.sortedBy { it.channel.number }
            "ending" -> entries.sortedBy { it.now?.stopMs ?: Long.MAX_VALUE }
            else -> entries
        }
        if (s.homeRowLimit > 0) entries.take(s.homeRowLimit) else entries
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The channels the home screen row shows (Favorites, Recently watched or one of your own
     * groups), loaded first at start-up so the row fills in straight away.
     */
    private val homeRowKeys: kotlinx.coroutines.flow.Flow<List<String>> =
        combine(displayChannels, userState, settings) { channels, user, s ->
            if (!s.homeRowEnabled || channels.isEmpty()) emptyList()
            else {
                val groupId = when (s.homeRowSource) {
                    "favorites", "" -> ChannelGroup.FAVORITES
                    "recent" -> ChannelGroup.RECENT
                    else -> s.homeRowSource
                }
                val limit = if (s.homeRowLimit > 0) s.homeRowLimit * 3 else 200
                buildUi(channels, user, s, groupId, "", true, exactGroup = true).channels.take(limit).map { it.key }
            }
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    /** Home screen: play [channel] (the guide then opens on the row's group behind it). */
    fun playFromHome(channel: LiveChannel) {
        val s = settings.value
        selectGroup(
            when (s.homeRowSource) {
                "favorites", "" -> ChannelGroup.FAVORITES
                "recent" -> ChannelGroup.RECENT
                else -> s.homeRowSource
            }
        )
        preview(channel)
    }

    init {
        viewModelScope.launch {
            combine(
                uiState.map { it.channels.map { c -> c.key } }.distinctUntilChanged(),
                userState.map { it.favorites }.distinctUntilChanged(),
                homeRowKeys
            ) { a, b, c -> c + b + a.take(400) }
                .distinctUntilChanged()
                .collectLatest { keys ->
                    delay(1_500)
                    if (keys.isNotEmpty()) repository.rememberPriority(keys)
                }
        }
        // The group you open: its listings straight away, if they aren't loaded yet.
        viewModelScope.launch {
            uiState.map { it.channels.take(400).map { c -> c.key } }.distinctUntilChanged().collect { keys ->
                repository.loadChannelsNow(keys)
            }
        }
    }

    /** Guide scrolled ahead or back: load those hours from the guide database if needed. */
    fun ensureGuideRange(fromMs: Long, toMs: Long) = repository.ensureRange(fromMs, toMs)
    /** The group on screen: its listings for every day the guide keeps (see the repository). */
    fun ensureGroupFullRange(channelKeys: List<String>) = repository.ensureFullRange(channelKeys)
    /** Changes when the guide's days ahead need asking for again (see the repository). */
    val guideGeneration: StateFlow<Int> = repository.guideGeneration

    /** A channel's full schedule (overlay mode's days, catch-up). */
    fun ensureChannelSchedule(channelKey: String) {
        repository.ensureChannelSchedule(channelKey)
        displayChannels.value.firstOrNull { it.key == channelKey }?.let { repository.loadXtreamArchive(listOf(it)) }
    }

    /** Past listings from the Xtream catch-up archive for these channels (browsing back). */
    fun loadCatchupArchive(channels: List<LiveChannel>) = repository.loadXtreamArchive(channels)

    /** Listings from the Xtream API for channels the guide file has nothing for. */
    fun loadMissingListings(channels: List<LiveChannel>) = repository.loadXtreamArchive(channels, missingOnly = true)

    /** Assign EPG: make sure the full guide-channel lists are loaded (the saved guide skips them). */
    fun ensureEpgDetails() = repository.ensureEpgDetails()
    val epgAutoMatches: StateFlow<Map<String, com.nuvio.tv.livetv.model.EpgAssignment>> = repository.autoMatches

    /** Assign EPG changes waiting to be applied (all at once, when the panel closes). */
    @Volatile private var epgChangesPending = false

    /** What changed in Assign EPG since the last commit: channel key -> new guide (null = automatic). */
    private val pendingEpg = HashMap<String, com.nuvio.tv.livetv.model.EpgAssignment?>()

    private fun notePendingEpg(key: String, a: com.nuvio.tv.livetv.model.EpgAssignment?) {
        synchronized(pendingEpg) { pendingEpg[key] = a }
        epgChangesPending = true
        scheduleEpgCommit()
    }

    fun setChannelEpg(channel: LiveChannel, sourceId: String, xmltvId: String) = viewModelScope.launch {
        val a = com.nuvio.tv.livetv.model.EpgAssignment(sourceId, xmltvId)
        prefs.setEpgOverride(channel.key, a)
        notePendingEpg(channel.key, a)
    }

    private var epgCommitJob: kotlinx.coroutines.Job? = null

    /**
     * Applies Assign EPG changes a moment after the last one, so the guide shows them while the
     * panel is still open (they used to wait for the panel to close, which looked like nothing
     * happened). Several quick changes in a row still mean one rebuild.
     */
    private fun scheduleEpgCommit() {
        epgCommitJob?.cancel()
        epgCommitJob = viewModelScope.launch {
            delay(1_500)
            commitEpgChanges()
        }
    }

    /**
     * Applies the Assign EPG changes in one go. Rebuilding the guide after every single change
     * made assigning many channels slow; now it happens once, when you close the panel.
     */
    fun commitEpgChanges() {
        if (!epgChangesPending) return
        epgChangesPending = false
        val changes = synchronized(pendingEpg) { HashMap(pendingEpg).also { pendingEpg.clear() } }
        // Only the changed channels are updated (see applyEpgChanges); full re-match otherwise.
        if (changes.isEmpty()) repository.rematchEpg() else repository.applyEpgChanges(changes)
    }

    override fun onCleared() {
        commitEpgChanges()
        super.onCleared()
    }

    /** Re-downloads every guide and rebuilds the assignable channel lists. */
    fun fullEpgScan() = repository.refreshEpgOnly()

    fun resetChannelEpg(channel: LiveChannel) = viewModelScope.launch {
        prefs.setEpgOverride(channel.key, null)
        notePendingEpg(channel.key, null)
    }

    /**
     * "Unassign" in Assign EPG: the channel shows no guide at all (it used to only undo a
     * hand-picked guide, so automatically matched channels didn't change). Pressed again on an
     * unassigned channel, it goes back to automatic matching.
     */
    fun unassignChannelEpg(channel: LiveChannel) = viewModelScope.launch {
        val cur = prefs.userState.first().epgOverrides[channel.key]
        val next = if (cur?.isNone == true) null else com.nuvio.tv.livetv.model.EpgAssignment.None
        prefs.setEpgOverride(channel.key, next)
        notePendingEpg(channel.key, next)
    }

    /** Read provider artwork from the local EPG; never search addon catalogues on focus. */
    suspend fun posterFor(program: EpgProgram?, channel: LiveChannel? = null): String? {
        if (!settings.value.showPosters) return null
        val full = if (program != null && channel != null) details(channel.key, program) else program
        return full?.icon?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ show details on demand

    private val detailCache = com.nuvio.tv.livetv.data.boundedCache<String, EpgProgram>(800)

    /**
     * A show with its full details (description, cast, category, year). The guide keeps only
     * the short form of every listing in memory (much faster to load); the details of the show
     * you're looking at are fetched here, once, and remembered.
     */
    suspend fun details(channelKey: String, program: EpgProgram): EpgProgram {
        if (!repository.lightPrograms || program.description != null || program.people.isNotEmpty()) return program
        val key = "$channelKey|${program.startMs}"
        detailCache[key]?.let { return it }
        val shift = settings.value.epgOffsetMinutes * 60_000L
        val full = repository.programDetails(channelKey, program.startMs - shift) ?: return program
        val merged = program.copy(
            description = full.description,
            category = full.category,
            year = full.year,
            people = full.people,
            episode = program.episode ?: full.episode,
            icon = program.icon ?: full.icon
        )
        detailCache[key] = merged
        return merged
    }

    /** Remembers which channels to fill in first next time (the group on screen, favorites). */
    fun rememberPriority(keys: List<String>) = repository.rememberPriority(keys)

    // ------------------------------------------------------------ channel management

    fun renameChannel(channel: LiveChannel, name: String?) = viewModelScope.launch {
        val original = repository.channels.value.firstOrNull { it.key == channel.key }?.name
        prefs.setChannelName(channel.key, name?.takeIf { it.isNotBlank() && it != original })
    }

    fun setChannelNumber(channel: LiveChannel, number: Int?) = viewModelScope.launch {
        val original = repository.channels.value.firstOrNull { it.key == channel.key }?.number
        prefs.setChannelNumber(channel.key, number?.takeIf { it != original })
    }

    fun renameGroup(groupId: String, name: String?) = viewModelScope.launch { prefs.setGroupName(groupId, name) }

    /** Playlist id -> name, for the playlist headings in the group list. */
    val playlistNames: StateFlow<Map<String, String>> = prefs.playlists
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Playlists folded away in the group list (kept while the app is open). */
    val collapsedPlaylists = session.collapsedPlaylists

    fun togglePlaylistCollapsed(id: String) {
        val cur = session.collapsedPlaylists.value
        session.collapsedPlaylists.value = if (id in cur) cur - id else cur + id
    }

    /** Saves the whole group order at once (Reorder groups). */
    fun saveGroupOrder(ids: List<String>) {
        // Reordering by hand means "my order" from now on.
        if (settings.value.groupSort != "custom") viewModelScope.launch { prefs.updateSettings { it.copy(groupSort = "custom") } }
        if (ids.isEmpty()) return
        viewModelScope.launch { prefs.moveGroup(ids.first(), 0, ids) }
    }

    fun moveGroup(groupId: String, delta: Int) {
        viewModelScope.launch { prefs.moveGroup(groupId, delta, uiState.value.orderableGroupIds) }
    }

    fun createGroupWith(name: String, channel: LiveChannel?) = viewModelScope.launch {
        val id = prefs.createCustomGroup(name)
        channel?.let { prefs.addToCustomGroup(id, it.key) }
    }

    fun addToGroup(groupId: String, channel: LiveChannel) = viewModelScope.launch { prefs.addToCustomGroup(groupId, channel.key) }
    fun removeFromGroup(groupId: String, channel: LiveChannel) = viewModelScope.launch { prefs.removeFromCustomGroup(groupId, channel.key) }
    fun moveInGroup(groupId: String, channel: LiveChannel, delta: Int) = viewModelScope.launch {
        prefs.moveInCustomGroup(groupId, channel.key, delta)
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch {
        prefs.deleteCustomGroup(groupId)
        if (session.currentGroupId.value == groupId) selectGroup(uiState.value.defaultGroupId)
    }

    fun channelByKey(key: String?): LiveChannel? =
        key?.let { k -> displayChannels.value.firstOrNull { it.key == k } ?: playback.playingChannel?.takeIf { it.key == k } }

    // ------------------------------------------------------------ recording

    val schedules = scheduler.schedules
    val seriesRules = scheduler.series

    /** Record a show from the guide (or cancel it if it's already set). Returns a message. */
    fun toggleScheduled(channel: LiveChannel, program: EpgProgram): String {
        if (scheduler.isScheduled(channel.key, program)) {
            scheduler.cancel(channel.key, program.startMs)
            return "Recording cancelled"
        }
        // On now, on a channel without catch-up: record it now (sharing the connection if you're
        // watching it). With catch-up, the whole show is saved from catch-up after it ends.
        val now = System.currentTimeMillis()
        if (channel.catchup == null && now >= program.startMs && now < program.stopMs && recorder.activeFor(channel.key) == null) {
            return toggleRecording(channel)
        }
        return scheduler.add(channel, program)
    }

    /** Record every airing of this show on this channel (or stop that). Returns a message. */
    fun toggleSeries(channel: LiveChannel, program: EpgProgram): String {
        scheduler.seriesFor(channel.key, titleMarks(program.title).title)?.let {
            scheduler.removeSeries(it.id)
            return "Stopped recording \"${it.title}\""
        }
        return scheduler.addSeries(channel, program)
    }

    fun isSeries(channel: LiveChannel, program: EpgProgram): Boolean =
        scheduler.seriesFor(channel.key, titleMarks(program.title).title) != null

    /** Every recording (for the REC marks in the guide and the player). */
    val recordings = recorder.recordings

    /**
     * Record the show on now on [channel] (or stop the recording running on it). Recording
     * starts from now and runs to 5 minutes past the show's end (an hour with no guide). If
     * you're watching this channel, you carry on watching from the recording, so one connection
     * to the provider does both. Returns a message to show.
     */
    fun toggleRecording(channel: LiveChannel): String {
        recorder.activeFor(channel.key)?.let { running ->
            recorder.stop(running.id)
            return "Recording stopped. It's in Recordings."
        }
        val now = System.currentTimeMillis()
        val show = currentProgram(channel.key, now)?.takeIf { !LiveTvRepository.isPlaceholderTitle(it.title) }
        val stopAt = if (show != null && show.stopMs > now) show.stopMs + 5 * 60_000L else now + 60 * 60_000L
        val title = show?.title?.let { titleMarks(it).title } ?: channel.name
        // Roughly 2 GB an hour in HD: say so if it may not fit.
        val needed = (stopAt - now) / 3_600_000.0 * 2.0 * 1024 * 1024 * 1024
        val watching = playback.playingChannel?.key == channel.key && playback.state.value.catchupTitle == null
        val url = playback.liveUrlFor(channel)
        if (watching) playback.handOverForRecording()
        val rec = recorder.start(
            channel = channel,
            url = url,
            title = title,
            episode = show?.episode,
            description = show?.description,
            programStartMs = show?.startMs ?: 0L,
            programStopMs = show?.stopMs ?: 0L,
            stopAtMs = stopAt,
            startDelayMs = if (watching) 1_500L else 0L
        )
        if (watching) playback.playFromRecording(recorder.playlistFile(rec))
        val until = formatClock(stopAt, settings.value.use24HourClock)
        return if (recorder.freeBytes() < needed) "Recording \"$title\" until $until. Storage is low, so it may stop early."
        else "Recording \"$title\" until $until"
    }

    fun currentProgram(channelKey: String, at: Long = _now.value): EpgProgram? =
        programs.value[channelKey]?.firstOrNull { at >= it.startMs && at < it.stopMs }

    fun nextProgram(channelKey: String, at: Long = _now.value): EpgProgram? =
        programs.value[channelKey]?.firstOrNull { it.startMs >= at }

    /** Channel list used for up/down zapping in the full-screen player. */
    fun zapList(): List<LiveChannel> {
        val ui = uiState.value
        return if (settings.value.zapMode == ZapMode.ALL || ui.channels.isEmpty()) ui.allVisibleChannels else ui.channels
    }

    fun zap(currentKey: String?, direction: Int): LiveChannel? {
        val list = zapList()
        if (list.isEmpty()) return null
        val dir = if (settings.value.reverseZap) -direction else direction
        val idx = list.indexOfFirst { it.key == currentKey }
        val next = if (idx < 0) 0 else Math.floorMod(idx + dir, list.size)
        return list[next]
    }

    /** The channel with this number, looking in the current group first (numbers can repeat). */
    fun channelByNumber(number: Int): LiveChannel? =
        uiState.value.channels.firstOrNull { it.number == number }
            ?: uiState.value.allVisibleChannels.firstOrNull { it.number == number }

    // ------------------------------------------------------------ reorder / copy

    /** Groups you can reorder: not Search or Recently watched (that one follows what you watch). */
    fun canReorder(groupId: String) = groupId != ChannelGroup.SEARCH && groupId != ChannelGroup.RECENT

    fun saveOrder(groupId: String, keys: List<String>) = viewModelScope.launch {
        when {
            groupId == ChannelGroup.FAVORITES -> prefs.setFavoritesOrder(keys)
            groupId.startsWith(ChannelGroup.CUSTOM_PREFIX) -> prefs.setCustomGroupOrder(groupId, keys)
            else -> prefs.setChannelOrder(groupId, keys)
        }
    }

    /** Copy to a playlist group, or add to one of your own groups. */
    fun copyToGroup(groupId: String, channel: LiveChannel) = viewModelScope.launch {
        if (groupId.startsWith(ChannelGroup.CUSTOM_PREFIX)) prefs.addToCustomGroup(groupId, channel.key)
        else prefs.copyChannel(groupId, channel.key)
    }

    /** True if [channel] is only in [groupId] because it was copied there. */
    fun isCopyIn(groupId: String, channel: LiveChannel): Boolean =
        channel.groupId != groupId && channel.key in userState.value.channelCopies[groupId].orEmpty()

    fun removeCopy(groupId: String, channel: LiveChannel) = viewModelScope.launch {
        prefs.removeChannelCopy(groupId, channel.key)
    }

    /** Groups a channel can be copied to (yours and the playlist's), minus the one it's in. */
    fun copyTargets(channel: LiveChannel): List<ChannelGroup> =
        uiState.value.groups.filter { g ->
            !g.special && g.id != channel.groupId &&
                (g.id.startsWith(ChannelGroup.CUSTOM_PREFIX) ||
                    channel.key !in userState.value.channelCopies[g.id].orEmpty())
        }

    fun previousChannel(): LiveChannel? = channelByKey(userState.value.previousChannelKey)

    /** The channel to start in the preview when Live TV opens ("Resume last channel in preview"). */
    suspend fun resumeCandidate(): LiveChannel? {
        val s = prefs.currentSettings()
        if (!s.resumeLastInPreview || !s.showPreview) return null
        val key = prefs.userState.first().lastChannelKey ?: return null
        val channels = withTimeoutOrNull(15_000) {
            displayChannels.first { list -> list.any { it.key == key } }
        } ?: return null
        if (skipCrashed(key)) return null
        return channels.firstOrNull { it.key == key }
    }

    /**
     * The app closed while [key] was starting last time: don't start it by itself again (a
     * channel that freezes the app would otherwise close it every time Live TV opens).
     */
    private fun skipCrashed(key: String): Boolean {
        if (playback.crashedWhileStarting != key || session.crashNoticeShown) return false
        session.crashNoticeShown = true
        val name = displayChannels.value.firstOrNull { it.key == key }?.name ?: "the last channel"
        com.nuvio.tv.livetv.record.LiveTvNotice.show("Live TV closed while $name was starting last time, so it wasn't started again. Pick it to try again.")
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Start-up: the app closed while $name was starting last time; not starting it by itself")
        return true
    }

    /** The last watched channel, once per app launch, when "auto-play last channel" is on. */
    suspend fun autoPlayCandidate(): LiveChannel? {
        if (session.autoPlayedThisLaunch) return null
        session.autoPlayedThisLaunch = true
        if (!prefs.currentSettings().autoPlayLastChannel) return null
        val key = prefs.userState.first().lastChannelKey ?: return null
        val channels = withTimeoutOrNull(15_000) {
            repository.channels.first { it.isNotEmpty() }
        } ?: return null
        if (skipCrashed(key)) return null
        return channels.firstOrNull { it.key == key }
            ?.let { c -> displayChannels.value.firstOrNull { it.key == c.key } ?: c }
    }
}

/** How far past the end of a show a catch-up replay keeps going (into the next shows). */
private const val CONTINUE_MS = 3L * 60 * 60 * 1000

/** How long catch-up sticks to TS on a provider whose HLS replays failed, before trying HLS again. */
private const val HLS_RETRY_MS = 7L * 24 * 3_600_000L

/**
 * A channel's name without what differs between copies of the same channel: a country or
 * provider prefix ("US: ", "UK | "), bracketed notes and quality or backup words ("HD", "4K",
 * "Backup", "FHD"…). "US: ESPN HD" and "ESPN (Backup)" both give "espn".
 */
// Made once (making a pattern is slow; this runs for every channel in the list).
private val BACKUP_PREFIX = Regex("""^\s*[a-z]{2,4}\s*[:|]\s*""")
private val BACKUP_PREFIX_DASH = Regex("""^\s*[a-z]{2,3}\s+-\s+""")
private val BACKUP_BRACKETS = Regex("""[\[(][^\])]*[\])]""")
private val BACKUP_NOISE = Regex("""\b(hd|fhd|uhd|sd|4k|8k|hevc|h\.?26[45]|1080[pi]?|720p?|2160p?|\d{2}\s?fps|backup|bkp|bk|alt|alternate|raw|ᴴᴰ)\b""")

internal fun backupName(name: String): String {
    var s = name.lowercase()
    s = s.replace(BACKUP_PREFIX, "")
    s = s.replace(BACKUP_PREFIX_DASH, "")
    s = s.replace(BACKUP_BRACKETS, " ")
    s = s.replace(BACKUP_NOISE, " ")
    return s.filter { it.isLetterOrDigit() || it == ' ' }.split(' ').filter { it.isNotBlank() }.joinToString(" ")
}

private val STATION_NOTE = Regex("""[\[(]([^\])]*)[\])]""")

/** The call sign or region in brackets ("FOX (WNYW)" -> "wnyw"), not quality notes like "(HD)". */
internal fun stationNote(name: String): String? =
    STATION_NOTE.findAll(name.lowercase()).map { it.groupValues[1].trim() }
        .firstOrNull { it.isNotBlank() && backupName(it).isNotBlank() }
