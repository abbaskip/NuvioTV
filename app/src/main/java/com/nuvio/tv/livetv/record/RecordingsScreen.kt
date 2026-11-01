@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.livetv.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.LIVE_RED
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.liveCorner
import com.nuvio.tv.livetv.ui.guideBackdrop
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A recording's artwork: the show's backdrop, and its title logo to put over it. */
data class RecordingArt(val backdrop: String?, val logo: String?)

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    val recorder: LiveTvRecorder,
    val scheduler: LiveTvRecordingScheduler,
    private val liveRepository: com.nuvio.tv.livetv.data.LiveTvRepository,
    private val livePrefs: com.nuvio.tv.livetv.data.LiveTvPreferences
) : ViewModel() {
    val recordings = recorder.recordings

    /** New Recording's channel list: favorites, then channels watched recently. */
    val pickerChannels: kotlinx.coroutines.flow.StateFlow<List<com.nuvio.tv.livetv.model.LiveChannel>> =
        kotlinx.coroutines.flow.combine(liveRepository.channels, livePrefs.userState) { all, user ->
            val byKey = all.associateBy { it.key }
            (user.favorites + user.recent).distinct().mapNotNull { byKey[it] }.take(80)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The guide's look (background, panel darkness, highlight style), so this screen matches it. */
    val look: kotlinx.coroutines.flow.StateFlow<com.nuvio.tv.livetv.model.LiveTvSettings> = livePrefs.settings
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.nuvio.tv.livetv.model.LiveTvSettings())

    val use24h: kotlinx.coroutines.flow.StateFlow<Boolean> = livePrefs.settings
        .map { it.use24HourClock }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun scheduleManual(channel: com.nuvio.tv.livetv.model.LiveChannel, title: String, startMs: Long, stopMs: Long) {
        LiveTvNotice.show(scheduler.addManual(channel, title, startMs, stopMs))
    }
    val schedules = scheduler.schedules
    val series = scheduler.series

    init {
        // Anything that should have started already (catch-up saves included) starts now.
        scheduler.fireOverdue()
    }

    /** "Saving from catch-up at 7:10 PM", "Not ready yet: trying again at 7:40 PM (2 of 3)". */
    fun catchupNote(s: RecordingSchedule): String {
        val at = scheduler.startsAt(s)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        return when {
            s.attempts > 0 -> "Not ready at the provider yet: trying again at $time (${s.attempts + 1} of ${LiveTvRecordingScheduler.MAX_TRIES + 1})"
            at <= System.currentTimeMillis() -> "Saving from catch-up now"
            else -> "Saved from catch-up at $time, after it ends"
        }
    }

    // ---- Artwork: the show's backdrop with its logo over it, like Nuvio's landscape cards.
    private val _art = MutableStateFlow<Map<String, RecordingArt>>(emptyMap())
    /** Previously saved artwork only; opening recordings never searches metadata services. */
    val art: StateFlow<Map<String, RecordingArt>> = _art.asStateFlow()
    private val triedArt = HashSet<String>()
    private val artPrefs = recorder.appContext.getSharedPreferences("livetv_recording_art", android.content.Context.MODE_PRIVATE)

    fun loadArt(list: List<LiveTvRecording>) {
        val todo = list.filter { triedArt.add(it.id) }
        if (todo.isEmpty()) return
        // Found before: straight away, no lookup.
        val saved = HashMap<String, RecordingArt>()
        todo.forEach { r ->
            runCatching { artPrefs.getString(r.id, null) }.getOrNull()?.let { v ->
                val parts = v.split('\n')
                saved[r.id] = RecordingArt(parts.getOrNull(0)?.ifBlank { null }, parts.getOrNull(1)?.ifBlank { null })
            }
        }
        if (saved.isNotEmpty()) _art.value = _art.value + saved
    }

    fun playUrl(rec: LiveTvRecording): String = recorder.playUrl(rec)
    /** Where you left off (ms), if partly watched. */
    fun resume(rec: LiveTvRecording): Long? = RecordingResume.load(recorder.appContext, RecordingResume.contentIdFor(rec.id))?.first
    fun stop(rec: LiveTvRecording) = recorder.stop(rec.id)
    fun delete(rec: LiveTvRecording) {
        recorder.delete(rec.id)
        runCatching { artPrefs.edit().remove(rec.id).apply() }
    }
    fun freeBytes(): Long = recorder.freeBytes()
}

