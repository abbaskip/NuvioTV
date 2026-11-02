@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.guideBackdrop
import com.nuvio.tv.livetv.ui.drawGuideBackdrop
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.ThemeGlow
import com.nuvio.tv.livetv.ui.drawThemeGlow
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Asks the Sports page to do something when it opens (from Settings, a reminder…). */
object SportsLaunch {
    @Volatile private var openPicker = false
    @Volatile private var openEvent: String? = null

    fun openPicker() { openPicker = true }
    @Volatile private var openArrange = false
    /** Open Follow Teams & Leagues straight on Arrange My Sports (from Sports settings). */
    fun openArrange() { openArrange = true; openPicker = true }
    fun takeOpenArrange(): Boolean = openArrange.also { openArrange = false }
    fun takeOpenPicker(): Boolean = openPicker.also { openPicker = false }
    fun openEvent(id: String) { openEvent = id }
    fun takeOpenEvent(): String? = openEvent.also { openEvent = null }

    /** A replay from the start, for Live TV to start when it opens. */
    data class Catchup(val channelKey: String, val program: com.nuvio.tv.livetv.model.EpgProgram)

    /** Live TV was opened from the Sports page (Back goes back there), and on which game. */
    @Volatile private var returnTo: String? = null
    @Volatile private var focusEvent: String? = null
    @Volatile private var returnSetAt = 0L
    fun openedFromSports(eventId: String) { returnTo = eventId; focusEvent = eventId; returnSetAt = System.currentTimeMillis() }
    fun takeReturnToSports(): Boolean = (returnTo != null).also { returnTo = null }
    fun clearReturn() { returnTo = null; focusEvent = null }
    /** Live TV closed some other way: Back there no longer goes to Sports. */
    fun dropReturn() {
        // Not while Live TV is still opening (its screens are swapped in once on the way).
        if (System.currentTimeMillis() - returnSetAt > 5_000) returnTo = null
    }
    fun takeFocusEvent(): String? = focusEvent.also { focusEvent = null }

    @Volatile private var catchup: Catchup? = null
    fun catchup(c: Catchup) { catchup = c }
    fun takeCatchup(): Catchup? = catchup.also { catchup = null }
}

private const val CHIP_MINE = "mine"
private const val CHIP_LIVE = "live"
private const val CHIP_ALL = "all"
/** A league from the top bar: "lg:" + its id. */
private const val CHIP_LEAGUE = "lg:"
/** A sport you follow: My Teams' rows for that sport only ("sp:" + the sport's name). */
private const val CHIP_SPORT = "sp:"
/** Tables: your leagues' tables (see SportsTables). */
private const val CHIP_TABLES = "tables"

/** Width of the game list next to the video window. */
private val LIST_WIDTH = 470.dp

/**
 * The Sports page. With the video window on (the default): the games on the left and a video
 * window on the right, where games play while you browse. With it off: the same cards, two to a
 * row across the whole width, and games open full screen.
 *
 *  - OK on a game: its game page (channels, score by period, scoring plays, follow, remind).
 *  - Hold OK on a game: watch it straight away (in the window, or full screen when it's off).
 *  - On the game page, OK on a channel plays it in the window; OK on it again goes full screen.
 *
 * The window is the same video player as Live TV's preview and full screen, so the picture
 * carries on without restarting from the list to the game page, to full screen and back.
 */
