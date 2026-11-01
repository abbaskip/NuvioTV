package com.nuvio.tv.livetv.ondemand

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The left-hand list: "All", "Recently added", then the providers' categories. */
sealed interface OnDemandSection {
    val key: String
    data object All : OnDemandSection { override val key = "__all__" }
    data object Recent : OnDemandSection { override val key = "__recent__" }
    data class Category(val category: VodCategory, val playlistName: String?) : OnDemandSection {
        override val key: String get() = category.uid
    }
    /** Browsing by genre (from the provider's genre tags). */
    data class Genre(val name: String, val count: Int) : OnDemandSection {
        override val key: String get() = "genre:$name"
    }
}

data class OnDemandUiState(
    val kind: VodKind = VodKind.MOVIE,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val sections: List<OnDemandSection> = listOf(OnDemandSection.All, OnDemandSection.Recent),
    val selectedKey: String = OnDemandSection.All.key,
    val items: List<VodItem> = emptyList(),
    val loading: Boolean = true,
    val endReached: Boolean = false,
    val query: String = "",
    val sort: VodSort = VodSort.DEFAULT,
    /** Left list shows genres instead of the provider's categories. */
    val byGenre: Boolean = false,
    /** Whether this kind has genre information at all (shows the Genres switch). */
    val hasGenres: Boolean = false
)

/** Where selecting a title leads. */
sealed interface OnDemandTarget {
    /** Found in one of your addon catalogs: open it on Nuvio's details page. */
    data class Details(val itemId: String, val itemType: String, val addonBaseUrl: String?, val uid: String) : OnDemandTarget
    /** Not in your catalogs: show the provider's own details. */
    data class Provider(val item: VodItem) : OnDemandTarget
}

