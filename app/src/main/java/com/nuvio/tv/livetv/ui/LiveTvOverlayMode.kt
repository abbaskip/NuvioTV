package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import com.nuvio.tv.livetv.model.effectiveGuideSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Where you are in overlay mode (TiviMate's name for it). */
private enum class OverlayLevel { CHANNELS, GROUPS, SCHEDULE, DATES }

/** A row of a channel's schedule: a day heading, or a program. */
private sealed interface ScheduleRow {
    data class Day(val label: String, val dayStart: Long) : ScheduleRow
    data class Show(val program: EpgProgram, val dayStart: Long) : ScheduleRow
}

/**
 * Overlay mode: a see-through guide over the playing video.
 *  - Channels (Left from full screen): the group's channels, with the highlighted channel's
 *    schedule beside them and the current show's details top right.
 *  - Groups (Left again): the group list; the channels move right, the schedule hides.
 *  - Schedule (Right from channels): that channel's shows, earlier and later, with a column
 *    of dates; Right again moves into the dates to jump to another day.
 */
@Composable
internal fun LiveTvOverlayMode(
    viewModel: LiveTvViewModel,
    onClose: () -> Unit,
    onFindInNuvio: (String) -> Unit
) {
    // Guide Size (Settings → Live TV) sets the size of overlay mode too: text, icons and rows.
    val sizeSettings by viewModel.settings.collectAsStateWithLifecycle()
    val (textScale, rowScale) = when (sizeSettings.effectiveGuideSize) {
        "COMPACT" -> 0.93f to 0.85f
        "LARGE" -> 1.2f to 1.2f
        "XLARGE" -> 1.4f to 1.4f
        else -> 1f to 1f
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalOverlayScale provides textScale,
        LocalOverlayRowScale provides rowScale
    ) {
        OverlayModeContent(viewModel, onClose, onFindInNuvio)
    }
}

/** Text and icon size scale for overlay mode, from Guide Size; 1 = Standard. */
private val LocalOverlayScale = androidx.compose.runtime.compositionLocalOf { 1f }
/** Row height scale for overlay mode, from Guide Size; 1 = Standard. */
private val LocalOverlayRowScale = androidx.compose.runtime.compositionLocalOf { 1f }

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun osp(v: Float): androidx.compose.ui.unit.TextUnit = (v * LocalOverlayScale.current).sp

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun orow(v: Float): androidx.compose.ui.unit.Dp = (v * LocalOverlayRowScale.current).dp

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun odp(v: Float): androidx.compose.ui.unit.Dp = (v * LocalOverlayScale.current.coerceAtLeast(1f)).dp

@Composable
private fun OverlayModeContent(
    viewModel: LiveTvViewModel,
    onClose: () -> Unit,
    onFindInNuvio: (String) -> Unit
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val playlistNames by viewModel.playlistNames.collectAsStateWithLifecycle()
    val collapsed by viewModel.collapsedPlaylists.collectAsStateWithLifecycle()
    val use24h = settings.use24HourClock

    val channels = ui.channels
    val groups = remember(ui.groups) { ui.groups.filter { it.id != ChannelGroup.SEARCH } }

    var level by remember { mutableStateOf(OverlayLevel.CHANNELS) }
    // Widths of the whole overlay and of its panels, for sizing the show card beside them.
    var overlayWidthPx by remember { mutableStateOf(0) }
    var panelsWidthPx by remember { mutableStateOf(0) }
    var chIndex by remember { mutableIntStateOf(channels.indexOfFirst { it.key == playback.channelKey }.coerceAtLeast(0)) }
    var groupIndex by remember { mutableIntStateOf(groups.indexOfFirst { it.id == ui.selectedGroupId }.coerceAtLeast(0)) }
    // Group by Playlist (more than one playlist): playlist headings in the group list, which
    // OK folds and unfolds like in the guide. [headingAt] is the heading highlighted, if any.
    val headed = settings.groupPlaylistHeadings && groups.mapNotNull { it.sourceId }.distinct().size > 1
    val groupRows = remember(groups, headed, collapsed) { overlayGroupRows(groups, headed, collapsed) }
    var headingAt by remember { mutableStateOf<String?>(null) }
    var scheduleChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var rowIndex by remember { mutableIntStateOf(0) }
    var dateIndex by remember { mutableIntStateOf(0) }
    var activity by remember { mutableIntStateOf(0) }
    var futurePrompt by remember { mutableStateOf<Pair<LiveChannel, EpgProgram>?>(null) }
    val focus = remember { FocusRequester() }

    // After changing group, keep the cursor on the playing channel if it's in the new list.
    LaunchedEffect(ui.selectedGroupId, channels.size) {
        val playing = channels.indexOfFirst { it.key == playback.channelKey }
        chIndex = if (playing >= 0) playing else chIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    // Closes itself after a while without a key press.
    LaunchedEffect(activity) {
        delay(15_000)
        if (futurePrompt == null) onClose()
    }

    val focusedChannel = channels.getOrNull(chIndex)
    // The schedule preview and show details follow the highlight once it settles, not on
    // every step while scrolling (that redrew both panels many times a second).
    var settledChannel by remember { mutableStateOf(focusedChannel) }
    LaunchedEffect(focusedChannel?.key) {
        if (settledChannel != null) delay(200)
        settledChannel = focusedChannel
    }

    // ---- schedule of one channel, grouped by day
    val schedule: List<ScheduleRow> = remember(scheduleChannel, programs) {
        val ch = scheduleChannel ?: return@remember emptyList()
        val out = ArrayList<ScheduleRow>()
        var lastDay = Long.MIN_VALUE
        programs[ch.key].orEmpty().forEach { p ->
            val day = startOfDay(p.startMs)
            if (day != lastDay) {
                out += ScheduleRow.Day(dayLabel(day, now), day)
                lastDay = day
            }
            out += ScheduleRow.Show(p, day)
        }
        out
    }
    val days: List<Long> = remember(schedule) { schedule.filterIsInstance<ScheduleRow.Day>().map { it.dayStart } }

    fun openSchedule(ch: LiveChannel) {
        scheduleChannel = ch
        level = OverlayLevel.SCHEDULE
    }
    // Land on what's on now when a schedule opens.
    // The full schedule (all days) for the channel being browsed, from the guide database.
    LaunchedEffect(scheduleChannel?.key) { scheduleChannel?.let { viewModel.ensureChannelSchedule(it.key) } }
    LaunchedEffect(scheduleChannel, schedule.size) {
        if (schedule.isEmpty()) return@LaunchedEffect
        val live = schedule.indexOfFirst { it is ScheduleRow.Show && now >= it.program.startMs && now < it.program.stopMs }
        val next = schedule.indexOfFirst { it is ScheduleRow.Show && it.program.startMs >= now }
        rowIndex = when {
            live >= 0 -> live
            next >= 0 -> next
            else -> schedule.indexOfLast { it is ScheduleRow.Show }.coerceAtLeast(0)
        }
    }
    val focusedShow = (schedule.getOrNull(rowIndex) as? ScheduleRow.Show)
    LaunchedEffect(level, focusedShow?.dayStart) {
        if (level != OverlayLevel.DATES) focusedShow?.let { s -> dateIndex = days.indexOf(s.dayStart).coerceAtLeast(0) }
    }

    fun moveShow(delta: Int) {
        if (schedule.isEmpty()) return
        var i = rowIndex
        repeat(kotlin.math.abs(delta)) {
            var j = i + if (delta > 0) 1 else -1
            while (j in schedule.indices && schedule[j] !is ScheduleRow.Show) j += if (delta > 0) 1 else -1
            if (j in schedule.indices) i = j
        }
        rowIndex = i
    }

    fun jumpToDate(index: Int) {
        val day = days.getOrNull(index) ?: return
        dateIndex = index
        val first = schedule.indexOfFirst { it is ScheduleRow.Show && it.dayStart == day }
        if (first >= 0) rowIndex = first
    }

    fun activateShow() {
        val ch = scheduleChannel ?: return
        val p = focusedShow?.program ?: return
        when {
            now >= p.startMs && now < p.stopMs -> { viewModel.preview(ch); onClose() }
            p.stopMs <= now && CatchupUrlBuilder.isAvailable(ch.catchup, p.startMs, now) -> {
                if (viewModel.playCatchup(ch, p)) onClose()
            }
            p.startMs > now -> futurePrompt = ch to p
            else -> Unit // already over, and this channel has no catch-up
        }
    }

    fun selectGroupAt(index: Int) {
        if (groups.isEmpty()) return
        groupIndex = index.coerceIn(0, groups.lastIndex)
        viewModel.selectGroup(groups[groupIndex].id)
    }

    /** Up / Down in the group list: onto the next group or playlist heading. */
    fun moveGroup(delta: Int) {
        if (groupRows.isEmpty()) return
        val at = headingAt
        val pos = if (at != null) groupRows.indexOfFirst { it.group < 0 && it.source == at }
        else groupRows.indexOfFirst { it.group == groupIndex }
        val next = groupRows[(pos.coerceAtLeast(0) + delta).coerceIn(0, groupRows.lastIndex)]
        if (next.group < 0) headingAt = next.source
        else { headingAt = null; selectGroupAt(next.group) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    return@onPreviewKeyEvent isOk || e.key == Key.Back
                }
                activity++
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when (level) {
                    OverlayLevel.CHANNELS -> when {
                        e.key == Key.DirectionUp -> chIndex = (chIndex - 1).let { if (it < 0) channels.lastIndex else it }.coerceAtLeast(0)
                        e.key == Key.DirectionDown -> chIndex = (chIndex + 1).let { if (it > channels.lastIndex) 0 else it }
                        e.key == Key.PageUp || e.key == Key.ChannelUp -> chIndex = (chIndex - 8).coerceAtLeast(0)
                        e.key == Key.PageDown || e.key == Key.ChannelDown -> chIndex = (chIndex + 8).coerceAtMost((channels.size - 1).coerceAtLeast(0))
                        e.key == Key.DirectionLeft -> {
                            groupIndex = groups.indexOfFirst { it.id == ui.selectedGroupId }.coerceAtLeast(0)
                            headingAt = null
                            // The group showing is in a folded playlist: unfold it, so it can be seen.
                            groups.getOrNull(groupIndex)?.sourceId?.let { src ->
                                if (headed && src in collapsed) viewModel.togglePlaylistCollapsed(src)
                            }
                            level = OverlayLevel.GROUPS
                        }
                        e.key == Key.DirectionRight -> focusedChannel?.let { openSchedule(it) }
                        isOk -> focusedChannel?.let { ch ->
                            if (ch.key != playback.channelKey) viewModel.preview(ch)
                            onClose()
                        }
                        e.key == Key.Back -> onClose()
                    }
                    OverlayLevel.GROUPS -> when {
                        e.key == Key.DirectionUp -> moveGroup(-1)
                        e.key == Key.DirectionDown -> moveGroup(1)
                        // OK on a playlist heading: fold or unfold it (the highlight stays on it).
                        isOk && headingAt != null -> headingAt?.let { viewModel.togglePlaylistCollapsed(it) }
                        e.key == Key.DirectionRight || isOk || e.key == Key.Back -> { headingAt = null; level = OverlayLevel.CHANNELS }
                        e.key == Key.DirectionLeft -> Unit
                    }
                    OverlayLevel.SCHEDULE -> when {
                        e.key == Key.DirectionUp -> moveShow(-1)
                        e.key == Key.DirectionDown -> moveShow(1)
                        e.key == Key.PageUp || e.key == Key.ChannelUp -> moveShow(-8)
                        e.key == Key.PageDown || e.key == Key.ChannelDown -> moveShow(8)
                        e.key == Key.DirectionRight -> if (days.isNotEmpty()) level = OverlayLevel.DATES
                        e.key == Key.DirectionLeft || e.key == Key.Back -> level = OverlayLevel.CHANNELS
                        isOk -> activateShow()
                    }
                    OverlayLevel.DATES -> when {
                        e.key == Key.DirectionUp -> jumpToDate((dateIndex - 1).coerceAtLeast(0))
                        e.key == Key.DirectionDown -> jumpToDate((dateIndex + 1).coerceAtMost(days.lastIndex))
                        e.key == Key.DirectionLeft || isOk || e.key == Key.Back -> level = OverlayLevel.SCHEDULE
                        e.key == Key.DirectionRight -> Unit
                    }
                }
                true
            }
            .onSizeChanged { overlayWidthPx = it.width }
            .focusable()
    ) {
        Row(modifier = Modifier.fillMaxHeight().onSizeChanged { panelsWidthPx = it.width }) {
            when (level) {
                OverlayLevel.GROUPS -> {
                    GroupsPanel(groups, groupRows, groupIndex, headingAt, settings.showGroupCounts, playlistNames, collapsed)
                    ChannelsPanel(ui.groups.firstOrNull { it.id == ui.selectedGroupId }?.title ?: "Channels",
                        channels, chIndex, focused = false, playback.channelKey, programs, now, user.favorites, settings.showChannelNumbers, settings.showChannelLogos)
                }
                OverlayLevel.CHANNELS -> {
                    ChannelsPanel(ui.groups.firstOrNull { it.id == ui.selectedGroupId }?.title ?: "Channels",
                        channels, chIndex, focused = true, playback.channelKey, programs, now, user.favorites, settings.showChannelNumbers, settings.showChannelLogos)
                    settledChannel?.let { SchedulePreview(it, programs[it.key].orEmpty(), now, use24h) }
                }
                OverlayLevel.SCHEDULE, OverlayLevel.DATES -> scheduleChannel?.let { ch ->
                    SchedulePanel(ch, schedule, rowIndex, focused = level == OverlayLevel.SCHEDULE, playback.channelKey, now, use24h)
                    DatesPanel(days, dateIndex, focused = level == OverlayLevel.DATES, today = startOfDay(now))
                }
            }
        }

        // Details of the highlighted show, top right, in the space the panels leave (it used to be
        // a fixed width and covered the schedule on narrower screens or with a bigger guide size).
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cardWidth = with(density) { (overlayWidthPx - panelsWidthPx).coerceAtLeast(0).toDp() } - 24.dp - 16.dp
        val cardFits = overlayWidthPx > 0 && cardWidth >= 200.dp
        val cardW = if (cardWidth > 360.dp) 360.dp else cardWidth
        val card: EpgProgram? = when (level) {
            OverlayLevel.CHANNELS -> settledChannel?.let { ch -> programs[ch.key]?.firstOrNull { now >= it.startMs && now < it.stopMs } }
            OverlayLevel.SCHEDULE, OverlayLevel.DATES -> focusedShow?.program
            OverlayLevel.GROUPS -> null
        }
        val cardChannelKey = if (level == OverlayLevel.CHANNELS) settledChannel?.key else scheduleChannel?.key
        rememberShowDetails(cardChannelKey, card)?.takeIf { cardFits }?.let { p ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 24.dp)
                    .width(cardW)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(16.dp)
            ) {
                LiveText(p.title, size = osp(20f), weight = FontWeight.Bold, marquee = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val live = now >= p.startMs && now < p.stopMs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val range = (if (startOfDay(p.startMs) != startOfDay(now)) "${dayLabel(startOfDay(p.startMs), now)}, " else "") +
                        formatRange(p.startMs, p.stopMs, use24h)
                    LiveText(range, color = NuvioTheme.colors.TextSecondary, size = osp(14f))
                    if (live) {
                        Spacer(Modifier.width(10.dp))
                        ProgressBar(p.progress(now), Modifier.width(60.dp))
                        Spacer(Modifier.width(10.dp))
                        LiveText(minutesLeftLabel(p.stopMs, now), color = NuvioTheme.colors.TextSecondary, size = osp(14f))
                    }
                }
                p.description?.let {
                    Spacer(Modifier.height(8.dp))
                    ScrollingDescription(it, color = NuvioTheme.colors.TextSecondary, size = osp(14f), maxLines = 4)
                }
            }
        }
    }

    futurePrompt?.let { (ch, p) ->
        val first = remember { FocusRequester() }
        LiveDialog(onDismiss = { futurePrompt = null; activity++ }, width = 460.dp) {
            LiveText(p.title, size = osp(20f), weight = FontWeight.Bold, maxLines = 2)
            LiveText(
                "${dayLabel(startOfDay(p.startMs), now)}, ${formatRange(p.startMs, p.stopMs, use24h)} · ${ch.name}",
                color = NuvioTheme.colors.TextSecondary, size = osp(13f)
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuItem("Find & stream in Nuvio", Modifier.focusRequester(first)) {
                    futurePrompt = null
                    onFindInNuvio(com.nuvio.tv.livetv.data.LiveTvPosterResolver.searchTitleFor(p, ch))
                }
                MenuItem(
                    if (user.reminders.any { it.channelKey == ch.key && it.startMs == p.startMs }) "Cancel reminder" else "Remind me"
                ) {
                    viewModel.toggleReminder(ch, p)
                    futurePrompt = null
                    activity++
                }
                MenuItem("Watch this channel now") {
                    futurePrompt = null
                    viewModel.preview(ch)
                    onClose()
                }
                MenuItem("Close") { futurePrompt = null; activity++ }
            }
        }
        LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    }
    LaunchedEffect(futurePrompt) {
        if (futurePrompt == null) { delay(60); runCatching { focus.requestFocus() } }
    }
}