@Composable
fun SportsScreen(
    /** Live TV is playing the picked channel: open it full screen. */
    onOpenLive: () -> Unit,
    viewModel: SportsViewModel = hiltViewModel()
) {
    com.nuvio.tv.livetv.ui.PauseLiveTvInBackground(viewModel.playback)
    val events by viewModel.events.collectAsStateWithLifecycle()
    val known by viewModel.known.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val matching by viewModel.matching.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val hasChannels by viewModel.hasChannels.collectAsStateWithLifecycle()
    val playingKey by viewModel.playingKey.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val backdrop by viewModel.backdrop.collectAsStateWithLifecycle()

    var day by rememberSaveable { mutableIntStateOf(0) }
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var geminiSetup by remember { mutableStateOf(false) }
    var sportsPreferences by rememberSaveable { mutableStateOf(false) }
    var fixFor by remember { mutableStateOf<SportsEvent?>(null) }
    // A league's whole table, opened from a game page (Back goes back to the game).
    var tableReq by remember { mutableStateOf<TableRequest?>(null) }
    // Back from a game (or Pick Your Teams): the highlight goes back to where you were.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(id: String) = requesters.getOrPut(id) { FocusRequester() }
    var returnTo by remember { mutableStateOf<String?>(null) }
    // Kept while a game is open, so the list is where you left it when you come back.
    val listState = rememberLazyListState()
    // My Teams rows: where each row and the page were, and the card last highlighted.
    val rowsState = rememberLazyListState()
    val rowStates = remember { HashMap<String, androidx.compose.foundation.lazy.LazyListState>() }
    // The card last highlighted (not page state: moving the highlight mustn't redraw the page).
    val rowFocus = remember { arrayOfNulls<String>(1) }
    var rowsReturning by remember { mutableStateOf(false) }

    // ---- the video window: the shared player, shown where the page puts its slot
    val videoOn = settings.videoWindow && hasChannels
    var playerRef by remember { mutableStateOf<androidx.media3.common.Player?>(null) }
    DisposableEffect(videoOn) {
        if (videoOn) playerRef = viewModel.attachPlayer()
        onDispose { if (videoOn) { viewModel.detachPlayer(); playerRef = null } }
    }
    // A game playing in the window: keep the TV awake (no screen saver), as Live TV does.
    val playingNow by viewModel.playingNow.collectAsStateWithLifecycle()
    com.nuvio.tv.livetv.ui.KeepScreenAwake(videoOn && playingNow)
    var slot by remember { mutableStateOf<Rect?>(null) }
    var root by remember { mutableStateOf<Rect?>(null) }
    val videoSlot: @Composable (Modifier) -> Unit = { m -> VideoSlot(m) { slot = it } }

    DisposableEffect(Unit) {
        viewModel.repo.setViewing(true)
        onDispose { viewModel.repo.setViewing(false) }
    }
    LaunchedEffect(Unit) {
        // Back from watching a game: the highlight goes back on it.
        SportsLaunch.takeFocusEvent()?.let { if (openId == null) { returnTo = it; rowsReturning = true } }
        SportsLaunch.takeOpenEvent()?.let { openId = it }
        if (SportsLaunch.takeOpenPicker()) picker = true
        else if (viewModel.isFirstVisit()) sportsPreferences = true
    }

    /** Full screen in Live TV (from the start when the game is over). */
    fun watch(ev: SportsEvent, m: ChannelMatch?) {
        if (m == null) {
            LiveTvNotice.show("No channel found for ${ev.title}. Open it and use Fix Channel to pick one.")
            return
        }
        SportsLaunch.openedFromSports(ev.id)
        if (ev.state == GameState.POST) {
            if (viewModel.watchFromStart(ev, m)) onOpenLive()
            else LiveTvNotice.show("${ev.title} has finished, and ${m.channel.name} can't replay it")
            return
        }
        viewModel.watch(m)
        onOpenLive()
    }
    fun fromStart(ev: SportsEvent, m: ChannelMatch) {
        SportsLaunch.openedFromSports(ev.id)
        if (viewModel.watchFromStart(ev, m)) onOpenLive()
        else LiveTvNotice.show("${m.channel.name} can't replay this from the start")
    }
    /** A channel picked on the game page: in the window (again: full screen), or full screen. */
    fun choose(ev: SportsEvent, m: ChannelMatch) {
        when {
            !videoOn -> watch(ev, m)
            ev.state == GameState.POST -> watch(ev, m)
            playingKey == m.channel.key -> watch(ev, m)
            else -> viewModel.playHere(m)
        }
    }
    /** Hold OK on a game: its best channel, straight away. */
    fun quickWatch(ev: SportsEvent) {
        val m = matches[ev.id]?.firstOrNull()
        when {
            m == null -> LiveTvNotice.show("No channel found for ${ev.title}. Open it and use Fix Channel to pick one.")
            videoOn && ev.state != GameState.POST -> viewModel.playHere(m)
            else -> watch(ev, m)
        }
    }

    // Hold Back: full screen on what's playing in the window (like Live TV's guide). Nothing
    // playing: Back works as usual.
    val hostActivity = androidx.compose.ui.platform.LocalContext.current as? com.nuvio.tv.MainActivity
    var backStartedHere by remember { mutableStateOf(false) }
    fun backToFullscreen() {
        val key = playingKey ?: return
        events.firstOrNull { ev -> matches[ev.id]?.any { it.channel.key == key } == true }?.let { SportsLaunch.openedFromSports(it.id) }
        onOpenLive()
    }
    com.nuvio.tv.livetv.ui.FrameMeter("Sports")
    Box(
        Modifier
            .fillMaxSize()
            .guideBackdrop(backdrop)
            .onGloballyPositioned { root = it.boundsInRoot() }
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back) return@onPreviewKeyEvent false
                val native = e.nativeKeyEvent
                val canGo = videoOn && playingKey != null && playingNow
                when {
                    e.type == KeyEventType.KeyDown && native.repeatCount == 0 && !native.isLongPress -> { backStartedHere = true; false }
                    // Remotes that repeat a held button: go while it's still held.
                    e.type == KeyEventType.KeyDown -> {
                        if (!backStartedHere || !canGo) return@onPreviewKeyEvent false
                        backStartedHere = false
                        // The rest of this press is swallowed (letting go isn't also a Back).
                        hostActivity?.longPressBackHeld?.value = true
                        backToFullscreen()
                        true
                    }
                    // Remotes that don't repeat: held half a second or more, judged on release.
                    e.type == KeyEventType.KeyUp -> {
                        val started = backStartedHere
                        backStartedHere = false
                        if (started && canGo && native.eventTime - native.downTime >= 500) { backToFullscreen(); true } else false
                    }
                    else -> false
                }
            }
    ) {
        // The same soft glow in the theme colour as the top of the guide.
        ThemeGlow(Modifier.fillMaxWidth().height(SPORTS_GLOW_HEIGHT))
        val opened = openId?.let { id -> known.firstOrNull { it.id == id } }
        if (openId != null && opened == null && known.isNotEmpty()) openId = null
        when {
            picker -> SportsPicker(viewModel = viewModel, onClose = { picker = false; returnTo = null; rowsReturning = false; viewModel.repo.markSetUp() })
            tableReq != null -> {
                BackHandler { tableReq = null }
                TablesPage(
                    viewModel = viewModel,
                    follows = follows,
                    settings = settings,
                    now = now,
                    use24h = use24h,
                    videoOn = videoOn,
                    videoSlot = videoSlot,
                    header = {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                            LiveText("‹  " + (opened?.title ?: "Sports"), color = SportsLook.Dim, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(10.dp))
                            LiveText("Tables", size = 22.sp, weight = FontWeight.Black)
                        }
                    },
                    request = tableReq,
                    focusTable = true,
                    onOpen = { ev -> tableReq = null; openId = ev.id },
                    onQuickWatch = ::quickWatch
                )
            }
            opened != null -> GamePage(
                ev = opened,
                matches = matches[opened.id].orEmpty(),
                follows = follows,
                settings = settings,
                now = now,
                use24h = use24h,
                matching = matching,
                hasChannels = hasChannels,
                videoOn = videoOn,
                playingKey = playingKey,
                videoSlot = videoSlot,
                viewModel = viewModel,
                onChoose = { m -> choose(opened, m) },
                onFromStart = { m -> fromStart(opened, m) },
                onFix = { fixFor = opened },
                onTable = { tableReq = TableRequest(opened.leagueId, opened.sides.map { it.team.id }.toSet()) },
                onOpenGame = { ev -> openId = ev.id },
                onQuickWatch = ::quickWatch,
                onClose = { returnTo = opened.id; rowsReturning = true; openId = null }
            )
            else -> SportsHub(
                events = events,
                known = known,
                rowsState = rowsState,
                rowState = { key, start -> rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState(start) } },
                lastRowFocus = rowFocus[0],
                onRowFocus = { rowFocus[0] = it },
                rowsReturning = rowsReturning,
                onRowsReturned = { rowsReturning = false },
                matches = matches,
                follows = follows,
                settings = settings,
                now = now,
                use24h = use24h,
                loading = loading,
                matching = matching,
                problem = problem,
                hasChannels = hasChannels,
                videoOn = videoOn,
                playingKey = playingKey,
                day = day,
                onDay = { day = it },
                chip = chip,
                onChip = { chip = it },
                listState = listState,
                requesterFor = ::requesterFor,
                returnTo = returnTo,
                onReturned = { returnTo = null; rowsReturning = false },
                videoSlot = videoSlot,
                viewModel = viewModel,
                onOpen = { ev -> openId = ev.id },
                onQuickWatch = ::quickWatch,
                onPick = { sportsPreferences = true }
            )
        }
        // The picture, on top of the page where its slot is (one view for every page, so it
        // never restarts), then the rounded corners and the "nothing playing" note over it.
        if (videoOn) VideoLayer(slot, root, playerRef ?: viewModel.player, playing = previewState.hasPreviewContent, label = previewState.catchupTitle ?: viewModel.playback.playingChannel?.name, backdrop = backdrop, onAttached = { viewModel.onSurfaceAttached() })
    }
    if (sportsPreferences) SportsPreferencesDialog(settings.excludedSportsNodes,
        region = SportsCatalog.regionOf(settings), onRegion = { region -> viewModel.repo.updateSettings { it.copy(region = region) } },
        onToggle = { id -> viewModel.repo.updateSettings { s -> s.copy(excludedSportsNodes =
            if (id in s.excludedSportsNodes) s.excludedSportsNodes - id else s.excludedSportsNodes + id) } },
        geminiMatching = settings.backgroundGeminiMatching,
        onToggleGemini = {
            if (!settings.backgroundGeminiMatching && !viewModel.gemini.configured()) {
                sportsPreferences = false; geminiSetup = true
            } else viewModel.repo.updateSettings { it.copy(backgroundGeminiMatching = !it.backgroundGeminiMatching) }
        },
        onTeams = { sportsPreferences = false; picker = true },
        onGemini = { sportsPreferences = false; geminiSetup = true },
        onClose = { sportsPreferences = false; viewModel.repo.markSetUp() })
    if (geminiSetup) SportsGeminiKeyDialog(viewModel.gemini) { geminiSetup = false }
    fixFor?.let { ev -> FixChannelDialog(ev, matches[ev.id].orEmpty(), settings.hideScores, viewModel) { fixFor = null } }
}

/** How far down the theme glow reaches (as in the guide). */
private val SPORTS_GLOW_HEIGHT = 220.dp

/** Where the video goes on a page: reports its place on screen, and clears it when it leaves. */
@Composable
private fun VideoSlot(modifier: Modifier, onBounds: (Rect?) -> Unit) {
    DisposableEffect(Unit) { onDispose { onBounds(null) } }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
    )
}

@Composable
private fun VideoLayer(slot: Rect?, root: Rect?, player: androidx.media3.common.Player?, playing: Boolean, label: String?, backdrop: String, onAttached: () -> Unit) {
    val density = LocalDensity.current
    val placed = slot != null && root != null && slot.width > 1f
    val place = if (placed) with(density) {
        Modifier
            .offset { IntOffset((slot!!.left - root!!.left).toInt(), (slot.top - root.top).toInt()) }
            .size(slot!!.width.toDp(), slot.height.toDp())
    } else Modifier.size(1.dp)
    LivePlayerSurface(player = player, useSurfaceView = true, modifier = place, onAttached = onAttached)
    if (!placed) return
    val bg = NuvioTheme.colors.Background
    val backdropResolved = com.nuvio.tv.livetv.ui.resolveBackdrop(backdrop)
    val accent = NuvioTheme.colors.Secondary
    val glowTop = NuvioTheme.colors.BackgroundElevated
    Box(
        place
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawBehind {
                // Rounded corners: exactly what's behind them (the page colour and the glow) over
                // the picture's square corners, then the rounded window cut back out.
                val dx = slot!!.left - root!!.left
                val dy = slot.top - root.top
                translate(-dx, -dy) {
                    drawGuideBackdrop(backdropResolved, root.width, root.height)
                    drawThemeGlow(accent, glowTop, root.width, SPORTS_GLOW_HEIGHT.toPx())
                }
                drawRoundRect(
                    color = Color.Black,
                    cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                    blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (!playing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                LiveText("▶", color = SportsLook.Dim, size = 26.sp)
                Spacer(Modifier.height(6.dp))
                LiveText("Pick a game to watch here", color = Color.White, size = 14.sp, weight = FontWeight.Bold)
                LiveText("Hold OK on a game, or open it and pick a channel", color = SportsLook.Dim, size = 11.sp)
            }
        } else if (!label.isNullOrBlank()) {
            LiveText(label, color = Color.White, size = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.65f)).padding(8.dp))
        }
    }
}

