package com.nuvio.tv.livetv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.matchFrameRate
import com.nuvio.tv.livetv.model.CustomGroup
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.Reminder
import com.nuvio.tv.livetv.model.ZapMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveTvPreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = {
            context.preferencesDataStoreFile("live_tv").apply {
                parentFile?.mkdirs()
                // Match Nuvio's first-read safeguard without replacing existing preferences.
                createNewFile()
            }
        }
    )

    /**
     * Small, frequently written, this-TV-only data (quality badges, On Demand import times),
     * kept apart so saving it doesn't rewrite and re-read the main settings every time.
     */
    private val deviceStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = {
            context.preferencesDataStoreFile("live_tv_device").apply {
                parentFile?.mkdirs()
                // Match Nuvio's first-read safeguard without replacing existing preferences.
                createNewFile()
            }
        }
    )

    private object Keys {
        val playlists = stringPreferencesKey("playlists")
        val epgs = stringPreferencesKey("epg_sources")

        val showInSidebar = booleanPreferencesKey("show_in_sidebar")
        val showNames = booleanPreferencesKey("show_channel_names")
        val showNumbers = booleanPreferencesKey("show_channel_numbers")
        val showLogos = booleanPreferencesKey("show_channel_logos")
        val showPreview = booleanPreferencesKey("show_preview")
        val showDetails = booleanPreferencesKey("show_program_details")
        val showFavGroup = booleanPreferencesKey("show_favorites_group")
        val showRecentGroup = booleanPreferencesKey("show_recent_group")
        val compactRows = booleanPreferencesKey("compact_rows")
        val showProgramTimes = booleanPreferencesKey("show_program_times")
        val showEpisodeNumbers = booleanPreferencesKey("show_episode_numbers")
        val guideSize = stringPreferencesKey("guide_size")
        val epgOffset = intPreferencesKey("epg_offset_minutes")
        val reverseZap = booleanPreferencesKey("reverse_zap")
        val zapMode = stringPreferencesKey("zap_mode")
        val autoPlayLast = booleanPreferencesKey("auto_play_last")
        val rememberGroup = booleanPreferencesKey("remember_group")
        val fullscreenOnSelect = booleanPreferencesKey("fullscreen_on_select")
        val bannerSeconds = intPreferencesKey("banner_seconds")
        val playlistRefresh = intPreferencesKey("playlist_refresh_hours")
        val epgRefresh = intPreferencesKey("epg_refresh_hours")
        val epgPast = intPreferencesKey("epg_past_hours")
        val epgFuture = intPreferencesKey("epg_future_days")
        val clock24 = booleanPreferencesKey("clock_24h")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val autoBackupChannel = booleanPreferencesKey("auto_backup_channel")
        val catchupCache = booleanPreferencesKey("catchup_cache")
        val channelSort = stringPreferencesKey("channel_sort")
        val solidHighlight = booleanPreferencesKey("solid_highlight")
        val showInSearch = booleanPreferencesKey("show_in_search")
        val guideSearchLive = booleanPreferencesKey("guide_search_live")
        val guideSearchCatalog = booleanPreferencesKey("guide_search_catalog")
        val guideSearchOnDemand = booleanPreferencesKey("guide_search_ondemand")
        val vodSearchLive = booleanPreferencesKey("vod_search_live")
        val vodSearchCatalog = booleanPreferencesKey("vod_search_catalog")
        val vodSearchOnDemand = booleanPreferencesKey("vod_search_ondemand")
        val showAllGroup = booleanPreferencesKey("show_all_group")
        val resumeInPreview = booleanPreferencesKey("resume_in_preview")
        val showGroupCounts = booleanPreferencesKey("show_group_counts")
        val smallHeader = booleanPreferencesKey("small_header")
        val aspectMode = stringPreferencesKey("aspect_mode")
        val sequentialNumbers = booleanPreferencesKey("sequential_numbers")
        val overlayMode = booleanPreferencesKey("overlay_mode")
        val startOnLiveTv = booleanPreferencesKey("start_on_live_tv")
        val startPage = stringPreferencesKey("start_page")
        val catchupPreferHls = booleanPreferencesKey("catchup_prefer_hls")
        val catchupShift = intPreferencesKey("catchup_shift_minutes")
        val parentalPin = stringPreferencesKey("parental_pin")
        val lockAdultContent = booleanPreferencesKey("lock_adult_content")
        val showQualityBadges = booleanPreferencesKey("show_quality_badges")
        val showFrameRate = booleanPreferencesKey("show_frame_rate")
        val matchFrameRate = booleanPreferencesKey("match_frame_rate")
        val frameRateMode = stringPreferencesKey("frame_rate_mode")
        val onDemandInSidebar = booleanPreferencesKey("on_demand_in_sidebar")
        val recordingsInSidebar = booleanPreferencesKey("recordings_in_sidebar")
        val onDemandInStreams = booleanPreferencesKey("on_demand_in_streams")
        val onDemandStreamsPosition = stringPreferencesKey("on_demand_streams_position")
        val onDemandAutoPlay = booleanPreferencesKey("on_demand_auto_play")
        val searchOrder = stringPreferencesKey("search_order")
        val searchPreview = booleanPreferencesKey("search_preview")
        val onDemandAddonPosters = booleanPreferencesKey("on_demand_addon_posters")
        val groupPlaylistHeadings = booleanPreferencesKey("group_playlist_headings")
        /** This TV has had Group by Playlist turned on once (per TV, never synced). */
        val groupPlaylistTurnedOn = booleanPreferencesKey("group_playlist_turned_on_once")
        /** This TV only: ids of playlists and guides removed here, not yet passed on by sync. */
        val removedSources = stringSetPreferencesKey("removed_sources")
        val nameRemovals = stringPreferencesKey("name_removals")
        val developerTools = booleanPreferencesKey("developer_tools")
        val browseByChannelName = booleanPreferencesKey("browse_by_channel_name")
        val showPlaylistInInfo = booleanPreferencesKey("show_playlist_in_info")
        val showNowLine = booleanPreferencesKey("show_now_line")
        val audioPassthrough = booleanPreferencesKey("audio_passthrough")
        val showPosters = booleanPreferencesKey("show_posters")
        val vodMergeDuplicates = booleanPreferencesKey("vod_merge_duplicates")
        val vodDefaultSort = stringPreferencesKey("vod_default_sort")
        val recordingStorage = stringPreferencesKey("recording_storage")
        val recordingSpaceRule = stringPreferencesKey("recording_space_rule")
        val recordPre = intPreferencesKey("record_pre_minutes")
        val recordPost = intPreferencesKey("record_post_minutes")
        val vodSorts = stringPreferencesKey("vod_sorts")
        val homeRowEnabled = booleanPreferencesKey("home_row_enabled")
        val homeRowHiddenProfiles = stringSetPreferencesKey("home_row_hidden_profiles")
        val homeRowAboveContinueWatching = booleanPreferencesKey("home_row_above_cw")
        val homeRowSource = stringPreferencesKey("home_row_source")
        val homeRowTitle = stringPreferencesKey("home_row_title")
        val homeRowSort = stringPreferencesKey("home_row_sort")
        val homeRowLimit = intPreferencesKey("home_row_limit")
        val homeRowHidePlaceholders = booleanPreferencesKey("home_row_hide_placeholders")
        val homeRowOpensGuide = booleanPreferencesKey("home_row_opens_guide")
        val homeRowShowNext = booleanPreferencesKey("home_row_show_next")
        val homeRowCompact = booleanPreferencesKey("home_row_compact")
        val bufferSize = stringPreferencesKey("buffer_size")
        val groupSort = stringPreferencesKey("group_sort")
        val timeshiftEnabled = booleanPreferencesKey("timeshift_enabled")
        val timeshiftMinutes = intPreferencesKey("timeshift_minutes")
        val rewindAlways = booleanPreferencesKey("rewind_always")
        val captionsByDefault = booleanPreferencesKey("captions_by_default")
        val clockMode = stringPreferencesKey("clock_mode")
        val playlistAccounts = stringPreferencesKey("playlist_accounts")
        val clockPosition = stringPreferencesKey("clock_position")
        val clockSize = stringPreferencesKey("clock_size")
        val clockOpacity = intPreferencesKey("clock_opacity")
        val clockBackground = booleanPreferencesKey("clock_background")
        val panelBackground = stringPreferencesKey("panel_background")
        val guideInfoBackground = stringPreferencesKey("guide_info_background")
        val guideBackdrop = stringPreferencesKey("guide_backdrop")
        val recentInPanel = booleanPreferencesKey("recent_in_panel")
        val panelRow = stringPreferencesKey("panel_row")
        val channelSurf = booleanPreferencesKey("channel_surf")
        val barHidden = stringPreferencesKey("bar_hidden")
        val guideInfoHidden = stringPreferencesKey("guide_info_hidden")
        val launcherRows = stringPreferencesKey("launcher_rows")
        val lockedGroups = stringSetPreferencesKey("locked_groups")
        val reminders = stringPreferencesKey("reminders")
        val channelQuality = stringPreferencesKey("channel_quality")
        val channelAudio = stringPreferencesKey("channel_audio")
        val vodHiddenCategories = stringSetPreferencesKey("vod_hidden_categories")
        val vodGroupOrder = stringPreferencesKey("vod_group_order")
        val vodGroupNames = stringPreferencesKey("vod_group_names")
        val onDemandImports = stringPreferencesKey("on_demand_imports")
        val channelOrder = stringPreferencesKey("channel_order")
        val channelCopies = stringPreferencesKey("channel_copies")

        val hiddenChannels = stringSetPreferencesKey("hidden_channels")
        val hiddenGroups = stringSetPreferencesKey("hidden_groups")
        val favorites = stringPreferencesKey("favorites")
        val recent = stringPreferencesKey("recent")
        val lastChannel = stringPreferencesKey("last_channel")
        val previousChannel = stringPreferencesKey("previous_channel")
        val lastGroup = stringPreferencesKey("last_group")
        val channelNames = stringPreferencesKey("channel_names")
        val channelNumbers = stringPreferencesKey("channel_numbers")
        val groupNames = stringPreferencesKey("group_names")
        val groupOrder = stringPreferencesKey("group_order")
        val customGroups = stringPreferencesKey("custom_groups")
        val epgOverrides = stringPreferencesKey("epg_overrides")
        /** Channel key → PictureShape name, for channels set away from Auto. */
        val pictureShapes = stringPreferencesKey("picture_shapes")
        /** Sports: settings and channel fixes (shared by every profile), as JSON. */
        val sports = stringPreferencesKey("sports")
        /** Sports: the teams, leagues and names you follow, and game reminders (per profile), as JSON. */
        val sportsMine = stringPreferencesKey("sports_mine")
    }

    // ---------- Nuvio profiles ----------
    // Favorites, recents, hidden and arranged groups/channels, reminders and On Demand's hidden
    // categories and order are kept per Nuvio profile. Playlists, guides, settings, renames,
    // channel numbers, EPG choices and parental locks stay shared by every profile.
    // The first profile uses the original keys (so nothing moves for it); the others use the same
    // names with "_p<id>" on the end, the same way Nuvio names its own per-profile files.

    private val currentProfile: Int get() = profileManager.activeProfileId.value

    private val personalStringKeys: List<Preferences.Key<String>> = listOf(
        Keys.favorites, Keys.recent, Keys.lastChannel, Keys.previousChannel, Keys.lastGroup,
        Keys.groupOrder, Keys.customGroups, Keys.channelOrder, Keys.channelCopies, Keys.reminders,
        Keys.vodGroupOrder, Keys.vodSorts, Keys.sportsMine
    )
    private val personalSetKeys: List<Preferences.Key<Set<String>>> = listOf(
        Keys.hiddenChannels, Keys.hiddenGroups, Keys.vodHiddenCategories
    )

    @JvmName("myString")
    private fun my(key: Preferences.Key<String>, id: Int = currentProfile): Preferences.Key<String> =
        if (id <= 1) key else stringPreferencesKey("${key.name}_p$id")

    @JvmName("mySet")
    private fun my(key: Preferences.Key<Set<String>>, id: Int = currentProfile): Preferences.Key<Set<String>> =
        if (id <= 1) key else stringSetPreferencesKey("${key.name}_p$id")

    private fun seededKey(id: Int) = booleanPreferencesKey("profile_seeded_p$id")
    private val SEEDED = Regex("""^profile_seeded_p(\d+)$""")

    /**
     * The stored data as profile [id] sees it: its own copies of the personal keys put in place
     * of the first profile's. A profile that hasn't been set up yet sees the first profile's.
     */
    private fun profileView(p: Preferences, id: Int): Preferences {
        if (id <= 1) return p
        val seeded = p[seededKey(id)] == true
        val m = p.toMutablePreferences()
        personalStringKeys.forEach { k ->
            val own = p[my(k, id)]
            if (own != null) m[k] = own else if (seeded) m.remove(k)
        }
        personalSetKeys.forEach { k ->
            val own = p[my(k, id)]
            if (own != null) m[k] = own else if (seeded) m.remove(k)
        }
        return m
    }

    /**
     * The first time a profile uses Live TV, it starts with a copy of the first profile's
     * favorites, hidden groups and so on; from then on each profile changes only its own.
     */
    private suspend fun seedProfile(id: Int) {
        if (id <= 1) return
        store.edit { p ->
            if (p[seededKey(id)] == true) return@edit
            personalStringKeys.forEach { k -> if (p[my(k, id)] == null) p[k]?.let { p[my(k, id)] = it } }
            personalSetKeys.forEach { k -> if (p[my(k, id)] == null) p[k]?.let { p[my(k, id)] = it } }
            p[seededKey(id)] = true
        }
    }

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching { turnOnPlaylistGroupsOnce() }
        }
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            profileManager.activeProfileId.collect { id -> runCatching { seedProfile(id) } }
        }
    }

    /**
     * Group by Playlist, switched on once for everyone (people with several playlists kept asking
     * where it was): the first time this version starts on a TV. Turned off after that, it stays
     * off: this TV remembers it's been done (kept per TV, not synced) and never does it again.
     */
    private suspend fun turnOnPlaylistGroupsOnce() {
        if (deviceStore.data.first()[Keys.groupPlaylistTurnedOn] == true) return
        store.edit { it[Keys.groupPlaylistHeadings] = true }
        deviceStore.edit { it[Keys.groupPlaylistTurnedOn] = true }
    }

    val playlists: Flow<List<PlaylistSource>> = store.data
        .map { decodePlaylists(it[Keys.playlists]) }
        .distinctUntilChanged()

    val epgSources: Flow<List<EpgSource>> = store.data
        .map { decodeEpgs(it[Keys.epgs]) }
        .distinctUntilChanged()

    val settings: Flow<LiveTvSettings> = store.data.map { p ->
        val d = LiveTvSettings()
        LiveTvSettings(
            showInSidebar = p[Keys.showInSidebar] ?: d.showInSidebar,
            showChannelNames = p[Keys.showNames] ?: d.showChannelNames,
            showChannelNumbers = p[Keys.showNumbers] ?: d.showChannelNumbers,
            showChannelLogos = p[Keys.showLogos] ?: d.showChannelLogos,
            showPreview = p[Keys.showPreview] ?: d.showPreview,
            showProgramDetails = p[Keys.showDetails] ?: d.showProgramDetails,
            showFavoritesGroup = p[Keys.showFavGroup] ?: d.showFavoritesGroup,
            showRecentGroup = p[Keys.showRecentGroup] ?: d.showRecentGroup,
            compactRows = p[Keys.compactRows] ?: d.compactRows,
            showProgramTimes = p[Keys.showProgramTimes] ?: d.showProgramTimes,
            showEpisodeNumbers = p[Keys.showEpisodeNumbers] ?: d.showEpisodeNumbers,
            guideSize = p[Keys.guideSize] ?: d.guideSize,
            epgOffsetMinutes = p[Keys.epgOffset] ?: d.epgOffsetMinutes,
            reverseZap = p[Keys.reverseZap] ?: d.reverseZap,
            zapMode = p[Keys.zapMode]?.let { runCatching { ZapMode.valueOf(it) }.getOrNull() } ?: d.zapMode,
            autoPlayLastChannel = p[Keys.autoPlayLast] ?: d.autoPlayLastChannel,
            rememberLastGroup = p[Keys.rememberGroup] ?: d.rememberLastGroup,
            openFullscreenOnSelect = p[Keys.fullscreenOnSelect] ?: d.openFullscreenOnSelect,
            infoBannerSeconds = p[Keys.bannerSeconds] ?: d.infoBannerSeconds,
            playlistRefreshHours = p[Keys.playlistRefresh] ?: d.playlistRefreshHours,
            epgRefreshHours = p[Keys.epgRefresh] ?: d.epgRefreshHours,
            epgPastHours = p[Keys.epgPast] ?: d.epgPastHours,
            epgFutureDays = p[Keys.epgFuture] ?: d.epgFutureDays,
            use24HourClock = p[Keys.clock24] ?: d.use24HourClock,
            autoReconnect = p[Keys.autoReconnect] ?: d.autoReconnect,
            autoBackupChannel = p[Keys.autoBackupChannel] ?: d.autoBackupChannel,
            catchupCache = p[Keys.catchupCache] ?: d.catchupCache,
            channelSort = p[Keys.channelSort]?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() } ?: d.channelSort,
            solidHighlight = p[Keys.solidHighlight] ?: d.solidHighlight,
            showInSearch = p[Keys.showInSearch] ?: d.showInSearch,
            guideSearchLive = p[Keys.guideSearchLive] ?: d.guideSearchLive,
            guideSearchCatalog = p[Keys.guideSearchCatalog] ?: d.guideSearchCatalog,
            guideSearchOnDemand = p[Keys.guideSearchOnDemand] ?: d.guideSearchOnDemand,
            onDemandSearchLive = p[Keys.vodSearchLive] ?: d.onDemandSearchLive,
            onDemandSearchCatalog = p[Keys.vodSearchCatalog] ?: d.onDemandSearchCatalog,
            onDemandSearchOnDemand = p[Keys.vodSearchOnDemand] ?: d.onDemandSearchOnDemand,
            showAllChannelsGroup = p[Keys.showAllGroup] ?: d.showAllChannelsGroup,
            resumeLastInPreview = p[Keys.resumeInPreview] ?: d.resumeLastInPreview,
            showGroupCounts = p[Keys.showGroupCounts] ?: d.showGroupCounts,
            smallHeader = p[Keys.smallHeader] ?: d.smallHeader,
            aspectMode = p[Keys.aspectMode] ?: d.aspectMode,
            sequentialNumbers = p[Keys.sequentialNumbers] ?: d.sequentialNumbers,
            overlayMode = p[Keys.overlayMode] ?: d.overlayMode,
            startOnLiveTv = p[Keys.startOnLiveTv] ?: d.startOnLiveTv,
            startPage = p[Keys.startPage] ?: d.startPage,
            catchupPreferHls = p[Keys.catchupPreferHls] ?: d.catchupPreferHls,
            catchupShiftMinutes = p[Keys.catchupShift] ?: d.catchupShiftMinutes,
            parentalPin = p[Keys.parentalPin] ?: d.parentalPin,
            lockAdultContent = p[Keys.lockAdultContent] ?: d.lockAdultContent,
            showQualityBadges = p[Keys.showQualityBadges] ?: d.showQualityBadges,
            showFrameRate = p[Keys.showFrameRate] ?: d.showFrameRate,
            // Before the three choices it was on or off; on worked like "On start/stop".
            frameRateMode = p[Keys.frameRateMode] ?: if (p[Keys.matchFrameRate] == true) "START_STOP" else d.frameRateMode,
            onDemandInSidebar = p[Keys.onDemandInSidebar] ?: d.onDemandInSidebar,
            recordingsInSidebar = p[Keys.recordingsInSidebar] ?: d.recordingsInSidebar,
            onDemandInStreams = p[Keys.onDemandInStreams] ?: d.onDemandInStreams,
            onDemandStreamsPosition = p[Keys.onDemandStreamsPosition] ?: d.onDemandStreamsPosition,
            onDemandAutoPlay = p[Keys.onDemandAutoPlay] ?: d.onDemandAutoPlay,
            searchOrder = p[Keys.searchOrder] ?: d.searchOrder,
            searchPreview = p[Keys.searchPreview] ?: d.searchPreview,
            onDemandAddonPosters = p[Keys.onDemandAddonPosters] ?: d.onDemandAddonPosters,
            groupPlaylistHeadings = p[Keys.groupPlaylistHeadings] ?: d.groupPlaylistHeadings,
            nameRemovals = p[Keys.nameRemovals] ?: d.nameRemovals,
            developerTools = p[Keys.developerTools] ?: d.developerTools,
            browseByChannelName = p[Keys.browseByChannelName] ?: d.browseByChannelName,
            showPlaylistInInfo = p[Keys.showPlaylistInInfo] ?: d.showPlaylistInInfo,
            showNowLine = p[Keys.showNowLine] ?: d.showNowLine,
            audioPassthrough = p[Keys.audioPassthrough] ?: d.audioPassthrough,
            showPosters = p[Keys.showPosters] ?: d.showPosters,
            vodMergeDuplicates = p[Keys.vodMergeDuplicates] ?: d.vodMergeDuplicates,
            vodDefaultSort = p[Keys.vodDefaultSort] ?: d.vodDefaultSort,
            recordingStorage = p[Keys.recordingStorage] ?: d.recordingStorage,
            recordingSpaceRule = p[Keys.recordingSpaceRule] ?: d.recordingSpaceRule,
            recordPreMinutes = p[Keys.recordPre] ?: d.recordPreMinutes,
            recordPostMinutes = p[Keys.recordPost] ?: d.recordPostMinutes,
            // The row used to be on/off for the whole app. Turned off before (with no per-profile
            // choice saved yet): that was done from the main profile, so it stays off there only.
            homeRowEnabled = true,
            homeRowHiddenProfiles = p[Keys.homeRowHiddenProfiles]?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: if (p[Keys.homeRowEnabled] == false) setOf(1) else emptySet(),
            homeRowAboveContinueWatching = p[Keys.homeRowAboveContinueWatching] ?: d.homeRowAboveContinueWatching,
            homeRowSource = p[Keys.homeRowSource] ?: d.homeRowSource,
            homeRowTitle = p[Keys.homeRowTitle] ?: d.homeRowTitle,
            homeRowSort = p[Keys.homeRowSort] ?: d.homeRowSort,
            homeRowLimit = p[Keys.homeRowLimit] ?: d.homeRowLimit,
            homeRowHidePlaceholders = p[Keys.homeRowHidePlaceholders] ?: d.homeRowHidePlaceholders,
            homeRowOpensGuide = p[Keys.homeRowOpensGuide] ?: d.homeRowOpensGuide,
            homeRowShowNext = p[Keys.homeRowShowNext] ?: d.homeRowShowNext,
            homeRowCompact = p[Keys.homeRowCompact] ?: d.homeRowCompact,
            bufferSize = p[Keys.bufferSize] ?: d.bufferSize,
            groupSort = p[Keys.groupSort] ?: d.groupSort,
            timeshiftEnabled = p[Keys.timeshiftEnabled] ?: d.timeshiftEnabled,
            timeshiftMinutes = p[Keys.timeshiftMinutes] ?: d.timeshiftMinutes,
            rewindAlways = p[Keys.rewindAlways] ?: d.rewindAlways,
            captionsByDefault = p[Keys.captionsByDefault] ?: d.captionsByDefault,
            clockMode = p[Keys.clockMode] ?: d.clockMode,
            clockPosition = p[Keys.clockPosition] ?: d.clockPosition,
            clockSize = p[Keys.clockSize] ?: d.clockSize,
            clockOpacity = p[Keys.clockOpacity] ?: d.clockOpacity,
            clockBackground = p[Keys.clockBackground] ?: d.clockBackground,
            panelBackground = p[Keys.panelBackground] ?: d.panelBackground,
            guideInfoBackground = p[Keys.guideInfoBackground] ?: d.guideInfoBackground,
            guideBackdrop = p[Keys.guideBackdrop] ?: d.guideBackdrop,
            recentInPanel = p[Keys.recentInPanel] ?: d.recentInPanel,
            // Before the choice existed it was an on / off switch for Recent.
            panelRow = p[Keys.panelRow] ?: if (p[Keys.recentInPanel] == false) "OFF" else d.panelRow,
            channelSurf = p[Keys.channelSurf] ?: d.channelSurf,
            barHidden = p[Keys.barHidden] ?: d.barHidden,
            guideInfoHidden = p[Keys.guideInfoHidden] ?: d.guideInfoHidden,
            launcherRows = p[Keys.launcherRows] ?: d.launcherRows
        )
    }.distinctUntilChanged()

    /**
     * Channels and groups hidden in every profile that uses Live TV. No one sees them, so the
     * guide doesn't store their listings (big providers' hidden groups made the guide database
     * many times bigger than what anyone looks at).
     */
    val hiddenEverywhere: Flow<HiddenEverywhere> = store.data.map { p ->
        val ids = listOf(1) + p.asMap().keys.mapNotNull { k ->
            SEEDED.find(k.name)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { runCatching { p[booleanPreferencesKey(k.name)] }.getOrNull() == true }
        }
        HiddenEverywhere(ids.distinct().map { id ->
            val v = profileView(p, id)
            val hiddenGroups = v[Keys.hiddenGroups] ?: emptySet()
            // Channels this profile still shows somewhere else: in Favorites, in a group of its
            // own, or copied into another group. Their own group being hidden doesn't hide them.
            val kept = HashSet<String>()
            kept += decodeStringList(v[Keys.favorites])
            decodeCustomGroups(v[Keys.customGroups]).forEach { g -> if (g.id !in hiddenGroups) kept += g.channelKeys }
            decodeListMap(v[Keys.channelCopies]).forEach { (groupId, keys) -> if (groupId !in hiddenGroups) kept += keys }
            HiddenView(v[Keys.hiddenChannels] ?: emptySet(), hiddenGroups, kept)
        })
    }.distinctUntilChanged()

    suspend fun currentHiddenEverywhere(): HiddenEverywhere = hiddenEverywhere.first()

    val userState: Flow<LiveUserState> = kotlinx.coroutines.flow.combine(store.data, deviceStore.data, profileManager.activeProfileId) { p0, d, id ->
        val p = profileView(p0, id)
        LiveUserState(
            hiddenChannels = p[Keys.hiddenChannels] ?: emptySet(),
            hiddenGroups = p[Keys.hiddenGroups] ?: emptySet(),
            favorites = decodeStringList(p[Keys.favorites]),
            recent = decodeStringList(p[Keys.recent]),
            lastChannelKey = p[Keys.lastChannel],
            previousChannelKey = p[Keys.previousChannel],
            lastGroupId = p[Keys.lastGroup],
            channelNames = decodeStringMap(p[Keys.channelNames]),
            channelNumbers = decodeStringMap(p[Keys.channelNumbers]).mapNotNull { (k, v) -> v.toIntOrNull()?.let { k to it } }.toMap(),
            groupNames = decodeStringMap(p[Keys.groupNames]),
            groupOrder = decodeStringList(p[Keys.groupOrder]),
            customGroups = decodeCustomGroups(p[Keys.customGroups]),
            epgOverrides = decodeStringMap(p[Keys.epgOverrides]).mapNotNull { (k, v) ->
                val parts = v.split(EPG_SEP, limit = 2)
                if (parts.size == 2) k to com.nuvio.tv.livetv.model.EpgAssignment(parts[0], parts[1]) else null
            }.toMap(),
            channelOrder = decodeListMap(p[Keys.channelOrder]),
            channelCopies = decodeListMap(p[Keys.channelCopies]),
            lockedGroups = p[Keys.lockedGroups] ?: emptySet(),
            reminders = decodeReminders(p[Keys.reminders]),
            channelQuality = decodeStringMap(d[Keys.channelQuality] ?: p[Keys.channelQuality]),
            channelAudio = decodeStringMap(p[Keys.channelAudio]),
            vodHiddenCategories = p[Keys.vodHiddenCategories] ?: emptySet(),
            vodGroupOrder = decodeStringList(p[Keys.vodGroupOrder]),
            vodGroupNames = decodeStringMap(p[Keys.vodGroupNames]),
            vodSorts = decodeStringMap(p[Keys.vodSorts])
        )
    }.distinctUntilChanged()

    suspend fun currentPlaylists(): List<PlaylistSource> = playlists.first()
    suspend fun currentEpgSources(): List<EpgSource> = epgSources.first()
    suspend fun currentSettings(): LiveTvSettings = settings.first()

    // ---------- sources ----------

    suspend fun updatePlaylists(transform: (List<PlaylistSource>) -> List<PlaylistSource>) {
        var removed = emptySet<String>()
        store.edit { p ->
            val before = decodePlaylists(p[Keys.playlists])
            val after = transform(before)
            removed = before.map { it.id }.toSet() - after.map { it.id }.toSet()
            p[Keys.playlists] = encodePlaylists(after)
        }
        noteRemovedSources(removed)
    }

    suspend fun updateEpgSources(transform: (List<EpgSource>) -> List<EpgSource>) {
        var removed = emptySet<String>()
        store.edit { p ->
            val before = decodeEpgs(p[Keys.epgs])
            val after = transform(before)
            removed = before.map { it.id }.toSet() - after.map { it.id }.toSet()
            p[Keys.epgs] = encodeEpgs(after)
        }
        noteRemovedSources(removed)
    }

    // Playlists and guides removed on this TV (by you), until Google Drive sync has passed that on.
    // Sync only takes a source out of the saved copy when it's listed here, so a TV that simply
    // never had a playlist (or lost it) can't delete it from your other TVs.
    private suspend fun noteRemovedSources(ids: Set<String>) {
        val clean = ids.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        deviceStore.edit { it[Keys.removedSources] = (it[Keys.removedSources] ?: emptySet()) + clean }
    }

    suspend fun removedSourceIds(): Set<String> = deviceStore.data.first()[Keys.removedSources] ?: emptySet()

    suspend fun forgetRemovedSources(ids: Set<String>) {
        if (ids.isEmpty()) return
        deviceStore.edit { it[Keys.removedSources] = (it[Keys.removedSources] ?: emptySet()) - ids }
    }

    // ---------- settings ----------

    suspend fun updateSettings(transform: (LiveTvSettings) -> LiveTvSettings) {
        val next = transform(currentSettings())
        store.edit { p -> writeSettings(p, next) }
    }

    private fun writeSettings(p: MutablePreferences, s: LiveTvSettings) {
        p[Keys.showInSidebar] = s.showInSidebar
        p[Keys.showNames] = s.showChannelNames
        p[Keys.showNumbers] = s.showChannelNumbers
        p[Keys.showLogos] = s.showChannelLogos
        p[Keys.showPreview] = s.showPreview
        p[Keys.showDetails] = s.showProgramDetails
        p[Keys.showFavGroup] = s.showFavoritesGroup
        p[Keys.showRecentGroup] = s.showRecentGroup
        p[Keys.compactRows] = s.compactRows
        p[Keys.showProgramTimes] = s.showProgramTimes
        p[Keys.showEpisodeNumbers] = s.showEpisodeNumbers
        p[Keys.guideSize] = s.guideSize
        p[Keys.epgOffset] = s.epgOffsetMinutes
        p[Keys.reverseZap] = s.reverseZap
        p[Keys.zapMode] = s.zapMode.name
        p[Keys.autoPlayLast] = s.autoPlayLastChannel
        p[Keys.rememberGroup] = s.rememberLastGroup
        p[Keys.fullscreenOnSelect] = s.openFullscreenOnSelect
        p[Keys.bannerSeconds] = s.infoBannerSeconds
        p[Keys.playlistRefresh] = s.playlistRefreshHours
        p[Keys.epgRefresh] = s.epgRefreshHours
        p[Keys.epgPast] = s.epgPastHours
        p[Keys.epgFuture] = s.epgFutureDays
        p[Keys.clock24] = s.use24HourClock
        p[Keys.autoReconnect] = s.autoReconnect
        p[Keys.autoBackupChannel] = s.autoBackupChannel
        p[Keys.catchupCache] = s.catchupCache
        p[Keys.channelSort] = s.channelSort.name
        p[Keys.solidHighlight] = s.solidHighlight
        p[Keys.showInSearch] = s.showInSearch
        p[Keys.guideSearchLive] = s.guideSearchLive
        p[Keys.guideSearchCatalog] = s.guideSearchCatalog
        p[Keys.guideSearchOnDemand] = s.guideSearchOnDemand
        p[Keys.vodSearchLive] = s.onDemandSearchLive
        p[Keys.vodSearchCatalog] = s.onDemandSearchCatalog
        p[Keys.vodSearchOnDemand] = s.onDemandSearchOnDemand
        p[Keys.showAllGroup] = s.showAllChannelsGroup
        p[Keys.resumeInPreview] = s.resumeLastInPreview
        p[Keys.showGroupCounts] = s.showGroupCounts
        p[Keys.smallHeader] = s.smallHeader
        p[Keys.aspectMode] = s.aspectMode
        p[Keys.sequentialNumbers] = s.sequentialNumbers
        p[Keys.overlayMode] = s.overlayMode
        p[Keys.startOnLiveTv] = s.startOnLiveTv
        p[Keys.startPage] = s.startPage
        p[Keys.catchupPreferHls] = s.catchupPreferHls
        p[Keys.catchupShift] = s.catchupShiftMinutes
        p[Keys.parentalPin] = s.parentalPin
        p[Keys.lockAdultContent] = s.lockAdultContent
        p[Keys.showQualityBadges] = s.showQualityBadges
        p[Keys.showFrameRate] = s.showFrameRate
        p[Keys.frameRateMode] = s.frameRateMode
        p[Keys.matchFrameRate] = s.matchFrameRate
        p[Keys.onDemandInSidebar] = s.onDemandInSidebar
        p[Keys.recordingsInSidebar] = s.recordingsInSidebar
        p[Keys.onDemandInStreams] = s.onDemandInStreams
        p[Keys.onDemandStreamsPosition] = s.onDemandStreamsPosition
        p[Keys.onDemandAutoPlay] = s.onDemandAutoPlay
        p[Keys.searchOrder] = s.searchOrder
        p[Keys.searchPreview] = s.searchPreview
        p[Keys.onDemandAddonPosters] = s.onDemandAddonPosters
        p[Keys.groupPlaylistHeadings] = s.groupPlaylistHeadings
        p[Keys.nameRemovals] = s.nameRemovals
        p[Keys.developerTools] = s.developerTools
        p[Keys.browseByChannelName] = s.browseByChannelName
        p[Keys.showPlaylistInInfo] = s.showPlaylistInInfo
        p[Keys.showNowLine] = s.showNowLine
        p[Keys.audioPassthrough] = s.audioPassthrough
        p[Keys.showPosters] = s.showPosters
        p[Keys.vodMergeDuplicates] = s.vodMergeDuplicates
        p[Keys.vodDefaultSort] = s.vodDefaultSort
        p[Keys.recordingStorage] = s.recordingStorage
        p[Keys.recordingSpaceRule] = s.recordingSpaceRule
        p[Keys.recordPre] = s.recordPreMinutes
        p[Keys.recordPost] = s.recordPostMinutes
        p[Keys.homeRowEnabled] = s.homeRowEnabled
        p[Keys.homeRowHiddenProfiles] = s.homeRowHiddenProfiles.map { it.toString() }.toSet()
        p[Keys.homeRowAboveContinueWatching] = s.homeRowAboveContinueWatching
        p[Keys.homeRowSource] = s.homeRowSource
        p[Keys.homeRowTitle] = s.homeRowTitle
        p[Keys.homeRowSort] = s.homeRowSort
        p[Keys.homeRowLimit] = s.homeRowLimit
        p[Keys.homeRowHidePlaceholders] = s.homeRowHidePlaceholders
        p[Keys.homeRowOpensGuide] = s.homeRowOpensGuide
        p[Keys.homeRowShowNext] = s.homeRowShowNext
        p[Keys.homeRowCompact] = s.homeRowCompact
        p[Keys.bufferSize] = s.bufferSize
        p[Keys.groupSort] = s.groupSort
        p[Keys.timeshiftEnabled] = s.timeshiftEnabled
        p[Keys.timeshiftMinutes] = s.timeshiftMinutes
        p[Keys.rewindAlways] = s.rewindAlways
        p[Keys.captionsByDefault] = s.captionsByDefault
        p[Keys.clockMode] = s.clockMode
        p[Keys.clockPosition] = s.clockPosition
        p[Keys.clockSize] = s.clockSize
        p[Keys.clockOpacity] = s.clockOpacity
        p[Keys.clockBackground] = s.clockBackground
        p[Keys.panelBackground] = s.panelBackground
        p[Keys.guideInfoBackground] = s.guideInfoBackground
        p[Keys.guideBackdrop] = s.guideBackdrop
        p[Keys.recentInPanel] = s.recentInPanel
        p[Keys.panelRow] = s.panelRow
        p[Keys.channelSurf] = s.channelSurf
        p[Keys.barHidden] = s.barHidden
        p[Keys.guideInfoHidden] = s.guideInfoHidden
        p[Keys.launcherRows] = s.launcherRows
    }

    // ---------- channel management ----------

    suspend fun setChannelName(key: String, name: String?) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.channelNames]).toMutableMap()
            if (name.isNullOrBlank()) m.remove(key) else m[key] = name.trim()
            p[Keys.channelNames] = encodeStringMap(m)
        }
    }

    suspend fun setChannelNumber(key: String, number: Int?) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.channelNumbers]).toMutableMap()
            if (number == null) m.remove(key) else m[key] = number.toString()
            p[Keys.channelNumbers] = encodeStringMap(m)
        }
    }

    suspend fun setGroupName(groupId: String, name: String?) {
        if (groupId.startsWith(com.nuvio.tv.livetv.model.ChannelGroup.CUSTOM_PREFIX)) {
            updateCustomGroups { list -> list.map { if (it.id == groupId && !name.isNullOrBlank()) it.copy(name = name.trim()) else it } }
            return
        }
        store.edit { p ->
            val m = decodeStringMap(p[Keys.groupNames]).toMutableMap()
            if (name.isNullOrBlank()) m.remove(groupId) else m[groupId] = name.trim()
            p[Keys.groupNames] = encodeStringMap(m)
        }
    }

    /** Moves [groupId] within [currentOrder] (the order currently shown) and saves the result. */
    suspend fun moveGroup(groupId: String, delta: Int, currentOrder: List<String>) {
        val order = currentOrder.toMutableList()
        val i = order.indexOf(groupId)
        if (i < 0) return
        order.removeAt(i)
        order.add((i + delta).coerceIn(0, order.size), groupId)
        store.edit { it[my(Keys.groupOrder)] = encodeStringList(order) }
    }

    suspend fun createCustomGroup(name: String): String {
        val id = com.nuvio.tv.livetv.model.ChannelGroup.CUSTOM_PREFIX + java.util.UUID.randomUUID().toString().take(8)
        updateCustomGroups { it + CustomGroup(id, name.trim().ifBlank { "My group" }) }
        return id
    }

    suspend fun deleteCustomGroup(groupId: String) = updateCustomGroups { list -> list.filterNot { it.id == groupId } }

    suspend fun addToCustomGroup(groupId: String, key: String) = updateCustomGroups { list ->
        list.map { if (it.id == groupId && key !in it.channelKeys) it.copy(channelKeys = it.channelKeys + key) else it }
    }

    suspend fun removeFromCustomGroup(groupId: String, key: String) = updateCustomGroups { list ->
        list.map { if (it.id == groupId) it.copy(channelKeys = it.channelKeys - key) else it }
    }

    suspend fun moveInCustomGroup(groupId: String, key: String, delta: Int) = updateCustomGroups { list ->
        list.map { g ->
            if (g.id != groupId) return@map g
            val keys = g.channelKeys.toMutableList()
            val i = keys.indexOf(key)
            if (i < 0) return@map g
            keys.removeAt(i)
            keys.add((i + delta).coerceIn(0, keys.size), key)
            g.copy(channelKeys = keys)
        }
    }

    suspend fun setEpgOverride(key: String, assignment: com.nuvio.tv.livetv.model.EpgAssignment?) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.epgOverrides]).toMutableMap()
            if (assignment == null) m.remove(key) else m[key] = assignment.sourceId + EPG_SEP + assignment.xmltvId
            p[Keys.epgOverrides] = encodeStringMap(m)
        }
    }

    /** Picture shape chosen per channel (channels left on Auto aren't listed). */
    val pictureShapes: Flow<Map<String, String>> = store.data.map { decodeStringMap(it[Keys.pictureShapes]) }

    // ---------- Sports ----------
    // Kept with Live TV's settings so Google Drive sync carries them to your other TVs too.

    /** Sports settings and channel fixes (JSON, read by the Sports page). */
    val sportsSettingsJson: Flow<String?> = store.data.map { it[Keys.sports] }.distinctUntilChanged()

    /** What this profile follows in Sports (JSON). */
    val sportsMineJson: Flow<String?> =
        kotlinx.coroutines.flow.combine(store.data, profileManager.activeProfileId) { p, id -> profileView(p, id)[Keys.sportsMine] }
            .distinctUntilChanged()

    suspend fun editSportsSettings(transform: (String?) -> String) {
        store.edit { p -> p[Keys.sports] = transform(p[Keys.sports]) }
    }

    suspend fun editSportsMine(transform: (String?) -> String) {
        store.edit { p ->
            val id = currentProfile
            p[my(Keys.sportsMine, id)] = transform(profileView(p, id)[Keys.sportsMine])
        }
    }

    suspend fun setPictureShape(key: String, shape: com.nuvio.tv.livetv.player.PictureShape) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.pictureShapes]).toMutableMap()
            if (shape == com.nuvio.tv.livetv.player.PictureShape.AUTO) m.remove(key) else m[key] = shape.name
            p[Keys.pictureShapes] = encodeStringMap(m)
        }
    }

    suspend fun clearEpgOverrides() {
        store.edit { it[Keys.epgOverrides] = encodeStringMap(emptyMap()) }
    }

    // ---------- parental controls, reminders, quality, On Demand ----------

    suspend fun setGroupLocked(groupId: String, locked: Boolean) {
        store.edit { p ->
            val cur = p[Keys.lockedGroups] ?: emptySet()
            p[Keys.lockedGroups] = if (locked) cur + groupId else cur - groupId
        }
    }

    suspend fun addReminder(r: Reminder) {
        store.edit { p ->
            val cur = decodeReminders(p[my(Keys.reminders)]).filterNot { it.channelKey == r.channelKey && it.startMs == r.startMs }
            p[my(Keys.reminders)] = encodeReminders((cur + r).sortedBy { it.startMs })
        }
    }

    suspend fun removeReminder(channelKey: String, startMs: Long) {
        store.edit { p ->
            p[my(Keys.reminders)] = encodeReminders(
                decodeReminders(p[my(Keys.reminders)]).filterNot { it.channelKey == channelKey && it.startMs == startMs }
            )
        }
    }

    /** Drops reminders for shows that started more than [graceMs] ago. */
    suspend fun pruneReminders(now: Long, graceMs: Long = 30 * 60_000L) {
        store.edit { p ->
            val cur = decodeReminders(p[my(Keys.reminders)])
            val kept = cur.filter { it.startMs + graceMs > now }
            if (kept.size != cur.size) p[my(Keys.reminders)] = encodeReminders(kept)
        }
    }

    /** The audio track picked on a channel, used every time it plays (null forgets it). */
    suspend fun setChannelAudio(key: String, audio: String?) {
        store.edit { p ->
            val m = decodeStringMap(p[Keys.channelAudio]).toMutableMap()
            if (audio == null) m.remove(key) else m[key] = audio
            p[Keys.channelAudio] = encodeStringMap(m)
        }
    }

    suspend fun setChannelQuality(key: String, quality: String) {
        // Older versions kept these in the main settings: move them over once.
        val legacy = store.data.first()[Keys.channelQuality]
        if (legacy != null) store.edit { it.remove(Keys.channelQuality) }
        deviceStore.edit { p ->
            val m = decodeStringMap(p[Keys.channelQuality] ?: legacy).toMutableMap()
            if (m[key] == quality && p[Keys.channelQuality] != null) return@edit
            m[key] = quality
            p[Keys.channelQuality] = encodeStringMap(m)
        }
    }

    /** On Demand: one category's sort (null = back to the default sort). */
    suspend fun setVodSort(uid: String, sort: String?) {
        store.edit { p ->
            val m = decodeStringMap(profileView(p, currentProfile)[Keys.vodSorts]).toMutableMap()
            if (sort == null) m.remove(uid) else m[uid] = sort
            p[my(Keys.vodSorts)] = encodeStringMap(m)
        }
    }

    /** On Demand: every category's sort at once (Manage VOD Groups → Done). */
    suspend fun setVodSorts(sorts: Map<String, String>) {
        store.edit { p -> p[my(Keys.vodSorts)] = encodeStringMap(sorts) }
    }

    /** Manage VOD Groups: which groups show, their order and names, saved together (Done). */
    suspend fun saveVodGroups(hidden: Set<String>, order: List<String>, names: Map<String, String>) {
        store.edit { p ->
            p[my(Keys.vodHiddenCategories)] = hidden
            p[my(Keys.vodGroupOrder)] = encodeStringList(order)
            p[Keys.vodGroupNames] = encodeStringMap(names.filterValues { it.isNotBlank() })
        }
    }

    suspend fun updateVodHiddenCategories(show: Set<String>, hide: Set<String>) {
        store.edit { p ->
            val cur = p[my(Keys.vodHiddenCategories)] ?: emptySet()
            p[my(Keys.vodHiddenCategories)] = (cur - show) + hide
        }
    }

    /** Account details per playlist (expiry, connections), as the provider last reported them. */
    val playlistAccounts: Flow<Map<String, com.nuvio.tv.livetv.model.PlaylistAccount>> = deviceStore.data.map { p ->
        val out = HashMap<String, com.nuvio.tv.livetv.model.PlaylistAccount>()
        runCatching {
            val o = JSONObject(p[Keys.playlistAccounts] ?: "{}")
            o.keys().forEach { id ->
                val a = o.optJSONObject(id) ?: return@forEach
                out[id] = com.nuvio.tv.livetv.model.PlaylistAccount(
                    expiresAtMs = a.optLong("exp"),
                    status = a.optString("status"),
                    maxConnections = a.optInt("max"),
                    activeConnections = a.optInt("active"),
                    checkedAtMs = a.optLong("checked")
                )
            }
        }
        out
    }.distinctUntilChanged()

    suspend fun setPlaylistAccount(playlistId: String, a: com.nuvio.tv.livetv.model.PlaylistAccount?) {
        deviceStore.edit { p ->
            val o = runCatching { JSONObject(p[Keys.playlistAccounts] ?: "{}") }.getOrDefault(JSONObject())
            if (a == null) o.remove(playlistId)
            else o.put(playlistId, JSONObject().put("exp", a.expiresAtMs).put("status", a.status)
                .put("max", a.maxConnections).put("active", a.activeConnections).put("checked", a.checkedAtMs))
            p[Keys.playlistAccounts] = o.toString()
        }
    }

    /** When each provider's On Demand catalog was last imported (per TV, not synced). */
    suspend fun onDemandImportTimes(): Map<String, Long> =
        decodeStringMap(deviceStore.data.first()[Keys.onDemandImports] ?: store.data.first()[Keys.onDemandImports])
            .mapValues { it.value.toLongOrNull() ?: 0L }

    suspend fun setOnDemandImportTime(playlistId: String, time: Long) {
        val legacy = store.data.first()[Keys.onDemandImports]
        if (legacy != null) store.edit { it.remove(Keys.onDemandImports) }
        deviceStore.edit { p ->
            val m = decodeStringMap(p[Keys.onDemandImports] ?: legacy).toMutableMap()
            m[playlistId] = time.toString()
            p[Keys.onDemandImports] = encodeStringMap(m)
        }
    }

    suspend fun clearVodHiddenCategories() {
        store.edit { it[my(Keys.vodHiddenCategories)] = emptySet() }
    }

    private fun decodeReminders(raw: String?): List<Reminder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(o.optString("k"), o.optString("c"), o.optString("t"), o.optLong("s"))
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeReminders(list: List<Reminder>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("k", it.channelKey).put("c", it.channelName).put("t", it.title).put("s", it.startMs)) }
        return arr.toString()
    }

    /** Saves the order of a playlist group or All channels (Reorder channels). */
    suspend fun setChannelOrder(groupId: String, keys: List<String>) {
        store.edit { p ->
            val m = decodeListMap(p[my(Keys.channelOrder)]).toMutableMap()
            m[groupId] = keys
            p[my(Keys.channelOrder)] = encodeListMap(m)
        }
    }

    suspend fun setFavoritesOrder(keys: List<String>) {
        store.edit { p ->
            val cur = decodeStringList(p[my(Keys.favorites)])
            // Keep any favorites that weren't in the list (e.g. hidden ones) at the end.
            p[my(Keys.favorites)] = encodeStringList(keys.filter { it in cur } + cur.filter { it !in keys })
        }
    }

    suspend fun setCustomGroupOrder(groupId: String, keys: List<String>) = updateCustomGroups { list ->
        list.map { g ->
            if (g.id != groupId) g
            else g.copy(channelKeys = keys.filter { it in g.channelKeys } + g.channelKeys.filter { it !in keys })
        }
    }

    /** Copies a channel into another playlist group (it stays in its own group too). */
    suspend fun copyChannel(groupId: String, key: String) {
        store.edit { p ->
            val m = decodeListMap(p[my(Keys.channelCopies)]).toMutableMap()
            val cur = m[groupId].orEmpty()
            if (key !in cur) m[groupId] = cur + key
            p[my(Keys.channelCopies)] = encodeListMap(m)
        }
    }

    suspend fun removeChannelCopy(groupId: String, key: String) {
        store.edit { p ->
            val m = decodeListMap(p[my(Keys.channelCopies)]).toMutableMap()
            m[groupId] = m[groupId].orEmpty() - key
            if (m[groupId].isNullOrEmpty()) m.remove(groupId)
            p[my(Keys.channelCopies)] = encodeListMap(m)
        }
    }

    suspend fun resetChannelOrder() {
        store.edit { it[my(Keys.channelOrder)] = encodeListMap(emptyMap()) }
    }

    suspend fun clearChannelCopies() {
        store.edit { it[my(Keys.channelCopies)] = encodeListMap(emptyMap()) }
    }

    suspend fun resetChannelEdits() {
        store.edit {
            it[Keys.channelNames] = encodeStringMap(emptyMap())
            it[Keys.channelNumbers] = encodeStringMap(emptyMap())
        }
    }

    suspend fun resetGroupEdits() {
        store.edit {
            it[Keys.groupNames] = encodeStringMap(emptyMap())
            it[my(Keys.groupOrder)] = encodeStringList(emptyList())
        }
    }

    private suspend fun updateCustomGroups(transform: (List<CustomGroup>) -> List<CustomGroup>) {
        store.edit { p -> p[my(Keys.customGroups)] = encodeCustomGroups(transform(decodeCustomGroups(p[my(Keys.customGroups)]))) }
    }

    // ---------- per-channel state ----------

    suspend fun setChannelHidden(key: String, hidden: Boolean) {
        store.edit { p ->
            val cur = p[my(Keys.hiddenChannels)] ?: emptySet()
            p[my(Keys.hiddenChannels)] = if (hidden) cur + key else cur - key
        }
    }

    /** Applies a batch of group show/hide changes at once (Manage visibility for groups). */
    suspend fun updateHiddenGroups(show: Set<String>, hide: Set<String>) {
        store.edit { p ->
            val cur = p[my(Keys.hiddenGroups)] ?: emptySet()
            p[my(Keys.hiddenGroups)] = (cur - show) + hide
        }
    }

    /** Applies a batch of show/hide changes at once (Manage visibility). */
    suspend fun updateHiddenChannels(show: Set<String>, hide: Set<String>) {
        store.edit { p ->
            val cur = p[my(Keys.hiddenChannels)] ?: emptySet()
            p[my(Keys.hiddenChannels)] = (cur - show) + hide
        }
    }

    suspend fun clearHiddenChannels() {
        store.edit { it[my(Keys.hiddenChannels)] = emptySet() }
    }

    suspend fun setGroupHidden(groupId: String, hidden: Boolean) {
        store.edit { p ->
            val cur = p[my(Keys.hiddenGroups)] ?: emptySet()
            p[my(Keys.hiddenGroups)] = if (hidden) cur + groupId else cur - groupId
        }
    }

    suspend fun clearHiddenGroups() {
        store.edit { it[my(Keys.hiddenGroups)] = emptySet() }
    }

    suspend fun toggleFavorite(key: String) {
        store.edit { p ->
            val cur = decodeStringList(p[my(Keys.favorites)])
            p[my(Keys.favorites)] = encodeStringList(if (key in cur) cur - key else cur + key)
        }
    }

    suspend fun moveFavorite(key: String, delta: Int) {
        store.edit { p ->
            val cur = decodeStringList(p[my(Keys.favorites)]).toMutableList()
            val idx = cur.indexOf(key)
            if (idx < 0) return@edit
            val target = (idx + delta).coerceIn(0, cur.lastIndex)
            cur.removeAt(idx)
            cur.add(target, key)
            p[my(Keys.favorites)] = encodeStringList(cur)
        }
    }

    suspend fun clearFavorites() {
        store.edit { it[my(Keys.favorites)] = encodeStringList(emptyList()) }
    }

    suspend fun clearRecent() {
        store.edit { it[my(Keys.recent)] = encodeStringList(emptyList()) }
    }

    /** [addToRecent] false: adult channels, which never go in Recently watched. */
    suspend fun recordWatched(key: String, addToRecent: Boolean = true) {
        store.edit { p ->
            val last = p[my(Keys.lastChannel)]
            if (last != null && last != key) p[my(Keys.previousChannel)] = last
            p[my(Keys.lastChannel)] = key
            if (addToRecent) {
                val recent = decodeStringList(p[my(Keys.recent)]).filter { it != key }
                p[my(Keys.recent)] = encodeStringList((listOf(key) + recent).take(30))
            }
        }
    }

    suspend fun setLastGroup(groupId: String) {
        store.edit { it[my(Keys.lastGroup)] = groupId }
    }

    // ---------- Google Drive sync ----------

    /** Fires whenever anything in Live TV's stored data changes. */
    val changes: Flow<Unit> = store.data.map { }

    /**
     * Everything worth carrying to another TV, as JSON: sources, settings, favorites, hidden
     * channels, groups, renames and EPG assignments. Left out: what this TV last watched, and
     * download status (last updated, errors, counts), which differs per TV and changes constantly.
     */
    suspend fun exportForSync(): JSONObject {
        val p = store.data.first()
        val out = JSONObject()
        p.asMap().forEach { (key, value) ->
            val name = key.name
            if (name in SYNC_EXCLUDED) return@forEach
            val entry = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Set<*> -> JSONObject().put("t", "s").put("v", JSONArray(value.map { it.toString() }))
                is String -> {
                    val v = when (name) {
                        // Download status and what this TV learned about the server (its time zone,
                        // whether HLS catch-up works) stay per TV: they differ between boxes, and
                        // syncing them made each TV look changed to the other, so both reloaded.
                        Keys.playlists.name -> encodePlaylists(decodePlaylists(value).map {
                            it.copy(lastUpdatedMs = 0, lastError = null, channelCount = 0, serverTimezone = "", catchupNoHlsSince = 0L)
                        })
                        Keys.epgs.name -> encodeEpgs(decodeEpgs(value).map {
                            it.copy(lastUpdatedMs = 0, lastError = null, programCount = 0)
                        })
                        else -> value
                    }
                    JSONObject().put("t", "str").put("v", v)
                }
                else -> null
            } ?: return@forEach
            out.put(name, entry)
        }
        return out
    }

    /** Whether a setup from [exportForSync] has any playlist or guide in it. */
    fun syncHasSources(data: JSONObject?): Boolean {
        if (data == null) return false
        fun raw(key: String) = data.optJSONObject(key)?.optString("v")
        return runCatching { decodePlaylists(raw(Keys.playlists.name)).isNotEmpty() }.getOrDefault(false) ||
            runCatching { decodeEpgs(raw(Keys.epgs.name)).isNotEmpty() }.getOrDefault(false)
    }

    /** Replaces this TV's Live TV setup with [data] from [exportForSync], keeping per-TV state. */
    suspend fun importFromSync(data: JSONObject): Set<String> {
        // Playlists whose address or login changed: they need downloading again.
        val changed = HashSet<String>()
        store.edit { p ->
            val localPlaylists = decodePlaylists(p[Keys.playlists]).associateBy { it.id }
            val localEpgs = decodeEpgs(p[Keys.epgs]).associateBy { it.id }
            // Remove synced keys the other TV doesn't have, then write everything it does.
            p.asMap().keys.filter { it.name !in SYNC_EXCLUDED && !data.has(it.name) }.forEach {
                @Suppress("UNCHECKED_CAST")
                p.remove(it as Preferences.Key<Any>)
            }
            data.keys().forEach { name ->
                if (name in SYNC_EXCLUDED) return@forEach
                val e = data.optJSONObject(name) ?: return@forEach
                when (e.optString("t")) {
                    "b" -> p[booleanPreferencesKey(name)] = e.optBoolean("v")
                    "i" -> p[intPreferencesKey(name)] = e.optInt("v")
                    "l" -> p[androidx.datastore.preferences.core.longPreferencesKey(name)] = e.optLong("v")
                    "s" -> {
                        val arr = e.optJSONArray("v") ?: JSONArray()
                        p[stringSetPreferencesKey(name)] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                    "str" -> {
                        var v = e.optString("v")
                        // Keep this TV's download status for sources it already had.
                        if (name == Keys.playlists.name) {
                            v = encodePlaylists(decodePlaylists(v).map { r ->
                                localPlaylists[r.id]?.let { l ->
                                    if (l.url != r.url || l.xtreamServer != r.xtreamServer || l.xtreamUsername != r.xtreamUsername ||
                                        l.xtreamPassword != r.xtreamPassword || l.stalkerPortal != r.stalkerPortal || l.stalkerMac != r.stalkerMac ||
                                        l.stalkerUsername != r.stalkerUsername || l.stalkerPassword != r.stalkerPassword || l.stalkerSerial != r.stalkerSerial || l.userAgent != r.userAgent || l.streamFormat != r.streamFormat
                                    ) changed += r.id
                                    r.copy(
                                        lastUpdatedMs = l.lastUpdatedMs, lastError = l.lastError, channelCount = l.channelCount,
                                        serverTimezone = l.serverTimezone, catchupNoHlsSince = l.catchupNoHlsSince
                                    )
                                } ?: r
                            })
                        } else if (name == Keys.epgs.name) {
                            v = encodeEpgs(decodeEpgs(v).map { r ->
                                localEpgs[r.id]?.let { l ->
                                    r.copy(lastUpdatedMs = l.lastUpdatedMs, lastError = l.lastError, programCount = l.programCount)
                                } ?: r
                            })
                        }
                        p[stringPreferencesKey(name)] = v
                    }
                }
            }
        }
        return changed
    }

    // ---------- JSON helpers ----------

    private fun decodePlaylists(raw: String?): List<PlaylistSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlaylistSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    enabled = o.optBoolean("enabled", true),
                    userAgent = o.optString("userAgent"),
                    useEmbeddedEpg = o.optBoolean("useEmbeddedEpg", true),
                    xtreamServer = o.optString("xtreamServer"),
                    xtreamUsername = o.optString("xtreamUsername"),
                    xtreamPassword = o.optString("xtreamPassword"),
                    lastUpdatedMs = o.optLong("lastUpdatedMs"),
                    lastError = o.optString("lastError").ifBlank { null },
                    channelCount = o.optInt("channelCount"),
                    // Logins from before On Demand existed stay off until you switch them on.
                    importVod = o.optBoolean("importVod", false),
                    importLive = o.optBoolean("importLive", true),
                    streamFormat = o.optString("streamFormat").ifBlank { "auto" },
                    serverTimezone = o.optString("serverTimezone"),
                    catchupNoHlsSince = o.optLong("catchupNoHlsSince", 0L),
                    logoSource = o.optString("logoSource").ifBlank { "playlist" },
                    stalkerPortal = o.optString("stalkerPortal"), stalkerMac = o.optString("stalkerMac"),
                    stalkerUsername = o.optString("stalkerUsername"), stalkerPassword = o.optString("stalkerPassword"),
                    stalkerSerial = o.optString("stalkerSerial")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePlaylists(list: List<PlaylistSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url)
                    .put("enabled", s.enabled).put("userAgent", s.userAgent)
                    .put("useEmbeddedEpg", s.useEmbeddedEpg)
                    .put("xtreamServer", s.xtreamServer).put("xtreamUsername", s.xtreamUsername)
                    .put("xtreamPassword", s.xtreamPassword)
                    .put("lastUpdatedMs", s.lastUpdatedMs).put("lastError", s.lastError ?: "")
                    .put("channelCount", s.channelCount)
                    .put("importVod", s.importVod)
                    .put("importLive", s.importLive)
                    .put("streamFormat", s.streamFormat)
                    .put("serverTimezone", s.serverTimezone)
                    .put("catchupNoHlsSince", s.catchupNoHlsSince)
                    .put("logoSource", s.logoSource)
                    .put("stalkerPortal", s.stalkerPortal).put("stalkerMac", s.stalkerMac)
                    .put("stalkerUsername", s.stalkerUsername).put("stalkerPassword", s.stalkerPassword)
                    .put("stalkerSerial", s.stalkerSerial)
            )
        }
        return arr.toString()
    }

    private fun decodeEpgs(raw: String?): List<EpgSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EpgSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    enabled = o.optBoolean("enabled", true),
                    lastUpdatedMs = o.optLong("lastUpdatedMs"),
                    lastError = o.optString("lastError").ifBlank { null },
                    programCount = o.optInt("programCount")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeEpgs(list: List<EpgSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url)
                    .put("enabled", s.enabled).put("lastUpdatedMs", s.lastUpdatedMs)
                    .put("lastError", s.lastError ?: "").put("programCount", s.programCount)
            )
        }
        return arr.toString()
    }

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringList(list: List<String>): String = JSONArray(list).toString()

    private companion object {
        const val EPG_SEP = "\u0001"
        /** Per-TV keys that Google Drive sync never copies. */
        private val SYNC_EXCLUDED_BASE = setOf("last_channel", "previous_channel", "last_group", "recent", "on_demand_imports", "channel_quality", "developer_tools", "recording_storage")
        private val PROFILE_SUFFIX = Regex("""_p\d+$""")
        /** Also matches each profile's own copy ("recent_p2"). */
        val SYNC_EXCLUDED = object : AbstractSet<String>() {
            override val size get() = SYNC_EXCLUDED_BASE.size
            override fun iterator() = SYNC_EXCLUDED_BASE.iterator()
            override fun contains(element: String) = element.replace(PROFILE_SUFFIX, "") in SYNC_EXCLUDED_BASE
        }
    }

    private fun decodeListMap(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val arr = o.optJSONArray(k) ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeListMap(map: Map<String, List<String>>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        return o.toString()
    }

    private fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun encodeStringMap(map: Map<String, String>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private fun decodeCustomGroups(raw: String?): List<CustomGroup> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val keys = o.optJSONArray("keys")
                CustomGroup(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    channelKeys = if (keys == null) emptyList() else (0 until keys.length()).map { keys.getString(it) }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeCustomGroups(list: List<CustomGroup>): String {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().put("id", g.id).put("name", g.name).put("keys", JSONArray(g.channelKeys)))
        }
        return arr.toString()
    }
}

/** Each profile's hidden channels and hidden groups (see [LiveTvPreferences.hiddenEverywhere]). */
/** One profile's hidden channels and groups, and the channels it shows elsewhere anyway. */
data class HiddenView(val channels: Set<String>, val groups: Set<String>, val kept: Set<String> = emptySet())

data class HiddenEverywhere(private val views: List<HiddenView>) {
    /**
     * True when no profile shows this channel (hidden itself, or its group is). A channel in
     * Favorites, in a group you made, or copied into another group is shown: its listings are
     * kept even when its own group is hidden. (They weren't: channels in your own groups whose
     * provider group was hidden had almost no guide.)
     */
    fun hides(channelKey: String, groupId: String): Boolean =
        views.isNotEmpty() && views.all { v ->
            channelKey !in v.kept && (channelKey in v.channels || groupId in v.groups)
        }
}