@HiltViewModel
class OnDemandViewModel @Inject constructor(
    private val repository: OnDemandRepository,
    private val prefs: LiveTvPreferences,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder
) : ViewModel() {

    /** Catalogue presentation uses only the provider's metadata. */
    fun nameFor(item: VodItem): String = OnDemandDatabase.displayTitle(item.name)

    /** Every version of a title (qualities, categories, providers), best quality first. */
    suspend fun versions(item: VodItem) = repository.versions(item)

    private val _ui = MutableStateFlow(OnDemandUiState(sort = lastSort))
    val ui: StateFlow<OnDemandUiState> = _ui.asStateFlow()

    val settings: StateFlow<LiveTvSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())
    val user: StateFlow<LiveUserState> =
        prefs.userState.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())
    val status = repository.status
    private val playlistNames: StateFlow<Map<String, String>> = prefs.playlists
        .map { l -> l.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The playlist of the title last highlighted on the On Demand page. */
    private val focusedPlaylist = MutableStateFlow<String?>(null)

    /** The grid layout's header: "On Demand - <playlist>", for the title you're on. */
    val homeHeader: StateFlow<String> = kotlinx.coroutines.flow.combine(focusedPlaylist, playlistNames) { id, names ->
        id?.let { names[it] }?.takeIf { it.isNotBlank() }?.let { "On Demand - $it" } ?: "On Demand"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "On Demand")

    private var loadJob: Job? = null
    /** Hidden and locked categories as of the last reload (the settings flow can lag behind). */
    @Volatile private var excludedNow: Set<String> = emptySet()

    init {
        repository.start()
        viewModelScope.launch { repository.version.collect { reload() } }
        // New genres from the background fill: refresh the lists, keep your place.
        viewModelScope.launch { repository.genreUpdates.drop(1).collect { refreshGenres() } }
        // Only reload when hidden or locked categories actually change (not on every saved setting).
        viewModelScope.launch {
            prefs.userState.map { it.vodHiddenCategories to it.lockedGroups }
                .distinctUntilChanged()
                .drop(1)
                .collect { reload(keepSelection = true) }
        }
        viewModelScope.launch { ParentalControls.unlocked.drop(1).collect { reload(keepSelection = true) } }
    }

    fun selectKind(kind: VodKind) {
        if (kind == _ui.value.kind) return
        _ui.value = _ui.value.copy(kind = kind, selectedKey = OnDemandSection.All.key, query = "")
        reload()
    }

    fun select(section: OnDemandSection) {
        _ui.value = _ui.value.copy(selectedKey = section.key, query = "")
        loadItems(reset = true)
    }

    /**
     * Nuvio's search (the On Demand row): your provider's movies and series whose names match,
     * movies first. Hidden and locked groups are left out, as everywhere else.
     */
    suspend fun searchEverywhere(text: String, limit: Int = 40): List<VodItem> {
        val q = text.trim()
        if (q.length < 2) return emptyList()
        val user = prefs.userState.first()
        val s = prefs.settings.first()
        val all = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
        val hidden = user.vodHiddenCategories + all.filter { c ->
            ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
        }.map { it.uid }
        val merge = s.vodMergeDuplicates
        // Fair between logins: one from each in turn, so the login imported first can't fill
        // every place when two catalogs have similar titles.
        val movies = interleaveByPlaylist(repository.search(VodKind.MOVIE, q, hidden, merge = merge))
        val series = interleaveByPlaylist(repository.search(VodKind.SERIES, q, hidden, merge = merge))
        // Some of each, so a long list of movies doesn't hide the series.
        val half = limit / 2
        val m = movies.take(maxOf(half, limit - series.size))
        val sr = series.take(limit - m.size)
        return m + sr
    }

    fun search(text: String) {
        _ui.value = _ui.value.copy(query = text.trim())
        loadItems(reset = true)
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.endReached || s.query.isNotBlank()) return
        loadItems(reset = false)
    }

    fun refreshNow() = repository.refreshNow()

    /** New genres arrived: update the genre list (and counts) without reloading the posters. */
    private fun refreshGenres() {
        viewModelScope.launch {
            val genres = repository.genres(_ui.value.kind)
            val cur = _ui.value
            if (!cur.byGenre) {
                _ui.value = cur.copy(hasGenres = genres.isNotEmpty())
                return@launch
            }
            val head = cur.sections.filter { it !is OnDemandSection.Genre }
            _ui.value = cur.copy(
                hasGenres = genres.isNotEmpty(),
                sections = head + genres.map { (g, n) -> OnDemandSection.Genre(g, n) }
            )
        }
    }

    fun toggleGenres() {
        _ui.value = _ui.value.copy(byGenre = !_ui.value.byGenre, selectedKey = OnDemandSection.All.key)
        reload()
    }

    fun setSort(sort: VodSort) {
        if (sort == _ui.value.sort) return
        lastSort = sort
        _ui.value = _ui.value.copy(sort = sort)
        loadItems(reset = true)
    }

    // ------------------------------------------------------------------ locks and hiding

    /** Categories that need the PIN right now. */
    fun isLocked(section: OnDemandSection): Boolean {
        val c = (section as? OnDemandSection.Category)?.category ?: return false
        return ParentalControls.isLocked("vod:${c.uid}", c.name, settings.value, user.value.lockedGroups)
    }

    fun unlock(section: OnDemandSection, pin: String): Boolean {
        val c = (section as? OnDemandSection.Category)?.category ?: return true
        return ParentalControls.unlock("vod:${c.uid}", pin, settings.value)
    }

    fun setLocked(section: OnDemandSection, locked: Boolean) {
        val c = (section as? OnDemandSection.Category)?.category ?: return
        viewModelScope.launch { prefs.setGroupLocked("vod:${c.uid}", locked) }
    }

    /** Every category of this kind, hidden ones included (Manage visibility). */
    suspend fun allCategories(): List<OnDemandSection.Category> {
        val names = playlistNames.value
        val multi = repository.categories(_ui.value.kind).map { it.playlistId }.distinct().size > 1
        return repository.categories(_ui.value.kind).map { OnDemandSection.Category(it, if (multi) names[it.playlistId] else null) }
    }

    fun saveVisibility(all: List<OnDemandSection.Category>, hidden: Set<String>) {
        val ids = all.map { it.key }.toSet()
        viewModelScope.launch { prefs.updateVodHiddenCategories(show = ids - hidden, hide = hidden intersect ids) }
    }

    // ------------------------------------------------------------------ opening and posters

    fun posterFor(item: VodItem): String? = item.icon

    fun targetFor(item: VodItem): OnDemandTarget = OnDemandTarget.Provider(item)

    suspend fun info(item: VodItem): VodInfo? = repository.info(item)
    suspend fun movieUrl(item: VodItem): String? = repository.movieUrl(item)

    // ------------------------------------------------------------------ loading

    private fun reload(keepSelection: Boolean = false) {
        viewModelScope.launch {
            val kind = _ui.value.kind
            val user = prefs.userState.first()
            val names = playlistNames.value
            val all = repository.categories(kind)
            val multi = all.map { it.playlistId }.distinct().size > 1
            val visible = all.filter { it.uid !in user.vodHiddenCategories }
                .map { OnDemandSection.Category(it, if (multi) names[it.playlistId] else null) }
            val genres = repository.genres(kind)
            val byGenre = _ui.value.byGenre && genres.isNotEmpty()
            val sections = listOf(OnDemandSection.All, OnDemandSection.Recent) +
                if (byGenre) genres.map { (g, n) -> OnDemandSection.Genre(g, n) } else visible
            val s = prefs.settings.first()
            excludedNow = user.vodHiddenCategories + all.filter { c ->
                ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
            }.map { it.uid }
            val selected = _ui.value.selectedKey.takeIf { k -> keepSelection && sections.any { it.key == k } }
                ?: OnDemandSection.All.key
            _ui.value = _ui.value.copy(
                sections = sections,
                selectedKey = selected,
                byGenre = byGenre,
                hasGenres = genres.isNotEmpty(),
                movieCount = runCatching { repository.categories(VodKind.MOVIE).sumOf { it.count } }.getOrDefault(0),
                seriesCount = runCatching { repository.categories(VodKind.SERIES).sumOf { it.count } }.getOrDefault(0)
            )
            loadItems(reset = true)
        }
    }

    /** Hidden categories, plus locked ones (kept out of All, Recently added and search). */
    private fun excluded(): Set<String> = excludedNow

    // ================================================================ the Nuvio-style page

    private val _home = MutableStateFlow(VodHomeState())
    /** Rows (your provider's groups) for the On Demand page in Nuvio's home layouts. */
    val home: StateFlow<VodHomeState> = _home

    private val homeItems = java.util.concurrent.ConcurrentHashMap<String, List<VodItem>>()
    private val homeEnded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var homeGroups: List<Pair<VodCategory, String>> = emptyList()

    /** Builds the page: every group in your order, hidden and locked ones left out. */
    fun loadHome() {
        viewModelScope.launch {
            val user = prefs.userState.first()
            val cats = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
            val orderIndex = user.vodGroupOrder.withIndex().associate { (i, uid) -> uid to i }
            val ordered = cats.withIndex()
                .sortedWith(compareBy({ orderIndex[it.value.uid] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
            val entries = ordered.map { c ->
                VodGroupEntry(
                    uid = c.uid,
                    defaultName = c.name,
                    name = user.vodGroupNames[c.uid]?.takeIf { it.isNotBlank() } ?: c.name,
                    visible = c.uid !in user.vodHiddenCategories,
                    kind = c.kind,
                    sort = user.vodSorts[c.uid]
                )
            }
            val visible = ordered.filter { c ->
                c.uid !in user.vodHiddenCategories &&
                    !ParentalControls.isLocked("vod:${c.uid}", c.name, settings.value, user.lockedGroups)
            }
            homeGroups = visible.map { it to (user.vodGroupNames[it.uid]?.takeIf { n -> n.isNotBlank() } ?: it.name) }
            // Before anything is highlighted, the header names the first group's playlist.
            if (focusedPlaylist.value == null) focusedPlaylist.value = homeGroups.firstOrNull()?.first?.playlistId
            homeItems.clear(); homeEnded.clear(); homeRead.clear()
            homeSorts = user.vodSorts
            // The first page of every group (each a quick read from the device's catalog).
            for ((c, _) in homeGroups) {
                val (sort, seed) = sortOf(c.uid)
                val raw = repository.items(c.kind, c, emptySet(), false, HOME_PAGE, 0, sort, merge = settings.value.vodMergeDuplicates, seed = seed)
                val page = dedupe(emptyList(), raw)
                homeItems[c.uid] = page
                homeRead[c.uid] = raw.size
                if (raw.size < HOME_PAGE) homeEnded += c.uid
            }
            _home.value = VodHomeState(rows = buildHomeRows(), groups = entries, loading = false)
        }
    }

    /** More titles for one group, as you scroll along it. */
    fun loadMoreHome(uid: String) {
        if (uid in homeEnded) return
        val (c, _) = homeGroups.firstOrNull { it.first.uid == uid } ?: return
        // One page at a time per group (scrolling asks again and again near the end).
        if (!homeLoadingMore.add(uid)) return
        viewModelScope.launch {
          try {
            val have = homeItems[uid].orEmpty()
            val (sort, seed) = sortOf(uid)
            // Read on from where the catalog left off (not from how many are shown: titles
            // dropped as duplicates would otherwise be read again, page after page).
            var offset = homeRead[uid] ?: have.size
            // A page where every title was already shown (copies of earlier ones) added nothing,
            // and the row never asked for more again: it stopped there (30 titles out of
            // hundreds). Keep reading until there's something new, or the group has ended.
            var more: List<VodItem>
            var pages = 0
            while (true) {
                val raw = repository.items(c.kind, c, emptySet(), false, HOME_PAGE, offset, sort, merge = settings.value.vodMergeDuplicates, seed = seed)
                offset += raw.size
                if (raw.size < HOME_PAGE) homeEnded += uid
                more = dedupe(have, raw)
                if (more.isNotEmpty() || uid in homeEnded || ++pages >= 40) break
            }
            homeRead[uid] = offset
            homeItems[uid] = have + more
            updateHomeRows()
          } finally { homeLoadingMore.remove(uid) }
        }
    }

    private val homeLoadingMore = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** How many catalog rows each group has read so far (its next page starts there). */
    private val homeRead = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ------------------------------------------------------------------ sorting

    /** The sort picked for each group (saved), as last loaded. */
    @Volatile private var homeSorts: Map<String, String> = emptyMap()

    /**
     * "Merge Duplicates": [more] without titles already in [have] (or earlier in [more]). The
     * catalog merges by TMDB id, or by name and year when there's none, so a copy with a TMDB id
     * and one without stayed apart; sorting A–Z put them side by side. Same name and year, or
     * same TMDB id, counts as the same title here. Play still offers every copy.
     */
    private fun dedupe(have: List<VodItem>, more: List<VodItem>): List<VodItem> {
        if (!settings.value.vodMergeDuplicates) return more
        val tmdbSeen = HashSet<String>()
        // Name -> the years seen with it (0 = no year). A copy without a year matches any year,
        // so "Movie" and "Movie (2019)" are one title.
        val namesSeen = HashMap<String, MutableSet<Int>>()
        fun isDup(i: VodItem): Boolean {
            val t = i.tmdbId?.takeIf { it.isNotBlank() }
            if (t != null && t in tmdbSeen) return true
            val n = OnDemandDatabase.normalize(i.name)
            if (n.isEmpty()) return false
            val years = namesSeen[n] ?: return false
            val y = i.year ?: 0
            return y == 0 || 0 in years || y in years
        }
        fun note(i: VodItem) {
            i.tmdbId?.takeIf { it.isNotBlank() }?.let { tmdbSeen += it }
            val n = OnDemandDatabase.normalize(i.name)
            if (n.isNotEmpty()) namesSeen.getOrPut(n) { HashSet() } += (i.year ?: 0)
        }
        have.forEach { note(it) }
        return more.filter { i -> if (isDup(i)) false else { note(i); true } }
    }

    /** A group's sort: the one picked for it, else Default Sort Order from Settings. */
    private fun sortOf(uid: String): Pair<VodSort, Long> =
        VodSort.parse(homeSorts[uid]) ?: VodSort.parse(settings.value.vodDefaultSort) ?: (VodSort.DEFAULT to 1L)

    /** What a group is sorted by right now (for the Sort button). */
    fun currentSort(uid: String): VodSort = sortOf(uid).first

    /** The default sort's name, for "Default (…)" in the sort list. */
    fun defaultSortLabel(): String = (VodSort.parse(settings.value.vodDefaultSort)?.first ?: VodSort.DEFAULT).label

    /**
     * Sort picked for one group (null = back to the default). Saved for this profile, and the
     * group's row (and its See all) loads again from the top in the new order.
     */
    fun setGroupSort(uid: String, sort: VodSort?) {
        val saved = sort?.let { VodSort.encode(it) }
        homeSorts = if (saved == null) homeSorts - uid else homeSorts + (uid to saved)
        _home.value = _home.value.copy(groups = _home.value.groups.map { if (it.uid == uid) it.copy(sort = saved) else it })
        viewModelScope.launch {
            prefs.setVodSort(uid, saved)
            val (c, _) = homeGroups.firstOrNull { it.first.uid == uid } ?: return@launch
            val (s, seed) = sortOf(uid)
            val raw = repository.items(c.kind, c, emptySet(), false, HOME_PAGE, 0, s, merge = settings.value.vodMergeDuplicates, seed = seed)
            val page = dedupe(emptyList(), raw)
            homeItems[uid] = page
            homeRead[uid] = raw.size
            if (raw.size < HOME_PAGE) homeEnded += uid else homeEnded -= uid
            updateHomeRows()
        }
    }
    /** The rows rebuilt off the main thread, then shown. */
    private suspend fun updateHomeRows() {
        val rows = buildHomeRows()
        _home.value = _home.value.copy(rows = rows)
    }

    /**
     * The page's rows, worked out off the main thread: cleaning up thousands of provider titles
     * (tags like "EN -", "4K") on the main thread froze slower devices (Fire TV Stick 4K) long
     * enough for Android to close the app.
     */
    private suspend fun buildHomeRows(): List<com.nuvio.tv.domain.model.CatalogRow> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { buildHomeRowsNow() }

    private fun buildHomeRowsNow(): List<com.nuvio.tv.domain.model.CatalogRow> = homeGroups.mapNotNull { (c, name) ->
        val items = homeItems[c.uid].orEmpty()
        if (items.isEmpty()) return@mapNotNull null
        VodHomeIds.row(
            uid = c.uid,
            name = name,
            kind = c.kind,
            items = items.map { item ->
                VodHomeIds.toMeta(item)
            },
            hasMore = c.uid !in homeEnded,
            page = (items.size + HOME_PAGE - 1) / HOME_PAGE
        )
    }

    fun focusHome(metaId: String) {
        val uid = VodHomeIds.uidOf(metaId) ?: return
        uid.split(':').getOrNull(1)?.let { focusedPlaylist.value = it }
    }

    // ------------------------------------------------------------ watched marks and the options menu

    /** Nuvio's watched movies and fully watched series, for the watched mark on cards (as on Home). */
    val watched: StateFlow<Pair<Set<String>, Set<String>>> = kotlinx.coroutines.flow.combine(
        watchProgressRepository.observeWatchedMovieIds(),
        watchedSeriesStateHolder.fullyWatchedSeriesIds
    ) { movies, series -> movies to series }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet<String>() to emptySet())

    /** Read watched state by the provider's existing identity without resolving every card. */
    fun isWatched(card: com.nuvio.tv.domain.model.MetaPreview, sets: Pair<Set<String>, Set<String>>): Boolean {
        val item = homeItem(card.id) ?: return false
        val id = item.tmdbId?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        val ids = if (item.kind == VodKind.SERIES) sets.second else sets.first
        return "tmdb:$id" in ids
    }

    /** Only explicit context-menu actions enter Nuvio, using a provider-supplied content ID. */
    fun optionsTarget(metaId: String): Pair<com.nuvio.tv.domain.model.MetaPreview, String?>? {
        val item = homeItem(metaId) ?: return null
        val id = item.tmdbId?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return VodHomeIds.toMeta(item).copy(id = "tmdb:$id") to null
    }

    /** The title behind a card (for opening it). */
    fun homeItem(metaId: String): VodItem? {
        val uid = VodHomeIds.uidOf(metaId) ?: return null
        return homeItems.values.asSequence().flatten().firstOrNull { it.uid == uid }
    }

    /** Manage VOD Groups: save which groups show, their order and names, then rebuild. */
    fun saveGroups(entries: List<VodGroupEntry>) {
        viewModelScope.launch {
            prefs.saveVodGroups(
                hidden = entries.filter { !it.visible }.map { it.uid }.toSet(),
                order = entries.map { it.uid },
                names = entries.filter { it.name.isNotBlank() && it.name != it.defaultName }.associate { it.uid to it.name }
            )
            prefs.setVodSorts(entries.mapNotNull { e -> e.sort?.let { e.uid to it } }.toMap())
            loadHome()
        }
    }

    /** Manage VOD Groups → Reset to Default: every group shown, provider order and names. */
    fun resetGroups() {
        viewModelScope.launch {
            prefs.saveVodGroups(hidden = emptySet(), order = emptyList(), names = emptyMap())
            prefs.setVodSorts(emptyMap())
            loadHome()
        }
    }

    private fun loadItems(reset: Boolean) {
        loadJob?.cancel()
        val state = _ui.value
        val offset = if (reset) 0 else state.items.size
        _ui.value = state.copy(loading = true, items = if (reset) emptyList() else state.items, endReached = if (reset) false else state.endReached)
        loadJob = viewModelScope.launch {
            val section = state.sections.firstOrNull { it.key == state.selectedKey } ?: OnDemandSection.All
            val merge = settings.value.vodMergeDuplicates
            val page = when {
                state.query.isNotBlank() -> repository.search(state.kind, state.query, excluded(), state.sort, merge = merge)
                section is OnDemandSection.Category -> repository.items(state.kind, section.category, emptySet(), false, PAGE, offset, state.sort, merge = merge)
                section is OnDemandSection.Genre -> repository.items(state.kind, null, excluded(), false, PAGE, offset, state.sort, genre = section.name, merge = merge)
                else -> repository.items(state.kind, null, excluded(), section == OnDemandSection.Recent, PAGE, offset, state.sort, merge = merge)
            }
            val cur = _ui.value
            _ui.value = cur.copy(
                items = if (reset) page else cur.items + page,
                loading = false,
                endReached = state.query.isNotBlank() || page.size < PAGE || (section == OnDemandSection.Recent && offset + page.size >= 200)
            )
        }
    }

    private companion object {
        const val HOME_PAGE = 30
        /** The sort you picked, kept while the app is open. */
        @Volatile var lastSort: VodSort = VodSort.DEFAULT
        const val PAGE = 120
    }
}

/** Takes one result from each login in turn (their own order kept), so no single login crowds out the rest. */
internal fun interleaveByPlaylist(items: List<VodItem>): List<VodItem> {
    if (items.size < 2) return items
    val groups = items.groupBy { it.playlistId }.values.map { it.iterator() }
    if (groups.size < 2) return items
    val out = ArrayList<VodItem>(items.size)
    while (out.size < items.size) for (g in groups) if (g.hasNext()) out += g.next()
    return out
}
