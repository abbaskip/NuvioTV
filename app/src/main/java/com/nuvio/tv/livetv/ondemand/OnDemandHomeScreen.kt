package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicHomeContent
import com.nuvio.tv.ui.screens.home.GridHomeContent
import com.nuvio.tv.ui.screens.home.GridItem
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.ModernCarouselRowBuildCache
import com.nuvio.tv.ui.screens.home.ModernHomeContent
import com.nuvio.tv.ui.screens.home.ModernHomePresentationInput
import com.nuvio.tv.ui.screens.home.ModernHomePresentationState
import com.nuvio.tv.ui.screens.home.buildModernHomePresentation
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * On Demand, Nuvio-home style: your provider's groups as rows, in whichever home layout Nuvio is
 * set to (Classic, Grid or Modern), with the same look and behaviour as Home — plus a settings
 * button (top right, or the Menu button) for Manage VOD Groups.
 *
 * [homeSettings] is Home's own state: every layout option (poster size, hero, labels…) comes from
 * it, so this page always matches Home.
 */
@Composable
fun OnDemandHomeScreen(
    homeSettings: HomeUiState?,
    onOpenDetail: (itemId: String, itemType: String, addonBaseUrl: String?, onDemandUid: String) -> Unit,
    onPlay: (url: String, title: String, type: String, poster: String?) -> Unit,
    /** The search button: Nuvio's own search. */
    onOpenSearch: () -> Unit,
    /** Home's own trailer lookup and results (trailers follow Nuvio's trailer settings). */
    trailers: OnDemandTrailers? = null,
    viewModel: OnDemandViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.home.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadHome() }

    var showManage by remember { mutableStateOf(false) }
    // The card last highlighted, for the group menu.
    var lastFocusedCard by remember { mutableStateOf<String?>(null) }
    var cardMenu by remember { mutableStateOf<VodItem?>(null) }
    var providerItem by remember { mutableStateOf<VodItem?>(null) }
    // "See all" on a row: that row in Nuvio's See all grid, over this page.
    var seeAllUid by remember { mutableStateOf<String?>(null) }
    // The Sort list over See all.
    var sortingUid by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    var focusState by remember { mutableStateOf(HomeScreenFocusState()) }
    var gridFocusState by remember { mutableStateOf(HomeScreenFocusState()) }

    // Leaving this page (Back, the menu, opening a title): stop the trailer straight away, before
    // the switch animates. Tearing the trailer's player down mid-switch, while Home starts up its
    // own screen, made going back to Home lag.
    // On Demand's own trailer player. The app's shared one belongs to Home: sharing it meant the
    // same player was attached to both pages while going back, and Android closed the app.
    val ownPool = remember {
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, OnDemandPlayerEntryPoint::class.java)
        com.nuvio.tv.core.player.TrailerPlayerPool(
            context.applicationContext,
            entry.playerSettingsDataStore(),
            entry.trailerSettingsDataStore()
        )
    }
    androidx.compose.runtime.DisposableEffect(ownPool) { onDispose { runCatching { ownPool.release() } } }
    // Home's hero backdrop, kept so it can be put back when you return to Home (the layout keeps
    // one app-wide "last backdrop", and this page would otherwise leave its own there).
    androidx.compose.runtime.LaunchedEffect(Unit) { OnDemandBackdropGuard.enter() }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE, androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // Free the video hardware before Home needs it.
                    runCatching { ownPool.yield() }
                    OnDemandBackdropGuard.leave()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    runCatching { ownPool.reclaim() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val base = homeSettings ?: HomeUiState()
    // Movies or Series: one at a time (mixed rows were confusing). Remembered while the app runs.
    var kind by remember { mutableStateOf(OnDemandHomeTab.last) }
    val hasMovies = state.rows.any { it.type == com.nuvio.tv.domain.model.ContentType.MOVIE }
    val hasSeries = state.rows.any { it.type == com.nuvio.tv.domain.model.ContentType.SERIES }
    // Only one kind available: show that one.
    val shownKind = when {
        kind == VodKind.SERIES && !hasSeries && hasMovies -> VodKind.MOVIE
        kind == VodKind.MOVIE && !hasMovies && hasSeries -> VodKind.SERIES
        else -> kind
    }
    val wantedType = if (shownKind == VodKind.SERIES) com.nuvio.tv.domain.model.ContentType.SERIES else com.nuvio.tv.domain.model.ContentType.MOVIE
    val rows = remember(state.rows, wantedType) { state.rows.filter { it.type == wantedType } }
    fun switchTo(k: VodKind) {
        if (k == kind) return
        kind = k
        OnDemandHomeTab.last = k
        // A fresh start in the other list (the saved position belongs to the old one).
        focusState = HomeScreenFocusState()
        gridFocusState = HomeScreenFocusState()
    }
    val homeRows = remember(rows) { rows.map { HomeRow.Catalog(it) } }
    val gridItems = remember(rows) {
        buildList {
            rows.forEach { r ->
                add(GridItem.SectionDivider(r.catalogName, r.catalogId, r.addonBaseUrl, r.addonId, r.apiType))
                r.items.forEach { add(GridItem.Content(it, r.addonBaseUrl, r.catalogId, r.catalogName, r.addonId)) }
                // The Grid layout only shows two or three rows of each group and cuts the rest
                // off; without a See all tile there (a group whose titles were all loaded already)
                // the rest of the group couldn't be reached. Any group that may not fit gets one.
                if (r.hasMore || r.items.size > 6) add(GridItem.SeeAll(r.catalogId, r.addonId, r.addonBaseUrl, r.apiType))
            }
        }
    }
    val modernCache = remember { ModernCarouselRowBuildCache() }
    val modernPresentation by produceState(ModernHomePresentationState(), rows, base.homeLayout) {
        if (base.homeLayout != HomeLayout.MODERN) return@produceState
        value = withContext(Dispatchers.Default) {
            buildModernHomePresentation(
                input = ModernHomePresentationInput(
                    homeRows = homeRows,
                    catalogRows = rows,
                    continueWatchingItems = emptyList(),
                    upcomingItems = emptyList(),
                    useLandscapePosters = base.modernLandscapePostersEnabled,
                    showCatalogTypeSuffix = false,
                    showFullReleaseDate = base.showFullReleaseDate,
                    showImdbRatings = base.homeImdbRatingsVisibility.showRatings,
                    localeTag = com.nuvio.tv.LocaleCache.localeTag
                ),
                cache = modernCache,
                context = context
            )
        }
    }
    // Home's settings, with this page's rows (no Continue Watching, no addon hero, no trailers:
    // those belong to Home).
    val ui = base.copy(
        catalogRows = rows,
        homeRows = homeRows,
        gridItems = gridItems,
        continueWatchingItems = emptyList(),
        upcomingItems = emptyList(),
        heroItems = emptyList(),
        heroSectionEnabled = false,
        isLoading = state.loading,
        error = null,
        modernHomePresentation = modernPresentation,
        heroEnrichmentEnabled = false,
        // Standalone catalogue artwork comes only from the provider.
        focusedPosterBackdropTrailerEnabled = false,
        catalogAddonNameEnabled = false,
        catalogTypeSuffixEnabled = false
    )
    val posterCardStyle = remember(ui.posterCardWidthDp, ui.posterCardCornerRadiusDp) {
        PosterCardStyle(
            width = ui.posterCardWidthDp.dp,
            height = (ui.posterCardWidthDp * 1.5f).roundToInt().dp,
            cornerRadius = ui.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    // Open the provider's details without searching external metadata catalogues.
    val open: (String) -> Unit = { metaId ->
        val item = viewModel.homeItem(metaId)
        if (item != null && !opening) {
            opening = true
            scope.launch {
                try {
                    when (val t = viewModel.targetFor(item)) {
                        is OnDemandTarget.Details -> onOpenDetail(t.itemId, t.itemType, t.addonBaseUrl, t.uid)
                        is OnDemandTarget.Provider -> providerItem = item
                    }
                } finally { opening = false }
            }
        }
    }
    val onNavigateToDetail: (String, String, String) -> Unit = { id, _, _ -> open(id) }
    val onItemFocus: (MetaPreview) -> Unit = { lastFocusedCard = it.id; viewModel.focusHome(it.id) }
    // Grid layout: "On Demand - <playlist>" at the top, for the title you're on.
    val homeHeader by viewModel.homeHeader.collectAsStateWithLifecycle()
    val loadMore: (String, String, String) -> Unit = { catalogId, _, _ -> viewModel.loadMoreHome(catalogId) }
    // The watched mark, as on Home. A stable lambda that reads the latest watched lists, so
    // a change only redraws the cards (not the whole page).
    val watchedSets = viewModel.watched.collectAsStateWithLifecycle()
    val isWatched: (MetaPreview) -> Boolean = remember(viewModel) {
        { card: MetaPreview -> viewModel.isWatched(card, watchedSets.value) }
    }
    // Holding OK on a card: Home's options (Go to details, Add to Library, Mark as watched),
    // using the title as your addons know it. Titles only your provider has open their details.
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsViewModel = hiltViewModel()
    val optionsState by posterOptions.controller.state.collectAsStateWithLifecycle()
    var optionsUid by remember { mutableStateOf<String?>(null) }
    val longPress: (MetaPreview) -> Unit = { card ->
        val item = viewModel.homeItem(card.id)
        if (item != null && !opening) {
            opening = true
            scope.launch {
                try {
                    val target = viewModel.optionsTarget(card.id)
                    if (target != null) {
                        optionsUid = item.uid
                        posterOptions.controller.show(target.first, target.second)
                    } else {
                        providerItem = item
                    }
                } finally { opening = false }
            }
        }
    }
    val onCatalogLongPress: (MetaPreview, String) -> Unit = { card, _ -> longPress(card) }

    val trailerUrls = emptyMap<String, String>()
    val trailerAudioUrls = emptyMap<String, String>()
    val requestTrailerById: (String, String, String?, String) -> Unit = { _, _, _, _ -> }
    val requestTrailerForItem: (MetaPreview) -> Unit = { }

    // Back opens the menu, like on every other menu page. Added here because otherwise Back
    // went to the page underneath (Home) on this one. The layouts' own Back handling (scroll a
    // row back to its start, close a full screen trailer) and any open dialog still come first,
    // and with the menu open Back is left to the app (exit).
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    val sidebarOpen = com.nuvio.tv.LocalSidebarExpanded.current
    val topFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val topContentFocus = com.nuvio.tv.LocalContentFocusRequester.current
    androidx.activity.compose.BackHandler(enabled = !sidebarOpen) { openSidebar() }

    androidx.compose.runtime.CompositionLocalProvider(com.nuvio.tv.core.player.LocalTrailerPlayerPool provides ownPool) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // Menu button: Manage VOD Groups, from anywhere on the page.
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) {
                    // Manage the provider groups without looking up title metadata.
                    val item = lastFocusedCard?.let { viewModel.homeItem(it) }
                    if (item != null) cardMenu = item else showManage = true
                    true
                } else false
            }
    ) {
        when {
            state.loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            rows.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LiveText("Nothing to show yet", size = 20.sp, weight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                LiveText(
                    "Turn on On Demand for a playlist in Settings → Live TV, or show some groups with the settings button.",
                    size = 14.sp, color = NuvioTheme.colors.TextSecondary
                )
            }
            seeAllUid != null -> {
                val seeAllRow = state.rows.firstOrNull { it.catalogId == seeAllUid }
                OnDemandSeeAll(
                    row = seeAllRow,
                    posterCardStyle = posterCardStyle,
                    showLabels = ui.posterLabelsEnabled,
                    onOpen = { open(it) },
                    onFocus = onItemFocus,
                    onLoadMore = { seeAllUid?.let { viewModel.loadMoreHome(it) } },
                    onBack = { seeAllUid = null },
                    // The group's own sort, else Default Sort Order.
                    sortLabel = seeAllUid?.let { uid ->
                        sortLabel(state.groups.firstOrNull { it.uid == uid }?.sort) ?: viewModel.defaultSortLabel()
                    },
                    onSort = { sortingUid = seeAllUid },
                    isWatched = isWatched,
                    onLongPress = longPress
                )
            }
            else -> when (ui.homeLayout) {
                HomeLayout.CLASSIC -> ClassicHomeContent(
                    uiState = ui,
                    posterCardStyle = posterCardStyle,
                    focusState = focusState,
                    trailerPreviewUrls = trailerUrls,
                    trailerPreviewAudioUrls = trailerAudioUrls,
                    onNavigateToDetail = onNavigateToDetail,
                    onContinueWatchingClick = {},
                    onContinueWatchingStartFromBeginning = {},
                    onContinueWatchingPlayManually = {},
                    showContinueWatchingManualPlayOption = false,
                    onNavigateToCatalogSeeAll = { catalogId, _, _ -> seeAllUid = catalogId },
                    onNavigateToFolderDetail = { _, _ -> },
                    onRemoveContinueWatching = { _, _, _, _ -> },
                    isCatalogItemWatched = isWatched,
                    onCatalogItemLongPress = onCatalogLongPress,
                    onRequestTrailerPreview = requestTrailerForItem,
                    onItemFocus = onItemFocus,
                    catalogSeeAllLabel = "See all",
                    onSaveFocusState = { vi, vo, rk, ikm, m, ma, ri, ii ->
                        focusState = focusState.copy(
                            verticalScrollIndex = vi, verticalScrollOffset = vo, focusedRowKey = rk,
                            focusedItemKeyByRow = ikm, catalogRowScrollStates = m, catalogRowScrollAnchors = ma,
                            focusedRowIndex = ri, focusedItemIndex = ii, hasSavedFocus = true
                        )
                    },
                    onFocusedRowKeyChanged = {},
                    scrollToTopTrigger = 0,
                    onRequestLazyCatalogLoad = {}
                )
                HomeLayout.GRID -> GridHomeContent(
                    uiState = ui,
                    gridFocusState = gridFocusState,
                    onNavigateToDetail = onNavigateToDetail,
                    onContinueWatchingClick = {},
                    onContinueWatchingStartFromBeginning = {},
                    onContinueWatchingPlayManually = {},
                    showContinueWatchingManualPlayOption = false,
                    onNavigateToCatalogSeeAll = { catalogId, _, _ -> seeAllUid = catalogId },
                    onNavigateToFolderDetail = { _, _ -> },
                    onRemoveContinueWatching = { _, _, _, _ -> },
                    isCatalogItemWatched = isWatched,
                    onCatalogItemLongPress = onCatalogLongPress,
                    posterCardStyle = posterCardStyle,
                    onItemFocus = onItemFocus,
                    catalogSeeAllLabel = "See all",
                    onSaveGridFocusState = { vi, vo, _ ->
                        gridFocusState = gridFocusState.copy(verticalScrollIndex = vi, verticalScrollOffset = vo, hasSavedFocus = true)
                    },
                    onFocusedRowKeyChanged = {},
                    scrollToTopTrigger = 0
                )
                HomeLayout.MODERN -> ModernHomeContent(
                    uiState = ui,
                    modernPresentation = modernPresentation,
                    focusState = focusState,
                    enrichingItemId = remember { mutableStateOf<String?>(null) },
                    enrichedPreviews = remember { mutableStateOf<Map<String, MetaPreview>>(emptyMap()) },
                    failedEnrichmentIds = remember { mutableStateOf<Set<String>>(emptySet()) },
                    trailerPreviewUrls = trailerUrls,
                    trailerPreviewAudioUrls = trailerAudioUrls,
                    onNavigateToDetail = onNavigateToDetail,
                    onContinueWatchingClick = {},
                    onContinueWatchingStartFromBeginning = {},
                    onContinueWatchingPlayManually = {},
                    showContinueWatchingManualPlayOption = false,
                    onRequestTrailerPreview = requestTrailerById,
                    onLoadMoreCatalog = loadMore,
                    onRemoveContinueWatching = { _, _, _, _ -> },
                    isCatalogItemWatched = isWatched,
                    onCatalogItemLongPress = onCatalogLongPress,
                    onNavigateToFolderDetail = { _, _ -> },
                    onItemFocus = onItemFocus,
                    onPreloadAdjacentItem = {},
                    onSaveFocusState = { vi, vo, rk, ikm, m, ma, ri, ii ->
                        focusState = focusState.copy(
                            verticalScrollIndex = vi, verticalScrollOffset = vo, focusedRowKey = rk,
                            focusedItemKeyByRow = ikm, catalogRowScrollStates = m, catalogRowScrollAnchors = ma,
                            focusedRowIndex = ri, focusedItemIndex = ii, hasSavedFocus = true
                        )
                    },
                    onFocusedRowKeyChanged = {},
                    scrollToTopTrigger = 0,
                    onRequestLazyCatalogLoad = {}
                )
            }
        }

        // Top right: Nuvio's search and Manage VOD Groups (not over See all).
        if (seeAllUid == null) Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 32.dp)
                // Down from these buttons: into the titles. In the Grid layout there's nothing
                // directly below them for Down to find, so it went nowhere (you had to go out to
                // the menu and back); then it goes to where the page's highlight was.
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    if (topFocusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) true
                    else { runCatching { topContentFocus.requestFocus() }; true }
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasMovies && hasSeries) {
                KindTab("Movies", selected = shownKind == VodKind.MOVIE) { switchTo(VodKind.MOVIE) }
                KindTab("Series", selected = shownKind == VodKind.SERIES) { switchTo(VodKind.SERIES) }
                Spacer(Modifier.size(6.dp))
            }
            TopButton(Icons.Default.Search, onClick = onOpenSearch)
            TopButton(Icons.Default.Tune, onClick = { showManage = true })
        }
    }

    }

    sortingUid?.let { uid ->
        val saved = state.groups.firstOrNull { it.uid == uid }?.sort
        VodSortPicker(
            title = "Sort " + (state.rows.firstOrNull { it.catalogId == uid }?.catalogName ?: "this group"),
            current = VodSort.parse(saved)?.first,
            useDefaultLabel = "Default (${viewModel.defaultSortLabel()})",
            onPick = { picked -> sortingUid = null; viewModel.setGroupSort(uid, picked) },
            onDismiss = { sortingUid = null }
        )
    }

    cardMenu?.let { item ->
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        com.nuvio.tv.livetv.ui.LiveDialog(onDismiss = { cardMenu = null }, width = 460.dp) {
            com.nuvio.tv.livetv.ui.LiveText(OnDemandDatabase.displayTitle(item.name), size = 20.sp, weight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2)
            androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                com.nuvio.tv.livetv.ui.MenuItem("Manage VOD Groups", Modifier.focusRequester(first)) { cardMenu = null; showManage = true }
            }
        }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    }
    if (showManage) {
        ManageVodGroupsPanel(
            groups = state.groups,
            defaultSortLabel = viewModel.defaultSortLabel(),
            onDone = { edited -> showManage = false; viewModel.saveGroups(edited) },
            onReset = { showManage = false; viewModel.resetGroups() },
            onDismiss = { showManage = false }
        )
    }
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = optionsState,
        controller = posterOptions.controller,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onOpenDetail(id, type, addonBaseUrl.ifBlank { null }, optionsUid.orEmpty())
        }
    )
    providerItem?.let { item ->
        ProviderDetailDialog(
            item = item,
            viewModel = viewModel,
            onDismiss = { providerItem = null },
            onPlay = { url, title, poster ->
                providerItem = null
                onPlay(url, title, if (item.kind == VodKind.SERIES) "series" else "movie", poster)
            }
        )
    }
}

