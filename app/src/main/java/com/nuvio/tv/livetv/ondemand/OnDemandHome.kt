package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

/**
 * The On Demand page, Nuvio-home style: one row per provider group (in your order, with your
 * names, hidden ones left out), shown with whatever home layout Nuvio is set to.
 */
data class VodHomeState(
    val rows: List<CatalogRow> = emptyList(),
    /** Every group, for Manage VOD Groups (including hidden ones). */
    val groups: List<VodGroupEntry> = emptyList(),
    val loading: Boolean = true
)

/** One group as Manage VOD Groups shows it. */
data class VodGroupEntry(
    val uid: String,
    val defaultName: String,
    val name: String,
    val visible: Boolean,
    val kind: VodKind,
    /** The sort picked for this group (VodSort name), or null to follow the default sort. */
    val sort: String? = null
)

/** Ids and markers that tie Nuvio's rows and cards back to On Demand. */
object VodHomeIds {
    const val ADDON_ID = "nuvio-iptv-on-demand"
    private const val PREFIX = "nuviovod:"

    fun metaId(uid: String) = PREFIX + uid
    fun uidOf(metaId: String): String? = metaId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    /** Provider artwork and labels, independent of addon metadata and old enrichment caches. */
    fun toMeta(item: VodItem): MetaPreview = MetaPreview(
        id = metaId(item.uid),
        type = if (item.kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE,
        name = OnDemandDatabase.displayTitle(item.name),
        poster = item.icon,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = item.year?.toString(),
        imdbRating = item.rating?.toFloatOrNull(),
        genres = item.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
    )

    fun row(uid: String, name: String, kind: VodKind, items: List<MetaPreview>, hasMore: Boolean, page: Int) = CatalogRow(
        addonId = ADDON_ID,
        addonName = "On Demand",
        addonBaseUrl = ADDON_ID,
        catalogId = uid,
        catalogName = name,
        type = if (kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE,
        items = items,
        isLoading = false,
        hasMore = hasMore,
        currentPage = page,
        // Nuvio's Modern layout only asks a row for more titles when its catalog says it can
        // page; without this, Modern stopped every On Demand row at its first 30 titles.
        supportsSkip = true
    )
}


/** The last layout settings Home had (used when Home isn't in the navigation history). */
object OnDemandHomeSettings {
    @Volatile var last: com.nuvio.tv.ui.screens.home.HomeUiState? = null
}


/** Home's state without its content (rows, Continue watching, hero): just its settings. */
fun com.nuvio.tv.ui.screens.home.HomeUiState.settingsOnly(): com.nuvio.tv.ui.screens.home.HomeUiState = copy(
    catalogRows = emptyList(),
    homeRows = emptyList(),
    gridItems = emptyList(),
    continueWatchingItems = emptyList(),
    upcomingItems = emptyList(),
    heroItems = emptyList(),
    modernHomePresentation = com.nuvio.tv.ui.screens.home.ModernHomePresentationState()
)
