package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.Meta

enum class PersonalRatingProvider(val label: String) { TRAKT("Trakt"), SIMKL("Simkl"), MDBLIST("MDBList") }

/** Title ratings use the native title identity, independently of the episode display order. */
data class PersonalRatingTarget(val movie: Boolean, val ids: TrackingExternalIds, val anime: Boolean = false) {
    val group: String get() = if (movie) "movies" else "shows"
    fun groupFor(provider: PersonalRatingProvider) = if (anime && provider == PersonalRatingProvider.SIMKL) "anime" else group
    fun supports(provider: PersonalRatingProvider) = when (provider) {
        PersonalRatingProvider.TRAKT -> ids.imdb != null || ids.tmdb != null || ids.trakt != null
        PersonalRatingProvider.SIMKL -> ids.imdb != null || ids.tmdb != null || ids.simkl != null || ids.mal != null
        PersonalRatingProvider.MDBLIST -> ids.imdb != null || ids.tmdb != null
    }
}

fun personalRatingTarget(meta: Meta): PersonalRatingTarget? {
    return personalRatingTarget(meta.id, meta.apiType, meta.imdbId)
}

fun personalRatingTarget(contentId: String, contentType: String, imdbId: String? = null): PersonalRatingTarget? {
    val type = contentType.lowercase()
    if (type !in setOf("movie", "series", "tv", "anime")) return null
    val parsed = parseTrackingExternalIds(contentId)
    val imdb = imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }
    val nativeImdb = parsed.imdb?.takeIf { it.matches(Regex("tt[0-9]+")) }
    if (imdb != null && nativeImdb != null && imdb != nativeImdb) return null
    val ids = TrackingExternalIds(imdb = imdb ?: nativeImdb,
        tmdb = parsed.tmdb?.takeIf { it > 0 }, trakt = parsed.trakt?.takeIf { it > 0 },
        simkl = parsed.simkl?.takeIf { it > 0 }, mal = parsed.mal?.takeIf { it > 0 })
    return PersonalRatingTarget(type == "movie", ids, type == "anime").takeIf { target -> PersonalRatingProvider.entries.any(target::supports) }
}

/** Ephemeral UI request; it never records a watch or changes playback progress. */
data class MovieRatingRequest(val profileId: Int, val contentId: String, val title: String)

internal fun shouldRequestMovieRating(enabled: Boolean, type: String?, ended: Boolean, hasError: Boolean): Boolean =
    enabled && type == "movie" && ended && !hasError