// ------------------------------------------------------------------ the list

@Composable
private fun SportsHub(
    events: List<SportsEvent>,
    known: List<SportsEvent>,
    rowsState: androidx.compose.foundation.lazy.LazyListState,
    rowState: (String, Int) -> androidx.compose.foundation.lazy.LazyListState,
    lastRowFocus: String?,
    onRowFocus: (String) -> Unit,
    rowsReturning: Boolean,
    onRowsReturned: () -> Unit,
    matches: Map<String, List<ChannelMatch>>,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    loading: Boolean,
    matching: Boolean,
    problem: String?,
    hasChannels: Boolean,
    videoOn: Boolean,
    playingKey: String?,
    day: Int,
    onDay: (Int) -> Unit,
    chip: String?,
    onChip: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    requesterFor: (String) -> FocusRequester,
    returnTo: String?,
    onReturned: () -> Unit,
    videoSlot: @Composable (Modifier) -> Unit,
    viewModel: SportsViewModel,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit,
    onPick: () -> Unit
) {
    // Back opens the side menu first, like the guide and On Demand (it used to go straight to
    // Home). With the menu open, Back is left to the app. A game page, Pick Your Teams and the
    // pop-ups handle Back themselves first.
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    val sidebarOpen = com.nuvio.tv.LocalSidebarExpanded.current
    BackHandler(enabled = !sidebarOpen) { openSidebar() }

    // ---- what this day holds
    val dayStart = viewModel.repo.dayStart(day)
    val dayEnd = viewModel.repo.dayStart(day + 1)
    val active = remember(settings, follows) { viewModel.repo.activeLeagues(settings, follows).map { it.id }.toSet() + SportsMatcher.OTHER_ID }
    // "Only games on my channels": once channels have been looked for, games on now or coming up
    // that none of them show are left out (results, your teams' games and names you follow stay).
    val filterToChannels = settings.onlyOnMyChannels && hasChannels && matches.isNotEmpty()
    // A league from the top bar: that league's whole week (yesterday to a week ahead), by date.
    val pinnedChip = chip?.takeIf { it.startsWith(CHIP_LEAGUE) }
    val followedSports = remember(follows, settings) { SportsRows.followedSports(follows, SportsCatalog.sportsOrder(settings)) }
    val sportChip = chip?.takeIf { it.startsWith(CHIP_SPORT) }
    // Picking a filter puts the highlight on the page: All Events' first game (the first one on
    // now), My Sports' big card.
    var contentJump by remember { mutableIntStateOf(0) }
    val pickChip: (String) -> Unit = { c -> onChip(c); contentJump++ }
    val dayTabReqs = remember { HashMap<Int, FocusRequester>() }
    // The day tabs' row: kept so the day you're on is on screen whenever the row isn't in use
    // (Up from the buttons below goes to it; a day scrolled off the row can't be reached).
    val tabsState = rememberLazyListState(initialFirstVisibleItemIndex = SportsRepository.DAYS_BACK - 1)
    val tabsScope = androidx.compose.runtime.rememberCoroutineScope()
    fun showPickedDay() {
        val i = (day + SportsRepository.DAYS_BACK - 1).coerceAtLeast(0)
        tabsScope.launch { runCatching { tabsState.animateScrollToItem(i) } }
    }
    LaunchedEffect(day) { showPickedDay() }
    fun dayTabReq(offset: Int) = dayTabReqs.getOrPut(offset) { FocusRequester() }
    val effectiveChip = when {
        // League buttons and Live were folded into My Teams' rows and All Events: saved ones go home.
        pinnedChip != null || chip == CHIP_LIVE -> if (follows.isEmpty) CHIP_ALL else CHIP_MINE
        sportChip != null && followedSports.none { CHIP_SPORT + it.name == sportChip } -> if (follows.isEmpty) CHIP_ALL else CHIP_MINE
        // Old saved filters (a sport's name on its own, from before): My Teams.
        chip != null && Sport.entries.any { it.name == chip } -> if (follows.isEmpty) CHIP_ALL else CHIP_MINE
        else -> chip ?: if (follows.isEmpty) CHIP_ALL else CHIP_MINE
    }
    val rowsSport: Sport? = effectiveChip.takeIf { it.startsWith(CHIP_SPORT) }?.let { c -> followedSports.firstOrNull { CHIP_SPORT + it.name == c } }
    val rowsPage = effectiveChip == CHIP_MINE || rowsSport != null
    val leagueView = effectiveChip.takeIf { it.startsWith(CHIP_LEAGUE) }?.removePrefix(CHIP_LEAGUE)
    val weekStart = viewModel.repo.dayStart(-1)
    val weekEnd = viewModel.repo.dayStart(SportsRepository.DAYS_AHEAD + 1)
    // Days before yesterday: their games are fetched when you open one.
    LaunchedEffect(day) { if (day < -1) viewModel.repo.wantPast() }
    val dayEvents = remember(events, dayStart, dayEnd, active, filterToChannels, matches, follows, leagueView, weekStart, now / 60_000L) {
        if (leagueView != null) return@remember events.filter { ev ->
            ev.leagueId == leagueView && ev.startMs < weekEnd && (ev.endMs ?: ev.startMs) >= weekStart
        }
        events.filter { ev ->
            // A finished game or event shows on the day it finished only (a race weekend or a
            // tournament isn't repeated on every day of its dates once it's over).
            val onDay = when {
                ev.state == GameState.POST -> {
                    val finished = if (ev.hasTime) ev.startMs else minOf(ev.endMs ?: ev.startMs, now)
                    finished in dayStart until dayEnd
                }
                ev.hasTime -> ev.startMs in dayStart until dayEnd
                // A tournament or race weekend still to come: on the day it starts only.
                ev.state == GameState.PRE -> ev.startMs in dayStart until dayEnd
                // One under way: today and the days ahead it still runs (not the days before today).
                else -> dayEnd > now && ev.startMs < dayEnd && (ev.endMs ?: (ev.startMs + 1)) >= dayStart
            }
            ev.leagueId in active && onDay &&
                (!filterToChannels || ev.state == GameState.POST || isMine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
        }
    }
    val shown = remember(dayEvents, effectiveChip, follows) {
        when (effectiveChip) {
            CHIP_MINE -> dayEvents.filter { isMine(it, follows) }
            CHIP_LIVE -> dayEvents.filter { it.state == GameState.LIVE }
            CHIP_ALL -> dayEvents
            else -> if (effectiveChip.startsWith(CHIP_LEAGUE)) dayEvents
            else dayEvents.filter { SportsCatalog.league(it.leagueId)?.sport?.name == effectiveChip }
        }
    }
    // League rank as a lookup (it was a list search inside every sort comparison).
    val leagueRank = remember(settings) { SportsCatalog.ordered(settings).mapIndexed { i, l -> l.id to i }.toMap() }
    // The sections, sorted once per change of games, channels or time, not on every redraw
    // (moving the highlight redraws this screen).
    val sections = remember(shown, dayEvents, matches, follows, now, leagueRank, leagueView) {
        fun rank(ev: SportsEvent) = leagueRank[ev.leagueId] ?: 999
        // Golf, tennis, a race weekend: "in progress" all week in the feed. Live Now only while one
        // of your channels is showing it; otherwise listed under This Week.
        fun airingNow(ev: SportsEvent): Boolean = matches[ev.id]?.any { m -> m.program?.let { now >= it.startMs && now < it.stopMs } == true } == true
        val weekLong = shown.filter { !it.hasTime && it.state == GameState.LIVE && !airingNow(it) }
        val weekIds = weekLong.mapTo(HashSet()) { it.id }
        val live = shown.filter { it.state == GameState.LIVE && it.id !in weekIds }
            .sortedWith(compareBy<SportsEvent> { it.source == EventSource.GUIDE }.thenBy { it.isReplay }.thenByDescending { isMine(it, follows) }.thenByDescending { matches[it.id]?.isNotEmpty() == true }.thenBy { rank(it) }.thenBy { it.startMs })
        val upcoming = shown.filter { it.state == GameState.PRE || it.state == GameState.OFF }
            .sortedWith(compareBy<SportsEvent> { if (it.hasTime) it.startMs else Long.MIN_VALUE }.thenByDescending { isMine(it, follows) }.thenBy { rank(it) })
        val finished = shown.filter { it.state == GameState.POST }
            .sortedWith(compareByDescending<SportsEvent> { it.startMs }.thenBy { rank(it) })
            .let { if (leagueView != null) it.take(12) else it }
        val liveCount = dayEvents.count { it.source != EventSource.GUIDE && it.state == GameState.LIVE && (it.hasTime || airingNow(it)) }
        DaySections(weekLong, live, upcoming, finished, liveCount)
    }
    val columns = if (videoOn) 1 else 2

    val firstContent = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val hasContent = sections.live.isNotEmpty() || sections.upcoming.isNotEmpty() || sections.finished.isNotEmpty() || sections.weekLong.isNotEmpty()
    LaunchedEffect(hasContent, day, effectiveChip) {
        delay(120)
        // My Teams' rows put the highlight where it belongs themselves.
        if (rowsPage) return@LaunchedEffect
        if (!focusedOnce) {
            focusedOnce = true
            val back = returnTo?.let { requesterFor(it) }
            onReturned()
            runCatching { (back ?: if (hasContent) firstContent else firstChip).requestFocus() }
                .onFailure { runCatching { if (hasContent) firstContent.requestFocus() else firstChip.requestFocus() }.onFailure { runCatching { firstChip.requestFocus() } } }
        }
    }

    // Only a problem is worth a line (scores update quietly in the background), and it sits in
    // the bottom corner so nothing on the page moves when it comes and goes.
    // A filter picked on All Events: down to its first game (LIVE NOW comes first).
    LaunchedEffect(contentJump) {
        if (contentJump == 0 || rowsPage) return@LaunchedEffect
        delay(160)
        focusedOnce = true
        runCatching { firstContent.requestFocus() }
    }
    val status: String? = problem
    val statusLine: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        // Top corner: a spinner while your channels are being searched for games; gone when done.
        if (matching) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.nuvio.tv.livetv.ui.LoadingSpinner()
                Spacer(Modifier.width(8.dp))
                LiveText("Finding channels for today's games…", color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold)
            }
        }
        status?.let {
            LiveText(it, color = NuvioTheme.colors.Error, size = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 8.dp))
        }
    }
    val header: @Composable (Modifier) -> Unit = { hm ->
        Row(hm, verticalAlignment = Alignment.CenterVertically) {
            LiveText("Sports", size = 26.sp, weight = FontWeight.Black)
            Spacer(Modifier.width(14.dp))
            val lv = leagueView?.let { SportsCatalog.league(it) }
            if (lv != null) {
                LiveText("${lv.name} · the next 7 days", color = SportsLook.Dim, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            } else {
                // A week back to a week ahead; opens on Yesterday and Today, and scrolls.
                val dayFmt = remember { SimpleDateFormat("EEE d", Locale.getDefault()) }
                LazyRow(
                    Modifier.weight(1f)
                        .onFocusChanged { if (!it.hasFocus) showPickedDay() }
                        .keepFocusWhileHeld(vertical = false),
                    state = tabsState,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(end = 12.dp)
                ) {
                    items((-SportsRepository.DAYS_BACK..SportsRepository.DAYS_AHEAD).toList(), key = { it }) { offset ->
                        val label = when (offset) {
                            -1 -> "Yesterday"
                            0 -> "Today"
                            1 -> "Tomorrow"
                            else -> dayFmt.format(Date(viewModel.repo.dayStart(offset) + 12 * 3_600_000L))
                        }
                        DayTab(label, selected = day == offset, modifier = Modifier.focusRequester(dayTabReq(offset)), onClick = { onDay(offset); focusedOnce = true })
                    }
                }
            }
        }
    }
    val filters: @Composable (Modifier) -> Unit = { m ->
        LazyRow(m.keepFocusWhileHeld(vertical = false), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
            // ↑ from the filters on All Events: the day you're looking at (on screen: it's the one picked).
            val upTo = if (!rowsPage) Modifier.focusProperties {
                up = if (tabsState.layoutInfo.visibleItemsInfo.any { it.key == day }) dayTabReq(day) else FocusRequester.Default
            } else Modifier
            item(key = CHIP_MINE) { FilterIcon("★", "My Sports", effectiveChip == CHIP_MINE, Modifier.focusRequester(firstChip).then(upTo), withLabel = true) { pickChip(CHIP_MINE) } }
            // Everything on, day by day (live games first), with how many are on now.
            item(key = CHIP_ALL) {
                FilterIcon(
                    "▦", if (sections.liveCount > 0) "All Events · ${sections.liveCount} live" else "All Events",
                    effectiveChip == CHIP_ALL, upTo, withLabel = true
                ) { pickChip(CHIP_ALL) }
            }
            // Your leagues' tables.
            item(key = "preferences") { FilterIcon("⚙", "Choose Sports", false, upTo, withLabel = true) { onPick() } }
            item(key = CHIP_TABLES) { FilterIcon("🏆", "Tables", effectiveChip == CHIP_TABLES, upTo, withLabel = true) { pickChip(CHIP_TABLES) } }
            // Sports you follow: My Teams for that sport only.
            if (followedSports.size > 1) {
                item(key = "sep1") { ChipGap() }
                items(followedSports, key = { CHIP_SPORT + it.name }) { s -> FilterIcon(s.icon, s.label, effectiveChip == CHIP_SPORT + s.name, upTo) { pickChip(CHIP_SPORT + s.name) } }
            }
        }
    }
    val list: @Composable (Modifier) -> Unit = { m ->
        GameList(
            modifier = m,
            sections = sections,
            columns = columns,
            day = day,
            matches = matches,
            follows = follows,
            settings = settings,
            now = now,
            use24h = use24h,
            loading = loading && events.isEmpty(),
            hasChannels = hasChannels,
            playingKey = playingKey,
            listState = listState,
            firstContent = firstContent,
            requesterFor = requesterFor,
            emptyChip = effectiveChip,
            byDate = leagueView != null,
            anyToday = dayEvents.isNotEmpty(),
            onShowAll = { onChip(CHIP_ALL) },
            onPick = onPick,
            onOpen = onOpen,
            onQuickWatch = onQuickWatch
        )
    }

    // Where your teams stand ("2nd in Premier League", by their rows' names): their leagues' tables.
    val tables by viewModel.repo.standings.collectAsStateWithLifecycle()
    LaunchedEffect(follows.teams, settings.hideScores) { if (!settings.hideScores) viewModel.repo.loadTeamTables() }
    val places = remember(tables, follows, settings.hideScores) {
        if (settings.hideScores) emptyMap()
        else follows.teams.mapNotNull { t -> tables[t.leagueId]?.placeText(t.teamId)?.let { t.key to it } }.toMap()
    }

    // ---- Tables: your leagues' tables
    if (effectiveChip == CHIP_TABLES) {
        TablesPage(
            viewModel = viewModel,
            follows = follows,
            settings = settings,
            now = now,
            use24h = use24h,
            videoOn = videoOn,
            videoSlot = videoSlot,
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveText("Sports", size = 26.sp, weight = FontWeight.Black)
                    Spacer(Modifier.width(14.dp))
                    filters(Modifier.weight(1f))
                }
            },
            request = null,
            focusTable = false,
            onOpen = onOpen,
            onQuickWatch = onQuickWatch
        )
        return
    }

    // ---- My Teams: rows (the next big game, Live & Upcoming, a row per team and league)
    if (rowsPage) {
        val minute = now / 60_000L
        val page = remember(known, follows, minute, rowsSport) { SportsRows.build(known, follows, now, rowsSport) { isMine(it, follows) } }
        Box(Modifier.fillMaxSize()) {
            MyTeamsRows(
                page = page,
                places = places,
                sport = rowsSport,
                follows = follows,
                settings = settings,
                matches = matches,
                now = now,
                use24h = use24h,
                playingKey = playingKey,
                videoOn = videoOn,
                videoSlot = videoSlot,
                header = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveText("Sports", size = 26.sp, weight = FontWeight.Black)
                        Spacer(Modifier.width(14.dp))
                        filters(Modifier.weight(1f))
                        FilterIcon("✎", "Choose Sports", selected = false, withLabel = true) { onPick() }
                    }
                },
                columnState = rowsState,
                rowState = rowState,
                requesterFor = requesterFor,
                lastFocus = lastRowFocus,
                onFocusKey = onRowFocus,
                autoFocus = !focusedOnce || rowsReturning,
                returning = rowsReturning,
                onReturned = { focusedOnce = true; onRowsReturned(); onReturned() },
                jumpToHero = contentJump,
                onOpen = onOpen,
                onQuickWatch = onQuickWatch,
                onPick = onPick
            )
            LiveText(
                if (videoOn) "OK: game info  ·  Hold OK: watch it in the window  ·  ◀ ▶ through a row" else "OK: game info  ·  Hold OK: watch it  ·  ◀ ▶ through a row",
                color = NuvioTheme.colors.TextTertiary, size = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 8.dp)
            )
            statusLine()
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
    if (videoOn) {
        // Stops above the hint line at the bottom, so no game sits behind it.
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp, bottom = 26.dp)) {
            Column(Modifier.width(LIST_WIDTH).fillMaxHeight()) {
                header(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                filters(Modifier.fillMaxWidth())
                list(Modifier.weight(1f))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) {
                videoSlot(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Spacer(Modifier.height(10.dp))
                NowPlayingPanel(events, matches, settings, now, use24h, playingKey, viewModel)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 36.dp, top = 20.dp, bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                header(Modifier.weight(1f))
                filters(Modifier.width(470.dp))
            }
            Spacer(Modifier.height(4.dp))
            list(Modifier.weight(1f))
        }
    }
    LiveText(
        if (videoOn) "OK: game info  ·  Hold OK: watch it in the window" else "OK: game info  ·  Hold OK: watch it",
        color = NuvioTheme.colors.TextTertiary, size = 10.sp,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 8.dp)
    )
    statusLine()
    }
}