// ==================================================================== panels

private val PanelDark = Color(0xE6141416)
private val PanelMid = Color(0x99141416)

@Composable
private fun keepVisible(state: LazyListState, index: Int) {
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (visible.isEmpty() || index <= first + 1 || index >= last - 1) {
            state.scrollToItem((index - 3).coerceAtLeast(0))
        }
    }
}

@Composable
private fun PanelHeader(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(orow(56f))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}

@Composable
private fun ChannelsPanel(
    title: String,
    channels: List<LiveChannel>,
    index: Int,
    focused: Boolean,
    playingKey: String?,
    programs: Map<String, List<EpgProgram>>,
    now: Long,
    favorites: List<String>,
    showNumbers: Boolean,
    showLogos: Boolean
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
    keepVisible(state, index)
    var restingIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(index) {
        restingIndex = -1
        delay(900)
        restingIndex = index
    }
    Column(modifier = Modifier.width(300.dp).fillMaxHeight().background(PanelDark)) {
        PanelHeader { LiveText(title, size = osp(17f), weight = FontWeight.SemiBold) }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(channels, key = { _, c -> c.key }) { i, ch ->
                val p = programs[ch.key]?.firstOrNull { now >= it.startMs && now < it.stopMs }
                val isFocused = i == index
                val colors = liveCellColors(focused = isFocused && focused, idle = if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                // On a solid highlight the accent-colored bits would disappear: make them white.
                val onSolid = isFocused && focused && LocalLiveSolidHighlight.current
                val accent = if (onSolid) Color.White else NuvioTheme.colors.Secondary
                val shape = RoundedCornerShape(8.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(orow(62f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .clip(shape)
                        .background(colors.background)
                        .border(2.dp, colors.border, shape)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showNumbers) LiveText(ch.number.toString(), modifier = Modifier.width(36.dp), color = NuvioTheme.colors.TextSecondary, size = osp(13f))
                    if (showLogos) {
                        ChannelLogo(ch.logo, 32.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveText(ch.name, color = colors.text, size = osp(15f), modifier = Modifier.weight(1f, fill = false), marquee = isFocused && i == restingIndex)
                            if (ch.catchup != null) {
                                Spacer(Modifier.width(4.dp))
                                Icon(androidx.compose.material.icons.Icons.Default.History, contentDescription = "Catch-up", tint = if (onSolid) Color.White else NuvioTheme.colors.TextSecondary, modifier = Modifier.size(odp(13f)))
                            }
                            if (ch.key in favorites) LiveText("  ★", color = if (onSolid) Color.White else NuvioTheme.colors.Rating, size = osp(12f))
                        }
                        LiveText(
                            p?.title ?: "No program information",
                            color = when {
                                p == null -> if (onSolid) Color.White.copy(alpha = 0.8f) else NuvioTheme.colors.TextTertiary
                                else -> accent
                            },
                            size = osp(13f),
                            marquee = isFocused && i == restingIndex
                        )
                        if (p != null) {
                            Spacer(Modifier.height(3.dp))
                            ProgressBar(
                                p.progress(now),
                                Modifier.fillMaxWidth(),
                                color = if (onSolid) Color.White else null,
                                trackColor = if (onSolid) Color.White.copy(alpha = 0.35f) else null
                            )
                        }
                    }
                    if (ch.key == playingKey) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Playing", tint = accent, modifier = Modifier.size(odp(20f)))
                    }
                }
            }
        }
    }
}

