package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvRepository
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.matchFrameRate
import com.nuvio.tv.livetv.player.LiveTrackOption
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayerDialog { NONE, OPTIONS, AUDIO, SUBTITLES, SCREEN_SIZE, STREAM_INFO, SLEEP }

/** The picture size saved in settings, as one of Nuvio's own player modes. */
internal fun aspectModeOf(name: String): com.nuvio.tv.ui.screens.player.AspectMode =
    com.nuvio.tv.ui.screens.player.AspectMode.entries.firstOrNull { it.name == name }
        ?: com.nuvio.tv.ui.screens.player.AspectMode.ORIGINAL

@Composable
fun LiveTvPlayerScreen(
    onBack: () -> Unit,
    onFindInNuvio: () -> Unit = {},
    /** Overlay mode off: Left goes back to the guide with the group list open. */
    onBackToGroups: () -> Unit = onBack,
    /**
     * Shown inside the guide (the guide draws the video, the same view as its preview, so the
     * picture never has to move to another view). This screen then only draws the controls.
     */
    embedded: Boolean = false,
    /** The gear panel's "Settings" link (All Live TV settings). */
    onOpenSettings: () -> Unit = {},
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val pictureShapes by viewModel.pictureShapes.collectAsStateWithLifecycle()
    val pictureShape = com.nuvio.tv.livetv.player.PictureShape.of(playback.channelKey?.let { pictureShapes[it] })
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    if (!embedded) PauseLiveTvInBackground(viewModel.playback)

    DisposableEffect(Unit) {
        viewModel.playback.attach()
        onDispose { viewModel.playback.detach() }
    }
    KeepScreenAwake(true)

    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var bannerVisible by remember { mutableStateOf(true) }
    var bannerToken by remember { mutableIntStateOf(0) }
    var listVisible by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(PlayerDialog.NONE) }
    // Which page the ⚙ panel opens on ("" = the main one; "audio", "subs", "sleep", "size", "info").
    var gearStart by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    var numberBuffer by remember { mutableStateOf("") }
    var longPressFired by remember { mutableStateOf(false) }
    var okHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Follows the channel list as it loads (it was read once before, so a list that was still
    // loading left full screen stuck on "Nothing playing"). Falls back to the player's own
    // channel, so full screen always knows what it's showing.
    val displayList by viewModel.displayChannels.collectAsStateWithLifecycle()
    val current: LiveChannel? = remember(displayList, playback.channelKey) {
        playback.channelKey?.let { k -> displayList.firstOrNull { it.key == k } }
            ?: viewModel.playback.playingChannel?.takeIf { it.key == playback.channelKey }
    }

    fun showBanner() { bannerVisible = true; bannerToken++ }

    // The bottom panel's controls (Down while it's showing): which one is highlighted.
    var controlFocus by remember { mutableStateOf<PanelControl?>(null) }
    /** The highlighted tile in the full panel's Recent row (0 TV Guide, 1… channels, then Clear), or null. */
    var recentFocus by remember { mutableStateOf<Int?>(null) }
    // TV Guide tile: opened when OK is let go, so the key's release doesn't land on the guide
    // (where it opened the channel full screen again, and the guide only flashed up).
    var guideOnOkUp by remember { mutableStateOf(false) }
    // Holding Right (live): back to the channel you were watching before.
    var rightDownAt by remember { mutableStateOf(0L) }
    var rightHoldDone by remember { mutableStateOf(false) }
    // The full panel (OK): timeline, buttons and options. Changing channels shows the compact one.
    var panelFull by remember { mutableStateOf(false) }
    // Opened with the bar hidden (a long press in full screen): the full bar comes up with the
    // panel, so changes in "Play bar layout" can be seen, and goes away again after.
    var gearShowedBar by remember { mutableStateOf(false) }
    fun openGear(start: String = "") {
        gearShowedBar = !(bannerVisible || panelFull || playback.userPaused)
        if (gearShowedBar) { showBanner(); panelFull = true }
        gearStart = start
        dialog = PlayerDialog.OPTIONS
    }
    fun closeFull() { panelFull = false; controlFocus = null; recentFocus = null }
    LaunchedEffect(dialog) {
        if (dialog == PlayerDialog.NONE && gearShowedBar) { gearShowedBar = false; closeFull(); bannerVisible = false }
    }

    // Channel surfing (Settings → Live TV → Watching): the channel being looked at on the info
    // bar while the one playing carries on, or null when not browsing.
    var browseKey by remember { mutableStateOf<String?>(null) }
    var lastBrowseStepAt by remember { mutableStateOf(0L) }
    // OK picked a channel: its release mustn't also open the full panel.
    var swallowOkUp by remember { mutableStateOf(false) }
    LaunchedEffect(bannerVisible) { if (!bannerVisible) browseKey = null }
    LaunchedEffect(playback.channelKey) { browseKey = null }
    /** One step while browsing; held, at a steady pace (4 a second, then 8 after 2 seconds). */
    fun browseStep(direction: Int, e: androidx.compose.ui.input.key.KeyEvent) {
        val n = e.nativeKeyEvent
        if (n.repeatCount > 0) {
            val held = n.eventTime - n.downTime
            val gap = if (held < 2_000) 250L else 125L
            if (n.eventTime - lastBrowseStepAt < gap) return
        }
        lastBrowseStepAt = n.eventTime
        viewModel.zap(browseKey ?: playback.channelKey, direction)?.let { browseKey = it.key }
        showBanner()
    }
    val shiftStartedAt by viewModel.playback.shiftStartedAt.collectAsStateWithLifecycle()
    var captionsOn by remember { mutableStateOf(false) }
    LaunchedEffect(playback.channelKey, bannerVisible) { captionsOn = viewModel.playback.captionsOn() }

    // Picture quality (for the guide's badges) and frame rate (for Match frame rate).
    var videoFps by remember { mutableStateOf(-1f) }
    // For the info bar: this channel's frame rate (not the last channel's while it starts).
    var shownFps by remember { mutableStateOf(-1f) }
    LaunchedEffect(playback.channelKey, playback.catchupTitle) {
        shownFps = -1f
        while (true) {
            val p = viewModel.playback.player
            val f = p?.videoFormat
            if (f != null) {
                if (playback.catchupTitle == null) playback.channelKey?.let { viewModel.recordQuality(it, f.height) }
                // The stream's stated frame rate, else the one measured from its frames (many
                // live streams don't state it, so the display was never switched).
                val fps = if (f.frameRate > 0f) f.frameRate else viewModel.playback.measuredFps
                if (fps > 0f) { videoFps = fps; shownFps = fps }
            }
            // Match Frame Rate switches the display itself: the player's own frame rate hint to
            // the screen is turned off then, so the two don't pull against each other.
            runCatching {
                p?.setVideoChangeFrameRateStrategy(
                    if (settings.matchFrameRate) androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                    else androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                )
            }
            delay(3_000)
        }
    }
    MatchFrameRate(enabled = settings.matchFrameRate, fps = videoFps, restore = settings.frameRateMode == "START_STOP")

    // Sleep timer: the controller stops playback (on any screen); full screen then closes.
    val sleepAt by viewModel.sleepAtMs.collectAsStateWithLifecycle()
    val sleepFired by viewModel.playback.sleepFired.collectAsStateWithLifecycle()
    val sleepFiredAtStart = remember { viewModel.playback.sleepFired.value }
    LaunchedEffect(sleepFired) {
        if (sleepFired != sleepFiredAtStart) onBack()
    }

    // Catch-up seeking: Left/Right move a target time; the seek happens once you stop pressing.
    val catchupSession by viewModel.catchup.collectAsStateWithLifecycle()
    var scrubTargetMs by remember { mutableStateOf<Long?>(null) }
    var scrubToken by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(playback.catchupTitle, catchupSession) {
        while (playback.catchupTitle != null && catchupSession != null) {
            viewModel.followCatchup()
            viewModel.catchupPositionMs()?.let { positionMs = it }
            delay(500)
        }
    }
    LaunchedEffect(scrubToken) {
        val target = scrubTargetMs ?: return@LaunchedEffect
        delay(700)
        viewModel.seekCatchupTo(target)
        positionMs = target
        scrubTargetMs = null
    }
    fun scrub(forward: Boolean, repeat: Int) {
        val s = catchupSession ?: return
        val step = when {
            repeat > 20 -> 300_000L
            repeat > 8 -> 120_000L
            else -> 30_000L
        }
        val length = s.program.stopMs - s.program.startMs
        val from = scrubTargetMs ?: positionMs
        scrubTargetMs = (from + if (forward) step else -step).coerceIn(0L, length)
        scrubToken++
        showBanner()
    }

    LaunchedEffect(bannerToken, settings.infoBannerSeconds) {
        delay(settings.infoBannerSeconds.coerceAtLeast(2) * 1000L)
        bannerVisible = false
    }
    LaunchedEffect(playback.channelKey) { showBanner() }
    LaunchedEffect(toast) { toast?.let { t -> delay(if (t.length > 40) 4_000 else 2_000); toast = null } }
    LaunchedEffect(Unit) { delay(100); runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isEmpty()) return@LaunchedEffect
        delay(1_300)
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        n?.let { viewModel.channelByNumber(it) }?.let { viewModel.preview(it) } ?: run { if (n != null) toast = "No channel $n" }
    }
    LaunchedEffect(listVisible) {
        if (!listVisible) {
            // Overlay mode took the focus with it when it closed: take it back, trying a few
            // times (once isn't always enough), or the remote does nothing until you press OK.
            for (wait in longArrayOf(60, 150, 300, 600, 1000)) {
                delay(wait)
                if (dialog != PlayerDialog.NONE) break
                runCatching { rootFocus.requestFocus() }
            }
        }
    }

    fun zap(direction: Int) {
        viewModel.zap(playback.channelKey, direction)?.let { viewModel.preview(it) }
    }

    // What is on now, with artwork supplied by the EPG.
    val nowProgram = rememberShowDetails(current?.key, current?.let { ch -> programs[ch.key]?.firstOrNull { now >= it.startMs && now < it.stopMs } })
    val watchingTitle = playback.catchupTitle ?: nowProgram?.title
    val poster by androidx.compose.runtime.produceState<String?>(initialValue = null, watchingTitle, current?.key) {
        value = null
        // In archive playback the guide entry isn't the live one, so no movie/series hint.
        val hintProgram = if (playback.catchupTitle == null) nowProgram else null
        value = viewModel.posterFor(hintProgram, current)
    }

    BackHandler(enabled = listVisible) { listVisible = false }

    CompositionLocalProvider(LocalLiveSolidHighlight provides settings.solidHighlight) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (embedded) Color.Transparent else Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (listVisible || dialog != PlayerDialog.NONE) return@onPreviewKeyEvent false
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                // ---- channel surfing: OK watches the channel on the bar, Back stays put
                if (swallowOkUp && isOk) {
                    if (e.type == KeyEventType.KeyUp) swallowOkUp = false
                    return@onPreviewKeyEvent true
                }
                val bk = browseKey
                if (bk != null && controlFocus == null && e.type == KeyEventType.KeyDown) {
                    if (isOk && e.nativeKeyEvent.repeatCount == 0) {
                        browseKey = null
                        swallowOkUp = true
                        viewModel.channelByKey(bk)?.takeIf { it.key != playback.channelKey }?.let { viewModel.preview(it) }
                        showBanner()
                        return@onPreviewKeyEvent true
                    }
                    if (e.key == Key.Back) {
                        (context as? com.nuvio.tv.MainActivity)?.longPressBackHeld?.value = true
                        browseKey = null
                        bannerVisible = false
                        return@onPreviewKeyEvent true
                    }
                }
                val archive = playback.catchupTitle != null
                val shifting = shiftStartedAt != null
                // ---- the remote's Fast Forward and Rewind: 30 seconds on (or back) in catch-up
                // and in Rewind Anytime, wherever the highlight is.
                val mediaCode = e.nativeKeyEvent.keyCode
                if (mediaCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD || mediaCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND ||
                    mediaCode == AndroidKeyEvent.KEYCODE_MEDIA_SKIP_FORWARD || mediaCode == AndroidKeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
                ) {
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val forward = mediaCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD || mediaCode == AndroidKeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
                    when {
                        // Each press 30 seconds; held, faster (as with ◀ / ▶).
                        archive -> scrub(forward = forward, repeat = e.nativeKeyEvent.repeatCount)
                        shifting && viewModel.playback.shiftCanSeek() -> {
                            viewModel.playback.shiftSeekBy(if (forward) 30_000L else -30_000L); showBanner()
                        }
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                // ---- the Recent row (Down from the panel's buttons)
                val rf = recentFocus
                if (rf != null && controlFocus != null) {
                    if (e.type == KeyEventType.KeyUp) {
                        if (isOk && guideOnOkUp) {
                            guideOnOkUp = false
                            closeFull()
                            onBack()
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent isOk || e.key == Key.Back
                    }
                    showBanner()
                    val recentList = viewModel.panelRowChannels(playback.channelKey)
                    // Clear is only in Recent (favorites are cleared in the guide).
                    val hasClear = settings.panelRow == "RECENT"
                    val lastTile = recentList.size + if (hasClear) 1 else 0
                    when {
                        e.key == Key.DirectionLeft -> recentFocus = (rf - 1).coerceAtLeast(0)
                        e.key == Key.DirectionRight -> recentFocus = (rf + 1).coerceAtMost(lastTile)
                        e.key == Key.DirectionUp -> recentFocus = null
                        e.key == Key.DirectionDown -> Unit
                        e.key == Key.Back -> {
                            (context as? com.nuvio.tv.MainActivity)?.longPressBackHeld?.value = true
                            closeFull(); bannerVisible = false
                        }
                        isOk && e.nativeKeyEvent.repeatCount == 0 -> when {
                            // TV Guide: back to the guide.
                            rf == 0 -> guideOnOkUp = true
                            hasClear && rf >= lastTile -> {
                                viewModel.clearRecent()
                                recentFocus = 0
                                toast = "Recently watched cleared"
                            }
                            else -> recentList.getOrNull(rf - 1)?.let { ch ->
                                closeFull()
                                viewModel.preview(ch)
                                showBanner()
                            }
                        }
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                // ---- the panel's controls (entered with Down while the panel shows)
                val cf = controlFocus
                if (cf != null) {
                    val mode = if (archive) PanelMode.CATCHUP else if (shifting) PanelMode.SHIFT else PanelMode.LIVE
                    val paused = playback.userPaused
                    val list = panelControls(mode, paused, canSkip = archive || viewModel.playback.shiftCanSeek())
                    if (e.type == KeyEventType.KeyUp) return@onPreviewKeyEvent isOk || e.key == Key.Back
                    showBanner()
                    val buttons = list.filter { it != PanelControl.TIMELINE }
                    val onTimeline = cf == PanelControl.TIMELINE && PanelControl.TIMELINE in list
                    val i = buttons.indexOf(cf).coerceAtLeast(0)
                    when {
                        // The timeline, grabbed: ◀ / ▶ move through the recording or show (faster
                        // the longer you hold), OK plays/pauses, Down back to the buttons.
                        onTimeline && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) -> {
                            val forward = e.key == Key.DirectionRight
                            if (archive) scrub(forward = forward, repeat = e.nativeKeyEvent.repeatCount)
                            else {
                                val r = e.nativeKeyEvent.repeatCount
                                val step = when { r > 20 -> 120_000L; r > 8 -> 60_000L; r > 2 -> 30_000L; else -> 10_000L }
                                viewModel.playback.shiftSeekBy(if (forward) step else -step)
                            }
                            return@onPreviewKeyEvent true
                        }
                        onTimeline && e.key == Key.DirectionDown -> { controlFocus = PanelControl.PLAY; return@onPreviewKeyEvent true }
                        onTimeline && e.key == Key.DirectionUp -> return@onPreviewKeyEvent true
                        onTimeline && isOk -> {
                            if (e.nativeKeyEvent.repeatCount == 0) viewModel.playback.togglePause()
                            return@onPreviewKeyEvent true
                        }
                        e.key == Key.DirectionLeft -> { controlFocus = buttons[(i - 1).coerceAtLeast(0)]; return@onPreviewKeyEvent true }
                        e.key == Key.DirectionRight -> { controlFocus = buttons[(i + 1).coerceAtMost(buttons.lastIndex)]; return@onPreviewKeyEvent true }
                        e.key == Key.DirectionUp -> {
                            // Up from the buttons: grab the timeline (when it can be moved).
                            if (PanelControl.TIMELINE in list) controlFocus = PanelControl.TIMELINE
                            return@onPreviewKeyEvent true
                        }
                        e.key == Key.Back -> {
                            (context as? com.nuvio.tv.MainActivity)?.longPressBackHeld?.value = true
                            closeFull(); bannerVisible = false; return@onPreviewKeyEvent true
                        }
                        e.key == Key.DirectionDown -> {
                            // Down from the buttons: into the Recent row (first channel, else TV Guide).
                            if (settings.panelRow != "OFF" && panelFull) {
                                recentFocus = if (viewModel.panelRowChannels(playback.channelKey).isNotEmpty()) 1 else 0
                            }
                            return@onPreviewKeyEvent true
                        }
                        isOk -> {
                            if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                            when (buttons.getOrNull(i)) {
                                PanelControl.BACK -> if (archive) scrub(forward = false, repeat = 0) else viewModel.playback.shiftSeekBy(-30_000)
                                PanelControl.FORWARD -> if (archive) scrub(forward = true, repeat = 0) else viewModel.playback.shiftSeekBy(30_000)
                                PanelControl.PLAY -> viewModel.playback.togglePause()
                                PanelControl.LIVE -> {
                                    if (archive) current?.let { viewModel.backToLive(it) } else viewModel.playback.goLive()
                                    controlFocus = PanelControl.PLAY
                                }
                                PanelControl.CC -> {
                                    captionsOn = viewModel.playback.toggleCaptions()
                                    toast = if (captionsOn) "Captions on" else if (viewModel.playback.subtitleTracks().isEmpty()) "No captions on this channel" else "Captions off"
                                }
                                PanelControl.AUDIO -> openGear("audio")
                                PanelControl.SIZE -> openGear("size")
                                PanelControl.SLEEP -> openGear("sleep")
                                PanelControl.MORE -> openGear()
                                PanelControl.TIMELINE, null -> Unit
                            }
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                if (isOk) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            // A new press: forget a long press whose release went to a dialog.
                            longPressFired = false
                            // Long press by time held too (remotes that never repeat, like CEC).
                            okHoldJob?.cancel()
                            okHoldJob = scope.launch {
                                delay(LONG_PRESS_MS)
                                if (!longPressFired) { longPressFired = true; openGear() }
                            }
                        } else if (!longPressFired) {
                            okHoldJob?.cancel()
                            longPressFired = true
                            openGear()
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyUp) {
                        okHoldJob?.cancel()
                        val heldMs = e.nativeKeyEvent.eventTime - e.nativeKeyEvent.downTime
                        if (longPressFired) {
                            longPressFired = false
                        } else if (heldMs >= LONG_PRESS_MS) {
                            openGear()
                        } else when {
                            playback.error != null && playback.reconnectAttempt > 8 -> viewModel.playback.retry()
                            // OK: the full panel (timeline, buttons, options), on Play/Pause.
                            current != null -> { panelFull = true; controlFocus = PanelControl.PLAY; showBanner() }
                            else -> showBanner()
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when {
                    e.key == Key.DirectionUp || e.key == Key.ChannelUp -> {
                        if (settings.channelSurf && current != null) browseStep(1, e) else zap(1); true
                    }
                    e.key == Key.DirectionDown || e.key == Key.ChannelDown -> {
                        if (settings.channelSurf && current != null) browseStep(-1, e) else zap(-1); true
                    }
                    e.key == Key.DirectionLeft -> {
                        when {
                            archive -> scrub(forward = false, repeat = e.nativeKeyEvent.repeatCount)
                            // Skips back in the buffer (with Rewind Anytime: once the panel is up
                            // or you're already behind live; otherwise Left keeps its usual job).
                            shifting && viewModel.playback.shiftCanSeek() && (bannerVisible || viewModel.playback.shiftIsBehind()) -> {
                                viewModel.playback.shiftSeekBy(-10_000L * (1 + minOf(e.nativeKeyEvent.repeatCount, 11))); showBanner()
                            }
                            settings.overlayMode -> listVisible = true
                            else -> { viewModel.requestGroupsOnReturn(); onBackToGroups() }
                        }
                        true
                    }
                    e.key == Key.DirectionRight -> {
                        when {
                            archive -> scrub(forward = true, repeat = e.nativeKeyEvent.repeatCount)
                            shifting && viewModel.playback.shiftCanSeek() && viewModel.playback.shiftIsBehind() -> {
                                viewModel.playback.shiftSeekBy(10_000L * (1 + minOf(e.nativeKeyEvent.repeatCount, 11))); showBanner()
                            }
                            e.nativeKeyEvent.repeatCount == 0 -> {
                                rightDownAt = System.currentTimeMillis()
                                rightHoldDone = false
                                showBanner()
                            }
                            // Held for over half a second: the previous channel (once per press).
                            !rightHoldDone && System.currentTimeMillis() - rightDownAt >= 600 -> {
                                rightHoldDone = true
                                val prev = viewModel.previousChannel()?.takeIf { it.key != playback.channelKey }
                                if (prev != null) {
                                    browseKey = null
                                    viewModel.preview(prev)
                                    showBanner()
                                } else toast = "No previous channel yet"
                            }
                            else -> Unit
                        }
                        true
                    }
                    e.key == Key.Info -> { showBanner(); true }
                    e.key == Key.Menu -> { openGear(); true }
                    e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause -> {
                        viewModel.playback.togglePause(); true
                    }
                    code == AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> {
                        viewModel.previousChannel()?.let { viewModel.preview(it) }
                        true
                    }
                    code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        if (numberBuffer.length < 5) numberBuffer += (code - AndroidKeyEvent.KEYCODE_0).toString()
                        true
                    }
                    e.key == Key.Back -> {
                        // Swallow the rest of this Back press (held repeats and letting go), so
                        // holding Back here can't reach the guide and bounce back to full screen.
                        (context as? com.nuvio.tv.MainActivity)?.longPressBackHeld?.value = true
                        // The info bar is up: Back just closes it (a second Back leaves). Before,
                        // Back always left full screen, so the bar could only be waited out.
                        if (bannerVisible && e.nativeKeyEvent.repeatCount == 0) bannerVisible = false
                        else onBack()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        if (!embedded) LivePlayerSurface(
            player = viewModel.playback.player,
            modifier = Modifier.fillMaxSize(),
            useSurfaceView = true,
            onAttached = { viewModel.playback.onSurfaceAttached() },
            aspectMode = aspectModeOf(settings.aspectMode),
            pictureShape = pictureShape
        )

        // Avoid flashing the buffering indicator during brief channel transitions.
        val slowLoading = rememberDelayedTrue(playback.isBuffering, 1_200)
        val recStarting by viewModel.playback.recordingStarting.collectAsStateWithLifecycle()
        val centerMessage = when {
            current == null -> "Nothing playing"
            playback.error != null -> null // shown with its reason below
            recStarting -> "Starting the recording…"
            else -> null
        }
        if (slowLoading && playback.error == null && !recStarting) {
            LiveText("Buffering…", size = 13.sp, color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp))
        }
        // A problem: what's happening, and why underneath (wrapped, so it isn't cut off).
        playback.error?.let { reason ->
            PlayerStatusMessage(
                title = if (playback.reconnectAttempt > 8) "Stream unavailable · press OK to retry"
                    else "Reconnecting… (attempt ${playback.reconnectAttempt})",
                detail = reason,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        centerMessage?.let {
            LiveText(
                it,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                size = 18.sp
            )
        }

        // The bottom panel: poster, the show, its timeline (live, catch-up or a pause-and-rewind
        // recording), playback buttons and quick options. Stays up while paused.
        // Only when you paused it (not while changing channel, loading or reconnecting).
        val pausedNow = playback.userPaused && current != null && playback.error == null
        // The full panel closes by itself after a while without a button press (not while paused,
        // and not while the gear panel or another dialog is open over it).
        LaunchedEffect(bannerToken, panelFull, pausedNow, dialog) {
            if (panelFull && !pausedNow && dialog == PlayerDialog.NONE) { delay(10_000); closeFull(); bannerVisible = false }
        }
        // The always-on corner clock, out of the way while the panel, channel list or a menu is
        // up (the panel shows the time itself).
        OnScreenClock(
            settings = settings,
            visible = current != null && !(bannerVisible || panelFull || pausedNow) && !listVisible &&
                dialog == PlayerDialog.NONE
        )
        // ---- channel surfing: the bar for the channel being looked at
        val browseChannel = browseKey?.let { viewModel.channelByKey(it) }
        // Details (poster, description) once you stop on a channel, not for every step.
        var settledBrowseKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(browseKey) {
            settledBrowseKey = null
            if (browseKey != null) { delay(450); settledBrowseKey = browseKey }
        }
        val browseProgram = browseChannel?.let { c -> programs[c.key]?.firstOrNull { now >= it.startMs && now < it.stopMs } }
        val browseSettled = browseChannel != null && settledBrowseKey == browseChannel.key
        val browseDetails = rememberShowDetails(
            if (browseSettled) browseChannel?.key else null,
            if (browseSettled) browseProgram else null
        )
        val browsePoster by androidx.compose.runtime.produceState<String?>(initialValue = null, settledBrowseKey, browseProgram?.title) {
            value = null
            val c = browseChannel?.takeIf { settledBrowseKey == it.key } ?: return@produceState
            value = viewModel.posterFor(browseProgram, c)
        }
        if (browseChannel != null && bannerVisible) {
            // What's actually playing, top right.
            current?.let { playingCh ->
                LiveText(
                    "●  Now watching: " + (if (settings.showChannelNumbers) "${playingCh.number} · " else "") + playingCh.name,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    size = 13.sp
                )
            }
        }
        AnimatedVisibility(
            visible = browseChannel != null && bannerVisible && !panelFull,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            browseChannel?.let { b ->
                val use24h = settings.use24HourClock
                var tick by remember { mutableStateOf(System.currentTimeMillis()) }
                // Live only (minutes left, a time bar over an hour or so): every 5 seconds is plenty,
                // and redraws the panel a fifth as often on slower boxes.
                LaunchedEffect(Unit) { while (true) { delay(5_000L - System.currentTimeMillis() % 5_000L); tick = System.currentTimeMillis() } }
                val shown = if (browseSettled) browseDetails ?: browseProgram else browseProgram
                val next = viewModel.nextProgram(b.key, browseProgram?.stopMs ?: tick)
                val browseParts = remember(shown?.description, shown?.year) { splitDescriptionYear(shown?.description, shown?.year) }
                LiveTvPlayerPanel(
                    background = settings.panelBackground,
                    channel = b,
                    mode = PanelMode.LIVE,
                    full = false,
                    title = browseProgram?.title?.let { titleMarks(it).title } ?: b.name,
                    // The year goes next to the category, off the end of the description.
                    meta = metaWithYear(
                        listOf(shown?.episode?.takeIf { settings.showEpisodeNumbers }, shown?.category),
                        if (browseSettled) browseParts.year else shown?.year
                    ),
                    description = if (browseSettled) browseParts.text else null,
                    poster = browsePoster,
                    showPosters = settings.showPosters,
                    timeline = browseProgram?.let { p ->
                        PanelTimeline(
                            startMs = p.startMs,
                            endMs = p.stopMs,
                            positionMs = tick.coerceIn(p.startMs, p.stopMs),
                            leftLabel = formatClock(p.startMs, use24h),
                            rightLabel = formatClock(p.stopMs, use24h),
                            status = minutesLeftLabel(p.stopMs, tick)
                        )
                    },
                    paused = false,
                    controls = emptyList(),
                    focused = null,
                    captionsOn = false,
                    audioLabel = "",
                    sizeLabel = "",
                    sleepLabel = null,
                    clock = if (settings.clockMode == "never") "" else formatClock(tick, use24h),
                    resolution = null,
                    showNumber = true,
                    nextTitle = next?.title?.let { titleMarks(it).title },
                    nextTime = next?.let { formatRange(it.startMs, it.stopMs, use24h) },
                    catchupAvailable = b.catchup != null,
                    browsing = true,
                    hidden = BarPart.parse(settings.barHidden)
                )
            }
        }
        // Sports: the game card in the top right corner while the full panel is up (OK).
        AnimatedVisibility(
            visible = panelFull && current != null && playback.catchupTitle == null && !listVisible,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            com.nuvio.tv.livetv.sports.SportsCornerCard(
                channelKey = current?.key,
                use24h = settings.use24HourClock,
                modifier = Modifier.padding(top = 28.dp, end = 36.dp)
            )
        }
        AnimatedVisibility(
            visible = (bannerVisible || panelFull || pausedNow) && current != null && (browseKey == null || panelFull),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            current?.let { ch ->
                val use24h = settings.use24HourClock
                val archive = playback.catchupTitle != null
                val cs = catchupSession
                val mode = when {
                    archive -> PanelMode.CATCHUP
                    shiftStartedAt != null -> PanelMode.SHIFT
                    else -> PanelMode.LIVE
                }
                // 24/7 channels with no episode info: what the channel plays, from your addons.
                val showProgram = if (archive) cs?.program else nowProgram
                // Ticks while visible, so the timeline moves.
                var tick by remember { mutableStateOf(System.currentTimeMillis()) }
                // Seconds show in catch-up and paused live ("12:04 / 58:00"); live only needs minutes,
                // so it ticks every 5 seconds there (a fifth of the redraws on slower boxes).
                LaunchedEffect(mode) {
                    tick = System.currentTimeMillis()
                    while (true) {
                        val step = if (mode == PanelMode.LIVE) 5_000L else 1_000L
                        delay(step - System.currentTimeMillis() % step)
                        tick = System.currentTimeMillis()
                    }
                }
                val timeline: PanelTimeline? = when (mode) {
                    PanelMode.CATCHUP -> cs?.let { session ->
                        val pos = scrubTargetMs ?: positionMs
                        val len = session.program.stopMs - session.program.startMs
                        val watchingAt = session.program.startMs + pos
                        // How far behind live you are: skip ahead (adverts) and watch it shrink.
                        val behind = (tick - watchingAt).coerceAtLeast(0L)
                        // A show still on air (watch from the beginning): the part that has aired
                        // so far in its own colour, with the live point marked.
                        val stillOn = tick < session.program.stopMs
                        PanelTimeline(
                            startMs = session.program.startMs,
                            endMs = session.program.stopMs,
                            positionMs = watchingAt,
                            liveMs = if (stillOn) tick else null,
                            recordedUntilMs = if (stillOn) tick else session.program.stopMs,
                            leftLabel = formatClock(session.program.startMs, use24h),
                            rightLabel = formatClock(session.program.stopMs, use24h),
                            status = formatDuration(pos) + " / " + formatDuration(len) +
                                (if (behind < 12 * 3_600_000L) "  ·  " + formatDuration(behind) + " behind live" else ""),
                            statusLive = stillOn
                        )
                    }
                    PanelMode.SHIFT -> {
                        // The buffer's earliest point (older parts drop off past its length).
                        val start = viewModel.playback.shiftEarliestWallMs() ?: shiftStartedAt ?: tick
                        val pos = viewModel.playback.shiftPositionWallMs() ?: start
                        val behind = (tick - pos).coerceAtLeast(0L)
                        // The whole show, like live and catch-up: the bar covers the full program,
                        // not just how much has made it into the pause buffer, so reaching "live"
                        // doesn't look like reaching the end of the show when there's time left.
                        val showStart = (nowProgram?.startMs ?: start).coerceAtMost(start)
                        val showEnd = (nowProgram?.stopMs ?: (start + 60_000)).coerceAtLeast(tick)
                        PanelTimeline(
                            startMs = showStart,
                            endMs = showEnd,
                            positionMs = pos,
                            liveMs = tick,
                            recordedUntilMs = tick,
                            leftLabel = "From " + formatClock(showStart, use24h),
                            rightLabel = "Live " + formatClock(tick, use24h),
                            status = formatDuration(behind) + " behind live",
                            statusLive = true
                        )
                    }
                    PanelMode.LIVE -> nowProgram?.let { p ->
                        PanelTimeline(
                            startMs = p.startMs,
                            endMs = p.stopMs,
                            positionMs = tick.coerceIn(p.startMs, p.stopMs),
                            leftLabel = formatClock(p.startMs, use24h),
                            rightLabel = formatClock(p.stopMs, use24h),
                            status = minutesLeftLabel(p.stopMs, tick)
                        )
                    }
                }
                val paused = playback.userPaused
                // What's on after the current show (live only).
                val nextUp = if (mode == PanelMode.LIVE) {
                    viewModel.nextProgram(ch.key, nowProgram?.stopMs ?: tick)
                } else null
                val descParts = remember(showProgram?.description, showProgram?.year) {
                    splitDescriptionYear(showProgram?.description, showProgram?.year)
                }
                LiveTvPlayerPanel(
                    background = settings.panelBackground,
                    channel = ch,
                    mode = mode,
                    full = panelFull || pausedNow,
                    title = watchingTitle ?: ch.name,
                    // The year goes next to the category, off the end of the description.
                    meta = metaWithYear(
                        listOf(
                            showProgram?.episode?.takeIf { settings.showEpisodeNumbers },
                            showProgram?.category
                        ),
                        descParts.year
                    ),
                    description = descParts.text,
                    poster = poster,
                    showPosters = settings.showPosters,
                    timeline = timeline,
                    paused = paused,
                    controls = panelControls(mode, paused, canSkip = archive || viewModel.playback.shiftCanSeek()),
                    focused = controlFocus,
                    captionsOn = captionsOn,
                    audioLabel = remember(playback.channelKey, bannerVisible) { viewModel.playback.audioLabel() },
                    // The same words as the Screen size list (Fit, Crop, Cinema Zoom…).
                    sizeLabel = context.getString(aspectModeOf(settings.aspectMode).labelResId),
                    sleepLabel = sleepAt?.let { at -> "Sleep " + ((at - tick).coerceAtLeast(0) / 60_000 + 1) + "m" },
                    clock = if (settings.clockMode == "never") "" else formatClock(tick, use24h),
                    // "1080p · 50 FPS": the frame rate rounded (23.976 -> 24, 59.94 -> 60).
                    resolution = if (playback.videoHeight > 0) {
                        "${playback.videoHeight}p" +
                            (if (settings.showFrameRate && shownFps > 0f) " · ${kotlin.math.round(shownFps).toInt()} FPS" else "")
                    } else null,
                    audioBadge = remember(playback.channelKey, bannerVisible, playback.videoHeight, panelFull) { viewModel.playback.audioBadge() },
                    showNumber = settings.showChannelNumbers,
                    nextTitle = nextUp?.title?.let { titleMarks(it).title },
                    nextTime = nextUp?.let { formatRange(it.startMs, it.stopMs, use24h) },
                    catchupAvailable = ch.catchup != null,
                    recentTitle = if (settings.panelRow == "FAVORITES") "FAVORITES" else "RECENT",
                    recentClear = settings.panelRow == "RECENT",
                    recent = if (settings.panelRow != "OFF" && (panelFull || pausedNow)) {
                        remember(playback.channelKey, user.recent, user.favorites, settings.panelRow, programs, tick / 60_000) {
                            viewModel.panelRowChannels(playback.channelKey).map { r ->
                                val p = programs[r.key]?.firstOrNull { tick >= it.startMs && tick < it.stopMs }
                                PanelRecent(
                                    channel = r,
                                    title = p?.title?.let { titleMarks(it).title },
                                    progress = p?.progress(tick)
                                )
                            }
                        }
                    } else null,
                    recentFocus = recentFocus,
                    hidden = BarPart.parse(settings.barHidden)
                )
            }
        }

        // Overlay mode (TiviMate style): channels, groups and each channel's schedule over the video.
        if (listVisible) {
            LiveTvOverlayMode(
                viewModel = viewModel,
                onClose = { listVisible = false },
                onFindInNuvio = { title ->
                    listVisible = false
                    LiveTvSearchBridge.request(title)
                    onFindInNuvio()
                }
            )
        }

        if (numberBuffer.isNotEmpty()) {
            LiveText(
                numberBuffer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                size = 40.sp,
                weight = FontWeight.Bold
            )
        }
        toast?.let {
            LiveText(
                it,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
        // This channel is being recorded: a small REC mark in the corner.
        if (playback.channelKey != null && recordings.any { it.channelKey == playback.channelKey && it.isRecording }) {
            LiveText(
                "● REC",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = LIVE_RED,
                size = 14.sp,
                weight = FontWeight.Bold
            )
        }
    }

    when (dialog) {
        PlayerDialog.OPTIONS -> current?.let { ch ->
            val archive = playback.catchupTitle != null
            val canRestart = !archive && viewModel.canWatchFromStart(ch)
            val shifting = shiftStartedAt != null
            val timeAction = when {
                archive -> "Back to live"
                canRestart -> viewModel.restartLabel(ch)
                shifting -> "Go live"
                else -> null
            }
            GearPanel(
                startPage = gearStart,
                audioOptions = viewModel.playback.audioTracks(),
                subtitleOptions = viewModel.playback.subtitleTracks(),
                moreSubtitleOptions = viewModel.playback.moreCaptionTracks(),
                subtitleNote = viewModel.playback.captionsNote(),
                onPickAudio = { o -> viewModel.playback.pickAudio(o); dialog = PlayerDialog.NONE },
                onPickSubtitle = { o ->
                    viewModel.playback.pickSubtitle(o)
                    captionsOn = viewModel.playback.captionsOn()
                    dialog = PlayerDialog.NONE
                },
                onPickSleep = { minutes ->
                    viewModel.setSleepTimer(minutes)
                    toast = if (minutes == null) "Sleep timer off" else "Sleep timer: $minutes min"
                    dialog = PlayerDialog.NONE
                    scope.launch { delay(60); runCatching { rootFocus.requestFocus() } }
                },
                streamInfo = {
                    streamInfoRows(viewModel.playback.player, ch, viewModel.pictureShapeOf(ch.key), viewModel.playback.captionsInfo())
                },
                bottom = 168.dp,
                background = settings.panelBackground,
                isFavorite = ch.key in user.favorites,
                recording = recordings.any { it.channelKey == ch.key && it.isRecording },
                audioLabel = viewModel.playback.audioLabel(),
                captionsOn = captionsOn,
                sizeLabel = context.getString(aspectModeOf(settings.aspectMode).labelResId),
                sizeOptions = com.nuvio.tv.ui.screens.player.AspectMode.entries.map {
                    context.getString(it.labelResId) to (it == aspectModeOf(settings.aspectMode))
                },
                onPickSize = { i ->
                    // Remembered for every channel, until you change it again.
                    val mode = com.nuvio.tv.ui.screens.player.AspectMode.entries[i]
                    viewModel.updateSettings { it.copy(aspectMode = mode.name) }
                },
                sleepLabel = sleepAt?.let { "${((it - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min left" } ?: "Off",
                timeAction = timeAction,
                hidden = BarPart.parse(settings.barHidden),
                channelRow = settings.panelRow,
                onChannelRow = {
                    viewModel.updateSettings {
                        it.copy(panelRow = when (it.panelRow) { "RECENT" -> "FAVORITES"; "FAVORITES" -> "OFF"; else -> "RECENT" })
                    }
                },
                onBackground = {
                    viewModel.updateSettings {
                        it.copy(panelBackground = when (it.panelBackground) { "standard" -> "darker"; "darker" -> "solid"; else -> "standard" })
                    }
                },
                onToggle = { part ->
                    viewModel.updateSettings { s ->
                        val cur = BarPart.parse(s.barHidden)
                        s.copy(barHidden = BarPart.join(if (part in cur) cur - part else cur + part))
                    }
                },
                onReset = { viewModel.updateSettings { it.copy(barHidden = "") } },
                onRecord = if (archive) null else {
                    {
                        toast = viewModel.toggleRecording(ch)
                    }
                },
                onFavorite = { viewModel.toggleFavorite(ch) },
                onTimeAction = {
                    dialog = PlayerDialog.NONE
                    when {
                        archive -> viewModel.backToLive(ch)
                        canRestart -> { viewModel.watchFromStart(ch); showBanner() }
                        shifting -> viewModel.playback.goLive()
                    }
                },
                onPrevious = {
                    dialog = PlayerDialog.NONE
                    viewModel.previousChannel()?.let { viewModel.preview(it) }
                },
                onSearch = viewModel.currentProgram(ch.key)
                    ?.let { com.nuvio.tv.livetv.data.LiveTvPosterResolver.searchTitleFor(it, ch) }
                    ?.let { title ->
                        {
                            dialog = PlayerDialog.NONE
                            LiveTvSearchBridge.request(title)
                            onFindInNuvio()
                        }
                    },
                onReload = { dialog = PlayerDialog.NONE; viewModel.playback.retry() },
                onHide = {
                    viewModel.hideChannel(ch)
                    dialog = PlayerDialog.NONE
                    zap(1)
                },
                onSettings = { dialog = PlayerDialog.NONE; onOpenSettings() },
                onDismiss = { dialog = PlayerDialog.NONE; scope.launch { delay(60); runCatching { rootFocus.requestFocus() } } }
            )
        } ?: run { dialog = PlayerDialog.NONE }
        PlayerDialog.AUDIO -> TrackDialog(
            title = "Audio",
            options = viewModel.playback.audioTracks(),
            allowOff = false,
            onPick = { option -> option?.let { viewModel.playback.pickAudio(it) }; dialog = PlayerDialog.NONE },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.SUBTITLES -> TrackDialog(
            title = "Subtitles",
            options = viewModel.playback.subtitleTracks(),
            allowOff = true,
            more = viewModel.playback.moreCaptionTracks(),
            note = viewModel.playback.captionsNote(),
            onPick = {
                viewModel.playback.pickSubtitle(it)
                captionsOn = viewModel.playback.captionsOn()
                dialog = PlayerDialog.NONE
            },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.STREAM_INFO -> viewModel.channelByKey(playback.channelKey)?.let { current ->
            StreamInfoDialog(
                player = viewModel.playback.player,
                channel = current,
                shape = viewModel.pictureShapeOf(current.key),
                captions = viewModel.playback.captionsInfo(),
                onDismiss = { dialog = PlayerDialog.NONE; scope.launch { delay(60); runCatching { rootFocus.requestFocus() } } }
            )
        }
        PlayerDialog.SLEEP -> SleepTimerDialog(
            onPick = { minutes ->
                viewModel.setSleepTimer(minutes)
                toast = if (minutes == null) "Sleep timer off" else "Sleep timer: $minutes min"
                dialog = PlayerDialog.NONE
                scope.launch { delay(60); runCatching { rootFocus.requestFocus() } }
            },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.SCREEN_SIZE -> ScreenSizeDialog(
            selected = aspectModeOf(settings.aspectMode),
            label = { context.getString(it.labelResId) },
            onPick = { mode ->
                // Remembered for every channel, until you change it again.
                viewModel.updateSettings { it.copy(aspectMode = mode.name) }
                toast = "Screen size: ${context.getString(mode.labelResId)}"
                dialog = PlayerDialog.NONE
                scope.launch { delay(60); runCatching { rootFocus.requestFocus() } }
            },
            onDismiss = { dialog = PlayerDialog.NONE }
        )
        PlayerDialog.NONE -> Unit
    }
    }
}

@Composable
private fun TrackDialog(
    title: String,
    options: List<LiveTrackOption>,
    allowOff: Boolean,
    /** Extra caption tracks, behind "More caption tracks" (hidden when there are none). */
    more: List<LiveTrackOption> = emptyList(),
    /** Shown above the list when it has nothing to pick ("No captions on this channel"). */
    note: String? = null,
    onPick: (LiveTrackOption?) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    // Opened already when one of the extra tracks is the one on.
    var showMore by remember { mutableStateOf(more.any { it.selected }) }
    LiveDialog(onDismiss = onDismiss, width = 480.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (options.isEmpty() && !allowOff) {
            LiveText("No tracks available", color = NuvioTheme.colors.TextSecondary)
        }
        note?.let {
            LiveText(it, color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (allowOff) {
                item {
                    LiveFocusRow(modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = { onPick(null) }) { f ->
                        LiveText("Off", color = focusedTextColor(f))
                    }
                }
            }
            items(options) { o ->
                val isFirst = !allowOff && o == options.first()
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (isFirst) Modifier.focusRequester(first) else Modifier),
                    selected = o.selected,
                    onClick = { onPick(o) }
                ) { f ->
                    LiveText((if (o.selected) "✓  " else "") + o.label, color = focusedTextColor(f))
                }
            }
            if (more.isNotEmpty()) {
                item {
                    LiveFocusRow(modifier = Modifier.fillMaxWidth(), onClick = { showMore = !showMore }) { f ->
                        LiveText(
                            (if (showMore) "▾  " else "▸  ") + "More caption tracks",
                            color = focusedTextColor(f).copy(alpha = 0.85f)
                        )
                    }
                }
                if (showMore) {
                    items(more) { o ->
                        LiveFocusRow(
                            modifier = Modifier.fillMaxWidth().padding(start = 18.dp),
                            selected = o.selected,
                            onClick = { onPick(o) }
                        ) { f ->
                            LiveText((if (o.selected) "✓  " else "") + o.label, color = focusedTextColor(f))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

/** Picture size, with the same choices as Nuvio's movie player (Fit, Crop, Stretch, Cinema Zoom, …). */
@Composable
private fun ScreenSizeDialog(
    selected: com.nuvio.tv.ui.screens.player.AspectMode,
    label: (com.nuvio.tv.ui.screens.player.AspectMode) -> String,
    onPick: (com.nuvio.tv.ui.screens.player.AspectMode) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 420.dp) {
        LiveText("Screen size", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            com.nuvio.tv.ui.screens.player.AspectMode.entries.forEach { mode ->
                val isSelected = mode == selected
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (isSelected) Modifier.focusRequester(first) else Modifier),
                    selected = isSelected,
                    onClick = { onPick(mode) }
                ) { f ->
                    LiveText((if (isSelected) "✓  " else "") + label(mode), color = focusedTextColor(f))
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Resolution, frame rate, codecs and bitrate of what's playing. */
@Composable
private fun StreamInfoDialog(
    player: androidx.media3.common.Player?,
    channel: LiveChannel,
    shape: com.nuvio.tv.livetv.player.PictureShape = com.nuvio.tv.livetv.player.PictureShape.AUTO,
    captions: String? = null,
    onDismiss: () -> Unit
) {
    val rows = streamInfoRows(player, channel, shape, captions)
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText("Stream info", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        rows.forEach { (k, value) ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                LiveText(k, color = NuvioTheme.colors.TextSecondary, size = 14.sp, modifier = Modifier.width(130.dp))
                LiveText(value, size = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        MenuItem("Close", Modifier.focusRequester(first), onDismiss)
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

private fun streamInfoRows(
    player: androidx.media3.common.Player?,
    channel: LiveChannel,
    shape: com.nuvio.tv.livetv.player.PictureShape,
    captions: String?
): List<Pair<String, String>> {
    val exo = player as? androidx.media3.exoplayer.ExoPlayer
    val v = exo?.videoFormat
    val a = exo?.audioFormat
    return buildList {
        add("Channel" to "${channel.number}  ${channel.name}")
        v?.let { f ->
            if (f.width > 0) add("Resolution" to "${f.width} × ${f.height}" + qualityName(f.height)?.let { "  ($it)" }.orEmpty())
            val size = exo?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN
            val par = if (size.width > 0) size.pixelWidthHeightRatio else f.pixelWidthHeightRatio
            val w = if (size.width > 0) size.width else f.width
            val h = if (size.height > 0) size.height else f.height
            com.nuvio.tv.livetv.player.LivePictureShape.aspect(w, h, par, shape)?.let { aspect ->
                val pic = com.nuvio.tv.livetv.player.LivePictureShape
                val note = when {
                    pic.ignoresFlag(w, par, shape) -> "  (stream's shape ignored: it says ${pic.ratioText(w * par / h)})"
                    pic.hasFlag(par) -> "  (stream's shape)"
                    else -> ""
                }
                add("Shape" to "${pic.ratioText(aspect)}$note")
            }
            if (f.frameRate > 0f) add("Frame rate" to "%.2f fps".format(f.frameRate).replace(".00", ""))
            add("Video" to listOfNotNull(f.sampleMimeType?.substringAfter('/'), f.codecs).joinToString(" · ").ifBlank { "Unknown" })
            val br = if (f.bitrate > 0) f.bitrate else f.averageBitrate
            if (br > 0) add("Video bitrate" to "%.1f Mbps".format(br / 1_000_000f))
        } ?: add("Video" to "Not known yet")
        a?.let { f ->
            add(
                "Audio" to listOfNotNull(
                    f.sampleMimeType?.substringAfter('/'),
                    f.channelCount.takeIf { it > 0 }?.let { c -> when (c) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$c ch" } },
                    f.sampleRate.takeIf { it > 0 }?.let { r -> "${r / 1000} kHz" },
                    f.language
                ).joinToString(" · ")
            )
        }
        captions?.let { add("Captions" to it) }
        exo?.let { p -> add("Buffered" to "${p.totalBufferedDuration / 1000} s") }
        add("Source" to (runCatching { android.net.Uri.parse(channel.url).host }.getOrNull() ?: "—"))
    }
}

private fun qualityName(height: Int): String? = when {
    height >= 1800 -> "4K"
    height >= 1000 -> "Full HD"
    height >= 700 -> "HD"
    height > 0 -> "SD"
    else -> null
}

@Composable
private fun SleepTimerDialog(onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 380.dp) {
        LiveText("Sleep timer", size = 20.sp, weight = FontWeight.Bold)
        LiveText("Stops playback after", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuItem("Off", Modifier.focusRequester(first)) { onPick(null) }
            listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                MenuItem(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "${m / 60.0} hours".replace(".0 ", " ")) { onPick(m) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

/**
 * Match frame rate: while the player is on screen, asks the TV for a display mode whose refresh
 * rate fits the video (24 → 24 Hz, 25 → 50 Hz, 30 → 60 Hz…). [restore] ("On start/stop"): puts
 * the original mode back when the player closes; otherwise ("On start") the TV stays switched.
 */
@Composable
private fun MatchFrameRate(enabled: Boolean, fps: Float, restore: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        c as? android.app.Activity
    } ?: return
    val originalMode = remember { activity.window.attributes.preferredDisplayModeId }
    val restoreNow = androidx.compose.runtime.rememberUpdatedState(restore)
    DisposableEffect(Unit) {
        onDispose {
            if (!restoreNow.value) return@onDispose
            val attrs = activity.window.attributes
            if (attrs.preferredDisplayModeId != originalMode) {
                attrs.preferredDisplayModeId = originalMode
                activity.window.attributes = attrs
            }
        }
    }
    // Say why nothing happened, so the Start-Up Report shows which part is missing.
    val fpsNow = androidx.compose.runtime.rememberUpdatedState(fps)
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        delay(20_000)
        if (fpsNow.value <= 0f) com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "Match frame rate: this stream doesn't say its frame rate and it couldn't be measured yet, so the display wasn't switched"
        )
    }
    LaunchedEffect(enabled, fps) {
        if (!enabled || fps <= 0f) return@LaunchedEffect
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return@LaunchedEffect
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        // The refresh rates that show every frame for the same time: a whole multiple of the
        // frame rate (25 fps: 25, 50, 75 Hz; 29.97 fps: 59.94 Hz). Close enough counts, but an
        // exact match wins (59.94 over 60 Hz for 29.97 fps, which otherwise skips a frame every
        // 16 seconds). Of those, the highest up to 60 Hz: sports at 25 fps are usually
        // interlaced and move 50 times a second, so 25 Hz stuttered where 50 Hz is smooth.
        fun error(refresh: Float): Float = (1..4).minOf { k -> kotlin.math.abs(refresh - fps * k) }
        val fitting = candidates.filter { error(it.refreshRate) <= 0.6f }
        val best = fitting.sortedWith(
            compareBy<android.view.Display.Mode>(
                { if (error(it.refreshRate) < 0.02f) 0 else 1 },
                { if (it.refreshRate <= 61f) 0 else 1 },
                { -it.refreshRate }
            )
        ).firstOrNull()
        if (best == null) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Match frame rate: video %.3f fps, but at %dx%d this box only offers ".format(fps, current.physicalWidth, current.physicalHeight) +
                    candidates.map { it.refreshRate }.distinct().sorted().joinToString(", ") { "%.2f Hz".format(it) } +
                    " (other resolutions: " + display.supportedModes.size + " modes in all)"
            )
            return@LaunchedEffect
        }
        if (best.modeId == current.modeId) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Match frame rate: video %.3f fps, display already at %.3f Hz".format(fps, current.refreshRate))
            return@LaunchedEffect
        }
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "Match frame rate: video %.3f fps, display %.3f Hz → %.3f Hz".format(fps, current.refreshRate, best.refreshRate)
        )
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = best.modeId
        activity.window.attributes = attrs
        // Did the box actually switch? Some ignore apps' requests (a setting of their own).
        delay(4_000)
        @Suppress("DEPRECATION")
        val after = activity.windowManager.defaultDisplay?.mode
        if (after != null && after.modeId != best.modeId) com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "Match frame rate: asked for %.3f Hz but the display stayed at %.3f Hz (the box didn't switch: check its own display settings, e.g. Match content frame rate)".format(best.refreshRate, after.refreshRate)
        )
    }
}
