package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.effectiveStartPage
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.ZapMode
import com.nuvio.tv.livetv.ui.LiveTvSettingsViewModel
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.livetv.model.effectiveGuideSize
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val LIVE_TV_TITLE = "Live TV"
private const val LIVE_TV_SUBTITLE = "Playlists, TV guide and channel options"

/**
 * Live TV settings are one place in Settings with a row of tabs across the top of it (Setup,
 * Guide, Playback, On Demand, Integration): [HUB] is that tabbed page. [ALL] is the old single
 * long list, kept for the guide's long-press menu. The others are the tabs themselves.
 */
internal enum class LiveTvPage(val title: String, val subtitle: String, val tab: String = "") {
    ALL("Live TV", "Playlists, TV guide and channel options"),
    HUB("Live TV", "Playlists, TV guide and channel options"),
    MAIN("Live TV", "Your playlists, guides, channels, groups and parental controls", "Setup"),
    GUIDE("Live TV", "How the guide looks and behaves while you're browsing", "Guide"),
    PLAYBACK("Live TV", "Picture, sound, pausing and recording", "Playback"),
    ON_DEMAND("Live TV", "Movies and shows from your provider", "On Demand"),
    INTEGRATION("Live TV", "How Live TV shows up in the rest of Nuvio", "Integration")
}

private val LIVE_TABS = listOf(LiveTvPage.MAIN, LiveTvPage.GUIDE, LiveTvPage.PLAYBACK)

/** The row of tabs across the top of Live TV settings; moving onto one shows it. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun LiveTabRow(selected: LiveTvPage, first: FocusRequester?, onSelect: (LiveTvPage) -> Unit) {
    // Coming up from the page below lands on the tab you're on, not whichever one is nearest.
    // Only the tab you're on can take focus from outside the row; once focus is in the row, all can.
    var rowHasFocus by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LIVE_TABS.forEach { tab ->
            var focused by remember { mutableStateOf(false) }
            val on = tab == selected
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
            Text(
                tab.tab,
                color = if (focused) NuvioTheme.colors.OnSecondary else if (on) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .then(if (on && first != null) Modifier.focusRequester(first) else Modifier)
                    .focusProperties { canFocus = on || rowHasFocus }
                    .clip(shape)
                    .background(
                        when {
                            focused -> NuvioTheme.colors.Secondary
                            on -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f)
                            else -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)
                        }
                    )
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect(tab) }
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }
    }
}


/** On the four sub-tabs the sections sit open (there are only a few per tab), not folded. */
private val LocalLiveFlat = androidx.compose.runtime.compositionLocalOf { false }

/** Standalone version, opened from the guide's long-press menu. */
@Composable
fun LiveTvSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: LiveTvSettingsViewModel = hiltViewModel()
) {
    SettingsStandaloneScaffold(title = LIVE_TV_TITLE, subtitle = LIVE_TV_SUBTITLE) {
        LiveTvSettingsContent(viewModel = viewModel, page = LiveTvPage.HUB)
    }
}

/** Match Frame Rate's choices, worded as in Nuvio's own Playback settings. */
private val FRAME_RATE_MODES = listOf(
    Triple("OFF", "Off", "Don't change display refresh rate."),
    Triple("START", "On start", "Switch when playback starts."),
    Triple("START_STOP", "On start/stop", "Switch on start and restore on stop.")
)

private val CLOCK_MODES = listOf(
    "always" to "Always",
    "panel" to "With player controls",
    "never" to "Never"
)
private val CLOCK_POSITIONS = listOf(
    "top_left" to "Top left",
    "top_right" to "Top right",
    "bottom_left" to "Bottom left",
    "bottom_right" to "Bottom right"
)
private fun padLabel(minutes: Int): String = if (minutes <= 0) "Off" else "$minutes min"

private val CLOCK_SIZES = listOf("small" to "Small", "medium" to "Medium", "large" to "Large")

private sealed interface LiveDialog {
    data object AddM3u : LiveDialog
    data object AddXtream : LiveDialog
    data object AddEpg : LiveDialog
    data class PlaylistActions(val source: PlaylistSource) : LiveDialog
    data class EditPlaylist(val source: PlaylistSource) : LiveDialog
    data class EpgActions(val source: EpgSource) : LiveDialog
    data class EditEpg(val source: EpgSource) : LiveDialog
    data object HiddenChannels : LiveDialog
    data object HiddenGroups : LiveDialog
    data object EpgShift : LiveDialog
    data object CatchupShift : LiveDialog
    data object ClockMode : LiveDialog
    data object FrameRate : LiveDialog
    data object RecordPre : LiveDialog
    data object RecordPost : LiveDialog
    data object ClockPosition : LiveDialog
    data object ClockSize : LiveDialog
    data object ClockOpacity : LiveDialog
    data object ZapModeChoice : LiveDialog
    data object BannerTime : LiveDialog
    data object PlaylistRefresh : LiveDialog
    data object EpgRefresh : LiveDialog
    data object FutureDays : LiveDialog
    data object PastHours : LiveDialog
    data object SortChoice : LiveDialog
    data object HighlightChoice : LiveDialog
    data object CustomGroups : LiveDialog
    data object ConfirmRestore : LiveDialog
    data object PinCheck : LiveDialog
    data object PinNew : LiveDialog
    data object PinRemove : LiveDialog
    data object Reminders : LiveDialog
    data object StartPage : LiveDialog
    data object NameEditor : LiveDialog
    data object PosterTest : LiveDialog
    data object ButtonTester : LiveDialog
    data object LoadReport : LiveDialog
    data object HomeRowSource : LiveDialog
    data object HomeRowTitle : LiveDialog
    data class ConfirmDeletePlaylist(val source: PlaylistSource) : LiveDialog
    data class ConfirmDeleteEpg(val source: EpgSource) : LiveDialog
    /** "Are you sure?" before a reset, clear or similar. */
    data class Confirm(val title: String, val message: String, val label: String, val action: () -> Unit) : LiveDialog
}

private val EPG_OFFSETS = (-24..24).map { it * 30 }
private val REFRESH_HOURS = listOf(3, 6, 12, 24, 48, 72, 168)
private val BANNER_SECONDS = listOf(3, 5, 8, 10, 15)
private val FUTURE_DAYS = listOf(1, 2, 3, 5, 7, 10, 14)
private val PAST_HOURS = listOf(6, 12, 24, 48, 72, 168)

private fun offsetLabel(min: Int): String {
    if (min == 0) return "None"
    val sign = if (min > 0) "+" else "−"
    val a = kotlin.math.abs(min)
    return if (a % 60 == 0) "$sign${a / 60} h" else "$sign${a / 60} h ${a % 60} min"
}

private fun hoursLabel(h: Int): String = when {
    h >= 24 && h % 24 == 0 -> if (h == 24) "1 day" else "${h / 24} days"
    else -> "$h hours"
}

/** Typed into the channel name editor, this turns the hidden developer tools on or off. */
private const val DEVELOPER_WORD = "#nuviodev"

private fun startPageLabel(page: String): String = when (page) {
    "LIVE_TV" -> "Live TV"
    "ON_DEMAND" -> "On Demand"
    "DISCOVER" -> "Discover"
    "SEARCH" -> "Search"
    "LIBRARY" -> "Library"
    else -> "Home"
}