/**
 * Recordings: what's recording now across the top, then three columns: Scheduled, Series and
 * Recorded. OK plays (in Nuvio's player, with its seek bar); the menu button or a long press
 * offers Resume / Play from Beginning, Stop and Delete. Same background as the guide (the
 * theme's glow at the top), not plain black.
 */
@Composable
fun RecordingsScreen(
    onPlay: (url: String, title: String, poster: String?, recordingId: String, fromStart: Boolean) -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val seriesRules by viewModel.series.collectAsStateWithLifecycle()
    val posters = remember(recordings) { recordings.mapNotNull { r -> r.channelLogo?.let { r.id to it } }.toMap() }
    val art by viewModel.art.collectAsStateWithLifecycle()
    var scheduleMenu by remember { mutableStateOf<RecordingSchedule?>(null) }
    // Back from the player: read where you got to again (the progress bars).
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // The clock, for "recording until" bars (moves on every few seconds).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(5_000); nowMs = System.currentTimeMillis() }
    }
    var seriesMenu by remember { mutableStateOf<SeriesRule?>(null) }
    var seriesKeepMenu by remember { mutableStateOf<SeriesRule?>(null) }
    LaunchedEffect(recordings) { viewModel.loadArt(recordings) }
    var menuFor by remember { mutableStateOf<LiveTvRecording?>(null) }
    var confirmDelete by remember { mutableStateOf<LiveTvRecording?>(null) }
    var infoFor by remember { mutableStateOf<LiveTvRecording?>(null) }
    // New Recording: picking the channel, then the time.
    var pickingChannel by remember { mutableStateOf(false) }
    var manualFor by remember { mutableStateOf<com.nuvio.tv.livetv.model.LiveChannel?>(null) }
    val pickerChannels by viewModel.pickerChannels.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val newButton = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val colors = NuvioTheme.colors
    val look by viewModel.look.collectAsStateWithLifecycle()

    val now = recordings.filter { it.isRecording }
    val done = recordings.filter { !it.isRecording }
    val playNow: (LiveTvRecording) -> Unit = { rec ->
        // Nothing saved yet (it's still connecting): say so instead of an error.
        if (rec.durationSec <= 0) LiveTvNotice.show(
            "\"${rec.title}\" hasn't recorded anything yet" + (rec.problem?.let { ": $it" } ?: ". Give it a few seconds.")
        )
        else onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false)
    }
    // Which item gets the highlight first: the top of the first section that has anything.
    val firstNowId = now.firstOrNull()?.id
    val firstScheduleId = if (now.isEmpty()) schedules.firstOrNull()?.id else null
    val firstSeriesId = if (now.isEmpty() && schedules.isEmpty()) seriesRules.firstOrNull()?.id else null
    val firstDoneId = if (now.isEmpty() && schedules.isEmpty() && seriesRules.isEmpty()) done.firstOrNull()?.id else null

    androidx.compose.runtime.CompositionLocalProvider(
        com.nuvio.tv.livetv.ui.LocalLiveSolidHighlight provides look.solidHighlight,
        com.nuvio.tv.livetv.ui.LocalGuideDarkness provides look.guideInfoBackground
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The same background as the guide and Sports (Settings → Live TV → Guide & Sports Background).
            .guideBackdrop(look.guideBackdrop)
    ) {
        // The guide's background: the theme's glow behind the top of the screen.
        com.nuvio.tv.livetv.ui.ThemeGlow(Modifier.fillMaxWidth().height(260.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 56.dp, end = 48.dp, top = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                LiveText("Recordings", size = 30.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                LiveText(
                    "${sizeLabel(viewModel.freeBytes())} free",
                    size = 15.sp,
                    color = colors.TextSecondary
                )
                Spacer(Modifier.width(18.dp))
                // Record a time you pick, on a channel with no listings (PPV events…).
                PillButton(
                    "+ New Recording",
                    primary = false,
                    modifier = Modifier.focusRequester(newButton),
                    onFocus = {},
                    onClick = { pickingChannel = true },
                    onMenu = { pickingChannel = true }
                )
            }
            Spacer(Modifier.height(20.dp))
            if (recordings.isEmpty() && schedules.isEmpty() && seriesRules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LiveText("No recordings yet", size = 20.sp, weight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LiveText(
                            "In the Live TV guide, long-press a show and choose Record or Record series. While watching, open the menu and choose Record this show. For a channel with no listings, use + New Recording.",
                            size = 14.sp, color = colors.TextSecondary, maxLines = 3
                        )
                    }
                }
                return@Column
            }

            // ---- Recording now: a wide card each, across the top.
            if (now.isNotEmpty()) {
                SectionTitle("Recording Now", color = LIVE_RED)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    now.take(2).forEach { rec ->
                        RecordingNowCard(
                            rec = rec,
                            art = art[rec.id],
                            nowMs = nowMs,
                            watchModifier = if (rec.id == firstNowId) Modifier.focusRequester(first) else Modifier,
                            onWatch = { playNow(rec) },
                            onStop = { viewModel.stop(rec) },
                            onMenu = { menuFor = rec }
                        )
                    }
                    if (now.size > 2) {
                        LiveText("And ${now.size - 2} more recording now", size = 14.sp, color = colors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(22.dp))
            }

            // ---- Scheduled · Series · Recorded, side by side.
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Scheduled")
                    if (schedules.isEmpty()) EmptyNote("Nothing scheduled")
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(schedules, key = { it.id }) { s ->
                            InfoCard(
                                title = s.title + (s.episode?.let { "  ·  $it" } ?: ""),
                                line = s.channel.name + " · " + dateLabel(s.startMs),
                                note = if (s.useCatchup) viewModel.catchupNote(s) else null,
                                modifier = if (s.id == firstScheduleId) Modifier.focusRequester(first) else Modifier,
                                onClick = { scheduleMenu = s }
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    SectionTitle("Series")
                    if (seriesRules.isEmpty()) EmptyNote("No series set to record")
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(seriesRules, key = { it.id }) { r ->
                            InfoCard(
                                title = r.title,
                                line = r.channel.name + " · " + (if (r.keepLast > 0) "Keep last ${r.keepLast}" else "Keep all"),
                                note = null,
                                modifier = if (r.id == firstSeriesId) Modifier.focusRequester(first) else Modifier,
                                onClick = { seriesMenu = r }
                            )
                        }
                    }
                }
                Column(Modifier.weight(1.4f)) {
                    SectionTitle("Recorded")
                    if (done.isEmpty()) EmptyNote("Nothing recorded yet")
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        gridItems(done, key = { it.id }) { rec ->
                            RecordedCard(
                                rec = rec,
                                art = art[rec.id],
                                resumeFraction = resumeTick.let { viewModel.resume(rec) }?.let { ms ->
                                    if (rec.durationSec > 0) (ms / 1000.0 / rec.durationSec).toFloat().coerceIn(0f, 1f) else null
                                },
                                modifier = if (rec.id == firstDoneId) Modifier.focusRequester(first) else Modifier,
                                onClick = {
                                    if (rec.status != RecordingStatus.FAILED) onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false)
                                    else menuFor = rec
                                },
                                onMenu = { menuFor = rec }
                            )
                        }
                    }
                }
            }
        }
    }
    }
    val anything = recordings.isNotEmpty() || schedules.isNotEmpty() || seriesRules.isNotEmpty()
    LaunchedEffect(anything) {
        kotlinx.coroutines.delay(120)
        // Nothing yet: start on New Recording.
        runCatching { if (anything) first.requestFocus() else newButton.requestFocus() }
    }
    scheduleMenu?.let { s ->
        RecordingActionDialog(
            title = s.title,
            detail = s.channel.name + " · " + dateLabel(s.startMs),
            actions = listOf(
                "Cancel Recording" to { scheduleMenu = null; viewModel.scheduler.cancel(s.id) },
                "Close" to { scheduleMenu = null }
            ),
            onDismiss = { scheduleMenu = null }
        )
    }
    // A series: stop it, or choose how many episodes to keep (its own list, so Stop is never
    // pushed off the bottom by the keep choices).
    seriesMenu?.let { r ->
        RecordingActionDialog(
            title = r.title,
            detail = "Every airing on " + r.channel.name + ". Stopping it also cancels the airings it set that haven't started; what's recorded is kept.",
            actions = listOf(
                "Stop Recording Series" to { seriesMenu = null; viewModel.scheduler.removeSeries(r.id) },
                ("Episodes to Keep: " + (if (r.keepLast == 0) "All" else "Last ${r.keepLast}")) to { seriesMenu = null; seriesKeepMenu = r },
                "Close" to { seriesMenu = null }
            ),
            onDismiss = { seriesMenu = null }
        )
    }
    seriesKeepMenu?.let { r ->
        RecordingActionDialog(
            title = "Episodes to Keep",
            detail = r.title + ": older episodes are deleted as new ones are recorded.",
            actions = listOf(0, 1, 3, 5, 10).map { n ->
                val label = (if (n == 0) "Keep All Episodes" else "Keep the Last $n") + if (r.keepLast == n) "  ✓" else ""
                label to { seriesKeepMenu = null; viewModel.scheduler.setKeep(r.id, n) }
            },
            onDismiss = { seriesKeepMenu = null }
        )
    }

    menuFor?.let { rec ->
        val actions = buildList<Pair<String, () -> Unit>> {
            if (rec.status != RecordingStatus.FAILED) {
                val resumeAt = viewModel.resume(rec)
                add((if (resumeAt != null) "Resume" else "Play") to { menuFor = null; onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false) })
                if (resumeAt != null) add("Play from Beginning" to { menuFor = null; onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, true) })
            }
            if (rec.isRecording) add("Stop Recording" to { menuFor = null; viewModel.stop(rec) })
            add("Information" to { menuFor = null; infoFor = rec })
            add("Delete" to { menuFor = null; confirmDelete = rec })
        }
        RecordingActionDialog(title = rec.title, detail = rec.problem, actions = actions, onDismiss = { menuFor = null })
    }
    if (pickingChannel) ManualRecordingChannelPicker(
        channels = pickerChannels,
        onPick = { ch -> pickingChannel = false; manualFor = ch },
        onDismiss = { pickingChannel = false }
    )
    manualFor?.let { ch ->
        ManualRecordingDialog(
            channel = ch,
            use24h = use24h,
            onSchedule = { title, start, stop -> manualFor = null; viewModel.scheduleManual(ch, title, start, stop) },
            onDismiss = { manualFor = null }
        )
    }
    infoFor?.let { picked ->
        // Live: a recording still going keeps its size and length up to date.
        val rec = recordings.firstOrNull { it.id == picked.id } ?: picked
        val context = androidx.compose.ui.platform.LocalContext.current
        RecordingInfoDialog(rec, savedOnLabel(context, rec.dir), onDismiss = { infoFor = null })
    }
    confirmDelete?.let { rec ->
        RecordingActionDialog(
            title = "Delete \"${rec.title}\"?",
            detail = if (rec.isRecording) "It's still recording. This stops it and deletes what's been recorded." else "This can't be undone.",
            actions = listOf(
                "Cancel" to { confirmDelete = null },
                "Delete" to { confirmDelete = null; viewModel.delete(rec) }
            ),
            onDismiss = { confirmDelete = null }
        )
    }
}