@Composable
private fun TopButton(icon: ImageVector, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(if (focused) colors.FocusRing else Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.18f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) colors.Background else colors.TextPrimary, modifier = Modifier.size(24.dp))
    }
}

/** Home's trailer lookup, shared with the On Demand page (results keyed by the real title id). */
class OnDemandTrailers(
    val urls: Map<String, String>,
    val audioUrls: Map<String, String>,
    val requestById: (itemId: String, title: String, releaseInfo: String?, apiType: String) -> Unit,
    val requestItem: (MetaPreview) -> Unit
)

/** The Movies / Series choice, remembered while the app runs. */
object OnDemandHomeTab {
    @Volatile var last: VodKind = VodKind.MOVIE
}

@Composable
private fun KindTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(shape)
            .background(
                when {
                    focused -> colors.FocusRing
                    selected -> colors.Secondary.copy(alpha = 0.35f)
                    else -> Color.Black.copy(alpha = 0.45f)
                }
            )
            .border(1.dp, Color.White.copy(alpha = if (focused || selected) 0f else 0.18f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        LiveText(
            label, size = 15.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (focused) colors.Background else colors.TextPrimary
        )
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface OnDemandPlayerEntryPoint {
    fun playerSettingsDataStore(): com.nuvio.tv.data.local.PlayerSettingsDataStore
    fun trailerSettingsDataStore(): com.nuvio.tv.data.local.TrailerSettingsDataStore
}

/**
 * Home's hero keeps one app-wide "last backdrop shown" (for a seamless return to Home). This
 * page uses the same layout, so it would leave its own backdrop there: Home then showed the On
 * Demand picture. The backdrop Home had is kept on entering and put back on leaving.
 */
object OnDemandBackdropGuard {
    @Volatile private var homeBackdrop: String? = null
    @Volatile private var inside = false

    fun enter() {
        if (inside) return
        inside = true
        homeBackdrop = com.nuvio.tv.ui.screens.home.HeroBackdropState.lastDisplayedUrl
    }

    fun leave() {
        if (!inside) return
        com.nuvio.tv.ui.screens.home.HeroBackdropState.lastDisplayedUrl = homeBackdrop
    }

    /** Called as Home is shown: make sure it starts from its own backdrop. */
    fun restoreForHome() {
        if (!inside) return
        inside = false
        com.nuvio.tv.ui.screens.home.HeroBackdropState.lastDisplayedUrl = homeBackdrop
    }
}

/** "Dune (2021)" or "Dune 2021" -> ("Dune", 2021); a name without a year -> (name, null). */
internal fun splitFixYear(text: String): Pair<String, Int?> {
    val t = text.trim()
    val m = FIX_YEAR.find(t) ?: return t to null
    val name = t.substring(0, m.range.first).trim()
    if (name.isEmpty()) return t to null
    return name to m.groupValues[1].toIntOrNull()
}

private val FIX_YEAR = Regex("\\s*\\(?((?:19|20)\\d{2})\\)?\\s*$")