private fun sourceStatus(enabled: Boolean, updated: Long, error: String?, count: String): String {
    if (!enabled) return "Disabled"
    val parts = mutableListOf<String>()
    if (error != null) parts += "Error: $error"
    if (updated > 0) {
        parts += count
        parts += "Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated))
    } else if (error == null) {
        parts += "Not downloaded yet"
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun LiveTvSettingsContent(
    viewModel: LiveTvSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    page: LiveTvPage = LiveTvPage.ALL
) {
    // HUB: one Live TV page with its own row of tabs; the tab picked is the page shown.
    val hub = page == LiveTvPage.HUB
    var tab by remember { mutableStateOf(LiveTvPage.MAIN) }
    @Suppress("NAME_SHADOWING") val page = if (hub) tab else page
    fun show(p: LiveTvPage) = page == LiveTvPage.ALL || page == p
    val flat = page != LiveTvPage.ALL && page != LiveTvPage.MAIN
    /** The first section takes the focus when you come in from the list on the left (the tab row does, in a hub). */
    val firstRequester = if (hub) null else initialFocusRequester
    fun firstFocus(p: LiveTvPage): FocusRequester? = firstRequester.takeIf { page == p }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val epgs by viewModel.epgSources.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LiveDialog?>(null) }
    val drive by viewModel.driveSync.state.collectAsStateWithLifecycle()
    val developerToolsOn by com.nuvio.tv.livetv.model.DeveloperTools.enabled.collectAsStateWithLifecycle()
    val onDemandHas by viewModel.onDemand.hasContent.collectAsStateWithLifecycle()
    val onDemandStatus by viewModel.onDemand.status.collectAsStateWithLifecycle()
    var pinWrong by remember { mutableStateOf(false) }

    fun update(t: (LiveTvSettings) -> LiveTvSettings) = viewModel.update(t)

    // Every section starts folded; OK on its header opens or closes it (not remembered).
    var open by remember { mutableStateOf(setOf<String>()) }
    fun toggle(key: String) { open = if (key in open) open - key else open + key }
    /** Asks before doing something that can't simply be undone. */
    fun confirm(title: String, message: String, label: String, action: () -> Unit) {
        dialog = LiveDialog.Confirm(title, message, label, action)
    }
    val hasPin = s.parentalPin.length >= 4

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = page.title,
            subtitle = if (status.loading && !flat) (status.message ?: "Updating…") else page.subtitle
        )
        if (hub) LiveTabRow(selected = tab, first = initialFocusRequester, onSelect = { tab = it })
        androidx.compose.runtime.key(page) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLiveFlat provides flat) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val settleScope = androidx.compose.runtime.rememberCoroutineScope()
        val hiddenTop = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.toPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onPreviewKeyEvent { e ->
                    // Going up onto the first rows of a section: bring its heading into view too
                    // (it was left cut off at the top, as if behind the tabs).
                    if (e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) {
                        settleScope.launch {
                            kotlinx.coroutines.delay(90)
                            val top = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                            if (top != null && top.offset < 0 && top.offset > -hiddenTop) listState.animateScrollToItem(top.index)
                        }
                    }
                    false
                },
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            // ------------------------------------------------------------ Backup & Sync
            if (show(LiveTvPage.MAIN)) item(key = "drive") {
                DriveSyncCard(
                    modifier = if (firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier,
                    state = drive,
                    expanded = "drive" in open,
                    onToggleExpanded = { toggle("drive") },
                    onConnect = { viewModel.driveSync.beginSignIn() },
                    onAutoSync = { viewModel.driveSync.setAutoSync(it) },
                    onBackUp = {
                        confirm(
                            "Back up now?",
                            "The copy in your Google Drive is replaced with this TV's Live TV setup.",
                            "Back Up"
                        ) { viewModel.driveSync.backUpNow() }
                    },
                    onRestore = { dialog = LiveDialog.ConfirmRestore },
                    onDisconnect = {
                        confirm(
                            "Disconnect Google Drive?",
                            "This TV stops syncing. Your backup stays in your Google Drive.",
                            "Disconnect"
                        ) { viewModel.driveSync.disconnect() }
                    }
                )
            }

            // ------------------------------------------------------------ Playlists & Guides
            if (show(LiveTvPage.MAIN)) item(key = "sources") {
                LiveSection(
                    title = "Playlists & Guides",
                    summary = if (status.loading) (status.message ?: "Updating…")
                    else "${playlists.size} ${if (playlists.size == 1) "playlist" else "playlists"} · ${epgs.size} ${if (epgs.size == 1) "guide" else "guides"}",
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    open = "sources" in open,
                    onToggle = { toggle("sources") }
                ) {
                    SubHeading("Playlists")
                    if (status.loading) LoadingLine(status.message ?: "Updating…")
                    playlists.forEach { pl ->
                        val account = accounts[pl.id]
                        val expiring = pl.enabled && account?.expiringSoon() == true
                        SettingsActionRow(
                            title = pl.name + if (pl.isXtream) " (Xtream)" else "",
                            subtitle = when {
                                pl.isXtream && pl.enabled && !pl.importLive && pl.importVod -> "Movies & series only (TV channels off)"
                                pl.isXtream && pl.enabled && !pl.importLive && !pl.importVod -> "Nothing included: turn on TV channels or movies & series under Edit"
                                else -> sourceStatus(pl.enabled, pl.lastUpdatedMs, pl.lastError, "${pl.channelCount} channels") +
                                    (if (pl.isXtream && pl.importVod && pl.enabled) " · + movies & series" else "")
                            } + (account?.expiryLabel()?.let { " · Expires $it" } ?: ""),
                            value = when {
                                !pl.enabled -> "Off"
                                expiring && (account?.expiresAtMs ?: 0L) <= System.currentTimeMillis() -> "Expired"
                                expiring -> "Expires soon"
                                else -> "On"
                            },
                            valueColor = if (expiring) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                            leadingIcon = Icons.Default.LiveTv,
                            onClick = { dialog = LiveDialog.PlaylistActions(pl) }
                        )
                    }
                    SettingsActionRow(
                        title = "Add M3U Playlist",
                        subtitle = "A channel list from the M3U link your provider gave you",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddM3u }
                    )
                    SettingsActionRow(
                        title = "Add Xtream Codes Login",
                        subtitle = "Sign in with the server, username and password from your provider. Its TV guide comes with it.",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddXtream }
                    )
                    SubHeading("TV Guides (EPG)")
                    epgs.forEach { e ->
                        SettingsActionRow(
                            title = e.name,
                            subtitle = sourceStatus(e.enabled, e.lastUpdatedMs, e.lastError, "${e.programCount} programs matched"),
                            value = if (e.enabled) "On" else "Off",
                            leadingIcon = Icons.Default.Link,
                            onClick = { dialog = LiveDialog.EpgActions(e) }
                        )
                    }
                    SettingsActionRow(
                        title = "Add EPG Source",
                        subtitle = "Extra TV listings from an XMLTV link (ending in .xml or .xml.gz). Guides linked in your playlists load by themselves.",
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddEpg }
                    )
                    SubHeading("Updates")
                    SettingsActionRow(
                        title = "Update Everything Now",
                        subtitle = "Downloads the latest channels and listings instead of waiting for the next automatic update",
                        leadingIcon = Icons.Default.Refresh,
                        onClick = { viewModel.refreshAll() }
                    )
                    SettingsActionRow(
                        "Check Playlists for Updates Every", "How often channel lists are downloaded again from your providers",
                        hoursLabel(s.playlistRefreshHours), onClick = { dialog = LiveDialog.PlaylistRefresh }
                    )
                    SettingsActionRow(
                        "Check Guides for Updates Every", "How often TV listings are downloaded again",
                        hoursLabel(s.epgRefreshHours), onClick = { dialog = LiveDialog.EpgRefresh }
                    )
                }
            }

            // ------------------------------------------------------------ Channels
            if (show(LiveTvPage.MAIN)) item(key = "channels") {
                LiveSection(
                    title = "Channels",
                    summary = "Name cleanup, hidden channels and groups, your groups, resets",
                    icon = Icons.Default.LiveTv,
                    open = "channels" in open,
                    onToggle = { toggle("channels") }
                ) {
                    Text(
                        text = "Tip: long-press a channel in the guide to rename, renumber, hide or favorite it. Long-press a group to rename, move or hide it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                    SubHeading("Channel Names")
                    SettingsActionRow(
                        title = "Channel Name Cleanup",
                        subtitle = "Words to take off the start or end of channel names, like USA, 24/7 or FHD, separated by commas",
                        value = com.nuvio.tv.livetv.model.ChannelNameEditor.terms(s.nameRemovals).size.let { if (it == 0) "Off" else "$it removed" },
                        onClick = { dialog = LiveDialog.NameEditor }
                    )
                    if (s.nameRemovals.isBlank()) {
                        SettingsActionRow(
                            "Add Common Prefixes & Suffixes",
                            "Fills in: ${com.nuvio.tv.livetv.model.ChannelNameEditor.COMMON}",
                            onClick = { update { it.copy(nameRemovals = com.nuvio.tv.livetv.model.ChannelNameEditor.COMMON) } }
                        )
                    }
                    SubHeading("Hidden & Your Groups")
                    SettingsActionRow("Hidden Channels", "Channels you hid. Select one to show it again.", "${user.hiddenChannels.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenChannels })
                    SettingsActionRow("Hidden Groups", "Groups you hid. Select one to show it again.", "${user.hiddenGroups.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenGroups })
                    SettingsActionRow("My Groups", "Groups you created. Select one to delete it.", "${user.customGroups.size}", onClick = { dialog = LiveDialog.CustomGroups })
                    SubHeading("Clear & Reset")
                    SettingsActionRow("Clear Favorites", "Removes every channel from Favorites", "${user.favorites.size}", onClick = {
                        confirm("Clear favorites?", "Every channel is taken out of Favorites.", "Clear") { viewModel.clearFavorites() }
                    })
                    SettingsActionRow("Clear Recently Watched", "Empties the Recently Watched group", "${user.recent.size}", onClick = {
                        confirm("Clear recently watched?", "The Recently Watched group is emptied.", "Clear") { viewModel.clearRecent() }
                    })
                    SettingsActionRow(
                        "Reset Channel Names & Numbers", "Puts back the names and numbers from your playlist",
                        "${(user.channelNames.keys + user.channelNumbers.keys).size}",
                        onClick = {
                            confirm("Reset channel names and numbers?", "Every channel you renamed or renumbered goes back to its name and number from the playlist.", "Reset") { viewModel.resetChannelEdits() }
                        }
                    )
                    SettingsActionRow("Reset Group Names & Order", "Puts back the group names and order from your playlist", onClick = {
                        confirm("Reset group names and order?", "Renamed groups get their playlist names back, and the groups go back to the playlist's order.", "Reset") { viewModel.resetGroupEdits() }
                    })
                    SettingsActionRow(
                        "Reset Channel Order", "Undoes Reorder Channels in every group",
                        "${user.channelOrder.size}", onClick = {
                            confirm("Reset channel order?", "Every group goes back to its normal channel order.", "Reset") { viewModel.resetChannelOrder() }
                        }
                    )
                    SettingsActionRow(
                        "Remove Copied Channels", "Takes out every channel you copied into another group. The originals stay.",
                        "${user.channelCopies.values.sumOf { it.size }}", onClick = {
                            confirm("Remove copied channels?", "Every channel you copied into another group is taken out of it. The originals stay where they are.", "Remove") { viewModel.clearChannelCopies() }
                        }
                    )
                    SettingsActionRow(
                        "Reset EPG Assignments",
                        "Channels you assigned a guide to go back to automatic matching",
                        "${user.epgOverrides.size}",
                        onClick = {
                            confirm("Reset EPG assignments?", "Every channel you assigned a guide to, or unassigned, goes back to automatic matching.", "Reset") { viewModel.resetEpgAssignments() }
                        }
                    )
                }
            }

            // ------------------------------------------------------------ Groups
            if (show(LiveTvPage.MAIN)) item(key = "groups") {
                LiveSection(
                    title = "Groups & Channel Order",
                    summary = "Your order, playlist order, or alphabetical sorting",
                    icon = Icons.Default.Folder,
                    open = "groups" in open,
                    onToggle = { toggle("groups") }
                ) {
                    SubHeading("Built-In Groups")
                    SettingsToggleRow(
                        "Show Favorites",
                        "The group of channels you've marked as favorites",
                        s.showFavoritesGroup,
                        { update { it.copy(showFavoritesGroup = !it.showFavoritesGroup) } }
                    )
                    SettingsToggleRow(
                        "Show Recently Watched",
                        "The group of channels you watched last",
                        s.showRecentGroup,
                        { update { it.copy(showRecentGroup = !it.showRecentGroup) } }
                    )
                    SettingsToggleRow(
                        "Show All Channels",
                        "The group with every channel in it",
                        s.showAllChannelsGroup,
                        { update { it.copy(showAllChannelsGroup = !it.showAllChannelsGroup) } }
                    )
                    SubHeading("The List")
                    SettingsToggleRow(
                        "Group by Playlist",
                        "With more than one playlist, each playlist's groups sit under its own heading, which you can fold away",
                        s.groupPlaylistHeadings,
                        { update { it.copy(groupPlaylistHeadings = !it.groupPlaylistHeadings) } }
                    )
                    SettingsToggleRow(
                        "Show Channel Counts",
                        "The number of channels next to each group",
                        s.showGroupCounts,
                        { update { it.copy(showGroupCounts = !it.showGroupCounts) } }
                    )
                    SettingsActionRow(
                        title = "Group Order",
                        subtitle = "How the group list is sorted. Your order follows Reorder Groups; anything you haven't moved stays in playlist order.",
                        value = when (s.groupSort) { "playlist" -> "Playlist order"; "name" -> "Name (A–Z)"; else -> "Your order" },
                        onClick = {
                            update { st -> st.copy(groupSort = when (st.groupSort) { "custom" -> "playlist"; "playlist" -> "name"; else -> "custom" }) }
                        }
                    )
                    SubHeading("Channels in a Group")
                    SettingsActionRow(
                        title = "Channel Order",
                        subtitle = "How channels are sorted in every group, Favorites included. Playlist order keeps your own order in Favorites and your groups.",
                        value = when (s.channelSort) {
                            ChannelSort.PLAYLIST -> "Playlist order"
                            ChannelSort.NUMBER -> "Channel number"
                            ChannelSort.NAME -> "Name"
                        },
                        onClick = { dialog = LiveDialog.SortChoice }
                    )
                    SettingsToggleRow(
                        "Number Channels 1, 2, 3… in Each Group",
                        "Replaces the playlist's numbers with each channel's place in the group: 20 favorites become 1 to 20",
                        s.sequentialNumbers,
                        { update { it.copy(sequentialNumbers = !it.sequentialNumbers) } }
                    )
                }
            }

            // ------------------------------------------------------------ On Demand
            if (show(LiveTvPage.ON_DEMAND)) item(key = "on_demand") {
                LiveSection(
                    title = "On Demand",
                    summary = when {
                        onDemandStatus.loading -> onDemandStatus.message ?: "Importing movies and series…"
                        !onDemandHas -> "Nothing imported yet"
                        else -> "Movies & series imported" + if (s.onDemandInSidebar) " · in the side menu" else ""
                    },
                    icon = Icons.Default.Movie,
                    open = "on_demand" in open,
                    onToggle = { toggle("on_demand") },
                    focusFirst = firstFocus(LiveTvPage.ON_DEMAND)
                ) {
                    Text(
                        text = "Movies and series from Xtream logins with movies & series switched on below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                    if (onDemandStatus.loading) LoadingLine(onDemandStatus.message ?: "Importing movies and series…")
                    SubHeading("Providers")
                    SettingsActionRow(
                        "Update Movies & Series Now",
                        onDemandStatus.message ?: if (onDemandHas) "Imported. Updates once a day by itself." else "Nothing imported yet",
                        onClick = { viewModel.onDemand.refreshNow() }
                    )
                    // One switch per Xtream login (logins added before On Demand start switched off).
                    playlists.filter { it.isXtream }.forEach { pl ->
                        SettingsToggleRow(
                            "Movies & Series from ${pl.name}",
                            if (pl.importVod) "Included" else "Not included. Switch on to import this provider's movies and series.",
                            pl.importVod,
                            { viewModel.savePlaylist(pl.copy(importVod = !pl.importVod)) }
                        )
                    }
                    SubHeading("In Nuvio")
                    SettingsToggleRow(
                        "Show On Demand in the Side Menu",
                        "Its own page in Nuvio's menu, once a provider's movies or series are imported",
                        s.onDemandInSidebar,
                        { update { it.copy(onDemandInSidebar = !it.onDemandInSidebar) } }
                    )
                    SubHeading("Browsing")
                    var pickingSort by remember { mutableStateOf(false) }
                    val defaultSort = com.nuvio.tv.livetv.ondemand.VodSort.parse(s.vodDefaultSort)?.first
                        ?: com.nuvio.tv.livetv.ondemand.VodSort.DEFAULT
                    SettingsActionRow(
                        title = "Default Sort Order",
                        subtitle = "How each group is sorted, unless you pick a sort for it in See all or Manage VOD Groups",
                        value = defaultSort.label,
                        onClick = { pickingSort = true }
                    )
                    if (pickingSort) com.nuvio.tv.livetv.ondemand.VodSortPicker(
                        title = "Default Sort Order",
                        current = defaultSort,
                        onPick = { picked ->
                            pickingSort = false
                            val p = picked ?: com.nuvio.tv.livetv.ondemand.VodSort.DEFAULT
                            update { it.copy(vodDefaultSort = com.nuvio.tv.livetv.ondemand.VodSort.encode(p)) }
                        },
                        onDismiss = { pickingSort = false }
                    )
                    SettingsToggleRow(
                        "Merge Duplicates",
                        "Shows each movie or series once, even when your provider lists several copies (other qualities, categories or providers). Play lets you pick the version.",
                        s.vodMergeDuplicates,
                        { update { it.copy(vodMergeDuplicates = !it.vodMergeDuplicates) } }
                    )
                    SettingsToggleRow(
                        "Use Posters from My Addons",
                        "Your addons' posters where they have the title. Off: the provider's images only.",
                        s.onDemandAddonPosters,
                        { update { it.copy(onDemandAddonPosters = !it.onDemandAddonPosters) } }
                    )
                    SettingsActionRow(
                        "Show Hidden Categories", "Brings back every On Demand category you hid",
                        "${user.vodHiddenCategories.size}", onClick = {
                            confirm("Show hidden categories?", "Every On Demand category you hid comes back.", "Show") { viewModel.clearVodHidden() }
                        }
                    )
                }
            }

            // ------------------------------------------------------------ Guide Layout
            if (show(LiveTvPage.GUIDE)) item(key = "layout") {
                LiveSection(
                    title = "Guide Layout",
                    summary = "What the guide shows and how much fits",
                    icon = Icons.Default.GridView,
                    open = "layout" in open,
                    onToggle = { toggle("layout") },
                    focusFirst = firstFocus(LiveTvPage.GUIDE)
                ) {
                    SubHeading("Top of the Guide")
                    SettingsToggleRow(
                        "Show Info Panel",
                        "The panel with the highlighted show's poster, title, time and description",
                        s.showProgramDetails,
                        { update { it.copy(showProgramDetails = !it.showProgramDetails) } }
                    )
                    SettingsToggleRow(
                        "Show Preview Window",
                        "The small video of the highlighted channel in the top-right corner",
                        s.showPreview,
                        { update { it.copy(showPreview = !it.showPreview) } }
                    )
                    SettingsToggleRow(
                        "Smaller Info Panel & Preview",
                        "Shrinks the top of the guide to about two-thirds of its height, so more channels fit",
                        s.smallHeader,
                        { update { it.copy(smallHeader = !it.smallHeader) } }
                    )
                    SettingsActionRow(
                        title = "Guide Darkness",
                        subtitle = "How dark the info panel at the top and the bars in the guide are. Lightest lets more of the background show through. Darkest makes the text easiest to read.",
                        value = when (s.guideInfoBackground) { "darker" -> "Standard"; "solid" -> "Darkest"; else -> "Lightest" },
                        onClick = {
                            update {
                                it.copy(guideInfoBackground = when (it.guideInfoBackground) { "standard" -> "darker"; "darker" -> "solid"; else -> "standard" })
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show Posters",
                        "Posters for what's on, from your addons. Off: just the channel logo.",
                        s.showPosters,
                        { update { it.copy(showPosters = !it.showPosters) } }
                    )
                    SettingsToggleRow(
                        "Show Playlist Name in Info Panel",
                        "Which playlist the highlighted channel comes from",
                        s.showPlaylistInInfo,
                        { update { it.copy(showPlaylistInInfo = !it.showPlaylistInInfo) } }
                    )
                    SubHeading("Info Panel Parts")
                    SettingsToggleRow(
                        "Show Channel Name & Logo",
                        "The channel line at the top of the info panel",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.CHANNEL !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.CHANNEL
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show LIVE & Catch-up Tags",
                        "The small tags beside the channel name",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.TAGS !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.TAGS
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show Title",
                        "The big show title",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.TITLE !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.TITLE
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show Start & End Time",
                        "Like 8:00 PM – 9:00 PM",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.TIME !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.TIME
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show Time Left",
                        "Like 25 min left",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.LEFT !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.LEFT
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show Category",
                        "Like Drama or Sports",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.CATEGORY !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.CATEGORY
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show Progress Bar",
                        "How far into the show you are",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.PROGRESS !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.PROGRESS
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        "Show Description",
                        "The text about the show",
                        com.nuvio.tv.livetv.ui.GuideInfoPart.DESCRIPTION !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.DESCRIPTION
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SubHeading("Channel Rows")
                    SettingsToggleRow(
                        "Show Channel Names",
                        "Off: just numbers and logos, which leaves more room for the schedule",
                        s.showChannelNames,
                        { update { it.copy(showChannelNames = !it.showChannelNames) } }
                    )
                    SettingsToggleRow(
                        "Show Channel Numbers",
                        "The number in front of each channel",
                        s.showChannelNumbers,
                        { update { it.copy(showChannelNumbers = !it.showChannelNumbers) } }
                    )
                    SettingsToggleRow(
                        "Show Channel Logos",
                        "The logo in front of each channel name",
                        s.showChannelLogos,
                        { update { it.copy(showChannelLogos = !it.showChannelLogos) } }
                    )
                    var pickingGuideSize by remember { mutableStateOf(false) }
                    val guideSizes = listOf(
                        Triple("COMPACT", "Compact", "Slimmer rows, slightly smaller text: about two more channels fit"),
                        Triple("STANDARD", "Standard", "The usual size"),
                        Triple("LARGE", "Large", "Bigger text and rows: about three fewer channels fit"),
                        Triple("XLARGE", "Extra Large", "The biggest text and rows: about five fewer channels fit, and 1½ hours across")
                    )
                    val currentSize = s.effectiveGuideSize
                    SettingsActionRow(
                        title = "Guide Size",
                        subtitle = "How big the text and rows in the guide are",
                        value = guideSizes.firstOrNull { it.first == currentSize }?.second ?: "Standard",
                        onClick = { pickingGuideSize = true }
                    )
                    if (pickingGuideSize) ActionListDialog(
                        title = "Guide Size",
                        actions = guideSizes.map { (key, label, _) ->
                            (if (key == currentSize) "$label  ✓" else label) to {
                                pickingGuideSize = false
                                update { it.copy(guideSize = key, compactRows = key == "COMPACT") }
                            }
                        },
                        onDismiss = { pickingGuideSize = false }
                    )
                    SettingsToggleRow(
                        "Show Times in the Guide",
                        "Each show's start and end time under its title (rows get a little taller)",
                        s.showProgramTimes,
                        { update { it.copy(showProgramTimes = !it.showProgramTimes) } }
                    )
                    SettingsToggleRow(
                        "Show Episode Numbers",
                        "Episode numbers like \"S2 E5\" after show titles, in the guide, show info and the player",
                        s.showEpisodeNumbers,
                        { update { it.copy(showEpisodeNumbers = !it.showEpisodeNumbers) } }
                    )
                    SettingsToggleRow(
                        "Show the \"Now\" Line",
                        "The line that marks the current time across the guide",
                        s.showNowLine,
                        { update { it.copy(showNowLine = !it.showNowLine) } }
                    )
                    SettingsToggleRow(
                        "Quality Badges",
                        "4K, FHD, HD or SD next to channels you've watched",
                        s.showQualityBadges,
                        { update { it.copy(showQualityBadges = !it.showQualityBadges) } }
                    )
                    SettingsToggleRow(
                        "Frame Rate on the Info Bar",
                        "Shows how smooth the channel is (25, 30, 50 or 60 FPS) next to the picture size while watching full screen. Handy for live sports.",
                        s.showFrameRate,
                        { update { it.copy(showFrameRate = !it.showFrameRate) } }
                    )
                    SubHeading("Look")
                    SettingsActionRow(
                        title = "Guide & Sports Background",
                        subtitle = "The backdrop behind the TV Guide and the Sports hub. Follows your color theme, or pick one of the app icon colors.",
                        value = com.nuvio.tv.livetv.ui.GuideBackdrops.label(s.guideBackdrop),
                        onClick = { update { it.copy(guideBackdrop = com.nuvio.tv.livetv.ui.GuideBackdrops.next(it.guideBackdrop)) } }
                    )
                    SettingsActionRow(
                        title = "Highlight Style",
                        subtitle = "How the selected show is marked: an outline, or filled with your theme color",
                        value = if (s.solidHighlight) "Solid" else "Outline",
                        onClick = { dialog = LiveDialog.HighlightChoice }
                    )
                    SettingsToggleRow(
                        "24-Hour Clock",
                        "Times like 20:30 instead of 8:30 PM",
                        s.use24HourClock,
                        { update { it.copy(use24HourClock = !it.use24HourClock) } }
                    )
                }
            }

            // ------------------------------------------------------------ Watching
            if (show(LiveTvPage.PLAYBACK)) item(key = "watching") {
                LiveSection(
                    title = "Opening & Channel Surfing",
                    summary = "Opening Live TV, changing channels, the info bar",
                    icon = Icons.Default.PlayCircleFilled,
                    open = "watching" in open,
                    onToggle = { toggle("watching") },
                    focusFirst = firstFocus(LiveTvPage.PLAYBACK),
                ) {
                    SubHeading("Opening Live TV")
                    SettingsToggleRow(
                        "Open on Last Group",
                        "Live TV opens on the group you last watched a channel in",
                        s.rememberLastGroup,
                        { update { it.copy(rememberLastGroup = !it.rememberLastGroup) } }
                    )
                    SettingsToggleRow(
                        "Play Last Channel in the Preview",
                        "Opening Live TV starts the channel you watched last in the preview window",
                        s.resumeLastInPreview,
                        { update { it.copy(resumeLastInPreview = !it.resumeLastInPreview) } }
                    )
                    SettingsToggleRow(
                        "Start in Full Screen",
                        "The first time you open Live TV after starting the app, your last channel plays full screen",
                        s.autoPlayLastChannel,
                        { update { it.copy(autoPlayLastChannel = !it.autoPlayLastChannel) } }
                    )
                    SubHeading("Moving Around")
                    SettingsToggleRow(
                        "OK Opens Full Screen",
                        "OK on a show goes straight to full screen, instead of playing it in the preview first",
                        s.openFullscreenOnSelect,
                        { update { it.copy(openFullscreenOnSelect = !it.openFullscreenOnSelect) } }
                    )
                    SettingsToggleRow(
                        "Land on Channel Names (Like a Cable Box)",
                        "Picking a group lands on the channel names: Up and Down change channel, OK plays, Right shows the schedule, Left goes back to the groups",
                        s.browseByChannelName,
                        { update { it.copy(browseByChannelName = !it.browseByChannelName) } }
                    )
                    SettingsToggleRow(
                        "Overlay Mode",
                        "Left while watching shows channels and schedules over the video. Off: Left goes back to the guide.",
                        s.overlayMode,
                        { update { it.copy(overlayMode = !it.overlayMode) } }
                    )
                    SettingsActionRow(
                        title = "Channel Up / Down Moves Within",
                        subtitle = "Which channels Up and Down (or CH+ and CH−) step through while watching",
                        value = if (s.zapMode == ZapMode.GROUP) "Current group" else "All channels",
                        onClick = { dialog = LiveDialog.ZapModeChoice }
                    )
                    SettingsToggleRow(
                        "Channel Surfing (Browse Before Switching)",
                        "Up / Down and CH+ / CH− show what's on other channels on the info bar without switching. What you're watching keeps playing until you press OK on the one you want. Back keeps you where you are.",
                        s.channelSurf,
                        { update { it.copy(channelSurf = !it.channelSurf) } }
                    )
                    SettingsToggleRow(
                        "Reverse Channel Up / Down",
                        "Up goes to the previous channel instead of the next one",
                        s.reverseZap,
                        { update { it.copy(reverseZap = !it.reverseZap) } }
                    )
                    SettingsActionRow(
                        title = "Channel Row in the Info Panel",
                        subtitle = "Pressing OK while watching shows a row of channels to jump to: the ones you watched recently (adult channels are never listed), or your favorites",
                        value = when (s.panelRow) { "FAVORITES" -> "Favorites"; "OFF" -> "Off"; else -> "Recently Watched" },
                        onClick = {
                            update {
                                it.copy(panelRow = when (it.panelRow) { "RECENT" -> "FAVORITES"; "FAVORITES" -> "OFF"; else -> "RECENT" })
                            }
                        }
                    )
                    SettingsActionRow(
                        title = "Play Bar Background",
                        subtitle = "How dark the play bar is. Standard or Darkest makes the text easier to read over bright, busy pictures.",
                        value = when (s.panelBackground) { "darker" -> "Standard"; "solid" -> "Darkest"; else -> "Lightest" },
                        onClick = {
                            update {
                                it.copy(panelBackground = when (it.panelBackground) { "standard" -> "darker"; "darker" -> "solid"; else -> "standard" })
                            }
                        }
                    )
                    SettingsActionRow(
                        title = "Info Bar Stays For",
                        subtitle = "How long the show info stays on screen after changing channel or pressing OK",
                        value = "${s.infoBannerSeconds} seconds",
                        onClick = { dialog = LiveDialog.BannerTime }
                    )
                }
            }

            // ------------------------------------------------------------ Picture, Sound & Clock
            if (show(LiveTvPage.PLAYBACK)) item(key = "picture") {
                LiveSection(
                    title = "Picture, Sound & Clock",
                    summary = "The clock, frame rate, pausing and catch-up",
                    icon = Icons.Default.PlayCircleFilled,
                    open = "picture" in open,
                    onToggle = { toggle("picture") }
                ) {
                    SubHeading("On-Screen Clock")
                    SettingsActionRow(
                        title = "Show Clock",
                        subtitle = "A clock in the corner while you watch. With player controls: only on the info panel.",
                        value = CLOCK_MODES.firstOrNull { it.first == s.clockMode }?.second ?: "With player controls",
                        onClick = { dialog = LiveDialog.ClockMode }
                    )
                    if (s.clockMode == "always") {
                        SettingsActionRow(
                            title = "Clock Position",
                            subtitle = "Which corner of the screen",
                            value = CLOCK_POSITIONS.firstOrNull { it.first == s.clockPosition }?.second ?: "Top right",
                            onClick = { dialog = LiveDialog.ClockPosition }
                        )
                        SettingsActionRow(
                            title = "Clock Size",
                            subtitle = "How big the time is",
                            value = CLOCK_SIZES.firstOrNull { it.first == s.clockSize }?.second ?: "Medium",
                            onClick = { dialog = LiveDialog.ClockSize }
                        )
                        SettingsActionRow(
                            title = "Clock Opacity",
                            subtitle = "Lower is more see-through, so it doesn't distract",
                            value = "${s.clockOpacity}%",
                            onClick = { dialog = LiveDialog.ClockOpacity }
                        )
                        SettingsToggleRow(
                            "Clock Background",
                            "A soft dark box behind the time, so it's easy to read on bright pictures",
                            s.clockBackground,
                            { update { it.copy(clockBackground = !it.clockBackground) } }
                        )
                    }
                    SubHeading("Picture & Sound")
                    SettingsToggleRow(
                        "Captions On by Default",
                        "Turns captions on when a channel has them. You can still switch them off with CC.",
                        s.captionsByDefault,
                        { update { it.copy(captionsByDefault = !it.captionsByDefault) } }
                    )
                    SettingsToggleRow(
                        "Audio Passthrough",
                        "Sends Dolby audio untouched to your TV or speaker. Turn off if sound cuts out, especially with Echo speakers or Alexa Home Theater on Fire TV.",
                        s.audioPassthrough,
                        { update { it.copy(audioPassthrough = !it.audioPassthrough) } }
                    )
                    SettingsActionRow(
                        title = "Match Frame Rate",
                        subtitle = "Switches the TV to the video's frame rate (for example 50 Hz for UK and European channels) for smoother motion, on TVs that support it",
                        value = FRAME_RATE_MODES.firstOrNull { it.first == s.frameRateMode }?.second ?: "Off",
                        onClick = { dialog = LiveDialog.FrameRate }
                    )
                    SubHeading("Pause & Catch-Up")
                    SettingsToggleRow(
                        "Pause and Rewind Live TV",
                        "On channels without catch-up, pausing records the channel to this device, so it carries on from where you paused",
                        s.timeshiftEnabled,
                        { update { it.copy(timeshiftEnabled = !it.timeshiftEnabled) } }
                    )
                    if (s.timeshiftEnabled) SettingsToggleRow(
                        "Rewind Anytime",
                        "Full-screen channels keep a rolling recording, so you can skip back without pausing first (Left / Right once you're behind live, or from the panel). Channels take a second longer to start.",
                        s.rewindAlways,
                        { update { it.copy(rewindAlways = !it.rewindAlways) } }
                    )
                    if (s.timeshiftEnabled) {
                        // Longer buffers when recordings go to a USB drive (far more room).
                        val onDrive = s.recordingStorage.isNotEmpty()
                        // 10 minutes by default: Fire TV Sticks and similar have little free space.
                        val lengths = if (onDrive) listOf(10, 15, 30, 60, 120, 240) else listOf(10, 15, 30, 60, 120)
                        SettingsActionRow(
                            title = "Keep Up To",
                            subtitle = "How far back you can go. Saved to the drive set in Recordings, and deleted when you change channel or leave Live TV.",
                            value = "${s.timeshiftMinutes} min · about " + (s.timeshiftMinutes * 35).let { mb -> if (mb >= 1000) String.format(java.util.Locale.US, "%.1f GB", mb / 1024f) else "$mb MB" },
                            onClick = {
                                update { st ->
                                    val i = lengths.indexOf(st.timeshiftMinutes)
                                    st.copy(timeshiftMinutes = lengths[if (i < 0) 1 else (i + 1) % lengths.size])
                                }
                            }
                        )
                    }
                    SettingsToggleRow(
                        "Faster Catch-Up Skipping",
                        "Saves a replay to the device while you watch it, so rewinding and skipping ahead are instant instead of reloading. Uses about 35 MB a minute, deleted when you stop watching.",
                        s.catchupCache,
                        { update { it.copy(catchupCache = !it.catchupCache) } }
                    )
                    SettingsToggleRow(
                        "Prefer HLS for Catch-Up",
                        "Asks Xtream providers for replays in HLS (m3u8) first, so the seek bar works. Falls back to TS if the provider doesn't offer it.",
                        s.catchupPreferHls,
                        { update { it.copy(catchupPreferHls = !it.catchupPreferHls) } }
                    )
                    SettingsActionRow(
                        title = "Catch-Up Time Shift",
                        subtitle = "If replays start the show before (or after) the one you picked, move the time asked for by that much. Example: it plays the show an hour earlier, set +1 h.",
                        value = offsetLabel(s.catchupShiftMinutes),
                        onClick = { dialog = LiveDialog.CatchupShift }
                    )
                    SubHeading("Connection")
                    SettingsActionRow(
                        title = "Buffer Size",
                        subtitle = "Larger rides out a slow connection or provider (fewer pauses); smaller changes channels a little faster",
                        value = when (s.bufferSize) { "small" -> "Small"; "large" -> "Large"; "xlarge" -> "Extra large"; else -> "Normal" },
                        onClick = {
                            update { st -> st.copy(bufferSize = when (st.bufferSize) { "small" -> "normal"; "normal" -> "large"; "large" -> "xlarge"; else -> "small" }) }
                        }
                    )
                    SettingsToggleRow(
                        "Reconnect Automatically",
                        "Tries the stream again by itself if it drops or freezes",
                        s.autoReconnect,
                        { update { it.copy(autoReconnect = !it.autoReconnect) } }
                    )
                    SettingsToggleRow(
                        "Switch to a Backup Channel",
                        "If a channel won't play, switch to another channel with the same name from another group or playlist (like \"ESPN\" and \"ESPN Backup\")",
                        s.autoBackupChannel,
                        { update { it.copy(autoBackupChannel = !it.autoBackupChannel) } }
                    )
                }
            }

            // ------------------------------------------------------------ Guide Listings
            if (show(LiveTvPage.GUIDE)) item(key = "listings") {
                LiveSection(
                    title = "Guide Listings",
                    summary = "How far the guide reaches, timing fixes",
                    icon = Icons.Default.Schedule,
                    open = "listings" in open,
                    onToggle = { toggle("listings") }
                ) {
                    SettingsActionRow(
                        "Days Ahead", "How far into the future the guide keeps shows",
                        if (s.epgFutureDays == 1) "1 day" else "${s.epgFutureDays} days", onClick = { dialog = LiveDialog.FutureDays }
                    )
                    SettingsActionRow(
                        "Past Shows Kept", "How far back the guide keeps shows, for catch-up",
                        hoursLabel(s.epgPastHours), onClick = { dialog = LiveDialog.PastHours }
                    )
                    SettingsActionRow(
                        title = "Guide Time Shift",
                        subtitle = "Moves all listings earlier or later, for a guide that's out of step with what's playing",
                        value = offsetLabel(s.epgOffsetMinutes),
                        onClick = { dialog = LiveDialog.EpgShift }
                    )
                    val clearContext = androidx.compose.ui.platform.LocalContext.current
                    SettingsActionRow(
                        "Clear Posters and Show Info",
                        "Forgets the posters and show info found for Live TV, so they're looked up fresh from your addons",
                        onClick = {
                            confirm(
                                "Clear posters and show info?",
                                "Live TV forgets every poster and show description it found. They're looked up again from your addons as you browse.",
                                "Clear"
                            ) {
                                viewModel.clearPosterData()
                                android.widget.Toast.makeText(clearContext, "Posters and show info cleared", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            // ------------------------------------------------------------ Home Screen & Nuvio
            if (show(LiveTvPage.INTEGRATION)) item(key = "nuvio") {
                LiveSection(
                    title = "Home Screen & Nuvio",
                    summary = "The Live TV row inside Nuvio's home page, and the menu",
                    icon = Icons.Default.Home,
                    open = "nuvio" in open,
                    onToggle = { toggle("nuvio") },
                    focusFirst = firstFocus(LiveTvPage.INTEGRATION)
                ) {
                    // ---- Live TV row on the home screen
                    // On or off for the Nuvio profile in use (each profile has its own choice).
                    val homeProfileContext = androidx.compose.ui.platform.LocalContext.current
                    val homeProfileId by remember {
                        dagger.hilt.android.EntryPointAccessors.fromApplication(
                            homeProfileContext.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
                        ).profileManager()
                    }.activeProfileId.collectAsStateWithLifecycle()
                    val homeRowOn = homeProfileId !in s.homeRowHiddenProfiles
                    SubHeading("Row Inside Nuvio's Home Page")
                    SettingsToggleRow(
                        "Live TV Row on the Home Screen",
                        "For this profile. Tucked above your top row: press Up to show it.",
                        homeRowOn,
                        {
                            update { st ->
                                st.copy(
                                    homeRowHiddenProfiles = if (homeProfileId in st.homeRowHiddenProfiles) st.homeRowHiddenProfiles - homeProfileId
                                    else st.homeRowHiddenProfiles + homeProfileId
                                )
                            }
                        }
                    )
                    if (homeRowOn) {
                        SettingsActionRow(
                            title = "Channels in the Row",
                            subtitle = "Which channels appear: Favorites, Recently Watched, or one of your own groups",
                            value = when (s.homeRowSource) {
                                "favorites", "" -> "Favorites"
                                "recent" -> "Recently watched"
                                else -> user.customGroups.firstOrNull { it.id == s.homeRowSource }?.name ?: "Favorites"
                            },
                            onClick = { dialog = LiveDialog.HomeRowSource }
                        )
                        SettingsActionRow(
                            title = "Row Title",
                            subtitle = "The name shown above the row",
                            value = s.homeRowTitle.ifBlank { "Live TV" },
                            onClick = { dialog = LiveDialog.HomeRowTitle }
                        )
                        SettingsActionRow(
                            title = "Row Order",
                            subtitle = "Your order, channel number, or the shows ending soonest first",
                            value = when (s.homeRowSort) { "number" -> "Channel number"; "ending" -> "Ending soonest"; else -> "Your order" },
                            onClick = {
                                update { st -> st.copy(homeRowSort = when (st.homeRowSort) { "yours" -> "number"; "number" -> "ending"; else -> "yours" }) }
                            }
                        )
                        SettingsActionRow(
                            title = "Channels in the Row",
                            subtitle = "How many channels the row shows",
                            value = if (s.homeRowLimit == 0) "All" else "${s.homeRowLimit}",
                            onClick = {
                                update { st -> st.copy(homeRowLimit = when (st.homeRowLimit) { 10 -> 20; 20 -> 40; 40 -> 0; else -> 10 }) }
                            }
                        )
                        SettingsToggleRow(
                            "Hide Channels with No Listings",
                            "Leaves out channels showing only \"Programming\" or no information",
                            s.homeRowHidePlaceholders,
                            { update { it.copy(homeRowHidePlaceholders = !it.homeRowHidePlaceholders) } }
                        )
                        SettingsToggleRow(
                            "OK Opens the Guide",
                            "Off: OK plays the channel full screen. On: opens the guide with the channel playing.",
                            s.homeRowOpensGuide,
                            { update { it.copy(homeRowOpensGuide = !it.homeRowOpensGuide) } }
                        )
                        SettingsToggleRow(
                            "Show What's Next",
                            "A \"Next: 9:00 The News\" line on each card",
                            s.homeRowShowNext,
                            { update { it.copy(homeRowShowNext = !it.homeRowShowNext) } }
                        )
                        SettingsToggleRow(
                            "Compact Cards",
                            "Just the logo and the show name",
                            s.homeRowCompact,
                            { update { it.copy(homeRowCompact = !it.homeRowCompact) } }
                        )
                    }
                    SubHeading("Menu")
                    SettingsToggleRow(
                        "Show Live TV in the Side Menu",
                        "Off: Live TV leaves Nuvio's main menu. You can still reach it from Settings.",
                        s.showInSidebar,
                        { update { it.copy(showInSidebar = !it.showInSidebar) } }
                    )
                }
            }

            // ------------------------------------------------------------ Search & Streams
            if (show(LiveTvPage.INTEGRATION)) item(key = "search_streams") {
                var pickingStreamsPosition by remember { mutableStateOf(false) }
                LiveSection(
                    title = "Search & Streams",
                    summary = "Search order · On Demand in streams",
                    icon = Icons.Default.Search,
                    open = "search_streams" in open,
                    onToggle = { toggle("search_streams") }
                ) {
                    SubHeading("Search Results Order")
                    SearchOrderList(
                        order = com.nuvio.tv.livetv.ui.parseSearchOrder(s.searchOrder),
                        onChange = { newOrder -> update { it.copy(searchOrder = newOrder.joinToString(",")) } }
                    )
                    SettingsToggleRow(
                        "Preview Channels Behind Search",
                        "OK on a channel plays it behind the search results, to check it loads and how it looks. OK again opens it full screen.",
                        s.searchPreview,
                        { update { it.copy(searchPreview = !it.searchPreview) } }
                    )
                    // Search from Home and the side menu always shows everything; these set what
                    // search shows when it's opened from the guide or from On Demand.
                    SubHeading("Search from the Guide Shows")
                    SettingsToggleRow(
                        "Live TV",
                        "Channels and shows in the guide",
                        s.guideSearchLive,
                        { update { it.copy(guideSearchLive = !it.guideSearchLive) } }
                    )
                    SettingsToggleRow(
                        "Movies & Series",
                        "Titles from your Nuvio addons",
                        s.guideSearchCatalog,
                        { update { it.copy(guideSearchCatalog = !it.guideSearchCatalog) } }
                    )
                    SettingsToggleRow(
                        "On Demand",
                        "Your provider's own movies and series",
                        s.guideSearchOnDemand,
                        { update { it.copy(guideSearchOnDemand = !it.guideSearchOnDemand) } }
                    )
                    SubHeading("Search from On Demand Shows")
                    SettingsToggleRow(
                        "Live TV",
                        "Channels and shows in the guide",
                        s.onDemandSearchLive,
                        { update { it.copy(onDemandSearchLive = !it.onDemandSearchLive) } }
                    )
                    SettingsToggleRow(
                        "Movies & Series",
                        "Titles from your Nuvio addons",
                        s.onDemandSearchCatalog,
                        { update { it.copy(onDemandSearchCatalog = !it.onDemandSearchCatalog) } }
                    )
                    SettingsToggleRow(
                        "On Demand",
                        "Your provider's own movies and series",
                        s.onDemandSearchOnDemand,
                        { update { it.copy(onDemandSearchOnDemand = !it.onDemandSearchOnDemand) } }
                    )
                    SubHeading("Streams List")
                    SettingsToggleRow(
                        "On Demand in Streams",
                        "Your provider's copy offered with your addon streams for movies and episodes",
                        s.onDemandInStreams,
                        { update { it.copy(onDemandInStreams = !it.onDemandInStreams) } }
                    )
                    SettingsActionRow(
                        title = "On Demand Tab Position",
                        subtitle = "Where its tab goes in the stream list, left to right after All",
                        value = streamsPositionLabel(s.onDemandStreamsPosition),
                        onClick = { pickingStreamsPosition = true }
                    )
                    SettingsToggleRow(
                        "Let Auto-Play Pick On Demand",
                        "Only when its tab is first (and auto-play is set to use all sources)",
                        s.onDemandAutoPlay,
                        { update { it.copy(onDemandAutoPlay = !it.onDemandAutoPlay) } }
                    )
                    if (pickingStreamsPosition) SettingsSingleChoiceDialog(
                        title = "On Demand tab position",
                        options = listOf("FIRST", "AFTER_FIRST", "LAST").map { SettingsPickerOption(it, streamsPositionLabel(it)) },
                        selectedValue = s.onDemandStreamsPosition,
                        onOptionSelected = { v -> update { it.copy(onDemandStreamsPosition = v) }; pickingStreamsPosition = false },
                        onDismiss = { pickingStreamsPosition = false }
                    )
                }
            }

            // ------------------------------------------------------------ Parental Controls
            if (show(LiveTvPage.MAIN)) item(key = "parental") {
                LiveSection(
                    title = "Parental Controls",
                    summary = if (hasPin) "On · ${user.lockedGroups.size} locked" else "Off",
                    icon = Icons.Default.Lock,
                    open = "parental" in open,
                    onToggle = { toggle("parental") }
                ) {
                    Text(
                        text = "Locked groups and categories need the PIN to open, and stay out of All Channels and search until unlocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                    SettingsActionRow(
                        if (hasPin) "Change PIN" else "Set a PIN",
                        if (hasPin) "Parental controls are on" else "Turns parental controls on",
                        onClick = { pinWrong = false; dialog = if (hasPin) LiveDialog.PinCheck else LiveDialog.PinNew }
                    )
                    if (hasPin) {
                        SettingsToggleRow(
                            "Lock Adult Content Automatically",
                            "Locks groups and categories with names like Adult, XXX or 18+",
                            s.lockAdultContent,
                            { update { it.copy(lockAdultContent = !it.lockAdultContent) } }
                        )
                        SettingsActionRow(
                            "Turn Off Parental Controls", "Removes the PIN. Your locked groups are remembered.",
                            "${user.lockedGroups.size} locked",
                            onClick = { pinWrong = false; dialog = LiveDialog.PinRemove }
                        )
                    }
                }
            }

            // ------------------------------------------------------------ Reminders
            if (show(LiveTvPage.GUIDE)) item(key = "reminders") {
                LiveSection(
                    title = "Reminders",
                    summary = if (user.reminders.isEmpty()) "None set" else "${user.reminders.size} upcoming",
                    icon = Icons.Default.Notifications,
                    open = "reminders" in open,
                    onToggle = { toggle("reminders") }
                ) {
                    SettingsActionRow(
                        "Upcoming Reminders",
                        "Set from a show's info in the guide. You get a message a minute before it starts.",
                        "${user.reminders.size}",
                        onClick = { dialog = LiveDialog.Reminders }
                    )
                }
            }

            // ------------------------------------------------------------ Recordings
            if (show(LiveTvPage.PLAYBACK)) item(key = "recordings") {
                val recContext = androidx.compose.ui.platform.LocalContext.current
                // Drives come and go: read again whenever the section is opened.
                val drives = remember("recordings" in open) { com.nuvio.tv.livetv.record.RecordingStorage.choices(recContext) }
                val driveProblems = remember("recordings" in open) { com.nuvio.tv.livetv.record.RecordingStorage.problems(recContext) }
                val chosen = drives.firstOrNull { it.id == s.recordingStorage }
                fun driveLabel(c: com.nuvio.tv.livetv.record.RecordingStorage.Choice) =
                    c.label + " · " + com.nuvio.tv.livetv.record.RecordingStorage.sizeLabel(c.freeBytes) + " free"
                var pickingDrive by remember { mutableStateOf(false) }
                LiveSection(
                    title = "Recordings",
                    summary = "Saved to " + (chosen?.label ?: if (s.recordingStorage.isEmpty()) "This Device" else "a drive that isn't plugged in"),
                    icon = Icons.Default.FiberManualRecord,
                    open = "recordings" in open,
                    onToggle = { toggle("recordings") }
                ) {
                    SettingsToggleRow(
                        "Show Recordings in the Side Menu",
                        "Once anything is recorded or set to record",
                        s.recordingsInSidebar,
                        { update { it.copy(recordingsInSidebar = !it.recordingsInSidebar) } }
                    )
                    SettingsActionRow(
                        title = "Save Recordings To",
                        subtitle = if (chosen == null && s.recordingStorage.isNotEmpty())
                            "The chosen drive isn't plugged in: recordings go to this device until it's back"
                        else "Where new recordings go. Ones already recorded stay where they are.",
                        value = chosen?.label ?: "This Device",
                        onClick = { pickingDrive = true }
                    )
                    SettingsActionRow(
                        title = "Start Recording Early",
                        subtitle = "Starts this long before the show, in case it begins early. Shows already set keep their times.",
                        value = padLabel(s.recordPreMinutes),
                        onClick = { dialog = LiveDialog.RecordPre }
                    )
                    SettingsActionRow(
                        title = "Keep Recording After",
                        subtitle = "Keeps going this long after the show, in case it runs over. Shows already set keep their times.",
                        value = padLabel(s.recordPostMinutes),
                        onClick = { dialog = LiveDialog.RecordPost }
                    )
                    SettingsActionRow(
                        title = "When Storage Runs Low",
                        subtitle = if (s.recordingSpaceRule == "DELETE_OLDEST") "The oldest recordings on that drive are deleted to make room"
                        else "Recording stops (nothing is deleted)",
                        value = if (s.recordingSpaceRule == "DELETE_OLDEST") "Delete Oldest" else "Stop Recording",
                        onClick = {
                            if (s.recordingSpaceRule == "DELETE_OLDEST") update { it.copy(recordingSpaceRule = "STOP") }
                            else confirm(
                                "Delete Old Recordings?",
                                "When space runs low, the oldest recordings on that drive will be deleted to make room for new ones.",
                                "Turn On"
                            ) { update { it.copy(recordingSpaceRule = "DELETE_OLDEST") } }
                        }
                    )
                    if (pickingDrive) ActionListDialog(
                        title = "Save Recordings To",
                        actions = drives.map { c ->
                            (driveLabel(c) + if (c.id == (chosen?.id ?: "")) "  ✓" else "") to {
                                pickingDrive = false
                                update { it.copy(recordingStorage = c.id) }
                            }
                        } + driveProblems.map { p ->
                            // Plugged in but not usable: say why instead of leaving it out.
                            "${p.label} · Can't be used" to {
                                pickingDrive = false
                                confirm("${p.label} Can't Be Used", p.reason, "OK") { }
                            }
                        } + listOf(
                            "Drive Not Listed?" to {
                                pickingDrive = false
                                confirm(
                                    "Drive Not Listed?",
                                    "Android itself hasn't found the drive. Check that the box shows it in its own settings (Storage). " +
                                        "Large drives often need more power than a TV box's USB port gives: use a drive with its own " +
                                        "power supply or a powered USB hub. The drive should be formatted as exFAT (NTFS is read only " +
                                        "on many boxes, and FAT32 can't hold files over 4 GB). Don't set it up as \"device storage\": " +
                                        "use it as removable storage.",
                                    "OK"
                                ) { }
                            }
                        ),
                        onDismiss = { pickingDrive = false }
                    )
                }
            }

            // ------------------------------------------------------------ Help
            if (show(LiveTvPage.MAIN)) item(key = "help") {
                val crashContext = androidx.compose.ui.platform.LocalContext.current
                LiveSection(
                    title = "Help",
                    summary = "Crash report · Start-up report",
                    icon = Icons.AutoMirrored.Filled.Help,
                    open = "help" in open,
                    onToggle = { toggle("help") }
                ) {
                    SettingsActionRow(
                        "Crash Report",
                        "The last time the app crashed, froze or was closed. Scan the code with your phone to send it.",
                        onClick = { com.nuvio.tv.livetv.crash.CrashCatcher.openLastReport(crashContext) }
                    )
                    SettingsActionRow(
                        "Start-Up Report",
                        "What Live TV did while loading this time, how long it took, and anything that failed",
                        onClick = { dialog = LiveDialog.LoadReport }
                    )
                    // Hidden developer tools: only shown after typing the secret word into the
                    // channel name cleanup (see NameEditor below).
                    if (developerToolsOn) {
                        SubHeading("Developer Tools")
                        SettingsActionRow(
                            "Test Poster Lookup",
                            "What your addons return for a title",
                            onClick = { dialog = LiveDialog.PosterTest }
                        )
                        SettingsActionRow(
                            "Remote Button Tester",
                            "Shows every button the remote sends",
                            onClick = { dialog = LiveDialog.ButtonTester }
                        )
                    }
                }
            }
        }
        } // LocalLiveFlat
        } // key(page)
    }

    drive.signIn?.let { prompt ->
        DriveSignInDialog(prompt = prompt, onCancel = { viewModel.driveSync.cancelSignIn() })
    }

    // ---------------------------------------------------------------- dialogs
    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        LiveDialog.AddM3u -> SourceFormDialog(
            title = "Add M3U playlist",
            fields = listOf(
                FormField("Name", "My provider"),
                FormField("Playlist URL", "http://…/playlist.m3u"),
                FormField("User agent (optional)", "Leave empty for default")
            ),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addPlaylist(v[0], v[1], v[2]); close() }
        )
        LiveDialog.AddXtream -> SourceFormDialog(
            title = "Add Xtream Codes login",
            fields = listOf(
                FormField("Name", "My provider"),
                FormField("Server", "http://host:port"),
                FormField("Username", ""),
                FormField("Password", "")
            ),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3]); close() },
            toggleLive = "Include TV channels" to true,
            toggle2 = "Include movies & series (On Demand)" to true,
            onSaveAll = { v, _, vod, live ->
                if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3], importVod = vod, importLive = live)
                close()
            }
        )
        LiveDialog.AddEpg -> SourceFormDialog(
            title = "Add EPG source",
            fields = listOf(FormField("Name", "Guide"), FormField("XMLTV URL", "http://…/epg.xml.gz")),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addEpg(v[0], v[1]); close() }
        )
        is LiveDialog.PlaylistActions -> ActionListDialog(
            title = d.source.name,
            actions = listOfNotNull(
                // The account, as the provider last reported it (nothing to do: shows the details).
                accounts[d.source.id]?.let { a ->
                    val parts = listOfNotNull(
                        a.status.takeIf { it.isNotBlank() },
                        a.expiryLabel()?.let { "Expires $it" } ?: "No expiry date",
                        a.maxConnections.takeIf { it > 0 }?.let { "${a.activeConnections} of $it connections in use" }
                    )
                    ("Account: " + parts.joinToString(" · ")) to { viewModel.updatePlaylistNow(d.source); close() }
                },
                "Edit" to { dialog = LiveDialog.EditPlaylist(d.source) },
                "Update now" to { viewModel.updatePlaylistNow(d.source); close() },
                (if (d.source.enabled) "Disable" else "Enable") to { viewModel.setPlaylistEnabled(d.source.id, !d.source.enabled); close() },
                "Delete playlist" to { dialog = LiveDialog.ConfirmDeletePlaylist(d.source) },
                // Whose channel logos to show: the playlist's own or the guide's (each falls back
                // to the other when it has none).
                (if (d.source.logoSource == "epg") "Logos: Prefer logos from EPG" else "Logos: Prefer logos from playlist") to {
                    viewModel.setPlaylistLogoSource(d.source.id, if (d.source.logoSource == "epg") "playlist" else "epg")
                    close()
                },
                // Xtream: which stream format to ask for. Auto follows what the account allows.
                *(if (d.source.isXtream) arrayOf<Pair<String, () -> Unit>>(
                    "Stream format: " + when (d.source.streamFormat) { "ts" -> "TS"; "m3u8" -> "HLS (m3u8)"; else -> "Auto" } to {
                        val next = when (d.source.streamFormat) { "auto" -> "ts"; "ts" -> "m3u8"; else -> "auto" }
                        viewModel.savePlaylist(d.source.copy(streamFormat = next))
                        close()
                    }
                ) else emptyArray()),
                "Move up" to { viewModel.movePlaylist(d.source.id, -1); close() },
                "Move down" to { viewModel.movePlaylist(d.source.id, 1); close() }
            ),
            onDismiss = close
        )
        is LiveDialog.EditPlaylist -> {
            val src = d.source
            if (src.isXtream) {
                SourceFormDialog(
                    title = "Edit Xtream login",
                    fields = listOf(
                        FormField("Name", "", src.name),
                        FormField("Server", "", src.xtreamServer),
                        FormField("Username", "", src.xtreamUsername),
                        FormField("Password", "", src.xtreamPassword),
                        FormField("User agent (optional)", "", src.userAgent)
                    ),
                    toggle = "Load built-in guide" to src.useEmbeddedEpg,
                    toggleLive = "Include TV channels" to src.importLive,
                    toggle2 = "Include movies & series (On Demand)" to src.importVod,
                    onDismiss = close,
                    onSave = { v, t ->
                        viewModel.savePlaylist(src.copy(name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3], userAgent = v[4], useEmbeddedEpg = t))
                        close()
                    },
                    onSaveAll = { v, t, vod, live ->
                        viewModel.savePlaylist(
                            src.copy(
                                name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3],
                                userAgent = v[4], useEmbeddedEpg = t, importVod = vod, importLive = live
                            )
                        )
                        close()
                    }
                )
            } else {
                SourceFormDialog(
                    title = "Edit playlist",
                    fields = listOf(
                        FormField("Name", "", src.name),
                        FormField("Playlist URL", "", src.url),
                        FormField("User agent (optional)", "", src.userAgent)
                    ),
                    toggle = "Load guide linked in playlist" to src.useEmbeddedEpg,
                    onDismiss = close,
                    onSave = { v, t ->
                        viewModel.savePlaylist(src.copy(name = v[0], url = v[1], userAgent = v[2], useEmbeddedEpg = t))
                        close()
                    }
                )
            }
        }
        is LiveDialog.EpgActions -> ActionListDialog(
            title = d.source.name,
            actions = listOf(
                "Edit" to { dialog = LiveDialog.EditEpg(d.source) },
                "Update now" to { viewModel.saveEpg(d.source); close() },
                (if (d.source.enabled) "Disable" else "Enable") to { viewModel.setEpgEnabled(d.source.id, !d.source.enabled); close() },
                "Delete EPG source" to { dialog = LiveDialog.ConfirmDeleteEpg(d.source) }
            ),
            onDismiss = close
        )
        LiveDialog.PinCheck -> TextInputDialog(
            title = if (pinWrong) "Wrong PIN, try again" else "Enter your current PIN",
            initial = "", hint = "Current PIN", confirmLabel = "Next", numeric = true,
            onDismiss = close,
            onConfirm = { if (it.trim() == s.parentalPin) { pinWrong = false; dialog = LiveDialog.PinNew } else pinWrong = true }
        )
        LiveDialog.PinNew -> TextInputDialog(
            title = if (pinWrong) "The PIN needs 4 to 8 digits" else "Choose a PIN (4 to 8 digits)",
            initial = "", hint = "New PIN", confirmLabel = "Save", numeric = true,
            onDismiss = close,
            onConfirm = { pin ->
                val p = pin.trim()
                if (p.length in 4..8 && p.all { it.isDigit() }) { update { it.copy(parentalPin = p) }; close() } else pinWrong = true
            }
        )
        LiveDialog.PinRemove -> TextInputDialog(
            title = if (pinWrong) "Wrong PIN, try again" else "Enter your PIN to turn parental controls off",
            initial = "", hint = "PIN", confirmLabel = "Turn off", numeric = true,
            onDismiss = close,
            onConfirm = { if (it.trim() == s.parentalPin) { update { st -> st.copy(parentalPin = "") }; close() } else pinWrong = true }
        )
        LiveDialog.HomeRowSource -> SettingsSingleChoiceDialog(
            title = "Channels in the row",
            options = buildList {
                add(SettingsPickerOption("favorites", "Favorites"))
                add(SettingsPickerOption("recent", "Recently watched"))
                user.customGroups.forEach { add(SettingsPickerOption(it.id, it.name)) }
            },
            selectedValue = s.homeRowSource.ifBlank { "favorites" },
            onOptionSelected = { v -> update { it.copy(homeRowSource = v) }; close() },
            onDismiss = close
        )
        LiveDialog.HomeRowTitle -> TextInputDialog(
            title = "Home row title",
            initial = s.homeRowTitle,
            hint = "Live TV",
            confirmLabel = "Save",
            onDismiss = close,
            onConfirm = { t -> update { it.copy(homeRowTitle = t.trim()) }; close() }
        )
        LiveDialog.ButtonTester -> com.nuvio.tv.livetv.ui.RemoteButtonTester(onClose = close)
        LiveDialog.LoadReport -> com.nuvio.tv.livetv.ui.LoadReportDialog(onDismiss = close)
        LiveDialog.PosterTest -> {
            val result by viewModel.posterTest.collectAsStateWithLifecycle()
            if (result == null) {
                TextInputDialog(
                    title = "Test poster lookup",
                    initial = "",
                    hint = "e.g. Family Guy",
                    confirmLabel = "Test",
                    onDismiss = close,
                    onConfirm = { viewModel.testPoster(it) }
                )
            } else {
                NuvioDialog(onDismiss = { viewModel.posterTest.value = null; close() }, title = "Poster lookup", width = 720.dp) {
                    Text(
                        text = result.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
                    )
                    SettingsDialogActionRow {
                        SettingsDialogActionButton(text = "Close", onClick = { viewModel.posterTest.value = null; close() }, primary = true)
                    }
                }
            }
        }
        LiveDialog.NameEditor -> TextInputDialog(
            title = "Prefixes and suffixes to remove",
            initial = s.nameRemovals,
            hint = "e.g. USA, US, UK, 24/7, FHD, HD",
            confirmLabel = "Save",
            onDismiss = close,
            onConfirm = { text ->
                // Secret word: turns the hidden developer tools on or off instead of saving.
                if (text.trim().equals(DEVELOPER_WORD, ignoreCase = true)) {
                    com.nuvio.tv.livetv.model.DeveloperTools.toggle() // until the app next starts
                } else {
                    update { it.copy(nameRemovals = text.trim()) }
                }
                close()
            }
        )
        LiveDialog.StartPage -> SettingsSingleChoiceDialog(
            title = "Start page",
            // On Demand is only offered once something has been imported.
            options = buildList {
                add(SettingsPickerOption("HOME", "Home"))
                add(SettingsPickerOption("LIVE_TV", "Live TV"))
                if (onDemandHas) add(SettingsPickerOption("ON_DEMAND", "On Demand"))
                add(SettingsPickerOption("DISCOVER", "Discover"))
                add(SettingsPickerOption("SEARCH", "Search"))
                add(SettingsPickerOption("LIBRARY", "Library"))
            },
            selectedValue = s.effectiveStartPage,
            onOptionSelected = { v -> update { it.copy(startPage = v, startOnLiveTv = v == "LIVE_TV") }; close() },
            onDismiss = close
        )
        LiveDialog.Reminders -> ActionListDialog(
            title = if (user.reminders.isEmpty()) "No reminders" else "Reminders (select to remove)",
            actions = user.reminders.map { r ->
                val whenText = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(r.startMs))
                "$whenText · ${r.title} · ${r.channelName}" to { viewModel.removeReminder(r.channelKey, r.startMs); Unit }
            }.ifEmpty { listOf("Close" to close) },
            onDismiss = close
        )
        is LiveDialog.Confirm -> ConfirmDeleteDialog(
            title = d.title,
            message = d.message,
            onDismiss = close,
            onConfirm = { d.action(); close() },
            confirmLabel = d.label
        )
        LiveDialog.ConfirmRestore -> {
            // When the saved copy was made, and on which TV: restoring an old copy puts back old
            // playlists and logins (a provider's old address, an old password).
            var loaded by remember { mutableStateOf(false) }
            var info by remember { mutableStateOf<com.nuvio.tv.livetv.sync.LiveTvDriveSync.BackupInfo?>(null) }
            LaunchedEffect(Unit) {
                info = runCatching { viewModel.driveSync.backupInfo() }.getOrNull()
                loaded = true
            }
            ConfirmDeleteDialog(
                title = "Restore from Google Drive?",
                message = restoreMessage(loaded, info, viewModel.driveSync.localChangedAt()),
                onDismiss = close,
                onConfirm = { viewModel.driveSync.restoreNow(); close() },
                confirmLabel = "Restore"
            )
        }
        is LiveDialog.ConfirmDeletePlaylist -> ConfirmDeleteDialog(
            title = "Delete \"${d.source.name}\"?",
            message = "Its ${d.source.channelCount} channels leave the guide. Favorites, hidden channels and EPG assignments for them are kept in case you add it back.",
            onDismiss = close,
            onConfirm = { viewModel.removePlaylist(d.source.id); close() }
        )
        is LiveDialog.ConfirmDeleteEpg -> ConfirmDeleteDialog(
            title = "Delete \"${d.source.name}\"?",
            message = "Channels using this guide go back to other guides or automatic matching.",
            onDismiss = close,
            onConfirm = { viewModel.removeEpg(d.source.id); close() }
        )
        is LiveDialog.EditEpg -> SourceFormDialog(
            title = "Edit EPG source",
            fields = listOf(FormField("Name", "", d.source.name), FormField("XMLTV URL", "", d.source.url)),
            onDismiss = close,
            onSave = { v, _ -> viewModel.saveEpg(d.source.copy(name = v[0], url = v[1])); close() }
        )
        LiveDialog.HiddenChannels -> UnhideDialog(
            title = "Hidden channels",
            entries = user.hiddenChannels.sorted().map { it to viewModel.channelLabel(it) },
            onUnhide = { viewModel.unhideChannel(it) },
            onUnhideAll = { viewModel.unhideAllChannels(); close() },
            onDismiss = close
        )
        LiveDialog.HiddenGroups -> UnhideDialog(
            title = "Hidden groups",
            entries = user.hiddenGroups.sorted().map { it to viewModel.groupLabel(it) },
            onUnhide = { viewModel.unhideGroup(it) },
            onUnhideAll = { viewModel.unhideAllGroups(); close() },
            onDismiss = close
        )
        LiveDialog.EpgShift -> SettingsSingleChoiceDialog(
            title = "EPG time shift",
            options = EPG_OFFSETS.map { SettingsPickerOption(it, offsetLabel(it)) },
            selectedValue = s.epgOffsetMinutes,
            onOptionSelected = { v -> update { it.copy(epgOffsetMinutes = v) }; close() },
            onDismiss = close
        )
        LiveDialog.RecordPre -> SettingsSingleChoiceDialog(
            title = "Start recording early",
            options = listOf(0, 1, 2, 5, 10, 15).map { SettingsPickerOption(it, padLabel(it)) },
            selectedValue = s.recordPreMinutes,
            onOptionSelected = { v -> update { it.copy(recordPreMinutes = v) }; close() },
            onDismiss = close
        )
        LiveDialog.RecordPost -> SettingsSingleChoiceDialog(
            title = "Keep recording after",
            options = listOf(0, 2, 5, 10, 15, 30).map { SettingsPickerOption(it, padLabel(it)) },
            selectedValue = s.recordPostMinutes,
            onOptionSelected = { v -> update { it.copy(recordPostMinutes = v) }; close() },
            onDismiss = close
        )
        LiveDialog.FrameRate -> SettingsSingleChoiceDialog(
            title = "Match Frame Rate",
            options = FRAME_RATE_MODES.map { (v, t, d) -> SettingsPickerOption(v, t, description = d) },
            selectedValue = s.frameRateMode,
            onOptionSelected = { v -> update { it.copy(frameRateMode = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockMode -> SettingsSingleChoiceDialog(
            title = "Show clock",
            options = CLOCK_MODES.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.clockMode,
            onOptionSelected = { v -> update { it.copy(clockMode = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockPosition -> SettingsSingleChoiceDialog(
            title = "Clock position",
            options = CLOCK_POSITIONS.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.clockPosition,
            onOptionSelected = { v -> update { it.copy(clockPosition = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockSize -> SettingsSingleChoiceDialog(
            title = "Clock size",
            options = CLOCK_SIZES.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.clockSize,
            onOptionSelected = { v -> update { it.copy(clockSize = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockOpacity -> SettingsSingleChoiceDialog(
            title = "Clock opacity",
            options = listOf(30, 50, 70, 100).map { SettingsPickerOption(it, "$it%") },
            selectedValue = s.clockOpacity,
            onOptionSelected = { v -> update { it.copy(clockOpacity = v) }; close() },
            onDismiss = close
        )
        LiveDialog.CatchupShift -> SettingsSingleChoiceDialog(
            title = "Catch-up time shift",
            options = EPG_OFFSETS.map { SettingsPickerOption(it, offsetLabel(it)) },
            selectedValue = s.catchupShiftMinutes,
            onOptionSelected = { v -> update { it.copy(catchupShiftMinutes = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ZapModeChoice -> SettingsSingleChoiceDialog(
            title = "Channel up / down switches within",
            options = listOf(
                SettingsPickerOption(ZapMode.GROUP, "Current group"),
                SettingsPickerOption(ZapMode.ALL, "All channels")
            ),
            selectedValue = s.zapMode,
            onOptionSelected = { v -> update { it.copy(zapMode = v) }; close() },
            onDismiss = close
        )
        LiveDialog.BannerTime -> SettingsSingleChoiceDialog(
            title = "Info banner duration",
            options = BANNER_SECONDS.map { SettingsPickerOption(it, "$it seconds") },
            selectedValue = s.infoBannerSeconds,
            onOptionSelected = { v -> update { it.copy(infoBannerSeconds = v) }; close() },
            onDismiss = close
        )
        LiveDialog.PlaylistRefresh -> SettingsSingleChoiceDialog(
            title = "Update playlists every",
            options = REFRESH_HOURS.map { SettingsPickerOption(it, hoursLabel(it)) },
            selectedValue = s.playlistRefreshHours,
            onOptionSelected = { v -> update { it.copy(playlistRefreshHours = v) }; close() },
            onDismiss = close
        )
        LiveDialog.EpgRefresh -> SettingsSingleChoiceDialog(
            title = "Update guides every",
            options = REFRESH_HOURS.map { SettingsPickerOption(it, hoursLabel(it)) },
            selectedValue = s.epgRefreshHours,
            onOptionSelected = { v -> update { it.copy(epgRefreshHours = v) }; close() },
            onDismiss = close
        )
        LiveDialog.FutureDays -> SettingsSingleChoiceDialog(
            title = "Guide days ahead",
            options = FUTURE_DAYS.map { SettingsPickerOption(it, if (it == 1) "1 day" else "$it days") },
            selectedValue = s.epgFutureDays,
            onOptionSelected = { v -> viewModel.updateAndReloadEpg { it.copy(epgFutureDays = v) }; close() },
            onDismiss = close
        )
        LiveDialog.SortChoice -> SettingsSingleChoiceDialog(
            title = "Channel order",
            options = listOf(
                SettingsPickerOption(ChannelSort.PLAYLIST, "Playlist order"),
                SettingsPickerOption(ChannelSort.NUMBER, "Channel number"),
                SettingsPickerOption(ChannelSort.NAME, "Name (A–Z)")
            ),
            selectedValue = s.channelSort,
            onOptionSelected = { v -> update { it.copy(channelSort = v) }; close() },
            onDismiss = close
        )
        LiveDialog.HighlightChoice -> SettingsSingleChoiceDialog(
            title = "Highlight style",
            options = listOf(
                SettingsPickerOption(false, "Outline", "Dark tint with a colored border — logos stay visible"),
                SettingsPickerOption(true, "Solid", "Fill with the theme's accent color")
            ),
            selectedValue = s.solidHighlight,
            onOptionSelected = { v -> update { it.copy(solidHighlight = v) }; close() },
            onDismiss = close
        )
        LiveDialog.CustomGroups -> UnhideDialog(
            title = "My groups",
            entries = user.customGroups.map { it.id to "${it.name} (${it.channelKeys.size})" },
            onUnhide = { viewModel.deleteCustomGroup(it) },
            onUnhideAll = { user.customGroups.forEach { g -> viewModel.deleteCustomGroup(g.id) }; close() },
            onDismiss = close,
            itemAction = "Delete",
            allLabel = "Delete all",
            emptyText = "You haven't made any groups yet. Long-press a channel in the guide and choose \"Add to group\".",
            hintText = "Select a group to delete it. The channels stay in your playlist."
        )
        LiveDialog.PastHours -> SettingsSingleChoiceDialog(
            title = "Past guide kept",
            options = PAST_HOURS.map { SettingsPickerOption(it, hoursLabel(it)) },
            selectedValue = s.epgPastHours,
            onOptionSelected = { v -> viewModel.updateAndReloadEpg { it.copy(epgPastHours = v) }; close() },
            onDismiss = close
        )
    }
}

// ==================================================================== loading line

/** A spinner and what's happening, while playlists, guides or On Demand are downloading. */
@Composable
private fun LoadingLine(message: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.nuvio.tv.livetv.ui.LoadingSpinner()
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
    }
}

// ==================================================================== Google Drive sync

/** Collapsed to one line until selected; then shows sign-in, backup and restore. */
@Composable
private fun DriveSyncCard(
    modifier: Modifier = Modifier,
    state: com.nuvio.tv.livetv.sync.DriveSyncState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onConnect: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    onDisconnect: () -> Unit
) {
    val status = when {
        !state.available -> "Not available in this build"
        !state.connected -> "Off"
        state.busy -> "Syncing…"
        state.lastSyncMs > 0 -> "On · last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastSyncMs))
        else -> "On"
    }
    LiveSection(
        title = "Backup & Sync",
        summary = "Google Drive · " + status,
        icon = Icons.Default.Backup,
        open = expanded,
        onToggle = onToggleExpanded,
        modifier = modifier
    ) {
        run {
            when {
                !state.available -> Text(
                    text = "Google Drive sync needs to be set up by whoever builds this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                !state.connected -> {
                    Text(
                        text = "Your playlists, guide settings, Sports Hub preferences and per-profile TVDB order choices are saved privately in your Google Drive. Backups include playlist logins, so keep the backup private. Personal API keys and tracker sign-ins are not included.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    SettingsActionRow("Connect Google Drive", "Keeps your Live TV setup the same on every TV, using your own Google Drive. Sign in with a code on your phone.", onClick = onConnect)
                }
                else -> {
                    SettingsActionRow("Account", "The Google account your Live TV setup is saved to", state.email ?: "Connected", onClick = {})
                    SettingsToggleRow(
                        "Sync Automatically",
                        "Back up changes, and pick up changes made on your other TVs",
                        state.autoSync,
                        { onAutoSync(!state.autoSync) }
                    )
                    SettingsActionRow("Back Up Now", "Save this TV's setup to Google Drive", onClick = onBackUp)
                    SettingsActionRow("Restore from Google Drive", "Replace this TV's setup with the saved copy", onClick = onRestore)
                    SettingsActionRow("Disconnect", "Stop syncing on this TV (your backup stays in Drive)", onClick = onDisconnect)
                }
            }
            state.message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DriveSignInDialog(prompt: com.nuvio.tv.livetv.sync.SignInPrompt, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }
    // Same layout as Nuvio's other QR sign-ins (debrid, Simkl): instructions, QR code, then the code.
    val qrBitmap = remember(prompt.qrUrl) {
        runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(prompt.qrUrl, 420, margin = 1) }.getOrNull()
    }
    val shortUrl = prompt.url.removePrefix("https://").removePrefix("http://")
    NuvioDialog(onDismiss = onCancel, title = "Connect Google Drive", width = 560.dp) {
        Text(
            text = "Scan the QR code with your phone, or go to $shortUrl, then enter the code below and allow access.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (qrBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(196.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
        Text(
            text = prompt.userCode,
            style = MaterialTheme.typography.displaySmall,
            color = NuvioTheme.colors.Secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = shortUrl,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Waiting for you to allow access… this screen continues by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = "Cancel", onClick = onCancel)
            }
        }
    }
}

// ==================================================================== dialogs

private data class FormField(val label: String, val hint: String, val initial: String = "")

@Composable
private fun SourceFormDialog(
    title: String,
    fields: List<FormField>,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean) -> Unit,
    toggle: Pair<String, Boolean>? = null,
    /** A second switch (Xtream: import movies & series); its value goes to [onSaveBoth]. */
    toggle2: Pair<String, Boolean>? = null,
    onSaveBoth: ((List<String>, Boolean, Boolean) -> Unit)? = null,
    /** Xtream: "Include TV channels", shown first. Its value goes to [onSaveAll]. */
    toggleLive: Pair<String, Boolean>? = null,
    onSaveAll: ((values: List<String>, guide: Boolean, vod: Boolean, live: Boolean) -> Unit)? = null
) {
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var toggleValue by remember { mutableStateOf(toggle?.second ?: true) }
    var toggle2Value by remember { mutableStateOf(toggle2?.second ?: true) }
    var toggleLiveValue by remember { mutableStateOf(toggleLive?.second ?: true) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstField.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 620.dp) {
        // The fields scroll inside the dialog, so Save and Cancel always stay on screen
        // (with a user agent field and the guide switch, they used to fall off the bottom).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
        fields.forEachIndexed { i, field ->
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                NuvioTextField(
                    value = values[i].value,
                    onValueChange = { values[i].value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = field.hint,
                    focusRequester = if (i == 0) firstField else null
                )
            }
        }
        if (toggleLive != null) {
            SettingsToggleRow(
                title = toggleLive.first,
                subtitle = "Adds the provider's live channels to the TV guide",
                checked = toggleLiveValue,
                onToggle = { toggleLiveValue = !toggleLiveValue }
            )
        }
        // The guide switch only matters when TV channels are included.
        if (toggle != null && (toggleLive == null || toggleLiveValue)) {
            SettingsToggleRow(
                title = toggle.first,
                subtitle = null,
                checked = toggleValue,
                onToggle = { toggleValue = !toggleValue }
            )
        }
        if (toggle2 != null) {
            SettingsToggleRow(
                title = toggle2.first,
                subtitle = "Adds the provider's movies and series to On Demand and to Nuvio's stream list",
                checked = toggle2Value,
                onToggle = { toggle2Value = !toggle2Value }
            )
        }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
            SettingsDialogActionButton(
                text = "Save",
                onClick = {
                    val v = values.map { it.value.trim() }
                    when {
                        onSaveAll != null -> onSaveAll(v, toggleValue, toggle2Value, toggleLiveValue)
                        onSaveBoth != null -> onSaveBoth(v, toggleValue, toggle2Value)
                        else -> onSave(v, toggleValue)
                    }
                },
                primary = true
            )
        }
    }
}

@Composable
private fun ActionListDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 460.dp) {
        // Scrollable: on a 1080p TV not every option fits, and the last ones were unreachable.
        LazyColumn(
            modifier = Modifier.heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            itemsIndexed(actions) { i, (label, action) ->
                SettingsActionRow(
                    title = label,
                    subtitle = null,
                    onClick = action,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "Delete"
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = message, width = 520.dp) {
        SettingsDialogActionRow {
            // Cancel is focused first, so a stray OK press can't delete anything.
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
            }
            SettingsDialogActionButton(text = confirmLabel, onClick = onConfirm, primary = true)
        }
    }
}

/** The restore prompt's text: what's replaced, when the saved copy was made and where, and a warning when it's old. */
private fun restoreMessage(loaded: Boolean, info: com.nuvio.tv.livetv.sync.LiveTvDriveSync.BackupInfo?, localChangedAt: Long): String {
    val base = "This TV's Live TV setup, Sports Hub preferences and saved TVDB episode orders are replaced with the copy in Google Drive. Profiles must match; personal API keys and tracker sign-ins are kept on this TV."
    if (!loaded) return "$base\n\nChecking when it was saved…"
    if (info == null || info.savedAtMs <= 0L) return base
    val now = System.currentTimeMillis()
    val days = ((now - info.savedAtMs) / (24 * 60 * 60_000L)).toInt()
    val ago = when {
        now - info.savedAtMs < 60 * 60_000L -> "less than an hour ago"
        days < 1 -> "today"
        days == 1 -> "yesterday"
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        else -> "${days / 30} months ago"
    }
    val stamp = java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(info.savedAtMs))
    val where = if (info.fromThisTv) "this TV" else info.device
    val saved = "Saved $stamp on $where ($ago)."
    val warning = when {
        localChangedAt > info.savedAtMs + 60_000L ->
            "This TV has changes made after that copy was saved. Restoring puts back the older playlists and logins. If this TV has the latest, press Back Up Now here instead."
        days >= 7 ->
            "That copy is $ago. If your playlists or logins have changed since (a new server address or password), press Back Up Now on a TV that has the latest first."
        else -> null
    }
    return listOfNotNull(base, saved, warning).joinToString("\n\n")
}

@Composable
private fun UnhideDialog(
    title: String,
    entries: List<Pair<String, String>>,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit,
    itemAction: String = "Show",
    allLabel: String = "Show all",
    emptyText: String = "Nothing is hidden.",
    hintText: String = "Select an item to show it again."
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = if (entries.isEmpty()) emptyText else hintText,
        width = 560.dp
    ) {
        if (entries.isEmpty()) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = "Close", onClick = onDismiss, primary = true)
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                item(key = "__all__") {
                    SettingsActionRow(
                        title = allLabel,
                        subtitle = null,
                        value = "${entries.size}",
                        onClick = onUnhideAll,
                        modifier = Modifier.focusRequester(first)
                    )
                }
                items(entries, key = { it.first }) { (key, label) ->
                    SettingsActionRow(title = label, subtitle = null, value = itemAction, onClick = { onUnhide(key) })
                }
            }
        }
    }
}

/**
 * One folding section of Live TV settings, in Nuvio's own settings style: a header row with the
 * section's icon, name and a one-line summary; OK opens or closes it.
 */
@Composable
private fun LiveSection(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Flat tabs: the first section takes the focus coming in (its first row). */
    focusFirst: FocusRequester? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    if (LocalLiveFlat.current) {
        // A sub-tab: sections sit open under a plain heading (nothing to fold, only a few per tab).
        Column(
            if (focusFirst != null) Modifier.focusRequester(focusFirst).focusGroup() else Modifier
        ) {
            SettingsGroupCard {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, end = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xxs),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    androidx.tv.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
                        Text(text = summary, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                    }
                }
                content()
            }
        }
        return
    }
    SettingsGroupCard {
        SettingsActionRow(
            title = title,
            subtitle = summary,
            leadingIcon = icon,
            trailingIcon = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            onClick = onToggle,
            modifier = modifier
        )
        if (open) content()
    }
}

/** A small label inside an open section ("Top of the Guide"), like Nuvio's group titles. */
@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
    )
}

private fun streamsPositionLabel(v: String): String = when (v) {
    "FIRST" -> "First"
    "AFTER_FIRST" -> "After the first addon"
    else -> "Last"
}

private fun searchSectionLabel(kind: String): String = when (kind) {
    "live" -> "Live TV channels"
    "ondemand" -> "On Demand (your provider)"
    else -> "Nuvio catalogs (addons)"
}

/**
 * The search sections in order. OK picks one up (it's marked), Up and Down move it, OK again
 * puts it down: the same way Reorder groups works in the guide.
 */
@Composable
private fun SearchOrderList(order: List<String>, onChange: (List<String>) -> Unit) {
    var moving by remember { mutableStateOf<String?>(null) }
    val requesters = remember { order.associateWith { FocusRequester() } }
    order.forEachIndexed { i, kind -> androidx.compose.runtime.key(kind) {
        val isMoving = moving == kind
        SettingsActionRow(
            title = "${i + 1}   ${searchSectionLabel(kind)}",
            subtitle = if (isMoving) "Up / Down to move it, OK to put it here" else null,
            value = if (isMoving) "Moving" else null,
            onClick = { moving = if (isMoving) null else kind },
            modifier = Modifier
                .focusRequester(requesters.getValue(kind))
                .onPreviewKeyEvent { e ->
                    if (!isMoving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    val to = i + step
                    if (to in order.indices) {
                        onChange(order.toMutableList().apply { add(to, removeAt(i)) })
                    }
                    true // stays on the row being moved
                }
        )
    } }
    // The moved row keeps the highlight in its new place.
    LaunchedEffect(order, moving) {
        moving?.let { runCatching { requesters.getValue(it).requestFocus() } }
    }
}