@Composable
private fun GameList(
    modifier: Modifier,
    sections: DaySections,
    columns: Int,
    day: Int,
    matches: Map<String, List<ChannelMatch>>,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    loading: Boolean,
    hasChannels: Boolean,
    playingKey: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstContent: FocusRequester,
    requesterFor: (String) -> FocusRequester,
    emptyChip: String,
    byDate: Boolean,
    anyToday: Boolean,
    onShowAll: () -> Unit,
    onPick: () -> Unit,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit
) {
    fun playing(ev: SportsEvent) = playingKey != null && matches[ev.id]?.any { it.channel.key == playingKey } == true
    LazyColumn(
        state = listState,
        // Nothing to the right of the list (the video window isn't a button): Right stays put
        // instead of jumping up to the day tabs.
        modifier = modifier.keepFocusWhileHeld(neverRight = true),
        // Room for the highlight's glow and bigger size at the edges.
        contentPadding = PaddingValues(start = 8.dp, end = 10.dp, top = 2.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasChannels) item(key = "nochannels") {
            LiveText(
                "Add a playlist in Live TV settings to watch games from here. Scores and times still show.",
                color = SportsLook.Dim, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        if (loading) {
            item(key = "ph_label") { SectionHeader("ON NOW", live = true) }
            items(3, key = { "ph_$it" }) { PlaceholderCard() }
            return@LazyColumn
        }
        var firstUsed = false
        fun firstMod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(firstContent) } else Modifier
        fun cardRows(prefix: String, list: List<SportsEvent>) {
            list.chunked(columns).forEachIndexed { ri, row ->
                val rowFirst = if (ri == 0) firstMod() else Modifier
                item(key = prefix + row.first().id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { i, ev ->
                            GameCard(
                                ev = ev,
                                follows = follows,
                                now = now,
                                use24h = use24h,
                                hideScores = settings.hideScores,
                                playing = playing(ev),
                                modifier = Modifier.weight(1f).then(if (i == 0) rowFirst else Modifier).focusRequester(requesterFor(ev.id)),
                                onClick = { onOpen(ev) },
                                onLongClick = { onQuickWatch(ev) }
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (sections.live.isNotEmpty()) {
            item(key = "live_label") { SectionHeader("ON NOW", live = true, count = sections.live.size) }
            cardRows("live_", sections.live)
        }
        if (sections.upcoming.isNotEmpty()) {
            // A league's week: a heading per day ("SATURDAY 24 OCT"); a day: one heading.
            val groups: List<Pair<String, List<SportsEvent>>> =
                if (byDate) sections.upcoming.groupBy { dayWord(it.startMs, now) }.toList()
                else listOf((if (day == 0) "COMING UP" else if (day > 0) "SCHEDULE" else "DIDN'T START") to sections.upcoming)
            groups.forEach { (label, groupList) ->
            item(key = "up_label_$label") { SectionHeader(label.uppercase(), count = groupList.size) }
            groupList.chunked(columns).forEachIndexed { ri, row ->
                val rowFirst = if (ri == 0) firstMod() else Modifier
                item(key = "up_" + row.first().id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { i, ev ->
                            val ms = matches[ev.id].orEmpty()
                            UpcomingRow(
                                ev = ev,
                                channel = ms.firstOrNull()?.channel?.name ?: ev.broadcasts.firstOrNull(),
                                follows = follows,
                                reminded = follows.reminders.any { it.eventId == ev.id },
                                use24h = use24h,
                                playing = playing(ev),
                                modifier = Modifier.weight(1f).then(if (i == 0) rowFirst else Modifier).focusRequester(requesterFor(ev.id)),
                                onClick = { onOpen(ev) },
                                onLongClick = { onQuickWatch(ev) }
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            }
        }
        if (sections.weekLong.isNotEmpty()) {
            item(key = "week_label") { SectionHeader("THIS WEEK", count = sections.weekLong.size) }
            cardRows("week_", sections.weekLong)
        }
        if (sections.finished.isNotEmpty()) {
            item(key = "done_label") { SectionHeader(if (day < 0 || byDate) "RESULTS" else "FINISHED", count = sections.finished.size) }
            cardRows("done_", sections.finished)
        }
        val hasContent = sections.live.isNotEmpty() || sections.upcoming.isNotEmpty() || sections.finished.isNotEmpty() || sections.weekLong.isNotEmpty()
        if (!hasContent) item(key = "empty") {
            EmptyDay(chip = emptyChip, loading = false, anyToday = anyToday, followsEmpty = follows.isEmpty, onShowAll = onShowAll, onPick = onPick)
        }
    }
}

/**
 * Under the video window: the game that's playing (both teams on their bands with the score and
 * clock), its latest scoring, and the channel it's on. A hint when nothing's playing.
 */
@Composable
private fun NowPlayingPanel(
    events: List<SportsEvent>,
    matches: Map<String, List<ChannelMatch>>,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    playingKey: String?,
    viewModel: SportsViewModel
) {
    val ev = remember(playingKey, events, matches) {
        if (playingKey == null) null
        else events.filter { e -> matches[e.id]?.any { it.channel.key == playingKey } == true }
            .sortedBy { if (it.state == GameState.LIVE) 0 else if (it.state == GameState.PRE) 1 else 2 }
            .firstOrNull()
    }
    val match = ev?.let { e -> matches[e.id]?.firstOrNull { it.channel.key == playingKey } }
    if (ev == null) {
        Box(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(14.dp)) {
            LiveText(
                if (playingKey == null) "Hold OK on a game to watch it here while you browse. OK opens the game info."
                else "Playing a channel that isn't showing a game from this page.",
                color = SportsLook.Dim, size = 12.sp, maxLines = 3
            )
        }
        return
    }
    ScoreboardStrip(ev, settings.hideScores, now, use24h, height = 88.dp)
    Spacer(Modifier.height(8.dp))
    val plays = rememberScoringPlays(ev, settings.hideScores, viewModel)
    val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 11.dp, vertical = 9.dp)) {
            LiveText("LATEST SCORING", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            if (plays.isEmpty()) LiveText(if (settings.hideScores) "Scores are hidden" else "No scoring yet", color = SportsLook.Dim, size = 10.sp)
            plays.takeLast(2).reversed().forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OneLine(listOfNotNull(p.clock, p.teamAbbr, p.text).joinToString(" · "), Color.White, 10.sp, modifier = Modifier.weight(1f))
                    if (p.awayScore != null && p.homeScore != null) {
                        LiveText(if (sport.homeFirst) " ${p.homeScore}–${p.awayScore}" else " ${p.awayScore}–${p.homeScore}", size = 10.sp, weight = FontWeight.Black)
                    }
                }
            }
        }
        Column(Modifier.width(132.dp).sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 11.dp, vertical = 9.dp)) {
            LiveText("WATCHING ON", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            OneLine(match?.channel?.name.orEmpty(), Color.White, 11.sp, FontWeight.ExtraBold)
            match?.quality?.let { LiveText(it, color = NuvioTheme.colors.Secondary, size = 9.sp, weight = FontWeight.Black) }
            matches[ev.id]?.firstOrNull { it.channel.key != playingKey }?.let {
                OneLine("Also on ${it.channel.name}", SportsLook.Dim, 9.sp)
            }
        }
    }
}

/** A game's two bands with the status column: the info under the video, the top of the game page. */
@Composable
private fun ScoreboardStrip(
    ev: SportsEvent,
    hideScores: Boolean,
    now: Long,
    use24h: Boolean,
    height: androidx.compose.ui.unit.Dp,
    big: Boolean = false,
    /** The teams can be highlighted: OK on one opens its card. */
    onTeam: ((Side) -> Unit)? = null,
    /** Before the game (the game page): each team's place, form and chances, and the time column. */
    preGame: Map<String, PreGameTeam>? = null,
    extras: GameExtras? = null
) {
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val pre = big && ev.state == GameState.PRE && sides.size == 2
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val wide = maxWidth > 640.dp
    Row(Modifier.fillMaxWidth().height(height).sportsFrame(false, accent = NuvioTheme.colors.Secondary)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (sides.size == 2) {
                val showScore = ev.hasScore && !hideScores
                val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
                val tied = showScore && sides[0].score == sides[1].score
                sides.forEach { s ->
                    TeamBand(
                        side = s, showScore = showScore, dim = showScore && !tied && s != lead, followed = false,
                        modifier = Modifier.weight(1f).then(if (onTeam != null) Modifier.teamSelectable { onTeam(s) } else Modifier),
                        logo = if (big) 44.dp else 30.dp, name = if (big) 19.sp else 13.sp, score = if (big) 34.sp else 22.sp,
                        extra = listOfNotNull(s.record, if (s.home) "Home" else "Away").joinToString(" · "),
                        trailing = preGame?.get(s.team.id)?.takeIf { pre }?.let { t -> @Composable { PreGameTeamStats(t, wide) } }
                    )
                }
            } else {
                EventBand(ev, league, sport, Modifier.fillMaxSize(), big = big)
            }
        }
        if (pre) PreGameTimeColumn(ev, sides, extras, now, use24h, Modifier.width(if (wide) 210.dp else 150.dp))
        else StatusColumn(ev, playing = false, now = now, use24h = use24h, hideScores = hideScores, modifier = Modifier.width(if (big) 150.dp else 124.dp))
    }
    }
}

@Composable
private fun EmptyDay(chip: String, loading: Boolean, anyToday: Boolean, followsEmpty: Boolean, onShowAll: () -> Unit, onPick: () -> Unit) {
    Column(Modifier.padding(top = 24.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveText(
            when {
                loading && !anyToday -> "Getting today's games…"
                chip == CHIP_MINE && followsEmpty -> "You're not following any teams yet."
                chip == CHIP_MINE -> "Nothing from your teams on this day."
                chip == CHIP_LIVE -> "Nothing on right now."
                else -> "Nothing on this day."
            },
            size = 17.sp, weight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chip != CHIP_ALL && anyToday) SportsAction("See Everything", onClick = onShowAll)
            if (chip == CHIP_MINE) SportsAction("⚙  Choose Sports", onClick = onPick)
        }
    }
}

/** A rounded pill button, in the Sports look (Pick Your Teams uses it too). */
@Composable
internal fun SportsPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    @Suppress("UNUSED_PARAMETER") small: Boolean = false,
    onClick: () -> Unit
) = SportsAction(text, modifier, selected, onClick = onClick)

// ------------------------------------------------------------------ the game page

@Composable
private fun GamePage(
    ev: SportsEvent,
    matches: List<ChannelMatch>,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    matching: Boolean,
    hasChannels: Boolean,
    videoOn: Boolean,
    playingKey: String?,
    videoSlot: @Composable (Modifier) -> Unit,
    viewModel: SportsViewModel,
    onChoose: (ChannelMatch) -> Unit,
    onFromStart: (ChannelMatch) -> Unit,
    onFix: () -> Unit,
    /** OK on the Table panel: the league's whole table. */
    onTable: () -> Unit,
    /** A game picked on a team's card. */
    onOpenGame: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit,
    onClose: () -> Unit
) {
    DisposableEffect(ev.id) {
        viewModel.repo.focusEvent(ev.id)
        onDispose { viewModel.repo.focusEvent(null) }
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(ev.id, owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            runCatching { viewModel.repo.loadEventChannels(ev) }
            runCatching { viewModel.repo.loadBroadcasts(ev) }
            runCatching { viewModel.repo.loadEventChannels(ev) }
            while (true) {
                runCatching { viewModel.repo.refreshFreeEvent(ev) }
                delay(90_000)
            }
        }
    }

    var geminiOpen by remember(ev.id) { mutableStateOf(false) }
    var geminiKey by remember(ev.id) { mutableStateOf(false) }
    if (geminiKey) SportsGeminiKeyDialog(viewModel.gemini) { geminiKey = false }
    if (geminiOpen) SportsGeminiLookupDialog(viewModel.gemini, ev, SportsCatalog.regionName(SportsCatalog.regionOf(settings))) { geminiOpen = false }
    BackHandler(onBack = onClose)
    // A team's card: Up to the teams at the top, OK on one.
    var cardTeam by remember(ev.id) { mutableStateOf<TeamInfo?>(null) }
    cardTeam?.let { t ->
        TeamCard(
            team = t, row = null, table = null, viewModel = viewModel, follows = follows, settings = settings,
            now = now, use24h = use24h,
            onOpen = { g -> cardTeam = null; if (g.id != ev.id) onOpenGame(g) },
            onWatch = { g -> cardTeam = null; onQuickWatch(g) },
            onDismiss = { cardTeam = null }
        )
    }
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val showScore = ev.hasScore && !settings.hideScores
    val first = remember(ev.id) { FocusRequester() }
    var askRecord by remember(ev.id) { mutableStateOf(false) }
    if (askRecord) {
        val pad by viewModel.recordPadding.collectAsStateWithLifecycle()
        RecordDialog(
            ev = ev, matches = matches, padding = pad, use24h = use24h, hideScores = settings.hideScores,
            onRecord = { m, minutes -> askRecord = false; LiveTvNotice.show(viewModel.record(ev, m, minutes)) },
            onDismiss = { askRecord = false }
        )
    }
    val firstAction = remember(ev.id) { FocusRequester() }
    val plays = rememberScoringPlays(ev, settings.hideScores, viewModel)
    LaunchedEffect(ev.id) {
        delay(120)
        runCatching { if (matches.isNotEmpty()) first.requestFocus() else firstAction.requestFocus() }
    }

    // More about the game, in the space under it all (see SportsGameExtras), and before it
    // starts, each team's place, form and chances beside its name.
    val extras = rememberGameExtras(ev, viewModel)
    val tables by viewModel.repo.standings.collectAsStateWithLifecycle()
    LaunchedEffect(ev.leagueId, ev.state) {
        if (ev.state == GameState.PRE && !settings.hideScores && league != null && viewModel.repo.mayHaveTable(league))
            runCatching { viewModel.repo.standings(ev.leagueId) }
    }
    val preGame = if (ev.state == GameState.PRE && sides.size == 2 && !settings.hideScores)
        preGameTeams(ev, sides, tables[ev.leagueId], extras) else null

    val top: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            LiveText("‹  Sports", color = SportsLook.Dim, size = 12.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            LiveText(listOfNotNull(league?.name, ev.subtitle?.takeIf { sides.size == 2 }).joinToString(" · "), color = SportsLook.Dim, size = 12.sp)
        }
        ScoreboardStrip(ev, settings.hideScores, now, use24h, height = if (sides.size == 2) 124.dp else 100.dp, big = true, onTeam = { cardTeam = it.team },
            preGame = preGame, extras = extras.takeIf { !settings.hideScores })
    }
    val channels: @Composable () -> Unit = {
        SectionHeader("WATCH ON YOUR CHANNELS")
        when {
            matches.isNotEmpty() -> LazyColumn(
                // Two channels showing (the rest scroll), so the panels underneath stay on screen.
                Modifier.heightIn(max = 126.dp).keepFocusWhileHeld(horizontal = false),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
            ) {
                itemsIndexed(matches, key = { _, m -> m.channel.key }) { i, m ->
                    SportsChannelRow(
                        m = m,
                        finished = ev.state == GameState.POST,
                        playing = videoOn && playingKey == m.channel.key,
                        now = now,
                        eventStartMs = ev.startMs,
                        use24h = use24h,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { onChoose(m) }
                    )
                }
            }
            matching -> LiveText("Looking through your channels…", color = SportsLook.Dim, size = 12.sp)
            !hasChannels -> LiveText("Add a playlist in Live TV settings to watch from here.", color = SportsLook.Dim, size = 12.sp)
            // Too far off to have been looked for yet: say when it will be.
            ev.startMs - System.currentTimeMillis() > SportsMatcher.AHEAD_MS -> LiveText(
                "Your channels are checked for this game from " +
                    java.text.SimpleDateFormat(if (use24h) "EEE HH:mm" else "EEE h:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(ev.startMs - SportsMatcher.AHEAD_MS)) +
                    ". Know where it's on? Pick a channel now and it's remembered.",
                color = SportsLook.Dim, size = 12.sp, maxLines = 3
            )
            else -> LiveText(
                "Not in your channels' guides right now. Guides update during the day, so check back closer to the start. " +
                    "Know where it's on? Pick a channel and it's remembered.",
                color = SportsLook.Dim, size = 12.sp, maxLines = 3
            )
        }
        Spacer(Modifier.height(10.dp))
        // One line (short labels; any that don't fit scroll into view as the highlight moves).
        LazyRow(
            Modifier.fillMaxWidth().keepFocusWhileHeld(vertical = false),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            var firstSet = false
            fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(firstAction) } else Modifier
            val replay = matches.firstOrNull { it.catchup }
            if (replay != null && (ev.state == GameState.LIVE || ev.state == GameState.POST)) {
                val m = f()
                item { SportsAction("⏮  From start", modifier = m, onClick = { onFromStart(replay) }) }
            }
            if (ev.state == GameState.PRE && ev.hasTime) {
                val m = f()
                item {
                    val on = follows.reminders.any { it.eventId == ev.id }
                    SportsAction(if (on) "🔔  Reminder set" else "🔔  Remind me", modifier = m, selected = on, onClick = {
                        viewModel.repo.toggleReminder(ev)
                        LiveTvNotice.show(if (on) "Reminder cancelled" else "We'll remind you before it starts")
                    })
                }
                // With a reminder: switch to the game by itself just before it starts.
                if (follows.reminders.any { it.eventId == ev.id }) {
                    item {
                        val auto = follows.reminders.firstOrNull { it.eventId == ev.id }?.autoSwitch ?: settings.autoSwitch
                        SportsAction(if (auto) "⏩  Auto-switch on" else "⏩  Auto-switch", selected = auto, onClick = {
                            viewModel.repo.setAutoSwitch(ev, !auto)
                            LiveTvNotice.show(if (auto) "It'll ask before switching" else "It'll switch to the game a minute before it starts")
                        })
                    }
                }
            }
            // Record: pick the channel and how long.
            if (ev.hasTime && (ev.state == GameState.PRE || ev.state == GameState.LIVE) && matches.isNotEmpty()) {
                val m = f()
                item {
                    val rec = isRecording(ev)
                    SportsAction(if (rec) "Recording set" else "Record", modifier = m, selected = rec, dot = Color(0xFFE53935), onClick = {
                        if (rec) { viewModel.cancelRecording(ev); LiveTvNotice.show("Recording cancelled") } else askRecord = true
                    })
                }
            }
            sides.forEach { side ->
                val m = f()
                item {
                    val following = follows.followsTeam(ev.leagueId, side.team.id)
                    SportsAction(
                        if (following) "★  ${side.team.name}" else "☆  ${side.team.name}",
                        modifier = m, selected = following,
                        onClick = { viewModel.repo.toggleTeam(side.team) }
                    )
                }
            }
            val m = f()
            item { SportsAction(if (matches.isEmpty()) "Pick a channel" else "Fix channel", modifier = m, onClick = onFix) }
            item { SportsAction("Ask Gemini", onClick = { if (viewModel.gemini.configured()) geminiOpen = true else geminiKey = true }) }
        }
    }
    val details: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (sides.size == 2 && showScore && sides.any { it.periods.isNotEmpty() }) {
                Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    LiveText(periodHeading(sport), color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    PeriodTable(sides)
                }
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 12.dp, vertical = 9.dp)) {
                when {
                    settings.hideScores && ev.state != GameState.PRE -> LiveText("Scores are hidden (Sports settings).", color = SportsLook.Dim, size = 12.sp)
                    ev.standings.isNotEmpty() -> {
                        // A race's finishing order (running order while it's on), a golf leaderboard.
                        LiveText(
                            when {
                                sport == Sport.TENNIS -> "MATCHES"
                                sport == Sport.FIGHTING -> if (ev.state == GameState.PRE) "FIGHT CARD" else "RESULTS"
                                sport == Sport.GOLF -> if (ev.state == GameState.LIVE) "LEADERBOARD" else "FINAL LEADERBOARD"
                                ev.state == GameState.LIVE -> "RUNNING ORDER"
                                else -> "RESULTS"
                            },
                            color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black
                        )
                        Spacer(Modifier.height(2.dp))
                        ev.standings.take(8).forEach { st -> StandingRow(st) }
                    }
                    plays.isNotEmpty() -> {
                        LiveText("LATEST SCORING", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
                        Spacer(Modifier.height(2.dp))
                        plays.takeLast(4).reversed().forEach { p -> PlayRow(p, sport) }
                    }
                    ev.state == GameState.PRE -> {
                        LiveText(
                            SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(ev.startMs)) + " · " + startLabel(ev, use24h),
                            size = 14.sp, weight = FontWeight.Bold
                        )
                        if (ev.broadcasts.isNotEmpty()) LiveText("On TV: " + ev.broadcasts.joinToString(", "), color = SportsLook.Dim, size = 12.sp)
                    }
                    ev.state == GameState.LIVE && !ev.hasScore -> LiveText(
                        "No live score for this event. The time bar runs from the start time.",
                        color = SportsLook.Dim, size = 12.sp, maxLines = 2
                    )
                    else -> LiveText(ev.note ?: ev.subtitle ?: league?.name.orEmpty(), color = SportsLook.Dim, size = 12.sp, maxLines = 3)
                }
                if (ev.source == EventSource.THESPORTSDB) {
                    LiveText("TheSportsDB · limited free coverage" + (ev.updatedAt?.let { " · checked " + formatClock(it, use24h) } ?: ""),
                        color = SportsLook.Dim, size = 11.sp)
                }
                if (ev.broadcastCoverage.isNotEmpty()) LiveText("Reported TV coverage (may be incomplete): " +
                        ev.broadcastCoverage.joinToString { it.name + (it.country?.let { c -> " ($c)" } ?: "") },
                        color = SportsLook.Dim, size = 12.sp)
                // What the show or event is (wrestling: from TheSportsDB).
                ev.about?.let {
                    Spacer(Modifier.height(6.dp))
                    LiveText(it, color = Color.White.copy(alpha = 0.85f), size = 12.sp, maxLines = 5)
                }
            }
        }
    }

    val panels = gameExtrasPanels(ev, extras, settings, onTable = onTable)
    if (videoOn) {
        // Two under the channels, the third under the video and the score.
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp)) {
            Column(Modifier.width(LIST_WIDTH).fillMaxHeight()) {
                top()
                Spacer(Modifier.height(4.dp))
                channels()
                ExtrasRow(panels.take(2), Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, top = 8.dp, bottom = 14.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) {
                videoSlot(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Spacer(Modifier.height(10.dp))
                details()
                ExtrasRow(panels.drop(2), Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 14.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 36.dp, top = 20.dp)) {
            top()
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(LIST_WIDTH)) { channels() }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).padding(top = 12.dp)) { details() }
            }
            // All of them in one row across the bottom.
            ExtrasRow(panels, Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, top = 10.dp, bottom = 14.dp))
        }
    }
}