@Composable
private fun SectionTitle(text: String, color: Color = NuvioTheme.colors.TextSecondary) {
    LiveText(
        text.uppercase(),
        size = 13.sp,
        weight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    LiveText(text, size = 14.sp, color = NuvioTheme.colors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
}

/** A card's look: the guide's tile color, the theme's focus color and ring when highlighted. */
@Composable
private fun cardColors(focused: Boolean): Pair<Color, Color> {
    val colors = NuvioTheme.colors
    return (if (focused) colors.FocusBackground else com.nuvio.tv.livetv.ui.guideSurface()) to
        (if (focused) colors.FocusRing else com.nuvio.tv.livetv.ui.GuideGlassEdge)
}

/**
 * A recording's picture, like Nuvio's landscape cards: the show's backdrop with its title logo
 * over the bottom (or the title in text when there's no logo). With no backdrop found, the
 * channel's logo on the tile color, with the title under it.
 */
@Composable
private fun ArtThumb(
    art: RecordingArt?,
    channelLogo: String?,
    title: String,
    modifier: Modifier,
    /** Smaller title and logo (the Recording Now card). */
    compact: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(liveCorner()))
            .background(com.nuvio.tv.livetv.ui.guideSurfaceVariant())
    ) {
        if (art?.backdrop != null) {
            AsyncImage(
                model = art.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Darker toward the bottom, so the logo and the progress bar stand out.
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.8f)
                    )
                )
            )
            if (art.logo != null) {
                AsyncImage(
                    model = art.logo,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = if (compact) 10.dp else 16.dp)
                        .fillMaxWidth(if (compact) 0.75f else 0.6f)
                        .fillMaxHeight(0.36f)
                )
            } else {
                LiveText(
                    title,
                    size = if (compact) 13.sp else 17.sp,
                    weight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = if (compact) 10.dp else 16.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (channelLogo != null) {
                    AsyncImage(
                        model = channelLogo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.5f).height(if (compact) 36.dp else 52.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LiveText(
                    title,
                    size = if (compact) 13.sp else 15.sp,
                    weight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 2,
                    align = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        content()
    }
}

@Composable
private fun RecTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(LIVE_RED)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        LiveText("REC", size = 12.sp, weight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun ProgressLine(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** What's recording now: art, REC, title, channel and times, how far along, Watch and Stop. */
@Composable
private fun RecordingNowCard(
    rec: LiveTvRecording,
    art: RecordingArt?,
    nowMs: Long,
    watchModifier: Modifier,
    onWatch: () -> Unit,
    onStop: () -> Unit,
    onMenu: () -> Unit
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    var watchFocused by remember { mutableStateOf(false) }
    var stopFocused by remember { mutableStateOf(false) }
    val (bg, ring) = cardColors(watchFocused || stopFocused)
    val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    val from = rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs
    val to = rec.programStopMs.takeIf { it > 0 } ?: rec.stopAtMs
    val fraction = if (rec.stopAtMs > rec.startedAtMs) ((nowMs - rec.startedAtMs).toFloat() / (rec.stopAtMs - rec.startedAtMs)) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(bg)
            .border(2.dp, ring, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtThumb(art, rec.channelLogo, rec.title, Modifier.width(156.dp).fillMaxHeight(), compact = true)
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecTag()
                Spacer(Modifier.width(10.dp))
                LiveText(rec.title, size = 20.sp, weight = FontWeight.Bold, marquee = watchFocused || stopFocused, modifier = Modifier.weight(1f, fill = false))
            }
            val status = when {
                rec.durationSec <= 0 -> "Starting…" + (rec.problem?.let { " ($it)" } ?: "")
                else -> durationLabel(rec.durationSec) + " recorded" +
                    (if (rec.sizeBytes > 0) " · " + sizeLabel(rec.sizeBytes) else "") +
                    (rec.problem?.let { " · $it" } ?: "")
            }
            LiveText(
                listOf(rec.channelName, time.format(Date(from)) + " – " + time.format(Date(to)), status).joinToString(" · "),
                size = 14.sp,
                color = colors.TextSecondary
            )
            ProgressLine(fraction, LIVE_RED, Modifier.fillMaxWidth(0.9f))
        }
        Spacer(Modifier.width(16.dp))
        PillButton("Watch", primary = true, modifier = watchModifier, onFocus = { watchFocused = it }, onClick = onWatch, onMenu = onMenu)
        Spacer(Modifier.width(10.dp))
        PillButton("Stop", primary = false, onFocus = { stopFocused = it }, onClick = onStop, onMenu = onMenu)
    }
}

@Composable
private fun PillButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> colors.TextPrimary
        primary -> colors.TextPrimary.copy(alpha = 0.85f)
        else -> com.nuvio.tv.livetv.ui.guideSurfaceVariant()
    }
    val fg = if (focused || primary) colors.Background else colors.TextPrimary
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(bg)
            .border(2.dp, if (focused) colors.FocusRing else com.nuvio.tv.livetv.ui.GuideGlassEdge, shape)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        LiveText(label, size = 16.sp, weight = FontWeight.SemiBold, color = fg)
    }
}