/** A row of the overlay's group list: a group ([group] is its index) or a playlist heading ([group] -1). */
private class GroupRow(val source: String?, val group: Int)

/** The group list as the guide lays it out: groups of no playlist first, then each playlist's heading and (unless folded) its groups. */
private fun overlayGroupRows(groups: List<ChannelGroup>, headed: Boolean, collapsed: Set<String>): List<GroupRow> {
    if (!headed) return groups.indices.map { GroupRow(groups[it].sourceId, it) }
    val out = ArrayList<GroupRow>()
    groups.forEachIndexed { i, g -> if (g.sourceId == null) out += GroupRow(null, i) }
    groups.mapNotNull { it.sourceId }.distinct().forEach { src ->
        out += GroupRow(src, -1)
        if (src !in collapsed) groups.forEachIndexed { i, g -> if (g.sourceId == src) out += GroupRow(src, i) }
    }
    return out
}

@Composable
private fun GroupsPanel(
    groups: List<ChannelGroup>,
    rows: List<GroupRow>,
    index: Int,
    headingAt: String?,
    showCounts: Boolean,
    playlistNames: Map<String, String>,
    collapsed: Set<String>
) {
    val itemIndex = if (headingAt != null) rows.indexOfFirst { it.group < 0 && it.source == headingAt }
    else rows.indexOfFirst { it.group == index }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (itemIndex - 3).coerceAtLeast(0))
    keepVisible(state, itemIndex)
    Column(modifier = Modifier.width(250.dp).fillMaxHeight().background(PanelDark).padding(top = 20.dp)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
            itemsIndexed(rows, key = { _, row -> if (row.group >= 0) groups[row.group].id else "header:${row.source}" }) { _, row ->
                val shape = RoundedCornerShape(8.dp)
                if (row.group < 0) {
                    val src = row.source.orEmpty()
                    val focused = headingAt == src
                    val isCollapsed = src in collapsed
                    val inSource = groups.filter { it.sourceId == src }
                    val colors = liveCellColors(focused = focused, idle = Color.Transparent, idleText = NuvioTheme.colors.Secondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp)
                            .height(orow(34f))
                            .clip(shape)
                            .background(colors.background)
                            .border(2.dp, colors.border, shape)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiveText(if (isCollapsed) "▸" else "▾", color = colors.text, size = osp(13f), modifier = Modifier.width(18.dp))
                        LiveText(
                            (playlistNames[src] ?: "Playlist").uppercase(),
                            color = colors.text, size = osp(12f), weight = FontWeight.Bold,
                            modifier = Modifier.weight(1f), marquee = focused
                        )
                        LiveText(
                            // Folded, but its group is the one showing: a dot says so.
                            if (isCollapsed && inSource.any { groups.indexOf(it) == index }) "•" else "${inSource.size}",
                            color = if (focused) colors.text else NuvioTheme.colors.TextSecondary,
                            size = osp(11f)
                        )
                    }
                    return@itemsIndexed
                }
                val i = row.group
                val g = groups[i]
                val colors = liveCellColors(focused = headingAt == null && i == index, idle = Color.Transparent)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(orow(44f))
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(shape)
                        .background(colors.background)
                        .border(2.dp, colors.border, shape)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveText(g.title, color = colors.text, size = osp(16f), modifier = Modifier.weight(1f), marquee = headingAt == null && i == index)
                    if (showCounts) LiveText(g.count.toString(), color = NuvioTheme.colors.TextSecondary, size = osp(12f))
                }
            }
        }
    }
}

