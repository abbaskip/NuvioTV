package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvRepository
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme

data class LiveSearchHit(
    val channel: LiveChannel,
    /** The program to show on the card: what's on now, or the upcoming show that matched. */
    val program: EpgProgram?,
    val matchedProgram: Boolean
)

class LiveTvSearchResults(
    val hits: List<LiveSearchHit>,
    val use24h: Boolean,
    val open: (LiveSearchHit) -> Unit,
    val posterFor: suspend (LiveSearchHit) -> String?,
    internal val viewModel: LiveTvViewModel? = null,
    /** Playlist id -> name, shown on each card when you have more than one playlist. */
    val playlistNames: Map<String, String> = emptyMap(),
    /** The channel playing behind the search (picked once with OK), or null. */
    val previewKey: String? = null,
    /** Plays [LiveSearchHit] behind the search (TiviMate style); OK again on it goes full screen. */
    val preview: (LiveSearchHit) -> Unit = {}
)

private const val MAX_HITS = 40
private const val UPCOMING_WINDOW_MS = 6L * 60 * 60 * 1000

/**
 * Live TV matches for Nuvio's search screen: channels whose name matches, then channels
 * showing (now or in the next few hours) a program whose title matches.
 * [onOpened] is called after the channel starts playing, to show the full-screen player.
 */
@Composable
fun rememberLiveTvSearchResults(
    query: String,
    onOpened: () -> Unit,
    enabled: Boolean = true
): LiveTvSearchResults {
    // Live TV turned off for search (or hidden from the menu): Nuvio's search stays exactly as
    // it is without the fork — movies and series only. Live TV isn't even loaded here, so it
    // can't slow Nuvio's own search down.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val liveSettings by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = liveSettings
    // What this search shows (by where it was opened from; see rememberSearchScope).
    if (s == null || !enabled) {
        return remember { LiveTvSearchResults(emptyList(), false, {}, { null }) }
    }
    return rememberLiveTvSearchResultsEnabled(query, onOpened)
}

@Composable
private fun rememberLiveTvSearchResultsEnabled(
    query: String,
    onOpened: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
): LiveTvSearchResults {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val playlistNames by viewModel.playlistNames.collectAsStateWithLifecycle()
    val q = query.trim()
    // The channel playing behind the search, if one was picked.
    var previewKey by remember { mutableStateOf<String?>(null) }

    // Search once typing pauses (not on every letter), and off the main thread, so typing
    // stays smooth on slower devices with big playlists.
    val settled by produceState(initialValue = q, q) {
        if (q.length >= 2) delay(300)
        value = q
    }
    val searchLive = true
    val hits by produceState(initialValue = emptyList<LiveSearchHit>(), settled, ui.allVisibleChannels, programs, now, searchLive) {
        val q = settled
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (!searchLive || q.length < 2) return@withContext emptyList()
            val out = ArrayList<LiveSearchHit>()
            val seen = HashSet<String>()
            for (c in ui.allVisibleChannels) {
                if (out.size >= MAX_HITS) break
                if (c.name.contains(q, ignoreCase = true) || c.number.toString() == q) {
                    val current = programs[c.key]?.firstOrNull { now >= it.startMs && now < it.stopMs }
                    out += LiveSearchHit(c, current, matchedProgram = false)
                    seen += c.key
                }
            }
            if (out.size < MAX_HITS) {
                for (c in ui.allVisibleChannels) {
                    if (out.size >= MAX_HITS) break
                    if (c.key in seen) continue
                    val match = programs[c.key]?.firstOrNull {
                        it.stopMs > now && it.startMs < now + UPCOMING_WINDOW_MS && it.title.contains(q, ignoreCase = true)
                    } ?: continue
                    out += LiveSearchHit(c, match, matchedProgram = true)
                }
            }
            out
        }
    }

    return LiveTvSearchResults(
        hits = hits,
        use24h = settings.use24HourClock,
        open = { hit ->
            viewModel.preview(hit.channel)
            viewModel.markFullscreenOpened()
            onOpened()
        },
        posterFor = { hit -> viewModel.posterFor(hit.program, hit.channel) },
        viewModel = viewModel,
        playlistNames = playlistNames,
        previewKey = previewKey,
        preview = { hit ->
            if (viewModel.settings.value.searchPreview) {
                viewModel.preview(hit.channel)
                previewKey = hit.channel.key
            }
        }
    )
}

/**
 * The Live TV row in Nuvio's search, drawn like the addon rows around it.
 * [entryFocusRequester] goes on the first card so the search screen can move focus into the row;
 * [upFocusRequester] is where D-pad up goes from the row.
 *
 * OK on a channel plays it behind the search (TiviMate style), to see that it loads and how it
 * looks; OK on the one playing opens it full screen. (With the preview off in settings, OK
 * opens it full screen straight away, as before.)
 */
