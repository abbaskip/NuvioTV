package com.nuvio.tv.livetv.ondemand

import java.text.Normalizer

internal data class VodStreamTarget(
    val kind: VodKind,
    val tmdbId: String?,
    val title: String,
    val year: Int?,
    val episodeTitles: Set<String> = emptySet(),
    val tvdbEpisodeIds: Set<Long> = emptySet(),
    val selectedTvdbEpisodeId: Long? = null,
    val imdbEpisodeId: String? = null,
    val imdbId: String? = null
)

/** Coordinates describe a provider's catalogue, not the identity of its episode. */
internal object VodMatchPolicy {
    fun titleMatches(target: VodStreamTarget, item: VodItem): Boolean {
        if (target.kind != item.kind) return false
        if (target.tmdbId != null && item.tmdbId != null) return target.tmdbId == item.tmdbId
        return target.year != null && item.year == target.year &&
            titleKey(target.title).isNotBlank() && titleKey(target.title) == titleKey(item.name)
    }

    fun parentMatches(target: VodStreamTarget, item: VodItem, detail: VodInfo?): Boolean {
        if (target.kind != item.kind) return false
        if (item.tmdbId != null && detail?.tmdbId != null && item.tmdbId != detail.tmdbId) return false
        val verified = item.copy(tmdbId = detail?.tmdbId ?: item.tmdbId,
            year = item.year ?: detail?.releaseDate?.take(4)?.toIntOrNull())
        if (target.tmdbId != null && verified.tmdbId != null && target.tmdbId != verified.tmdbId) return false
        if (target.imdbId != null && detail?.imdbId != null && target.imdbId != detail.imdbId) return false
        return titleMatches(target, verified) || target.imdbId != null && target.imdbId == detail?.imdbId
    }

    fun episodes(target: VodStreamTarget, candidates: List<VodEpisode>, seriesTitle: String): List<VodEpisode> {
        val identified = candidates.filter { episode ->
            (target.imdbEpisodeId != null && episode.imdbId == target.imdbEpisodeId) ||
                (episode.tvdbId != null && (episode.tvdbId == target.selectedTvdbEpisodeId ||
                    target.tvdbEpisodeIds.size == 1 && episode.tvdbId in target.tvdbEpisodeIds))
        }
        if (identified.isNotEmpty()) return identified.filterNot { contradicts(target, it) }
        val titles = target.episodeTitles.map(::episodeKey).filter { distinctive(it) }.toSet()
        if (titles.isEmpty()) return emptyList()
        val matches = candidates.filter { candidate ->
            !contradicts(target, candidate) && episodeKey(candidate.title, seriesTitle) in titles
        }
        // Same title in distinct episode positions is ambiguous; leave those in the browser.
        return matches.takeIf { it.map { e -> e.season to e.episode }.distinct().size == 1 }.orEmpty()
    }

    private fun contradicts(target: VodStreamTarget, episode: VodEpisode): Boolean =
        target.imdbEpisodeId != null && episode.imdbId != null && target.imdbEpisodeId != episode.imdbId ||
            episode.tvdbId != null && target.tvdbEpisodeIds.isNotEmpty() &&
                episode.tvdbId != target.selectedTvdbEpisodeId &&
                (target.tvdbEpisodeIds.size != 1 || episode.tvdbId !in target.tvdbEpisodeIds)

    private val coordinates = Regex("""(?i)\b(?:S\d{1,3}\s*E\d{1,4}|\d{1,3}x\d{1,4}|season\s*\d+\s*episode\s*\d+)\b""")
    private val edition = Regex("""(?i)\s*[\[(](?:extended(?: cut| edition)?|uncut|director's cut)[\])]\s*""")
    private val part = Regex("""(?i)\((\d+)\)|\bpart\s*(\d+)\b""")
    private val generic = Regex("""(?:episode|ep|part|chapter|episodio|folge|capitulo|odcinek)?\d*""")
    private val placeholders = setOf("noepisodetitle", "episodetitle", "notitle", "untitled", "unknown", "tba", "tbd", "tobeannounced", "specialepisode")
    private fun distinctive(key: String) = key.length >= 3 && key !in placeholders && !generic.matches(key)
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase().replace(Regex("""\p{M}"""), "").replace('&', ' ').replace(Regex("""[^\p{L}\p{N}]+"""), "")
    private fun titleKey(value: String) = normalized(OnDemandDatabase.displayTitle(value))
    internal fun episodeKey(value: String, series: String = ""): String {
        var title = value.replace(coordinates, " ").replace(edition, " ")
        title = title.replace(part) { " part" + (it.groups[1]?.value ?: it.groups[2]?.value) + " " }
        val key = normalized(title)
        val prefix = titleKey(series)
        val seriesWords = OnDemandDatabase.displayTitle(series).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }
        val markedPrefix = seriesWords.isNotEmpty() && Regex("(?iu)^\\s*" + seriesWords.joinToString("[\\s._-]+") { Regex.escape(it) } + "\\s*[-:|.]").containsMatchIn(value)
        val withoutSeries = if (prefix.isNotEmpty() && key.startsWith(prefix) && key.length > prefix.length &&
            (coordinates.containsMatchIn(value) || markedPrefix)) key.removePrefix(prefix) else key
        return withoutSeries.replace(Regex("""^(?:episode|ep)\d+(?=[a-z])"""), "")
    }
}