private fun periodHeading(sport: Sport): String = when (sport) {
    Sport.BASEBALL -> "BY INNING"
    Sport.HOCKEY -> "BY PERIOD"
    Sport.FOOTBALL, Sport.RUGBY, Sport.RUGBY_LEAGUE -> "BY HALF"
    Sport.AMERICAN_FOOTBALL -> "BY QUARTER"
    else -> "BY PERIOD"
}

@Composable
private fun PeriodTable(sides: List<Side>) {
    val n = sides.maxOf { it.periods.size }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            (1..n).forEach { LiveText(if (it > 9) "E$it" else "$it", color = SportsLook.Faint, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(30.dp)) }
            LiveText("T", color = SportsLook.Faint, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        }
        sides.forEach { s ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(SportsLook.Divider))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(teamColor(s.team)))
                Spacer(Modifier.width(7.dp))
                LiveText(s.team.name, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                (0 until n).forEach { i -> LiveText(s.periods.getOrNull(i) ?: "–", size = 12.sp, modifier = Modifier.width(30.dp)) }
                LiveText(s.score.orEmpty(), size = 13.sp, weight = FontWeight.Black, modifier = Modifier.width(30.dp))
            }
        }
    }
}

@Composable
private fun StandingRow(st: Standing) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SportsLook.Divider))
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(
            st.pos, size = 11.sp, weight = FontWeight.Black,
            color = if (st.pos == "1") Color(0xFFFFCE54) else Color.White, modifier = Modifier.width(28.dp)
        )
        OneLine(st.name, Color.White, 12.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
        st.detail?.let { LiveText(it, color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold) }
    }
}