/** A scheduled show or a series: title, and a line under it. */
@Composable
private fun InfoCard(
    title: String,
    line: String,
    note: String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val (bg, ring) = cardColors(focused)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(2.dp, ring, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        LiveText(title, size = 17.sp, weight = FontWeight.SemiBold, marquee = focused)
        LiveText(line, size = 14.sp, color = colors.TextSecondary)
        note?.let { LiveText(it, size = 12.sp, color = colors.TextTertiary) }
    }
}

/** A finished recording: art with how much you've watched, then title, day and length. */
@Composable
private fun RecordedCard(
    rec: LiveTvRecording,
    art: RecordingArt?,
    resumeFraction: Float?,
    modifier: Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val failed = rec.status == RecordingStatus.FAILED
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ArtThumb(
            art, rec.channelLogo, rec.title,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .border(2.dp, if (focused) colors.FocusRing else Color.Transparent, shape)
        ) {
            resumeFraction?.let { f ->
                ProgressLine(
                    f, colors.Secondary,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
        LiveText(
            rec.title + (rec.episode?.let { " · $it" } ?: ""),
            size = 15.sp,
            weight = FontWeight.SemiBold,
            color = if (focused) colors.TextPrimary else colors.TextPrimary.copy(alpha = 0.9f),
            marquee = focused
        )
        LiveText(
            if (failed) rec.problem ?: "Couldn't be recorded"
            else listOfNotNull(
                shortDayLabel(rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs),
                durationLabel(rec.durationSec).takeIf { rec.durationSec > 0 },
                sizeLabel(rec.sizeBytes).takeIf { rec.sizeBytes > 0 }
            ).joinToString(" · ") + (rec.problem?.let { " · $it" } ?: ""),
            size = 13.sp,
            color = if (failed) colors.Error else colors.TextSecondary
        )
    }
}

@Composable
private fun RecordingActionDialog(
    title: String,
    detail: String?,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    // The same dark glass menu as the guide's.
    com.nuvio.tv.livetv.ui.LiveDialog(onDismiss = onDismiss, width = 480.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            LiveText(detail, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 4)
        }
        Spacer(Modifier.height(14.dp))
        // Scrolls when the choices don't all fit (the last ones used to sit off the bottom of
        // the screen, out of reach).
        Column(
            modifier = Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            actions.forEachIndexed { i, (label, action) ->
                com.nuvio.tv.livetv.ui.MenuItem(label, if (i == 0) Modifier.focusRequester(first) else Modifier, action)
            }
        }
    }
}

/** Hold OK → Information: what a recording is, how big it is and where it's kept. */
@Composable
private fun RecordingInfoDialog(rec: LiveTvRecording, savedOn: String, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    val aired = if (rec.programStartMs > 0 && rec.programStopMs > rec.programStartMs)
        day.format(Date(rec.programStartMs)) + ", " + time.format(Date(rec.programStartMs)) + " – " + time.format(Date(rec.programStopMs))
    else day.format(Date(rec.startedAtMs)) + ", " + time.format(Date(rec.startedAtMs))
    val rows = buildList {
        add("Channel" to rec.channelName)
        rec.episode?.let { add("Episode" to it) }
        add("Aired" to aired)
        add("Length" to if (rec.durationSec > 0) durationLabel(rec.durationSec) + (if (rec.isRecording) " so far" else "") else "—")
        add("Size" to if (rec.sizeBytes > 0) sizeLabel(rec.sizeBytes) + (if (rec.isRecording) " so far" else "") else "—")
        add("Recorded" to when {
            rec.isRecording && rec.fromCatchup -> "Saving from catch-up now"
            rec.isRecording -> "Recording now"
            rec.fromCatchup -> "From catch-up"
            else -> "Live, as it aired"
        })
        add("Saved on" to savedOn)
        rec.problem?.let { add("Note" to it) }
    }
    com.nuvio.tv.livetv.ui.LiveDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText(rec.title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { (k, v) ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    LiveText(k, color = NuvioTheme.colors.TextSecondary, size = 14.sp, modifier = Modifier.width(110.dp))
                    LiveText(v, size = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            com.nuvio.tv.livetv.ui.MenuItem("Close", Modifier.focusRequester(first), onDismiss)
        }
    }
}

/** "This Device" or the drive's name, for the folder a recording is in. */
private fun savedOnLabel(context: android.content.Context, dir: String): String {
    val choice = runCatching { RecordingStorage.choices(context) }.getOrDefault(emptyList())
        .filter { dir.startsWith(it.dir.absolutePath) }
        .maxByOrNull { it.dir.absolutePath.length }
    return choice?.label ?: if (dir.startsWith("/storage/emulated") || dir.startsWith("/data/")) "This Device" else "A drive that isn't plugged in"
}

private fun dateLabel(ms: Long): String {
    val day = shortDayLabel(ms)
    return day + ", " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}

/** "Today", "Tomorrow", "Yesterday", the weekday within a week, else the date. */
private fun shortDayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    fun dayIndex(t: Long): Long {
        cal.timeInMillis = t
        return cal.get(java.util.Calendar.YEAR) * 400L + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val diff = dayIndex(ms) - dayIndex(System.currentTimeMillis())
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff == -1L -> "Yesterday"
        diff in -6L..6L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

private fun durationLabel(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}