@Composable
fun LiveTvSearchRow(
    results: LiveTvSearchResults,
    modifier: Modifier = Modifier,
    entryFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    posterCardStyle: com.nuvio.tv.ui.components.PosterCardStyle = com.nuvio.tv.ui.components.PosterCardDefaults.Style
) {
    if (results.hits.isEmpty()) return
    var menuFor by remember { mutableStateOf<LiveChannel?>(null) }
    menuFor?.let { ch ->
        results.viewModel?.let { vm -> ChannelQuickMenu(ch, vm, onDismiss = { menuFor = null }) }
    }
    val previewOn = results.viewModel?.settings?.collectAsStateWithLifecycle()?.value?.searchPreview ?: false
    SearchResultsRow(title = "Live TV", modifier = modifier) {
        itemsIndexed(results.hits, key = { _, h -> h.channel.key }) { index, hit ->
            LiveSearchCard(
                hit = hit,
                playlistName = results.playlistNames[hit.channel.sourceId],
                posterFor = results.posterFor,
                posterCardStyle = posterCardStyle,
                playing = hit.channel.key == results.previewKey,
                modifier = Modifier
                    .then(if (index == 0 && entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier)
                    .focusProperties { if (upFocusRequester != null) up = upFocusRequester },
                onClick = {
                    if (previewOn && hit.channel.key != results.previewKey) results.preview(hit) else results.open(hit)
                },
                onLongClick = { menuFor = hit.channel }
            )
        }
    }
}

/**
 * The channel picked in search, playing full size behind the results under a dark veil so the
 * results stay readable. Goes when search closes (unless it was opened full screen).
 */
@Composable
fun LiveTvSearchPreview(results: LiveTvSearchResults, modifier: Modifier = Modifier) {
    val vm = results.viewModel ?: return
    if (results.previewKey == null) return
    androidx.compose.runtime.DisposableEffect(vm) {
        vm.playback.attach()
        onDispose { vm.playback.detach() }
    }
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    // Back on search after a details page (the player was let go meanwhile): start it again.
    LaunchedEffect(results.previewKey) {
        val key = results.previewKey ?: return@LaunchedEffect
        if (vm.playback.state.value.channelKey != key) {
            results.hits.firstOrNull { it.channel.key == key }?.let { vm.preview(it.channel) }
        }
    }
    Box(modifier = modifier) {
        LivePlayerSurface(
            player = vm.playback.player,
            modifier = Modifier.fillMaxSize(),
            useSurfaceView = false,
            onAttached = { vm.playback.onSurfaceAttached() }
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
        playback.error?.let {
            PlayerStatusMessage(
                "Reconnecting…", it,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                compact = true
            )
        }
    }
}

/**
 * One Live TV result: the show's poster (or the channel's logo), with a LIVE / UPCOMING tag,
 * then the show, "CH: channel" and the playlist, like Nuvio's addon results.
 */
@Composable
private fun LiveSearchCard(
    hit: LiveSearchHit,
    playlistName: String?,
    posterFor: suspend (LiveSearchHit) -> String?,
    posterCardStyle: com.nuvio.tv.ui.components.PosterCardStyle,
    playing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val now = System.currentTimeMillis()
    val p = hit.program
    val live = p != null && now >= p.startMs && now < p.stopMs
    val upcoming = p != null && p.startMs > now
    val sports = com.nuvio.tv.livetv.data.LiveTvPosterResolver.isSports(hit.channel, p)
    val poster by produceState<String?>(initialValue = if (sports) null else p?.icon, p?.title, hit.channel.key) {
        if (sports) return@produceState
        // Give Nuvio's own addon search a head start, so poster lookups never compete with it.
        kotlinx.coroutines.delay(2_500)
        posterFor(hit)?.let { value = it }
    }
    val title = p?.title?.takeIf { !LiveTvRepository.isPlaceholderTitle(it) } ?: hit.channel.name
    SearchResultCard(
        title = title,
        lines = listOfNotNull("CH: ${hit.channel.name}", playlistName),
        poster = poster,
        logo = hit.channel.logo,
        posterCardStyle = posterCardStyle,
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        badge = {
            val tag = when {
                playing -> "PLAYING"
                live -> "LIVE"
                upcoming -> "UPCOMING"
                else -> null
            }
            tag?.let {
                LiveText(
                    it,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (live || playing) NuvioTheme.colors.Error else Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White,
                    size = 10.sp,
                    weight = FontWeight.Bold
                )
            }
            if (live) {
                ProgressBar(p!!.progress(now), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
            }
        }
    )
}

private fun startsInLabel(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(1)
    return when {
        min < 60 -> "in $min min"
        min < 24 * 60 -> "in ${min / 60}h ${min % 60}m"
        else -> "in ${min / (24 * 60)} days"
    }
}

/**
 * Hands a program title from Live TV to Nuvio's search screen, which picks it up once and
 * runs the search. From there the normal detail page streams it through your addons / debrid.
 */
object LiveTvSearchBridge {
    /**
     * Where search was opened from: the guide ([GUIDE]: its Search button, the Search key or
     * the channel menu), On Demand ([ON_DEMAND]), or anywhere else ([HOME]: everything shows).
     * What the guide and On Demand show is set in Live TV settings. Back to [HOME] when the
     * search screen closes.
     */
    val origin = kotlinx.coroutines.flow.MutableStateFlow(HOME)
    const val HOME = "home"
    const val GUIDE = "guide"
    const val ON_DEMAND = "ondemand"

    /** The title waiting to be searched, or null. The search screen collects this. */
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun request(programTitle: String) {
        pending.value = cleanTitle(programTitle).ifBlank { programTitle.trim() }
    }

    fun consume(): String? = pending.value.also { pending.value = null }

    private val leadingTags = Regex("""^\s*(new|live|premiere|repeat|re-?run|encore|movie|film)\s*[:!\-–]\s*""", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("""\s*[\[(][^\])]*[\])]\s*""")
    private val episodeCode = Regex("""\b[Ss]\d{1,2}\s*[Ee]\d{1,3}\b""")
    private val trailingEpisode = Regex("""\s*[-:–]\s*(episode|ep\.?|part)\s*\d+.*$""", RegexOption.IGNORE_CASE)

    /** "24/7", "24-7", "24/7:", "24x7", "(24/7)"… (marks a channel, never part of a show's name). */
    private val twentyFourSeven = Regex("""(?i)[\[(]?\b24\s*[/\\|\-x]?\s*7\b[\])]?\s*[:|\-–]?""")

    /** "(2012)" or "[2018]" at the end of a title: its release year, not part of the name. */
    private val trailingYear = Regex("""\s*[(\[]\s*((?:19|20)\d{2})\s*[)\]]\s*$""")

    /** The release year a title ends with ("American Pie: Reunion (2012)" -> 2012), if any. */
    internal fun yearInTitle(raw: String): Int? = trailingYear.find(raw.trim())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Small raised letters some providers put in titles and names ("ᴸᴵⱽᴱ", "ᴺᴱᵂ", "ᴴᴰ", "ʟɪᴠᴇ"):
     * superscript and small-capital letters, as one string of ordinary letters per run.
     */
    private const val SMALL = "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻᶦᶫᶰᶸᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ⁰¹²³⁴⁵⁶⁷⁸⁹⁺"
    private const val PLAIN = "abdeghijklmnoprtuvwabcdefghijklmnoprstuvwxyzilnuabcdefghijklmnoprstuvwyz0123456789+"
    private val smallRun = Regex("[$SMALL]+(?:\\s*[$SMALL]+)*")

    private fun plainOf(run: String): String = buildString {
        run.forEach { ch -> val i = SMALL.indexOf(ch); if (i >= 0) append(PLAIN[i]) }
    }

    /** The small raised words in [raw], as plain lower-case words ("ᴸᴵⱽᴱ" -> "live"). */
    internal fun smallWords(raw: String): List<String> = smallRun.findAll(raw).map { plainOf(it.value) }.toList()

    /** [raw] without the small raised words in [words] ("ᴸᴵⱽᴱ" when [words] has "live"). */
    internal fun withoutSmallWords(raw: String, words: Set<String>): String =
        if (raw.none { it in SMALL }) raw
        else raw.replace(smallRun) { m -> if (plainOf(m.value) in words) " " else m.value }.replace(Regex("\\s+"), " ").trim()

    /** [raw] without its small raised letters ("Monday Night Football ᴸᴵⱽᴱ" -> "Monday Night Football"). */
    internal fun withoutSmallMarks(raw: String): String =
        if (raw.none { it in SMALL }) raw else raw.replace(smallRun, " ").replace(Regex("\\s+"), " ").trim()

    internal fun cleanTitle(raw: String): String {
        // Small raised "ᴸᴵⱽᴱ" / "ᴺᴱᵂ" / "ᴴᴰ" marks aren't part of the name: searching with them
        // in found nothing, so those shows got no poster.
        var t = withoutSmallMarks(raw).trim().replace(trailingYear, "").replace(twentyFourSeven, " ")
        repeat(2) { t = t.replace(leadingTags, "") }
        t = t.replace(bracketed, " ")
            .replace(episodeCode, " ")
            .replace(trailingEpisode, "")
        return t.replace(Regex("""\s+"""), " ").trim().trimEnd(':', '-', '–').trim()
    }
}

/**
 * Long-press menu for a Live TV search result: favorites, groups, rename, renumber, hide.
 */
@Composable
private fun ChannelQuickMenu(channel: LiveChannel, viewModel: LiveTvViewModel, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf("menu") }
    val first = remember { FocusRequester() }
    when (step) {
        "rename" -> TextInputDialog(
            title = "Rename channel",
            initial = channel.name,
            hint = "Leave empty to use the playlist name",
            confirmLabel = "Save",
            onDismiss = onDismiss,
            onConfirm = { viewModel.renameChannel(channel, it); onDismiss() }
        )
        "number" -> TextInputDialog(
            title = "Channel number",
            initial = channel.number.toString(),
            hint = "Leave empty to use the playlist number",
            confirmLabel = "Save",
            numeric = true,
            onDismiss = onDismiss,
            onConfirm = { viewModel.setChannelNumber(channel, it.trim().toIntOrNull()); onDismiss() }
        )
        "newgroup" -> TextInputDialog(
            title = "New group",
            initial = "",
            hint = "Group name",
            confirmLabel = "Save",
            onDismiss = onDismiss,
            onConfirm = { name -> viewModel.createGroupWith(name, channel); onDismiss() }
        )
        "group" -> GroupPickerDialog(
            channel = channel,
            groups = viewModel.uiState.value.customGroups,
            onDismiss = onDismiss,
            onPick = { g -> viewModel.addToGroup(g.id, channel); onDismiss() },
            onNewGroup = { step = "newgroup" }
        )
        else -> {
            val isFavorite = channel.key in viewModel.userState.value.favorites
            LiveDialog(onDismiss = onDismiss, width = 440.dp) {
                LiveText("${channel.number}  ${channel.name}", size = 20.sp, weight = FontWeight.Bold)
                LiveText(channel.group, color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MenuItem(if (isFavorite) "Remove from favorites" else "Add to favorites", Modifier.focusRequester(first)) {
                        viewModel.toggleFavorite(channel)
                        onDismiss()
                    }
                    MenuItem("Add to group…") { step = "group" }
                    MenuItem("Rename channel") { step = "rename" }
                    MenuItem("Change channel number") { step = "number" }
                    MenuItem("Hide channel") {
                        viewModel.hideChannel(channel)
                        onDismiss()
                    }
                }
            }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
        }
    }
}


/** What a search shows: Live TV, Nuvio's movies and series, your provider's On Demand. */
data class SearchScope(
    val live: Boolean,
    val catalog: Boolean,
    val onDemand: Boolean,
    /** The order the result sections appear in: "live", "ondemand" and "catalog". */
    val order: List<String> = SEARCH_SECTIONS
)

/** Nuvio's search sections, in their default order. */
val SEARCH_SECTIONS = listOf("live", "ondemand", "catalog")

/** A saved search order, with anything missing put back at the end (in the default order). */
fun parseSearchOrder(raw: String?): List<String> {
    val saved = raw.orEmpty().split(',').map { it.trim() }.filter { it in SEARCH_SECTIONS }.distinct()
    return saved + SEARCH_SECTIONS.filter { it !in saved }
}

/**
 * What this search shows, by where it was opened from: from the guide or On Demand, what's set
 * for it in Live TV settings (by default the guide shows Live TV, On Demand shows On Demand);
 * from anywhere else (Home, the side menu), everything.
 */
@Composable
fun rememberSearchScope(): SearchScope {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val settings by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val origin by LiveTvSearchBridge.origin.collectAsStateWithLifecycle()
    val s = settings ?: com.nuvio.tv.livetv.model.LiveTvSettings()
    return when (origin) {
        LiveTvSearchBridge.GUIDE -> SearchScope(s.guideSearchLive, s.guideSearchCatalog, s.guideSearchOnDemand)
        LiveTvSearchBridge.ON_DEMAND -> SearchScope(s.onDemandSearchLive, s.onDemandSearchCatalog, s.onDemandSearchOnDemand)
        else -> SearchScope(live = true, catalog = true, onDemand = true)
    }.let { if (!it.live && !it.catalog && !it.onDemand) SearchScope(true, true, true) else it }
        .copy(order = parseSearchOrder(s.searchOrder))
}