@Composable
private fun PlayRow(p: ScoringPlay, sport: Sport) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SportsLook.Divider))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(p.clock, color = SportsLook.Dim, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(64.dp))
        p.teamAbbr?.let { LiveText("$it  ", size = 11.sp, weight = FontWeight.Black) }
        OneLine(p.text, Color.White, 11.sp, modifier = Modifier.weight(1f))
        if (p.awayScore != null && p.homeScore != null) {
            LiveText(
                if (sport.homeFirst) "  ${p.homeScore} – ${p.awayScore}" else "  ${p.awayScore} – ${p.homeScore}",
                size = 11.sp, weight = FontWeight.Black
            )
        }
    }
}

// ------------------------------------------------------------------ fixing the channel

@Composable
private fun FixChannelDialog(ev: SportsEvent, matches: List<ChannelMatch>, hideScores: Boolean, viewModel: SportsViewModel, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(matches.isEmpty()) }
    var chosen by remember { mutableStateOf<LiveChannel?>(null) }
    val pick = chosen
    if (pick != null) {
        val networks = ev.broadcasts
        val league = SportsCatalog.league(ev.leagueId)
        val teams = ev.ordered(league?.sport ?: Sport.OTHER).map { it.team }
        if (networks.isEmpty() && teams.isEmpty() && league?.kind != LeagueKind.EVENT) {
            LaunchedEffect(pick.key) {
                viewModel.repo.pickChannel(ev, pick.key, forNetworks = false)
                LiveTvNotice.show("Saved: ${ev.title} on ${pick.name}")
                onDismiss()
            }
            return
        }
        val first = remember { FocusRequester() }
        SportsDialog(onDismiss = onDismiss, width = 520.dp, header = { GameHeaderBands(ev, hideScores) }) {
            LiveText("Use ${pick.name} for more games?", size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
            LiveText(
                (if (networks.isNotEmpty()) "${ev.title} is on ${networks.joinToString(", ")}. " else "") +
                    "Pick a network or a team, and their games from now on use ${pick.name} too (a team's own channel, say).",
                color = SportsLook.Dim, size = 12.sp, maxLines = 3
            )
            Spacer(Modifier.height(10.dp))
            var firstUsed = false
            fun firstMod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            networks.firstOrNull()?.let { n ->
                SportsMenuRow("Yes, every $n game", firstMod()) {
                    viewModel.repo.pickChannel(ev, pick.key, forNetworks = true)
                    LiveTvNotice.show("Saved: $n games use ${pick.name}")
                    onDismiss()
                }
            }
            // A race series, a golf tour, a fight promotion: every event of it on this channel.
            if (league?.kind == LeagueKind.EVENT) {
                SportsMenuRow("Yes, every ${league.name} event", firstMod()) {
                    viewModel.repo.pickChannel(ev, pick.key, forNetworks = false, forLeague = true)
                    LiveTvNotice.show("Saved: ${league.name} events use ${pick.name}")
                    onDismiss()
                }
            }
            teams.forEach { t ->
                SportsMenuRow("Yes, every ${t.name} game", firstMod()) {
                    viewModel.repo.pickChannel(ev, pick.key, forNetworks = false, forTeam = t)
                    LiveTvNotice.show("Saved: ${t.name} games use ${pick.name}")
                    onDismiss()
                }
            }
            SportsMenuRow("Just this game", firstMod()) {
                viewModel.repo.pickChannel(ev, pick.key, forNetworks = false)
                LiveTvNotice.show("Saved: ${ev.title} on ${pick.name}")
                onDismiss()
            }
        }
        LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
        return
    }
    if (picking) {
        ChannelPickerDialog(
            title = "Pick the channel for ${ev.title}",
            initial = "",
            viewModel = viewModel,
            onPick = { chosen = it },
            onDismiss = onDismiss
        )
        return
    }
    val first = remember { FocusRequester() }
    SportsDialog(onDismiss = onDismiss, width = 560.dp, header = { GameHeaderBands(ev, hideScores) }) {
        LiveText("Fix the channel", size = 18.sp, weight = FontWeight.Bold)
        LiveText("Take wrong channels off the list, or pick the right one.", color = SportsLook.Dim, size = 12.sp, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 260.dp).keepFocusWhileHeld(horizontal = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(matches, key = { _, m -> m.channel.key }) { i, m ->
                ChannelLine(
                    channel = m.channel,
                    detail = "Not this channel",
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = {
                        viewModel.repo.blockChannel(ev, m.channel.key)
                        LiveTvNotice.show("${m.channel.name} won't be used for this game")
                    }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SportsMenuRow("Pick the right channel…") { picking = true }
        SportsMenuRow("Done") { onDismiss() }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

/** A channel in a Sports pop-up: logo, name, and a note on the right; white when highlighted. */
@Composable
private fun ChannelLine(channel: LiveChannel, detail: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(SportsLook.SmallShape)
            .background(if (focused) NuvioTheme.colors.FocusBackground.copy(alpha = 0.25f) else Color.Transparent)
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent), SportsLook.SmallShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(channel.logo, 22.dp)
        Spacer(Modifier.width(10.dp))
        LiveText(channel.name, color = Color.White, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        LiveText(detail, color = SportsLook.Dim, size = 11.sp)
    }
}

/** Type part of a channel's name; OK on one picks it. */
@Composable
private fun ChannelPickerDialog(
    title: String,
    initial: String,
    viewModel: SportsViewModel,
    onPick: (LiveChannel) -> Unit,
    onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf(initial) }
    val all = remember { viewModel.repo.channelsForPicker() }
    val list = remember(q, all) {
        val n = SportsCatalog.norm(q).trim()
        if (n.length < 2) emptyList()
        else all.asSequence().filter { SportsCatalog.norm(it.name).contains(n) }.take(80).toList()
    }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    SportsDialog(onDismiss = onDismiss, width = 640.dp) {
        LiveText(title, size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        // ✓ on the keyboard: down to the channels found (no need to press Back first).
        LiveTextField(
            value = q, onValueChange = { q = it }, hint = "Type part of the channel's name", focusRequester = field,
            onDone = {
                keyboard?.hide()
                if (list.isNotEmpty()) runCatching { firstResult.requestFocus() }
            }
        )
        Spacer(Modifier.height(8.dp))
        if (q.trim().length >= 2 && list.isEmpty()) {
            LiveText("No channel names match.", color = SportsLook.Dim, size = 12.sp)
        }
        LazyColumn(Modifier.heightIn(max = 330.dp).keepFocusWhileHeld(horizontal = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(list, key = { _, ch -> ch.key }) { i, ch ->
                ChannelLine(channel = ch, detail = listOf(ch.sourceName, ch.group).filter { it.isNotBlank() }.joinToString(" · "), modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier, onClick = { onPick(ch) })
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
}

/** "1:00 PM" etc., also used by the picker and host. */
internal fun clock(ms: Long, use24h: Boolean): String = formatClock(ms, use24h)

/** The Sports page's sections for the day on screen. */
private class DaySections(
    val weekLong: List<SportsEvent>,
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>,
    val finished: List<SportsEvent>,
    val liveCount: Int
)

/**
 * A game's scoring plays, kept up to date: asked again when the score changes, and every 30
 * seconds while it's on. (ESPN's play list can lag a little behind the score, so asking only
 * when the score changed could leave the newest goal out until the next one.)
 */
@Composable
private fun rememberScoringPlays(ev: SportsEvent, hideScores: Boolean, viewModel: SportsViewModel): List<ScoringPlay> {
    var plays by remember(ev.id) { mutableStateOf<List<ScoringPlay>>(emptyList()) }
    val latest = androidx.compose.runtime.rememberUpdatedState(ev)
    LaunchedEffect(ev.id, ev.state, ev.away?.score, ev.home?.score, hideScores) {
        if (hideScores) return@LaunchedEffect
        while (true) {
            val e = latest.value
            if (e.state != GameState.PRE) plays = viewModel.plays(e)
            if (e.state != GameState.LIVE) break
            delay(30_000)
        }
    }
    return plays
}