/** The highlighted channel's shows around now (just to look at; Right opens the full schedule). */
@Composable
private fun SchedulePreview(channel: LiveChannel, list: List<EpgProgram>, now: Long, use24h: Boolean) {
    val liveIndex = list.indexOfFirst { now >= it.startMs && now < it.stopMs }
    val anchor = if (liveIndex >= 0) liveIndex else list.indexOfFirst { it.startMs >= now }.coerceAtLeast(0)
    val shown = list.drop((anchor - 5).coerceAtLeast(0)).take(14)
    Column(modifier = Modifier.width(320.dp).fillMaxHeight().background(PanelMid)) {
        PanelHeader {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.logo, 26.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    LiveText(channel.name, size = osp(15f), weight = FontWeight.SemiBold)
                    LiveText(channel.group, color = NuvioTheme.colors.TextSecondary, size = osp(12f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            LiveText("No program information", color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(18.dp))
        }
        shown.forEach { p ->
            val live = now >= p.startMs && now < p.stopMs
            val color = when {
                live -> NuvioTheme.colors.Secondary
                p.stopMs <= now -> NuvioTheme.colors.TextSecondary
                else -> NuvioTheme.colors.TextPrimary
            }
            Row(modifier = Modifier.fillMaxWidth().height(orow(36f)).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(formatClock(p.startMs, use24h), color = color, size = osp(14f), modifier = Modifier.width(80.dp))
                LiveText(p.title, color = color, size = osp(14f), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SchedulePanel(
    channel: LiveChannel,
    rows: List<ScheduleRow>,
    index: Int,
    focused: Boolean,
    playingKey: String?,
    now: Long,
    use24h: Boolean
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
    keepVisible(state, index)
    Column(modifier = Modifier.width(460.dp).fillMaxHeight().background(PanelDark)) {
        PanelHeader {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.logo, 28.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    LiveText(channel.name, size = osp(16f), weight = FontWeight.SemiBold)
                    LiveText(channel.group, color = NuvioTheme.colors.TextSecondary, size = osp(12f))
                }
            }
        }
        if (rows.isEmpty()) {
            LiveText("No program information for this channel", color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(18.dp))
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows) { i, row ->
                when (row) {
                    is ScheduleRow.Day -> LiveText(
                        row.label,
                        color = NuvioTheme.colors.Secondary,
                        size = osp(14f),
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp)
                    )
                    is ScheduleRow.Show -> {
                        val p = row.program
                        val live = now >= p.startMs && now < p.stopMs
                        val past = p.stopMs <= now
                        val isFocused = i == index
                        val colors = liveCellColors(
                            focused = isFocused && focused,
                            idle = if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                            idleText = if (past) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                        )
                        val shape = RoundedCornerShape(8.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(orow(40f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .clip(shape)
                                .background(colors.background)
                                .border(2.dp, colors.border, shape)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LiveText(formatClock(p.startMs, use24h), color = colors.text, size = osp(14f), modifier = Modifier.width(80.dp))
                            LiveText(p.title, color = colors.text, size = osp(14f), modifier = Modifier.weight(1f), marquee = isFocused)
                            if (live && channel.key == playingKey) {
                                val onSolid = isFocused && focused && LocalLiveSolidHighlight.current
                                Icon(
                                    Icons.Default.PlayArrow, contentDescription = null,
                                    tint = if (onSolid) Color.White else NuvioTheme.colors.Secondary,
                                    modifier = Modifier.size(odp(18f))
                                )
                            }
                            if (live) {
                                Spacer(Modifier.width(6.dp))
                                LiveText(
                                    "LIVE",
                                    modifier = Modifier
                                        .border(1.dp, colors.text.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                    color = colors.text,
                                    size = osp(10f),
                                    weight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DatesPanel(days: List<Long>, index: Int, focused: Boolean, today: Long) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
    keepVisible(state, index)
    val fmt = remember { SimpleDateFormat("EEE,\nMMM d", Locale.getDefault()) }
    Column(modifier = Modifier.width(100.dp).fillMaxHeight().background(PanelMid).padding(top = 56.dp)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(days) { i, day ->
                val isSelected = i == index
                val colors = liveCellColors(focused = isSelected && focused, idle = if (isSelected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                val shape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(orow(64f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .clip(shape)
                        .background(colors.background)
                        .border(2.dp, colors.border, shape),
                    contentAlignment = Alignment.Center
                ) {
                    LiveText(
                        fmt.format(Date(day)),
                        color = if (day == today && !(isSelected && focused)) NuvioTheme.colors.Secondary else colors.text,
                        size = osp(13f),
                        maxLines = 2,
                        align = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

// ==================================================================== dates

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(dayStart: Long, now: Long): String {
    val today = startOfDay(now)
    val diffDays = Math.round((dayStart - today) / 86_400_000.0).toInt()
    return when (diffDays) {
        0 -> "Today"
        -1 -> "Yesterday"
        1 -> "Tomorrow"
        else -> SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(dayStart))
    }
}
